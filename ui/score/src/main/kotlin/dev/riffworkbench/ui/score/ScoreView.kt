package dev.riffworkbench.ui.score

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import dev.riffworkbench.model.*
import dev.riffworkbench.ui.components.Tabino
import dev.riffworkbench.notation.*

/** A tap on the score: measure, the nearest beat column and the string under the finger (TAB only). */
data class ScoreTap(val measureId: EntityId, val offset: Fraction, val string: Int?, val noteId: EntityId?)

/** Cursor to highlight: a column (optionally a string) or a bar range. */
data class ScoreCursor(val measureId: EntityId, val offset: Fraction, val string: Int?, val rangeEnd: EntityId? = null)

@Composable
fun rememberInkPalette(): InkPalette {
    val c = MaterialTheme.colorScheme
    return remember(c) {
        InkPalette(mapOf(
            Ink.STAFF to c.outline.copy(alpha = 0.75f).toArgb(), Ink.NOTE to c.onSurface.toArgb(), Ink.TEXT to c.onSurface.toArgb(),
            Ink.MUTED to c.onSurfaceVariant.copy(alpha = 0.6f).toArgb(), Ink.ACCENT to c.primary.toArgb(), Ink.SELECTION to c.primary.toArgb(),
            Ink.PLAYHEAD to Color(0xFFFF7043).toArgb(), Ink.SECTION to c.primary.toArgb(), Ink.ON_SECTION to c.onPrimary.toArgb(),
            Ink.ERROR to c.error.toArgb(), Ink.GRID to c.outlineVariant.toArgb()), c.surface.toArgb(),
            chips = Tabino.StringColors.map { it.toArgb() }.toIntArray(), chipText = Tabino.Ink.toArgb())
    }
}

/**
 * Viewport over a [ScoreLayout]: draws only visible glyphs, pans and pinch-zooms around the focus point, maps taps
 * back to musical positions and keeps the cursor (and, when [follow] is set, the playhead) in view.
 * Layout runs elsewhere; selection and playhead are overlays, so moving them never re-lays out the score.
 */
@Composable
fun ScoreCanvas(
    layout: ScoreLayout?,
    cursor: ScoreCursor?,
    playhead: Pair<EntityId, Double>?,
    follow: Boolean,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onTap: (ScoreTap) -> Unit,
    modifier: Modifier = Modifier,
    description: String = "",
    /** Drag and drop of the selected note: (from, to). Null disables dragging (pans as before). */
    onMoveNote: ((ScoreTap, ScoreTap) -> Unit)? = null,
) {
    val density = LocalDensity.current.density
    val palette = rememberInkPalette()
    val painter = remember(palette) { GlyphPainter(palette) }
    val selectionColor = MaterialTheme.colorScheme.primary
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val factor = density * zoom
    val currentFactor by rememberUpdatedState(factor)
    val currentLayout by rememberUpdatedState(layout)
    val currentZoom by rememberUpdatedState(zoom)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentCursor by rememberUpdatedState(cursor)
    val currentOnMove by rememberUpdatedState(onMoveNote)
    val currentFollow by rememberUpdatedState(follow)
    // Finger position (viewport px) while a selected note is dragged.
    var dragAt by remember { mutableStateOf<Offset?>(null) }
    // Last pan/zoom by the user: following the playhead pauses briefly instead of fighting the finger.
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    val overlay = remember(selectionColor, palette) { OverlayPaints(selectionColor.toArgb(), palette.of(Ink.PLAYHEAD)) }
    fun clamp() {
        val l = currentLayout ?: return
        offsetX = offsetX.coerceIn(0f, maxOf(0f, l.width * currentFactor - viewport.width))
        offsetY = offsetY.coerceIn(0f, maxOf(0f, l.height * currentFactor - viewport.height))
    }
    // Keep the edit cursor visible after taps, arrows and auto-advance.
    LaunchedEffect(cursor?.measureId, cursor?.offset, layout, zoom, viewport) {
        val l = layout ?: return@LaunchedEffect
        val c = cursor ?: return@LaunchedEffect
        // While following playback the playhead owns the viewport; re-running on a layout/viewport change must not
        // yank it back to the edit cursor (that made the view jump between cursor and playhead).
        if (currentFollow) return@LaunchedEffect
        val x = (l.xFor(c.measureId, c.offset) ?: return@LaunchedEffect) * factor
        val sys = l.measures.firstOrNull { it.measureId == c.measureId }?.let { m -> l.systems.getOrNull(m.system) }
        if (x < offsetX + viewport.width * 0.08f || x > offsetX + viewport.width * 0.85f) offsetX = x - viewport.width * 0.3f
        if (sys != null) {
            val top = sys.top * factor; val bottom = sys.bottom * factor
            if (top < offsetY || bottom > offsetY + viewport.height) offsetY = top - 8f * density
        }
        clamp()
    }
    // Follow the playhead while playing.
    LaunchedEffect(playhead, follow, layout, zoom) {
        val l = layout ?: return@LaunchedEffect
        val p = playhead ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        if (System.currentTimeMillis() - userScrolledAt < 2500) return@LaunchedEffect
        val x = (l.xFor(p.first, p.second) ?: return@LaunchedEffect) * factor
        if (x < offsetX + viewport.width * 0.1f || x > offsetX + viewport.width * 0.75f) offsetX = x - viewport.width * 0.25f
        val sys = l.measures.firstOrNull { it.measureId == p.first }?.let { m -> l.systems.getOrNull(m.system) }
        if (sys != null) { val top = sys.top * factor; if (top < offsetY || sys.bottom * factor > offsetY + viewport.height) offsetY = top - 8f * density }
        clamp()
    }
    Box(modifier.clipToBounds().onSizeChanged { viewport = it; clamp() }
        .semantics { contentDescription = description }
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                val old = currentFactor
                val nextZoom = (currentZoom * gestureZoom).coerceIn(0.5f, 2.5f)
                if (gestureZoom != 1f) {
                    val nf = density * nextZoom
                    // Keep the content under the fingers fixed while zooming.
                    offsetX = (offsetX + centroid.x) * nf / old - centroid.x
                    offsetY = (offsetY + centroid.y) * nf / old - centroid.y
                    currentOnZoom(nextZoom)
                }
                offsetX -= pan.x; offsetY -= pan.y
                if (pan != Offset.Zero || gestureZoom != 1f) userScrolledAt = System.currentTimeMillis()
                clamp()
            }
        }
        .pointerInput(Unit) {
            detectTapGestures { pos: Offset ->
                val l = currentLayout ?: return@detectTapGestures
                val x = (pos.x + offsetX) / currentFactor
                val y = (pos.y + offsetY) / currentFactor
                val note = l.notes.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
                // A hit fret number selects its own beat, even where compressed note boxes overlap the neighbouring column.
                if (note != null) { currentOnTap(ScoreTap(note.measureId, note.offset, note.string ?: l.stringAt(y), note.noteId)); return@detectTapGestures }
                val column = l.columnAt(x, y) ?: return@detectTapGestures
                currentOnTap(ScoreTap(column.measureId, column.offset, l.stringAt(y), null))
            }
        }
        .pointerInput(Unit) {
            // Innermost detector: it sees moves first and consumes them only when the selected note is being dragged.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val l = currentLayout ?: return@awaitEachGesture
                val c = currentCursor ?: return@awaitEachGesture
                if (currentOnMove == null) return@awaitEachGesture
                val hx = (down.position.x + offsetX) / currentFactor; val hy = (down.position.y + offsetY) / currentFactor
                val note = l.notes.firstOrNull { hx >= it.x && hx <= it.x + it.w && hy >= it.y && hy <= it.y + it.h } ?: return@awaitEachGesture
                // A notehead in the standard staff has no string: it stands for the selected note of that beat.
                if (note.measureId != c.measureId || note.offset != c.offset || (note.string != null && note.string != c.string)) return@awaitEachGesture
                val fromString = note.string ?: c.string
                var dragging = false
                var last = down.position
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) { dragAt = null; return@awaitEachGesture }   // pinch: zoom as before
                    val change = event.changes.firstOrNull { it.id == down.id } ?: run { dragAt = null; return@awaitEachGesture }
                    // A cancelled gesture (system gesture, parent intercept) arrives as an already consumed release: abandon it.
                    if (!change.pressed) { if (change.isConsumed) { dragAt = null; return@awaitEachGesture }; break }
                    if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                    // While dragging, following the playback pauses like after a pan, so the score does not move under the finger.
                    if (dragging) { change.consume(); last = change.position; dragAt = last; userScrolledAt = System.currentTimeMillis() }
                }
                if (!dragging) return@awaitEachGesture
                dragAt = null
                val x = (last.x + offsetX) / currentFactor; val y = (last.y + offsetY) / currentFactor
                val column = l.columnAt(x, y) ?: return@awaitEachGesture
                val string = l.stringAt(y) ?: fromString
                currentOnMove?.invoke(ScoreTap(note.measureId, note.offset, fromString, note.noteId), ScoreTap(column.measureId, column.offset, string, null))
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val l = layout ?: return@Canvas
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.save()
                nc.translate(-offsetX, -offsetY)
                nc.scale(factor, factor)
                val x0 = offsetX / factor; val x1 = (offsetX + size.width) / factor
                // Selection overlay below the notes.
                cursor?.let { c -> drawCursor(nc, l, c, overlay) }
                // Drop target while dragging a note.
                dragAt?.let { d ->
                    val x = (d.x + offsetX) / factor; val y = (d.y + offsetY) / factor
                    l.columnAt(x, y)?.let { col -> drawCursor(nc, l, ScoreCursor(col.measureId, col.offset, l.stringAt(y) ?: cursor?.string), overlay) }
                }
                // Cull by system first (y), then by x inside the painter: off-screen systems cost nothing per frame.
                val y0 = offsetY / factor; val y1 = (offsetY + size.height) / factor
                l.systems.forEachIndexed { i, sys -> if (sys.bottom >= y0 - 40f && sys.top <= y1 + 40f) painter.draw(nc, l.glyphsBySystem[i], x0, x1) }
                painter.draw(nc, l.glyphsBySystem.last(), x0, x1)
                playhead?.let { (measure, offset) ->
                    val px = l.xFor(measure, offset)
                    val sys = l.measures.firstOrNull { it.measureId == measure }?.let { m -> l.systems.getOrNull(m.system) }
                    if (px != null && sys != null) nc.drawLine(px, sys.top + 18f, px, sys.bottom - 12f, overlay.playhead)
                }
                nc.restore()
                dragAt?.let { d -> nc.drawCircle(d.x, d.y, 22f * density, overlay.fill); nc.drawCircle(d.x, d.y, 22f * density, overlay.stroke) }
            }
        }
    }
}

private class OverlayPaints(color: Int, playheadColor: Int) {
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = (color and 0x00FFFFFF) or 0x26000000; strokeWidth = 2f }
    val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.6f }
    val playhead = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = playheadColor; strokeWidth = 2.2f }
    val rect = android.graphics.RectF()
}

private fun drawCursor(nc: android.graphics.Canvas, l: ScoreLayout, c: ScoreCursor, paints: OverlayPaints) {
    val fill = paints.fill
    val stroke = paints.stroke
    c.rangeEnd?.let { end ->
        val a = l.measures.indexOfFirst { it.measureId == c.measureId }; val b = l.measures.indexOfFirst { it.measureId == end }
        if (a >= 0 && b >= 0) for (m in l.measures.subList(minOf(a, b), maxOf(a, b) + 1)) {
            val sys = l.systems.getOrNull(m.system) ?: continue
            nc.drawRect(m.x + 1f, sys.top + 18f, m.x + m.width - 1f, sys.bottom - 14f, fill)
        }
    }
    val x = l.xFor(c.measureId, c.offset) ?: return
    val m = l.measures.firstOrNull { it.measureId == c.measureId } ?: return
    val sys = l.systems.getOrNull(m.system) ?: return
    val string = c.string
    if (string != null && string in sys.stringY.indices) {
        val y = sys.stringY[string]
        val h = if (sys.stringY.size > 1) kotlin.math.abs(sys.stringY[0] - sys.stringY[1]) else 20f
        val rect = paints.rect.apply { set(x - h * 0.62f, y - h * 0.48f, x + h * 0.62f, y + h * 0.48f) }
        nc.drawRoundRect(rect, 4f, 4f, fill); nc.drawRoundRect(rect, 4f, 4f, stroke)
        // Beat column marker spanning the staff.
        nc.drawLine(x, sys.tabTop - h * 0.6f, x, sys.tabBottom + h * 0.6f, fill)
    } else {
        val top = if (sys.staffBottom > sys.staffTop) sys.staffTop - 10f else sys.tabTop - 10f
        val bottom = if (sys.tabBottom > sys.tabTop) sys.tabBottom + 10f else sys.staffBottom + 10f
        nc.drawRect(x - 9f, top, x + 9f, bottom, fill); nc.drawRect(x - 9f, top, x + 9f, bottom, stroke)
    }
}

fun pitchName(midi: Int): String = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[((midi % 12) + 12) % 12]
