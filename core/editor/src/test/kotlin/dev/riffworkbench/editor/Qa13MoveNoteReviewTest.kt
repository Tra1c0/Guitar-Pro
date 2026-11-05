package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/** QA 13 adversarial review of EditorCommand.MoveNote (b7041dc). Failing tests = confirmed findings; the rest rule cases out. */
class Qa13MoveNoteReviewTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("q-${sequence++}") }
    private fun session(p: Project = ProjectFactory(ids).create("Drag")) = EditorSession(p, ids)
    private fun EditorSession.at(offset: Fraction, string: Int = 0, measure: Int = 0, voice: Int = 0) =
        select(state.value.selection.copy(measureId = state.value.project.measures[measure].id, offset = offset, string = string, voice = voice))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.beats(measure: Int = 0, voice: Int = 0) = state.value.project.tracks[0].staves[0].bars[measure].voices[voice].beats
    private fun EditorSession.noteAt(measure: Int, offset: Fraction, string: Int, voice: Int = 0) =
        beats(measure, voice).firstOrNull { it.offset == offset }?.notes?.firstOrNull { it.string == string }
    private fun EditorSession.m(i: Int) = state.value.project.measures[i].id
    private fun EditorSession.allLinks() = state.value.project.tracks.flatMap { t -> t.staves.flatMap { s -> s.bars.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { it.notes.flatMap { n -> n.links } } } } } }
    private val q = WrittenDuration(4)

    /** Two tied quarters on string 2 (fret 5) at 0 and 1/4 of bar 1. */
    private fun tied(): EditorSession {
        val e = session()
        e.at(Fraction.ZERO, 2); e.ok(EditorCommand.PutNote(fret = 5, duration = q))
        e.at(Fraction.of(1, 4), 2); e.ok(EditorCommand.PutNote(fret = 5, duration = q))
        e.ok(EditorCommand.ToggleTie)
        assertEquals(listOf(LinkKind.TIE), e.allLinks().map { it.kind })
        return e
    }

    // ---- Confirmed findings -------------------------------------------------------------------------------------------

    /**
     * MoveToString refuses to put one end of a tie on another string ("Gebundene Noten müssen auf derselben Saite bleiben");
     * MoveNote to another string of the SAME beat does the same thing but succeeds and silently drops the tie.
     */
    @Test fun moveNoteToAnotherStringMustNotSilentlyBreakATie() {
        val e = tied()
        e.at(Fraction.of(1, 4), 2)
        assertTrue("reference: MoveToString refuses", e.dispatch(EditorCommand.MoveToString(1)) is Outcome.Failure)
        val r = e.dispatch(EditorCommand.MoveNote(e.m(0), Fraction.of(1, 4), 1))
        assertTrue("MoveNote must refuse like MoveToString, or keep the tie; got $r with links ${e.allLinks()}",
            r is Outcome.Failure || e.allLinks().isNotEmpty())
    }

    /** Dragging the continuation of a tie to another beat removes the tie on its origin without any message. */
    @Test fun movingTheTieContinuationAwayMustNotSilentlyBreakTheTie() {
        val e = tied()
        e.at(Fraction.of(1, 4), 2)
        val r = e.dispatch(EditorCommand.MoveNote(e.m(1), Fraction.ZERO, 2, q))
        assertTrue("got $r, links ${e.allLinks()}", r is Outcome.Failure || e.allLinks().isNotEmpty())
    }

    /** The doc says "techniques travel with it": the note's own outgoing links (tie/hammer-on/slur) are not copied. */
    @Test fun outgoingLegatoLinkTravelsWithTheMovedNote() {
        val e = session()
        e.at(Fraction.ZERO, 2); e.ok(EditorCommand.PutNote(fret = 5, duration = q))
        e.at(Fraction.of(1, 4), 2); e.ok(EditorCommand.PutNote(fret = 7, duration = q))
        val p = e.state.value.project
        val a = e.noteAt(0, Fraction.ZERO, 2)!!; val b = e.noteAt(0, Fraction.of(1, 4), 2)!!
        val withHammer = p.copy(tracks = p.tracks.mapIndexed { ti, t -> if (ti != 0) t else t.copy(staves = t.staves.map { s -> s.copy(bars = s.bars.map { bar -> bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { beat ->
            beat.copy(notes = beat.notes.map { n -> if (n.id == a.id) n.copy(links = listOf(NoteLink(LinkKind.HAMMER_ON, b.id))) else n }) }) }) }) }) })
        val e2 = session(withHammer)
        // Move the hammer-on origin up one string at the same beat (pitch kept): the link to b should stay.
        e2.at(Fraction.ZERO, 2)
        e2.ok(EditorCommand.MoveNote(e2.m(0), Fraction.ZERO, 3))
        assertEquals("hammer-on from the moved note", listOf(LinkKind.HAMMER_ON), e2.allLinks().map { it.kind })
    }

    /** The moved note gets a fresh id (Delete + PutNote): anything keyed by note id (links, slide targets, UI noteId) loses it. */
    @Test fun movedNoteKeepsItsIdentity() {
        val e = session()
        e.at(Fraction.ZERO, 2); e.ok(EditorCommand.PutNote(fret = 5, duration = q))
        val before = e.noteAt(0, Fraction.ZERO, 2)!!.id
        e.ok(EditorCommand.MoveNote(e.m(0), Fraction.of(1, 4), 2, q))
        assertEquals(before, e.noteAt(0, Fraction.of(1, 4), 2)!!.id)
    }

    /**
     * Low severity: dragging the only note of a beat that carries a strum/fermata leaves both on the rest that remains at the
     * source (a strummed rest) and the moved note arrives without them.
     */
    @Test fun strumAndFermataDoNotStayBehindOnTheRest() {
        val e = session()
        e.at(Fraction.ZERO, 2); e.ok(EditorCommand.PutNote(fret = 5, duration = q))
        e.ok(EditorCommand.SetStrum(Strum(StrumKind.STRUM, StrokeDirection.DOWN)))
        e.ok(EditorCommand.SetFermata(Fermata()))
        e.ok(EditorCommand.MoveNote(e.m(0), Fraction.of(1, 2), 2, q))
        val rest = e.beats(0).first { it.offset == Fraction.ZERO }
        assertTrue(rest.explicitRest)
        assertNull("strum left on a rest", rest.strum)
        assertNull("fermata left on a rest", rest.fermata)
    }

    // ---- Ruled out (expected to pass) ---------------------------------------------------------------------------------

    @Test fun undoRedoIsOneStepAndSelectionIsRestored() {
        val e = session()
        e.at(Fraction.ZERO, 2); e.ok(EditorCommand.PutNote(fret = 5, duration = q))
        val before = e.state.value.project
        e.ok(EditorCommand.MoveNote(e.m(1), Fraction.of(1, 8), 2, WrittenDuration(8)))
        val after = e.state.value.project
        assertTrue(e.undo() is Outcome.Success)
        assertEquals(before.tracks, e.state.value.project.tracks)
        assertEquals(Fraction.ZERO, e.state.value.selection.offset); assertEquals(before.measures[0].id, e.state.value.selection.measureId)
        e.redo()
        assertEquals(after.tracks, e.state.value.project.tracks)
    }

    @Test fun overlongOffGridAndOutOfBarTargetsAreRejectedWithoutChange() {
        val e = session()
        e.at(Fraction.ZERO, 2); e.ok(EditorCommand.PutNote(fret = 5, duration = q))
        e.at(Fraction.of(1, 4), 3); e.ok(EditorCommand.PutNote(fret = 5, duration = q))
        e.at(Fraction.ZERO, 2)
        val rev = e.state.value.project.revision
        // 7/8 bar: a half note at 5/8 overflows; offset 7/8 is past the bar; 1/8 overlaps the quarter at 0 in bar 1.
        assertTrue(e.dispatch(EditorCommand.MoveNote(e.m(1), Fraction.of(5, 8), 2, WrittenDuration(2))) is Outcome.Failure)
        assertTrue(e.dispatch(EditorCommand.MoveNote(e.m(1), Fraction.of(7, 8), 2)) is Outcome.Failure)
        assertTrue(e.dispatch(EditorCommand.MoveNote(e.m(0), Fraction.of(1, 8), 2)) is Outcome.Failure)
        assertEquals(rev, e.state.value.project.revision)
        assertNotNull(e.noteAt(0, Fraction.ZERO, 2))
    }

    @Test fun voiceTwoMovesWithinItsOwnVoice() {
        val e = session()
        e.at(Fraction.ZERO, 2, voice = 2); e.ok(EditorCommand.PutNote(fret = 5, duration = q))
        e.ok(EditorCommand.MoveNote(e.m(0), Fraction.of(1, 2), 2, q))
        assertEquals(5, e.noteAt(0, Fraction.of(1, 2), 2, voice = 2)?.fret)
        assertTrue(e.beats(0, 0).isEmpty())
    }

    @Test fun capoAndTranspositionKeepSoundingPitch() {
        val p0 = ProjectFactory(ids).create("Capo")
        val inst = p0.tracks[0].instrument.copy(capoByString = List(7) { 2 }, transposition = -12)
        val e = session(p0.copy(tracks = p0.tracks.mapIndexed { i, t -> if (i == 0) t.copy(instrument = inst) else t }))
        e.at(Fraction.ZERO, 5); e.ok(EditorCommand.PutNote(fret = 3, duration = q))
        val pitch = e.noteAt(0, Fraction.ZERO, 5)!!.pitch.midiNumber
        e.ok(EditorCommand.MoveNote(e.m(1), Fraction.ZERO, 4, q))
        assertEquals(pitch, e.noteAt(1, Fraction.ZERO, 4)!!.pitch.midiNumber)
    }

    @Test fun percussionIsRejected() {
        val e = session()
        val drums = e.state.value.project.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }
        e.select(e.state.value.selection.copy(trackId = drums.id, string = 0))
        e.ok(EditorCommand.ToggleDrum(36, q))
        assertTrue(e.dispatch(EditorCommand.MoveNote(e.m(0), Fraction.of(1, 4), 0)) is Outcome.Failure)
    }

    @Test fun tupletSourceAndExistingTupletTargetKeepDurations() {
        val e = session()
        val triplet = WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2)))
        e.at(Fraction.ZERO, 2); e.ok(EditorCommand.PutNote(fret = 5, duration = triplet))
        e.at(Fraction.of(1, 12), 3); e.ok(EditorCommand.PutNote(fret = 7, duration = triplet))
        e.at(Fraction.ZERO, 2)
        e.ok(EditorCommand.MoveNote(e.m(0), Fraction.of(1, 12), 2, q))
        assertEquals(triplet, e.beats(0).first { it.offset == Fraction.of(1, 12) }.duration)
        assertEquals(triplet, e.beats(0).first { it.offset == Fraction.ZERO }.duration)
        assertTrue(e.beats(0).first { it.offset == Fraction.ZERO }.explicitRest)
    }
}
