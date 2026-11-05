package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cursor plus optional bar range ([rangeEnd] inclusive, may lie before [measureId]). */
data class Selection(val trackId: EntityId, val measureId: EntityId, val voice: Int = 0, val offset: Fraction = Fraction.ZERO, val string: Int = 0, val rangeEnd: EntityId? = null) {
    fun measureRange(p: Project): List<EntityId> {
        val a = p.measures.indexOfFirst { it.id == measureId }
        val b = rangeEnd?.let { end -> p.measures.indexOfFirst { it.id == end } }?.takeIf { it >= 0 } ?: a
        if (a < 0) return emptyList()
        return p.measures.subList(minOf(a, b), maxOf(a, b) + 1).map { it.id }
    }
}
enum class EditScope { NOTE, BEAT, MEASURES, TRACK }
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
    // Notes and beats at the selection
    /** Adds the articulation to the selected note(s); replaces one of the same kind; with [toggle] an equal one is removed. */
    data class ApplyArticulation(val articulation: Articulation, val toggle: Boolean = true) : EditorCommand
    data class ClearArticulation(val slot: String) : EditorCommand
    /** GP-style: the selected note becomes tied from the previous note on the same string (and takes its pitch). */
    data object ToggleTie : EditorCommand
    data class SetVelocity(val velocity: Int) : EditorCommand
    /** Moves the selected note to another string keeping its sounding pitch. */
    data class MoveToString(val string: Int) : EditorCommand
    data class Transpose(val semitones: Int, val scope: EditScope) : EditorCommand
    data class SetTuplet(val ratio: TupletRatio?) : EditorCommand
    data class SetFermata(val fermata: Fermata?) : EditorCommand
    data class SetStrum(val strum: Strum?) : EditorCommand
    /** Removes the selected beat and moves later beats of the voice earlier. */
    data object DeleteBeat : EditorCommand
    /** Inserts a rest at the selection and moves later beats of the voice later (fails if the bar overflows). */
    data class InsertBeat(val duration: WrittenDuration) : EditorCommand
    data class SetText(val text: String?, val kind: TextKind = TextKind.TEXT) : EditorCommand
    // Measures (all tracks)
    data class InsertMeasures(val index: Int, val count: Int = 1, val signature: TimeSignature? = null) : EditorCommand
    data class DeleteMeasures(val ids: List<EntityId>) : EditorCommand
    data class DuplicateMeasures(val ids: List<EntityId>) : EditorCommand
    data class PasteBars(val content: BarClipboard, val targetMeasure: EntityId, val targetTrack: EntityId) : EditorCommand
    data class ClearBars(val ids: List<EntityId>, val trackId: EntityId?) : EditorCommand
    data class SetRepeat(val measureId: EntityId, val repeat: RepeatMark) : EditorCommand
    data class SetNavigation(val measureId: EntityId, val marks: List<NavigationMark>) : EditorCommand
    data class SetKey(val measureId: EntityId, val key: KeySignature) : EditorCommand
    data class SetFeel(val measureId: EntityId, val feel: RhythmicFeel) : EditorCommand
    data class SetTempoAt(val measureId: EntityId, val bpm: Double?) : EditorCommand
    data class SetSection(val measureId: EntityId, val label: String?) : EditorCommand
    // Tracks and project
    data class DuplicateTrack(val id: EntityId) : EditorCommand
    data class RenameTrack(val id: EntityId, val name: String) : EditorCommand
    data class SetInstrument(val id: EntityId, val instrument: InstrumentDefinition, val policy: RetuningPolicy) : EditorCommand
    data class SetSound(val id: EntityId, val sound: SoundAssignment) : EditorCommand
    data class SetMetadata(val metadata: ProjectMetadata) : EditorCommand
    data class SetMasterMix(val mix: ChannelMix) : EditorCommand
    // Reference audio
    data class AddAudioClip(val clip: AudioClip, val asset: AssetDescriptor) : EditorCommand
    data class UpdateAudioClip(val clip: AudioClip) : EditorCommand
    data class RemoveAudioClip(val id: EntityId) : EditorCommand
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
        require(selection.rangeEnd == null || p.measures.any { it.id == selection.rangeEnd })
        mutable.value = state.value.copy(selection = selection)
    }

    fun dispatch(command: EditorCommand): Outcome<Unit> = try {
        val before = state.value
        var after = apply(before.project, before.selection, command)
        // Any edit that removes notes (delete, rest over a note, drum toggle) drops the links pointing at them, like Delete.
        // Re-pitching one end of a tie stays rejected by the validator (deliberate: ties are never broken silently).
        if (after != before.project) after = Links.prune(after)
        if (after != before.project) requireTiesStayOnTheirString(before.project, after)
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
        is EditorCommand.ApplyArticulation -> {
            (c.articulation as? Articulation.Vibrato)?.let { require(it.rateHz.isFinite() && it.rateHz in 0.1..20.0 && it.depthCents in 0..1200) { "Vibrato-Werte ungültig" } }
            NoteEdits.applyArticulation(p, s, c.articulation, c.toggle)
        }
        is EditorCommand.ClearArticulation -> NoteEdits.clearArticulation(p, s, c.slot)
        EditorCommand.ToggleTie -> NoteEdits.toggleTie(p, s, ids)
        is EditorCommand.SetVelocity -> NoteEdits.setVelocity(p, s, c.velocity)
        is EditorCommand.MoveToString -> NoteEdits.moveToString(p, s, c.string)
        is EditorCommand.Transpose -> NoteEdits.transpose(p, s, c.semitones, c.scope)
        is EditorCommand.SetTuplet -> NoteEdits.setDurationRipple(p, s) { it.copy(tuplets = listOfNotNull(c.ratio)) }
        is EditorCommand.SetFermata -> {
            c.fermata?.let { require(it.multiplier.isFinite() && it.multiplier in 0.1..8.0) { "Fermatendauer ungültig" } }
            NoteEdits.updateSelectedBeat(p, s) { it.copy(fermata = c.fermata) }
        }
        is EditorCommand.SetStrum -> NoteEdits.updateSelectedBeat(p, s) { it.copy(strum = c.strum) }
        EditorCommand.DeleteBeat -> NoteEdits.deleteBeat(p, s)
        is EditorCommand.InsertBeat -> NoteEdits.insertBeat(p, s, c.duration, ids)
        is EditorCommand.SetText -> NoteEdits.setText(p, s, c.text, c.kind, ids)
        is EditorCommand.InsertMeasures -> StructureEdits.insert(p, c.index, c.count, c.signature, ids)
        is EditorCommand.DeleteMeasures -> StructureEdits.delete(p, c.ids)
        is EditorCommand.DuplicateMeasures -> StructureEdits.duplicate(p, c.ids, ids)
        is EditorCommand.PasteBars -> StructureEdits.paste(p, c.content, c.targetMeasure, c.targetTrack, ids)
        is EditorCommand.ClearBars -> StructureEdits.clear(p, c.ids, c.trackId)
        is EditorCommand.SetRepeat -> StructureEdits.setRepeat(p, c.measureId, c.repeat)
        is EditorCommand.SetNavigation -> {
            require(c.marks.mapNotNull { it.target }.all { t -> p.measures.any { it.id == t } }) { "Sprungziel existiert nicht" }
            StructureEdits.updateMeasure(p, c.measureId) { it.copy(navigation = c.marks.distinctBy { m -> m.kind }) }
        }
        is EditorCommand.SetKey -> { require(c.key.fifths in -7..7) { "Tonart außerhalb von 7 Vorzeichen" }; StructureEdits.updateMeasure(p, c.measureId) { it.copy(key = c.key) } }
        is EditorCommand.SetFeel -> StructureEdits.updateMeasure(p, c.measureId) { it.copy(feel = c.feel) }
        is EditorCommand.SetTempoAt -> StructureEdits.setTempoAt(p, c.measureId, c.bpm)
        is EditorCommand.SetSection -> StructureEdits.setSection(p, c.measureId, c.label, ids)
        is EditorCommand.DuplicateTrack -> TrackEdits.duplicate(p, c.id, ids)
        is EditorCommand.RenameTrack -> { require(c.name.isNotBlank() && c.name.length <= 120) { "Spurname ungültig" }; TrackEdits.update(p, c.id) { it.copy(name = c.name.trim()) } }
        is EditorCommand.SetInstrument -> {
            // Non-finite numbers cannot be saved (JSON) and would make every sounding pitch NaN.
            require(c.instrument.fineTuneCents.isFinite() && kotlin.math.abs(c.instrument.fineTuneCents) <= 1200.0) { "Feinstimmung ungültig" }
            require(c.instrument.drumKit.all { it.volume.isFinite() && it.pan.isFinite() }) { "Schlagzeug-Werte ungültig" }
            TrackEdits.setInstrument(p, c.id, c.instrument, c.policy)
        }
        is EditorCommand.SetSound -> TrackEdits.update(p, c.id) { it.copy(sound = c.sound) }
        is EditorCommand.SetMetadata -> { require(c.metadata.title.isNotBlank() && c.metadata.title.length <= 200) { "Titel ungültig" }; p.copy(metadata = c.metadata) }
        is EditorCommand.SetMasterMix -> { require(c.mix.volume.isFinite() && c.mix.volume in 0.0..1.0) { "Master-Lautstärke ungültig" }; p.copy(mix = p.mix.copy(master = c.mix)) }
        is EditorCommand.SetDuration -> NoteEdits.setDurationRipple(p, s) { c.duration }
        is EditorCommand.AddAudioClip -> {
            require(c.clip.asset == c.asset.hash) { "Clip und Asset passen nicht zusammen" }
            require(p.audio.clips.size < 16) { "Höchstens 16 Audioclips" }
            require(c.clip.gain.isFinite() && c.clip.gain in 0.0..2.0 && c.clip.pan in -1.0..1.0 && kotlin.math.abs(c.clip.offsetMicros) <= 600_000_000L) { "Clip-Werte ungültig" }
            p.copy(audio = p.audio.copy(assets = (p.audio.assets + c.asset).distinctBy { it.hash }, clips = p.audio.clips + c.clip))
        }
        is EditorCommand.UpdateAudioClip -> {
            require(p.audio.clips.any { it.id == c.clip.id }) { "Clip fehlt" }
            require(c.clip.gain.isFinite() && c.clip.gain in 0.0..2.0 && c.clip.pan in -1.0..1.0 && kotlin.math.abs(c.clip.offsetMicros) <= 600_000_000L) { "Clip-Werte ungültig" }
            p.copy(audio = p.audio.copy(clips = p.audio.clips.map { if (it.id == c.clip.id) c.clip else it }))
        }
        is EditorCommand.RemoveAudioClip -> {
            val clips = p.audio.clips.filterNot { it.id == c.id }
            p.copy(audio = p.audio.copy(clips = clips, assets = p.audio.assets.filter { a -> clips.any { it.asset == a.hash } }))
        }
        else -> updateBeat(p, s, createIfMissing = c != EditorCommand.Delete) { beat, track, existed ->
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
                    beat.copy(notes = beat.notes.filterNot { if (note.string != null) it.string == note.string else it.pitch.midiNumber == note.pitch.midiNumber } + note, duration = if (existed) beat.duration else c.duration, explicitRest = false)
                }
                is EditorCommand.PutRest -> beat.copy(notes = emptyList(), explicitRest = true, duration = if (existed) beat.duration else c.duration)
                is EditorCommand.ToggleDrum -> {
                    require(track.instrument.kind == InstrumentKind.PERCUSSION && c.midiNote in 0..127)
                    val notes = if (beat.notes.any { it.pitch.midiNumber == c.midiNote }) beat.notes.filterNot { it.pitch.midiNumber == c.midiNote }
                        else beat.notes + Note(ids.next(), Pitch(c.midiNote))
                    beat.copy(notes = notes, duration = if (existed) beat.duration else c.duration, explicitRest = notes.isEmpty())
                }
                EditorCommand.Delete -> {
                    val remaining = if (track.instrument.tuning.isNotEmpty()) beat.notes.filterNot { it.string == s.string } else emptyList()
                    beat.copy(notes = remaining, explicitRest = remaining.isEmpty())
                }
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

    private fun updateBeat(p: Project, s: Selection, createIfMissing: Boolean = true, transform: (Beat, Track, Boolean) -> Beat): Project = p.copy(tracks = p.tracks.map { track ->
        if (track.id != s.trackId) track else track.copy(staves = track.staves.mapIndexed { staffIndex, staff ->
            if (staffIndex != 0) staff else staff.copy(bars = staff.bars.map { bar ->
                if (bar.measureId != s.measureId) bar else bar.copy(voices = bar.voices.map { voice ->
                    if (voice.index != s.voice) voice else {
                        val old = voice.beats.firstOrNull { it.offset == s.offset }
                        if (old == null && !createIfMissing) voice else {
                            val next = transform(old ?: Beat(ids.next(), s.offset), track, old != null)
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
            return s.copy(trackId = track.id, measureId = measure.id, offset = if (s.offset < (measure.pickupLength ?: measure.signature.length())) s.offset else Fraction.ZERO,
                string = s.string.coerceIn(0, maxOf(0, track.instrument.tuning.lastIndex)), rangeEnd = s.rangeEnd?.takeIf { end -> p.measures.any { it.id == end } })
        }
    }

    /** A tie that joined two notes on one string must not end up spanning two strings (playback groups ties per string). */
    private fun requireTiesStayOnTheirString(before: Project, after: Project) {
        fun strings(p: Project): Map<EntityId, Int?> = p.tracks.flatMap { it.staves }.flatMap { it.bars }.flatMap { it.voices }.flatMap { it.beats }.flatMap { it.notes }.associate { it.id to it.string }
        val old = strings(before); val now = strings(after)
        for (t in after.tracks) for (st in t.staves) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes) for (l in n.links) if (l.kind == LinkKind.TIE) {
            val a = now[n.id]; val z = now[l.target]
            if (a != null && z != null && a != z && (old[n.id] != a || old[l.target] != z)) throw IllegalArgumentException("Gebundene Noten müssen auf derselben Saite bleiben")
        }
    }

}
