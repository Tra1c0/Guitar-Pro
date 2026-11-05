package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.adapter.audio.EngineTransport
import dev.riffworkbench.adapter.audio.RealtimeHost
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA cycle 16: deterministic reproductions of the WorkspaceViewModel findings of the model-based campaign
 * (Qa16ModelBasedViewModelTest, JVM lifecycle shim). Each test asserts the expected behaviour and fails on the current code.
 * UNVERIFIED with the real androidx lifecycle (Android module, runs on CI only); verified red (7/7) through the JVM shim.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa16ViewModelFindingsTest {
    private var n = 0
    private val ids = IdSource { EntityId("q16f-${n++}") }
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
    /** The transport compiles on Dispatchers.Default (real threads): pump the test scheduler until [condition] holds. */
    private fun TestScope.await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) { advanceUntilIdle(); if (System.currentTimeMillis() > deadline) fail("timeout: $what"); Thread.sleep(5) }
    }
    /** Every coroutine the ViewModel launched has finished (the autosave loop always stays active). */
    private fun TestScope.settle(vm: WorkspaceViewModel) { await("ViewModel idle") { vm.viewModelScope.coroutineContext.job.children.count { it.isActive } <= 1 }; advanceUntilIdle() }

    private class Fixture(val vm: WorkspaceViewModel, val transport: EngineTransport, val host: RealtimeHost)
    private fun harness(bars: Int = 2, block: TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 1_000_000)
        val session = EditorSession(ProjectFactory(ids).create("QA16"), ids)
        repeat(bars - session.state.value.project.measures.size) { session.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        // Notes in every bar so wrapped systems have real widths.
        session.state.value.project.measures.forEach { m ->
            session.select(session.state.value.selection.copy(measureId = m.id, offset = Fraction.ZERO, string = 0))
            session.dispatch(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(8)))
        }
        val repo = Repository(session.state.value.project.copy(revision = 0))
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, settings, transport, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); block(Fixture(vm, transport, host)) } finally { store.clear(); transport.shutdown(); Dispatchers.resetMain() }
    }

    /** Mirror of WorkspaceViewModel.relayout() for the default view (TAB, no wrap unless [width]). */
    private fun expectedLayout(vm: WorkspaceViewModel, width: Float? = null): ScoreLayout {
        val e = vm.state.value.editor!!
        val style = LayoutStyle.EDIT.copy(showTab = true, showStandard = false)
        return ScoreLayouter.layout(LayoutRequest(e.project, 0, style, width, vm.input.value.duration.length(), e.selection.voice, null))
    }

    // ------------------------------------------------------------------ F1: entry duration vs. edit grid
    /** Entry duration 1/32 on an empty position: the edit grid (tappable empty slots) must follow; it stays at the old one. */
    @Test fun changingTheEntryDurationRedrawsTheEditGrid() = harness { f ->
        val vm = f.vm
        vm.select(vm.state.value.editor!!.selection.copy(offset = Fraction.of(1, 2)))   // empty slot, no beat selected
        advanceUntilIdle()
        val before = vm.layout.value!!.columns
        vm.setDuration(32); advanceUntilIdle()
        assertEquals(32, vm.input.value.duration.denominator)
        assertEquals("grid columns must use the entry duration 1/32", expectedLayout(vm).columns, vm.layout.value!!.columns)
    }
    /** Same with a selected beat: SetDuration re-lays out *before* the input duration is updated, so the grid keeps the old value. */
    @Test fun changingTheDurationOfTheSelectedBeatRedrawsTheEditGrid() = harness { f ->
        val vm = f.vm
        assertNotNull(vm.selectedBeat(vm.state.value.editor!!))
        vm.setDuration(32); advanceUntilIdle()
        assertEquals("grid columns must use the entry duration 1/32", expectedLayout(vm).columns, vm.layout.value!!.columns)
    }
    @Test fun changingTheTupletRedrawsTheEditGrid() = harness { f ->
        val vm = f.vm
        vm.select(vm.state.value.editor!!.selection.copy(offset = Fraction.of(1, 2))); advanceUntilIdle()
        vm.setTuplet(TupletRatio(3, 2)); advanceUntilIdle()
        assertEquals(expectedLayout(vm).columns, vm.layout.value!!.columns)
    }

    // ------------------------------------------------------------------ F2: zoom with wrapped systems
    /** Wrapped systems are laid out for viewportWidth / zoom; changing the zoom does not re-wrap them. */
    @Test fun zoomingWrappedSystemsReflowsThem() = harness(bars = 12) { f ->
        val vm = f.vm
        vm.setWrap(true); advanceUntilIdle()
        assertEquals(expectedLayout(vm, 400f).measures, vm.layout.value!!.measures)
        vm.setZoom(2f); advanceUntilIdle()
        val expected = expectedLayout(vm, 200f)
        assertEquals("systems must wrap at 400 dp / zoom 2 = 200 (layout width)", expected.width, vm.layout.value!!.width, 0.5f)
        assertEquals(expected.measures, vm.layout.value!!.measures)
    }

    // ------------------------------------------------------------------ F3/F4: Play after Stop
    /** Stop while an edit is being recompiled during playback, then Play: the Play is swallowed (transport stays STOPPED). */
    @Test fun playAfterStopDuringAnEditRecompileStartsPlayback() = harness { f ->
        val vm = f.vm
        vm.togglePlay(); await("playing") { f.transport.state.value.status == TransportStatus.PLAYING }
        vm.insertBar(after = true)   // edit while playing: publish() → transport.prepare() recompiles
        vm.stop()
        vm.togglePlay()              // user presses Play again
        settle(vm)
        assertEquals("Play pressed after Stop must start playback", TransportStatus.PLAYING, f.transport.state.value.status)
    }
    /** Same with pause (audio focus / ON_STOP) during the recompile and Play to resume. */
    @Test fun resumeAfterPauseDuringAnEditRecompileStartsPlayback() = harness { f ->
        val vm = f.vm
        vm.togglePlay(); await("playing") { f.transport.state.value.status == TransportStatus.PLAYING }
        vm.setSpeed(0.8)             // practice change while playing: prepare() recompiles
        vm.togglePlay()              // pause
        vm.togglePlay()              // resume
        settle(vm)
        assertEquals(TransportStatus.PLAYING, f.transport.state.value.status)
    }
    /** Three quick taps on Play (status still STOPPED while compiling), then Stop: one of the pending plays starts after the Stop. */
    @Test fun stopAfterRapidPlayTapsStopsPlayback() = harness { f ->
        val vm = f.vm
        repeat(3) { vm.togglePlay() }
        vm.stop()
        settle(vm)
        repeat(4) { f.host.renderBlock() }
        assertEquals("Stop pressed last must win", TransportStatus.STOPPED, f.transport.state.value.status)
        assertFalse(f.host.snapshot().playing)
    }
}
