package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.*
import dev.riffworkbench.ports.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                publish()
            }
        }
    } }

    // ------------------------------------------------------------------ selection and note entry
    fun select(selection: Selection) {
        val s = session ?: return
        val adjusted = if (input.value.rangeMode) s.state.value.selection.copy(rangeEnd = selection.measureId) else selection.copy(rangeEnd = null)
        try { s.select(adjusted) } catch (e: IllegalArgumentException) { return }
        publish(); mutableInput.value = input.value.copy(buffer = "")
    }
    fun selectRange(endMeasure: EntityId?) { session?.let { s -> runCatching { s.select(s.state.value.selection.copy(rangeEnd = endMeasure)) }; publish() } }
    fun setRangeMode(enabled: Boolean) { mutableInput.value = input.value.copy(rangeMode = enabled); if (!enabled) selectRange(null) }

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
        val result = command(EditorCommand.PutNote(fret = fret, midiNote = if (instrument.tuning.isEmpty()) input.value.buffer.toIntOrNull() ?: 60 else null, duration = input.value.duration, marks = marks))
        if (result is Outcome.Success) {
            previewSelected()
            mutableInput.value = input.value.copy(buffer = "")
            if (settings.settings.value.autoAdvance && !input.value.chordMode) move(1)
        }
    }
    private fun existingFret(e: EditorState): Int? = selectedBeat(e)?.notes?.firstOrNull { it.string == e.selection.string }?.fret

    fun rest() { if (command(EditorCommand.PutRest(input.value.duration)) is Outcome.Success && settings.settings.value.autoAdvance && !input.value.chordMode) move(1) }
    fun setDuration(denominator: Int = input.value.duration.denominator, dots: Int = input.value.duration.dots) {
        val duration = WrittenDuration(denominator, dots, input.value.duration.tuplets)
        val current = state.value.editor ?: return
        if (selectedBeat(current) != null && command(EditorCommand.SetDuration(duration)) is Outcome.Failure) return
        mutableInput.value = input.value.copy(duration = duration)
    }
    fun setTuplet(ratio: TupletRatio?) {
        val current = state.value.editor ?: return
        if (selectedBeat(current) != null && command(EditorCommand.SetTuplet(ratio)) is Outcome.Failure) return
        mutableInput.value = input.value.copy(duration = input.value.duration.copy(tuplets = listOfNotNull(ratio)))
    }
    fun togglePalmMute() {
        mutableInput.value = input.value.copy(palmMute = !input.value.palmMute)
        val current = state.value.editor ?: return
        if (selectedNote(current) != null) command(EditorCommand.ApplyArticulation(Articulation.Mark(NoteMark.PALM_MUTE)))
    }
    fun toggleMark(kind: NoteMark) { command(EditorCommand.ApplyArticulation(Articulation.Mark(kind))) }
    fun articulation(a: Articulation) { if (command(EditorCommand.ApplyArticulation(a)) is Outcome.Success) previewSelected() }
    fun chordMode(enabled: Boolean) { mutableInput.value = input.value.copy(chordMode = enabled) }
    /** Changes only the entry duration (grid), never the selected beat. */
    fun setGrid(duration: WrittenDuration) { mutableInput.value = input.value.copy(duration = duration); relayout() }
    fun retunePolicy(): RetuningPolicy = settings.settings.value.retuningPolicy

    fun move(direction: Int) {
        val current = state.value.editor ?: return
        val p = current.project; val s = current.selection; val index = p.measures.indexOfFirst { it.id == s.measureId }
        val step = selectedBeat(current)?.takeIf { direction > 0 }?.duration?.length() ?: input.value.duration.length()
        val offset = s.offset + step * Fraction(direction.toLong())
        val length = p.measures[index].pickupLength ?: p.measures[index].signature.length()
        val next = when {
            offset >= length && index < p.measures.lastIndex -> s.copy(measureId = p.measures[index + 1].id, offset = Fraction.ZERO)
            offset >= length -> { command(EditorCommand.AddMeasure(p.measures[index].signature)); state.value.editor?.project?.measures?.getOrNull(index + 1)?.let { s.copy(measureId = it.id, offset = Fraction.ZERO) } ?: s }
            offset < Fraction.ZERO && index > 0 -> {
                val previous = p.measures[index - 1]
                s.copy(measureId = previous.id, offset = maxOf(Fraction.ZERO, (previous.pickupLength ?: previous.signature.length()) + input.value.duration.length() * Fraction(-1)))
            }
            offset < Fraction.ZERO -> s.copy(offset = Fraction.ZERO)
            else -> s.copy(offset = offset)
        }
        select(next)
    }
    fun moveString(delta: Int) {
        val e = state.value.editor ?: return
        val track = e.project.tracks.first { it.id == e.selection.trackId }
        select(e.selection.copy(string = (e.selection.string + delta).coerceIn(0, maxOf(0, track.instrument.tuning.lastIndex))))
    }
    fun moveMeasure(delta: Int) {
        val e = state.value.editor ?: return
        val i = (e.project.measures.indexOfFirst { it.id == e.selection.measureId } + delta).coerceIn(e.project.measures.indices)
        select(e.selection.copy(measureId = e.project.measures[i].id, offset = Fraction.ZERO))
    }
    fun toggleDrum(trackId: EntityId, measureId: EntityId, offset: Fraction, midi: Int) {
        val selection = state.value.editor?.selection ?: return
        select(selection.copy(trackId = trackId, measureId = measureId, offset = offset, string = 0))
        if (command(EditorCommand.ToggleDrum(midi, input.value.duration)) is Outcome.Success) previewSelected(midi)
    }

    fun selectedBeat(e: EditorState): Beat? = e.project.tracks.firstOrNull { it.id == e.selection.trackId }?.staves?.firstOrNull()?.bars?.firstOrNull { it.measureId == e.selection.measureId }
        ?.voices?.firstOrNull { it.index == e.selection.voice }?.beats?.firstOrNull { it.offset == e.selection.offset }
    fun selectedNote(e: EditorState): Note? {
        val track = e.project.tracks.firstOrNull { it.id == e.selection.trackId } ?: return null
        val beat = selectedBeat(e) ?: return null
        return if (track.instrument.tuning.isNotEmpty()) beat.notes.firstOrNull { it.string == e.selection.string } else beat.notes.firstOrNull()
    }

    private fun previewSelected(drumMidi: Int? = null) {
        val preview = services.preview ?: return
        val e = state.value.editor ?: return
        val note = if (drumMidi != null) selectedBeat(e)?.notes?.firstOrNull { it.pitch.midiNumber == drumMidi } else selectedNote(e)
        note ?: return
        viewModelScope.launch { preview.preview(e.project, e.selection.trackId, note) }
    }

    // ------------------------------------------------------------------ commands, history, clipboard
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
    fun setZoom(zoom: Float) { mutableView.value = view.value.copy(zoom = zoom.coerceIn(0.5f, 2.5f)) }
    fun setNotation(mode: NotationMode) { mutableView.value = view.value.copy(notation = mode); relayout() }
    fun setWrap(wrap: Boolean) { mutableView.value = view.value.copy(wrap = wrap); relayout() }
    fun setCompact(compact: Boolean) { mutableView.value = view.value.copy(compact = compact); relayout() }
    fun setFollow(follow: Boolean) { mutableView.value = view.value.copy(follow = follow) }
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
        val request = LayoutRequest(e.project, trackIndex, style, width, grid, e.selection.voice, if (e.project.tracks[trackIndex].staves.firstOrNull()?.bars?.any { b -> b.voices.count { it.beats.isNotEmpty() } > 1 } == true) e.selection.voice else null)
        layoutJob = viewModelScope.launch {
            val result = withContext(background) { runCatching { ScoreLayouter.layout(request) }.getOrNull() }
            if (result != null) mutableLayout.value = result
        }
    }

    // ------------------------------------------------------------------ practice and transport
    fun togglePlay() { viewModelScope.launch {
        val status = transport.state.value.status
        if (status == TransportStatus.PLAYING) { transport.pause(); return@launch }
        val project = session?.state?.value?.project ?: return@launch
        transport.prepare(project, practice.value).let { if (it is Outcome.Failure && it.code != FailureCode.UNAVAILABLE) showNotice(it.message) }
        // Resume continues where it paused; otherwise playback starts at the cursor (inside a loop: at the loop start).
        if (transport.state.value.status != TransportStatus.PAUSED) cursorPosition()?.let { transport.seek(it) }
        when (val r = transport.play()) { is Outcome.Failure -> showNotice(r.message); is Outcome.Success -> Unit }
    } }
    fun stop() { viewModelScope.launch { transport.stop() } }
    fun seekToCursor() { viewModelScope.launch { cursorPosition()?.let { transport.seek(it) } } }
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
    fun setSpeed(speed: Double) = updatePractice { it.copy(speed = speed.coerceIn(0.25, 2.0)) }
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
        chord.frets.forEachIndexed { string, fret -> if (fret != null && string < track.instrument.tuning.size) { editor.select(editor.state.value.selection.copy(string = string)); editor.dispatch(EditorCommand.PutNote(fret = fret, duration = input.value.duration)) } }
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
                val svg = SvgExport.render(project, project.tracks.indices.toList())
                Outcome.Success(ExportResult(svg.toByteArray(), "image/svg+xml", "${fileBase(project)}.svg", emptyList()))
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
                    is Outcome.Success -> { showNotice("WAV exportiert: ${"%.1f".format(r.value.frames / 48_000.0)} s, Spitze ${"%.1f".format(20 * kotlin.math.log10(r.value.peak.coerceAtLeast(1e-6)))} dBFS"); Outcome.Success(Unit) }
                }
                ExportTarget.STEMS -> withContext(background) {
                    val zip = ZipOutputStream(object : java.io.OutputStream() {
                        override fun write(b: Int) { sink.write(byteArrayOf(b.toByte()), 0, 1) }
                        override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len) }
                    })
                    zip.setLevel(1)
                    zip.use { out ->
                        project.tracks.forEachIndexed { i, track ->
                            out.putNextEntry(ZipEntry("${(i + 1).toString().padStart(2, '0')}_${safe(track.name)}.wav"))
                            val r = renderer.renderTo(project, options.copy(includeMasterFx = false), { b, o, l -> out.write(b, o, l) }, i) { p ->
                                p.total?.let { t -> mutable.value = mutable.value.copy(busyProgress = ((i + p.completed.toFloat() / t) / (project.tracks.size + 1)).coerceIn(0f, 1f)) }
                            }
                            if (r is Outcome.Failure) return@withContext r
                            out.closeEntry()
                        }
                        out.putNextEntry(ZipEntry("00_mix.wav"))
                        val mix = renderer.renderTo(project, options, { b, o, l -> out.write(b, o, l) }, null)
                        if (mix is Outcome.Failure) return@withContext mix
                        out.closeEntry()
                        services.formats?.exporter("midi")?.let { midi ->
                            val m = midi.export(ExportRequest(project, "midi"))
                            if (m is Outcome.Success) { out.putNextEntry(ZipEntry("tempo_and_notes.mid")); out.write(m.value.bytes); out.closeEntry() }
                        }
                        out.putNextEntry(ZipEntry("LIESMICH.txt"))
                        out.write(("Alle Stems beginnen bei Sample 0 der aufgelösten Wiedergabereihenfolge (Wiederholungen ausgeschrieben), 48 kHz, 24 Bit, gleiche Länge inkl. 2 s Ausklang.\n" +
                            "Stems ohne Master-Limiter; 00_mix.wav mit Master-Kette. MIDI-Datei mit Tempo-Map in derselben Reihenfolge.\nTempo zu Beginn: ${project.tempo.firstOrNull()?.quarterNotesPerMinute ?: 120.0} BPM\n").toByteArray())
                        out.closeEntry()
                    }
                    showNotice("${project.tracks.size} Stems + Mix exportiert")
                    Outcome.Success(Unit)
                }
                else -> Outcome.Failure(FailureCode.VALIDATION, "Kein Audioformat")
            }
        } finally { mutable.value = mutable.value.copy(busy = null, busyProgress = null) }
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
        if (bytes.size > MAX_AUDIO_BYTES) { showNotice("Die Audiodatei ist größer als 64 MB"); return@launch }
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

    // ------------------------------------------------------------------ internals
    private fun publish() {
        mutable.value = mutable.value.copy(editor = session?.state?.value)
        relayout()
        val project = session?.state?.value?.project ?: return
        if (transport.state.value.status == TransportStatus.PLAYING || transport.state.value.status == TransportStatus.PAUSED) viewModelScope.launch { transport.prepare(project, practice.value) }
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
    override fun onCleared() { tunerJob?.cancel(); viewModelScope.launch { services.tuner?.stop() } }

    private companion object {
        const val MAX_AUDIO_BYTES = 64 * 1024 * 1024
        const val MAX_CLIP_MICROS = 600_000_000L
    }
}
