package dev.riffworkbench.ui.score

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.riffworkbench.editor.Selection
import dev.riffworkbench.model.*

/** Virtualizes measures. Geometry is derived from the same layout for painting and hit testing. */
@Composable fun ScoreView(project: Project, selection: Selection, onSelect: (Selection) -> Unit, modifier: Modifier = Modifier) {
    val track = project.tracks.firstOrNull { it.id == selection.trackId } ?: return
    val instrument = track.instrument
    val rows = if (instrument.tuning.isNotEmpty()) instrument.tuning.indices.reversed().toList() else listOf(0)
    val color = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val background = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    val density = LocalDensity.current
    val paint = remember(color, density) { Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb(); textSize = with(density) { 14.sp.toPx() }; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER } }
    val rowHeight = 34.dp; val header = 48.dp
    val height = header + rowHeight * rows.size + 24.dp
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Row {
            Column(Modifier.width(38.dp).padding(top = header - 12.dp)) {
                rows.forEach { string -> Box(Modifier.height(rowHeight).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(if (instrument.tuning.isEmpty()) "♪" else pitchName(instrument.tuning[string]), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                } }
            }
            LazyRow(Modifier.height(height).weight(1f)) {
                itemsIndexed(project.measures, key = { _, measure -> measure.id.value }) { index, measure ->
                    val slots = (measure.signature.length().toDouble() * 16).toInt().coerceAtLeast(1)
                    val width = 24.dp + 42.dp * slots
                    val beats = track.staves.firstOrNull()?.bars?.firstOrNull { it.measureId == measure.id }?.voices?.firstOrNull { it.index == selection.voice }?.beats.orEmpty()
                    Canvas(Modifier.width(width).height(height).semantics {
                        contentDescription = "${index + 1}. ${measure.signature.numerator}/${measure.signature.denominator}, ${beats.sumOf { it.notes.size }} notes"
                        customActions = listOf(CustomAccessibilityAction("Select bar ${index + 1}") { onSelect(selection.copy(measureId = measure.id, offset = Fraction.ZERO)); true })
                    }.pointerInput(project.revision, selection, slots) {
                        detectTapGestures { position ->
                            val slot = ((position.x - 12.dp.toPx()) / 42.dp.toPx()).toInt().coerceIn(0, slots - 1)
                            val row = ((position.y - header.toPx() + rowHeight.toPx() / 2) / rowHeight.toPx()).toInt().coerceIn(rows.indices)
                            onSelect(selection.copy(measureId = measure.id, offset = Fraction.of(slot.toLong(), 16), string = rows[row]))
                        }
                    }) {
                        val top = header.toPx(); val step = 42.dp.toPx(); val inset = 12.dp.toPx()
                        drawLine(muted, Offset(0f, top - 10.dp.toPx()), Offset(0f, size.height - 16.dp.toPx()))
                        paint.color = muted.toArgb(); paint.textAlign = Paint.Align.LEFT
                        drawContext.canvas.nativeCanvas.drawText("${index + 1}   ${measure.signature.numerator}/${measure.signature.denominator}", inset, 24.dp.toPx(), paint)
                        paint.textAlign = Paint.Align.CENTER
                        rows.forEachIndexed { row, _ -> drawLine(muted.copy(alpha = 0.45f), Offset(0f, top + row * rowHeight.toPx()), Offset(size.width, top + row * rowHeight.toPx())) }
                        if (measure.id == selection.measureId) {
                            val x = inset + selection.offset.toDouble().toFloat() * 16 * step
                            val row = rows.indexOf(selection.string).coerceAtLeast(0)
                            drawRoundRect(accent.copy(alpha = 0.12f), Offset(x - 15.dp.toPx(), top + row * rowHeight.toPx() - 15.dp.toPx()), Size(30.dp.toPx(), 30.dp.toPx()), CornerRadius(5.dp.toPx()))
                            drawRoundRect(accent, Offset(x - 15.dp.toPx(), top + row * rowHeight.toPx() - 15.dp.toPx()), Size(30.dp.toPx(), 30.dp.toPx()), CornerRadius(5.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        }
                        beats.forEach { beat ->
                            val x = inset + beat.offset.toDouble().toFloat() * 16 * step
                            paint.color = muted.toArgb()
                            paint.textSize = 10.dp.toPx()
                            drawContext.canvas.nativeCanvas.drawText("1/${beat.duration.denominator}${"·".repeat(beat.duration.dots)}", x, size.height - 3.dp.toPx(), paint)
                            paint.textSize = 14.dp.toPx()
                            if (beat.explicitRest) { paint.color = muted.toArgb(); drawContext.canvas.nativeCanvas.drawText("r", x, top + 5.dp.toPx(), paint) }
                            beat.notes.forEach { note ->
                                val row = if (note.string != null) rows.indexOf(note.string).coerceAtLeast(0) else 0
                                val y = top + row * rowHeight.toPx()
                                drawRect(background, Offset(x - 11.dp.toPx(), y - 10.dp.toPx()), Size(22.dp.toPx(), 20.dp.toPx()))
                                paint.color = color.toArgb()
                                val dead = Articulation.Mark(NoteMark.DEAD) in note.articulations
                                drawContext.canvas.nativeCanvas.drawText(if (dead) "x" else note.fret?.toString() ?: note.pitch.midiNumber.toString(), x, y + 5.dp.toPx(), paint)
                                if (Articulation.Mark(NoteMark.PALM_MUTE) in note.articulations) { paint.color = accent.toArgb(); drawContext.canvas.nativeCanvas.drawText("·", x, y + 18.dp.toPx(), paint) }
                            }
                        }
                    }
                }
            }
        }
    }
}
fun pitchName(midi: Int): String = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[((midi % 12) + 12) % 12]
