package dev.riffworkbench.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.riffworkbench.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", application = CleanTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ApplicationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var container: AppContainer
    @Before fun setup() {
        container = (compose.activity.application as RiffApplication).container
    }
    private fun createProject() {
        compose.onNodeWithTag("nav-new").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Drop A Test")
        compose.onNodeWithText("Anlegen").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun storedProject(): Project = runBlocking { (container.projects.open(container.projects.projects.value.single().id) as Outcome.Success).value }
    private fun allNotes(p: Project) = p.tracks.flatMap { it.staves }.flatMap { it.bars }.flatMap { it.voices }.flatMap { it.beats }.flatMap { it.notes }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File("build/reports/screenshots/$name.png"); file.parentFile!!.mkdirs()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun createEnterHighFretUndoRedoAndPersist() {
        createProject()
        compose.onNodeWithTag("fret-range").performClick()
        compose.onNodeWithTag("fret-12").performClick()
        compose.waitUntil(20000) { container.projects.projects.value.singleOrNull()?.revision == 1L }
        assertEquals(12, allNotes(storedProject()).single().fret)
        compose.onNodeWithContentDescription("Rückgängig").performClick()
        compose.waitUntil(20000) { container.projects.projects.value.single().revision == 2L }
        assertTrue(allNotes(storedProject()).isEmpty())
        compose.onNodeWithContentDescription("Wiederholen").performClick()
        compose.waitUntil(20000) { container.projects.projects.value.single().revision == 3L }
        assertEquals(12, allNotes(storedProject()).single().fret)
        screenshot("editor-phone")
    }
    @Test fun libraryRenders() { compose.onNodeWithText("Projekte").assertExists(); screenshot("library-phone") }
    @Test fun allWorkspaceAreasAndFeatureSearchRemainReachable() {
        createProject()
        compose.onNodeWithTag("nav-practice").performClick()
        compose.onAllNodesWithText("Tempo", substring = true).onFirst().assertExists()
        listOf("arrangement" to "Arrangement", "drums" to "Drumgrid", "audio" to "Audio & Stems", "mixer" to "Mixer & FX", "instruments" to "Instrumente", "effects" to "Artikulationen",
            "layout" to "Layout", "exchange" to "Austausch", "history" to "Historie", "integrations" to "Geräte & Integrationen", "status" to "Funktionsstand").forEach { (route, title) ->
            compose.onNodeWithTag("nav-more").performClick()
            compose.onNodeWithTag("page-$route").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onAllNodesWithText(title).onFirst().assertExists()
        }
        compose.onNodeWithTag("nav-editor").performClick()
        compose.onAllNodesWithTag("fret-0").onFirst().assertExists()
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("page-status").performScrollTo().performClick()
        compose.onNodeWithText("Funktionen suchen").performTextInput("fx.bend")
        compose.onNodeWithText("Bend, Prebend und Release").assertExists()
        screenshot("status-phone")
    }
    @Test fun drumGridSharesPersistedScoreAndKeepsSimultaneousElements() {
        createProject()
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("page-drums").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Kick, 1, off").performClick()
        compose.waitUntil(20000) { container.projects.projects.value.single().revision == 1L }
        compose.onNodeWithContentDescription("Closed HH, 1, off").performClick()
        compose.waitUntil(20000) { container.projects.projects.value.single().revision == 2L }
        assertEquals(setOf(36, 42), allNotes(storedProject()).map { it.pitch.midiNumber }.toSet())
        compose.onNodeWithContentDescription("Kick, 1, on").performClick()
        compose.waitUntil(20000) { container.projects.projects.value.single().revision == 3L }
        assertEquals(listOf(42), allNotes(storedProject()).map { it.pitch.midiNumber })
        screenshot("drums-phone")
    }
    @Test fun settingsPersistAcrossRepositoryRestart() {
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.waitUntil(20000) { !container.settings.settings.value.darkTheme }
        runBlocking {
            val restarted = dev.riffworkbench.data.local.FileSettingsRepository(File(RuntimeEnvironment.getApplication().filesDir, "workbench/settings.json"))
            assertTrue(restarted.load() is Outcome.Success)
            assertFalse(restarted.settings.value.darkTheme)
        }
        screenshot("settings-light")
    }
    /** Rotation/theme change recreates the activity: the open project, its edits and the editor must survive (QA cycle 6). */
    @Test fun activityRecreationKeepsTheOpenProjectAndItsEdits() {
        createProject()
        compose.onNodeWithTag("fret-5").performClick()
        compose.waitUntil(20000) { container.projects.projects.value.singleOrNull()?.revision == 1L }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Rückgängig").assertIsEnabled()
        assertEquals(5, allNotes(storedProject()).single().fret)
    }

    @Test fun audioDiagnosticsPageIsReachable() {
        createProject()
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("page-diagnostics").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Audio-Diagnose").onFirst().assertExists()
        compose.onNodeWithText("Blindvergleich").performScrollTo().assertExists()
        screenshot("diagnostics-phone")
    }

    @Test @Config(sdk = [35], qualifiers = "de-rDE-w1280dp-h800dp") fun tabletWorkspaceRendersWithNavigationRail() {
        createProject()
        compose.onNodeWithText("Funktionsstand").assertExists()
        screenshot("editor-tablet")
    }
}

class CleanTestApplication : RiffApplication() {
    override fun onCreate() { File(filesDir, "workbench").deleteRecursively(); super.onCreate() }
}
