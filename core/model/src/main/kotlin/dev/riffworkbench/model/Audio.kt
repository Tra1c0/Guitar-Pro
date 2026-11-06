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

/**
 * Resolved performance of one note occurrence. Frames are absolute plan frames (speed already applied).
 * Each note owns its pitch curve; nothing here is a channel-wide controller, so a bend on one string
 * cannot drag a held note on another string.
 */
data class ScheduledNote(
    val noteId: EntityId,
    val trackId: EntityId,
    val frame: Long,
    val endFrame: Long,
    val pitch: Pitch,
    val velocity: Int,
    val string: Int?,
    val articulations: List<Articulation>,
    val trackIndex: Int = 0,
    val routeIndex: Int = 0,
    /** Sounding pitch in fractional MIDI units: written pitch, harmonic shift and fine tuning. */
    val soundingPitch: Double = pitch.midiNumber + pitch.cents / 100.0,
    /** Pitch offsets in cents relative to [soundingPitch]; frames relative to [frame], ascending. */
    val curve: List<CurvePoint> = emptyList(),
    val vibrato: VibratoSpec? = null,
    val attack: AttackKind = AttackKind.PICK,
    val palmMute: Boolean = false,
    val dead: Boolean = false,
    val harmonic: HarmonicKind? = null,
    val letRing: Boolean = false,
    val drumStroke: DrumStroke = DrumStroke.NORMAL,
    val chokeGroup: Int? = null,
    /** Element gain/pan for percussion kits; 1.0/0.0 for pitched instruments. */
    val elementGain: Double = 1.0,
    val elementPan: Double = 0.0,
)
data class CurvePoint(val frame: Long, val cents: Double)
data class VibratoSpec(val depthCents: Double, val rateHz: Double, val startFrame: Long)
enum class AttackKind { PICK, LEGATO, TAP, SLAP, POP, GRACE }
enum class DrumStroke { NORMAL, RIMSHOT, CROSS_STICK, CHOKE }
data class MetronomeClick(val frame: Long, val level: Int)

/** One played measure of the unfolded route with its local time map (written offset -> plan frame). */
class RouteSpan(
    val routeIndex: Int,
    val measureId: EntityId,
    val measureIndex: Int,
    val occurrence: Int,
    val length: Fraction,
    /** Written offsets in whole notes, ascending, first 0 and last length. */
    val offsets: DoubleArray,
    /** Plan frames (fractional) for each entry in [offsets]. */
    val frames: DoubleArray,
) {
    init { require(offsets.size == frames.size && offsets.size >= 2) }
    val startFrame: Long get() = kotlin.math.round(frames.first()).toLong()
    val endFrame: Long get() = kotlin.math.round(frames.last()).toLong()
    fun frameAt(offset: Double): Double {
        if (offset <= offsets[0]) return frames[0]
        for (i in 1 until offsets.size) if (offset <= offsets[i]) {
            val span = offsets[i] - offsets[i - 1]
            return if (span <= 0.0) frames[i] else frames[i - 1] + (offset - offsets[i - 1]) / span * (frames[i] - frames[i - 1])
        }
        return frames.last()
    }
    fun offsetAt(frame: Double): Double {
        if (frame <= frames[0]) return offsets[0]
        for (i in 1 until frames.size) if (frame <= frames[i]) {
            val span = frames[i] - frames[i - 1]
            return if (span <= 0.0) offsets[i] else offsets[i - 1] + (frame - frames[i - 1]) / span * (offsets[i] - offsets[i - 1])
        }
        return offsets.last()
    }
}

data class PlaybackOptions(val speed: Double = 1.0, val metronome: Boolean = false, val includeMutedTracks: Boolean = true) {
    init { require(speed.isFinite() && speed in 0.1..4.0) { "Geschwindigkeit außerhalb von 10–400 %" } }
}

data class PlaybackPlan(
    val projectRevision: Long,
    val sampleRate: Int,
    val route: List<PlaybackOccurrence>,
    /** Sorted by [ScheduledNote.frame]. */
    val notes: List<ScheduledNote>,
    val spans: List<RouteSpan> = emptyList(),
    val clicks: List<MetronomeClick> = emptyList(),
    val speed: Double = 1.0,
    val trackIds: List<EntityId> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    /** End of the written route (without release tails). */
    val totalFrames: Long get() = spans.lastOrNull()?.endFrame ?: (notes.maxOfOrNull { it.endFrame } ?: 0L)
    fun spanAt(frame: Long): RouteSpan? {
        if (spans.isEmpty()) return null
        var low = 0; var high = spans.lastIndex
        while (low < high) { val mid = (low + high + 1) / 2; if (spans[mid].startFrame <= frame) low = mid else high = mid - 1 }
        return spans[low]
    }
    /** Playhead position for a plan frame; offset is quantized to 1/3840 of a whole note for display. */
    fun positionAt(frame: Long): PlaybackOccurrence? {
        val span = spanAt(frame) ?: return null
        val offset = span.offsetAt(frame.toDouble()).coerceIn(0.0, span.length.toDouble())
        val ticks = kotlin.math.floor(offset * 3840).toLong().coerceAtLeast(0)
        return PlaybackOccurrence(ScorePosition(span.measureId, Fraction.of(ticks, 3840)), span.occurrence, span.routeIndex)
    }
    fun frameOf(routeIndex: Int, offset: Fraction = Fraction.ZERO): Long? =
        spans.getOrNull(routeIndex)?.let { kotlin.math.round(it.frameAt(offset.toDouble())).toLong() }

    /**
     * Frame of [position] in this plan, by bar rather than by route index (indices go stale when bars are inserted or
     * deleted): the same route index if it is still that bar, else the same pass of that bar, else its first pass.
     * When the bar no longer exists, [orNearest] falls back to the start of the span now at that route index.
     */
    fun frameFor(position: PlaybackOccurrence, orNearest: Boolean = false): Long? {
        val id = position.position.measureId
        val span = spans.getOrNull(position.routeIndex)?.takeIf { it.measureId == id }
            ?: spans.firstOrNull { it.measureId == id && it.occurrence == position.occurrence }
            ?: spans.firstOrNull { it.measureId == id }
        if (span != null) return kotlin.math.round(span.frameAt(position.position.offset.toDouble())).toLong()
        if (!orNearest || spans.isEmpty()) return null
        return spans[position.routeIndex.coerceIn(0, spans.lastIndex)].startFrame
    }

    /**
     * [frame] of [old] expressed in this plan: the same result as `old.positionAt(frame)?.let { frameFor(it, orNearest = true) }`
     * (same 1/3840 quantisation, same bar/pass lookup), -1 where that is null. Allocates nothing - the render thread maps the
     * playhead with it when a recompiled plan arrives (QA cycle 20: the object path allocated ~100 bytes per edit/speed change).
     */
    fun frameFromPlan(old: PlaybackPlan, frame: Long): Long {
        val from = old.spanAt(frame) ?: return -1
        val offset = from.offsetAt(frame.toDouble()).coerceIn(0.0, from.length.toDouble())
        // Fraction.of(ticks, 3840).toDouble() is the correctly rounded quotient of the same rational: identical bits.
        val position = kotlin.math.floor(offset * 3840).toLong().coerceAtLeast(0) / 3840.0
        val id = from.measureId
        var span = spans.getOrNull(from.routeIndex)?.takeIf { it.measureId == id }
        if (span == null) for (i in spans.indices) { val s = spans[i]; if (s.measureId == id && s.occurrence == from.occurrence) { span = s; break } }
        if (span == null) for (i in spans.indices) { val s = spans[i]; if (s.measureId == id) { span = s; break } }
        if (span != null) return kotlin.math.round(span.frameAt(position)).toLong()
        if (spans.isEmpty()) return -1
        return spans[from.routeIndex.coerceIn(0, spans.lastIndex)].startFrame
    }
}
data class TransportState(val status: TransportStatus = TransportStatus.UNAVAILABLE, val position: PlaybackOccurrence? = null, val sample: SamplePosition? = null, val underruns: Long = 0, val reason: String? = "Audio-Engine nicht verbunden", val speed: Double = 1.0, val loop: LoopRange? = null, val countingIn: Boolean = false, val latencyMs: Double? = null)
enum class TransportStatus { UNAVAILABLE, STOPPED, PREPARING, PLAYING, PAUSED, INTERRUPTED, FAILED }
data class AudioDevice(val id: String, val name: String, val sampleRates: List<Int>, val input: Boolean, val estimatedLatencyMs: Double?)
/** Min/max peaks of a mono mix; [sampleRate] is the decoded rate, so one peak spans samplesPerPeak / sampleRate seconds. */
data class WaveformLevel(val samplesPerPeak: Int, val minimum: List<Float>, val maximum: List<Float>, val sampleRate: Int = 0, val frames: Long = 0) {
    val secondsPerPeak: Double get() = if (sampleRate > 0) samplesPerPeak.toDouble() / sampleRate else 0.0
    /** Exact length when the frame count is known (the last peak usually covers a partial bucket). */
    val durationSeconds: Double get() = if (frames > 0 && sampleRate > 0) frames.toDouble() / sampleRate else minimum.size * secondsPerPeak
}
data class RenderOptions(val sampleRate: Int = 48000, val bitDepth: Int = 24, val includeMasterFx: Boolean = true, val tailSeconds: Double = 2.0, val splitTracks: Boolean = true, val commonStartFrame: Long = 0)
