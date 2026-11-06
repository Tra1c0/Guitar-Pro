package dev.riffworkbench.feature.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.ScoreScale
import dev.riffworkbench.ports.*
import dev.riffworkbench.ui.components.RiffTheme
import dev.riffworkbench.ui.score.ScoreViewport
import dev.riffworkbench.ui.score.ScoreViewportKey
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
 * QA 23 (Gerätebefunde Flackern / Meldungen / Partitur-Größe) — UNVERIFIED locally (Robolectric, CI only). The real
 * WorkspaceScreen with a WorkspaceViewModel over in-memory repositories and a transport whose state the test drives.
 *
 *  R1  A transport poll (position update while PLAYING, ~30 Hz on the device) recomposes the score and nothing else of the
 *      editor: workspace scaffold, editor screen, header, card row, track menu and controls keep their composition count
 *      ([RenderCounts]). Before, WorkspaceScreen, EditorHeader and TabCard read the whole transport state.
 *  R2  Open menus and sheets survive polls without recomposing: the tools sheet, the track menu and the "Alle Bereiche" sheet.
 *  N1  A notice lies over the page: the score canvas and the fret pad keep their bounds, a control under the notice still
 *      takes the tap, and the notice closes itself after [NoticeTiming] (ViewModel state back to null).
 *  N2  Inside the tools sheet the notice is shown once (not also on the page behind the scrim) and does not move the tiles.
 *  N3  A failed save keeps its notice with "Erneut versuchen" past the timeout.
 *  Z1  Default: the score is drawn at density × [ScoreScale.EDITOR]; a pinch does not zoom (two fingers pan). With
 *      AppSettings.pinchZoom the pinch zooms and the ViewModel hears the final zoom once, at release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa23RenderStabilityUiTest {
    @get:Rule val compose = createComposeRule()

    private var n = 0
    private val ids = IdSource { EntityId("q23ui-${n++}") }
    private class Repository(var stored: Project) : ProjectRepository {
        @Volatile var failSaves = false
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> {
            if (failSaves) return Outcome.Failure(FailureCode.IO, "Speichern fehlgeschlagen (QA23)")
            stored = project; return Outcome.Success(Unit)
        }
    }
    private class Snapshots : SnapshotRepository {
        override suspend fun list(projectId: EntityId) = Outcome.Success(emptyList<SnapshotInfo>())
        override suspend fun create(project: Project, name: String) = Outcome.Success(SnapshotInfo("snap", project.id, project.revision, name, 0))
        override suspend fun load(projectId: EntityId, snapshotId: String): Outcome<Project> = unavailable("C-SNAPSHOT")
    }
    private class Settings(initial: AppSettings) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun load() = Outcome.Success(Unit)
        override suspend fun update(settings: AppSettings): Outcome<Unit> { this.settings.value = settings; return Outcome.Success(Unit) }
    }
    private val exchange = object : NativeProjectExchange {
        override suspend fun export(project: Project): Outcome<ExportResult> = unavailable("C-NATIVE")
        override suspend fun import(bytes: ByteArray, newProjectId: EntityId): Outcome<Project> = unavailable("C-NATIVE")
    }
    /** A transport whose published state the test sets; commands only answer. */
    private class DrivenTransport : Transport {
        override val state = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun play(): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun pause(): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun setLoop(range: LoopRange?): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> = Outcome.Success(Unit)
    }
    private class Fixture(val vm: WorkspaceViewModel, val repo: Repository, val transport: DrivenTransport, val settings: Settings)

    private val store = ViewModelStore()
    @After fun tearDown() { RenderCounts.enabled = false; RenderCounts.reset(); store.clear() }

    private fun start(settings: AppSettings = AppSettings()): Fixture {
        val repo = Repository(ProjectFactory(ids).create("QA23").copy(revision = 0))
        val transport = DrivenTransport()
        val s = Settings(settings)
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, s, transport, ids)
        store.put("vm", vm)
        RenderCounts.enabled = true
        compose.setContent { RiffTheme { WorkspaceScreen(vm, onBack = {}, onExport = {}, onSettings = {}) } }
        compose.waitUntil(20000) { vm.state.value.editor != null && compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() && vm.layout.value != null }
        compose.waitForIdle()
        return Fixture(vm, repo, transport, s)
    }

    /** [count] polls of a playing transport walking through bar 1 (what EngineTransport publishes every ~33 ms). */
    private fun polls(f: Fixture, count: Int) {
        val bar = f.vm.state.value.editor!!.project.measures.first().id
        repeat(count) { k ->
            f.transport.state.value = f.transport.state.value.copy(status = TransportStatus.PLAYING,
                position = PlaybackOccurrence(ScorePosition(bar, Fraction.of((k % 32).toLong(), 32)), 0, 0), sample = SamplePosition(1600L * (k + 1), 48_000))
            compose.waitForIdle()
        }
    }
    private fun startPlaying(f: Fixture) {
        f.transport.state.value = TransportState(TransportStatus.PLAYING, reason = null)
        compose.waitForIdle()
        polls(f, 1)
        RenderCounts.reset()
    }
    private val chrome = listOf("WorkspaceScreen", "EditorScreen", "EditorHeader", "TabCard", "TrackMenu", "Controls")

    private fun topRoot(): SemanticsNode? = compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).maxByOrNull { it.id }
    private fun inTopWindow(matcher: SemanticsMatcher): List<SemanticsNode> {
        val top = topRoot()?.layoutInfo ?: return emptyList()
        return compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).filter { n -> generateSequence(n) { it.parent }.last().layoutInfo === top }
    }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun viewport(): ScoreViewport = compose.onNodeWithTag("score-canvas").fetchSemanticsNode().config.getOrNull(ScoreViewportKey)!!

    // ------------------------------------------------------------------ R1 / R2

    @Test fun r1_pollsRecomposeOnlyTheScore() {
        val f = start()
        startPlaying(f)
        polls(f, 30)
        val c = RenderCounts.snapshot()
        assertTrue("R1 sanity: the score follows the polls: $c", RenderCounts.of("ScoreArea") >= 10)
        for (name in chrome) assertEquals("R1 '$name' recomposed by transport polls: $c", 0, RenderCounts.of(name))
    }

    @Test fun r2_openToolsSheetAndTrackMenuSurvivePolls() {
        val f = start()
        startPlaying(f)
        compose.onNodeWithTag("tools-more").performClick()
        compose.waitForIdle()
        assertTrue("tools sheet open", inTopWindow(hasText("Werkzeuge")).isNotEmpty())
        RenderCounts.reset()
        polls(f, 30)
        assertEquals("R2 tools sheet recomposed by polls: ${RenderCounts.snapshot()}", 0, RenderCounts.of("MoreTools"))
        assertTrue("R2 tools sheet still open", inTopWindow(hasText("Werkzeuge")).isNotEmpty())
    }

    @Test fun r2_openTrackMenuSurvivesPolls() {
        val f = start()
        startPlaying(f)
        compose.onAllNodes(hasText("Gitarre", substring = true) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertTrue("track menu open", inTopWindow(hasText("Stimme 1", substring = true)).isNotEmpty())
        val before = inTopWindow(hasText("Stimme 1", substring = true)).first().boundsInRoot
        RenderCounts.reset()
        polls(f, 30)
        assertEquals("R2 track menu items recomposed by polls: ${RenderCounts.snapshot()}", 0, RenderCounts.of("TrackMenu.items"))
        val after = inTopWindow(hasText("Stimme 1", substring = true))
        assertTrue("R2 track menu still open", after.isNotEmpty())
        assertEquals("R2 track menu did not move", before, after.first().boundsInRoot)
    }

    @Test fun r2_allAreasSheetSurvivesPolls() {
        val f = start()
        startPlaying(f)
        compose.onNodeWithTag("nav-more").performClick()
        compose.waitForIdle()
        assertTrue("areas sheet open", inTopWindow(hasText("Alle Bereiche")).isNotEmpty())
        RenderCounts.reset()
        polls(f, 30)
        assertEquals("R2 areas sheet recomposed by polls: ${RenderCounts.snapshot()}", 0, RenderCounts.of("MorePages"))
        assertTrue("R2 areas sheet still open", inTopWindow(hasText("Alle Bereiche")).isNotEmpty())
    }

    // ------------------------------------------------------------------ N1–N3

    @Test fun n1_noticeLiesOverThePageAndClosesItself() {
        val f = start()
        val canvas = bounds("score-canvas"); val fret = bounds("fret-0")
        val text = "Testmeldung QA23"
        compose.runOnIdle { f.vm.showNotice(text) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("N1 score canvas moved or resized by the notice", canvas, bounds("score-canvas"))
        assertEquals("N1 fret pad moved by the notice", fret, bounds("fret-0"))
        // A control under the notice still takes the tap (the notice has no pointer input).
        val notice = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val up = compose.onNodeWithContentDescription("Saite höher").fetchSemanticsNode()
        assertTrue("precondition: the notice covers the bottom key row (${notice} vs ${up.boundsInRoot})", notice.overlaps(up.boundsInRoot))
        val stringBefore = f.vm.state.value.editor!!.selection.string
        val hit = notice.intersect(up.boundsInRoot).center
        compose.onRoot().performTouchInput { click(Offset(hit.x, hit.y)) }
        compose.waitUntil(5000) { f.vm.state.value.editor!!.selection.string == stringBefore + 1 }
        // Closes itself.
        compose.mainClock.advanceTimeBy(NoticeTiming.millis(text) + 1000)
        compose.waitUntil(5000) { f.vm.state.value.notice == null }
        compose.waitForIdle()
        assertTrue("N1 notice gone", compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
    }

    @Test fun n2_toolsSheetShowsTheNoticeOnceWithoutMovingTheTiles() {
        start()
        compose.onNodeWithTag("tools-more").performClick()
        compose.waitForIdle()
        val tile = inTopWindow(hasText("Let Ring") and hasClickAction()).first()
        val before = tile.boundsInRoot
        val id = tile.id
        compose.onNode(SemanticsMatcher("Let Ring tile") { it.id == id }).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5000) { inTopWindow(hasText("Zuerst eine Note", substring = true)).isNotEmpty() }
        val after = inTopWindow(hasText("Let Ring") and hasClickAction()).first().boundsInRoot
        assertEquals("N2 tiles moved by the notice", before, after)
        assertEquals("N2 the notice is shown once (sheet only, not also behind the scrim)", 1,
            compose.onAllNodesWithText("Zuerst eine Note", substring = true).fetchSemanticsNodes(atLeastOneRootRequired = false).size)
    }

    @Test fun n3_failedSaveKeepsItsNoticeWithRetry() {
        val f = start()
        f.repo.failSaves = true
        compose.onNodeWithTag("fret-3").performClick()
        compose.waitUntil(10000) { f.vm.state.value.saveStatus == SaveStatus.FAILED && f.vm.state.value.notice != null }
        compose.mainClock.advanceTimeBy(NoticeTiming.MAX_MILLIS + 2000)
        compose.waitForIdle()
        assertNotNull("N3 failed-save notice stays", f.vm.state.value.notice)
        compose.onNodeWithText("Erneut versuchen").assertExists()
        f.repo.failSaves = false
        compose.onNodeWithText("Erneut versuchen").performClick()
        compose.waitUntil(10000) { f.vm.state.value.saveStatus == SaveStatus.SAVED }
    }

    // ------------------------------------------------------------------ Z1

    private fun pinchAt(fx: Float) {
        compose.onNodeWithTag("score-canvas").performTouchInput {
            val focusX = width * fx; val focusY = height * 0.5f
            val near = minOf(width, height) * 0.08f; val far = minOf(width, height) * 0.3f
            pinch(Offset(focusX - near, focusY), Offset(focusX - far, focusY), Offset(focusX + near, focusY), Offset(focusX + far, focusY), 400)
        }
        compose.waitForIdle()
    }

    @Test fun z1_zoomGestureOnlyWithTheSetting() {
        val f = start()
        val d = compose.density.density
        val before = viewport()
        assertEquals("Z1 default factor = density × ScoreScale.EDITOR", d * ScoreScale.EDITOR, before.factor, 0.001f)
        pinchAt(0.5f)
        val off = viewport()
        assertEquals("Z1 pinch without the setting does not zoom", before.factor, off.factor, 0.0001f)
        assertEquals("Z1 ViewModel zoom unchanged", 1f, f.vm.view.value.zoom, 0.0001f)
        // Symmetric pinch: the centroid stands still, so the view does not move either.
        assertEquals("Z1 view x unchanged", before.offsetX, off.offsetX, 1f)
        assertEquals("Z1 view y unchanged", before.offsetY, off.offsetY, 1f)
        // With the setting the same pinch zooms; the ViewModel hears the final zoom when the fingers lift.
        f.settings.settings.value = f.settings.settings.value.copy(pinchZoom = true)
        compose.waitForIdle()
        pinchAt(0.5f)
        val on = viewport()
        assertTrue("Z1 pinch with the setting zooms: ${off.factor} → ${on.factor}", on.factor > off.factor * 1.2f)
        assertEquals("Z1 ViewModel zoom = drawn zoom after release", on.factor / (d * ScoreScale.EDITOR), f.vm.view.value.zoom, 0.001f)
    }
}
