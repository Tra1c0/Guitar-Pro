package dev.riffworkbench.notation

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 19 / Bericht 1 §5: Takt-Treffer für die Bereichsauswahl per langem Drücken. [ScoreLayout.measureAt] ist der
 * strenge Treffer für den Start (nur auf einem Takt), [ScoreLayout.measureNear] der für das Ziehen: der Finger darf das
 * System verlassen (über/unter/neben die Takte), der Bereich endet dann am nächstliegenden Takt der Zeile.
 */
class MeasureHitTest {
    private var n = 0
    private val ids = IdSource { EntityId("mh-${n++}") }

    /** Acht 4/4-Takte, auf 380 Einheiten umbrochen (mehrere Systeme), wie im Editor mit Rand 6. */
    private fun layout(): ScoreLayout {
        val s = EditorSession(ProjectFactory(ids).create("Hit"), ids)
        repeat(7) { s.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        val l = ScoreLayouter.layout(LayoutRequest(s.state.value.project, 0, width = 380f, editGrid = Fraction.of(1, 16), margin = 6f))
        assertTrue("Fixture braucht mindestens zwei Systeme: ${l.systems.size}", l.systems.size >= 2)
        return l
    }
    private fun midY(sys: SystemBox) = if (sys.stringY.isNotEmpty()) (sys.tabTop + sys.tabBottom) / 2 else (sys.staffTop + sys.staffBottom) / 2

    @Test fun measureAtHitsEveryBarInsideItsBox() {
        val l = layout()
        for (m in l.measures) {
            val y = midY(l.systems[m.system])
            assertEquals("Taktmitte von Takt ${m.index + 1}", m.measureId, l.measureAt(m.x + m.width / 2, y)?.measureId)
            assertEquals("linker Taktrand gehört zum Takt", m.measureId, l.measureAt(m.x + 0.5f, y)?.measureId)
            assertEquals("measureNear stimmt innen mit measureAt überein", m.measureId, l.measureNear(m.x + m.width / 2, y)?.measureId)
        }
    }

    @Test fun measureAtIsNullBesideTheBars() {
        val l = layout()
        val sys = l.systems[0]
        val row = l.measures.filter { it.system == 0 }
        assertNull("links vor dem ersten Takt", l.measureAt(row.minOf { it.x } - 1f, midY(sys)))
        assertNull("rechts hinter dem letzten Takt", l.measureAt(row.maxOf { it.x + it.width } + 1f, midY(sys)))
        assertNull("über dem ersten System", l.measureAt(row.first().x + 5f, sys.top - 5f))
    }

    @Test fun measureNearClampsToTheRowWhenTheFingerLeavesTheBars() {
        val l = layout()
        val first = l.measures.filter { it.system == 0 }
        assertEquals("links neben der Zeile: erster Takt der Zeile", first.first().measureId, l.measureNear(-50f, midY(l.systems[0]))?.measureId)
        assertEquals("rechts neben der Zeile: letzter Takt der Zeile", first.last().measureId, l.measureNear(l.width + 50f, midY(l.systems[0]))?.measureId)
        assertEquals("über dem ersten System: erste Zeile", first.first().measureId, l.measureNear(first.first().x + 1f, l.systems[0].top - 40f)?.measureId)
        val lastSys = l.systems.last()
        val lastRow = l.measures.filter { it.system == lastSys.index }
        assertEquals("unter dem letzten System: letzte Zeile", lastRow.last().measureId, l.measureNear(l.width + 50f, lastSys.bottom + 200f)?.measureId)
    }

    @Test fun columnAtStillUsesTheBarUnderTheFinger() {
        val l = layout()
        for (m in l.measures) {
            val c = l.columnAt(m.x + m.width / 2, midY(l.systems[m.system]))
            assertEquals(m.measureId, c?.measureId)
        }
    }

    @Test fun emptyLayoutHasNoBar() {
        val l = ScoreLayout(0f, 0f, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertNull(l.measureAt(1f, 1f))
        assertNull(l.measureNear(1f, 1f))
    }
}
