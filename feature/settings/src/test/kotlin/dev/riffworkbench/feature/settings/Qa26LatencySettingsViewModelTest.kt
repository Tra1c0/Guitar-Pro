package dev.riffworkbench.feature.settings

import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 26, Settings → „Latenz“: shows the active device, its value and the fallback; „Kalibrieren“ stores the median for
 * the device it ran on; a device switch cancels; „Zurücksetzen“ and removing list entries; without audio no calibration.
 * Transport fake with a hand-made calibration plan (8 clicks at 100 BPM) and the identity time map.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa26LatencySettingsViewModelTest {
    private val sr = 48_000
    private val beat = 28_800L
    private val ms = 48L
    private val speaker = AudioRoute(AudioRouteKind.SPEAKER, "Pixel 8")
    private val buds = AudioRoute(AudioRouteKind.BLUETOOTH_A2DP, "Galaxy Buds2")

    private class Settings(initial: AppSettings) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun load() = Outcome.Success(Unit)
        override suspend fun update(settings: AppSettings): Outcome<Unit> { this.settings.value = settings; return Outcome.Success(Unit) }
    }
    private class FakeTransport(val sr: Int, val beat: Long) : Transport {
        val mutable = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
        override val state = mutable
        var plan: PlaybackPlan? = null
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> {
            plan = PlaybackPlan(project.revision, sr, emptyList(), emptyList(),
                spans = project.measures.mapIndexed { i, m -> RouteSpan(i, m.id, i, 1, Fraction.ONE, doubleArrayOf(0.0, 1.0), doubleArrayOf(i * 4.0 * beat, (i + 1) * 4.0 * beat)) },
                clicks = List(8) { MetronomeClick(it * beat, 0) })
            return Outcome.Success(Unit)
        }
        override suspend fun play(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.PLAYING, sample = SamplePosition(-4 * beat, sr), countingIn = true); return Outcome.Success(Unit) }
        override suspend fun pause(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.PAUSED); return Outcome.Success(Unit) }
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun setLoop(range: LoopRange?) = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.STOPPED, sample = SamplePosition(0, sr), countingIn = false); return Outcome.Success(Unit) }
        override fun audiblePlanFrameAt(nanos: Long): Double = if (mutable.value.status == TransportStatus.PLAYING) nanos.toDouble() else Double.NaN
        override fun installedPlan(): PlaybackPlan? = plan
        fun advance(frame: Long) { mutable.value = mutable.value.copy(sample = SamplePosition(frame, sr), countingIn = false) }
    }

    private class Fixture(val vm: LatencySettingsViewModel, val t: FakeTransport?, val settings: Settings, val routes: ManualAudioRouteMonitor)
    private fun harness(initial: AppSettings, route: AudioRoute?, audio: Boolean = true, block: TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = Settings(initial)
        val routes = ManualAudioRouteMonitor(route)
        val t = if (audio) FakeTransport(sr, beat) else null
        val vm = LatencySettingsViewModel(settings, routes, t, clock = { 1_700_000_000_000L })
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); block(Fixture(vm, t, settings, routes)) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    private fun TestScope.tapAll(x: Fixture, lateMs: Int) {
        x.vm.calibrate(); advanceUntilIdle()
        assertTrue(x.vm.state.value.calibrating)
        x.t!!.advance(10); advanceUntilIdle()
        (0 until 8).forEach { i -> x.vm.tapDown(i * beat + (lateMs + i % 3 - 1) * ms) }
        advanceUntilIdle()
        assertEquals(8, x.vm.state.value.taps)
        x.t.advance(8 * beat); advanceUntilIdle()
    }

    @Test fun showsTheActiveDeviceAndStoresTheCalibrationForIt() = harness(AppSettings(tapLatencyMs = 30.0, latencyProfiles = mapOf(speaker.key to LatencyProfile(42.0, 5L))), buds) { x ->
        val s0 = x.vm.state.value
        assertEquals("Galaxy Buds2 (Bluetooth)", s0.device)
        assertNull(s0.deviceMs)
        assertEquals("uncalibrated: fallback applied", 30.0, s0.appliedMs, 0.0)
        assertEquals(30.0, s0.fallbackMs, 0.0)
        assertEquals(listOf(LatencyDeviceRow(speaker.key, "Pixel 8 (Lautsprecher)", 42.0, 5L, false)), s0.devices)
        tapAll(x, 232)
        val s1 = x.vm.state.value
        assertFalse(s1.calibrating)
        assertEquals(232.0, s1.deviceMs!!, 1.0)
        assertEquals(LatencyProfile(232.0, 1_700_000_000_000L), x.settings.settings.value.latencyProfiles[buds.key])
        assertEquals("Tipp-Latenz gespeichert: 232 ms für Galaxy Buds2 (Bluetooth)", s1.message)
        assertEquals("both calibrated: playhead correction", 190.0, s1.playheadDelayMs, 1.0)
        assertEquals(listOf(true, false), s1.devices.map { it.active })
        assertEquals(TransportStatus.STOPPED, x.t!!.state.value.status)
        // Switching shows the other device; „Zurücksetzen“ affects only the active one; list entries are removable.
        x.routes.set(speaker); advanceUntilIdle()
        assertEquals(42.0, x.vm.state.value.deviceMs!!, 0.0)
        assertEquals(0.0, x.vm.state.value.playheadDelayMs, 0.0)
        x.vm.reset(); advanceUntilIdle()
        assertEquals(setOf(buds.key), x.settings.settings.value.latencyProfiles.keys)
        x.vm.remove(buds.key); advanceUntilIdle()
        assertTrue(x.settings.settings.value.latencyProfiles.isEmpty())
        x.vm.clearFallback(); advanceUntilIdle()
        assertEquals(0.0, x.settings.settings.value.tapLatencyMs, 0.0)
    }

    @Test fun aDeviceSwitchCancelsAndNothingIsStored() = harness(AppSettings(), speaker) { x ->
        x.vm.calibrate(); advanceUntilIdle()
        x.t!!.advance(10); advanceUntilIdle()
        (0 until 8).forEach { i -> x.vm.tapDown(i * beat + 80 * ms) }
        x.routes.set(buds); advanceUntilIdle()
        assertFalse(x.vm.state.value.calibrating)
        assertEquals("Ausgabegerät gewechselt – Kalibrierung abgebrochen", x.vm.state.value.message)
        assertEquals(AppSettings(), x.settings.settings.value)
        assertEquals(TransportStatus.STOPPED, x.t.state.value.status)
        // „Abbrechen“ by hand: no message, nothing stored.
        x.vm.calibrate(); advanceUntilIdle()
        x.vm.cancelCalibration(); advanceUntilIdle()
        assertFalse(x.vm.state.value.calibrating)
        assertNull(x.vm.state.value.message)
        assertEquals(AppSettings(), x.settings.settings.value)
    }

    @Test fun unknownDeviceWritesTheFallbackAndNoAudioMeansNoCalibration() {
        harness(AppSettings(), null) { x ->
            assertNull(x.vm.state.value.device)
            tapAll(x, 60)
            assertEquals(60.0, x.settings.settings.value.tapLatencyMs, 1.0)
            assertTrue(x.settings.settings.value.latencyProfiles.isEmpty())
            assertEquals("Tipp-Latenz gespeichert: 60 ms", x.vm.state.value.message)
        }
        harness(AppSettings(), speaker, audio = false) { x ->
            assertFalse(x.vm.state.value.canCalibrate)
            x.vm.calibrate(); advanceUntilIdle()
            assertEquals("Audioausgabe nicht verfügbar", x.vm.state.value.message)
        }
    }
}
