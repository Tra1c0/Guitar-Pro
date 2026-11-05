package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class EditGridRegressionTest {
    private var n = 0
    private fun id() = EntityId("g${n++}")

    @Test fun editGridCoversTheWholeOfALongBar() {
        val m = Measure(id(), TimeSignature(64, 4))
        val track = Track(id(), "Gitarre", InstrumentDefinition(), listOf(Staff(id(), bars = listOf(Bar(id(), m.id, List(4) { Voice(id(), it, emptyList()) })))))
        val p = Project(id = id(), measures = listOf(m), tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m.id), 120.0)))
        val cols = ScoreLayouter.layout(LayoutRequest(p, 0, editGrid = Fraction.of(1, 64))).columns.filter { it.measureId == m.id }
        assertEquals(1024, cols.size)
    }
}
