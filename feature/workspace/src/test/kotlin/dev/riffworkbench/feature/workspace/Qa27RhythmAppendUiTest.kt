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
 * Zyklus 27 „Rhythmus tippen: Takte anhängen“ — Robolectric, CI only (excluded from the JVM shim by its name). The real
 * WorkspaceScreen on the one-bar default piece: „Offen (Takte anhängen)“ → Auto-Stopp switch → Start → taps in bar 1 and
 * in the buffer bar after the end → the large „Stopp“ → the preview says „+1 Takt am Stückende“ → „Übernehmen: Leersaite“
 * appends the bar (all tracks) and writes the rhythm in one undo step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa27RhythmAppendUiTest {
    @get:Rule val compose = createComposeRule()

    private var n = 0
    private val ids = IdSource { EntityId("q27ui-${n++}") }
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
    /** Compiles real plans; the test moves the playhead and sets the audible frame of the next touch (and of „Stopp“). */
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

    @Test fun openRecordingStopAndApplyAppendsTheTappedBar() {
        val repo = Repository(ProjectFactory(ids).create("QA27").copy(revision = 0))
        val transport = TapTransport()
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, Settings(), transport, ids)
        store.put("vm", vm)
        compose.setContent { RiffTheme { WorkspaceScreen(vm, onBack = {}, onExport = {}, onSettings = {}) } }
        compose.waitUntil(20000) { vm.state.value.editor != null && compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val before = vm.state.value.editor!!.project
        assertEquals(1, before.measures.size)

        compose.onNodeWithTag("tools-more").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription("Rhythmus tippen") or hasContentDescription("Tap rhythm")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("rhythm-open").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(vm.rhythm.value.openActive)
        compose.onNodeWithTag("rhythm-autostop").performScrollTo().assertExists()
        compose.onNodeWithTag("rhythm-start").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.rhythm.value.phase == RhythmTapPhase.COUNT_IN }

        // The copy: bar 1 and four buffer bars (150 BPM: 76 800 frames per bar).
        val plan = transport.plan!!
        assertEquals(1 + WorkspaceViewModel.OPEN_BUFFER_BARS, plan.spans.size)
        val bar1 = plan.spans[0]; val bar2 = plan.spans[1]
        transport.state.value = transport.state.value.copy(sample = SamplePosition(bar1.startFrame + 100, 48_000), countingIn = false)
        compose.waitUntil(5000) { vm.rhythm.value.phase == RhythmTapPhase.RECORDING }
        compose.onNodeWithTag("rhythm-stop").assertExists()
        fun touch(downFrame: Double, upFrame: Double) {
            transport.nextFrame = downFrame
            compose.onNodeWithTag("rhythm-tap-surface").performTouchInput { down(center) }
            compose.waitForIdle()
            transport.nextFrame = upFrame
            compose.onNodeWithTag("rhythm-tap-surface").performTouchInput { up() }
            compose.waitForIdle()
        }
        touch(bar1.frameAt(0.0), bar1.frameAt(0.25) - 960)
        // Past the end of the piece: the metronome goes on in the buffer bar.
        transport.state.value = transport.state.value.copy(sample = SamplePosition(bar2.startFrame + 100, 48_000))
        compose.waitForIdle()
        touch(bar2.frameAt(0.5), bar2.frameAt(0.5) + 2880)
        assertEquals(2, vm.rhythm.value.taps)
        transport.nextFrame = bar2.frameAt(0.75)
        compose.onNodeWithTag("rhythm-stop").performClick()
        compose.waitUntil(5000) { vm.rhythm.value.phase == RhythmTapPhase.PREVIEW }
        compose.onNodeWithTag("rhythm-new-bars").assertExists()
        assertEquals(1, vm.rhythm.value.newBars)
        assertSame("preview writes nothing", before, vm.state.value.editor!!.project)

        compose.onNodeWithTag("rhythm-apply-open").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.rhythm.value.phase == RhythmTapPhase.IDLE }
        compose.waitForIdle()
        val written = vm.state.value.editor!!.project
        assertEquals(2, written.measures.size)
        for (t in written.tracks) assertEquals(written.measures.map { it.id }, t.staves[0].bars.map { it.measureId })
        val beats = written.tracks.first { it.instrument.kind == InstrumentKind.GUITAR }.staves[0].bars[1].voices[0].beats
        assertEquals(listOf(null, 0, null), beats.map { it.notes.firstOrNull()?.fret })
        vm.undo(); compose.waitForIdle()
        assertEquals(before.measures, vm.state.value.editor!!.project.measures)
        assertEquals(before.tracks, vm.state.value.editor!!.project.tracks)
    }
}
