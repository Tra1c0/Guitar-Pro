package dev.riffworkbench.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.getOrNull
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.LayoutRequest
import dev.riffworkbench.notation.LayoutStyle
import dev.riffworkbench.notation.ScoreLayouter
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
 * QA 18 / D4 — UNVERIFIED (Robolectric, CI only). The signature/tempo sheet of the editor: opened by a long press on the
 * drawn time signature or by the bar info in the header; a preset changes the bar and the header follows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", application = CleanTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa18SignatureSheetTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var container: AppContainer
    @Before fun setup() { container = (compose.activity.application as RiffApplication).container }

    private fun createProject() {
        compose.onNodeWithTag("nav-new").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Taktart")
        compose.onNodeWithText("Anlegen").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun storedProject(): Project = runBlocking { (container.projects.open(container.projects.projects.value.single().id) as Outcome.Success).value }
    private fun headerShows(text: String) = compose.onAllNodesWithTag("bar-info").fetchSemanticsNodes().any { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.any { it.contains(text) } == true }

    private fun changeToThreeFourAndCheck() {
        compose.onNodeWithTag("signature-sheet").assertExists()
        compose.onNodeWithTag("sig-preset-3-4").performClick()
        compose.waitUntil(20000) { storedProject().measures.first().signature == TimeSignature(3, 4) }
        compose.onNodeWithTag("signature-done").performClick()
        compose.waitUntil(20000) { headerShows("Takt 1 · 3/4") }
        assertTrue(headerShows("Takt 1 · 3/4"))
    }

    /** Accessible route: the bar info in the header opens the sheet for the selected bar. */
    @Test fun headerBarInfoOpensTheSheetAndAPresetChangesTheSignature() {
        createProject()
        assertTrue(headerShows("Takt 1 · 4/4"))
        compose.onNodeWithTag("bar-info").performClick()
        compose.waitForIdle()
        changeToThreeFourAndCheck()
    }

    /** Touch route: a long press on the drawn 4/4 of bar 1 opens the same sheet. */
    @Test fun longPressOnTheDrawnSignatureOpensTheSheet() {
        createProject()
        // The editor's layout for a fresh project: TAB only, 16th entry grid, unwrapped, editor margin 6 (WorkspaceViewModel.relayout).
        val p = storedProject()
        val layout = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT.copy(showTab = true, showStandard = false), null, WrittenDuration().length(), 0, null, margin = 6f))
        val box = layout.signatures.first { it.measureId == p.measures.first().id }
        val d = compose.density.density
        compose.waitUntil(20000) { compose.onAllNodesWithTag("score-canvas").fetchSemanticsNodes().isNotEmpty() }
        // Cursor at bar 1, zoom 1 and no scroll yet: canvas pixels = layout units × density.
        compose.onNodeWithTag("score-canvas").performTouchInput { longClick(Offset((box.x + box.w / 2) * d, (box.y + box.h / 2) * d)) }
        compose.waitUntil(20000) { compose.onAllNodesWithTag("signature-sheet").fetchSemanticsNodes().isNotEmpty() }
        changeToThreeFourAndCheck()
    }
}
