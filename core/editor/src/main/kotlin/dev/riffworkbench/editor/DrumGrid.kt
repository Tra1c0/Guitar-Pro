package dev.riffworkbench.editor

import dev.riffworkbench.model.*

/**
 * Cells of the drum step grid (Zyklus 29), pure so the count and the positions are testable on the JVM. One continuous
 * grid over all bars: every bar has one cell per grid step from its downbeat to its barline (length = pickup or meter),
 * plus a cell on every beat start that is no grid multiple (a triplet grid in 6/8), so each bar is complete and every
 * position is exact. The step is the plain value of the entry duration (dots ignored, tuplets kept: 1/8 T = 1/12).
 */
object DrumGrid {
    /** One cell: [offset] inside the bar, 0-based [beat] count and whether it starts that beat (ruler label, wider gap). */
    data class Cell(val index: Int, val offset: Fraction, val beat: Int, val beatStart: Boolean)

    /** One bar of the grid; [signature]/[tempo] are set where the header shows a change (bar 1 always shows its meter). */
    data class Bar(val index: Int, val measureId: EntityId, val length: Fraction, val cells: List<Cell>, val signature: TimeSignature?, val tempo: Double?)

    fun step(entry: WrittenDuration): Fraction = entry.copy(dots = 0).length()

    /** Counting unit: compound x/8 and x/16 meters (6, 9, 12, …) count dotted beats, all others the denominator. */
    fun beatUnit(signature: TimeSignature): Fraction {
        val compound = signature.denominator >= 8 && signature.numerator > 3 && signature.numerator % 3 == 0
        return Fraction.of(if (compound) 3L else 1L, signature.denominator.toLong())
    }

    fun cells(measure: Measure, step: Fraction): List<Cell> {
        require(step > Fraction.ZERO) { "Raster ungültig" }
        val length = NoteEdits.capacity(measure)
        val unit = beatUnit(measure.signature)
        val offsets = sortedSetOf<Fraction>()
        var o = Fraction.ZERO
        var guard = 0
        while (o < length && guard++ < 4096) { offsets += o; o += step }
        o = Fraction.ZERO; guard = 0
        while (o < length && guard++ < 4096) { offsets += o; o += unit }
        return offsets.mapIndexed { i, offset ->
            val beats = offset * Fraction.of(unit.denominator, unit.numerator)
            Cell(i, offset, (beats.numerator / beats.denominator).toInt(), beats.denominator == 1L)
        }
    }

    fun bars(p: Project, step: Fraction): List<Bar> = p.measures.mapIndexed { i, m ->
        val previous = p.measures.getOrNull(i - 1)
        val tempo = p.tempo.lastOrNull { it.position.measureId == m.id && it.position.offset == Fraction.ZERO }?.quarterNotesPerMinute
        Bar(i, m.id, NoteEdits.capacity(m), cells(m, step), m.signature.takeIf { previous == null || previous.signature != it || previous.pickupLength != m.pickupLength }, tempo)
    }

    /** (bar index, cell index) of the cell that sounds at [offset] of [measureId]: the last cell starting at or before it. */
    fun locate(bars: List<Bar>, measureId: EntityId, offset: Fraction): Pair<Int, Int>? {
        val bar = bars.firstOrNull { it.measureId == measureId } ?: return null
        val cell = bar.cells.lastOrNull { it.offset <= offset } ?: return null
        return bar.index to cell.index
    }

    /** Like [locate] for a playhead offset in whole notes as a double (the transport's position). */
    fun locate(bars: List<Bar>, measureId: EntityId, offset: Double): Pair<Int, Int>? {
        val bar = bars.firstOrNull { it.measureId == measureId } ?: return null
        val cell = bar.cells.lastOrNull { it.offset.toDouble() <= offset + 1e-9 } ?: return null
        return bar.index to cell.index
    }
}
