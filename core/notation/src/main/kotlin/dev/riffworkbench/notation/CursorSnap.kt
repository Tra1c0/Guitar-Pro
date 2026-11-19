package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import kotlin.math.abs

/**
 * Zyklus 30 „Cursor selbst schieben“: positioning is separate from editing. A drag on the positioning strip (drum grid:
 * ruler and guitar lane; score: the number lane above a system) moves only the cursor/start marker; on release it snaps:
 * to the nearest attack (onset: a hit or a note) within [radius], else to the nearest grid position. Pure, in the caller's
 * x unit (dp or layout units), so the drum grid and the score share the rule.
 */
object CursorSnap {
    /** A snap candidate: its x and the musical position it stands for. */
    data class Target(val x: Float, val measureId: EntityId, val offset: Fraction)

    /** Nearest onset within [radius] of [x]; otherwise the nearest grid position; null without any candidate. Ties: the earlier one. */
    fun snap(x: Float, onsets: List<Target>, grid: List<Target>, radius: Float): Target? {
        val onset = nearest(x, onsets)
        if (onset != null && abs(onset.x - x) <= radius) return onset
        return nearest(x, grid) ?: onset
    }

    private fun nearest(x: Float, list: List<Target>): Target? {
        var best: Target? = null
        var distance = Float.MAX_VALUE
        for (t in list) { val d = abs(t.x - x); if (d < distance) { distance = d; best = t } }
        return best
    }

    /** Snap candidates of a score system (layout units): note onsets of the layout and its beat/grid columns. */
    fun scoreTargets(layout: ScoreLayout, system: Int): Pair<List<Target>, List<Target>> {
        val onsets = layout.notes.asSequence().filter { n -> layout.measures.firstOrNull { it.measureId == n.measureId }?.system == system }
            .map { it.measureId to it.offset }.distinct().mapNotNull { (m, o) -> layout.xFor(m, o)?.let { Target(it, m, o) } }.toList()
        val grid = layout.columns.filter { it.system == system }.map { Target(it.x, it.measureId, it.offset) }
        return onsets to grid
    }

    /**
     * The number lane of a system under (x, y) — the strip from the system top down to just above its first staff line
     * (bar numbers, sections, tempo); null elsewhere. Notes, fret numbers and signatures lie below it.
     */
    fun laneAt(layout: ScoreLayout, y: Float): SystemBox? {
        val sys = layout.systems.firstOrNull { y >= it.top && y < it.bottom } ?: return null
        val firstLine = if (sys.staffBottom > sys.staffTop) sys.staffTop else sys.tabTop
        return sys.takeIf { y < firstLine - LANE_CLEARANCE }
    }

    /** Space (layout units) kept free above the first staff line, so fret numbers on the top line stay note taps. */
    const val LANE_CLEARANCE = 10f
}
