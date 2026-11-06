package dev.riffworkbench.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A notice that lies over the UI instead of taking a row of its own (QA 23, Gerätebefund: notices pushed the layout down
 * and had to be closed by hand). Place it in a Box with an alignment; it never changes the size of anything around it.
 *
 *  - [timeoutMillis] non-null: [onTimeout] runs after that much wall-clock time (longer when the accessibility service asks
 *    for more time for text); null: it stays until the owner removes it (e.g. a failed save with "Erneut versuchen" in [actions]).
 *  - No click handler on the notice itself: taps on the controls underneath still land (only [actions] take touches).
 *  - A polite live region, so TalkBack announces the text.
 */
@Composable fun TransientNotice(text: String, timeoutMillis: Long?, onTimeout: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val accessibility = LocalAccessibilityManager.current
    val currentOnTimeout by rememberUpdatedState(onTimeout)
    if (timeoutMillis != null) LaunchedEffect(text, timeoutMillis) {
        val millis = accessibility?.calculateRecommendedTimeoutMillis(timeoutMillis, containsIcons = false, containsText = true, containsControls = false) ?: timeoutMillis
        // Wall-clock time, not the composition's clock: a test clock that skips delays would close the notice before anyone
        // (or any test) saw it. Same behaviour on the device.
        withContext(Dispatchers.Default) { delay(millis) }
        currentOnTimeout()
    }
    val shape = RoundedCornerShape(16.dp)
    // A Box, not a Surface: a Surface would swallow every touch on its area.
    Box(modifier.widthIn(max = 560.dp).fillMaxWidth().shadow(6.dp, shape).background(MaterialTheme.colorScheme.errorContainer, shape)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            actions()
        }
    }
}
