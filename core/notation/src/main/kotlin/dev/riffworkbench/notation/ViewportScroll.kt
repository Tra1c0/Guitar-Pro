package dev.riffworkbench.notation

import dev.riffworkbench.model.EntityId
import dev.riffworkbench.model.Fraction
import kotlin.math.abs
import kotlin.math.max

/**
 * Scroll rules of the score viewport, in whatever unit the caller uses consistently (layout units or pixels).
 * Pure arithmetic so the rules are testable without Compose (QA 18 / D5, Bericht 1 §4):
 *
 *  - the view stands still while the target marker lies inside the viewport minus a [margin];
 *  - when it leaves, the view shifts by the smallest amount that brings the marker back inside the margin;
 *  - a far target (more than one viewport away) is paged instead and lands at 30 % of the viewport;
 *  - results are clamped to the content extent.
 */
object ViewportScroll {
    /** Edge zone in layout units: about one 16th column; multiplied by the density when working in pixels. */
    const val MARGIN = 24f
    /** Where a far target lands, as a fraction of the viewport. */
    const val PAGE_AT = 0.3f
    /** Playback follow: the playhead may run from [FOLLOW_MIN] to [FOLLOW_MAX] of the viewport, then it is put at [FOLLOW_AT]. */
    const val FOLLOW_MIN = 0.1f
    const val FOLLOW_MAX = 0.75f
    const val FOLLOW_AT = 0.25f

    fun clamp(offset: Float, content: Float, viewport: Float): Float = offset.coerceIn(0f, max(0f, content - viewport))

    /**
     * Minimal shift along one axis. [center] and [half] describe the marker (centre and half extent), [offset] the current
     * scroll position, [viewport] its size and [content] the total extent. Returns the new offset (unchanged while the
     * marker is inside the margin).
     */
    fun minimal(center: Float, half: Float, offset: Float, viewport: Float, content: Float, margin: Float = MARGIN): Float {
        if (viewport <= 0f) return offset
        val m = margin.coerceAtMost(viewport / 4f)
        val next = when {
            center - half < offset + m -> center - half - m
            center + half > offset + viewport - m -> center + half + m - viewport
            else -> offset
        }
        return clamp(next, content, viewport)
    }

    /** Horizontal cursor rule: minimal shift, paged (landing at [PAGE_AT]) when the shift would exceed a viewport. */
    fun horizontal(x: Float, half: Float, offset: Float, viewport: Float, content: Float, margin: Float = MARGIN): Float {
        val next = minimal(x, half, offset, viewport, content, margin)
        return if (abs(next - offset) > viewport) clamp(x - viewport * PAGE_AT, content, viewport) else next
    }

    /** Vertical cursor rule: based on the cursor's string row ([rowY] ± [half]), not on the whole system. */
    fun vertical(rowY: Float, half: Float, offset: Float, viewport: Float, content: Float, margin: Float = MARGIN): Float =
        minimal(rowY, half, offset, viewport, content, margin)

    /**
     * What the cursor effect last scrolled for: cursor column [x] and string row [rowY] in layout units, the scale and the
     * viewport size in pixels. An equal key means nothing moved, so the view stays (a relayout that keeps the cursor in place).
     */
    data class CursorKey(val x: Float, val rowY: Float?, val factor: Float, val viewportWidth: Float, val viewportHeight: Float)

    /** Result of [cursor]: the key to remember and the new scroll offsets (pixels). */
    data class CursorScroll(val key: CursorKey, val offsetX: Float, val offsetY: Float)

    /**
     * The edit-cursor rule of the score viewport (ScoreView's cursor effect, QA 21: pulled out of Compose so it is testable
     * on the JVM), in pixels with [factor] = density × zoom. Returns null while the cursor has no position in [layout].
     *
     *  - unchanged [lastKey]: the view stays;
     *  - [zoomedByGesture] and only the factor changed (cursor and viewport as before): the view stays as the pinch put it,
     *    so the content under the fingers keeps its place even when the cursor leaves the view (QA 21: the effect pulled the
     *    view back to the cursor on every zoom step of a pinch away from it);
     *  - otherwise [horizontal] for the column and [vertical] for the string row (both with [MARGIN] × [density]), clamped.
     */
    fun cursor(layout: ScoreLayout, measureId: EntityId, offset: Fraction, string: Int?, offsetX: Float, offsetY: Float,
               viewportWidth: Float, viewportHeight: Float, factor: Float, density: Float, lastKey: CursorKey?, zoomedByGesture: Boolean = false): CursorScroll? {
        val lx = layout.xFor(measureId, offset) ?: return null
        val sys = layout.measures.firstOrNull { it.measureId == measureId }?.let { m -> layout.systems.getOrNull(m.system) }
        val spacing = sys?.let { if (it.stringY.size > 1) abs(it.stringY[0] - it.stringY[1]) else 20f } ?: 20f
        val rowY = sys?.let { s -> string?.let { s.stringY.getOrNull(it) } ?: if (s.stringY.isNotEmpty()) (s.tabTop + s.tabBottom) / 2 else (s.staffTop + s.staffBottom) / 2 }
        val key = CursorKey(lx, rowY, factor, viewportWidth, viewportHeight)
        if (key == lastKey) return CursorScroll(key, offsetX, offsetY)
        if (zoomedByGesture && lastKey != null && lastKey.copy(factor = factor) == key) return CursorScroll(key, offsetX, offsetY)
        val margin = MARGIN * density
        val x = horizontal(lx * factor, spacing * 0.62f * factor, offsetX, viewportWidth, layout.width * factor, margin)
        val y = if (rowY != null) vertical(rowY * factor, spacing * factor, offsetY, viewportHeight, layout.height * factor, margin) else offsetY
        return CursorScroll(key, clamp(x, layout.width * factor, viewportWidth), clamp(y, layout.height * factor, viewportHeight))
    }

    /**
     * Playback follow along x: the playhead runs freely between [FOLLOW_MIN] and [FOLLOW_MAX] of the viewport and is then
     * re-placed at [FOLLOW_AT], so the view moves in large steps while the music runs. [minimalFirst] (first scroll after a
     * suspended follow resumes) uses the minimal rule instead, so the resume does not jump.
     */
    fun follow(x: Float, half: Float, offset: Float, viewport: Float, content: Float, minimalFirst: Boolean = false, margin: Float = MARGIN): Float {
        if (viewport <= 0f) return offset
        if (minimalFirst) return horizontal(x, half, offset, viewport, content, margin)
        return if (x < offset + viewport * FOLLOW_MIN || x > offset + viewport * FOLLOW_MAX) clamp(x - viewport * FOLLOW_AT, content, viewport) else offset
    }
}
