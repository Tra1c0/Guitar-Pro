package dev.riffworkbench.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.riffworkbench.capabilities.*


@Composable fun RiffTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) TabinoDark else TabinoLight, shapes = TabinoShapes, typography = TabinoType, content = content)
}
@Composable fun english() = LocalConfiguration.current.locales[0].language == "en"
@Composable fun SectionHeading(title: String, description: String? = null, kicker: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        kicker?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable fun NoticeCard(text: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable fun PendingCard(title: String, description: String, milestone: String = "C") {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${stringResource(R.string.pending)} · ${stringResource(R.string.stage)} $milestone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
@Composable fun statusLabel(status: ImplementationStatus): String = stringResource(when (status) {
    ImplementationStatus.IMPLEMENTED -> R.string.implemented
    ImplementationStatus.SCAFFOLDED -> R.string.pending
    ImplementationStatus.EXPERIMENTAL -> R.string.experimental
    ImplementationStatus.LICENSE_BOUND -> R.string.license_bound
    ImplementationStatus.NOT_APPLICABLE -> R.string.not_applicable
})
@Composable fun dimensionLabel(dimension: Dimension): String = stringResource(when (dimension) {
    Dimension.READ -> R.string.read_label; Dimension.DISPLAY -> R.string.display_label; Dimension.EDIT -> R.string.edit_label
    Dimension.PLAYBACK -> R.string.play_label; Dimension.EXPORT -> R.string.export_label
})
@Composable fun FeatureCard(feature: Feature) {
    val en = english()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (en) feature.labelEn else feature.labelDe, style = MaterialTheme.typography.titleSmall)
            feature.dimensions.forEach { (dimension, status) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dimensionLabel(dimension), style = MaterialTheme.typography.bodySmall)
                    Text(statusLabel(status), style = MaterialTheme.typography.bodySmall, color = if (status == ImplementationStatus.IMPLEMENTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("${feature.id} · ${feature.contractId} · ${feature.milestone}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    // One toggleable row: TalkBack reads the label together with the switch state, and the whole row is the touch target.
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
