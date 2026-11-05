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
 * QA 18 / D5: start position as the editor drives it (WorkspaceViewModel with real EngineTransport and a manually driven
 * RealtimeHost). The tests encode the rules of Bericht 1 §3; they were red before the start-marker rules:
 *
 *  R1  Pause → navigation (tap elsewhere in the editor) → Play starts at the new cursor, not at the paused position.
 *  R1b Pause → PageDown twice → Play starts at the cursor.
 *  R1c Pause → Play without navigation resumes at the paused position (unchanged rule).
 *  R2  After the piece ran to its end (STOPPED by the poller) Play starts at the cursor (needs the EngineTransport fix for
 *      the stale `ended` flag, D5-1, tracked separately; ignored until then).
 *  R3  Jump to start: cursor in bar 1, transport stopped, next Play at the top.
 *  R4  Bar map / PageDown while PLAYING seek the transport (D5-8).
 *  R5  Follow suspension is ViewModel state and is cleared by Play, seek, stop and jumpToStart (D5-5).
 *
 * Verified on the JVM through the lifecycle shim of the QA harness; UNVERIFIED with the real androidx lifecycle (CI).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa18StartPositionViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("d5vm-${n++}") }
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
    private fun TestScope.await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) { advanceUntilIdle(); if (System.currentTimeMillis() > deadline) fail("timeout: $what"); Thread.sleep(5) }
    }
    private fun TestScope.settle(vm: WorkspaceViewModel) { await("ViewModel idle") { vm.viewModelScope.coroutineContext.job.children.count { it.isActive } <= 1 }; advanceUntilIdle() }

    private class Fixture(val vm: WorkspaceViewModel, val transport: EngineTransport, val host: RealtimeHost)
    /** Default project plus [extraBars] 4/4 bars, one note per bar. */
    private fun harness(extraBars: Int = 2, block: TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 5)   // the app polls ~30×/s; the poller is what publishes STOPPED at the end
        val session = EditorSession(ProjectFactory(ids).create("QA18 D5"), ids)
        repeat(extraBars) { session.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        session.state.value.project.measures.forEach { m ->
            session.select(session.state.value.selection.copy(measureId = m.id, offset = Fraction.ZERO, string = 0))
            session.dispatch(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(8)))
        }
        session.select(session.state.value.selection.copy(measureId = session.state.value.project.measures.first().id, offset = Fraction.ZERO, string = 0))
        val repo = Repository(session.state.value.project.copy(revision = 0))
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, settings, transport, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); await("project open") { vm.state.value.editor != null }; block(Fixture(vm, transport, host)) } finally { transport.shutdown(); store.clear(); Dispatchers.resetMain() }
    }
    private fun TestScope.awaitStatus(f: Fixture, s: TransportStatus) = await("transport $s (is ${f.transport.state.value.status})") { f.transport.state.value.status == s }
    private fun playheadBar(f: Fixture): Int = f.vm.state.value.editor!!.project.measures.indexOfFirst { it.id == f.transport.state.value.position?.position?.measureId } + 1
    private fun cursorBar(f: Fixture): Int = f.vm.state.value.editor!!.project.measures.indexOfFirst { it.id == f.vm.state.value.editor!!.selection.measureId } + 1
    /** The render thread must process the posted Seek and the poller must republish before the playhead shows the new bar. */
    private fun TestScope.awaitPlayheadBar(f: Fixture, bar: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (playheadBar(f) != bar) { advanceUntilIdle(); f.host.renderBlock(); if (System.currentTimeMillis() > deadline) fail("timeout: playhead bar $bar (is ${playheadBar(f)})"); Thread.sleep(5) }
    }

    /** Editor tap handler (EditorScreen TabCard): select; seek the transport only while PLAYING. */
    private fun editorTap(f: Fixture, bar: Int) {
        val m = f.vm.state.value.editor!!.project.measures[bar - 1]
        f.vm.tap(m.id, Fraction.ZERO, 0)
        if (f.transport.state.value.status == TransportStatus.PLAYING) f.vm.seekToCursor()
    }
    private fun TestScope.playThenPause(f: Fixture) {
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        repeat(40) { f.host.renderBlock() }                       // ~0.2 s into bar 1
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PAUSED)
        f.host.renderBlock()
        assertEquals(1, playheadBar(f))
    }

    // ---------------------------------------------------------------- R1: pause, navigate, play
    @Test fun r1_pauseThenTapElsewhereThenPlayStartsAtTheTappedBar() = harness { f ->
        playThenPause(f)
        editorTap(f, 3)                                           // user taps bar 3 while paused (cursor moves, playhead stays in bar 1)
        settle(f.vm)
        assertEquals(3, cursorBar(f))
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals("Pause → navigation → Play must start where the user navigated to (Bericht 1 §3), not resume at the paused position",
            3, playheadBar(f))
    }

    @Test fun r1b_pauseThenArrowKeysThenPlayStartsAtTheCursor() = harness { f ->
        playThenPause(f)
        f.vm.moveMeasure(1); f.vm.moveMeasure(1); settle(f.vm)    // PageDown twice / "Takt weiter"
        assertEquals(3, cursorBar(f))
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals(3, playheadBar(f))
    }

    /** The unchanged half of the rule: Pause → Play with no navigation in between resumes where it paused. */
    @Test fun r1c_pauseThenPlayWithoutNavigationResumes() = harness { f ->
        editorTap(f, 2); settle(f.vm)
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals(2, playheadBar(f))
        repeat(40) { f.host.renderBlock() }
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PAUSED); f.host.renderBlock()
        val paused = f.transport.state.value.sample?.frame
        assertNotNull(paused)
        // An edit at the cursor (same selection) is not navigation either.
        f.vm.setDuration(16); settle(f.vm)
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        val resumed = f.transport.state.value.sample?.frame
        assertTrue("resume keeps the paused frame (paused $paused, resumed $resumed)", resumed != null && kotlin.math.abs(resumed - paused!!) <= 512)
        assertEquals(2, playheadBar(f))
    }

    // ---------------------------------------------------------------- R2: natural end, play again
    @Test fun r2_afterTheEndPlayStartsAtTheCursor() = harness { f ->
        editorTap(f, 2); settle(f.vm)                             // cursor in bar 2 (the user works there)
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals("first Play starts at the cursor", 2, playheadBar(f))
        var blocks = 0
        while (!f.host.snapshot().ended && blocks < 20_000) { f.host.renderBlock(); blocks++ }
        awaitStatus(f, TransportStatus.STOPPED)                   // poller: snap.ended && PLAYING -> STOPPED
        repeat(2) { f.host.renderBlock() }                       // idle render thread keeps reporting `ended`
        assertEquals("cursor did not move", 2, cursorBar(f))
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals("second Play after the natural end must start at the cursor (bar 2) like the first one, not at bar 1", 2, playheadBar(f))
    }

    // ---------------------------------------------------------------- R3: explicit jump to the start
    @Test fun r3_jumpToStartStopsAndTheNextPlayStartsAtBarOne() = harness { f ->
        editorTap(f, 3); settle(f.vm)
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals(3, playheadBar(f))
        repeat(20) { f.host.renderBlock() }
        f.vm.jumpToStart(); awaitStatus(f, TransportStatus.STOPPED); settle(f.vm)
        assertEquals("cursor at bar 1", 1, cursorBar(f))
        assertEquals(Fraction.ZERO, f.vm.state.value.editor!!.selection.offset)
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals("Play after the jump starts at the top", 1, playheadBar(f))
        // Also from PAUSED: the jump wins over "resume where paused".
        repeat(40) { f.host.renderBlock() }
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PAUSED)
        editorTap(f, 3); settle(f.vm)
        f.vm.jumpToStart(); awaitStatus(f, TransportStatus.STOPPED); settle(f.vm)
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals(1, playheadBar(f))
    }

    // ---------------------------------------------------------------- R4: navigation while playing seeks (D5-8)
    @Test fun r4_barMapAndPageDownSeekWhilePlaying() = harness { f ->
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        repeat(10) { f.host.renderBlock() }
        assertEquals(1, playheadBar(f))
        val bar3 = f.vm.state.value.editor!!.project.measures[2].id
        f.vm.selectMeasure(bar3); settle(f.vm)
        awaitPlayheadBar(f, 3)                                    // bar map while playing moves the playhead
        assertEquals(3, cursorBar(f))
        f.vm.moveMeasure(1); settle(f.vm)
        awaitPlayheadBar(f, 4)                                    // PageDown while playing moves the playhead
        f.vm.move(-1); settle(f.vm)
        awaitPlayheadBar(f, 3)                                    // one beat back lands in bar 3
        assertEquals(3, cursorBar(f))
        assertEquals(TransportStatus.PLAYING, f.transport.state.value.status)
    }

    // ---------------------------------------------------------------- R5: follow suspension as ViewModel state (D5-5)
    @Test fun r5_followSuspensionIsClearedByPlaySeekStopAndJump() = harness { f ->
        val vm = f.vm
        assertFalse(vm.view.value.followSuspended)
        vm.suspendFollow(); assertTrue(vm.view.value.followSuspended)
        vm.resumeFollow(); assertFalse(vm.view.value.followSuspended)
        vm.suspendFollow(); vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertFalse("Play resumes following", vm.view.value.followSuspended)
        vm.suspendFollow(); vm.seekToCursor(); settle(vm)
        assertFalse("seek resumes following", vm.view.value.followSuspended)
        vm.suspendFollow(); vm.stop(); awaitStatus(f, TransportStatus.STOPPED)
        assertFalse("stop resumes following", vm.view.value.followSuspended)
        vm.suspendFollow(); vm.jumpToStart(); settle(vm)
        assertFalse("jump to start resumes following", vm.view.value.followSuspended)
        vm.suspendFollow(); vm.setFollow(false)
        assertFalse(vm.view.value.followSuspended)
        assertFalse(vm.view.value.follow)
    }
}
