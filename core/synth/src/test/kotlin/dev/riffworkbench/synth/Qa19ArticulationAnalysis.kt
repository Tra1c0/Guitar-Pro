package dev.riffworkbench.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * QA cycle 19: measurement kit for guitar articulations (stdlib only, deterministic). All windows are given in frames
 * relative to the note start; levels are dB relative to full scale unless stated otherwise.
 */
internal object Qa19Analysis {
    fun db(x: Double): Double = if (x <= 1e-15) -300.0 else 20 * log10(x)
    fun f1(x: Double) = String.format(java.util.Locale.ROOT, "%.1f", x)
    fun f2(x: Double) = String.format(java.util.Locale.ROOT, "%.2f", x)

    fun rms(x: FloatArray, from: Int, to: Int): Double {
        val a = from.coerceIn(0, x.size); val b = to.coerceIn(a, x.size)
        var s = 0.0; for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / (b - a).coerceAtLeast(1))
    }
    fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Double { var p = 0.0; for (i in from.coerceAtLeast(0) until to.coerceAtMost(x.size)) p = maxOf(p, abs(x[i].toDouble())); return p }

    /** RMS envelope in [hop]-frame steps over [win]-frame windows, in dB. */
    fun envelope(x: FloatArray, sr: Int, hopMs: Double = 5.0, winMs: Double = 10.0): DoubleArray {
        val hop = (hopMs * sr / 1000).toInt(); val win = (winMs * sr / 1000).toInt()
        val n = (x.size - win) / hop
        return DoubleArray(n.coerceAtLeast(0)) { db(rms(x, it * hop, it * hop + win)) }
    }

    /**
     * Decay: milliseconds from the loudest envelope frame until the envelope stays below (max - [dropDb]) for good (the last
     * time it is above that line, plus one hop). Null when the signal is still above the line at the end of [x].
     */
    fun decayMs(x: FloatArray, sr: Int, dropDb: Double, from: Int = 0, hopMs: Double = 5.0): Double? {
        val env = envelope(x.copyOfRange(from.coerceAtLeast(0), x.size), sr, hopMs)
        if (env.isEmpty()) return null
        var top = 0; for (i in env.indices) if (env[i] > env[top]) top = i
        val line = env[top] - dropDb
        var last = top; for (i in top until env.size) if (env[i] > line) last = i
        if (last >= env.size - 2) return null
        return (last + 1 - top) * hopMs
    }
    /** Milliseconds from [fromFrame] until the envelope stays below [levelDb] (absolute) for good; null if never. */
    fun timeBelowMs(x: FloatArray, sr: Int, levelDb: Double, fromFrame: Int, hopMs: Double = 5.0): Double? {
        val env = envelope(x.copyOfRange(fromFrame.coerceAtLeast(0), x.size), sr, hopMs)
        var last = -1; for (i in env.indices) if (env[i] > levelDb) last = i
        if (last >= env.size - 2) return null
        return (last + 1) * hopMs
    }

    /** Attack: RMS of the first 20 ms against the body (20-200 ms) in dB; a sharp pick transient is positive. */
    fun attackDb(x: FloatArray, sr: Int, from: Int = 0): Double = db(rms(x, from, from + sr / 50)) - db(rms(x, from + sr / 50, from + sr / 5))

    // ------------------------------------------------------------------ spectrum

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len; val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    class Spectrum(val power: DoubleArray, val binHz: Double) {
        /** Power between [lo] and [hi] Hz. */
        fun band(lo: Double, hi: Double): Double { var s = 0.0; for (k in (lo / binHz).toInt().coerceAtLeast(1)..(hi / binHz).toInt().coerceAtMost(power.size - 1)) s += power[k]; return s }
        val total: Double get() = band(40.0, 16_000.0)
        fun centroid(): Double { var a = 0.0; var b = 0.0; for (k in (40 / binHz).toInt() until power.size) { if (k * binHz > 16_000) break; a += k * binHz * power[k]; b += power[k] }; return if (b <= 0) 0.0 else a / b }
        fun shareAbove(hz: Double): Double = band(hz, 16_000.0) / total.coerceAtLeast(1e-30)
        /** Share of the power within +-[cents] of [hz]. */
        fun shareAt(hz: Double, cents: Double = 50.0): Double { val r = Math.pow(2.0, cents / 1200); return band(hz / r, hz * r) / total.coerceAtLeast(1e-30) }
        /** Share of the power in the first [count] partials of [f0] (+-50 cents each): 1 = purely periodic at f0. */
        fun harmonicShare(f0: Double, count: Int = 12): Double { var s = 0.0; for (k in 1..count) if (f0 * k < 16_000) s += shareAt(f0 * k); return s }
    }

    /** Power spectrum of [x] from [from] over 2^[log2] frames (Hann window); shorter signals are zero-padded. */
    fun spectrum(x: FloatArray, sr: Int, from: Int, log2: Int = 14): Spectrum {
        val n = 1 shl log2
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until n) { val s = if (from + i in x.indices) x[from + i].toDouble() else 0.0; re[i] = s * (0.5 - 0.5 * cos(2 * PI * i / (n - 1))) }
        fft(re, im)
        return Spectrum(DoubleArray(n / 2) { re[it] * re[it] + im[it] * im[it] }, sr.toDouble() / n)
    }

    /**
     * Periodicity: the highest normalised autocorrelation for lags within +-6 % of the period of [f0] in a [winMs] window
     * from [from]. A stable pitch at f0 gives ~0.9-1, noise ~0.
     */
    fun periodicity(x: FloatArray, sr: Int, f0: Double, from: Int, winMs: Double = 40.0): Double {
        val n = (winMs * sr / 1000).toInt()
        val p = sr / f0
        var best = -1.0
        for (lag in (p * 0.94).toInt()..(p * 1.06).toInt() + 1) {
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (i in from until from + n) { if (i + lag >= x.size) break; val a = x[i].toDouble(); val b = x[i + lag].toDouble(); xy += a * b; xx += a * a; yy += b * b }
            if (xx > 0 && yy > 0) best = maxOf(best, xy / sqrt(xx * yy))
        }
        return best
    }

    /** Fundamental estimate (YIN-style difference function) between [loHz] and [hiHz] in a 40 ms window from [from]. */
    fun pitchHz(x: FloatArray, sr: Int, from: Int, loHz: Double = 60.0, hiHz: Double = 1500.0, winMs: Double = 40.0): Double {
        val n = (winMs * sr / 1000).toInt()
        val maxLag = (sr / loHz).toInt(); val minLag = (sr / hiHz).toInt()
        val d = DoubleArray(maxLag + 2)
        for (lag in 1..maxLag + 1) { var s = 0.0; for (i in from until from + n) { if (i + lag >= x.size) break; val v = x[i] - x[i + lag]; s += v * v }; d[lag] = s }
        // Cumulative mean normalised difference; first dip below 0.15, else the global minimum.
        val cm = DoubleArray(d.size); var sum = 0.0; cm[0] = 1.0
        for (lag in 1 until d.size) { sum += d[lag]; cm[lag] = if (sum <= 0) 1.0 else d[lag] * lag / sum }
        var best = -1
        for (lag in minLag..maxLag) if (cm[lag] < 0.15) { var l = lag; while (l + 1 <= maxLag && cm[l + 1] < cm[l]) l++; best = l; break }
        if (best < 0) { best = minLag; for (lag in minLag..maxLag) if (cm[lag] < cm[best]) best = lag }
        // Parabolic refinement.
        val a = cm[best - 1]; val b = cm[best]; val c = cm[best + 1]
        val shift = if (a - 2 * b + c != 0.0) 0.5 * (a - c) / (a - 2 * b + c) else 0.0
        return sr / (best + shift.coerceIn(-1.0, 1.0))
    }
    fun cents(hz: Double, ref: Double) = 1200 * Math.log(hz / ref) / Math.log(2.0)

    /** THD of [y] (a sine of [hz] after processing) over its second half, harmonics 2..10, in percent. */
    fun thdPercent(y: FloatArray, sr: Int, hz: Double): Double {
        fun g(f: Double): Double {
            val from = y.size / 2; val len = y.size - from
            val w = 2 * PI * f / sr; val c = 2 * cos(w); var s1 = 0.0; var s2 = 0.0
            for (i in from until y.size) { val h = 0.5 - 0.5 * cos(2 * PI * (i - from) / (len - 1)); val s0 = y[i] * h + c * s1 - s2; s2 = s1; s1 = s0 }
            return s1 * s1 + s2 * s2 - c * s1 * s2
        }
        val p1 = g(hz); var ph = 0.0
        for (k in 2..10) if (hz * k < sr / 2) ph += g(hz * k)
        return 100 * sqrt(ph / p1)
    }
}
