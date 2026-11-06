package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 20 (Editor-Robustheit), editor core: deterministic reproductions of the cycle-20 findings.
 *
 * F5: deleting the bar(s) under the cursor (the long-press range → "Takt löschen") put the cursor into bar 1 and kept the
 * old beat offset there: the view jumped to the start of the piece (ViewportScroll follows the cursor) and the next Play
 * started at bar 1 instead of the place where the user was working.
 *
 * F6: SetSignature for a bar that no longer exists (repeatLast after the bar was deleted) reported Success without any
 * change — a silent no-op; every other per-bar command (key, feel, tempo, repeat) fails with "Takt fehlt".
 */
class Qa20EditorFindingsTest {
    private var n = 0
    private val ids = IdSource { EntityId("q20f-${n++}") }

    private fun session(bars: Int): EditorSession {
        val e = EditorSession(ProjectFactory(ids).create("QA20 F"), ids)
        repeat(bars - 1) { assertTrue(e.dispatch(EditorCommand.AddMeasure(TimeSignature())) is Outcome.Success) }
        return e
    }
    private fun EditorSession.bar(i: Int) = state.value.project.measures[i].id
    private fun EditorSession.cursorIndex() = state.value.project.measures.indexOfFirst { it.id == state.value.selection.measureId }

    @Test fun f5_deletingTheCursorRangeKeepsTheCursorAtThatPlace() {
        val e = session(6)
        val g = BarRangeGesture()
        // Cursor on bar 5, beat 2; long press on bar 4, drag to bar 5 (anchor bar 4, range 4–5).
        e.select(e.state.value.selection.copy(measureId = e.bar(4), offset = Fraction.of(1, 4)))
        e.select(g.begin(e.state.value.selection, e.bar(3)))
        e.select(g.extend(e.state.value.selection, e.bar(4))!!); g.end()
        val formerBar6 = e.bar(5)
        assertTrue(e.dispatch(EditorCommand.DeleteMeasures(e.state.value.selection.measureRange(e.state.value.project))) is Outcome.Success)
        val s = e.state.value.selection
        assertEquals("cursor on the bar that followed the deleted range (now bar 4), not bar 1", formerBar6, s.measureId)
        assertEquals(Fraction.ZERO, s.offset)
        assertNull(s.rangeEnd)
        // Undo brings back bars, cursor and range exactly.
        assertTrue(e.undo() is Outcome.Success)
        assertEquals(3, e.cursorIndex()); assertEquals(e.bar(4), e.state.value.selection.rangeEnd)
    }

    @Test fun f5_deletingTheLastBarsPutsTheCursorOnTheNewLastBar() {
        val e = session(4)
        e.select(e.state.value.selection.copy(measureId = e.bar(2), offset = Fraction.of(1, 2), rangeEnd = e.bar(3)))
        val bar2 = e.bar(1)
        assertTrue(e.dispatch(EditorCommand.DeleteMeasures(e.state.value.selection.measureRange(e.state.value.project))) is Outcome.Success)
        assertEquals("cursor on the new last bar (bar 2)", bar2, e.state.value.selection.measureId)
        assertEquals(Fraction.ZERO, e.state.value.selection.offset)
    }

    @Test fun f6_signatureForAMissingBarFailsInsteadOfASilentSuccess() {
        val e = session(2)
        val doomed = e.bar(1)
        assertTrue(e.dispatch(EditorCommand.SetSignature(doomed, TimeSignature(3, 4))) is Outcome.Success)
        assertTrue(e.dispatch(EditorCommand.DeleteMeasures(listOf(doomed))) is Outcome.Success)
        val before = e.state.value
        val r = e.dispatch(EditorCommand.SetSignature(doomed, TimeSignature(7, 8)))   // e.g. "Wiederholen" of the last command
        assertTrue("SetSignature on a deleted bar must fail, was $r", r is Outcome.Failure)
        assertEquals(before, e.state.value)
    }
}
