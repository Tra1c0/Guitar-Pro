package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * QA cycle 18, dimension D2 (gain staging), measured end to end with the bundled sample bank at 48 kHz: the master
 * limiter must not be the mix bus compressor of a new project, Clean must stay clean, Crunch must keep pick dynamics,
 * the sampled voice sum must stay below full scale with continuous velocity steps, stems must not clip, softClip must
 * be bounded. Every test prints its numbers ("D2 ..."); the two findings the cycle-18 fixes do not solve (preset loudness
 * parity, 1-ulp hypersensitivity of the oversampled amp) are printed probes without assertion.
 */
class Qa18GainStructureTest {
    private var n = 0
    private fun id() = EntityId("d2g-${n++}")
    private val guitar = InstrumentDefinition()
    private val bassDef = InstrumentDefinition(InstrumentKind.BASS, listOf(28, 33, 38, 43), List(4) { 0 }, midiProgram = 33)
    private fun f(a: Long, b: Long) = Fraction.of(a, b)
    private val pm = Articulation.Mark(NoteMark.PALM_MUTE)
    private fun g(s: Int, fr: Int, v: Int = 96, vararg a: Articulation) = Note(id(), Pitch(guitar.pitch(s, fr)), s, fr, velocity = v, articulations = a.toList())
    private fun b(s: Int, fr: Int, vararg a: Articulation) = Note(id(), Pitch(bassDef.pitch(s, fr)), s, fr, velocity = 104, articulations = a.toList())
    private fun d(midi: Int, v: Int = 100) = Note(id(), Pitch(midi), velocity = v)
    private fun beat(o: Fraction, den: Int, vararg notes: Note) = Beat(id(), o, WrittenDuration(den), notes.toList())
    private fun bankOrSkip(): SampleBank { val b = bank; assumeTrue("sample bank not found", b != null); return b!! }

    companion object {
        const val SR = 48_000
        const val BLOCK = 1024
        val bankFile: File? = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.firstOrNull { it.isFile }
        val bank: SampleBank? by lazy { bankFile?.inputStream()?.buffered()?.use { input -> SampleBank.read { b, o, l -> input.read(b, o, l) } } }
        /** Constant-power pan law at centre: cos(pi/4) * 1.414f (what mixDown applies to a mono track). */
        val PAN_UNITY: Float = (cos((PI / 4).toFloat()) * 1.414f)
        fun db(x: Double): Double = if (x <= 1e-12) -240.0 else 20 * log10(x)
        fun f1(x: Double) = String.format(java.util.Locale.ROOT, "%.1f", x)

        class Stats(val peak: Double, val rms: Double, val clip: Double)
        /** Peak over the whole array, RMS over the first [rmsFrames] and the fraction of those frames at or above 0.98. */
        fun stats(x: FloatArray, rmsFrames: Int): Stats {
            var peak = 0.0; var sum = 0.0; var clip = 0L
            val n = minOf(rmsFrames, x.size)
            for (i in x.indices) { val a = abs(x[i].toDouble()); if (a > peak) peak = a }
            for (i in 0 until n) { val a = abs(x[i].toDouble()); sum += a * a; if (a >= 0.98) clip++ }
            return Stats(peak, sqrt(sum / n.coerceAtLeast(1)), clip.toDouble() / n.coerceAtLeast(1))
        }
        fun rms(x: FloatArray, from: Int, to: Int): Double { var s = 0.0; val b = to.coerceAtMost(x.size); for (i in from until b) s += x[i].toDouble() * x[i]; return sqrt(s / (b - from).coerceAtLeast(1)) }
        /** Runs an effect over [x] in engine-sized blocks (mono, as a STRING channel is processed in mixDown). */
        internal fun run(fx: Effect, x: FloatArray): FloatArray {
            val y = x.copyOf(); val buf = FloatArray(BLOCK); var i = 0
            while (i < y.size) { val k = minOf(BLOCK, y.size - i); System.arraycopy(y, i, buf, 0, k); fx.process(buf, null, k); System.arraycopy(buf, 0, y, i, k); i += k }
            return y
        }
        /** Mirror of [Limiter]'s gain computer (mono, L = R), validated against the real unit in cycle 18 (< 1e-5). */
        fun limiterGain(x: FloatArray, ceiling: Float = 0.94f): FloatArray {
            val release = Dsp.timeCoefficient(0.12, SR); var gain = 1f; val g = FloatArray(x.size)
            for (i in x.indices) { val peak = abs(x[i]); val needed = if (peak * gain > ceiling) ceiling / peak else 1f; gain = if (needed < gain) needed else needed + (gain - needed) * release; g[i] = gain }
            return g
        }
        fun goertzelPower(x: FloatArray, hz: Double, from: Int, to: Int): Double {
            val w = 2 * PI * hz / SR; val c = 2 * cos(w); var s1 = 0.0; var s2 = 0.0; val len = to - from
            for (i in from until to) { val h = 0.5 - 0.5 * cos(2 * PI * (i - from) / (len - 1)); val s0 = x[i] * h + c * s1 - s2; s2 = s1; s1 = s0 }
            return s1 * s1 + s2 * s2 - c * s1 * s2
        }
    }

    private fun oneTrack(preset: String, bars: List<List<Beat>>, bpm: Double = 150.0, effects: List<EffectUnit> = emptyList(), instrument: InstrumentDefinition = guitar, mix: ChannelMix = ChannelMix(effects = effects)): Project {
        val m = bars.map { Measure(id()) }
        val staff = Staff(id(), bars = m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0) bars[i] else emptyList()) }) })
        return Project(id = id(), measures = m, tracks = listOf(Track(id(), "G", instrument, listOf(staff), SoundAssignment(presetId = preset), mix)), tempo = listOf(TempoChange(ScorePosition(m[0].id), bpm)))
    }
    private fun render(p: Project, seconds: Double, rack: ChannelRack, mix: MixState, bk: SampleBank?): Pair<FloatArray, FloatArray> {
        val plan = (ScorePlaybackCompiler().compileNow(p, SR) as Outcome.Success).value
        val engine = SynthEngine(SR, BLOCK, bk)
        engine.installRack(rack); engine.setMix(mix); engine.install(EngineProgram.prepare(plan)); engine.play(0)
        val frames = (seconds * SR).toInt(); val l = FloatArray(frames); val r = FloatArray(frames); val buf = FloatArray(BLOCK * 2)
        var done = 0
        while (done < frames) { val k = minOf(BLOCK, frames - done); engine.render(buf, k); for (i in 0 until k) { l[done + i] = buf[2 * i]; r[done + i] = buf[2 * i + 1] }; done += k }
        return l to r
    }
    /** The sampled voice sum of a one-track project before any effect (the channel's dry signal, pan law removed). */
    private fun dry(preset: TonePreset, p: Project, seconds: Double, bk: SampleBank): FloatArray {
        val ch = Channel(ChannelKind.STRING, TonePresets.stringTone(preset), TonePresets.keysMode(preset), emptyArray(), BLOCK, sampled = TonePresets.isSampledGuitar(preset), sampleGain = TonePresets.sampleGain(preset), voiceSignature = "dry", units = emptyList())
        val (l, _) = render(p, seconds, ChannelRack(arrayOf(ch), emptyArray(), "dry"), MixState(floatArrayOf(1f), floatArrayOf(0f), 2f, 0f), bk)
        return FloatArray(l.size) { l[it] / PAN_UNITY }
    }

    /** Demo band (own material, as in DemoRiffRender): high-gain guitar(s), pick bass, rock drums; 4 bars at 150 BPM, the last in 7/8. */
    private fun demoBand(guitarPreset: String? = "rw.guitar.highgain", guitars: Int = 2, factoryMix: Boolean = false): Project {
        val m = listOf(Measure(id()), Measure(id()), Measure(id()), Measure(id(), TimeSignature(7, 8)))
        fun power(fret: Int, vararg a: Articulation) = arrayOf(g(0, fret, 96, *a), g(1, fret, 96, *a), g(2, fret, 96, *a))
        val g1 = listOf(beat(f(0, 16), 16, g(0, 0, 96, pm)), beat(f(1, 16), 16, g(0, 0, 96, pm)), beat(f(2, 16), 16, g(0, 0, 96, pm)), beat(f(3, 16), 16, *power(3)),
            beat(f(4, 16), 16, g(0, 0, 96, pm)), beat(f(5, 16), 16, g(0, 0, 96, pm)), beat(f(6, 16), 16, *power(5)), beat(f(7, 16), 16, g(0, 0, 96, pm)),
            beat(f(8, 16), 16, g(0, 0, 96, pm)), beat(f(9, 16), 16, g(0, 0, 96, Articulation.Mark(NoteMark.DEAD))), beat(f(10, 16), 16, g(0, 0, 96, pm)), beat(f(11, 16), 8, *power(7)),
            beat(f(13, 16), 16, g(0, 0, 96, pm)), beat(f(14, 16), 8, g(2, 5, 96, Articulation.Harmonic(HarmonicKind.PINCH), Articulation.Vibrato(45, 5.5))))
        val g2 = g1.map { it.copy(id = id(), notes = it.notes.map { n -> n.copy(id = id()) }) }
        val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(f(1, 4), 200), PitchCurvePoint(f(2, 3), 200), PitchCurvePoint(Fraction.ONE, 0)))
        val g3 = listOf(beat(f(0, 1), 4, g(4, 7, 96, bend)), beat(f(1, 4), 8, g(4, 7, 96, Articulation.Vibrato(40, 5.0)), g(3, 7)), beat(f(3, 8), 8, g(3, 5, 96, Articulation.Mark(NoteMark.HAMMER_ON))),
            beat(f(1, 2), 8, g(3, 7)), beat(f(5, 8), 8, g(4, 5)), beat(f(3, 4), 4, g(2, 7, 96, Articulation.Vibrato(60, 5.0))))
        val g4 = listOf(beat(f(0, 16), 16, g(0, 0, 96, pm)), beat(f(1, 16), 16, g(0, 0, 96, pm)), beat(f(2, 16), 16, g(0, 0, 96, pm)), beat(f(3, 16), 16, g(0, 1, 96, pm)),
            beat(f(4, 16), 16, g(0, 0, 96, pm)), beat(f(5, 16), 16, g(0, 0, 96, pm)), beat(f(6, 16), 16, g(0, 3, 96, pm)), beat(f(7, 16), 16, g(0, 0, 96, pm)),
            beat(f(8, 16), 8, *power(8, Articulation.Slide(SlideKind.OUT_DOWN))), beat(f(10, 16), 4, *power(0, Articulation.Mark(NoteMark.LET_RING))))
        val guitarBars = listOf(g1, g2, g3, g4)
        fun bassOf(beats: List<Beat>) = beats.mapNotNull { bt -> bt.notes.firstOrNull { it.string == 0 }?.let { lowest -> Beat(id(), bt.offset, bt.duration, listOf(b(1, (lowest.fret ?: 0), *if (pm in lowest.articulations) arrayOf<Articulation>(pm) else emptyArray()))) } }
        val bassBars = listOf(bassOf(g1), bassOf(g2), listOf(Beat(id(), Fraction.ZERO, WrittenDuration(2), listOf(b(2, 2))), Beat(id(), f(1, 2), WrittenDuration(2), listOf(b(1, 5)))), bassOf(g4))
        fun drumBar(len: Int, crash: Boolean) = (0 until len).map { k ->
            val notes = mutableListOf(d(36, if (k % 2 == 0) 110 else 92))
            if (k % 2 == 0) notes += d(42, 80)
            if (k % 8 == 4) notes += d(38, 118)
            if (k == 0 && crash) notes += d(49, 120)
            Beat(id(), f(k.toLong(), 16), WrittenDuration(16), notes)
        }
        val drumBars = listOf(drumBar(16, true), drumBar(16, false), (0 until 8).map { k -> Beat(id(), f(k.toLong(), 8), WrittenDuration(8), listOfNotNull(d(51, 90), if (k % 4 == 2) d(38, 110) else null, if (k % 4 == 0) d(36, 105) else null)) }, drumBar(14, true))
        fun staff(bars: List<List<Beat>>, kind: NotationKind = NotationKind.TAB) = listOf(Staff(id(), setOf(kind), m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0) bars[i] else emptyList()) }) }))
        fun mix(volume: Double, pan: Double) = if (factoryMix) ChannelMix() else ChannelMix(volume = volume, pan = pan)
        val tracks = mutableListOf<Track>()
        tracks += Track(id(), "Gitarre · Drop A", guitar, staff(guitarBars), SoundAssignment(presetId = guitarPreset), mix(0.78, -0.55))
        if (guitars > 1) tracks += Track(id(), "Gitarre R", guitar, staff(guitarBars.map { bars -> bars.map { it.copy(id = id(), notes = it.notes.map { n -> n.copy(id = id(), velocity = 92) }) } }), SoundAssignment(presetId = guitarPreset), mix(0.76, 0.55))
        tracks += Track(id(), "Bass", bassDef, staff(bassBars), SoundAssignment(presetId = "rw.bass.pick"), mix(0.8, 0.0))
        tracks += Track(id(), "Drums", InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0), staff(drumBars, NotationKind.PERCUSSION), mix = mix(0.85, 0.0))
        return Project(id = id(), metadata = ProjectMetadata("D2 band"), measures = m, tempo = listOf(TempoChange(ScorePosition(m[0].id), 150.0)), tracks = tracks)
    }
    /** A new-project-like band: the default guitar (program 30 -> high gain), bass, drums, all at the factory ChannelMix (0.8, centre). */
    private fun defaultLikeBand() = demoBand(guitarPreset = null, guitars = 1, factoryMix = true)

    private fun limiterReport(p: Project, bk: SampleBank, seconds: Double, scoreSeconds: Double): Triple<Double, Double, Double> {
        val (l, r) = render(p, seconds, ChannelRack.build(p, SR, BLOCK, includeMasterFx = false), MixState.from(p), bk)
        val scoreFrames = (scoreSeconds * SR).toInt()
        val gain = limiterGain(FloatArray(l.size) { maxOf(abs(l[it]), abs(r[it])) })
        var minG = 1f; var engaged = 0
        for (i in 0 until scoreFrames) { if (gain[i] < minG) minG = gain[i]; if (gain[i] < 0.944f) engaged++ }
        return Triple(db(stats(l, scoreFrames).peak), 100.0 * engaged / scoreFrames, -db(minG.toDouble()))
    }

    // ------------------------------------------------------------------------------------------------------------------

    /** D2-1: a new project's three default tracks at factory volume must not keep the master limiter in gain reduction. */
    @Test fun defaultBandDoesNotLiveInTheMasterLimiter() {
        val bk = bankOrSkip()
        val (peak, share, maxGr) = limiterReport(defaultLikeBand(), bk, 7.0, 6.2)
        println("D2 default-like band (HG + bass + drums, vol 0.8): master pre-limiter peak ${f1(peak)} dBFS, limiter > 0.5 dB for ${f1(share)} % of 4 bars, max GR ${f1(maxGr)} dB")
        val (peak4, share4, maxGr4) = limiterReport(demoBand(), bk, 7.0, 6.2)
        println("D2 demo 4-track band (2 x HG, bass, drums): master pre-limiter peak ${f1(peak4)} dBFS, limiter > 0.5 dB for ${f1(share4)} % of 4 bars, max GR ${f1(maxGr4)} dB")
        assertTrue("master before limiter peaks at ${f1(peak)} dBFS; limiter reduces > 0.5 dB for ${f1(share)} % of the 4 bars (max ${f1(maxGr)} dB) - budget: < 10 % and < 1.5 dB", share < 10.0 && maxGr < 1.5)
    }

    /** D2-4: isolated-track (stem) exports have no limiter (OfflineMixdown: includeMasterFx && soloTrack == null) and must not clip in the WAV encoder. */
    @Test fun soloStemExportsDoNotClip() {
        val bk = bankOrSkip()
        val cases = listOf(
            "clean guitar, power chord A2+E3 v127" to oneTrack("rw.guitar.clean", listOf(listOf(beat(Fraction.ZERO, 2, g(2, 0, 127), g(3, 2, 127))))),
            "clean guitar, open E chord (6 notes) v96" to oneTrack("rw.guitar.clean", listOf(listOf(beat(Fraction.ZERO, 2, g(1, 0, 96), g(2, 2, 96), g(3, 2, 96), g(4, 1, 96), g(5, 0, 96), g(6, 0, 96))))),
            "acoustic guitar, open E chord (6 notes) v127" to oneTrack("rw.guitar.acoustic", listOf(listOf(beat(Fraction.ZERO, 2, g(1, 0, 127), g(2, 2, 127), g(3, 2, 127), g(4, 1, 127), g(5, 0, 127), g(6, 0, 127))))),
            "drums, rock kit bar" to defaultLikeBand().let { it.copy(tracks = it.tracks.filter { t -> t.instrument.kind == InstrumentKind.PERCUSSION }) },
        )
        val failures = mutableListOf<String>()
        for ((name, p) in cases) {
            val r = OfflineMixdown(bank = bk).render(p, RenderOptions(sampleRate = SR, bitDepth = 16, tailSeconds = 0.5), { _, _, _ -> }, soloTrack = 0)
            val s = (r as Outcome.Success).value
            println("D2 stem $name: peak ${f1(db(s.peak))} dBFS, ${s.clippedSamples} clipped samples")
            if (s.clippedSamples > 0) failures += "$name: ${s.clippedSamples} clipped samples, peak ${f1(db(s.peak))} dBFS"
        }
        assertTrue("stem exports clip: $failures", failures.isEmpty())
    }

    /** D2-2: the Clean preset's amp must not saturate on a single default-velocity note (its 3 % THD point is -12 dBFS). */
    @Test fun cleanPresetAmpStaysCleanOnADefaultVelocityNote() {
        val bk = bankOrSkip()
        val preset = TonePresets.find("rw.guitar.clean")!!
        fun ampInput(v: Int): Pair<Double, Double> {
            val p = oneTrack(preset.id, listOf(listOf(beat(Fraction.ZERO, 2, g(2, 0, v)))))
            var cur = dry(preset, p, 1.2, bk); var ampIn = cur; var ampOut = cur
            for (u in preset.defaultChain) { if (u.kind == EffectKind.AMP) ampIn = cur; cur = run(Effects.create(u, SR, BLOCK)!!, cur); if (u.kind == EffectKind.AMP) ampOut = cur }
            return db(stats(ampIn, (0.8 * SR).toInt()).peak) to stats(ampOut, (0.8 * SR).toInt()).clip
        }
        val (inPeak, saturated) = ampInput(96)
        val (in127, sat127) = ampInput(127)
        // Reference: the THD this amp produces for a sine at that input level.
        val amp = preset.defaultChain.first { it.kind == EffectKind.AMP }
        val x = FloatArray(SR) { (Dsp.dbToGain(inPeak) * sin(2 * PI * 220 * it / SR)).toFloat() }
        val y = run(Effects.create(amp, SR, BLOCK)!!, x)
        val p1 = goertzelPower(y, 220.0, SR / 2, SR); var ph = 0.0; for (k in 2..12) ph += goertzelPower(y, 220.0 * k, SR / 2, SR)
        val thd = 100 * sqrt(ph / p1)
        println("D2 clean amp input: single A2 v96 peaks ${f1(inPeak)} dBFS (sine THD at that level ${f1(thd)} %, ${f1(saturated * 100)} % saturated output samples); v127 ${f1(in127)} dBFS (${f1(sat127 * 100)} % saturated)")
        assertTrue("clean amp input peaks at ${f1(inPeak)} dBFS (sine THD at that level ${f1(thd)} %), ${f1(saturated * 100)} % of the amp output samples are at >= 0.98 - budget: input <= -12 dBFS (< 3 % THD), no saturated samples", inPeak <= -12.0 && saturated == 0.0)
    }

    /**
     * D2-3: the velocity-to-level curve of the sampled guitar must be continuous at the layer boundaries (64/65, 100/101).
     * Loudness (RMS of the first 100 ms and of 0.05-0.45 s) is what the ear hears as a level jump and what the bank
     * normalisation matches; the peak may still step by the layers' different attack sharpness (the f layer's pick
     * transient is sharper at equal loudness), bounded separately.
     */
    @Test fun velocityLayerBoundariesAreContinuous() {
        val bk = bankOrSkip()
        val preset = TonePresets.find("rw.guitar.highgain")!!
        class L(val peak: Double, val attack: Double, val body: Double)
        fun at(v: Int): L { val x = dry(preset, oneTrack(preset.id, listOf(listOf(beat(Fraction.ZERO, 2, g(2, 0, v))))), 0.6, bk); return L(db(stats(x, SR / 2).peak), db(rms(x, 0, SR / 10)), db(rms(x, SR / 20, (0.45 * SR).toInt()))) }
        val rows = listOf(40, 64, 65, 80, 81, 100, 101, 127).associateWith { at(it) }
        println("D2 velocity curve (dry A2, string 3): " + rows.entries.joinToString { "v${it.key}: peak ${f1(it.value.peak)} / attack ${f1(it.value.attack)} / body ${f1(it.value.body)} dBFS" })
        fun step(a: Int, b: Int, m: (L) -> Double) = m(rows[b]!!) - m(rows[a]!!)
        val peak64 = step(64, 65) { it.peak }; val peak100 = step(100, 101) { it.peak }
        val att64 = step(64, 65) { it.attack }; val att100 = step(100, 101) { it.attack }
        val body64 = step(64, 65) { it.body }; val body100 = step(100, 101) { it.body }
        println("D2 velocity steps: v64->65 peak ${f1(peak64)} / attack ${f1(att64)} / body ${f1(body64)} dB; v100->101 peak ${f1(peak100)} / attack ${f1(att100)} / body ${f1(body100)} dB (v80->81 peak ${f1(step(80, 81) { it.peak })} dB); range v40..v127 ${f1(step(40, 127) { it.attack })} dB")
        assertTrue("loudness steps: v64->65 attack ${f1(att64)} / body ${f1(body64)} dB, v100->101 attack ${f1(att100)} / body ${f1(body100)} dB - budget: <= 1.5 dB per velocity step",
            abs(att64) <= 1.5 && abs(att100) <= 1.5 && abs(body64) <= 1.5 && abs(body100) <= 1.5)
        // The f layer's pick transient is 2-4 dB sharper than the mf layer's at equal loudness (measured crest 15 vs 12 dB at A2).
        assertTrue("peak steps: v64->65 ${f1(peak64)} dB, v100->101 ${f1(peak100)} dB - budget: <= 5 dB (attack sharpness of the next layer at equal loudness)", abs(peak64) <= 5.0 && abs(peak100) <= 5.0)
    }

    /** D2-5: Crunch must respond to pick dynamics (Bericht 2 section 7): v127 and v40 differ audibly at the output. */
    @Test fun crunchRespondsToPickDynamics() {
        val bk = bankOrSkip()
        val preset = TonePresets.find("rw.guitar.crunch")!!
        fun out(v: Int): Pair<Double, Double> {
            val p = oneTrack(preset.id, listOf(listOf(beat(Fraction.ZERO, 2, g(2, 0, v)))))
            val x = dry(preset, p, 1.2, bk)
            var cur = x; for (u in preset.defaultChain) cur = run(Effects.create(u, SR, BLOCK)!!, cur)
            return db(rms(x, 0, (0.8 * SR).toInt())) to db(rms(cur, 0, (0.8 * SR).toInt()))
        }
        val (in127, out127) = out(127); val (in40, out40) = out(40); val (in96, out96) = out(96)
        val dyn = out127 - out40
        println("D2 crunch dynamics: dry rms v40 ${f1(in40)} / v96 ${f1(in96)} / v127 ${f1(in127)} dBFS -> chain output rms ${f1(out40)} / ${f1(out96)} / ${f1(out127)} dBFS; v127 - v40 = ${f1(dyn)} dB for ${f1(in127 - in40)} dB input difference")
        assertTrue("crunch output rms v127 - v40 = ${f1(dyn)} dB (dry input differs by ${f1(in127 - in40)} dB) - budget: >= 6 dB", dyn >= 6.0)
    }

    /**
     * D2-7 (probe, no assertion): the same riff through the four guitar presets. HG/crunch are brickwalled by their amps
     * (crest ~10 dB) while clean/acoustic keep 17-22 dB crest, so RMS parity needs a bus compressor, not more gain:
     * a design decision pending a listening check (Bericht 2 section 11). The table is kept for that decision.
     */
    @Test fun probe_guitarPresetLoudnessSpread() {
        val bk = bankOrSkip()
        fun power(fret: Int) = arrayOf(g(0, fret), g(1, fret), g(2, fret))
        val bar = (0 until 16).map { k -> if (k == 3 || k == 11) beat(f(k.toLong(), 16), 8, *power(if (k == 3) 3 else 5)) else if (k == 4 || k == 12) null else beat(f(k.toLong(), 16), 16, g(0, 0, 96, pm)) }.filterNotNull()
        val levels = listOf("rw.guitar.highgain", "rw.guitar.crunch", "rw.guitar.clean", "rw.guitar.acoustic").associateWith { preset ->
            val p = oneTrack(preset, listOf(bar))
            val (l, _) = render(p, 1.9, ChannelRack.build(p, SR, BLOCK, includeMasterFx = true), MixState.from(p), bk)
            db(rms(l, 0, (1.6 * SR).toInt())) to db(stats(l, (1.6 * SR).toInt()).peak)
        }
        val spread = levels.values.maxOf { it.first } - levels.values.minOf { it.first }
        println("D2 preset loudness (1-bar riff, post-limiter): " + levels.entries.joinToString { "${it.key.removePrefix("rw.guitar.")} rms ${f1(it.value.first)} / peak ${f1(it.value.second)} dBFS" } + " -> spread ${f1(spread)} dB (design target <= 6 dB, not solved by level trims alone)")
    }

    /** D2-3: the sampled voice sum must stay below full scale before any effect: a single note <= -6 dBFS, chords < 0 dBFS. */
    @Test fun sampledVoiceSumStaysBelowFullScale() {
        val bk = bankOrSkip()
        val preset = TonePresets.find("rw.guitar.highgain")!!
        val single = db(stats(dry(preset, oneTrack(preset.id, listOf(listOf(beat(Fraction.ZERO, 2, g(2, 0, 127))))), 0.9, bk), SR / 2).peak)
        val power = db(stats(dry(preset, oneTrack(preset.id, listOf(listOf(beat(Fraction.ZERO, 2, g(2, 0, 127), g(3, 2, 127))))), 0.9, bk), SR / 2).peak)
        val chord96 = db(stats(dry(preset, oneTrack(preset.id, listOf(listOf(beat(Fraction.ZERO, 2, g(1, 0), g(2, 2), g(3, 2), g(4, 1), g(5, 0), g(6, 0))))), 0.9, bk), SR / 2).peak)
        val chord127 = db(stats(dry(preset, oneTrack(preset.id, listOf(listOf(beat(Fraction.ZERO, 2, g(1, 0, 127), g(2, 2, 127), g(3, 2, 127), g(4, 1, 127), g(5, 0, 127), g(6, 0, 127))))), 0.9, bk), SR / 2).peak)
        // Worst single note across the neck at v127 (every string, open and fret 12).
        val worst = guitar.tuning.indices.flatMap { s -> listOf(0, 5, 12).map { fr -> db(stats(dry(preset, oneTrack(preset.id, listOf(listOf(beat(Fraction.ZERO, 2, g(s, fr, 127))))), 0.6, bk), SR / 2).peak) } }.max()
        println("D2 dry voice sum peaks (high gain trim): single A2 v127 ${f1(single)} dBFS (worst single v127 over the neck ${f1(worst)}), power chord v127 ${f1(power)}, 6-note chord v96 ${f1(chord96)} / v127 ${f1(chord127)} dBFS")
        assertTrue("dry voice sum peaks: single A2 v127 ${f1(single)} dBFS (worst over the neck ${f1(worst)}), power chord v127 ${f1(power)} dBFS, 6-note chord v96 ${f1(chord96)} dBFS - budget: a single note <= -6 dBFS, chords < 0 dBFS", single <= -6.0 && worst <= -6.0 && power < 0.0 && chord96 < 0.0)
    }

    /** Sanity: the offline summary's clipped-sample count really reflects values above full scale (so the stem counts are trustworthy). */
    @Test fun wavEncoderCountsOvershootAsClipped() {
        val stats = Wav.Stats(); val out = ByteArray(64)
        Wav.encode(floatArrayOf(0.5f, 1.2f, -1.5f, 0.9f), 4, 16, out, stats)
        assertEquals(2L, stats.clipped)
    }

    /** D2-6: Dsp.softClip (every amp stage, the overdrive and the amp power stage) must be bounded by 1 and monotonic. */
    @Test fun softClipIsBoundedAndMonotonic() {
        var maxValue = 0f; var maxAt = 0f; var worstStep = 0f; var stepAt = 0f; var prev = Dsp.softClip(0f)
        var x = 0.001f
        while (x <= 6f) { val y = Dsp.softClip(x); if (y > maxValue) { maxValue = y; maxAt = x }; if (prev - y > worstStep) { worstStep = prev - y; stepAt = x }; prev = y; x += 0.001f }
        println("D2 softClip: maximum ${"%.5f".format(maxValue)} at x = ${"%.2f".format(maxAt)}, largest downward step ${"%.2e".format(worstStep)} at x = ${"%.2f".format(stepAt)}")
        assertTrue("softClip peaks at ${"%.4f".format(maxValue)} (x = ${"%.2f".format(maxAt)}) and drops by ${"%.4f".format(worstStep)} at x = ${"%.2f".format(stepAt)}; the rational form equals 1 exactly at x = 3, so the clamp belongs at |x| >= 3", maxValue <= 1.0001f && worstStep < 1e-3f)
    }

    /**
     * D2-9 (probe, no assertion): the oversampled high-gain amp turns a 1-ulp input difference into a -30..-60 dBFS output
     * difference (float32 biquads with poles near 1). The softClip clamp removed the 3 % step; the residual is a numerical
     * conditioning issue of AmpSim, out of scope of the level fixes. Printed so a later change can be measured.
     */
    @Test fun probe_highGainChainSensitivityToOneUlp() {
        val bk = bankOrSkip()
        val preset = TonePresets.find("rw.guitar.highgain")!!
        val p = oneTrack(preset.id, listOf(listOf(beat(Fraction.ZERO, 2, g(1, 0, 127), g(2, 2, 127), g(3, 2, 127), g(4, 1, 127), g(5, 0, 127), g(6, 0, 127)))))
        val x = dry(preset, p, 1.2, bk)
        val y = FloatArray(x.size) { Math.nextUp(x[it]) }   // every sample one ulp up: what a different summation order or mix constant produces
        var inDiff = 0.0; for (i in x.indices) inDiff = maxOf(inDiff, abs(x[i] - y[i]).toDouble())
        var a = x.copyOf(); var b = y.copyOf()
        for (u in preset.defaultChain.take(2)) { a = run(Effects.create(u, SR, BLOCK)!!, a); b = run(Effects.create(u, SR, BLOCK)!!, b) }
        var outDiff = 0.0; var sum = 0.0; for (i in a.indices) { val d = abs(a[i] - b[i]).toDouble(); outDiff = maxOf(outDiff, d); sum += d * d }
        println("D2 1-ulp sensitivity (GATE + AMP, 6-note chord v127): input differs by ${"%.1e".format(inDiff)}, output max ${"%.2e".format(outDiff)} / rms ${"%.2e".format(sqrt(sum / a.size))} (design target < 1e-3; cycle 18 before the clamp: 0.15)")
    }
}
