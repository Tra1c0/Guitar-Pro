package dev.riffworkbench.platform

import android.media.AudioDeviceInfo
import dev.riffworkbench.ports.AudioRouteKind
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 26 — UNVERIFIED locally (Android unit test, CI only): device types map to the latency classes; BLE and hearing-aid
 * types count only on the API levels that define them (minSdk 26: on older systems those ints never occur and are OTHER),
 * earpiece/telephony/remote submix are no media outputs; without a reporting track the radio links win over the speaker.
 */
class Qa26AudioRouteKindTest {
    @Test fun typesMapToClassesBehindTheirApiLevels() {
        assertEquals(AudioRouteKind.SPEAKER, audioRouteKind(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 35))
        for (t in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE))
            assertEquals(AudioRouteKind.WIRED, audioRouteKind(t, 26))
        for (t in listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY))
            assertEquals(AudioRouteKind.USB, audioRouteKind(t, 26))
        assertEquals(AudioRouteKind.BLUETOOTH_A2DP, audioRouteKind(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26))
        assertEquals(AudioRouteKind.HEARING_AID, audioRouteKind(AudioDeviceInfo.TYPE_HEARING_AID, 28))
        assertEquals(AudioRouteKind.OTHER, audioRouteKind(AudioDeviceInfo.TYPE_HEARING_AID, 27))
        assertEquals(AudioRouteKind.BLE, audioRouteKind(AudioDeviceInfo.TYPE_BLE_HEADSET, 31))
        assertEquals(AudioRouteKind.BLE, audioRouteKind(AudioDeviceInfo.TYPE_BLE_SPEAKER, 35))
        assertEquals(AudioRouteKind.OTHER, audioRouteKind(AudioDeviceInfo.TYPE_BLE_HEADSET, 30))
        assertEquals(AudioRouteKind.BLE, audioRouteKind(AudioDeviceInfo.TYPE_BLE_BROADCAST, 33))
        assertEquals(AudioRouteKind.OTHER, audioRouteKind(AudioDeviceInfo.TYPE_HDMI, 26))
        assertEquals(AudioRouteKind.OTHER, audioRouteKind(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26))
        for (t in listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX)) assertNull(audioRouteKind(t, 35))
        assertNull(audioRouteKind(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, 30))
    }

    @Test fun radioLinksWinOverWiredAndSpeakerWhenInferring() {
        val order = listOf(AudioRouteKind.SPEAKER, AudioRouteKind.OTHER, AudioRouteKind.WIRED, AudioRouteKind.USB, AudioRouteKind.BLUETOOTH_A2DP, AudioRouteKind.HEARING_AID)
        assertEquals(order, order.sortedBy { priority(it) })
        assertEquals(priority(AudioRouteKind.BLUETOOTH_A2DP), priority(AudioRouteKind.BLE))
    }
}
