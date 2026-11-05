package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.ports.*
import dev.riffworkbench.ports.SinkFormat
import dev.riffworkbench.synth.*
import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * C-AUDIO / C-PREVIEW / C-RENDER on top of [RealtimeHost]. Plans are compiled off the audio thread and
 * swapped atomically at block boundaries. The UI state is derived from the sample clock the audio thread
 * published (polled ~30 times per second); nothing here advances the transport.
 */
/** Loads and decodes reference-audio assets to engine-rate PCM (app wiring: asset store + WAV/platform decoder). */
fun interface ClipLoader { suspend fun load(asset: AssetHash, targetRate: Int): Outcome<PcmBuffer> }

class EngineTransport(
    private val host: RealtimeHost,
    private val compiler: ScorePlaybackCompiler = ScorePlaybackCompiler(),
    private val clipLoader: ClipLoader? = null,
    /** Sample bank for offline renders (live playback gets it from the host). */
    private val bankProvider: () -> SampleBank? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val pollMillis: Long = 33,
) : Transport, PreviewPlayer, OfflineRenderer {
    private val mutable = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
    override val state: StateFlow<TransportState> = mutable.asStateFlow()
    private val mutex = Mutex()
    @Volatile private var project: Project? = null
    @Volatile private var settings = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)
    @Volatile private var plan: PlaybackPlan? = null
    private var rackSignature: String? = null
    private var rackRate = 0
    private var installedRate = 0
    private var loopFrames: Pair<Long, Long>? = null
    private var poller: Job? = null
    private var lastLoops = 0L
    private var trainerPasses = 0
    private val clipCache = HashMap<Pair<AssetHash, Int>, PcmBuffer>()
    /** Seek requested before a plan existed; applied when playback starts. */
    @Volatile private var pendingSeek: PlaybackOccurrence? = null
    /** Set by pause()/stop() until the next play(): the render thread may still report the old playing state for one block. */
    @Volatile private var halted: TransportStatus? = null
    /** Bumped by every pause()/stop(); a play() that was requested before cannot start afterwards. Guarded by [haltLock]. */
    private var haltEpoch = 0L
    /** Play was posted but the render thread has not started yet: its snapshot still shows the old, non-playing position. */
    @Volatile private var starting = false
    @Volatile private var startingSince = 0L
    /** Halt epoch when the caller began preparing this play (togglePlay compiles in prepare() while the output is open). */
    /** Number of pause()/stop() calls so far; lets callers (stress test) notice an external halt between its passes. */
    override val halts: Long get() = synchronized(haltLock) { haltEpoch }
    private val haltLock = Any()
    /** stop() was the last positioning: the next play() starts at the loop start of the plan current *then* (or 0). */
    @Volatile private var restartFromTop = false
    /** Project whose plan is installed; a different project never inherits the old position. */
    private var planProject: EntityId? = null
    /** Install/Reprogram commands posted so far; the poller trusts a snapshot's frame only once the engine has applied all. */
    @Volatile private var postedPrograms = 0L
    @Volatile private var postedPlays = 0L
    /** Speed last requested by the caller (practice settings), as opposed to the speed the trainer has reached. */
    @Volatile private var requestedSpeed = 1.0

    /**
     * Stores [project] for playback. While the output is running the new plan is compiled and swapped in at the
     * same musical position; otherwise compilation waits for the next play/preview so edits never open the device.
     */
    override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> {
        return prepareLocked(project, settings)
    }
    private suspend fun prepareLocked(project: Project, settings: PracticeSettings): Outcome<Unit> = mutex.withLock {
        // The speed trainer raises the speed here; a caller that re-sends its unchanged start speed (cursor move, edit)
        // must not reset it. A different speed from the caller is a user change and wins.
        val running = mutable.value.status == TransportStatus.PLAYING || mutable.value.status == TransportStatus.PAUSED
        val keepTrainer = running && settings.trainer.enabled && settings.trainer == this.settings.trainer && settings.speed == requestedSpeed
        requestedSpeed = settings.speed
        val effective = if (keepTrainer) settings.copy(speed = this.settings.speed) else settings
        // Nothing changed (e.g. a cursor move while playing): do not recompile the whole score.
        val same = plan != null && this.project?.id == project.id && this.project?.revision == project.revision && this.settings == effective && host.isRunning
        if (this.project?.id != null && this.project?.id != project.id) {
            // Another project: positions (and a parked seek) of the old one mean nothing here.
            pendingSeek = null
            mutable.value = mutable.value.copy(position = null, sample = null)
        }
        this.project = project
        this.settings = effective
        if (same) return@withLock Outcome.Success(Unit)
        val format = host.format
        if (!host.isRunning || format == null) { plan = null; return@withLock Outcome.Success(Unit) }
        installLocked(project, format.sampleRate, forceRack = installedRate != format.sampleRate)
    }

    private suspend fun installLocked(project: Project, rate: Int, forceRack: Boolean): Outcome<Unit> {
        val compiled = withContext(Dispatchers.Default) { compiler.compileNow(project, rate, PlaybackOptions(settings.speed.coerceIn(0.1, 4.0))) }
        val next = when (compiled) { is Outcome.Failure -> { publish(TransportStatus.FAILED, compiled.message); return compiled }; is Outcome.Success -> compiled.value }
        val signature = ChannelRack.signature(project)
        if (forceRack || signature != rackSignature || rackRate != rate) {
            host.post(HostCommand.Rack(withContext(Dispatchers.Default) { ChannelRack.build(project, rate, maxOf(1024, host.format?.blockFrames ?: 1024)) }))
            rackSignature = signature; rackRate = rate
        }
        host.post(HostCommand.Mix(MixState.from(project, if (settings.metronome) 1f else 0f)))
        host.post(HostCommand.Clips(clipsFor(project, rate)))
        val previous = plan
        val program = EngineProgram.prepare(next)
        val loop = settings.loop?.let { loopFramesFor(next, it) }
        if (previous == null || installedRate != rate) host.post(HostCommand.Install(program))
        else host.post(HostCommand.Reprogram(program, previous, loop?.first ?: -1L, loop?.second ?: -1L))
        postedPrograms++
        if (planProject != null && planProject != project.id) {
            // Another project: start from its top (or loop start), never from a bar of the previous one.
            val top = loop?.first ?: 0L
            if (mutable.value.status == TransportStatus.PLAYING || starting) host.post(HostCommand.Seek(top))
            mutable.value = mutable.value.copy(sample = SamplePosition(top, rate), position = next.positionAt(top))
        } else if (mutable.value.sample == null) mutable.value.position?.let { pos ->
            // Only a position was published (seek parked before a plan existed): express it in the new plan.
            next.frameFor(pos, orNearest = true)?.let { f -> mutable.value = mutable.value.copy(sample = SamplePosition(f, rate), position = next.positionAt(f)) }
        } else if (previous != null && installedRate == rate && mutable.value.status == TransportStatus.PLAYING) mutable.value.sample?.let { s ->
            // Playing: publish the remapped position at once (the engine remaps the same way when it applies the program).
            previous.positionAt(s.frame)?.let { pos -> next.frameFor(pos, orNearest = true) }?.let { f ->
                mutable.value = mutable.value.copy(sample = SamplePosition(f, rate), position = next.positionAt(f))
            }
        }
        // While not playing, the published frame belongs to the old plan (speed or tempo changes move bars): carry it over.
        if (previous != null && installedRate == rate && planProject == project.id && mutable.value.status != TransportStatus.PLAYING) mutable.value.sample?.let { s ->
            // By bar, not by route index: inserting or deleting an earlier bar must not shift the position by a bar.
            previous.positionAt(s.frame)?.let { pos -> next.frameFor(pos, orNearest = true) }?.let { f ->
                mutable.value = mutable.value.copy(sample = SamplePosition(f, rate), position = next.positionAt(f))
            }
        }
        planProject = project.id
        installedRate = rate
        plan = next
        if (applyLoopLocked(settings.loop) is Outcome.Failure) {
            // The loop no longer exists in the new score (bars deleted): drop it visibly instead of keeping a stale label.
            loopFrames = null; host.post(HostCommand.Loop(-1, -1)); settings = settings.copy(loop = null)
            mutable.value = mutable.value.copy(loop = null, reason = "Schleife passt nicht mehr zur geänderten Partitur und wurde aufgehoben")
        }
        if (mutable.value.status == TransportStatus.UNAVAILABLE || mutable.value.status == TransportStatus.FAILED) publish(TransportStatus.STOPPED, null)
        return Outcome.Success(Unit)
    }

    private fun ensureHost(): SinkFormat? {
        if (host.isRunning) return host.format
        val previousRate = host.format?.sampleRate
        return when (val r = host.start()) {
            is Outcome.Failure -> { publish(TransportStatus.UNAVAILABLE, r.message); null }
            is Outcome.Success -> {
                if (previousRate != null && previousRate != r.value.sampleRate) { rackSignature = null; plan = null }
                startPolling(); r.value
            }
        }
    }

    /** Decoded clips for [project] at [rate]; clips that fail to load are skipped and reported through the state reason. */
    private suspend fun clipsFor(project: Project, rate: Int): ClipSet {
        val loader = clipLoader ?: return ClipSet.EMPTY
        // Only the prepared project's clips stay decoded (up to ~115 MB each): a closed project must not keep its PCM.
        clipCache.keys.removeAll { k -> k.second != rate || project.audio.clips.none { it.asset == k.first } }
        if (project.audio.clips.isEmpty()) return ClipSet.EMPTY
        val buffers = HashMap<AssetHash, PcmBuffer>()
        for (hash in project.audio.clips.map { it.asset }.distinct()) {
            val key = hash to rate
            val cached = clipCache[key]
            if (cached != null) { buffers[hash] = cached; continue }
            when (val r = withContext(Dispatchers.Default) { loader.load(hash, rate) }) {
                is Outcome.Success -> { clipCache[key] = r.value; buffers[hash] = r.value }
                is Outcome.Failure -> mutable.value = mutable.value.copy(reason = "Referenz-Audio: ${r.message}")
            }
        }
        return ClipSet.build(project, buffers, rate, settings.speed)
    }

    override suspend fun play(): Outcome<Unit> {
        return play(synchronized(haltLock) { haltEpoch })
    }

    /** A stop/pause after [since] wins, also one during the caller's preceding prepare() (togglePlay passes its start). */
    override suspend fun play(since: Long): Outcome<Unit> {
        val epoch = since
        return mutex.withLock {
        val p = project ?: return@withLock Outcome.Failure(FailureCode.VALIDATION, "Kein Projekt vorbereitet", contractId = "C-AUDIO")
        val wasRunning = host.isRunning
        val format = ensureHost() ?: return@withLock unavailable("C-AUDIO", "Audioausgabe konnte nicht geöffnet werden")
        if (!wasRunning || plan == null || installedRate != format.sampleRate) installLocked(p, format.sampleRate, forceRack = true).let { if (it is Outcome.Failure) return@withLock it }
        val current = plan ?: return@withLock unavailable("C-SEQUENCER")
        val snap = host.snapshot()
        val parked = pendingSeek
        var from = parked?.let { frameFor(current, it) } ?: (if (restartFromTop) null else state.value.sample?.frame) ?: (if (restartFromTop) 0L else snap.planFrame)
        if (snap.ended || from >= current.totalFrames) from = loopFrames?.first ?: 0L
        loopFrames?.let { (start, end) -> if (from !in start until end) from = start }
        val (countIn, clicks) = countIn(current, from)
        synchronized(haltLock) {
            // Stop/pause (button, background, audio focus) arrived while this play was compiling or decoding: they win.
            if (haltEpoch != epoch) return@withLock Outcome.Success(Unit)
            if (pendingSeek === parked) pendingSeek = null
            restartFromTop = false
            halted = null
            starting = true; startingSince = System.nanoTime()
            host.post(HostCommand.Play(from, countIn, clicks)); postedPlays++
            mutable.value = mutable.value.copy(sample = SamplePosition(from, current.sampleRate), position = current.positionAt(from))
            trainerPasses = 0; lastLoops = snap.loopsCompleted
            publish(TransportStatus.PLAYING, null)
        }
        Outcome.Success(Unit)
    } }

    override suspend fun pause(): Outcome<Unit> {
        synchronized(haltLock) {
            haltEpoch++
            // Only a running playback becomes PAUSED; a stopped transport stays STOPPED so the next play starts at the cursor.
            // The published status may lag one poll behind a play that was just posted: ask the engine as well.
            // …but not right after stop(): the engine reports playing until the stop's Pause has been rendered.
            val running = halted != TransportStatus.STOPPED && (mutable.value.status == TransportStatus.PLAYING || starting || host.snapshot().let { it.playing || it.countingIn })
            starting = false
            if (running) { halted = TransportStatus.PAUSED; host.post(HostCommand.Pause); publish(TransportStatus.PAUSED, null) }
        }
        return Outcome.Success(Unit)
    }

    override suspend fun stop(): Outcome<Unit> = synchronized(haltLock) {
        haltEpoch++
        starting = false
        pendingSeek = null
        restartFromTop = true
        halted = TransportStatus.STOPPED
        host.post(HostCommand.Pause)
        host.post(HostCommand.Seek(loopFrames?.first ?: 0L))
        mutable.value = mutable.value.copy(status = TransportStatus.STOPPED, position = plan?.positionAt(loopFrames?.first ?: 0L), sample = SamplePosition(loopFrames?.first ?: 0L, plan?.sampleRate ?: 48_000))
        Outcome.Success(Unit)
    }

    private fun frameFor(p: PlaybackPlan, position: PlaybackOccurrence): Long? = p.frameFor(position)

    override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> = mutex.withLock {
        val p = plan ?: run { pendingSeek = position; mutable.value = mutable.value.copy(position = position, sample = null); return@withLock Outcome.Success(Unit) }
        val wanted = frameFor(p, position) ?: return@withLock Outcome.Failure(FailureCode.VALIDATION, "Position liegt außerhalb der Route", contractId = "C-AUDIO")
        // As in play(): while a loop is set, playback stays inside it (a position outside means its start).
        val frame = loopFrames?.let { (a, b) -> if (wanted in a until b) wanted else a } ?: wanted
        host.post(HostCommand.Seek(frame))
        restartFromTop = false
        mutable.value = mutable.value.copy(position = p.positionAt(frame), sample = SamplePosition(frame, p.sampleRate))
        Outcome.Success(Unit)
    }

    override suspend fun setLoop(range: LoopRange?): Outcome<Unit> = mutex.withLock {
        // A rejected range must not replace the loop that is active (it would be dropped on the next recompile).
        applyLoopLocked(range).also { if (it is Outcome.Success) settings = settings.copy(loop = range) }
    }

    /** Loop frames of [range] in [p], or null when the range is not on the route. */
    private fun loopFramesFor(p: PlaybackPlan, range: LoopRange): Pair<Long, Long>? {
        // Route indices go stale when bars are inserted or deleted; the bars themselves (measure ids) are the reference.
        val start = frameFor(p, range.start) ?: return null
        val endSpan = p.spans.getOrNull(range.end.routeIndex)?.takeIf { it.measureId == range.end.position.measureId }
            ?: p.spans.firstOrNull { it.measureId == range.end.position.measureId && it.endFrame > start } ?: return null
        val end = if (range.end.position.offset == Fraction.ZERO) endSpan.endFrame else kotlin.math.round(endSpan.frameAt(range.end.position.offset.toDouble())).toLong()
        return if (end > start) start to end else null
    }

    private fun applyLoopLocked(range: LoopRange?): Outcome<Unit> {
        val p = plan
        if (range == null || p == null) { loopFrames = null; host.post(HostCommand.Loop(-1, -1)); mutable.value = mutable.value.copy(loop = null); return Outcome.Success(Unit) }
        val (start, end) = loopFramesFor(p, range) ?: return Outcome.Failure(FailureCode.VALIDATION, "Loop liegt außerhalb der Route", contractId = "C-PRACTICE")
        loopFrames = start to end
        host.post(HostCommand.Loop(start, end))
        mutable.value = mutable.value.copy(loop = range)
        // A loop set (or recompiled) while the playhead is outside it engages at once instead of never (after it) or late.
        val playing = mutable.value.status == TransportStatus.PLAYING || starting
        val at = mutable.value.sample?.frame ?: host.snapshot().planFrame
        if (playing && at != null && at !in start until end) {
            host.post(HostCommand.Seek(start))
            mutable.value = mutable.value.copy(sample = SamplePosition(start, p.sampleRate), position = p.positionAt(start))
        }
        return Outcome.Success(Unit)
    }

    /** Applies a new speed while playing: recompiles and remaps the playhead to the same bar and beat. */
    suspend fun setSpeed(speed: Double): Outcome<Unit> = mutex.withLock {
        settings = settings.copy(speed = speed.coerceIn(0.1, 4.0))
        val p = project ?: return@withLock Outcome.Success(Unit)
        val rate = host.format?.sampleRate ?: return@withLock Outcome.Success(Unit)
        installLocked(p, rate, forceRack = false)
    }

    /** Updates metronome and mixer without recompiling. */
    suspend fun setMetronome(enabled: Boolean) = mutex.withLock {
        settings = settings.copy(metronome = enabled)
        project?.let { host.post(HostCommand.Mix(MixState.from(it, if (enabled) 1f else 0f))) }
    }
    fun currentSettings(): PracticeSettings = settings

    override suspend fun preview(project: Project, trackId: EntityId, note: Note): Outcome<Unit> = mutex.withLock {
        val index = project.tracks.indexOfFirst { it.id == trackId }
        if (index < 0) return@withLock Outcome.Failure(FailureCode.VALIDATION, "Spur fehlt", contractId = "C-PREVIEW")
        val wasRunning = host.isRunning
        val format = ensureHost() ?: return@withLock unavailable("C-PREVIEW", "Audioausgabe konnte nicht geöffnet werden")
        if (!wasRunning || this.project == null || rackSignature != ChannelRack.signature(project) || rackRate != format.sampleRate) {
            host.post(HostCommand.Rack(withContext(Dispatchers.Default) { ChannelRack.build(project, format.sampleRate, maxOf(1024, format.blockFrames)) }))
            rackSignature = ChannelRack.signature(project); rackRate = format.sampleRate
        }
        host.post(HostCommand.Mix(MixState.from(project, if (settings.metronome) 1f else 0f)))
        val track = project.tracks[index]
        val single = previewProject(project, track, note)
        val compiled = compiler.compileNow(single, format.sampleRate)
        val scheduled = (compiled as? Outcome.Success)?.value?.notes ?: return@withLock unavailable("C-PREVIEW", "Note lässt sich nicht vorhören")
        val length = (0.45 * format.sampleRate).roundToLong()
        scheduled.forEach { host.post(HostCommand.Preview(it.copy(trackIndex = index, frame = 0, endFrame = if (track.instrument.kind == InstrumentKind.PERCUSSION) it.endFrame else length))) }
        Outcome.Success(Unit)
    }

    /** One-bar project holding just [note] on [track], so preview uses the same articulation rules as playback. */
    private fun previewProject(project: Project, track: Track, note: Note): Project {
        val measureId = EntityId("preview")
        val beat = Beat(EntityId("preview-beat"), Fraction.ZERO, WrittenDuration(4), listOf(note))
        val voices = List(4) { v -> Voice(EntityId("preview-v$v"), v, if (v == 0) listOf(beat) else emptyList()) }
        val staff = Staff(EntityId("preview-staff"), bars = listOf(Bar(EntityId("preview-bar"), measureId, voices)))
        return Project(id = project.id, measures = listOf(Measure(measureId)), tracks = listOf(track.copy(staves = listOf(staff))),
            tempo = listOf(TempoChange(ScorePosition(measureId), 120.0)))
    }

    override suspend fun render(project: Project, options: RenderOptions, progress: (OperationProgress) -> Unit): Outcome<List<ExportResult>> = withContext(Dispatchers.Default) {
        val name = project.metadata.title.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(60).ifBlank { "mix" }
        val targets = listOf<Int?>(null) + if (options.splitTracks) project.tracks.indices.toList() else emptyList()
        val results = ArrayList<ExportResult>()
        for (solo in targets) {
            ensureActive()
            val out = ByteArrayOutputStream()
            when (val r = OfflineMixdown(compiler, bankProvider()).render(project, options, { b, o, l -> out.write(b, o, l) }, solo, { !isActive }, progress)) {
                is Outcome.Failure -> return@withContext r
                is Outcome.Success -> results += ExportResult(out.toByteArray(), "audio/wav", if (solo == null) "$name.wav" else "${name}_${(solo + 1).toString().padStart(2, '0')}_${project.tracks[solo].name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")}.wav", emptyList())
            }
        }
        Outcome.Success(results)
    }

    override suspend fun renderTo(project: Project, options: RenderOptions, sink: ByteSink, soloTrack: Int?, progress: (OperationProgress) -> Unit): Outcome<RenderSummary> {
        val clips = if (soloTrack == null) mutex.withLock { clipsFor(project, options.sampleRate).let { ClipSet.build(project, it.voices.associate { v -> project.audio.clips.first { c -> c.id == v.clipId }.asset to v.pcm }, options.sampleRate, 1.0) } } else ClipSet.EMPTY
        return withContext(Dispatchers.Default) { OfflineMixdown(compiler, bankProvider()).render(project, options, sink, soloTrack, { !isActive }, progress, clips) }
    }

    /** Live measurements of the running output (null before the device was opened). */
    fun stats() = host.stats()

    fun shutdown() { poller?.cancel(); poller = null; host.stop(); publish(TransportStatus.STOPPED, null) }

    /**
     * One regular bar of the starting bar's meter at the tempo where playback starts: a pickup, a fermata or a tempo
     * change later in the first bar must not shorten or stretch the count-in.
     */
    private fun countIn(plan: PlaybackPlan, from: Long): Pair<Long, LongArray> {
        val bars = settings.countInBars.coerceIn(0, 4)
        if (bars == 0) return 0L to LongArray(0)
        val span = plan.spanAt(from) ?: return 0L to LongArray(0)
        val p = project ?: return 0L to LongArray(0)
        val signature = p.measures.getOrNull(span.measureIndex)?.signature ?: return 0L to LongArray(0)
        val o = Fraction.of(kotlin.math.round(span.offsetAt(from.toDouble()) * 3840).toLong(), 3840)
        // Tempo in effect at the start point (written order), independent of fermatas or swing in that bar.
        val index = p.measures.withIndex().associate { it.value.id to it.index }
        val qpm = p.tempo.filter { t -> val i = index[t.position.measureId] ?: Int.MAX_VALUE; i < span.measureIndex || (i == span.measureIndex && t.position.offset <= o) }
            .maxWithOrNull(compareBy({ index[it.position.measureId] ?: 0 }, { it.position.offset }))?.quarterNotesPerMinute ?: p.tempo.firstOrNull()?.quarterNotesPerMinute ?: 120.0
        val framesPerWhole = 4.0 * 60.0 / qpm * plan.sampleRate / plan.speed
        if (!framesPerWhole.isFinite() || framesPerWhole <= 0.0) return 0L to LongArray(0)
        val beat = dev.riffworkbench.playback.metronomeBeat(signature).toDouble() * framesPerWhole
        val barFrames = kotlin.math.round(signature.length().toDouble() * framesPerWhole).toLong()
        val perBar = kotlin.math.round(signature.length().toDouble() / dev.riffworkbench.playback.metronomeBeat(signature).toDouble()).toInt().coerceIn(1, 64)
        val offsets = (0 until bars).flatMap { bar -> (0 until perBar).map { k -> bar * barFrames + kotlin.math.round(k * beat).toLong() } }
        return bars * barFrames to offsets.toLongArray()
    }

    private fun startPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                delay(pollMillis)
                val snap = host.snapshot()
                val p = plan
                val held = halted
                // (An `ended` flag left over from the previous run is not an answer; give up after 1 s if the thread never starts.)
                if (starting && (snap.plays >= postedPlays || !snap.running || System.nanoTime() - startingSince > 1_000_000_000L)) starting = false
                val status = when {
                    held != null -> held
                    // Until the first block of the new playback the snapshot is stale: keep PLAYING and the seek position.
                    starting -> TransportStatus.PLAYING
                    !snap.running && mutable.value.status == TransportStatus.PLAYING -> TransportStatus.STOPPED
                    snap.countingIn -> TransportStatus.PLAYING
                    snap.playing -> TransportStatus.PLAYING
                    snap.ended && mutable.value.status == TransportStatus.PLAYING -> TransportStatus.STOPPED
                    mutable.value.status == TransportStatus.PLAYING -> TransportStatus.PAUSED
                    else -> mutable.value.status
                }
                // A snapshot taken before the engine applied the newest program belongs to the old plan: skip it.
                if (held == null && !starting && (snap.programs >= postedPrograms || !snap.running) && (status == TransportStatus.PLAYING || snap.countingIn)) {
                    // The snapshot is taken at the block start plus its length; a wrap or end inside the block is folded back.
                    val frame = loopFrames?.let { (a, b) -> if (snap.planFrame >= b && b > a) a + (snap.planFrame - b) % (b - a) else snap.planFrame }
                        ?: p?.let { minOf(snap.planFrame, it.totalFrames) } ?: snap.planFrame
                    mutable.value = mutable.value.copy(status = status, position = p?.positionAt(frame), sample = SamplePosition(frame, p?.sampleRate ?: 48_000),
                        underruns = snap.underruns, countingIn = snap.countingIn, speed = settings.speed, latencyMs = host.format?.let { it.latencyFrames * 1000.0 / it.sampleRate })
                } else if (status != mutable.value.status) mutable.value = mutable.value.copy(status = status, countingIn = false)
                if (snap.loopsCompleted != lastLoops) { val passes = snap.loopsCompleted - lastLoops; lastLoops = snap.loopsCompleted; onLoopPasses(passes) }
                if (!snap.running && status != TransportStatus.PLAYING) { poller = null; break }
            }
        }
    }

    private suspend fun onLoopPasses(passes: Long) {
        val trainer = settings.trainer
        if (!trainer.enabled) return
        trainerPasses += passes.toInt()
        if (trainerPasses >= trainer.repetitions.coerceAtLeast(1) && settings.speed < trainer.endSpeed - 1e-9) {
            trainerPasses = 0
            setSpeed(minOf(trainer.endSpeed, settings.speed + trainer.increment.coerceAtLeast(0.01)))
        }
    }

    private fun publish(status: TransportStatus, reason: String?) { mutable.value = mutable.value.copy(status = status, reason = reason) }
}
