package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.riffworkbench.model.Outcome
import dev.riffworkbench.ports.AudioStats
import dev.riffworkbench.ports.StressSample
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Device acceptance tools: live engine measurements, note-on latency, a stress run with memory and thermals,
 * and a loudness-matched blind comparison of the modelled and the sampled guitar.
 */
@Composable fun DiagnosticsScreen(vm: WorkspaceViewModel) {
    val lab = vm.audioLab
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var stats by remember { mutableStateOf<AudioStats?>(null) }
    var stressJob by remember { mutableStateOf<Job?>(null) }
    var stressLive by remember { mutableStateOf<StressSample?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lab) { while (lab != null) { stats = lab.stats(); delay(500) } }
    ScrollPage(stringResource(R.string.audio_diagnostics), tr("Messwerte vom laufenden Gerät für den Prüfplan. Nichts hier ist geschätzt: Werte erscheinen, sobald die Wiedergabe einmal lief.",
        "Measurements from the running device for the test plan. Nothing here is estimated: values appear once playback has run.")) {
        if (lab == null) { NoticeCard(tr("Diagnose ist in diesem Build nicht verfügbar.", "Diagnostics are not available in this build."), true); return@ScrollPage }
        TabinoCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("Live", "Live"), style = MaterialTheme.typography.titleMedium)
            val s = stats
            if (s == null) Text(tr("Noch keine Ausgabe geöffnet – einmal abspielen oder unten „Note-on messen“.", "No output opened yet – play once or use “Measure note-on” below."), style = MaterialTheme.typography.bodySmall)
            else {
                Line(tr("Gerät", "Device"), "${s.deviceName} · ${s.sampleRate} Hz · Block ${s.blockFrames}")
                // Zyklus 28: the rate the track consumes vs. the rate the engine renders at; any difference changes tempo and pitch.
                Line(tr("Ausgabe / Engine", "Output / engine"), tr("Ausgabe: ${s.sampleRate} Hz, Engine: ${s.engineRate} Hz", "Output: ${s.sampleRate} Hz, engine: ${s.engineRate} Hz") +
                    (if (s.deviceRate > 0 && s.deviceRate != s.sampleRate) tr(" · Gerät ${s.deviceRate} Hz (System resampelt)", " · device ${s.deviceRate} Hz (resampled by the system)") else "") +
                    (if (s.engineRate > 0 && s.engineRate != s.sampleRate) tr(" – ABWEICHUNG", " – MISMATCH") else ""))
                Line(tr("Gitarren-Samples", "Guitar samples"), if (s.hasSamples) tr("aktiv", "active") else tr("nicht geladen (modelliert)", "not loaded (modelled)"))
                Line(tr("Schlagzeug-Samples", "Drum samples"), if (s.hasDrumSamples) tr("aktiv (ArtiFret Pearl Kit)", "active (ArtiFret Pearl Kit)") else tr("nicht geladen (synthetisch)", "not loaded (synthetic)"))
                Line(tr("Renderzeit Median / P99 / Max", "Render median / P99 / max"), "%.0f / %.0f / %.0f µs".format(s.renderMedianMicros, s.renderP99Micros, s.renderMaxMicros))
                Line(tr("Budget pro Block", "Budget per block"), "%.0f µs · Last %.0f %%".format(s.budgetMicros, 100 * s.renderMedianMicros / s.budgetMicros))
                Line(tr("Überläufe / Underruns", "Overruns / underruns"), "${s.overruns} / ${s.underruns}")
                Line(tr("Ausgabelatenz", "Output latency"), s.outputLatencyMs?.let { "%.1f ms".format(it) } ?: tr("keine Zeitstempel", "no timestamps"))
                Line(tr("Note-on bis Lautsprecher", "Note-on to speaker"), s.noteOnLatencyMs?.let { "%.1f ms".format(it) } ?: "–")
                Line(tr("Stimmen", "Voices"), "${s.voices}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::auditionSelection, shape = RoundedCornerShape(22.dp)) { Text(tr("Note-on messen", "Measure note-on")) }
            }
            Text(tr("Hinweis: Die Ausgabelatenz misst Puffer und Treiber bis zum Ausgang. Lautsprecher-Schall oder Bluetooth-Verzögerung danach misst sie nicht.",
                "Note: output latency covers buffers and driver up to the output, not speaker acoustics or Bluetooth delay after it."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }

        TabinoCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Stresstest", "Stress test"), style = MaterialTheme.typography.titleMedium)
            Text(tr("8 Gitarrenspuren (High Gain/Crunch) mit Palm Mutes, Akkorden, 32tel-Repetitionen, Bends und Slides in Dauerschleife. Misst jede Sekunde Renderzeit, Underruns, Speicher, Thermik und CPU-Takt.",
                "8 guitar tracks (high gain/crunch) with palm mutes, chords, 32nd repetitions, bends and slides in a loop. Samples render time, underruns, memory, thermals and CPU clock every second."), style = MaterialTheme.typography.bodySmall)
            val running = stressJob?.isActive == true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60 to "1 min", 600 to "10 min").forEach { (sec, label) ->
                    Button(enabled = !running, onClick = {
                        report = null
                        stressJob = scope.launch {
                            val r = lab.stressTest(sec) { stressLive = it }
                            report = when (r) { is Outcome.Success -> r.value.text; is Outcome.Failure -> r.message }
                        }
                    }, shape = RoundedCornerShape(22.dp)) { Text(label) }
                }
                if (running) OutlinedButton(onClick = { stressJob?.cancel(); scope.launch { vm.stop() } }, shape = RoundedCornerShape(22.dp)) { Text(tr("Abbrechen", "Cancel")) }
            }
            stressLive?.takeIf { running }?.let { l ->
                Text("${l.second} s · P99 ${l.stats?.renderP99Micros?.let { "%.0f µs".format(it) } ?: "–"} · Underruns ${l.stats?.underruns ?: "–"} · ${l.memoryMb?.let { "%.0f MB".format(it) } ?: ""} · ${l.thermal ?: ""}",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            report?.let { text ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(text, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(tr("Bericht kopieren", "Copy report")) }
            }
        } }

        BlindComparison(vm)
    }
}

@Composable private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** A/B with hidden assignment: modelled string vs. DI samples through the same amp/cab, both normalised to the same RMS. */
@Composable private fun BlindComparison(vm: WorkspaceViewModel) {
    val lab = vm.audioLab ?: return
    val scope = rememberCoroutineScope()
    var tone by remember { mutableStateOf("highgain") }
    var pair by remember { mutableStateOf<Pair<FloatArray, FloatArray>?>(null) }
    var aIsSampled by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<String?>(null) }
    val tally = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    DisposableEffect(Unit) { onDispose { lab.stopPcm() } }
    TabinoCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Blindvergleich", "Blind comparison"), style = MaterialTheme.typography.titleMedium)
        Text(tr("Dasselbe Riff zweimal: einmal mit der modellierten Saite, einmal mit den DI-Samples, beide durch denselben Amp und auf gleiche Lautheit (RMS) gebracht. Welche Seite A ist, wird zufällig gewählt und erst nach deiner Wahl gezeigt.",
            "The same riff twice: modelled string and DI samples through the same amp, matched in loudness (RMS). Which one is A is random and shown only after you choose."), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("clean" to "Clean", "crunch" to "Crunch", "highgain" to "High Gain").forEach { (id, label) -> FilterChip(tone == id, { tone = id; pair = null; revealed = null; lab.stopPcm() }, label = { Text(label) }) }
        }
        Button(enabled = !busy, onClick = {
            busy = true; revealed = null; lab.stopPcm()
            scope.launch {
                val m = lab.renderComparison(tone, sampled = false); val s = lab.renderComparison(tone, sampled = true)
                if (m is Outcome.Success && s is Outcome.Success) { aIsSampled = Math.random() < 0.5; pair = if (aIsSampled) s.value to m.value else m.value to s.value }
                else vm.showNotice(((s as? Outcome.Failure) ?: (m as? Outcome.Failure))?.message)
                busy = false
            }
        }, shape = RoundedCornerShape(22.dp)) { Text(if (busy) tr("Rendere …", "Rendering …") else tr("Neues Paar", "New pair")) }
        pair?.let { (a, b) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { lab.playPcm(a, 48_000) }, shape = RoundedCornerShape(22.dp)) { Text("▶ A") }
                OutlinedButton(onClick = { lab.playPcm(b, 48_000) }, shape = RoundedCornerShape(22.dp)) { Text("▶ B") }
                TextButton(onClick = { lab.stopPcm() }) { Text("■") }
            }
            val revealText = tr("A war ${if (aIsSampled) "gesampelt" else "modelliert"}, B war ${if (aIsSampled) "modelliert" else "gesampelt"}.",
                "A was ${if (aIsSampled) "sampled" else "modelled"}, B was ${if (aIsSampled) "modelled" else "sampled"}.")
            if (revealed == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true, false).forEach { pickA ->
                    Button(onClick = {
                        val pickedSampled = pickA == aIsSampled
                        val (s, m) = tally[tone] ?: (0 to 0)
                        tally[tone] = if (pickedSampled) (s + 1) to m else s to (m + 1)
                        revealed = revealText
                    }, shape = RoundedCornerShape(22.dp)) { Text(tr("${if (pickA) "A" else "B"} klingt echter", "${if (pickA) "A" else "B"} sounds more real")) }
                }
            }
        }
        revealed?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val sampledWord = tr("gesampelt", "sampled"); val modelledWord = tr("modelliert", "modelled")
        if (tally.isNotEmpty()) Text(tally.entries.joinToString(" · ") { (t, v) -> "$t: $sampledWord ${v.first} – $modelledWord ${v.second}" },
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    } }
}
