package dev.riffworkbench.feature.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.ui.components.RiffTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Zyklus 26, Settings → „Latenz“ — UNVERIFIED locally (Robolectric, CI only; excluded from the JVM shim by its name). The
 * page shows the active device (Bluetooth, not calibrated) and the list (speaker); „Kalibrieren“ turns the page into the
 * tap surface, 8 touches on the clicks (230 ms late) store a profile for the Bluetooth device, the list shows it, „Löschen“
 * removes it again, „Zurücksetzen“ is only enabled with a stored value. The transport fake returns the frame the test
 * sets before each touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa26LatencySettingsUiTest {
    @get:Rule val compose = createComposeRule()

    private val beat = 28_800L
    private val speaker = AudioRoute(AudioRouteKind.SPEAKER, "Pixel 8")
    private val buds = AudioRoute(AudioRouteKind.BLUETOOTH_A2DP, "Galaxy Buds2")

    private class Settings(initial: AppSettings) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun load() = Outcome.Success(Unit)
        override suspend fun update(settings: AppSettings): Outcome<Unit> { this.settings.value = settings; return Outcome.Success(Unit) }
    }
    /** The calibration plan by hand (8 clicks at 100 BPM); the test sets the audible frame of the next touch. */
    private class TapTransport(val beat: Long) : Transport {
        override val state = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
        var plan: PlaybackPlan? = null
        @Volatile var nextFrame = Double.NaN
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> {
            plan = PlaybackPlan(project.revision, 48_000, emptyList(), emptyList(),
                spans = project.measures.mapIndexed { i, m -> RouteSpan(i, m.id, i, 1, Fraction.ONE, doubleArrayOf(0.0, 1.0), doubleArrayOf(i * 4.0 * beat, (i + 1) * 4.0 * beat)) },
                clicks = List(8) { MetronomeClick(it * beat, 0) })
            return Outcome.Success(Unit)
        }
        override suspend fun play(): Outcome<Unit> { state.value = state.value.copy(status = TransportStatus.PLAYING, sample = SamplePosition(-4 * beat, 48_000), countingIn = true); return Outcome.Success(Unit) }
        override suspend fun pause(): Outcome<Unit> { state.value = state.value.copy(status = TransportStatus.PAUSED); return Outcome.Success(Unit) }
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun setLoop(range: LoopRange?): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> { state.value = state.value.copy(status = TransportStatus.STOPPED, countingIn = false); return Outcome.Success(Unit) }
        override fun audiblePlanFrameAt(nanos: Long): Double = if (state.value.status == TransportStatus.PLAYING) nextFrame else Double.NaN
        override fun installedPlan(): PlaybackPlan? = plan
    }

    private val store = ViewModelStore()
    @After fun tearDown() { store.clear() }

    @Test fun calibrateStoresTheActiveDeviceAndTheListCanDeleteIt() {
        val settings = Settings(AppSettings(latencyProfiles = mapOf(speaker.key to LatencyProfile(42.0, 1_700_000_000_000L))))
        val routes = ManualAudioRouteMonitor(buds)
        val transport = TapTransport(beat)
        val vm = LatencySettingsViewModel(settings, routes, transport)
        store.put("vm", vm)
        compose.setContent { RiffTheme { LatencySettingsScreen(vm, onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("latency-device").assertTextContains("Galaxy Buds2", substring = true)
        compose.onNodeWithTag("latency-row-0").assertExists()
        compose.onNodeWithTag("latency-row-1").assertDoesNotExist()
        compose.onNodeWithTag("latency-reset").assertIsNotEnabled()

        compose.onNodeWithTag("latency-calibrate").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.state.value.calibrating }
        compose.onNodeWithTag("latency-tap-surface").assertExists()
        transport.state.value = transport.state.value.copy(sample = SamplePosition(10, 48_000), countingIn = false)
        compose.waitUntil(5000) { !vm.state.value.countingIn }
        for (i in 0 until 8) {
            transport.nextFrame = (i * beat + 230 * 48).toDouble()
            compose.onNodeWithTag("latency-tap-surface").performTouchInput { down(center); up() }
            compose.waitForIdle()
        }
        assertEquals("tap surface reacts", 8, vm.state.value.taps)
        transport.state.value = transport.state.value.copy(sample = SamplePosition(8 * beat, 48_000))
        compose.waitUntil(5000) { !vm.state.value.calibrating }
        compose.waitForIdle()
        assertEquals(230.0, settings.settings.value.latencyProfiles[buds.key]!!.tapLatencyMs, 0.5)
        assertEquals(TransportStatus.STOPPED, transport.state.value.status)
        compose.onNodeWithTag("latency-row-1").assertExists()
        compose.onNodeWithTag("latency-reset").assertIsEnabled()

        // The active device is listed first: delete it, the speaker stays.
        compose.onNodeWithTag("latency-remove-0").performScrollTo().performClick()
        compose.waitUntil(5000) { buds.key !in settings.settings.value.latencyProfiles }
        assertEquals(setOf(speaker.key), settings.settings.value.latencyProfiles.keys)
        compose.waitForIdle()
        compose.onNodeWithTag("latency-row-1").assertDoesNotExist()
    }
}
