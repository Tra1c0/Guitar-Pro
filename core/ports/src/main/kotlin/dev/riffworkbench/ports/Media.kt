package dev.riffworkbench.ports

import dev.riffworkbench.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** C-NOTATION: revision-tagged derived model; stale results must never replace a newer revision. */
interface NotationRenderer { suspend fun render(project: Project, widthPx: Int): Outcome<RenderedScore> }
interface HitTestProvider { fun hitTest(score: RenderedScore, x: Float, y: Float): EntityId? }

/** C-FORMAT: sniff bytes, not extension. GP7 export and GP8 fidelity are distinct capabilities. */
interface FormatDetector { fun detect(prefix: ByteArray): Outcome<FormatInfo> }
interface ScoreImporter {
    val formatId: String
    suspend fun import(bytes: ByteArray, ids: IdSource, progress: (OperationProgress) -> Unit = {}): Outcome<ImportResult>
}
interface ScoreExporter {
    val formatId: String
    suspend fun export(request: ExportRequest, progress: (OperationProgress) -> Unit = {}): Outcome<ExportResult>
}

/** C-FORMAT catalog: content sniffing plus the importers and exporters that are actually implemented. */
interface FormatCatalog {
    val formats: List<FormatInfo>
    /** Detects by content first; [fileName] only breaks ties between families with identical containers. */
    fun detect(bytes: ByteArray, fileName: String?): Outcome<FormatInfo>
    fun importer(formatId: String): ScoreImporter?
    fun exporter(formatId: String): ScoreExporter?
}

/** C-REPEAT: finite route with occurrence identity; malformed jumps fail with diagnostics. */
interface RepeatCompiler { fun compile(project: Project): Outcome<List<PlaybackOccurrence>> }
/** C-SEQUENCER: exact rhythm -> sample events; each note owns its pitch curve. Off the real-time thread. */
interface PlaybackCompiler { suspend fun compile(project: Project, sampleRate: Int, options: PlaybackOptions = PlaybackOptions()): Outcome<PlaybackPlan> }
interface TempoMapper { fun toMicros(position: PlaybackOccurrence, project: Project): Outcome<Long> }
interface AudioSyncMapper { fun toAudioMicros(position: PlaybackOccurrence, map: SyncMap): Outcome<Long> }

/** C-AUDIO: native sample clock is authoritative; UI never advances the transport. No work in callbacks. */
interface Transport {
    val state: StateFlow<TransportState>
    suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit>
    suspend fun play(): Outcome<Unit>
    /**
     * Number of pause()/stop() calls so far. A caller that prepares before playing reads it when the user asked to play
     * and passes it to [play]: a halt in between wins, a halt before does not.
     */
    val halts: Long get() = 0
    /** Plays unless a pause()/stop() happened after [since] (a value of [halts]). */
    suspend fun play(since: Long): Outcome<Unit> = play()
    suspend fun pause(): Outcome<Unit>
    suspend fun seek(position: PlaybackOccurrence): Outcome<Unit>
    suspend fun setLoop(range: LoopRange?): Outcome<Unit>
    suspend fun stop(): Outcome<Unit>
    /**
     * Zyklus 25 (Rhythmus tippen): plan frame of [installedPlan] that was audible at [nanos] (System.nanoTime base, an
     * instant in the past such as a touch-down); before the start frame during a count-in; NaN when unknown (no output).
     */
    fun audiblePlanFrameAt(nanos: Long): Double = Double.NaN
    /** The plan playback runs on (its frames are those of [audiblePlanFrameAt]); null before playback compiled one. */
    fun installedPlan(): PlaybackPlan? = null
}
interface AudioEngine {
    suspend fun devices(): Outcome<List<AudioDevice>>
    suspend fun selectOutput(id: String): Outcome<Unit>
    suspend fun install(plan: PlaybackPlan): Outcome<Unit>
    suspend fun close(): Outcome<Unit>
}
/** C-PREVIEW: immediate audition of one note through the track's sound; never mutates the project. */
interface PreviewPlayer { suspend fun preview(project: Project, trackId: EntityId, note: Note): Outcome<Unit> }
/** C-RENDER: same compiler and mix graph as playback. Stems share start frame and length. */
interface OfflineRenderer {
    suspend fun render(project: Project, options: RenderOptions, progress: (OperationProgress) -> Unit = {}): Outcome<List<ExportResult>>
    /** Streams a WAV (mix when [soloTrack] is null, otherwise that isolated track) into [sink]. */
    suspend fun renderTo(project: Project, options: RenderOptions, sink: ByteSink, soloTrack: Int? = null, progress: (OperationProgress) -> Unit = {}): Outcome<RenderSummary>
}
interface WaveformCache { suspend fun peaks(asset: AssetHash, samplesPerPeak: Int): Outcome<WaveformLevel> }
interface TimeStretchProcessor { suspend fun process(asset: AssetHash, speed: Double, pitchCents: Int): Outcome<AssetHash> }
interface SoundBankRepository {
    suspend fun list(): Outcome<List<SoundBank>>
    suspend fun import(asset: AssetDescriptor): Outcome<SoundBank>
}

/** C-AUDIO device boundary; Android implements it with AudioTrack. [write] blocks and paces the render thread. */
interface AudioSink {
    fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat>
    /** Writes interleaved stereo float frames; returns frames accepted (fewer only when closing). */
    fun write(buffer: FloatArray, frames: Int): Int
    /** Sink frame currently leaving the speaker, extrapolated to now; null when the device gives no timestamp. */
    fun presentedFrame(): Long?
    /**
     * [presentedFrame] for the render thread: -1 when the device gives no timestamp. The nullable Long boxes on every
     * call; a real-time sink overrides this without boxing (QA cycle 20, no allocation on the audio thread).
     */
    fun presentedFrameOrNegative(): Long = presentedFrame() ?: -1L
    fun underruns(): Long = 0
    fun close()
}
data class SinkFormat(val sampleRate: Int, val blockFrames: Int, val latencyFrames: Int, val deviceName: String)

/** C-TUNER input boundary; Android implements it with AudioRecord. */
interface AudioInputSource {
    /** Returns the actual sample rate. */
    fun open(preferredSampleRate: Int): Outcome<Int>
    /** Blocking read of mono float samples; returns the count read, or a negative value when closed. */
    fun read(buffer: FloatArray, frames: Int): Int
    fun close()
}

/** Format facts of an audio file, read without decoding the whole stream. */
data class AudioInfo(val mediaType: String, val sampleRate: Int, val channels: Int, val durationMicros: Long)

/** Receives decoded audio as interleaved 16-bit PCM; returning false stops decoding (limit reached or cancelled). */
fun interface PcmSink { fun accept(sampleRate: Int, channels: Int, data: ShortArray, frames: Int): Boolean }

/**
 * C-DECODE reference-audio decoding. [probe] validates untrusted bytes and reports the format; [decode] streams PCM
 * chunks to [PcmSink] so no full-length intermediate copy is needed. Android implements compressed formats with
 * MediaCodec; WAV is decoded in Kotlin.
 */
interface AudioDecoder {
    suspend fun probe(bytes: ByteArray): Outcome<AudioInfo>
    suspend fun decode(bytes: ByteArray, sink: PcmSink): Outcome<AudioInfo>
}

/** Live audio measurements (see HostStats in adapter:audio); render times are DSP work per callback block. */
data class AudioStats(
    val sampleRate: Int, val blockFrames: Int, val blocks: Long, val budgetMicros: Double,
    val renderMedianMicros: Double, val renderP99Micros: Double, val renderMaxMicros: Double, val overruns: Long,
    val underruns: Long, val outputLatencyMs: Double?, val noteOnLatencyMs: Double?, val voices: Int, val hasSamples: Boolean, val deviceName: String,
    /** The sampled drum kit plays (false: synthetic kit, not loaded or missing). */
    val hasDrumSamples: Boolean = false,
)
/** One second of a stress run. */
data class StressSample(val second: Int, val stats: AudioStats?, val memoryMb: Double?, val thermal: String?, val cpuMhz: List<Int>)
data class StressReport(val seconds: Int, val samples: List<StressSample>, val text: String)

/**
 * C-AUDIO diagnostics: measurements for the device acceptance plan (latency, DSP load, underruns, memory, thermals)
 * and loudness-matched renders for blind listening comparisons. Implemented in the composition root.
 */
interface AudioLab {
    fun stats(): AudioStats?
    /** Plays an own 8-track stress arrangement in a loop for [seconds] and samples stats, memory and thermals each second. */
    suspend fun stressTest(seconds: Int, onSample: (StressSample) -> Unit): Outcome<StressReport>
    /** Renders the own comparison riff with [toneId] (clean/crunch/highgain) as stereo float PCM at 48 kHz, RMS-normalised. */
    suspend fun renderComparison(toneId: String, sampled: Boolean): Outcome<FloatArray>
    fun playPcm(stereo: FloatArray, sampleRate: Int)
    fun stopPcm()
}

/** Device probes for diagnostics (Android: ActivityManager/Debug, PowerManager thermal status, cpufreq sysfs). */
interface SystemProbes {
    fun memoryMb(): Double?
    fun thermal(): String?
    fun cpuMhz(): List<Int>
}
/** Plays a short in-memory stereo buffer outside the engine (blind comparisons). */
interface PcmOutput {
    fun play(stereo: FloatArray, sampleRate: Int)
    fun stop()
}

data class MidiCapture(val deviceId: String, val timestampNanos: Long, val data: List<Int>)
interface MidiInput {
    val events: Flow<MidiCapture>
    suspend fun connect(deviceId: String): Outcome<Unit>
    suspend fun disconnect(): Outcome<Unit>
}
interface LiveInput {
    suspend fun configure(routing: LiveRouting): Outcome<Unit>
    suspend fun start(): Outcome<Unit>
    suspend fun stop(): Outcome<Unit>
}
interface ExternalIntegration {
    val id: String
    suspend fun devices(): Outcome<List<ExternalDevice>>
    suspend fun connect(deviceId: String): Outcome<Unit>
}

fun unavailable(contractId: String, message: String = "Diese Funktion wird in einer späteren Etappe implementiert."): Outcome.Failure =
    Outcome.Failure(FailureCode.UNAVAILABLE, message, contractId = contractId)
