package dev.riffworkbench.notation

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 23 (Gerätebefund „wenn du in die Partitur zoomst und Play drückst, flackert sie“): the playback follow of the score
 * viewport ([ViewportScroll.playhead], the step ScoreView's follow effect runs on every transport poll, ~30 Hz).
 *
 *  F1  zoomed in, the band the playhead follows (TAB ± 12 units) is taller than the canvas minus both edge margins. The
 *      vertical minimal rule then jumped between "band top inside the margin" and "band bottom inside the margin" on every
 *      poll — two offsets ~200 px apart, alternating at the poll rate (red before the fix in [ViewportScroll.minimal]).
 *      Now consecutive playhead updates in the same system keep one vertical offset, at every zoom of the pinch range.
 *  F2  the horizontal follow never alternates either: over a bar walked in 1/96 steps the offset changes monotonically.
 *  F3  the minimal rule is unchanged while the marker fits (C1 of Qa21ViewportCursorTest covers the cursor), and for a
 *      marker taller than the viewport it is a projection: applying it twice gives the same offset.
 *
 * Pixels = layout units × factor, density 1 (dp). Canvases: 328 × 118 (TabCard of a 360 × 640 phone, QA journal cycle 21)
 * and 379 × 290 (411 × 891 phone).
 */
class Qa23FollowZoomTest {
    private var n = 0
    private val ids = IdSource { EntityId("q23f-${n++}") }

    private fun project(bars: Int): Project {
        val s = EditorSession(ProjectFactory(ids).create("QA23"), ids)
        repeat(bars - 1) { s.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        s.state.value.project.measures.forEach { m ->
            for (k in 0 until 4) {
                s.select(s.state.value.selection.copy(measureId = m.id, offset = Fraction.of(k.toLong(), 4), string = k % 6))
                s.dispatch(EditorCommand.PutNote(fret = k + 2, duration = WrittenDuration(4)))
            }
        }
        return s.state.value.project
    }
    private fun layout(p: Project) = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT.copy(showTab = true, showStandard = false), null, Fraction.of(1, 16), 0, null, margin = 6f))

    private data class Run(val xs: List<Float>, val ys: List<Float>)
    /** Playback over every bar in 1/96 steps from the view at (0, 0), as the follow effect applies it poll after poll. */
    private fun play(l: ScoreLayout, p: Project, w: Float, h: Float, zoom: Float): Run {
        var x = 0f; var y = 0f
        val xs = mutableListOf<Float>(); val ys = mutableListOf<Float>()
        for (m in p.measures) for (k in 0 until 96) {
            val r = ViewportScroll.playhead(l, m.id, k / 96.0, x, y, w, h, zoom, 1f)!!
            x = r.offsetX; y = r.offsetY; xs += x; ys += y
        }
        return Run(xs, ys)
    }
    private fun alternations(v: List<Float>): Int = (2 until v.size).count { i -> v[i] == v[i - 2] && v[i] != v[i - 1] }

    @Test fun f1_zoomedFollowKeepsOneVerticalOffset() {
        val p = project(6)
        val l = layout(p)
        val sys = l.systems.single()
        val report = mutableListOf<String>()
        var degenerate = 0
        for ((w, h) in listOf(328f to 118f, 379f to 290f)) for (zoom in listOf(1f, 1.25f, 1.5f, 2f, 2.5f)) {
            val band = (sys.tabBottom - sys.tabTop + 24f) * zoom
            if (band + 2 * minOf(ViewportScroll.MARGIN, h / 4) > h) degenerate++
            val run = play(l, p, w, h, zoom)
            val distinct = run.ys.distinct()
            report += "canvas ${w.toInt()}×${h.toInt()} zoom $zoom: band ${band.toInt()} px, y offsets ${distinct.map { it.toInt() }.take(6)}, ${alternations(run.ys)} alternations"
            assertEquals("F1 vertical offset alternates between polls\n" + report.joinToString("\n"), 0, alternations(run.ys))
            // One system: after the first poll the vertical offset never changes again.
            assertEquals("F1 one vertical offset per system\n" + report.joinToString("\n"), 1, run.ys.drop(1).distinct().size)
            // The band the playhead runs in stays (partly) visible: its top or the TAB rows below it are on screen.
            val y = run.ys.last()
            assertTrue("F1 TAB band visible at zoom $zoom", sys.tabTop * zoom < y + h && sys.tabBottom * zoom > y)
        }
        println("QA23 follow:\n  " + report.joinToString("\n  "))
        assertTrue("precondition: some cases have a band taller than the canvas minus the margins", degenerate >= 3)
    }

    @Test fun f2_horizontalFollowNeverAlternates() {
        val p = project(6)
        val l = layout(p)
        for (zoom in listOf(1f, 1.25f, 2f, 2.5f)) {
            val run = play(l, p, 328f, 118f, zoom)
            assertEquals("F2 x alternates at zoom $zoom", 0, alternations(run.xs))
            assertTrue("F2 x only moves forward at zoom $zoom", run.xs.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test fun f3_minimalIsAProjection() {
        // Fits: unchanged rule (inside the margin → stays; outside → minimal shift).
        assertEquals(100f, ViewportScroll.minimal(200f, 10f, 100f, 300f, 1000f, 24f), 0.001f)
        assertEquals(200f - 10f - 24f, ViewportScroll.minimal(200f, 10f, 180f, 300f, 1000f, 24f), 0.001f)
        assertEquals(400f + 10f + 24f - 300f, ViewportScroll.minimal(400f, 10f, 0f, 300f, 1000f, 24f), 0.001f)
        // Taller than the viewport: idempotent from any start, and the viewport lies inside the marker (± margin).
        for (start in listOf(0f, 150f, 300f, 450f, 700f)) {
            val once = ViewportScroll.minimal(400f, 200f, start, 300f, 1000f, 24f)
            val twice = ViewportScroll.minimal(400f, 200f, once, 300f, 1000f, 24f)
            assertEquals("projection from $start", once, twice, 0.001f)
            assertTrue("viewport inside the marker from $start: $once", once >= 400f - 200f - 24f && once + 300f <= 400f + 200f + 24f)
        }
    }
}
