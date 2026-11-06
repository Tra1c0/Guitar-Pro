package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 21 (Bedienung): `EditorSession.dispatch(command, sameUndoStep = true)` joins a follow-up change (the bar that
 * auto-advance adds after the last note) to the previous undo step, so one Undo takes back what one tap did.
 */
class Qa21UndoStepTest {
    private var n = 0
    private val ids = IdSource { EntityId("q21u-${n++}") }
    private fun session() = EditorSession(ProjectFactory(ids).create("QA21"), ids)
    private fun noteCount(s: EditorSession) = s.state.value.project.tracks.first().staves.first().bars.sumOf { b -> b.voices.sumOf { v -> v.beats.sumOf { it.notes.size } } }

    @Test fun joinedStepIsUndoneAndRedoneAsOne() {
        val s = session()
        val bar1 = s.state.value.selection
        s.select(bar1.copy(offset = Fraction.of(3, 4)))
        assertTrue(s.dispatch(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4))) is Outcome.Success)
        assertTrue(s.dispatch(EditorCommand.AddMeasure(TimeSignature()), sameUndoStep = true) is Outcome.Success)
        assertEquals(2, s.state.value.project.measures.size)
        assertTrue(s.state.value.canUndo)
        assertTrue(s.undo() is Outcome.Success)
        assertEquals("note and bar undone together", 0 to 1, noteCount(s) to s.state.value.project.measures.size)
        assertFalse("one step only", s.state.value.canUndo)
        assertEquals("selection of the step's start", Fraction.of(3, 4), s.state.value.selection.offset)
        assertTrue(s.redo() is Outcome.Success)
        assertEquals(1 to 2, noteCount(s) to s.state.value.project.measures.size)
        assertFalse(s.state.value.canRedo)
    }

    @Test fun withoutAPreviousStepItIsAStepOfItsOwn() {
        val s = session()
        assertTrue(s.dispatch(EditorCommand.AddMeasure(TimeSignature()), sameUndoStep = true) is Outcome.Success)
        assertTrue(s.state.value.canUndo)
        s.undo()
        assertEquals(1, s.state.value.project.measures.size)
    }

    @Test fun aFailedJoinLeavesTheHistoryAlone() {
        val s = session()
        s.dispatch(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        val before = s.state.value
        val failed = s.dispatch(EditorCommand.DeleteMeasures(s.state.value.project.measures.map { it.id }), sameUndoStep = true)
        assertTrue(failed is Outcome.Failure)
        assertEquals(before, s.state.value)
        s.undo()
        assertEquals("the note's own step is still there", 0, noteCount(s))
        assertFalse(s.state.value.canUndo)
    }

    @Test fun ordinaryStepsStaySeparate() {
        val s = session()
        s.dispatch(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        s.dispatch(EditorCommand.AddMeasure(TimeSignature()))
        s.undo()
        assertEquals("default: separate steps", 1 to 1, noteCount(s) to s.state.value.project.measures.size)
    }
}
