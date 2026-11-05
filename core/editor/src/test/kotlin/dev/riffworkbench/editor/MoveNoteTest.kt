package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/** Drag and drop of a selected note (user request): one undo step, pitch kept, properties travel with the note. */
class MoveNoteTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("m-${sequence++}") }
    private fun session() = EditorSession(ProjectFactory(ids).demo("Drag"), ids)
    private fun EditorSession.at(offset: Fraction, string: Int = 0, measure: Int = 0) =
        select(state.value.selection.copy(measureId = state.value.project.measures[measure].id, offset = offset, string = string))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.beats(measure: Int = 0) = state.value.project.tracks[0].staves[0].bars[measure].voices[0].beats
    private fun EditorSession.noteAt(measure: Int, offset: Fraction, string: Int) = beats(measure).firstOrNull { it.offset == offset }?.notes?.firstOrNull { it.string == string }

    @Test fun moveToAnotherBeatKeepsFretTechniqueAndIsOneUndoStep() {
        val e = session(); e.at(Fraction.ZERO, string = 2); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.ok(EditorCommand.ToggleMark(NoteMark.PALM_MUTE))
        val target = e.state.value.project.measures[1].id
        e.ok(EditorCommand.MoveNote(target, Fraction.of(1, 4), 2, WrittenDuration(8)))
        assertNull("source is empty", e.noteAt(0, Fraction.ZERO, 2))
        val moved = e.noteAt(1, Fraction.of(1, 4), 2)
        assertEquals(5, moved?.fret)
        assertTrue(Articulation.Mark(NoteMark.PALM_MUTE) in moved!!.articulations)
        assertEquals(WrittenDuration(8), e.beats(1).first { it.offset == Fraction.of(1, 4) }.duration)
        e.undo()
        assertEquals(5, e.noteAt(0, Fraction.ZERO, 2)?.fret)
        assertNull(e.noteAt(1, Fraction.of(1, 4), 2))
    }

    @Test fun moveToAnotherStringKeepsThePitch() {
        val e = session()
        val tuning = e.state.value.project.tracks[0].instrument.tuning
        val high = tuning.indices.maxBy { tuning[it] }
        val lower = tuning.indices.filter { tuning[it] < tuning[high] }.maxBy { tuning[it] }   // the next lower string
        e.at(Fraction.ZERO, string = high); e.ok(EditorCommand.PutNote(fret = 5))
        val pitch = e.noteAt(0, Fraction.ZERO, high)!!.pitch.midiNumber
        e.ok(EditorCommand.MoveNote(e.state.value.project.measures[0].id, Fraction.ZERO, lower))
        val moved = e.noteAt(0, Fraction.ZERO, lower)!!
        assertEquals(pitch, moved.pitch.midiNumber)
        assertEquals(5 + tuning[high] - tuning[lower], moved.fret)
        assertNull(e.noteAt(0, Fraction.ZERO, high))
    }

    @Test fun occupiedTargetUnplayableStringAndEmptySourceAreRejected() {
        val e = session(); e.at(Fraction.ZERO, string = 0); e.ok(EditorCommand.PutNote(fret = 0))
        e.at(Fraction.ZERO, string = 1); e.ok(EditorCommand.PutNote(fret = 3))
        val m = e.state.value.project.measures[0].id
        assertTrue(e.dispatch(EditorCommand.MoveNote(m, Fraction.ZERO, 0)) is Outcome.Failure)        // occupied
        e.at(Fraction.ZERO, string = 0)
        val tuning = e.state.value.project.tracks[0].instrument.tuning
        val higher = tuning.indices.firstOrNull { tuning[it] > tuning[0] }
        if (higher != null) assertTrue(e.dispatch(EditorCommand.MoveNote(m, Fraction.of(1, 4), higher)) is Outcome.Failure)  // open string 0 would need a negative fret
        e.at(Fraction.of(1, 2), string = 3)
        assertTrue(e.dispatch(EditorCommand.MoveNote(m, Fraction.of(3, 4), 3)) is Outcome.Failure)    // nothing selected
        val revision = e.state.value.project.revision
        e.at(Fraction.ZERO, string = 1); e.ok(EditorCommand.MoveNote(m, Fraction.ZERO, 1))            // drop on itself: no-op
        assertEquals(revision, e.state.value.project.revision)
    }
}
