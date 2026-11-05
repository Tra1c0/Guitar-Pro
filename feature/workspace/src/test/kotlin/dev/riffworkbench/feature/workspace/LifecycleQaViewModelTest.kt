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
 * QA cycle 11 (UNVERIFIED locally: Android module, runs on CI only).
 * MainActivity calls transport.pause() on every ON_STOP. EngineTransport.pause() turns STOPPED into PAUSED, and
 * togglePlay() treats PAUSED as "resume" and skips the seek to the cursor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleQaViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("lq-${n++}") }
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
    private fun TestScope.await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) { advanceUntilIdle(); if (System.currentTimeMillis() > deadline) fail("timeout"); Thread.sleep(5) }
    }

    @Test fun playAfterTheAppWasInTheBackgroundStartsAtTheCursor() = cursorAfterStop(backgrounded = true)
    /** Control: identical flow without the ON_STOP pause passes. */
    @Test fun playAfterStopStartsAtTheCursor() = cursorAfterStop(backgrounded = false)

    private fun cursorAfterStop(backgrounded: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 1_000_000)
        val repo = Repository(ProjectFactory(ids).create("Cursor"))
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, settings, transport, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try {
            advanceUntilIdle()
            vm.togglePlay(); await { transport.state.value.status == TransportStatus.PLAYING }
            repeat(20) { host.renderBlock() }
            vm.stop(); await { transport.state.value.status == TransportStatus.STOPPED }
            host.renderBlock()
            if (backgrounded) transport.pause()     // MainActivity: ON_STOP -> transport.pause(); user comes back
            vm.moveMeasure(1); advanceUntilIdle()   // cursor to bar 2 (bar 1 = 4/4 at the factory tempo of 150 BPM = 76 800 frames)
            vm.togglePlay(); await { transport.state.value.status == TransportStatus.PLAYING }
            host.renderBlock()
            assertTrue("play must start at the cursor (bar 2), started at frame ${host.snapshot().planFrame}", host.snapshot().planFrame >= 76_800)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    // A second session on one project is prevented in navigation (LifecycleQaNavigationTest.doubleTapOnAProjectOpensItOnce).
}
