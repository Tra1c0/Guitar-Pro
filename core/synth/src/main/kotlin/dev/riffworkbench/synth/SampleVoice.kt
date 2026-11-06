package dev.riffworkbench.synth

import dev.riffworkbench.model.AttackKind
import dev.riffworkbench.model.ScheduledNote
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One string of a sampled electric guitar (DI). Plays the round robin of the nearest root at the note's velocity
 * layer, resampled per sample with cubic interpolation, so bends, slides and vibrato follow the note's own pitch
 * curve. Long notes continue in the sample's crossfaded loop with its measured natural decay. Palm mute and dead
 * notes damp the string (low-pass and short decay, real muted samples for dead notes); note-off adds the recorded
 * string-damping noise. Same contract as [StringVoice]: no allocation after construction.
 */
internal class SampleVoice(private val sampleRate: Int) {
    var active = false; private set
    var track = -1; private set
    var string = -1; private set
    var startClock = 0L; private set
    var endClock = Long.MAX_VALUE
    var releasing = false; private set
    val pan = 0f
    /** Last rendered output, for the engine's de-click when the voice is stolen. */
    var lastOut = 0f; private set
    // Pitch ratio across the current 32-frame control tick (ticks follow the voice's age, not the render calls).
    private var tickRatio0 = 1.0
    private var tickRatio1 = 1.0

    private var bank: SampleBank? = null
    private var region: SampleBank.Region? = null
    private var pos = 0.0
    private var looped = false
    private var baseRatio = 1.0
    private var level = 0.5f
    private var env = 1f
    private var envDecay = 1f
    private var loopDecay = 1f
    private var fade = 1f
    private var fadeStep = 0f
    private var age = 0L
    /** Output frames since the last pick (a legato note continues the same string, so it does not reset this). */
    private var sinceAttack = 0L
    private var baseMidi = 60.0
    private var rootMidi = 60
    /** The region's measured pitch offset (cents above its root); the ratio removes it so the note lands on its pitch. */
    private var fineTune = 0.0
    /** The region's level normalisation (SampleBank.Region.gain), separate from the velocity level so release noises keep their own. */
    private var regionGain = 1f
    // Crossfade from the previous sample on legato (hammer-on, pull-off, legato slide).
    private var oldRegion: SampleBank.Region? = null
    private var oldPos = 0.0
    private var oldRatio = 1.0
    private var oldGain = 0f
    private var xfade = 0f
    private val xfadeStep = 1f / (0.006f * sampleRate)
    // Release noise played on note-off.
    private var relRegion: SampleBank.Region? = null
    private var relPos = 0.0
    private var relGain = 0f
    // Palm-mute / damping low-pass (two one-pole stages) with its own envelope.
    private var damp = false
    private var lpA = 0f
    /** Palm-mute low-pass cutoff (Hz): opens at [lpCutStart] for the pick, then glides to [lpCutEnd] (QA cycle 19). */
    private var lpCut = 2600.0
    private var lpCutEnd = 2600.0
    private val lpGlide = exp(-32.0 / (0.018 * sampleRate))
    /**
     * Harmonic comb (pinch / artificial harmonics, QA cycle 19): the thumb or finger at the node leaves only the partials
     * that are multiples of [combN] (1 = off). The voice plays the fretted note and sums [combN] copies spaced a 1/[combN]
     * period apart, which cancels every other partial exactly; pick attack, string noise and decay stay the recorded ones.
     */
    private var combN = 1
    private var combGain = 0f
    private var combBleed = 0f
    /** Share of the full (uncombed) note right after the pick; the thumb/finger reaches the node within a few ms. */
    private var combOnset = 0f
    private val combOnsetDecay = exp(-1.0 / (0.006 * sampleRate)).toFloat()
    private val combBuf = FloatArray(2048)
    private var combPos = 0
    private var combPeriod = 100.0
    private var rootHz = 110.0
    private var lp1 = 0f
    private var lp2 = 0f
    private val curveFrames = LongArray(64)
    private val curveCents = FloatArray(64)
    private var curveCount = 0
    private var vibratoDepth = 0f
    private var vibratoRate = 0f
    private var vibratoStart = 0L
    private var dead = false
    private var rr = 0
    private var silentRun = 0

    /**
     * [harmonic] > 1 plays a harmonic of the fretted note: the note's sounding pitch is that overtone, the voice plays the
     * fretted pitch below it through the harmonic comb, scaled by [combGain] with [combBleed] of the full note mixed in.
     */
    fun start(note: ScheduledNote, track: Int, bank: SampleBank, clock: Long, endClock: Long, roundRobin: Int, gain: Float,
              harmonic: Int = 1, combGain: Float = 1f, combBleed: Float = 0f) {
        this.bank = bank
        this.track = track
        string = note.string ?: -1
        startClock = clock
        this.endClock = endClock
        active = true; releasing = false; fade = 1f; fadeStep = 0f; silentRun = 0
        age = 0; sinceAttack = 0; lastOut = 0f
        rr = roundRobin
        oldRegion = null; xfade = 0f; relRegion = null
        lp1 = 0f; lp2 = 0f
        configure(note, gain)
        combN = if (note.dead) 1 else harmonic.coerceIn(1, 8)
        this.combGain = combGain; this.combBleed = combBleed
        if (combN > 1) { baseMidi -= 12.0 * ln(combN.toDouble()) / ln(2.0); combBuf.fill(0f); combPos = 0; combOnset = 1f - combBleed }
        val key = Math_round(baseMidi)
        dead = note.dead
        region = if (dead && bank.deadNotes.isNotEmpty()) bank.deadNotes[(roundRobin + key) % bank.deadNotes.size] else bank.note(key, layerFor(note.velocity, bank.layers), roundRobin)
        rootMidi = region!!.root
        fineTune = region!!.fineTuneCents.toDouble()
        regionGain = region!!.gain
        rootHz = 440.0 * Dsp_pow2((rootMidi + fineTune / 100.0 - 69.0) / 12.0)
        // Slight start offset per round robin and per string keeps fast repetitions and unisons on two strings from being
        // the identical waveform (the engine also seeds the round robin by string).
        pos = (roundRobin % 3) * 0.0004 * bank.sampleRate + (if (string >= 0) string * 0.00015 * bank.sampleRate else 0.0)
        looped = false; loopDecay = 1f
        baseRatio = bank.sampleRate.toDouble() / sampleRate * (if (dead) Dsp_pow2((note.soundingPitch - 52.0) / 12.0 * 0.35) else 1.0)
        if (note.attack == AttackKind.TAP || note.attack == AttackKind.GRACE) { pos += 0.012 * bank.sampleRate; level *= 0.8f }
    }

    /** Hammer-on, pull-off, legato slide, trill: the next note continues without a new pick attack. */
    fun legato(note: ScheduledNote, clock: Long, endClock: Long, gain: Float) {
        val bank = bank ?: return
        val current = region ?: return
        oldRegion = current; oldPos = pos; oldRatio = ratioAt(0); oldGain = level * regionGain * env * fade; xfade = 1f
        val stringEnv = env
        this.endClock = endClock
        releasing = false; fade = 1f; fadeStep = 0f; silentRun = 0; relRegion = null
        configure(note, gain)
        combN = 1
        level *= 0.72f
        val key = Math_round(note.soundingPitch)
        val next = bank.note(key, layerFor(note.velocity, bank.layers), rr + 1)
        region = next
        rootMidi = next.root
        fineTune = next.fineTuneCents.toDouble()
        regionGain = next.gain
        // No new pick (QA cycle 22): the string keeps vibrating, so the new note continues at the age of the vibration since
        // the last pick - its brightness and level are those of the ringing string, not of a fresh attack. Starting 30 ms into
        // the new sample put a bright, loud early attack on every hammer-on (dry: as much energy above 3 kHz in the first 8 ms
        // as a picked change). The envelope (palm-mute damping, loop decay) carries over.
        val bankFrames = sinceAttack.toDouble() * bank.sampleRate / sampleRate
        pos = bankFrames.coerceIn(0.03 * bank.sampleRate, (if (next.loopEnd > 0) next.loopEnd - 4 else next.frames - 4).toDouble().coerceAtLeast(0.0))
        looped = false; loopDecay = 1f
        env = stringEnv
        age = 0
        startClock = clock
    }

    private fun configure(note: ScheduledNote, gain: Float) {
        baseMidi = note.soundingPitch
        damp = note.palmMute || note.dead
        val v = note.velocity.coerceIn(1, 127) / 127f
        // The layers are loudness-matched by the bank normalisation, so the dynamics come from this law alone (v40 = -13 dB re v127).
        level = gain * v.pow(1.3f)
        env = 1f
        // Palm mute: the hand damps the string within ~0.25 s and takes the top end off; darker when played softer.
        val t60 = when { note.dead -> 0.09; note.palmMute -> 0.32 * (110.0 / Dsp.midiToHz(note.soundingPitch)).coerceIn(0.6, 1.6); else -> 0.0 }
        envDecay = if (t60 > 0) exp(-6.9078 / (t60 * sampleRate)).toFloat() else 1f
        // Palm mute (QA cycle 19): the pick still sounds bright, then within ~20 ms the hand leaves only the lowest partials;
        // the cutoff follows the note (about the 3rd-6th partial), so the mute is darker than the open note at every pitch.
        if (note.palmMute && !note.dead) {
            lpCut = 2000.0 + 1500.0 * v
            lpCutEnd = (Dsp.midiToHz(note.soundingPitch) * (2.0 + 3.0 * v)).coerceIn(250.0, 1200.0)
        } else { lpCut = 2600.0; lpCutEnd = 2600.0 }
        lpA = exp(-2 * Math.PI * lpCut / sampleRate).toFloat()
        curveCount = 0
        for (i in 0 until minOf(note.curve.size, curveFrames.size)) { curveFrames[i] = note.curve[i].frame; curveCents[i] = note.curve[i].cents.toFloat(); curveCount++ }
        val vib = note.vibrato
        if (vib != null) { vibratoDepth = vib.depthCents.toFloat(); vibratoRate = vib.rateHz.toFloat(); vibratoStart = vib.startFrame } else vibratoDepth = 0f
    }

    private fun layerFor(velocity: Int, layers: Int) = when {
        layers <= 1 -> 0
        velocity <= 64 -> 0
        velocity <= 100 -> minOf(1, layers - 1)
        else -> layers - 1
    }

    fun release(fast: Boolean) {
        if (!active || releasing) { if (fast && active) fadeStep = maxOf(fadeStep, 1f / (0.006f * sampleRate)); return }
        releasing = true
        fadeStep = 1f / ((if (fast) 0.006f else if (damp) 0.03f else 0.07f) * sampleRate)
        if (!fast && !dead) {
            val b = bank ?: return
            relRegion = b.release(Math_round(baseMidi), rr)
            relPos = 0.0
            relGain = level * env * 0.55f * (relRegion?.gain ?: 1f)
        }
    }

    fun kill() { active = false; releasing = false; region = null; oldRegion = null; relRegion = null }

    private fun curveAt(frame: Long): Double {
        if (curveCount == 0) return 0.0
        if (frame <= curveFrames[0]) return curveCents[0].toDouble()
        for (i in 1 until curveCount) if (frame <= curveFrames[i]) {
            val span = (curveFrames[i] - curveFrames[i - 1]).toDouble()
            return if (span <= 0) curveCents[i].toDouble() else curveCents[i - 1] + (frame - curveFrames[i - 1]) / span * (curveCents[i] - curveCents[i - 1])
        }
        return curveCents[curveCount - 1].toDouble()
    }

    private fun ratioAt(frame: Long): Double {
        var cents = curveAt(frame)
        if (vibratoDepth > 0f && frame > vibratoStart) {
            val t = (frame - vibratoStart).toDouble() / sampleRate
            cents += vibratoDepth * (t / 0.15).coerceAtMost(1.0) * sin(Dsp.TWO_PI * vibratoRate * t)
        }
        // Bounded step: a hostile pitch must not push the read position to magnitudes where the loop wrap stops working.
        return (baseRatio * Dsp_pow2((baseMidi - rootMidi - fineTune / 100.0 + cents / 100.0) / 12.0)).coerceIn(0.0, 64.0)
    }

    /** Adds [count] samples into [out] from [offset]; [clock] is the engine clock of the first sample. */
    fun render(out: FloatArray, offset: Int, count: Int, clock: Long) {
        if (!active) return
        val r = region ?: run { active = false; return }
        val data = r.data
        var i = 0
        while (i < count) {
            // Control updates run every 32 frames of the voice's own age, so the result does not depend on how calls are split.
            val phase = (age and 31).toInt()
            if (phase == 0) {
                if (!releasing && clock + i >= endClock) release(false)
                tickRatio0 = ratioAt(age)
                tickRatio1 = ratioAt(age + 32)
                if (damp && lpCut != lpCutEnd) {
                    lpCut = lpCutEnd + (lpCut - lpCutEnd) * lpGlide
                    if (abs(lpCut - lpCutEnd) < 1.0) lpCut = lpCutEnd
                    lpA = exp(-2 * Math.PI * lpCut / sampleRate).toFloat()
                }
                // One period of the played (fretted) note in output frames, following bends and vibrato.
                if (combN > 1 && tickRatio0 > 0.0) combPeriod = ((bank?.sampleRate ?: sampleRate) / (rootHz * tickRatio0)).coerceIn(4.0, (combBuf.size - 4).toDouble() * combN / (combN - 1))
            }
            val chunk = minOf(32 - phase, count - i)
            val ratio0 = tickRatio0
            val ratio1 = tickRatio1
            var peak = 0f
            for (k in 0 until chunk) {
                val ratio = ratio0 + (ratio1 - ratio0) * (phase + k) / 32
                var s = 0f
                if (r.loopEnd > 0 && pos >= r.loopEnd) { pos = r.loopStart + (pos - r.loopStart) % (r.loopEnd - r.loopStart); looped = true; if (loopDecay == 1f) loopDecay = exp(-ln(10.0) * r.decayDbPerSec / 20.0 / sampleRate).toFloat() }
                if (r.loopEnd > 0) s = cubicLoop(data, pos, r.loopStart, r.loopEnd) else if (pos < r.frames - 2) s = cubic(data, pos) else fade = 0f
                pos += ratio
                if (looped) env *= loopDecay
                // Legato crossfade (QA cycle 22): the old vibration fades out while the new one fades in from the ringing string.
                // The new region used to start at full level mid-cycle (a step, i.e. a broadband click on every hammer-on).
                var oldPart = 0f
                var newShare = 1f
                val crossfading = xfade > 0f
                if (crossfading) {
                    val o = oldRegion
                    if (o != null) {
                        if (o.loopEnd > 0 && oldPos >= o.loopEnd) oldPos = o.loopStart + (oldPos - o.loopStart) % (o.loopEnd - o.loopStart)
                        if (o.loopEnd > 0) oldPart = cubicLoop(o.data, oldPos, o.loopStart, o.loopEnd) * oldGain * xfade
                        else if (oldPos < o.frames - 2) oldPart = cubic(o.data, oldPos) * oldGain * xfade
                        oldPos += oldRatio
                    }
                    newShare = 1f - xfade
                    xfade -= xfadeStep
                    if (xfade <= 0f) { xfade = 0f; oldRegion = null }
                }
                if (damp) {
                    env *= envDecay
                    // While crossfading, the old vibration goes through the same damping low-pass as the new one.
                    val g = level * regionGain * env
                    val input = if (crossfading) s * newShare + (if (g > 1e-9f) oldPart / g else 0f) else s
                    lp1 = ftz(lp1 + (1f - lpA) * (input - lp1)); lp2 = ftz(lp2 + (1f - lpA) * (lp1 - lp2)); s = lp2
                }
                var y = s * level * regionGain * env
                if (crossfading && !damp) y = y * newShare + oldPart
                if (fadeStep > 0f) { fade -= fadeStep; if (fade < 0f) fade = 0f }
                y *= fade
                if (combN > 1) y = comb(y)
                val rel = relRegion
                if (rel != null) {
                    if (relPos < rel.frames - 2) { y += cubic(rel.data, relPos) * relGain; relPos += baseRatio } else relRegion = null
                }
                lastOut = y
                out[offset + i + k] += y
                val a = abs(y); if (a > peak) peak = a
            }
            age += chunk
            sinceAttack += chunk
            i += chunk
            val done = (fade <= 0f && relRegion == null) || (env < 1e-4f && relRegion == null)
            if (done) { silentRun += chunk; if (silentRun > 64) { active = false; region = null; oldRegion = null; return } } else silentRun = 0
            if (releasing && peak < 1e-6f && relRegion == null) { active = false; region = null; return }
        }
    }

    /**
     * Harmonic comb: mean of [combN] copies spaced [combPeriod]/[combN] apart, times [combGain], plus the full note: all of
     * it at the pick, fading (6 ms) to [combBleed] as the thumb or finger settles on the node.
     */
    private fun comb(x: Float): Float {
        val size = combBuf.size
        combBuf[combPos] = x
        var sum = x
        val step = combPeriod / combN
        for (m in 1 until combN) {
            val d = step * m
            val di = floor(d).toInt(); val f = (d - di).toFloat()
            val a = combBuf[(combPos - di + size) % size]; val b = combBuf[(combPos - di - 1 + 2 * size) % size]
            sum += a + (b - a) * f
        }
        combPos = (combPos + 1) % size
        val full = combBleed + combOnset
        combOnset = ftz(combOnset * combOnsetDecay)
        return ftz(sum / combN * combGain + x * full)
    }

    private fun cubic(d: ShortArray, p: Double): Float {
        val base = floor(p).toInt()
        val f = (p - base).toFloat()
        val y0 = d[if (base > 0) base - 1 else 0].toFloat(); val y1 = d[base].toFloat(); val y2 = d[base + 1].toFloat(); val y3 = d[minOf(base + 2, d.size - 1)].toFloat()
        val c1 = 0.5f * (y2 - y0)
        val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
        return (((c3 * f + c2) * f + c1) * f + y1) * (1f / 32768f)
    }

    /** Cubic read inside a loop: indices past the loop end continue at the loop start, so the seam has no gap. */
    private fun cubicLoop(d: ShortArray, p: Double, loopStart: Int, loopEnd: Int): Float {
        val base = floor(p).toInt()
        val f = (p - base).toFloat()
        fun at(i: Int): Float { var j = i; while (j >= loopEnd) j -= loopEnd - loopStart; return d[j.coerceIn(0, d.size - 1)].toFloat() }
        val y0 = at(base - 1); val y1 = at(base); val y2 = at(base + 1); val y3 = at(base + 2)
        val c1 = 0.5f * (y2 - y0)
        val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
        return (((c3 * f + c2) * f + c1) * f + y1) * (1f / 32768f)
    }

    private fun Math_round(x: Double) = floor(x + 0.5).toInt()
    private fun Dsp_pow2(x: Double) = exp(x * 0.6931471805599453)
}
