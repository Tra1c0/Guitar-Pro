package dev.riffworkbench.synth

import kotlin.math.abs
import kotlin.math.roundToInt

/** RIFF/WAVE PCM writer (16/24-bit integer, 32-bit float). Sizes are known up front so output can stream. */
object Wav {
    fun dataBytes(frames: Long, channels: Int, bitDepth: Int): Long = frames * channels * (bitDepth / 8)

    fun header(frames: Long, sampleRate: Int, channels: Int, bitDepth: Int): ByteArray {
        require(bitDepth == 16 || bitDepth == 24 || bitDepth == 32) { "Bittiefe $bitDepth wird nicht unterstützt" }
        val data = dataBytes(frames, channels, bitDepth)
        require(data + 36 <= 0xFFFFFFFFL) { "WAV-Datei überschreitet 4 GiB" }
        val out = ByteArray(44)
        fun ascii(at: Int, text: String) { for (i in text.indices) out[at + i] = text[i].code.toByte() }
        fun le(at: Int, value: Long, bytes: Int) { for (i in 0 until bytes) out[at + i] = ((value ushr (8 * i)) and 0xFF).toByte() }
        ascii(0, "RIFF"); le(4, 36 + data, 4); ascii(8, "WAVE")
        ascii(12, "fmt "); le(16, 16, 4); le(20, if (bitDepth == 32) 3 else 1, 2); le(22, channels.toLong(), 2)
        le(24, sampleRate.toLong(), 4); le(28, sampleRate.toLong() * channels * (bitDepth / 8), 4); le(32, (channels * bitDepth / 8).toLong(), 2); le(34, bitDepth.toLong(), 2)
        ascii(36, "data"); le(40, data, 4)
        return out
    }

    /** Encodes interleaved samples; returns bytes written into [out]. Counts clipped samples in [stats]. */
    fun encode(samples: FloatArray, count: Int, bitDepth: Int, out: ByteArray, stats: Stats): Int {
        var o = 0
        for (i in 0 until count) {
            val s = samples[i]
            val a = abs(s)
            if (a > stats.peak) stats.peak = a
            if (a > 1f) stats.clipped++
            val c = s.coerceIn(-1f, 1f)
            when (bitDepth) {
                16 -> { val v = (c * 32767f).roundToInt(); out[o++] = v.toByte(); out[o++] = (v shr 8).toByte() }
                24 -> { val v = (c * 8388607f).roundToInt(); out[o++] = v.toByte(); out[o++] = (v shr 8).toByte(); out[o++] = (v shr 16).toByte() }
                else -> { val v = s.toRawBits(); out[o++] = v.toByte(); out[o++] = (v shr 8).toByte(); out[o++] = (v shr 16).toByte(); out[o++] = (v shr 24).toByte() }
            }
        }
        return o
    }
    class Stats { var peak = 0f; var clipped = 0L }

    /** Minimal reader for own PCM WAV fixtures and imported reference audio (16/24-bit PCM, 32-bit float). */
    fun decode(bytes: ByteArray): Decoded {
        fun u32(at: Int) = (bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8) or ((bytes[at + 2].toLong() and 0xFF) shl 16) or ((bytes[at + 3].toLong() and 0xFF) shl 24)
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        require(bytes.size >= 44 && bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WAVE") { "Keine WAV-Datei" }
        var pos = 12
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = bytes.decodeToString(pos, pos + 4)
            val size = u32(pos + 4)
            require(size in 0..(bytes.size - pos - 8).toLong()) { "Beschädigter WAV-Block $id" }
            val body = pos + 8
            if (id == "fmt ") { format = u16(body); channels = u16(body + 2); rate = u32(body + 4).toInt(); bits = u16(body + 14); if (format == 0xFFFE && size >= 26) format = u16(body + 24) }
            if (id == "data") {
                require(channels in 1..8 && rate in 8000..192000) { "Nicht unterstütztes WAV-Format" }
                require((format == 1 && (bits == 16 || bits == 24)) || (format == 3 && bits == 32)) { "Nur PCM 16/24 Bit oder Float 32 Bit" }
                val frameBytes = channels * bits / 8
                val frames = (size / frameBytes).toInt()
                val left = FloatArray(frames); val right = FloatArray(frames)
                var p = body
                for (f in 0 until frames) {
                    for (c in 0 until channels) {
                        val v = when (bits) {
                            16 -> (((bytes[p].toInt() and 0xFF) or (bytes[p + 1].toInt() shl 8)).toShort()).toFloat() / 32768f
                            24 -> ((bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() shl 16)).toFloat() / 8388608f
                            else -> Float.fromBits(u32(p).toInt())
                        }
                        if (c == 0) left[f] = v
                        if (c == 1 || channels == 1) right[f] = v
                        p += bits / 8
                    }
                }
                return Decoded(rate, channels, left, right)
            }
            pos = body + size.toInt() + (size.toInt() and 1)
        }
        throw IllegalArgumentException("WAV ohne Datenblock")
    }
    class Decoded(val sampleRate: Int, val channels: Int, val left: FloatArray, val right: FloatArray) { val frames: Int get() = left.size }
}
