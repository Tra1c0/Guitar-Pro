package dev.riffworkbench.synth

import dev.riffworkbench.model.EffectKind
import dev.riffworkbench.model.EffectUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/** Effects process blocks in place. [right] is null on mono channels. Construction allocates; process never does. */
internal interface Effect {
    fun process(left: FloatArray, right: FloatArray?, n: Int)
    fun reset()
    /** Frames by which the output lags the input (the cabinet convolver's partition); the engine compensates the other sources. */
    val latencyFrames: Int get() = 0
}

internal fun EffectUnit.p(name: String, default: Double, range: ClosedFloatingPointRange<Double>): Double =
    (parameters[name]?.takeIf { it.isFinite() } ?: EffectDefaults.value(kind, name) ?: default).coerceIn(range)

/** Parameter values the DSP uses when a unit does not store them; the UI shows the same values (one source of truth). */
object EffectDefaults {
    private val table: Map<EffectKind, Map<String, Double>> = mapOf(
        EffectKind.GATE to mapOf("thresholdDb" to -60.0, "releaseMs" to 60.0),
        EffectKind.OVERDRIVE to mapOf("drive" to 0.5, "tone" to 0.5, "level" to 0.0),
        EffectKind.AMP to mapOf("gain" to 0.5, "bass" to 0.0, "mid" to 0.0, "treble" to 0.0, "presence" to 0.0, "level" to 0.0),
        EffectKind.CABINET_IR to mapOf("model" to 0.0),
        EffectKind.EQ to mapOf("low" to 0.0, "mid" to 0.0, "high" to 0.0, "midHz" to 800.0),
        EffectKind.COMPRESSOR to mapOf("thresholdDb" to -18.0, "ratio" to 3.0, "attackMs" to 8.0, "releaseMs" to 120.0, "makeupDb" to 3.0),
        EffectKind.DELAY to mapOf("timeMs" to 380.0, "feedback" to 0.3, "mix" to 0.25),
        EffectKind.REVERB to mapOf("size" to 0.5, "damping" to 0.5, "mix" to 0.2),
        EffectKind.CHORUS to mapOf("rateHz" to 0.8, "depthMs" to 3.0, "mix" to 0.35),
        EffectKind.FILTER to mapOf("type" to 0.0, "cutoffHz" to 2000.0, "q" to 0.7),
    )
    fun value(kind: EffectKind, name: String): Double? = table[kind]?.get(name)
}

internal object Effects {
    fun create(unit: EffectUnit, sampleRate: Int, maxBlock: Int): Effect? = if (!unit.enabled) null else when (unit.kind) {
        EffectKind.GATE -> Gate(sampleRate, unit.p("thresholdDb", -60.0, -96.0..0.0), unit.p("releaseMs", 60.0, 5.0..1000.0))
        EffectKind.OVERDRIVE -> Overdrive(sampleRate, unit.p("drive", 0.5, 0.0..1.0), unit.p("tone", 0.5, 0.0..1.0), unit.p("level", 0.0, -24.0..12.0))
        EffectKind.AMP -> AmpSim(sampleRate, maxBlock, unit.p("gain", 0.5, 0.0..1.0), unit.p("bass", 0.0, -12.0..12.0), unit.p("mid", 0.0, -12.0..12.0),
            unit.p("treble", 0.0, -12.0..12.0), unit.p("presence", 0.0, -12.0..12.0), unit.p("level", 0.0, -24.0..12.0))
        EffectKind.CABINET_IR -> Cabinet(sampleRate, unit.p("model", 0.0, 0.0..4.0).toInt())
        EffectKind.EQ -> Eq3(sampleRate, unit.p("low", 0.0, -18.0..18.0), unit.p("mid", 0.0, -18.0..18.0), unit.p("high", 0.0, -18.0..18.0), unit.p("midHz", 800.0, 150.0..6000.0))
        EffectKind.COMPRESSOR -> Compressor(sampleRate, unit.p("thresholdDb", -18.0, -60.0..0.0), unit.p("ratio", 3.0, 1.0..20.0),
            unit.p("attackMs", 8.0, 0.1..200.0), unit.p("releaseMs", 120.0, 10.0..2000.0), unit.p("makeupDb", 3.0, 0.0..24.0))
        EffectKind.DELAY -> Delay(sampleRate, unit.p("timeMs", 380.0, 10.0..2000.0), unit.p("feedback", 0.3, 0.0..0.95), unit.p("mix", 0.25, 0.0..1.0))
        EffectKind.REVERB -> Reverb(sampleRate, unit.p("size", 0.5, 0.0..1.0), unit.p("damping", 0.5, 0.0..1.0), unit.p("mix", 0.2, 0.0..1.0))
        EffectKind.CHORUS -> Chorus(sampleRate, unit.p("rateHz", 0.8, 0.05..8.0), unit.p("depthMs", 3.0, 0.1..12.0), unit.p("mix", 0.35, 0.0..1.0))
        EffectKind.FILTER -> Filter(sampleRate, unit.p("type", 0.0, 0.0..2.0).toInt(), unit.p("cutoffHz", 2000.0, 20.0..20000.0), unit.p("q", 0.7, 0.1..12.0))
    }
}

internal class Gate(private val sr: Int, thresholdDb: Double, releaseMs: Double) : Effect {
    private val open = Dsp.dbToGain(thresholdDb).toFloat()
    private val close = open * 0.5f
    private val attack = Dsp.timeCoefficient(0.0008, sr)
    private val release = Dsp.timeCoefficient(releaseMs / 1000.0, sr)
    private val follow = Dsp.timeCoefficient(0.004, sr)
    private val hold = (0.025 * sr).toInt()
    private var env = 0f; private var gain = 0f; private var holdLeft = 0
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        for (i in 0 until n) {
            val level = if (right != null) max(abs(left[i]), abs(right[i])) else abs(left[i])
            env = ftz(if (level > env) level else env * follow + level * (1 - follow))
            val target = if (env > open) { holdLeft = hold; 1f } else if (holdLeft > 0) { holdLeft--; 1f } else if (env < close) 0f else gain
            gain = ftz(if (target > gain) target + (gain - target) * attack else target + (gain - target) * release)
            left[i] *= gain; if (right != null) right[i] *= gain
        }
    }
    override fun reset() { env = 0f; gain = 0f; holdLeft = 0 }
}

internal class Overdrive(sr: Int, drive: Double, tone: Double, levelDb: Double) : Effect {
    private val hump = Biquad().apply { peak(720.0, 0.7, 9.0, sr) }
    private val hump2 = Biquad().apply { peak(720.0, 0.7, 9.0, sr) }
    private val lp = Biquad().apply { lowPass(1500.0 + tone * 5500.0, 0.7, sr) }
    private val lp2 = Biquad().apply { lowPass(1500.0 + tone * 5500.0, 0.7, sr) }
    private val gain = Dsp.dbToGain(drive * 30.0).toFloat()
    private val out = (Dsp.dbToGain(levelDb) * 0.7).toFloat()
    // 4x oversampled clipping (as in AmpSim): at full drive, harmonics of a 3.5 kHz note folded back at -18 dB.
    private val os = 4
    private val upL = Array(3) { i -> Biquad().apply { lowPass(sr * 0.45, doubleArrayOf(0.518, 0.707, 1.932)[i], sr * os) } }
    private val downL = Array(3) { i -> Biquad().apply { lowPass(sr * 0.45, doubleArrayOf(0.518, 0.707, 1.932)[i], sr * os) } }
    private val upR = Array(3) { i -> Biquad().apply { lowPass(sr * 0.45, doubleArrayOf(0.518, 0.707, 1.932)[i], sr * os) } }
    private val downR = Array(3) { i -> Biquad().apply { lowPass(sr * 0.45, doubleArrayOf(0.518, 0.707, 1.932)[i], sr * os) } }
    private fun clip(x: Float, up: Array<Biquad>, down: Array<Biquad>): Float {
        var acc = 0f
        for (phase in 0 until os) {
            var u = if (phase == 0) x * os else 0f
            for (f in up) u = f.process(u)
            u = Dsp.softClip(u * gain)
            for (f in down) u = f.process(u)
            if (phase == 0) acc = u
        }
        return acc
    }
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        for (i in 0 until n) {
            left[i] = lp.process(clip(hump.process(left[i]), upL, downL)) * out
            if (right != null) right[i] = lp2.process(clip(hump2.process(right[i]), upR, downR)) * out
        }
        // Once per block: end the limit cycles near the flush-to-zero quantum, so a silent drive is exactly silent (and cheap).
        hump.flushTiny(); hump2.flushTiny(); lp.flushTiny(); lp2.flushTiny()
        for (f in upL) f.flushTiny(); for (f in downL) f.flushTiny(); for (f in upR) f.flushTiny(); for (f in downR) f.flushTiny()
    }
    // reset() runs on the audio thread when a channel goes idle: plain loops, no list concatenation.
    override fun reset() { hump.reset(); hump2.reset(); lp.reset(); lp2.reset(); for (f in upL) f.reset(); for (f in downL) f.reset(); for (f in upR) f.reset(); for (f in downR) f.reset() }
}

/**
 * Tube-style preamp: up to three cascaded gain stages (one for clean, two for crunch, three for high gain), each an
 * asymmetric soft clipper with coupling high-pass and Miller-style low-pass between stages, run at 4x sample rate so
 * high gain does not alias. Then a tone stack and a gently saturating power stage with presence. Mono.
 */
internal class AmpSim(sr: Int, maxBlock: Int, gain: Double, bass: Double, mid: Double, treble: Double, presence: Double, levelDb: Double) : Effect {
    private val os = 4
    private val tight = Biquad().apply { highPass(70.0 + gain * 60.0, 0.7, sr) }
    private val push = Biquad().apply { peak(750.0, 0.8, 2.0 + gain * 6.0, sr) }
    private val up = Array(3) { i -> Biquad().apply { lowPass(sr * 0.45, doubleArrayOf(0.518, 0.707, 1.932)[i], sr * os) } }
    private val down = Array(3) { i -> Biquad().apply { lowPass(sr * 0.45, doubleArrayOf(0.518, 0.707, 1.932)[i], sr * os) } }
    private val stages = when { gain > 0.68 -> 3; gain > 0.3 -> 2; else -> 1 }
    private val couple = Array(3) { Biquad().apply { highPass(if (it == 0) 25.0 else 90.0 + 40.0 * gain, 0.6, sr * os) } }
    private val miller = Array(3) { Biquad().apply { lowPass(if (it == 0) 11000.0 else 7000.0 - 2500.0 * gain, 0.6, sr * os) } }
    private val stageGain = FloatArray(3) { i -> when (i) { 0 -> Dsp.dbToGain(2.0 + gain * 30.0); 1 -> Dsp.dbToGain(4.0 + gain * 16.0); else -> Dsp.dbToGain(gain * 14.0) }.toFloat() }
    private val bias = floatArrayOf(0.18f, -0.12f, 0.08f)
    private val biasOut = FloatArray(3) { Dsp.softClip(bias[it]) }
    private val low = Biquad().apply { lowShelf(110.0, bass, sr) }
    private val mids = Biquad().apply { peak(650.0, 0.7, mid - 2.5 * gain, sr) }
    private val high = Biquad().apply { highShelf(3200.0, treble, sr) }
    private val pres = Biquad().apply { peak(4500.0, 0.8, presence, sr) }
    private val reso = Biquad().apply { peak(95.0, 1.0, 1.5 + 1.5 * gain, sr) }
    private val output = (Dsp.dbToGain(levelDb) * (if (gain > 0.3) 0.8 else 1.5)).toFloat()
    private val dc = DcBlocker()
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        processChannel(left, n)
        if (right != null) System_arraycopy(left, right, n)
    }
    private fun processChannel(buf: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = push.process(tight.process(buf[i]))
            var acc = 0f
            for (phase in 0 until os) {
                // Zero-stuffed upsampling; the gain os compensates the inserted zeros.
                var u = if (phase == 0) x * os else 0f
                for (f in up) u = f.process(u)
                for (s in 0 until stages) {
                    u = Dsp.softClip(couple[s].process(u) * stageGain[s] + bias[s]) - biasOut[s]
                    u = miller[s].process(u)
                }
                for (f in down) u = f.process(u)
                if (phase == 0) acc = u
            }
            val t = pres.process(high.process(mids.process(low.process(dc.process(acc)))))
            // Power stage: mild saturation and speaker-resonance bump.
            buf[i] = reso.process(Dsp.softClip(t * 1.4f) * 0.72f) * output
        }
    }
    override fun reset() {
        tight.reset(); push.reset(); low.reset(); mids.reset(); high.reset(); pres.reset(); reso.reset(); dc.reset()
        for (f in up) f.reset(); for (f in down) f.reset(); for (f in couple) f.reset(); for (f in miller) f.reset()
    }
}

private fun System_arraycopy(from: FloatArray, to: FloatArray, n: Int) { for (i in 0 until n) to[i] = from[i] }

/**
 * Speaker cabinet as convolution with an own designed impulse response ([CabinetIr]): 0 = 4x12 closed, 1 = 2x12,
 * 2 = 1x12 open back, 3 = bass 8x10, 4 = bypass. [customIr] (a user-imported IR) replaces the designed one.
 */
internal class Cabinet(sr: Int, private val model: Int, customIr: FloatArray? = null) : Effect {
    private val ir = customIr ?: if (model == 4) null else CabinetIr.design(model, sr)
    private val l = ir?.let { PartitionedConvolver(it) }
    private val r = ir?.let { PartitionedConvolver(it) }
    override val latencyFrames: Int = l?.latency ?: 0
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        val cl = l ?: return
        cl.process(left, n)
        if (right != null) r!!.process(right, n)
    }
    override fun reset() { l?.reset(); r?.reset() }
}

internal class Eq3(sr: Int, private val low: Double, private val mid: Double, private val high: Double, private val midHz: Double) : Effect {
    private fun chain(sr: Int) = arrayOf(Biquad().apply { lowShelf(120.0, low, sr) }, Biquad().apply { peak(midHz, 0.8, mid, sr) }, Biquad().apply { highShelf(4200.0, high, sr) })
    private val l = chain(sr); private val r = chain(sr)
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        for (i in 0 until n) {
            left[i] = l[2].process(l[1].process(l[0].process(left[i])))
            if (right != null) right[i] = r[2].process(r[1].process(r[0].process(right[i])))
        }
    }
    override fun reset() { l.forEach { it.reset() }; r.forEach { it.reset() } }
}

internal class Compressor(sr: Int, thresholdDb: Double, private val ratio: Double, attackMs: Double, releaseMs: Double, makeupDb: Double) : Effect {
    private val threshold = thresholdDb.toFloat()
    private val attack = Dsp.timeCoefficient(attackMs / 1000.0, sr)
    private val release = Dsp.timeCoefficient(releaseMs / 1000.0, sr)
    private val makeup = Dsp.dbToGain(makeupDb).toFloat()
    private var env = 0f
    private var gain = 1f
    private var gainTarget = 1f
    /** Persistent sample counter for the 8-sample gain computer, so the output does not depend on the block size. */
    private var tick = 0
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        val slope = (1.0 - 1.0 / ratio).toFloat()
        for (i in 0 until n) {
            val level = if (right != null) max(abs(left[i]), abs(right[i])) else abs(left[i])
            env = ftz(if (level > env) level + (env - level) * attack else level + (env - level) * release)
            // The log-domain gain computer runs every 8 samples; the gain itself is smoothed per sample.
            if (tick == 0) {
                val db = if (env > 1e-6f) (20.0 * log10(env.toDouble())).toFloat() else -120f
                val reduction = if (db > threshold) (db - threshold) * slope else 0f
                gainTarget = Dsp.dbToGain(-reduction.toDouble()).toFloat() * makeup
            }
            tick = (tick + 1) and 7
            gain += (gainTarget - gain) * 0.2f
            left[i] *= gain; if (right != null) right[i] *= gain
        }
    }
    override fun reset() { env = 0f; gain = 1f; gainTarget = 1f; tick = 0 }
}

internal class Delay(private val sr: Int, timeMs: Double, private val feedback: Double, private val mix: Double) : Effect {
    private val size = (2.1 * sr).toInt()
    private val bufL = FloatArray(size); private val bufR = FloatArray(size)
    private val delay = (timeMs / 1000.0 * sr).toInt().coerceIn(1, size - 1)
    private var pos = 0
    private val tone = OnePole().apply { lowPassHz(4500.0, sr) }
    private val toneR = OnePole().apply { lowPassHz(4500.0, sr) }
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        val fb = feedback.toFloat(); val wet = mix.toFloat(); val dry = 1f - wet * 0.5f
        for (i in 0 until n) {
            val read = (pos - delay + size) % size
            val dl = bufL[read]
            bufL[pos] = left[i] + tone.process(dl) * fb
            left[i] = left[i] * dry + dl * wet
            if (right != null) { val dr = bufR[read]; bufR[pos] = right[i] + toneR.process(dr) * fb; right[i] = right[i] * dry + dr * wet }
            pos = (pos + 1) % size
        }
    }
    override fun reset() { bufL.fill(0f); bufR.fill(0f); tone.reset(); toneR.reset() }
}

internal class Chorus(private val sr: Int, private val rateHz: Double, depthMs: Double, private val mix: Double) : Effect {
    private val size = (0.05 * sr).toInt()
    private val bufL = FloatArray(size); private val bufR = FloatArray(size)
    private var pos = 0; private var phase = 0.0
    private val depth = depthMs / 1000.0 * sr
    private val base = 0.012 * sr
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        for (i in 0 until n) {
            phase += rateHz / sr; if (phase >= 1.0) phase -= 1.0
            val mod = Dsp.sin01(phase).toDouble()
            bufL[pos] = left[i]
            left[i] = left[i] * (1f - mix.toFloat() * 0.5f) + read(bufL, base + depth * (0.5 + 0.5 * mod)) * mix.toFloat()
            if (right != null) { bufR[pos] = right[i]; right[i] = right[i] * (1f - mix.toFloat() * 0.5f) + read(bufR, base + depth * (0.5 - 0.5 * mod)) * mix.toFloat() }
            pos = (pos + 1) % size
        }
    }
    private fun read(buf: FloatArray, delay: Double): Float {
        val p = pos - delay
        val i = kotlin.math.floor(p).toInt(); val frac = (p - i).toFloat()
        val a = buf[((i % size) + size) % size]; val b = buf[(((i + 1) % size) + size) % size]
        return a + (b - a) * frac
    }
    override fun reset() { bufL.fill(0f); bufR.fill(0f); pos = 0; phase = 0.0 }
}

/** Schroeder/Moorer reverb (parallel damped combs, serial allpasses) with independent left/right tunings. */
internal class Reverb(sr: Int, size: Double, damping: Double, private val mix: Double) : Effect {
    private val scale = sr / 44100.0
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTunings = intArrayOf(556, 441, 341, 225)
    private val combsL = Array(8) { FloatArray((combTunings[it] * scale).toInt()) }
    private val combsR = Array(8) { FloatArray(((combTunings[it] + 23) * scale).toInt()) }
    private val apL = Array(4) { FloatArray((allpassTunings[it] * scale).toInt()) }
    private val apR = Array(4) { FloatArray(((allpassTunings[it] + 23) * scale).toInt()) }
    private val combPosL = IntArray(8); private val combPosR = IntArray(8)
    private val combStoreL = FloatArray(8); private val combStoreR = FloatArray(8)
    private val apPosL = IntArray(4); private val apPosR = IntArray(4)
    private val feedback = (0.7 + size * 0.28).toFloat()
    private val damp = (damping * 0.4).toFloat()
    private val inputHp = Biquad().apply { highPass(180.0, 0.7, sr) }
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        val wet = mix.toFloat()
        for (i in 0 until n) {
            val input = inputHp.process(if (right != null) (left[i] + right[i]) * 0.5f else left[i]) * 0.015f
            var outL = 0f; var outR = 0f
            for (c in 0 until 8) {
                outL += comb(combsL[c], combPosL, combStoreL, c, input)
                outR += comb(combsR[c], combPosR, combStoreR, c, input)
            }
            for (a in 0 until 4) { outL = allpass(apL[a], apPosL, a, outL); outR = allpass(apR[a], apPosR, a, outR) }
            if (right != null) { left[i] += outL * wet * 3f; right[i] += outR * wet * 3f }
            else left[i] += (outL + outR) * 0.5f * wet * 3f
        }
    }
    private fun comb(buf: FloatArray, posArr: IntArray, store: FloatArray, c: Int, input: Float): Float {
        val p = posArr[c]
        val out = buf[p]
        store[c] = ftz(out * (1f - damp) + store[c] * damp)
        buf[p] = input + store[c] * feedback
        posArr[c] = if (p + 1 >= buf.size) 0 else p + 1
        return out
    }
    private fun allpass(buf: FloatArray, posArr: IntArray, a: Int, input: Float): Float {
        val p = posArr[a]
        val b = buf[p]
        buf[p] = ftz(input + b * 0.5f)
        posArr[a] = if (p + 1 >= buf.size) 0 else p + 1
        return b - input
    }
    override fun reset() { for (b in combsL) b.fill(0f); for (b in combsR) b.fill(0f); for (b in apL) b.fill(0f); for (b in apR) b.fill(0f); combStoreL.fill(0f); combStoreR.fill(0f); inputHp.reset() }
}

internal class Filter(sr: Int, private val type: Int, private val cutoff: Double, private val q: Double) : Effect {
    private fun make(sr: Int) = Biquad().apply { when (type) { 1 -> highPass(cutoff, q, sr); 2 -> bandPass(cutoff, q, sr); else -> lowPass(cutoff, q, sr) } }
    private val l = make(sr); private val r = make(sr)
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        for (i in 0 until n) { left[i] = l.process(left[i]); if (right != null) right[i] = r.process(right[i]) }
    }
    override fun reset() { l.reset(); r.reset() }
}

/** Master safety: peak limiter plus soft ceiling. Keeps exports and live output out of hard clipping. */
internal class Limiter(sr: Int, private val ceiling: Float = 0.94f) : Effect {
    private val release = Dsp.timeCoefficient(0.12, sr)
    private var gain = 1f
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        for (i in 0 until n) {
            val peak = if (right != null) max(abs(left[i]), abs(right[i])) else abs(left[i])
            // The gain computer holds the knee's input at the ceiling (knee output ~0.95 x ceiling): a higher target let 10 % of
            // a dense mix run deep into the knee and distorted more than before under sustained overload.
            val needed = if (peak * gain > target) target / peak else 1f
            gain = if (needed < gain) needed else needed + (gain - needed) * release
            left[i] = knee(left[i] * gain)
            if (right != null) right[i] = knee(right[i] * gain)
        }
    }
    override fun reset() { gain = 1f }
    // Transparent below 80 % of the ceiling, then a smooth knee that approaches the ceiling (it used to saturate every sample
    // and top out at 0.73 instead of the documented 0.94).
    private val kneeStart = ceiling * 0.8f
    private val target = ceiling
    private fun knee(y: Float): Float {
        val a = abs(y)
        if (a <= kneeStart) return y
        val r = ceiling - kneeStart
        val v = kneeStart + r * kotlin.math.tanh((a - kneeStart) / r)
        return if (y < 0f) -v else v
    }
}
