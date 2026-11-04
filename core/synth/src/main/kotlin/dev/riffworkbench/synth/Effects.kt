package dev.riffworkbench.synth

import dev.riffworkbench.model.EffectKind
import dev.riffworkbench.model.EffectUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/** Effects process blocks in place. [right] is null on mono channels. Construction allocates; process never does. */
internal interface Effect {
    fun process(left: FloatArray, right: FloatArray?, n: Int)
    fun reset()
}

internal fun EffectUnit.p(name: String, default: Double, range: ClosedFloatingPointRange<Double>): Double =
    (parameters[name]?.takeIf { it.isFinite() } ?: default).coerceIn(range)

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
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        for (i in 0 until n) {
            left[i] = lp.process(Dsp.softClip(hump.process(left[i]) * gain)) * out
            if (right != null) right[i] = lp2.process(Dsp.softClip(hump2.process(right[i]) * gain)) * out
        }
    }
    override fun reset() { hump.reset(); hump2.reset(); lp.reset(); lp2.reset() }
}

/** Two-stage tube-style preamp, 2x oversampled around the waveshapers, followed by a tone stack. Mono. */
internal class AmpSim(sr: Int, maxBlock: Int, gain: Double, bass: Double, mid: Double, treble: Double, presence: Double, levelDb: Double) : Effect {
    private val tight = Biquad().apply { highPass(95.0, 0.7, sr) }
    private val push = Biquad().apply { peak(800.0, 0.8, 3.0 + gain * 6.0, sr) }
    private val up1 = Biquad().apply { lowPass(sr * 0.45, 0.541, sr * 2) }
    private val up2 = Biquad().apply { lowPass(sr * 0.45, 1.307, sr * 2) }
    private val down1 = Biquad().apply { lowPass(sr * 0.45, 0.541, sr * 2) }
    private val down2 = Biquad().apply { lowPass(sr * 0.45, 1.307, sr * 2) }
    private val inter = Biquad().apply { highPass(60.0, 0.7, sr * 2) }
    private val low = Biquad().apply { lowShelf(110.0, bass, sr) }
    private val mids = Biquad().apply { peak(650.0, 0.7, mid - 2.0 * gain, sr) }
    private val high = Biquad().apply { highShelf(3200.0, treble, sr) }
    private val pres = Biquad().apply { peak(4800.0, 0.9, presence, sr) }
    private val drive = Dsp.dbToGain(4.0 + gain * 44.0).toFloat()
    private val secondStage = gain > 0.45
    private val stage2 = (1.5 + gain * 2.0).toFloat()
    private val output = (Dsp.dbToGain(levelDb) * (if (gain > 0.3) 0.55 else 0.9)).toFloat()
    private val bias = 0.12f
    private val biasOffset = Dsp.softClip(bias)
    private val dc = DcBlocker()
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        processChannel(left, n)
        if (right != null) System_arraycopy(left, right, n)
    }
    private fun processChannel(buf: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = push.process(tight.process(buf[i])) * drive
            // Zero-stuffed 2x upsampling (gain 2 compensates the inserted zero).
            var acc = 0f
            for (phase in 0 until 2) {
                val u = up2.process(up1.process(if (phase == 0) x * 2f else 0f))
                var y = Dsp.softClip(u + bias) - biasOffset
                if (secondStage) y = Dsp.softClip(inter.process(y) * stage2)
                val d = down2.process(down1.process(y))
                if (phase == 1) acc = d
            }
            buf[i] = pres.process(high.process(mids.process(low.process(dc.process(acc))))) * output
        }
    }
    override fun reset() { listOf(tight, push, up1, up2, down1, down2, inter, low, mids, high, pres).forEach { it.reset() }; dc.reset() }
}

private fun System_arraycopy(from: FloatArray, to: FloatArray, n: Int) { for (i in 0 until n) to[i] = from[i] }

/** Speaker-cabinet approximation: 0 = 4x12 closed, 1 = 2x12, 2 = 1x12 open back, 3 = bass 8x10, 4 = bypass. */
internal class Cabinet(sr: Int, private val model: Int) : Effect {
    private val chainL = build(sr)
    private val chainR = build(sr)
    private fun build(sr: Int): Array<Biquad> = when (model) {
        4 -> emptyArray()
        3 -> arrayOf(Biquad().apply { highPass(38.0, 0.7, sr) }, Biquad().apply { peak(80.0, 1.0, 3.0, sr) }, Biquad().apply { peak(900.0, 0.8, -2.0, sr) }, Biquad().apply { lowPass(4200.0, 0.7, sr) }, Biquad().apply { lowPass(5500.0, 0.6, sr) })
        2 -> arrayOf(Biquad().apply { highPass(85.0, 0.6, sr) }, Biquad().apply { peak(130.0, 1.0, 1.5, sr) }, Biquad().apply { peak(2700.0, 1.2, 2.5, sr) }, Biquad().apply { lowPass(6200.0, 0.75, sr) }, Biquad().apply { lowPass(7800.0, 0.6, sr) })
        1 -> arrayOf(Biquad().apply { highPass(80.0, 0.7, sr) }, Biquad().apply { peak(120.0, 1.1, 2.5, sr) }, Biquad().apply { peak(1900.0, 1.0, -2.5, sr) }, Biquad().apply { peak(3500.0, 1.2, 2.5, sr) }, Biquad().apply { lowPass(5600.0, 0.8, sr) }, Biquad().apply { lowPass(7200.0, 0.6, sr) })
        else -> arrayOf(Biquad().apply { highPass(75.0, 0.7, sr) }, Biquad().apply { peak(110.0, 1.2, 3.5, sr) }, Biquad().apply { peak(1600.0, 0.9, -3.0, sr) }, Biquad().apply { peak(3800.0, 1.3, 2.5, sr) }, Biquad().apply { lowPass(5200.0, 0.85, sr) }, Biquad().apply { lowPass(6800.0, 0.6, sr) })
    }
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        if (chainL.isEmpty()) return
        for (i in 0 until n) {
            var l = left[i]; for (f in chainL) l = f.process(l); left[i] = l
            if (right != null) { var r = right[i]; for (f in chainR) r = f.process(r); right[i] = r }
        }
    }
    override fun reset() { chainL.forEach { it.reset() }; chainR.forEach { it.reset() } }
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
    override fun process(left: FloatArray, right: FloatArray?, n: Int) {
        val slope = (1.0 - 1.0 / ratio).toFloat()
        for (i in 0 until n) {
            val level = if (right != null) max(abs(left[i]), abs(right[i])) else abs(left[i])
            env = ftz(if (level > env) level + (env - level) * attack else level + (env - level) * release)
            // The log-domain gain computer runs every 8 samples; the gain itself is smoothed per sample.
            if (i and 7 == 0) {
                val db = if (env > 1e-6f) (20.0 * log10(env.toDouble())).toFloat() else -120f
                val reduction = if (db > threshold) (db - threshold) * slope else 0f
                gainTarget = Dsp.dbToGain(-reduction.toDouble()).toFloat() * makeup
            }
            gain += (gainTarget - gain) * 0.2f
            left[i] *= gain; if (right != null) right[i] *= gain
        }
    }
    override fun reset() { env = 0f; gain = 1f; gainTarget = 1f }
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
    override fun reset() { bufL.fill(0f); bufR.fill(0f) }
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
    override fun reset() { (combsL + combsR + apL + apR).forEach { it.fill(0f) }; combStoreL.fill(0f); combStoreR.fill(0f); inputHp.reset() }
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
            val needed = if (peak * gain > ceiling) ceiling / peak else 1f
            gain = if (needed < gain) needed else needed + (gain - needed) * release
            left[i] = Dsp.softClip(left[i] * gain * 1.05f) * ceiling
            if (right != null) right[i] = Dsp.softClip(right[i] * gain * 1.05f) * ceiling
        }
    }
    override fun reset() { gain = 1f }
}
