package dev.riffworkbench.notation

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 21 (Bedienung): the cursor effect of the score viewport, pulled out of ScoreCanvas as [ViewportScroll.cursor].
 *
 *  C1  it is the rule ScoreCanvas applied before (minimal shift / paging per [ViewportScroll.horizontal], row rule per
 *      [ViewportScroll.vertical], clamped; unchanged key → no move), checked step by step over a fast-entry session;
 *  C2  fast TAB entry on a 360-dp phone canvas: while the cursor stays inside the view the view does not move, and after
 *      every step the cursor is visible;
 *  C3  a pinch away from the cursor keeps the content under the fingers (red before the fix: every zoom step of the pinch
 *      pulled the view back to the cursor), and the next cursor move scrolls as usual again.
 *
 * Pixels = layout units × factor, density 1 (dp); 328 × 118 is the TabCard canvas of a 360 × 640 phone (QA journal cycle 21).
 */
class Qa21ViewportCursorTest {
    private var n = 0
    private val ids = IdSource { EntityId("q21v-${n++}") }
    private val w = 328f
    private val h = 118f

    private fun layout(p: Project, grid: Fraction = Fraction.of(1, 16)) =
        ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT.copy(showTab = true, showStandard = false), null, grid, 0, null, margin = 6f))

    /** The inline rule of ScoreCanvas before the extraction (QA 18 / D5), as reference for C1. */
    private fun reference(l: ScoreLayout, s: Selection, ox: Float, oy: Float, factor: Float, last: List<Any?>?): Triple<List<Any?>, Float, Float>? {
        val lx = l.xFor(s.measureId, s.offset) ?: return null
        val sys = l.measures.firstOrNull { it.measureId == s.measureId }?.let { m -> l.systems.getOrNull(m.system) }
        val spacing = sys?.let { if (it.stringY.size > 1) abs(it.stringY[0] - it.stringY[1]) else 20f } ?: 20f
        val rowY = sys?.let { st -> st.stringY.getOrNull(s.string) ?: (st.tabTop + st.tabBottom) / 2 }
        val key = listOf(lx, rowY, factor, w, h)
        if (key == last) return Triple(key, ox, oy)
        var x = ViewportScroll.horizontal(lx * factor, spacing * 0.62f * factor, ox, w, l.width * factor, ViewportScroll.MARGIN)
        var y = oy
        if (rowY != null) y = ViewportScroll.vertical(rowY * factor, spacing * factor, oy, h, l.height * factor, ViewportScroll.MARGIN)
        x = ViewportScroll.clamp(x, l.width * factor, w); y = ViewportScroll.clamp(y, l.height * factor, h)
        return Triple(key, x, y)
    }

    private class View { var x = 0f; var y = 0f; var key: ViewportScroll.CursorKey? = null; var refKey: List<Any?>? = null }

    /** Fast entry like the fret pad: [count] notes of [duration] with auto-advance, string changes every third note. */
    private fun session(): EditorSession = EditorSession(ProjectFactory(ids).create("QA21"), ids)
    private fun enter(s: EditorSession, fret: Int, duration: WrittenDuration) {
        s.dispatch(EditorCommand.PutNote(fret = fret, duration = duration))
        val e = s.state.value; val sel = e.selection; val p = e.project
        val i = p.measures.indexOfFirst { it.id == sel.measureId }
        val next = sel.offset + duration.length()
        if (next >= p.measures[i].signature.length()) {
            if (i == p.measures.lastIndex) s.dispatch(EditorCommand.AddMeasure(p.measures[i].signature), sameUndoStep = true)
            s.select(sel.copy(measureId = s.state.value.project.measures[i + 1].id, offset = Fraction.ZERO))
        } else s.select(sel.copy(offset = next))
    }

    @Test fun c1_c2_fastEntryKeepsTheViewWhileTheCursorIsVisible() {
        val s = session()
        val v = View()
        val log = mutableListOf<String>()
        var moves = 0
        val durations = listOf(WrittenDuration(4), WrittenDuration(8), WrittenDuration(16))
        for (k in 0 until 40) {
            val d = durations[(k / 8) % durations.size]
            if (k % 3 == 2) s.select(s.state.value.selection.copy(string = (s.state.value.selection.string + 1) % 6))
            enter(s, k % 13, d)
            val l = layout(s.state.value.project, d.length())
            val sel = s.state.value.selection
            val before = v.x to v.y
            val r = ViewportScroll.cursor(l, sel.measureId, sel.offset, sel.string, v.x, v.y, w, h, 1f, 1f, v.key)!!
            val ref = reference(l, sel, v.x, v.y, 1f, v.refKey)!!
            assertEquals("C1 x step $k", ref.second, r.offsetX, 0.001f)
            assertEquals("C1 y step $k", ref.third, r.offsetY, 0.001f)
            v.key = r.key; v.refKey = ref.first; v.x = r.offsetX; v.y = r.offsetY
            val cx = l.xFor(sel.measureId, sel.offset)!!
            val half = 0.62f * 24f
            val m = ViewportScroll.MARGIN
            if (cx - half >= before.first + m && cx + half <= before.first + w - m) assertEquals("C2 view moved with the cursor inside it, step $k", before.first, v.x, 0.001f)
            assertTrue("C2 cursor visible after step $k", cx >= v.x && cx <= v.x + w)
            if (v.x != before.first) moves++
            log += "step $k bar ${s.state.value.project.measures.indexOfFirst { it.id == sel.measureId } + 1} ${sel.offset} x=$cx view=${v.x}"
        }
        // The view does follow the entry (40 notes span several viewports); C2 above checks it only moves when it must.
        assertTrue("C2 the view followed the entry: $moves moves\n" + log.joinToString("\n"), moves >= 3)
    }

    @Test fun c3_pinchAwayFromTheCursorKeepsTheFocusPoint() {
        val s = session()
        repeat(12) { enter(s, it, WrittenDuration(8)) }
        // Cursor back to bar 1, beat 1; the view at the start.
        s.select(s.state.value.selection.copy(measureId = s.state.value.project.measures.first().id, offset = Fraction.ZERO, string = 0))
        val l = layout(s.state.value.project, Fraction.of(1, 8))
        val sel = s.state.value.selection
        var r = ViewportScroll.cursor(l, sel.measureId, sel.offset, sel.string, 0f, 0f, w, h, 1f, 1f, null)!!
        assertEquals(0f, r.offsetX, 0.001f)
        // Pinch with the fingers at the right edge, zooming in step by step as detectTransformGestures reports it.
        var zoom = 1f; var x = r.offsetX; var y = r.offsetY; var key = r.key
        val focusX = w * 0.9f; val focusY = h * 0.5f
        repeat(6) {
            val next = zoom * 1.15f
            x = (x + focusX) * next / zoom - focusX; y = (y + focusY) * next / zoom - focusY
            zoom = next
            val contentX = (x + focusX) / zoom                                  // layout x under the fingers
            r = ViewportScroll.cursor(l, sel.measureId, sel.offset, sel.string, x, y, w, h, zoom, 1f, key, zoomedByGesture = true)!!
            assertEquals("C3 pinch step $it: the view stays where the fingers put it", x, r.offsetX, 0.001f)
            assertEquals("C3 content under the fingers", contentX, (r.offsetX + focusX) / zoom, 0.01f)
            key = r.key; x = r.offsetX; y = r.offsetY
        }
        val cursorX = l.xFor(sel.measureId, sel.offset)!! * zoom
        assertTrue("precondition: after the pinch the cursor is outside the view ($cursorX vs $x)", cursorX < x)
        // The same zoom set elsewhere (layout page, not the fingers) still brings the cursor into view.
        val other = ViewportScroll.cursor(l, sel.measureId, sel.offset, sel.string, x, y, w, h, zoom * 1.1f, 1f, key, zoomedByGesture = false)!!
        assertTrue("a non-gesture zoom keeps the cursor visible", l.xFor(sel.measureId, sel.offset)!! * zoom * 1.1f in other.offsetX..(other.offsetX + w))
        // The next cursor move after the pinch scrolls as usual.
        val moved = sel.copy(offset = Fraction.of(1, 8))
        val after = ViewportScroll.cursor(l, moved.measureId, moved.offset, moved.string, x, y, w, h, zoom, 1f, key, zoomedByGesture = true)!!
        val mx = l.xFor(moved.measureId, moved.offset)!! * zoom
        assertTrue("cursor move after the pinch: cursor visible again", mx >= after.offsetX && mx <= after.offsetX + w)
    }

    @Test fun c1_unchangedKeyKeepsAManualPan() {
        val s = session()
        repeat(20) { enter(s, 3, WrittenDuration(8)) }
        val l = layout(s.state.value.project, Fraction.of(1, 8))
        val sel = s.state.value.selection
        val r = ViewportScroll.cursor(l, sel.measureId, sel.offset, sel.string, 0f, 0f, w, h, 1f, 1f, null)!!
        // The user pans away; a relayout with the cursor in the same place (same key) must not pull the view back.
        val panned = ViewportScroll.cursor(l, sel.measureId, sel.offset, sel.string, 0f, 0f, w, h, 1f, 1f, r.key)!!
        assertEquals(0f, panned.offsetX, 0.001f)
        assertNull("no cursor position: nothing to do", ViewportScroll.cursor(l, EntityId("missing"), Fraction.ZERO, 0, 0f, 0f, w, h, 1f, 1f, null))
    }
}
