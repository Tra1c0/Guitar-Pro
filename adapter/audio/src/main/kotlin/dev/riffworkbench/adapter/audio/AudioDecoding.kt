package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.synth.PcmBuffer
import dev.riffworkbench.synth.PcmBuilder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * WAV decoder in Kotlin: PCM 8/16/24/32 bit, float 32/64 bit, WAVE_FORMAT_EXTENSIBLE, 1–8 channels. The data block is
 * streamed to the [PcmSink] in 4096-frame chunks. A data size larger than the file (recorders that never patch the
 * header) is clamped to the bytes present instead of being rejected.
 */
class WavAudioDecoder : AudioDecoder {
    private class Layout(val format: Int, val channels: Int, val rate: Int, val bits: Int, val dataStart: Int, val dataBytes: Int) {
        val frameBytes get() = channels * bits / 8
        val frames get() = dataBytes / frameBytes
        fun info() = AudioInfo("audio/wav", rate, channels, frames * 1_000_000L / rate)
    }

    override suspend fun probe(bytes: ByteArray): Outcome<AudioInfo> = when (val l = layout(bytes)) {
        is Outcome.Success -> Outcome.Success(l.value.info())
        is Outcome.Failure -> l
    }

    override suspend fun decode(bytes: ByteArray, sink: PcmSink): Outcome<AudioInfo> {
        val layout = when (val l = layout(bytes)) { is Outcome.Success -> l.value; is Outcome.Failure -> return l }
        val chunk = ShortArray(4096 * layout.channels)
        val step = layout.bits / 8
        var p = layout.dataStart
        var remaining = layout.frames
        return try {
            while (remaining > 0) {
                if (!currentCoroutineContext().isActive) return Outcome.Failure(FailureCode.CANCELLED, "Abgebrochen")
                val n = minOf(4096, remaining)
                for (i in 0 until n * layout.channels) { chunk[i] = sample(bytes, p, layout); p += step }
                remaining -= n
                if (!sink.accept(layout.rate, layout.channels, chunk, n)) break
            }
            Outcome.Success(layout.info())
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(FailureCode.VALIDATION, e.message ?: "Ungültige Audiodaten")
        }
    }

    private fun sample(b: ByteArray, p: Int, l: Layout): Short = when {
        l.format == 1 && l.bits == 8 -> (((b[p].toInt() and 0xFF) - 128) shl 8).toShort()
        l.format == 1 && l.bits == 16 -> ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)).toShort()
        l.format == 1 && l.bits == 24 -> (b[p + 1].toInt() and 0xFF or (b[p + 2].toInt() shl 8)).toShort()
        l.format == 1 -> (b[p + 2].toInt() and 0xFF or (b[p + 3].toInt() shl 8)).toShort()
        l.bits == 32 -> toShort(Float.fromBits(u32(b, p).toInt()).toDouble())
        else -> toShort(Double.fromBits(u32(b, p) or (u32(b, p + 4) shl 32)))
    }

    private fun toShort(v: Double): Short = if (v.isNaN()) 0 else (v.coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort()

    private fun layout(bytes: ByteArray): Outcome<Layout> {
        if (!isWav(bytes)) return Outcome.Failure(FailureCode.VALIDATION, "Keine WAV-Datei")
        var pos = 12
        var format = 0; var channels = 0; var rate = 0; var bits = 0; var haveFormat = false
        while (pos + 8 <= bytes.size) {
            val id = String(CharArray(4) { (bytes[pos + it].toInt() and 0xFF).toChar() })
            val size = u32(bytes, pos + 4)
            val body = pos + 8
            if (id == "data") {
                if (!haveFormat) return Outcome.Failure(FailureCode.CORRUPT, "WAV: Datenblock vor dem Formatblock")
                if (channels !in 1..8 || rate !in 8_000..192_000) return Outcome.Failure(FailureCode.VALIDATION, "WAV: nicht unterstützte Kanäle oder Abtastrate")
                val supported = (format == 1 && bits in setOf(8, 16, 24, 32)) || (format == 3 && (bits == 32 || bits == 64))
                if (!supported) return Outcome.Failure(FailureCode.VALIDATION, "WAV: nur PCM 8/16/24/32 Bit oder Float 32/64 Bit")
                val available = (bytes.size - body).toLong()
                val dataBytes = minOf(size, available).toInt()
                val layout = Layout(format, channels, rate, bits, body, dataBytes)
                if (layout.frames == 0) return Outcome.Failure(FailureCode.VALIDATION, "WAV enthält keine Samples")
                return Outcome.Success(layout)
            }
            if (size > (bytes.size - body).toLong()) return Outcome.Failure(FailureCode.CORRUPT, "WAV: beschädigter Block $id")
            if (id == "fmt ") {
                if (size < 16) return Outcome.Failure(FailureCode.CORRUPT, "WAV: Formatblock zu kurz")
                format = u16(bytes, body); channels = u16(bytes, body + 2); rate = u32(bytes, body + 4).toInt(); bits = u16(bytes, body + 14)
                if (format == 0xFFFE && size >= 26) format = u16(bytes, body + 24)
                haveFormat = true
            }
            pos = body + size.toInt() + (size.toInt() and 1)
        }
        return Outcome.Failure(FailureCode.CORRUPT, "WAV ohne Datenblock")
    }

    companion object {
        fun isWav(bytes: ByteArray) = bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() && bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, at: Int) = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}

/** WAV in Kotlin; every other container goes to [platform] (MediaCodec on Android). Without a platform decoder only WAV works. */
class AudioDecoders(private val platform: AudioDecoder? = null) : AudioDecoder {
    private val wav = WavAudioDecoder()
    override suspend fun probe(bytes: ByteArray): Outcome<AudioInfo> =
        if (WavAudioDecoder.isWav(bytes)) wav.probe(bytes) else platform?.probe(bytes) ?: unsupported()
    override suspend fun decode(bytes: ByteArray, sink: PcmSink): Outcome<AudioInfo> =
        if (WavAudioDecoder.isWav(bytes)) wav.decode(bytes, sink) else platform?.decode(bytes, sink) ?: unsupported()
    private fun unsupported() = Outcome.Failure(FailureCode.VALIDATION, "Dieses Audioformat kann hier nicht decodiert werden (WAV geht immer)")
}

/**
 * Loads a reference-audio asset and decodes it to engine-rate stereo PCM. Clips are capped at [maxSeconds]
 * (default 10 min ≈ 115 MB at 48 kHz) so one long file cannot exhaust the heap.
 */
class DecodingClipLoader(private val assets: AssetStore, private val decoder: AudioDecoder, private val maxSeconds: Int = MAX_CLIP_SECONDS) : ClipLoader {
    override suspend fun load(asset: AssetHash, targetRate: Int): Outcome<PcmBuffer> {
        val bytes = when (val r = assets.get(asset)) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        val info = when (val r = decoder.probe(bytes)) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        val maxFrames = maxSeconds * targetRate
        val expected = (info.durationMicros.coerceAtLeast(0) * targetRate / 1_000_000L).coerceAtMost(maxFrames.toLong()).toInt()
        val builder = PcmBuilder(targetRate, maxFrames, expected)
        val decoded = try {
            decoder.decode(bytes) { rate, channels, data, frames -> builder.append(rate, channels, data, frames) }
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(FailureCode.VALIDATION, e.message ?: "Ungültige Audiodaten")
        }
        if (decoded is Outcome.Failure) return decoded
        if (builder.outputFrames == 0) return Outcome.Failure(FailureCode.VALIDATION, "Die Audiodatei enthält keine decodierbaren Samples")
        return Outcome.Success(builder.build())
    }

    companion object { const val MAX_CLIP_SECONDS = 600 }
}

/**
 * C-WAVEFORM: min/max peaks of the mono mix, computed while decoding without keeping any PCM. Results are cached per
 * asset and resolution (the asset is immutable, so the hash is a sufficient key).
 */
class StreamingWaveforms(private val assets: AssetStore, private val decoder: AudioDecoder, private val maxSeconds: Int = DecodingClipLoader.MAX_CLIP_SECONDS) : WaveformCache {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Pair<AssetHash, Int>, WaveformLevel>()

    /** Decodes off the caller's thread (WAV decoding is plain Kotlin and would otherwise run on the UI dispatcher). */
    override suspend fun peaks(asset: AssetHash, samplesPerPeak: Int): Outcome<WaveformLevel> = withContext(Dispatchers.Default) { compute(asset, samplesPerPeak) }

    private suspend fun compute(asset: AssetHash, samplesPerPeak: Int): Outcome<WaveformLevel> {
        if (samplesPerPeak !in 16..1_000_000) return Outcome.Failure(FailureCode.VALIDATION, "Ungültige Wellenform-Auflösung", contractId = "C-WAVEFORM")
        mutex.withLock { cache[asset to samplesPerPeak]?.let { return Outcome.Success(it) } }
        val bytes = when (val r = assets.get(asset)) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        val minimum = ArrayList<Float>(); val maximum = ArrayList<Float>()
        var rate = 0; var count = 0; var frames = 0L
        var lo = 1f; var hi = -1f
        val decoded = try {
            decoder.decode(bytes) { sampleRate, channels, data, n ->
                rate = sampleRate
                for (f in 0 until n) {
                    val v = if (channels == 1) data[f] / 32768f else (data[f * channels] + data[f * channels + 1]) / 65536f
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                    if (++count == samplesPerPeak) { minimum += lo; maximum += hi; lo = 1f; hi = -1f; count = 0 }
                }
                frames += n
                frames < maxSeconds.toLong() * sampleRate
            }
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(FailureCode.VALIDATION, e.message ?: "Ungültige Audiodaten", contractId = "C-WAVEFORM")
        }
        if (decoded is Outcome.Failure) return decoded
        if (count > 0) { minimum += lo; maximum += hi }
        if (minimum.isEmpty() || rate == 0) return Outcome.Failure(FailureCode.VALIDATION, "Die Audiodatei enthält keine Samples", contractId = "C-WAVEFORM")
        val level = WaveformLevel(samplesPerPeak, minimum, maximum, rate)
        mutex.withLock {
            cache[asset to samplesPerPeak] = level
            while (cache.size > 16) cache.remove(cache.keys.first())
        }
        return Outcome.Success(level)
    }
}
