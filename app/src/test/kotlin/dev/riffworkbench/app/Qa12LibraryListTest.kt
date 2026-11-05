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
 * QA cycle 12 — UNVERIFIED (Robolectric, CI only). Review of 69a6295.
 * LibraryScreen.kt:56 now shows `projects.maxByOrNull { modifiedMillis }` on the "Weiter mit" card, but LibraryScreen.kt:67
 * still lists `projects.drop(1)` (the list is sorted by title). With "Alpha" saved before "Beta", the card shows Beta,
 * the list shows only Beta, and Alpha is not shown anywhere: it can no longer be opened or deleted from the library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", application = CleanTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa12LibraryListTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun libraryVisible() = compose.onAllNodesWithTag("nav-new").fetchSemanticsNodes().isNotEmpty()
    private fun createAndLeave(title: String) {
        compose.onNodeWithTag("nav-new").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(title)
        compose.onNodeWithText("Anlegen").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(20000) { libraryVisible() }
    }

    @Test fun everyProjectStaysVisibleWhenTheNewestIsNotAlphabeticallyFirst() {
        createAndLeave("Alpha")
        Thread.sleep(1100)                          // distinct head.json mtimes even on coarse file systems
        createAndLeave("Beta")
        compose.waitForIdle()
        check(compose.onAllNodesWithText("Beta", substring = true).fetchSemanticsNodes().isNotEmpty())
        check(compose.onAllNodesWithText("Alpha", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            "project 'Alpha' is neither on the continue card nor in the list (drop(1) removes it)"
        }
    }
}
