package dev.riffworkbench.data.local

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.Updates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Zyklus 27: the update switch and the time of the last automatic check persist; files of older versions (without the
 * fields) read as „switched on, never checked“, so the first start after the update checks once.
 */
class Qa27SettingsUpdateChecksTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun switchAndLastCheckRoundTrip() = runBlocking {
        val file = temp.newFile(); file.delete()
        val s = AppSettings(tapLatencyMs = 20.0, updateChecks = false, lastUpdateCheckEpochMs = 1_800_000_000_000L)
        assertTrue(FileSettingsRepository(file).update(s) is Outcome.Success)
        val restarted = FileSettingsRepository(file).also { assertTrue(it.load() is Outcome.Success) }
        assertEquals(s, restarted.settings.value)
    }

    @Test fun zyklus26FileReadsAsSwitchedOnAndNeverChecked() = runBlocking {
        val file = temp.newFile()
        // As written by Zyklus 26 (encodeDefaults = true, no update fields).
        file.writeText("""{ "darkTheme": true, "autoAdvance": true, "strictRhythm": false, "retuningPolicy": "KEEP_SOUNDING_PITCH",
            "favoriteEffects": ["palm_mute"], "shortcuts": {}, "pinchZoom": true, "tapLatencyMs": 64.0,
            "latencyProfiles": { "SPEAKER|Pixel 8": { "tapLatencyMs": 40.0, "calibratedAtEpochMs": 3 } } }""")
        val s = FileSettingsRepository(file).also { assertTrue(it.load() is Outcome.Success) }.settings.value
        assertTrue(s.updateChecks)
        assertEquals(0L, s.lastUpdateCheckEpochMs)
        assertTrue(s.pinchZoom); assertEquals(64.0, s.tapLatencyMs, 0.0); assertEquals(1, s.latencyProfiles.size)
        assertTrue(Updates.dueForAutomaticCheck(s, 1_800_000_000_000L))
        val oldest = FileSettingsRepository(temp.newFile().apply { writeText("""{ "darkTheme": false }""") }).also { assertTrue(it.load() is Outcome.Success) }.settings.value
        assertTrue(oldest.updateChecks)
    }
}
