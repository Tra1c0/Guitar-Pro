package dev.riffworkbench.app

import androidx.compose.ui.semantics.SemanticsProperties
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
 * QA cycle 12 (accessibility / localisation), Robolectric — runs on CI only, NOT verified locally (no Android SDK here).
 * Each test pins a finding of cycle 12 that was fixed (selected state, switch labels, live region, singular counts).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AccessibilityQaTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun createProject(create: String) {
        compose.onNodeWithTag("nav-new").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("A11y")
        compose.onNodeWithText(create).performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** Selection/mode state of ChoiceTile is colour only: no Selected / ToggleableState for TalkBack. */
    @Test @Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", application = CleanTestApplication::class)
    fun durationAndChordModeExposeTheirState() {
        createProject("Anlegen")
        compose.onNodeWithTag("dur-8").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("dur-8").assert(isSelected() or isOn())
        compose.onNodeWithContentDescription("Akkordmodus").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Akkordmodus").assert(isSelected() or isOn())
    }

    /** ToggleRow: Text and Switch are siblings, so the switch node TalkBack focuses has no name. */
    @Test @Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", application = CleanTestApplication::class)
    fun settingsSwitchCarriesItsLabel() {
        compose.onNodeWithTag("nav-settings").performClick()
        compose.waitForIdle()
        compose.onNode(isToggleable() and hasText("Dunkles Design")).assertExists()
    }

    /** Save status (autosave is the only save path on touch) changes silently: no live region. */
    @Test @Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", application = CleanTestApplication::class)
    fun saveStatusIsALiveRegion() {
        createProject("Anlegen")
        compose.onNodeWithTag("fret-5").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Lokal gespeichert").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Lokal gespeichert").onFirst().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    /** English library header: "1 projects · offline" — plurals are concatenated, no <plurals> resources. */
    @Test @Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp", application = CleanTestApplication::class)
    fun englishLibraryCountUsesSingular() {
        createProject("Create")
        compose.onNodeWithContentDescription("Back: Projects").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("· offline", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("1 projects", substring = true).assertCountEquals(0)
    }
}
