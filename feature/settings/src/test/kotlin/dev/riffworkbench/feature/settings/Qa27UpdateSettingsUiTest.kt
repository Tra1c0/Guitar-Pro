package dev.riffworkbench.feature.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.ui.components.RiffTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Zyklus 27, Einstellungen → „Updates“ — UNVERIFIED locally (Robolectric, CI only). Fakes for checker and installer, no
 * network: the row shows the installed version, the switch writes AppSettings.updateChecks, „Jetzt prüfen“ reports „aktuell“,
 * then a newer build with „Installieren“, which hands exactly that build to the installer and shows the permission hint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa27UpdateSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    private class Settings(initial: AppSettings) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun load() = Outcome.Success(Unit)
        override suspend fun update(settings: AppSettings): Outcome<Unit> { this.settings.value = settings; return Outcome.Success(Unit) }
    }
    private class FakeChecker : UpdateChecker {
        @Volatile var answer: Outcome<UpdateInfo?> = Outcome.Success(null)
        @Volatile var calls = 0
        override suspend fun check(): Outcome<UpdateInfo?> { calls++; return answer }
    }
    private class FakeInstaller : UpdateInstaller {
        val installed = java.util.concurrent.CopyOnWriteArrayList<UpdateInfo>()
        override suspend fun install(update: UpdateInfo, progress: (Long, Long) -> Unit): Outcome<UpdateInstallStep> {
            installed += update; progress(update.sizeBytes, update.sizeBytes); return Outcome.Success(UpdateInstallStep.PERMISSION_NEEDED)
        }
    }

    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { store.clear(); scope.cancel() }

    @Test fun updateRowChecksAndInstalls() {
        val settings = Settings(AppSettings())
        val checker = FakeChecker()
        val installer = FakeInstaller()
        val updates = UpdateCoordinator(settings, checker, installer, 1042, "0.42", scope) { 1_800_000_000_000L }
        val vm = SettingsViewModel(settings, updates)
        store.put("vm", vm)
        compose.setContent { RiffTheme { SettingsScreen(vm, onBack = {}) } }
        compose.waitForIdle()

        compose.onNodeWithTag("update-installed").performScrollTo().assertTextContains("0.42 (1042)", substring = true)
        compose.onNodeWithTag("update-install").assertDoesNotExist()

        // Switch off and on again.
        compose.onNodeWithTag("update-auto").performScrollTo().performClick()
        compose.waitUntil(5000) { !settings.settings.value.updateChecks }
        compose.onNodeWithTag("update-auto").performClick()
        compose.waitUntil(5000) { settings.settings.value.updateChecks }

        compose.onNodeWithTag("update-check").performScrollTo().performClick()
        compose.waitUntil(5000) { updates.state.value.phase == UpdatePhase.UP_TO_DATE }
        compose.waitForIdle()
        compose.onNodeWithTag("update-status").assertExists()
        compose.onNodeWithTag("update-install").assertDoesNotExist()

        val found = UpdateInfo(1057, "0.57", Updates.DOWNLOAD_PREFIX + "v1057/ArtiFret-0.57.apk", "- Neu: Updates", 62_914_560L, null)
        checker.answer = Outcome.Success(found)
        compose.onNodeWithTag("update-check").performScrollTo().performClick()
        compose.waitUntil(5000) { updates.state.value.phase == UpdatePhase.AVAILABLE }
        compose.waitForIdle()
        compose.onNodeWithTag("update-status").assertTextContains("0.57", substring = true)
        compose.onNodeWithTag("update-install").performScrollTo().assertTextContains("60.0 MB", substring = true).performClick()
        compose.waitUntil(5000) { updates.state.value.phase == UpdatePhase.PERMISSION_NEEDED }
        assertEquals(listOf(found), installer.installed.toList())
        assertEquals(2, checker.calls)
        assertEquals("manual checks do not touch the throttle", 0L, settings.settings.value.lastUpdateCheckEpochMs)
    }
}
