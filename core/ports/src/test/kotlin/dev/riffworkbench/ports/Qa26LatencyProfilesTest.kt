package dev.riffworkbench.ports

import dev.riffworkbench.model.AppSettings
import dev.riffworkbench.model.LatencyProfile
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 26: per-device tap latency. Keys (kind class + product name), the fallback to the device-less Zyklus-25 value,
 * calibrate/reset/remove, the hint condition and the playhead rule max(0, tap(device) − tap(speaker/wired)), only with both.
 */
class Qa26LatencyProfilesTest {
    private val speaker = AudioRoute(AudioRouteKind.SPEAKER, "Pixel 8")
    private val wired = AudioRoute(AudioRouteKind.WIRED, "USB-C Kopfhörer")
    private val buds = AudioRoute(AudioRouteKind.BLUETOOTH_A2DP, "Galaxy Buds2")
    private val ble = AudioRoute(AudioRouteKind.BLE, "LE-Audio Headset")

    @Test fun keysAreKindPlusProductNameAndRoundTrip() {
        assertEquals("BLUETOOTH_A2DP|Galaxy Buds2", buds.key)
        assertEquals(buds.key, AudioRoute(AudioRouteKind.BLUETOOTH_A2DP, "  Galaxy Buds2 ").key)
        assertNotEquals(buds.key, AudioRoute(AudioRouteKind.BLE, "Galaxy Buds2").key)
        for (r in listOf(speaker, wired, buds, ble, AudioRoute(AudioRouteKind.OTHER, "a|b"))) assertEquals(r, AudioRoute.parse(r.key))
        assertNull("unknown kind (newer version)", AudioRoute.parse("SATELLITE|x"))
        assertNull(AudioRoute.parse("no separator"))
        assertEquals("Galaxy Buds2 (Bluetooth)", buds.label)
        assertEquals("Lautsprecher", AudioRoute(AudioRouteKind.SPEAKER, " ").label)
    }

    @Test fun activeDeviceSelectsItsProfileElseTheDeviceLessFallback() {
        val s = AppSettings(tapLatencyMs = 35.0, latencyProfiles = mapOf(buds.key to LatencyProfile(210.0, 1L), speaker.key to LatencyProfile(40.0, 2L)))
        assertEquals(210.0, LatencyProfiles.tapLatencyMs(s, buds), 0.0)
        assertEquals(40.0, LatencyProfiles.tapLatencyMs(s, speaker), 0.0)
        assertEquals("uncalibrated device: Zyklus-25 value", 35.0, LatencyProfiles.tapLatencyMs(s, ble), 0.0)
        assertEquals("unknown device: Zyklus-25 value", 35.0, LatencyProfiles.tapLatencyMs(s, null), 0.0)
    }

    @Test fun calibrateResetAndRemove() {
        var s = AppSettings(tapLatencyMs = 35.0)
        s = LatencyProfiles.calibrated(s, buds, 190.0, 1234L)
        assertEquals(LatencyProfile(190.0, 1234L), s.latencyProfiles[buds.key])
        assertEquals("device-less value untouched", 35.0, s.tapLatencyMs, 0.0)
        s = LatencyProfiles.calibrated(s, null, 50.0, 99L)
        assertEquals("without a device: the device-less value", 50.0, s.tapLatencyMs, 0.0)
        assertEquals(1, s.latencyProfiles.size)
        s = LatencyProfiles.calibrated(s, speaker, 45.0, 5L)
        s = LatencyProfiles.reset(s, buds)
        assertNull(s.latencyProfiles[buds.key])
        assertEquals("reset: fallback applies again", 50.0, LatencyProfiles.tapLatencyMs(s, buds), 0.0)
        s = LatencyProfiles.remove(s, speaker.key)
        assertTrue(s.latencyProfiles.isEmpty())
        assertEquals(0.0, LatencyProfiles.reset(s, null).tapLatencyMs, 0.0)
    }

    @Test fun hintOnlyForUncalibratedRadioLinks() {
        val s = AppSettings(tapLatencyMs = 80.0, latencyProfiles = mapOf(buds.key to LatencyProfile(200.0)))
        assertFalse("calibrated", LatencyProfiles.needsCalibrationHint(s, buds))
        assertTrue("BLE without profile (the legacy value does not count)", LatencyProfiles.needsCalibrationHint(s, ble))
        assertTrue(LatencyProfiles.needsCalibrationHint(s, AudioRoute(AudioRouteKind.HEARING_AID, "Phonak")))
        assertFalse(LatencyProfiles.needsCalibrationHint(s, speaker))
        assertFalse(LatencyProfiles.needsCalibrationHint(s, wired))
        assertFalse(LatencyProfiles.needsCalibrationHint(s, AudioRoute(AudioRouteKind.USB, "DAC")))
        assertFalse(LatencyProfiles.needsCalibrationHint(s, null))
        val memory = RouteNoticeMemory()
        assertTrue(memory.firstTime(ble.key)); assertFalse(memory.firstTime(ble.key)); assertTrue(memory.firstTime(buds.key))
    }

    @Test fun playheadDelayIsTheDifferenceToTheReferenceOnlyWhenBothAreCalibrated() {
        val none = AppSettings(tapLatencyMs = 40.0)
        assertEquals("nothing calibrated", 0.0, LatencyProfiles.playheadDelayMs(none, buds), 0.0)
        val onlyBuds = AppSettings(tapLatencyMs = 40.0, latencyProfiles = mapOf(buds.key to LatencyProfile(230.0)))
        assertEquals("no reference (the legacy value is none)", 0.0, LatencyProfiles.playheadDelayMs(onlyBuds, buds), 0.0)
        val onlySpeaker = AppSettings(latencyProfiles = mapOf(speaker.key to LatencyProfile(45.0)))
        assertEquals("current device not calibrated", 0.0, LatencyProfiles.playheadDelayMs(onlySpeaker, buds), 0.0)
        val both = AppSettings(latencyProfiles = mapOf(speaker.key to LatencyProfile(45.0), buds.key to LatencyProfile(230.0), wired.key to LatencyProfile(30.0)))
        assertEquals("reference = lowest of speaker/wired", 200.0, LatencyProfiles.playheadDelayMs(both, buds), 1e-9)
        assertEquals(15.0, LatencyProfiles.playheadDelayMs(both, speaker), 1e-9)
        assertEquals(0.0, LatencyProfiles.playheadDelayMs(both, wired), 0.0)
        assertEquals("unknown device", 0.0, LatencyProfiles.playheadDelayMs(both, null), 0.0)
        val faster = AppSettings(latencyProfiles = mapOf(speaker.key to LatencyProfile(60.0), buds.key to LatencyProfile(50.0)))
        assertEquals("never negative", 0.0, LatencyProfiles.playheadDelayMs(faster, buds), 0.0)
        val usbOnly = AppSettings(latencyProfiles = mapOf(AudioRoute(AudioRouteKind.USB, "DAC").key to LatencyProfile(20.0), buds.key to LatencyProfile(230.0)))
        assertEquals("USB is no reference", 0.0, LatencyProfiles.playheadDelayMs(usbOnly, buds), 0.0)
    }
}
