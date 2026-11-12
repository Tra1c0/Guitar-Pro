package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.ui.components.R
import dev.riffworkbench.ui.components.TabinoIcons
import dev.riffworkbench.ui.components.TransientNotice

/**
 * Open sheets that show the ViewModel notice themselves ([SheetNoticeOverlay]). While one is open the page overlay waits:
 * it lies behind the sheet's scrim anyway, and TalkBack should announce the notice once.
 */
internal val LocalNoticeSheets = staticCompositionLocalOf<MutableIntState?> { null }

/**
 * The ViewModel notice of a workspace page as an overlay at the bottom of the page content, just above the navigation bar
 * (QA 23). It lies over the content — nothing moves when it appears or goes — and closes itself after [NoticeTiming]. A
 * failed save keeps it with "Erneut versuchen" and a close button. `WorkspaceState.notice` stays the only source.
 */
@Composable internal fun BoxScope.PageNoticeOverlay(vm: WorkspaceViewModel, ui: WorkspaceState, onAction: (NoticeActionKind) -> Unit = {}) {
    val notice = ui.notice ?: return
    val sheets = LocalNoticeSheets.current
    if ((sheets?.intValue ?: 0) > 0) return
    val failed = ui.saveStatus == SaveStatus.FAILED
    // Zyklus 26: a button that belongs to exactly this notice text („<Gerät>: Latenz nicht kalibriert“ → „Kalibrieren“).
    val action = ui.noticeAction?.takeIf { it.text == notice && !failed }
    TransientNotice(notice, if (failed) null else NoticeTiming.millis(notice), { if (vm.state.value.notice == notice) vm.showNotice(null) },
        Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (action != null) TextButton(onClick = { vm.showNotice(null); onAction(action.kind) }, modifier = Modifier.testTag("notice-action")) { Text(action.label) }
        if (failed) {
            TextButton(onClick = vm::retrySave) { Text(stringResource(R.string.retry)) }
            IconButton(onClick = { vm.showNotice(null) }) { Icon(TabinoIcons.Close, stringResource(R.string.dismiss), Modifier.size(18.dp)) }
        }
    }
}

/**
 * The ViewModel notice inside a bottom sheet (the page overlay lies behind the sheet's scrim): over the top of the sheet,
 * where a partially expanded sheet is always visible, without moving the sheet's content (QA 23: the notice row pushed the
 * tiles down under the finger and made the sheet re-anchor). Closes itself like the page overlay; no buttons, so it never
 * covers a control with one of its own.
 */
@Composable internal fun BoxScope.SheetNoticeOverlay(vm: WorkspaceViewModel) {
    val sheets = LocalNoticeSheets.current
    DisposableEffect(sheets) {
        sheets?.let { it.intValue += 1 }
        onDispose { sheets?.let { it.intValue -= 1 } }
    }
    val ws by vm.state.collectAsStateWithLifecycle()
    val notice = ws.notice ?: return
    TransientNotice(notice, NoticeTiming.millis(notice), { if (vm.state.value.notice == notice) vm.showNotice(null) },
        Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp))
}

/**
 * Test hook (QA 23): counts compositions of named parts of the workspace UI while [enabled], so a Robolectric test can show
 * that a transport poll does not recompose menus, sheets and the editor chrome. Production never enables it.
 */
internal object RenderCounts {
    @Volatile var enabled = false
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun of(name: String): Int = counts[name] ?: 0
    fun snapshot(): Map<String, Int> = HashMap(counts)
    fun reset() = counts.clear()
    fun hit(name: String) { counts.merge(name, 1, Int::plus) }
}

/** Counts one composition of the enclosing scope (non-restartable: it runs whenever that scope runs, and is never skipped). */
@Composable @NonRestartableComposable internal fun ProbeComposition(name: String) {
    if (RenderCounts.enabled) SideEffect { RenderCounts.hit(name) }
}
