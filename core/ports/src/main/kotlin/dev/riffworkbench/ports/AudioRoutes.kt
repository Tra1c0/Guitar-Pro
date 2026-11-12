package dev.riffworkbench.ports

import dev.riffworkbench.model.AppSettings
import dev.riffworkbench.model.LatencyProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Zyklus 26: class of an audio output. The calibration key is the class plus the product name, so a phone's speaker,
 * wired headphones and each Bluetooth model keep their own tap latency.
 */
enum class AudioRouteKind(val labelDe: String) {
    SPEAKER("Lautsprecher"), WIRED("Kabel"), USB("USB"), BLUETOOTH_A2DP("Bluetooth"), BLE("Bluetooth LE"), HEARING_AID("Hörgerät"), OTHER("Sonstiges");

    /** Radio links with their own (large, device-specific) latency: switching to an uncalibrated one gets a hint. */
    val wireless: Boolean get() = this == BLUETOOTH_A2DP || this == BLE || this == HEARING_AID
    /** Outputs without a radio link: their calibration is the reference for the playhead correction. */
    val reference: Boolean get() = this == SPEAKER || this == WIRED
}

/** The output the app's audio goes to (Zyklus 26). [productName] is the stable model name, never an address. */
data class AudioRoute(val kind: AudioRouteKind, val productName: String) {
    val key: String get() = keyOf(kind, productName)
    /** „WH-1000XM4 (Bluetooth)“. */
    val label: String get() = if (productName.isBlank()) kind.labelDe else "${productName.trim()} (${kind.labelDe})"

    companion object {
        fun keyOf(kind: AudioRouteKind, productName: String): String = "${kind.name}|${productName.trim()}"
        /** Inverse of [key]; null for keys of an unknown kind (written by a newer version). */
        fun parse(key: String): AudioRoute? {
            val bar = key.indexOf('|')
            if (bar < 0) return null
            val kind = AudioRouteKind.values().firstOrNull { it.name == key.substring(0, bar) } ?: return null
            return AudioRoute(kind, key.substring(bar + 1))
        }
    }
}

/**
 * C-AUDIO (Zyklus 26): the output device currently in use, null while unknown. The platform implementation prefers the
 * device the running AudioTrack is routed to and otherwise infers it from the connected outputs.
 */
interface AudioRouteMonitor { val route: StateFlow<AudioRoute?> }

/** No device detection (tests, JVM): always unknown, so every calibration falls back to [AppSettings.tapLatencyMs]. */
object NoAudioRouteMonitor : AudioRouteMonitor { override val route: StateFlow<AudioRoute?> = MutableStateFlow<AudioRoute?>(null).asStateFlow() }

/** A monitor whose route is set by hand (tests, previews). */
class ManualAudioRouteMonitor(initial: AudioRoute? = null) : AudioRouteMonitor {
    private val mutable = MutableStateFlow(initial)
    override val route: StateFlow<AudioRoute?> = mutable.asStateFlow()
    fun set(route: AudioRoute?) { mutable.value = route }
}

/**
 * „<Name>: Latenz nicht kalibriert“ appears once per device and app process (Zyklus 26), however many workspaces
 * observe the route. One instance lives in the composition root.
 */
class RouteNoticeMemory {
    private val seen = HashSet<String>()
    /** True the first time [key] is offered, false afterwards. */
    @Synchronized fun firstTime(key: String): Boolean = seen.add(key)
}

/**
 * Zyklus 26: rules for per-device tap latencies.
 *
 * Migration decision: the global [AppSettings.tapLatencyMs] from Zyklus 25 is NOT attributed to the device that happens to
 * be active on first start. Nobody knows on which output it was measured; attributing it to, say, the speaker while it was
 * measured over Bluetooth would silently store a wrong profile and switch on a wrong playhead correction (which needs two
 * calibrated devices). It stays the fallback for every device without its own profile — tapping behaves exactly as in
 * Zyklus 25 until a device is calibrated — and it never counts as „calibrated“ for the playhead or the hint.
 */
object LatencyProfiles {
    fun profile(settings: AppSettings, route: AudioRoute?): LatencyProfile? = route?.let { settings.latencyProfiles[it.key] }

    /** The tap latency to subtract on [route]: its own profile, else the device-less (legacy) value. */
    fun tapLatencyMs(settings: AppSettings, route: AudioRoute?): Double = profile(settings, route)?.tapLatencyMs ?: settings.tapLatencyMs

    /** Lowest calibrated tap latency of a reference output (speaker or wired), null without one. */
    fun referenceMs(settings: AppSettings): Double? =
        settings.latencyProfiles.entries.filter { AudioRoute.parse(it.key)?.kind?.reference == true }.minOfOrNull { it.value.tapLatencyMs }

    /**
     * Extra delay of the visible playhead on [route]: max(0, tap(route) − tap(reference)), only when both are calibrated,
     * else 0. Both calibrations contain the same touch latency and the same timestamp-covered output latency, so their
     * difference is exactly the output latency the AudioTrack timestamps miss on [route] (Bluetooth codec/radio buffers).
     */
    fun playheadDelayMs(settings: AppSettings, route: AudioRoute?): Double {
        val current = profile(settings, route) ?: return 0.0
        val reference = referenceMs(settings) ?: return 0.0
        return maxOf(0.0, current.tapLatencyMs - reference)
    }

    /** [settings] with [ms] stored for [route] (null: the device-less value). */
    fun calibrated(settings: AppSettings, route: AudioRoute?, ms: Double, epochMs: Long): AppSettings =
        if (route == null) settings.copy(tapLatencyMs = ms) else settings.copy(latencyProfiles = settings.latencyProfiles + (route.key to LatencyProfile(ms, epochMs)))

    /** „Zurücksetzen“: [route]'s profile removed (the fallback applies again); without a device the fallback goes back to 0. */
    fun reset(settings: AppSettings, route: AudioRoute?): AppSettings =
        if (route == null) settings.copy(tapLatencyMs = 0.0) else settings.copy(latencyProfiles = settings.latencyProfiles - route.key)

    /** Removes one entry of the list of calibrated devices. */
    fun remove(settings: AppSettings, key: String): AppSettings = settings.copy(latencyProfiles = settings.latencyProfiles - key)

    /** The hint is due: a wireless output without its own profile. */
    fun needsCalibrationHint(settings: AppSettings, route: AudioRoute?): Boolean = route != null && route.kind.wireless && profile(settings, route) == null
}
