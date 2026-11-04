package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.model.ExportResult
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R

enum class WorkspacePage(val route: String, val title: Int, val icon: String) {
    EDITOR("editor", R.string.editor, "T"), ARRANGEMENT("arrangement", R.string.arrangement, "≡"), DRUMS("drums", R.string.drums, "▦"),
    PRACTICE("practice", R.string.practice, "▶"), AUDIO("audio", R.string.audio, "≈"), MIXER("mixer", R.string.mixer, "☷"),
    INSTRUMENTS("instruments", R.string.instruments, "♮"), EFFECTS("effects", R.string.effects, "ƒ"), LAYOUT("layout", R.string.layout, "▤"),
    EXCHANGE("exchange", R.string.exchange, "↗"), HISTORY("history", R.string.history, "↶"), INTEGRATIONS("integrations", R.string.integrations, "⌁"), STATUS("status", R.string.capabilities, "◎")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkspaceScreen(vm: WorkspaceViewModel, onBack: () -> Unit, onExport: (ExportResult) -> Unit, onSettings: () -> Unit, initialPage: WorkspacePage = WorkspacePage.EDITOR) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf(initialPage.route) }
    val page = WorkspacePage.entries.first { it.route == route }
    val keyboardFocus = remember { FocusRequester() }
    LaunchedEffect(ui.editor?.project?.id) { if (ui.editor != null) keyboardFocus.requestFocus() }
    Scaffold(bottomBar = {
        Surface(tonalElevation = 4.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 48.dp)) { Text("▶ ${stringResource(R.string.play)}") }
            Text(stringResource(R.string.transport_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).focusRequester(keyboardFocus).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else if (event.isCtrlPressed && event.key == Key.Z) { if (event.isShiftPressed) vm.redo() else vm.undo(); true }
            else if (event.isCtrlPressed && event.key == Key.Y) { vm.redo(); true }
            else if (event.isCtrlPressed && event.key == Key.S) { vm.retrySave(); true }
            else if (page == WorkspacePage.EDITOR && !event.isCtrlPressed && !event.isAltPressed) {
                val code = event.nativeKeyEvent.keyCode
                when {
                    code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> { vm.digit(code - android.view.KeyEvent.KEYCODE_0); true }
                    event.key == Key.Enter -> { vm.commitInput(); true }
                    event.key == Key.Backspace -> { vm.backspace(); true }
                    event.key == Key.DirectionRight -> { vm.move(1); true }
                    event.key == Key.DirectionLeft -> { vm.move(-1); true }
                    event.key == Key.DirectionUp || event.key == Key.DirectionDown -> {
                        ui.editor?.let { editor ->
                            val track = editor.project.tracks.first { it.id == editor.selection.trackId }
                            vm.select(editor.selection.copy(string = (editor.selection.string + if (event.key == Key.DirectionUp) 1 else -1).coerceIn(0, maxOf(0, track.instrument.tuning.lastIndex))))
                        }; true
                    }
                    else -> false
                }
            } else false
        }.focusable()) {
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
                            when (page) {
                                WorkspacePage.EDITOR -> EditorScreen(vm, ui.editor!!)
                                WorkspacePage.ARRANGEMENT -> ArrangementScreen(vm, ui.editor!!)
                                WorkspacePage.DRUMS -> DrumScreen(vm, ui.editor!!)
                                WorkspacePage.PRACTICE -> PracticeScreen()
                                WorkspacePage.AUDIO -> AudioScreen(ui.editor!!.project)
                                WorkspacePage.MIXER -> MixerScreen(vm, ui.editor!!)
                                WorkspacePage.INSTRUMENTS -> InstrumentsScreen(vm, ui.editor!!)
                                WorkspacePage.EFFECTS -> EffectsScreen(vm)
                                WorkspacePage.LAYOUT -> LayoutScreen()
                                WorkspacePage.EXCHANGE -> ExchangeScreen { vm.exportNative(onExport) }
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
