package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Decoded reference audio: interleaved stereo 16-bit PCM at the engine rate (keeps memory at 4 bytes per frame). */
class PcmBuffer(val sampleRate: Int, val frames: Int, val data: ShortArray) {
    init { require(data.size >= frames * 2) }
    companion object {
        /** Resamples (linear) decoded audio to [targetRate] and packs it as stereo 16-bit. */
        fun from(decoded: Wav.Decoded, targetRate: Int): PcmBuffer = from(decoded.sampleRate, decoded.left, decoded.right, targetRate)
        /** From interleaved 16-bit PCM (what Android decoders emit). */
        fun fromShorts(rate: Int, channels: Int, data: ShortArray, targetRate: Int): PcmBuffer =
            PcmBuilder(targetRate, Int.MAX_VALUE / 2, expectedFrames = (data.size / channels.coerceAtLeast(1) * (targetRate.toDouble() / rate)).toInt() + 2)
                .also { it.append(rate, channels, data, data.size / channels.coerceAtLeast(1)) }.build()
        fun from(rate: Int, left: FloatArray, right: FloatArray, targetRate: Int): PcmBuffer {
            val ratio = rate.toDouble() / targetRate
            val frames = floor((left.size - 1) / ratio).toInt().coerceAtLeast(0) + if (left.isNotEmpty()) 1 else 0
            val out = ShortArray(frames * 2)
            for (i in 0 until frames) {
                val pos = i * ratio
                val k = floor(pos).toInt().coerceAtMost(left.lastIndex)
                val t = (pos - k).toFloat()
                val k2 = (k + 1).coerceAtMost(left.lastIndex)
                val l = left[k] + (left[k2] - left[k]) * t
                val r = right[k] + (right[k2] - right[k]) * t
                out[2 * i] = (l.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                out[2 * i + 1] = (r.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            return PcmBuffer(targetRate, frames, out)
        }
    }
}

/**
 * Streams decoded PCM (8–192 kHz, 1–8 channels; channels beyond the first two are dropped) into a [PcmBuffer] at
 * [targetRate] with linear interpolation. Only the output is held in memory (4 bytes per frame); it is preallocated
 * from [expectedFrames] when the container states a duration. Output beyond [maxFrames] is cut off and [truncated]
 * reports it.
 */
class PcmBuilder(val targetRate: Int, private val maxFrames: Int, expectedFrames: Int = 0) {
    init { require(targetRate in 8_000..192_000 && maxFrames > 0) }
    private var out = ShortArray(2 * minOf(maxFrames, maxOf(4096, expectedFrames + expectedFrames / 64 + 64)))
    private var frames = 0
    private var rate = 0
    private var ratio = 1.0
    private var inFrames = 0L
    private var prevL = 0
    private var prevR = 0
    private var next = 0L
    var truncated = false; private set
    val outputFrames: Int get() = frames

    /** Appends [count] interleaved frames; returns false once the limit is reached. */
    fun append(sampleRate: Int, channels: Int, data: ShortArray, count: Int): Boolean {
        require(channels in 1..8 && sampleRate in 8_000..192_000) { "Nicht unterstütztes Audioformat" }
        require(count >= 0 && count * channels <= data.size) { "Ungültiger PCM-Block" }
        if (rate == 0) { rate = sampleRate; ratio = sampleRate.toDouble() / targetRate }
        require(sampleRate == rate) { "Abtastrate wechselt innerhalb der Datei" }
        for (f in 0 until count) {
            val l = data[f * channels].toInt()
            val r = if (channels == 1) l else data[f * channels + 1].toInt()
            if (inFrames > 0) {
                val base = inFrames - 1
                while (true) {
                    val pos = next * ratio
                    if (pos >= inFrames) break
                    val t = pos - base
                    if (!put(prevL + (l - prevL) * t, prevR + (r - prevR) * t)) return false
                }
            }
            prevL = l; prevR = r; inFrames++
        }
        return true
    }

    private fun put(l: Double, r: Double): Boolean {
        if (frames >= maxFrames) { truncated = true; return false }
        if (2 * frames + 1 >= out.size) out = out.copyOf(minOf(2L * maxFrames, out.size + out.size / 2 + 16_384L).toInt())
        out[2 * frames] = Math_round(l).toShort()
        out[2 * frames + 1] = Math_round(r).toShort()
        frames++; next++
        return true
    }

    fun build(): PcmBuffer {
        if (inFrames > 0) while (next * ratio <= inFrames - 1 + 1e-9) if (!put(prevL.toDouble(), prevR.toDouble())) break
        // Keep a slightly oversized buffer instead of a second full-length copy; PcmBuffer only reads [frames].
        val data = if (out.size - 2 * frames > out.size / 16) out.copyOf(2 * frames) else out
        return PcmBuffer(targetRate, frames, data)
    }

    private fun Math_round(v: Double): Int = kotlin.math.round(v).toInt().coerceIn(-32768, 32767)
}

/**
 * One reference clip on the plan timeline. The clip's frame 0 sits at [startFrame] plan frames (score start plus
 * the clip offset). Because playback position, loops and seeks are plan frames, the clip stays sample-locked to
 * the score without its own clock. Playback is only exact at speed 1; other speeds need time-stretch (C-STRETCH).
 */
class ClipVoice(val clipId: EntityId, val pcm: PcmBuffer, val startFrame: Long, gain: Double, pan: Double) {
    private val angle = (pan.coerceIn(-1.0, 1.0) + 1) * PI / 4
    internal val gainL = (gain * cos(angle) * 1.414).toFloat()
    internal val gainR = (gain * sin(angle) * 1.414).toFloat()
    internal fun mix(left: FloatArray, right: FloatArray, offset: Int, count: Int, planFrame: Long, scale: Float) {
        var src = planFrame - startFrame
        val data = pcm.data
        for (k in 0 until count) {
            if (src in 0 until pcm.frames) {
                val i = (src * 2).toInt()
                left[offset + k] += data[i] * (gainL * scale / 32768f)
                right[offset + k] += data[i + 1] * (gainR * scale / 32768f)
            }
            src++
        }
    }
}

/** Immutable set of clips for one plan; muted and non-soloed clips are already filtered out. */
class ClipSet(val voices: Array<ClipVoice>, val exactSpeed: Boolean) {
    companion object {
        val EMPTY = ClipSet(emptyArray(), true)
        /** Builds the audible clips. [buffers] maps asset hashes to decoded PCM at the engine rate. */
        fun build(project: Project, buffers: Map<AssetHash, PcmBuffer>, sampleRate: Int, speed: Double): ClipSet {
            // One solo rule for tracks and clips (as in a DAW): any solo silences everything that is not soloed.
            val anySolo = project.audio.clips.any { it.solo } || project.tracks.any { it.mix.solo }
            val voices = project.audio.clips.filter { !it.mute && (!anySolo || it.solo) }.mapNotNull { clip ->
                val pcm = buffers[clip.asset] ?: return@mapNotNull null
                ClipVoice(clip.id, pcm, Math_roundToLong(clip.offsetMicros * sampleRate / 1_000_000.0), clip.gain.coerceIn(0.0, 2.0), clip.pan)
            }
            return ClipSet(voices.toTypedArray(), kotlin.math.abs(speed - 1.0) < 1e-9)
        }
        private fun Math_roundToLong(v: Double) = kotlin.math.round(v).toLong()
    }
}
