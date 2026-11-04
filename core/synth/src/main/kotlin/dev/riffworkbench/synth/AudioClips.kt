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
        /** From interleaved 16-bit PCM (what Android decoders emit) without a float copy of the whole file. */
        fun fromShorts(rate: Int, channels: Int, data: ShortArray, targetRate: Int): PcmBuffer {
            require(channels in 1..8 && rate in 8_000..192_000) { "Nicht unterstütztes Audioformat" }
            val inFrames = data.size / channels
            val ratio = rate.toDouble() / targetRate
            val frames = if (inFrames == 0) 0 else floor((inFrames - 1) / ratio).toInt() + 1
            val out = ShortArray(frames * 2)
            for (i in 0 until frames) {
                val pos = i * ratio
                val k = floor(pos).toInt().coerceAtMost(inFrames - 1)
                val k2 = (k + 1).coerceAtMost(inFrames - 1)
                val t = pos - k
                for (ch in 0 until 2) {
                    val c = if (channels == 1) 0 else ch
                    val a = data[k * channels + c]; val b = data[k2 * channels + c]
                    out[2 * i + ch] = (a + (b - a) * t).toInt().toShort()
                }
            }
            return PcmBuffer(targetRate, frames, out)
        }
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
            val anySolo = project.audio.clips.any { it.solo }
            val voices = project.audio.clips.filter { !it.mute && (!anySolo || it.solo) }.mapNotNull { clip ->
                val pcm = buffers[clip.asset] ?: return@mapNotNull null
                ClipVoice(clip.id, pcm, Math_roundToLong(clip.offsetMicros * sampleRate / 1_000_000.0), clip.gain.coerceIn(0.0, 2.0), clip.pan)
            }
            return ClipSet(voices.toTypedArray(), kotlin.math.abs(speed - 1.0) < 1e-9)
        }
        private fun Math_roundToLong(v: Double) = kotlin.math.round(v).toLong()
    }
}
