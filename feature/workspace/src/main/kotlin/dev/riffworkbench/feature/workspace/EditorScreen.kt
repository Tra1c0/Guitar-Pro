package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.ScoreScale
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R
import dev.riffworkbench.ui.score.ScoreCanvas
import dev.riffworkbench.ui.score.ScoreCursor
import dev.riffworkbench.ui.score.pitchName

@Composable fun tr(de: String, en: String): String = if (english()) en else de

@Composable fun TrackAndVoice(vm: WorkspaceViewModel, state: EditorState) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.project.tracks.forEachIndexed { i, track -> FilterChip(selected = state.selection.trackId == track.id, onClick = { vm.selectTrack(track.id) },
            label = { Text(track.name) }, leadingIcon = { ColorDot(Tabino.Accents[i % Tabino.Accents.size]) }) }
    }
}

/**
 * Editor after the Tabino V2 design: title, bar map, TAB card, then three rows of large controls
 * (durations, techniques, fret pad). Everything rare lives in the "more" sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EditorScreen(vm: WorkspaceViewModel, state: EditorState) {
    val input by vm.input.collectAsStateWithLifecycle()
    var moreTools by rememberSaveable { mutableStateOf(false) }
    // Bar whose signature/tempo sheet is open (EntityId is a value class over String, so the id itself is saveable).
    var signatureEditor by rememberSaveable { mutableStateOf<String?>(null) }
    val track = state.project.tracks.first { it.id == state.selection.trackId }
    ProbeComposition("EditorScreen")
    // Long press on a drawn time signature (or a tap on the bar info in the header): select that bar and open its sheet.
    val editSignature: (EntityId) -> Unit = { id ->
        // selectCell, not select: in range mode select() would only move the range end (Zyklus 23).
        if (id != state.selection.measureId) vm.selectCell(state.selection.copy(measureId = id, offset = Fraction.ZERO, rangeEnd = null))
        signatureEditor = id.value
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 700.dp) Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) { EditorHeader(vm, state, editSignature); BarMap(vm, state); TabCard(vm, state, Modifier.weight(1f), editSignature) }
            Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 8.dp)) { Controls(vm, state, track, input) { moreTools = true } }
        } else Column(Modifier.fillMaxSize()) {
            EditorHeader(vm, state, editSignature)
            BarMap(vm, state)
            TabCard(vm, state, Modifier.weight(1f), editSignature)
            Controls(vm, state, track, input) { moreTools = true }
        }
    }
    if (moreTools) ModalBottomSheet(onDismissRequest = { moreTools = false }, containerColor = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxWidth()) { MoreTools(vm, state, input); SheetNoticeOverlay(vm) }
    }
    signatureEditor?.let { id -> SignatureSheet(vm, EntityId(id), onDismiss = { signatureEditor = null }) }
}

/**
 * Title row. The bar info ("Takt N · 4/4 · 150 BPM") sits next to the title in label size instead of a second display
 * line (Bericht 1 §9: metadata had too much weight and height) and opens the signature/tempo sheet of the selected bar:
 * the accessible alternative to the long press on the drawn signature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EditorHeader(vm: WorkspaceViewModel, state: EditorState, onEditSignature: (EntityId) -> Unit) {
    ProbeComposition("EditorHeader")
    val p = state.project
    val index = p.measures.indexOfFirst { it.id == state.selection.measureId }
    val bpm = tempoInEffect(p, index)
    val measure = p.measures.getOrNull(index)
    val sig = measure?.signature?.let { "${it.numerator}/${it.denominator}" }.orEmpty()
    val info = "${stringResource(R.string.measure)} ${index + 1} · $sig" + (bpm?.let { " · ${it.toInt()} BPM" } ?: "")
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigTitle(p.metadata.title, null, Modifier.weight(1f), size = 22)
        HeaderBeatDots(vm, p)
        val action = stringResource(R.string.bar_settings)
        // Touch target 40 dp instead of Material's 48 (QA 20 / Partitur-Kopf): the 32-dp pill set the height of the whole
        // title row; 40 dp keeps the agreed minimum and gives the score 8 dp.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides HeaderTouchTarget) {
            Surface(onClick = { measure?.let { onEditSignature(it.id) } }, shape = RoundedCornerShape(16.dp), color = Color.Transparent, contentColor = Tabino.Faint,
                modifier = Modifier.heightIn(min = 32.dp).semantics { contentDescription = "$info. $action" }.testTag("bar-info")) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(info, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Visual metronome of the header. It reads the transport itself, so a poll (~30 Hz while playing) recomposes only the dots,
 * not the title row with its bar-info chip (QA 23).
 */
@Composable private fun HeaderBeatDots(vm: WorkspaceViewModel, p: Project) {
    val transport by vm.transport.state.collectAsStateWithLifecycle()
    val practice by vm.practice.collectAsStateWithLifecycle()
    val playing = transport.status == TransportStatus.PLAYING && !transport.countingIn
    val beat = transport.position?.let { pos -> p.measures.firstOrNull { it.id == pos.position.measureId }?.let { it.signature to pos.position.offset } }
    if (practice.visualMetronome && playing && beat != null) BeatDots(beat.first, beat.second)
}

/** Overview strip: one rounded bar per measure, height by note count, the current one bright. Tap jumps there. */
@Composable private fun BarMap(vm: WorkspaceViewModel, state: EditorState) {
    val track = state.project.tracks.first { it.id == state.selection.trackId }
    val staff = track.staves.firstOrNull()
    val counts = state.project.measures.map { m -> staff?.bars?.firstOrNull { it.measureId == m.id }?.voices?.sumOf { v -> v.beats.sumOf { it.notes.size } } ?: 0 }
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp).horizontalScroll(scroll).clearAndSetSemantics { }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        state.project.measures.forEachIndexed { i, m ->
            val current = m.id == state.selection.measureId || m.id == state.selection.rangeEnd
            val h = 6 + (14f * counts[i] / max).toInt()
            Box(Modifier.width(16.dp).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                Surface(onClick = { vm.selectMeasure(m.id) }, shape = RoundedCornerShape(6.dp),
                    color = Tabino.Accents[(i / 4) % Tabino.Accents.size].copy(alpha = if (current) 1f else 0.45f), modifier = Modifier.fillMaxWidth().height(h.dp)) {}
            }
        }
    }
}

@Composable private fun TabCard(vm: WorkspaceViewModel, state: EditorState, modifier: Modifier, onEditSignature: (EntityId) -> Unit) {
    ProbeComposition("TabCard")
    val selection = state.selection
    val track = state.project.tracks.first { it.id == selection.trackId }
    val fretted = track.instrument.tuning.isNotEmpty()
    val input by vm.input.collectAsStateWithLifecycle()
    // Paddings 4/6 instead of 6/12: the canvas is the scarce part of a phone screen (Bericht 1 §9); QA 20: row top 4.
    TabinoCard(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrackMenu(vm, state)
            InfoPill(selectionInfo(vm, state, input), if (fretted) Tabino.stringColor(selection.string, track.instrument.tuning.size) else null, Modifier.weight(1f))
        }
        ScoreArea(vm, state, fretted, onEditSignature, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * The score of the editor card. It alone reads the transport, so a poll (~30 Hz while playing) recomposes the canvas with
 * its playhead and the follow chip, not the card row, the track menu or the controls (QA 23). Drawn at [ScoreScale.EDITOR];
 * pinch zoom only with the setting "Zoomen per Geste" (AppSettings.pinchZoom), otherwise two fingers pan.
 */
@Composable private fun ScoreArea(vm: WorkspaceViewModel, state: EditorState, fretted: Boolean, onEditSignature: (EntityId) -> Unit, modifier: Modifier) {
    ProbeComposition("ScoreArea")
    val layout by vm.layout.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val transport by vm.transport.state.collectAsStateWithLifecycle()
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val selection = state.selection
    val measureNumber = state.project.measures.indexOfFirst { it.id == selection.measureId } + 1
    val playing = transport.status == TransportStatus.PLAYING
    val playhead = transport.position?.takeIf { playing || transport.status == TransportStatus.PAUSED }
        ?.let { it.position.measureId to it.position.offset.toDouble() }
    Box(modifier) {
        ScoreCanvas(
            layout = layout,
            cursor = ScoreCursor(selection.measureId, selection.offset, selection.string.takeIf { fretted }, selection.rangeEnd),
            playhead = playhead,
            follow = view.follow && playing,
            followSuspended = view.followSuspended,
            zoom = view.zoom,
            onZoom = vm::setZoom,
            // While playing, a tap also moves playback there: cursor and playhead stay together instead of fighting over the view.
            // While paused, the moved cursor becomes the start of the next Play (WorkspaceViewModel.navigatedSincePause).
            onTap = { tap -> vm.tap(tap.measureId, tap.offset, tap.string); if (transport.status == TransportStatus.PLAYING) vm.seekToCursor() },
            onMoveNote = { from, to -> vm.moveNote(from.measureId, from.offset, from.string, to.measureId, to.offset, to.string) },
            onSignatureLongPress = onEditSignature,
            // Long press on a bar + drag: bar range with haptics (Bericht 1 §5). Selection only, never a seek, also while playing.
            onRangeStart = vm::beginRange,
            onRangeExtend = vm::extendRange,
            onRangeEnd = vm::endRange,
            // A pan/zoom/drag while following: the view stays where the finger put it until the chip (or Play/seek/stop) resumes.
            onUserScroll = vm::suspendFollow,
            scale = ScoreScale.EDITOR,
            zoomGesture = settings.pinchZoom,
            // No top padding: the layout's own margin (EDITOR_MARGIN) already separates the score from the row above (QA 20).
            // The width goes to the ViewModel in zoom-1 units (dp / display scale): wrapped systems fit the drawn width.
            modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(20.dp)).testTag("score-canvas").onSizeChanged { with(density) { vm.setViewportWidth(it.width.toDp().value / ScoreScale.EDITOR) } },
            description = tr("Partitur, Takt $measureNumber, Position ${selection.offset}, Saite ${selection.string + 1}", "Score, bar $measureNumber, position ${selection.offset}, string ${selection.string + 1}") +
                tr(". Lange auf die Taktart drücken, um Taktart und Tempo zu ändern", ". Long-press the time signature to change signature and tempo") +
                tr(". Lange auf einen Takt drücken und ziehen, um Takte auszuwählen", ". Long-press a bar and drag to select bars"),
        )
        if (view.followSuspended && playing) {
            // Following is paused after a manual pan: say so, and offer the way back (Bericht 1 §4 "klar erkennbare Rückkehr").
            Surface(onClick = vm::resumeFollow, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).height(36.dp).testTag("follow-resume")) {
                Row(Modifier.padding(start = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(TabinoIcons.Play, null, Modifier.size(16.dp))
                    Text(stringResource(R.string.follow_resume), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Minimum touch target of the chips above the score (agreed minimum 40 dp; Material's default is 48 dp). */
private val HeaderTouchTarget = 40.dp

/** Track (with colour dot) and voice chooser in one chip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TrackMenu(vm: WorkspaceViewModel, state: EditorState) {
    ProbeComposition("TrackMenu")
    var open by remember { mutableStateOf(false) }
    val i = state.project.tracks.indexOfFirst { it.id == state.selection.trackId }.coerceAtLeast(0)
    val track = state.project.tracks[i]
    Box {
        // 40-dp touch target like the bar info: the 36-dp chip made the row 48 dp high (QA 20 / Partitur-Kopf).
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides HeaderTouchTarget) {
            Surface(onClick = { open = true }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.heightIn(min = 36.dp)) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorDot(Tabino.Accents[i % Tabino.Accents.size])
                    Text(track.name.substringBefore(" · ").take(14), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    if (state.selection.voice > 0) Text("V${state.selection.voice + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(open, { open = false }) {
            ProbeComposition("TrackMenu.items")
            state.project.tracks.forEachIndexed { k, t -> DropdownMenuItem(text = { Text(t.name) }, leadingIcon = { ColorDot(Tabino.Accents[k % Tabino.Accents.size]) },
                onClick = { open = false; vm.selectTrack(t.id) }) }
            HorizontalDivider()
            (0..3).forEach { v -> DropdownMenuItem(text = { Text("${stringResource(R.string.voice)} ${v + 1}" + if (v == state.selection.voice) "  ✓" else "") },
                onClick = { open = false; vm.select(state.selection.copy(voice = v)) }) }
        }
    }
}

@Composable private fun selectionInfo(vm: WorkspaceViewModel, state: EditorState, input: NoteInput): String {
    val track = state.project.tracks.first { it.id == state.selection.trackId }
    val tuning = track.instrument.tuning
    val note = vm.selectedNote(state)
    val beat = vm.selectedBeat(state)
    val string = if (tuning.isNotEmpty()) "${pitchName(tuning[state.selection.string.coerceIn(tuning.indices)])}-${tr("Saite", "string")}" else track.name
    val what = when {
        beat?.explicitRest == true -> tr("Pause", "rest")
        note == null -> tr("leer", "empty")
        Articulation.Mark(NoteMark.DEAD) in note.articulations -> tr("tote Note", "dead note")
        note.fret != null -> "${tr("Bund", "fret")} ${note.fret}"
        else -> pitchName(note.pitch.midiNumber)
    }
    val d = (beat?.duration ?: input.duration)
    val dur = when (d.denominator) { 1 -> tr("Ganze", "whole"); 2 -> tr("Halbe", "half"); 4 -> tr("Viertel", "quarter"); 8 -> tr("Achtel", "eighth"); 16 -> tr("16tel", "16th"); 32 -> tr("32tel", "32nd"); else -> "1/${d.denominator}" } +
        (if (d.dots > 0) tr(" punktiert", " dotted") else "") + (if (d.tuplets.isNotEmpty()) " ${d.tuplets.first().actual}:${d.tuplets.first().normal}" else "")
    return "$string · $what · $dur"
}

/** Durations, techniques and the fret pad (or the note keypad for unfretted tracks). */
@Composable private fun Controls(vm: WorkspaceViewModel, state: EditorState, track: Track, input: NoteInput, onMore: () -> Unit) {
    ProbeComposition("Controls")
    val tuning = track.instrument.tuning
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DurationRow(vm, state, input)
        TechniqueRow(vm, state, input, onMore)
        if (tuning.isNotEmpty()) FretPad(vm, state, track, input) else NotePad(vm, input)
    }
}

@Composable private fun DurationRow(vm: WorkspaceViewModel, state: EditorState, input: NoteInput) {
    val beat = vm.selectedBeat(state)
    val d = beat?.duration ?: input.duration
    val rest = beat?.explicitRest == true
    val items = listOf(1 to (TabinoIcons.Whole to tr("Ganze", "Whole")), 2 to (TabinoIcons.Half to tr("Halbe", "Half")), 4 to (TabinoIcons.Quarter to tr("Viertel", "Quarter")),
        8 to (TabinoIcons.Eighth to tr("Achtel", "Eighth")), 16 to (TabinoIcons.Sixteenth to tr("16tel", "16th")), 32 to (TabinoIcons.ThirtySecond to tr("32tel", "32nd")))
    Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (value, pair) ->
            ChoiceTile(pair.second, !rest && d.denominator == value, { vm.setDuration(denominator = value) }, Modifier.weight(1f).fillMaxHeight().testTag("dur-$value"), icon = pair.first)
        }
        ChoiceTile(tr("Punkt", "Dot"), d.dots > 0, { vm.setDuration(dots = if (d.dots == 0) 1 else 0) }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Dot, description = stringResource(R.string.dotted))
        ChoiceTile(tr("Pause", "Rest"), rest, vm::rest, Modifier.weight(1f).fillMaxHeight().testTag("dur-rest"), icon = TabinoIcons.Rest, description = stringResource(R.string.rest))
    }
}

@Composable private fun TechniqueRow(vm: WorkspaceViewModel, state: EditorState, input: NoteInput, onMore: () -> Unit) {
    val note = vm.selectedNote(state)
    fun has(kind: NoteMark) = note?.articulations?.contains(Articulation.Mark(kind)) == true
    fun hasType(predicate: (Articulation) -> Boolean) = note?.articulations?.any(predicate) == true
    var slideMenu by remember { mutableStateOf(false) }
    var bendMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceTile("H/P", has(NoteMark.HAMMER_ON) || has(NoteMark.PULL_OFF), { vm.toggleMark(NoteMark.HAMMER_ON) }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Hammer,
            description = tr("Hammer-on / Pull-off", "Hammer-on / pull-off"))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            ChoiceTile("Slide", hasType { it is Articulation.Slide }, { slideMenu = true }, Modifier.fillMaxSize(), icon = TabinoIcons.Slide)
            DropdownMenu(slideMenu, { slideMenu = false }) {
                ProbeComposition("SlideMenu.items")
                listOf(SlideKind.SHIFT to tr("Shift-Slide", "Shift slide"), SlideKind.LEGATO to tr("Legato-Slide", "Legato slide"), SlideKind.INTO_FROM_BELOW to tr("Von unten hinein", "Slide in from below"),
                    SlideKind.INTO_FROM_ABOVE to tr("Von oben hinein", "Slide in from above"), SlideKind.OUT_DOWN to tr("Heraus nach unten", "Slide out down"), SlideKind.OUT_UP to tr("Heraus nach oben", "Slide out up")).forEach { (kind, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { slideMenu = false; vm.articulation(Articulation.Slide(kind)) })
                }
                DropdownMenuItem(text = { Text(tr("Slide entfernen", "Remove slide")) }, onClick = { slideMenu = false; vm.command(EditorCommand.ClearArticulation("slide")) })
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            ChoiceTile("Bend", hasType { it is Articulation.Bend }, { bendMenu = true }, Modifier.fillMaxSize(), icon = TabinoIcons.Bend)
            DropdownMenu(bendMenu, { bendMenu = false }) {
                listOf(tr("Ganzton", "Full") to Articulations.fullBend(), tr("Halbton", "Half") to Articulations.halfBend(), tr("Viertelton", "Quarter") to Articulations.quarterBend(),
                    tr("Bend + Release", "Bend and release") to Articulations.bendRelease(), tr("Prebend", "Pre-bend") to Articulations.prebend(), tr("Prebend + Release", "Pre-bend and release") to Articulations.prebendRelease(),
                    tr("1½ Töne", "1½ steps") to Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 3), 300), PitchCurvePoint(Fraction.ONE, 300)))).forEach { (label, bend) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { bendMenu = false; vm.articulation(bend) })
                }
                DropdownMenuItem(text = { Text(tr("Bend entfernen", "Remove bend")) }, onClick = { bendMenu = false; vm.command(EditorCommand.ClearArticulation("bend")) })
            }
        }
        ChoiceTile("Vibrato", hasType { it is Articulation.Vibrato }, { vm.articulation(Articulation.Vibrato()) }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Vibrato)
        // On a note the tile shows that note's palm mute; on an empty beat the entry mode (QA 21, WorkspaceViewModel.togglePalmMute).
        ChoiceTile("P.M.", vm.palmMuteLit(state), vm::togglePalmMute, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.PalmMute, description = stringResource(R.string.palm_mute))
        ChoiceTile(tr("Mehr", "More"), false, onMore, Modifier.weight(1f).fillMaxHeight().testTag("tools-more"), icon = TabinoIcons.More, description = tr("Weitere Techniken und Takt-Werkzeuge", "More techniques and bar tools"))
    }
}

/**
 * Fret pad: one tap writes the fret on the selected string (colour = string). Two ranges cover 0–24; the bottom row
 * moves the cursor, switches strings and toggles chord mode.
 */
@Composable private fun FretPad(vm: WorkspaceViewModel, state: EditorState, track: Track, input: NoteInput) {
    var high by rememberSaveable { mutableStateOf(false) }
    val note = vm.selectedNote(state)
    val dead = note?.articulations?.contains(Articulation.Mark(NoteMark.DEAD)) == true
    val color = Tabino.stringColor(state.selection.string, track.instrument.tuning.size)
    val base = if (high) 12 else 0
    val frets = (base..minOf(base + 12, track.instrument.maxFret)).toList()
    val cells: List<Int?> = frets + listOf(null) // null = dead note
    cells.chunked(7).forEach { row ->
        Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { fret ->
                if (fret == null) ChoiceTile("x", dead, { if (note != null) vm.toggleMark(NoteMark.DEAD) else vm.commitInput(dead = true) }, Modifier.weight(1f).fillMaxHeight().testTag("fret-x"),
                    accent = color, big = true, description = stringResource(R.string.dead_note), idle = MaterialTheme.colorScheme.surfaceVariant)
                else ChoiceTile("$fret", !dead && note?.fret == fret, { vm.enterFret(fret) }, Modifier.weight(1f).fillMaxHeight().testTag("fret-$fret"),
                    accent = color, big = true, description = "${tr("Bund", "Fret")} $fret", idle = MaterialTheme.colorScheme.surfaceVariant)
            }
            repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    // At the first beat and on the outer strings these keys would do nothing: they show it as disabled (QA 21).
    val atStart = state.selection.offset == Fraction.ZERO && state.selection.measureId == state.project.measures.first().id
    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceTile("", false, { vm.move(-1) }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Left, description = stringResource(R.string.previous), enabled = !atStart)
        ChoiceTile("", false, { vm.moveString(1) }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Up, description = tr("Saite höher", "Higher string"),
            enabled = state.selection.string < track.instrument.tuning.lastIndex)
        ChoiceTile("", false, { vm.moveString(-1) }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Down, description = tr("Saite tiefer", "Lower string"),
            enabled = state.selection.string > 0)
        ChoiceTile(if (high) "0–12" else "12+", high, { high = !high }, Modifier.weight(1f).fillMaxHeight().testTag("fret-range"), description = tr("Bundbereich wechseln", "Switch fret range"))
        ChoiceTile("", input.chordMode, { vm.chordMode(!input.chordMode) }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Chord, description = stringResource(R.string.chord_mode))
        ChoiceTile("", false, vm::backspace, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Erase, description = stringResource(R.string.delete))
        ChoiceTile("", false, { vm.move(1) }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Right, description = stringResource(R.string.next))
    }
}

/** Unfretted tracks (keys and other pitched parts): MIDI note number entry with preview and commit. */
@Composable private fun NotePad(vm: WorkspaceViewModel, input: NoteInput) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("MIDI-Note ", "MIDI note ") + input.buffer.ifEmpty { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        Button(onClick = { vm.commitInput() }, shape = RoundedCornerShape(22.dp), modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.commit)) }
    }
    listOf(listOf(1, 2, 3, 4, 5), listOf(6, 7, 8, 9, 0)).forEach { row ->
        Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { d -> ChoiceTile("$d", false, { vm.digit(d) }, Modifier.weight(1f).fillMaxHeight().testTag("key-$d"), big = true, idle = MaterialTheme.colorScheme.surfaceVariant) }
        }
    }
    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceTile("", false, { vm.move(-1) }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Left, description = stringResource(R.string.previous))
        ChoiceTile("", false, vm::backspace, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Erase, description = stringResource(R.string.delete))
        ChoiceTile("", false, { vm.move(1) }, Modifier.weight(1f).fillMaxHeight(), icon = TabinoIcons.Right, description = stringResource(R.string.next))
    }
}

/** Everything that is not needed for every note: further techniques, rhythm extras and bar tools. */
@Composable private fun MoreTools(vm: WorkspaceViewModel, state: EditorState, input: NoteInput) {
    val practice by vm.practice.collectAsStateWithLifecycle()
    val note = vm.selectedNote(state)
    fun has(kind: NoteMark) = note?.articulations?.contains(Articulation.Mark(kind)) == true
    fun hasType(predicate: (Articulation) -> Boolean) = note?.articulations?.any(predicate) == true
    val beat = vm.selectedBeat(state)
    ProbeComposition("MoreTools")
    // Feedback of these tools (e.g. "Zuerst eine Note wählen") is shown over the top of the sheet by the caller's
    // SheetNoticeOverlay (QA 21: not behind the scrim; QA 23: without pushing the tiles down).
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BigTitle(tr("Werkzeuge", "Tools"), tr("Techniken, Rhythmus, Takte", "Techniques, rhythm, bars"), size = 22)
        @Composable fun section(title: String) = Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        @Composable fun grid(items: List<Triple<String, Boolean, () -> Unit>>) {
            items.chunked(4).forEach { row -> Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, on, action) -> ChoiceTile(label, on, action, Modifier.weight(1f).fillMaxHeight(), idle = MaterialTheme.colorScheme.surfaceVariant) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            } }
        }
        section(tr("Technik", "Technique"))
        grid(listOf(
            Triple(tr("Haltebogen", "Tie"), false) { vm.command(EditorCommand.ToggleTie); Unit },
            Triple("Let Ring", has(NoteMark.LET_RING)) { vm.toggleMark(NoteMark.LET_RING) },
            Triple("Ghost", has(NoteMark.GHOST)) { vm.toggleMark(NoteMark.GHOST) },
            Triple(tr("Akzent", "Accent"), has(NoteMark.ACCENT)) { vm.toggleMark(NoteMark.ACCENT) },
            Triple("Staccato", has(NoteMark.STACCATO)) { vm.toggleMark(NoteMark.STACCATO) },
            Triple("Tapping", has(NoteMark.TAPPING)) { vm.toggleMark(NoteMark.TAPPING) },
            Triple("Tremolo", hasType { it is Articulation.TremoloPicking }) { vm.articulation(Articulation.TremoloPicking(WrittenDuration(32))) },
            Triple("Pick Scrape", has(NoteMark.PICK_SCRAPE)) { vm.toggleMark(NoteMark.PICK_SCRAPE) },
            Triple("Slap", has(NoteMark.SLAP)) { vm.toggleMark(NoteMark.SLAP) },
            Triple("Pop", has(NoteMark.POP)) { vm.toggleMark(NoteMark.POP) },
            Triple(tr("Breites Vib.", "Wide vib."), false) { vm.articulation(Articulation.Vibrato(depthCents = 70, rateHz = 4.5)) },
            Triple("Strum ↓", beat?.strum?.direction == StrokeDirection.DOWN) { vm.command(EditorCommand.SetStrum(if (beat?.strum?.direction == StrokeDirection.DOWN) null else Strum(StrumKind.STRUM, StrokeDirection.DOWN))); Unit },
        ))
        section(tr("Flageoletts", "Harmonics"))
        grid(listOf(HarmonicKind.NATURAL to "N.H.", HarmonicKind.PINCH to "P.H.", HarmonicKind.ARTIFICIAL to "A.H.", HarmonicKind.TAPPED to "T.H.", HarmonicKind.SEMI to "S.H.", HarmonicKind.FEEDBACK to "Feedback").map { (kind, label) ->
            Triple(label, hasType { it is Articulation.Harmonic && it.kind == kind }) { vm.articulation(Articulation.Harmonic(kind)) }
        } + Triple(tr("Entfernen", "Remove"), false) { vm.command(EditorCommand.ClearArticulation("harmonic")); Unit })
        section(tr("Rhythmus", "Rhythm"))
        grid(listOf(
            Triple(tr("Triole", "Triplet"), input.duration.tuplets.isNotEmpty() || beat?.duration?.tuplets?.isNotEmpty() == true) { vm.setTuplet(if (input.duration.tuplets.isEmpty()) TupletRatio(3, 2) else null) },
            Triple("1/64", input.duration.denominator == 64) { vm.setDuration(denominator = 64) },
            Triple(tr("Doppelpunkt", "Double dot"), input.duration.dots == 2) { vm.setDuration(dots = if (input.duration.dots == 2) 0 else 2) },
            Triple(tr("Beat löschen", "Delete beat"), false) { vm.command(EditorCommand.DeleteBeat); Unit },
        ))
        section(tr("Takte", "Bars"))
        grid(listOf(
            Triple(tr("Bereich", "Range"), input.rangeMode) { vm.setRangeMode(!input.rangeMode) },
            Triple(tr("Kopieren", "Copy"), false) { vm.copyBars() },
            // Without clipboard / with a single bar the ViewModel answers with a notice instead of a silent no-op (QA 21).
            Triple(tr("Einfügen", "Paste"), false) { vm.pasteBars() },
            Triple(tr("Duplizieren", "Duplicate"), false) { vm.duplicateBars() },
            Triple(tr("+ Takt", "+ Bar"), false) { vm.insertBar(after = true) },
            Triple(tr("Takt löschen", "Delete bar"), false) { vm.deleteBars() },
            Triple(tr("Leeren", "Clear"), false) { vm.clearBars() },
            // Lit while a loop is set: the loop itself is only drawn on the practice page (QA 21).
            Triple(tr("Loop", "Loop"), practice.loop != null) { vm.loopSelection() },
        ))
    }
}

/** Visual metronome: one dot per beat of the current bar, driven by the audio-clock position (downbeat larger). */
@Composable internal fun BeatDots(signature: TimeSignature, offset: Fraction) {
    val beats = signature.numerator.coerceIn(1, 16)
    val current = (offset.toDouble() * signature.denominator + 1e-9).toInt().coerceIn(0, beats - 1)
    Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(beats) { i ->
            val color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Box(Modifier.size(if (i == 0) 12.dp else 9.dp).background(color, CircleShape))
        }
    }
}
