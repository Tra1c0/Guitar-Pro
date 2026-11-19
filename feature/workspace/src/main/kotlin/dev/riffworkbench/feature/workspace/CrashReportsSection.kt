package dev.riffworkbench.feature.workspace

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.riffworkbench.ports.CrashEntry
import dev.riffworkbench.ports.CrashReports
import dev.riffworkbench.ui.components.TabinoCard

/**
 * Zyklus 31 „Letzte Abstürze“ (diagnostics page and the start notice „ArtiFret ist abgestürzt“): the reports kept on the
 * device, newest first; a tap shows the text, „Teilen“ hands it to the system share sheet (ACTION_SEND text/plain — the user
 * picks the target, nothing is sent automatically, no network), „Löschen“ removes it. The directory holds at most 5 small
 * files, so it is read directly.
 */
@Composable fun CrashReportsSection(reports: CrashReports, onShare: (String) -> Unit = rememberCrashShare()) {
    var revision by remember { mutableIntStateOf(0) }
    val entries = remember(reports, revision) { runCatching { reports.list() }.getOrDefault(emptyList()) }
    var open by remember(reports) { mutableStateOf<String?>(null) }
    TabinoCard(Modifier.fillMaxWidth().testTag("crash-reports")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Letzte Abstürze", "Recent crashes"), style = MaterialTheme.typography.titleMedium)
        if (entries.isEmpty()) Text(tr("Keine Absturzberichte gespeichert.", "No crash reports stored."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("crash-none"))
        entries.forEachIndexed { index, entry ->
            CrashEntryRow(entry, Modifier.testTag("crash-entry-$index").clickable { open = if (open == entry.id) null else entry.id })
            if (open == entry.id) {
                val text = remember(entry.id, revision) { runCatching { reports.read(entry.id) }.getOrNull() }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(text ?: tr("Bericht nicht lesbar.", "Report not readable."), Modifier.padding(12.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("crash-text"),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { text?.let(onShare) }, enabled = text != null, shape = RoundedCornerShape(22.dp), modifier = Modifier.testTag("crash-share")) { Text(tr("Teilen", "Share")) }
                    OutlinedButton(onClick = { runCatching { reports.delete(entry.id) }; open = null; revision++ }, shape = RoundedCornerShape(22.dp), modifier = Modifier.testTag("crash-delete")) { Text(tr("Löschen", "Delete")) }
                }
            }
        }
        if (entries.size > 1) TextButton(onClick = { runCatching { reports.clear() }; open = null; revision++ }, modifier = Modifier.testTag("crash-delete-all")) { Text(tr("Alle löschen", "Delete all")) }
        Text(tr("Berichte bleiben auf dem Gerät (die letzten 5). Sie enthalten Zeit, App-Version, Gerät, Bildschirm, Takt-/Spurzahl, Raster und den Fehlerverlauf – keine Noten, Titel oder Namen. „Teilen“ übergibt den Text an eine App deiner Wahl.",
            "Reports stay on the device (the last 5). They contain time, app version, device, screen, bar/track count, grid and the error trace – no notes, titles or names. “Share” hands the text to an app of your choice."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable private fun CrashEntryRow(entry: CrashEntry, modifier: Modifier) {
    val time = remember(entry.epochMillis) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(entry.epochMillis)) }
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)) {
        Text(time, style = MaterialTheme.typography.labelLarge)
        Text(entry.title, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The share intent of a report: plain text for an app the user chooses (no automatic transfer). */
fun crashShareIntent(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, "ArtiFret Absturzbericht")
    putExtra(Intent.EXTRA_TEXT, text)
}

@Composable fun rememberCrashShare(): (String) -> Unit {
    val context = LocalContext.current
    val title = tr("Absturzbericht teilen", "Share crash report")
    return remember(context, title) { { text: String -> shareCrash(context, text, title) } }
}

private fun shareCrash(context: Context, text: String, title: String) {
    try { context.startActivity(Intent.createChooser(crashShareIntent(text), title).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
    catch (_: ActivityNotFoundException) { }
}

/** The reports as a dialog (from the start notice „ArtiFret ist abgestürzt“ → „Ansehen“, where no project page is open). */
@Composable fun CrashReportsDialog(reports: CrashReports, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CrashReportsSection(reports)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("crash-dialog-close")) { Text(tr("Schließen", "Close")) }
            }
        }
    }
}
