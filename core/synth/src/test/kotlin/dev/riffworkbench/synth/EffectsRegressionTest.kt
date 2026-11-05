package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Defects found by measuring the effects against their parameters (QA cycle 5). */
class EffectsRegressionTest {
    private var n = 0
    private fun id() = EntityId("fx${n++}")
    private val sr = 48_000
    private fun unit(kind: EffectKind, vararg p: Pair<String, Double>) = EffectUnit(id(), kind, parameters = mapOf(*p))
    private fun fx(kind: EffectKind, vararg p: Pair<String, Double>) = Effects.create(unit(kind, *p), sr, 1024)!!
    private fun sine(hz: Double, amp: Double, frames: Int = sr) = FloatArray(frames) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }
    private fun run(e: Effect, x: FloatArray): FloatArray { val y = x.copyOf(); var i = 0; while (i < y.size) { val k = minOf(256, y.size - i); val b = y.copyOfRange(i, i + k); e.process(b, null, k); b.copyInto(y, i); i += k }; return y }
    private fun rmsDb(x: FloatArray, from: Int = x.size / 2) = 10 * log10((from until x.size).sumOf { x[it].toDouble() * x[it] } / (x.size - from))
    /** Goertzel power of [hz] over [x] with a Hann window (1 s at 48 kHz → integer-Hz bins are exact). */
    private fun tone(x: FloatArray, hz: Double): Double {
        val w = 2 * PI * hz / sr; val c = 2 * cos(w); var s1 = 0.0; var s2 = 0.0
        for (i in x.indices) { val h = 0.5 - 0.5 * cos(2 * PI * i / (x.size - 1)); val s0 = x[i] * h + c * s1 - s2; s2 = s1; s1 = s0 }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }

    private fun project(chain: List<EffectUnit>, beats: List<Beat>, bars: Int = 1): Project {
        val m = List(bars) { Measure(id()) }
        val t = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })),
            SoundAssignment(presetId = "rw.guitar.clean"), ChannelMix(effects = chain))
        return Project(id = id(), measures = m, tracks = listOf(t), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }
    private fun engine(p: Project) = SynthEngine(sr, 1024).apply {
        installRack(ChannelRack.build(p, sr, 1024)); setMix(MixState.from(p))
        install(EngineProgram.prepare((ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value)); play(0)
    }
    private fun render(e: SynthEngine, seconds: Double): FloatArray {
        val out = FloatArray((seconds * sr).toInt()); val buf = FloatArray(512); var i = 0
        while (i < out.size) { val k = minOf(256, out.size - i); e.render(buf, k); for (j in 0 until k) out[i + j] = buf[2 * j]; i += k }
        return out
    }

    @Test fun longDelayTailsAreNotCutAfterSixSeconds() {
        val note = Beat(id(), Fraction.ZERO, WrittenDuration(16), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(3, 5)), 3, 5)))
        val y = render(engine(project(listOf(unit(EffectKind.DELAY, "timeMs" to 2000.0, "feedback" to 0.95, "mix" to 1.0)), listOf(note))), 9.0)
        val echo8 = (8 * sr until (8.15 * sr).toInt()).maxOf { abs(y[it]) }
        val echo2 = (2 * sr until (2.15 * sr).toInt()).maxOf { abs(y[it]) }
        // Before the fix the channel was skipped 6 s after the last voice: the 8 s echo was exactly 0.
        assertTrue("echo at 8 s ${20 * log10(echo8 / echo2)} dB re 2 s", echo8 > echo2 * 0.1f)
    }

    @Test fun changingAnEffectParameterKeepsNotesSounding() {
        val whole = Beat(id(), Fraction.ZERO, WrittenDuration(1), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(2, 5)), 2, 5)))
        val chain = listOf(unit(EffectKind.REVERB, "mix" to 0.20))
        val p = project(chain, listOf(whole))
        val reference = engine(p); render(reference, 1.5); val ref = render(reference, 0.2)
        val swapped = engine(p); render(swapped, 1.5)
        swapped.installRack(ChannelRack.build(p.copy(tracks = p.tracks.map { it.copy(mix = it.mix.copy(effects = listOf(chain[0].copy(parameters = mapOf("mix" to 0.21))))) }), sr, 1024))
        val after = render(swapped, 0.2)
        assertTrue("note silenced by a parameter change: ${rmsDb(after, after.size / 2)} vs ${rmsDb(ref, ref.size / 2)} dB", rmsDb(after, after.size / 2) > rmsDb(ref, ref.size / 2) - 3)
    }

    @Test fun limiterIsTransparentBelowTheKneeAndReachesItsCeiling() {
        val quiet = sine(997.0, 0.5); val loud = sine(997.0, 3.0)
        val q = quiet.copyOf(); Limiter(sr).process(q, null, q.size)
        assertTrue((q.indices).all { abs(q[it] - quiet[it]) < 1e-6f })
        val l = loud.copyOf(); Limiter(sr).process(l, null, l.size)
        val peak = l.maxOf { abs(it) }
        assertTrue("peak $peak", peak > 0.88f && peak <= 0.94f)
        // Sustained overload: the third harmonic must stay well below the old limiter's -24 dB.
        val h3 = tone(l.copyOfRange(sr / 2, sr / 2 + sr / 2).copyOf(sr), 3 * 997.0) / tone(l.copyOfRange(sr / 2, sr / 2 + sr / 2).copyOf(sr), 997.0)
        assertTrue("3rd harmonic ${10 * log10(h3)} dB", 10 * log10(h3) < -30)
    }

    @Test fun overdriveDoesNotFoldHarmonicsBackIntoTheBand() {
        val y = run(fx(EffectKind.OVERDRIVE, "drive" to 1.0), sine(3517.0, 0.3))
        val fundamental = tone(y, 3517.0)
        val alias = tone(y, 2279.0) // 13th harmonic folded at 48 kHz
        assertTrue("alias ${10 * log10(alias / fundamental)} dB", 10 * log10(alias / fundamental) < -40)
    }

    @Test fun effectDefaultsMatchWhatTheDspUses() {
        assertEquals(0.2, EffectDefaults.value(EffectKind.REVERB, "mix")!!, 0.0)
        assertEquals(-18.0, EffectDefaults.value(EffectKind.COMPRESSOR, "thresholdDb")!!, 0.0)
        assertEquals(unit(EffectKind.EQ).p("mid", 99.0, -18.0..18.0), 0.0, 0.0)
    }
}
