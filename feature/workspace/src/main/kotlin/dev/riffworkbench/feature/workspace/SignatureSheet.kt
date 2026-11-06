package dev.riffworkbench.feature.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.editor.EditorCommand
import dev.riffworkbench.model.*
import dev.riffworkbench.ui.components.BigTitle
import dev.riffworkbench.ui.components.R

@Composable internal fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusTag: String? = null, plusTag: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 72.dp))
        OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp).let { m -> minusTag?.let { m.testTag(it) } ?: m }) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.widthIn(min = 40.dp))
        OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp).let { m -> plusTag?.let { m.testTag(it) } ?: m }) { Text("+") }
    }
}

/** The ViewModel notice inside a bottom sheet: the page's notice row lies behind the sheet's scrim. */
@Composable internal fun SheetNotice(vm: WorkspaceViewModel, notice: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(notice, Modifier.weight(1f).padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { vm.showNotice(null) }) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

/** Tempo in effect at bar [index]: the last tempo mark at or before it (bar 1 always carries one). */
internal fun tempoInEffect(p: Project, index: Int): Double? =
    p.tempo.filter { t -> p.measures.indexOfFirst { it.id == t.position.measureId } <= index }
        .maxByOrNull { t -> p.measures.indexOfFirst { it.id == t.position.measureId } }?.quarterNotesPerMinute

/** Bar (1-based) whose time signature [index] inherits, or null when the signature is set in this bar. */
internal fun signatureSetInBar(p: Project, index: Int): Int? {
    if (index <= 0 || p.measures[index].signature != p.measures[index - 1].signature) return null
    var k = index
    while (k > 0 && p.measures[k - 1].signature == p.measures[index].signature) k--
    return k + 1
}

/** Signature and tempo controls of one bar, shared by the arrangement page and the editor's signature sheet. */
@Composable internal fun SignatureTempoControls(vm: WorkspaceViewModel, p: Project, measure: Measure, index: Int) {
    val tempoHere = p.tempo.firstOrNull { it.position == ScorePosition(measure.id) }
    var bpm by remember(p.tempo, measure.id) { mutableStateOf((tempoInEffect(p, index) ?: 120.0).toInt().toString()) }
    Text(stringResource(R.string.time_signature), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stepper(tr("Zähler", "Beats"), "${measure.signature.numerator}", { if (measure.signature.numerator > 1) vm.command(EditorCommand.SetSignature(measure.id, measure.signature.copy(numerator = measure.signature.numerator - 1))) },
            { if (measure.signature.numerator < 32) vm.command(EditorCommand.SetSignature(measure.id, measure.signature.copy(numerator = measure.signature.numerator + 1))) },
            minusTag = "sig-numerator-minus", plusTag = "sig-numerator-plus")
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(2, 4, 8, 16).forEach { d -> FilterChip(measure.signature.denominator == d, { vm.command(EditorCommand.SetSignature(measure.id, measure.signature.copy(denominator = d))) }, label = { Text("/$d") }) }
        listOf(TimeSignature(4, 4), TimeSignature(3, 4), TimeSignature(6, 8), TimeSignature(7, 8), TimeSignature(5, 4), TimeSignature(12, 8)).forEach { sig ->
            AssistChip(onClick = { vm.command(EditorCommand.SetSignature(measure.id, sig)) }, label = { Text("${sig.numerator}/${sig.denominator}") }, modifier = Modifier.testTag("sig-preset-${sig.numerator}-${sig.denominator}"))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(bpm, { bpm = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("BPM") }, modifier = Modifier.textEditing().width(120.dp).testTag("sig-bpm"), singleLine = true)
        Button(onClick = { bpm.toDoubleOrNull()?.let { vm.command(if (index == 0) EditorCommand.SetTempo(it) else EditorCommand.SetTempoAt(measure.id, it)) } ?: vm.showNotice("Ungültiges Tempo") }) { Text(stringResource(R.string.tempo)) }
        if (tempoHere != null && index > 0) TextButton(onClick = { vm.command(EditorCommand.SetTempoAt(measure.id, null)) }) { Text(tr("Tempowechsel entfernen", "Remove tempo change")) }
    }
}

/**
 * Bottom sheet opened by a long press on a time signature (or a tap on the bar info in the header): signature and tempo
 * of [measureId]. The ViewModel notice is shown inside the sheet, because the page's notice row is behind the scrim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SignatureSheet(vm: WorkspaceViewModel, measureId: EntityId, onDismiss: () -> Unit) {
    val ws by vm.state.collectAsStateWithLifecycle()
    val p = ws.editor?.project
    val index = p?.measures?.indexOfFirst { it.id == measureId } ?: -1
    // The bar may have been deleted meanwhile (undo, another device): close instead of crashing.
    if (p == null || index < 0) { LaunchedEffect(measureId) { onDismiss() }; return }
    val measure = p.measures[index]
    val bpm = tempoInEffect(p, index)
    val inherited = signatureSetInBar(p, index)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("signature-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTitle("${stringResource(R.string.measure)} ${index + 1}", "${measure.signature.numerator}/${measure.signature.denominator}" + (bpm?.let { " · ${it.toInt()} BPM" } ?: ""), size = 22)
            if (inherited != null) Text(tr("Taktart übernommen aus Takt $inherited – eine Änderung gilt ab Takt ${index + 1}.", "Time signature carried over from bar $inherited – a change applies from bar ${index + 1}."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ws.notice?.let { SheetNotice(vm, it) }
            SignatureTempoControls(vm, p, measure, index)
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).height(48.dp).testTag("signature-done"), shape = RoundedCornerShape(24.dp)) { Text(tr("Fertig", "Done")) }
        }
    }
}
