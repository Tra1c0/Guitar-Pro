package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.adapter.audio.PendingAudioServices
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutosaveTest {
    private var n = 0
    private val ids = IdSource { EntityId("vm-${n++}") }
    private class Repository(var stored: Project) : ProjectRepository {
        var fail = false
        var writes = 0
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> {
            if (fail) return Outcome.Failure(FailureCode.IO, "disk full")
            if (expectedRevision != stored.revision) return Outcome.Failure(FailureCode.CONFLICT, "stale writer")
            stored = project; writes++
            return Outcome.Success(Unit)
        }
    }
    private class Snapshots : SnapshotRepository {
        val pending = CompletableDeferred<Outcome<Project>>()
        override suspend fun list(projectId: EntityId) = Outcome.Success(emptyList<SnapshotInfo>())
        override suspend fun create(project: Project, name: String) = Outcome.Success(SnapshotInfo("snap", project.id, project.revision, name, 0))
        override suspend fun load(projectId: EntityId, snapshotId: String) = pending.await()
    }
    private val settings = object : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override suspend fun load() = Outcome.Success(Unit)
        override suspend fun update(settings: AppSettings): Outcome<Unit> { this.settings.value = settings; return Outcome.Success(Unit) }
    }
    private val exchange = object : NativeProjectExchange {
        override suspend fun export(project: Project): Outcome<ExportResult> = unavailable("C-NATIVE")
        override suspend fun import(bytes: ByteArray, newProjectId: EntityId): Outcome<Project> = unavailable("C-NATIVE")
    }
    private suspend fun TestScope.harness(block: suspend TestScope.(WorkspaceViewModel, Repository, Snapshots) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repository(ProjectFactory(ids).demo("Autosave")); val snapshots = Snapshots()
        // Layout work runs on the injected background dispatcher; the test scheduler keeps it deterministic.
        val vm = WorkspaceViewModel(repo.stored.id, repo, snapshots, exchange, settings, PendingAudioServices(), ids, background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); block(vm, repo, snapshots) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun rapidTransactionsCoalesceWithoutLosingTheLatestMusic() = runTest { harness { vm, repo, _ ->
        repeat(12) { index -> vm.select(vm.state.value.editor!!.selection.copy(offset = Fraction.of(index.toLong(), 16))); vm.command(EditorCommand.PutNote(fret = index)) }
        advanceUntilIdle()
        assertEquals(12L, repo.stored.revision)
        val notes = repo.stored.tracks.first().staves.first().bars.first().voices.first().beats.flatMap { it.notes }
        assertEquals((0..11).toList(), notes.map { it.fret })
        assertEquals(SaveStatus.SAVED, vm.state.value.saveStatus)
    } }
    @Test fun failedAutosaveRetainsDirtyMusicAndCanRetry() = runTest { harness { vm, repo, _ ->
        repo.fail = true; vm.command(EditorCommand.PutNote(fret = 7)); advanceUntilIdle()
        assertEquals(SaveStatus.FAILED, vm.state.value.saveStatus); assertEquals(0L, repo.stored.revision)
        assertEquals(1L, vm.state.value.editor!!.project.revision)
        assertTrue(vm.flush() is Outcome.Failure)
        repo.fail = false; vm.retrySave(); advanceUntilIdle()
        assertEquals(1L, repo.stored.revision); assertEquals(SaveStatus.SAVED, vm.state.value.saveStatus)
    } }
    @Test fun editsAfterAFailedSaveUseTheLastPersistedRevision() = runTest { harness { vm, repo, _ ->
        repo.fail = true; vm.command(EditorCommand.Rename("First")); advanceUntilIdle()
        repo.fail = false; vm.command(EditorCommand.Rename("Second")); advanceUntilIdle()
        assertEquals("Second", repo.stored.metadata.title); assertEquals(2L, repo.stored.revision)
    } }
    @Test fun snapshotLoadingCannotOverwriteAnInterveningEdit() = runTest { harness { vm, repo, snapshots ->
        val original = repo.stored
        vm.restoreSnapshot(SnapshotInfo("snap", original.id, 0, "old", 0)); runCurrent()
        vm.command(EditorCommand.Rename("Do not lose me"))
        snapshots.pending.complete(Outcome.Success(original)); advanceUntilIdle()
        assertEquals("Do not lose me", vm.state.value.editor!!.project.metadata.title)
        assertTrue(vm.state.value.notice!!.contains("geändert"))
    } }
    @Test fun twoDigitInputIsAPreviewUntilExplicitCommit() = runTest { harness { vm, repo, _ ->
        vm.digit(1); vm.digit(2); advanceUntilIdle()
        assertEquals(0L, repo.stored.revision); assertEquals("12", vm.input.value.buffer)
        vm.commitInput(); advanceUntilIdle()
        assertEquals(12, repo.stored.tracks.first().staves.first().bars.first().voices.first().beats.single().notes.single().fret)
        assertEquals(Fraction.of(1, 16), vm.state.value.editor!!.selection.offset)
        vm.undo(); advanceUntilIdle()
        assertTrue(repo.stored.tracks.first().staves.first().bars.first().voices.first().beats.isEmpty())
    } }
    @Test fun cellTapsInRangeModeActOnTheTappedCell() = runTest { harness { vm, repo, _ ->
        val p = vm.state.value.editor!!.project
        vm.setRangeMode(true)
        vm.selectCell(vm.state.value.editor!!.selection.copy(measureId = p.measures[1].id, offset = Fraction.of(1, 4)))
        vm.putNoteAt(2, 5); advanceUntilIdle()
        val beat = repo.stored.tracks.first().staves.first().bars[1].voices.first().beats.single()
        assertEquals(Fraction.of(1, 4), beat.offset); assertEquals(2, beat.notes.single().string); assertEquals(5, beat.notes.single().fret)
    } }
    @Test fun aChordThatDoesNotFitIsReportedAndNotRepeatable() = runTest { harness { vm, repo, _ ->
        vm.select(vm.state.value.editor!!.selection.copy(offset = Fraction.of(15, 16)))
        vm.setDuration(1)
        vm.insertChord(ChordDefinition(EntityId("chord-e"), "E", listOf(40, 47, 52, 56, 59, 64), frets = listOf(0, 2, 2, 1, 0, 0))); advanceUntilIdle()
        assertEquals(0L, repo.stored.revision)
        assertNotNull(vm.state.value.notice)
    } }
    @Test fun clearingTheViewModelStopsTheTuner() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val stopped = java.util.concurrent.CountDownLatch(1)
        val tuner = object : Tuner {
            override val readings = kotlinx.coroutines.flow.MutableSharedFlow<TuningReading>()
            override suspend fun start(inputDeviceId: String?) = Outcome.Success(Unit)
            override suspend fun stop(): Outcome<Unit> { stopped.countDown(); return Outcome.Success(Unit) }
        }
        val repo = Repository(ProjectFactory(ids).demo("Tuner"))
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, settings, PendingAudioServices(), ids, WorkspaceServices(tuner = tuner), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try {
            advanceUntilIdle(); vm.startTuner(); runCurrent()
            store.clear()
            assertTrue("microphone left open after the workspace was closed", stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { Dispatchers.resetMain() }
    }
}
