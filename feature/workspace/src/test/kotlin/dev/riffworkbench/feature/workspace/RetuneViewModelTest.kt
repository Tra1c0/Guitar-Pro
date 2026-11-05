package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.adapter.audio.EngineTransport
import dev.riffworkbench.adapter.audio.RealtimeHost
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 18 / D4-D1: the tuning editor applies a draft through WorkspaceViewModel.retune(): dry run first, one SetInstrument
 * (one undo step), a notice that summarises octave shifts, and no change at all when notes would be dropped without consent.
 * UNVERIFIED locally (Android module, runs on CI only); the editor-level rules are covered by RetuneResolutionTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetuneViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("rt-${n++}") }
    private class Repository(var stored: Project) : ProjectRepository {
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> {
            if (expectedRevision != stored.revision) return Outcome.Failure(FailureCode.CONFLICT, "stale writer")
            stored = project; return Outcome.Success(Unit)
        }
    }
    private class Snapshots : SnapshotRepository {
        override suspend fun list(projectId: EntityId) = Outcome.Success(emptyList<SnapshotInfo>())
        override suspend fun create(project: Project, name: String) = Outcome.Success(SnapshotInfo("snap", project.id, project.revision, name, 0))
        override suspend fun load(projectId: EntityId, snapshotId: String): Outcome<Project> = unavailable("C-SNAPSHOT")
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
    private class Sink : AudioSink {
        var frames = 0L
        override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> = Outcome.Success(SinkFormat(48_000, 256, 0, "fake"))
        override fun write(buffer: FloatArray, frames: Int): Int { this.frames += frames; return frames }
        override fun presentedFrame(): Long = frames
        override fun close() {}
    }

    /** New default project (one 4/4 bar, E standard) plus a second bar; G2 = 43 on the low E string in both bars. */
    private fun harness(block: TestScope.(WorkspaceViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 1_000_000)
        val session = EditorSession(ProjectFactory(ids).create("Retune"), ids)
        session.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4)))
        session.state.value.project.measures.forEach { m ->
            session.select(session.state.value.selection.copy(measureId = m.id, offset = Fraction.ZERO, string = 0))
            session.dispatch(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(8)))
        }
        val repo = Repository(session.state.value.project.copy(revision = 0))
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, settings, transport, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); block(vm) } finally { store.clear(); transport.shutdown(); Dispatchers.resetMain() }
    }
    private fun guitar(vm: WorkspaceViewModel) = vm.state.value.editor!!.project.tracks.first { it.instrument.kind == InstrumentKind.GUITAR }
    private fun pitches(vm: WorkspaceViewModel) = guitar(vm).staves.first().bars.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { it.notes } } }.map { it.pitch.midiNumber }
    private fun content(p: Project) = p.copy(revision = 0)

    @Test fun retuneWithoutConflictsAppliesTheDraftAndKeepsTheNotesPitch() = harness { vm ->
        val dropA = TuningPresets.all.first { it.id == "g7-dropa" }
        val draft = guitar(vm).instrument.copy(tuning = dropA.tuning, capoByString = List(7) { 0 })
        assertTrue(vm.retuneProblems(draft).isEmpty())
        assertTrue(vm.retune(draft) is Outcome.Success); advanceUntilIdle()
        assertEquals(dropA.tuning, guitar(vm).instrument.tuning)
        assertEquals(listOf(43, 43), pitches(vm))
        assertNull("nothing to summarise", vm.state.value.notice)
    }

    @Test fun octavedNotesAreSummarisedWithTheirBarsAndUndoIsOneStep() = harness { vm ->
        val before = vm.state.value.editor!!.project
        // No string reaches G2 = 43: both notes go an octave up.
        val draft = guitar(vm).instrument.copy(tuning = listOf(45, 50, 55, 59, 64, 69), capoByString = List(6) { 0 })
        val problems = vm.retuneProblems(draft)
        assertEquals(listOf(RetuneResolution.OCTAVE_UP, RetuneResolution.OCTAVE_UP), problems.map { it.resolution })
        assertEquals(listOf(0, 1), problems.map { it.barIndex })
        assertTrue(vm.retune(draft) is Outcome.Success); advanceUntilIdle()
        assertEquals("2 Noten um eine Oktave versetzt (Takt 1, 2)", vm.state.value.notice)
        assertEquals(listOf(55, 55), pitches(vm))
        vm.undo(); advanceUntilIdle()
        assertEquals("one undo restores tuning and pitches", content(before), content(vm.state.value.editor!!.project))
    }

    @Test fun dropsNeedExplicitConsentAndAreReportedWithBarNumbers() = harness { vm ->
        val before = vm.state.value.editor!!.project
        // Three strings far above the notes: G2 fits nowhere, not even an octave away.
        val draft = guitar(vm).instrument.copy(tuning = listOf(100, 105, 110), capoByString = List(3) { 0 })
        assertEquals(listOf(RetuneResolution.DROP, RetuneResolution.DROP), vm.retuneProblems(draft).map { it.resolution })
        val refused = vm.retune(draft); advanceUntilIdle()
        assertTrue(refused is Outcome.Failure)
        assertEquals("Umstimmen nicht möglich: Ton G2 (Takt 1, Saite 1) nicht spielbar; Ton G2 (Takt 2, Saite 1) nicht spielbar", (refused as Outcome.Failure).message)
        assertEquals(refused.message, vm.state.value.notice)
        assertEquals("a refused retune changes nothing", content(before), content(vm.state.value.editor!!.project))
        assertTrue(vm.retune(draft, allowDrop = true) is Outcome.Success); advanceUntilIdle()
        assertEquals("2 Noten entfernt (Takt 1, 2)", vm.state.value.notice)
        assertEquals(emptyList<Int>(), pitches(vm))
        assertEquals(listOf(100, 105, 110), guitar(vm).instrument.tuning)
    }

    @Test fun retunePolicyChipsPersistToAppSettings() = harness { vm ->
        assertEquals(RetuningPolicy.KEEP_SOUNDING_PITCH, vm.retunePolicy())
        vm.setRetunePolicy(RetuningPolicy.KEEP_FINGER_POSITION); advanceUntilIdle()
        assertEquals(RetuningPolicy.KEEP_FINGER_POSITION, settings.settings.value.retuningPolicy)
        assertEquals(RetuningPolicy.KEEP_FINGER_POSITION, vm.retunePolicy())
    }
}
