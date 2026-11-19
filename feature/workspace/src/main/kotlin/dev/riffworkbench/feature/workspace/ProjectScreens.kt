package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.riffworkbench.notation.CursorSnap
import dev.riffworkbench.playback.GuitarActivity
import dev.riffworkbench.playback.HitGlow
import dev.riffworkbench.playback.ScoreClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import kotlinx.coroutines.launch
import dev.riffworkbench.synth.TonePreset
import dev.riffworkbench.synth.TonePresets
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R
import dev.riffworkbench.ui.score.pitchName

@Composable internal fun ScrollPage(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BigTitle(title, null)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable fun ArrangementScreen(vm: WorkspaceViewModel, state: EditorState) {
    val p = state.project
    val measure = p.measures.first { it.id == state.selection.measureId }
    val index = p.measures.indexOf(measure)
    var title by remember(p.metadata.title) { mutableStateOf(p.metadata.title) }
    var artist by remember(p.metadata.artist) { mutableStateOf(p.metadata.artist) }
    var section by remember(p.sections, measure.id) { mutableStateOf(p.sections.firstOrNull { it.measureId == measure.id }?.label ?: "") }
    ScrollPage(stringResource(R.string.arrangement), stringResource(R.string.portrait_hint)) {
        OutlinedTextField(title, { title = it.take(200) }, label = { Text(stringResource(R.string.project_title)) }, modifier = Modifier.textEditing().fillMaxWidth(), singleLine = true)
        OutlinedTextField(artist, { artist = it.take(200) }, label = { Text(tr("Interpret", "Artist")) }, modifier = Modifier.textEditing().fillMaxWidth(), singleLine = true)
        Button(onClick = { vm.command(EditorCommand.SetMetadata(p.metadata.copy(title = title.trim().ifBlank { p.metadata.title }, artist = artist.trim()))) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.rename)) }
        HorizontalDivider()
        Text("${stringResource(R.string.measure)} ${index + 1}", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.measures.forEachIndexed { i, m -> FilterChip(selected = m.id == state.selection.measureId, onClick = { vm.selectCell(state.selection.copy(measureId = m.id, offset = Fraction.ZERO)) },
                label = { Text("${i + 1} · ${m.signature.numerator}/${m.signature.denominator}" + (p.sections.firstOrNull { it.measureId == m.id }?.let { " · ${it.label}" } ?: "")) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.insertBar(after = false) }) { Text(tr("+ Takt davor", "+ Bar before")) }
            OutlinedButton(onClick = { vm.insertBar(after = true) }) { Text(tr("+ Takt danach", "+ Bar after")) }
            OutlinedButton(onClick = vm::duplicateBars) { Text(tr("Duplizieren", "Duplicate")) }
            OutlinedButton(onClick = vm::deleteBars, enabled = p.measures.size > 1) { Text(tr("Löschen", "Delete")) }
            OutlinedButton(onClick = vm::clearBars) { Text(tr("Leeren", "Clear")) }
        }
        SignatureTempoControls(vm, p, measure, index)
        Text(tr("Tonart", "Key"), style = MaterialTheme.typography.titleSmall)
        Stepper(if (measure.key.fifths >= 0) "♯" else "♭", "${kotlin.math.abs(measure.key.fifths)}", { if (measure.key.fifths > -7) vm.command(EditorCommand.SetKey(measure.id, measure.key.copy(fifths = measure.key.fifths - 1))) },
            { if (measure.key.fifths < 7) vm.command(EditorCommand.SetKey(measure.id, measure.key.copy(fifths = measure.key.fifths + 1))) })
        ToggleRow(tr("Moll", "Minor"), measure.key.minor, { vm.command(EditorCommand.SetKey(measure.id, measure.key.copy(minor = it))) })
        Text(tr("Rhythmisches Feel", "Rhythmic feel"), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RhythmicFeel.STRAIGHT to tr("Gerade", "Straight"), RhythmicFeel.SWING_EIGHTH to "Swing 8tel", RhythmicFeel.SWING_SIXTEENTH to "Swing 16tel", RhythmicFeel.TRIPLET to tr("Triolisch", "Triplet feel")).forEach { (feel, label) ->
                FilterChip(measure.feel == feel, { vm.command(EditorCommand.SetFeel(measure.id, feel)) }, label = { Text(label) })
            }
        }
        Text(tr("Wiederholungen", "Repeats"), style = MaterialTheme.typography.titleSmall)
        ToggleRow(tr("Wiederholung beginnt hier", "Repeat starts here"), measure.repeat.starts, { vm.command(EditorCommand.SetRepeat(measure.id, measure.repeat.copy(starts = it))) })
        Stepper(tr("Endet ×", "Ends ×"), "${measure.repeat.endCount ?: "–"}", { val c = measure.repeat.endCount; vm.command(EditorCommand.SetRepeat(measure.id, measure.repeat.copy(endCount = if (c == null || c <= 2) null else c - 1))) },
            { val c = measure.repeat.endCount; vm.command(EditorCommand.SetRepeat(measure.id, measure.repeat.copy(endCount = ((c ?: 1) + 1).coerceAtMost(32)))) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Volta", "Ending"), style = MaterialTheme.typography.bodyMedium)
            (1..4).forEach { n -> FilterChip(n in measure.repeat.alternateEndings, { vm.command(EditorCommand.SetRepeat(measure.id, measure.repeat.copy(alternateEndings = if (n in measure.repeat.alternateEndings) measure.repeat.alternateEndings - n else measure.repeat.alternateEndings + n))) }, label = { Text("$n.") }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(NavigationKind.SEGNO to "Segno", NavigationKind.CODA to "Coda", NavigationKind.TO_CODA to "To Coda", NavigationKind.DA_CAPO to "D.C.", NavigationKind.DAL_SEGNO to "D.S.", NavigationKind.FINE to "Fine").forEach { (kind, label) ->
                val on = measure.navigation.any { it.kind == kind }
                FilterChip(on, { vm.command(EditorCommand.SetNavigation(measure.id, if (on) measure.navigation.filterNot { it.kind == kind } else measure.navigation + NavigationMark(kind))) }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(section, { section = it.take(80) }, label = { Text(tr("Abschnitt", "Section")) }, modifier = Modifier.textEditing().weight(1f), singleLine = true)
            Button(onClick = { vm.command(EditorCommand.SetSection(measure.id, section.ifBlank { null })) }) { Text(tr("Setzen", "Set")) }
        }
        HorizontalDivider()
        Text(tr("Spuren", "Tracks"), style = MaterialTheme.typography.titleMedium)
        p.tracks.forEach { track -> TrackCard(vm, p, track) }
        Text(stringResource(R.string.add_track), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(InstrumentKind.GUITAR to R.string.guitar, InstrumentKind.BASS to R.string.bass, InstrumentKind.PERCUSSION to R.string.percussion, InstrumentKind.PIANO to R.string.piano).forEach { (kind, label) ->
                OutlinedButton(onClick = { vm.command(EditorCommand.AddTrack(kind)) }) { Text("+ ${stringResource(label)}") }
            }
        }
        if (state.diagnostics.isNotEmpty()) { Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleMedium); state.diagnostics.take(8).forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable private fun TrackCard(vm: WorkspaceViewModel, p: Project, track: Track) {
    var name by remember(track.id, track.name) { mutableStateOf(track.name) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it.take(120) }, modifier = Modifier.textEditing().weight(1f), singleLine = true, label = { Text(stringResource(R.string.track)) })
                TextButton(onClick = { vm.command(EditorCommand.RenameTrack(track.id, name)) }, enabled = name.isNotBlank() && name != track.name) { Text("✓") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.command(EditorCommand.MoveTrack(track.id, -1)) }, modifier = Modifier.semanticsLabel(stringResource(R.string.move_up))) { Text("↑") }
                TextButton(onClick = { vm.command(EditorCommand.MoveTrack(track.id, 1)) }, modifier = Modifier.semanticsLabel(stringResource(R.string.move_down))) { Text("↓") }
                TextButton(onClick = { vm.command(EditorCommand.DuplicateTrack(track.id)) }) { Text(tr("Duplizieren", "Duplicate")) }
                TextButton(onClick = { vm.command(EditorCommand.RemoveTrack(track.id)) }, enabled = p.tracks.size > 1) { Text(stringResource(R.string.remove_track)) }
            }
        }
    }
}

/**
 * Drum step grid (Zyklus 29, „durchgehendes Drum-Raster“): all bars side by side in ONE horizontally scrolling area
 * (lazy, one item per bar). Every cell writes into its own bar — no bar has to be selected first; the bar chips only
 * scroll there. Each bar starts with a barline, its number and, where they change, meter and tempo; the beat ruler and the
 * wider gap before every beat stay (QA 19). The element names stay in a fixed column. The grid is the entry duration
 * (Zyklus 23) and applies to all bars; the cells per bar come from [DrumGrid] (every bar complete, exact positions).
 * While playing, the sounding column is tinted over all rows and the view follows minimally ([DrumGridGeometry.follow]);
 * a swipe pauses the follow like in the editor. The playhead is read only in the draw phase and in
 * [DrumPlayheadFollower], so the grid content does not recompose on transport polls (Zyklus 23).
 */
@Composable fun DrumScreen(vm: WorkspaceViewModel, state: EditorState) {
    ProbeComposition("DrumScreen")
    val track = state.project.tracks.firstOrNull { it.id == state.selection.trackId && it.instrument.kind == InstrumentKind.PERCUSSION }
        ?: state.project.tracks.firstOrNull { it.instrument.kind == InstrumentKind.PERCUSSION }
    val input by vm.input.collectAsStateWithLifecycle()
    val step = DrumGrid.step(input.duration)
    ScrollPage(stringResource(R.string.drums), stringResource(R.string.stepgrid_hint)) {
        if (track == null) {
            NoticeCard(stringResource(R.string.no_drum_track))
            Button(onClick = { vm.command(EditorCommand.AddTrack(InstrumentKind.PERCUSSION)) }) { Text(stringResource(R.string.add_track)) }
        } else {
            val project = state.project
            val bars = remember(project.measures, project.tempo, step) { DrumGrid.bars(project, step) }
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val cursorBar = project.measures.indexOfFirst { it.id == state.selection.measureId }
            // Jump only: the cells address their bar themselves.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("drum-bar-jump")) {
                items(bars.size) { index ->
                    FilterChip(selected = index == cursorBar, onClick = { scope.launch { listState.animateScrollToItem(index) } },
                        label = { Text("${stringResource(R.string.measure)} ${index + 1}") })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..3).forEach { voice -> FilterChip(selected = voice == state.selection.voice, onClick = { vm.select(state.selection.copy(trackId = track.id, voice = voice, string = 0)) }, label = { Text("${stringResource(R.string.voice)} ${voice + 1}") }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Raster", "Grid"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DrumGridChoices.forEach { (duration, label) ->
                    FilterChip(DrumGrid.step(duration) == step, { vm.setGrid(duration) }, label = { Text(label) })
                }
            }
            DrumGridArea(vm, project, track, state.selection, bars, listState)
            DrumKitMix(vm, track)
            val note = vm.selectedBeat(state)?.notes?.firstOrNull()
            if (note != null) {
                Text(tr("Ausgewählter Schlag", "Selected hit"), style = MaterialTheme.typography.titleSmall)
                var velocity by remember(note.id, note.velocity) { mutableFloatStateOf(note.velocity.toFloat()) }
                Text("Velocity ${velocity.toInt()}")
                Slider(velocity, { velocity = it }, valueRange = 1f..127f, onValueChangeFinished = { vm.command(EditorCommand.SetVelocity(velocity.toInt())) }, modifier = Modifier.semanticsLabel("Velocity"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(PercussionTechnique.GHOST to "Ghost", PercussionTechnique.RIMSHOT to "Rimshot", PercussionTechnique.CROSS_STICK to "Cross-Stick", PercussionTechnique.CHOKE to "Choke", PercussionTechnique.OPEN_HI_HAT to "HH offen").forEach { (kind, label) ->
                        AssistChip(onClick = { vm.articulation(Articulation.Percussion(kind)) }, label = { Text(label) })
                    }
                }
            }
        }
    }
}

/** Grid choices of the drum screen (entry durations; 1/8 T = 12, 1/16 T = 24 cells per whole note). */
private val DrumGridChoices = listOf(
    WrittenDuration(8) to "1/8", WrittenDuration(16) to "1/16", WrittenDuration(32) to "1/32",
    WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2))) to "1/8 T", WrittenDuration(16, tuplets = listOf(TupletRatio(3, 2))) to "1/16 T",
)

/** Sounding column (bar index, cell index) for tests: set by [DrumPlayheadFollower], null while stopped. */
internal val DrumPlayheadKey = SemanticsPropertyKey<Pair<Int, Int>?>("DrumPlayhead")

/**
 * Zyklus 30: live values of the drum grid — written by the playhead follower, the frame ticker and the positioning gesture,
 * read ONLY in draw lambdas (and by the test probe), so playback and dragging never recompose a bar: they invalidate the
 * draw of the visible bars that show something, nothing else.
 */
@Stable
private class DrumLive {
    /** Sounding column (bar index, cell index), null while stopped. */
    val column = mutableStateOf<Pair<Int, Int>?>(null)
    /** Displayed playhead (incl. the Bluetooth delay) in score seconds ([ScoreClock]) at the last poll; NaN while stopped. */
    val now = mutableDoubleStateOf(Double.NaN)
    @Volatile var pollNanos = 0L
    @Volatile var speed = 1.0
    @Volatile var playing = false
    /** Frame tick while playing: lets the glow fade between two polls (~30 Hz). */
    val frame = mutableLongStateOf(0L)
    /** Sounding guitar section: bar index, from, to (whole notes). */
    val laneSection = mutableStateOf<Triple<Int, Double, Double>?>(null)
    /** Cursor/start marker: bar index and offset (whole notes); null when the cursor is not in a shown bar. */
    val cursor = mutableStateOf<Pair<Int, Double>?>(null)
    /** Positioning drag: finger x and snapped x (global dp over all bars), NaN when idle. */
    val handle = mutableFloatStateOf(Float.NaN)
    val snapped = mutableFloatStateOf(Float.NaN)

    /** Score time for the glow: the last poll, extrapolated by the real time since (at most 0.1 s) while playing. */
    fun scoreNow(): Double {
        val n = now.doubleValue
        if (!playing || n.isNaN()) return n
        return n + ((System.nanoTime() - pollNanos) / 1e9).coerceIn(0.0, 0.1) * speed
    }
}

/** Fixed name column plus the lazy row of bars; playhead, glow, guitar lane highlight and cursor handle are draw-phase reads of [DrumLive]. */
@Composable private fun DrumGridArea(vm: WorkspaceViewModel, project: Project, track: Track, selection: Selection, bars: List<DrumGrid.Bar>, listState: LazyListState) {
    ProbeComposition("DrumGrid")
    val live = remember { DrumLive().also { LiveObjects.track("DrumLive", it) } }
    val clock = remember(project.measures, project.tempo, project.tracks) { ScoreClock(project) }
    // Common activity of all guitars (GuitarActivity); no guitar: no lane.
    val lanes = remember(project.measures, project.tracks) { GuitarActivity.lanes(project) }
    val voice = selection.voice
    val staff = track.staves.first()
    // Zyklus 31: by measure id (was a search over all bars per bar: quadratic in the bar count).
    val beatsByBar = remember(staff, bars, voice) {
        val byMeasure = staff.bars.associateBy { it.measureId }
        bars.map { b -> byMeasure[b.measureId]?.voices?.firstOrNull { it.index == voice }?.beats.orEmpty() }
    }
    val starts = remember(bars) { DrumGridGeometry.barStarts(bars) }
    DrumPlayheadFollower(vm, bars, starts, lanes, clock, listState, live)
    // The cursor is the start marker (Zyklus 18); a positioning drag moves it.
    val cursorBar = bars.indexOfFirst { it.measureId == selection.measureId }
    val cursorOffset = selection.offset.toDouble()
    SideEffect { live.cursor.value = if (cursorBar >= 0) cursorBar to cursorOffset else null }
    val haptics = LocalHapticFeedback.current
    val positioning = remember(vm, listState) { DrumPositioning(vm, listState, live) }
    SideEffect { positioning.update(bars, starts, beatsByBar, lanes, haptics) }
    // A swipe by the user pauses the follow (like a pan on the score); programmatic scrolls do not pass nested scrolling.
    val userScroll = remember(vm) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.x != 0f && vm.transport.state.value.status == TransportStatus.PLAYING) vm.suspendFollow()
                return Offset.Zero
            }
        }
    }
    val kit = track.instrument.drumKit
    val hasLane = lanes.isNotEmpty()
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(76.dp), verticalArrangement = Arrangement.spacedBy(DrumGridGeometry.ROW_GAP.dp)) {
            if (hasLane) Box(Modifier.height(DrumGridActivity.LANE.dp), contentAlignment = Alignment.CenterStart) {
                Text(tr("Gitarre", "Guitar"), style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 8.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { })
            }
            Spacer(Modifier.height(DrumGridGeometry.HEADER.dp))
            Spacer(Modifier.height(DrumGridGeometry.RULER.dp))
            kit.forEach { drum ->
                Box(Modifier.height(DrumGridGeometry.CELL.dp), contentAlignment = Alignment.CenterStart) { Text(drum.name, maxLines = 2, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Box(Modifier.weight(1f).nestedScroll(userScroll)) {
            LazyRow(state = listState, modifier = Modifier.fillMaxWidth().testTag("drum-grid")) {
                items(bars.size, key = { bars[it].measureId.value }, contentType = { "bar" }) { index ->
                    DrumBar(vm, track.id, kit, bars[index], starts[index], beatsByBar[index], lanes.getOrNull(index), clock, live, positioning)
                }
            }
            DrumFollowChip(vm, Modifier.align(Alignment.TopEnd))
            DrumPlayheadProbe(live.column)
        }
    }
}

/** Test hook: publishes the sounding column as semantics; recomposes only when the column changes, not per poll. */
@Composable private fun DrumPlayheadProbe(playColumn: State<Pair<Int, Int>?>) {
    val column = playColumn.value
    Box(Modifier.size(0.dp).testTag("drum-playhead").semantics { this[DrumPlayheadKey] = column })
}

/**
 * Reads the transport (shown playhead incl. the Bluetooth delay of Zyklus 26) in a coroutine, never in composition: sets
 * the sounding column, the score time of the glow and the sounding guitar section, and, while playing with follow on,
 * scrolls the grid minimally to keep the column visible (only when it leaves the visible part, [DrumGridGeometry.follow]).
 * A frame ticker runs only while playing, so the glow fades smoothly between polls; it writes one state that only the
 * bars around the playhead read in their draw phase.
 */
@Composable private fun DrumPlayheadFollower(vm: WorkspaceViewModel, bars: List<DrumGrid.Bar>, starts: FloatArray, lanes: List<GuitarActivity.BarLane>, clock: ScoreClock,
                                             listState: LazyListState, live: DrumLive) {
    val currentBars by rememberUpdatedState(bars)
    val currentStarts by rememberUpdatedState(starts)
    val currentLanes by rememberUpdatedState(lanes)
    val currentClock by rememberUpdatedState(clock)
    val density = LocalDensity.current.density
    // Zyklus 31 (P0 „stürzt bei zu viel Drum-Grid ab“): restarted when the bars change (grid switch, meter change, bars
    // added/removed). The transport's StateFlow then delivers its current value again, so a column that stands still
    // (paused, or between two polls) is located in the NEW cells at once; the draw phase additionally ignores a column
    // index the bar does not have for the frame before that.
    LaunchedEffect(vm, listState, bars) {
        vm.transport.state.collect { s ->
            val shown = vm.displayedPlayhead(s)
            val all = currentBars
            val column = shown?.let { DrumGrid.locate(all, it.position.measureId, it.position.offset) }
            live.column.value = column
            live.playing = s.status == TransportStatus.PLAYING
            live.speed = s.speed.takeIf { it > 0.0 } ?: 1.0
            live.pollNanos = System.nanoTime()
            if (shown == null) { live.now.doubleValue = Double.NaN; live.laneSection.value = null }
            else {
                val offset = shown.position.offset.toDouble()
                live.now.doubleValue = currentClock.seconds(shown.position.measureId, offset)
                val bar = column?.first ?: -1
                live.laneSection.value = currentLanes.getOrNull(bar)?.let { GuitarActivity.soundingAt(it, offset) }?.let { (a, b) -> Triple(bar, a.toDouble(), b.toDouble()) }
            }
            val view = vm.view.value
            if (column != null && s.status == TransportStatus.PLAYING && view.follow && !view.followSuspended) {
                // Bar starts are remembered per grid (was: recomputed over all bars on every poll).
                val starts = currentStarts
                val viewport = listState.layoutInfo.viewportSize.width / density
                val first = listState.firstVisibleItemIndex
                if (viewport > 0f && first < all.size) {
                    val scroll = starts[first] + listState.firstVisibleItemScrollOffset / density
                    val x = starts[column.first] + DrumGridGeometry.cellX(all[column.first].cells)[column.second]
                    val target = DrumGridGeometry.follow(x, scroll, viewport, starts.last())
                    if (kotlin.math.abs(target - scroll) > 0.5f) listState.scrollBy((target - scroll) * density)
                }
            }
        }
    }
    LaunchedEffect(vm) {
        vm.transport.state.map { it.status == TransportStatus.PLAYING }.distinctUntilChanged().collectLatest { playing ->
            if (!playing) return@collectLatest
            // Under UI tests the infinite-animation policy cancels this loop (the glow then advances per poll only).
            try { while (true) infiniteFrame { t -> live.frame.longValue = t } }
            catch (e: kotlinx.coroutines.CancellationException) { if (!currentCoroutineContext().isActive) throw e }
        }
    }
}

/**
 * Zyklus 30 „Cursor selbst schieben“: the positioning strip (guitar lane, bar header, beat ruler) of every bar. Touching
 * it only moves the cursor/start marker: the handle follows the finger over all bars (auto-scroll at the edges), snaps on
 * the nearest hit or guitar attack within [DrumGridActivity.SNAP_RADIUS], else on the nearest cell, with a light tick per
 * snap change; the release commits ONE [WorkspaceViewModel.positionCursor] (a seek while playing). Cells stay editing.
 */
private class DrumPositioning(private val vm: WorkspaceViewModel, private val listState: LazyListState, private val live: DrumLive) {
    private var starts = FloatArray(1)
    private var onsets: List<CursorSnap.Target> = emptyList()
    private var grid: List<CursorSnap.Target> = emptyList()
    private var haptics: androidx.compose.ui.hapticfeedback.HapticFeedback? = null
    private var source: Triple<List<DrumGrid.Bar>, List<List<Beat>>, List<GuitarActivity.BarLane>>? = null
    private var fingerInView = 0f
    private var target: CursorSnap.Target? = null
    var density = 1f

    fun update(bars: List<DrumGrid.Bar>, starts: FloatArray, beats: List<List<Beat>>, lanes: List<GuitarActivity.BarLane>, haptics: androidx.compose.ui.hapticfeedback.HapticFeedback) {
        this.haptics = haptics
        this.starts = starts
        // Zyklus 31: by identity (the lists are remembered); `equals` compared every bar, beat and note on each recomposition.
        val old = source
        if (old == null || old.first !== bars || old.second !== beats || old.third !== lanes) {
            source = Triple(bars, beats, lanes)
            DrumGridActivity.snapTargets(bars, beats, lanes).let { (o, g) -> onsets = o; grid = g }
        }
    }
    private fun scroll(): Float {
        val first = listState.firstVisibleItemIndex
        return (starts.getOrNull(first) ?: 0f) + listState.firstVisibleItemScrollOffset / density
    }
    fun start(bar: Int, localX: Float) { vm.beginPositioning(); target = null; move(bar, localX) }
    fun move(bar: Int, localX: Float) { fingerInView = (starts.getOrNull(bar) ?: 0f) + localX - scroll(); track() }
    private fun edgeDelta(): Float {
        val viewport = listState.layoutInfo.viewportSize.width / density
        if (viewport <= 0f) return 0f
        val zone = DrumGridGeometry.CELL
        val delta = when { fingerInView < zone -> -(zone - fingerInView) / 3f; fingerInView > viewport - zone -> (fingerInView - viewport + zone) / 3f; else -> 0f }
        // Nothing left to scroll in that direction: no auto-scroll (and no wake-ups).
        return if ((delta < 0f && !listState.canScrollBackward) || (delta > 0f && !listState.canScrollForward)) 0f else delta
    }
    /** The finger rests in the edge zone and the grid can still scroll that way. */
    fun atEdge(): Boolean = edgeDelta() != 0f
    /** Auto-scroll while the finger rests near an edge (called about every frame without pointer events). */
    fun edge() {
        val delta = edgeDelta()
        if (delta != 0f && listState.dispatchRawDelta(delta * density) != 0f) track()
    }
    private fun track() {
        val x = fingerInView + scroll()
        live.handle.floatValue = x
        val snap = CursorSnap.snap(x, onsets, grid, DrumGridActivity.SNAP_RADIUS)
        live.snapped.floatValue = snap?.x ?: Float.NaN
        if (snap != null && snap != target) { if (target != null) haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove); target = snap }
    }
    fun release() { val t = target; end(); if (t != null) vm.positionCursor(t.measureId, t.offset) }
    fun cancel() = end()
    private fun end() { live.handle.floatValue = Float.NaN; live.snapped.floatValue = Float.NaN; target = null }
}

/** One frame of an endless animation; under UI tests the [InfiniteAnimationPolicy] decides (it cancels it there). */
private suspend fun infiniteFrame(onFrame: (Long) -> Unit) {
    val policy = currentCoroutineContext()[InfiniteAnimationPolicy]
    if (policy == null) withFrameNanos(onFrame) else policy.onInfiniteOperation { withFrameNanos(onFrame) }
}

/** „Folgen“ again after a swipe paused it while playing (like the score's chip). */
@Composable private fun DrumFollowChip(vm: WorkspaceViewModel, modifier: Modifier) {
    val transport = vm.transport.state.collectAsStateWithLifecycle()
    val playing by remember(transport) { derivedStateOf { transport.value.status == TransportStatus.PLAYING } }
    val view by vm.view.collectAsStateWithLifecycle()
    if (playing && view.followSuspended) {
        Surface(onClick = vm::resumeFollow, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = modifier.padding(4.dp).height(40.dp).testTag("drum-follow-resume")) {
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.follow_resume), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Static draw data of one bar (computed when its content changes, never per frame). */
private class DrumBarPaint(val xs: FloatArray, val active: BooleanArray, val glowRow: IntArray, val glowCell: IntArray, val glowOnset: DoubleArray,
                           val laneSpans: FloatArray, val laneAttacks: FloatArray)

/**
 * One bar (Zyklus 30: one layer instead of one button per cell): barline, guitar lane, header (number, meter/tempo where
 * they change), beat ruler and ALL cells of all kit rows drawn in one cached graphics layer; a tap is hit-tested by
 * coordinate ([DrumGridGeometry.cellAt]). Each cell keeps a light semantics node (label „Kick, 3, off“ — from bar 2 on with
 * the bar —, testTag `drum-cell-<bar>-<cell>-<midi>`, click action, 48 dp) for TalkBack and the tests. Playhead column,
 * hit glow, the sounding guitar section and the cursor handle are drawn over it in the draw phase only.
 */
@Composable private fun DrumBar(vm: WorkspaceViewModel, trackId: EntityId, kit: List<DrumElement>, bar: DrumGrid.Bar, start: Float, beats: List<Beat>,
                                lane: GuitarActivity.BarLane?, clock: ScoreClock, live: DrumLive, positioning: DrumPositioning) {
    ProbeComposition("DrumGrid.bar")
    val rows = kit.size
    val paint = remember(bar, beats, kit, lane, clock) {
        val xs = DrumGridGeometry.cellX(bar.cells)
        val hits = HashMap<Fraction, Set<Int>>()
        beats.forEach { b -> hits[b.offset] = b.notes.map { it.pitch.midiNumber }.toSet() }
        val active = BooleanArray(rows * bar.cells.size)
        val gr = ArrayList<Int>(); val gc = ArrayList<Int>(); val go = ArrayList<Double>()
        kit.forEachIndexed { r, drum -> bar.cells.forEach { c ->
            if (hits[c.offset]?.contains(drum.midiNote) == true) { active[r * bar.cells.size + c.index] = true; gr += r; gc += c.index; go += clock.seconds(bar.index, c.offset.toDouble()) }
        } }
        val spans = lane?.spans.orEmpty().flatMap { (a, b) -> listOf(DrumGridActivity.xAt(bar.cells, xs, bar.length, a.toDouble()), DrumGridActivity.xAt(bar.cells, xs, bar.length, b.toDouble())) }.toFloatArray()
        val attacks = lane?.attacks.orEmpty().map { DrumGridActivity.xAt(bar.cells, xs, bar.length, it.toDouble()) }.toFloatArray()
        DrumBarPaint(xs, active, gr.toIntArray(), gc.toIntArray(), go.toDoubleArray(), spans, attacks).also { LiveObjects.track("DrumBarPaint", it) }
    }
    val colors = MaterialTheme.colorScheme
    val barline = colors.outline
    val primary = colors.primary
    val laneGround = colors.surfaceVariant
    val tint = Tabino.Sky.copy(alpha = 0.32f)
    val number = bar.index + 1
    val barWord = tr("Takt", "Bar")
    val laneWord = tr("Gitarren-Aktivität", "Guitar activity")
    val header = buildString {
        append(barWord).append(' ').append(number)
        bar.signature?.let { append(" · ").append(it.numerator).append('/').append(it.denominator) }
        bar.tempo?.let { append(" · ♩=").append(kotlin.math.round(it).toInt()) }
    }
    val width = DrumGridGeometry.barWidth(bar.cells)
    val hasLane = lane != null
    // Offset (dp) of the cell rows below the top of the bar item; the strip above them is lane + header + ruler.
    val rowsTop = (if (hasLane) DrumGridActivity.LANE + DrumGridGeometry.ROW_GAP else 0f) + DrumGridGeometry.HEADER + DrumGridGeometry.ROW_GAP + DrumGridGeometry.RULER + DrumGridGeometry.ROW_GAP
    val rulerTop = rowsTop - DrumGridGeometry.ROW_GAP - DrumGridGeometry.RULER
    val density = LocalDensity.current.density
    positioning.density = density
    val capPath = remember { androidx.compose.ui.graphics.Path() }
    val onCell = remember(vm, trackId, bar.measureId) { { offset: Fraction, midi: Int -> vm.toggleDrum(trackId, bar.measureId, offset, midi) } }
    Box(Modifier.width(width.dp).testTag("drum-bar-$number").drawWithContent {
        drawContent()
        drawRect(barline, topLeft = Offset.Zero, size = Size(2.dp.toPx(), size.height))
        val cellPx = DrumGridGeometry.CELL.dp.toPx()
        // Playhead column over the ruler and all rows. Zyklus 31: only a cell this bar has — a column located in another
        // grid (switched while paused or between polls) indexed past the cells and the draw threw: the device crash.
        val column = live.column.value
        if (column != null && column.first == bar.index && column.second in paint.xs.indices) {
            val top = rulerTop.dp.toPx()
            drawRect(tint, topLeft = Offset((paint.xs[column.second] - 2f).dp.toPx(), top), size = Size(cellPx + 4.dp.toPx(), size.height - top))
        }
        // Hit glow: only the bar that plays and the one before it (whose last hits may still fade) read the clock.
        if (column != null && (column.first == bar.index || column.first == bar.index + 1) && paint.glowOnset.isNotEmpty()) {
            live.frame.longValue
            val now = live.scoreNow()
            val speed = live.speed
            val top = rowsTop.dp.toPx()
            val step = (DrumGridGeometry.CELL + DrumGridGeometry.ROW_GAP).dp.toPx()
            for (k in paint.glowOnset.indices) {
                val i = HitGlow.intensity(paint.glowOnset[k], now, speed)
                if (i <= 0f) continue
                val glow = Tabino.Accents[paint.glowRow[k] % Tabino.Accents.size]
                val center = Offset(paint.xs[paint.glowCell[k]].dp.toPx() + cellPx / 2, top + paint.glowRow[k] * step + cellPx / 2)
                drawCircle(glow.copy(alpha = 0.45f * i), radius = cellPx / 2 + 6.dp.toPx() * i, center = center)
                drawCircle(Color.White.copy(alpha = 0.85f * i), radius = cellPx / 2 - 2.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
            }
        }
        // Sounding guitar section.
        if (hasLane) {
            val section = live.laneSection.value
            if (section != null && section.first == bar.index) {
                val a = DrumGridActivity.xAt(bar.cells, paint.xs, bar.length, section.second).dp.toPx()
                val b = DrumGridActivity.xAt(bar.cells, paint.xs, bar.length, section.third).dp.toPx()
                drawRoundRect(Tabino.Sun, topLeft = Offset(a, 0f), size = Size((b - a).coerceAtLeast(3.dp.toPx()), DrumGridActivity.LANE.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }
        // Cursor (start marker) or, while dragging, the snapped handle plus a ghost line under the finger.
        val handle = live.handle.floatValue
        val markerX: Float = if (!handle.isNaN()) live.snapped.floatValue - start
            else live.cursor.value?.takeIf { it.first == bar.index }?.let { DrumGridActivity.xAt(bar.cells, paint.xs, bar.length, it.second) } ?: Float.NaN
        if (!markerX.isNaN() && markerX >= 0f && markerX < width) {
            val x = (markerX - 2f).dp.toPx()
            drawLine(primary, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            val capTop = rulerTop.dp.toPx()
            val cap = 6.dp.toPx()
            drawPath(capPath.apply { reset(); moveTo(x, capTop); lineTo(x + cap * 1.4f, capTop + cap); lineTo(x, capTop + cap * 2); close() }, primary)
        }
        if (!handle.isNaN() && handle - start in 0f..width) {
            val x = (handle - start).dp.toPx()
            drawLine(primary.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(DrumGridGeometry.ROW_GAP.dp)) {
            // Positioning strip: lane, header, ruler. It never edits (see DrumPositioning); the cells below stay editing.
            Column(Modifier.fillMaxWidth().testTag("drum-strip-$number").pointerInput(bar.index, positioning) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    positioning.start(bar.index, down.position.x / density)
                    while (true) {
                        // Only near an edge does the loop wake up without pointer events (auto-scroll); otherwise it just waits.
                        val event = if (positioning.atEdge()) withTimeoutOrNull(16L) { awaitPointerEvent() } else awaitPointerEvent()
                        if (event == null) { positioning.edge(); continue }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) { positioning.cancel(); break }
                        if (!change.pressed) { val cancelled = change.isConsumed; change.consume(); if (cancelled) positioning.cancel() else positioning.release(); break }
                        change.consume()
                        positioning.move(bar.index, change.position.x / density)
                    }
                }
            }, verticalArrangement = Arrangement.spacedBy(DrumGridGeometry.ROW_GAP.dp)) {
                if (lane != null) Spacer(Modifier.fillMaxWidth().height(DrumGridActivity.LANE.dp)
                    .semantics { contentDescription = if (number > 1) "$laneWord, $barWord $number" else laneWord }
                    .drawBehind {
                        val h = size.height
                        drawRect(laneGround, size = Size(size.width, h))
                        var k = 0
                        while (k + 1 < paint.laneSpans.size) {
                            val a = paint.laneSpans[k].dp.toPx(); val b = paint.laneSpans[k + 1].dp.toPx()
                            drawRoundRect(Tabino.Lime.copy(alpha = 0.55f), topLeft = Offset(a, h * 0.25f), size = Size((b - a).coerceAtLeast(2.dp.toPx()), h * 0.5f), cornerRadius = CornerRadius(h * 0.25f))
                            k += 2
                        }
                        for (x in paint.laneAttacks) drawRect(Tabino.Lime, topLeft = Offset(x.dp.toPx(), 0f), size = Size(2.dp.toPx(), h))
                    })
                Box(Modifier.fillMaxWidth().height(DrumGridGeometry.HEADER.dp).padding(start = DrumGridGeometry.BAR_START.dp), contentAlignment = Alignment.CenterStart) {
                    Text(header, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, color = colors.onSurface)
                }
                Box(Modifier.fillMaxWidth().height(DrumGridGeometry.RULER.dp)) {
                    bar.cells.forEach { cell ->
                        if (cell.beatStart) Text("${cell.beat + 1}", Modifier.absoluteOffset(x = paint.xs[cell.index].dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            DrumCells(kit, bar, paint, number, barWord, onCell)
        }
    }
}

/**
 * All cells of one bar: drawn in one cached layer (circles like the former tonal buttons, ● set, · empty), tapped by
 * coordinate; one light semantics node per cell (no button, no ripple, no own pointer input).
 */
@Composable private fun DrumCells(kit: List<DrumElement>, bar: DrumGrid.Bar, paint: DrumBarPaint, number: Int, barWord: String, onCell: (Fraction, Int) -> Unit) {
    ProbeComposition("DrumGrid.cells")
    val colors = MaterialTheme.colorScheme
    val on = colors.primary; val onDot = colors.onPrimary
    val beatFill = colors.surfaceVariant; val fill = colors.surface; val dot = colors.onSurfaceVariant
    val rows = kit.size
    val n = bar.cells.size
    val currentOnCell by rememberUpdatedState(onCell)
    val measurePolicy = remember(paint, rows) {
        MeasurePolicy { measurables, constraints ->
            val cellPx = DrumGridGeometry.CELL.dp.roundToPx()
            val fixed = Constraints.fixed(cellPx, cellPx)
            val placeables = measurables.map { it.measure(fixed) }
            layout(constraints.maxWidth, DrumGridGeometry.rowsHeight(rows).dp.roundToPx()) {
                placeables.forEachIndexed { i, p -> p.place(paint.xs[i % n].dp.roundToPx(), ((i / n) * (DrumGridGeometry.CELL + DrumGridGeometry.ROW_GAP)).dp.roundToPx()) }
            }
        }
    }
    Layout(content = {
        kit.forEachIndexed { row, drum ->
            bar.cells.forEach { cell ->
                Spacer(Modifier.semantics {
                    // Built lazily (only when semantics are read: TalkBack, tests). „Kick, 3, off“ as before; from bar 2 on with the bar.
                    val active = paint.active[row * n + cell.index]
                    contentDescription = "${drum.name}, ${cell.index + 1}, ${if (active) "on" else "off"}" + if (number > 1) ", $barWord $number" else ""
                    testTag = "drum-cell-$number-${cell.index + 1}-${drum.midiNote}"
                    role = Role.Button
                    onClick { currentOnCell(cell.offset, drum.midiNote); true }
                })
            }
        }
    }, modifier = Modifier.fillMaxWidth().height(DrumGridGeometry.rowsHeight(rows).dp)
        .graphicsLayer { }
        .drawBehind {
            val cellPx = DrumGridGeometry.CELL.dp.toPx()
            val r = cellPx / 2
            val step = (DrumGridGeometry.CELL + DrumGridGeometry.ROW_GAP).dp.toPx()
            for (row in 0 until rows) for (c in 0 until n) {
                val center = Offset(paint.xs[c].dp.toPx() + r, row * step + r)
                val active = paint.active[row * n + c]
                drawCircle(if (active) on else if (bar.cells[c].beatStart) beatFill else fill, radius = r, center = center)
                drawCircle(if (active) onDot else dot, radius = if (active) 5.dp.toPx() else 1.5.dp.toPx(), center = center)
            }
        }
        .pointerInput(paint, kit) {
            detectTapGestures { pos ->
                val hit = DrumGridGeometry.cellAt(paint.xs, rows, pos.x / density, pos.y / density) ?: return@detectTapGestures
                currentOnCell(bar.cells[hit.second].offset, kit[hit.first].midiNote)
            }
        },
        measurePolicy = measurePolicy)
}

/** Level and pan per kit element (kick louder, toms panned); one transaction per slider gesture. */
@Composable private fun DrumKitMix(vm: WorkspaceViewModel, track: Track) {
    var selected by remember(track.id) { mutableStateOf<Int?>(null) }
    Text(tr("Kit-Mix pro Element", "Kit mix per element"), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        track.instrument.drumKit.forEach { drum ->
            FilterChip(selected == drum.midiNote, { selected = if (selected == drum.midiNote) null else drum.midiNote }, label = { Text(drum.name) })
        }
    }
    val drum = track.instrument.drumKit.firstOrNull { it.midiNote == selected } ?: return
    var volume by remember(drum) { mutableFloatStateOf(drum.volume.toFloat().coerceIn(0f, 2f)) }
    var pan by remember(drum) { mutableFloatStateOf(drum.pan.toFloat().coerceIn(-1f, 1f)) }
    fun commit(updated: DrumElement) = vm.command(EditorCommand.SetInstrument(track.id,
        track.instrument.copy(drumKit = track.instrument.drumKit.map { if (it.midiNote == drum.midiNote) updated else it }), vm.retunePolicy()))
    val db = if (volume <= 0.0001f) "−∞" else "%+.1f".format(java.util.Locale.ROOT, 20 * kotlin.math.log10(volume.toDouble()))
    Text("${drum.name} · " + tr("Pegel", "Level") + " $db dB", style = MaterialTheme.typography.labelLarge)
    Slider(volume, { volume = it }, valueRange = 0f..2f, onValueChangeFinished = { commit(drum.copy(volume = volume.toDouble())) }, modifier = Modifier.semanticsLabel("${drum.name} " + tr("Pegel", "level")))
    Text("Pan " + when { pan < -0.02f -> "L ${(-pan * 100).toInt()}"; pan > 0.02f -> "R ${(pan * 100).toInt()}"; else -> "C" }, style = MaterialTheme.typography.labelLarge)
    Slider(pan, { pan = it }, valueRange = -1f..1f, onValueChangeFinished = { commit(drum.copy(pan = pan.toDouble())) }, modifier = Modifier.semanticsLabel("${drum.name} Pan"))
}

@Composable fun MixerScreen(vm: WorkspaceViewModel, state: EditorState) {
    ScrollPage(stringResource(R.string.mixer), stringResource(R.string.mixer_hint)) {
        state.project.tracks.forEach { track -> MixerStrip(vm, track) }
        var master by remember(state.project.mix.master.volume) { mutableFloatStateOf(state.project.mix.master.volume.toFloat()) }
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.master_bus), style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.volume)} ${(master * 100).toInt()}%")
            Slider(master, { master = it }, onValueChangeFinished = { vm.command(EditorCommand.SetMasterMix(state.project.mix.master.copy(volume = master.toDouble()))) },
                modifier = Modifier.semanticsLabel("${stringResource(R.string.master_bus)} ${stringResource(R.string.volume)}"))
            Text(tr("Am Ausgang begrenzt ein Limiter Spitzen; Stems werden ohne ihn exportiert.", "A limiter protects the output; stems are exported without it."), style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable private fun MixerStrip(vm: WorkspaceViewModel, track: Track) {
    var volume by remember(track.id, track.mix.volume) { mutableFloatStateOf(track.mix.volume.toFloat()) }
    var pan by remember(track.id, track.mix.pan) { mutableFloatStateOf(track.mix.pan.toFloat()) }
    var presetMenu by remember { mutableStateOf(false) }
    val preset = TonePresets.forTrack(track)
    val family = when (track.instrument.kind) { InstrumentKind.PERCUSSION -> TonePreset.Family.DRUMS; InstrumentKind.BASS -> TonePreset.Family.BASS; InstrumentKind.GUITAR -> TonePreset.Family.GUITAR; else -> TonePreset.Family.KEYS }
    val en = english()
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(track.name, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(track.mix.mute, { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(mute = !track.mix.mute))) }, label = { Text(stringResource(R.string.mute)) })
            FilterChip(track.mix.solo, { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(solo = !track.mix.solo))) }, label = { Text(stringResource(R.string.solo)) })
            Box {
                AssistChip(onClick = { presetMenu = true }, label = { Text(if (en) preset.labelEn else preset.labelDe) })
                DropdownMenu(presetMenu, { presetMenu = false }) {
                    TonePresets.all.filter { it.family == family || family == TonePreset.Family.KEYS }.forEach { option ->
                        DropdownMenuItem(text = { Text(if (en) option.labelEn else option.labelDe) }, onClick = {
                            presetMenu = false
                            vm.command(EditorCommand.SetSound(track.id, track.sound.copy(presetId = option.id, mappingDescription = "ArtiFret-Klang: ${option.labelDe}")))
                            vm.command(EditorCommand.SetMix(track.id, track.mix.copy(effects = emptyList())))
                        })
                    }
                }
            }
        }
        Text("${stringResource(R.string.volume)} ${(volume * 100).toInt()}%")
        Slider(volume, { volume = it }, onValueChangeFinished = { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(volume = volume.toDouble()))) }, modifier = Modifier.semanticsLabel(stringResource(R.string.volume)))
        Text("${stringResource(R.string.pan)} ${(pan * 100).toInt()}")
        Slider(pan, { pan = it }, valueRange = -1f..1f, onValueChangeFinished = { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(pan = pan.toDouble()))) }, modifier = Modifier.semanticsLabel(stringResource(R.string.pan)))
        EffectChain(vm, track)
    } }
}

/** Pedalboard: the effective chain (preset default until edited); toggles and the main parameter per unit. */
@Composable private fun EffectChain(vm: WorkspaceViewModel, track: Track) {
    val chain = TonePresets.effectiveChain(track)
    Text(tr("Effektkette", "Effect chain"), style = MaterialTheme.typography.titleSmall)
    chain.forEachIndexed { i, unit ->
        val main = when (unit.kind) { EffectKind.AMP -> "gain"; EffectKind.OVERDRIVE -> "drive"; EffectKind.GATE -> "thresholdDb"; EffectKind.REVERB, EffectKind.DELAY, EffectKind.CHORUS -> "mix"; EffectKind.COMPRESSOR -> "thresholdDb"; EffectKind.EQ -> "mid"; EffectKind.FILTER -> "cutoffHz"; EffectKind.CABINET_IR -> "model" }
        val range = when (main) { "thresholdDb" -> if (unit.kind == EffectKind.COMPRESSOR) -60f..0f else -80f..0f; "mid" -> -12f..12f; "cutoffHz" -> 100f..12000f; "model" -> 0f..4f; else -> 0f..1f }
        // Show what the DSP actually uses when the unit does not store the value yet.
        var value by remember(unit) { mutableFloatStateOf((unit.parameters[main] ?: dev.riffworkbench.synth.EffectDefaults.value(unit.kind, main) ?: range.start.toDouble()).toFloat().coerceIn(range)) }
        fun write(updated: EffectUnit) = vm.command(EditorCommand.SetMix(track.id, track.mix.copy(effects = chain.mapIndexed { k, u -> if (k == i) updated else u })))
        val name = unit.kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(unit.enabled, { write(unit.copy(enabled = it)) }, modifier = Modifier.semanticsLabel(name))
            Text(name, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
            Slider(value, { value = it }, valueRange = range, modifier = Modifier.weight(1f).semanticsLabel("$name $main"), onValueChangeFinished = { write(unit.copy(parameters = unit.parameters + (main to value.toDouble()))) })
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(EffectKind.OVERDRIVE, EffectKind.EQ, EffectKind.COMPRESSOR, EffectKind.CHORUS, EffectKind.DELAY, EffectKind.REVERB).forEach { kind ->
            AssistChip(onClick = { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(effects = chain + EffectUnit(EntityId("fx-${kind.name.lowercase()}-${chain.size}"), kind)))) }, label = { Text("+ ${kind.name.lowercase()}") })
        }
        if (track.mix.effects.isNotEmpty()) AssistChip(onClick = { vm.command(EditorCommand.SetMix(track.id, track.mix.copy(effects = emptyList()))) }, label = { Text(tr("Preset-Kette", "Preset chain")) })
    }
}

private fun noteName(midi: Int) = "${pitchName(midi)}${midi / 12 - 1}"

/** Top-aligned string count change: the high strings keep their pitches, the lowest string is added (a fourth below, or from a matching preset) or removed. */
internal fun resizeTuning(instrument: InstrumentDefinition, strings: Int): InstrumentDefinition {
    val tuning = instrument.tuning
    if (strings == tuning.size || tuning.isEmpty()) return instrument
    val capo = instrument.capoByString.firstOrNull() ?: 0
    val next = if (strings < tuning.size) tuning.drop(tuning.size - strings) else {
        val preset = TuningPresets.all.firstOrNull { it.kind == instrument.kind && it.tuning.size == strings && it.tuning.takeLast(tuning.size) == tuning }
        preset?.tuning ?: (List(strings - tuning.size) { 0 } + tuning).let { padded -> padded.toMutableList().also { t -> for (i in (strings - tuning.size - 1) downTo 0) t[i] = (t[i + 1] - 5).coerceAtLeast(0) } }
    }
    return instrument.copy(tuning = next, capoByString = List(strings) { capo })
}

@Composable fun InstrumentsScreen(vm: WorkspaceViewModel, state: EditorState) {
    val track = state.project.tracks.first { it.id == state.selection.trackId }
    val instrument = track.instrument
    var policy by remember { mutableStateOf(vm.retunePolicy()) }
    // The draft is edited locally; one "Anwenden" dispatches a single SetInstrument (one undo step).
    var draft by remember(track.id, instrument) { mutableStateOf(instrument) }
    var allowDrop by remember(track.id, instrument) { mutableStateOf(false) }
    val dirty = draft != instrument
    val problems = remember(draft, policy, state.project, track.id) { if (dirty) vm.retuneProblems(draft, policy) else emptyList() }
    val drops = problems.filter { it.resolution == RetuneResolution.DROP }
    val chords by vm.chords.collectAsStateWithLifecycle()
    var chordName by remember { mutableStateOf("E5") }
    ScrollPage(stringResource(R.string.instruments), stringResource(R.string.instrument_hint)) {
        TrackAndVoice(vm, state)
        Text(track.name, style = MaterialTheme.typography.titleLarge)
        if (instrument.tuning.isNotEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${stringResource(R.string.tuning)} · ${TuningPresets.matching(draft.tuning)?.name ?: tr("eigene", "custom")}", style = MaterialTheme.typography.titleMedium)
                Text(draft.tuning.joinToString(" – ") { noteName(it) }, fontFamily = FontFamily.Monospace)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(policy == RetuningPolicy.KEEP_SOUNDING_PITCH, { policy = RetuningPolicy.KEEP_SOUNDING_PITCH; vm.setRetunePolicy(policy) }, label = { Text(stringResource(R.string.retain_pitch)) })
                    FilterChip(policy == RetuningPolicy.KEEP_FINGER_POSITION, { policy = RetuningPolicy.KEEP_FINGER_POSITION; vm.setRetunePolicy(policy) }, label = { Text(stringResource(R.string.retain_fret)) })
                }
                Text(if (policy == RetuningPolicy.KEEP_SOUNDING_PITCH) tr("Gleiche Tonhöhe: Bünde ändern sich, Noten wandern auf passende Saiten; was nirgends passt, wird oktaviert.", "Same pitch: frets change and notes move to fitting strings; what fits nowhere is shifted an octave.")
                    else tr("Gleicher Griff: Saite und Bund bleiben, die Tonhöhen ändern sich; Noten verschwundener Saiten werden nach Tonhöhe gesetzt.", "Same fingering: string and fret stay, pitches change; notes of vanished strings are placed by pitch."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TuningPresets.all.filter { it.kind == instrument.kind || (instrument.kind == InstrumentKind.OTHER) }.forEach { preset ->
                        FilterChip(preset.tuning == draft.tuning, { draft = draft.copy(tuning = preset.tuning, capoByString = List(preset.tuning.size) { draft.capoByString.firstOrNull() ?: 0 }) }, label = { Text(preset.name) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Saiten", "Strings"), style = MaterialTheme.typography.titleSmall)
                    listOf(6, 7, 8).forEach { n -> FilterChip(draft.tuning.size == n, { draft = resizeTuning(draft, n) }, label = { Text("$n") }) }
                }
                Text(tr("Oben bündig: Mehr oder weniger Saiten ändern nur die tiefste Saite; die hohen Saiten behalten ihre Noten.", "Top-aligned: more or fewer strings change only the lowest string; the high strings keep their notes."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("Einzelne Saiten (tief → hoch)", "Individual strings (low → high)"), style = MaterialTheme.typography.titleSmall)
                val reference = TuningPresets.all.filter { it.kind == draft.kind && it.tuning.size == draft.tuning.size }
                    .minByOrNull { preset -> preset.tuning.indices.sumOf { kotlin.math.abs(preset.tuning[it] - draft.tuning[it]) } }
                draft.tuning.forEachIndexed { s, pitch ->
                    val delta = reference?.let { pitch - it.tuning[s] } ?: 0
                    Stepper("${s + 1}", noteName(pitch) + (if (delta != 0) " ${if (delta > 0) "+" else "−"}${kotlin.math.abs(delta)}" else ""),
                        { if (pitch > 12) draft = draft.copy(tuning = draft.tuning.toMutableList().also { it[s] = pitch - 1 }) },
                        { if (pitch < 100) draft = draft.copy(tuning = draft.tuning.toMutableList().also { it[s] = pitch + 1 }) })
                }
                reference?.let { Text(tr("Abweichung gegenüber ${it.name}", "Offset from ${it.name}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Stepper(tr("Capo", "Capo"), "${draft.capoByString.firstOrNull() ?: 0}",
                    { val c = (draft.capoByString.firstOrNull() ?: 0) - 1; if (c >= 0) draft = draft.copy(capoByString = List(draft.tuning.size) { c }) },
                    { val c = (draft.capoByString.firstOrNull() ?: 0) + 1; if (c <= 12) draft = draft.copy(capoByString = List(draft.tuning.size) { c }) })
                if (draft.tuning.any { it < 37 }) Text(tr("Hinweis: unter D♭2 (MIDI 37) wird aus dem tiefsten Sample gestimmt.", "Note: below D♭2 (MIDI 37) the lowest sample is pitched down."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                if (dirty) {
                    if (problems.isEmpty()) Text(tr("Alle Noten bleiben spielbar.", "All notes stay playable."), style = MaterialTheme.typography.bodyMedium)
                    else {
                        Text(tr("Konflikte", "Conflicts") + " (${problems.size})", style = MaterialTheme.typography.titleSmall)
                        problems.take(12).forEach { pr ->
                            val resolution = when (pr.resolution) {
                                RetuneResolution.OCTAVE_UP -> tr("oktavieren (+12)", "octave up (+12)")
                                RetuneResolution.OCTAVE_DOWN -> tr("oktavieren (−12)", "octave down (−12)")
                                RetuneResolution.DROP -> tr("entfernen", "remove")
                            }
                            Text("${tr("Takt", "Bar")} ${pr.barIndex + 1} · ${stringResource(R.string.string_label)} ${pr.string + 1} · ${noteName(pr.midi)} → $resolution", style = MaterialTheme.typography.bodySmall,
                                color = if (pr.resolution == RetuneResolution.DROP) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                        if (problems.size > 12) Text("… +${problems.size - 12}", style = MaterialTheme.typography.bodySmall)
                        if (drops.isNotEmpty()) ToggleRow(tr("${drops.size} nicht spielbare Noten entfernen", "Remove ${drops.size} unplayable notes"), allowDrop, { allowDrop = it })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.retune(draft, policy, allowDrop) }, enabled = dirty && (drops.isEmpty() || allowDrop)) { Text(tr("Anwenden", "Apply")) }
                    OutlinedButton(onClick = { draft = instrument; allowDrop = false }, enabled = dirty) { Text(tr("Zurücksetzen", "Reset")) }
                }
            } }
            HorizontalDivider()
            Text(stringResource(R.string.fretboard), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.horizontalScroll(rememberScrollState())) { instrument.tuning.indices.reversed().forEach { string -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pitchName(instrument.tuning[string]), Modifier.width(28.dp))
                (0..15).forEach { fret -> OutlinedButton(onClick = { vm.putNoteAt(string, fret) }, modifier = Modifier.size(48.dp).semanticsLabel("${stringResource(R.string.string_label)} ${string + 1}, $fret"), contentPadding = PaddingValues(0.dp)) { Text("$fret") } }
            } } }
            HorizontalDivider()
            Text(tr("Akkordbibliothek", "Chord library"), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(chordName, { chordName = it.take(12) }, label = { Text(tr("Akkord (z. B. Am, F#m7, E5)", "Chord (e.g. Am, F#m7, E5)")) }, modifier = Modifier.textEditing().weight(1f), singleLine = true)
                Button(onClick = { vm.searchChords(chordName) }) { Text(tr("Suchen", "Search")) }
            }
            chords.forEach { chord ->
                OutlinedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(chord.frets.joinToString(" ") { it?.toString() ?: "x" }, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.insertChord(chord) }) { Text(tr("Eintragen", "Insert")) }
                } }
            }
        } else {
            NoticeCard(tr("Dieses Instrument hat keine Saiten; Noten werden mit Tonhöhe eingegeben.", "This instrument has no strings; notes are entered by pitch."))
        }
    }
}

@Composable fun HistoryScreen(vm: WorkspaceViewModel, state: WorkspaceState) {
    var name by remember { mutableStateOf("") }
    ScrollPage(stringResource(R.string.history), stringResource(R.string.history_hint)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(vm::undo, enabled = state.editor?.canUndo == true) { Text(stringResource(R.string.undo)) }
            OutlinedButton(vm::redo, enabled = state.editor?.canRedo == true) { Text(stringResource(R.string.redo)) }
        }
        OutlinedTextField(name, { name = it.take(200) }, label = { Text(stringResource(R.string.snapshot_name)) }, modifier = Modifier.textEditing().fillMaxWidth())
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
