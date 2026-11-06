package dev.riffworkbench.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.ViewportScroll
import dev.riffworkbench.ui.score.ScoreViewport
import dev.riffworkbench.ui.score.ScoreViewportKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * QA 21 (Bedienung) — UNVERIFIED (Robolectric, CI only; the same flow is JVM-verified at ViewModel level in
 * feature/workspace Qa21TabEntryViewModelTest). Fast TAB entry on a 360 × 640 phone through the real touch controls:
 * new project → 8 notes over the fret pad with auto-advance, duration and string changes, undo/redo, a chord, palm mute
 * and natural harmonic, track switch to the bass and back, pinch zoom.
 *
 * After every step: the stored project (what autosave wrote) has the expected notes, the canvas description names the
 * expected cursor, and the score viewport (semantics property [ScoreViewportKey]) shows the cursor; while the cursor stays
 * inside the old view (minus the edge margin of [ViewportScroll]) the scroll offset does not change. The pinch must keep
 * the content under the fingers (QA 21 fix in ViewportScroll.cursor).
 *
 * [feedbackForInputsWithoutEffect]: tools that cannot do anything answer visibly — a notice inside the tools sheet (paste
 * without clipboard, delete the only bar, technique without note), disabled cursor keys at the first beat and on the outer
 * strings, disabled undo without history, the loop tile lit once a loop is set, and the library's "open last project"
 * without any project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w360dp-h640dp", application = CleanTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa21TabEntryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var container: AppContainer
    private val log = mutableListOf<String>()

    @Before fun setup() { container = (compose.activity.application as RiffApplication).container }

    private fun createProject() {
        compose.onNodeWithTag("nav-new").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Schnell")
        compose.onNodeWithText("Anlegen").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun stored(): Project = runBlocking { (container.projects.open(container.projects.projects.value.single().id) as Outcome.Success<Project>).value }
    private fun guitar(p: Project) = p.tracks.first { it.instrument.kind == InstrumentKind.GUITAR }
    private fun notes(p: Project): List<String> = guitar(p).staves.first().bars.flatMapIndexed { bi, bar ->
        bar.voices.first { it.index == 0 }.beats.sortedBy { it.offset }.flatMap { b -> b.notes.sortedBy { it.string }.map { "${bi + 1}@${b.offset}:s${it.string}f${it.fret}" } }
    }
    private fun canvas(): SemanticsNode = compose.onNodeWithTag("score-canvas").fetchSemanticsNode()
    private fun description(): String = canvas().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
    /** "Partitur, Takt 2, Position 1/8, Saite 3" (EditorScreen TabCard: string 1-based). */
    private fun cursorText(bar: Int, offset: Fraction, string: Int) = "Takt $bar, Position $offset, Saite ${string + 1}"

    /** The viewport once layout (background) and the cursor effect have settled: two equal reads 40 ms apart. */
    private fun viewport(): ScoreViewport {
        var last: ScoreViewport? = null
        repeat(50) {
            compose.waitForIdle()
            val now = canvas().config.getOrNull(ScoreViewportKey) ?: error("score canvas publishes no ScoreViewport")
            if (now == last && now.cursorX != null) return now
            last = now
            Thread.sleep(40)
        }
        return last!!
    }

    /**
     * One user step. [expectNotes] (null: unchanged) is awaited in the stored project, [cursor] in the canvas description;
     * then the viewport rules are checked against the view before the step.
     */
    private fun step(label: String, expectNotes: List<String>? = null, cursor: String? = null, checkStill: Boolean = true, action: () -> Unit) {
        val before = viewport()
        val notesBefore = notes(stored())
        action()
        compose.waitForIdle()
        val want = expectNotes ?: notesBefore
        compose.waitUntil(20000) { notes(stored()) == want }
        cursor?.let { c -> compose.waitUntil(20000) { description().contains(c) } }
        val after = viewport()
        log += "$label → ${description().substringBefore(". ")} | view (${after.offsetX}, ${after.offsetY}) ×${after.factor} cursor (${after.cursorX}, ${after.cursorY})"
        val context = "after '$label'\n  " + log.takeLast(8).joinToString("\n  ")
        assertEquals("stored notes $context", want, notes(stored()))
        val cx = after.cursorX!!
        assertTrue("cursor column visible $context", cx >= after.offsetX && cx <= after.offsetX + after.width)
        after.cursorY?.let { cy -> assertTrue("cursor row visible $context", cy >= after.offsetY && cy <= after.offsetY + after.height) }
        if (checkStill && after.factor == before.factor) {
            val d = compose.density.density
            val margin = ViewportScroll.MARGIN * d
            val half = 0.62f * 24f * after.factor
            if (cx - half >= before.offsetX + margin && cx + half <= before.offsetX + before.width - margin) {
                val expected = ViewportScroll.clamp(before.offsetX, after.contentWidth, after.width.toFloat())
                assertEquals("view moved although the cursor stayed inside it $context", expected, after.offsetX, 1f)
            }
        }
    }

    private fun tapDescription(text: String) = compose.onNodeWithContentDescription(text).performClick()

    @Test fun fastTabEntry() {
        createProject()
        try {
            step("quarter") { compose.onNodeWithTag("dur-4").performClick() }
            step("string up", cursor = cursorText(1, Fraction.ZERO, 1)) { tapDescription("Saite höher") }
            val entered = mutableListOf<String>()
            listOf(0, 3, 5, 7).forEachIndexed { i, fret ->
                entered += "1@${Fraction.of(i.toLong(), 4)}:s1f$fret"
                val next = if (i < 3) cursorText(1, Fraction.of(i + 1L, 4), 1) else cursorText(2, Fraction.ZERO, 1)
                step("fret $fret", entered.toList(), next) { compose.onNodeWithTag("fret-$fret").performClick() }
            }
            assertEquals("auto-advance added bar 2", 2, stored().measures.size)
            step("eighth") { compose.onNodeWithTag("dur-8").performClick() }
            step("string up", cursor = cursorText(2, Fraction.ZERO, 2)) { tapDescription("Saite höher") }
            listOf(2, 4, 5, 7).forEachIndexed { i, fret ->
                entered += "2@${Fraction.of(i.toLong(), 8)}:s2f$fret"
                step("fret $fret", entered.toList(), cursorText(2, Fraction.of(i + 1L, 8), 2)) { compose.onNodeWithTag("fret-$fret").performClick() }
            }
            step("undo", entered.dropLast(1), cursorText(2, Fraction.of(3, 8), 2)) { tapDescription("Rückgängig") }
            step("redo", entered.toList(), cursorText(2, Fraction.of(1, 2), 2)) { tapDescription("Wiederholen") }
            // Chord: D and G string on the same beat; the cursor stays on the beat.
            step("chord mode on") { tapDescription("Akkordmodus") }
            compose.onNodeWithContentDescription("Akkordmodus").assert(isSelected())
            val chord = entered + "2@1/2:s2f5"
            step("chord D", chord, cursorText(2, Fraction.of(1, 2), 2)) { compose.onNodeWithTag("fret-5").performClick() }
            step("string up", cursor = cursorText(2, Fraction.of(1, 2), 3)) { tapDescription("Saite höher") }
            val chord2 = chord + "2@1/2:s3f5"
            step("chord G", chord2, cursorText(2, Fraction.of(1, 2), 3)) { compose.onNodeWithTag("fret-5").performClick() }
            step("chord mode off") { tapDescription("Akkordmodus") }
            // Back to note 1 with the cursor keys (12 eighth steps), down to the A string.
            repeat(12) { step("previous beat ${it + 1}") { tapDescription("Vorheriger Beat") } }
            repeat(2) { step("string down") { tapDescription("Saite tiefer") } }
            compose.waitUntil(20000) { description().contains(cursorText(1, Fraction.ZERO, 1)) }
            compose.onNodeWithContentDescription("Vorheriger Beat").assertIsNotEnabled()
            // Palm mute on the selected note: stored, and the tile shows it.
            step("palm mute") { tapDescription("Palm-Mute") }
            compose.waitUntil(20000) { guitar(stored()).staves.first().bars.first().voices.first().beats.first { it.offset == Fraction.ZERO }.notes.first { it.string == 1 }.articulations.contains(Articulation.Mark(NoteMark.PALM_MUTE)) }
            compose.onNodeWithContentDescription("Palm-Mute").assert(isSelected())
            // Natural harmonic from the tools sheet.
            compose.onNodeWithTag("tools-more").performClick()
            compose.waitForIdle()
            compose.onAllNodes(hasText("N.H.") and hasClickAction()).onFirst().performScrollTo().performClick()
            compose.waitUntil(20000) { guitar(stored()).staves.first().bars.first().voices.first().beats.first { it.offset == Fraction.ZERO }.notes.first { it.string == 1 }.articulations.any { it is Articulation.Harmonic && it.kind == HarmonicKind.NATURAL } }
            closeSheet()
            // Track switch to the bass and back: bar, beat and string stay.
            step("track menu bass", cursor = cursorText(1, Fraction.ZERO, 1)) {
                compose.onAllNodes(hasText("Gitarre", substring = true) and hasClickAction()).onFirst().performClick()
                compose.waitForIdle()
                compose.onAllNodes(hasText("Bass") and hasClickAction()).onFirst().performClick()
            }
            step("track menu guitar", cursor = cursorText(1, Fraction.ZERO, 1)) {
                compose.onAllNodes(hasText("Bass", substring = true) and hasClickAction()).onFirst().performClick()
                compose.waitForIdle()
                compose.onAllNodes(hasText("Gitarre") and hasClickAction()).onFirst().performClick()
            }
            // Pinch zoom at the right side of the score, away from the cursor at bar 1: the content under the fingers stays.
            val canvasNode = compose.onNodeWithTag("score-canvas")
            var focusX = 0f; var focusY = 0f
            fun pinchRight() = canvasNode.performTouchInput {
                focusX = width * 0.85f; focusY = height * 0.5f
                val near = minOf(width, height) * 0.08f; val far = minOf(width, height) * 0.3f
                pinch(Offset(focusX - near, focusY), Offset(focusX - far, focusY), Offset(focusX + near, focusY), Offset(focusX + far, focusY), 400)
            }
            // QA 23: pinch zoom is off by default (fixed, readable scale): the same pinch neither zooms nor moves the view
            // (symmetric fingers, the centroid stands still) ...
            val unzoomed = viewport()
            pinchRight()
            val stillUnzoomed = viewport()
            log += "pinch with zoom off → view (${stillUnzoomed.offsetX}, ${stillUnzoomed.offsetY}) ×${stillUnzoomed.factor}"
            assertEquals("zoom off: the pinch does not zoom", unzoomed.factor, stillUnzoomed.factor, 0.0001f)
            assertEquals("zoom off: the view does not move (x)", unzoomed.offsetX, stillUnzoomed.offsetX, 1f)
            assertEquals("zoom off: the view does not move (y)", unzoomed.offsetY, stillUnzoomed.offsetY, 1f)
            // ... and with the setting "Zoomen per Geste" it zooms around the fingers as before.
            runBlocking { container.settings.update(container.settings.settings.value.copy(pinchZoom = true)) }
            compose.waitForIdle()
            val before = viewport()
            pinchRight()
            val after = viewport()
            log += "pinch → view (${after.offsetX}, ${after.offsetY}) ×${after.factor}"
            assertTrue("pinch zoomed in: ${before.factor} → ${after.factor}", after.factor > before.factor * 1.2f)
            val contentBefore = (before.offsetX + focusX) / before.factor
            val contentAfter = (after.offsetX + focusX) / after.factor
            // Clamping at the content edges may shift it; otherwise the focus point keeps its place (within a few dp of slop).
            if (after.offsetX > 0f && after.offsetX < after.contentWidth - after.width)
                assertEquals("content under the fingers after the pinch\n  " + log.takeLast(6).joinToString("\n  "), contentBefore, contentAfter, 12f * compose.density.density / after.factor)
            println("QA21 flow:\n  " + log.joinToString("\n  "))
        } catch (t: Throwable) {
            throw AssertionError("QA21 fast TAB entry failed; last steps:\n  " + log.takeLast(12).joinToString("\n  "), t)
        }
    }

    private fun topRoot(): SemanticsNode? = compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).maxByOrNull { it.id }
    private fun inTopWindow(matcher: SemanticsMatcher): List<SemanticsNode> {
        val top = topRoot()?.layoutInfo ?: return emptyList()
        return compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).filter { n -> generateSequence(n) { it.parent }.last().layoutInfo === top }
    }
    private fun closeSheet() {
        val dismiss = inTopWindow(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).firstOrNull()
        if (dismiss != null) {
            val id = dismiss.id; val bounds = dismiss.boundsInRoot
            compose.onNode(SemanticsMatcher("dismiss $id") { it.id == id && it.boundsInRoot == bounds }).performSemanticsAction(SemanticsActions.Dismiss)
        } else {
            val window = topRoot()?.boundsInRoot ?: return
            inTopWindow(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).firstOrNull { it.boundsInRoot.width * it.boundsInRoot.height >= 0.85f * window.width * window.height }?.let { n ->
                val id = n.id; val bounds = n.boundsInRoot
                compose.onNode(SemanticsMatcher("scrim $id") { it.id == id && it.boundsInRoot == bounds }).performSemanticsAction(SemanticsActions.OnClick)
            }
        }
        compose.waitForIdle()
    }
    private fun tool(label: String) = compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performScrollTo().performClick()
    private fun sheetShows(text: String): Boolean = inTopWindow(hasText(text, substring = true)).isNotEmpty()

    @Test fun feedbackForInputsWithoutEffect() {
        // Library without projects: "open last project" says why nothing opens.
        compose.onNodeWithContentDescription("Letztes Projekt öffnen").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Noch kein Projekt", substring = true).fetchSemanticsNodes().isNotEmpty() }
        createProject()
        // Cursor keys that would do nothing are disabled; so is undo without history.
        compose.onNodeWithContentDescription("Vorheriger Beat").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Saite tiefer").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Saite höher").assertIsEnabled()
        compose.onNodeWithContentDescription("Rückgängig").assertIsNotEnabled()
        repeat(5) { tapDescription("Saite höher"); compose.waitForIdle() }
        compose.onNodeWithContentDescription("Saite höher").assertIsNotEnabled()
        // Tools sheet: each refusal is shown inside the sheet (the page's notice row lies behind its scrim).
        compose.onNodeWithTag("tools-more").performClick()
        compose.waitForIdle()
        tool("Einfügen")
        compose.waitUntil(20000) { sheetShows("Zwischenablage ist leer") }
        tool("Takt löschen")
        compose.waitUntil(20000) { sheetShows("Mindestens ein Takt") }
        assertEquals("the only bar stays", 1, stored().measures.size)
        tool("Let Ring")
        compose.waitUntil(20000) { sheetShows("Zuerst eine Note wählen") }
        // Loop: the editor does not draw the loop, the tile shows it is set.
        tool("Loop")
        compose.waitUntil(20000) { inTopWindow(hasText("Loop") and hasClickAction() and isSelected()).isNotEmpty() }
        closeSheet()
    }
}
