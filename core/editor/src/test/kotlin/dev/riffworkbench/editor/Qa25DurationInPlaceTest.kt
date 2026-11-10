package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 25, Nutzerentscheidung „Notenwert ändern ohne Verschieben“: SetDuration/SetTuplet on an existing beat keep every
 * later onset in place. Shorter leaves rests right behind the beat, longer consumes rests (or empty room) and is refused
 * without any change when a note is in the way. Red against the former ripple (later beats moved).
 */
class Qa25DurationInPlaceTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("d-${sequence++}") }
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private fun session() = EditorSession(ProjectFactory(ids).create("Dauer", withBand = false), ids)
    private fun EditorSession.at(offset: Fraction) = select(state.value.selection.copy(offset = offset, string = 0))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.beats() = state.value.project.tracks[0].staves[0].bars[0].voices[0].beats
    private fun EditorSession.onsets() = beats().filter { it.notes.isNotEmpty() }.map { it.offset }
    private fun EditorSession.filled(): Fraction = beats().fold(Fraction.ZERO) { a, b -> a + b.duration.length() }
    private fun EditorSession.valid() {
        val errors = ScoreValidator.validate(state.value.project).filter { it.severity == Severity.ERROR }
        assertTrue(errors.toString(), errors.isEmpty())
        var end = Fraction.ZERO
        for (b in beats()) { assertEquals("lückenlos", end, b.offset); end = b.offset + b.duration.length() }
    }

    /** Quarter, quarter, half: a full 4/4 bar. */
    private fun EditorSession.bar() {
        at(Fraction.ZERO); ok(EditorCommand.PutNote(fret = 1, duration = WrittenDuration(4)))
        at(f(1, 4)); ok(EditorCommand.PutNote(fret = 2, duration = WrittenDuration(4)))
        at(f(1, 2)); ok(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(2)))
    }

    @Test fun shorterLeavesRestsBehindAndKeepsLaterOnsets() {
        val e = session(); e.bar()
        e.at(Fraction.ZERO); e.ok(EditorCommand.SetDuration(WrittenDuration(16)))
        assertEquals(listOf(f(0, 1), f(1, 4), f(1, 2)), e.onsets())
        val rest = e.beats()[1]
        assertTrue(rest.explicitRest); assertEquals(f(1, 16), rest.offset); assertEquals(WrittenDuration(8, 1), rest.duration)
        assertEquals(f(1, 1), e.filled()); e.valid()
        // Triplet on the half: 1/3, rest 1/6 (quarter triplet) behind it.
        e.at(f(1, 2)); e.ok(EditorCommand.SetTuplet(TupletRatio(3, 2)))
        assertEquals(f(1, 3), e.beats().first { it.offset == f(1, 2) }.duration.length())
        assertEquals(f(1, 6), e.beats().last().duration.length()); assertTrue(e.beats().last().explicitRest)
        assertEquals(f(1, 1), e.filled()); e.valid()
    }

    @Test fun longerConsumesRestsAndShortensAPartlyCoveredRest() {
        val e = session(); e.bar()
        e.at(f(1, 4)); e.ok(EditorCommand.PutRest(WrittenDuration(4)))   // note → rest (keeps its quarter)
        e.at(Fraction.ZERO); e.ok(EditorCommand.SetDuration(WrittenDuration(4, 1)))   // 3/8: eats 1/8 of the rest
        assertEquals(listOf(f(0, 1), f(1, 2)), e.onsets())
        val rest = e.beats()[1]
        assertTrue(rest.explicitRest); assertEquals(f(3, 8), rest.offset); assertEquals(f(1, 8), rest.duration.length())
        e.valid()
        e.ok(EditorCommand.SetDuration(WrittenDuration(2)))   // up to the next note exactly
        assertEquals(listOf(f(0, 1), f(1, 2)), e.beats().map { it.offset }); e.valid()
    }

    @Test fun longerWithoutRoomIsRefusedWithoutAnyChange() {
        val e = session(); e.bar()
        e.at(Fraction.ZERO)
        val before = e.state.value
        val r = e.dispatch(EditorCommand.SetDuration(WrittenDuration(2)))
        assertTrue(r is Outcome.Failure)
        assertEquals("Kein Platz: nächste Note bei Zählzeit 2", (r as Outcome.Failure).message)
        assertSame(before, e.state.value)
        // Over the barline: refused as well.
        e.at(f(1, 2)); assertTrue(e.dispatch(EditorCommand.SetDuration(WrittenDuration(1))) is Outcome.Failure)
        assertEquals(before.project, e.state.value.project)
        // Dotting the quarter before another quarter: also no room.
        e.at(Fraction.ZERO); assertTrue(e.dispatch(EditorCommand.SetDuration(WrittenDuration(4, 1))) is Outcome.Failure)
    }

    @Test fun undoRestoresTheBarExactly() {
        val e = session(); e.bar()
        val before = e.state.value.project
        e.at(f(1, 2)); e.ok(EditorCommand.SetDuration(WrittenDuration(16)))
        assertNotEquals(before.tracks, e.state.value.project.tracks)
        assertTrue(e.undo() is Outcome.Success)
        assertEquals(before.copy(revision = 0), e.state.value.project.copy(revision = 0))
    }

    @Test fun lastBeatOfAnIncompleteBarGrowsIntoTheEmptyRoom() {
        val e = session()
        e.at(Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 0, duration = WrittenDuration(8)))
        e.ok(EditorCommand.SetDuration(WrittenDuration(2)))
        assertEquals(1, e.beats().size); assertEquals(f(1, 2), e.beats()[0].duration.length())
        e.ok(EditorCommand.SetDuration(WrittenDuration(4)))   // shorter: the old extent stays filled by a rest
        assertEquals(listOf(f(0, 1), f(1, 4)), e.beats().map { it.offset }); assertTrue(e.beats()[1].explicitRest)
    }

    @Test fun decomposeWritesDyadicAndTripletLengths() {
        assertEquals(listOf(WrittenDuration(4), WrittenDuration(16)), Durations.decompose(f(5, 16)))
        assertEquals(listOf(WrittenDuration(4, 1)), Durations.decompose(f(3, 8)))
        assertEquals(listOf(WrittenDuration(16, tuplets = listOf(TupletRatio(3, 2)))), Durations.decompose(f(1, 24)))
        assertEquals(f(5, 24), Durations.decompose(f(5, 24))!!.fold(Fraction.ZERO) { a, d -> a + d.length() })
        assertNull(Durations.decompose(f(1, 7)))
    }
}
