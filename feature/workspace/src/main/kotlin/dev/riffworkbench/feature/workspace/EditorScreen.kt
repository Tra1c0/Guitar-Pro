package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R
import dev.riffworkbench.ui.score.ScoreView

@Composable fun TrackAndVoice(vm: WorkspaceViewModel, state: EditorState) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.project.tracks.forEach { track -> FilterChip(selected = state.selection.trackId == track.id, onClick = { vm.select(state.selection.copy(trackId = track.id, string = 0)) }, label = { Text(track.name) }) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.voice), style = MaterialTheme.typography.labelMedium)
        (0..3).forEach { voice -> FilterChip(selected = state.selection.voice == voice, onClick = { vm.select(state.selection.copy(voice = voice)) }, label = { Text("${voice + 1}") }) }
        Spacer(Modifier.width(8.dp))
        val track = state.project.tracks.first { it.id == state.selection.trackId }
        track.instrument.tuning.indices.forEach { string -> FilterChip(selected = state.selection.string == string, onClick = { vm.select(state.selection.copy(string = string)) }, label = { Text("${stringResource(R.string.string_label)} ${string + 1}") }) }
    }
}

@Composable fun EditorScreen(vm: WorkspaceViewModel, state: EditorState) {
    val input by vm.input.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val scoreScroll = rememberScrollState(initial = Int.MAX_VALUE)
    val activeTrack = state.project.tracks.first { it.id == state.selection.trackId }
    LaunchedEffect(state.selection.string, activeTrack.id) {
        scoreScroll.scrollTo(if (state.selection.string > activeTrack.instrument.tuning.lastIndex / 2) 0 else Int.MAX_VALUE)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideInput = maxWidth >= 700.dp
        Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TrackAndVoice(vm, state)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 4, 8, 16, 32).forEach { value -> FilterChip(selected = input.duration.denominator == value, onClick = { vm.setDuration(denominator = value) }, label = { Text("1/$value") }) }
            FilterChip(selected = input.duration.dots > 0, onClick = { vm.setDuration(dots = if (input.duration.dots == 0) 1 else 0) }, label = { Text("· ${stringResource(R.string.dotted)}") })
            FilterChip(selected = input.palmMute, onClick = vm::togglePalmMute, label = { Text("PM") }, modifier = Modifier.semanticsLabel(stringResource(R.string.palm_mute)))
        }
        Box(Modifier.weight(1f).verticalScroll(scoreScroll).padding(horizontal = 12.dp)) { ScoreView(state.project, state.selection, vm::select, Modifier.fillMaxWidth()) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${stringResource(R.string.measure)} ${state.project.measures.indexOfFirst { it.id == state.selection.measureId } + 1} · ${state.selection.offset}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            FilterChip(input.chordMode, { vm.chordMode(!input.chordMode) }, label = { Text(stringResource(R.string.chord_mode)) })
            TextButton(onClick = { expanded = !expanded }) { Text("${if (expanded) "⌄" else "⌃"} ${stringResource(R.string.input)}") }
        }
            if (expanded && !sideInput) InputPanel(vm, input)
        }
        if (expanded && sideInput) Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { InputPanel(vm, input) }
        }
    }
}

@Composable private fun InputPanel(vm: WorkspaceViewModel, input: NoteInput) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.fret_preview), style = MaterialTheme.typography.labelSmall); Text(input.buffer.ifEmpty { "—" }, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace) }
                Button(onClick = { vm.commitInput() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.commit)) }
            }
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("X", "0", "⌫")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { label ->
                    OutlinedButton(onClick = { when (label) { "X" -> vm.commitInput(dead = true); "⌫" -> vm.backspace(); else -> vm.digit(label.toInt()) } }, modifier = Modifier.weight(1f).height(48.dp).testTag("key-$label").semanticsLabel(when (label) { "X" -> stringResource(R.string.dead_note); "⌫" -> stringResource(R.string.delete); else -> label }), contentPadding = PaddingValues(0.dp)) { Text(label) }
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { vm.move(-1) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(8.dp)) { Text("‹", Modifier.semanticsLabel(stringResource(R.string.previous))) }
                OutlinedButton(onClick = vm::rest, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(8.dp)) { Text(stringResource(R.string.rest)) }
                OutlinedButton(onClick = { vm.move(1) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(8.dp)) { Text("›", Modifier.semanticsLabel(stringResource(R.string.next))) }
            }
        }
    }
}
