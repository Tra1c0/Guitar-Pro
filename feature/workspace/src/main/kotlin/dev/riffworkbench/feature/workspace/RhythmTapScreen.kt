package dev.riffworkbench.feature.workspace

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.editor.EditorState
import dev.riffworkbench.editor.RhythmEvent
import dev.riffworkbench.editor.RhythmGrid
import dev.riffworkbench.editor.RhythmMode
import dev.riffworkbench.editor.RhythmResult
import dev.riffworkbench.model.InstrumentKind
import dev.riffworkbench.model.WrittenDuration
import dev.riffworkbench.ui.components.BigTitle
import dev.riffworkbench.ui.components.ChoiceTile
import dev.riffworkbench.ui.components.TabinoIcons

/**
 * Zyklus 25 „Rhythmus tippen“: full-screen dialog over the editor (a dialog, not a bottom sheet: a held finger that moves a
 * little must not drag a sheet). Setup → count-in/recording with the large tap surface → preview → apply or discard.
 * Notices (ViewModel) show over the dialog's top through [SheetNoticeOverlay].
 */
@Composable internal fun RhythmTapDialog(vm: WorkspaceViewModel, state: EditorState) {
    val rhythm by vm.rhythm.collectAsStateWithLifecycle()
    if (rhythm.phase == RhythmTapPhase.IDLE) return
    Dialog(onDismissRequest = { vm.closeRhythmTap() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag("rhythm-dialog"), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BigTitle(tr("Rhythmus tippen", "Tap rhythm"), rhythm.label.ifEmpty { null }, Modifier.weight(1f), size = 22)
                        IconButton(onClick = { vm.closeRhythmTap() }, modifier = Modifier.testTag("rhythm-close")) { Icon(TabinoIcons.Close, tr("Schließen", "Close")) }
                    }
                    when (rhythm.phase) {
                        RhythmTapPhase.SETUP -> RhythmSetup(vm, state, rhythm, Modifier.weight(1f))
                        RhythmTapPhase.COUNT_IN, RhythmTapPhase.RECORDING, RhythmTapPhase.CALIBRATING -> RhythmRecording(vm, rhythm, Modifier.weight(1f))
                        RhythmTapPhase.PREVIEW -> RhythmPreview(vm, state, rhythm, Modifier.weight(1f))
                        RhythmTapPhase.IDLE -> Unit
                    }
                }
                SheetNoticeOverlay(vm)
            }
        }
    }
    rhythm.confirm?.let { mode ->
        AlertDialog(onDismissRequest = { vm.cancelRhythmConfirm() },
            title = { Text(tr("Takte überschreiben?", "Overwrite bars?")) },
            text = { Text(tr("Überschreibt ${rhythm.overwrites} Noten in dieser Stimme.", "Overwrites ${rhythm.overwrites} notes in this voice.")) },
            confirmButton = { TextButton(onClick = { vm.applyRhythm(mode, confirmed = true) }, modifier = Modifier.testTag("rhythm-confirm")) { Text(tr("Überschreiben", "Overwrite")) } },
            dismissButton = { TextButton(onClick = { vm.cancelRhythmConfirm() }) { Text(tr("Abbrechen", "Cancel")) } })
    }
}

@Composable private fun Section(title: String) =
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A row of equal tiles (48 dp high). */
@Composable private fun TileRow(items: List<Triple<String, Boolean, () -> Unit>>, tagPrefix: String) {
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, (label, on, action) ->
            ChoiceTile(label, on, action, Modifier.weight(1f).fillMaxHeight().testTag("$tagPrefix-$i"), idle = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable private fun GridChoice(vm: WorkspaceViewModel, rhythm: RhythmTapState) {
    Section(tr("Raster", "Grid"))
    TileRow(RhythmGrid.entries.map { g -> Triple(g.label, rhythm.grid == g) { vm.setRhythmGrid(g) } }, "rhythm-grid")
}

/** String (fretted) or kit piece (drums) for „Leersaite“. */
@Composable private fun SoundChoice(vm: WorkspaceViewModel, state: EditorState, rhythm: RhythmTapState) {
    val track = state.project.tracks.firstOrNull { it.id == state.selection.trackId } ?: return
    if (rhythm.fretted) {
        Section(tr("Saite für „Leersaite“", "String for “open string”"))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Lowest string first (tuning is low → high); the label is the open string's pitch.
            track.instrument.tuning.indices.forEach { s ->
                ChoiceTile("${s + 1} · " + dev.riffworkbench.ui.score.pitchName(track.instrument.pitch(s, 0)), rhythm.string == s, { vm.setRhythmString(s) },
                    Modifier.size(56.dp, 44.dp).testTag("rhythm-string-$s"), idle = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    } else if (track.instrument.kind == InstrumentKind.PERCUSSION) {
        Section(tr("Kit-Element für „Leersaite“", "Kit piece for “open string”"))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            track.instrument.drumKit.forEach { element ->
                ChoiceTile(element.name, rhythm.drumNote == element.midiNote, { vm.setRhythmDrumNote(element.midiNote) },
                    Modifier.size(84.dp, 44.dp).testTag("rhythm-drum-${element.midiNote}"), idle = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable private fun RhythmSetup(vm: WorkspaceViewModel, state: EditorState, rhythm: RhythmTapState, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Tippe zum Metronom. Lang halten = langer Notenwert, kurz tippen = kurze Note mit Pausen danach.",
            "Tap along with the metronome. Hold = long value, quick tap = short note followed by rests."), style = MaterialTheme.typography.bodyMedium)
        Section(tr("Zielbereich", "Target"))
        if (state.selection.rangeEnd != null) TileRow(listOf(
            Triple(tr("Markierter Bereich", "Selected range"), rhythm.useRange) { vm.setRhythmUseRange(true) },
            Triple(tr("Ab Cursor", "From cursor"), !rhythm.useRange) { vm.setRhythmUseRange(false) }), "rhythm-target")
        if (state.selection.rangeEnd == null || !rhythm.useRange)
            TileRow(listOf(1, 2, 4, 8).map { n -> Triple(if (n == 1) tr("1 Takt", "1 bar") else tr("$n Takte", "$n bars"), rhythm.barCount == n) { vm.setRhythmBarCount(n) } }, "rhythm-bars")
        GridChoice(vm, rhythm)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Begleitung hören (Zielspur stumm)", "Hear the other tracks (target track muted)"), Modifier.weight(1f))
            Switch(rhythm.accompaniment, { vm.setRhythmAccompaniment(it) }, Modifier.testTag("rhythm-accompaniment"))
        }
        SoundChoice(vm, state, rhythm)
        Section(tr("Latenz", "Latency"))
        // Zyklus 26: the value of the active output device; without its own calibration the device-less value applies.
        rhythm.latencyDevice?.let { device ->
            Text(tr("Ausgabegerät: $device", "Output: $device"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rhythm-latency-device"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val millis = kotlin.math.round(rhythm.latencyMs).toInt()
            val uncalibrated = rhythm.latencyDevice != null && !rhythm.latencyOwn
            Text(if (uncalibrated) tr("Ausgleich: $millis ms (Gerät nicht kalibriert)", "Offset: $millis ms (device not calibrated)") else tr("Ausgleich: $millis ms", "Offset: $millis ms"),
                Modifier.weight(1f))
            OutlinedButton(onClick = { vm.startTapCalibration() }, modifier = Modifier.testTag("rhythm-calibrate")) { Text(tr("Kalibrieren", "Calibrate")) }
            if (if (rhythm.latencyDevice != null) rhythm.latencyOwn else rhythm.latencyMs != 0.0) TextButton(onClick = { vm.resetTapCalibration() }) { Text(tr("Zurücksetzen", "Reset")) }
        }
        Button(onClick = { vm.startRhythmTap() }, enabled = rhythm.bars.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp).testTag("rhythm-start")) {
            Text(tr("Start (1 Takt Vorzähler)", "Start (1 bar count-in)"), fontWeight = FontWeight.Bold)
        }
    }
}

/** System.nanoTime of a pointer event: its uptime (ms, same monotonic clock) unless that is implausible (test clocks). */
private fun eventNanos(uptimeMillis: Long): Long {
    val now = System.nanoTime()
    val event = uptimeMillis * 1_000_000L
    return if (event in now - 1_000_000_000L..now) event else now
}

@Composable private fun RhythmRecording(vm: WorkspaceViewModel, rhythm: RhythmTapState, modifier: Modifier) {
    val view = LocalView.current
    val status = when (rhythm.phase) {
        RhythmTapPhase.COUNT_IN -> tr("Vorzähler …", "Count-in …")
        RhythmTapPhase.CALIBRATING -> tr("Kalibrieren: tippe genau auf jeden Klick", "Calibrating: tap exactly on every click")
        else -> tr("Aufnahme – tippe den Rhythmus", "Recording – tap the rhythm")
    }
    val onDown by rememberUpdatedState<(Long) -> Unit> { nanos -> vm.rhythmTapDown(nanos); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    val onUp by rememberUpdatedState<(Long) -> Unit> { nanos -> vm.rhythmTapUp(nanos) }
    val surfaceLabel = tr("Tippfläche: zum Metronom tippen", "Tap surface: tap along with the metronome")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("rhythm-status"))
        Text(tr("${rhythm.taps} Anschläge", "${rhythm.taps} taps"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("rhythm-taps"))
        // The tap surface: the whole width, most of the height. Its own handler takes every finger's down and up with the
        // event time (not the frame it is handled in), so two-finger drumming works and the handler's lag does not count.
        Box(Modifier.fillMaxWidth().weight(1f)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
            .testTag("rhythm-tap-surface")
            .semantics { contentDescription = surfaceLabel }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (change.changedToDownIgnoreConsumed()) { onDown(eventNanos(change.uptimeMillis)); change.consume() }
                            else if (change.changedToUpIgnoreConsumed()) { onUp(eventNanos(change.uptimeMillis)); change.consume() }
                        }
                    }
                }
            }, contentAlignment = Alignment.Center) {
            Text(tr("Hier tippen", "Tap here"), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        OutlinedButton(onClick = { vm.cancelRhythmTap() }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("rhythm-cancel")) { Text(tr("Abbrechen", "Cancel")) }
    }
}

@Composable private fun RhythmPreview(vm: WorkspaceViewModel, state: EditorState, rhythm: RhythmTapState, modifier: Modifier) {
    val result = rhythm.result
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (result == null) Text(tr("Keine Anschläge erkannt", "No taps recognised"))
        else {
            Text(previewSummary(result), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("rhythm-summary"))
            RhythmLine(result)
        }
        GridChoice(vm, rhythm)
        SoundChoice(vm, state, rhythm)
        val ready = result != null && result.onsets > 0
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.applyRhythm(RhythmMode.GRID) }, enabled = ready, modifier = Modifier.weight(1f).fillMaxHeight().testTag("rhythm-apply-grid")) { Text(tr("Übernehmen: Raster", "Apply: grid"), textAlign = TextAlign.Center) }
            Button(onClick = { vm.applyRhythm(RhythmMode.OPEN_STRING) }, enabled = ready, modifier = Modifier.weight(1f).fillMaxHeight().testTag("rhythm-apply-open")) {
                Text(if (rhythm.drums) tr("Übernehmen: Schlag", "Apply: hits") else tr("Übernehmen: Leersaite", "Apply: open string"), textAlign = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.discardRhythm() }, modifier = Modifier.weight(1f).fillMaxHeight().testTag("rhythm-discard")) { Text(tr("Verwerfen", "Discard")) }
            OutlinedButton(onClick = { vm.retryRhythm() }, modifier = Modifier.weight(1f).fillMaxHeight().testTag("rhythm-retry")) { Text(tr("Nochmal", "Again")) }
        }
    }
}

@Composable private fun previewSummary(r: RhythmResult): String = buildString {
    append(tr("${r.onsets} Anschläge", "${r.onsets} attacks"))
    if (r.tripletBeats > 0) append(tr(", ${r.tripletBeats} triolische Zählzeiten", ", ${r.tripletBeats} triplet beats"))
    if (r.merged > 0) append(tr(", ${r.merged} Doppeltipps zusammengeführt", ", ${r.merged} double taps merged"))
    if (r.discarded > 0) append(tr(", ${r.discarded} außerhalb verworfen", ", ${r.discarded} outside discarded"))
}

/** Written value as a short label: 1/4, 1/8. (dotted), 1/8³ (triplet). */
private fun valueLabel(d: WrittenDuration): String = "1/${d.denominator}" + ".".repeat(d.dots) + if (d.tuplets.isNotEmpty()) "³" else ""

/**
 * Rhythm line: one row per bar, each event as a box whose width is its duration (notes filled, rests outlined, a held
 * continuation starts with „⌒“). Read-only; the score shows the applied result.
 */
@Composable private fun RhythmLine(result: RhythmResult) {
    Column(Modifier.fillMaxWidth().testTag("rhythm-preview"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        result.bars.forEachIndexed { b, bar ->
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                bar.events.forEach { e ->
                    val weight = (e.duration.length().toDouble() / bar.length.toDouble()).toFloat().coerceAtLeast(0.01f)
                    val note = e.kind != RhythmEvent.Kind.REST
                    val shape = RoundedCornerShape(6.dp)
                    Box(Modifier.weight(weight).fillMaxHeight()
                        .background(if (note) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, shape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape), contentAlignment = Alignment.Center) {
                        Text((if (e.kind == RhythmEvent.Kind.CONTINUATION) "⌒" else "") + (if (note) "" else "𝄽") + valueLabel(e.duration),
                            fontSize = 9.sp, maxLines = 1, color = if (note) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (b == result.bars.lastIndex) Spacer(Modifier.height(2.dp))
        }
    }
}
