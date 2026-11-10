package dev.riffworkbench.editor

import dev.riffworkbench.editor.RhythmEvent.Kind.CONTINUATION
import dev.riffworkbench.editor.RhythmEvent.Kind.ONSET
import dev.riffworkbench.editor.RhythmEvent.Kind.REST
import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/** Zyklus 25 „Rhythmus tippen“: EditorCommand.ApplyRhythm replaces the target voice exactly, as one undo step. */
class Qa25ApplyRhythmTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("r-${sequence++}") }
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private fun w(d: Int, dots: Int = 0) = WrittenDuration(d, dots)
    private fun e(o: Fraction, d: WrittenDuration, k: RhythmEvent.Kind) = RhythmEvent(o, d, k)
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.at(bar: Int, offset: Fraction, string: Int = 0, voice: Int = 0) =
        select(state.value.selection.copy(measureId = state.value.project.measures[bar].id, offset = offset, string = string, voice = voice, rangeEnd = null))
    private fun EditorSession.voice(bar: Int, voice: Int = 0, track: Int = 0) = state.value.project.tracks[track].staves[0].bars[bar].voices[voice]
    private fun noErrors(p: Project) { val errors = ScoreValidator.validate(p).filter { it.severity == Severity.ERROR }; assertTrue(errors.toString(), errors.isEmpty()) }

    /** Three bars of 4/4 with a riff in voice 0 of bars 1–3, a note in voice 1 and the bass playing; target: bars 2 and 3. */
    private fun session(): EditorSession {
        val p = ProjectFactory(ids).create("Tippen", signatures = List(3) { TimeSignature() })
        val e = EditorSession(p, ids)
        for (bar in 0..2) for (k in 0..3) { e.at(bar, f(k.toLong(), 4), string = 1); e.ok(EditorCommand.PutNote(fret = 3 + k, duration = w(4))) }
        e.at(1, Fraction.ZERO, string = 5, voice = 1); e.ok(EditorCommand.PutNote(fret = 12, duration = w(1)))
        // A tie from the last note of bar 1 into bar 2 (target): must be dropped, not left dangling.
        e.at(1, Fraction.ZERO, string = 1); e.ok(EditorCommand.ToggleTie)
        return e
    }
    private fun bars(p: Project) = listOf(
        RhythmBar(p.measures[1].id, f(1, 1), listOf(e(f(0, 1), w(4), ONSET), e(f(1, 4), w(16), ONSET), e(f(5, 16), w(16), REST), e(f(3, 8), w(8), REST), e(f(1, 2), w(4), REST), e(f(3, 4), w(4), ONSET))),
        RhythmBar(p.measures[2].id, f(1, 1), listOf(e(f(0, 1), w(8), CONTINUATION), e(f(1, 8), w(8), REST), e(f(1, 4), w(4, 1), ONSET), e(f(5, 8), w(32), CONTINUATION), e(f(21, 32), w(32), REST),
            e(f(11, 16), w(16), REST), e(f(3, 4), w(4), REST))))

    @Test fun openStringReplacesTheVoiceTiesHeldNotesAndUndoesInOneStep() {
        val e = session()
        val before = e.state.value.project
        val p = before
        assertEquals(8, rhythmOverwrites(p, p.tracks[0].id, 0, listOf(p.measures[1].id, p.measures[2].id)))
        e.ok(EditorCommand.ApplyRhythm(p.tracks[0].id, 0, bars(p), RhythmMode.OPEN_STRING, string = 0))
        val after = e.state.value.project
        noErrors(after)
        val b1 = e.voice(1); val b2 = e.voice(2)
        assertEquals(listOf(f(0, 1), f(1, 4), f(5, 16), f(3, 8), f(1, 2), f(3, 4)), b1.beats.map { it.offset })
        assertEquals(listOf(false, false, true, true, true, false), b1.beats.map { it.explicitRest })
        val notes1 = b1.beats.flatMap { it.notes }; val notes2 = b2.beats.flatMap { it.notes }
        assertTrue((notes1 + notes2).all { it.string == 0 && it.fret == 0 && it.pitch.midiNumber == 40 })
        // Held over the barline and over the value split: tied.
        assertEquals(listOf(NoteLink(LinkKind.TIE, notes2[0].id)), notes1.last().links)
        assertEquals(listOf(NoteLink(LinkKind.TIE, notes2[2].id)), notes2[1].links)
        assertTrue(notes1[0].links.isEmpty() && notes1[1].links.isEmpty())
        // Bar 1 (outside), voice 1 and the bass are untouched; the tie from bar 1 into the replaced bar is gone.
        assertEquals(before.tracks[0].staves[0].bars[0].voices.map { v -> v.beats.map { it.offset to it.notes.map { n -> n.fret } } },
            after.tracks[0].staves[0].bars[0].voices.map { v -> v.beats.map { it.offset to it.notes.map { n -> n.fret } } })
        assertTrue(after.tracks[0].staves[0].bars[0].voices[0].beats.flatMap { it.notes }.all { it.links.isEmpty() })
        assertEquals(before.tracks[0].staves[0].bars[1].voices[1], after.tracks[0].staves[0].bars[1].voices[1])
        assertEquals(before.tracks[1], after.tracks[1])
        // One undo step back to the exact score.
        assertTrue(e.undo() is Outcome.Success)
        assertEquals(before.copy(revision = 0), e.state.value.project.copy(revision = 0))
        assertTrue(e.redo() is Outcome.Success)
        assertEquals(after.tracks, e.state.value.project.tracks)
    }

    @Test fun gridWritesPlaceholdersAndAFretTakesThePlaceholderDuration() {
        val e = session()
        val p = e.state.value.project
        e.ok(EditorCommand.ApplyRhythm(p.tracks[0].id, 0, bars(p), RhythmMode.GRID))
        noErrors(e.state.value.project)
        assertTrue((e.voice(1).beats + e.voice(2).beats).all { it.explicitRest && it.notes.isEmpty() })
        assertEquals(bars(p).flatMap { b -> b.events.map { it.offset to it.duration } }, (e.voice(1).beats + e.voice(2).beats).map { it.offset to it.duration })
        // Fret on a placeholder: the beat keeps its tapped duration, not the entry duration (1/32 here).
        e.at(1, f(1, 4), string = 2); e.ok(EditorCommand.PutNote(fret = 7, duration = WrittenDuration(32)))
        val placed = e.voice(1).beats.first { it.offset == f(1, 4) }
        assertEquals(w(16), placed.duration); assertEquals(7, placed.notes.single().fret); assertFalse(placed.explicitRest)
        // The held note's continuation over the barline: a tie into the continuation placeholder.
        e.at(1, f(3, 4), string = 2); e.ok(EditorCommand.PutNote(fret = 5))
        e.at(2, Fraction.ZERO, string = 2); e.ok(EditorCommand.ToggleTie)
        val origin = e.voice(1).beats.last().notes.single(); val cont = e.voice(2).beats.first().notes.single()
        assertEquals(listOf(NoteLink(LinkKind.TIE, cont.id)), origin.links); assertEquals(5, cont.fret); assertEquals(w(8), e.voice(2).beats.first().duration)
        noErrors(e.state.value.project)
    }

    @Test fun drumsGetOneKitPiecePerAttackAndRestsForHeldTime() {
        val p = ProjectFactory(ids).create("Drums", signatures = List(2) { TimeSignature() })
        val e = EditorSession(p, ids)
        val drums = p.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }
        e.ok(EditorCommand.ApplyRhythm(drums.id, 0, bars(p.copy(measures = listOf(p.measures[0]) + p.measures)), RhythmMode.OPEN_STRING, drumNote = 36))
        val v1 = e.state.value.project.tracks.first { it.id == drums.id }.staves[0].bars[1].voices[0]
        assertEquals(listOf(emptyList(), emptyList(), listOf(36), emptyList(), emptyList(), emptyList(), emptyList<Int>()), v1.beats.map { b -> b.notes.map { it.pitch.midiNumber } })
        noErrors(e.state.value.project)
    }

    @Test fun invalidInputChangesNothing() {
        val e = session()
        val p = e.state.value.project
        val before = e.state.value
        val wrongLength = listOf(RhythmBar(p.measures[1].id, f(7, 8), listOf(e(f(0, 1), w(2, 2), REST))))
        assertTrue(e.dispatch(EditorCommand.ApplyRhythm(p.tracks[0].id, 0, wrongLength, RhythmMode.GRID)) is Outcome.Failure)
        val gap = listOf(RhythmBar(p.measures[1].id, f(1, 1), listOf(e(f(0, 1), w(2), ONSET))))
        assertTrue(e.dispatch(EditorCommand.ApplyRhythm(p.tracks[0].id, 0, gap, RhythmMode.GRID)) is Outcome.Failure)
        assertTrue(e.dispatch(EditorCommand.ApplyRhythm(p.tracks[0].id, 0, bars(p), RhythmMode.OPEN_STRING, string = 9)) is Outcome.Failure)
        assertSame(before, e.state.value)
    }
}
