package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

class EditorSessionTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("id-${sequence++}") }
    private fun session() = EditorSession(ProjectFactory(ids).demo("Metal"), ids)
    private fun notes(p: Project) = p.tracks.flatMap { it.staves }.flatMap { it.bars }.flatMap { it.voices }.flatMap { it.beats }.flatMap { it.notes }

    @Test fun noteEntryUndoRedoRestoresMusicWithMonotonicRevision() {
        val editor = session(); val original = editor.state.value.project
        assertTrue(editor.dispatch(EditorCommand.PutNote(fret = 12, marks = setOf(NoteMark.PALM_MUTE))) is Outcome.Success)
        val edited = editor.state.value.project
        assertEquals(45, notes(edited).single().pitch.midiNumber)
        assertEquals(12, notes(edited).single().fret)
        editor.undo()
        assertEquals(original, editor.state.value.project.copy(revision = original.revision))
        editor.redo()
        assertEquals(edited, editor.state.value.project.copy(revision = edited.revision))
        assertEquals(3, editor.state.value.project.revision)
    }
    @Test fun fourVoicesRemainIndependent() {
        val editor = session()
        (0..3).forEach { voice -> editor.select(editor.state.value.selection.copy(voice = voice)); editor.dispatch(EditorCommand.PutNote(fret = voice)) }
        assertEquals(listOf(0, 1, 2, 3), notes(editor.state.value.project).map { it.fret })
        editor.undo()
        assertEquals(listOf(0, 1, 2), notes(editor.state.value.project).map { it.fret })
    }
    @Test fun chordEntryReplacesOnlyTheSameString() {
        val editor = session()
        editor.dispatch(EditorCommand.PutNote(fret = 2))
        val id = notes(editor.state.value.project).single().id
        editor.dispatch(EditorCommand.PutNote(fret = 3))
        assertEquals(id, notes(editor.state.value.project).single().id)
        editor.select(editor.state.value.selection.copy(string = 1))
        editor.dispatch(EditorCommand.PutNote(fret = 4))
        assertEquals(2, notes(editor.state.value.project).size)
    }
    @Test fun invalidPositionsAndOverlapsDoNotMutateHistory() {
        val editor = session()
        assertTrue(editor.dispatch(EditorCommand.PutNote(fret = 99)) is Outcome.Failure)
        assertFalse(editor.state.value.canUndo)
        editor.dispatch(EditorCommand.PutNote(fret = 0, duration = WrittenDuration(4)))
        val p = editor.state.value.project
        editor.select(editor.state.value.selection.copy(offset = Fraction.of(1, 16)))
        assertTrue(editor.dispatch(EditorCommand.PutNote(fret = 0)) is Outcome.Failure)
        assertEquals(p, editor.state.value.project)
    }
    @Test fun freeDraftAllowsUnderfullBarsAndStrictValidationReportsThem() {
        val editor = session(); editor.dispatch(EditorCommand.PutNote(fret = 0))
        assertTrue(editor.state.value.diagnostics.any { it.code == "incomplete_bar" && it.severity == Severity.WARNING })
        assertTrue(ScoreValidator.validate(editor.state.value.project, true).any { it.code == "incomplete_bar" && it.severity == Severity.ERROR })
    }
    @Test fun signatureChangeCannotSilentlyTruncateNotes() {
        val editor = session(); editor.select(editor.state.value.selection.copy(offset = Fraction.of(15, 16)))
        editor.dispatch(EditorCommand.PutNote(fret = 2))
        assertTrue(editor.dispatch(EditorCommand.SetSignature(editor.state.value.selection.measureId, TimeSignature(7, 8))) is Outcome.Failure)
    }
    @Test fun drumsToggleOneElementWithoutDeletingTheChord() {
        val editor = session(); val drum = editor.state.value.project.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }
        editor.select(editor.state.value.selection.copy(trackId = drum.id))
        editor.dispatch(EditorCommand.ToggleDrum(36)); editor.dispatch(EditorCommand.ToggleDrum(42)); editor.dispatch(EditorCommand.ToggleDrum(36))
        assertEquals(listOf(42), notes(editor.state.value.project).map { it.pitch.midiNumber })
        editor.undo()
        assertEquals(setOf(36, 42), notes(editor.state.value.project).map { it.pitch.midiNumber }.toSet())
    }
    @Test fun addingMeasureMaintainsAlignmentAcrossAllTracksAndVoices() {
        val editor = session(); editor.dispatch(EditorCommand.AddMeasure(TimeSignature(7, 8)))
        val project = editor.state.value.project
        assertTrue(project.tracks.all { it.staves.all { it.bars.map { b -> b.measureId } == project.measures.map(Measure::id) } })
        assertTrue(ScoreValidator.validate(project).none { it.severity == Severity.ERROR })
    }
    @Test fun staleDetachedProposalIsRejected() {
        val editor = session(); val proposal = editor.state.value.project
        editor.dispatch(EditorCommand.Rename("Changed"))
        assertTrue(editor.dispatch(EditorCommand.Replace(proposal, 0)) is Outcome.Failure)
        assertEquals("Changed", editor.state.value.project.metadata.title)
    }
    @Test fun newEditAfterUndoDiscardsRedoBranch() {
        val editor = session(); editor.dispatch(EditorCommand.PutNote(fret = 2)); editor.undo(); editor.dispatch(EditorCommand.PutNote(fret = 3))
        assertFalse(editor.state.value.canRedo)
    }
    @Test fun lastTrackCannotBeRemovedAndMixIsUndoable() {
        val editor = session(); val tracks = editor.state.value.project.tracks
        editor.dispatch(EditorCommand.RemoveTrack(tracks[2].id)); editor.dispatch(EditorCommand.RemoveTrack(tracks[1].id))
        assertTrue(editor.dispatch(EditorCommand.RemoveTrack(tracks[0].id)) is Outcome.Failure)
        editor.dispatch(EditorCommand.SetMix(tracks[0].id, ChannelMix(volume = 0.4, solo = true)))
        assertEquals(0.4, editor.state.value.project.tracks[0].mix.volume, 0.0)
        editor.undo(); assertEquals(0.8, editor.state.value.project.tracks[0].mix.volume, 0.0)
    }
    @Test fun cancelledGestureNeverPublishesAndOneCommitMeansOneUndo() {
        val editor = session(); val original = editor.state.value.project
        val drag = GestureTransaction(); drag.arm(editor.state.value.selection); drag.move(editor.state.value.selection.copy(offset = Fraction.of(1, 16)))
        drag.secondPointer(); assertTrue(drag.commit(EditorCommand.PutNote(fret = 1)) is Outcome.Failure)
        assertEquals(original, editor.state.value.project)
        drag.arm(editor.state.value.selection); drag.move(editor.state.value.selection)
        val committed = drag.commit(EditorCommand.PutNote(fret = 1)) as Outcome.Success
        editor.dispatch(committed.value); editor.undo()
        assertEquals(original, editor.state.value.project.copy(revision = original.revision))
    }
    @Test fun largeArrangementHasStableUniqueAlignedIdentity() {
        val project = largeFixture(ids)
        assertEquals(200, project.measures.size); assertEquals(8, project.tracks.size)
        assertTrue(ScoreValidator.validate(project).isEmpty())
    }
    @Test fun initialTempoEditKeepsLaterTempoChanges() {
        val base = ProjectFactory(ids).demo("Tempo")
        val later = TempoChange(ScorePosition(base.measures[1].id), 180.0)
        val editor = EditorSession(base.copy(tempo = base.tempo + later), ids)
        editor.dispatch(EditorCommand.SetTempo(160.0))
        assertEquals(160.0, editor.state.value.project.tempo.first().quarterNotesPerMinute, 0.0)
        assertEquals(later, editor.state.value.project.tempo[1])
    }
    @Test fun createReorderAndRemoveTracksAreTransactions() {
        val editor = session(); editor.dispatch(EditorCommand.AddTrack(InstrumentKind.PIANO))
        val piano = editor.state.value.project.tracks.last()
        assertEquals(InstrumentKind.PIANO, piano.instrument.kind)
        editor.dispatch(EditorCommand.MoveTrack(piano.id, -3))
        assertEquals(piano.id, editor.state.value.project.tracks.first().id)
        editor.undo(); assertEquals(piano.id, editor.state.value.project.tracks.last().id)
        editor.dispatch(EditorCommand.RemoveTrack(piano.id)); assertEquals(3, editor.state.value.project.tracks.size)
    }
    @Test fun restAndDurationAreUndoableWithoutASecondModel() {
        val editor = session(); editor.dispatch(EditorCommand.PutNote(fret = 3))
        editor.dispatch(EditorCommand.SetDuration(WrittenDuration(8, 1)))
        editor.dispatch(EditorCommand.PutRest(WrittenDuration(8, 1)))
        val beat = editor.state.value.project.tracks.first().staves.first().bars.first().voices.first().beats.single()
        assertTrue(beat.explicitRest); assertTrue(beat.notes.isEmpty()); assertEquals(Fraction.of(3, 16), beat.duration.length())
        editor.undo(); assertEquals(3, notes(editor.state.value.project).single().fret)
    }
    @Test fun deletingAnEmptyCellDoesNotCreateASavedRest() {
        val editor = session(); val original = editor.state.value.project
        editor.dispatch(EditorCommand.Delete)
        assertEquals(original, editor.state.value.project); assertFalse(editor.state.value.canUndo)
    }
    private fun linkedProject(kind: LinkKind): Project {
        val base = ProjectFactory(ids).demo("Links")
        val target = Note(EntityId("target-note"), Pitch(33), 0, 0)
        val source = Note(EntityId("source-note"), Pitch(33), 0, 0, velocity = 71, links = listOf(NoteLink(kind, target.id)), fingering = Fingering(2, "p"))
        val track = base.tracks.first(); val staff = track.staves.first(); val bar = staff.bars.first()
        val voice = bar.voices.first().copy(beats = listOf(Beat(ids.next(), Fraction.ZERO, notes = listOf(source)), Beat(ids.next(), Fraction.of(1, 16), notes = listOf(target))))
        val changed = track.copy(staves = listOf(staff.copy(bars = listOf(bar.copy(voices = listOf(voice) + bar.voices.drop(1))) + staff.bars.drop(1))))
        return base.copy(tracks = listOf(changed) + base.tracks.drop(1))
    }
    @Test fun editingAFretPreservesVelocityFingeringAndLinks() {
        val editor = EditorSession(linkedProject(LinkKind.SLUR), ids)
        assertTrue(editor.dispatch(EditorCommand.PutNote(fret = 1)) is Outcome.Success)
        val note = notes(editor.state.value.project).first()
        assertEquals(71, note.velocity); assertEquals(Fingering(2, "p"), note.fingering)
        assertEquals(listOf(NoteLink(LinkKind.SLUR, EntityId("target-note"))), note.links)
    }
    @Test fun editingATiedPitchCannotSilentlyBreakItsLink() {
        val project = linkedProject(LinkKind.TIE); val editor = EditorSession(project, ids)
        assertTrue(editor.dispatch(EditorCommand.PutNote(fret = 1)) is Outcome.Failure)
        assertEquals(project, editor.state.value.project)
    }
}
