package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.riffworkbench.capabilities.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R

@Composable fun PracticeScreen() {
    ScrollPage(stringResource(R.string.practice), stringResource(R.string.practice_hint)) {
        NoticeCard(stringResource(R.string.transport_unavailable))
        Text("${stringResource(R.string.speed)} · 70%", style = MaterialTheme.typography.titleMedium)
        Slider(0.7f, {}, valueRange = 0.25f..1.5f, enabled = false)
        ToggleRow(stringResource(R.string.loop), false, {}, enabled = false)
        ToggleRow(stringResource(R.string.metronome), true, {}, enabled = false)
        Text("${stringResource(R.string.count_in)} · 1", style = MaterialTheme.typography.bodyMedium)
        PendingCard("Speed Trainer", if (english()) "70 → 100%, configurable steps and repetitions." else "70 → 100%, mit einstellbaren Schritten und Wiederholungen.")
        PendingCard(stringResource(R.string.instruments), if (english()) "Tuner, playback fretboard and keyboard follow with audio input and transport." else "Stimmgerät, Playback-Griffbrett und Keyboard folgen mit Audioeingang und Transport.")
    }
}

@Composable fun AudioScreen(project: Project) {
    ScrollPage(stringResource(R.string.audio), stringResource(R.string.audio_hint)) {
        if (project.audio.clips.isEmpty()) Text(stringResource(R.string.audio_empty), style = MaterialTheme.typography.titleMedium)
        project.audio.clips.forEach { clip -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(clip.name, style = MaterialTheme.typography.titleMedium)
            Text("Offset ${clip.offsetMicros} μs · ${clip.gain}", style = MaterialTheme.typography.bodySmall)
        } } }
        OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.audio_import)) }
        PendingCard(stringResource(R.string.audio), stringResource(R.string.waveform_pending))
        OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.sync_anchor)) }
        FeatureRegistry.all.filter { it.id in listOf("audio.sync_modes", "audio.shared_map", "audio.pitch_stretch") }.forEach { FeatureCard(it) }
    }
}

@Composable fun EffectsScreen(vm: WorkspaceViewModel) {
    ScrollPage(stringResource(R.string.effects), stringResource(R.string.effect_hint)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::togglePalmMute) { Text(stringResource(R.string.palm_mute)) }
            OutlinedButton(onClick = { vm.commitInput(dead = true) }) { Text(stringResource(R.string.dead_note)) }
        }
        PendingCard(stringResource(R.string.bend_draw), if (english()) "Select a note, edit a per-note pitch curve, preview, then commit one transaction. Each string retains its own pitch." else "Note auswählen, eigene Tonhöhenkurve bearbeiten, Vorschau prüfen und als eine Transaktion bestätigen. Jede Saite behält ihre eigene Tonhöhe.")
        FeatureRegistry.group("articulations").forEach { FeatureCard(it) }
    }
}

@Composable fun LayoutScreen() {
    ScrollPage(stringResource(R.string.layout), stringResource(R.string.layout_hint)) {
        FeatureRegistry.all.filter { it.id.startsWith("layout.") || it.id.startsWith("notation.") }.forEach { FeatureCard(it) }
    }
}

@Composable fun ExchangeScreen(onNativeExport: () -> Unit) {
    ScrollPage(stringResource(R.string.exchange), stringResource(R.string.available_native)) {
        Button(onClick = onNativeExport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.export_native)) }
        NoticeCard(if (english()) "GP7 compatibility and GP8-specific fidelity are separate checks. Only the native project format is available in phase 1." else "GP7-Kompatibilität und GP8-spezifische Treue werden getrennt geprüft. In Phase 1 ist das native Projektformat verfügbar.")
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
