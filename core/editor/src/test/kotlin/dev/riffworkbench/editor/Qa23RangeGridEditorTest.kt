package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 23 / Daten, Auswahl, Raster — Editor-Kern.
 *
 *  D  Drum-Raster feiner als 1/16: ein Schlag auf einen 1/32-Platz innerhalb eines vorhandenen 1/16-Beats scheiterte mit
 *     „Beats derselben Stimme überlappen“ (rot vor dem Fix). Jetzt wird der überdeckende Beat am Platz geteilt (verkürzt),
 *     der neue Schlag bekommt die Rasterdauer, gekürzt auf den Platz bis zum nächsten Beat.
 *  B  [EditorCommand.EditNotesInBars]: Artikulation/Velocity für alle Noten der Spur und Stimme im Bereich, ein Undo-Schritt,
 *     nichts außerhalb.
 *  S  [CursorSteps]: Schritte enden nie innerhalb eines Beats; vorwärts nie über einen Beat hinweg.
 */
class Qa23RangeGridEditorTest {
    private var n = 0
    private val ids = IdSource { EntityId("q23e-${n++}") }

    private fun session(bars: Int = 4): EditorSession {
        val e = EditorSession(ProjectFactory(ids).create("QA23"), ids)
        repeat(bars - 1) { e.dispatch(EditorCommand.AddMeasure(TimeSignature())) }
        return e
    }
    private fun EditorSession.bar(i: Int) = state.value.project.measures[i].id
    private fun EditorSession.at(track: Int, bar: Int, offset: Fraction, string: Int = 0, voice: Int = 0) =
        select(Selection(state.value.project.tracks[track].id, bar(bar), voice, offset, string))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c: $r", r is Outcome.Success) }
    private fun EditorSession.beats(track: Int, bar: Int, voice: Int = 0) = state.value.project.tracks[track].staves.first().bars[bar].voices.first { it.index == voice }.beats
    private fun f(a: Long, b: Long) = Fraction.of(a, b)

    // ---------------------------------------------------------------- D: drum grid refinement
    @Test fun d_aHitOnA32ndSlotInsideA16thSplitsTheCoveringBeat() {
        val e = session(1)
        for (k in 0L until 4L) { e.at(2, 0, f(k, 16)); e.ok(EditorCommand.ToggleDrum(42, WrittenDuration(16))) }
        val before = e.state.value.project
        e.at(2, 0, f(1, 32))
        e.ok(EditorCommand.ToggleDrum(38, WrittenDuration(32)))
        val beats = e.beats(2, 0)
        assertEquals(listOf(f(0, 1), f(1, 32), f(1, 16), f(1, 8), f(3, 16)), beats.map { it.offset })
        assertEquals("the covering 1/16 now ends at the slot", WrittenDuration(32), beats[0].duration)
        assertEquals("its hit is kept", before.tracks[2].staves.first().bars[0].voices[0].beats[0].notes, beats[0].notes)
        assertEquals(WrittenDuration(32), beats[1].duration); assertEquals(38, beats[1].notes.single().pitch.midiNumber)
        assertTrue(ScoreValidator.validate(e.state.value.project).none { it.severity == Severity.ERROR })
        e.undo()
        assertEquals("undo restores exactly", before.tracks, e.state.value.project.tracks)
    }

    @Test fun d_aLongHitIsShortenedToADottedValueAndANewHitFitsTheGap() {
        val e = session(1)
        e.at(2, 0, Fraction.ZERO); e.ok(EditorCommand.ToggleDrum(36, WrittenDuration(4)))
        e.at(2, 0, f(1, 4)); e.ok(EditorCommand.ToggleDrum(38, WrittenDuration(4)))
        e.at(2, 0, f(3, 32)); e.ok(EditorCommand.ToggleDrum(42, WrittenDuration(32)))
        assertEquals(WrittenDuration(16, 1), e.beats(2, 0)[0].duration)                  // 3/32 = dotted 1/16
        // An 1/8 entry at 7/32 has only 1/32 before the beat at 1/4: shortened, not overlapping.
        e.at(2, 0, f(7, 32)); e.ok(EditorCommand.ToggleDrum(46, WrittenDuration(8)))
        assertEquals(WrittenDuration(32), e.beats(2, 0).first { it.offset == f(7, 32) }.duration)
        assertTrue(ScoreValidator.validate(e.state.value.project).none { it.severity == Severity.ERROR })
    }

    @Test fun d_aSlotThatCannotSplitItsBeatIsRefusedWithAMessage() {
        val e = session(1)
        e.at(2, 0, Fraction.ZERO); e.ok(EditorCommand.ToggleDrum(36, WrittenDuration(4)))
        val before = e.state.value.project
        e.at(2, 0, f(5, 32))                                                            // 5/32 is no single written value
        val r = e.dispatch(EditorCommand.ToggleDrum(38, WrittenDuration(32)))
        assertTrue(r is Outcome.Failure && r.message.contains("Raster"))
        assertEquals(before, e.state.value.project)
    }

    @Test fun d_entryOnTheTabStillRefusesAPositionInsideABeat() {
        // Unchanged contract (EditorSessionTest): only the drum grid splits; the cursor rules keep TAB entries out of beats.
        val e = session(1)
        e.at(0, 0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 0, duration = WrittenDuration(4)))
        e.at(0, 0, f(1, 32))
        assertTrue(e.dispatch(EditorCommand.PutNote(fret = 1, duration = WrittenDuration(32))) is Outcome.Failure)
    }

    // ---------------------------------------------------------------- B: range edits
    @Test fun b_rangeArticulationTogglesOnAllNotesOfTheVoiceInTheRange() {
        val e = session(4)
        for (b in 0..3) { e.at(0, b, Fraction.ZERO, 0); e.ok(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(8)))
            e.at(0, b, f(1, 8), 2); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(8))) }
        e.at(0, 1, Fraction.ZERO, 2, voice = 1); e.ok(EditorCommand.PutNote(fret = 7, duration = WrittenDuration(4)))   // voice 2 in bar 2
        e.at(1, 1, Fraction.ZERO, 0); e.ok(EditorCommand.PutNote(fret = 1, duration = WrittenDuration(4)))             // bass
        // one note already muted
        e.at(0, 1, Fraction.ZERO, 0); e.ok(EditorCommand.ApplyArticulation(Articulation.Mark(NoteMark.PALM_MUTE)))
        val before = e.state.value.project
        e.at(0, 1, Fraction.ZERO, 0)
        val range = listOf(e.bar(1), e.bar(2))
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        e.ok(EditorCommand.EditNotesInBars(range, NoteEdit.Apply(pm)))
        fun muted(track: Int, bar: Int, voice: Int = 0) = e.beats(track, bar, voice).flatMap { it.notes }.map { pm in it.articulations }
        assertEquals("mixed → all get it", listOf(true, true), muted(0, 1)); assertEquals(listOf(true, true), muted(0, 2))
        assertEquals(listOf(false, false), muted(0, 0)); assertEquals(listOf(false, false), muted(0, 3))
        assertEquals("other voice untouched", listOf(false), muted(0, 1, voice = 1))
        assertEquals("other track untouched", before.tracks[1], e.state.value.project.tracks[1])
        e.ok(EditorCommand.EditNotesInBars(range, NoteEdit.Apply(pm)))
        assertEquals("all had it → removed from all", listOf(false, false), muted(0, 1))
        e.ok(EditorCommand.EditNotesInBars(range, NoteEdit.Velocity(40)))
        assertTrue(e.beats(0, 2).flatMap { it.notes }.all { it.velocity == 40 })
        assertTrue(e.beats(0, 0).flatMap { it.notes }.none { it.velocity == 40 })
        e.ok(EditorCommand.EditNotesInBars(range, NoteEdit.Apply(Articulation.Mark(NoteMark.HAMMER_ON))))
        e.ok(EditorCommand.EditNotesInBars(range, NoteEdit.Apply(Articulation.Mark(NoteMark.PULL_OFF))))
        assertTrue("exclusive marks replace each other", e.beats(0, 1).flatMap { it.notes }.all { Articulation.Mark(NoteMark.HAMMER_ON) !in it.articulations && Articulation.Mark(NoteMark.PULL_OFF) in it.articulations })
        e.ok(EditorCommand.EditNotesInBars(range, NoteEdit.Clear(Articulations.slot(Articulation.Mark(NoteMark.PULL_OFF)))))
        assertTrue(e.beats(0, 1).flatMap { it.notes }.all { it.articulations.isEmpty() })
        repeat(6) { e.undo() }
        assertEquals("one undo step per range edit", before.tracks, e.state.value.project.tracks)
    }

    @Test fun b_aRangeWithoutNotesFailsWithoutChange() {
        val e = session(3)
        val before = e.state.value.project
        val r = e.dispatch(EditorCommand.EditNotesInBars(listOf(e.bar(1), e.bar(2)), NoteEdit.Apply(Articulation.Mark(NoteMark.ACCENT))))
        assertTrue(r is Outcome.Failure)
        assertEquals(before, e.state.value.project)
        assertTrue(e.dispatch(EditorCommand.EditNotesInBars(listOf(EntityId("nope")), NoteEdit.Velocity(10))) is Outcome.Failure)
    }

    // ---------------------------------------------------------------- S: cursor steps
    private fun beat(o: Fraction, d: WrittenDuration) = Beat(ids.next(), o, d, explicitRest = true)

    @Test fun s_stepsNeverEndInsideABeat() {
        val beats = listOf(beat(Fraction.ZERO, WrittenDuration(4)), beat(f(1, 4), WrittenDuration(32)), beat(f(3, 8), WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2)))))
        assertEquals("back from 1/4 on 1/32: start of the quarter", Fraction.ZERO, CursorSteps.backward(beats, f(1, 4), f(1, 32)))
        assertEquals("back from 3/8 on 1/4: the beat right before", f(1, 4), CursorSteps.backward(beats, f(3, 8), f(1, 4)))
        assertEquals("back from 1/32 on 1/4: bar start, not the previous bar", Fraction.ZERO, CursorSteps.backward(beats, f(1, 32), f(1, 4)))
        assertEquals("forward from 9/32 (empty) on 1/4 stops at the triplet", f(3, 8), CursorSteps.forward(beats, f(9, 32), f(1, 4)))
        assertEquals("forward inside a gap", f(5, 16), CursorSteps.forward(beats, f(9, 32), f(1, 32)))
        // Every 1/64 position of a 4/4 bar steps to a non-covered stop.
        for (k in 0L until 64L) {
            val from = f(k, 64)
            if (CursorSteps.covering(beats, from) != null) continue
            for (g in listOf(f(1, 64), f(1, 32), f(1, 24), f(1, 12), f(1, 4))) {
                val fw = CursorSteps.forward(beats, from, g); val bw = CursorSteps.backward(beats, from, g)
                assertNull("forward $from+$g = $fw inside a beat", CursorSteps.covering(beats, fw))
                assertNull("backward $from-$g = $bw inside a beat", CursorSteps.covering(beats, bw))
                assertTrue(fw > from && (from == Fraction.ZERO || bw < from))
            }
        }
    }

    @Test fun s_durationsForExactLengths() {
        assertEquals(WrittenDuration(16, 1), Durations.exact(f(3, 32)))
        assertEquals(WrittenDuration(8, 2), Durations.exact(f(7, 32)))
        assertNull(Durations.exact(f(5, 32)))
        assertEquals(WrittenDuration(16, tuplets = listOf(TupletRatio(3, 2))), Durations.exact(f(1, 24), listOf(TupletRatio(3, 2))))
        assertEquals(WrittenDuration(32), Durations.fitting(WrittenDuration(8), f(1, 32)))
        assertEquals(WrittenDuration(8), Durations.fitting(WrittenDuration(8), f(1, 4)))
    }
}
