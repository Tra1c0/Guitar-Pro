package dev.riffworkbench.data.local

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.AudioRoute
import dev.riffworkbench.ports.AudioRouteKind
import dev.riffworkbench.ports.LatencyProfiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Zyklus 26: per-device latency profiles persist; files without the field (Zyklus 25 and older) read as no profiles and
 * keep their tap latency as the device-less fallback (not attributed to any device); keys of unknown kinds survive.
 */
class Qa26SettingsLatencyProfilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val buds = AudioRoute(AudioRouteKind.BLUETOOTH_A2DP, "Galaxy Buds2")
    private val speaker = AudioRoute(AudioRouteKind.SPEAKER, "Pixel 8")

    @Test fun profilesPersistAndRoundTrip() = runBlocking {
        val file = temp.newFile(); file.delete()
        val s = AppSettings(pinchZoom = true, tapLatencyMs = 12.0, latencyProfiles = mapOf(buds.key to LatencyProfile(212.5, 1_700_000_000_000L), speaker.key to LatencyProfile(41.0, 5L)))
        assertTrue(FileSettingsRepository(file).update(s) is Outcome.Success)
        val restarted = FileSettingsRepository(file)
        assertTrue(restarted.load() is Outcome.Success)
        assertEquals(s, restarted.settings.value)
    }

    @Test fun zyklus25FileReadsWithoutProfilesAndKeepsItsValueAsFallback() = runBlocking {
        val file = temp.newFile()
        // As written by Zyklus 25 (encodeDefaults = true, no latencyProfiles).
        file.writeText("""{ "darkTheme": true, "autoAdvance": true, "strictRhythm": false, "retuningPolicy": "KEEP_SOUNDING_PITCH",
            "favoriteEffects": ["palm_mute"], "shortcuts": {}, "pinchZoom": false, "tapLatencyMs": 64.0 }""")
        val old = FileSettingsRepository(file)
        assertTrue(old.load() is Outcome.Success)
        val s = old.settings.value
        assertTrue(s.latencyProfiles.isEmpty())
        assertEquals(64.0, s.tapLatencyMs, 0.0)
        // Fallback for every device, attributed to none: no hint suppressed, no playhead correction.
        assertEquals(64.0, LatencyProfiles.tapLatencyMs(s, buds), 0.0)
        assertEquals(64.0, LatencyProfiles.tapLatencyMs(s, speaker), 0.0)
        assertTrue(LatencyProfiles.needsCalibrationHint(s, buds))
        assertEquals(0.0, LatencyProfiles.playheadDelayMs(s, buds), 0.0)
        // Calibrating a device later adds its profile and keeps the fallback for the others.
        assertTrue(old.update(LatencyProfiles.calibrated(s, buds, 190.0, 7L)) is Outcome.Success)
        val again = FileSettingsRepository(file).also { assertTrue(it.load() is Outcome.Success) }.settings.value
        assertEquals(190.0, LatencyProfiles.tapLatencyMs(again, buds), 0.0)
        assertEquals(64.0, LatencyProfiles.tapLatencyMs(again, speaker), 0.0)
    }

    @Test fun olderFilesAndUnknownKindsAreTolerated() = runBlocking {
        val file = temp.newFile()
        file.writeText("""{ "darkTheme": false }""")
        val oldest = FileSettingsRepository(file).also { assertTrue(it.load() is Outcome.Success) }.settings.value
        assertTrue(oldest.latencyProfiles.isEmpty()); assertEquals(0.0, oldest.tapLatencyMs, 0.0)
        // A newer version's device class: kept as data, ignored as reference.
        file.writeText("""{ "latencyProfiles": { "SATELLITE|X": { "tapLatencyMs": 300.0 }, "SPEAKER|Pixel 8": { "tapLatencyMs": 40.0, "calibratedAtEpochMs": 3 } } }""")
        val newer = FileSettingsRepository(file).also { assertTrue(it.load() is Outcome.Success) }.settings.value
        assertEquals(LatencyProfile(300.0, 0L), newer.latencyProfiles["SATELLITE|X"])
        assertEquals(40.0, LatencyProfiles.referenceMs(newer)!!, 0.0)
    }
}
