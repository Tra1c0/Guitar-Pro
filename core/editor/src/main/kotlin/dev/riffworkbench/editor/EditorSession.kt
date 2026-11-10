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

/** Note-level change for [EditorCommand.EditNotesInBars]. */
sealed interface NoteEdit {
    /** Like [EditorCommand.ApplyArticulation]; with [toggle] it is removed only when every target note already has it. */
    data class Apply(val articulation: Articulation, val toggle: Boolean = true) : NoteEdit
    data class Clear(val slot: String) : NoteEdit
    data class Velocity(val velocity: Int) : NoteEdit
}

sealed interface EditorCommand {
    data class PutNote(val fret: Int? = null, val midiNote: Int? = null, val duration: WrittenDuration = WrittenDuration(), val marks: Set<NoteMark> = emptySet()) : EditorCommand
    data class ToggleDrum(val midiNote: Int, val duration: WrittenDuration = WrittenDuration()) : EditorCommand
    data class PutRest(val duration: WrittenDuration) : EditorCommand
    data object Delete : EditorCommand
    /**
     * Drag and drop: moves the selected note (string instruments) to another beat position and/or string as one undo step.
     * The pitch is kept (fret recomputed on another string); velocity, techniques and fingering travel with it. A new beat
     * gets [duration]; an existing target beat keeps its own. The source becomes a rest when it was the beat's last note.
     */
    data class MoveNote(val toMeasure: EntityId, val toOffset: Fraction, val toString: Int, val duration: WrittenDuration = WrittenDuration()) : EditorCommand
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
    /**
     * Range edit (Zyklus 23): [edit] applies to every note of the selected track and voice in the bars [ids] (the visible
     * bar range) as one undo step. Fails without notes there. Cursor commands (ApplyArticulation, …) keep acting on the
     * cursor note only.
     */
    data class EditNotesInBars(val ids: List<EntityId>, val edit: NoteEdit) : EditorCommand
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
    /** [allowDrop]: notes that fit nowhere even an octave away are removed instead of rejecting the edit. */
    data class SetInstrument(val id: EntityId, val instrument: InstrumentDefinition, val policy: RetuningPolicy, val allowDrop: Boolean = false) : EditorCommand
    data class SetSound(val id: EntityId, val sound: SoundAssignment) : EditorCommand
    data class SetMetadata(val metadata: ProjectMetadata) : EditorCommand
    data class SetMasterMix(val mix: ChannelMix) : EditorCommand
    // Reference audio
    data class AddAudioClip(val clip: AudioClip, val asset: AssetDescriptor) : EditorCommand
    data class UpdateAudioClip(val clip: AudioClip) : EditorCommand
    data class RemoveAudioClip(val id: EntityId) : EditorCommand
    /**
     * Zyklus 25 „Rhythmus tippen“: replaces the whole content of [voice] of [trackId] in the bars of [bars] (consecutive,
     * each filled exactly) by the tapped rhythm, as one undo step. GRID: rest placeholders with the tapped durations;
     * OPEN_STRING: fret 0 on [string] (held notes tied over barlines and value splits), drum tracks [drumNote] per attack.
     */
    data class ApplyRhythm(val trackId: EntityId, val voice: Int, val bars: List<RhythmBar>, val mode: RhythmMode, val string: Int = 0, val drumNote: Int = 38) : EditorCommand
}

/** Single-owner dispatcher: call selection + commands on the same UI dispatcher. No adapter mutates this state. */
class EditorSession(project: Project, private val ids: IdSource, private val historyLimit: Int = 200) {
    private data class History(val project: Project, val selection: Selection, val diagnostics: List<Diagnostic>)
    private val undo = ArrayDeque<History>()
    private val redo = ArrayDeque<History>()
    private val mutable = MutableStateFlow(EditorState(project, initialSelection(project), diagnostics = ScoreValidator.validate(project)))
    val state: StateFlow<EditorState> = mutable.asStateFlow()

    fun select(selection: Selection) {
        val p = state.value.project
        require(p.tracks.any { it.id == selection.trackId } && p.measures.any { it.id == selection.measureId })
        require(selection.voice in 0..3 && selection.offset >= Fraction.ZERO)
        // Positions lie inside the bar (pickups and odd meters are ≤ 64 whole notes); larger values only overflow later.
        require(selection.offset.denominator <= 1L shl 20 && selection.offset.numerator <= 64L * selection.offset.denominator) { "Position außerhalb des Takts" }
        val track = p.tracks.first { it.id == selection.trackId }
        require(selection.string in 0 until maxOf(1, track.instrument.tuning.size))
        require(selection.rangeEnd == null || p.measures.any { it.id == selection.rangeEnd })
        mutable.value = state.value.copy(selection = selection)
    }

    /**
     * Applies [command] as one undo step. With [sameUndoStep] the change joins the previous step instead (the history keeps
     * the state before that step; redo is cleared as usual): for follow-ups of one user action, e.g. the bar that
     * auto-advance adds after the last note. Without a previous step it is a step of its own.
     */
    fun dispatch(command: EditorCommand, sameUndoStep: Boolean = false): Outcome<Unit> = try {
        val before = state.value
        var after = apply(before.project, before.selection, command)
        // Any edit that removes notes (delete, rest over a note, drum toggle) drops the links pointing at them, like Delete.
        // Re-pitching one end of a tie stays rejected by the validator (deliberate: ties are never broken silently).
        if (after != before.project) after = Links.prune(after)
        // Only single-note moves are checked: track-wide transpose/retune re-place chords per beat and may split a tie,
        // which they did before as well (known limitation, see QA journal cycle 6).
        if (after != before.project && (command is EditorCommand.MoveToString || command is EditorCommand.PutNote)) requireTiesStayOnTheirString(before.project, after)
        // Content-identical (e.g. Replace with the current score, which carries its own revision): not a change.
        if (after != before.project && after.revision != before.project.revision && after.copy(revision = before.project.revision) == before.project) after = before.project
        // Bar insertion/duplication between tied bars keeps the tie by design (EditorExtensionsTest); beat-level edits may not open a gap.
        if (after != before.project && command !is EditorCommand.Replace && command !is EditorCommand.InsertMeasures && command !is EditorCommand.DuplicateMeasures)
            requireTiesStayAdjacent(before.project, after)
        if (after == before.project) Outcome.Success(Unit) else {
            require(after.id == before.project.id) { "Projekt-ID darf nicht geändert werden" }
            require(before.project.revision < Long.MAX_VALUE) { "Revisionslimit erreicht" }
            after = after.copy(revision = before.project.revision + 1)
            val diagnostics = ScoreValidator.validate(after)
            if (diagnostics.any { it.severity == Severity.ERROR }) Outcome.Failure(FailureCode.VALIDATION, diagnostics.first { it.severity == Severity.ERROR }.message, diagnostics)
            else {
                // Computed before the history changes: a failure here must leave undo/redo untouched.
                val selection = validSelection(after, before.selection, before.project)
                if (!sameUndoStep || undo.isEmpty()) undo.addLast(History(before.project, before.selection, before.diagnostics))
                while (undo.size > historyLimit) undo.removeFirst()
                redo.clear()
                mutable.value = EditorState(after, selection, undo.isNotEmpty(), false, diagnostics)
                Outcome.Success(Unit)
            }
        }
    } catch (e: IllegalArgumentException) { Outcome.Failure(FailureCode.VALIDATION, e.message ?: "Ungültige Änderung") }
    catch (e: ArithmeticException) { Outcome.Failure(FailureCode.VALIDATION, e.message ?: "Ungültige Änderung") }
    catch (e: IndexOutOfBoundsException) { Outcome.Failure(FailureCode.VALIDATION, "Ungültige Daten: ${e.message}") }

    fun undo(): Outcome<Unit> = travel(undo, redo)
    fun redo(): Outcome<Unit> = travel(redo, undo)
    private fun travel(from: ArrayDeque<History>, to: ArrayDeque<History>): Outcome<Unit> {
        if (from.isEmpty()) return Outcome.Failure(FailureCode.VALIDATION, "Keine weitere Änderung")
        val current = state.value
        val next = from.removeLast()
        to.addLast(History(current.project, current.selection, current.diagnostics))
        val p = next.project.copy(revision = current.project.revision + 1)
        // A history entry was valid when it was recorded; its diagnostics do not depend on the revision number.
        val selection = try { validSelection(p, next.selection) } catch (e: RuntimeException) { initialSelection(p) }
        mutable.value = EditorState(p, selection, undo.isNotEmpty(), redo.isNotEmpty(), next.diagnostics)
        return Outcome.Success(Unit)
    }

    private fun apply(p: Project, s: Selection, c: EditorCommand): Project = when (c) {
        is EditorCommand.Rename -> { requireText(c.title, "Titel"); require(c.title.isNotBlank() && c.title.length <= 200); p.copy(metadata = p.metadata.copy(title = c.title.trim())) }
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
            val target = (index.toLong() + c.delta).coerceIn(0L, tracks.lastIndex.toLong()).toInt()
            val track = tracks.removeAt(index); tracks.add(target, track)
            p.copy(tracks = tracks)
        }
        is EditorCommand.AddMeasure -> {
            val measure = Measure(ids.next(), c.signature)
            p.copy(measures = p.measures + measure, tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { staff -> staff.copy(bars = staff.bars + ProjectFactory(ids).bar(measure.id)) }) })
        }
        // Like the other per-bar commands: a missing bar fails ("Takt fehlt") instead of a silent no-op (QA 20 F6).
        is EditorCommand.SetSignature -> StructureEdits.updateMeasure(p, c.measureId) { it.copy(signature = c.signature) }
        is EditorCommand.SetMix -> {
            require(c.mix.volume.isFinite() && c.mix.volume in 0.0..1.0 && c.mix.pan.isFinite() && c.mix.pan in -1.0..1.0)
            requireFiniteEffects(c.mix.effects)
            p.copy(tracks = p.tracks.map { if (it.id == c.trackId) it.copy(mix = c.mix) else it })
        }
        is EditorCommand.Replace -> { require(c.expectedRevision == null || c.expectedRevision == p.revision) { "Partitur wurde seit der Vorschau geändert" }; c.project.copy(id = p.id) }
        is EditorCommand.ApplyArticulation -> {
            (c.articulation as? Articulation.Vibrato)?.let { require(it.rateHz.isFinite() && it.rateHz in 0.1..20.0 && it.depthCents in 0..1200) { "Vibrato-Werte ungültig" } }
            NoteEdits.applyArticulation(p, s, c.articulation, c.toggle)
        }
        is EditorCommand.ClearArticulation -> NoteEdits.clearArticulation(p, s, c.slot)
        is EditorCommand.EditNotesInBars -> {
            ((c.edit as? NoteEdit.Apply)?.articulation as? Articulation.Vibrato)?.let { require(it.rateHz.isFinite() && it.rateHz in 0.1..20.0 && it.depthCents in 0..1200) { "Vibrato-Werte ungültig" } }
            NoteEdits.editNotesInBars(p, s, c.ids, c.edit)
        }
        EditorCommand.ToggleTie -> NoteEdits.toggleTie(p, s, ids)
        is EditorCommand.SetVelocity -> NoteEdits.setVelocity(p, s, c.velocity)
        is EditorCommand.MoveToString -> NoteEdits.moveToString(p, s, c.string)
        is EditorCommand.MoveNote -> moveNote(p, s, c)
        is EditorCommand.Transpose -> NoteEdits.transpose(p, s, c.semitones, c.scope)
        is EditorCommand.SetTuplet -> NoteEdits.setDurationInPlace(p, s, ids) { it.copy(tuplets = listOfNotNull(c.ratio)) }
        is EditorCommand.SetFermata -> {
            c.fermata?.let { require(it.multiplier.isFinite() && it.multiplier in 0.1..8.0) { "Fermatendauer ungültig" } }
            NoteEdits.updateSelectedBeat(p, s) { it.copy(fermata = c.fermata) }
        }
        is EditorCommand.SetStrum -> NoteEdits.updateSelectedBeat(p, s) { it.copy(strum = c.strum) }
        EditorCommand.DeleteBeat -> NoteEdits.deleteBeat(p, s)
        is EditorCommand.InsertBeat -> NoteEdits.insertBeat(p, s, c.duration, ids)
        is EditorCommand.SetText -> requireText(c.text, "Text").let { NoteEdits.setText(p, s, c.text, c.kind, ids) }
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
        is EditorCommand.SetSection -> requireText(c.label, "Abschnitt").let { StructureEdits.setSection(p, c.measureId, c.label, ids) }
        is EditorCommand.DuplicateTrack -> TrackEdits.duplicate(p, c.id, ids)
        is EditorCommand.RenameTrack -> { requireText(c.name, "Spurname"); require(c.name.isNotBlank() && c.name.length <= 120) { "Spurname ungültig" }; TrackEdits.update(p, c.id) { it.copy(name = c.name.trim()) } }
        is EditorCommand.SetInstrument -> {
            // Non-finite numbers cannot be saved (JSON) and would make every sounding pitch NaN.
            require(c.instrument.fineTuneCents.isFinite() && kotlin.math.abs(c.instrument.fineTuneCents) <= 1200.0) { "Feinstimmung ungültig" }
            require(c.instrument.drumKit.all { it.volume.isFinite() && it.pan.isFinite() }) { "Schlagzeug-Werte ungültig" }
            TrackEdits.setInstrument(p, c.id, c.instrument, c.policy, c.allowDrop)
        }
        is EditorCommand.SetSound -> requireText(c.sound.mappingDescription, "Klang").let { TrackEdits.update(p, c.id) { it.copy(sound = c.sound) } }
        is EditorCommand.SetMetadata -> { c.metadata.let { m -> listOf(m.title, m.artist, m.album, m.composer, m.copyright, m.notes).forEach { requireText(it, "Metadaten") } }; require(c.metadata.title.isNotBlank() && c.metadata.title.length <= 200) { "Titel ungültig" }
            // Canonical form like Rename: surrounding blanks are not part of a field (XML formats trim them anyway).
            p.copy(metadata = c.metadata.let { m -> m.copy(title = m.title.trim(), artist = m.artist.trim(), album = m.album.trim(), composer = m.composer.trim(), copyright = m.copyright.trim(), notes = m.notes.trim()) }) }
        is EditorCommand.SetMasterMix -> {
            require(c.mix.volume.isFinite() && c.mix.volume in 0.0..1.0 && c.mix.pan.isFinite() && c.mix.pan in -1.0..1.0) { "Master-Werte ungültig" }
            requireFiniteEffects(c.mix.effects)
            p.copy(mix = p.mix.copy(master = c.mix))
        }
        is EditorCommand.SetDuration -> NoteEdits.setDurationInPlace(p, s, ids) { c.duration }
        is EditorCommand.AddAudioClip -> {
            require(c.clip.asset == c.asset.hash) { "Clip und Asset passen nicht zusammen" }
            require(p.audio.clips.size < 16) { "Höchstens 16 Audioclips" }
            require(c.clip.gain.isFinite() && c.clip.gain in 0.0..2.0 && c.clip.pan in -1.0..1.0 && kotlin.math.abs(c.clip.offsetMicros) <= 600_000_000L) { "Clip-Werte ungültig" }
            requireFiniteEffects(c.clip.filters)
            p.copy(audio = p.audio.copy(assets = (p.audio.assets + c.asset).distinctBy { it.hash }, clips = p.audio.clips + c.clip))
        }
        is EditorCommand.UpdateAudioClip -> {
            require(p.audio.clips.any { it.id == c.clip.id }) { "Clip fehlt" }
            require(c.clip.gain.isFinite() && c.clip.gain in 0.0..2.0 && c.clip.pan in -1.0..1.0 && kotlin.math.abs(c.clip.offsetMicros) <= 600_000_000L) { "Clip-Werte ungültig" }
            requireFiniteEffects(c.clip.filters)
            p.copy(audio = p.audio.copy(clips = p.audio.clips.map { if (it.id == c.clip.id) c.clip else it }))
        }
        is EditorCommand.RemoveAudioClip -> {
            val clips = p.audio.clips.filterNot { it.id == c.id }
            p.copy(audio = p.audio.copy(clips = clips, assets = p.audio.assets.filter { a -> clips.any { it.asset == a.hash } }))
        }
        is EditorCommand.ToggleDrum -> drumToggle(p, s, c)
        is EditorCommand.ApplyRhythm -> RhythmEdits.apply(p, c, ids)
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

    /**
     * Drum step grid (Zyklus 23, „über 16tel nicht nutzbar“): a hit on a finer grid slot that lies inside an existing beat
     * (a 1/16 groove refined to 1/32) shortens that beat to end at the slot instead of failing with an overlap; the new hit
     * gets the grid duration, shortened to the room before the next beat. Hits keep their ids, the covering beat keeps its notes.
     */
    private fun drumToggle(p: Project, s: Selection, c: EditorCommand.ToggleDrum): Project {
        val track = NoteEdits.track(p, s)
        require(track.instrument.kind == InstrumentKind.PERCUSSION && c.midiNote in 0..127)
        val voice = NoteEdits.selectedVoice(p, s)
        val beats = voice?.beats.orEmpty()
        var project = p
        var duration = c.duration
        if (beats.none { it.offset == s.offset }) {
            val m = p.measures.firstOrNull { it.id == s.measureId } ?: throw IllegalArgumentException("Takt fehlt")
            CursorSteps.covering(beats, s.offset)?.let { cover ->
                val shorter = Durations.exact(s.offset + cover.offset * Fraction.of(-1), cover.duration.tuplets)
                    ?: throw IllegalArgumentException("Schlag liegt innerhalb eines Beats, der sich dort nicht teilen lässt – Raster wechseln")
                project = NoteEdits.mapVoice(project, s) { v, _, _ -> v.copy(beats = v.beats.map { if (it.id == cover.id) it.copy(duration = shorter) else it }) }
            }
            val end = beats.filter { it.offset > s.offset }.minOfOrNull { it.offset } ?: NoteEdits.capacity(m)
            duration = Durations.fitting(c.duration, end + s.offset * Fraction.of(-1)) ?: c.duration
        }
        return updateBeat(project, s) { beat, _, existed ->
            val notes = if (beat.notes.any { it.pitch.midiNumber == c.midiNote }) beat.notes.filterNot { it.pitch.midiNumber == c.midiNote }
                else beat.notes + Note(ids.next(), Pitch(c.midiNote))
            beat.copy(notes = notes, duration = if (existed) beat.duration else duration, explicitRest = notes.isEmpty())
        }
    }

    /** NaN/Infinity cannot be saved (JSON) and would poison the DSP. */
    private fun requireFiniteEffects(effects: List<EffectUnit>) = require(effects.all { e -> e.parameters.values.all { it.isFinite() } }) { "Effektwerte ungültig" }
    /** Unpaired UTF-16 surrogates cannot be stored as UTF-8 (they would come back as '?'). */
    private fun requireText(text: String?, what: String) { if (text == null) return
        var i = 0
        while (i < text.length) { val c = text[i]
            if (c.isHighSurrogate()) { require(i + 1 < text.length && text[i + 1].isLowSurrogate()) { "$what enthält ungültige Zeichen" }; i += 2; continue }
            require(!c.isLowSurrogate()) { "$what enthält ungültige Zeichen" }; i++ }
    }

    private fun moveNote(p: Project, s: Selection, c: EditorCommand.MoveNote): Project {
        val track = p.tracks.first { it.id == s.trackId }
        require(track.instrument.tuning.isNotEmpty()) { "Ziehen von Noten nur bei Saiteninstrumenten" }
        require(p.measures.any { it.id == c.toMeasure } && c.toOffset >= Fraction.ZERO && c.toString in track.instrument.tuning.indices) { "Ziel existiert nicht" }
        val beatAt = { m: EntityId, o: Fraction -> track.staves.first().bars.firstOrNull { it.measureId == m }?.voices?.firstOrNull { it.index == s.voice }?.beats?.firstOrNull { it.offset == o } }
        val sourceBeat = beatAt(s.measureId, s.offset)
        val note = sourceBeat?.notes?.firstOrNull { it.string == s.string } ?: throw IllegalArgumentException("Zuerst eine Note wählen")
        if (c.toMeasure == s.measureId && c.toOffset == s.offset && c.toString == s.string) return p
        // Ties are never broken silently (as MoveToString): a tied note is released first.
        val tied = note.links.any { it.kind == LinkKind.TIE } || p.tracks.any { t -> t.staves.any { st -> st.bars.any { b -> b.voices.any { v -> v.beats.any { bt -> bt.notes.any { n -> n.links.any { it.kind == LinkKind.TIE && it.target == note.id } } } } } } }
        require(!tied) { "Gebundene Note zuerst lösen, dann verschieben" }
        val targetExisted = beatAt(c.toMeasure, c.toOffset) != null
        val onlyNote = sourceBeat.notes.size == 1
        require(beatAt(c.toMeasure, c.toOffset)?.notes?.none { it.string == c.toString } ?: true) { "Zielposition ist belegt" }
        val fret = (if (c.toString == s.string) note.fret else null) ?: NoteEdits.fretFor(track.instrument, c.toString, note.pitch.midiNumber)
            ?: throw IllegalArgumentException("Ton ist auf dieser Saite nicht spielbar")
        val target = s.copy(measureId = c.toMeasure, offset = c.toOffset, string = c.toString, rangeEnd = null)
        val placed = apply(apply(p, s, EditorCommand.Delete), target, EditorCommand.PutNote(fret = fret, duration = c.duration))
        // The note itself travels (same id, so links from and to it survive); only string and fret change.
        val moved = updateBeat(placed, target, createIfMissing = false) { beat, _, _ ->
            beat.copy(notes = beat.notes.map { n -> if (n.string != c.toString) n else note.copy(string = c.toString, fret = fret) },
                fermata = if (onlyNote && !targetExisted) sourceBeat.fermata else beat.fermata, strum = if (onlyNote && !targetExisted) sourceBeat.strum else beat.strum)
        }
        // A strum or fermata that moved with the beat's only note does not stay behind on the rest.
        return if (onlyNote && !targetExisted && (sourceBeat.fermata != null || sourceBeat.strum != null))
            updateBeat(moved, s, createIfMissing = false) { beat, _, _ -> beat.copy(fermata = null, strum = null) } else moved
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
        /**
         * [s] adjusted to [p]. A cursor bar that was deleted (QA 20 F5) moves to the first remaining bar at or after its
         * former place in [previous] (the bar that followed a deleted range), else to the last bar, at its downbeat — not to
         * bar 1, where the view would jump to the start of the piece.
         */
        private fun validSelection(p: Project, s: Selection, previous: Project? = null): Selection {
            val track = p.tracks.firstOrNull { it.id == s.trackId } ?: p.tracks.first()
            val kept = p.measures.firstOrNull { it.id == s.measureId }
            val measure = kept ?: previous?.measures?.let { old -> old.indexOfFirst { it.id == s.measureId }.takeIf { it >= 0 }?.let { i ->
                old.drop(i).firstNotNullOfOrNull { m -> p.measures.firstOrNull { it.id == m.id } } ?: p.measures.last() } } ?: p.measures.first()
            val offset = if (kept == null) Fraction.ZERO else s.offset
            return s.copy(trackId = track.id, measureId = measure.id, offset = if (offset < (measure.pickupLength ?: measure.signature.length())) offset else Fraction.ZERO,
                string = s.string.coerceIn(0, maxOf(0, track.instrument.tuning.lastIndex)), rangeEnd = s.rangeEnd?.takeIf { end -> p.measures.any { it.id == end } })
        }
    }

    /** A tie that joined two notes on one string must not end up spanning two strings (playback groups ties per string). */
    /**
     * Ties are never broken silently: an edit that leaves a previously gap-free tie with a gap (beat inserted, bar inserted
     * or duplicated between, origin shortened, bar lengthened) is rejected; the user releases the tie first.
     */
    private fun requireTiesStayAdjacent(before: Project, after: Project) {
        val was = adjacentTies(before)
        if (was.isEmpty()) return
        val now = adjacentTies(after)
        val broken = was.filter { it !in now && tieExists(after, it) }
        require(broken.isEmpty()) { "Haltebogen würde unterbrochen – zuerst den Haltebogen lösen" }
    }
    private fun tieExists(p: Project, tie: Pair<EntityId, EntityId>): Boolean =
        p.tracks.any { t -> t.staves.any { st -> st.bars.any { b -> b.voices.any { v -> v.beats.any { bt -> bt.notes.any { n -> n.id == tie.first && n.links.any { it.kind == LinkKind.TIE && it.target == tie.second } } } } } } }
    /** Ties whose target starts exactly where the origin beat ends (same bar, or the next bar's downbeat). */
    private fun adjacentTies(p: Project): Set<Pair<EntityId, EntityId>> {
        val out = HashSet<Pair<EntityId, EntityId>>()
        val measureIndex = p.measures.withIndex().associate { (i, m) -> m.id to i }
        for (t in p.tracks) for (st in t.staves) {
            // note id -> (bar index, start, end) per voice
            val where = HashMap<EntityId, Triple<Int, Fraction, Fraction>>()
            val voiceOf = HashMap<EntityId, Int>()
            var anyTie = false
            for (bar in st.bars) { val mi = measureIndex[bar.measureId] ?: continue
                for (v in bar.voices) for (b in v.beats) for (n in b.notes) {
                    where[n.id] = Triple(mi, b.offset, b.offset + b.duration.length()); voiceOf[n.id] = v.index
                    if (n.links.any { it.kind == LinkKind.TIE }) anyTie = true
                }
            }
            if (!anyTie) continue
            for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes) for (l in n.links) {
                if (l.kind != LinkKind.TIE) continue
                val o = where[n.id] ?: continue; val g = where[l.target] ?: continue
                if (voiceOf[l.target] != voiceOf[n.id]) continue
                val adjacent = if (o.first == g.first) o.third == g.second
                    else g.first == o.first + 1 && g.second == Fraction.ZERO && p.measures[o.first].let { m -> o.third == (m.pickupLength ?: m.signature.length()) }
                if (adjacent) out += n.id to l.target
            }
        }
        return out
    }

    private fun requireTiesStayOnTheirString(before: Project, after: Project) {
        // Only bars that changed can hold notes whose string changed; unchanged bars are shared instances.
        val moved = HashSet<EntityId>()
        for ((ti, t) in after.tracks.withIndex()) {
            val oldTrack = before.tracks.getOrNull(ti)?.takeIf { it.id == t.id } ?: before.tracks.firstOrNull { it.id == t.id } ?: continue
            if (oldTrack === t) continue
            val oldStrings = HashMap<EntityId, Int?>()
            for ((si, st) in t.staves.withIndex()) {
                val os = oldTrack.staves.getOrNull(si) ?: continue
                if (os === st) continue
                for ((bi, bar) in st.bars.withIndex()) { val ob = os.bars.getOrNull(bi) ?: continue; if (ob === bar) continue
                    for (v in ob.voices) for (b in v.beats) for (n in b.notes) oldStrings[n.id] = n.string
                    for (v in bar.voices) for (b in v.beats) for (n in b.notes) if (n.id in oldStrings && oldStrings[n.id] != n.string) moved += n.id }
            }
        }
        if (moved.isEmpty()) return
        val strings = HashMap<EntityId, Int?>()
        for (t in after.tracks) for (st in t.staves) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes) strings[n.id] = n.string
        for (t in after.tracks) for (st in t.staves) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes) for (l in n.links) if (l.kind == LinkKind.TIE) {
            if (n.id !in moved && l.target !in moved) continue
            val a = strings[n.id]; val z = strings[l.target]
            if (a != null && z != null && a != z) throw IllegalArgumentException("Gebundene Noten müssen auf derselben Saite bleiben")
        }
    }

}
