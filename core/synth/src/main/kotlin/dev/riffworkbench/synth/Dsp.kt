package dev.riffworkbench.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Small allocation-free DSP primitives. All state lives in fields; process methods never allocate. */
internal object Dsp {
    const val TWO_PI = 2.0 * PI
    fun midiToHz(midi: Double): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)
    fun dbToGain(db: Double): Double = 10.0.pow(db / 20.0)
    /** Per-sample coefficient reaching 1/e after [seconds]. */
    fun timeCoefficient(seconds: Double, sampleRate: Int): Float = if (seconds <= 0.0) 0f else exp(-1.0 / (seconds * sampleRate)).toFloat()
    /** Gain per period so that amplitude falls by 60 dB after [t60] seconds at [hz]. */
    fun loopGainForT60(t60: Double, hz: Double): Float = 10.0.pow(-3.0 / (t60.coerceAtLeast(0.005) * hz.coerceAtLeast(1.0))).toFloat()
    /** Rational tanh approximation, accurate to ~1e-3 and monotonic on the whole axis. */
    fun softClip(x: Float): Float {
        if (x > 4.97f) return 1f
        // Below ~1e-19 the square underflows to a subnormal (very slow on x86); -360 dBFS is silence.
        if (x > -1e-18f && x < 1e-18f) return 0f
        if (x < -4.97f) return -1f
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
    }
    fun ln2(x: Double) = ln(x) / ln(2.0)

    private const val TABLE = 4096
    private val SIN = FloatArray(TABLE + 1) { sin(TWO_PI * it / TABLE).toFloat() }
    /** Table sine of a phase in cycles (any sign); linear interpolation, error below 1e-6. */
    fun sin01(phase: Double): Float {
        val x = (phase - kotlin.math.floor(phase)) * TABLE
        val i = x.toInt()
        val a = SIN[i]
        return a + (SIN[i + 1] - a) * (x - i).toFloat()
    }
}

/**
 * Branch-free flush-to-zero for recursive state: adding and removing 1e-18 rounds anything below ~1e-25
 * (-500 dBFS) to exactly zero, so decaying filters never enter the subnormal range (very slow on x86).
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun ftz(x: Float): Float = (x + 1e-18f) - 1e-18f

/** Deterministic xorshift noise so offline renders and tests are reproducible. */
internal class Noise(seed: Int = 0x2545F491) {
    private var state = if (seed == 0) 0x2545F491 else seed
    fun reseed(seed: Int) { state = if (seed == 0) 0x2545F491 else seed }
    fun next(): Float {
        var x = state
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        state = x
        return (x and 0x7FFFFF).toFloat() / 0x400000.toFloat() - 1f
    }
}

/** RBJ cookbook biquad, transposed direct form II. */
internal class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f
    fun reset() { z1 = 0f; z2 = 0f }
    /**
     * Rounding at the [ftz] quantum can hold a high-Q or oversampled section in a limit cycle around 1e-23 instead of
     * decaying to zero; a state that small (-400 dBFS) is cleared, so silence in gives exact silence out.
     */
    fun flushTiny() { if (abs(z1) < 1e-20f && abs(z2) < 1e-20f) { z1 = 0f; z2 = 0f } }
    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = ftz(b1 * x - a1 * y + z2)
        z2 = ftz(b2 * x - a2 * y)
        return y
    }
    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat(); a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
    }
    private fun w(hz: Double, sr: Int) = Dsp.TWO_PI * hz.coerceIn(5.0, sr * 0.49) / sr
    fun lowPass(hz: Double, q: Double, sr: Int) { val w0 = w(hz, sr); val c = cos(w0); val a = sin(w0) / (2 * q); set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + a, -2 * c, 1 - a) }
    fun highPass(hz: Double, q: Double, sr: Int) { val w0 = w(hz, sr); val c = cos(w0); val a = sin(w0) / (2 * q); set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + a, -2 * c, 1 - a) }
    fun bandPass(hz: Double, q: Double, sr: Int) { val w0 = w(hz, sr); val c = cos(w0); val a = sin(w0) / (2 * q); set(a, 0.0, -a, 1 + a, -2 * c, 1 - a) }
    fun peak(hz: Double, q: Double, db: Double, sr: Int) {
        val w0 = w(hz, sr); val c = cos(w0); val a = sin(w0) / (2 * q); val g = 10.0.pow(db / 40.0)
        set(1 + a * g, -2 * c, 1 - a * g, 1 + a / g, -2 * c, 1 - a / g)
    }
    fun lowShelf(hz: Double, db: Double, sr: Int) {
        val w0 = w(hz, sr); val c = cos(w0); val g = 10.0.pow(db / 40.0); val a = sin(w0) / 2 * sqrt(2.0); val sg = 2 * sqrt(g) * a
        set(g * ((g + 1) - (g - 1) * c + sg), 2 * g * ((g - 1) - (g + 1) * c), g * ((g + 1) - (g - 1) * c - sg), (g + 1) + (g - 1) * c + sg, -2 * ((g - 1) + (g + 1) * c), (g + 1) + (g - 1) * c - sg)
    }
    fun highShelf(hz: Double, db: Double, sr: Int) {
        val w0 = w(hz, sr); val c = cos(w0); val g = 10.0.pow(db / 40.0); val a = sin(w0) / 2 * sqrt(2.0); val sg = 2 * sqrt(g) * a
        set(g * ((g + 1) + (g - 1) * c + sg), -2 * g * ((g - 1) + (g + 1) * c), g * ((g + 1) + (g - 1) * c - sg), (g + 1) - (g - 1) * c + sg, 2 * ((g - 1) - (g + 1) * c), (g + 1) - (g - 1) * c - sg)
    }
}

internal class OnePole {
    var a = 0f; private var y = 0f
    fun reset() { y = 0f }
    fun lowPassHz(hz: Double, sr: Int) { a = exp(-Dsp.TWO_PI * hz / sr).toFloat() }
    fun process(x: Float): Float { y = ftz((1f - a) * x + a * y); return y }
}

internal class DcBlocker {
    private var x1 = 0f; private var y1 = 0f
    fun reset() { x1 = 0f; y1 = 0f }
    fun process(x: Float): Float { val y = ftz(x - x1 + 0.995f * y1); x1 = x; y1 = y; return y }
}

/** One-pole parameter smoothing against zipper noise. */
internal class Smoothed(initial: Float) {
    var value = initial; private set
    var target = initial
    fun next(coefficient: Float): Float { value = target + (value - target) * coefficient; return value }
    fun snap() { value = target }
}
