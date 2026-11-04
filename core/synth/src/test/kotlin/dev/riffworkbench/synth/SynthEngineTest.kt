package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SynthEngineTest {
    private var n = 0
    private fun id() = EntityId("s${n++}")
    private val sr = 48_000
    private val neutral = listOf(EffectUnit(EntityId("fx-eq"), EffectKind.EQ))

    private fun project(beats: List<Beat>, preset: String = "rw.guitar.clean", effects: List<EffectUnit> = neutral, bpm: Double = 120.0, measures: Int = 1, drums: List<Beat> = emptyList()): Project {
        val m = List(measures) { Measure(id()) }
        fun bars(content: List<Beat>) = m.mapIndexed { i, measure -> Bar(id(), measure.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) content else emptyList()) }) }
        val guitar = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = bars(beats))), SoundAssignment(presetId = preset), ChannelMix(effects = effects))
        val tracks = mutableListOf(guitar)
        if (drums.isNotEmpty()) tracks += Track(id(), "D", InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0), listOf(Staff(id(), setOf(NotationKind.PERCUSSION), bars(drums))), mix = ChannelMix(effects = neutral))
        return Project(id = id(), measures = m, tracks = tracks, tempo = listOf(TempoChange(ScorePosition(m.first().id), bpm)))
    }
    private fun note(string: Int, fret: Int, vararg a: Articulation) = Note(id(), Pitch(InstrumentDefinition().pitch(string, fret)), string, fret, articulations = a.toList())
    private fun beat(offset: Fraction, denominator: Int, vararg notes: Note) = Beat(id(), offset, WrittenDuration(denominator), notes.toList())

    /** Renders [seconds] of the project; returns the left channel. */
    private fun render(p: Project, seconds: Double, master: Boolean = false, block: Int = 256, setup: (SynthEngine) -> Unit = {}): FloatArray {
        val plan = (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
        val engine = SynthEngine(sr, 1024)
        engine.installRack(ChannelRack.build(p, sr, 1024, includeMasterFx = master))
        engine.setMix(MixState.from(p))
        engine.install(EngineProgram.prepare(plan))
        engine.play(0)
        setup(engine)
        val frames = (seconds * sr).toInt()
        val out = FloatArray(frames)
        val buf = FloatArray(block * 2)
        var done = 0
        while (done < frames) { val k = minOf(block, frames - done); engine.render(buf, k); for (i in 0 until k) out[done + i] = buf[2 * i]; done += k }
        return out
    }
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * sr).toInt(); val b = (to * sr).toInt().coerceAtMost(x.size)
        var s = 0.0; for (i in a until b) s += x[i] * x[i]
        return sqrt(s / (b - a))
    }
    /** Goertzel magnitude of [hz] in a window. */
    private fun tone(x: FloatArray, hz: Double, from: Double, to: Double): Double {
        val a = (from * sr).toInt(); val b = (to * sr).toInt()
        val w = 2 * PI * hz / sr; val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in a until b) { val win = 0.5 - 0.5 * cos(2 * PI * (i - a) / (b - a)); val s0 = x[i] * win + c * s1 - s2; s2 = s1; s1 = s0 }
        return sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / (b - a)
    }
    private fun estimatePitch(x: FloatArray, from: Double, to: Double, low: Double, high: Double): Double {
        // Fine scan of the Goertzel response; the strongest bin near the fundamental wins.
        var best = low; var bestMag = -1.0; var hz = low
        while (hz <= high) { val m = tone(x, hz, from, to); if (m > bestMag) { bestMag = m; best = hz }; hz += 0.1 }
        return best
    }

    @Test fun pluckedOpenStringSoundsAtItsPitch() {
        val out = render(project(listOf(beat(Fraction.ZERO, 1, note(1, 0)))), 1.0) // low E string of the 7-string = E2 82.41 Hz
        val pitch = estimatePitch(out, 0.2, 0.8, 78.0, 87.0)
        assertEquals(82.41, pitch, 0.4)
        assertTrue(out.all { it.isFinite() })
    }

    @Test fun highStringIsInTuneDespiteLoopFilterDelay() {
        val out = render(project(listOf(beat(Fraction.ZERO, 1, note(6, 5)))), 1.0) // A4 440 Hz
        assertEquals(440.0, estimatePitch(out, 0.1, 0.6, 430.0, 450.0), 1.0)
    }

    @Test fun palmMuteAndDeadNoteDecayMuchFasterThanOpenNote() {
        val open = render(project(listOf(beat(Fraction.ZERO, 2, note(0, 0)))), 0.9)
        val pm = render(project(listOf(beat(Fraction.ZERO, 2, note(0, 0, Articulation.Mark(NoteMark.PALM_MUTE))))), 0.9)
        val dead = render(project(listOf(beat(Fraction.ZERO, 2, note(0, 0, Articulation.Mark(NoteMark.DEAD))))), 0.9)
        val openLate = rms(open, 0.3, 0.6); val pmLate = rms(pm, 0.3, 0.6); val deadLate = rms(dead, 0.15, 0.6)
        assertTrue("open $openLate pm $pmLate", pmLate < openLate * 0.15)
        assertTrue("dead $deadLate open $openLate", deadLate < openLate * 0.02)
        assertTrue(rms(pm, 0.0, 0.05) > 0.01)
    }

    @Test fun bendMovesOnlyTheBentString() {
        val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 4), 200), PitchCurvePoint(Fraction.ONE, 200)))
        // A3 on string 3 held, string 5 (B3 246.94) bent a whole tone to C#4 277.18.
        val out = render(project(listOf(beat(Fraction.ZERO, 1, note(3, 7), note(5, 0, bend)))), 1.6)
        val held = tone(out, 220.0, 1.0, 1.5)
        val bent = tone(out, 277.18, 1.0, 1.5)
        val unbent = tone(out, 246.94, 1.0, 1.5)
        assertTrue("held $held", held > 1e-4)
        assertTrue("bent $bent unbent $unbent", bent > unbent * 4)
    }

    @Test fun naturalHarmonicSoundsOctaveAboveOpenString() {
        val out = render(project(listOf(beat(Fraction.ZERO, 1, note(0, 12, Articulation.Harmonic(HarmonicKind.NATURAL))))), 1.0)
        assertEquals(110.0, estimatePitch(out, 0.2, 0.8, 100.0, 120.0), 0.6)
    }

    @Test fun renderingIsDeterministic() {
        val p = project(listOf(beat(Fraction.ZERO, 4, note(0, 0, Articulation.Mark(NoteMark.PALM_MUTE))), beat(Fraction.of(1, 4), 4, note(2, 2), note(3, 2))), preset = "rw.guitar.highgain", effects = emptyList(),
            drums = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(36)), Note(id(), Pitch(42))))))
        assertArrayEquals(render(p, 1.0, master = true), render(p, 1.0, master = true), 0f)
    }

    @Test fun highGainChainStaysBoundedAndProducesSound() {
        val p = project(List(8) { beat(Fraction.of(it.toLong(), 8), 8, note(0, 0, Articulation.Mark(NoteMark.PALM_MUTE)), note(1, 0), note(2, 0)) }, preset = "rw.guitar.highgain", effects = emptyList())
        val out = render(p, 2.0, master = true)
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
        assertTrue(rms(out, 0.0, 1.0) > 0.02)
    }

    @Test fun drumsKitElementsAndHiHatChoke() {
        val drums = listOf(
            Beat(id(), Fraction.ZERO, WrittenDuration(8), listOf(Note(id(), Pitch(46)))),
            Beat(id(), Fraction.of(1, 8), WrittenDuration(8), listOf(Note(id(), Pitch(42)))))
        val p = project(emptyList(), drums = drums)
        val out = render(p, 1.0)
        assertTrue(rms(out, 0.0, 0.2) > 0.005)
        // Open hi-hat would ring 0.6 s; the closed hat at 0.25 s chokes it.
        assertTrue(rms(out, 0.5, 0.9) < rms(out, 0.0, 0.2) * 0.05)
    }

    @Test fun mutedTracksAreSilentAndSoloIsolates() {
        val base = project(listOf(beat(Fraction.ZERO, 1, note(0, 0))), drums = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(38))))))
        val muted = base.copy(tracks = base.tracks.map { it.copy(mix = it.mix.copy(mute = true)) })
        assertEquals(0.0, rms(render(muted, 0.5), 0.0, 0.5), 1e-9)
        val soloDrums = base.copy(tracks = base.tracks.mapIndexed { i, t -> if (i == 1) t.copy(mix = t.mix.copy(solo = true)) else t })
        val out = render(soloDrums, 1.0)
        assertTrue(tone(out, 55.0, 0.4, 0.9) < 1e-5)
    }

    @Test fun loopWrapsAtSampleAccuratePosition() {
        val p = project(listOf(beat(Fraction.ZERO, 4, note(0, 0))), measures = 2)
        var engineRef: SynthEngine? = null
        render(p, 3.0) { it.setLoop(24_000, 72_000); engineRef = it }
        val e = engineRef!!
        assertTrue(e.loopsCompleted >= 2)
        assertTrue(e.position in 24_000 until 72_000)
        // 3 s at 48 kHz = 144000 frames: 24000 until the loop end, then 48000 per pass -> 2 complete passes + 24000 into the third.
        assertEquals(24_000L + 24_000L, e.position)
    }

    @Test fun countInDelaysThePlanAndClicks() {
        val p = project(listOf(beat(Fraction.ZERO, 4, note(0, 0))))
        val plan = (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
        val engine = SynthEngine(sr, 1024)
        engine.installRack(ChannelRack.build(p, sr, 1024, includeMasterFx = false))
        engine.setMix(MixState.from(p, metronome = 1f))
        engine.install(EngineProgram.prepare(plan))
        engine.play(0, countInFrames = 48_000, countInClickOffsets = longArrayOf(0, 24_000))
        val buf = FloatArray(512)
        repeat(90) { engine.render(buf, 256) } // 23040 frames, still counting in
        assertTrue(engine.countingIn)
        assertEquals(0L, engine.position)
        repeat(200) { engine.render(buf, 256) }
        assertFalse(engine.countingIn)
        assertTrue(engine.position > 0)
    }

    @Test fun previewSoundsWithoutPlayback() {
        val p = project(emptyList())
        val engine = SynthEngine(sr, 1024)
        engine.installRack(ChannelRack.build(p, sr, 1024, includeMasterFx = false))
        engine.setMix(MixState.from(p))
        engine.preview(ScheduledNote(id(), p.tracks[0].id, 0, 12_000, Pitch(45), 100, 2, emptyList()))
        val buf = FloatArray(2048); var energy = 0.0
        repeat(10) { engine.render(buf, 1024); for (v in buf) energy += v * v }
        assertTrue(energy > 0.1)
        assertFalse(engine.playing)
    }

    @Test fun offlineMixAndStemsShareLengthAndStartFrame() {
        val p = project(listOf(beat(Fraction.ZERO, 4, note(0, 0))), drums = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(36))))))
        val options = RenderOptions(sampleRate = sr, bitDepth = 16, tailSeconds = 0.5)
        fun renderBytes(solo: Int?): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val result = OfflineMixdown().render(p, options, { b, o, l -> out.write(b, o, l) }, solo)
            assertTrue(result is Outcome.Success)
            return out.toByteArray()
        }
        val mix = renderBytes(null); val guitar = renderBytes(0); val drums = renderBytes(1)
        assertEquals(44 + (96_000 + 24_000) * 4, mix.size)
        assertEquals(mix.size, guitar.size); assertEquals(mix.size, drums.size)
        val decoded = Wav.decode(guitar)
        assertEquals(sr, decoded.sampleRate); assertEquals(120_000, decoded.frames)
        assertTrue(decoded.left.take(2000).any { abs(it) > 1e-3 })
    }

    @Test fun realtimeFactorIsComfortable() {
        val beats = List(16) { beat(Fraction.of(it.toLong(), 16), 16, note(0, 0, Articulation.Mark(NoteMark.PALM_MUTE)), note(1, 0), note(2, 0)) }
        val p = project(beats, preset = "rw.guitar.highgain", effects = emptyList(), measures = 4,
            drums = List(8) { Beat(id(), Fraction.of(it.toLong(), 8), WrittenDuration(8), listOf(Note(id(), Pitch(36)), Note(id(), Pitch(42)))) })
        render(p, 1.0, master = true)
        val start = System.nanoTime()
        render(p, 8.0, master = true)
        val seconds = (System.nanoTime() - start) / 1e9
        println("Rendered 8 s in ${"%.3f".format(seconds)} s (x${"%.1f".format(8.0 / seconds)} real time)")
        assertTrue(seconds < 8.0)
    }
}
