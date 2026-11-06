package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 19 / Bericht 1 §5: Bereichsauswahl per langem Drücken. [BarRangeGesture] ist die reine Logik hinter
 * WorkspaceViewModel.beginRange/extendRange/endRange: Anker = gedrückter Takt, Ziehen setzt das Bereichsende taktweise,
 * Loslassen beendet die Geste und lässt den Bereich stehen. Nur die Auswahl ändert sich (kein Befehl, kein Undo-Schritt).
 */
class BarRangeGestureTest {
    private var n = 0
    private val ids = IdSource { EntityId("brg-${n++}") }

    /** Standardprojekt (ein 4/4-Takt) plus drei Takte = vier Takte; Cursor in Takt 2 auf Schlag 1/4, Saite 3. */
    private fun session(): EditorSession {
        val s = EditorSession(ProjectFactory(ids).create("Range"), ids)
        repeat(3) { s.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        val bars = s.state.value.project.measures
        s.select(s.state.value.selection.copy(measureId = bars[1].id, offset = Fraction.of(1, 4), string = 2, rangeEnd = null))
        return s
    }
    private fun EditorSession.bar(i: Int) = state.value.project.measures[i].id
    private fun EditorSession.rangeIndices(): List<Int> = state.value.selection.measureRange(state.value.project).map { id -> state.value.project.measures.indexOfFirst { it.id == id } }

    @Test fun beginSetsTheAnchorToThePressedBarAndSelectsThatOneBar() {
        val s = session(); val g = BarRangeGesture()
        val before = s.state.value.selection
        val next = g.begin(before, s.bar(3))
        assertTrue(g.active)
        assertEquals(s.bar(3), g.anchor)
        assertEquals("Anker = gedrückter Takt", s.bar(3), next.measureId)
        assertEquals("ein Takt ist ausgewählt", s.bar(3), next.rangeEnd)
        assertEquals("anderer Takt: Cursor an den Taktanfang", Fraction.ZERO, next.offset)
        assertEquals("Spur, Stimme und Saite bleiben", before.copy(measureId = s.bar(3), offset = Fraction.ZERO, rangeEnd = s.bar(3)), next)
        s.select(next)
        assertEquals(listOf(3), s.rangeIndices())
    }

    @Test fun beginOnTheCursorBarKeepsTheCursorOffset() {
        val s = session(); val g = BarRangeGesture()
        val next = g.begin(s.state.value.selection, s.bar(1))
        assertEquals(Fraction.of(1, 4), next.offset)
        assertEquals(s.bar(1), next.rangeEnd)
    }

    @Test fun dragExtendsAndShrinksTheRangeBarByBarInBothDirections() {
        val s = session(); val g = BarRangeGesture()
        s.select(g.begin(s.state.value.selection, s.bar(1)))
        s.select(g.extend(s.state.value.selection, s.bar(2))!!)
        assertEquals(listOf(1, 2), s.rangeIndices())
        s.select(g.extend(s.state.value.selection, s.bar(3))!!)
        assertEquals(listOf(1, 2, 3), s.rangeIndices())
        s.select(g.extend(s.state.value.selection, s.bar(2))!!)
        assertEquals("zurückziehen verkleinert", listOf(1, 2), s.rangeIndices())
        s.select(g.extend(s.state.value.selection, s.bar(0))!!)
        assertEquals("über den Anker hinaus nach links: Bereich vor dem Anker", listOf(0, 1), s.rangeIndices())
        assertEquals("der Anker bleibt der Cursor-Takt", s.bar(1), s.state.value.selection.measureId)
        s.select(g.extend(s.state.value.selection, s.bar(1))!!)
        assertEquals("zurück auf den Anker: ein Takt", listOf(1), s.rangeIndices())
    }

    @Test fun extendReportsAChangeOnlyWhenTheBarChanges() {
        val s = session(); val g = BarRangeGesture()
        s.select(g.begin(s.state.value.selection, s.bar(1)))
        assertNull("noch derselbe Takt: keine Änderung, kein Haptik-Tick", g.extend(s.state.value.selection, s.bar(1)))
        s.select(g.extend(s.state.value.selection, s.bar(2))!!)
        assertNull(g.extend(s.state.value.selection, s.bar(2)))
    }

    @Test fun withoutAnActiveGestureExtendDoesNothing() {
        val s = session(); val g = BarRangeGesture()
        assertFalse(g.active)
        assertNull("ohne begin keine Bereichsänderung", g.extend(s.state.value.selection, s.bar(2)))
        assertFalse("end ohne Geste meldet nichts", g.end())
    }

    @Test fun endFinishesTheGestureAndKeepsTheRange() {
        val s = session(); val g = BarRangeGesture()
        s.select(g.begin(s.state.value.selection, s.bar(0)))
        s.select(g.extend(s.state.value.selection, s.bar(2))!!)
        assertTrue(g.end())
        assertFalse(g.active)
        assertNull(g.anchor)
        assertEquals("Loslassen lässt den Bereich stehen", listOf(0, 1, 2), s.rangeIndices())
        assertNull("nach dem Loslassen erweitert Ziehen nichts mehr", g.extend(s.state.value.selection, s.bar(3)))
        assertFalse(g.end())
    }

    @Test fun aNewGestureReplacesTheOldRange() {
        val s = session(); val g = BarRangeGesture()
        s.select(g.begin(s.state.value.selection, s.bar(0)))
        s.select(g.extend(s.state.value.selection, s.bar(3))!!)
        g.end()
        s.select(g.begin(s.state.value.selection, s.bar(2)))
        assertEquals(listOf(2), s.rangeIndices())
    }

    /** Auswahl ist kein Befehl: kein Undo-Schritt, keine neue Revision (nichts wird gespeichert). */
    @Test fun theGestureIsNoEditCommand() {
        val s = session(); val g = BarRangeGesture()
        val revision = s.state.value.project.revision
        val undo = s.state.value.canUndo
        s.select(g.begin(s.state.value.selection, s.bar(0)))
        s.select(g.extend(s.state.value.selection, s.bar(3))!!)
        g.end()
        assertEquals(revision, s.state.value.project.revision)
        assertEquals(undo, s.state.value.canUndo)
    }
}
