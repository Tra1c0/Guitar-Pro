package dev.riffworkbench.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.SettingsRepository
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val settings = repository.settings
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutex = Mutex()
    fun update(change: (AppSettings) -> AppSettings) { viewModelScope.launch { mutex.withLock {
        when (val result = repository.update(change(settings.value))) { is Outcome.Failure -> mutableError.value = result.message; is Outcome.Success -> mutableError.value = null }
    } } }
}

@Composable fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Scaffold { padding -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onBack) { Text("‹ ${stringResource(R.string.back)}") }
        SectionHeading(stringResource(R.string.settings), stringResource(R.string.settings_hint))
        error?.let { NoticeCard(it, true) }
        ToggleRow(stringResource(R.string.dark_theme), settings.darkTheme, { value -> vm.update { it.copy(darkTheme = value) } })
        ToggleRow(stringResource(R.string.auto_advance), settings.autoAdvance, { value -> vm.update { it.copy(autoAdvance = value) } })
        // QA 23: pinch zoom is off by default (fixed, readable score size); this switch turns it on.
        ToggleRow(stringResource(R.string.pinch_zoom), settings.pinchZoom, { value -> vm.update { it.copy(pinchZoom = value) } })
        Text(stringResource(R.string.pinch_zoom_hint), style = MaterialTheme.typography.bodySmall)
        ToggleRow(stringResource(R.string.strict_rhythm), settings.strictRhythm, {}, enabled = false)
        Text(stringResource(R.string.strict_hint), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.shortcuts), style = MaterialTheme.typography.titleMedium)
        Text("Ctrl+Z · ${stringResource(R.string.undo)}\nCtrl+Shift+Z / Ctrl+Y · ${stringResource(R.string.redo)}\nCtrl+S · ${stringResource(R.string.save)}\n0–9 / Enter · ${stringResource(R.string.commit)}\n← / → · ${stringResource(R.string.previous)} / ${stringResource(R.string.next)}\nBackspace · ${stringResource(R.string.delete)}")
        PendingCard(stringResource(R.string.command_palette), if (english()) "Searchable commands, custom shortcuts and favorites follow through the command catalog." else "Durchsuchbare Befehle, eigene Tastenkürzel und Favoriten folgen über den Befehlskatalog.", "B")
        Text(stringResource(R.string.licenses), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.license_hint_bundled), style = MaterialTheme.typography.bodyMedium)
        Text("Kotlin · Apache-2.0\nAndroidX / Compose · Apache-2.0\nkotlinx.coroutines / serialization · Apache-2.0\nalphaTab · MPL-2.0 (Spike A)\n" +
            "E-Gitarre / electric guitar: Karoryfer Emilyguitar · CC0-1.0\nSchlagzeug / drums: ArtiFret Pearl Kit · CC BY-SA 3.0 (AVL Black Pearl 4, Glen MacArthur, bandshed.net/avldrumkits)\n" +
            "Bricolage Grotesque, DM Sans · SIL OFL 1.1", style = MaterialTheme.typography.bodySmall)
        DrumKitLicence()
    } }
}

/**
 * CC BY-SA 3.0 attribution of the bundled drum samples (QA cycle 24): the kit's notice and the unchanged readme of the
 * AVL-Drumkits (author information and licence), read from the APK assets when the user opens them.
 */
@Composable private fun DrumKitLicence() {
    var shown by rememberSaveable { mutableStateOf(false) }
    val assets = LocalContext.current.assets
    val missing = stringResource(R.string.license_text_missing)
    val text by produceState<String?>(null, shown) {
        value = if (!shown) null else withContext(Dispatchers.IO) {
            runCatching {
                listOf("licenses/artifret-pearl-kit-NOTICE.txt", "licenses/avl-drumkits-README.txt").joinToString("\n\n") { path -> assets.open(path).bufferedReader().use { reader -> reader.readText() } }
            }.getOrNull() ?: missing
        }
    }
    TextButton(onClick = { shown = !shown }) { Text(stringResource(if (shown) R.string.license_hide_drums else R.string.license_show_drums)) }
    text?.let { licence -> Text(licence, style = MaterialTheme.typography.bodySmall) }
}
