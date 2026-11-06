package dev.riffworkbench.synth

import dev.riffworkbench.model.DrumStroke
import dev.riffworkbench.model.ScheduledNote
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Shared constant tables: voices start on the audio thread and must not allocate.
private val METAL_RATIOS = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)
private val ORGAN_RATIOS = doubleArrayOf(0.5, 1.0, 2.0, 3.0, 4.0)
private val ORGAN_AMPS = floatArrayOf(0.6f, 1f, 0.6f, 0.35f, 0.3f)

internal enum class DrumModel { KICK, SNARE, SIDESTICK, CLAP, TOM, HH_CLOSED, HH_PEDAL, HH_OPEN, CRASH, SPLASH, CHINA, RIDE, RIDE_BELL, COWBELL, TAMBOURINE, PERC }

/** Synthesized drum kit voice (GM note numbers). Own synthesis only, no samples. */
internal class DrumVoice(private val sampleRate: Int) {
    var active = false; private set
    var track = -1; private set
    var chokeGroup: Int? = null; private set
    var startClock = 0L; private set
    var pan = 0f; private set
    var model = DrumModel.PERC; private set
    var midi = -1; private set
    /** Last rendered output per side, for the engine's de-click when the voice is stolen. */
    var lastL = 0f; private set
    var lastR = 0f; private set
    private var t = 0
    private var gain = 0f
    private var phase = DoubleArray(6)
    private val freqs = DoubleArray(6)
    private val bp = Biquad(); private val hp = Biquad(); private val bp2 = Biquad()
    private val noise = Noise()
    private var pitchStart = 100.0; private var pitchEnd = 50.0
    private var pitchDecay = 0f; private var ampDecay = 0f; private var noiseDecay = 0f
    private var amp = 1f; private var pitchEnv = 1f; private var noiseEnv = 1f
    private var bodyLevel = 0f; private var noiseLevel = 0f; private var metalLevel = 0f; private var clickLeft = 0
    private var fade = 1f; private var fadeStep = 0f
    private var chokeAt = Long.MAX_VALUE

    fun start(note: ScheduledNote, track: Int, clock: Long, seed: Int) {
        this.track = track; startClock = clock; active = true; t = 0; fade = 1f; fadeStep = 0f; lastL = 0f; lastR = 0f
        chokeGroup = note.chokeGroup
        noise.reseed(seed)
        bp.reset(); hp.reset(); bp2.reset()
        for (i in phase.indices) phase[i] = 0.0
        val v = note.velocity.coerceIn(1, 127) / 127f
        // Kit trim (QA 19, measured at 48 kHz, factory mix, master before the limiter): 0.8 put a v96 groove with v127
        // accents at -0.4 dBFS (voice sum +8.3 dBFS into the drum compressor) and kept the master
        // limiter of a standard band busy 4.7 % of the time. 0.5 (-4.1 dB) with the preset compressor unchanged: groove
        // -4.5 dBFS peak for -1.4 dB RMS (less squash), every element <= -6 dBFS at v127, limiter idle (Qa19DrumLevelTest).
        gain = (v.toDouble().pow(1.3) * note.elementGain).toFloat() * 0.5f
        pan = note.elementPan.toFloat()
        amp = 1f; pitchEnv = 1f; noiseEnv = 1f; clickLeft = 0
        bodyLevel = 0f; noiseLevel = 0f; metalLevel = 0f
        val midi = note.soundingPitch.toInt()
        this.midi = midi
        model = when (midi) {
            35, 36 -> DrumModel.KICK; 37 -> DrumModel.SIDESTICK; 38, 40 -> DrumModel.SNARE; 39 -> DrumModel.CLAP
            41, 43, 45, 47, 48, 50 -> DrumModel.TOM; 42 -> DrumModel.HH_CLOSED; 44 -> DrumModel.HH_PEDAL; 46 -> DrumModel.HH_OPEN
            49, 57 -> DrumModel.CRASH; 55 -> DrumModel.SPLASH; 52 -> DrumModel.CHINA; 51, 59 -> DrumModel.RIDE; 53 -> DrumModel.RIDE_BELL
            56 -> DrumModel.COWBELL; 54 -> DrumModel.TAMBOURINE; else -> DrumModel.PERC
        }
        if (note.drumStroke == DrumStroke.CROSS_STICK) model = DrumModel.SIDESTICK
        val sr = sampleRate
        fun decay(seconds: Double) = Dsp.timeCoefficient(seconds, sr)
        val bright = 0.8 + 0.4 * v
        when (model) {
            DrumModel.KICK -> { pitchStart = if (midi == 35) 140.0 else 165.0; pitchEnd = if (midi == 35) 44.0 else 50.0; pitchDecay = decay(0.03); ampDecay = decay(0.2); bodyLevel = 1.15f; clickLeft = (0.004 * sr).toInt(); noiseLevel = 0.35f; noiseDecay = decay(0.006); hp.highPass(1800.0, 0.7, sr) }
            DrumModel.SNARE -> {
                val rim = note.drumStroke == DrumStroke.RIMSHOT
                pitchStart = 240.0; pitchEnd = 185.0; pitchDecay = decay(0.02); ampDecay = decay(if (rim) 0.11 else 0.085); bodyLevel = if (rim) 0.75f else 0.55f
                noiseLevel = if (rim) 0.95f else 0.75f; noiseDecay = decay(if (rim) 0.2 else 0.16); bp.bandPass(3800.0 * bright, 0.6, sr); hp.highPass(900.0, 0.7, sr)
            }
            DrumModel.SIDESTICK -> { pitchStart = 820.0; pitchEnd = 780.0; pitchDecay = decay(0.01); ampDecay = decay(0.018); bodyLevel = 0.5f; noiseLevel = 0.6f; noiseDecay = decay(0.03); bp.bandPass(1900.0, 2.0, sr); hp.highPass(500.0, 0.7, sr) }
            DrumModel.CLAP -> { noiseLevel = 0.9f; noiseDecay = decay(0.13); bp.bandPass(1250.0, 1.2, sr); hp.highPass(400.0, 0.7, sr) }
            DrumModel.TOM -> {
                val base = when (midi) { 41 -> 82.0; 43 -> 98.0; 45 -> 110.0; 47 -> 131.0; 48 -> 147.0; else -> 175.0 }
                pitchStart = base * 1.55; pitchEnd = base; pitchDecay = decay(0.05); ampDecay = decay(if (base < 100) 0.5 else 0.38); bodyLevel = 0.95f
                noiseLevel = 0.18f; noiseDecay = decay(0.03); bp.bandPass(base * 6, 1.0, sr); hp.highPass(200.0, 0.7, sr)
            }
            DrumModel.HH_CLOSED, DrumModel.HH_PEDAL, DrumModel.HH_OPEN -> {
                metal(1.0); metalLevel = if (model == DrumModel.HH_PEDAL) 0.38f else 0.55f; noiseLevel = 0.25f
                val d = when (model) { DrumModel.HH_CLOSED -> 0.05; DrumModel.HH_PEDAL -> 0.035; else -> 0.6 }
                ampDecay = decay(d); noiseDecay = decay(d); bp.bandPass(9500.0 * bright, 0.9, sr); hp.highPass(7000.0, 0.7, sr)
            }
            DrumModel.CRASH, DrumModel.SPLASH, DrumModel.CHINA, DrumModel.RIDE -> {
                metal(if (model == DrumModel.CHINA) 0.82 else if (midi == 57) 1.06 else 1.0)
                metalLevel = when (model) { DrumModel.RIDE -> 0.22f; DrumModel.CHINA -> 0.62f; else -> 0.48f }
                noiseLevel = if (model == DrumModel.RIDE) 0.12f else 0.4f
                val d = when (model) { DrumModel.CRASH -> 1.7; DrumModel.SPLASH -> 0.65; DrumModel.CHINA -> 1.3; else -> 2.4 }
                ampDecay = decay(d); noiseDecay = decay(d * 0.7)
                bp.bandPass(if (model == DrumModel.CHINA) 4800.0 else 7200.0 * bright, 0.55, sr); hp.highPass(if (model == DrumModel.RIDE) 2500.0 else 3600.0, 0.7, sr)
                if (model == DrumModel.RIDE) { clickLeft = (0.006 * sr).toInt(); bp2.bandPass(5200.0, 2.0, sr) }
            }
            DrumModel.RIDE_BELL -> { freqs[0] = 523.0; freqs[1] = 1236.0; freqs[2] = 1834.0; freqs[3] = 2460.0; bodyLevel = 0.45f; ampDecay = decay(1.3); noiseLevel = 0.05f; noiseDecay = decay(0.01); hp.highPass(300.0, 0.7, sr); bp.bandPass(3000.0, 0.7, sr) }
            DrumModel.COWBELL -> { freqs[0] = 540.0; freqs[1] = 800.0; metalLevel = 0.5f; ampDecay = decay(0.22); bp.bandPass(820.0, 2.5, sr); hp.highPass(300.0, 0.7, sr) }
            DrumModel.TAMBOURINE -> { metal(1.3); metalLevel = 0.3f; noiseLevel = 0.5f; ampDecay = decay(0.16); noiseDecay = decay(0.14); bp.bandPass(7000.0, 1.0, sr); hp.highPass(5000.0, 0.7, sr) }
            DrumModel.PERC -> { pitchStart = 200.0 + midi * 6; pitchEnd = pitchStart * 0.8; pitchDecay = decay(0.02); bodyLevel = 0.4f; ampDecay = decay(0.12); noiseLevel = 0.4f; noiseDecay = decay(0.08); bp.bandPass(1000.0 + midi * 40, 1.5, sr); hp.highPass(300.0, 0.7, sr) }
        }
        chokeAt = if (note.drumStroke == DrumStroke.CHOKE) clock + (0.12 * sr).toLong() else Long.MAX_VALUE
    }

    private fun metal(scale: Double) { for (i in 0 until 6) freqs[i] = METAL_RATIOS[i] * scale }

    fun choke() { if (active && fadeStep == 0f) fadeStep = 1f / (0.015f * sampleRate) }
    fun kill() { active = false }

    fun render(left: FloatArray, right: FloatArray, offset: Int, count: Int, clock: Long) {
        if (!active) return
        val gl = gain * (1f - pan.coerceAtLeast(0f) * 0.9f)
        val gr = gain * (1f + pan.coerceAtMost(0f) * 0.9f)
        val inv = 1.0 / sampleRate
        val sr = sampleRate.toDouble()
        for (k in 0 until count) {
            if (clock + k >= chokeAt) choke()
            var s = 0f
            if (bodyLevel > 0f) {
                if (model == DrumModel.RIDE_BELL) {
                    var bell = 0.0
                    for (i in 0 until 4) { phase[i] += freqs[i] * inv; if (phase[i] >= 1.0) phase[i] -= 1.0; bell += Dsp.sin01(phase[i]) / (i + 1) }
                    s += (bell * bodyLevel * amp).toFloat()
                } else {
                    val f = pitchEnd + (pitchStart - pitchEnd) * pitchEnv
                    phase[0] += f * inv; if (phase[0] >= 1.0) phase[0] -= 1.0
                    s += Dsp.sin01(phase[0]) * bodyLevel * amp
                    pitchEnv *= pitchDecay
                }
            }
            if (metalLevel > 0f) {
                var m = 0f
                val n = if (model == DrumModel.COWBELL) 2 else 6
                for (i in 0 until n) { phase[i] += freqs[i] * inv; if (phase[i] >= 1.0) phase[i] -= 1.0; m += if (phase[i] < 0.5) 1f else -1f }
                s += hp.process(bp.process(m / n)) * metalLevel * amp * 2.2f
            }
            if (noiseLevel > 0f) {
                var nz = noise.next()
                if (model == DrumModel.CLAP) { val ms = t * 1000.0 / sr; if (ms < 30 && (ms % 10.0) > 4.0) nz *= 0.15f }
                val filtered = if (metalLevel > 0f) bp2.process(nz) * 0.5f + nz * 0.35f else hp.process(bp.process(nz))
                s += filtered * noiseLevel * noiseEnv * 1.6f
                noiseEnv *= noiseDecay
            }
            if (clickLeft > 0) { s += hp.process(noise.next()) * 0.5f * amp; clickLeft-- }
            amp *= ampDecay
            if (fadeStep > 0f) { fade -= fadeStep; if (fade <= 0f) { active = false; return } }
            val out = s * fade
            lastL = out * gl; lastR = out * gr
            left[offset + k] += lastL
            right[offset + k] += lastR
            t++
            // Sample-exact end (QA cycle 24): checked once per render call, the voice stopped at the end of whatever block or
            // segment it was in, so the live path (256/192/480-frame blocks) and the export (1024) differed by up to 7.6e-5.
            if (amp < 1e-3f && (noiseLevel == 0f || noiseEnv < 1e-3f) && clickLeft == 0) { active = false; return }
        }
    }

    /** Drum heads damp their previous vibration when struck again; cymbals keep ringing. */
    val isHead: Boolean get() = model != DrumModel.CRASH && model != DrumModel.SPLASH && model != DrumModel.CHINA && model != DrumModel.RIDE && model != DrumModel.RIDE_BELL
}

internal enum class KeysMode { PIANO, EPIANO, ORGAN, PAD, LEAD }

/** Simple additive/FM keys and pads for non-fretted tracks. */
internal class KeysVoice(private val sampleRate: Int) {
    var active = false; private set
    var track = -1; private set
    var midi = -1; private set
    var startClock = 0L; private set
    var endClock = Long.MAX_VALUE
    /** Last rendered output, for the engine's de-click when the voice is stolen. */
    var lastOut = 0f; private set
    private var mode = KeysMode.PIANO
    private val phase = DoubleArray(8)
    private val freq = DoubleArray(8)
    private val partialAmp = FloatArray(8)
    private val partialDecay = FloatArray(8)
    private val partialEnv = FloatArray(8)
    private var partials = 0
    private var gain = 0f
    private var env = 0f
    private var attackStep = 1f
    private var release = 1f
    private var releasing = false
    private var releaseCoeff = 0.999f
    private val lp = Biquad()
    private var t = 0L
    private var baseHz = 440.0

    fun start(note: ScheduledNote, track: Int, mode: KeysMode, clock: Long, endClock: Long) {
        this.track = track; this.mode = mode; startClock = clock; this.endClock = endClock
        midi = note.soundingPitch.toInt(); active = true; releasing = false; release = 1f; t = 0; lastOut = 0f
        baseHz = Dsp.midiToHz(note.soundingPitch)
        val v = note.velocity.coerceIn(1, 127) / 127f
        gain = v * sqrt(v) * 0.32f
        for (i in phase.indices) { phase[i] = 0.0; partialEnv[i] = 1f }
        val sr = sampleRate
        when (mode) {
            KeysMode.PIANO -> {
                partials = 8
                val tau = 2.6 * sqrt(220.0 / baseHz).coerceIn(0.3, 3.0)
                for (n in 1..8) {
                    freq[n - 1] = baseHz * n * sqrt(1 + 0.0004 * n * n)
                    partialAmp[n - 1] = (1.0 / n.toDouble().pow(1.1 + (1 - v) * 0.6)).toFloat()
                    partialDecay[n - 1] = Dsp.timeCoefficient(tau / n.toDouble().pow(0.7), sr)
                }
                env = 1f; attackStep = 1f; releaseCoeff = Dsp.timeCoefficient(0.12, sr)
            }
            KeysMode.EPIANO -> {
                partials = 3
                freq[0] = baseHz; freq[1] = baseHz * 2; freq[2] = baseHz * 14
                partialAmp[0] = 1f; partialAmp[1] = 0.3f * v; partialAmp[2] = 0.08f * v
                partialDecay[0] = Dsp.timeCoefficient(2.2, sr); partialDecay[1] = Dsp.timeCoefficient(0.6, sr); partialDecay[2] = Dsp.timeCoefficient(0.05, sr)
                env = 1f; attackStep = 1f; releaseCoeff = Dsp.timeCoefficient(0.18, sr)
            }
            KeysMode.ORGAN -> {
                partials = 5
                for (i in 0 until 5) { freq[i] = baseHz * ORGAN_RATIOS[i]; partialAmp[i] = ORGAN_AMPS[i]; partialDecay[i] = 1f }
                env = 0f; attackStep = 1f / (0.006f * sr); releaseCoeff = Dsp.timeCoefficient(0.04, sr)
            }
            KeysMode.PAD, KeysMode.LEAD -> {
                partials = 3
                freq[0] = baseHz * 0.997; freq[1] = baseHz * 1.003; freq[2] = baseHz * (if (mode == KeysMode.LEAD) 2.0 else 0.5)
                for (i in 0 until 3) { partialAmp[i] = if (i == 2) 0.35f else 0.6f; partialDecay[i] = 1f }
                env = 0f; attackStep = 1f / ((if (mode == KeysMode.PAD) 0.25f else 0.01f) * sr)
                releaseCoeff = Dsp.timeCoefficient(if (mode == KeysMode.PAD) 0.5 else 0.08, sr)
                lp.reset(); lp.lowPass(if (mode == KeysMode.PAD) 1800.0 else 3200.0, 0.8, sr)
            }
        }
    }

    fun release(fast: Boolean) { if (!releasing) { releasing = true; if (fast) releaseCoeff = Dsp.timeCoefficient(0.008, sampleRate) } }
    fun kill() { active = false }

    fun render(out: FloatArray, offset: Int, count: Int, clock: Long) {
        if (!active) return
        val sr = sampleRate.toDouble()
        for (k in 0 until count) {
            if (!releasing && clock + k >= endClock) release(false)
            if (env < 1f && !releasing) env = (env + attackStep).coerceAtMost(1f)
            var s = 0.0
            for (i in 0 until partials) {
                phase[i] += freq[i] / sr; if (phase[i] >= 1.0) phase[i] -= 1.0
                val wave = if (mode == KeysMode.PAD || mode == KeysMode.LEAD) (2.0 * phase[i] - 1.0) else Dsp.sin01(phase[i]).toDouble()
                s += wave * partialAmp[i] * partialEnv[i]
                partialEnv[i] *= partialDecay[i]
            }
            var v = s.toFloat()
            if (mode == KeysMode.PAD || mode == KeysMode.LEAD) v = lp.process(v)
            if (releasing) release *= releaseCoeff
            if (t < 64) v *= t / 64f
            lastOut = v * gain * env * release
            out[offset + k] += lastOut
            t++
        }
        if (releasing && release < 1e-4f) active = false
        if (mode == KeysMode.PIANO || mode == KeysMode.EPIANO) if (partialEnv[0] < 1e-4f) active = false
    }
}

/** Metronome click; level 0 = downbeat. */
internal class ClickVoice(private val sampleRate: Int) {
    var active = false; private set
    private var phase = 0.0; private var hz = 1000.0; private var amp = 0f; private var decay = 0f; private var left = 0
    fun start(level: Int) {
        active = true; phase = 0.0; hz = if (level == 0) 1760.0 else 1175.0; amp = if (level == 0) 0.55f else 0.38f
        decay = Dsp.timeCoefficient(0.012, sampleRate); left = (0.05 * sampleRate).toInt()
    }
    fun render(left: FloatArray, right: FloatArray, offset: Int, count: Int, gain: Float) {
        if (!active) return
        for (k in 0 until count) {
            if (this.left <= 0) { active = false; return }
            phase += hz / sampleRate; if (phase >= 1.0) phase -= 1.0
            val v = Dsp.sin01(phase) * amp * gain
            left[offset + k] += v; right[offset + k] += v
            amp *= decay; this.left--
            if (abs(amp) < 1e-5f) { active = false; return }
        }
    }
}

