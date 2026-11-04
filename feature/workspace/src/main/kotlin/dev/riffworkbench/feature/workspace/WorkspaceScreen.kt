package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    EXCHANGE("exchange", R.string.exchange, "↗"), HISTORY("history", R.string.history, "↶"), INTEGRATIONS("integrations", R.string.integrations, "⌁"), STATUS("status", R.string.capabilities, "◎")
}

/** Export callbacks provided by the composition root (they own the Storage Access Framework launchers). */
class ExportActions(val bytes: (ExportResult) -> Unit, val stream: (ExportTarget) -> Unit, val pdf: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkspaceScreen(vm: WorkspaceViewModel, onBack: () -> Unit, onExport: (ExportResult) -> Unit, onSettings: () -> Unit, initialPage: WorkspacePage = WorkspacePage.EDITOR,
                                exportActions: ExportActions = ExportActions(onExport, {}, {})) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf(initialPage.route) }
    val page = WorkspacePage.entries.first { it.route == route }
    val keyboardFocus = remember { FocusRequester() }
    LaunchedEffect(ui.editor?.project?.id) { if (ui.editor != null) runCatching { keyboardFocus.requestFocus() } }
    Scaffold(bottomBar = { TransportBar(vm) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).focusRequester(keyboardFocus).onPreviewKeyEvent { event -> handleKey(vm, page, event) }.focusable()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ ${stringResource(R.string.back)}") }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(ui.editor?.project?.metadata?.title ?: stringResource(R.string.loading), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(when (ui.saveStatus) { SaveStatus.SAVED -> R.string.saved; SaveStatus.SAVING -> R.string.saving; SaveStatus.FAILED -> R.string.save_failed }), style = MaterialTheme.typography.labelSmall, color = if (ui.saveStatus == SaveStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
                TextButton(onClick = vm::undo, enabled = ui.editor?.canUndo == true) { Text("↶", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semanticsLabel(stringResource(R.string.undo))) }
                TextButton(onClick = vm::redo, enabled = ui.editor?.canRedo == true) { Text("↷", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semanticsLabel(stringResource(R.string.redo))) }
            }
            ui.notice?.let { notice -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { vm.showNotice(null) }) { Text("×", modifier = Modifier.semanticsLabel(stringResource(R.string.dismiss))) }
                if (ui.saveStatus == SaveStatus.FAILED) TextButton(onClick = vm::retrySave) { Text(stringResource(R.string.retry)) }
            } }
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                ui.loadError != null -> Column(Modifier.padding(24.dp)) { NoticeCard(ui.loadError!!, true); Button(onClick = vm::reload) { Text(stringResource(R.string.retry)) } }
                ui.editor != null -> BoxWithConstraints(Modifier.weight(1f)) {
                    val wide = maxWidth >= 840.dp
                    Row(Modifier.fillMaxSize()) {
                        if (wide) NavigationRail { Column(Modifier.verticalScroll(rememberScrollState())) {
                            WorkspacePage.entries.forEach { tab -> NavigationRailItem(selected = tab == page, onClick = { route = tab.route }, icon = { Text(tab.icon) }, label = { Text(stringResource(tab.title)) }) }
                        } }
                        Column(Modifier.weight(1f)) {
                            if (!wide) PrimaryScrollableTabRow(selectedTabIndex = page.ordinal, edgePadding = 8.dp) {
                                WorkspacePage.entries.forEach { tab -> Tab(selected = page == tab, onClick = { route = tab.route }, text = { Text(stringResource(tab.title)) }) }
                            }
                            val editor = ui.editor!!
                            when (page) {
                                WorkspacePage.EDITOR -> EditorScreen(vm, editor)
                                WorkspacePage.ARRANGEMENT -> ArrangementScreen(vm, editor)
                                WorkspacePage.DRUMS -> DrumScreen(vm, editor)
                                WorkspacePage.PRACTICE -> PracticeScreen(vm)
                                WorkspacePage.AUDIO -> AudioScreen(editor.project)
                                WorkspacePage.MIXER -> MixerScreen(vm, editor)
                                WorkspacePage.INSTRUMENTS -> InstrumentsScreen(vm, editor)
                                WorkspacePage.EFFECTS -> EffectsScreen(vm, editor)
                                WorkspacePage.LAYOUT -> LayoutScreen(vm)
                                WorkspacePage.EXCHANGE -> ExchangeScreen(vm, exportActions)
                                WorkspacePage.HISTORY -> HistoryScreen(vm, ui)
                                WorkspacePage.INTEGRATIONS -> IntegrationsScreen(onSettings)
                                WorkspacePage.STATUS -> StatusScreen()
                            }
                        }
                    }
                }
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
