package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 29, Nutzerentscheidung „Du kannst eine Note vor einer anderen nicht vom Wert ändern, wenn eine andere den Platz
 * belegt. Diese sollen sich dann verschieben.“ Longer: rests right behind first, then the following beats move later, over
 * the barline into the next bar (ties), bar by bar, a bar appended at the end. Red against the Zyklus-25 refusal.
 */
class Qa29DurationRippleTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("r-${sequence++}") }
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private val q = WrittenDuration(4)
    private fun session(band: Boolean = false) = EditorSession(ProjectFactory(ids).create("Ripple", withBand = band), ids)
    private fun EditorSession.at(bar: Int, offset: Fraction, track: Int = 0, voice: Int = 0) =
        select(state.value.selection.copy(trackId = state.value.project.tracks[track].id, measureId = state.value.project.measures[bar].id, offset = offset, string = 0, voice = voice))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.beats(bar: Int, track: Int = 0, voice: Int = 0) = state.value.project.tracks[track].staves[0].bars[bar].voices[voice].beats
    /** (offset, fret or null for a rest, length) per beat. */
    private fun EditorSession.shape(bar: Int, track: Int = 0) = beats(bar, track).map { Triple(it.offset, it.notes.firstOrNull()?.let { n -> n.fret ?: n.pitch.midiNumber }, it.duration.length()) }
    private fun EditorSession.valid() {
        val p = state.value.project
        val errors = ScoreValidator.validate(p).filter { it.severity == Severity.ERROR }
        assertTrue(errors.toString(), errors.isEmpty())
    }
    /** Every bar of the voice gapless and exactly full (or empty). */
    private fun EditorSession.full(track: Int = 0) {
        val p = state.value.project
        p.measures.forEachIndexed { i, m ->
            val beats = beats(i, track)
            if (beats.isEmpty()) return@forEachIndexed
            var end = Fraction.ZERO
            for (b in beats) { assertEquals("Takt ${i + 1} lückenlos", end, b.offset); end = b.offset + b.duration.length() }
            assertEquals("Takt ${i + 1} exakt gefüllt", m.signature.length(), end)
        }
    }
    private fun EditorSession.quarters(bar: Int, vararg frets: Int?) = frets.forEachIndexed { i, fret ->
        at(bar, f(i.toLong(), 4)); ok(if (fret == null) EditorCommand.PutRest(q) else EditorCommand.PutNote(fret = fret, duration = q))
    }
    private fun EditorSession.bars(n: Int) { if (n > 1) ok(EditorCommand.InsertMeasures(state.value.project.measures.size, n - 1)) }

    @Test fun longerConsumesTheRestRightBehindFirst() {
        val e = session(); e.quarters(0, 1, null, 3, 4)
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.SetDuration(WrittenDuration(2)))
        assertEquals(listOf(Triple(f(0, 1), 1, f(1, 2)), Triple(f(1, 2), 3, f(1, 4)), Triple(f(3, 4), 4, f(1, 4))), e.shape(0))
        assertEquals(1, e.state.value.project.measures.size)
        e.valid(); e.full()
    }

    @Test fun longerMovesTheFollowingNotesOfTheBarByTheDifference() {
        val e = session(); e.quarters(0, 1, 2, null, null)
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.SetDuration(WrittenDuration(2)))
        // Fret 2 moves by 1/4 (no rest right behind the note); the trailing rests are free room and give way.
        assertEquals(listOf(f(0, 1), f(1, 2)), e.beats(0).filter { it.notes.isNotEmpty() }.map { it.offset })
        assertEquals(1, e.state.value.project.measures.size)
        e.valid(); e.full()
        // Dotting the half: fret 2 moves by another 1/4.
        e.ok(EditorCommand.SetDuration(WrittenDuration(2, 1)))
        assertEquals(listOf(Triple(f(0, 1), 1, f(3, 4)), Triple(f(3, 4), 2, f(1, 4))), e.shape(0))
        e.valid(); e.full()
    }

    @Test fun overflowMovesIntoTheNextBarWithATie() {
        val e = session(); e.bars(2)
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 1, duration = q))
        e.at(0, f(1, 4)); e.ok(EditorCommand.PutNote(fret = 2, duration = q))
        e.at(0, f(1, 2)); e.ok(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(2)))
        e.quarters(1, 5, null, 7, null)
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.SetDuration(WrittenDuration(2)))
        // Bar 1: half, fret 2 at 1/2, fret 3 split at the barline (1/4 + 1/4 tied).
        assertEquals(listOf(Triple(f(0, 1), 1, f(1, 2)), Triple(f(1, 2), 2, f(1, 4)), Triple(f(3, 4), 3, f(1, 4))), e.shape(0))
        // Bar 2: the tied continuation at the downbeat; its content moves by 1/4 (rhythm kept), the trailing rest gives way.
        assertEquals(listOf(Triple(f(0, 1), 3, f(1, 4)), Triple(f(1, 4), 5, f(1, 4)), Triple(f(1, 2), null, f(1, 4)), Triple(f(3, 4), 7, f(1, 4))), e.shape(1))
        val origin = e.beats(0)[2].notes.single(); val target = e.beats(1)[0].notes.single()
        assertEquals(listOf(NoteLink(LinkKind.TIE, target.id)), origin.links)
        assertEquals(origin.pitch, target.pitch); assertEquals(origin.string to origin.fret, target.string to target.fret)
        assertEquals(2, e.state.value.project.measures.size)
        e.valid(); e.full()
    }

    @Test fun cascadeOverSeveralBarsAppendsABarAtTheEnd() {
        val e = session(); e.bars(3)
        e.quarters(0, 1, 2, 3, 4); e.quarters(1, 5, 6, 7, 8); e.quarters(2, 9, 10, 11, 12)
        val before = e.state.value.project
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.SetDuration(WrittenDuration(2)))
        val p = e.state.value.project
        assertEquals(4, p.measures.size)
        assertEquals(p.measures[2].signature, p.measures[3].signature)
        // Every note after the change starts exactly 1/4 later (in whole-piece time).
        fun onsets(s: EditorSession) = s.state.value.project.measures.indices.flatMap { b -> s.beats(b).filter { it.notes.isNotEmpty() }.map { it.notes.single().fret!! to (f(b.toLong(), 1) + it.offset) } }
        val now = onsets(e).toMap()
        val then = e.run { before.measures.indices.flatMap { b -> before.tracks[0].staves[0].bars[b].voices[0].beats.map { it.notes.single().fret!! to (f(b.toLong(), 1) + it.offset) } } }.toMap()
        for ((fret, t) in then) if (fret != 1) assertEquals("Bund $fret", t + f(1, 4), now[fret])
        assertEquals(listOf(Triple(f(0, 1), 12, f(1, 4)), Triple(f(1, 4), null, f(3, 4))), e.shape(3))
        // The appended bar exists (empty) in every other track too: validated by bar alignment.
        e.valid(); e.full()
        // One undo step restores everything, the appended bar included.
        assertTrue(e.undo() is Outcome.Success)
        assertEquals(before.copy(revision = 0), e.state.value.project.copy(revision = 0))
        assertTrue(e.redo() is Outcome.Success)
        assertEquals(p.copy(revision = 0), e.state.value.project.copy(revision = 0))
    }

    @Test fun aRestAtTheStartOfTheNextBarStopsTheCascade() {
        val e = session(); e.bars(3)
        e.quarters(0, 1, 2, 3, 4); e.quarters(1, null, 6, 7, 8); e.quarters(2, 9, 10, 11, 12)
        val bar3 = e.beats(2)
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.SetDuration(WrittenDuration(2)))
        assertEquals(3, e.state.value.project.measures.size)
        assertEquals(listOf(4, 6, 7, 8), e.beats(1).map { it.notes.single().fret })
        assertSame(bar3, e.beats(2))
        e.valid(); e.full()
    }

    @Test fun otherVoicesAndTracksStayUnchanged() {
        val e = session(band = true); e.bars(2)
        e.quarters(0, 1, 2, 3, 4)
        e.at(1, Fraction.ZERO, voice = 1); e.ok(EditorCommand.PutNote(fret = 9, duration = WrittenDuration(1)))
        e.at(0, Fraction.ZERO, track = 1); e.ok(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(1)))
        val before = e.state.value.project
        e.at(0, f(3, 4)); e.ok(EditorCommand.SetDuration(WrittenDuration(2, 1)))
        val p = e.state.value.project
        assertEquals(2, p.measures.size)
        assertEquals(before.tracks[0].staves[0].bars[1].voices[1], p.tracks[0].staves[0].bars[1].voices[1])
        assertEquals(before.tracks.drop(1), p.tracks.drop(1))
        assertEquals(listOf(Triple(f(0, 1), 4, f(1, 2)), Triple(f(1, 2), null, f(1, 2))), e.shape(1))
        e.valid(); e.full()
    }

    @Test fun drumHitsOverTheBarlineContinueAsRestsWithoutTie() {
        val e = session(band = true)
        val drums = e.state.value.project.tracks.indexOfFirst { it.instrument.kind == InstrumentKind.PERCUSSION }
        (0..3).forEach { i -> e.at(0, f(i.toLong(), 4), track = drums); e.ok(EditorCommand.ToggleDrum(36, q)) }
        e.at(0, f(3, 4), track = drums); e.ok(EditorCommand.SetDuration(WrittenDuration(2)))
        assertEquals(2, e.state.value.project.measures.size)
        val last = e.beats(0, drums).last()
        assertEquals(f(3, 4) to f(1, 4), last.offset to last.duration.length())
        assertTrue(last.notes.single().links.isEmpty())
        val next = e.beats(1, drums)
        assertTrue(next.all { it.explicitRest && it.notes.isEmpty() })
        assertEquals(f(1, 1), next.fold(Fraction.ZERO) { a, b -> a + b.duration.length() })
        e.valid(); e.full(drums)
    }

    @Test fun aTiedNoteKeepsItsTieWhenItMoves() {
        val e = session(); e.bars(2)
        e.quarters(0, 1, 2, 3, 4)
        e.at(1, Fraction.ZERO); e.ok(EditorCommand.ToggleTie)   // fret 4 → tied into bar 2
        e.at(1, f(1, 4)); e.ok(EditorCommand.PutRest(WrittenDuration(2, 1)))
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.SetDuration(WrittenDuration(4, 1)))
        // Fret 4 moved to 7/8 and is split (1/8 + 1/8 tied), the tie to bar 2 leaves from the continuation.
        val bar1 = e.beats(0); val bar2 = e.beats(1)
        assertEquals(f(7, 8), bar1.last().offset)
        val cont = bar2[0].notes.single()
        assertEquals(listOf(NoteLink(LinkKind.TIE, cont.id)), bar1.last().notes.single().links)
        assertEquals(f(1, 8), bar2[0].duration.length())
        assertEquals(LinkKind.TIE, cont.links.single().kind)
        assertEquals(bar2[1].notes.single().id, cont.links.single().target)
        e.valid(); e.full()
    }
}
