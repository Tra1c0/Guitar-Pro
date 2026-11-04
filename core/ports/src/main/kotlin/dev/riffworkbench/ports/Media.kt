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

/** C-REPEAT: finite route with occurrence identity; malformed jumps fail with diagnostics. */
interface RepeatCompiler { fun compile(project: Project): Outcome<List<PlaybackOccurrence>> }
/** C-SEQUENCER: exact rhythm -> sample events; each note owns its pitch curve. Off the real-time thread. */
interface PlaybackCompiler { suspend fun compile(project: Project, sampleRate: Int): Outcome<PlaybackPlan> }
interface TempoMapper { fun toMicros(position: PlaybackOccurrence, project: Project): Outcome<Long> }
interface AudioSyncMapper { fun toAudioMicros(position: PlaybackOccurrence, map: SyncMap): Outcome<Long> }

/** C-AUDIO: native sample clock is authoritative; UI never advances the transport. No work in callbacks. */
interface Transport {
    val state: StateFlow<TransportState>
    suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit>
    suspend fun play(): Outcome<Unit>
    suspend fun pause(): Outcome<Unit>
    suspend fun seek(position: PlaybackOccurrence): Outcome<Unit>
    suspend fun setLoop(range: LoopRange?): Outcome<Unit>
    suspend fun stop(): Outcome<Unit>
}
interface AudioEngine {
    suspend fun devices(): Outcome<List<AudioDevice>>
    suspend fun selectOutput(id: String): Outcome<Unit>
    suspend fun install(plan: PlaybackPlan): Outcome<Unit>
    suspend fun close(): Outcome<Unit>
}
interface PreviewPlayer { suspend fun preview(note: Note, instrument: InstrumentDefinition): Outcome<Unit> }
interface OfflineRenderer {
    suspend fun render(project: Project, options: RenderOptions, progress: (OperationProgress) -> Unit = {}): Outcome<List<ExportResult>>
}
interface AudioDecoder { suspend fun decode(asset: AssetHash, targetSampleRate: Int, progress: (OperationProgress) -> Unit = {}): Outcome<AssetHash> }
interface WaveformCache { suspend fun peaks(asset: AssetHash, samplesPerPeak: Int): Outcome<WaveformLevel> }
interface TimeStretchProcessor { suspend fun process(asset: AssetHash, speed: Double, pitchCents: Int): Outcome<AssetHash> }
interface SoundBankRepository {
    suspend fun list(): Outcome<List<SoundBank>>
    suspend fun import(asset: AssetDescriptor): Outcome<SoundBank>
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
