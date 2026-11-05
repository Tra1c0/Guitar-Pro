package dev.riffworkbench.synth

import org.junit.Test
import kotlin.math.*

/** QA cycle 6: the cycle-5 amp change made the bass presets (gain 0.3) 5 dB quieter; it was reverted. The level must stay as shipped. */
class FixReviewAmpReviewTest {
    private fun goertzel(x: FloatArray, from: Int, hz: Double, sr: Int): Double {
        val w = 2 * PI * hz / sr; val c = 2 * cos(w); var s1 = 0.0; var s2 = 0.0
        for (i in from until x.size) { val s0 = x[i] + c * s1 - s2; s2 = s1; s1 = s0 }
        return sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / (x.size - from)
    }
    @Test fun bassPickAmpAtGain03() {
        val sr = 48_000
        val levels = HashMap<Pair<Float, Double>, Double>()
        for (amp in listOf(0.3f, 0.03f)) for (gain in listOf(0.25, 0.3, 0.32, 0.34, 0.35, 0.36, 0.38, 0.4, 0.42)) {
            val a = AmpSim(sr, 1024, gain, 3.0, 1.0, 1.0, 0.0, 2.0)
            val n = sr; val x = FloatArray(n) { (amp * sin(2 * PI * 82.4 * it / sr)).toFloat() }
            var i = 0; while (i < n) { val k = minOf(1024, n - i); val buf = x.copyOfRange(i, i + k); a.process(buf, null, k); System.arraycopy(buf, 0, x, i, k); i += k }
            var rms = 0.0; for (j in n / 2 until n) rms += x[j] * x[j].toDouble(); rms = sqrt(rms / (n / 2))
            val h1 = goertzel(x, n / 2, 82.4, sr); val h3 = goertzel(x, n / 2, 3 * 82.4, sr); val h2 = goertzel(x, n / 2, 2 * 82.4, sr)
            levels[amp to gain] = 20 * log10(rms)
            println("AMPBENCH in=$amp gain=$gain rmsDb=${"%.2f".format(20 * log10(rms))} h2/h1=${"%.1f".format(20 * log10(h2 / h1))}dB h3/h1=${"%.1f".format(20 * log10(h3 / h1))}dB")
        }
        // Bass-pick preset (gain 0.3): the measured level before the cycle-5 change.
        org.junit.Assert.assertEquals(-0.19, levels.getValue(0.3f to 0.3), 0.05)
    }
}
