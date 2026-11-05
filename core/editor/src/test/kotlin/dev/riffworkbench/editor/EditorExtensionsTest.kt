package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorExtensionsTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("x-${sequence++}") }
    private fun session() = EditorSession(ProjectFactory(ids).demo("Edit"), ids)
    private fun EditorSession.at(offset: Fraction, string: Int = 0, measure: Int = 0) =
        select(state.value.selection.copy(measureId = state.value.project.measures[measure].id, offset = offset, string = string))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.voice(track: Int = 0, measure: Int = 0, voice: Int = 0) = state.value.project.tracks[track].staves[0].bars[measure].voices[voice]
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private fun valid(p: Project) = assertTrue(ScoreValidator.validate(p).filter { it.severity == Severity.ERROR }.toString(), ScoreValidator.validate(p).none { it.severity == Severity.ERROR })

    @Test fun articulationsToggleReplaceAndRespectExclusiveGroups() {
        val e = session(); e.ok(EditorCommand.PutNote(fret = 5))
        e.ok(EditorCommand.ApplyArticulation(Articulation.Mark(NoteMark.PULL_OFF)))
        e.ok(EditorCommand.ApplyArticulation(Articulation.Mark(NoteMark.HAMMER_ON)))
        e.ok(EditorCommand.ApplyArticulation(Articulations.fullBend()))
        e.ok(EditorCommand.ApplyArticulation(Articulations.halfBend()))
        val arts = e.voice().beats.single().notes.single().articulations
        assertEquals(listOf(Articulation.Mark(NoteMark.HAMMER_ON), Articulations.halfBend()), arts)
        e.ok(EditorCommand.ApplyArticulation(Articulations.halfBend()))
        assertEquals(listOf(Articulation.Mark(NoteMark.HAMMER_ON)), e.voice().beats.single().notes.single().articulations)
    }

    @Test fun tieContinuesPreviousNoteAcrossTheBarline() {
        val e = session(); e.at(f(15, 16)); e.ok(EditorCommand.PutNote(fret = 7))
        e.at(Fraction.ZERO, measure = 1); e.ok(EditorCommand.ToggleTie)
        val origin = e.voice(measure = 0).beats.single().notes.single()
        val tied = e.voice(measure = 1).beats.single().notes.single()
        assertEquals(listOf(NoteLink(LinkKind.TIE, tied.id)), origin.links)
        assertEquals(origin.pitch, tied.pitch); valid(e.state.value.project)
        e.ok(EditorCommand.ToggleTie)
        assertTrue(e.voice(measure = 0).beats.single().notes.single().links.isEmpty())
    }

    @Test fun durationChangesRippleInsideTheBarAndRefuseOverflow() {
        val e = session()
        listOf(0L, 1L, 2L).forEach { e.at(f(it, 16)); e.ok(EditorCommand.PutNote(fret = it.toInt())) }
        e.at(Fraction.ZERO); e.ok(EditorCommand.SetDuration(WrittenDuration(8)))
        assertEquals(listOf(f(0, 1), f(1, 8), f(3, 16)), e.voice().beats.map { it.offset })
        e.ok(EditorCommand.SetTuplet(TupletRatio(3, 2)))
        assertEquals(f(1, 12), e.voice().beats[0].duration.length())
        e.ok(EditorCommand.DeleteBeat)
        assertEquals(listOf(f(0, 1), f(1, 16)), e.voice().beats.map { it.offset })
        e.at(Fraction.ZERO); e.ok(EditorCommand.SetDuration(WrittenDuration(2)))
        assertTrue(e.dispatch(EditorCommand.SetDuration(WrittenDuration(1))) is Outcome.Failure)
    }

    @Test fun insertBeatShiftsLaterBeats() {
        val e = session(); e.at(Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 1)); e.at(f(1, 16)); e.ok(EditorCommand.PutNote(fret = 2))
        e.at(Fraction.ZERO); e.ok(EditorCommand.InsertBeat(WrittenDuration(8)))
        assertEquals(listOf(f(0, 1), f(1, 8), f(3, 16)), e.voice().beats.map { it.offset })
        assertTrue(e.voice().beats.first().explicitRest)
    }

    @Test fun moveToStringKeepsSoundingPitchAndTransposeFindsStrings() {
        val e = session(); e.at(Fraction.ZERO, string = 1); e.ok(EditorCommand.PutNote(fret = 5)) // A2 on E string
        e.ok(EditorCommand.MoveToString(2))
        val note = e.voice().beats.single().notes.single()
        assertEquals(2 to 0, note.string to note.fret); assertEquals(45, note.pitch.midiNumber)
        e.at(Fraction.ZERO, string = 2)
        assertTrue(e.dispatch(EditorCommand.Transpose(-14, EditScope.NOTE)) is Outcome.Failure) // G1 lies below the low A string
        e.ok(EditorCommand.Transpose(-10, EditScope.NOTE)) // B1 moves to the low A string, fret 2
        val moved = e.voice().beats.single().notes.single()
        assertEquals(0 to 2, moved.string to moved.fret)
        valid(e.state.value.project)
    }

    @Test fun transposeOutOfRangeFailsWithoutChange() {
        val e = session(); e.at(Fraction.ZERO, string = 0); e.ok(EditorCommand.PutNote(fret = 0))
        val before = e.state.value.project
        assertTrue(e.dispatch(EditorCommand.Transpose(-1, EditScope.NOTE)) is Outcome.Failure)
        assertEquals(before, e.state.value.project)
        e.ok(EditorCommand.Transpose(12, EditScope.TRACK))
        assertEquals(45, e.voice().beats.single().notes.single().pitch.midiNumber)
    }

    @Test fun measureInsertDeleteDuplicateKeepAlignmentTempoAndLinks() {
        val e = session(); val p0 = e.state.value.project
        e.at(f(15, 16)); e.ok(EditorCommand.PutNote(fret = 3)); e.at(Fraction.ZERO, measure = 1); e.ok(EditorCommand.ToggleTie)
        e.ok(EditorCommand.InsertMeasures(1, 2))
        val inserted = e.state.value.project.measures.subList(1, 3).map { it.id }
        assertEquals(4, e.state.value.project.measures.size)
        valid(e.state.value.project)
        e.ok(EditorCommand.SetTempoAt(inserted[0], 180.0))
        e.ok(EditorCommand.DeleteMeasures(listOf(inserted[0])))
        // The tempo in effect moves to the next kept bar.
        assertEquals(TempoChange(ScorePosition(inserted[1]), 180.0), e.state.value.project.tempo.last())
        assertFalse(e.state.value.project.tracks[0].staves[0].bars[0].voices[0].beats.single().notes.single().links.isEmpty())
        e.ok(EditorCommand.DeleteMeasures(listOf(p0.measures[1].id)))
        val p = e.state.value.project
        valid(p)
        assertTrue(p.tracks[0].staves[0].bars[0].voices[0].beats.single().notes.single().links.isEmpty())
        e.ok(EditorCommand.DuplicateMeasures(listOf(p.measures[0].id)))
        val d = e.state.value.project
        assertEquals(3, d.measures.size); valid(d)
        assertEquals(d.tracks[0].staves[0].bars[0].voices[0].beats.single().notes.single().fret, d.tracks[0].staves[0].bars[1].voices[0].beats.single().notes.single().fret)
        assertNotEquals(d.tracks[0].staves[0].bars[0].voices[0].beats.single().notes.single().id, d.tracks[0].staves[0].bars[1].voices[0].beats.single().notes.single().id)
    }

    @Test fun pasteBetweenSixAndSevenStringTracksShiftsStrings() {
        val e = session()
        val six = ProjectFactory(ids).track(InstrumentKind.GUITAR, e.state.value.project.measures, "Sechs").let { it.copy(instrument = it.instrument.copy(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = List(6) { 0 })) }
        e.ok(EditorCommand.Replace(e.state.value.project.copy(tracks = e.state.value.project.tracks + six)))
        e.at(Fraction.ZERO, string = 6); e.ok(EditorCommand.PutNote(fret = 0)) // high E on the 7-string
        val clip = BarClipboard.copy(e.state.value.project, listOf(e.state.value.project.measures[0].id), listOf(e.state.value.project.tracks[0].id))
        e.ok(EditorCommand.PasteBars(clip, e.state.value.project.measures[1].id, six.id))
        val pasted = e.state.value.project.tracks.last().staves[0].bars[1].voices[0].beats.single().notes.single()
        assertEquals(5 to 64, pasted.string to pasted.pitch.midiNumber)
        assertEquals(TimeSignature(4, 4), e.state.value.project.measures[1].signature)
        valid(e.state.value.project)
    }

    @Test fun retuneKeepsPitchOrFingerAsRequested() {
        val e = session(); e.at(Fraction.ZERO, string = 1); e.ok(EditorCommand.PutNote(fret = 2)) // F#2 on E2 string
        val track = e.state.value.project.tracks[0]
        val dropG = track.instrument.copy(tuning = listOf(31, 38, 43, 48, 53, 57, 62))
        e.ok(EditorCommand.SetInstrument(track.id, dropG, RetuningPolicy.KEEP_SOUNDING_PITCH))
        val kept = e.voice().beats.single().notes.single()
        assertEquals(42, kept.pitch.midiNumber); assertEquals(1 to 4, kept.string to kept.fret)
        e.undo()
        e.ok(EditorCommand.SetInstrument(track.id, dropG, RetuningPolicy.KEEP_FINGER_POSITION))
        assertEquals(40, e.voice().beats.single().notes.single().pitch.midiNumber)
        e.undo()
        val eight = track.instrument.copy(tuning = listOf(28, 33, 40, 45, 50, 55, 59, 64), capoByString = List(8) { 0 })
        e.ok(EditorCommand.SetInstrument(track.id, eight, RetuningPolicy.KEEP_FINGER_POSITION))
        assertEquals(2, e.voice().beats.single().notes.single().string)
        valid(e.state.value.project)
    }

    @Test fun capoChangeWithSoundingPitchLowersFrets() {
        val e = session(); e.at(Fraction.ZERO, string = 3); e.ok(EditorCommand.PutNote(fret = 5))
        val t = e.state.value.project.tracks[0]
        e.ok(EditorCommand.SetInstrument(t.id, t.instrument.copy(capoByString = List(7) { 2 }), RetuningPolicy.KEEP_SOUNDING_PITCH))
        assertEquals(3, e.voice().beats.single().notes.single().fret)
    }

    @Test fun repeatsTempoSectionsAndValidation() {
        val e = session(); val m = e.state.value.project.measures
        e.ok(EditorCommand.SetRepeat(m[0].id, RepeatMark(starts = true, endCount = 2)))
        assertTrue(e.dispatch(EditorCommand.SetRepeat(m[0].id, RepeatMark(endCount = 99))) is Outcome.Failure)
        e.ok(EditorCommand.SetSection(m[1].id, "Breakdown"))
        e.ok(EditorCommand.SetSection(m[0].id, null))
        assertEquals(listOf("Breakdown"), e.state.value.project.sections.map { it.label })
        assertTrue(e.dispatch(EditorCommand.SetTempoAt(m[0].id, null)) is Outcome.Failure)
        e.ok(EditorCommand.SetKey(m[0].id, KeySignature(-3, true)))
        e.ok(EditorCommand.SetFeel(m[1].id, RhythmicFeel.SWING_EIGHTH))
        valid(e.state.value.project)
    }

    @Test fun duplicateTrackGetsFreshIdsAndValidates() {
        val e = session(); e.ok(EditorCommand.PutNote(fret = 3))
        e.ok(EditorCommand.DuplicateTrack(e.state.value.project.tracks[0].id))
        val p = e.state.value.project
        assertEquals(4, p.tracks.size); assertTrue(p.tracks[1].name.endsWith("(Kopie)")); valid(p)
    }

    @Test fun chordLibraryFindsOpenAndPowerChordShapes() = runBlocking {
        val lib = BuiltInChordLibrary()
        val standard = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64))
        val am = (lib.search("Am", standard) as Outcome.Success).value
        assertTrue(am.any { it.frets == listOf(null, 0, 2, 2, 1, 0) })
        val dropA = InstrumentDefinition()
        val a5 = (lib.search("A5", dropA) as Outcome.Success).value
        assertTrue(a5.isNotEmpty() && a5.all { it.pitches.min() % 12 == 9 })
        assertTrue(lib.search("Xm", standard) is Outcome.Failure)
        assertTrue(((BuiltInScaleLibrary().search("Penta") as Outcome.Success).value).size == 2)
    }

    @Test fun advancedEditorProposesDetachedCandidates() = runBlocking {
        val p = ProjectFactory(ids).create("Proposal")
        val proposal = (DefaultAdvancedEditor(ids).propose(p, AdvancedEdit.DuplicateTrack(p.tracks[0].id)) as Outcome.Success).value
        assertEquals(p.revision, proposal.baseRevision)
        assertEquals(4, proposal.candidate.tracks.size)
        assertEquals(3, p.tracks.size)
    }
}
