package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import dev.riffworkbench.notation.*
import org.junit.Assert.assertTrue
import org.junit.Test

/** Effect-lane spans (let ring / P.M.) on the last beat of a stretched bar must end at its barline, not past it. */
class LetRingLaneRegressionTest {
    private var n = 0
    private fun id() = EntityId("lr${n++}")
    private val ins = InstrumentDefinition()

    @Test fun letRingOnLastBeatOfAStretchedBarStaysInsideTheBar() {
        val ring = Articulation.Mark(NoteMark.LET_RING)
        val m0 = Measure(id()); val m1 = Measure(id())
        val bar0 = List(8) { k -> Beat(id(), Fraction.of(k.toLong(), 8), WrittenDuration(8), listOf(Note(id(), Pitch(ins.pitch(3, 2)), 3, 2, articulations = listOf(ring)))) }
        // A dense second bar forces a line break, so bar 0 alone is stretched across the whole first system.
        val bar1 = List(32) { k -> Beat(id(), Fraction.of(k.toLong(), 32), WrittenDuration(32), listOf(Note(id(), Pitch(ins.pitch(k % 6, 12)), k % 6, 12))) }
        val track = Track(id(), "Gitarre", ins, listOf(Staff(id(), bars = listOf(m0 to bar0, m1 to bar1).map { (m, b) -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) b else emptyList()) }) })))
        val p = Project(id = id(), metadata = ProjectMetadata("T"), measures = listOf(m0, m1), tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m0.id), 120.0)))
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT, width = 760f))
        val box = l.measures[0]
        assertTrue("bar 0 must be alone in its system", l.measures[1].system != box.system)
        val lane = l.glyphs.filterIsInstance<LineGlyph>().filter { it.dashed && it.ink == Ink.ACCENT && it.y1 >= box.top && it.y1 < box.bottom }
        assertTrue("let ring line expected", lane.isNotEmpty())
        val end = box.x + box.width
        assertTrue("let ring ends at ${lane.maxOf { it.maxX }}, barline at $end", lane.all { it.maxX <= end })
    }
}
