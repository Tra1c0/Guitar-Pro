package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.synth.Wav
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DspKitWav {
    class MemoryAssets : AssetStore {
        val data = HashMap<AssetHash, ByteArray>()
        override suspend fun put(name: String, mediaType: String, bytes: ByteArray, rights: AssetRights): Outcome<AssetDescriptor> {
            val hash = AssetHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            data[hash] = bytes
            return Outcome.Success(AssetDescriptor(hash, name, mediaType, bytes.size.toLong(), rights))
        }
        override suspend fun get(hash: AssetHash): Outcome<ByteArray> = data[hash]?.let { Outcome.Success(it) } ?: Outcome.Failure(FailureCode.IO, "fehlt")
    }

    class B { val o = ByteArrayOutputStream()
        fun le(v: Long, n: Int) = apply { for (i in 0 until n) o.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        fun id(s: String) = apply { s.forEach { o.write(it.code) } }
        fun raw(b: ByteArray) = apply { o.write(b) }
        fun bytes(): ByteArray = o.toByteArray() }

    class Chunk(val id: String, val body: ByteArray, val sizeOverride: Long? = null, val pad: Boolean = true)

    fun riff(chunks: List<Chunk>, riffSize: Long? = null): ByteArray {
        val b = B()
        for (c in chunks) { b.id(c.id).le(c.sizeOverride ?: c.body.size.toLong(), 4).raw(c.body); if (c.pad && c.body.size % 2 == 1) b.le(0, 1) }
        val inner = b.bytes()
        return B().id("RIFF").le(riffSize ?: (4L + inner.size), 4).id("WAVE").raw(inner).bytes()
    }

    fun fmt(format: Int, ch: Int, rate: Int, bits: Int, blockAlign: Int = ch * bits / 8, extensible: Int? = null): Chunk {
        val b = B().le((if (extensible != null) 0xFFFE else format).toLong(), 2).le(ch.toLong(), 2).le(rate.toLong(), 4).le(rate.toLong() * blockAlign, 4).le(blockAlign.toLong(), 2).le(bits.toLong(), 2)
        if (extensible != null) { b.le(22, 2).le(bits.toLong(), 2).le(if (ch == 1) 4 else 3, 4).le(extensible.toLong(), 2); b.raw(byteArrayOf(0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)) }
        return Chunk("fmt ", b.bytes())
    }

    /** Integer sample values (raw container ints), expected 16-bit output per decoder spec. */
    fun pcmData(bits: Int, float: Boolean, values: LongArray, dvalues: DoubleArray): ByteArray {
        val b = B()
        if (float) for (v in dvalues) { if (bits == 32) b.le(v.toFloat().toRawBits().toLong() and 0xFFFFFFFFL, 4) else b.le(v.toRawBits(), 8) }
        else for (v in values) b.le(if (bits == 8) v + 128 else v, bits / 8)
        return b.bytes()
    }

    fun expected16(bits: Int, float: Boolean, v: Long, d: Double): Short = when {
        float -> { val q = if (bits == 32) d.toFloat().toDouble() else d; if (q.isNaN()) 0 else Math.round(q.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort() }
        bits == 8 -> (v shl 8).toInt().toShort()
        else -> (v shr (bits - 16)).toInt().toShort()
    }

    class Capture { val out = ArrayList<Short>(); var rate = 0; var ch = 0; var frames = 0L; var calls = 0 }

    fun decode(bytes: ByteArray, cap: Capture = Capture()): Pair<Outcome<AudioInfo>, Capture> = runBlocking {
        val r = WavAudioDecoder().decode(bytes) { rate, ch, data, n -> cap.rate = rate; cap.ch = ch; cap.calls++; cap.frames += n; for (i in 0 until n * ch) cap.out += data[i]; true }
        r to cap
    }

    fun headerVariantsBitExact() {
        val rnd = Random(1)
        val lines = ArrayList<String>(); var fails = 0
        for (bits in listOf(8, 16, 24, 32)) for (float in listOf(false, true)) {
            if (float && bits !in setOf(32, 64)) continue
            for (ch in 1..8) for (rate in listOf(8_000, 11_025, 22_050, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000)) for (ext in listOf(false, true)) {
                val frames = 1 + rnd.nextInt(5000)
                val max = (1L shl (bits - 1)) - 1
                val values = LongArray(frames * ch) { when (it % 7) { 0 -> max; 1 -> -max - 1; 2 -> 0; 3 -> -1; else -> rnd.nextLong(-max - 1, max + 1) } }
                val dvalues = DoubleArray(frames * ch) { when (it % 9) { 0 -> 1.0; 1 -> -1.0; 2 -> 1.5; 3 -> -2.0; 4 -> Double.NaN; 5 -> 0.0; else -> rnd.nextDouble() * 2 - 1 } }
                val data = pcmData(bits, float, values, dvalues)
                val f = if (ext) fmt(0, ch, rate, bits, extensible = if (float) 3 else 1) else fmt(if (float) 3 else 1, ch, rate, bits)
                val bytes = riff(listOf(Chunk("LIST", ByteArray(13) { 'x'.code.toByte() }), f, Chunk("fact", B().le(frames.toLong(), 4).bytes()), Chunk("data", data)))
                val (r, cap) = decode(bytes)
                val ok = r is Outcome.Success && cap.frames == frames.toLong() && cap.ch == ch && cap.rate == rate &&
                    (0 until frames * ch).all { cap.out[it] == expected16(bits, float, values[it], dvalues[it]) }
                if (!ok) { fails++; if (lines.size < 10) lines += "bits=$bits float=$float ch=$ch rate=$rate ext=$ext -> $r frames=${cap.frames}/$frames" }
            }
        }
        // 64-bit float
        for (ch in 1..8) {
            val d = DoubleArray(100 * ch) { (it % 21 - 10) / 10.0 }
            val bytes = riff(listOf(fmt(3, ch, 48_000, 64), Chunk("data", pcmData(64, true, LongArray(0), d))))
            val (r, cap) = decode(bytes)
            if (r !is Outcome.Success || (0 until d.size).any { cap.out[it] != expected16(64, true, 0, d[it]) }) { fails++; lines += "float64 ch=$ch -> $r" }
        }
        println("VARIANTS fails=$fails"); lines.forEach { println("  $it") }
    }

    fun edgeHeaders() {
        val data16 = pcmData(16, false, LongArray(1000) { (it * 31 % 65536 - 32768).toLong() }, DoubleArray(0))
        val f = fmt(1, 1, 48_000, 16)
        val cases = linkedMapOf(
            "dataSize0" to riff(listOf(f, Chunk("data", data16, sizeOverride = 0))),
            "dataSizeFFFFFFFF" to riff(listOf(f, Chunk("data", data16, sizeOverride = 0xFFFFFFFFL))),
            "dataSizeLarger" to riff(listOf(f, Chunk("data", data16, sizeOverride = 5000))),
            "riffSize0" to riff(listOf(f, Chunk("data", data16)), riffSize = 0),
            "fmtAfterData" to riff(listOf(Chunk("data", data16), f)),
            "twoDataChunks" to riff(listOf(f, Chunk("data", data16.copyOf(500)), Chunk("data", data16.copyOfRange(500, 1000)))),
            "oddLIST" to riff(listOf(Chunk("LIST", ByteArray(3)), f, Chunk("data", data16))),
            "oddLISTnoPad" to riff(listOf(Chunk("LIST", ByteArray(3), pad = false), f, Chunk("data", data16))),
            "oddData8bit" to riff(listOf(fmt(1, 1, 8000, 8), Chunk("data", ByteArray(7) { 0x90.toByte() }), Chunk("LIST", ByteArray(4)))),
            "partialFrame" to riff(listOf(fmt(1, 2, 48000, 24), Chunk("data", ByteArray(6 * 10 + 4)))),
            "blockAlignMismatch(4 for mono16)" to riff(listOf(fmt(1, 1, 48_000, 16, blockAlign = 4), Chunk("data", data16))),
            "pcm24in32container(blockAlign=4)" to riff(listOf(fmt(1, 1, 48_000, 24, blockAlign = 4), Chunk("data", B().apply { for (i in 0 until 1000) le(((i * 4000L) % 8_000_000) shl 8, 4) }.bytes()))),
            "blockAlign0" to riff(listOf(fmt(1, 1, 48_000, 16, blockAlign = 0), Chunk("data", data16))),
            "rate384k" to riff(listOf(fmt(1, 1, 384_000, 16), Chunk("data", data16))),
            "rate7999" to riff(listOf(fmt(1, 1, 7999, 16), Chunk("data", data16))),
            "ch0" to riff(listOf(fmt(1, 0, 48000, 16, blockAlign = 2), Chunk("data", data16))),
            "ch9" to riff(listOf(fmt(1, 9, 48000, 16), Chunk("data", data16))),
            "bits12" to riff(listOf(fmt(1, 1, 48000, 12, blockAlign = 2), Chunk("data", data16))),
            "bits20in24ext" to riff(listOf(fmt(0, 1, 48000, 24, extensible = 1), Chunk("data", data16.copyOf(999)))),
            "fmt14bytes" to riff(listOf(Chunk("fmt ", ByteArray(14)), Chunk("data", data16))),
            "noData" to riff(listOf(f)),
            "headerOnly12" to riff(emptyList()),
            "extensibleCb0(size18)" to riff(listOf(Chunk("fmt ", fmt(0, 1, 48000, 16, extensible = 1).body.copyOf(18)), Chunk("data", data16))),
            "RIFX" to riff(listOf(f, Chunk("data", data16))).also { it[3] = 'X'.code.toByte() },
            "RF64" to riff(listOf(f, Chunk("data", data16))).also { it[0] = 'R'.code.toByte(); it[1] = 'F'.code.toByte(); it[2] = '6'.code.toByte(); it[3] = '4'.code.toByte() },
        )
        for ((label, bytes) in cases) {
            val (r, cap) = runCatching { decode(bytes) }.getOrElse { println("EDGE $label THREW $it"); continue }
            val probe = runBlocking { WavAudioDecoder().probe(bytes) }
            println("EDGE $label -> ${if (r is Outcome.Failure) "Failure(${r.code}: ${r.message})" else "Success frames=${cap.frames} ch=${cap.ch} first=${cap.out.take(3)}"} probe=${(probe as? Outcome.Success)?.value ?: (probe as Outcome.Failure).code}")
        }
    }

    fun mutationFuzz() {
        val rnd = Random(42)
        val seeds = ArrayList<ByteArray>()
        for (bits in listOf(8, 16, 24, 32)) for (ch in listOf(1, 2, 6)) seeds += riff(listOf(Chunk("LIST", ByteArray(5)), fmt(1, ch, 44_100, bits), Chunk("data", ByteArray(ch * bits / 8 * 300) { rnd.nextInt().toByte() })))
        for (bits in listOf(32, 64)) seeds += riff(listOf(fmt(3, 2, 96_000, bits), Chunk("data", ByteArray(2 * bits / 8 * 200) { rnd.nextInt().toByte() })))
        seeds += riff(listOf(fmt(0, 2, 48_000, 24, extensible = 1), Chunk("fact", ByteArray(4)), Chunk("data", ByteArray(600))))
        val assets = MemoryAssets()
        var total = 0; var success = 0; var failures = HashMap<String, Int>(); var exceptions = 0; var slow = 0; var inconsistent = 0
        val exc = ArrayList<String>()
        val t0 = System.nanoTime()
        repeat(12_000) { k ->
            val base = seeds[k % seeds.size]
            var b = base.copyOf()
            when (k % 6) {
                0 -> repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt().toByte() }
                1 -> { val at = 4 + rnd.nextInt(minOf(b.size - 4, 80)); for (i in 0 until 4) if (at + i < b.size) b[at + i] = listOf(0, 0xFF, 0x7F, 0x80, 1, rnd.nextInt())[rnd.nextInt(6)].toByte() }
                2 -> b = b.copyOf(rnd.nextInt(b.size + 1))
                3 -> { val at = rnd.nextInt(b.size); b = b.copyOfRange(0, at) + ByteArray(rnd.nextInt(64)) { rnd.nextInt().toByte() } + b.copyOfRange(at, b.size) }
                4 -> { // header field fuzz: channels / rate / bits / format / data size
                    val fmtAt = String(CharArray(b.size) { (b[it].toInt() and 0xFF).toChar() }).indexOf("fmt ")
                    if (fmtAt >= 0 && fmtAt + 24 <= b.size) { val off = listOf(8, 10, 12, 20, 22, 4)[rnd.nextInt(6)]; b[fmtAt + off] = rnd.nextInt().toByte(); b[fmtAt + off + 1] = listOf(0, 0, 0xFF, rnd.nextInt())[rnd.nextInt(4)].toByte() }
                }
                else -> { val dataAt = String(CharArray(b.size) { (b[it].toInt() and 0xFF).toChar() }).indexOf("data"); if (dataAt >= 0) for (i in 4 until 8) b[dataAt + i] = rnd.nextInt().toByte() }
            }
            total++
            val start = System.nanoTime()
            try {
                runBlocking { withTimeout(2000) {
                    val dec = WavAudioDecoders()
                    val probe = dec.probe(b)
                    var frames = 0L
                    val r = dec.decode(b) { _, ch, data, n -> require(n * ch <= data.size); frames += n; true }
                    if ((probe is Outcome.Success) != (r is Outcome.Success)) inconsistent++
                    if (probe is Outcome.Success && r is Outcome.Success && probe.value != r.value) inconsistent++
                    when (r) { is Outcome.Success -> success++; is Outcome.Failure -> failures.merge(r.code.name, 1, Int::plus) }
                    val hash = (assets.put("x", "audio/wav", b) as Outcome.Success).value.hash
                    DecodingClipLoader(assets, AudioDecoders()).load(hash, listOf(44_100, 48_000)[k % 2])
                    StreamingWaveforms(assets, AudioDecoders()).peaks(hash, 64)
                    assets.data.clear()
                } }
            } catch (e: Throwable) { exceptions++; if (exc.size < 10) exc += "k=$k mode=${k % 6} $e" }
            if ((System.nanoTime() - start) / 1e6 > 500) slow++
        }
        println("FUZZ total=$total success=$success failures=$failures exceptions=$exceptions slow=$slow inconsistent=$inconsistent in ${(System.nanoTime() - t0) / 1e6} ms")
        exc.forEach { println("  $it") }
        // Same mutations against core/synth Wav.decode (documented as also reading imported reference audio).
        var wavExc = HashMap<String, Int>()
        repeat(6000) { k ->
            var b = seeds[k % seeds.size].copyOf()
            repeat(1 + rnd.nextInt(6)) { b[rnd.nextInt(b.size)] = rnd.nextInt().toByte() }
            if (k % 3 == 0) b = b.copyOf(rnd.nextInt(b.size + 1))
            try { Wav.decode(b) } catch (e: IllegalArgumentException) { wavExc.merge("IAE", 1, Int::plus) } catch (e: Throwable) { wavExc.merge(e.javaClass.simpleName, 1, Int::plus) }
        }
        println("WAVDECODE exceptions=$wavExc")
    }

    private fun WavAudioDecoders() = AudioDecoders()
}
