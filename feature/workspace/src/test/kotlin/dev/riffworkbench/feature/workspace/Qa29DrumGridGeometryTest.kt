package dev.riffworkbench.feature.workspace

import dev.riffworkbench.editor.DrumGrid
import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 29 (JVM shim): geometry and follow rule of the continuous drum grid. Cells never overlap and every beat starts
 * after the wider gap; the follow scrolls only when the sounding column leaves the visible part, then minimally (no jump,
 * no paging), and never backwards while the music runs forward.
 */
class Qa29DrumGridGeometryTest {
    private fun bar(n: Int, d: Int, perWhole: Long) = DrumGrid.cells(Measure(EntityId("m$n$d$perWhole"), TimeSignature(n, d)), Fraction.of(1, perWhole))

    @Test fun cellsDoNotOverlapAndBeatsGetTheWiderGap() {
        val cells = bar(4, 4, 16)
        val xs = DrumGridGeometry.cellX(cells)
        assertEquals(DrumGridGeometry.BAR_START, xs[0], 0f)
        for (i in 1 until xs.size) {
            val gap = xs[i] - xs[i - 1] - DrumGridGeometry.CELL
            assertEquals(DrumGridGeometry.GAP + if (cells[i].beatStart) DrumGridGeometry.BEAT_GAP else 0f, gap, 1e-4f)
        }
        assertEquals(xs.last() + DrumGridGeometry.CELL + DrumGridGeometry.BAR_END, DrumGridGeometry.barWidth(cells), 1e-4f)
        assertTrue("touch target ≥ 40 dp", DrumGridGeometry.CELL >= 40f)
    }

    @Test fun followMovesOnlyWhenTheColumnLeavesAndThenMinimally() {
        val bars = (0 until 8).map { i -> DrumGrid.Bar(i, EntityId("b$i"), Fraction.ONE, bar(4, 4, 16), null, null) }
        val starts = DrumGridGeometry.barStarts(bars)
        val content = starts.last()
        val viewport = 640f
        var scroll = 0f
        var jumps = 0
        var moves = 0
        var previous = 0f
        for ((b, bar) in bars.withIndex()) for (x in DrumGridGeometry.cellX(bar.cells)) {
            val column = starts[b] + x
            val next = DrumGridGeometry.follow(column, scroll, viewport, content)
            assertTrue("never backwards", next >= scroll)
            if (next != scroll) moves++
            // A jump: the view moves further than the column did since the last poll.
            if (next - scroll > column - previous + 0.01f) jumps++
            previous = column
            scroll = next
            assertTrue("column visible", column >= scroll && column + DrumGridGeometry.CELL <= scroll + viewport)
        }
        assertEquals("no jumps", 0, jumps)
        assertTrue(moves > 0)
        // Inside the visible part nothing moves.
        assertEquals(100f, DrumGridGeometry.follow(300f, 100f, viewport, content), 0f)
    }
}
