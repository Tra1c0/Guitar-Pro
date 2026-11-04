package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SaveStatus { SAVED, SAVING, FAILED }
data class WorkspaceState(val loading: Boolean = true, val editor: EditorState? = null, val loadError: String? = null, val notice: String? = null, val saveStatus: SaveStatus = SaveStatus.SAVED, val snapshots: List<SnapshotInfo> = emptyList())
data class NoteInput(val buffer: String = "", val duration: WrittenDuration = WrittenDuration(), val palmMute: Boolean = false, val chordMode: Boolean = false)

class WorkspaceViewModel(
    private val id: EntityId,
    private val repository: ProjectRepository,
    private val snapshots: SnapshotRepository,
    private val exchange: NativeProjectExchange,
    private val settings: SettingsRepository,
    val transport: Transport,
    private val ids: IdSource,
) : ViewModel() {
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private val mutableInput = MutableStateFlow(NoteInput())
    val input = mutableInput.asStateFlow()
    private var session: EditorSession? = null
    private val saves = Channel<Project>(Channel.CONFLATED)
    private val saveMutex = Mutex()
    private var savedRevision = -1L
    private var lastCommand: EditorCommand? = null
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
                mutable.value = WorkspaceState(loading = false, editor = session!!.state.value, notice = result.value.diagnostics.firstOrNull { it.code == "recovered" }?.message)
                refreshSnapshots()
            }
        }
    } }
    fun select(selection: Selection) { session?.select(selection); publish(); mutableInput.value = input.value.copy(buffer = "") }
    fun digit(digit: Int) {
        if (input.value.buffer.length >= 2) { showNotice("Bund zuerst bestätigen oder Eingabe löschen"); return }
        mutableInput.value = input.value.copy(buffer = input.value.buffer + digit)
    }
    fun backspace() { if (input.value.buffer.isNotEmpty()) mutableInput.value = input.value.copy(buffer = input.value.buffer.dropLast(1)) else command(EditorCommand.Delete) }
    fun commitInput(dead: Boolean = false) {
        val instrument = state.value.editor?.let { e -> e.project.tracks.first { it.id == e.selection.trackId }.instrument } ?: return
        val marks = buildSet { if (input.value.palmMute) add(NoteMark.PALM_MUTE); if (dead) add(NoteMark.DEAD) }
        val result = command(EditorCommand.PutNote(fret = input.value.buffer.toIntOrNull() ?: 0, midiNote = if (instrument.tuning.isEmpty()) input.value.buffer.toIntOrNull() ?: 60 else null, duration = input.value.duration, marks = marks))
        if (result is Outcome.Success) { mutableInput.value = input.value.copy(buffer = ""); if (settings.settings.value.autoAdvance && !input.value.chordMode) move(1) }
    }
    fun rest() { if (command(EditorCommand.PutRest(input.value.duration)) is Outcome.Success && settings.settings.value.autoAdvance && !input.value.chordMode) move(1) }
    fun setDuration(denominator: Int = input.value.duration.denominator, dots: Int = input.value.duration.dots) {
        val duration = WrittenDuration(denominator, dots)
        val current = state.value.editor ?: return
        val beat = current.project.tracks.first { it.id == current.selection.trackId }.staves.first().bars.first { it.measureId == current.selection.measureId }.voices.first { it.index == current.selection.voice }.beats.firstOrNull { it.offset == current.selection.offset }
        if (beat != null && command(EditorCommand.SetDuration(duration)) is Outcome.Failure) return
        mutableInput.value = input.value.copy(duration = duration)
    }
    fun togglePalmMute() {
        mutableInput.value = input.value.copy(palmMute = !input.value.palmMute)
        val current = state.value.editor ?: return
        val track = current.project.tracks.first { it.id == current.selection.trackId }
        val note = track.staves.first().bars.first { it.measureId == current.selection.measureId }.voices.first { it.index == current.selection.voice }.beats.firstOrNull { it.offset == current.selection.offset }?.notes?.firstOrNull { it.string == current.selection.string }
        if (note != null) command(EditorCommand.ToggleMark(NoteMark.PALM_MUTE))
    }
    fun chordMode(enabled: Boolean) { mutableInput.value = input.value.copy(chordMode = enabled) }
    fun move(direction: Int) {
        val current = state.value.editor ?: return
        val p = current.project; val s = current.selection; val index = p.measures.indexOfFirst { it.id == s.measureId }
        val offset = s.offset + input.value.duration.length() * Fraction(direction.toLong())
        val length = p.measures[index].pickupLength ?: p.measures[index].signature.length()
        val next = when {
            offset >= length && index < p.measures.lastIndex -> s.copy(measureId = p.measures[index + 1].id, offset = Fraction.ZERO)
            offset >= length -> s
            offset < Fraction.ZERO && index > 0 -> {
                val previous = p.measures[index - 1]
                s.copy(measureId = previous.id, offset = maxOf(Fraction.ZERO, (previous.pickupLength ?: previous.signature.length()) + input.value.duration.length() * Fraction(-1)))
            }
            offset < Fraction.ZERO -> s.copy(offset = Fraction.ZERO)
            else -> s.copy(offset = offset)
        }
        select(next)
    }
    fun toggleDrum(trackId: EntityId, measureId: EntityId, offset: Fraction, midi: Int) {
        val selection = state.value.editor?.selection ?: return
        select(selection.copy(trackId = trackId, measureId = measureId, offset = offset, string = 0))
        command(EditorCommand.ToggleDrum(midi))
    }
    fun command(command: EditorCommand): Outcome<Unit> {
        val active = session ?: return Outcome.Failure(FailureCode.VALIDATION, "Projekt noch nicht geladen")
        val before = active.state.value.project.revision
        val result = active.dispatch(command)
        if (result is Outcome.Failure) showNotice(result.message)
        else if (active.state.value.project.revision != before) { lastCommand = command; enqueueSave() }
        publish()
        return result
    }
    fun repeatLast() { lastCommand?.let(::command) }
    fun undo() { val result = session?.undo(); if (result is Outcome.Success) enqueueSave() else if (result is Outcome.Failure) showNotice(result.message); publish() }
    fun redo() { val result = session?.redo(); if (result is Outcome.Success) enqueueSave() else if (result is Outcome.Failure) showNotice(result.message); publish() }
    fun showNotice(text: String?) { mutable.value = mutable.value.copy(notice = text) }
    fun retrySave() { viewModelScope.launch { flush() } }
    suspend fun flush(): Outcome<Unit> = session?.state?.value?.project?.let { persist(it) } ?: Outcome.Success(Unit)
    fun exportNative(onReady: (ExportResult) -> Unit) { viewModelScope.launch {
        val project = session?.state?.value?.project ?: return@launch
        when (val result = exchange.export(project)) { is Outcome.Failure -> showNotice(result.message); is Outcome.Success -> onReady(result.value) }
    } }
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
    private fun publish() { mutable.value = mutable.value.copy(editor = session?.state?.value) }
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
}
