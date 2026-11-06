package dev.riffworkbench.synth

import dev.riffworkbench.model.AttackKind
import dev.riffworkbench.model.HarmonicKind
import dev.riffworkbench.model.ScheduledNote
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Timbre parameters of a plucked-string model; chosen by the tone preset, not by the score. */
internal class StringTone(
    /** One-pole loop low-pass coefficient for an open, normally picked note (higher = darker). */
    val brightness: Float,
    /** Decay to -60 dB for a sustained note at 110 Hz; scales with pitch. */
    val sustainSeconds: Double,
    /** Relative pick position for the excitation comb (0.1 = near the bridge). */
    val pickPosition: Double,
    /** Excitation low-pass coefficient (higher = softer pick). */
    val excitationSoftness: Float,
    val pickNoise: Float,
    val level: Float,
)

/**
 * Extended Karplus-Strong string with fractional, modulated delay. Each instance is one physical string
 * sounding one note, so bends and slides are per-string by construction. Palm mute, dead notes, release
 * and harmonics change damping and excitation, not just volume.
 */
internal class StringVoice(private val sampleRate: Int) {
    private val size = 8192
    private val mask = size - 1
    private val buffer = FloatArray(size)
    private var write = 0
    private val noise = Noise()
    private val dc = DcBlocker()
    private var loopState = 0f

    var active = false; private set
    var track = -1; private set
    var string = -1; private set
    var startClock = 0L; private set
    var endClock = Long.MAX_VALUE
    var releasing = false; private set

    private var tone: StringTone? = null
    private var baseMidi = 60.0
    private var brightness = 0.3f
    private var loopGain = 0.99f
    private var targetBrightness = 0.3f
    private var targetGain = 0.99f
    private var delay = 100f
    private var level = 0.5f
    private var age = 0L
    private val curveFrames = LongArray(64)
    private val curveCents = FloatArray(64)
    private var curveCount = 0
    private var vibratoDepth = 0f
    private var vibratoRate = 0f
    private var vibratoStart = 0L
    private var pickNoiseLeft = 0
    private var pickNoiseLevel = 0f
    private var injectLeft = 0
    private var injectLevel = 0f
    private var palmMute = false
    private var dead = false
    private var harmonic: HarmonicKind? = null
    private var silentRun = 0
    private var fadeOut = 1f
    private var fadeStep = 0f
    var pan = 0f; private set
    /** Last rendered output, for the engine's de-click when the voice is stolen. */
    var lastOut = 0f; private set
    // Delay interpolation across the current 16-frame control tick (ticks follow the voice's age, not the render calls).
    private var tickD0 = 100f
    private var tickD1 = 100f

    fun start(note: ScheduledNote, track: Int, tone: StringTone, clock: Long, endClock: Long, seed: Int) {
        this.tone = tone
        this.track = track
        string = note.string ?: -1
        startClock = clock
        this.endClock = endClock
        active = true; releasing = false; silentRun = 0; fadeOut = 1f; fadeStep = 0f
        age = 0; lastOut = 0f
        noise.reseed(seed)
        dc.reset(); loopState = 0f
        buffer.fill(0f)
        write = 0
        pan = 0f
        configure(note, tone)
        val hz = Dsp.midiToHz(baseMidi + curveAt(0) / 100.0)
        delay = delayFor(hz)
        excite(hz, note.velocity, note.attack, fresh = true)
    }

    /** Hammer-on, pull-off, legato slide, trill: the string keeps vibrating at a new pitch. */
    fun legato(note: ScheduledNote, clock: Long, endClock: Long) {
        val tone = tone ?: return
        this.endClock = endClock
        releasing = false; fadeOut = 1f; fadeStep = 0f; silentRun = 0
        age = 0
        configure(note, tone)
        val hz = Dsp.midiToHz(baseMidi + curveAt(0) / 100.0)
        delay = delayFor(hz)
        injectLeft = delay.toInt().coerceIn(8, size / 2)
        injectLevel = 0.18f * (note.velocity / 127f)
        startClock = clock
    }

    private fun configure(note: ScheduledNote, tone: StringTone) {
        baseMidi = note.soundingPitch
        palmMute = note.palmMute
        dead = note.dead
        harmonic = note.harmonic
        level = tone.level * velocityGain(note.velocity)
        curveCount = 0
        for (i in 0 until minOf(note.curve.size, curveFrames.size)) { curveFrames[i] = note.curve[i].frame; curveCents[i] = note.curve[i].cents.toFloat(); curveCount++ }
        val v = note.vibrato
        if (v != null) { vibratoDepth = v.depthCents.toFloat(); vibratoRate = v.rateHz.toFloat(); vibratoStart = v.startFrame } else { vibratoDepth = 0f }
        val hz = Dsp.midiToHz(baseMidi)
        val sustain = tone.sustainSeconds * (110.0 / hz).let { if (it > 1) sqrt(it) else it.pow(0.6) }
        val t60 = when {
            dead -> 0.035
            palmMute -> 0.22 * (110.0 / hz).coerceIn(0.5, 2.0)
            harmonic != null -> sustain * 1.4
            else -> sustain
        }
        targetBrightness = when {
            dead -> 0.75f
            palmMute -> (tone.brightness + 0.42f).coerceAtMost(0.9f)
            // A natural harmonic is a pure, bell-like overtone (its own upper partials die first); pinched and artificial
            // harmonics keep the bright loop of the picked string (QA cycle 19).
            harmonic == HarmonicKind.NATURAL -> tone.brightness
            harmonic != null -> (tone.brightness * 0.5f)
            else -> tone.brightness
        }
        targetGain = Dsp.loopGainForT60(t60, hz)
        brightness = targetBrightness
        loopGain = targetGain
    }

    private fun velocityGain(velocity: Int): Float { val v = velocity.coerceIn(1, 127) / 127f; return v * sqrt(v) }

    private fun excite(hz: Double, velocity: Int, attack: AttackKind, fresh: Boolean) {
        val tone = tone ?: return
        val period = delay.toInt().coerceIn(4, size / 2)
        val v = velocity.coerceIn(1, 127) / 127f
        var soft = tone.excitationSoftness + (1f - v) * 0.25f
        if (attack == AttackKind.TAP || attack == AttackKind.LEGATO || attack == AttackKind.GRACE) soft += 0.15f
        if (attack == AttackKind.SLAP || attack == AttackKind.POP) soft -= 0.2f
        if (harmonic != null && harmonic != HarmonicKind.PINCH) soft = maxOf(soft, 0.82f)
        if (palmMute) soft += 0.1f
        soft = soft.coerceIn(0f, 0.95f)
        val pick = (period * tone.pickPosition).toInt().coerceIn(1, period - 1)
        var lp = 0f
        val start = (write - period) and mask
        // Raw burst, then pick-position comb (difference with a delayed copy), then softness low-pass.
        if (harmonic == HarmonicKind.NATURAL) {
            // Natural harmonic (QA cycle 19): the string touched at the node vibrates in that overtone's series with the
            // partial amplitudes of a pluck (~1/k^2): a triangle with its apex at 30 % of the period plus a little noise,
            // so the overtone itself is the strongest partial (a noise burst made its 3rd-6th partials dominate).
            val apex = 0.3 * period
            for (i in 0 until period) buffer[(start + i) and mask] = (if (i < apex) i / apex else (period - i) / (period - apex)).toFloat() + 0.04f * noise.next()
        } else {
            for (i in 0 until period) buffer[(start + i) and mask] = noise.next()
            for (i in period - 1 downTo pick) { val idx = (start + i) and mask; buffer[idx] = buffer[idx] - buffer[(start + i - pick) and mask] }
        }
        var mean = 0f
        for (i in 0 until period) { val idx = (start + i) and mask; lp = (1f - soft) * buffer[idx] + soft * lp; buffer[idx] = lp; mean += lp }
        mean /= period
        var peak = 1e-6f
        for (i in 0 until period) { val idx = (start + i) and mask; buffer[idx] -= mean; peak = maxOf(peak, abs(buffer[idx])) }
        val scale = 0.9f / peak
        for (i in 0 until period) buffer[(start + i) and mask] *= scale
        pickNoiseLeft = if (fresh && attack != AttackKind.LEGATO) (0.0025 * sampleRate).toInt() else 0
        pickNoiseLevel = tone.pickNoise * v * if (dead) 2.2f else if (harmonic == HarmonicKind.PINCH) 3f else 1f
        if (attack == AttackKind.SLAP) pickNoiseLevel *= 2f
    }

    private fun delayFor(hz: Double): Float {
        val a = brightness.toDouble()
        val filterDelay = a / (1.0 - a)
        return (sampleRate / hz - filterDelay).toFloat().coerceIn(2.5f, (size - 8).toFloat())
    }

    private fun curveAt(frame: Long): Double {
        if (curveCount == 0) return 0.0
        if (frame <= curveFrames[0]) return curveCents[0].toDouble()
        for (i in 1 until curveCount) if (frame <= curveFrames[i]) {
            val span = (curveFrames[i] - curveFrames[i - 1]).toDouble()
            return if (span <= 0) curveCents[i].toDouble() else curveCents[i - 1] + (frame - curveFrames[i - 1]) / span * (curveCents[i] - curveCents[i - 1])
        }
        return curveCents[curveCount - 1].toDouble()
    }

    private fun pitchCents(frame: Long): Double {
        var cents = curveAt(frame)
        if (vibratoDepth > 0f && frame > vibratoStart) {
            val t = (frame - vibratoStart).toDouble() / sampleRate
            val fadeIn = (t / 0.15).coerceAtMost(1.0)
            cents += vibratoDepth * fadeIn * sin(Dsp.TWO_PI * vibratoRate * t)
        }
        return cents
    }

    fun release(fast: Boolean) {
        if (!active) return
        releasing = true
        val hz = Dsp.midiToHz(baseMidi)
        targetGain = Dsp.loopGainForT60(if (fast) 0.02 else if (palmMute || dead) 0.04 else 0.09, hz)
        targetBrightness = maxOf(brightness, 0.7f)
        if (fast) fadeStep = 1f / (0.008f * sampleRate)
    }

    fun kill() { active = false; releasing = false }

    /** Adds [count] samples into [out] starting at [offset]; [clock] is the engine clock of the first sample. */
    fun render(out: FloatArray, offset: Int, count: Int, clock: Long) {
        if (!active) return
        var i = 0
        while (i < count) {
            // Control updates run every 16 frames of the voice's own age, so the result does not depend on how calls are split.
            val phase = (age and 15).toInt()
            if (phase == 0) {
                val now = clock + i
                if (!releasing && now >= endClock) release(false)
                // Smooth damping changes per tick (release, choke) to avoid clicks.
                brightness += (targetBrightness - brightness) * 0.25f
                loopGain += (targetGain - loopGain) * 0.25f
                val hz1 = Dsp.midiToHz(baseMidi + pitchCents(age + 16) / 100.0)
                tickD0 = delay
                tickD1 = delayFor(hz1)
            }
            val chunk = minOf(16 - phase, count - i)
            val d0 = tickD0
            val d1 = tickD1
            val g = loopGain
            val a = brightness
            var peak = 0f
            for (k in 0 until chunk) {
                val d = d0 + (d1 - d0) * (phase + k + 1) / 16
                val pos = write - d
                val base = floor(pos).toInt()
                val frac = pos - base
                val y0 = buffer[(base - 1) and mask]; val y1 = buffer[base and mask]; val y2 = buffer[(base + 1) and mask]; val y3 = buffer[(base + 2) and mask]
                val c1 = 0.5f * (y2 - y0)
                val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
                val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
                val sample = ((c3 * frac + c2) * frac + c1) * frac + y1
                loopState = ftz((1f - a) * sample + a * loopState)
                var feed = loopState * g
                if (injectLeft > 0) { feed += noise.next() * injectLevel; injectLeft-- }
                buffer[write] = feed
                write = (write + 1) and mask
                var outSample = dc.process(sample)
                if (pickNoiseLeft > 0) { outSample += noise.next() * pickNoiseLevel; pickNoiseLeft-- }
                if (fadeStep > 0f) { fadeOut -= fadeStep; if (fadeOut <= 0f) { fadeOut = 0f } }
                val v = outSample * level * fadeOut
                lastOut = v
                out[offset + i + k] += v
                val av = abs(v); if (av > peak) peak = av
            }
            if (phase + chunk == 16) delay = d1
            age += chunk
            i += chunk
            if (releasing && (peak < 2e-5f || fadeOut <= 0f)) { silentRun += chunk; if (silentRun > 256 || fadeOut <= 0f) { active = false; return } } else silentRun = 0
        }
    }
}

