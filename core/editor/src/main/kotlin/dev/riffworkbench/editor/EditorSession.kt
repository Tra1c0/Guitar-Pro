package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Selection(val trackId: EntityId, val measureId: EntityId, val voice: Int = 0, val offset: Fraction = Fraction.ZERO, val string: Int = 0)
data class EditorState(val project: Project, val selection: Selection, val canUndo: Boolean = false, val canRedo: Boolean = false, val diagnostics: List<Diagnostic> = emptyList())

sealed interface EditorCommand {
    data class PutNote(val fret: Int? = null, val midiNote: Int? = null, val duration: WrittenDuration = WrittenDuration(), val marks: Set<NoteMark> = emptySet()) : EditorCommand
    data class ToggleDrum(val midiNote: Int, val duration: WrittenDuration = WrittenDuration()) : EditorCommand
    data class PutRest(val duration: WrittenDuration) : EditorCommand
    data object Delete : EditorCommand
    data class SetDuration(val duration: WrittenDuration) : EditorCommand
    data class ToggleMark(val kind: NoteMark) : EditorCommand
    data class AddTrack(val kind: InstrumentKind) : EditorCommand
    data class RemoveTrack(val id: EntityId) : EditorCommand
    data class MoveTrack(val id: EntityId, val delta: Int) : EditorCommand
    data class AddMeasure(val signature: TimeSignature) : EditorCommand
    data class SetSignature(val measureId: EntityId, val signature: TimeSignature) : EditorCommand
    data class Rename(val title: String) : EditorCommand
    data class SetTempo(val bpm: Double) : EditorCommand
    data class SetMix(val trackId: EntityId, val mix: ChannelMix) : EditorCommand
    data class Replace(val project: Project, val expectedRevision: Long? = null) : EditorCommand
}

/** Single-owner dispatcher: call selection + commands on the same UI dispatcher. No adapter mutates this state. */
class EditorSession(project: Project, private val ids: IdSource, private val historyLimit: Int = 200) {
    private data class History(val project: Project, val selection: Selection)
    private val undo = ArrayDeque<History>()
    private val redo = ArrayDeque<History>()
    private val mutable = MutableStateFlow(EditorState(project, initialSelection(project), diagnostics = ScoreValidator.validate(project)))
    val state: StateFlow<EditorState> = mutable.asStateFlow()

    fun select(selection: Selection) {
        val p = state.value.project
        require(p.tracks.any { it.id == selection.trackId } && p.measures.any { it.id == selection.measureId })
        require(selection.voice in 0..3 && selection.offset >= Fraction.ZERO)
        val track = p.tracks.first { it.id == selection.trackId }
        require(selection.string in 0 until maxOf(1, track.instrument.tuning.size))
        mutable.value = state.value.copy(selection = selection)
    }

    fun dispatch(command: EditorCommand): Outcome<Unit> = try {
        val before = state.value
        var after = apply(before.project, before.selection, command)
        if (after == before.project) Outcome.Success(Unit) else {
            require(after.id == before.project.id) { "Projekt-ID darf nicht geändert werden" }
            require(before.project.revision < Long.MAX_VALUE) { "Revisionslimit erreicht" }
            after = after.copy(revision = before.project.revision + 1)
            val diagnostics = ScoreValidator.validate(after)
            if (diagnostics.any { it.severity == Severity.ERROR }) Outcome.Failure(FailureCode.VALIDATION, diagnostics.first { it.severity == Severity.ERROR }.message, diagnostics)
            else {
                undo.addLast(History(before.project, before.selection))
                while (undo.size > historyLimit) undo.removeFirst()
                redo.clear()
                mutable.value = EditorState(after, validSelection(after, before.selection), undo.isNotEmpty(), false, diagnostics)
                Outcome.Success(Unit)
            }
        }
    } catch (e: IllegalArgumentException) { Outcome.Failure(FailureCode.VALIDATION, e.message ?: "Ungültige Änderung") }

    fun undo(): Outcome<Unit> = travel(undo, redo)
    fun redo(): Outcome<Unit> = travel(redo, undo)
    private fun travel(from: ArrayDeque<History>, to: ArrayDeque<History>): Outcome<Unit> {
        if (from.isEmpty()) return Outcome.Failure(FailureCode.VALIDATION, "Keine weitere Änderung")
        val current = state.value
        val next = from.removeLast()
        to.addLast(History(current.project, current.selection))
        val p = next.project.copy(revision = current.project.revision + 1)
        mutable.value = EditorState(p, validSelection(p, next.selection), undo.isNotEmpty(), redo.isNotEmpty(), ScoreValidator.validate(p))
        return Outcome.Success(Unit)
    }

    private fun apply(p: Project, s: Selection, c: EditorCommand): Project = when (c) {
        is EditorCommand.Rename -> { require(c.title.isNotBlank() && c.title.length <= 200); p.copy(metadata = p.metadata.copy(title = c.title.trim())) }
        is EditorCommand.SetTempo -> {
            require(c.bpm.isFinite() && c.bpm in 10.0..1000.0)
            val origin = ScorePosition(p.measures.first().id)
            p.copy(tempo = listOf(TempoChange(origin, c.bpm)) + p.tempo.filterNot { it.position == origin })
        }
        is EditorCommand.AddTrack -> p.copy(tracks = p.tracks + ProjectFactory(ids).track(c.kind, p.measures))
        is EditorCommand.RemoveTrack -> {
            require(p.tracks.size > 1) { "Mindestens eine Spur behalten" }
            require(p.tracks.any { it.id == c.id })
            require(p.mix.buses.none { c.id in it.inputTrackIds }) { "Bus-Routing zuerst entfernen" }
            p.copy(tracks = p.tracks.filterNot { it.id == c.id })
        }
        is EditorCommand.MoveTrack -> {
            val tracks = p.tracks.toMutableList()
            val index = tracks.indexOfFirst { it.id == c.id }; require(index >= 0)
            val target = (index + c.delta).coerceIn(tracks.indices)
            val track = tracks.removeAt(index); tracks.add(target, track)
            p.copy(tracks = tracks)
        }
        is EditorCommand.AddMeasure -> {
            val measure = Measure(ids.next(), c.signature)
            p.copy(measures = p.measures + measure, tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { staff -> staff.copy(bars = staff.bars + ProjectFactory(ids).bar(measure.id)) }) })
        }
        is EditorCommand.SetSignature -> p.copy(measures = p.measures.map { if (it.id == c.measureId) it.copy(signature = c.signature) else it })
        is EditorCommand.SetMix -> {
            require(c.mix.volume.isFinite() && c.mix.volume in 0.0..1.0 && c.mix.pan.isFinite() && c.mix.pan in -1.0..1.0)
            p.copy(tracks = p.tracks.map { if (it.id == c.trackId) it.copy(mix = c.mix) else it })
        }
        is EditorCommand.Replace -> { require(c.expectedRevision == null || c.expectedRevision == p.revision) { "Partitur wurde seit der Vorschau geändert" }; c.project.copy(id = p.id) }
        else -> updateBeat(p, s, createIfMissing = c != EditorCommand.Delete) { beat, track ->
            when (c) {
                is EditorCommand.PutNote -> {
                    val note = if (track.instrument.tuning.isNotEmpty()) {
                        val fret = requireNotNull(c.fret) { "Bund fehlt" }
                        require(fret in 0..track.instrument.maxFret) { "Bund außerhalb des Instruments" }
                        val previous = beat.notes.firstOrNull { it.string == s.string }
                        val pitch = Pitch(track.instrument.pitch(s.string, fret), previous?.pitch?.cents ?: 0)
                        val effects = ((previous?.articulations ?: emptyList()) + c.marks.map { Articulation.Mark(it) }).distinct()
                        previous?.copy(pitch = if (previous.pitch.midiNumber == pitch.midiNumber) previous.pitch else pitch, string = s.string, fret = fret, articulations = effects)
                            ?: Note(ids.next(), pitch, s.string, fret, articulations = effects)
                    } else {
                        val midi = c.midiNote ?: 60
                        require(midi in 0..127)
                        val previous = beat.notes.firstOrNull { it.pitch.midiNumber == midi }
                        previous?.copy(articulations = (previous.articulations + c.marks.map { Articulation.Mark(it) }).distinct())
                            ?: Note(ids.next(), Pitch(midi), articulations = c.marks.map { Articulation.Mark(it) })
                    }
                    beat.copy(notes = beat.notes.filterNot { if (note.string != null) it.string == note.string else it.pitch.midiNumber == note.pitch.midiNumber } + note, duration = c.duration, explicitRest = false)
                }
                is EditorCommand.PutRest -> beat.copy(notes = emptyList(), explicitRest = true, duration = c.duration)
                is EditorCommand.ToggleDrum -> {
                    require(track.instrument.kind == InstrumentKind.PERCUSSION && c.midiNote in 0..127)
                    val notes = if (beat.notes.any { it.pitch.midiNumber == c.midiNote }) beat.notes.filterNot { it.pitch.midiNumber == c.midiNote }
                        else beat.notes + Note(ids.next(), Pitch(c.midiNote))
                    beat.copy(notes = notes, duration = c.duration, explicitRest = notes.isEmpty())
                }
                EditorCommand.Delete -> {
                    val remaining = if (track.instrument.tuning.isNotEmpty()) beat.notes.filterNot { it.string == s.string } else emptyList()
                    beat.copy(notes = remaining, explicitRest = remaining.isEmpty())
                }
                is EditorCommand.SetDuration -> beat.copy(duration = c.duration)
                is EditorCommand.ToggleMark -> {
                    require(beat.notes.isNotEmpty()) { "Zuerst eine Note wählen" }
                    beat.copy(notes = beat.notes.map { note ->
                        if (track.instrument.tuning.isNotEmpty() && note.string != s.string) note else {
                            val mark = Articulation.Mark(c.kind)
                            note.copy(articulations = if (mark in note.articulations) note.articulations - mark else note.articulations + mark)
                        }
                    })
                }
                else -> beat
            }
        }
    }

    private fun updateBeat(p: Project, s: Selection, createIfMissing: Boolean = true, transform: (Beat, Track) -> Beat): Project = p.copy(tracks = p.tracks.map { track ->
        if (track.id != s.trackId) track else track.copy(staves = track.staves.mapIndexed { staffIndex, staff ->
            if (staffIndex != 0) staff else staff.copy(bars = staff.bars.map { bar ->
                if (bar.measureId != s.measureId) bar else bar.copy(voices = bar.voices.map { voice ->
                    if (voice.index != s.voice) voice else {
                        val old = voice.beats.firstOrNull { it.offset == s.offset }
                        if (old == null && !createIfMissing) voice else {
                            val next = transform(old ?: Beat(ids.next(), s.offset), track)
                            voice.copy(beats = (voice.beats.filterNot { it.offset == s.offset } + next).sortedBy { it.offset })
                        }
                    }
                })
            })
        })
    })

    companion object {
        private fun initialSelection(p: Project) = Selection(p.tracks.first().id, p.measures.first().id)
        private fun validSelection(p: Project, s: Selection): Selection {
            val track = p.tracks.firstOrNull { it.id == s.trackId } ?: p.tracks.first()
            val measure = p.measures.firstOrNull { it.id == s.measureId } ?: p.measures.first()
            return s.copy(trackId = track.id, measureId = measure.id, offset = if (s.offset < measure.signature.length()) s.offset else Fraction.ZERO, string = s.string.coerceIn(0, maxOf(0, track.instrument.tuning.lastIndex)))
        }
    }
}
