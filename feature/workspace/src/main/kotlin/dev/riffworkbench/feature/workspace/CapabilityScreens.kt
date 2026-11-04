package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable fun PracticeScreen(vm: WorkspaceViewModel) {
    val practice by vm.practice.collectAsStateWithLifecycle()
    val transport by vm.transport.state.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val tuner by vm.tuner.collectAsStateWithLifecycle()
    var speed by remember(practice.speed) { mutableFloatStateOf(practice.speed.toFloat()) }
    ScrollPage(stringResource(R.string.practice), stringResource(R.string.practice_hint)) {
        if (transport.status == TransportStatus.UNAVAILABLE || transport.status == TransportStatus.FAILED) NoticeCard(transport.reason ?: stringResource(R.string.transport_unavailable), true)
        Text("${stringResource(R.string.speed)} · ${(speed * 100).toInt()} %", style = MaterialTheme.typography.titleMedium)
        Slider(speed, { speed = it }, valueRange = 0.25f..1.5f, steps = 24, onValueChangeFinished = { vm.setSpeed(speed.toDouble()) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5, 0.7, 0.85, 1.0).forEach { s -> FilterChip(abs(practice.speed - s) < 0.001, { vm.setSpeed(s) }, label = { Text("${(s * 100).toInt()} %") }) }
        }
        Text(stringResource(R.string.loop), style = MaterialTheme.typography.titleMedium)
        Text(practice.loop?.let { l -> tr("Loop aktiv: Routen-Takt ${l.start.routeIndex + 1} bis ${l.end.routeIndex + 1}", "Loop: route bar ${l.start.routeIndex + 1} to ${l.end.routeIndex + 1}") }
            ?: tr("Takte im Editor markieren (Bereich) und hier loopen.", "Select bars in the editor (range) and loop them here."), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::loopSelection) { Text(tr("Auswahl loopen", "Loop selection")) }
            OutlinedButton(onClick = vm::clearLoop, enabled = practice.loop != null) { Text(tr("Loop aus", "Loop off")) }
        }
        ToggleRow(stringResource(R.string.metronome), practice.metronome, vm::setMetronome)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.count_in), style = MaterialTheme.typography.bodyMedium)
            (0..2).forEach { bars -> FilterChip(practice.countInBars == bars, { vm.setCountIn(bars) }, label = { Text(if (bars == 0) tr("aus", "off") else "$bars ${tr("Takt", "bar")}") }) }
        }
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

@Composable fun AudioScreen(project: Project) {
    ScrollPage(stringResource(R.string.audio), stringResource(R.string.audio_hint)) {
        if (project.audio.clips.isEmpty()) Text(stringResource(R.string.audio_empty), style = MaterialTheme.typography.titleMedium)
        project.audio.clips.forEach { clip -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(clip.name, style = MaterialTheme.typography.titleMedium)
            Text("Offset ${clip.offsetMicros} μs · ${clip.gain}", style = MaterialTheme.typography.bodySmall)
        } } }
        NoticeCard(tr("Referenz-Audio und Stems sind im Projektmodell und in der Sync-Zuordnung vorbereitet. Decodieren, Wellenform und Time-Stretch folgen; bis dahin gibt es hier keine Attrappe.",
            "Reference audio and stems are prepared in the model and the sync mapping. Decoding, waveform and time-stretch follow; until then there is no stand-in."))
        FeatureRegistry.all.filter { it.id in listOf("audio.sync_modes", "audio.shared_map", "audio.pitch_stretch") }.forEach { FeatureCard(it) }
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
        Slider(zoom, { zoom = it }, valueRange = 0.5f..2.5f, onValueChangeFinished = { vm.setZoom(zoom) })
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
        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_features)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(group.isEmpty(), { group = "" }, label = { Text(if (en) "All" else "Alle") })
            FeatureRegistry.all.distinctBy { it.group }.forEach { feature -> FilterChip(group == feature.group, { group = feature.group }, label = { Text(if (en) feature.group else feature.groupDe) }) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) { items(features, key = { it.id }) { FeatureCard(it) } }
    }
}
