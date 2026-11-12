package dev.riffworkbench.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.editor.TapLatencyCalibrator
import dev.riffworkbench.model.AppSettings
import dev.riffworkbench.model.FailureCode
import dev.riffworkbench.model.Outcome
import dev.riffworkbench.ports.AudioRoute
import dev.riffworkbench.ports.AudioRouteMonitor
import dev.riffworkbench.ports.LatencyProfiles
import dev.riffworkbench.ports.SettingsRepository
import dev.riffworkbench.ports.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One calibrated device in the list of the „Latenz“ page (Zyklus 26). */
data class LatencyDeviceRow(val key: String, val label: String, val tapLatencyMs: Double, val calibratedAtEpochMs: Long, val active: Boolean)

/** What the „Latenz“ page shows (Zyklus 26). */
data class LatencyUiState(
    /** Active output („WH-1000XM4 (Bluetooth)“), null while unknown. */
    val device: String? = null,
    /** Its own calibration in ms, null when it has none. */
    val deviceMs: Double? = null,
    /** Tap latency applied on the active device (own value, else [fallbackMs]). */
    val appliedMs: Double = 0.0,
    /** Device-less value (Zyklus 25 calibration or one made without a known device); fallback for uncalibrated devices. */
    val fallbackMs: Double = 0.0,
    /** Extra delay of the visible playhead on the active device (0 unless it and a speaker/wired output are calibrated). */
    val playheadDelayMs: Double = 0.0,
    val devices: List<LatencyDeviceRow> = emptyList(),
    val calibrating: Boolean = false,
    val countingIn: Boolean = false,
    val taps: Int = 0,
    /** Result or problem of the last action („Gespeichert: 182 ms …“), shown as an overlay notice. */
    val message: String? = null,
    /** False without audio output (calibration impossible). */
    val canCalibrate: Boolean = true,
)

/**
 * Settings → „Latenz“ (Zyklus 26): the active output device, its stored tap latency, „Kalibrieren“ (the Zyklus-25 tap
 * calibration: 8 clicks, median, outliers removed — [TapLatencyCalibrator]), „Zurücksetzen“ and the list of calibrated
 * devices (removable). A device switch during a calibration cancels it; the result is stored for the device it ran on.
 */
class LatencySettingsViewModel(
    private val repository: SettingsRepository,
    private val routes: AudioRouteMonitor,
    transport: Transport?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val calibrator = transport?.let { TapLatencyCalibrator(it) }
    private val message = MutableStateFlow<String?>(null)
    private val mutex = Mutex()
    private var job: Job? = null
    private val progress: StateFlow<TapLatencyCalibrator.Progress> = calibrator?.progress ?: MutableStateFlow(TapLatencyCalibrator.Progress())

    val state: StateFlow<LatencyUiState> = combine(repository.settings, routes.route, progress, message) { s, route, p, m -> describe(s, route, p, m) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, describe(repository.settings.value, routes.route.value, progress.value, null))

    private fun describe(s: AppSettings, route: AudioRoute?, p: TapLatencyCalibrator.Progress, m: String?): LatencyUiState {
        val own = LatencyProfiles.profile(s, route)
        val rows = s.latencyProfiles.entries.map { (key, profile) ->
            val parsed = AudioRoute.parse(key)
            LatencyDeviceRow(key, parsed?.label ?: key, profile.tapLatencyMs, profile.calibratedAtEpochMs, key == route?.key)
        }.sortedWith(compareByDescending<LatencyDeviceRow> { it.active }.thenBy { it.label.lowercase() })
        return LatencyUiState(route?.label, own?.tapLatencyMs, LatencyProfiles.tapLatencyMs(s, route), s.tapLatencyMs, LatencyProfiles.playheadDelayMs(s, route),
            rows, p.running, p.running && p.countingIn, p.taps, m, calibrator != null)
    }

    /** Starts the calibration on the active device (no-op while one runs). */
    fun calibrate() {
        val c = calibrator ?: run { message.value = "Audioausgabe nicht verfügbar"; return }
        if (job?.isActive == true) return
        val device = routes.route.value
        message.value = null
        job = viewModelScope.launch {
            var switched = false
            val watcher = launch { routes.route.first { it?.key != device?.key }; switched = true; c.cancel() }
            val result = c.run(null)
            watcher.cancel()
            message.value = when {
                switched -> "Ausgabegerät gewechselt – Kalibrierung abgebrochen"
                result is Outcome.Success -> {
                    val saved = update { LatencyProfiles.calibrated(it, device, result.value, clock()) }
                    if (saved) "Tipp-Latenz gespeichert: ${kotlin.math.round(result.value).toInt()} ms" + (device?.let { " für ${it.label}" } ?: "") else message.value
                }
                result is Outcome.Failure && result.code == FailureCode.CANCELLED -> null
                result is Outcome.Failure -> result.message
                else -> null
            }
        }
    }
    /** Touch-down on the tap surface; [nanos] = event time (System.nanoTime base). */
    fun tapDown(nanos: Long) { calibrator?.tapDown(nanos) }
    fun cancelCalibration() { val c = calibrator ?: return; viewModelScope.launch { c.cancel() } }
    /** „Zurücksetzen“: the active device's profile goes (without a known device: the fallback goes back to 0). */
    fun reset() { viewModelScope.launch { update { LatencyProfiles.reset(it, routes.route.value) } } }
    /** Removes one calibrated device from the list. */
    fun remove(key: String) { viewModelScope.launch { update { LatencyProfiles.remove(it, key) } } }
    /** Removes the device-less value (back to 0 ms). */
    fun clearFallback() { viewModelScope.launch { update { it.copy(tapLatencyMs = 0.0) } } }
    fun dismissMessage() { message.value = null }

    private suspend fun update(change: (AppSettings) -> AppSettings): Boolean = mutex.withLock {
        when (val r = repository.update(change(repository.settings.value))) {
            is Outcome.Failure -> { message.value = r.message; false }
            is Outcome.Success -> true
        }
    }

    // viewModelScope is already cancelled in onCleared: stop a running calibration on its own scope (the transport plays on).
    override fun onCleared() { val c = calibrator ?: return; if (c.progress.value.running) CoroutineScope(Dispatchers.Default).launch { c.cancel() } }
}
