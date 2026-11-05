package dev.riffworkbench.notation

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 18 / D5 (view jumps, Bericht 1 §4): the ScoreView scrolling rules ([ViewportScroll], used by ScoreView's cursor and
 * follow effects) over real layouts, in layout units (factor = density·zoom cancels out).
 *
 * The four "current rule" tests were red against the old band rule (scroll when x < 8 % or > 85 %, recenter to 30 %;
 * vertical reset to the system top) and pin the new behaviour: no scroll while the target is visible inside the margin,
 * minimal shift otherwise, paged landing at 30 % for far targets, vertical rule by cursor row.
 *
 * Viewport: 411-dp phone, TabCard horizontal padding 12 dp and canvas padding 4 dp (EditorScreen.kt TabCard) → 379 dp;
 * 375 layout units at zoom 1 are used here. Height varies with the device; 260 units is used where it matters.
 */
class ViewportScrollTest {
    private var n = 0
    private val ids = IdSource { EntityId("d5l-${n++}") }
    private val W = 375f
    private val H = 260f
    /** Half width of the cursor marker: rounded rect ±0.62·string spacing (ScoreView.kt:240), 24-unit TAB lines. */
    private val HALF = 15f
    /** "Small edge zone" of the proposed rule (Bericht 1 §4): about one 16th column. */
    private val MARGIN = 24f

    private fun project(bars: Int, extraNotes: Boolean = true): Project {
        val session = EditorSession(ProjectFactory(ids).demo("QA18 D5"), ids)
        repeat(bars - 2) { session.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        session.state.value.project.measures.forEachIndexed { i, m ->
            session.select(session.state.value.selection.copy(measureId = m.id, offset = Fraction.ZERO, string = 0))
            session.dispatch(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(8)))
            if (extraNotes && i % 2 == 0) {
                session.select(session.state.value.selection.copy(measureId = m.id, offset = Fraction.of(1, 2), string = 2))
                session.dispatch(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
            }
        }
        return session.state.value.project
    }
    /** Mirror of WorkspaceViewModel.relayout() for the default view: TAB only, 16th entry grid, unwrapped unless [width]. */
    private fun layout(p: Project, standard: Boolean = false, width: Float? = null) =
        ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT.copy(showTab = true, showStandard = standard), width, Fraction.of(1, 16), 0, null))

    // ---------------------------------------------------------------- the rules
    private fun clampX(l: ScoreLayout, offX: Float) = offX.coerceIn(0f, max(0f, l.width - W))
    /** The rule ScoreView applies to the edit cursor. */
    private fun currentRule(l: ScoreLayout, x: Float, offX: Float): Float = ViewportScroll.horizontal(x, HALF, offX, W, l.width, MARGIN)
    /** Minimal shift only (no paging). */
    private fun minimalRule(l: ScoreLayout, x: Float, offX: Float): Float = ViewportScroll.minimal(x, HALF, offX, W, l.width, MARGIN)
    /** Minimal shift with paged landing at 30 % for far targets (bar map, PageUp/Down). */
    private fun pagedRule(l: ScoreLayout, x: Float, offX: Float): Float = ViewportScroll.horizontal(x, HALF, offX, W, l.width, MARGIN)
    private fun fullyVisible(x: Float, offX: Float) = x - HALF >= offX && x + HALF <= offX + W
    private fun visibleWithinMargin(x: Float, offX: Float) = x - HALF >= offX + MARGIN && x + HALF <= offX + W - MARGIN

    private data class Step(val bar: Int, val offset: Fraction, val x: Float, val offBefore: Float, val offAfter: Float, val visible: Boolean, val visibleStrict: Boolean) {
        val shift get() = offAfter - offBefore
    }
    /** Walks the cursor over every grid column (what move(±1) with a 16th entry duration does) and applies [rule] after each step. */
    private fun walk(l: ScoreLayout, columns: List<Column>, startOff: Float, rule: (ScoreLayout, Float, Float) -> Float): List<Step> {
        var off = startOff
        return columns.map { c ->
            val before = off
            off = rule(l, c.x, off)
            Step(c.measureIndex + 1, c.offset, c.x, before, off, fullyVisible(c.x, before), visibleWithinMargin(c.x, before))
        }
    }
    private fun report(name: String, steps: List<Step>) {
        val scrolls = steps.filter { it.shift != 0f }
        val unjustified = scrolls.filter { it.visible }
        val unjustifiedStrict = scrolls.filter { it.visibleStrict }
        val pct = { v: Float -> "${(v / W * 100).toInt()} %" }
        println("== $name: ${steps.size} cursor steps, ${scrolls.size} scrolls, ${unjustified.size} with the target fully visible (${unjustifiedStrict.size} even inside the ${MARGIN.toInt()}-unit margin)")
        if (scrolls.isNotEmpty()) println("   shifts: min ${pct(scrolls.minOf { abs(it.shift) })}, median ${pct(scrolls.map { abs(it.shift) }.sorted()[scrolls.size / 2])}, max ${pct(scrolls.maxOf { abs(it.shift) })} of the viewport width")
        unjustified.take(4).forEach { println("   e.g. bar ${it.bar} offset ${it.offset}: x=${it.x.toInt()} was at ${((it.x - it.offBefore) / W * 100).toInt()} % of the viewport → scrolled by ${pct(it.shift)}") }
    }

    // ---------------------------------------------------------------- horizontal, forward (next beat / auto-advance)
    @Test fun cursorRuleDoesNotScrollWhileTheNextColumnIsVisible_forward() {
        val l = layout(project(8))
        val cols = l.columns.sortedBy { it.x }
        println("layout width ${l.width.toInt()} units; ${cols.size} grid columns; bar 1 is ${l.measures[0].width.toInt()} units wide; viewport $W")
        val steps = walk(l, cols, 0f, ::currentRule)
        report("current rule, forward", steps)
        val unjustified = steps.filter { it.shift != 0f && it.visibleStrict }
        assertTrue("current rule must scroll at least once (sanity)", steps.any { it.shift != 0f })
        assertEquals("Bericht 1 §4: moving the cursor to a column that is fully visible must not scroll (found ${unjustified.size} such scrolls)", 0, unjustified.size)
    }

    @Test fun cursorRuleDoesNotScrollWhileThePreviousColumnIsVisible_backward() {
        val l = layout(project(8))
        val cols = l.columns.sortedByDescending { it.x }
        val steps = walk(l, cols, clampX(l, cols.first().x - W * 0.3f), ::currentRule)
        report("current rule, backward", steps)
        val unjustified = steps.filter { it.shift != 0f && it.visibleStrict }
        assertEquals("moving one beat left while the target column is fully visible must not scroll (found ${unjustified.size})", 0, unjustified.size)
    }

    /** The old band rule put a visible target at 86 % back to 30 %; the rightmost fully visible column must not scroll at all. */
    @Test fun aVisibleTargetAtTheRightEdgeDoesNotScroll() {
        val l = layout(project(8))
        val off = 0f
        val target = l.columns.filter { visibleWithinMargin(it.x, off) }.maxByOrNull { it.x }!!   // the rightmost column visible inside the margin
        val after = currentRule(l, target.x, off)
        println("rightmost visible column at ${((target.x - off) / W * 100).toInt()} % → current rule scrolls by ${((after - off) / W * 100).toInt()} % of the viewport")
        assertEquals("a visible target must not scroll", off, after, 0.01f)
    }

    // ---------------------------------------------------------------- the proposal, same walks
    @Test fun minimalShiftRuleNeverScrollsWhileTheTargetIsInsideTheMargin() {
        val l = layout(project(8))
        val fwd = walk(l, l.columns.sortedBy { it.x }, 0f, ::minimalRule)
        report("minimal-shift rule, forward", fwd)
        val back = walk(l, l.columns.sortedByDescending { it.x }, clampX(l, l.width), ::minimalRule)
        report("minimal-shift rule, backward", back)
        for (s in fwd + back) {
            if (s.shift != 0f) assertFalse("no scroll while the target is inside the margin", s.visibleStrict)
            assertTrue("shift stays small: ${s.shift}", abs(s.shift) <= 2 * MARGIN + 2 * HALF + 40f)
            assertTrue("target fully visible after the shift", fullyVisible(s.x, s.offAfter) || s.offAfter == 0f || s.offAfter == clampX(l, l.width))
        }
        // The cursor keeps a stable position near the edge instead of jumping back to 30 %: each shift is at most the
        // distance to the next column (one 16th = 30 units; across a barline with a time signature up to ~85 units).
        val xs = l.columns.map { it.x }.sorted()
        val maxGap = xs.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0f
        val scrolls = fwd.filter { it.shift != 0f }
        assertTrue(scrolls.isNotEmpty())
        println("   largest forward shift ${scrolls.maxOf { it.shift }.toInt()} units vs largest column gap ${maxGap.toInt()} units")
        assertTrue("forward shifts are at most one column step (${scrolls.maxOf { it.shift }} units, gap $maxGap)", scrolls.maxOf { it.shift } <= maxGap + 0.5f)
    }

    /** Bar-map tap / PageDown from bar 1 to bar 7: the paged variant lands the far target at 30 %, otherwise minimal. */
    @Test fun pagedRuleForFarTargets() {
        val l = layout(project(8))
        val far = l.columns.first { it.measureIndex == 6 && it.offset == Fraction.ZERO }
        val after = pagedRule(l, far.x, 0f)
        println("bar 7 from the top: minimal would put it at the right edge; paged lands it at ${((far.x - after) / W * 100).toInt()} %")
        assertTrue(fullyVisible(far.x, after))
        val near = l.columns.sortedBy { it.x }.first { !fullyVisible(it.x, 0f) }
        assertEquals("near target: paged == minimal", minimalRule(l, near.x, 0f), pagedRule(l, near.x, 0f), 0.01f)
    }

    // ---------------------------------------------------------------- relayout (edit, duration change) while the cursor is visible
    /** The LaunchedEffect also runs on every new layout (key `layout`): re-applying the rule with the same cursor x must be a no-op. */
    @Test fun aRelayoutWithTheCursorVisibleAtTheRightEdgeDoesNotScroll() {
        val l = layout(project(8))
        // The user panned so the cursor (bar 2, beat 1) is at ~88 %: visible inside the margin.
        val cursor = l.columns.first { it.measureIndex == 1 && it.offset == Fraction.ZERO }
        val off = clampX(l, cursor.x - W * 0.88f)
        assertTrue(visibleWithinMargin(cursor.x, off))
        // Same cursor x, new layout object (e.g. SetDuration on the selected beat changes nothing at the cursor):
        val l2 = layout(project(8))
        assertEquals(cursor.x, l2.columns.first { it.measureIndex == 1 && it.offset == Fraction.ZERO }.x, 0.01f)
        val after = currentRule(l2, cursor.x, off)
        println("relayout with the cursor at 90 %: view moves by ${((after - off) / W * 100).toInt()} % of the viewport")
        assertEquals("an edit that does not move the cursor must not move the view", off, after, 0.01f)
    }

    // ---------------------------------------------------------------- vertical rule
    /** Default project, TAB + standard notation, wrapped to the phone width: one system is taller than the canvas. */
    @Test fun verticalRuleResetsTheScrollOnEveryCursorChangeWhenTheSystemIsTallerThanTheViewport() {
        val p = project(2, extraNotes = false)
        val tabOnly = layout(p)
        val both = layout(p, standard = true, width = W)
        val s0 = tabOnly.systems[0]; val s1 = both.systems[0]
        println("default project, EDIT: TAB-only system ${s0.top.toInt()}..${s0.bottom.toInt()} (height ${(s0.bottom - s0.top).toInt()}, first string at y=${s0.stringY.last().toInt()}, layout height ${tabOnly.height.toInt()}); " +
            "TAB+standard system height ${(s1.bottom - s1.top).toInt()}, layout height ${both.height.toInt()} units (viewport height assumed $H)")
        val sys = if (s1.bottom - s1.top > H) s1 else s0
        assertTrue("fixture: a system taller than the viewport", sys.bottom - sys.top > H)
        // The user scrolled down so the lowest string (and the rhythm lane) is visible; the cursor row (string 0) is fully visible.
        val offY = sys.stringY[0] + 40f - H
        assertTrue(sys.stringY[0] in offY..(offY + H))
        // The vertical rule looks at the cursor's string row, not at the whole system:
        val layoutHeight = if (sys === s1) both.height else tabOnly.height
        val next = ViewportScroll.vertical(sys.stringY[0], 12f, offY, H, layoutHeight)
        println("user offsetY=${offY.toInt()} with the cursor row visible → rule keeps offsetY=${next.toInt()} (shift ${(offY - next).toInt()} units)")
        assertEquals("a visible cursor row must not reset the vertical scroll", offY, next, 0.01f)
        // A row above the viewport (the standard staff of the same system) scrolls up by the minimum, not to the system top.
        val topRow = sys.staffTop
        val up = ViewportScroll.vertical(topRow, 12f, offY, H, layoutHeight)
        assertEquals(topRow - 12f - MARGIN, up, 0.01f)
        assertTrue(topRow - 12f >= up && topRow + 12f <= up + H)
    }

    // ---------------------------------------------------------------- playback follow through the same object
    @Test fun followRuleKeepsTheBandAndTheResumeUsesTheMinimalShift() {
        val l = layout(project(8))
        val off = 0f
        assertEquals("inside the band: no scroll", off, ViewportScroll.follow(W * 0.5f, HALF, off, W, l.width), 0.01f)
        val far = W * 0.95f
        assertEquals("past 75 %: the playhead is re-placed at 25 %", far - W * 0.25f, ViewportScroll.follow(far, HALF, off, W, l.width), 0.01f)
        assertEquals("first scroll after resuming: minimal shift, not the 25 % jump", far + HALF + MARGIN - W, ViewportScroll.follow(far, HALF, off, W, l.width, minimalFirst = true), 0.01f)
        assertEquals("clamped to the content", max(0f, l.width - W), ViewportScroll.follow(l.width, HALF, off, W, l.width), 0.01f)
        assertEquals("empty viewport: unchanged", off, ViewportScroll.follow(far, HALF, off, 0f, l.width), 0.01f)
    }
}
