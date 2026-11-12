package dev.riffworkbench.platform

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.riffworkbench.ports.AudioRoute
import dev.riffworkbench.ports.AudioRouteKind
import dev.riffworkbench.ports.AudioRouteMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * C-AUDIO (Zyklus 26): the output device the app's audio goes to.
 *
 *  - While a track plays: the device the running AudioTrack is actually routed to ([attach]: `getRoutedDevice` plus an
 *    `OnRoutingChangedListener`), which is what the user hears.
 *  - Otherwise (and until the track reports): inferred from the connected outputs (`AudioManager.getDevices(GET_DEVICES_OUTPUTS)`,
 *    kept current by an `AudioDeviceCallback`), preferring hearing aids, Bluetooth/BLE, USB, wired, then the speaker —
 *    the order in which Android routes media to a newly connected device in practice.
 *
 * The key is the device class plus `productName` (stable per model, no MAC address). Callbacks run on the main looper; the
 * render thread only calls [attach]/[detach] when it opens or closes the track, never per block.
 */
class AndroidAudioRouteMonitor(context: Context) : AudioRouteMonitor {
    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow<AudioRoute?>(null)
    override val route: StateFlow<AudioRoute?> = mutable.asStateFlow()
    /** Device of the running track (id + route), null while no track runs or it has not reported. */
    @Volatile private var routed: Pair<Int, AudioRoute>? = null

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { refresh() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val current = routed
            if (current != null && removedDevices?.any { it.id == current.first } == true) routed = null
            refresh()
        }
    }
    private val routing = AudioRouting.OnRoutingChangedListener { router -> report(router.routedDevice) }

    init {
        // Also calls onAudioDevicesAdded once with the outputs present now.
        try { manager.registerAudioDeviceCallback(devices, main) } catch (_: RuntimeException) { }
        refresh()
    }

    /** A track was opened: follow its routing (preferred over the inferred device). */
    fun attach(track: AudioTrack) {
        try { track.addOnRoutingChangedListener(routing, main); report(track.routedDevice) } catch (_: RuntimeException) { }
    }
    /** The track is about to be released: back to the inferred device. */
    fun detach(track: AudioTrack) {
        try { track.removeOnRoutingChangedListener(routing) } catch (_: RuntimeException) { }
        routed = null
        refresh()
    }

    private fun report(device: AudioDeviceInfo?) {
        val route = device?.let { d -> audioRouteKind(d.type)?.let { kind -> d.id to AudioRoute(kind, productName(d)) } }
        if (route != null) routed = route
        refresh()
    }

    private fun refresh() {
        val outputs = try { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: RuntimeException) { emptyArray<AudioDeviceInfo>() }
        val current = routed?.takeIf { r -> outputs.any { it.id == r.first } }?.second
        mutable.value = current ?: inferred(outputs)
    }

    private fun inferred(outputs: Array<AudioDeviceInfo>): AudioRoute? =
        outputs.mapNotNull { d -> audioRouteKind(d.type)?.let { kind -> AudioRoute(kind, productName(d)) } }.maxByOrNull { priority(it.kind) }

    private fun productName(device: AudioDeviceInfo): String = device.productName?.toString()?.trim().orEmpty()
}

/** Higher wins when no track reports its routing. */
internal fun priority(kind: AudioRouteKind): Int = when (kind) {
    AudioRouteKind.HEARING_AID -> 7
    AudioRouteKind.BLE -> 6
    AudioRouteKind.BLUETOOTH_A2DP -> 6
    AudioRouteKind.USB -> 5
    AudioRouteKind.WIRED -> 4
    AudioRouteKind.OTHER -> 2
    AudioRouteKind.SPEAKER -> 1
}

/**
 * Class of an output device type; null for outputs that never carry the app's media (earpiece, telephony, remote submix,
 * the "safe" speaker). TYPE_HEARING_AID exists from API 28, TYPE_BLE_HEADSET/TYPE_BLE_SPEAKER from 31, TYPE_BLE_BROADCAST
 * from 33: each is compared only behind its SDK check (the int constants are inlined; on older systems they never occur).
 */
@SuppressLint("InlinedApi")
internal fun audioRouteKind(type: Int, sdk: Int = Build.VERSION.SDK_INT): AudioRouteKind? = when {
    type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRouteKind.SPEAKER
    type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
        type == AudioDeviceInfo.TYPE_LINE_DIGITAL || type == AudioDeviceInfo.TYPE_AUX_LINE -> AudioRouteKind.WIRED
    type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_ACCESSORY || type == AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRouteKind.USB
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRouteKind.BLUETOOTH_A2DP
    sdk >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID -> AudioRouteKind.HEARING_AID
    sdk >= Build.VERSION_CODES.S && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> AudioRouteKind.BLE
    sdk >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_BLE_BROADCAST -> AudioRouteKind.BLE
    type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || type == AudioDeviceInfo.TYPE_TELEPHONY || type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> null
    sdk >= Build.VERSION_CODES.R && type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> null
    else -> AudioRouteKind.OTHER
}
