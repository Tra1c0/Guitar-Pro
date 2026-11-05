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
 * QA cycle 11 — UNVERIFIED (Robolectric, CI only). Lifecycle of the project route in MainActivity.
 * - leave() is launched per back press and pops unconditionally after flush: two presses pop twice, the second
 *   pop removes the library (start destination) and leaves an empty NavHost.
 * - nav.navigate(project) has no launchSingleTop / dropUnlessResumed guard: two taps on a library card before the
 *   transition ends push two project/{id} entries (two sessions on one project).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", application = CleanTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LifecycleQaNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val title = "Lifecycle QA"

    private fun createProject() {
        compose.onNodeWithTag("nav-new").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(title)
        compose.onNodeWithText("Anlegen").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun libraryVisible() = compose.onAllNodesWithTag("nav-new").fetchSemanticsNodes().isNotEmpty()

    @Test fun doubleBackFromAProjectReturnsToTheLibrary() {
        createProject()
        // Two back presses before the first leave() coroutine ran (double tap on the system back / top-bar close).
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isEmpty() }
        runCatching { compose.waitUntil(10000) { libraryVisible() } }
        check(libraryVisible()) { "second back press popped the library: empty screen" }
    }

    @Test fun doubleTapOnAProjectOpensItOnce() {
        createProject()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(20000) { libraryVisible() }
        compose.mainClock.autoAdvance = false
        val card = compose.onAllNodes(hasText(title) and hasClickAction(), useUnmergedTree = false)[0]
        card.performClick()
        card.performClick()                         // second tap during the transition
        compose.mainClock.autoAdvance = true
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        runCatching { compose.waitUntil(10000) { libraryVisible() } }
        check(libraryVisible()) { "one back press after opening a project must return to the library (a second copy was on the stack)" }
    }
}
