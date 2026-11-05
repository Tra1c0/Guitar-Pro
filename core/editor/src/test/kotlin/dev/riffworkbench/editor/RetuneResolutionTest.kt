package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 18 / D1-4 and D4-C4 (rewritten from the probes that pinned "Umstimmen nicht möglich: Ton 33 nicht spielbar"):
 * retuning never leaves the user stuck. Notes that fit nowhere are shifted an octave (up first, then down), KEEP_FINGER_POSITION
 * falls back to pitch placement for vanished strings, ties get one shift for both ends, and notes that still fit nowhere are
 * removed only with an explicit allowDrop; without it the failure names bar and string. One command = one undo step.
 */
class RetuneResolutionTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("q18-${sequence++}") }
    private val standardE = TuningPresets.all.first { it.id == "g6-std" }
    private val quarter = WrittenDuration(4)

    private fun guitar(p: Project) = p.tracks.first { it.instrument.kind == InstrumentKind.GUITAR }
    private fun chip(track: Track, preset: TuningPreset, policy: RetuningPolicy, allowDrop: Boolean = false) =
        EditorCommand.SetInstrument(track.id, track.instrument.copy(tuning = preset.tuning, capoByString = List(preset.tuning.size) { track.instrument.capoByString.firstOrNull() ?: 0 }), policy, allowDrop)
    private fun target(track: Track) = track.instrument.copy(tuning = standardE.tuning, capoByString = List(6) { 0 })

    /** Drop-A demo project with notes given as (bar, offset, string, fret). */
    private fun session(vararg notes: Note4): EditorSession {
        val p = ProjectFactory(ids).demo("Retune")
        val s = EditorSession(p, ids)
        val g = guitar(p)
        for (n in notes) {
            s.select(Selection(g.id, p.measures[n.bar].id, 0, n.offset, n.string))
            val r = s.dispatch(EditorCommand.PutNote(fret = n.fret, duration = n.duration))
            assertTrue("put note $n: $r", r is Outcome.Success)
        }
        return s
    }
    private data class Note4(val bar: Int, val offset: Fraction, val string: Int, val fret: Int, val duration: WrittenDuration = WrittenDuration(4))
    private fun at(string: Int, fret: Int, bar: Int = 0, offset: Fraction = Fraction.ZERO, duration: WrittenDuration = quarter) = Note4(bar, offset, string, fret, duration)
    private fun notes(p: Project, bar: Int = 0) = guitar(p).staves.first().bars[bar].voices.first().beats.flatMap { it.notes }.sortedBy { it.string }
    private fun message(r: Outcome<Unit>) = (r as? Outcome.Failure)?.message ?: "OK"
    /** Undo restores the content; the revision keeps counting up (monotonic, see Qa14ModelBasedEditorTest). */
    private fun assertSameContent(message: String, expected: Project, actual: Project) = assertEquals(message, expected.copy(revision = 0), actual.copy(revision = 0))

    // ---- KEEP_SOUNDING_PITCH: octave instead of failure

    @Test fun lowANoteIsOctavedUpInsteadOfRejectingTheRetune() {
        val s = session(at(0, 0)) // A1 = 33 on the Drop-A low string
        val before = s.state.value.project
        val problems = TrackEdits.retuneProblems(before, guitar(before).id, target(guitar(before)), RetuningPolicy.KEEP_SOUNDING_PITCH)
        assertEquals(1, problems.size)
        val pr = problems.single()
        assertEquals(0, pr.barIndex); assertEquals(0, pr.string); assertEquals(33, pr.midi); assertEquals(RetuneResolution.OCTAVE_UP, pr.resolution)
        assertEquals("Ton A1 liegt unter der tiefsten Saite", pr.reason)
        assertEquals("dry run is pure", before, s.state.value.project)

        val r = s.dispatch(chip(guitar(before), standardE, RetuningPolicy.KEEP_SOUNDING_PITCH))
        assertEquals("OK", message(r))
        val moved = notes(s.state.value.project).single()
        assertEquals(45, moved.pitch.midiNumber); assertEquals(0, moved.string); assertEquals(5, moved.fret)
        assertEquals(standardE.tuning, guitar(s.state.value.project).instrument.tuning)
        assertTrue(ScoreValidator.validate(s.state.value.project).none { it.severity == Severity.ERROR })
        assertEquals("1 Note um eine Oktave versetzt (Takt 1)", Retuning.summary(problems))
    }

    @Test fun everyLowStringFretRetunesWithBothPolicies() {
        for (fret in 0..6) for (policy in RetuningPolicy.values()) {
            val s = session(at(0, fret))
            val r = s.dispatch(chip(guitar(s.state.value.project), standardE, policy))
            assertEquals("s1 f$fret $policy", "OK", message(r))
            val n = notes(s.state.value.project).single()
            assertEquals("octave up, $policy f$fret", 33 + fret + 12, n.pitch.midiNumber)
            assertEquals(n.pitch.midiNumber, guitar(s.state.value.project).instrument.pitch(n.string!!, n.fret!!))
        }
    }

    @Test fun octaveDownWhenUpDoesNotFit() {
        // A note above the neck of the new instrument: E standard with maxFret 24 reaches 88; put 90 on an 8-string with maxFret 30.
        val p0 = ProjectFactory(ids).create("Hoch", withBand = false, guitar = InstrumentDefinition(tuning = listOf(30, 35, 40, 45, 50, 55, 59, 64), capoByString = List(8) { 0 }, maxFret = 30))
        val s = EditorSession(p0, ids)
        s.select(Selection(p0.tracks[0].id, p0.measures[0].id, 0, Fraction.ZERO, 7))
        assertTrue(s.dispatch(EditorCommand.PutNote(fret = 26, duration = quarter)) is Outcome.Success) // 90
        val target = InstrumentDefinition(tuning = standardE.tuning, capoByString = List(6) { 0 })
        val problems = TrackEdits.retuneProblems(s.state.value.project, p0.tracks[0].id, target, RetuningPolicy.KEEP_SOUNDING_PITCH)
        assertEquals(listOf(RetuneResolution.OCTAVE_DOWN), problems.map { it.resolution })
        assertEquals("OK", message(s.dispatch(EditorCommand.SetInstrument(p0.tracks[0].id, target, RetuningPolicy.KEEP_SOUNDING_PITCH))))
        assertEquals(78, notes(s.state.value.project).single().pitch.midiNumber)
    }

    @Test fun capoAndStepperWithAnOpenLowNoteOctaveInsteadOfFailing() {
        val s = session(at(0, 0))
        val g = guitar(s.state.value.project)
        assertEquals("OK", message(s.dispatch(EditorCommand.SetInstrument(g.id, g.instrument.copy(capoByString = List(7) { 1 }), RetuningPolicy.KEEP_SOUNDING_PITCH))))
        assertEquals(45, notes(s.state.value.project).single().pitch.midiNumber)
        s.undo()
        val up = g.instrument.copy(tuning = g.instrument.tuning.toMutableList().also { it[0] = 34 })
        assertEquals("OK", message(s.dispatch(EditorCommand.SetInstrument(g.id, up, RetuningPolicy.KEEP_SOUNDING_PITCH))))
        assertEquals(45, notes(s.state.value.project).single().pitch.midiNumber)
    }

    @Test fun notesThatFitAreNotTouched() {
        for (policy in RetuningPolicy.values()) {
            val s = session(at(1, 0), at(2, 2), at(3, 2))
            val g = guitar(s.state.value.project)
            assertTrue(TrackEdits.retuneProblems(s.state.value.project, g.id, target(g), policy).isEmpty())
            assertEquals("OK", message(s.dispatch(chip(g, standardE, policy))))
            val ns = notes(s.state.value.project)
            assertEquals(listOf(40, 47, 52), ns.map { it.pitch.midiNumber })
            assertEquals(listOf(0, 1, 2), ns.map { it.string })
        }
    }

    // ---- KEEP_FINGER_POSITION: vanished string falls back to pitch, then octave

    @Test fun keepFingerPositionFallsBackToPitchPlacementForTheVanishedString() {
        val s = session(at(0, 7)) // E2 = 40 on the vanished low string: playable as open low E on the 6-string
        val g = guitar(s.state.value.project)
        assertTrue(TrackEdits.retuneProblems(s.state.value.project, g.id, target(g), RetuningPolicy.KEEP_FINGER_POSITION).isEmpty())
        assertEquals("OK", message(s.dispatch(chip(g, standardE, RetuningPolicy.KEEP_FINGER_POSITION))))
        val n = notes(s.state.value.project).single()
        assertEquals(40, n.pitch.midiNumber); assertEquals(0, n.string); assertEquals(0, n.fret)
    }

    @Test fun keepFingerPositionOctavesWhenTheVanishedStringsNoteIsBelowTheNeck() {
        val s = session(at(0, 0), at(1, 0)) // 33 on the vanished string, 40 on string 2 (becomes string 1, open E)
        val g = guitar(s.state.value.project)
        val problems = TrackEdits.retuneProblems(s.state.value.project, g.id, target(g), RetuningPolicy.KEEP_FINGER_POSITION)
        assertEquals(1, problems.size)
        assertEquals("Saite 1 existiert nicht mehr", problems.single().reason); assertEquals(RetuneResolution.OCTAVE_UP, problems.single().resolution)
        assertEquals("OK", message(s.dispatch(chip(g, standardE, RetuningPolicy.KEEP_FINGER_POSITION))))
        val ns = notes(s.state.value.project)
        assertEquals(listOf(40, 45), ns.map { it.pitch.midiNumber })
        assertEquals(listOf(0, 1), ns.map { it.string })
        assertEquals(listOf(0, 0), ns.map { it.fret })
    }

    // ---- drops: only with allowDrop, otherwise a message with bar numbers

    @Test fun sevenNoteChordNeedsAnExplicitDropAndIsOneUndoStep() {
        for (policy in RetuningPolicy.values()) {
            val s = session(at(0, 7), at(1, 0), at(2, 0), at(3, 0), at(4, 0), at(5, 0), at(6, 0))
            val before = s.state.value.project
            val g = guitar(before)
            val problems = TrackEdits.retuneProblems(before, g.id, target(g), policy)
            assertEquals("$policy: $problems", 1, problems.count { it.resolution == RetuneResolution.DROP })
            val r = s.dispatch(chip(g, standardE, policy))
            assertTrue("$policy must fail without allowDrop", r is Outcome.Failure)
            assertTrue(message(r), message(r).startsWith("Umstimmen nicht möglich: Ton ") && message(r).contains("(Takt 1, Saite "))
            assertEquals("failure changes nothing", before, s.state.value.project)
            assertEquals("$policy", "OK", message(s.dispatch(chip(g, standardE, policy, allowDrop = true))))
            val retuned = s.state.value.project
            assertEquals(6, notes(retuned).size)
            assertEquals(6, notes(retuned).mapNotNull { it.string }.distinct().size)
            assertEquals("the six fitting notes keep their pitch; only the crowded-out one is gone", listOf(40, 45, 50, 55, 59, 64), notes(retuned).map { it.pitch.midiNumber })
            assertTrue(ScoreValidator.validate(retuned).none { it.severity == Severity.ERROR })
            assertTrue(s.undo() is Outcome.Success)
            assertSameContent("one undo restores the dropped note and the tuning", before, s.state.value.project)
            assertTrue(s.redo() is Outcome.Success)
            assertSameContent("redo", retuned, s.state.value.project)
            assertTrue("the retune was one history step", s.undo() is Outcome.Success)
            assertSameContent("", before, s.state.value.project)
            assertTrue(s.undo() is Outcome.Success)
            assertEquals("the step before the retune is the last PutNote", 6, notes(s.state.value.project).size)
        }
    }

    @Test fun failureMessageNamesBarsAndStringsAndAtMostThreeEntries() {
        // Bar 2 (7/8) holds a seven-note chord on eighths: one note per retune is unplaceable there; bar 1 holds another full chord.
        val s = session(*(0..6).map { at(it, if (it == 0) 7 else 0) }.toTypedArray(), *(0..6).map { at(it, if (it == 0) 7 else 0, bar = 1, duration = WrittenDuration(8)) }.toTypedArray())
        val g = guitar(s.state.value.project)
        val problems = TrackEdits.retuneProblems(s.state.value.project, g.id, target(g), RetuningPolicy.KEEP_SOUNDING_PITCH)
        assertEquals(listOf(0, 1), problems.filter { it.resolution == RetuneResolution.DROP }.map { it.barIndex })
        val m = message(s.dispatch(chip(g, standardE, RetuningPolicy.KEEP_SOUNDING_PITCH)))
        assertTrue(m, m.startsWith("Umstimmen nicht möglich: Ton ") && m.contains("(Takt 1, Saite ") && m.contains("(Takt 2, Saite "))
        assertEquals(Retuning.failureMessage(problems.filter { it.resolution == RetuneResolution.DROP }), m)
        // More than three distinct entries end with an ellipsis and name three.
        val many = (0 until 5).map { RetuneProblem(EntityId("n$it"), it, 0, 33, "x", RetuneResolution.DROP) }
        val text = Retuning.failureMessage(many)
        assertEquals("Umstimmen nicht möglich: Ton A1 (Takt 1, Saite 1) nicht spielbar; Ton A1 (Takt 2, Saite 1) nicht spielbar; Ton A1 (Takt 3, Saite 1) nicht spielbar …", text)
        assertEquals("2 Noten entfernt (Takt 1, 2)", Retuning.summary(problems))
    }

    @Test fun summaryCountsOctavesAndDropsPerBar() {
        val none = Retuning.summary(emptyList())
        assertNull(none)
        val mixed = listOf(
            RetuneProblem(EntityId("a"), 0, 0, 33, "r", RetuneResolution.OCTAVE_UP), RetuneProblem(EntityId("b"), 1, 0, 34, "r", RetuneResolution.OCTAVE_UP),
            RetuneProblem(EntityId("c"), 1, 0, 35, "r", RetuneResolution.OCTAVE_DOWN), RetuneProblem(EntityId("d"), 3, 2, 40, "r", RetuneResolution.DROP))
        assertEquals("3 Noten um eine Oktave versetzt (Takt 1, 2) · 1 Note entfernt (Takt 4)", Retuning.summary(mixed))
    }

    // ---- ties: both ends get the same resolution

    @Test fun tiedNotesGetTheSameOctaveShift() {
        val s = session(at(0, 0), at(0, 0, offset = Fraction.of(1, 4)))
        val g = guitar(s.state.value.project)
        s.select(Selection(g.id, s.state.value.project.measures[0].id, 0, Fraction.of(1, 4), 0))
        assertEquals("OK", message(s.dispatch(EditorCommand.ToggleTie)))
        val tied = notes(s.state.value.project)
        assertEquals(1, tied.count { n -> n.links.any { it.kind == LinkKind.TIE } })
        val problems = TrackEdits.retuneProblems(s.state.value.project, g.id, target(g), RetuningPolicy.KEEP_SOUNDING_PITCH)
        assertEquals(listOf(RetuneResolution.OCTAVE_UP, RetuneResolution.OCTAVE_UP), problems.map { it.resolution })
        assertEquals("OK", message(s.dispatch(chip(g, standardE, RetuningPolicy.KEEP_SOUNDING_PITCH))))
        val after = notes(s.state.value.project)
        assertEquals(listOf(45, 45), after.map { it.pitch.midiNumber })
        assertEquals(1, after.count { n -> n.links.any { it.kind == LinkKind.TIE } })
        assertTrue(ScoreValidator.validate(s.state.value.project).none { it.severity == Severity.ERROR })
    }

    /** Only the tie's second note is crowded out of its string: the first note takes the same octave so the tie survives. */
    @Test fun tieWhoseOneEndNeedsAnOctaveMovesBothEnds() {
        val s = session(at(0, 7), at(1, 0, offset = Fraction.of(1, 4)), at(0, 7, offset = Fraction.of(1, 4)))
        val g = guitar(s.state.value.project)
        s.select(Selection(g.id, s.state.value.project.measures[0].id, 0, Fraction.of(1, 4), 0))
        assertEquals("OK", message(s.dispatch(EditorCommand.ToggleTie)))
        val problems = TrackEdits.retuneProblems(s.state.value.project, g.id, target(g), RetuningPolicy.KEEP_SOUNDING_PITCH)
        assertEquals(2, problems.size)
        assertTrue(problems.all { it.resolution == RetuneResolution.OCTAVE_UP && it.midi == 40 })
        assertEquals("OK", message(s.dispatch(chip(g, standardE, RetuningPolicy.KEEP_SOUNDING_PITCH))))
        val after = guitar(s.state.value.project).staves.first().bars[0].voices.first().beats.flatMap { it.notes }
        val tieOrigin = after.single { n -> n.links.any { it.kind == LinkKind.TIE } }
        val tieTarget = after.single { it.id == tieOrigin.links.single { l -> l.kind == LinkKind.TIE }.target }
        assertEquals(52, tieOrigin.pitch.midiNumber); assertEquals(52, tieTarget.pitch.midiNumber)
        assertEquals(listOf(40), after.filter { it.id != tieOrigin.id && it.id != tieTarget.id }.map { it.pitch.midiNumber })
        assertTrue(ScoreValidator.validate(s.state.value.project).none { it.severity == Severity.ERROR })
    }

    /** A tie whose ends cannot be placed uniformly is dropped as a whole, and only with allowDrop. */
    @Test fun unplaceableTieIsDroppedAsAGroupOnlyWithAllowDrop() {
        // Beat 2 is a full 7-note chord; its low-string note (fret 7 = E2) is tied from beat 1 and listed after the other E2
        // (string 2 open), so on 6 strings it is the one crowded out. The tie is then dropped as a whole, never half.
        val s = session(at(0, 7), at(1, 0, offset = Fraction.of(1, 4)), at(0, 7, offset = Fraction.of(1, 4)), *(2..6).map { at(it, 0, offset = Fraction.of(1, 4)) }.toTypedArray())
        val g = guitar(s.state.value.project)
        s.select(Selection(g.id, s.state.value.project.measures[0].id, 0, Fraction.of(1, 4), 0))
        assertEquals("OK", message(s.dispatch(EditorCommand.ToggleTie)))
        val before = s.state.value.project
        val tieOrigin = notes(before).single { n -> n.links.any { it.kind == LinkKind.TIE } }
        val tieTarget = tieOrigin.links.single { it.kind == LinkKind.TIE }.target
        val problems = TrackEdits.retuneProblems(before, g.id, target(g), RetuningPolicy.KEEP_SOUNDING_PITCH)
        val drops = problems.filter { it.resolution == RetuneResolution.DROP }
        assertEquals("$problems", setOf(tieOrigin.id, tieTarget), drops.map { it.noteId }.toSet())
        assertTrue(drops.all { it.reason == "Haltebogen lässt sich nicht einheitlich versetzen" })
        assertTrue(problems.none { it.resolution != RetuneResolution.DROP })
        val r = s.dispatch(chip(g, standardE, RetuningPolicy.KEEP_SOUNDING_PITCH))
        assertEquals("Umstimmen nicht möglich: Ton E2 (Takt 1, Saite 1) im Haltebogen nicht spielbar", message(r))
        assertEquals(before, s.state.value.project)
        assertEquals("OK", message(s.dispatch(chip(g, standardE, RetuningPolicy.KEEP_SOUNDING_PITCH, allowDrop = true))))
        val beats = guitar(s.state.value.project).staves.first().bars[0].voices.first().beats
        assertTrue("the emptied origin beat is an explicit rest", beats[0].notes.isEmpty() && beats[0].explicitRest)
        assertEquals(listOf(40, 45, 50, 55, 59, 64), beats[1].notes.map { it.pitch.midiNumber }.sorted())
        assertTrue(beats.flatMap { it.notes }.none { n -> n.links.any { it.kind == LinkKind.TIE } })
        assertTrue(ScoreValidator.validate(s.state.value.project).none { it.severity == Severity.ERROR })
        assertTrue(s.undo() is Outcome.Success)
        assertSameContent("one undo restores tie and tuning", before, s.state.value.project)
    }

    // ---- kept behaviour

    /** A low A1 with a fifth and octave above (A1, D3, G3) retunes into every guitar preset: the A1 is octaved where it must. */
    @Test fun everyGuitarPresetTakesALowRiffWithoutDrops() {
        val s = session(at(0, 0), at(3, 0), at(4, 0))
        val original = s.state.value.project
        for (preset in TuningPresets.all.filter { it.kind == InstrumentKind.GUITAR && it.tuning != guitar(original).instrument.tuning }) {
            val g = guitar(s.state.value.project)
            val r = s.dispatch(chip(g, preset, RetuningPolicy.KEEP_SOUNDING_PITCH))
            assertEquals(preset.name, "OK", message(r))
            assertEquals(preset.name, 3, notes(s.state.value.project).size)
            assertTrue(ScoreValidator.validate(s.state.value.project).none { it.severity == Severity.ERROR })
            assertTrue(s.undo() is Outcome.Success)
            assertSameContent(preset.name, original, s.state.value.project)
        }
    }

    @Test fun emptyTrackMayStillSwitchBetweenDrumsAndPitched() {
        val s = EditorSession(ProjectFactory(ids).create("Kit"), ids)
        val drums = s.state.value.project.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }
        assertEquals("OK", message(s.dispatch(EditorCommand.SetInstrument(drums.id, InstrumentDefinition(kind = InstrumentKind.PIANO, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0), RetuningPolicy.KEEP_SOUNDING_PITCH))))
        s.select(Selection(drums.id, s.state.value.project.measures[0].id, 0, Fraction.ZERO, 0))
        assertEquals("OK", message(s.dispatch(EditorCommand.PutNote(midiNote = 60, duration = quarter))))
        val r = s.dispatch(EditorCommand.SetInstrument(drums.id, InstrumentDefinition(kind = InstrumentKind.PERCUSSION, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0), RetuningPolicy.KEEP_SOUNDING_PITCH))
        assertEquals("Umstimmen nicht möglich: Schlagzeug ↔ Tonhöheninstrument", message(r))
    }
}
