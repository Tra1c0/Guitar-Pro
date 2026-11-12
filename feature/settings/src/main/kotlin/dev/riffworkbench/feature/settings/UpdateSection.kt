package dev.riffworkbench.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.ports.UpdateCoordinator
import dev.riffworkbench.ports.UpdatePhase
import dev.riffworkbench.ports.UpdateState
import dev.riffworkbench.ui.components.ToggleRow
import dev.riffworkbench.ui.components.english
import java.util.Locale

/**
 * Zyklus 27, Einstellungen → „Updates“: installed version, the switch for the automatic check at start (at most every 6 h),
 * „Jetzt prüfen“, the result, and „Installieren“ with download progress. The only network use of the app.
 */
@Composable internal fun UpdateSection(automatic: Boolean, onAutomatic: (Boolean) -> Unit, updates: UpdateCoordinator) {
    val state by updates.state.collectAsStateWithLifecycle()
    val en = english()
    val busy = state.phase == UpdatePhase.CHECKING || state.phase == UpdatePhase.DOWNLOADING
    Column(Modifier.fillMaxWidth().testTag("settings-updates"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Text((if (en) "Installed: " else "Installiert: ") + "ArtiFret ${state.installedVersionName} (${state.installedVersionCode})",
            Modifier.testTag("update-installed"), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.testTag("update-auto")) {
            ToggleRow(if (en) "Check for updates automatically" else "Automatisch nach Updates suchen", automatic, onAutomatic)
        }
        Text(if (en) "Asks GitHub (api.github.com) for the newest ArtiFret release at start, at most every 6 hours. No personal data is sent; everything else works offline."
            else "Fragt beim Start höchstens alle 6 Stunden bei GitHub (api.github.com) nach der neuesten ArtiFret-Version. Es werden keine persönlichen Daten gesendet; alles andere läuft offline.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { updates.checkNow() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("update-check")) {
            Text(when { state.phase == UpdatePhase.CHECKING -> if (en) "Checking …" else "Prüfe …"; en -> "Check now"; else -> "Jetzt prüfen" })
        }
        status(state, en)?.let { Text(it, Modifier.testTag("update-status"), style = MaterialTheme.typography.bodyMedium) }
        if (state.phase == UpdatePhase.DOWNLOADING) {
            val total = state.totalBytes.coerceAtLeast(1L)
            LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("update-progress"))
        }
        state.available?.let { info ->
            if (info.notes.isNotBlank()) Text(info.notes, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Button(onClick = { updates.install() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("update-install")) {
                Text((if (en) "Install " else "Installieren: ") + "${info.versionName} (${megabytes(info.sizeBytes)} MB)")
            }
        }
    }
}

private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0)

private fun status(s: UpdateState, en: Boolean): String? {
    val name = s.available?.versionName.orEmpty()
    return when (s.phase) {
        UpdatePhase.IDLE -> null
        UpdatePhase.CHECKING -> if (en) "Looking for updates …" else "Suche nach Updates …"
        UpdatePhase.UP_TO_DATE -> if (en) "ArtiFret is up to date." else "ArtiFret ist aktuell."
        UpdatePhase.AVAILABLE -> if (en) "Update $name available." else "Update $name verfügbar."
        UpdatePhase.FAILED -> s.message ?: if (en) "Update failed." else "Update fehlgeschlagen."
        UpdatePhase.DOWNLOADING -> {
            val pct = if (s.totalBytes > 0) (100 * s.downloadedBytes / s.totalBytes).coerceIn(0, 100) else 0
            if (en) "Downloading $name … $pct %" else "Lade $name … $pct %"
        }
        UpdatePhase.PERMISSION_NEEDED -> if (en) "Allow „Install unknown apps“ for ArtiFret, then tap „Install“ again."
            else "Bitte „Unbekannte Apps installieren“ für ArtiFret erlauben, dann erneut „Installieren“ tippen."
        UpdatePhase.CONFIRMING -> if (en) "Confirm the installation in the system dialog." else "Bitte die Installation im Systemdialog bestätigen."
    }
}
