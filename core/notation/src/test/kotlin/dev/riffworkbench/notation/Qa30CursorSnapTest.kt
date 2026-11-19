package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 30 „Cursor selbst schieben“: the snap rule of the positioning strips (drum grid ruler/lane, score number lane) —
 * nearest onset within the radius, else the nearest grid position; across barlines; triplet grids — and the score's
 * number lane as positioning zone (above the first staff line, never on a fret number).
 */
class Qa30CursorSnapTest {
    private val a = EntityId("bar-a"); private val b = EntityId("bar-b")
    private fun t(x: Float, bar: EntityId, n: Long, d: Long) = CursorSnap.Target(x, bar, Fraction.of(n, d))

    @Test fun nearestOnsetInsideTheRadiusElseTheGrid() {
        val grid = (0 until 8).map { t(10f + 50f * it, a, it.toLong(), 8) }
        val onsets = listOf(t(160f, a, 3, 8), t(310f, a, 6, 8))
        // 20 from an onset, 0 from a grid cell: the onset wins inside the radius 24.
        assertEquals(onsets[0], CursorSnap.snap(140f, onsets, grid, 24f))
        // Outside the radius: the nearest grid position.
        assertEquals(grid[2], CursorSnap.snap(115f, onsets, grid, 24f))
        assertEquals(grid[7], CursorSnap.snap(1000f, onsets, grid, 24f))
        // No onsets at all: grid; nothing at all: null.
        assertEquals(grid[0], CursorSnap.snap(-50f, emptyList(), grid, 24f))
        assertNull(CursorSnap.snap(0f, emptyList(), emptyList(), 24f))
    }

    @Test fun acrossTheBarlineAndOnTripletGrids() {
        // Bar a: 1/8 T grid (12 cells), bar b starts at x = 600.
        val grid = (0 until 12).map { t(10f + 48f * it, a, it.toLong(), 12) } + (0 until 12).map { t(600f + 10f + 48f * it, b, it.toLong(), 12) }
        val onsets = listOf(t(538f, a, 11, 12), t(610f, b, 0, 12))
        // Just right of the barline, nearer to bar b's downbeat hit.
        assertEquals(onsets[1], CursorSnap.snap(590f, onsets, grid, 24f))
        // Just left: the last triplet hit of bar a.
        assertEquals(onsets[0], CursorSnap.snap(560f, onsets, grid, 24f))
        // Between, outside both radii, the triplet grid position 5/12 of bar a.
        val g = CursorSnap.snap(255f, onsets, grid, 24f)!!
        assertEquals(a to Fraction.of(5, 12), g.measureId to g.offset)
    }

    @Test fun theScoreNumberLaneIsAboveTheFirstStaffLineAndItsTargetsAreTheSystemsColumns() {
        val guitar = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = List(6) { 0 })
        var n = 0
        fun id() = EntityId("q30s${n++}")
        val m = List(2) { Measure(id(), TimeSignature()) }
        val beats = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(43), 0, 3))), Beat(id(), Fraction.of(1, 2), WrittenDuration(4), listOf(Note(id(), Pitch(45), 0, 5))))
        val track = Track(id(), "G", guitar, listOf(Staff(id(), bars = m.map { mm -> Bar(id(), mm.id, listOf(Voice(id(), 0, if (mm == m[0]) beats else emptyList()))) })))
        val l = ScoreLayouter.layout(LayoutRequest(Project(id = id(), measures = m, tracks = listOf(track)), 0, LayoutStyle.EDIT, editGrid = Fraction.of(1, 8), margin = 6f))
        val sys = l.systems.first()
        assertNotNull("system top is the lane", CursorSnap.laneAt(l, sys.top + 2f))
        assertNull("fret numbers on the top line stay note taps", CursorSnap.laneAt(l, sys.tabTop - 4f))
        assertNull(CursorSnap.laneAt(l, sys.tabTop + 2f))
        // No note box reaches into the lane.
        assertTrue(l.notes.none { CursorSnap.laneAt(l, it.y) != null })
        val (onsets, grid) = CursorSnap.scoreTargets(l, 0)
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 2)), onsets.filter { it.measureId == m[0].id }.map { it.offset })
        assertTrue("grid: the columns of the system (incl. the note columns)", grid.count { it.measureId == m[0].id } >= 2 && onsets.all { o -> grid.any { it.measureId == o.measureId && it.offset == o.offset } })
        assertTrue("only this system", grid.all { g -> l.measures.first { it.measureId == g.measureId }.system == 0 })
        // A drag just beside the second note snaps onto it; far from notes onto the grid.
        val second = onsets.first { it.offset == Fraction.of(1, 2) }
        assertEquals(second, CursorSnap.snap(second.x + 6f, onsets, grid, 12f))
    }
}
