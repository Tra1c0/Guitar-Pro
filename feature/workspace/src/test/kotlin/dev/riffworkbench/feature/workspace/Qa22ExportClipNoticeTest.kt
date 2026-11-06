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
 * QA 22 (Export-Rückmeldung): Stems haben keinen Master-Limiter; ein heißer Spur- oder Master-Fader (beide 1,0, Clean-v127-
 * Akkorde) übersteuert sie im 24-Bit-WAV. Der Renderer zählt die Samples über Vollaussteuerung, die Oberfläche zeigte nur
 * „exportiert“ — die Übersteuerung blieb still. Jetzt nennt die Meldung jede übersteuerte Datei (rot davor: kein Hinweis).
 *
 * Verified on the JVM through the lifecycle shim of the QA harness; UNVERIFIED with the real androidx lifecycle (CI).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa22ExportClipNoticeTest {
    private var n = 0
    private val ids = IdSource { EntityId("q22clip-${n++}") }
    private class Repository(var stored: Project) : ProjectRepository {
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> { stored = project; return Outcome.Success(Unit) }
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
        override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> = Outcome.Success(SinkFormat(48_000, 256, 0, "fake"))
        override fun write(buffer: FloatArray, frames: Int): Int = frames
        override fun presentedFrame(): Long = 0
        override fun close() {}
    }
    /** Clips [clippedPerTrack] samples in the stem of that track index and [mixClipped] in the mix (soloTrack == null). */
    private class Renderer(val clippedPerTrack: Map<Int, Long>, val mixClipped: Long) : OfflineRenderer {
        override suspend fun render(project: Project, options: RenderOptions, progress: (OperationProgress) -> Unit): Outcome<List<ExportResult>> = unavailable("C-RENDER")
        override suspend fun renderTo(project: Project, options: RenderOptions, sink: ByteSink, soloTrack: Int?, progress: (OperationProgress) -> Unit): Outcome<RenderSummary> {
            sink.write(ByteArray(44), 0, 44)
            val clipped = if (soloTrack == null) mixClipped else clippedPerTrack[soloTrack] ?: 0L
            return Outcome.Success(RenderSummary(48_000, 48_000, 2, if (clipped > 0) 1.4 else 0.5, clipped, 44))
        }
    }

    private fun harness(renderer: Renderer, block: suspend TestScope.(WorkspaceViewModel, Project) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 5)
        val repo = Repository(ProjectFactory(ids).create("QA22").copy(revision = 0))
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, Settings(), transport, ids, WorkspaceServices(renderer = renderer), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try {
            advanceUntilIdle()
            val deadline = System.currentTimeMillis() + 10_000
            while (vm.state.value.editor == null) { advanceUntilIdle(); if (System.currentTimeMillis() > deadline) fail("project open"); Thread.sleep(2) }
            block(vm, repo.stored)
        } finally { transport.shutdown(); store.clear(); Dispatchers.resetMain() }
    }
    private val discard = ByteSink { _, _, _ -> }

    @Test fun clippedStemsAreNamedInTheExportNotice() = harness(Renderer(mapOf(0 to 620L), 0L)) { vm, project ->
        assertTrue(vm.renderAudio(ExportTarget.STEMS, discard) is Outcome.Success)
        val notice = vm.state.value.notice ?: ""
        assertTrue("Meldung nennt die übersteuerte Spur: $notice", notice.contains("Übersteuert") && notice.contains(project.tracks[0].name) && notice.contains("620"))
        assertFalse("nicht übersteuerte Dateien werden nicht genannt: $notice", notice.contains("Mix ("))
    }

    @Test fun aCleanExportSaysNothingAboutClipping() = harness(Renderer(emptyMap(), 0L)) { vm, _ ->
        assertTrue(vm.renderAudio(ExportTarget.STEMS, discard) is Outcome.Success)
        assertFalse(vm.state.value.notice ?: "", (vm.state.value.notice ?: "").contains("Übersteuert"))
        assertTrue(vm.renderAudio(ExportTarget.WAV, discard) is Outcome.Success)
        assertFalse(vm.state.value.notice ?: "", (vm.state.value.notice ?: "").contains("Übersteuert"))
    }

    @Test fun aClippedMixIsReportedToo() = harness(Renderer(emptyMap(), 12L)) { vm, _ ->
        assertTrue(vm.renderAudio(ExportTarget.WAV, discard) is Outcome.Success)
        assertTrue(vm.state.value.notice ?: "", (vm.state.value.notice ?: "").contains("Mix (12 Samples)"))
    }
}
