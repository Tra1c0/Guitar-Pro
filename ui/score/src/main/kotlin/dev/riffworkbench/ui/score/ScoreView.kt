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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsPropertyKey
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

/**
 * State of the score viewport in pixels, published as the semantics property [ScoreViewportKey] for UI tests (QA 21: the
 * view must not jump while the cursor is visible). Accessibility services ignore unknown keys. [cursorX]/[cursorY] are the
 * cursor column and string row in content pixels (null without a cursor position in the layout).
 */
data class ScoreViewport(val offsetX: Float, val offsetY: Float, val factor: Float, val width: Int, val height: Int,
                         val contentWidth: Float, val contentHeight: Float, val cursorX: Float?, val cursorY: Float?)
val ScoreViewportKey = SemanticsPropertyKey<ScoreViewport>("RiffScoreViewport")

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
    /**
     * Long press on a bar (Bericht 1 §5): the pressed bar becomes the anchor of a bar range. A drawn time signature wins
     * ([onSignatureLongPress]); on the selected note the press stays a note drag ([onMoveNote]). Null disables it.
     */
    onRangeStart: ((EntityId) -> Unit)? = null,
    /** Drag after [onRangeStart]: the bar under (or, beside the bars, nearest to) the finger; true when the range changed (haptic tick). */
    onRangeExtend: ((EntityId) -> Boolean)? = null,
    /** Release (or cancel) after [onRangeStart]: the range stays selected. */
    onRangeEnd: (() -> Unit)? = null,
    /** Display scale on top of density × [zoom] (QA 23: the editor draws the score larger, ScoreScale.EDITOR). */
    scale: Float = 1f,
    /** Pinch zoom (QA 23: off by default in the app, "Zoomen per Geste" in the settings). Off, two fingers only pan. */
    zoomGesture: Boolean = true,
) {
    val density = LocalDensity.current.density
    val palette = rememberInkPalette()
    val painter = remember(palette) { GlyphPainter(palette) }
    val selectionColor = MaterialTheme.colorScheme.primary
    val haptics = LocalHapticFeedback.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // Zoom drawn right now (QA 23). A pinch writes it together with the offsets, so no frame combines the new offsets with
    // the old factor, and each step multiplies the live value. Before, every step read the ViewModel's zoom, which comes
    // back one or two frames later: steps compounded on a stale factor and the content jumped while pinching. The owner
    // hears the zoom once, when the last finger lifts ([pendingZoom]); a zoom set elsewhere (layout page) replaces it.
    var liveZoom by remember { mutableFloatStateOf(zoom) }
    var pendingZoom by remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(zoom) { if (pendingZoom.isNaN()) liveZoom = zoom }
    val factor = density * scale * liveZoom
    val currentScale by rememberUpdatedState(scale)
    val currentZoomGesture by rememberUpdatedState(zoomGesture)
    val currentLayout by rememberUpdatedState(layout)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentCursor by rememberUpdatedState(cursor)
    val currentOnMove by rememberUpdatedState(onMoveNote)
    val currentFollow by rememberUpdatedState(follow)
    val currentSuspended by rememberUpdatedState(followSuspended)
    val currentOnSignature by rememberUpdatedState(onSignatureLongPress)
    val currentOnUserScroll by rememberUpdatedState(onUserScroll)
    val currentOnRangeStart by rememberUpdatedState(onRangeStart)
    val currentOnRangeExtend by rememberUpdatedState(onRangeExtend)
    val currentOnRangeEnd by rememberUpdatedState(onRangeEnd)
    // Long-press range: started by the tap detector's long press, followed bar by bar by the range tracker below.
    val range = remember { RangeDrag() }
    // Finger position (viewport px) while a selected note is dragged, and the drop target it would commit (null: none).
    var dragAt by remember { mutableStateOf<Offset?>(null) }
    var dragDrop by remember { mutableStateOf<ScoreCursor?>(null) }
    // Cursor geometry of the last cursor-effect run: a relayout that keeps the cursor in place must not move the view.
    var lastCursorKey by remember { mutableStateOf<ViewportScroll.CursorKey?>(null) }
    // Zoom last set by a pinch: a factor change that comes from the fingers keeps the focus point (ViewportScroll.cursor).
    var zoomSetByGesture by remember { mutableFloatStateOf(Float.NaN) }
    // After a suspended follow resumes, the first scroll uses the minimal shift instead of the 25 % re-centre.
    var resumeMinimal by remember { mutableStateOf(false) }
    var wasSuspended by remember { mutableStateOf(false) }
    val overlay = remember(selectionColor, palette) { OverlayPaints(selectionColor.toArgb(), palette.of(Ink.PLAYHEAD)) }
    /** The factor of the current frame, for gesture and draw code that runs between compositions. */
    fun factorNow(): Float = density * currentScale * liveZoom
    fun clamp() {
        val l = currentLayout ?: return
        val f = factorNow()
        offsetX = ViewportScroll.clamp(offsetX, l.width * f, viewport.width.toFloat())
        offsetY = ViewportScroll.clamp(offsetY, l.height * f, viewport.height.toFloat())
    }
    fun userScrolled() { if (currentFollow && !currentSuspended) currentOnUserScroll?.invoke() }
    // The selected note under (x, y) in layout units, i.e. where the note-drag detector below would start a drag.
    fun onDragHandle(l: ScoreLayout, x: Float, y: Float): Boolean {
        if (currentOnMove == null) return false
        val c = currentCursor ?: return false
        val note = l.notes.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h } ?: return false
        return note.measureId == c.measureId && note.offset == c.offset && (note.string == null || note.string == c.string)
    }
    // Keep the edit cursor visible after taps, arrows and auto-advance.
    LaunchedEffect(cursor?.measureId, cursor?.offset, cursor?.string, layout, factor, viewport) {
        val l = layout ?: return@LaunchedEffect
        val c = cursor ?: return@LaunchedEffect
        // Before the first measure pass the viewport is 0×0: every position looks off-screen and the view would jump.
        if (viewport.width == 0 || viewport.height == 0) return@LaunchedEffect
        // While following playback the playhead owns the viewport; re-running on a layout/viewport change must not
        // yank it back to the edit cursor (that made the view jump between cursor and playhead).
        if (currentFollow) return@LaunchedEffect
        // The rule itself (minimal shift, paging, the pinch exception) is JVM-tested in core:notation (ViewportScrollTest, Qa21ViewportCursorTest).
        val step = ViewportScroll.cursor(l, c.measureId, c.offset, c.string, offsetX, offsetY, viewport.width.toFloat(), viewport.height.toFloat(),
            factor, density, lastCursorKey, zoomedByGesture = zoomSetByGesture == liveZoom) ?: return@LaunchedEffect
        lastCursorKey = step.key
        offsetX = step.offsetX
        offsetY = step.offsetY
    }
    LaunchedEffect(followSuspended) {
        if (followSuspended) wasSuspended = true else if (wasSuspended) { wasSuspended = false; resumeMinimal = true }
    }
    // Follow the playhead while playing.
    LaunchedEffect(playhead, follow, followSuspended, layout, factor) {
        val l = layout ?: return@LaunchedEffect
        val p = playhead ?: return@LaunchedEffect
        if (!follow || followSuspended || viewport.width == 0 || viewport.height == 0) return@LaunchedEffect
        // The rule (x: run and re-place; y: the band the playhead runs through, stable when it is taller than the canvas)
        // is JVM-tested in core:notation (ViewportScrollTest, Qa23FollowZoomTest).
        val step = ViewportScroll.playhead(l, p.first, p.second, offsetX, offsetY, viewport.width.toFloat(), viewport.height.toFloat(), factor, density, resumeMinimal)
            ?: return@LaunchedEffect
        resumeMinimal = false
        offsetX = step.offsetX
        offsetY = step.offsetY
    }
    Box(modifier.clipToBounds().onSizeChanged { viewport = it; clamp() }
        .semantics {
            contentDescription = description
            val l = layout
            val cx = l?.let { lay -> cursor?.let { c -> lay.xFor(c.measureId, c.offset) } }
            val cy = l?.let { lay -> cursor?.let { c -> lay.measures.firstOrNull { it.measureId == c.measureId }?.let { m -> lay.systems.getOrNull(m.system) } } }
                ?.let { sys -> cursor?.string?.let { sys.stringY.getOrNull(it) } ?: if (sys.stringY.isNotEmpty()) (sys.tabTop + sys.tabBottom) / 2 else (sys.staffTop + sys.staffBottom) / 2 }
            this[ScoreViewportKey] = ScoreViewport(offsetX, offsetY, factor, viewport.width, viewport.height, (l?.width ?: 0f) * factor, (l?.height ?: 0f) * factor,
                cx?.let { it * factor }, cy?.let { it * factor })
        }
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoomChange, _ ->
                // Without the zoom setting two fingers only pan (QA 23).
                val change = if (currentZoomGesture) zoomChange else 1f
                val nextZoom = (liveZoom * change).coerceIn(0.5f, 2.5f)
                val zoomed = nextZoom != liveZoom
                if (zoomed) {
                    val old = factorNow()
                    val nf = density * currentScale * nextZoom
                    // Keep the content under the fingers fixed while zooming.
                    offsetX = (offsetX + centroid.x) * nf / old - centroid.x
                    offsetY = (offsetY + centroid.y) * nf / old - centroid.y
                    zoomSetByGesture = nextZoom
                    liveZoom = nextZoom
                    pendingZoom = nextZoom
                }
                offsetX -= pan.x; offsetY -= pan.y
                if (pan != Offset.Zero || zoomed) userScrolled()
                clamp()
            }
        }
        .pointerInput(Unit) {
            // Same detector as the tap, so the outer transform detector still cancels both once a pan passes the slop.
            // After a long press it consumes every change until release, which also ends pan/zoom for that gesture.
            detectTapGestures(onLongPress = { pos: Offset ->
                val l = currentLayout ?: return@detectTapGestures
                val x = (pos.x + offsetX) / factorNow()
                val y = (pos.y + offsetY) / factorNow()
                // The drawn time signature has priority: it opens the signature/tempo sheet.
                val signature = currentOnSignature
                if (signature != null) {
                    val box = l.signatureAt(x, y)
                    if (box != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        signature(box.measureId)
                        return@detectTapGestures
                    }
                }
                // Anywhere else on a bar the long press anchors a bar range; the selected note stays a drag handle.
                val start = currentOnRangeStart ?: return@detectTapGestures
                if (onDragHandle(l, x, y)) return@detectTapGestures
                val bar = l.measureAt(x, y) ?: return@detectTapGestures
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                range.active = true
                range.last = bar.measureId
                // Following pauses like for a note drag, so the bars do not move under the finger (no seek: the view only).
                userScrolled()
                start(bar.measureId)
            }) { pos: Offset ->
                val l = currentLayout ?: return@detectTapGestures
                val x = (pos.x + offsetX) / factorNow()
                val y = (pos.y + offsetY) / factorNow()
                // Nearest column; a fret number wins where it is the closer target (ScoreLayout.tapAt, JVM-tested in core:notation).
                val hit = l.tapAt(x, y) ?: return@detectTapGestures
                currentOnTap(ScoreTap(hit.measureId, hit.offset, hit.string, hit.noteId))
            }
        }
        .pointerInput(Unit) {
            // Innermost detector: it sees moves first and consumes them only when the selected note is being dragged.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val l = currentLayout ?: return@awaitEachGesture
                val c = currentCursor ?: return@awaitEachGesture
                if (currentOnMove == null) return@awaitEachGesture
                val hx = (down.position.x + offsetX) / factorNow(); val hy = (down.position.y + offsetY) / factorNow()
                val note = l.notes.firstOrNull { hx >= it.x && hx <= it.x + it.w && hy >= it.y && hy <= it.y + it.h } ?: return@awaitEachGesture
                // A notehead in the standard staff has no string: it stands for the selected note of that beat.
                if (note.measureId != c.measureId || note.offset != c.offset || (note.string != null && note.string != c.string)) return@awaitEachGesture
                val fromString = note.string ?: c.string
                var dragging = false
                var last = down.position
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) { dragAt = null; dragDrop = null; return@awaitEachGesture }   // pinch: zoom as before
                    val change = event.changes.firstOrNull { it.id == down.id } ?: run { dragAt = null; dragDrop = null; return@awaitEachGesture }
                    // A cancelled gesture (system gesture, parent intercept) arrives as an already consumed release: abandon it.
                    if (!change.pressed) { if (change.isConsumed) { dragAt = null; dragDrop = null; return@awaitEachGesture }; break }
                    if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                    // While dragging, following the playback pauses like after a pan, so the score does not move under the finger.
                    if (dragging) {
                        change.consume(); last = change.position; dragAt = last; userScrolled()
                        // Zyklus 23: the target is the note's own place moved by the finger travel, with a dead zone
                        // (ScoreLayout.dropTarget), so a slip while tapping the selected note does not move it.
                        dragDrop = l.dropTarget(note, fromString, (last.x - down.position.x) / factorNow(), (last.y - down.position.y) / factorNow())
                            ?.let { ScoreCursor(it.measureId, it.offset, it.string) }
                    }
                }
                if (!dragging) return@awaitEachGesture
                dragAt = null; dragDrop = null
                val target = l.dropTarget(note, fromString, (last.x - down.position.x) / factorNow(), (last.y - down.position.y) / factorNow()) ?: return@awaitEachGesture
                currentOnMove?.invoke(ScoreTap(note.measureId, note.offset, fromString, note.noteId), ScoreTap(target.measureId, target.offset, target.string ?: fromString, null))
            }
        }
        .pointerInput(Unit) {
            // Long-press range tracker: once the long press above started a range, follow the finger bar by bar until it
            // is lifted or the gesture is cancelled. It only reads positions; the tap detector consumes them after the long press.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!range.active) continue
                        val l = currentLayout ?: continue
                        val bar = l.measureNear((change.position.x + offsetX) / factorNow(), (change.position.y + offsetY) / factorNow()) ?: continue
                        if (bar.measureId == range.last) continue
                        range.last = bar.measureId
                        // A light tick per bar change, only when the owner actually moved the range end.
                        if (currentOnRangeExtend?.invoke(bar.measureId) == true) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                } finally {
                    if (range.active) {
                        range.active = false
                        range.last = null
                        currentOnRangeEnd?.invoke()
                    }
                }
            }
        }
        .pointerInput(Unit) {
            // End of a pinch: once the last finger lifts (or the gesture is cancelled), the owner hears the final zoom — one
            // ViewModel update (and one re-wrap of wrapped systems) per pinch instead of one per step (QA 23). Only reads.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do { val event = awaitPointerEvent(PointerEventPass.Final) } while (event.changes.any { it.pressed })
                val z = pendingZoom
                if (!z.isNaN()) { pendingZoom = Float.NaN; currentOnZoom(z) }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val l = layout ?: return@Canvas
            // Read here, not captured from the composition: the frame a pinch step lands in draws offsets and factor together.
            val factor = factorNow()
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.save()
                nc.translate(-offsetX, -offsetY)
                nc.scale(factor, factor)
                val x0 = offsetX / factor; val x1 = (offsetX + size.width) / factor
                // Selection overlay below the notes; without a playhead the cursor is the start marker (▶ cap).
                cursor?.let { c -> drawCursor(nc, l, c, overlay, startMarker = playhead == null) }
                // Drop target while dragging a note (none inside the dead zone: the note stays).
                dragDrop?.let { target -> drawCursor(nc, l, target, overlay) }
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

/** State of a long-press range drag, shared by the tap detector (start) and the range tracker (extend/end); not drawn. */
private class RangeDrag {
    var active = false
    var last: EntityId? = null
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
        // Kept inside the layout: should the column of the first system start less than 12 units below the layout top
        // (small margin), the cap moves down onto the column instead of being cut off at the canvas edge.
        val capBottom = maxOf(top, 12f)
        val cap = paints.path.apply { rewind(); moveTo(x - 4f, capBottom - 12f); lineTo(x + 6f, capBottom - 6f); lineTo(x - 4f, capBottom); close() }
        nc.drawPath(cap, paints.marker)
    }
}

fun pitchName(midi: Int): String = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[((midi % 12) + 12) % 12]
