package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R
import dev.riffworkbench.ui.score.ScoreCanvas
import dev.riffworkbench.ui.score.ScoreCursor

@Composable fun tr(de: String, en: String): String = if (english()) en else de

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
    val layout by vm.layout.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val transport by vm.transport.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val density = LocalDensity.current
    val selection = state.selection
    val measureNumber = state.project.measures.indexOfFirst { it.id == selection.measureId } + 1
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideInput = maxWidth >= 700.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TrackRow(vm, state)
                QuickBar(vm, state, input)
                val playhead = transport.position?.takeIf { transport.status == TransportStatus.PLAYING || transport.status == TransportStatus.PAUSED }
                    ?.let { it.position.measureId to it.position.offset.toDouble() }
                ScoreCanvas(
                    layout = layout,
                    cursor = ScoreCursor(selection.measureId, selection.offset, selection.string.takeIf { state.project.tracks.first { t -> t.id == selection.trackId }.instrument.tuning.isNotEmpty() }, selection.rangeEnd),
                    playhead = playhead,
                    follow = view.follow && transport.status == TransportStatus.PLAYING,
                    zoom = view.zoom,
                    onZoom = vm::setZoom,
                    onTap = { tap -> vm.tap(tap.measureId, tap.offset, tap.string) },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp).onSizeChanged { with(density) { vm.setViewportWidth(it.width.toDp().value) } },
                    description = tr("Partitur, Takt $measureNumber, Position ${selection.offset}, Saite ${selection.string + 1}", "Score, bar $measureNumber, position ${selection.offset}, string ${selection.string + 1}"),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${stringResource(R.string.measure)} $measureNumber · ${selection.offset}" + (selection.rangeEnd?.let { end -> " – ${state.project.measures.indexOfFirst { it.id == end } + 1}" } ?: ""),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    FilterChip(input.chordMode, { vm.chordMode(!input.chordMode) }, label = { Text(stringResource(R.string.chord_mode)) })
                    TextButton(onClick = { expanded = !expanded }) { Text("${if (expanded) "⌄" else "⌃"} ${stringResource(R.string.input)}") }
                }
                if (expanded && !sideInput) InputPanel(vm, input)
            }
            if (expanded && sideInput) Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { InputPanel(vm, input) }
        }
    }
}

@Composable private fun TrackRow(vm: WorkspaceViewModel, state: EditorState) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.project.tracks.forEach { track -> FilterChip(selected = state.selection.trackId == track.id, onClick = { vm.select(state.selection.copy(trackId = track.id, string = 0)) }, label = { Text(track.name, maxLines = 1) }) }
        Text(stringResource(R.string.voice), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        (0..3).forEach { voice -> FilterChip(selected = state.selection.voice == voice, onClick = { vm.select(state.selection.copy(voice = voice)) }, label = { Text("${voice + 1}") }) }
    }
}

/** One-tap techniques for the selected note; the same commands are reachable from the articulation page. */
@Composable private fun QuickBar(vm: WorkspaceViewModel, state: EditorState, input: NoteInput) {
    val note = vm.selectedNote(state)
    fun has(kind: NoteMark) = note?.articulations?.contains(Articulation.Mark(kind)) == true
    fun hasType(predicate: (Articulation) -> Boolean) = note?.articulations?.any(predicate) == true
    var bendMenu by remember { mutableStateOf(false) }
    var harmonicMenu by remember { mutableStateOf(false) }
    var slideMenu by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1, 2, 4, 8, 16, 32).forEach { value -> FilterChip(selected = input.duration.denominator == value, onClick = { vm.setDuration(denominator = value) }, label = { Text(if (value == 1) "1" else "1/$value") }) }
        FilterChip(selected = input.duration.dots > 0, onClick = { vm.setDuration(dots = if (input.duration.dots == 0) 1 else 0) }, label = { Text("·") }, modifier = Modifier.semanticsLabel(stringResource(R.string.dotted)))
        FilterChip(selected = input.duration.tuplets.isNotEmpty(), onClick = { vm.setTuplet(if (input.duration.tuplets.isEmpty()) TupletRatio(3, 2) else null) }, label = { Text("3:2") }, modifier = Modifier.semanticsLabel(tr("Triole", "Triplet")))
        FilterChip(selected = input.palmMute || has(NoteMark.PALM_MUTE), onClick = vm::togglePalmMute, label = { Text("PM") }, modifier = Modifier.semanticsLabel(stringResource(R.string.palm_mute)))
        FilterChip(selected = has(NoteMark.DEAD), onClick = { if (note != null) vm.toggleMark(NoteMark.DEAD) else vm.commitInput(dead = true) }, label = { Text("X") }, modifier = Modifier.semanticsLabel(stringResource(R.string.dead_note)))
        FilterChip(selected = note?.let { n -> state.project.tracks.flatMap { it.staves }.flatMap { it.bars }.flatMap { it.voices }.flatMap { it.beats }.flatMap { it.notes }.any { o -> o.links.any { it.kind == LinkKind.TIE && it.target == n.id } } } == true,
            onClick = { vm.command(EditorCommand.ToggleTie) }, label = { Text("⌒") }, modifier = Modifier.semanticsLabel(tr("Haltebogen", "Tie")))
        FilterChip(selected = has(NoteMark.HAMMER_ON) || has(NoteMark.PULL_OFF), onClick = { vm.toggleMark(NoteMark.HAMMER_ON) }, label = { Text("H/P") }, modifier = Modifier.semanticsLabel(tr("Hammer-on / Pull-off", "Hammer-on / pull-off")))
        Box {
            FilterChip(selected = hasType { it is Articulation.Slide }, onClick = { slideMenu = true }, label = { Text("╱ Slide") })
            DropdownMenu(slideMenu, { slideMenu = false }) {
                listOf(SlideKind.SHIFT to tr("Shift-Slide", "Shift slide"), SlideKind.LEGATO to tr("Legato-Slide", "Legato slide"), SlideKind.INTO_FROM_BELOW to tr("Von unten hinein", "Slide in from below"),
                    SlideKind.INTO_FROM_ABOVE to tr("Von oben hinein", "Slide in from above"), SlideKind.OUT_DOWN to tr("Heraus nach unten", "Slide out down"), SlideKind.OUT_UP to tr("Heraus nach oben", "Slide out up")).forEach { (kind, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { slideMenu = false; vm.articulation(Articulation.Slide(kind)) })
                }
                DropdownMenuItem(text = { Text(tr("Slide entfernen", "Remove slide")) }, onClick = { slideMenu = false; vm.command(EditorCommand.ClearArticulation("slide")) })
            }
        }
        Box {
            FilterChip(selected = hasType { it is Articulation.Bend }, onClick = { bendMenu = true }, label = { Text("⤴ Bend") })
            DropdownMenu(bendMenu, { bendMenu = false }) {
                listOf(tr("Ganzton", "Full") to Articulations.fullBend(), tr("Halbton", "Half") to Articulations.halfBend(), tr("Viertelton", "Quarter") to Articulations.quarterBend(),
                    tr("Bend + Release", "Bend and release") to Articulations.bendRelease(), tr("Prebend", "Pre-bend") to Articulations.prebend(), tr("Prebend + Release", "Pre-bend and release") to Articulations.prebendRelease(),
                    tr("1½ Töne", "1½ steps") to Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 3), 300), PitchCurvePoint(Fraction.ONE, 300)))).forEach { (label, bend) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { bendMenu = false; vm.articulation(bend) })
                }
                DropdownMenuItem(text = { Text(tr("Bend entfernen", "Remove bend")) }, onClick = { bendMenu = false; vm.command(EditorCommand.ClearArticulation("bend")) })
            }
        }
        Box {
            FilterChip(selected = hasType { it is Articulation.Harmonic }, onClick = { harmonicMenu = true }, label = { Text("◇ Harm.") })
            DropdownMenu(harmonicMenu, { harmonicMenu = false }) {
                listOf(HarmonicKind.NATURAL to tr("Natürlich (N.H.)", "Natural (N.H.)"), HarmonicKind.PINCH to tr("Pinch (P.H.)", "Pinch (P.H.)"), HarmonicKind.ARTIFICIAL to tr("Künstlich (A.H.)", "Artificial (A.H.)"),
                    HarmonicKind.TAPPED to tr("Getappt (T.H.)", "Tapped (T.H.)"), HarmonicKind.SEMI to tr("Semi (S.H.)", "Semi (S.H.)"), HarmonicKind.FEEDBACK to "Feedback").forEach { (kind, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { harmonicMenu = false; vm.articulation(Articulation.Harmonic(kind)) })
                }
                DropdownMenuItem(text = { Text(tr("Flageolett entfernen", "Remove harmonic")) }, onClick = { harmonicMenu = false; vm.command(EditorCommand.ClearArticulation("harmonic")) })
            }
        }
        FilterChip(selected = hasType { it is Articulation.Vibrato }, onClick = { vm.articulation(Articulation.Vibrato()) }, label = { Text("∿") }, modifier = Modifier.semanticsLabel("Vibrato"))
        FilterChip(selected = has(NoteMark.LET_RING), onClick = { vm.toggleMark(NoteMark.LET_RING) }, label = { Text("L.R.") }, modifier = Modifier.semanticsLabel("Let ring"))
        FilterChip(selected = has(NoteMark.GHOST), onClick = { vm.toggleMark(NoteMark.GHOST) }, label = { Text("( )") }, modifier = Modifier.semanticsLabel("Ghost note"))
        FilterChip(selected = has(NoteMark.ACCENT), onClick = { vm.toggleMark(NoteMark.ACCENT) }, label = { Text(">") }, modifier = Modifier.semanticsLabel(tr("Akzent", "Accent")))
        FilterChip(selected = has(NoteMark.STACCATO), onClick = { vm.toggleMark(NoteMark.STACCATO) }, label = { Text("·st") }, modifier = Modifier.semanticsLabel("Staccato"))
        FilterChip(selected = has(NoteMark.TAPPING), onClick = { vm.toggleMark(NoteMark.TAPPING) }, label = { Text("T") }, modifier = Modifier.semanticsLabel("Tapping"))
        FilterChip(selected = hasType { it is Articulation.TremoloPicking }, onClick = { vm.articulation(Articulation.TremoloPicking(WrittenDuration(32))) }, label = { Text("///") }, modifier = Modifier.semanticsLabel("Tremolo picking"))
        FilterChip(selected = vm.selectedBeat(state)?.strum?.direction == StrokeDirection.DOWN, onClick = { vm.command(EditorCommand.SetStrum(if (vm.selectedBeat(state)?.strum?.direction == StrokeDirection.DOWN) null else Strum(StrumKind.STRUM, StrokeDirection.DOWN))) }, label = { Text("↑ Strum") })
        FilterChip(selected = vm.selectedBeat(state)?.strum?.direction == StrokeDirection.UP, onClick = { vm.command(EditorCommand.SetStrum(if (vm.selectedBeat(state)?.strum?.direction == StrokeDirection.UP) null else Strum(StrumKind.STRUM, StrokeDirection.UP))) }, label = { Text("↓ Strum") })
    }
}

/** Bar range tools: copy/paste/duplicate/insert/delete, range selection and loop. */
@Composable private fun BarTools(vm: WorkspaceViewModel, state: EditorState, input: NoteInput) {
    val ws by vm.state.collectAsStateWithLifecycle()
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(input.rangeMode, { vm.setRangeMode(!input.rangeMode) }, label = { Text(tr("Bereich", "Range")) })
        AssistChip(onClick = { vm.copyBars() }, label = { Text(tr("Kopieren", "Copy")) })
        AssistChip(onClick = { vm.pasteBars() }, label = { Text(tr("Einfügen", "Paste")) }, enabled = ws.clipboard != null)
        AssistChip(onClick = { vm.duplicateBars() }, label = { Text(tr("Duplizieren", "Duplicate")) })
        AssistChip(onClick = { vm.insertBar(after = true) }, label = { Text(tr("+ Takt", "+ Bar")) })
        AssistChip(onClick = { vm.deleteBars() }, label = { Text(tr("Takt löschen", "Delete bar")) }, enabled = state.project.measures.size > 1)
        AssistChip(onClick = { vm.loopSelection() }, label = { Text(tr("⟲ Loop", "⟲ Loop")) })
    }
}

@Composable internal fun InputPanel(vm: WorkspaceViewModel, input: NoteInput) {
    val state by vm.state.collectAsStateWithLifecycle()
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.editor?.let { BarTools(vm, it, input) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.fret_preview), style = MaterialTheme.typography.labelSmall)
                    Text(input.buffer.ifEmpty { "—" }, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { vm.moveString(1) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(10.dp)) { Text("↑", Modifier.semanticsLabel(tr("Saite höher", "Higher string"))) }
                OutlinedButton(onClick = { vm.moveString(-1) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(10.dp)) { Text("↓", Modifier.semanticsLabel(tr("Saite tiefer", "Lower string"))) }
                Button(onClick = { vm.commitInput() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.commit)) }
            }
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("X", "0", "⌫")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { label ->
                    OutlinedButton(onClick = { when (label) { "X" -> vm.commitInput(dead = true); "⌫" -> vm.backspace(); else -> vm.digit(label.toInt()) } },
                        modifier = Modifier.weight(1f).height(48.dp).testTag("key-$label").semanticsLabel(when (label) { "X" -> stringResource(R.string.dead_note); "⌫" -> stringResource(R.string.delete); else -> label }),
                        contentPadding = PaddingValues(0.dp)) { Text(label, style = MaterialTheme.typography.titleMedium) }
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { vm.move(-1) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(8.dp)) { Text("‹", Modifier.semanticsLabel(stringResource(R.string.previous))) }
                OutlinedButton(onClick = vm::rest, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(8.dp)) { Text(stringResource(R.string.rest)) }
                OutlinedButton(onClick = { vm.command(EditorCommand.DeleteBeat) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(8.dp)) { Text(tr("Beat −", "Beat −")) }
                OutlinedButton(onClick = { vm.move(1) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(8.dp)) { Text("›", Modifier.semanticsLabel(stringResource(R.string.next))) }
            }
        }
    }
}

/** Bottom transport: play/pause, stop, loop, metronome, speed and the playhead position from the audio clock. */
@Composable fun TransportBar(vm: WorkspaceViewModel) {
    val transport by vm.transport.state.collectAsStateWithLifecycle()
    val practice by vm.practice.collectAsStateWithLifecycle()
    val ws by vm.state.collectAsStateWithLifecycle()
    var speedMenu by remember { mutableStateOf(false) }
    val playing = transport.status == TransportStatus.PLAYING
    Surface(tonalElevation = 4.dp) {
        Column {
            ws.busy?.let { label -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                val p = ws.busyProgress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            } }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::togglePlay, modifier = Modifier.heightIn(min = 48.dp).testTag("transport-play")) { Text(if (playing) "⏸ ${tr("Pause", "Pause")}" else "▶ ${stringResource(R.string.play)}") }
                OutlinedButton(onClick = vm::stop, modifier = Modifier.heightIn(min = 48.dp)) { Text("■", Modifier.semanticsLabel(stringResource(R.string.stop))) }
                FilterChip(practice.loop != null, { if (practice.loop != null) vm.clearLoop() else vm.loopSelection() }, label = { Text("⟲") }, modifier = Modifier.semanticsLabel(stringResource(R.string.loop)))
                FilterChip(practice.metronome, { vm.setMetronome(!practice.metronome) }, label = { Text("♩") }, modifier = Modifier.semanticsLabel(stringResource(R.string.metronome)))
                Box {
                    AssistChip(onClick = { speedMenu = true }, label = { Text("${(practice.speed * 100).toInt()} %") })
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.25).forEach { s -> DropdownMenuItem(text = { Text("${(s * 100).toInt()} %") }, onClick = { speedMenu = false; vm.setSpeed(s) }) }
                    }
                }
                val position = transport.position
                val bar = position?.let { p -> ws.editor?.project?.measures?.indexOfFirst { it.id == p.position.measureId }?.plus(1) }
                Text(when {
                    transport.countingIn -> tr("Einzähler …", "Count-in …")
                    transport.status == TransportStatus.UNAVAILABLE -> transport.reason ?: stringResource(R.string.transport_unavailable)
                    bar != null && bar > 0 -> "${stringResource(R.string.measure)} $bar"
                    else -> ""
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                val beat = position?.let { p -> ws.editor?.project?.measures?.firstOrNull { it.id == p.position.measureId }?.let { it.signature to p.position.offset } }
                if (practice.visualMetronome && playing && !transport.countingIn && beat != null) BeatDots(beat.first, beat.second)
            }
        }
    }
}

/** Visual metronome: one dot per beat of the current bar, driven by the audio-clock position (downbeat larger). */
@Composable private fun BeatDots(signature: TimeSignature, offset: Fraction) {
    val beats = signature.numerator.coerceIn(1, 16)
    val current = (offset.toDouble() * signature.denominator + 1e-9).toInt().coerceIn(0, beats - 1)
    Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(beats) { i ->
            val color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(if (i == 0) 12.dp else 9.dp).background(color, CircleShape))
        }
    }
}
