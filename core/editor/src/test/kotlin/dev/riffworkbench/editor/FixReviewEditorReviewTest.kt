package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

class FixReviewEditorReviewTest {
    private var n = 0
    private val ids = IdSource { EntityId("q-${n++}") }

    /** Transposing a whole track down one semitone: an open-string tied note in a chord. Worked before cycle 5. */
    @Test fun transposeTrackWithTiedOpenStringInChord() {
        val inst = InstrumentDefinition()
        fun note(s: Int, fr: Int, links: List<NoteLink> = emptyList()) = Note(ids.next(), Pitch(inst.pitch(s, fr)), s, fr, links = links)
        val target = note(3, 0)
        val chord = listOf(note(2, 2), note(3, 0, listOf(NoteLink(LinkKind.TIE, target.id))))
        val m = Measure(ids.next())
        val beats = listOf(Beat(ids.next(), Fraction.ZERO, WrittenDuration(2), chord), Beat(ids.next(), Fraction.of(1, 2), WrittenDuration(2), listOf(target)))
        val track = Track(ids.next(), "G", inst, listOf(Staff(ids.next(), bars = listOf(Bar(ids.next(), m.id, List(4) { v -> Voice(ids.next(), v, if (v == 0) beats else emptyList()) })))))
        val p = Project(id = ids.next(), measures = listOf(m), tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m.id), 120.0)))
        assertTrue(ScoreValidator.validate(p).none { it.severity == Severity.ERROR })
        val e = EditorSession(p, ids)
        val r = e.dispatch(EditorCommand.Transpose(-1, EditScope.TRACK))
        println("transpose result: $r")
        val strings = e.state.value.project.tracks[0].staves[0].bars[0].voices[0].beats.map { b -> b.notes.map { it.string to it.fret } }
        println("strings after: $strings")
        assertTrue("whole-track transpose refused: $r", r is Outcome.Success)
    }
}
