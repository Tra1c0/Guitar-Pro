package dev.riffworkbench.data.local

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** QA 23: the new setting "Zoomen per Geste" (AppSettings.pinchZoom) persists, defaults to off and reads as off from older files. */
class Qa23SettingsPinchZoomTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun pinchZoomPersistsAndOlderFilesReadAsOff() = runBlocking {
        assertFalse("default off", AppSettings().pinchZoom)
        val file = temp.newFile(); file.delete()
        assertTrue(FileSettingsRepository(file).update(AppSettings(pinchZoom = true)) is Outcome.Success)
        val restarted = FileSettingsRepository(file)
        assertTrue(restarted.load() is Outcome.Success)
        assertTrue("persisted", restarted.settings.value.pinchZoom)
        // A settings file written before cycle 23 (no key): loads, keeps its values, pinch zoom off.
        file.writeText("""{ "darkTheme": false, "autoAdvance": true, "strictRhythm": false, "retuningPolicy": "KEEP_SOUNDING_PITCH", "favoriteEffects": ["bend"], "shortcuts": {} }""")
        val old = FileSettingsRepository(file)
        assertTrue(old.load() is Outcome.Success)
        assertFalse(old.settings.value.darkTheme)
        assertFalse("older file: pinch zoom off", old.settings.value.pinchZoom)
    }
}
