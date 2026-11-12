package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.*
import dev.riffworkbench.playback.DelayedPlayhead
import dev.riffworkbench.ports.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WorkspaceViewModel(
    private val id: EntityId,
    private val repository: ProjectRepository,
    private val snapshots: SnapshotRepository,
    private val exchange: NativeProjectExchange,
    private val settings: SettingsRepository,
    val transport: Transport,
    private val ids: IdSource,
    private val services: WorkspaceServices = WorkspaceServices(),
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private val mutableInput = MutableStateFlow(NoteInput())
    val input = mutableInput.asStateFlow()
    private val mutablePractice = MutableStateFlow(PracticeSettings(speed = 1.0, countInBars = 0, metronome = false))
    val practice = mutablePractice.asStateFlow()
    private val mutableView = MutableStateFlow(ViewOptions())
    val view = mutableView.asStateFlow()
    /** App settings the workspace UI reads (QA 23: pinch zoom on/off). */
    val appSettings: StateFlow<AppSettings> get() = settings.settings
    private val mutableLayout = MutableStateFlow<ScoreLayout?>(null)
    val layout: StateFlow<ScoreLayout?> = mutableLayout.asStateFlow()
    private val mutableTuner = MutableStateFlow(TunerState())
    val tuner = mutableTuner.asStateFlow()
    private val mutableChords = MutableStateFlow<List<ChordDefinition>>(emptyList())
    val chords = mutableChords.asStateFlow()
    private var session: EditorSession? = null
    private val saves = Channel<Project>(Channel.CONFLATED)
    private val saveMutex = Mutex()
    private var savedRevision = -1L
    private var lastCommand: EditorCommand? = null
    private var layoutJob: Job? = null
    private var tunerJob: Job? = null
    private var layoutKey: Any? = null
    /**
     * Start-position rule (Bericht 1 §3): the edit cursor is the start marker. Pause → Play resumes where it paused only
     * while nothing was navigated in between; any selection change while PAUSED makes the next Play start at the cursor.
     */
    private var navigatedSincePause = false
    /** Laufende Bereichsauswahl per langem Drücken (Bericht 1 §5). */
    private val rangeGesture = BarRangeGesture()
    /** Zyklus 25 „Rhythmus tippen“ (see the section „rhythm tapping“). */
    private val mutableRhythm = MutableStateFlow(RhythmTapState())
    val rhythm: StateFlow<RhythmTapState> = mutableRhythm.asStateFlow()

    init {
        viewModelScope.launch { for (project in saves) persist(project) }
        reload()
    }

    fun reload() { viewModelScope.launch {
        mutable.value = mutable.value.copy(loading = true, loadError = null)
        when (val result = repository.open(id)) {
            is Outcome.Failure -> mutable.value = mutable.value.copy(loading = false, loadError = result.message)
            is Outcome.Success -> {
                savedRevision = result.value.revision
                session = EditorSession(result.value, ids)
                // A device hint (Zyklus 26) that arrived while loading stays unless the recovery message replaces it.
                val hint = mutable.value.noticeAction?.takeIf { it.text == mutable.value.notice }
                val recovered = result.value.diagnostics.firstOrNull { it.code == "recovered" }?.message
                mutable.value = WorkspaceState(loading = false, editor = session!!.state.value, notice = recovered ?: hint?.text, noticeAction = hint.takeIf { recovered == null })
                refreshSnapshots()
                publish()
            }
        }
    } }

    // ------------------------------------------------------------------ selection and note entry
    /**
     * Explicit selection (score tap via [tap], menus, chips). In range mode a position change only moves the range end (the
     * cursor stays the anchor), but a voice or track choice applies (Zyklus 23: the voice menu and the drum screen's chips
     * went through here and were swallowed, so entries landed in the old voice/track).
     */
    fun select(selection: Selection) {
        val s = session ?: return
        val current = s.state.value.selection
        val requested = if (input.value.rangeMode) {
            val moved = selection.measureId != current.measureId || selection.offset != current.offset
            current.copy(trackId = selection.trackId, voice = selection.voice, string = if (selection.trackId != current.trackId) selection.string else current.string,
                rangeEnd = if (moved) selection.measureId else current.rangeEnd)
        } else selection.copy(rangeEnd = null)
        // Never inside a beat of the edited voice (Zyklus 23): a column of another voice or a voice switch selects the beat
        // that covers the position, so the next entry does not fail with an overlap.
        val p = s.state.value.project
        val adjusted = requested.copy(offset = CursorSteps.covering(voiceBeats(p, requested.trackId, requested.measureId, requested.voice), requested.offset)?.offset ?: requested.offset)
        try { s.select(adjusted) } catch (e: IllegalArgumentException) { return }
        noteNavigation()
        publish(); mutableInput.value = input.value.copy(buffer = "")
    }
    /** A tap on one cell (drum grid, fretboard) targets exactly that cell, also while range mode is on; false if it does not exist. */
    fun selectCell(selection: Selection): Boolean {
        val s = session ?: return false
        try { s.select(selection.copy(rangeEnd = null)) } catch (e: IllegalArgumentException) { return false }
        noteNavigation()
        publish(); mutableInput.value = input.value.copy(buffer = "")
        return true
    }
    private fun noteNavigation() { if (transport.state.value.status == TransportStatus.PAUSED) navigatedSincePause = true }
    /**
     * Explicit navigation (arrows, PageUp/Down, bar map): like a score tap, the transport follows while PLAYING (D5-8), so
     * cursor and playhead stay together; while PAUSED the next Play starts here.
     *
     * [moveCursor] (arrows, PageUp/Down, auto-advance, string keys): the cursor moves also in range mode, where only taps
     * (and the bar map) extend the range; the range end is kept there (QA 20 F2/F3: in range mode note entry overwrote one
     * beat and PageDown seeked back to the unchanged cursor). Without range mode this is exactly [select].
     */
    private fun navigateTo(selection: Selection, moveCursor: Boolean = true) {
        val before = session?.state?.value?.selection
        if (moveCursor) moveCursorTo(selection.copy(rangeEnd = if (input.value.rangeMode) selection.rangeEnd else null)) else select(selection)
        val after = session?.state?.value?.selection
        val moved = before == null || after == null || before.measureId != after.measureId || before.offset != after.offset
        if (transport.state.value.status == TransportStatus.PLAYING && (moved || !input.value.rangeMode)) seekToCursor()
    }
    /** Moves the cursor to [selection] (no range-mode adjustment; inside a beat of its voice: that beat's start); false if it does not exist. */
    private fun moveCursorTo(selection: Selection): Boolean {
        val s = session ?: return false
        val beats = voiceBeats(s.state.value.project, selection.trackId, selection.measureId, selection.voice)
        try { s.select(selection.copy(offset = CursorSteps.covering(beats, selection.offset)?.offset ?: selection.offset)) } catch (e: IllegalArgumentException) { return false }
        noteNavigation()
        publish(); mutableInput.value = input.value.copy(buffer = "")
        return true
    }
    /** Bar map: jump to the start of [measureId] (in range mode, like a tap, it extends the range). */
    fun selectMeasure(measureId: EntityId) { val s = state.value.editor?.selection ?: return; navigateTo(s.copy(measureId = measureId, offset = Fraction.ZERO), moveCursor = false) }
    fun putNoteAt(string: Int, fret: Int) {
        val selection = state.value.editor?.selection ?: return
        if (selectCell(selection.copy(string = string))) command(EditorCommand.PutNote(fret = fret, duration = input.value.duration))
    }
    /**
     * Drag and drop of a note in the score: moves it to [toMeasure]/[toOffset] on [toString] (pitch kept). A new beat gets
     * the duration chosen in the input bar; it can be changed afterwards on the selected note. One undo step.
     */
    fun moveNote(fromMeasure: EntityId, fromOffset: Fraction, fromString: Int?, toMeasure: EntityId, toOffset: Fraction, toString: Int?) {
        val selection = state.value.editor?.selection ?: return
        val from = fromString ?: return
        if (!selectCell(selection.copy(measureId = fromMeasure, offset = fromOffset, string = from))) return
        val target = toString ?: from
        if (command(EditorCommand.MoveNote(toMeasure, toOffset, target, input.value.duration)) is Outcome.Success)
            selectCell(selection.copy(measureId = toMeasure, offset = toOffset, string = target))
    }
    fun selectRange(endMeasure: EntityId?) { session?.let { s -> runCatching { s.select(s.state.value.selection.copy(rangeEnd = endMeasure)) }; publish() } }
    /**
     * Range switch (Zyklus 23): on shows the cursor bar as range at once (the mode is visible on the score, taps then move
     * the range end); off clears the range and ends a running long-press gesture, so no stale anchor drags the range later.
     */
    fun setRangeMode(enabled: Boolean) {
        mutableInput.value = input.value.copy(rangeMode = enabled)
        if (enabled) { val s = state.value.editor?.selection; if (s != null && s.rangeEnd == null) selectRange(s.measureId) }
        else { rangeGesture.end(); selectRange(null) }
    }
    /** A bar range is visible (long press or range switch): effects target it, see [command]. */
    private fun rangeActive(): Boolean = session?.state?.value?.selection?.rangeEnd != null

    /**
     * Langes Drücken auf einen Takt (Bericht 1 §5, [BarRangeGesture]): Anker = [measureId], genau dieser Takt ist
     * ausgewählt. Unabhängig vom Bereich-Schalter. Nie ein Transport-Seek, auch nicht während der Wiedergabe; im PAUSED
     * zählt ein verschobener Cursor wie jede Navigation (das nächste Play beginnt am Anker, Startmarken-Regel).
     */
    fun beginRange(measureId: EntityId) {
        val s = session ?: return
        val before = s.state.value.selection
        try { s.select(rangeGesture.begin(before, measureId)) } catch (e: IllegalArgumentException) { rangeGesture.end(); return }
        val after = s.state.value.selection
        if (after.measureId != before.measureId || after.offset != before.offset) noteNavigation()
        publish(); mutableInput.value = input.value.copy(buffer = "")    }
    /** Ziehen nach [beginRange]: Bereichsende auf [measureId]; true nur bei einem Taktwechsel (dann tickt die Haptik). */
    fun extendRange(measureId: EntityId): Boolean {
        val s = session ?: return false
        val next = rangeGesture.extend(s.state.value.selection, measureId) ?: return false
        try { s.select(next) } catch (e: IllegalArgumentException) { return false }
        publish()
        return true
    }
    /** Loslassen: die Geste endet, der Bereich bleibt (ein einfaches Tippen hebt ihn auf). */
    fun endRange() { rangeGesture.end() }

    /** Score tap: select column/string; in range mode extend the bar range. */
    fun tap(measureId: EntityId, offset: Fraction, string: Int?) {
        val current = state.value.editor ?: return
        if (input.value.rangeMode) { selectRange(measureId); return }
        val track = current.project.tracks.first { it.id == current.selection.trackId }
        val s = string?.takeIf { track.instrument.tuning.isNotEmpty() } ?: current.selection.string.coerceIn(0, maxOf(0, track.instrument.tuning.lastIndex))
        select(current.selection.copy(measureId = measureId, offset = offset, string = s))
    }

    fun digit(digit: Int) {
        if (input.value.buffer.length >= 2) { showNotice("Bund zuerst bestätigen oder Eingabe löschen"); return }
        val next = input.value.buffer + digit
        mutableInput.value = input.value.copy(buffer = next)
    }
    fun backspace() { if (input.value.buffer.isNotEmpty()) mutableInput.value = input.value.copy(buffer = input.value.buffer.dropLast(1)) else command(EditorCommand.Delete) }
    fun commitInput(dead: Boolean = false) {
        val current = state.value.editor ?: return
        val track = current.project.tracks.first { it.id == current.selection.trackId }
        val instrument = track.instrument
        val marks = buildSet { if (input.value.palmMute) add(NoteMark.PALM_MUTE); if (dead) add(NoteMark.DEAD) }
        val fret = input.value.buffer.toIntOrNull() ?: existingFret(current) ?: 0
        // A tapped placeholder (Zyklus 25, „Raster“): the fret keeps the placeholder's duration (PutNote keeps an existing beat's).
        val slot = rhythmSlotIndex(current)
        val result = command(EditorCommand.PutNote(fret = fret, midiNote = if (instrument.tuning.isEmpty()) input.value.buffer.toIntOrNull() ?: 60 else null, duration = input.value.duration, marks = marks))
        if (result is Outcome.Success) {
            previewSelected()
            mutableInput.value = input.value.copy(buffer = "")
            // Only an entry that changed the score owns an undo step the new bar may join (an unchanged re-entry has none).
            val changed = state.value.editor?.project?.revision != current.project.revision
            if (slot >= 0 && !input.value.chordMode) { fillRhythmSlot(slot, current.selection, changed, settings.settings.value.autoAdvance); return }
            if (settings.settings.value.autoAdvance && !input.value.chordMode) move(1, sameUndoStep = changed)
        }
    }
    /** Fret pad: one tap enters and commits the fret; the pad covers 0–24, so no two-digit preview is needed. */
    fun enterFret(fret: Int) { mutableInput.value = input.value.copy(buffer = fret.coerceIn(0, 48).toString()); commitInput() }
    private fun existingFret(e: EditorState): Int? = selectedBeat(e)?.notes?.firstOrNull { it.string == e.selection.string }?.fret

    fun rest() {
        val before = state.value.editor?.project?.revision
        if (command(EditorCommand.PutRest(input.value.duration)) is Outcome.Success && settings.settings.value.autoAdvance && !input.value.chordMode)
            move(1, sameUndoStep = state.value.editor?.project?.revision != before)
    }
    fun setDuration(denominator: Int = input.value.duration.denominator, dots: Int = input.value.duration.dots) {
        val duration = WrittenDuration(denominator, dots, input.value.duration.tuplets)
        val current = state.value.editor ?: return
        val before = input.value
        // The entry duration is the edit grid: set it before the command's relayout, and redraw also without a selected beat.
        mutableInput.value = input.value.copy(duration = duration)
        // With a visible range the key sets the entry value only: the beat under the hidden cursor is not rewritten (Zyklus 23 P0).
        if (selectedBeat(current) != null && !rangeActive()) { if (command(EditorCommand.SetDuration(duration)) is Outcome.Failure) { mutableInput.value = before; relayout() }; return }
        relayout()
    }
    fun setTuplet(ratio: TupletRatio?) {
        val current = state.value.editor ?: return
        val before = input.value
        mutableInput.value = input.value.copy(duration = input.value.duration.copy(tuplets = listOfNotNull(ratio)))
        if (selectedBeat(current) != null && !rangeActive()) { if (command(EditorCommand.SetTuplet(ratio)) is Outcome.Failure) { mutableInput.value = before; relayout() }; return }
        relayout()
    }
    /**
     * P.M.: on a selected note it toggles that note's palm mute and the entry mode follows the note's new state; without a
     * note it toggles the entry mode for the next notes. (QA 21: both were toggled independently, so with the mode on, a
     * tap on an unmuted note muted it while the tile, lit by the mode, stayed lit: the tap looked like it did nothing.)
     */
    fun togglePalmMute() {
        val current = state.value.editor
        if (rangeActive()) { command(EditorCommand.ApplyArticulation(Articulation.Mark(NoteMark.PALM_MUTE))); return }
        if (current == null || selectedNote(current) == null) { mutableInput.value = input.value.copy(palmMute = !input.value.palmMute); return }
        if (command(EditorCommand.ApplyArticulation(Articulation.Mark(NoteMark.PALM_MUTE))) is Outcome.Success)
            mutableInput.value = input.value.copy(palmMute = state.value.editor?.let { selectedNote(it) }?.articulations?.contains(Articulation.Mark(NoteMark.PALM_MUTE)) == true)
    }
    /** Whether the P.M. tile is lit: the selected note's palm mute, or on an empty beat the entry mode. */
    fun palmMuteLit(e: EditorState): Boolean = selectedNote(e)?.let { Articulation.Mark(NoteMark.PALM_MUTE) in it.articulations } ?: input.value.palmMute
    fun toggleMark(kind: NoteMark) { command(EditorCommand.ApplyArticulation(Articulation.Mark(kind))) }
    fun articulation(a: Articulation) { if (command(EditorCommand.ApplyArticulation(a)) is Outcome.Success) previewSelected() }
    fun chordMode(enabled: Boolean) { mutableInput.value = input.value.copy(chordMode = enabled) }
    /** Changes only the entry duration (grid), never the selected beat. */
    fun setGrid(duration: WrittenDuration) { mutableInput.value = input.value.copy(duration = duration); relayout() }
    fun retunePolicy(): RetuningPolicy = settings.settings.value.retuningPolicy
    /** Persists the retuning policy chosen on the Instruments screen (AppSettings.retuningPolicy). */
    fun setRetunePolicy(policy: RetuningPolicy) { viewModelScope.launch { settings.update(settings.settings.value.copy(retuningPolicy = policy)) } }
    /** Dry run of [retune] for the selected track: the notes that would be octaved or dropped; a non-retunable change throws into an empty list plus notice. */
    fun retuneProblems(instrument: InstrumentDefinition, policy: RetuningPolicy = retunePolicy()): List<RetuneProblem> {
        val e = state.value.editor ?: return emptyList()
        return try { Retuning.problems(e.project, e.selection.trackId, instrument, policy) } catch (ex: IllegalArgumentException) { emptyList() }
    }
    /**
     * Retunes the selected track as ONE command (one undo step): the dry run first, then SetInstrument; without [allowDrop]
     * a change that would remove notes is refused with the bars in the notice. After success the notice summarises what was
     * resolved, e.g. '3 Noten um eine Oktave versetzt (Takt 1, 2)'.
     */
    fun retune(instrument: InstrumentDefinition, policy: RetuningPolicy = retunePolicy(), allowDrop: Boolean = false): Outcome<Unit> {
        val e = state.value.editor ?: return Outcome.Failure(FailureCode.VALIDATION, "Projekt noch nicht geladen")
        val problems = try { Retuning.problems(e.project, e.selection.trackId, instrument, policy) }
            catch (ex: IllegalArgumentException) { val m = ex.message ?: "Umstimmen nicht möglich"; showNotice(m); publish(); return Outcome.Failure(FailureCode.VALIDATION, m) }
        val drops = problems.filter { it.resolution == RetuneResolution.DROP }
        if (drops.isNotEmpty() && !allowDrop) { val m = Retuning.failureMessage(drops); showNotice(m); publish(); return Outcome.Failure(FailureCode.VALIDATION, m) }
        val result = command(EditorCommand.SetInstrument(e.selection.trackId, instrument, policy, allowDrop))
        if (result is Outcome.Success) { Retuning.summary(problems)?.let { showNotice(it); publish() } }
        return result
    }

    /**
     * Moves the cursor by one beat; past the last bar a bar is added. [sameUndoStep] (auto-advance after an entry): that bar
     * joins the entry's undo step, so one Undo takes back what one tap did (QA 21: the first Undo after the last note of the
     * last bar only removed the empty new bar).
     */
    fun move(direction: Int, sameUndoStep: Boolean = false) {
        val current = state.value.editor ?: return
        val p = current.project; val s = current.selection; val index = p.measures.indexOfFirst { it.id == s.measureId }
        val step = selectedBeat(current)?.takeIf { direction > 0 }?.duration?.length() ?: input.value.duration.length()
        val length = p.measures[index].pickupLength ?: p.measures[index].signature.length()
        // Zyklus 23: steps stop at beat starts and never end inside a beat (CursorSteps), also on 1/32, 1/64 and tuplet grids.
        val beats = voiceBeats(p, s.trackId, p.measures[index].id, s.voice)
        val offset = if (direction > 0) CursorSteps.forward(beats, s.offset, step) else if (s.offset > Fraction.ZERO) CursorSteps.backward(beats, s.offset, step) else Fraction(-1)
        val next = when {
            offset >= length && index < p.measures.lastIndex -> s.copy(measureId = p.measures[index + 1].id, offset = Fraction.ZERO)
            offset >= length -> { command(EditorCommand.AddMeasure(p.measures[index].signature), sameUndoStep); state.value.editor?.project?.measures?.getOrNull(index + 1)?.let { s.copy(measureId = it.id, offset = Fraction.ZERO) } ?: s }
            offset < Fraction.ZERO && index > 0 -> {
                val previous = p.measures[index - 1]
                val end = previous.pickupLength ?: previous.signature.length()
                s.copy(measureId = previous.id, offset = CursorSteps.backward(voiceBeats(p, s.trackId, previous.id, s.voice), end, input.value.duration.length()))
            }
            offset < Fraction.ZERO -> s.copy(offset = Fraction.ZERO)
            else -> s.copy(offset = offset)
        }
        navigateTo(next)
    }
    /**
     * Track chooser: switches the edited track, keeping bar, beat, voice and (clamped to the new track's strings) the
     * string, also in range mode (QA 21: select() turned the switch into a range extension there, so the track did not
     * change; and every switch reset the cursor to the lowest string).
     */
    fun selectTrack(trackId: EntityId) {
        val e = state.value.editor ?: return
        val track = e.project.tracks.firstOrNull { it.id == trackId } ?: return
        val next = e.selection.copy(trackId = trackId, string = e.selection.string.coerceIn(0, maxOf(0, track.instrument.tuning.lastIndex)))
        if (input.value.rangeMode) moveCursorTo(next) else select(next)
    }
    fun moveString(delta: Int) {
        val e = state.value.editor ?: return
        val track = e.project.tracks.first { it.id == e.selection.trackId }
        val next = e.selection.copy(string = (e.selection.string + delta).coerceIn(0, maxOf(0, track.instrument.tuning.lastIndex)))
        // Also in range mode (F3): a string change is never a range extension.
        if (input.value.rangeMode) moveCursorTo(next) else select(next)
    }
    fun moveMeasure(delta: Int) {
        val e = state.value.editor ?: return
        val i = (e.project.measures.indexOfFirst { it.id == e.selection.measureId } + delta).coerceIn(e.project.measures.indices)
        navigateTo(e.selection.copy(measureId = e.project.measures[i].id, offset = Fraction.ZERO))
    }
    /** Explicit "back to the start": cursor to bar 1, beat 1, transport stopped, so the next Play unambiguously starts at the top. */
    fun jumpToStart() {
        val e = state.value.editor ?: return
        // Also in range mode (F1): select() would only have moved the range end there; the jump clears the range.
        moveCursorTo(e.selection.copy(measureId = e.project.measures.first().id, offset = Fraction.ZERO, rangeEnd = null))
        navigatedSincePause = false
        resumeFollow()
        viewModelScope.launch { transport.stop() }
    }
    fun toggleDrum(trackId: EntityId, measureId: EntityId, offset: Fraction, midi: Int) {
        val selection = state.value.editor?.selection ?: return
        if (!selectCell(selection.copy(trackId = trackId, measureId = measureId, offset = offset, string = 0))) return
        if (command(EditorCommand.ToggleDrum(midi, input.value.duration)) is Outcome.Success) previewSelected(midi)
    }

    private fun voiceBeats(p: Project, track: EntityId, measure: EntityId, voice: Int): List<Beat> =
        p.tracks.firstOrNull { it.id == track }?.staves?.firstOrNull()?.bars?.firstOrNull { it.measureId == measure }?.voices?.firstOrNull { it.index == voice }?.beats.orEmpty()
    fun selectedBeat(e: EditorState): Beat? = e.project.tracks.firstOrNull { it.id == e.selection.trackId }?.staves?.firstOrNull()?.bars?.firstOrNull { it.measureId == e.selection.measureId }
        ?.voices?.firstOrNull { it.index == e.selection.voice }?.beats?.firstOrNull { it.offset == e.selection.offset }
    fun selectedNote(e: EditorState): Note? {
        val track = e.project.tracks.firstOrNull { it.id == e.selection.trackId } ?: return null
        val beat = selectedBeat(e) ?: return null
        return if (track.instrument.tuning.isNotEmpty()) beat.notes.firstOrNull { it.string == e.selection.string } else beat.notes.firstOrNull()
    }

    /** Plays the selected note (or a test note) through the track's sound; used to measure note-on latency. */
    fun auditionSelection() {
        val preview = services.preview ?: return
        val e = state.value.editor ?: return
        val track = e.project.tracks.firstOrNull { it.id == e.selection.trackId } ?: return
        val inst = track.instrument
        val note = selectedNote(e) ?: if (inst.tuning.isNotEmpty()) Note(EntityId("audition"), Pitch(inst.pitch(0, 5)), 0, 5) else Note(EntityId("audition"), Pitch(if (inst.kind == InstrumentKind.PERCUSSION) 38 else 60))
        viewModelScope.launch { preview.preview(e.project, track.id, note) }
    }
    val audioLab: AudioLab? get() = services.audioLab

    private fun previewSelected(drumMidi: Int? = null) {
        val preview = services.preview ?: return
        val e = state.value.editor ?: return
        val note = if (drumMidi != null) selectedBeat(e)?.notes?.firstOrNull { it.pitch.midiNumber == drumMidi } else selectedNote(e)
        note ?: return
        viewModelScope.launch { preview.preview(e.project, e.selection.trackId, note) }
    }

    // ------------------------------------------------------------------ commands, history, clipboard
    /** [sameUndoStep]: the change joins the previous undo step (EditorSession.dispatch). */
    fun command(command: EditorCommand, sameUndoStep: Boolean = false): Outcome<Unit> {
        val active = session ?: return Outcome.Failure(FailureCode.VALIDATION, "Projekt noch nicht geladen")
        val before = active.state.value.project.revision
        val cursor = active.state.value.selection
        val result = active.dispatch(addressed(active, command), sameUndoStep)
        if (result is Outcome.Failure) showNotice(result.message)
        else if (active.state.value.project.revision != before) { if (command !is EditorCommand.Replace && command !is EditorCommand.MoveNote) lastCommand = command; enqueueSave() }
        noteCursorMove(cursor)
        publish()
        return result
    }
    /**
     * What a command addresses while a bar range is visible (Zyklus 23, P0 "effects/values rewritten on other notes"): note
     * effects (articulations, marks, velocity) go to every note of the track and voice in the range, not to the cursor
     * note, which a range-mode tap does not move and a long press puts on beat 1 of the anchor bar. Entry and beat commands
     * act on the visible cursor and clear the range first. Without a range every command acts on the cursor as before.
     */
    private fun addressed(active: EditorSession, command: EditorCommand): EditorCommand {
        val s = active.state.value.selection
        if (s.rangeEnd == null) return command
        val bars = s.measureRange(active.state.value.project)
        fun cursorOnly(): EditorCommand { runCatching { active.select(s.copy(rangeEnd = null)) }; return command }
        return when (command) {
            is EditorCommand.ApplyArticulation -> EditorCommand.EditNotesInBars(bars, NoteEdit.Apply(command.articulation, command.toggle))
            is EditorCommand.ToggleMark -> EditorCommand.EditNotesInBars(bars, NoteEdit.Apply(Articulation.Mark(command.kind)))
            is EditorCommand.ClearArticulation -> EditorCommand.EditNotesInBars(bars, NoteEdit.Clear(command.slot))
            is EditorCommand.SetVelocity -> EditorCommand.EditNotesInBars(bars, NoteEdit.Velocity(command.velocity))
            is EditorCommand.PutNote, is EditorCommand.PutRest, EditorCommand.Delete, EditorCommand.DeleteBeat, is EditorCommand.InsertBeat,
            EditorCommand.ToggleTie, is EditorCommand.MoveToString, is EditorCommand.SetFermata, is EditorCommand.SetStrum, is EditorCommand.SetText,
            is EditorCommand.ToggleDrum, is EditorCommand.MoveNote, is EditorCommand.SetDuration, is EditorCommand.SetTuplet -> cursorOnly()
            is EditorCommand.Transpose -> if (command.scope == EditScope.NOTE || command.scope == EditScope.BEAT) cursorOnly() else command
            else -> command
        }
    }
    fun repeatLast() { lastCommand?.let(::command) }
    fun undo() { val cursor = session?.state?.value?.selection; val result = session?.undo(); if (result is Outcome.Success) enqueueSave() else if (result is Outcome.Failure) showNotice(result.message); noteCursorMove(cursor); publish() }
    fun redo() { val cursor = session?.state?.value?.selection; val result = session?.redo(); if (result is Outcome.Success) enqueueSave() else if (result is Outcome.Failure) showNotice(result.message); noteCursorMove(cursor); publish() }
    /** An edit or undo/redo that re-places the cursor (the start marker) counts as navigation while PAUSED (QA 20 F4). */
    private fun noteCursorMove(before: Selection?) {
        val now = session?.state?.value?.selection ?: return
        if (before != null && (before.measureId != now.measureId || before.offset != now.offset)) noteNavigation()
    }
    fun showNotice(text: String?) { mutable.value = mutable.value.copy(notice = text) }

    fun copyBars(allTracks: Boolean = false) {
        val e = state.value.editor ?: return
        val range = e.selection.measureRange(e.project)
        val tracks = if (allTracks) e.project.tracks.map { it.id } else listOf(e.selection.trackId)
        try {
            mutable.value = mutable.value.copy(clipboard = BarClipboard.copy(e.project, range, tracks), notice = "${range.size} Takt(e) kopiert")
        } catch (ex: IllegalArgumentException) { showNotice(ex.message) }
    }
    fun pasteBars() {
        val e = state.value.editor ?: return
        val clip = state.value.clipboard ?: return showNotice("Zwischenablage ist leer")
        val target = if (clip.tracks.size > 1) e.project.tracks.first().id else e.selection.trackId
        command(EditorCommand.PasteBars(clip, e.selection.measureId, target))
    }
    fun duplicateBars() { val e = state.value.editor ?: return; command(EditorCommand.DuplicateMeasures(e.selection.measureRange(e.project))) }
    fun deleteBars() { val e = state.value.editor ?: return; command(EditorCommand.DeleteMeasures(e.selection.measureRange(e.project))) }
    fun clearBars() { val e = state.value.editor ?: return; command(EditorCommand.ClearBars(e.selection.measureRange(e.project), e.selection.trackId)) }
    fun insertBar(after: Boolean) {
        val e = state.value.editor ?: return
        val index = e.project.measures.indexOfFirst { it.id == e.selection.measureId }
        command(EditorCommand.InsertMeasures(if (after) index + 1 else index))
    }

    // ------------------------------------------------------------------ view and layout
    // Wrapped systems are laid out for viewport width / zoom: a zoom change re-wraps them.
    fun setZoom(zoom: Float) { val z = zoom.coerceIn(0.5f, 2.5f); if (z == view.value.zoom) return; mutableView.value = view.value.copy(zoom = z); if (view.value.wrap) relayout() }
    fun setNotation(mode: NotationMode) { mutableView.value = view.value.copy(notation = mode); relayout() }
    fun setWrap(wrap: Boolean) { mutableView.value = view.value.copy(wrap = wrap); relayout() }
    fun setCompact(compact: Boolean) { mutableView.value = view.value.copy(compact = compact); relayout() }
    fun setFollow(follow: Boolean) { mutableView.value = view.value.copy(follow = follow, followSuspended = false) }
    /** A manual pan/zoom/drag while following: the view stays where the user put it until [resumeFollow] (chip, Play, seek, stop). */
    fun suspendFollow() { if (!view.value.followSuspended) mutableView.value = view.value.copy(followSuspended = true) }
    fun resumeFollow() { if (view.value.followSuspended) mutableView.value = view.value.copy(followSuspended = false) }
    fun setViewportWidth(dp: Float) { if (kotlin.math.abs(dp - view.value.viewportWidthDp) > 8f) { mutableView.value = view.value.copy(viewportWidthDp = dp); if (view.value.wrap) relayout() } }

    private fun relayout() {
        val e = session?.state?.value ?: return
        val trackIndex = e.project.tracks.indexOfFirst { it.id == e.selection.trackId }.coerceAtLeast(0)
        val v = view.value
        val base = if (v.compact) LayoutStyle.READ else LayoutStyle.EDIT
        val style = base.copy(showTab = v.notation != NotationMode.STANDARD, showStandard = v.notation != NotationMode.TAB)
        val grid = if (v.compact) null else input.value.duration.length()
        val width = if (v.wrap) v.viewportWidthDp / v.zoom else null
        val key = listOf(e.project.revision, trackIndex, style, grid, width, e.selection.voice)
        if (key == layoutKey) return
        layoutKey = key
        layoutJob?.cancel()
        // Margin 6 (instead of 12): the editor canvas is small on a phone; pages and exports keep their own margins.
        val request = LayoutRequest(e.project, trackIndex, style, width, grid, e.selection.voice, if (e.project.tracks[trackIndex].staves.firstOrNull()?.bars?.any { b -> b.voices.count { it.beats.isNotEmpty() } > 1 } == true) e.selection.voice else null, margin = EDITOR_MARGIN)
        layoutJob = viewModelScope.launch {
            val result = withContext(background) { runCatching { ScoreLayouter.layout(request) }.getOrNull() }
            if (result != null) mutableLayout.value = result
        }
    }

    // ------------------------------------------------------------------ practice and transport
    fun togglePlay() { viewModelScope.launch {
        if (rhythmBusy) return@launch
        val status = transport.state.value.status
        resumeFollow()
        if (status == TransportStatus.PLAYING) { navigatedSincePause = false; transport.pause(); return@launch }
        // The moment of the tap: a stop/pause (button, background, audio focus) after it wins over this play, one before not.
        val since = transport.halts
        val project = session?.state?.value?.project ?: return@launch
        transport.prepare(project, practice.value).let { if (it is Outcome.Failure && it.code != FailureCode.UNAVAILABLE) showNotice(it.message) }
        // Resume continues where it paused unless the user navigated meanwhile; otherwise playback starts at the cursor
        // (inside a loop: at the loop start). The cursor is the visible start marker (Bericht 1 §3).
        val resume = transport.state.value.status == TransportStatus.PAUSED && !navigatedSincePause
        navigatedSincePause = false
        if (!resume) cursorPosition()?.let { transport.seek(it) }
        when (val r = transport.play(since)) { is Outcome.Failure -> showNotice(r.message); is Outcome.Success -> Unit }
    } }
    fun stop() { resumeFollow(); viewModelScope.launch { transport.stop() } }
    fun seekToCursor() { resumeFollow(); viewModelScope.launch { cursorPosition()?.let { transport.seek(it) } } }
    private fun cursorPosition(): PlaybackOccurrence? {
        val e = session?.state?.value ?: return null
        val route = routeIndexFor(e.project, e.selection.measureId) ?: return null
        return PlaybackOccurrence(ScorePosition(e.selection.measureId, e.selection.offset), 1, route)
    }
    private fun routeIndexFor(p: Project, measure: EntityId): Int? =
        (dev.riffworkbench.playback.UnfoldingRepeatCompiler().compile(p) as? Outcome.Success)?.value?.firstOrNull { it.position.measureId == measure }?.routeIndex

    fun updatePractice(change: (PracticeSettings) -> PracticeSettings) {
        mutablePractice.value = change(practice.value)
        val project = session?.state?.value?.project ?: return
        viewModelScope.launch { transport.prepare(project, practice.value) }
    }
    fun setSpeed(speed: Double) = updatePractice { it.copy(speed = speed.coerceIn(0.25, 1.5)) }
    fun setMetronome(on: Boolean) = updatePractice { it.copy(metronome = on) }
    fun setCountIn(bars: Int) = updatePractice { it.copy(countInBars = bars.coerceIn(0, 4)) }
    fun setTrainer(trainer: SpeedTrainer) = updatePractice { it.copy(trainer = trainer, speed = if (trainer.enabled) trainer.startSpeed else it.speed) }
    /** Loops the selected bar range (first occurrence of each bar in the unfolded route). */
    fun loopSelection() {
        val e = session?.state?.value ?: return
        val range = e.selection.measureRange(e.project)
        val route = (dev.riffworkbench.playback.UnfoldingRepeatCompiler().compile(e.project) as? Outcome.Success)?.value ?: return showNotice("Wiedergaberoute ungültig")
        val start = route.firstOrNull { it.position.measureId == range.first() } ?: return
        val end = route.firstOrNull { it.position.measureId == range.last() && it.routeIndex >= start.routeIndex } ?: return
        updatePractice { it.copy(loop = LoopRange(start, end)) }
        viewModelScope.launch { transport.setLoop(LoopRange(start, end)) }
    }
    fun clearLoop() { updatePractice { it.copy(loop = null) }; viewModelScope.launch { transport.setLoop(null) } }

    // ------------------------------------------------------------------ tuner and chords
    fun startTuner() {
        val tuner = services.tuner ?: return run { mutableTuner.value = TunerState(error = "Stimmgerät nicht verfügbar") }
        if (tunerJob?.isActive == true) return
        tunerJob = viewModelScope.launch {
            when (val r = tuner.start(null)) {
                is Outcome.Failure -> { mutableTuner.value = TunerState(error = r.message); return@launch }
                is Outcome.Success -> mutableTuner.value = TunerState(running = true)
            }
            tuner.readings.collect { reading -> mutableTuner.value = TunerState(running = true, reading = reading) }
        }
    }
    fun stopTuner() { tunerJob?.cancel(); tunerJob = null; viewModelScope.launch { services.tuner?.stop() }; mutableTuner.value = TunerState() }
    fun searchChords(name: String) { viewModelScope.launch {
        val e = session?.state?.value ?: return@launch
        val instrument = e.project.tracks.first { it.id == e.selection.trackId }.instrument
        when (val r = services.chords.search(name, instrument)) { is Outcome.Failure -> { mutableChords.value = emptyList(); showNotice(r.message) }; is Outcome.Success -> mutableChords.value = r.value }
    } }
    /** Writes a chord voicing into the selected beat as one transaction. */
    fun insertChord(chord: ChordDefinition) {
        val e = session?.state?.value ?: return
        val track = e.project.tracks.first { it.id == e.selection.trackId }
        if (track.instrument.tuning.isEmpty()) return showNotice("Akkordgriffe nur für Saiteninstrumente")
        var project = e.project
        val editor = EditorSession(project, ids)
        editor.select(e.selection)
        chord.frets.forEachIndexed { string, fret -> if (fret != null && string < track.instrument.tuning.size) {
            editor.select(editor.state.value.selection.copy(string = string))
            // All or nothing: a chord that does not fit here is reported, never written partially or silently dropped.
            val r = editor.dispatch(EditorCommand.PutNote(fret = fret, duration = input.value.duration))
            if (r is Outcome.Failure) return showNotice(r.message)
        } }
        project = editor.state.value.project
        if (command(EditorCommand.Replace(project, e.project.revision)) is Outcome.Success) previewSelected()
    }

    // ------------------------------------------------------------------ persistence and snapshots
    fun retrySave() { viewModelScope.launch { flush() } }
    suspend fun flush(): Outcome<Unit> = session?.state?.value?.project?.let { persist(it) } ?: Outcome.Success(Unit)
    fun exportNative(onReady: (ExportResult) -> Unit) = export(ExportTarget.NATIVE, onReady)
    fun createSnapshot(name: String) { viewModelScope.launch {
        val project = session?.state?.value?.project ?: return@launch
        when (val result = snapshots.create(project, name)) { is Outcome.Failure -> showNotice(result.message); is Outcome.Success -> refreshSnapshots() }
    } }
    fun restoreSnapshot(snapshot: SnapshotInfo) { viewModelScope.launch {
        val expected = session?.state?.value?.project?.revision ?: return@launch
        when (val result = snapshots.load(id, snapshot.id)) {
            is Outcome.Failure -> showNotice(result.message)
            is Outcome.Success -> command(EditorCommand.Replace(result.value, expected))
        }
    } }
    private suspend fun refreshSnapshots() { when (val result = snapshots.list(id)) {
        is Outcome.Failure -> showNotice(result.message)
        is Outcome.Success -> mutable.value = mutable.value.copy(snapshots = result.value)
    } }

    // ------------------------------------------------------------------ export
    /** In-memory exports (score formats). PDF is produced by the UI layer from [svgOrLayoutFor]. */
    fun export(target: ExportTarget, onReady: (ExportResult) -> Unit) { viewModelScope.launch {
        val project = session?.state?.value?.project ?: return@launch
        mutable.value = mutable.value.copy(busy = target.labelDe, busyProgress = null)
        val result: Outcome<ExportResult> = when (target) {
            ExportTarget.NATIVE -> exchange.export(project)
            ExportTarget.SVG -> withContext(background) {
                // Like the format exporters: a layout failure becomes a notice, not an uncaught exception in viewModelScope.
                try {
                    val svg = SvgExport.render(project, project.tracks.indices.toList())
                    Outcome.Success(ExportResult(svg.toByteArray(), "image/svg+xml", "${fileBase(project)}.svg", emptyList()))
                } catch (e: RuntimeException) { Outcome.Failure(FailureCode.CORRUPT, "SVG-Export fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
            }
            ExportTarget.PDF, ExportTarget.WAV, ExportTarget.STEMS -> Outcome.Failure(FailureCode.VALIDATION, "Dieses Format wird gestreamt exportiert")
            else -> {
                val exporter = services.formats?.exporter(target.id)
                if (exporter == null) unavailable("C-FORMAT", "${target.labelDe} ist in diesem Build nicht verfügbar")
                else withContext(background) { exporter.export(ExportRequest(project, target.id)) }
            }
        }
        mutable.value = mutable.value.copy(busy = null, busyProgress = null)
        when (result) {
            is Outcome.Failure -> showNotice(result.message)
            is Outcome.Success -> {
                val losses = result.value.losses
                mutable.value = mutable.value.copy(lastLosses = losses, notice = if (losses.isEmpty()) null else "${losses.size} Merkmal(e) konnten in ${target.labelDe} nicht exakt übertragen werden – Details im Austausch-Bereich.")
                onReady(result.value.copy(fileName = result.value.fileName.ifBlank { "${fileBase(project)}.${target.extension}" }))
            }
        }
    } }
    fun suggestedName(target: ExportTarget): String = "${fileBase(session?.state?.value?.project)}.${target.extension}"

    /** Streams a WAV mix or a ZIP of stems (+ MIDI) into [sink]. Same engine and mix graph as playback. */
    suspend fun renderAudio(target: ExportTarget, sink: ByteSink): Outcome<Unit> {
        val renderer = services.renderer ?: return unavailable("C-RENDER", "Audio-Rendering ist nicht verfügbar")
        val project = session?.state?.value?.project ?: return Outcome.Failure(FailureCode.VALIDATION, "Projekt nicht geladen")
        val options = RenderOptions(sampleRate = 48_000, bitDepth = 24, includeMasterFx = true, tailSeconds = 2.0)
        mutable.value = mutable.value.copy(busy = target.labelDe, busyProgress = 0f)
        try {
            return when (target) {
                ExportTarget.WAV -> when (val r = renderer.renderTo(project, options, sink, null) { p -> p.total?.let { t -> mutable.value = mutable.value.copy(busyProgress = (p.completed.toFloat() / t).coerceIn(0f, 1f)) } }) {
                    is Outcome.Failure -> r
                    is Outcome.Success -> { showNotice("WAV exportiert: ${"%.1f".format(r.value.frames / 48_000.0)} s, Spitze ${"%.1f".format(20 * kotlin.math.log10(r.value.peak.coerceAtLeast(1e-6)))} dBFS" + clipWarning(listOf("Mix" to r.value.clippedSamples))); Outcome.Success(Unit) }
                }
                ExportTarget.STEMS -> withContext(background) {
                    val zip = ZipOutputStream(object : java.io.OutputStream() {
                        override fun write(b: Int) { sink.write(byteArrayOf(b.toByte()), 0, 1) }
                        override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len) }
                    })
                    zip.setLevel(1)
                    val clipped = ArrayList<Pair<String, Long>>()
                    zip.use { out ->
                        project.tracks.forEachIndexed { i, track ->
                            out.putNextEntry(ZipEntry("${(i + 1).toString().padStart(2, '0')}_${safe(track.name)}.wav"))
                            val r = renderer.renderTo(project, options.copy(includeMasterFx = false), { b, o, l -> out.write(b, o, l) }, i) { p ->
                                p.total?.let { t -> mutable.value = mutable.value.copy(busyProgress = ((i + p.completed.toFloat() / t) / (project.tracks.size + 1)).coerceIn(0f, 1f)) }
                            }
                            if (r is Outcome.Failure) return@withContext r
                            if (r is Outcome.Success) clipped += track.name to r.value.clippedSamples
                            out.closeEntry()
                        }
                        out.putNextEntry(ZipEntry("00_mix.wav"))
                        val mix = renderer.renderTo(project, options, { b, o, l -> out.write(b, o, l) }, null)
                        if (mix is Outcome.Failure) return@withContext mix
                        if (mix is Outcome.Success) clipped += "Mix" to mix.value.clippedSamples
                        out.closeEntry()
                        services.formats?.exporter("midi")?.let { midi ->
                            val m = midi.export(ExportRequest(project, "midi"))
                            if (m is Outcome.Success) { out.putNextEntry(ZipEntry("tempo_and_notes.mid")); out.write(m.value.bytes); out.closeEntry() }
                        }
                        out.putNextEntry(ZipEntry("LIESMICH.txt"))
                        out.write(("Alle Stems beginnen bei Sample 0 der aufgelösten Wiedergabereihenfolge (Wiederholungen ausgeschrieben), 48 kHz, 24 Bit, gleiche Länge inkl. 2 s Ausklang.\n" +
                            "Stems ohne Master-Kette und unabhängig vom Master-Fader (Werkspegel); 00_mix.wav mit Master-Fader und Master-Kette. MIDI-Datei mit Tempo-Map in derselben Reihenfolge.\nTempo zu Beginn: ${project.tempo.firstOrNull()?.quarterNotesPerMinute ?: 120.0} BPM\n").toByteArray())
                        out.closeEntry()
                    }
                    showNotice("${project.tracks.size} Stems + Mix exportiert" + clipWarning(clipped))
                    Outcome.Success(Unit)
                }
                else -> Outcome.Failure(FailureCode.VALIDATION, "Kein Audioformat")
            }
        } finally { mutable.value = mutable.value.copy(busy = null, busyProgress = null) }
    }
    /**
     * Export feedback (QA 22): the renderer counts samples beyond full scale; stems have no master limiter, so a hot track
     * or master fader clips them silently in the 24-bit file. Names every clipped file so the user can lower that fader.
     */
    private fun clipWarning(files: List<Pair<String, Long>>): String {
        val hot = files.filter { it.second > 0 }
        if (hot.isEmpty()) return ""
        return " · Übersteuert: " + hot.joinToString(", ") { "${it.first} (${it.second} Samples)" } + " – Spur- oder Master-Fader senken"
    }
    private fun safe(name: String) = name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40).ifBlank { "track" }
    private fun fileBase(p: Project?) = safe(p?.metadata?.title ?: "riff").take(60)
    fun currentProject(): Project? = session?.state?.value?.project

    // ------------------------------------------------------------------ reference audio
    /**
     * Adds a user audio file as reference clip. The bytes are probed first (format, decoder available), then stored by
     * hash and referenced by a new clip at offset 0. Playback loads and decodes the clip on demand.
     */
    fun importAudio(bytes: ByteArray, fileName: String?) { viewModelScope.launch {
        val decoder = services.audioDecoder; val assets = services.assets
        if (decoder == null || assets == null) { showNotice("Referenz-Audio ist in dieser Umgebung nicht verfügbar"); return@launch }
        if (bytes.size > MAX_AUDIO_BYTES) { showNotice("Die Audiodatei ist größer als 64 MiB"); return@launch }
        mutable.value = mutable.value.copy(busy = "Audio prüfen", busyProgress = null)
        try {
            val info = when (val r = decoder.probe(bytes)) { is Outcome.Failure -> { showNotice(r.message); return@launch }; is Outcome.Success -> r.value }
            val name = (fileName?.takeIf { it.isNotBlank() } ?: "Referenz").take(120)
            val rights = AssetRights("Vom Nutzer importiertes Referenz-Audio", "unverified", false)
            val stored = when (val r = assets.put(name, info.mediaType.ifBlank { "application/octet-stream" }, bytes, rights)) {
                is Outcome.Failure -> { showNotice(r.message); return@launch }
                is Outcome.Success -> r.value
            }
            val clip = AudioClip(ids.next(), stored.hash, name.substringBeforeLast('.').ifBlank { name })
            if (command(EditorCommand.AddAudioClip(clip, stored)) is Outcome.Success && info.durationMicros > MAX_CLIP_MICROS)
                showNotice("„${clip.name}“ ist länger als 10 Minuten; abgespielt werden die ersten 10 Minuten.")
        } finally { mutable.value = mutable.value.copy(busy = null, busyProgress = null) }
    } }
    fun updateClip(clip: AudioClip) { command(EditorCommand.UpdateAudioClip(clip)) }
    /** Peaks at ~100 per second for alignment; null while unavailable. */
    suspend fun waveform(asset: AssetHash): WaveformLevel? = (services.waveforms?.peaks(asset, 480) as? Outcome.Success)?.value
    fun removeClip(id: EntityId) { command(EditorCommand.RemoveAudioClip(id)) }

    // ------------------------------------------------------------------ rhythm tapping (Zyklus 25)
    /**
     * „Rhythmus tippen“: count-in (1 bar), then the metronome (optionally the other tracks, the target track silent) plays
     * the target bars while the user taps; each touch-down/-up is mapped at once to the audible plan frame
     * ([Transport.audiblePlanFrameAt], minus the calibrated [AppSettings.tapLatencyMs]). After the last target bar the
     * recording stops by itself and the quantised preview shows; the grid can change without tapping again. „Übernehmen“
     * writes one undo step ([EditorCommand.ApplyRhythm]), asking first when it overwrites notes; „Verwerfen“ changes nothing.
     */
    private val strokes = ArrayList<TapStroke>()
    private val calibrationStrokes = ArrayList<TapStroke>()
    private var rhythmSpans: List<RouteSpan> = emptyList()
    private var rhythmRate = 48_000
    private var rhythmJob: Job? = null
    private var calibrationClicks: List<Long> = emptyList()
    /** Track, voice and string of the recording (the cursor when it started). */
    private var rhythmOrigin: Selection? = null
    /** Placeholders of the last „Raster“ apply: a fret entered on one jumps to the next (auto-advance between tapped attacks). */
    private var rhythmSlots: List<RhythmSlot> = emptyList()
    private var rhythmSlotVoice: Pair<EntityId, Int>? = null
    /**
     * Zyklus 27: the tapping copy (the score plus appended bars, muted tracks, own negative revision), the ids of its
     * appended bars in order, whether the recording is open-ended, the existing target bars kept even when empty, the stop
     * frame (NaN until „Stopp“ or Auto-Stopp), the route index of the start, the playback settings of the copy and the
     * extension state (an open recording that met a repeat or jump ends before it and is never extended).
     */
    private var rhythmCopy: Project? = null
    private var rhythmAppended: List<EntityId> = emptyList()
    private var rhythmOpen = false
    private var rhythmKeep = 0
    private var rhythmStop = Double.NaN
    private var rhythmStartRoute = 0
    private var rhythmPractice = PracticeSettings()
    private var rhythmBounded = false
    private var rhythmExtending = false
    private var rhythmCapNoticed = false
    private val rhythmBusy: Boolean get() = rhythm.value.phase == RhythmTapPhase.COUNT_IN || rhythm.value.phase == RhythmTapPhase.RECORDING || rhythm.value.phase == RhythmTapPhase.CALIBRATING

    fun openRhythmTap() {
        val e = state.value.editor ?: return
        if (rhythmBusy) return
        val track = e.project.tracks.firstOrNull { it.id == e.selection.trackId } ?: return
        val fretted = track.instrument.tuning.isNotEmpty()
        val drums = track.instrument.kind == InstrumentKind.PERCUSSION
        if (!fretted && !drums) { showNotice("Rhythmus tippen gibt es für Gitarre, Bass und Schlagzeug"); return }
        val previous = rhythm.value
        mutableRhythm.value = RhythmTapState(RhythmTapPhase.SETUP, useRange = previous.useRange, barCount = previous.barCount, grid = previous.grid, accompaniment = previous.accompaniment,
            openEnded = previous.openEnded, autoStop = previous.autoStop, fretted = fretted, drums = drums, string = 0, drumNote = if (drums) previous.drumNote else 38)
        refreshRhythmLatency()
        refreshRhythmTarget()
    }
    fun closeRhythmTap() { if (rhythmBusy) cancelRhythmTap(); mutableRhythm.value = rhythm.value.copy(phase = RhythmTapPhase.IDLE, result = null, confirm = null) }
    fun setRhythmBarCount(count: Int) { mutableRhythm.value = rhythm.value.copy(barCount = count.coerceIn(1, 8), openEnded = false); refreshRhythmTarget() }
    /** Zyklus 27: „Offen (Takte anhängen)“ instead of a bar count. */
    fun setRhythmOpenEnded(open: Boolean) { mutableRhythm.value = rhythm.value.copy(openEnded = open); refreshRhythmTarget() }
    fun setRhythmAutoStop(on: Boolean) { mutableRhythm.value = rhythm.value.copy(autoStop = on) }
    fun setRhythmUseRange(use: Boolean) { mutableRhythm.value = rhythm.value.copy(useRange = use); refreshRhythmTarget() }
    fun setRhythmAccompaniment(on: Boolean) { mutableRhythm.value = rhythm.value.copy(accompaniment = on) }
    fun setRhythmString(string: Int) { mutableRhythm.value = rhythm.value.copy(string = string) }
    fun setRhythmDrumNote(midi: Int) { mutableRhythm.value = rhythm.value.copy(drumNote = midi.coerceIn(0, 127)) }
    /** Grid change; in the preview the same taps are quantised again (no new recording). */
    fun setRhythmGrid(grid: RhythmGrid) {
        mutableRhythm.value = rhythm.value.copy(grid = grid)
        if (rhythm.value.phase == RhythmTapPhase.PREVIEW) quantizeRhythm()
    }

    /**
     * Existing target bars and the bars missing after the end of the piece (Zyklus 27: appended instead of refused): the
     * marked range; open — the cursor bar (the recording goes on from there); else [RhythmTapState.barCount] bars from the cursor.
     */
    private fun rhythmTarget(e: EditorState, r: RhythmTapState): Pair<List<EntityId>, Int> {
        val p = e.project
        if (r.useRange && e.selection.rangeEnd != null) return e.selection.measureRange(p) to 0
        val i = p.measures.indexOfFirst { it.id == e.selection.measureId }
        if (i < 0) return emptyList<EntityId>() to 0
        if (r.openEnded) return listOf(p.measures[i].id) to 0
        return p.measures.subList(i, minOf(p.measures.size, i + r.barCount)).map { it.id } to maxOf(0, i + r.barCount - p.measures.size)
    }
    private fun refreshRhythmTarget() {
        val e = state.value.editor ?: return
        val (bars, missing) = rhythmTarget(e, rhythm.value)
        val open = rhythm.value.openEnded && !(rhythm.value.useRange && e.selection.rangeEnd != null) && bars.isNotEmpty()
        val first = e.project.measures.indexOfFirst { it.id == bars.firstOrNull() } + 1
        val count = bars.size + missing
        val label = when { bars.isEmpty() -> ""; open -> "Ab Takt $first, offen"; count == 1 -> "Takt $first"; else -> "Takte $first–${first + count - 1}" } +
            if (missing > 0) " (+$missing neu)" else ""
        mutableRhythm.value = rhythm.value.copy(bars = bars, label = label, newBars = missing, openActive = open)
    }

    /**
     * Start → count-in → recording. A fixed target stops by itself after its last bar ([finishRhythmTap]); bars it needs
     * after the end of the piece are in the tapping copy (Zyklus 27). An open recording runs until „Stopp“ ([stopRhythmTap])
     * or Auto-Stopp; its copy carries [OPEN_BUFFER_BARS] buffer bars after the end and is extended while it plays.
     */
    fun startRhythmTap() { viewModelScope.launch {
        if (rhythmBusy) return@launch
        val e = session?.state?.value ?: return@launch
        refreshRhythmTarget()
        val r = rhythm.value
        val bars = r.bars
        if (bars.isEmpty()) return@launch
        val track = e.project.tracks.firstOrNull { it.id == e.selection.trackId } ?: return@launch
        rhythmOrigin = e.selection.copy(rangeEnd = null)
        val open = r.openActive
        rhythmOpen = open
        rhythmKeep = if (open) 1 else bars.size
        rhythmStop = Double.NaN
        rhythmBounded = false
        rhythmExtending = false
        rhythmCapNoticed = false
        // Zyklus 27: bars after the end of the piece (fixed: the missing ones; open: a buffer) — the same function writes them on apply.
        rhythmAppended = List(if (open) OPEN_BUFFER_BARS else r.newBars) { ids.next() }
        // Own (negative) revision: the transport skips a prepare with the same id, revision and settings, so a tapping copy
        // with the score's revision could survive the restore when the practice settings equal the tapping ones (muted tracks).
        val base = appendEmptyMeasures(e.project, rhythmAppended, ids)
        val playback = base.copy(revision = -1 - e.project.revision,
            tracks = base.tracks.map { t -> t.copy(mix = t.mix.copy(solo = false, mute = if (r.accompaniment) t.id == track.id || t.mix.mute else true)) })
        rhythmCopy = playback
        val route = routeIndexFor(playback, bars.first()) ?: run { showNotice("Wiedergaberoute ungültig"); return@launch }
        rhythmStartRoute = route
        strokes.clear()
        rhythmRoute = audioRoute
        mutableRhythm.value = r.copy(phase = RhythmTapPhase.COUNT_IN, taps = 0, result = null, confirm = null, latencyMs = tapLatencyMs(), latencyDevice = audioRoute?.label,
            latencyOwn = LatencyProfiles.profile(settings.settings.value, audioRoute) != null)
        val all = beginTapPlayback(playback, PlaybackOccurrence(ScorePosition(bars.first()), 1, route), Int.MAX_VALUE) ?: return@launch
        if (open) {
            // Over the bars played in written order; a repeat or jump ahead ends the recording before it (nothing is appended).
            val run = linearRun(all)
            rhythmSpans = run
            rhythmBounded = run.size < all.size
            if (rhythmBounded) showNotice("Wiederholung oder Sprung nach Takt ${run.last().measureIndex + 1}: die Aufnahme endet dort")
            watchTapPlayback(run.first().startFrame, if (rhythmBounded) run.last().endFrame else Long.MAX_VALUE, endsPiece = true, tick = ::openRecordingTick) { finishRhythmTap() }
            return@launch
        }
        val spans = all.take(bars.size + rhythmAppended.size)
        if (spans.map { it.measureId } != bars + rhythmAppended) {
            abortRhythm("Der Bereich wird wegen Wiederholungen oder Sprüngen nicht am Stück gespielt – kürzeren Bereich wählen"); return@launch
        }
        rhythmSpans = spans
        watchTapPlayback(spans.first().startFrame, spans.last().endFrame, spans.last().endFrame >= (transport.installedPlan()?.totalFrames ?: Long.MAX_VALUE)) { finishRhythmTap() }
    } }

    /**
     * Open recording, per transport state while recording: Auto-Stopp (two empty bars after the last touch, when on) and
     * the extension of the copy once the playhead is in its second-to-last bar or less than two seconds from its end —
     * [OPEN_EXTEND_BARS] more bars, prepared at the same position (EngineTransport swaps an appending plan seamlessly: the
     * metronome and the tap time map go on unchanged). Returns true to end the recording.
     */
    private fun openRecordingTick(frame: Long): Boolean {
        if (rhythm.value.phase != RhythmTapPhase.RECORDING) return false
        val spans = rhythmSpans
        if (spans.isEmpty()) return false
        if (rhythm.value.autoStop) RhythmAutoStop.stopFrame(spans, strokes)?.let { stop -> if (frame >= stop) { rhythmStop = stop.toDouble(); return true } }
        if (rhythmBounded || rhythmExtending) return false
        val threshold = minOf(spans[maxOf(0, spans.size - 2)].startFrame, spans.last().endFrame - 2L * rhythmRate)
        if (frame < threshold) return false
        if (rhythmAppended.size >= OPEN_MAX_NEW_BARS) {
            if (!rhythmCapNoticed) { rhythmCapNoticed = true; showNotice("Höchstens $OPEN_MAX_NEW_BARS neue Takte je Aufnahme – sie endet am Ende der angehängten Takte") }
            return false
        }
        extendRhythmCopy()
        return false
    }

    /**
     * Appends [OPEN_EXTEND_BARS] buffer bars to the tapping copy and prepares it (same project id, next negative revision).
     * Not cancelled halfway: a prepare cut off between posting the program and recording it would leave the transport's
     * plan unlike the engine's. A finish or abort meanwhile queues its restore behind it (the transport's mutex is fair).
     */
    private fun extendRhythmCopy() {
        val copy = rhythmCopy ?: return
        rhythmExtending = true
        val more = List(minOf(OPEN_EXTEND_BARS, OPEN_MAX_NEW_BARS - rhythmAppended.size)) { ids.next() }
        val next = appendEmptyMeasures(copy, more, ids).copy(revision = copy.revision - 1)
        viewModelScope.launch {
            try {
                if (!rhythmBusy || rhythmCopy !== copy) return@launch
                val prepared = withContext(NonCancellable) { transport.prepare(next, rhythmPractice) }
                if (!rhythmBusy || rhythmCopy !== copy || prepared is Outcome.Failure) return@launch
                val plan = transport.installedPlan() ?: return@launch
                rhythmCopy = next
                rhythmAppended = rhythmAppended + more
                val run = linearRun(plan.spans.drop(rhythmStartRoute))
                if (run.size >= rhythmSpans.size) rhythmSpans = run
            } finally { rhythmExtending = false }
        }
    }

    /**
     * „Stopp“ (open recording): ends it at the audible plan frame of [nanos] (calibration subtracted, like a tap); a finger
     * still down is released there. In the count-in nothing was recorded: it cancels.
     */
    fun stopRhythmTap(nanos: Long = System.nanoTime()) {
        val phase = rhythm.value.phase
        if (!rhythmOpen || (phase != RhythmTapPhase.RECORDING && phase != RhythmTapPhase.COUNT_IN)) return
        if (phase == RhythmTapPhase.COUNT_IN) { abortRhythm(null); return }
        val raw = transport.audiblePlanFrameAt(nanos)
        val at = if (raw.isNaN()) transport.state.value.sample?.frame?.toDouble() ?: return else raw - tapLatencyMs() * rhythmRate / 1000.0
        rhythmStop = at
        rhythmJob?.cancel(); rhythmJob = null
        viewModelScope.launch { finishRhythmTap() }
    }

    /** Prepares, seeks and plays [playback] with metronome and a one-bar count-in; returns [count] spans from the start. */
    private suspend fun beginTapPlayback(playback: Project, start: PlaybackOccurrence, count: Int): List<RouteSpan>? {
        transport.stop()
        rhythmPractice = PracticeSettings(speed = practice.value.speed, countInBars = 1, metronome = true)
        val prepared = transport.prepare(playback, rhythmPractice)
        if (prepared is Outcome.Failure) { abortRhythm(prepared.message); return null }
        transport.seek(start)
        val played = transport.play()
        if (played is Outcome.Failure) { abortRhythm(played.message); return null }
        val plan = transport.installedPlan() ?: run { abortRhythm("Wiedergabe nicht verfügbar"); return null }
        rhythmRate = plan.sampleRate
        return plan.spans.drop(start.routeIndex).take(count)
    }

    /**
     * Follows the transport: count-in → recording; ends at [end] (or when the transport stops at the end of the piece,
     * [endsPiece]) with [onEnd]; a pause, failure or stop before the end (audio focus, headphones) aborts without writing.
     */
    private fun watchTapPlayback(start: Long, end: Long, endsPiece: Boolean, tick: ((Long) -> Boolean)? = null, onEnd: suspend () -> Unit) {
        rhythmJob?.cancel()
        rhythmJob = viewModelScope.launch {
            var interrupted = false
            transport.state.first { s ->
                if (!rhythmBusy) return@first true
                val frame = s.sample?.frame
                if (rhythm.value.phase == RhythmTapPhase.COUNT_IN && s.status == TransportStatus.PLAYING && !s.countingIn && frame != null && frame > start)
                    mutableRhythm.value = rhythm.value.copy(phase = RhythmTapPhase.RECORDING)
                when {
                    s.status == TransportStatus.PLAYING && frame != null && frame >= end -> true
                    tick != null && s.status == TransportStatus.PLAYING && !s.countingIn && frame != null && tick(frame) -> true
                    s.status == TransportStatus.STOPPED && endsPiece && frame != null && frame >= start -> true
                    s.status != TransportStatus.PLAYING && s.status != TransportStatus.PREPARING -> { interrupted = true; true }
                    else -> false
                }
            }
            if (!rhythmBusy) return@launch
            if (interrupted) abortRhythm("Aufnahme unterbrochen") else onEnd()
        }
    }

    private suspend fun stopTapPlayback() {
        transport.stop()
        session?.state?.value?.project?.let { transport.prepare(it, practice.value) }
    }
    private fun abortRhythm(message: String?) {
        mutableRhythm.value = rhythm.value.copy(phase = RhythmTapPhase.SETUP)
        rhythmJob?.cancel(); rhythmJob = null
        viewModelScope.launch { stopTapPlayback() }
        if (message != null) showNotice(message)
    }
    /** Cancel while counting in, recording or calibrating: nothing is written. */
    fun cancelRhythmTap() { if (rhythmBusy) abortRhythm(null) }

    private suspend fun finishRhythmTap() {
        if (rhythm.value.phase != RhythmTapPhase.RECORDING && rhythm.value.phase != RhythmTapPhase.COUNT_IN) return
        mutableRhythm.value = rhythm.value.copy(phase = RhythmTapPhase.PREVIEW)
        if (rhythmOpen && !rhythmStop.isNaN()) {
            // Zyklus 27: up to the stop — later touches go, a finger still down is released there; the spans keep the stop
            // bar and the next one (a tap just before its downbeat snaps there), empty bars are trimmed after quantising.
            val stop = rhythmStop
            strokes.removeAll { it.downFrame > stop }
            for (i in strokes.indices) if (strokes[i].upFrame.isNaN()) strokes[i] = strokes[i].copy(upFrame = stop)
            val bar = rhythmSpans.indexOfFirst { stop < it.endFrame }.let { if (it < 0) rhythmSpans.lastIndex else it }
            rhythmSpans = rhythmSpans.take(minOf(rhythmSpans.size, bar + 2))
        }
        stopTapPlayback()
        quantizeRhythm()
    }
    private fun quantizeRhythm() {
        val e = session?.state?.value ?: return
        val origin = rhythmOrigin ?: return
        val r = rhythm.value
        // The copy knows the appended bars too.
        val measures = (rhythmCopy ?: e.project).measures
        val compound = { b: Int -> measures.firstOrNull { it.id == rhythmSpans.getOrNull(b)?.measureId }?.signature?.let { s -> s.denominator >= 8 && s.numerator % 3 == 0 && s.numerator > 3 } == true }
        val raw = try { RhythmQuantizer.quantize(strokes.toList(), rhythmSpans, rhythmRate, r.grid, compound) } catch (x: IllegalArgumentException) { showNotice(x.message); return }
        // Zyklus 27: new bars only as far as tapped (buffer bars after the last content are dropped); a fixed target keeps its existing bars.
        val result = raw.trimmed(rhythmKeep)
        val appended = rhythmAppended.toSet()
        mutableRhythm.value = r.copy(result = result, overwrites = rhythmOverwrites(e.project, origin.trackId, origin.voice, result.bars.map { it.measureId }),
            newBars = result.bars.count { it.measureId in appended })
    }

    /** Touch-down on the tap surface; [nanos] = event time (System.nanoTime base). Haptics are the UI's. */
    fun rhythmTapDown(nanos: Long) {
        val phase = rhythm.value.phase
        if (phase != RhythmTapPhase.COUNT_IN && phase != RhythmTapPhase.RECORDING && phase != RhythmTapPhase.CALIBRATING) return
        val raw = transport.audiblePlanFrameAt(nanos)
        if (raw.isNaN()) return
        if (phase == RhythmTapPhase.CALIBRATING) { calibrationStrokes += TapStroke(raw); mutableRhythm.value = rhythm.value.copy(taps = calibrationStrokes.size); return }
        strokes += TapStroke(raw - tapLatencyMs() * rhythmRate / 1000.0)
        mutableRhythm.value = rhythm.value.copy(taps = strokes.size)
    }
    /** Touch-up: the held time of the last stroke (calibration ignores it). */
    fun rhythmTapUp(nanos: Long) {
        val phase = rhythm.value.phase
        if (phase != RhythmTapPhase.COUNT_IN && phase != RhythmTapPhase.RECORDING) return
        val i = strokes.indexOfLast { it.upFrame.isNaN() }
        if (i < 0) return
        val raw = transport.audiblePlanFrameAt(nanos)
        if (raw.isNaN()) return
        strokes[i] = strokes[i].copy(upFrame = raw - tapLatencyMs() * rhythmRate / 1000.0)
    }

    /** „Übernehmen“: one undo step; with notes in the target voice the first call only asks ([RhythmTapState.confirm]). */
    fun applyRhythm(mode: RhythmMode, confirmed: Boolean = false) {
        val r = rhythm.value
        val result = r.result ?: return
        val origin = rhythmOrigin ?: return
        if (r.phase != RhythmTapPhase.PREVIEW) return
        if (!confirmed && r.overwrites > 0) { mutableRhythm.value = r.copy(confirm = mode); return }
        // Zyklus 27: the bars after the end of the piece are appended in the same undo step.
        val appended = rhythmAppended.toSet()
        val outcome = command(EditorCommand.ApplyRhythm(origin.trackId, origin.voice, result.bars, mode, r.string, r.drumNote, appended = result.bars.map { it.measureId }.filter { it in appended }))
        if (outcome is Outcome.Failure) { mutableRhythm.value = r.copy(confirm = null); return }
        val slots = result.slots()
        if (mode == RhythmMode.GRID && slots.isNotEmpty()) {
            rhythmSlots = slots; rhythmSlotVoice = origin.trackId to origin.voice
            moveCursorTo(origin.copy(measureId = slots.first().measureId, offset = slots.first().offset, string = if (r.fretted) r.string else origin.string))
        } else moveCursorTo(origin.copy(measureId = result.bars.first().measureId, offset = Fraction.ZERO))
        mutableRhythm.value = r.copy(phase = RhythmTapPhase.IDLE, result = null, confirm = null)
        val added = if (r.newBars > 0) ", +${r.newBars} ${if (r.newBars == 1) "Takt" else "Takte"}" else ""
        showNotice(if (mode == RhythmMode.GRID) "Rhythmus übernommen: ${result.onsets} Anschläge$added – Bünde eingeben, der Cursor springt zum nächsten Anschlag"
            else "Rhythmus übernommen: ${result.onsets} Anschläge$added")
    }
    fun cancelRhythmConfirm() { mutableRhythm.value = rhythm.value.copy(confirm = null) }
    /** „Verwerfen“: closes the preview; the score is unchanged. */
    fun discardRhythm() { mutableRhythm.value = rhythm.value.copy(phase = RhythmTapPhase.IDLE, result = null, confirm = null) }
    /** „Nochmal“: a new recording with the same settings. */
    fun retryRhythm() { mutableRhythm.value = rhythm.value.copy(phase = RhythmTapPhase.SETUP, result = null, confirm = null); startRhythmTap() }

    private fun rhythmSlotIndex(e: EditorState): Int {
        val (track, voice) = rhythmSlotVoice ?: return -1
        if (rhythmSlots.isEmpty() || e.selection.trackId != track || e.selection.voice != voice || e.selection.rangeEnd != null) return -1
        val beat = selectedBeat(e) ?: return -1
        if (beat.notes.isNotEmpty()) return -1
        return rhythmSlots.indexOfFirst { it.measureId == e.selection.measureId && it.offset == e.selection.offset }
    }
    /**
     * After a fret on placeholder [index]: the held note continues into its continuation placeholders (tied, same undo step),
     * then (with auto-advance) the cursor jumps to the next tapped attack that is still empty — rests between attacks are skipped.
     */
    private fun fillRhythmSlot(index: Int, at: Selection, changed: Boolean, advance: Boolean) {
        val active = session ?: return
        for ((m, o) in rhythmSlots[index].continuations) {
            val beat = voiceBeats(active.state.value.project, at.trackId, m, at.voice).firstOrNull { it.offset == o }
            if (beat == null || beat.notes.isNotEmpty()) break
            try { active.select(at.copy(measureId = m, offset = o, rangeEnd = null)) } catch (x: IllegalArgumentException) { break }
            if (command(EditorCommand.ToggleTie, sameUndoStep = changed) is Outcome.Failure) break
        }
        if (!advance) { moveCursorTo(at); return }
        val p = active.state.value.project
        val next = (index + 1 until rhythmSlots.size).firstOrNull { i -> rhythmSlots[i].let { sl -> voiceBeats(p, at.trackId, sl.measureId, at.voice).firstOrNull { it.offset == sl.offset }?.notes?.isEmpty() == true } }
        if (next == null) { rhythmSlots = emptyList(); rhythmSlotVoice = null; moveCursorTo(at); showNotice("Alle getippten Anschläge belegt"); return }
        navigateTo(at.copy(measureId = rhythmSlots[next].measureId, offset = rhythmSlots[next].offset, rangeEnd = null))
    }

    /** „Kalibrieren“: 8 clicks at 100 BPM after a one-bar count-in; the user taps along, the median offset is stored. */
    fun startTapCalibration() { viewModelScope.launch {
        if (rhythmBusy) return@launch
        val e = session?.state?.value ?: return@launch
        val cal = calibrationProject(e.project)
        val measures = cal.measures
        calibrationStrokes.clear()
        rhythmRoute = audioRoute
        mutableRhythm.value = rhythm.value.copy(phase = RhythmTapPhase.CALIBRATING, taps = 0)
        val spans = beginTapPlayback(cal, PlaybackOccurrence(ScorePosition(measures.first().id), 1, 0), 2) ?: return@launch
        calibrationClicks = transport.installedPlan()?.clicks?.map { it.frame }.orEmpty()
        watchTapPlayback(spans.first().startFrame, spans.last().endFrame, endsPiece = true) { finishCalibration() }
    } }
    private suspend fun finishCalibration() {
        if (rhythm.value.phase != RhythmTapPhase.CALIBRATING) return
        mutableRhythm.value = rhythm.value.copy(phase = RhythmTapPhase.SETUP)
        stopTapPlayback()
        val offset = TapCalibration.offsetMillis(TapCalibration.deltasMillis(calibrationStrokes.map { it.downFrame }, calibrationClicks, rhythmRate))
        if (offset == null) { showNotice("Kalibrierung unsicher – bitte zu jedem Klick tippen und wiederholen"); return }
        // Zyklus 26: stored for the device the calibration ran on (without a known device: the device-less value).
        val device = rhythmRoute
        settings.update(LatencyProfiles.calibrated(settings.settings.value, device, offset, System.currentTimeMillis()))
        refreshRhythmLatency()
        showNotice("Tipp-Latenz gespeichert: ${kotlin.math.round(offset).toInt()} ms" + (device?.let { " für ${it.label}" } ?: ""))
    }
    /** „Zurücksetzen“ for the active device (its profile goes, the fallback applies); without a known device back to 0 ms. */
    fun resetTapCalibration() { viewModelScope.launch { settings.update(LatencyProfiles.reset(settings.settings.value, audioRoute)); refreshRhythmLatency() } }

    // ------------------------------------------------------------------ output device (Zyklus 26)
    /** The output device now, null while unknown (no monitor or nothing detected). */
    val audioRoute: AudioRoute? get() = services.audioRoutes?.route?.value
    /** Device the running recording/calibration started on. */
    private var rhythmRoute: AudioRoute? = null
    private fun tapLatencyMs(): Double = LatencyProfiles.tapLatencyMs(settings.settings.value, audioRoute)
    private fun refreshRhythmLatency() {
        if (rhythm.value.phase == RhythmTapPhase.IDLE) return
        val device = audioRoute
        mutableRhythm.value = rhythm.value.copy(latencyMs = tapLatencyMs(), latencyDevice = device?.label, latencyOwn = LatencyProfiles.profile(settings.settings.value, device) != null)
    }
    private fun onRouteChanged(device: AudioRoute?) {
        var aborted: String? = null
        if (rhythmBusy && device?.key != rhythmRoute?.key) {
            val what = if (rhythm.value.phase == RhythmTapPhase.CALIBRATING) "Kalibrierung" else "Aufnahme"
            aborted = "Ausgabegerät gewechselt (${device?.label ?: "unbekannt"}) – $what abgebrochen"
            abortRhythm(null)
        }
        refreshRhythmLatency()
        // Once per device and app process, only for radio links without their own calibration; after an abort both in one notice.
        val hint = device?.takeIf { LatencyProfiles.needsCalibrationHint(settings.settings.value, it) && services.routeNotices.firstTime(it.key) }
            ?.let { "${it.productName.ifBlank { it.kind.labelDe }}: Latenz nicht kalibriert" }
        when {
            hint != null -> {
                val text = if (aborted != null) "$aborted. $hint" else hint
                mutable.value = mutable.value.copy(notice = text, noticeAction = NoticeAction(text, NoticeActionKind.CALIBRATE_LATENCY, "Kalibrieren"))
            }
            aborted != null -> showNotice(aborted)
        }
    }

    /**
     * Zyklus 26: extra delay of the visible playhead on the active device, max(0, tap(device) − tap(speaker/wired)) when
     * both are calibrated, else 0 (LatencyProfiles.playheadDelayMs). Audio is not delayed.
     */
    fun playheadDelayMs(): Double = LatencyProfiles.playheadDelayMs(settings.settings.value, audioRoute)
    private val delayedPlayhead = DelayedPlayhead()
    /**
     * Playhead for the score: the transport position, delayed by [playheadDelayMs] while PLAYING (no backward steps; play,
     * seek and stop show their target at once — see [DelayedPlayhead]); null when neither playing nor paused.
     */
    fun displayedPlayhead(s: TransportState, nanos: Long = System.nanoTime()): PlaybackOccurrence? =
        delayedPlayhead.position(s, transport.installedPlan(), playheadDelayMs(), nanos)

    // ------------------------------------------------------------------ internals
    private fun publish() {
        mutable.value = mutable.value.copy(editor = session?.state?.value)
        relayout()
        val project = session?.state?.value?.project ?: return
        // While a rhythm is tapped the transport plays the tapping copy (target track silent): keep it.
        if (!rhythmBusy && (transport.state.value.status == TransportStatus.PLAYING || transport.state.value.status == TransportStatus.PAUSED)) viewModelScope.launch { transport.prepare(project, practice.value) }
    }
    private fun enqueueSave() { mutable.value = mutable.value.copy(saveStatus = SaveStatus.SAVING); session?.state?.value?.project?.let { saves.trySend(it) } }
    private suspend fun persist(project: Project): Outcome<Unit> = saveMutex.withLock {
        if (project.revision <= savedRevision) return@withLock Outcome.Success(Unit)
        val result = repository.save(project, savedRevision)
        when (result) {
            is Outcome.Failure -> mutable.value = mutable.value.copy(saveStatus = SaveStatus.FAILED, notice = result.message)
            is Outcome.Success -> { savedRevision = project.revision; mutable.value = mutable.value.copy(saveStatus = if (session?.state?.value?.project?.revision == savedRevision) SaveStatus.SAVED else SaveStatus.SAVING) }
        }
        result
    }
    // viewModelScope is already cancelled when onCleared runs: a launch there never executes, so the microphone would stay open.
    override fun onCleared() { tunerJob?.cancel(); services.tuner?.let { t -> kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { t.stop() } } }

    init {
        // Zyklus 26: the output device selects the latency profile; a switch aborts a running recording/calibration. Last in
        // the class: viewModelScope runs on Main.immediate, so the first route arrives while the instance is constructed.
        services.audioRoutes?.let { routes -> viewModelScope.launch { routes.route.collect { onRouteChanged(it) } } }
    }

    companion object {
        /** Layout margin of the editor canvas (layout units). */
        const val EDITOR_MARGIN = 6f
        private const val MAX_AUDIO_BYTES = 64 * 1024 * 1024
        private const val MAX_CLIP_MICROS = 600_000_000L
        /** Zyklus 27, open recording: buffer bars after the end of the piece at the start, bars per extension, limit per recording. */
        const val OPEN_BUFFER_BARS = 4
        const val OPEN_EXTEND_BARS = 4
        const val OPEN_MAX_NEW_BARS = 64
    }
}
