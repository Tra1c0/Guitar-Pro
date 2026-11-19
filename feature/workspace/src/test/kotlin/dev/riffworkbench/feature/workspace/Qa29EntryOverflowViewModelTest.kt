package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.adapter.audio.EngineTransport
import dev.riffworkbench.adapter.audio.RealtimeHost
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 29 („Probleme mit Pausen und Noten“, ViewModel through the lifecycle shim): fret entry with auto-advance where the
 * entry value does not fit before the barline. Before: refused with „Beat überschreitet den Takt“ (a dotted quarter on the
 * „und“ of 3 in 4/4). Now the note is written over the barline with a tie, the cursor steps over the tied continuation
 * and the next fret is a new note; one entry is one undo step; the Zyklus-23 range rule for duration keys and the
 * Zyklus-29 lengthening go through the ViewModel as in the editor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa29EntryOverflowViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("q29vm-${n++}") }
    private class Repository(var stored: Project) : ProjectRepository {
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> {
            require(ScoreValidator.validate(project).none { it.severity == Severity.ERROR })
            stored = project; return Outcome.Success(Unit)
        }
    }
    private class Snapshots : SnapshotRepository {
        override suspend fun list(projectId: EntityId) = Outcome.Success(emptyList<SnapshotInfo>())
        override suspend fun create(project: Project, name: String) = Outcome.Success(SnapshotInfo("snap", project.id, project.revision, name, 0))
        override suspend fun load(projectId: EntityId, snapshotId: String): Outcome<Project> = unavailable("C-SNAPSHOT")
    }
    private class Settings : SettingsRepository {
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
    private fun TestScope.await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) { advanceUntilIdle(); if (System.currentTimeMillis() > deadline) fail("timeout: $what"); Thread.sleep(2) }
    }
    private fun TestScope.settle(vm: WorkspaceViewModel) { await("ViewModel idle") { vm.viewModelScope.coroutineContext.job.children.count { it.isActive } <= 1 }; advanceUntilIdle() }
    private fun harness(block: TestScope.(WorkspaceViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 5)
        val repo = Repository(ProjectFactory(ids).create("QA29").copy(revision = 0))
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, Settings(), transport, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); await("project open") { vm.state.value.editor != null && vm.layout.value != null }; block(vm) }
        finally { transport.shutdown(); store.clear(); Dispatchers.resetMain() }
    }
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private fun guitar(vm: WorkspaceViewModel) = vm.state.value.editor!!.project.tracks.first { it.instrument.kind == InstrumentKind.GUITAR }
    /** (bar 1-based, offset, fret or null, length) of the guitar's voice 1. */
    private fun shape(vm: WorkspaceViewModel) = guitar(vm).staves[0].bars.flatMapIndexed { i, b -> b.voices[0].beats.map { listOf(i + 1, it.offset, it.notes.firstOrNull()?.fret, it.duration.length()) } }

    @Test fun aDottedQuarterOverTheBarlineIsTiedAndTheCursorStepsOverTheContinuation() = harness { vm ->
        vm.setDuration(4, 1)
        repeat(3) { vm.enterFret(it + 1); settle(vm) }
        assertNull(vm.state.value.notice)
        val p = vm.state.value.editor!!.project
        assertEquals(2, p.measures.size)
        assertEquals(listOf(listOf(1, f(0, 1), 1, f(3, 8)), listOf(1, f(3, 8), 2, f(3, 8)), listOf(1, f(3, 4), 3, f(1, 4)), listOf(2, f(0, 1), 3, f(1, 8))), shape(vm))
        val bar1 = guitar(vm).staves[0].bars[0].voices[0].beats
        val cont = guitar(vm).staves[0].bars[1].voices[0].beats[0].notes.single()
        assertEquals(listOf(NoteLink(LinkKind.TIE, cont.id)), bar1.last().notes.single().links)
        // The cursor stands behind the continuation; the next fret is a new note there (not a refused re-pitch).
        val sel = vm.state.value.editor!!.selection
        assertEquals(p.measures[1].id to f(1, 8), sel.measureId to sel.offset)
        vm.enterFret(5); settle(vm)
        assertNull(vm.state.value.notice)
        assertEquals(listOf(2, f(1, 8), 5, f(3, 8)), shape(vm)[4])
        // One undo step per entry: the overflow entry (with its appended bar) goes away in one step.
        vm.undo(); settle(vm); vm.undo(); settle(vm)
        assertEquals(1, vm.state.value.editor!!.project.measures.size)
        assertEquals(listOf(listOf(1, f(0, 1), 1, f(3, 8)), listOf(1, f(3, 8), 2, f(3, 8))), shape(vm))
    }

    /** Before: „Beat überschreitet den Takt“ (AutosaveTest wrote that refusal down). Now every string is tied over. */
    @Test fun aChordOverTheBarlineIsTiedOnEveryString() = harness { vm ->
        vm.select(vm.state.value.editor!!.selection.copy(offset = f(15, 16))); settle(vm)
        vm.setDuration(1)
        vm.insertChord(ChordDefinition(EntityId("chord-e"), "E", listOf(40, 47, 52, 56, 59, 64), frets = listOf(0, 2, 2, 1, 0, 0))); settle(vm)
        assertNull(vm.state.value.notice)
        val bars = guitar(vm).staves[0].bars
        assertEquals(2, bars.size)
        // 15/16 in bar 2 are written as a double-dotted half plus a 16th, tied on.
        val first = bars[0].voices[0].beats.single(); val conts = bars[1].voices[0].beats; val cont = conts.first()
        assertEquals(f(1, 16) to f(15, 16), first.duration.length() to conts.fold(Fraction.ZERO) { a, b -> a + b.duration.length() })
        assertTrue(conts.all { it.notes.size == 6 })
        assertEquals(6, first.notes.size); assertEquals(6, cont.notes.size)
        for (n in first.notes) {
            val target = cont.notes.single { it.id == n.links.single().target }
            assertEquals(LinkKind.TIE, n.links.single().kind); assertEquals(n.string to n.fret, target.string to target.fret)
        }
        vm.undo(); settle(vm)
        assertEquals(1, vm.state.value.editor!!.project.measures.size)
        assertTrue(guitar(vm).staves[0].bars[0].voices[0].beats.isEmpty())
    }

    @Test fun aLongerValueKeyMovesTheFollowingNotes() = harness { vm ->
        vm.setDuration(4)
        repeat(4) { vm.enterFret(it); settle(vm) }
        vm.select(vm.state.value.editor!!.selection.copy(measureId = vm.state.value.editor!!.project.measures[0].id, offset = Fraction.ZERO, rangeEnd = null)); settle(vm)
        vm.setDuration(2); settle(vm)
        assertNull(vm.state.value.notice)
        assertEquals(listOf(listOf(1, f(0, 1), 0, f(1, 2)), listOf(1, f(1, 2), 1, f(1, 4)), listOf(1, f(3, 4), 2, f(1, 4)), listOf(2, f(0, 1), 3, f(1, 4))), shape(vm).take(4))
        vm.undo(); settle(vm)
        assertEquals(listOf(0, 1, 2, 3), shape(vm).filter { it[0] == 1 }.map { it[2] })
    }
}
