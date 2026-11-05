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
 * QA cycle 10: the main flow under device configurations — English, small phone, large font, landscape, dark theme.
 * Each run creates a project, enters a note, and reaches the practice page and the "more" sheet; core controls must be
 * on screen (displayed), not merely present in the tree.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConfigurationMatrixTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun mainFlow(create: String) {
        compose.onNodeWithTag("nav-new").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Matrix")
        compose.onNodeWithText(create).assertIsDisplayed().performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("fret-0").assertIsDisplayed()
        compose.onNodeWithTag("fret-5").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("nav-practice").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("nav-more").assertIsDisplayed().performClick()
        compose.onNodeWithTag("page-diagnostics").performScrollTo().assertIsDisplayed()
    }

    @Test @Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp", application = CleanTestApplication::class)
    fun englishPhone() = mainFlow("Create")

    @Test @Config(sdk = [35], qualifiers = "de-rDE-w320dp-h568dp", application = CleanTestApplication::class)
    fun smallPhone() = mainFlow("Anlegen")

    @Test @Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", fontScale = 2.0f, application = CleanTestApplication::class)
    fun largeFont() = mainFlow("Anlegen")

    @Test @Config(sdk = [35], qualifiers = "de-rDE-w891dp-h411dp-land", application = CleanTestApplication::class)
    fun landscapePhone() = mainFlow("Anlegen")

    @Test @Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp-night", application = CleanTestApplication::class)
    fun darkTheme() = mainFlow("Anlegen")
}
