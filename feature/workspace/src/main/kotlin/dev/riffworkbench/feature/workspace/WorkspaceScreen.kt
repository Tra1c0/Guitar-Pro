package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    val transport by vm.transport.state.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf(initialPage.route) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    val page = WorkspacePage.entries.first { it.route == route }
    val keyboardFocus = remember { FocusRequester() }
    val playing = transport.status == TransportStatus.PLAYING
    LaunchedEffect(ui.editor?.project?.id) { if (ui.editor != null) runCatching { keyboardFocus.requestFocus() } }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (wide) SideRail(page, playing, onPick = { route = it.route }, onPlay = vm::togglePlay)
            Column(Modifier.weight(1f).fillMaxHeight().focusRequester(keyboardFocus).onPreviewKeyEvent { event -> handleKey(vm, page, event) }.focusable()) {
                TopBar(vm, ui, onBack)
                ui.notice?.let { notice -> NoticeRow(vm, ui, notice) }
                ui.busy?.let { label -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val p = ui.busyProgress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                } }
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
                }
                if (!wide) PillNavBar(
                    items = listOf(
                        PillItem(TabinoIcons.Library, stringResource(R.string.library), false, onBack),
                        PillItem(TabinoIcons.Pen, stringResource(R.string.editor), page == WorkspacePage.EDITOR, { route = WorkspacePage.EDITOR.route }, "nav-editor"),
                        PillItem(TabinoIcons.Metronome, stringResource(R.string.practice), page == WorkspacePage.PRACTICE, { route = WorkspacePage.PRACTICE.route }, "nav-practice"),
                        PillItem(TabinoIcons.More, tr("Mehr", "More"), page !in primaryPages, { moreOpen = true }, "nav-more"),
                    ),
                    centerIcon = if (playing) TabinoIcons.Pause else TabinoIcons.Play,
                    centerLabel = if (playing) tr("Pause", "Pause") else stringResource(R.string.play),
                    onCenter = vm::togglePlay, centerTag = "transport-play",
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                )
            }
        }
    }
    if (moreOpen) ModalBottomSheet(onDismissRequest = { moreOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
        MorePages(page, onPick = { route = it.route; moreOpen = false }, onSettings = { moreOpen = false; onSettings() })
    }
}

@Composable private fun TopBar(vm: WorkspaceViewModel, ui: WorkspaceState, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundIconButton(TabinoIcons.Back, "${stringResource(R.string.back)}: ${stringResource(R.string.library)}", onBack)
        val (status, color) = when (ui.saveStatus) {
            SaveStatus.SAVED -> stringResource(R.string.saved) to Tabino.Lime
            SaveStatus.SAVING -> stringResource(R.string.saving) to Tabino.Sun
            SaveStatus.FAILED -> stringResource(R.string.save_failed) to MaterialTheme.colorScheme.error
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorDot(color, 8.dp)
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundIconButton(TabinoIcons.Undo, stringResource(R.string.undo), vm::undo, enabled = ui.editor?.canUndo == true)
        RoundIconButton(TabinoIcons.Redo, stringResource(R.string.redo), vm::redo, enabled = ui.editor?.canRedo == true)
    }
}

@Composable private fun NoticeRow(vm: WorkspaceViewModel, ui: WorkspaceState, notice: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(notice, Modifier.weight(1f).padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (ui.saveStatus == SaveStatus.FAILED) TextButton(onClick = vm::retrySave) { Text(stringResource(R.string.retry)) }
            IconButton(onClick = { vm.showNotice(null) }) { Icon(TabinoIcons.Close, stringResource(R.string.dismiss), Modifier.size(18.dp)) }
        }
    }
}

/** Grid of every other area, colour-coded like the design's library cards. */
@Composable private fun MorePages(current: WorkspacePage, onPick: (WorkspacePage) -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigTitle(tr("Alle Bereiche", "All areas"), tr("Projekt, Klang, Austausch", "Project, sound, exchange"), size = 22)
        LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 560.dp)) {
            items(WorkspacePage.entries.filter { it !in primaryPages }) { p ->
                val selected = p == current
                Surface(onClick = { onPick(p) }, shape = RoundedCornerShape(24.dp), color = if (selected) p.accent else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) Tabino.Ink else MaterialTheme.colorScheme.onSurface, modifier = Modifier.height(76.dp).testTag("page-${p.route}")) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(36.dp).background(if (selected) Tabino.Ink else p.accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text(p.icon, color = if (selected) p.accent else Tabino.Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                        Text(stringResource(p.title), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
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
