package dev.riffworkbench.data.local

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Zyklus 25: the calibrated tap latency (AppSettings.tapLatencyMs) persists, defaults to 0 and reads as 0 from older files. */
class Qa25SettingsTapLatencyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun tapLatencyPersistsAndOlderFilesReadAsZero() = runBlocking {
        assertEquals(0.0, AppSettings().tapLatencyMs, 0.0)
        val file = temp.newFile(); file.delete()
        assertTrue(FileSettingsRepository(file).update(AppSettings(tapLatencyMs = 37.5, pinchZoom = true)) is Outcome.Success)
        val restarted = FileSettingsRepository(file)
        assertTrue(restarted.load() is Outcome.Success)
        assertEquals(37.5, restarted.settings.value.tapLatencyMs, 0.0)
        assertTrue(restarted.settings.value.pinchZoom)
        file.writeText("""{ "darkTheme": false, "autoAdvance": true, "pinchZoom": true }""")
        val old = FileSettingsRepository(file)
        assertTrue(old.load() is Outcome.Success)
        assertEquals("older file: no latency", 0.0, old.settings.value.tapLatencyMs, 0.0)
        assertTrue(old.settings.value.pinchZoom)
    }
}
