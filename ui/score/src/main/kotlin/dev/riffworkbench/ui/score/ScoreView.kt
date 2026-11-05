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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import dev.riffworkbench.model.*
import dev.riffworkbench.ui.components.Tabino
import dev.riffworkbench.notation.*
import kotlin.math.abs

/** A tap on the score: measure, the nearest beat column and the string under the finger (TAB only). */
data class ScoreTap(val measureId: EntityId, val offset: Fraction, val string: Int?, val noteId: EntityId?)

/** Cursor to highlight: a column (optionally a string) or a bar range. */
data class ScoreCursor(val measureId: EntityId, val offset: Fraction, val string: Int?, val rangeEnd: EntityId? = null)

@Composable
fun rememberInkPalette(): InkPalette {
    val c = MaterialTheme.colorScheme
    return remember(c) {
        InkPalette(mapOf(
            Ink.STAFF to c.outline.copy(alpha = 0.9f).toArgb(), Ink.NOTE to c.onSurface.toArgb(), Ink.TEXT to c.onSurface.toArgb(),
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
 *
 * Scrolling follows [ViewportScroll]: the view stands still while the cursor is visible inside a small edge zone and
 * otherwise shifts by the minimum (far targets are paged). Without a playhead the cursor is the start marker and carries
 * a small ▶ cap; the playhead is the heavier orange line with a triangle cap.
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
    /** Long press on a drawn time signature (incl. courtesy repeats): the bar whose signature was pressed. */
    onSignatureLongPress: ((EntityId) -> Unit)? = null,
    /** Following is paused by the owner (after a manual pan while playing); the effect waits, no timer here. */
    followSuspended: Boolean = false,
    /** The user panned, zoomed or dragged while following: the owner decides how long following pauses. */
    onUserScroll: (() -> Unit)? = null,
) {
    val density = LocalDensity.current.density
    val palette = rememberInkPalette()
    val painter = remember(palette) { GlyphPainter(palette) }
    val selectionColor = MaterialTheme.colorScheme.primary
    val haptics = LocalHapticFeedback.current
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
    val currentSuspended by rememberUpdatedState(followSuspended)
    val currentOnSignature by rememberUpdatedState(onSignatureLongPress)
    val currentOnUserScroll by rememberUpdatedState(onUserScroll)
    // Finger position (viewport px) while a selected note is dragged.
    var dragAt by remember { mutableStateOf<Offset?>(null) }
    // Cursor geometry of the last cursor-effect run: a relayout that keeps the cursor in place must not move the view.
    var lastCursorKey by remember { mutableStateOf<List<Any?>?>(null) }
    // After a suspended follow resumes, the first scroll uses the minimal shift instead of the 25 % re-centre.
    var resumeMinimal by remember { mutableStateOf(false) }
    var wasSuspended by remember { mutableStateOf(false) }
    val overlay = remember(selectionColor, palette) { OverlayPaints(selectionColor.toArgb(), palette.of(Ink.PLAYHEAD)) }
    fun clamp() {
        val l = currentLayout ?: return
        offsetX = ViewportScroll.clamp(offsetX, l.width * currentFactor, viewport.width.toFloat())
        offsetY = ViewportScroll.clamp(offsetY, l.height * currentFactor, viewport.height.toFloat())
    }
    fun userScrolled() { if (currentFollow && !currentSuspended) currentOnUserScroll?.invoke() }
    // Keep the edit cursor visible after taps, arrows and auto-advance.
    LaunchedEffect(cursor?.measureId, cursor?.offset, cursor?.string, layout, zoom, viewport) {
        val l = layout ?: return@LaunchedEffect
        val c = cursor ?: return@LaunchedEffect
        // Before the first measure pass the viewport is 0×0: every position looks off-screen and the view would jump.
        if (viewport.width == 0 || viewport.height == 0) return@LaunchedEffect
        // While following playback the playhead owns the viewport; re-running on a layout/viewport change must not
        // yank it back to the edit cursor (that made the view jump between cursor and playhead).
        if (currentFollow) return@LaunchedEffect
        val lx = l.xFor(c.measureId, c.offset) ?: return@LaunchedEffect
        val sys = l.measures.firstOrNull { it.measureId == c.measureId }?.let { m -> l.systems.getOrNull(m.system) }
        val spacing = sys?.let { if (it.stringY.size > 1) abs(it.stringY[0] - it.stringY[1]) else 20f } ?: 20f
        val rowY = sys?.let { s -> c.string?.let { s.stringY.getOrNull(it) } ?: if (s.stringY.isNotEmpty()) (s.tabTop + s.tabBottom) / 2 else (s.staffTop + s.staffBottom) / 2 }
        val key = listOf(lx, rowY, factor, viewport)
        if (key == lastCursorKey) return@LaunchedEffect
        lastCursorKey = key
        val margin = ViewportScroll.MARGIN * density
        offsetX = ViewportScroll.horizontal(lx * factor, spacing * 0.62f * factor, offsetX, viewport.width.toFloat(), l.width * factor, margin)
        if (rowY != null) offsetY = ViewportScroll.vertical(rowY * factor, spacing * factor, offsetY, viewport.height.toFloat(), l.height * factor, margin)
        clamp()
    }
    LaunchedEffect(followSuspended) {
        if (followSuspended) wasSuspended = true else if (wasSuspended) { wasSuspended = false; resumeMinimal = true }
    }
    // Follow the playhead while playing.
    LaunchedEffect(playhead, follow, followSuspended, layout, zoom) {
        val l = layout ?: return@LaunchedEffect
        val p = playhead ?: return@LaunchedEffect
        if (!follow || followSuspended || viewport.width == 0 || viewport.height == 0) return@LaunchedEffect
        val x = (l.xFor(p.first, p.second) ?: return@LaunchedEffect) * factor
        val margin = ViewportScroll.MARGIN * density
        offsetX = ViewportScroll.follow(x, 6f * density, offsetX, viewport.width.toFloat(), l.width * factor, resumeMinimal, margin)
        resumeMinimal = false
        val sys = l.measures.firstOrNull { it.measureId == p.first }?.let { m -> l.systems.getOrNull(m.system) }
        if (sys != null) {
            // The band the playhead runs through (TAB, or the staff without TAB), not the whole system.
            val (top, bottom) = if (sys.stringY.isNotEmpty()) sys.tabTop to sys.tabBottom else sys.staffTop to sys.staffBottom
            offsetY = ViewportScroll.vertical((top + bottom) / 2 * factor, ((bottom - top) / 2 + 12f) * factor, offsetY, viewport.height.toFloat(), l.height * factor, margin)
        }
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
                if (pan != Offset.Zero || gestureZoom != 1f) userScrolled()
                clamp()
            }
        }
        .pointerInput(Unit) {
            // Same detector as the tap, so the outer transform detector still cancels both once a pan passes the slop.
            detectTapGestures(onLongPress = { pos: Offset ->
                val l = currentLayout ?: return@detectTapGestures
                val handler = currentOnSignature ?: return@detectTapGestures
                val box = l.signatureAt((pos.x + offsetX) / currentFactor, (pos.y + offsetY) / currentFactor) ?: return@detectTapGestures
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                handler(box.measureId)
            }) { pos: Offset ->
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
                    if (dragging) { change.consume(); last = change.position; dragAt = last; userScrolled() }
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
                // Selection overlay below the notes; without a playhead the cursor is the start marker (▶ cap).
                cursor?.let { c -> drawCursor(nc, l, c, overlay, startMarker = playhead == null) }
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
                    if (px != null && sys != null) {
                        nc.drawLine(px, sys.top + 18f, px, sys.bottom - 12f, overlay.playhead)
                        // Triangle cap: the playhead differs from the cursor by shape, not only by colour.
                        val cap = overlay.path.apply { rewind(); moveTo(px - 6f, sys.top + 8f); lineTo(px + 6f, sys.top + 8f); lineTo(px, sys.top + 18f); close() }
                        nc.drawPath(cap, overlay.playhead)
                    }
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
    /** Beat column line of the cursor: visible on its own (the 15 % fill was the "thin dark pointer" users could hardly see). */
    val column = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = (color and 0x00FFFFFF) or 0x80000000.toInt(); strokeWidth = 2.5f }
    val marker = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val playhead = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = playheadColor; strokeWidth = 3.5f }
    val rect = android.graphics.RectF()
    val path = android.graphics.Path()
}

private fun drawCursor(nc: android.graphics.Canvas, l: ScoreLayout, c: ScoreCursor, paints: OverlayPaints, startMarker: Boolean = false) {
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
    val top: Float
    if (string != null && string in sys.stringY.indices) {
        val y = sys.stringY[string]
        val h = if (sys.stringY.size > 1) abs(sys.stringY[0] - sys.stringY[1]) else 20f
        val rect = paints.rect.apply { set(x - h * 0.62f, y - h * 0.48f, x + h * 0.62f, y + h * 0.48f) }
        nc.drawRoundRect(rect, 4f, 4f, fill); nc.drawRoundRect(rect, 4f, 4f, stroke)
        // Beat column marker spanning the staff.
        top = sys.tabTop - h * 0.6f
        nc.drawLine(x, top, x, sys.tabBottom + h * 0.6f, paints.column)
    } else {
        top = if (sys.staffBottom > sys.staffTop) sys.staffTop - 10f else sys.tabTop - 10f
        val bottom = if (sys.tabBottom > sys.tabTop) sys.tabBottom + 10f else sys.staffBottom + 10f
        nc.drawRect(x - 9f, top, x + 9f, bottom, fill); nc.drawRect(x - 9f, top, x + 9f, bottom, stroke)
    }
    // Start marker: a small ▶ above the column tells where Play will begin (the orange playhead is drawn only while playing/paused).
    if (startMarker) {
        val cap = paints.path.apply { rewind(); moveTo(x - 4f, top - 12f); lineTo(x + 6f, top - 6f); lineTo(x - 4f, top); close() }
        nc.drawPath(cap, paints.marker)
    }
}

fun pitchName(midi: Int): String = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[((midi % 12) + 12) % 12]
