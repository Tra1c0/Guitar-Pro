package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 29, Gerätebefund „Drum-Raster: Schlag 4 hat nur ein Feld“: the cells of the step grid as a pure function. Every bar
 * gets exactly one cell per grid step up to its barline (beats × subdivision; x/8 accordingly), at exact positions, for
 * every grid and meter, also across meter changes and in pickups; beat starts that are no grid multiple get a cell too.
 */
class Qa29DrumGridTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("g-${sequence++}") }
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private val triplet = listOf(TupletRatio(3, 2))
    private val grids = listOf(WrittenDuration(8) to 8, WrittenDuration(16) to 16, WrittenDuration(32) to 32,
        WrittenDuration(8, tuplets = triplet) to 12, WrittenDuration(16, tuplets = triplet) to 24)
    private fun measure(n: Int, d: Int) = Measure(ids.next(), TimeSignature(n, d))

    @Test fun everyBarHasOneCellPerGridStepUpToTheBarline() {
        for ((n, d) in listOf(4 to 4, 3 to 4, 5 to 4, 2 to 2, 6 to 8, 7 to 8, 12 to 8, 5 to 8, 9 to 8)) for ((entry, perWhole) in grids) {
            val m = measure(n, d)
            val cells = DrumGrid.cells(m, DrumGrid.step(entry))
            val length = f(n.toLong(), d.toLong())
            val steps = length * f(perWhole.toLong(), 1)
            val gridCells = cells.filter { (it.offset * f(perWhole.toLong(), 1)).denominator == 1L }
            assertEquals("$n/$d grid $perWhole: grid cells", ((steps.numerator + steps.denominator - 1) / steps.denominator).toInt(), gridCells.size)
            assertEquals("$n/$d grid $perWhole: positions", List(gridCells.size) { f(it.toLong(), perWhole.toLong()) }, gridCells.map { it.offset })
            assertTrue("inside the bar", cells.all { it.offset >= Fraction.ZERO && it.offset < length })
            assertEquals("sorted, indexed", cells.indices.toList(), cells.map { it.index })
            assertEquals(cells.map { it.offset }.sorted(), cells.map { it.offset })
            // Every beat start has a cell and is marked as one.
            val unit = DrumGrid.beatUnit(m.signature)
            var o = Fraction.ZERO; var beat = 0
            while (o < length) { val c = cells.single { it.offset == o }; assertTrue(c.beatStart); assertEquals(beat, c.beat); o += unit; beat++ }
        }
    }

    @Test fun theLastBeatOf44IsComplete() {
        // The device finding in numbers: beats 1–3 full, beat 4 had one cell. Now 2/3/4/8/6 cells per beat for every grid.
        for ((entry, perWhole) in grids) {
            val cells = DrumGrid.cells(measure(4, 4), DrumGrid.step(entry))
            assertEquals(perWhole, cells.size)
            assertEquals(List(4) { perWhole / 4 }, cells.groupBy { it.beat }.values.map { it.size })
            assertEquals(f((perWhole - 1).toLong(), perWhole.toLong()), cells.last().offset)
        }
    }

    @Test fun compoundMetersCountDottedBeatsAndTripletGridsKeepTheBeatStarts() {
        assertEquals(f(3, 8), DrumGrid.beatUnit(TimeSignature(6, 8)))
        assertEquals(f(3, 8), DrumGrid.beatUnit(TimeSignature(12, 8)))
        assertEquals(f(1, 8), DrumGrid.beatUnit(TimeSignature(7, 8)))
        assertEquals(f(1, 8), DrumGrid.beatUnit(TimeSignature(3, 8)))
        // 6/8 with 1/8 T: 9 grid cells (0 … 8/12) plus the second beat at 3/8.
        val cells = DrumGrid.cells(measure(6, 8), DrumGrid.step(WrittenDuration(8, tuplets = triplet)))
        assertEquals(10, cells.size)
        assertEquals(listOf(0, 1), cells.filter { it.beatStart }.map { it.beat })
        assertTrue(cells.any { it.offset == f(3, 8) && it.beatStart })
        // Dots of the entry value do not change the grid.
        assertEquals(DrumGrid.step(WrittenDuration(16)), DrumGrid.step(WrittenDuration(16, 1)))
    }

    @Test fun pickupBarsEndAtTheirLength() {
        val pickup = Measure(ids.next(), TimeSignature(4, 4), pickupLength = f(1, 4))
        assertEquals(listOf(f(0, 1), f(1, 8)), DrumGrid.cells(pickup, f(1, 8)).map { it.offset })
    }

    @Test fun barsCarryHeaderChangesAndLocateFindsTheSoundingCell() {
        val e = EditorSession(ProjectFactory(ids).create("Grid"), ids)
        e.dispatch(EditorCommand.InsertMeasures(1, 3))
        val ms = e.state.value.project.measures
        e.dispatch(EditorCommand.SetSignature(ms[2].id, TimeSignature(3, 4)))
        e.dispatch(EditorCommand.SetTempoAt(ms[3].id, 140.0))
        val bars = DrumGrid.bars(e.state.value.project, f(1, 16))
        assertEquals(listOf(16, 16, 12, 16), bars.map { it.cells.size })
        assertEquals(listOf(TimeSignature(4, 4), null, TimeSignature(3, 4), TimeSignature(4, 4)), bars.map { it.signature })
        assertEquals(140.0, bars[3].tempo!!, 1e-9); assertNull(bars[1].tempo)
        assertEquals(2 to 5, DrumGrid.locate(bars, ms[2].id, f(5, 16)))
        assertEquals(2 to 5, DrumGrid.locate(bars, ms[2].id, f(11, 32)))
        assertEquals(1 to 0, DrumGrid.locate(bars, ms[1].id, Fraction.ZERO))
        assertNull(DrumGrid.locate(bars, EntityId("missing"), Fraction.ZERO))
    }
}
