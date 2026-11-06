package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.capabilities.*
import dev.riffworkbench.editor.Articulations
import dev.riffworkbench.editor.EditorCommand
import dev.riffworkbench.editor.EditorState
import dev.riffworkbench.model.*
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R
import dev.riffworkbench.ui.score.pitchName
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable fun PracticeScreen(vm: WorkspaceViewModel) {
    val practice by vm.practice.collectAsStateWithLifecycle()
    val transport by vm.transport.state.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val appSettings by vm.appSettings.collectAsStateWithLifecycle()
    val tuner by vm.tuner.collectAsStateWithLifecycle()
    var speed by remember(practice.speed) { mutableFloatStateOf(practice.speed.toFloat()) }
    val layout by vm.layout.collectAsStateWithLifecycle()
    val ws by vm.state.collectAsStateWithLifecycle()
    val playing = transport.status == TransportStatus.PLAYING
    val project = ws.editor?.project
    val baseBpm = project?.tempo?.firstOrNull()?.quarterNotesPerMinute
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row {
                    // While the speed trainer runs, the transport's speed is the one that plays.
                    val speed = if (practice.trainer.enabled && playing) transport.speed else practice.speed
                    Text("${(speed * 100).toInt()}%", fontFamily = Tabino.Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Tabino.Lime)
                    Text(" Tempo", fontFamily = Tabino.Display, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                }
                Text(baseBpm?.let { "${(it * (if (practice.trainer.enabled && playing) transport.speed else practice.speed)).toInt()} ${tr("von", "of")} ${it.toInt()} BPM" } ?: project?.metadata?.title.orEmpty(),
                    fontFamily = Tabino.Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Tabino.Faint, maxLines = 1)
            }
            transport.position?.let { pos -> project?.measures?.firstOrNull { it.id == pos.position.measureId }?.let { m -> if (playing && practice.visualMetronome) BeatDots(m.signature, pos.position.offset) } }
        }
        if (transport.status == TransportStatus.UNAVAILABLE || transport.status == TransportStatus.FAILED) NoticeCard(transport.reason ?: stringResource(R.string.transport_unavailable), true)
        TabinoCard(Modifier.fillMaxWidth().height(250.dp)) {
            practice.loop?.takeIf { !playing || transport.loop != null }?.let { l -> Surface(shape = RoundedCornerShape(8.dp), color = Tabino.Lavender.copy(alpha = 0.16f), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp).fillMaxWidth().height(24.dp)) {
                // Written bar numbers (the route index counts repeats and was off behind a repeat).
                val bar = { id: EntityId -> (project?.measures?.indexOfFirst { it.id == id } ?: -1) + 1 }
                Box(contentAlignment = Alignment.Center) { Text("${tr("Schleife", "Loop")} · ${tr("Takt", "bar")} ${bar(l.start.position.measureId)}–${bar(l.end.position.measureId)}", style = MaterialTheme.typography.labelMedium, color = Tabino.Lavender) }
            } }
            val playhead = transport.position?.takeIf { playing || transport.status == TransportStatus.PAUSED }?.let { it.position.measureId to it.position.offset.toDouble() }
            ws.editor?.let { e -> dev.riffworkbench.ui.score.ScoreCanvas(layout = layout, cursor = dev.riffworkbench.ui.score.ScoreCursor(e.selection.measureId, e.selection.offset, null, e.selection.rangeEnd),
                playhead = playhead, follow = view.follow && playing, zoom = view.zoom, onZoom = vm::setZoom, onTap = { tap -> vm.tap(tap.measureId, tap.offset, tap.string); vm.seekToCursor() },
                // Same display scale and pinch setting as the editor (QA 23).
                scale = dev.riffworkbench.notation.ScoreScale.EDITOR, zoomGesture = appSettings.pinchZoom,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp), description = tr("Partitur mit Wiedergabeposition", "Score with playback position")) }
        }
        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceTile(tr("Takt zurück", "Bar back"), false, { vm.moveMeasure(-1); vm.seekToCursor() }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Back5, idle = MaterialTheme.colorScheme.surfaceVariant)
            Surface(onClick = vm::togglePlay, shape = RoundedCornerShape(34.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.weight(1.5f).fillMaxHeight()) {
                Box(contentAlignment = Alignment.Center) { Icon(if (playing) TabinoIcons.Pause else TabinoIcons.Play, if (playing) tr("Pause", "Pause") else stringResource(R.string.play), Modifier.size(36.dp)) }
            }
            ChoiceTile(tr("Takt weiter", "Bar on"), false, { vm.moveMeasure(1); vm.seekToCursor() }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Next5, idle = MaterialTheme.colorScheme.surfaceVariant)
        }
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceTile("", practice.metronome, { vm.setMetronome(!practice.metronome) }, Modifier.width(52.dp).fillMaxHeight(), icon = TabinoIcons.Metronome, accent = Tabino.Lime,
                description = stringResource(R.string.metronome), idle = MaterialTheme.colorScheme.surfaceVariant)
            Surface(onClick = { vm.setSpeed(((practice.speed - 0.05) * 100).let { kotlin.math.round(it) } / 100) }, shape = RoundedCornerShape(26.dp), color = Tabino.Text, contentColor = Tabino.Ink, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(TabinoIcons.Minus, tr("5 Prozent langsamer", "5 percent slower"), Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("5 %", fontFamily = Tabino.Display, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            }
            Surface(onClick = { vm.setSpeed(((practice.speed + 0.05) * 100).let { kotlin.math.round(it) } / 100) }, shape = RoundedCornerShape(26.dp), color = Tabino.Text, contentColor = Tabino.Ink, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(TabinoIcons.Plus, tr("5 Prozent schneller", "5 percent faster"), Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("5 %", fontFamily = Tabino.Display, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val looping = practice.loop != null
            Surface(onClick = { if (looping) vm.clearLoop() else vm.loopSelection() }, shape = RoundedCornerShape(22.dp), color = if (looping) Tabino.Lavender else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (looping) Tabino.Ink else MaterialTheme.colorScheme.onSurface, modifier = Modifier.height(44.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(TabinoIcons.Loop, null, Modifier.size(18.dp)); Text(if (looping) tr("Schleife an", "Loop on") else tr("Auswahl loopen", "Loop selection"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        // Own row (QA 21): next to the loop button the count-in chips were squeezed to 0-23 dp on phones and vanished at
        // font scale 1.3/2.0. Scrolls sideways if label and chips do not fit.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.count_in), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (0..2).forEach { bars -> FilterChip(practice.countInBars == bars, { vm.setCountIn(bars) }, label = { Text(if (bars == 0) tr("aus", "off") else "$bars") }) }
        }
        Text("${stringResource(R.string.speed)} · ${(speed * 100).toInt()} %", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        Slider(speed, { speed = it }, valueRange = 0.25f..1.5f, steps = 24, onValueChangeFinished = { vm.setSpeed(speed.toDouble()) }, modifier = Modifier.semanticsLabel(stringResource(R.string.speed)))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5, 0.7, 0.85, 1.0).forEach { s -> FilterChip(abs(practice.speed - s) < 0.001, { vm.setSpeed(s) }, label = { Text("${(s * 100).toInt()} %") }) }
        }
        ToggleRow(stringResource(R.string.metronome), practice.metronome, vm::setMetronome)
        ToggleRow(tr("Visuelles Metronom (Zählzeit-Punkte)", "Visual metronome (beat dots)"), practice.visualMetronome, { on -> vm.updatePractice { it.copy(visualMetronome = on) } })
        ToggleRow(tr("Cursor folgt der Wiedergabe", "Cursor follows playback"), view.follow, vm::setFollow)
        HorizontalDivider()
        Text("Speed Trainer", style = MaterialTheme.typography.titleMedium)
        val trainer = practice.trainer
        ToggleRow(tr("Nach jedem Loop schneller", "Faster after each loop"), trainer.enabled, { vm.setTrainer(trainer.copy(enabled = it)) })
        Text("${tr("Von", "From")} ${(trainer.startSpeed * 100).toInt()} % ${tr("bis", "to")} ${(trainer.endSpeed * 100).toInt()} %, +${(trainer.increment * 100).toInt()} % ${tr("alle", "every")} ${trainer.repetitions} ${tr("Durchläufe", "passes")}", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5, 0.6, 0.7).forEach { s -> FilterChip(abs(trainer.startSpeed - s) < 0.001, { vm.setTrainer(trainer.copy(startSpeed = s)) }, label = { Text("${tr("Start", "Start")} ${(s * 100).toInt()} %") }) }
            listOf(0.05, 0.1).forEach { s -> FilterChip(abs(trainer.increment - s) < 0.001, { vm.setTrainer(trainer.copy(increment = s)) }, label = { Text("+${(s * 100).toInt()} %") }) }
            listOf(1, 2, 4).forEach { r -> FilterChip(trainer.repetitions == r, { vm.setTrainer(trainer.copy(repetitions = r)) }, label = { Text("×$r") }) }
        }
        if (trainer.enabled && practice.loop == null) Text(tr("Der Trainer braucht einen aktiven Loop.", "The trainer needs an active loop."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        TunerCard(vm, tuner)
    }
}

@Composable private fun TunerCard(vm: WorkspaceViewModel, tuner: TunerState) {
    DisposableEffect(Unit) { onDispose { vm.stopTuner() } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.startTuner() else vm.showNotice("Ohne Mikrofonzugriff kann das Stimmgerät nicht messen.") }
    val start = {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) vm.startTuner()
        else permission.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Stimmgerät", "Tuner"), style = MaterialTheme.typography.titleMedium)
        val r = tuner.reading
        if (tuner.running && r != null) {
            val cents = r.deviationCents
            Text("${pitchName(r.pitch.midiNumber)}${r.pitch.midiNumber / 12 - 1}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                color = if (abs(cents) < 5) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text("${if (cents >= 0) "+" else ""}${"%.1f".format(cents)} cent", fontFamily = FontFamily.Monospace)
            LinearProgressIndicator(progress = { ((cents + 50.0) / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else if (tuner.running) Text(tr("Saite anschlagen …", "Play a string …"))
        tuner.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!tuner.running) Button(onClick = { start() }) { Text(tr("Stimmgerät starten", "Start tuner")) }
            else OutlinedButton(onClick = vm::stopTuner) { Text(tr("Stoppen", "Stop")) }
        }
        Text(tr("Nutzt das Mikrofon nur, solange das Stimmgerät läuft. Kein Audio verlässt das Gerät.", "Uses the microphone only while running. No audio leaves the device."), style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable fun AudioScreen(vm: WorkspaceViewModel, state: EditorState, onImport: () -> Unit) {
    val practice by vm.practice.collectAsStateWithLifecycle()
    // Only the reason: the whole transport state changes on every poll while playing and recomposed the page with its
    // clip sliders 30 times a second (QA 23).
    val transport = vm.transport.state.collectAsStateWithLifecycle()
    val transportReason by remember(transport) { derivedStateOf { transport.value.reason } }
    val ui by vm.state.collectAsStateWithLifecycle()
    val clips = state.project.audio.clips
    ScrollPage(stringResource(R.string.audio), stringResource(R.string.audio_hint)) {
        Button(onClick = onImport, enabled = ui.busy == null && clips.size < 16) { Text(tr("Audiodatei importieren …", "Import audio file …")) }
        Text(tr("WAV immer; MP3, AAC/M4A, Ogg und FLAC über die Decoder des Geräts. Bis 64 MiB, wiedergegeben werden höchstens 10 Minuten. Die Datei bleibt privat im Projekt.",
            "WAV always; MP3, AAC/M4A, Ogg and FLAC through the device decoders. Up to 64 MiB, at most 10 minutes are played. The file stays private in the project."), style = MaterialTheme.typography.bodySmall)
        if (clips.isNotEmpty() && abs(practice.speed - 1.0) > 1e-9) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Referenz-Audio ist nur bei 100 % Tempo hörbar – Time-Stretch fehlt noch.", "Reference audio only sounds at 100 % speed – time-stretch is not implemented yet."), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.setSpeed(1.0) }) { Text("100 %") }
            }
        }
        transportReason?.takeIf { it.startsWith("Referenz-Audio") }?.let { NoticeCard(it, true) }
        if (clips.isEmpty()) Text(stringResource(R.string.audio_empty), style = MaterialTheme.typography.titleMedium)
        clips.forEach { clip -> ClipCard(vm, clip) }
        if (clips.isNotEmpty()) Text(tr("Ausrichten: „Takt 1 im Audio bei“ ist die Stelle der Aufnahme, an der Takt 1 der Partitur beginnt. Solo gilt gemeinsam für Spuren und Clips. Loops, Sprünge und Count-in bleiben sample-genau synchron, solange das Partitur-Tempo zur Aufnahme passt.",
            "Alignment: “Bar 1 in audio at” is the point in the recording where bar 1 of the score starts. Solo applies to tracks and clips together. Loops, seeks and count-in stay sample-locked as long as the score tempo matches the recording."), style = MaterialTheme.typography.bodySmall)
        FeatureRegistry.all.filter { it.id in listOf("audio.sync_modes", "audio.shared_map", "audio.pitch_stretch") }.forEach { FeatureCard(it) }
    }
}

@Composable private fun ClipCard(vm: WorkspaceViewModel, clip: AudioClip) {
    var gain by remember(clip.gain) { mutableFloatStateOf(clip.gain.toFloat()) }
    var pan by remember(clip.pan) { mutableFloatStateOf(clip.pan.toFloat()) }
    var confirmRemove by remember(clip.id) { mutableStateOf(false) }
    val barOneAt = -clip.offsetMicros / 1_000_000.0
    fun shift(seconds: Double) = vm.updateClip(clip.copy(offsetMicros = (clip.offsetMicros - (seconds * 1_000_000).toLong()).coerceIn(-600_000_000L, 600_000_000L)))
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clip.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (confirmRemove) {
                TextButton(onClick = { vm.removeClip(clip.id) }) { Text(tr("Wirklich entfernen", "Really remove"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { confirmRemove = false }) { Text(tr("Abbrechen", "Cancel")) }
            } else TextButton(onClick = { confirmRemove = true }) { Text(tr("Entfernen", "Remove")) }
        }
        val locale = if (english()) java.util.Locale.ROOT else java.util.Locale.GERMANY
        Text(tr("Takt 1 im Audio bei ", "Bar 1 in audio at ") + "%.3f s".format(locale, barOneAt), style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
        ClipWaveform(vm, clip)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-1.0 to "−1 s", -0.1 to tr("−0,1 s", "−0.1 s"), -0.01 to "−10 ms", 0.01 to "+10 ms", 0.1 to tr("+0,1 s", "+0.1 s"), 1.0 to "+1 s").forEach { (delta, label) ->
                OutlinedButton(onClick = { shift(delta) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
            }
            TextButton(onClick = { vm.updateClip(clip.copy(offsetMicros = 0)) }) { Text("0") }
        }
        val db = if (gain <= 0.0001f) "−∞" else "%+.1f".format(locale, 20 * kotlin.math.log10(gain.toDouble()))
        Text(tr("Pegel", "Level") + " $db dB", style = MaterialTheme.typography.labelLarge)
        Slider(gain, { gain = it }, valueRange = 0f..2f, onValueChangeFinished = { vm.updateClip(clip.copy(gain = gain.toDouble())) }, modifier = Modifier.semanticsLabel(tr("Pegel", "Level")))
        Text("Pan " + when { pan < -0.02f -> "L ${(-pan * 100).toInt()}"; pan > 0.02f -> "R ${(pan * 100).toInt()}"; else -> "C" }, style = MaterialTheme.typography.labelLarge)
        Slider(pan, { pan = it }, valueRange = -1f..1f, onValueChangeFinished = { vm.updateClip(clip.copy(pan = pan.toDouble())) }, modifier = Modifier.semanticsLabel("Pan"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(clip.mute, { vm.updateClip(clip.copy(mute = !clip.mute)) }, label = { Text("Mute") })
            FilterChip(clip.solo, { vm.updateClip(clip.copy(solo = !clip.solo)) }, label = { Text("Solo") })
        }
    } }
}

/** Waveform strip of a clip around the bar-1 marker; tapping places bar 1 at the tapped point of the recording. */
@Composable private fun ClipWaveform(vm: WorkspaceViewModel, clip: AudioClip) {
    val level by produceState<WaveformLevel?>(null, clip.asset) { value = vm.waveform(clip.asset) }
    var span by remember(clip.id) { mutableFloatStateOf(20f) }
    val wave = level
    if (wave == null || wave.minimum.isEmpty() || wave.secondsPerPeak <= 0.0) {
        Text(tr("Wellenform wird berechnet …", "Computing waveform …"), style = MaterialTheme.typography.bodySmall)
        return
    }
    val barOneAt = -clip.offsetMicros / 1_000_000.0
    val total = wave.durationSeconds
    val visible = if (span <= 0f) total else minOf(span.toDouble(), total)
    val start = if (span <= 0f) 0.0 else (barOneAt - visible / 4).coerceIn(0.0, maxOf(0.0, total - visible))
    val waveColor = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.error
    val description = tr("Wellenform, Takt 1 bei %.2f Sekunden".format(java.util.Locale.GERMANY, barOneAt), "Waveform, bar 1 at %.2f seconds".format(java.util.Locale.ROOT, barOneAt))
    Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = description }
        .pointerInput(clip, start, visible) {
            detectTapGestures { pos ->
                val t = start + pos.x / size.width * visible
                vm.updateClip(clip.copy(offsetMicros = -(t * 1_000_000).roundToLong()))
            }
        }) {
        val w = size.width; val mid = size.height / 2
        if (w < 1f || visible <= 0.0) return@Canvas
        val perPx = visible / w
        var x = 0
        while (x < w.toInt()) {
            val t0 = start + x * perPx
            val i0 = (t0 / wave.secondsPerPeak).toInt()
            if (i0 >= wave.minimum.size) break
            val i1 = maxOf(i0 + 1, ((t0 + perPx) / wave.secondsPerPeak).toInt()).coerceAtMost(wave.minimum.size)
            var lo = 1f; var hi = -1f
            for (i in i0 until i1) { lo = minOf(lo, wave.minimum[i]); hi = maxOf(hi, wave.maximum[i]) }
            if (hi >= lo) drawLine(waveColor, Offset(x.toFloat(), mid - hi * mid), Offset(x.toFloat(), mid - lo * mid + 1f), strokeWidth = 1f)
            x++
        }
        val marker = ((barOneAt - start) / visible * w).toFloat()
        if (marker in 0f..w) drawLine(markerColor, Offset(marker, 0f), Offset(marker, size.height), strokeWidth = 2.dp.toPx())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(4f to "4 s", 20f to "20 s", 0f to tr("Gesamt", "All")).forEach { (s, label) -> FilterChip(span == s, { span = s }, label = { Text(label) }) }
        Text(tr("Antippen setzt Takt 1", "Tap to place bar 1"), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun EffectsScreen(vm: WorkspaceViewModel, state: EditorState) {
    val note = vm.selectedNote(state)
    ScrollPage(stringResource(R.string.effects), stringResource(R.string.effect_hint)) {
        Text(note?.let { tr("Ausgewählte Note: Bund ${it.fret ?: "–"}, Saite ${(it.string ?: 0) + 1}", "Selected note: fret ${it.fret ?: "–"}, string ${(it.string ?: 0) + 1}") } ?: tr("Im Editor eine Note wählen.", "Select a note in the editor."), style = MaterialTheme.typography.bodyMedium)
        @Composable fun group(title: String, items: List<Pair<String, () -> Any?>>) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { items.forEach { (label, action) -> AssistChip(onClick = { action() }, label = { Text(label) }, enabled = note != null) } }
        }
        group(tr("Anschlag", "Attack"), listOf("Palm Mute" to { vm.toggleMark(NoteMark.PALM_MUTE) }, "Dead" to { vm.toggleMark(NoteMark.DEAD) }, "Ghost" to { vm.toggleMark(NoteMark.GHOST) },
            tr("Akzent", "Accent") to { vm.toggleMark(NoteMark.ACCENT) }, tr("Starker Akzent", "Heavy accent") to { vm.toggleMark(NoteMark.HEAVY_ACCENT) }, "Staccato" to { vm.toggleMark(NoteMark.STACCATO) },
            "Let Ring" to { vm.toggleMark(NoteMark.LET_RING) }, "Pick Scrape" to { vm.toggleMark(NoteMark.PICK_SCRAPE) }))
        group(tr("Legato", "Legato"), listOf("Hammer-on" to { vm.toggleMark(NoteMark.HAMMER_ON) }, "Pull-off" to { vm.toggleMark(NoteMark.PULL_OFF) }, tr("Haltebogen", "Tie") to { vm.command(EditorCommand.ToggleTie) },
            "Tapping" to { vm.toggleMark(NoteMark.TAPPING) }, "Slap" to { vm.toggleMark(NoteMark.SLAP) }, "Pop" to { vm.toggleMark(NoteMark.POP) }))
        group("Slides", SlideKind.entries.map { kind -> kind.name.lowercase().replace('_', ' ') to { vm.articulation(Articulation.Slide(kind)) } })
        group("Bends", listOf("full" to { vm.articulation(Articulations.fullBend()) }, "½" to { vm.articulation(Articulations.halfBend()) }, "¼" to { vm.articulation(Articulations.quarterBend()) },
            "bend/release" to { vm.articulation(Articulations.bendRelease()) }, "prebend" to { vm.articulation(Articulations.prebend()) }, "prebend/release" to { vm.articulation(Articulations.prebendRelease()) },
            "Whammy dip" to { vm.articulation(Articulation.Whammy(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 2), -200), PitchCurvePoint(Fraction.ONE, 0)))) }))
        group(tr("Flageoletts", "Harmonics"), HarmonicKind.entries.map { kind -> kind.name.lowercase() to { vm.articulation(Articulation.Harmonic(kind)) } })
        group(tr("Modulation", "Modulation"), listOf("Vibrato" to { vm.articulation(Articulation.Vibrato()) }, tr("Breites Vibrato", "Wide vibrato") to { vm.articulation(Articulation.Vibrato(depthCents = 70, rateHz = 4.5)) },
            "Trill +2" to { note?.fret?.let { vm.articulation(Articulation.Trill(it + 2)) } }, "Tremolo 1/16" to { vm.articulation(Articulation.TremoloPicking(WrittenDuration(16))) }, "Tremolo 1/32" to { vm.articulation(Articulation.TremoloPicking(WrittenDuration(32))) }))
        group(tr("Vorschlag", "Grace note"), listOf(tr("Vorschlag −2", "Grace −2") to { note?.let { n -> n.fret?.let { f -> if (f >= 2) vm.articulation(Articulation.Grace(n.pitch.copy(midiNumber = n.pitch.midiNumber - 2), fret = f - 2, string = n.string)) } } },
            tr("Vorschlag entfernen", "Remove grace") to { vm.command(EditorCommand.ClearArticulation("grace")) }))
        group(tr("Dynamik", "Dynamics"), DynamicLevel.entries.map { level -> level.name.lowercase() to { vm.articulation(Articulation.Dynamic(level)) } })
        group(tr("Beat", "Beat"), listOf("Fermate" to { vm.command(EditorCommand.SetFermata(if (vm.selectedBeat(state)?.fermata == null) Fermata() else null)) },
            "Arpeggio ↑" to { vm.command(EditorCommand.SetStrum(Strum(StrumKind.ARPEGGIO, StrokeDirection.DOWN))) }, "Rasgueado" to { vm.command(EditorCommand.SetStrum(Strum(StrumKind.RASGUEADO, StrokeDirection.DOWN))) },
            tr("Strum entfernen", "Remove strum") to { vm.command(EditorCommand.SetStrum(null)) }, "⊓ Down" to { vm.articulation(Articulation.Pickstroke(StrokeDirection.DOWN)) }, "V Up" to { vm.articulation(Articulation.Pickstroke(StrokeDirection.UP)) }))
        FeatureRegistry.group("articulations").forEach { FeatureCard(it) }
    }
}

@Composable fun LayoutScreen(vm: WorkspaceViewModel) {
    val view by vm.view.collectAsStateWithLifecycle()
    ScrollPage(stringResource(R.string.layout), stringResource(R.string.layout_hint)) {
        Text(tr("Notation", "Notation"), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(NotationMode.TAB to "TAB", NotationMode.TAB_AND_STANDARD to tr("Noten + TAB", "Standard + TAB"), NotationMode.STANDARD to tr("Noten", "Standard")).forEach { (mode, label) ->
                FilterChip(view.notation == mode, { vm.setNotation(mode) }, label = { Text(label) })
            }
        }
        ToggleRow(tr("Zeilenumbruch (Seitenansicht)", "Wrapped systems (page view)"), view.wrap, vm::setWrap)
        ToggleRow(tr("Kompakt (Lesen statt Eingeben)", "Compact (reading instead of entry)"), view.compact, vm::setCompact)
        Text("Zoom ${(view.zoom * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
        var zoom by remember(view.zoom) { mutableFloatStateOf(view.zoom) }
        Slider(zoom, { zoom = it }, valueRange = 0.5f..2.5f, onValueChangeFinished = { vm.setZoom(zoom) }, modifier = Modifier.semanticsLabel("Zoom"))
        FeatureRegistry.all.filter { it.id.startsWith("layout.") || it.id.startsWith("notation.") }.forEach { FeatureCard(it) }
    }
}

@Composable fun ExchangeScreen(vm: WorkspaceViewModel, actions: ExportActions) {
    val ws by vm.state.collectAsStateWithLifecycle()
    val en = english()
    ScrollPage(stringResource(R.string.exchange), tr("Exportiert das aktuelle Projekt. Die Projektdatei ist die einzige vollständige Fassung; jedes Fremdformat meldet, was es nicht abbilden kann.",
        "Exports the current project. The project file is the only complete version; every foreign format reports what it cannot represent.")) {
        ExportTarget.entries.forEach { target ->
            OutlinedButton(onClick = {
                when {
                    target == ExportTarget.PDF -> actions.pdf()
                    target.streamed -> actions.stream(target)
                    else -> vm.export(target, actions.bytes)
                }
            }, enabled = ws.busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (en) target.labelEn else target.labelDe) }
        }
        if (ws.lastLosses.isNotEmpty()) {
            Text(tr("Verlustbericht des letzten Exports", "Loss report of the last export"), style = MaterialTheme.typography.titleMedium)
            ws.lastLosses.groupBy { it.featureId }.forEach { (feature, entries) -> Text("• $feature: ${entries.first().reason}" + if (entries.size > 1) " (×${entries.size})" else "", style = MaterialTheme.typography.bodySmall) }
        }
        ws.editor?.project?.provenance.orEmpty().filter { it.losses.isNotEmpty() }.forEach { origin ->
            Text(tr("Beim Import (${origin.format}) nicht oder nur angenähert übernommen", "Not or only approximately imported (${origin.format})"), style = MaterialTheme.typography.titleMedium)
            origin.losses.groupBy { it.featureId }.forEach { (feature, entries) -> Text("• $feature: ${entries.first().reason}" + if (entries.size > 1) " (×${entries.size})" else "", style = MaterialTheme.typography.bodySmall) }
        }
        NoticeCard(tr("GP7-kompatibler Export ist nur gegen die eigene Importlogik geprüft. Das Öffnen in Guitar Pro 7/8 Desktop ist noch nicht verifiziert.",
            "The GP7-compatible export is only checked against the app's own importer. Opening in Guitar Pro 7/8 desktop is not verified yet."))
        FeatureRegistry.group("exchange").filter { it.id.startsWith("import.") || it.id.startsWith("export.") }.forEach { FeatureCard(it) }
    }
}

@Composable fun IntegrationsScreen(onSettings: () -> Unit) {
    ScrollPage(stringResource(R.string.integrations), stringResource(R.string.integration_hint)) {
        FeatureRegistry.all.filter { it.id.startsWith("integration.") || it.id == "workflow.midi_input" }.forEach { FeatureCard(it) }
        OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.settings)) }
    }
}

@Composable fun StatusScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }
    val en = english()
    val features = FeatureRegistry.all.filter { (group.isEmpty() || it.group == group) && (query.isBlank() || it.id.contains(query, true) || it.labelDe.contains(query, true) || it.labelEn.contains(query, true)) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(stringResource(R.string.capabilities), "${FeatureRegistry.all.size} ${stringResource(R.string.feature_count)}")
        Text(stringResource(R.string.status_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_features)) }, modifier = Modifier.textEditing().fillMaxWidth(), singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(group.isEmpty(), { group = "" }, label = { Text(if (en) "All" else "Alle") })
            FeatureRegistry.all.distinctBy { it.group }.forEach { feature -> FilterChip(group == feature.group, { group = feature.group }, label = { Text(if (en) feature.group else feature.groupDe) }) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) { items(features, key = { it.id }) { FeatureCard(it) } }
    }
}
