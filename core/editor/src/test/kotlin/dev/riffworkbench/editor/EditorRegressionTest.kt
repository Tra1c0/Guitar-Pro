package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

class EditorRegressionTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("q-${sequence++}") }
    private fun session() = EditorSession(ProjectFactory(ids).demo("QA"), ids)
    private fun EditorSession.at(offset: Fraction, string: Int = 0, measure: Int = 0, track: Int = 0) =
        select(state.value.selection.copy(trackId = state.value.project.tracks[track].id, measureId = state.value.project.measures[measure].id, offset = offset, string = string))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.voice(track: Int = 0, measure: Int = 0) = state.value.project.tracks[track].staves[0].bars[measure].voices[0]

    /** Tie across barline: m0 @15/16 fret 7 tied into m1 @0. */
    private fun tied(): EditorSession {
        val e = session(); e.at(Fraction.of(15, 16)); e.ok(EditorCommand.PutNote(fret = 7))
        e.at(Fraction.ZERO, measure = 1); e.ok(EditorCommand.ToggleTie); return e
    }

    @Test fun restOverTiedNoteReplacesItAndPrunesTheTie() {
        val e = tied() // selection at m1 @0 = tie target
        val r = e.dispatch(EditorCommand.PutRest(WrittenDuration(16)))
        assertTrue("PutRest should replace the tied note and prune the tie, got $r", r is Outcome.Success)
    }

    @Test fun deleteOverTiedNoteWorksForComparison() {
        val e = tied(); e.ok(EditorCommand.Delete)
    }

    @Test fun retunePercussionToPianoSwitchesToStandardNotation() {
        val e = session()
        val drums = e.state.value.project.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }
        e.ok(EditorCommand.SetInstrument(drums.id, InstrumentDefinition(kind = InstrumentKind.PIANO, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0), RetuningPolicy.KEEP_SOUNDING_PITCH))
        val t = e.state.value.project.tracks.first { it.id == drums.id }
        assertEquals(setOf(NotationKind.STANDARD), t.staves[0].notation)
    }

    @Test fun retunePianoToPercussionSwitchesToPercussionNotation() {
        val e = session(); e.ok(EditorCommand.AddTrack(InstrumentKind.PIANO))
        val piano = e.state.value.project.tracks.last()
        e.ok(EditorCommand.SetInstrument(piano.id, InstrumentDefinition(kind = InstrumentKind.PERCUSSION, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0), RetuningPolicy.KEEP_SOUNDING_PITCH))
        val t = e.state.value.project.tracks.last()
        assertEquals(setOf(NotationKind.PERCUSSION), t.staves[0].notation)
    }
}
