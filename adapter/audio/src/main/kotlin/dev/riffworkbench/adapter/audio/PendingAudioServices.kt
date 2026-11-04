package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** CONTRACTS.md C-AUDIO..C-SOUNDBANK. No timer, fake audio, success export, or bundled unlicensed samples. */
class PendingAudioServices : Transport, AudioEngine, PreviewPlayer, OfflineRenderer, AudioDecoder, WaveformCache, TimeStretchProcessor, SoundBankRepository, LiveInput, MidiInput {
    override val state = MutableStateFlow(TransportState()).asStateFlow()
    override val events = emptyFlow<MidiCapture>()
    override suspend fun prepare(project: Project, settings: PracticeSettings) = unavailable("C-AUDIO")
    override suspend fun play() = unavailable("C-AUDIO")
    override suspend fun pause() = unavailable("C-AUDIO")
    override suspend fun seek(position: PlaybackOccurrence) = unavailable("C-AUDIO")
    override suspend fun setLoop(range: LoopRange?) = unavailable("C-AUDIO")
    override suspend fun stop() = unavailable("C-AUDIO")
    override suspend fun devices(): Outcome<List<AudioDevice>> = unavailable("C-AUDIO")
    override suspend fun selectOutput(id: String) = unavailable("C-AUDIO")
    override suspend fun install(plan: PlaybackPlan) = unavailable("C-AUDIO")
    override suspend fun close() = unavailable("C-AUDIO")
    override suspend fun preview(note: Note, instrument: InstrumentDefinition) = unavailable("C-PREVIEW")
    override suspend fun render(project: Project, options: RenderOptions, progress: (OperationProgress) -> Unit): Outcome<List<ExportResult>> = unavailable("C-RENDER")
    override suspend fun decode(asset: AssetHash, targetSampleRate: Int, progress: (OperationProgress) -> Unit): Outcome<AssetHash> = unavailable("C-DECODE")
    override suspend fun peaks(asset: AssetHash, samplesPerPeak: Int): Outcome<WaveformLevel> = unavailable("C-WAVEFORM")
    override suspend fun process(asset: AssetHash, speed: Double, pitchCents: Int): Outcome<AssetHash> = unavailable("C-STRETCH")
    override suspend fun list(): Outcome<List<SoundBank>> = unavailable("C-SOUNDBANK")
    override suspend fun import(asset: AssetDescriptor): Outcome<SoundBank> = unavailable("C-SOUNDBANK")
    override suspend fun configure(routing: LiveRouting) = unavailable("C-LIVE")
    override suspend fun start() = unavailable("C-LIVE")
    override suspend fun connect(deviceId: String) = unavailable("C-MIDI")
    override suspend fun disconnect() = unavailable("C-MIDI")
}
