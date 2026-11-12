package dev.riffworkbench.feature.settings

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.ui.components.NoticeCard
import dev.riffworkbench.ui.components.SectionHeading
import dev.riffworkbench.ui.components.TransientNotice
import dev.riffworkbench.ui.components.english
import kotlin.math.roundToInt

@Composable private fun t(de: String, en: String): String = if (english()) en else de

/** System.nanoTime of a pointer event: its uptime (ms, same monotonic clock) unless that is implausible (test clocks). */
private fun eventNanos(uptimeMillis: Long): Long {
    val now = System.nanoTime()
    val event = uptimeMillis * 1_000_000L
    return if (event in now - 1_000_000_000L..now) event else now
}

/**
 * Settings → „Latenz“ (Zyklus 26): the active output device with its stored tap latency, „Kalibrieren“ (8 clicks after a
 * one-bar count-in; tap on every click), „Zurücksetzen“ and the list of calibrated devices (each removable). While
 * calibrating, the page is the tap surface. Messages lie over the page and close themselves (QA 23).
 */
@Composable fun LatencySettingsScreen(vm: LatencySettingsViewModel, onBack: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    // Leaving the page (back, background) ends a running calibration without a result.
    DisposableEffect(vm) { onDispose { vm.cancelCalibration() } }
    Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        if (ui.calibrating) LatencyCalibrationSurface(vm, ui, Modifier.fillMaxSize().padding(24.dp))
        else LatencyOverview(vm, ui, onBack)
        ui.message?.let { text ->
            TransientNotice(text, 4_000L + 50L * (text.length - 40).coerceAtLeast(0), { if (vm.state.value.message == text) vm.dismissMessage() },
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp))
        }
    } }
}

@Composable private fun LatencyOverview(vm: LatencySettingsViewModel, ui: LatencyUiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onBack, modifier = Modifier.testTag("latency-back")) { Text("‹ ${t("Zurück", "Back")}") }
        SectionHeading(t("Latenz", "Latency"), t("Tipp-Latenz pro Ausgabegerät (Lautsprecher, Kopfhörer, Bluetooth). Sie gleicht „Rhythmus tippen“ aus und verzögert auf Bluetooth den Wiedergabekopf, damit er zum Ton passt.",
            "Tap latency per output device (speaker, headphones, Bluetooth). It corrects „tap rhythm“ and delays the playhead on Bluetooth so it matches the sound."))
        if (!ui.canCalibrate) NoticeCard(t("Audioausgabe nicht verfügbar – Kalibrieren ist nicht möglich.", "Audio output unavailable – calibration is not possible."), true)
        Text(t("Aktuelles Ausgabegerät", "Current output"), style = MaterialTheme.typography.titleMedium)
        Text(ui.device ?: t("Unbekannt (es gilt der allgemeine Wert)", "Unknown (the general value applies)"), style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("latency-device"))
        val stored = ui.deviceMs?.let { t("Gespeichert: ${it.roundToInt()} ms", "Stored: ${it.roundToInt()} ms") }
            ?: if (ui.device != null) t("Nicht kalibriert – es gilt ${ui.appliedMs.roundToInt()} ms", "Not calibrated – ${ui.appliedMs.roundToInt()} ms apply")
            else t("Allgemeiner Wert: ${ui.fallbackMs.roundToInt()} ms", "General value: ${ui.fallbackMs.roundToInt()} ms")
        Text(stored, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("latency-value"))
        if (ui.playheadDelayMs > 0.0) Text(t("Wiedergabekopf läuft ${ui.playheadDelayMs.roundToInt()} ms später (gegenüber Lautsprecher/Kabel)",
            "Playhead runs ${ui.playheadDelayMs.roundToInt()} ms later (relative to speaker/wired)"), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::calibrate, enabled = ui.canCalibrate, modifier = Modifier.heightIn(min = 48.dp).testTag("latency-calibrate")) { Text(t("Kalibrieren", "Calibrate")) }
            val resettable = if (ui.device != null) ui.deviceMs != null else ui.fallbackMs != 0.0
            OutlinedButton(onClick = vm::reset, enabled = resettable, modifier = Modifier.heightIn(min = 48.dp).testTag("latency-reset")) { Text(t("Zurücksetzen", "Reset")) }
        }
        Text(t("Kalibrieren: nach einem Takt Vorzähler 8 Klicks – tippe genau auf jeden Klick. Gespeichert wird der Median, Ausreißer zählen nicht.",
            "Calibrate: after a one-bar count-in, 8 clicks – tap exactly on every click. The median is stored, outliers are ignored."), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(t("Kalibrierte Geräte", "Calibrated devices"), style = MaterialTheme.typography.titleMedium)
        if (ui.devices.isEmpty() && ui.fallbackMs == 0.0) Text(t("Noch keine", "None yet"), style = MaterialTheme.typography.bodyMedium)
        val dates = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
        ui.devices.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().testTag("latency-row-$index"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.label + if (row.active) t(" · aktiv", " · active") else "", style = MaterialTheme.typography.bodyLarge)
                    val date = if (row.calibratedAtEpochMs > 0) " · " + dates.format(java.util.Date(row.calibratedAtEpochMs)) else ""
                    Text("${row.tapLatencyMs.roundToInt()} ms$date", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { vm.remove(row.key) }, modifier = Modifier.heightIn(min = 48.dp).testTag("latency-remove-$index")) { Text(t("Löschen", "Delete")) }
            }
        }
        if (ui.fallbackMs != 0.0) Row(Modifier.fillMaxWidth().testTag("latency-row-general"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("Ohne Gerätezuordnung (allgemeiner Wert)", "Without a device (general value)"), style = MaterialTheme.typography.bodyLarge)
                Text(t("${ui.fallbackMs.roundToInt()} ms · gilt für Geräte ohne eigene Kalibrierung", "${ui.fallbackMs.roundToInt()} ms · applies to devices without their own calibration"),
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::clearFallback, modifier = Modifier.heightIn(min = 48.dp).testTag("latency-remove-general")) { Text(t("Löschen", "Delete")) }
        }
    }
}

@Composable private fun LatencyCalibrationSurface(vm: LatencySettingsViewModel, ui: LatencyUiState, modifier: Modifier) {
    val view = LocalView.current
    val onDown by rememberUpdatedState<(Long) -> Unit> { nanos -> vm.tapDown(nanos); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    val surfaceLabel = t("Tippfläche: genau auf jeden Klick tippen", "Tap surface: tap exactly on every click")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (ui.countingIn) t("Vorzähler …", "Count-in …") else t("Kalibrieren: tippe genau auf jeden Klick", "Calibrating: tap exactly on every click"),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("latency-status"))
        ui.device?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(t("${ui.taps} Tipps", "${ui.taps} taps"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("latency-taps"))
        Box(Modifier.fillMaxWidth().weight(1f)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
            .testTag("latency-tap-surface")
            .semantics { contentDescription = surfaceLabel }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) if (change.changedToDownIgnoreConsumed()) { onDown(eventNanos(change.uptimeMillis)); change.consume() }
                    }
                }
            }, contentAlignment = Alignment.Center) {
            Text(t("Hier tippen", "Tap here"), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        OutlinedButton(onClick = vm::cancelCalibration, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("latency-cancel")) { Text(t("Abbrechen", "Cancel")) }
    }
}
