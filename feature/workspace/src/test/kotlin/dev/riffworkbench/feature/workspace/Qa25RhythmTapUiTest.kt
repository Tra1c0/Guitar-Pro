package dev.riffworkbench.feature.workspace

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.ports.*
import dev.riffworkbench.ui.components.RiffTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Zyklus 25 „Rhythmus tippen“ — UNVERIFIED locally (Robolectric, CI only; excluded from the JVM shim by its name). The real
 * WorkspaceScreen: tools sheet → „Rhythmus tippen“ → Start → the tap surface takes touch-downs and -ups (with haptics),
 * the recording stops after the target bars, the preview (rhythm line) appears, „Übernehmen: Leersaite“ writes the bar.
 * The transport is a fake whose time map returns the plan frame the test sets before each touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa25RhythmTapUiTest {
    @get:Rule val compose = createComposeRule()

    private var n = 0
    private val ids = IdSource { EntityId("q25ui-${n++}") }
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
    /** Compiles real plans; the test moves the playhead and sets the audible frame of the next touch. */
    private class TapTransport : Transport {
        override val state = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
        var plan: PlaybackPlan? = null
        var settings = PracticeSettings()
        var start = 0L
        @Volatile var nextFrame = Double.NaN
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> {
            this.settings = settings
            plan = (ScorePlaybackCompiler().compileNow(project, 48_000, PlaybackOptions(settings.speed)) as Outcome.Success).value
            return Outcome.Success(Unit)
        }
        override suspend fun play(): Outcome<Unit> { state.value = state.value.copy(status = TransportStatus.PLAYING, sample = SamplePosition(start, 48_000), countingIn = settings.countInBars > 0); return Outcome.Success(Unit) }
        override suspend fun pause(): Outcome<Unit> { state.value = state.value.copy(status = TransportStatus.PAUSED); return Outcome.Success(Unit) }
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> { start = plan?.frameFor(position) ?: 0L; return Outcome.Success(Unit) }
        override suspend fun setLoop(range: LoopRange?): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> { state.value = state.value.copy(status = TransportStatus.STOPPED, countingIn = false); return Outcome.Success(Unit) }
        override fun audiblePlanFrameAt(nanos: Long): Double = nextFrame
        override fun installedPlan(): PlaybackPlan? = plan
    }

    private val store = ViewModelStore()
    @After fun tearDown() { store.clear() }

    @Test fun tapSurfaceRecordsPreviewAppearsAndApplyWrites() {
        val repo = Repository(ProjectFactory(ids).create("QA25").copy(revision = 0))
        val transport = TapTransport()
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, Settings(), transport, ids)
        store.put("vm", vm)
        compose.setContent { RiffTheme { WorkspaceScreen(vm, onBack = {}, onExport = {}, onSettings = {}) } }
        compose.waitUntil(20000) { vm.state.value.editor != null && compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val before = vm.state.value.editor!!.project

        // Entry: tools sheet → „Rhythmus tippen“.
        compose.onNodeWithTag("tools-more").performClick()
        compose.waitForIdle()
        // By its click action, not coordinates: a partially expanded sheet may hold the tile below the screen edge.
        compose.onAllNodes(hasContentDescription("Rhythmus tippen") or hasContentDescription("Tap rhythm")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("rhythm-dialog").assertExists()
        assertEquals(RhythmTapPhase.SETUP, vm.rhythm.value.phase)
        compose.onNodeWithTag("rhythm-bars-0").performClick()   // 1 bar
        compose.onNodeWithTag("rhythm-start").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.rhythm.value.phase == RhythmTapPhase.COUNT_IN }
        compose.onNodeWithTag("rhythm-tap-surface").assertExists()

        // Recording: the bar is 76 800 frames at 150 BPM; one held quarter, one quick tap on beat 3.
        val bar = transport.plan!!.spans[0]
        transport.state.value = transport.state.value.copy(sample = SamplePosition(bar.startFrame + 100, 48_000), countingIn = false)
        compose.waitUntil(5000) { vm.rhythm.value.phase == RhythmTapPhase.RECORDING }
        fun touch(downFrame: Double, upFrame: Double) {
            transport.nextFrame = downFrame
            compose.onNodeWithTag("rhythm-tap-surface").performTouchInput { down(center) }
            compose.waitForIdle()
            transport.nextFrame = upFrame
            compose.onNodeWithTag("rhythm-tap-surface").performTouchInput { up() }
            compose.waitForIdle()
        }
        touch(bar.frameAt(0.0), bar.frameAt(0.25) - 960)
        assertEquals("tap surface reacts", 1, vm.rhythm.value.taps)
        touch(bar.frameAt(0.5), bar.frameAt(0.5) + 2880)
        assertEquals(2, vm.rhythm.value.taps)

        // Auto-stop after the target bar → preview.
        transport.state.value = transport.state.value.copy(sample = SamplePosition(bar.endFrame, 48_000))
        compose.waitUntil(5000) { vm.rhythm.value.phase == RhythmTapPhase.PREVIEW }
        compose.onNodeWithTag("rhythm-preview").assertExists()
        assertEquals(TransportStatus.STOPPED, transport.state.value.status)
        assertSame("preview writes nothing", before, vm.state.value.editor!!.project)

        // Apply as open strings: the bar is written, one undo step, the dialog closes.
        compose.onNodeWithTag("rhythm-apply-open").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.rhythm.value.phase == RhythmTapPhase.IDLE }
        compose.waitForIdle()
        compose.onNodeWithTag("rhythm-dialog").assertDoesNotExist()
        val written = vm.state.value.editor!!.project
        val beats = written.tracks.first { it.instrument.kind == InstrumentKind.GUITAR }.staves[0].bars[0].voices[0].beats
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(1, 2), Fraction.of(9, 16)), beats.map { it.offset })
        assertEquals(listOf(0, null, 0, null), beats.map { it.notes.firstOrNull()?.fret })
        vm.undo(); compose.waitForIdle()
        assertEquals(before.tracks, vm.state.value.editor!!.project.tracks)
    }
}
