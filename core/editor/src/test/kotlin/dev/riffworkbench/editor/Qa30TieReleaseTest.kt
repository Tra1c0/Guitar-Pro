package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 30, Haltebogen-Regel (user decision): shortening a tied origin releases the tie and turns the continuation(s) into
 * rests; „Beat löschen“ on a continuation releases the tie at the origin the same way. Nothing moves, one undo step,
 * validator green. Before: „Haltebogen würde unterbrochen – zuerst den Haltebogen lösen“ (Zyklus 25) resp. a ripple.
 */
class Qa30TieReleaseTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("q30t-${sequence++}") }
    private fun session(bars: Int = 3): EditorSession {
        val e = EditorSession(ProjectFactory(ids).create("QA30"), ids)
        if (bars > 1) e.ok(EditorCommand.InsertMeasures(1, bars - 1))
        return e
    }
    private fun EditorSession.at(offset: Fraction, measure: Int = 0, string: Int = 2) =
        select(state.value.selection.copy(trackId = state.value.project.tracks[0].id, measureId = state.value.project.measures[measure].id, offset = offset, string = string, rangeEnd = null))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.beats(measure: Int): List<Beat> = state.value.project.tracks[0].staves[0].bars[measure].voices[0].beats
    private fun EditorSession.ties(): Int = state.value.project.tracks.sumOf { t -> t.staves.sumOf { st -> st.bars.sumOf { b -> b.voices.sumOf { v -> v.beats.sumOf { bt -> bt.notes.sumOf { n -> n.links.count { it.kind == LinkKind.TIE } } } } } } }
    private fun EditorSession.valid() = assertTrue(ScoreValidator.validate(state.value.project).none { it.severity == Severity.ERROR })
    /** Offsets and durations of all beats of the guitar voice, bar by bar. */
    private fun EditorSession.rhythm(): List<List<Pair<Fraction, Fraction>>> = state.value.project.tracks[0].staves[0].bars.map { b -> b.voices[0].beats.map { it.offset to it.duration.length() } }

    /** A dotted half at 1/2 of bar 1 — reaches over the barline, tied into bar 2 (Zyklus 29 entry rule). */
    private fun tiedOverBarline(): EditorSession {
        val e = session()
        e.at(Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(2)))
        e.at(Fraction.of(1, 2)); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(2, dots = 1)))
        assertEquals(1, e.ties())
        return e
    }

    @Test fun shorteningATiedOriginReleasesTheTieAndTheContinuationBecomesARest() {
        val e = tiedOverBarline()
        val before = e.state.value.project
        val rhythm = e.rhythm()
        val continuation = e.beats(1).first()
        assertTrue(continuation.notes.isNotEmpty())
        e.at(Fraction.of(1, 2)); e.ok(EditorCommand.SetDuration(WrittenDuration(4)))
        assertEquals("tie released", 0, e.ties())
        val rest = e.beats(1).first { it.offset == Fraction.ZERO }
        assertTrue("the continuation is a rest", rest.notes.isEmpty() && rest.explicitRest)
        assertEquals("of the same duration", continuation.duration, rest.duration)
        // Nothing moves: bar 2 keeps its rhythm, bar 1 gets a quarter rest behind the shortened note.
        assertEquals(rhythm[1], e.rhythm()[1])
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 2), Fraction.of(3, 4)), e.beats(0).map { it.offset })
        assertTrue(e.beats(0).last().notes.isEmpty())
        e.valid()
        // One undo step back to the tie.
        e.undo()
        assertEquals(before.tracks, e.state.value.project.tracks)
    }

    @Test fun aChainOfContinuationsGoesAsAWhole() {
        val e = session()
        // Whole note on 3/4 → ¼ + ½. (tied) + … : a quarter in bar 1, tied into a whole in bar 2, tied on by hand into bar 3.
        e.at(Fraction.of(3, 4)); e.ok(EditorCommand.PutNote(fret = 7, duration = WrittenDuration(4)))
        e.at(Fraction.ZERO, measure = 1); e.ok(EditorCommand.PutNote(fret = 7, duration = WrittenDuration(1)))
        e.ok(EditorCommand.ToggleTie)
        e.at(Fraction.ZERO, measure = 2); e.ok(EditorCommand.PutNote(fret = 7, duration = WrittenDuration(4)))
        e.ok(EditorCommand.ToggleTie)
        assertEquals(2, e.ties())
        val rhythm = e.rhythm()
        e.at(Fraction.of(3, 4)); e.ok(EditorCommand.SetDuration(WrittenDuration(8)))
        assertEquals(0, e.ties())
        assertTrue("bar 2 and bar 3 continuation are rests", e.beats(1).first().notes.isEmpty() && e.beats(2).first().notes.isEmpty())
        assertEquals(rhythm[1], e.rhythm()[1]); assertEquals(rhythm[2], e.rhythm()[2])
        e.valid()
    }

    @Test fun onlyTheTiedNotesOfAChordGo() {
        val e = session()
        e.at(Fraction.of(3, 4), string = 2); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.at(Fraction.ZERO, measure = 1, string = 2); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.ok(EditorCommand.ToggleTie)
        e.at(Fraction.ZERO, measure = 1, string = 0); e.ok(EditorCommand.PutNote(fret = 0, duration = WrittenDuration(4)))
        assertEquals(2, e.beats(1).first().notes.size)
        e.at(Fraction.of(3, 4), string = 2); e.ok(EditorCommand.SetDuration(WrittenDuration(8)))
        val chord = e.beats(1).first()
        assertEquals("the untied note stays", listOf(0), chord.notes.map { it.string })
        assertFalse(chord.explicitRest)
        assertEquals(0, e.ties())
        e.valid()
    }

    @Test fun aTupletOnATiedOriginReleasesItToo() {
        val e = tiedOverBarline()
        e.at(Fraction.of(1, 2)); e.ok(EditorCommand.SetDuration(WrittenDuration(4)))
        e.undo()
        e.at(Fraction.of(1, 2)); e.ok(EditorCommand.SetTuplet(TupletRatio(3, 2)))
        assertEquals(0, e.ties())
        e.valid()
    }

    @Test fun deletingAContinuationReleasesTheTieAtItsOrigin() {
        val e = tiedOverBarline()
        e.at(Fraction.of(1, 4), measure = 1); e.ok(EditorCommand.PutNote(fret = 8, duration = WrittenDuration(4)))
        val rhythm = e.rhythm()
        val origin = e.beats(0).first { it.offset == Fraction.of(1, 2) }
        e.at(Fraction.ZERO, measure = 1); e.ok(EditorCommand.DeleteBeat)
        assertEquals("tie released at the origin", 0, e.ties())
        assertEquals("the origin keeps note and duration", origin.copy(notes = origin.notes.map { it.copy(links = emptyList()) }), e.beats(0).first { it.offset == Fraction.of(1, 2) })
        assertTrue("the continuation is a rest", e.beats(1).first().let { it.notes.isEmpty() && it.explicitRest })
        assertEquals("nothing moves", rhythm, e.rhythm())
        assertEquals("the next note stays", 8, e.beats(1).first { it.offset == Fraction.of(1, 4) }.notes.single().fret)
        e.valid()
        e.undo()
        assertEquals(1, e.ties())
    }

    @Test fun deletingAnUntiedBeatStillMovesTheFollowingBeats() {
        val e = session()
        e.at(Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 1, duration = WrittenDuration(4)))
        e.at(Fraction.of(1, 4)); e.ok(EditorCommand.PutNote(fret = 2, duration = WrittenDuration(4)))
        e.at(Fraction.ZERO); e.ok(EditorCommand.DeleteBeat)
        assertEquals(2, e.beats(0).first { it.offset == Fraction.ZERO }.notes.single().fret)
    }
}
