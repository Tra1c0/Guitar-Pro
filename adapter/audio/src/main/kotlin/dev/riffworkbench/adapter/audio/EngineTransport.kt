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

    /**
     * Stores [project] for playback. While the output is running the new plan is compiled and swapped in at the
     * same musical position; otherwise compilation waits for the next play/preview so edits never open the device.
     */
    override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> = mutex.withLock {
        this.project = project
        this.settings = settings
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
        if (previous == null || installedRate != rate) host.post(HostCommand.Install(program)) else host.post(HostCommand.Reprogram(program, previous))
        installedRate = rate
        plan = next
        applyLoopLocked(settings.loop)
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
        if (clipCache.size > 8) clipCache.keys.filter { k -> project.audio.clips.none { it.asset == k.first } }.forEach { clipCache.remove(it) }
        return ClipSet.build(project, buffers, rate, settings.speed)
    }

    override suspend fun play(): Outcome<Unit> = mutex.withLock {
        val p = project ?: return@withLock Outcome.Failure(FailureCode.VALIDATION, "Kein Projekt vorbereitet", contractId = "C-AUDIO")
        val wasRunning = host.isRunning
        val format = ensureHost() ?: return@withLock unavailable("C-AUDIO", "Audioausgabe konnte nicht geöffnet werden")
        if (!wasRunning || plan == null || installedRate != format.sampleRate) installLocked(p, format.sampleRate, forceRack = true).let { if (it is Outcome.Failure) return@withLock it }
        val current = plan ?: return@withLock unavailable("C-SEQUENCER")
        val snap = host.snapshot()
        var from = pendingSeek?.let { frameFor(current, it) } ?: state.value.sample?.frame ?: snap.planFrame
        pendingSeek = null
        if (snap.ended || from >= current.totalFrames) from = loopFrames?.first ?: 0L
        loopFrames?.let { (start, end) -> if (from !in start until end) from = start }
        val (countIn, clicks) = countIn(current, from)
        host.post(HostCommand.Play(from, countIn, clicks))
        trainerPasses = 0; lastLoops = snap.loopsCompleted
        publish(TransportStatus.PLAYING, null)
        Outcome.Success(Unit)
    }

    override suspend fun pause(): Outcome<Unit> { host.post(HostCommand.Pause); publish(TransportStatus.PAUSED, null); return Outcome.Success(Unit) }

    override suspend fun stop(): Outcome<Unit> {
        host.post(HostCommand.Pause)
        host.post(HostCommand.Seek(loopFrames?.first ?: 0L))
        mutable.value = mutable.value.copy(status = TransportStatus.STOPPED, position = plan?.positionAt(loopFrames?.first ?: 0L), sample = SamplePosition(loopFrames?.first ?: 0L, plan?.sampleRate ?: 48_000))
        return Outcome.Success(Unit)
    }

    private fun frameFor(p: PlaybackPlan, position: PlaybackOccurrence): Long? = p.frameOf(position.routeIndex, position.position.offset)
        ?.takeIf { p.spans.getOrNull(position.routeIndex)?.measureId == position.position.measureId }
        ?: p.spans.firstOrNull { it.measureId == position.position.measureId }?.let { kotlin.math.round(it.frameAt(position.position.offset.toDouble())).toLong() }

    override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> = mutex.withLock {
        val p = plan ?: run { pendingSeek = position; mutable.value = mutable.value.copy(position = position, sample = null); return@withLock Outcome.Success(Unit) }
        val frame = frameFor(p, position) ?: return@withLock Outcome.Failure(FailureCode.VALIDATION, "Position liegt außerhalb der Route", contractId = "C-AUDIO")
        host.post(HostCommand.Seek(frame))
        mutable.value = mutable.value.copy(position = p.positionAt(frame), sample = SamplePosition(frame, p.sampleRate))
        Outcome.Success(Unit)
    }

    override suspend fun setLoop(range: LoopRange?): Outcome<Unit> = mutex.withLock {
        settings = settings.copy(loop = range)
        applyLoopLocked(range)
    }

    private fun applyLoopLocked(range: LoopRange?): Outcome<Unit> {
        val p = plan
        if (range == null || p == null) { loopFrames = null; host.post(HostCommand.Loop(-1, -1)); mutable.value = mutable.value.copy(loop = null); return Outcome.Success(Unit) }
        val start = p.frameOf(range.start.routeIndex, range.start.position.offset)
        val endSpan = p.spans.getOrNull(range.end.routeIndex)
        if (start == null || endSpan == null || endSpan.measureId != range.end.position.measureId) return Outcome.Failure(FailureCode.VALIDATION, "Loop liegt außerhalb der Route", contractId = "C-PRACTICE")
        val end = if (range.end.position.offset == Fraction.ZERO) endSpan.endFrame else kotlin.math.round(endSpan.frameAt(range.end.position.offset.toDouble())).toLong()
        if (end <= start) return Outcome.Failure(FailureCode.VALIDATION, "Loop-Ende liegt vor dem Anfang", contractId = "C-PRACTICE")
        loopFrames = start to end
        host.post(HostCommand.Loop(start, end))
        mutable.value = mutable.value.copy(loop = range)
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
            when (val r = OfflineMixdown(compiler).render(project, options, { b, o, l -> out.write(b, o, l) }, solo, { !isActive }, progress)) {
                is Outcome.Failure -> return@withContext r
                is Outcome.Success -> results += ExportResult(out.toByteArray(), "audio/wav", if (solo == null) "$name.wav" else "${name}_${(solo + 1).toString().padStart(2, '0')}_${project.tracks[solo].name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")}.wav", emptyList())
            }
        }
        Outcome.Success(results)
    }

    override suspend fun renderTo(project: Project, options: RenderOptions, sink: ByteSink, soloTrack: Int?, progress: (OperationProgress) -> Unit): Outcome<RenderSummary> {
        val clips = if (soloTrack == null) mutex.withLock { clipsFor(project, options.sampleRate).let { ClipSet.build(project, it.voices.associate { v -> project.audio.clips.first { c -> c.id == v.clipId }.asset to v.pcm }, options.sampleRate, 1.0) } } else ClipSet.EMPTY
        return withContext(Dispatchers.Default) { OfflineMixdown(compiler).render(project, options, sink, soloTrack, { !isActive }, progress, clips) }
    }

    fun shutdown() { poller?.cancel(); poller = null; host.stop(); publish(TransportStatus.STOPPED, null) }

    private fun countIn(plan: PlaybackPlan, from: Long): Pair<Long, LongArray> {
        val bars = settings.countInBars.coerceIn(0, 4)
        if (bars == 0) return 0L to LongArray(0)
        val span = plan.spanAt(from) ?: return 0L to LongArray(0)
        val clicks = plan.clicks.filter { it.frame >= span.startFrame && it.frame < span.endFrame }
        val barFrames = span.endFrame - span.startFrame
        if (clicks.isEmpty() || barFrames <= 0) return 0L to LongArray(0)
        val offsets = (0 until bars).flatMap { b -> clicks.map { b * barFrames + (it.frame - span.startFrame) } }
        return bars * barFrames to offsets.toLongArray()
    }

    private fun startPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                delay(pollMillis)
                val snap = host.snapshot()
                val p = plan
                val status = when {
                    !snap.running && mutable.value.status == TransportStatus.PLAYING -> TransportStatus.STOPPED
                    snap.countingIn -> TransportStatus.PLAYING
                    snap.playing -> TransportStatus.PLAYING
                    snap.ended && mutable.value.status == TransportStatus.PLAYING -> TransportStatus.STOPPED
                    mutable.value.status == TransportStatus.PLAYING -> TransportStatus.PAUSED
                    else -> mutable.value.status
                }
                if (status == TransportStatus.PLAYING || snap.countingIn) {
                    mutable.value = mutable.value.copy(status = status, position = p?.positionAt(snap.planFrame), sample = SamplePosition(snap.planFrame, p?.sampleRate ?: 48_000),
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
