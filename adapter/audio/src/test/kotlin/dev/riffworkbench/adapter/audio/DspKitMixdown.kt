package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.synth.*
import java.io.ByteArrayOutputStream
import kotlin.math.*
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DspKitMixdown {
    private var n = 0
    private fun id() = EntityId("q${n++}")
    private fun project(bars: Int = 2, withNote: Boolean = true): Project {
        val m = List(bars) { Measure(id()) }
        val beats = if (withNote) listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0)))) else emptyList()
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }
    fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    fun u32(b: ByteArray, at: Int) = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    fun checkHeader(h: ByteArray, frames: Long, rate: Int, ch: Int, bits: Int, fileLen: Long?): String? {
        val bytesPer = bits / 8
        val problems = ArrayList<String>()
        if (String(h, 0, 4) != "RIFF" || String(h, 8, 8) != "WAVEfmt " || String(h, 36, 4) != "data") problems += "ids"
        if (u32(h, 4) != 36 + frames * ch * bytesPer) problems += "riff=${u32(h, 4)}"
        if (u32(h, 16) != 16L) problems += "fmtsize"
        if (u16(h, 20) != (if (bits == 32) 3 else 1)) problems += "fmt=${u16(h, 20)}"
        if (u16(h, 22) != ch) problems += "ch"
        if (u32(h, 24) != rate.toLong()) problems += "rate"
        if (u32(h, 28) != rate.toLong() * ch * bytesPer) problems += "byterate=${u32(h, 28)}"
        if (u16(h, 32) != ch * bytesPer) problems += "align"
        if (u16(h, 34) != bits) problems += "bits"
        if (u32(h, 40) != frames * ch * bytesPer) problems += "data=${u32(h, 40)}"
        if (fileLen != null && fileLen != 44 + frames * ch * bytesPer) problems += "len=$fileLen"
        return problems.takeIf { it.isNotEmpty() }?.joinToString()
    }

    fun headersAndRoundTrip() {
        var bad = 0
        for (bits in listOf(16, 24, 32)) for (rate in listOf(8_000, 22_050, 44_100, 48_000, 96_000, 192_000, 384_000)) for (ch in 1..8) for (frames in listOf(0L, 1L, 12_345L, 100_000_000L)) {
            val h = runCatching { Wav.header(frames, rate, ch, bits) }.getOrElse { if (frames * ch * bits / 8 + 36 <= 0xFFFFFFFFL) { bad++; println("HDR threw $bits/$rate/$ch/$frames $it") }; null } ?: continue
            checkHeader(h, frames, rate, ch, bits, null)?.let { bad++; println("HDR $bits/$rate/$ch/$frames: $it") }
        }
        println("HDR problems=$bad; 4GiB guard: " + runCatching { Wav.header(600_000_000L, 48_000, 2, 32) }.exceptionOrNull())
        val rnd = Random(2)
        val x = FloatArray(200_000) { when (it) { 0 -> 1f; 1 -> -1f; 2 -> 1.5f; 3 -> -3f; 4 -> 0f; 5 -> -1f / 8388607f; 6 -> 1f / 8388607f; 7 -> -0.5f; 8 -> -1e-9f; else -> rnd.nextFloat() * 2 - 1 } }
        for (bits in listOf(16, 24, 32)) {
            val stats = Wav.Stats()
            val bytes = ByteArray(x.size * 4)
            val nBytes = Wav.encode(x, x.size, bits, bytes, stats)
            val wav = Wav.header(x.size / 2L, 48_000, 2, bits) + bytes.copyOf(nBytes)
            val d = Wav.decode(wav)
            val lsb = if (bits == 16) 1.0 / 32768 else if (bits == 24) 1.0 / 8388608 else 0.0
            var maxErr = 0.0; var worstIdx = -1
            for (i in x.indices) { val v = (if (i % 2 == 0) d.left else d.right)[i / 2].toDouble(); val ref = if (bits == 32) x[i].toDouble() else x[i].toDouble().coerceIn(-1.0, 1.0); val e = abs(v - ref); if (e > maxErr) { maxErr = e; worstIdx = i } }
            // Via the import decoder (16-bit output).
            val cap = ArrayList<Short>()
            runBlocking { WavAudioDecoder().decode(wav) { _, ch, data, nn -> for (i in 0 until nn * ch) cap += data[i]; true } }
            var maxErr16 = 0.0; for (i in x.indices) maxErr16 = maxOf(maxErr16, abs(cap[i] / 32767.0 - x[i].toDouble().coerceIn(-1.0, 1.0)))
            println("RT bits=$bits maxErr=${"%.3g".format(maxErr)} (${if (lsb > 0) "%.3f LSB".format(maxErr / lsb) else "exact"}) at x=${x.getOrNull(worstIdx)} clipped=${stats.clipped} peak=${stats.peak} | via WavAudioDecoder maxErr=${"%.2f".format(maxErr16 * 32767)} LSB16")
            println("RT bits=$bits decoded specials: ${(0..8).map { (if (it % 2 == 0) d.left else d.right)[it / 2] }}")
        }
        // 24-bit sign handling at byte level
        val b = ByteArray(12); Wav.encode(floatArrayOf(-1f, -1f / 8388607f, 0.5f, -0.5f), 4, 24, b, Wav.Stats())
        println("S24 bytes=" + b.joinToString(" ") { "%02x".format(it) })
        println("NaN encode16: " + runCatching { Wav.encode(floatArrayOf(Float.NaN), 1, 16, ByteArray(4), Wav.Stats()) }.exceptionOrNull())
        println("NaN encode32: " + runCatching { Wav.encode(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY), 2, 32, ByteArray(8), Wav.Stats()) }.exceptionOrNull())
    }

    fun mixdownFormatAndLength() {
        val p = project(2)
        for (rate in listOf(22_050, 44_100, 48_000, 96_000)) for (bits in listOf(16, 24, 32)) for (tail in listOf(0.0, 0.123, 2.0, 99.0)) {
            val out = ByteArrayOutputStream()
            val opts = RenderOptions(sampleRate = rate, bitDepth = bits, tailSeconds = tail)
            val r = OfflineMixdown().render(p, opts, { bb, o, l -> out.write(bb, o, l) })
            val frames = (OfflineMixdown().framesFor(p, opts) as Outcome.Success).value
            val bytes = out.toByteArray()
            val expectedFrames = 4L * rate + Math.round(tail.coerceIn(0.0, 30.0) * rate)
            val s = (r as Outcome.Success).value
            val prob = checkHeader(bytes, frames, rate, 2, bits, bytes.size.toLong())
            if (prob != null || frames != expectedFrames || s.frames != frames || s.bytes != bytes.size.toLong()) println("MIX rate=$rate bits=$bits tail=$tail frames=$frames expected=$expectedFrames summary=$s problems=$prob")
        }
        println("MIX done")
        for (o in listOf(RenderOptions(tailSeconds = Double.NaN), RenderOptions(sampleRate = 0), RenderOptions(sampleRate = 1_000_000), RenderOptions(bitDepth = 8)))
            println("MIXOPT $o -> " + runCatching { OfflineMixdown().render(p, o, { _, _, _ -> }) }.let { it.exceptionOrNull()?.toString() ?: (it.getOrNull() as? Outcome.Failure)?.message ?: "Success" })
    }

    fun clipSyncInMixdown() {
        val p = project(2, withNote = false) // 4 s of silence from the synth
        for (rate in listOf(44_100, 48_000)) {
            // Clip: impulse at clip frames 0 and 1000, then a 1 kHz tone from 3.0 s to 6.0 s (score ends at 4.0 s)
            val frames = 6 * rate
            val data = ShortArray(frames * 2)
            data[0] = 20000; data[1] = 20000; data[2000] = 20000; data[2001] = 20000
            for (i in 3 * rate until frames) { val v = (8000 * sin(2 * PI * 1000.0 * i / rate)).toInt().toShort(); data[2 * i] = v; data[2 * i + 1] = v }
            val pcm = PcmBuffer(rate, frames, data)
            for (offsetMicros in listOf(0L, 333_333L, -10_000L)) {
                val start = Math.round(offsetMicros * rate / 1_000_000.0)
                val clips = ClipSet(arrayOf(ClipVoice(EntityId("c"), pcm, start, 1.0, 0.0)), true)
                val out = ByteArrayOutputStream()
                OfflineMixdown().render(p, RenderOptions(sampleRate = rate, bitDepth = 32, tailSeconds = 2.0, includeMasterFx = false), { bb, o, l -> out.write(bb, o, l) }, clips = clips)
                val d = Wav.decode(out.toByteArray())
                val onsets = (0 until minOf(d.frames, rate)).filter { abs(d.left[it]) > 0.05f }
                fun rms(a: Int, b: Int) = sqrt((a until b).sumOf { (d.left[it] * d.left[it]).toDouble() } / (b - a))
                println("CLIP rate=$rate offset=${offsetMicros}us startFrame=$start onsets=${onsets.take(4)} expected=${listOf(start, start + 1000).filter { it >= 0 }} " +
                    "rms[3.0-4.0s]=${"%.4f".format(rms(3 * rate + 100, 4 * rate - 100))} rms[4.0-4.5s]=${"%.4f".format(rms(4 * rate + 10, 4 * rate + rate / 2))} rms[5-6s]=${"%.4f".format(rms(5 * rate, 6 * rate))} total=${d.frames}")
            }
        }
    }

    class MemoryAssets : AssetStore {
        val data = HashMap<AssetHash, ByteArray>()
        override suspend fun put(name: String, mediaType: String, bytes: ByteArray, rights: AssetRights): Outcome<AssetDescriptor> {
            val hash = AssetHash(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            data[hash] = bytes; return Outcome.Success(AssetDescriptor(hash, name, mediaType, bytes.size.toLong(), rights))
        }
        override suspend fun get(hash: AssetHash): Outcome<ByteArray> = data[hash]?.let { Outcome.Success(it) } ?: Outcome.Failure(FailureCode.IO, "fehlt")
    }

    fun wav16(rate: Int, ch: Int, frames: Int, v: (Int, Int) -> Double): ByteArray {
        val x = FloatArray(frames * ch) { v(it / ch, it % ch).toFloat() }
        val body = ByteArray(x.size * 2); Wav.encode(x, x.size, 16, body, Wav.Stats())
        return Wav.header(frames.toLong(), rate, ch, 16) + body
    }

    fun waveformPeaks() = runBlocking {
        val assets = MemoryAssets()
        suspend fun put(b: ByteArray) = (assets.put("w", "audio/wav", b) as Outcome.Success).value.hash
        val waves = StreamingWaveforms(assets, AudioDecoders(), maxSeconds = 2)
        // Mono ramp, 1000 frames, 300 spp -> 4 peaks, last partial (100 frames)
        val ramp = put(wav16(48_000, 1, 1000) { f, _ -> f / 1000.0 - 0.5 })
        val l = (waves.peaks(ramp, 300) as Outcome.Success).value
        println("WF ramp n=${l.minimum.size} min=${l.minimum.map { "%.4f".format(it) }} max=${l.maximum.map { "%.4f".format(it) }} duration=${l.durationSeconds}s actual=${1000 / 48_000.0}s")
        // Stereo opposite channels -> mono mix 0; stereo identical full-scale
        val opp = put(wav16(44_100, 2, 4410) { _, c -> if (c == 0) 0.8 else -0.8 })
        val lo = (waves.peaks(opp, 441) as Outcome.Success).value
        println("WF opposite stereo max=${lo.maximum.max()} min=${lo.minimum.min()}")
        val full = put(wav16(44_100, 2, 4410) { f, _ -> if (f % 2 == 0) 1.0 else -1.0 })
        val lf = (waves.peaks(full, 441) as Outcome.Success).value
        println("WF fullscale stereo max=${lf.maximum.max()} min=${lf.minimum.min()}")
        // 6-channel: only first two used
        val six = put(wav16(48_000, 6, 4800) { _, c -> if (c < 2) 0.0 else 0.9 })
        println("WF 6ch (ch3-6 at 0.9, ch1-2 silent) max=${(waves.peaks(six, 480) as Outcome.Success).value.maximum.max()}")
        // maxSeconds=2 on a 5 s file
        val long = put(wav16(48_000, 1, 5 * 48_000) { f, _ -> 0.1 })
        val ll = (waves.peaks(long, 480) as Outcome.Success).value
        println("WF cap: peaks=${ll.minimum.size} (2 s = 200) duration=${ll.durationSeconds}")
        // Cache: FIFO vs LRU
        val hashes = (0 until 17).map { k -> put(wav16(48_000, 1, 480 + k) { _, _ -> 0.01 * k }) }
        val first = (waves.peaks(hashes[0], 16) as Outcome.Success).value
        for (k in 1 until 16) { waves.peaks(hashes[k], 16); waves.peaks(hashes[0], 16) } // hashes[0] is the most recently used
        waves.peaks(hashes[16], 16) // 17th entry -> eviction
        val again = (waves.peaks(hashes[0], 16) as Outcome.Success).value
        println("WF cache: most-recently-used entry survived eviction = ${again === first}")
    }
}
