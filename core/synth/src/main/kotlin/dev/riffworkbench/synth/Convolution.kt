package dev.riffworkbench.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place radix-2 complex FFT with precomputed twiddles; no allocation after construction. */
internal class Fft(val size: Int) {
    init { require(size >= 2 && size and (size - 1) == 0) }
    private val bits = Integer.numberOfTrailingZeros(size)
    private val rev = IntArray(size) { i -> Integer.reverse(i) ushr (32 - bits) }
    private val cosT = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }

    /** Forward transform when [inverse] is false; the inverse is unscaled (divide by [size]). */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until size) { val j = rev[i]; if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var len = 2
        while (len <= size) {
            val half = len / 2; val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosT[k]; val wi = if (inverse) sinT[k] else -sinT[k]
                    val a = start + j; val b = a + half
                    val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                    k += step
                }
                start += len
            }
            len = len shl 1
        }
    }
}

/**
 * Uniformly partitioned overlap-save convolution (block [block], FFT 2x block). Adds [block] samples of latency;
 * per sample it costs O(log block) for the transforms plus O(IR length / block) for the spectral products. Mono.
 */
internal class PartitionedConvolver(ir: FloatArray, private val block: Int = 64) {
    private val n = block * 2
    private val fft = Fft(n)
    private val parts = (ir.size + block - 1) / block
    private val irRe = Array(parts) { FloatArray(n) }
    private val irIm = Array(parts) { FloatArray(n) }
    private val histRe = Array(parts) { FloatArray(n) }
    private val histIm = Array(parts) { FloatArray(n) }
    private var head = 0
    private val inBuf = FloatArray(n)
    private val outBuf = FloatArray(block)
    private val accRe = FloatArray(n)
    private val accIm = FloatArray(n)
    private var fill = 0
    /** Output lags the input by one partition. */
    val latency: Int get() = block

    init {
        for (p in 0 until parts) {
            for (i in 0 until block) { val k = p * block + i; if (k < ir.size) irRe[p][i] = ir[k] }
            fft.transform(irRe[p], irIm[p], inverse = false)
        }
    }

    fun reset() { histRe.forEach { it.fill(0f) }; histIm.forEach { it.fill(0f) }; inBuf.fill(0f); outBuf.fill(0f); fill = 0 }

    fun process(buf: FloatArray, count: Int) {
        for (i in 0 until count) {
            val x = buf[i]
            buf[i] = outBuf[fill]
            inBuf[block + fill] = x
            fill++
            if (fill == block) { runBlock(); fill = 0 }
        }
    }

    private fun runBlock() {
        head = (head + parts - 1) % parts
        val hr = histRe[head]; val hi = histIm[head]
        for (i in 0 until n) { hr[i] = inBuf[i]; hi[i] = 0f }
        fft.transform(hr, hi, inverse = false)
        accRe.fill(0f); accIm.fill(0f)
        for (p in 0 until parts) {
            val h = (head + p) % parts
            val xr = histRe[h]; val xi = histIm[h]; val cr = irRe[p]; val ci = irIm[p]
            for (k in 0 until n) { accRe[k] += xr[k] * cr[k] - xi[k] * ci[k]; accIm[k] += xr[k] * ci[k] + xi[k] * cr[k] }
        }
        fft.transform(accRe, accIm, inverse = true)
        val scale = 1f / n
        for (i in 0 until block) outBuf[i] = accRe[block + i] * scale
        for (i in 0 until block) inBuf[i] = inBuf[block + i]
    }
}

/**
 * Own cabinet impulse responses (designed, not measured): speaker/box resonance, the cone-breakup region and the
 * steep high roll-off of guitar speakers, plus fine deterministic ripple so it does not sound like a plain EQ.
 * Converted to minimum phase (real cepstrum), 1024 taps at 48 kHz.
 */
internal object CabinetIr {
    fun design(model: Int, sampleRate: Int, taps: Int = (sampleRate / 46.875).toInt().coerceAtLeast(256)): FloatArray {
        val n = 8192
        val half = n / 2
        val mag = DoubleArray(half + 1)
        var seed = 0x9E3779B1.toInt() + model * 7919
        fun rnd(): Double { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5); return (seed and 0xFFFF) / 65535.0 * 2 - 1 }
        // Ripple: a few dozen narrow bumps between 700 Hz and 6 kHz (cone break-up), seeded per model.
        val bumps = (0 until 40).map { Triple(700.0 * Math_pow(6000.0 / 700.0, (it + 0.5 + 0.4 * rnd()) / 40), 2.5 + 2.0 * rnd(), 0.06 + 0.03 * rnd()) }
        for (k in 0..half) {
            val f = (k.toDouble() * sampleRate / n).coerceAtLeast(1.0)
            var db = when (model) {
                1 -> curve(f, hp = 80.0, res = 115.0 to 3.0, dip = 1900.0 to -2.5, presence = listOf(1400.0 to 2.0, 3300.0 to 3.5), lp = 6000.0)
                2 -> curve(f, hp = 95.0, res = 135.0 to 2.0, dip = 700.0 to -1.0, presence = listOf(1100.0 to 1.5, 2700.0 to 3.0), lp = 6500.0)
                3 -> curve(f, hp = 38.0, res = 70.0 to 3.5, dip = 900.0 to -2.0, presence = listOf(2200.0 to 1.5), lp = 4200.0)
                else -> curve(f, hp = 72.0, res = 105.0 to 4.5, dip = 420.0 to -2.0, presence = listOf(1600.0 to 1.5, 2500.0 to 4.0, 4100.0 to 3.0), lp = 5300.0)
            }
            if (model != 3) for ((fc, g, w) in bumps) { val x = ln(f / fc) / w; db += g * exp(-x * x) * 0.5 }
            mag[k] = Dsp.dbToGain(db)
        }
        val ir = minimumPhase(mag, n)
        val out = FloatArray(taps)
        for (i in 0 until taps) {
            val w = if (i > taps * 3 / 4) 0.5 * (1 + cos(PI * (i - taps * 3 / 4.0) / (taps / 4.0))) else 1.0
            out[i] = (ir[i] * w).toFloat()
        }
        // Normalise to unity gain at 1 kHz-ish (RMS of the response between 500 Hz and 2 kHz).
        val k1 = (500.0 * n / sampleRate).toInt(); val k2 = (2000.0 * n / sampleRate).toInt()
        val ref = sqrt((k1..k2).sumOf { mag[it] * mag[it] } / (k2 - k1 + 1))
        val g = (1.0 / ref).toFloat()
        for (i in out.indices) out[i] *= g
        return out
    }

    private fun curve(f: Double, hp: Double, res: Pair<Double, Double>, dip: Pair<Double, Double>, presence: List<Pair<Double, Double>>, lp: Double): Double {
        fun bell(fc: Double, db: Double, octaves: Double): Double { val x = Dsp.ln2(f / fc) / octaves; return db * exp(-x * x) }
        var db = 0.0
        db += -40.0 * kotlin.math.log10(1 + Math_pow(hp / f, 4.0)) / 2          // 4th-order high-pass (sealed-box roll-off)
        db += bell(res.first, res.second, 0.45)
        db += bell(dip.first, dip.second, 0.8)
        for ((fc, g) in presence) db += bell(fc, g, 0.35)
        db += -60.0 * kotlin.math.log10(1 + Math_pow(f / lp, 6.0)) / 2          // steep cone roll-off above the break-up
        return db
    }

    /** Minimum-phase impulse response for a magnitude response sampled at 0..n/2 (real cepstrum folding). */
    private fun minimumPhase(mag: DoubleArray, n: Int): DoubleArray {
        val fft = Fft(n)
        val re = FloatArray(n); val im = FloatArray(n)
        for (k in 0 until n) { val m = mag[if (k <= n / 2) k else n - k].coerceAtLeast(1e-6); re[k] = ln(m).toFloat() }
        fft.transform(re, im, inverse = true)
        for (i in 0 until n) { re[i] /= n; im[i] = 0f }
        // Fold the cepstrum: keep c0 and c(n/2), double the causal part, drop the anti-causal part.
        for (i in 1 until n / 2) { re[i] *= 2f; re[n - i] = 0f }
        fft.transform(re, im, inverse = false)
        // exp of the complex log spectrum
        for (k in 0 until n) { val e = exp(re[k].toDouble()); val a = im[k].toDouble(); re[k] = (e * cos(a)).toFloat(); im[k] = (e * sin(a)).toFloat() }
        fft.transform(re, im, inverse = true)
        return DoubleArray(n) { re[it].toDouble() / n }
    }

    private fun Math_pow(a: Double, b: Double) = exp(b * ln(a))
}
