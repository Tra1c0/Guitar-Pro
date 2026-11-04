package dev.riffworkbench.model

import kotlinx.serialization.Serializable

@Serializable @JvmInline value class AssetHash(val value: String) {
    init { require(value.matches(Regex("[a-f0-9]{64}"))) { "Expected SHA-256" } }
}
@Serializable data class AssetDescriptor(val hash: AssetHash, val name: String, val mediaType: String, val sizeBytes: Long, val rights: AssetRights = AssetRights())
@Serializable data class AssetRights(val source: String = "user-provided", val license: String = "unverified", val redistributionAllowed: Boolean = false)
@Serializable data class AudioArrangement(val assets: List<AssetDescriptor> = emptyList(), val clips: List<AudioClip> = emptyList(), val syncMaps: List<SyncMap> = emptyList())
@Serializable data class AudioClip(val id: EntityId, val asset: AssetHash, val name: String, val offsetMicros: Long = 0, val syncMapId: EntityId? = null, val gain: Double = 1.0, val pan: Double = 0.0, val mute: Boolean = false, val solo: Boolean = false, val pitchCents: Int = 0, val filters: List<EffectUnit> = emptyList())
@Serializable data class SyncMap(val id: EntityId, val mode: SyncMode, val anchors: List<SyncAnchor> = emptyList())
enum class SyncMode { SCORE_TEMPO, AUDIO_REFERENCE }
@Serializable data class SyncAnchor(val id: EntityId, val occurrence: PlaybackOccurrence, val audioMicros: Long)
@Serializable data class SamplePosition(val frame: Long, val sampleRate: Int)

@Serializable data class SoundAssignment(val bankId: String? = null, val presetId: String? = null, val originalRseData: AssetHash? = null, val mappingDescription: String = "Unmapped")
@Serializable data class ChannelMix(val volume: Double = 0.8, val pan: Double = 0.0, val mute: Boolean = false, val solo: Boolean = false, val effects: List<EffectUnit> = emptyList())
@Serializable data class MixGraph(val master: ChannelMix = ChannelMix(), val buses: List<MixBus> = emptyList(), val automation: List<AutomationLane> = emptyList())
@Serializable data class MixBus(val id: EntityId, val name: String, val inputTrackIds: List<EntityId>, val mix: ChannelMix)
@Serializable data class EffectUnit(val id: EntityId, val kind: EffectKind, val enabled: Boolean = true, val parameters: Map<String, Double> = emptyMap(), val asset: AssetHash? = null)
enum class EffectKind { GATE, OVERDRIVE, AMP, CABINET_IR, EQ, COMPRESSOR, DELAY, REVERB, CHORUS, FILTER }
@Serializable data class AutomationLane(val id: EntityId, val targetId: EntityId, val parameter: String, val points: List<AutomationPoint>)
@Serializable data class AutomationPoint(val position: ScorePosition, val value: Double, val interpolation: Interpolation = Interpolation.LINEAR)
enum class Interpolation { STEP, LINEAR, SMOOTH }
@Serializable data class SoundBank(val id: String, val name: String, val asset: AssetHash, val format: SoundBankFormat, val presets: List<SoundPreset> = emptyList())
enum class SoundBankFormat { SF2, SF3, ARTICULATION_SAMPLES }
@Serializable data class SoundPreset(val id: String, val name: String, val regions: List<SampleRegion> = emptyList())
@Serializable data class SampleRegion(val asset: AssetHash, val rootPitch: Int, val string: Int? = null, val lowPitch: Int, val highPitch: Int, val lowVelocity: Int = 1, val highVelocity: Int = 127, val roundRobin: Int = 0, val articulation: String = "normal", val releaseAsset: AssetHash? = null)
@Serializable data class PracticeSettings(val speed: Double = 0.7, val countInBars: Int = 1, val metronome: Boolean = true, val visualMetronome: Boolean = true, val loop: LoopRange? = null, val trainer: SpeedTrainer = SpeedTrainer(), val cursor: CursorMode = CursorMode.FOLLOW)
@Serializable data class SpeedTrainer(val enabled: Boolean = false, val startSpeed: Double = 0.7, val endSpeed: Double = 1.0, val increment: Double = 0.05, val repetitions: Int = 4)
@Serializable data class LoopRange(val start: PlaybackOccurrence, val end: PlaybackOccurrence)
enum class CursorMode { FIXED, FOLLOW, FREE }
@Serializable data class LiveRouting(val midiDeviceId: String? = null, val audioInputId: String? = null, val monitor: Boolean = false, val record: Boolean = false)

/** Each note has an independent voice: channel-wide bend is not the engine contract. */
data class ScheduledNote(val noteId: EntityId, val trackId: EntityId, val frame: Long, val endFrame: Long, val pitch: Pitch, val velocity: Int, val string: Int?, val articulations: List<Articulation>)
data class PlaybackPlan(val projectRevision: Long, val sampleRate: Int, val route: List<PlaybackOccurrence>, val notes: List<ScheduledNote>)
data class TransportState(val status: TransportStatus = TransportStatus.UNAVAILABLE, val position: PlaybackOccurrence? = null, val sample: SamplePosition? = null, val underruns: Long = 0, val reason: String? = "Native engine is not implemented in phase 1")
enum class TransportStatus { UNAVAILABLE, STOPPED, PREPARING, PLAYING, PAUSED, INTERRUPTED, FAILED }
data class AudioDevice(val id: String, val name: String, val sampleRates: List<Int>, val input: Boolean, val estimatedLatencyMs: Double?)
data class WaveformLevel(val samplesPerPeak: Int, val minimum: List<Float>, val maximum: List<Float>)
data class RenderOptions(val sampleRate: Int = 48000, val bitDepth: Int = 24, val includeMasterFx: Boolean = true, val tailSeconds: Double = 2.0, val splitTracks: Boolean = true, val commonStartFrame: Long = 0)
