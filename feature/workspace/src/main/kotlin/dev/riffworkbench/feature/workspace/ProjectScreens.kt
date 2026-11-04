package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R
import dev.riffworkbench.ui.score.pitchName

@Composable internal fun ScrollPage(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading(title, description)
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable fun ArrangementScreen(vm: WorkspaceViewModel, state: EditorState) {
    var title by remember(state.project.metadata.title) { mutableStateOf(state.project.metadata.title) }
    var bpm by remember(state.project.tempo) { mutableStateOf((state.project.tempo.firstOrNull()?.quarterNotesPerMinute ?: 150.0).toInt().toString()) }
    ScrollPage(stringResource(R.string.arrangement), stringResource(R.string.portrait_hint)) {
        OutlinedTextField(title, { title = it.take(200) }, label = { Text(stringResource(R.string.project_title)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { vm.command(EditorCommand.Rename(title)) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.rename)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(bpm, { bpm = it.take(6) }, label = { Text("BPM") }, modifier = Modifier.width(120.dp), singleLine = true)
            Button(onClick = { bpm.toDoubleOrNull()?.let { vm.command(EditorCommand.SetTempo(it)) } ?: vm.showNotice("Ungültiges Tempo") }) { Text(stringResource(R.string.tempo)) }
        }
        Text(stringResource(R.string.measure), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.project.measures.forEachIndexed { index, measure -> FilterChip(selected = measure.id == state.selection.measureId, onClick = { vm.select(state.selection.copy(measureId = measure.id, offset = Fraction.ZERO)) }, label = { Text("${index + 1} · ${measure.signature.numerator}/${measure.signature.denominator}") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.command(EditorCommand.AddMeasure(TimeSignature())) }) { Text("+ 4/4") }
            OutlinedButton(onClick = { vm.command(EditorCommand.AddMeasure(TimeSignature(7, 8))) }) { Text("+ 7/8") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.time_signature), Modifier.align(Alignment.CenterVertically))
            TextButton(onClick = { vm.command(EditorCommand.SetSignature(state.selection.measureId, TimeSignature())) }) { Text("4/4") }
            TextButton(onClick = { vm.command(EditorCommand.SetSignature(state.selection.measureId, TimeSignature(7, 8))) }) { Text("7/8") }
        }
        HorizontalDivider()
        state.project.tracks.forEach { track -> OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(track.name, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.command(EditorCommand.MoveTrack(track.id, -1)) }, modifier = Modifier.semanticsLabel(stringResource(R.string.move_up))) { Text("↑") }
                    TextButton(onClick = { vm.command(EditorCommand.MoveTrack(track.id, 1)) }, modifier = Modifier.semanticsLabel(stringResource(R.string.move_down))) { Text("↓") }
                    TextButton(onClick = { vm.command(EditorCommand.RemoveTrack(track.id)) }, enabled = state.project.tracks.size > 1) { Text(stringResource(R.string.remove_track)) }
                }
            }
        } }
        Text(stringResource(R.string.add_track), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(InstrumentKind.GUITAR to R.string.guitar, InstrumentKind.BASS to R.string.bass, InstrumentKind.PERCUSSION to R.string.percussion, InstrumentKind.PIANO to R.string.piano).forEach { (kind, label) ->
                OutlinedButton(onClick = { vm.command(EditorCommand.AddTrack(kind)) }) { Text("+ ${stringResource(label)}") }
            }
        }
        PendingCard(stringResource(R.string.duplicate_range), if (english()) "Range clipboard, drag preview and ripple operations share the transaction contract." else "Bereichs-Clipboard, Drag-Vorschau und Ripple verwenden denselben Transaktionsvertrag.", "B")
        if (state.diagnostics.isNotEmpty()) { Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleMedium); state.diagnostics.take(8).forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable fun DrumScreen(vm: WorkspaceViewModel, state: EditorState) {
    val track = state.project.tracks.firstOrNull { it.id == state.selection.trackId && it.instrument.kind == InstrumentKind.PERCUSSION }
        ?: state.project.tracks.firstOrNull { it.instrument.kind == InstrumentKind.PERCUSSION }
    ScrollPage(stringResource(R.string.drums), stringResource(R.string.stepgrid_hint)) {
        if (track == null) {
            NoticeCard(stringResource(R.string.no_drum_track))
            Button(onClick = { vm.command(EditorCommand.AddTrack(InstrumentKind.PERCUSSION)) }) { Text(stringResource(R.string.add_track)) }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.project.measures.forEachIndexed { index, measure -> FilterChip(selected = measure.id == state.selection.measureId, onClick = { vm.select(state.selection.copy(trackId = track.id, measureId = measure.id, offset = Fraction.ZERO, string = 0)) }, label = { Text("${stringResource(R.string.measure)} ${index + 1}") }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..3).forEach { voice -> FilterChip(selected = voice == state.selection.voice, onClick = { vm.select(state.selection.copy(trackId = track.id, voice = voice, string = 0)) }, label = { Text("${stringResource(R.string.voice)} ${voice + 1}") }) }
            }
            val measure = state.project.measures.first { it.id == state.selection.measureId }
            val beats = track.staves.first().bars.first { it.measureId == measure.id }.voices.first { it.index == state.selection.voice }.beats
            Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                track.instrument.drumKit.forEach { drum -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(drum.name, Modifier.width(100.dp), style = MaterialTheme.typography.labelMedium)
                    repeat((measure.signature.length().toDouble() * 16).toInt()) { slot ->
                        val offset = Fraction.of(slot.toLong(), 16)
                        val active = beats.firstOrNull { it.offset == offset }?.notes?.any { it.pitch.midiNumber == drum.midiNote } == true
                        FilledTonalButton(onClick = { vm.toggleDrum(track.id, measure.id, offset, drum.midiNote) }, modifier = Modifier.size(48.dp).semanticsLabel("${drum.name}, ${slot + 1}, ${if (active) "on" else "off"}"), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(if (active) "●" else "·") }
                    }
                } }
            }
            PendingCard("Velocity / Chokes", if (english()) "The model includes velocity, articulations and choke groups. Their controls follow in stage B." else "Velocity, Artikulationen und Choke-Gruppen sind im Modell enthalten. Die Bedienelemente folgen in Etappe B.", "B")
        }
    }
}

@Composable fun MixerScreen(vm: WorkspaceViewModel, state: EditorState) {
    ScrollPage(stringResource(R.string.mixer), stringResource(R.string.mixer_hint)) {
        state.project.tracks.forEach { track ->
            var volume by remember(track.id, track.mix.volume) { mutableFloatStateOf(track.mix.volume.toFloat()) }
            var pan by remember(track.id, track.mix.pan) { mutableFloatStateOf(track.mix.pan.toFloat()) }
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(track.name, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(track.mix.mute, { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(mute = !track.mix.mute))) }, label = { Text(stringResource(R.string.mute)) })
                    FilterChip(track.mix.solo, { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(solo = !track.mix.solo))) }, label = { Text(stringResource(R.string.solo)) })
                }
                Text("${stringResource(R.string.volume)} ${(volume * 100).toInt()}%")
                Slider(volume, { volume = it }, onValueChangeFinished = { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(volume = volume.toDouble()))) }, modifier = Modifier.semanticsLabel(stringResource(R.string.volume)))
                Text("${stringResource(R.string.pan)} ${(pan * 100).toInt()}")
                Slider(pan, { pan = it }, valueRange = -1f..1f, onValueChangeFinished = { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(pan = pan.toDouble()))) }, modifier = Modifier.semanticsLabel(stringResource(R.string.pan)))
            } }
        }
        PendingCard(stringResource(R.string.master_bus), if (english()) "Bus routing, automation, EQ, amp and cabinet processing are prepared." else "Bus-Routing, Automation, EQ sowie Amp- und Cabinet-Verarbeitung sind vorbereitet.", "D")
    }
}

@Composable fun InstrumentsScreen(vm: WorkspaceViewModel, state: EditorState) {
    val track = state.project.tracks.first { it.id == state.selection.trackId }
    ScrollPage(stringResource(R.string.instruments), stringResource(R.string.instrument_hint)) {
        TrackAndVoice(vm, state)
        Text(track.name, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.tuning), style = MaterialTheme.typography.titleSmall)
        Text(track.instrument.tuning.joinToString(" – ") { "${pitchName(it)}${it / 12 - 1}" }.ifEmpty { "—" })
        Text("${stringResource(R.string.capo)}: ${track.instrument.capoByString.joinToString()}")
        if (track.instrument.tuning.isNotEmpty()) {
            Text(stringResource(R.string.fretboard), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.horizontalScroll(rememberScrollState())) { track.instrument.tuning.indices.reversed().forEach { string -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pitchName(track.instrument.tuning[string]), Modifier.width(28.dp))
                (0..12).forEach { fret -> OutlinedButton(onClick = { vm.select(state.selection.copy(string = string)); vm.command(EditorCommand.PutNote(fret = fret)) }, modifier = Modifier.size(48.dp).semanticsLabel("${stringResource(R.string.string_label)} ${string + 1}, $fret"), contentPadding = PaddingValues(0.dp)) { Text("$fret") } }
            } } }
        }
        PendingCard(stringResource(R.string.tuning), "${stringResource(R.string.retain_pitch)} / ${stringResource(R.string.retain_fret)}", "B")
        PendingCard("${stringResource(R.string.instruments)} / ${stringResource(R.string.soundbank)}", if (english()) "Chord and scale libraries, fingering alternatives, keyboard and custom banks follow through dedicated services." else "Akkord- und Skalenbibliotheken, Griffalternativen, Keyboard und eigene Klangbanken folgen über eigene Dienste.", "B–D")
    }
}

@Composable fun HistoryScreen(vm: WorkspaceViewModel, state: WorkspaceState) {
    var name by remember { mutableStateOf("") }
    ScrollPage(stringResource(R.string.history), stringResource(R.string.history_hint)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(vm::undo, enabled = state.editor?.canUndo == true) { Text(stringResource(R.string.undo)) }
            OutlinedButton(vm::redo, enabled = state.editor?.canRedo == true) { Text(stringResource(R.string.redo)) }
        }
        OutlinedTextField(name, { name = it.take(200) }, label = { Text(stringResource(R.string.snapshot_name)) }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.createSnapshot(name); name = "" }, enabled = name.isNotBlank()) { Text(stringResource(R.string.snapshot_create)) }
        if (state.snapshots.isEmpty()) Text(stringResource(R.string.snapshots_empty))
        state.snapshots.forEach { snapshot -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(snapshot.name, style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.revision)} ${snapshot.revision}", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { vm.restoreSnapshot(snapshot) }) { Text(stringResource(R.string.restore)) }
        } } }
        PendingCard(if (english()) "Variants" else "Varianten", if (english()) "Branching history builds on immutable revisions and snapshots." else "Verzweigte Historie baut auf unveränderlichen Revisionen und Schnappschüssen auf.", "D")
    }
}
