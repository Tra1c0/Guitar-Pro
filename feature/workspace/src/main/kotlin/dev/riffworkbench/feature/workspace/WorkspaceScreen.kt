package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.editor.Articulations
import dev.riffworkbench.editor.EditScope
import dev.riffworkbench.editor.EditorCommand
import dev.riffworkbench.model.*
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

enum class WorkspacePage(val route: String, val title: Int, val icon: String) {
    EDITOR("editor", R.string.editor, "T"), ARRANGEMENT("arrangement", R.string.arrangement, "≡"), DRUMS("drums", R.string.drums, "▦"),
    PRACTICE("practice", R.string.practice, "▶"), AUDIO("audio", R.string.audio, "≈"), MIXER("mixer", R.string.mixer, "☷"),
    INSTRUMENTS("instruments", R.string.instruments, "♮"), EFFECTS("effects", R.string.effects, "ƒ"), LAYOUT("layout", R.string.layout, "▤"),
    EXCHANGE("exchange", R.string.exchange, "↗"), HISTORY("history", R.string.history, "↶"), INTEGRATIONS("integrations", R.string.integrations, "⌁"), STATUS("status", R.string.capabilities, "◎"),
    DIAGNOSTICS("diagnostics", R.string.audio_diagnostics, "⏱")
}

/** Accent per page for the tiles in the "more" sheet (the design's colour-coded cards). */
private val WorkspacePage.accent: Color get() = Tabino.Accents[(ordinal + 1) % Tabino.Accents.size]
private val primaryPages = setOf(WorkspacePage.EDITOR, WorkspacePage.PRACTICE)

/** Document callbacks provided by the composition root (it owns the Storage Access Framework launchers). */
class ExportActions(val bytes: (ExportResult) -> Unit, val stream: (ExportTarget) -> Unit, val pdf: () -> Unit, val importAudio: () -> Unit = {})

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkspaceScreen(vm: WorkspaceViewModel, onBack: () -> Unit, onExport: (ExportResult) -> Unit, onSettings: () -> Unit, initialPage: WorkspacePage = WorkspacePage.EDITOR,
                                exportActions: ExportActions = ExportActions(onExport, {}, {})) {
    val ui by vm.state.collectAsStateWithLifecycle()
    // Only the status (play/pause icon): the position changes on every transport poll (~30 Hz) and recomposed the whole
    // workspace, open sheets and menus included, while playing (QA 23). Parts that show the position read it themselves.
    val transport = vm.transport.state.collectAsStateWithLifecycle()
    val status by remember(transport) { derivedStateOf { transport.value.status } }
    var route by rememberSaveable { mutableStateOf(initialPage.route) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    val page = WorkspacePage.entries.first { it.route == route }
    val keyboardFocus = remember { FocusRequester() }
    val playing = status == TransportStatus.PLAYING
    val paused = status == TransportStatus.PAUSED
    ProbeComposition("WorkspaceScreen")
    LaunchedEffect(ui.editor?.project?.id) { if (ui.editor != null) runCatching { keyboardFocus.requestFocus() } }
    val textEditing = remember { mutableStateOf(false) }
    val noticeSheets = remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalTextEditing provides textEditing, LocalNoticeSheets provides noticeSheets) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (wide) SideRail(page, playing, onPick = { route = it.route }, onPlay = vm::togglePlay)
            // Shortcuts act on the score, not on text being typed (Space, Ctrl+Z/C/V in a name field belong to the field).
            Column(Modifier.weight(1f).fillMaxHeight().focusRequester(keyboardFocus).onPreviewKeyEvent { event -> !textEditing.value && handleKey(vm, page, event) }.focusable()) {
                TopBar(vm, ui, onBack)
                // Notice and progress lie over the page instead of taking rows above it (QA 23): a row appearing or going
                // moved every control below it and resized the score canvas, which re-ran its scroll rules.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        ui.loadError != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { NoticeCard(ui.loadError!!, true); Button(onClick = vm::reload) { Text(stringResource(R.string.retry)) } }
                        ui.editor != null -> {
                            val editor = ui.editor!!
                            when (page) {
                                WorkspacePage.EDITOR -> EditorScreen(vm, editor)
                                WorkspacePage.ARRANGEMENT -> ArrangementScreen(vm, editor)
                                WorkspacePage.DRUMS -> DrumScreen(vm, editor)
                                WorkspacePage.PRACTICE -> PracticeScreen(vm)
                                WorkspacePage.AUDIO -> AudioScreen(vm, editor, exportActions.importAudio)
                                WorkspacePage.MIXER -> MixerScreen(vm, editor)
                                WorkspacePage.INSTRUMENTS -> InstrumentsScreen(vm, editor)
                                WorkspacePage.EFFECTS -> EffectsScreen(vm, editor)
                                WorkspacePage.LAYOUT -> LayoutScreen(vm)
                                WorkspacePage.EXCHANGE -> ExchangeScreen(vm, exportActions)
                                WorkspacePage.HISTORY -> HistoryScreen(vm, ui)
                                WorkspacePage.INTEGRATIONS -> IntegrationsScreen(onSettings)
                                WorkspacePage.STATUS -> StatusScreen()
                                WorkspacePage.DIAGNOSTICS -> DiagnosticsScreen(vm)
                            }
                        }
                    }
                    ui.busy?.let { label -> BusyOverlay(label, ui.busyProgress) }
                    PageNoticeOverlay(vm, ui)
                }
                if (!wide) PillNavBar(
                    items = listOf(
                        PillItem(TabinoIcons.Library, stringResource(R.string.library), false, onBack),
                        PillItem(TabinoIcons.Pen, stringResource(R.string.editor), page == WorkspacePage.EDITOR, { route = WorkspacePage.EDITOR.route }, "nav-editor"),
                        PillItem(TabinoIcons.Metronome, stringResource(R.string.practice), page == WorkspacePage.PRACTICE, { route = WorkspacePage.PRACTICE.route }, "nav-practice"),
                        PillItem(TabinoIcons.More, tr("Mehr", "More"), page !in primaryPages, { moreOpen = true }, "nav-more"),
                    ),
                    centerIcon = if (playing) TabinoIcons.Pause else TabinoIcons.Play,
                    centerLabel = when { playing -> tr("Pause", "Pause"); paused -> tr("Fortsetzen", "Resume"); else -> stringResource(R.string.play) },
                    onCenter = vm::togglePlay, centerTag = "transport-play",
                    // Explicit "back to the start": cursor to bar 1 and the transport stopped (Bericht 1 §3).
                    beforeCenter = PillItem(TabinoIcons.SkipStart, stringResource(R.string.jump_to_start), false, vm::jumpToStart, "transport-start"),
                    centerPaused = paused,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                )
            }
        }
    }
    }
    CompositionLocalProvider(LocalNoticeSheets provides noticeSheets) {
        if (moreOpen) ModalBottomSheet(onDismissRequest = { moreOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxWidth()) {
                MorePages(page, onPick = { route = it.route; moreOpen = false }, onSettings = { moreOpen = false; onSettings() })
                SheetNoticeOverlay(vm)
            }
        }
    }
}

/** Progress of a long operation (export/render) over the top of the page content (QA 23: it was a row that moved the page). */
@Composable private fun BoxScope.BusyOverlay(label: String, progress: Float?) {
    // No Surface: touches pass through to the page below.
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable private fun TopBar(vm: WorkspaceViewModel, ui: WorkspaceState, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundIconButton(TabinoIcons.Back, "${stringResource(R.string.back)}: ${stringResource(R.string.library)}", onBack)
        val (status, color) = when (ui.saveStatus) {
            SaveStatus.SAVED -> stringResource(R.string.saved) to Tabino.Lime
            SaveStatus.SAVING -> stringResource(R.string.saving) to Tabino.Sun
            SaveStatus.FAILED -> stringResource(R.string.save_failed) to MaterialTheme.colorScheme.error
        }
        // Autosave is silent otherwise: a failed save must be announced to screen-reader users.
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorDot(color, 8.dp)
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundIconButton(TabinoIcons.Undo, stringResource(R.string.undo), vm::undo, enabled = ui.editor?.canUndo == true)
        RoundIconButton(TabinoIcons.Redo, stringResource(R.string.redo), vm::redo, enabled = ui.editor?.canRedo == true)
    }
}

/** Grid of every other area, colour-coded like the design's library cards. */
@Composable private fun MorePages(current: WorkspacePage, onPick: (WorkspacePage) -> Unit, onSettings: () -> Unit) {
    ProbeComposition("MorePages")
    // The whole sheet content scrolls (title, tiles, settings), so every tile and the settings button stay reachable on a
    // short phone and at large font sizes without a nested scroll area. A plain two-column grid: ~10 tiles, all composed
    // (QA 21: a lazy grid with weight/heightIn left the settings button 0 dp high on a 640-dp phone, and its off-screen
    // tiles were not composed at all on a 568-dp phone).
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigTitle(tr("Alle Bereiche", "All areas"), tr("Projekt, Klang, Austausch", "Project, sound, exchange"), size = 22)
        WorkspacePage.entries.filter { it !in primaryPages }.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p ->
                    val selected = p == current
                    Surface(onClick = { onPick(p) }, shape = RoundedCornerShape(24.dp), color = if (selected) p.accent else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) Tabino.Ink else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).heightIn(min = 76.dp).testTag("page-${p.route}")) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(36.dp).background(if (selected) Tabino.Ink else p.accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Text(p.icon, color = if (selected) p.accent else Tabino.Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            }
                            Text(stringResource(p.title), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
            Icon(TabinoIcons.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.settings))
        }
    }
}

/** Tablet/landscape: every area in a rail, play on top. */
@Composable private fun SideRail(page: WorkspacePage, playing: Boolean, onPick: (WorkspacePage) -> Unit, onPlay: () -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.background, header = {
        FloatingActionButton(onClick = onPlay, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(28.dp),
            modifier = Modifier.testTag("transport-play")) {
            Icon(if (playing) TabinoIcons.Pause else TabinoIcons.Play, if (playing) tr("Pause", "Pause") else stringResource(R.string.play))
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            WorkspacePage.entries.forEach { tab ->
                NavigationRailItem(selected = tab == page, onClick = { onPick(tab) }, icon = { Text(tab.icon) }, label = { Text(stringResource(tab.title), maxLines = 1) },
                    colors = NavigationRailItemDefaults.colors(indicatorColor = tab.accent, selectedIconColor = Tabino.Ink))
            }
        }
    }
}

/** External keyboard: the same commands as the touch controls (CONTRACTS.md C-WORKFLOW). */
/** Set while a text field of the workspace has focus; editor shortcuts are suspended then. */
internal val LocalTextEditing = staticCompositionLocalOf { mutableStateOf(false) }
internal fun Modifier.textEditing(): Modifier = composed {
    val state = LocalTextEditing.current
    onFocusChanged { state.value = it.isFocused }
}

private fun handleKey(vm: WorkspaceViewModel, page: WorkspacePage, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val ctrl = event.isCtrlPressed
    when {
        ctrl && event.key == Key.Z -> { if (event.isShiftPressed) vm.redo() else vm.undo(); return true }
        ctrl && event.key == Key.Y -> { vm.redo(); return true }
        ctrl && event.key == Key.S -> { vm.retrySave(); return true }
        ctrl && event.key == Key.C -> { vm.copyBars(); return true }
        ctrl && event.key == Key.V -> { vm.pasteBars(); return true }
        ctrl && event.key == Key.D -> { vm.duplicateBars(); return true }
        ctrl && event.key == Key.DirectionUp -> { vm.command(EditorCommand.Transpose(1, EditScope.NOTE)); return true }
        ctrl && event.key == Key.DirectionDown -> { vm.command(EditorCommand.Transpose(-1, EditScope.NOTE)); return true }
        event.key == Key.Spacebar -> { vm.togglePlay(); return true }
        event.key == Key.F4 -> { vm.repeatLast(); return true }
    }
    if (page != WorkspacePage.EDITOR || ctrl || event.isAltPressed) return false
    val digit = when (event.key) { Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2; Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4
        Key.Five, Key.NumPad5 -> 5; Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8; Key.Nine, Key.NumPad9 -> 9; else -> -1 }
    if (digit >= 0) { vm.digit(digit); return true }
    when (event.key) {
        Key.Enter, Key.NumPadEnter -> vm.commitInput()
        Key.Backspace -> vm.backspace()
        Key.Delete -> vm.command(EditorCommand.Delete)
        Key.DirectionRight -> vm.move(1)
        Key.DirectionLeft -> vm.move(-1)
        Key.DirectionUp -> vm.moveString(1)
        Key.DirectionDown -> vm.moveString(-1)
        Key.PageDown -> vm.moveMeasure(1)
        Key.PageUp -> vm.moveMeasure(-1)
        Key.MoveHome -> vm.jumpToStart()
        Key.R -> vm.rest()
        Key.P -> vm.togglePalmMute()
        Key.X -> vm.toggleMark(NoteMark.DEAD)
        Key.G -> vm.toggleMark(NoteMark.GHOST)
        Key.H -> vm.toggleMark(NoteMark.HAMMER_ON)
        Key.I -> vm.toggleMark(NoteMark.LET_RING)
        Key.T -> vm.toggleMark(NoteMark.TAPPING)
        Key.L -> vm.command(EditorCommand.ToggleTie)
        Key.S -> vm.articulation(Articulation.Slide(SlideKind.SHIFT))
        Key.B -> vm.articulation(Articulations.fullBend())
        Key.V -> vm.articulation(Articulation.Vibrato())
        Key.N -> vm.articulation(Articulation.Harmonic(HarmonicKind.NATURAL))
        Key.D -> vm.setDuration(dots = if (vm.input.value.duration.dots == 0) 1 else 0)
        Key.NumPadAdd, Key.Plus -> vm.setDuration(denominator = maxOf(1, vm.input.value.duration.denominator / 2))
        Key.NumPadSubtract, Key.Minus -> vm.setDuration(denominator = minOf(64, vm.input.value.duration.denominator * 2))
        else -> return false
    }
    return true
}
