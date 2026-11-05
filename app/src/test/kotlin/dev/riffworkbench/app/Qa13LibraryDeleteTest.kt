package dev.riffworkbench.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * QA 13 — UNVERIFIED (Robolectric, CI only; not run locally). Review of 0e2ddda, LibraryScreen.kt:69.
 * The list now shows every project except the one on the "Weiter mit" card. ContinueCard (LibraryScreen.kt:122) has no
 * delete action, so the most recently saved project — and a sole project — has no delete button anywhere in the library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", application = CleanTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa13LibraryDeleteTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun theMostRecentProjectCanBeDeletedFromTheLibrary() {
        compose.onNodeWithTag("nav-new").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Solo")
        compose.onNodeWithText("Anlegen").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(20000) { compose.onAllNodesWithTag("nav-new").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        check(compose.onAllNodesWithContentDescription("Solo löschen").fetchSemanticsNodes().isNotEmpty()) {
            "no delete action for the project on the continue card"
        }
    }
}
