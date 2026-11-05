package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import org.junit.Assert.*
import org.junit.Test

class SynthRegressionTest {
    private var n = 0
    private fun id() = EntityId("q${n++}")
    private val sr = 48_000
    private val neutral = listOf(EffectUnit(EntityId("fx-eq"), EffectKind.EQ))

    private fun guitarTrack(beats: List<Beat>, m: List<Measure>, mix: ChannelMix = ChannelMix(effects = neutral)) =
        Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })),
            SoundAssignment(presetId = "rw.guitar.clean"), mix)
    private fun drumTrack(beats: List<Beat>, m: List<Measure>) =
        Track(id(), "D", InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0),
            listOf(Staff(id(), setOf(NotationKind.PERCUSSION), m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })), mix = ChannelMix(effects = neutral))
    private fun project(tracks: (List<Measure>) -> List<Track>, measures: Int = 1): Project {
        val m = List(measures) { Measure(id()) }
        return Project(id = id(), measures = m, tracks = tracks(m), tempo = listOf(TempoChange(ScorePosition(m.first().id), 120.0)))
    }
    private fun engineFor(p: Project): SynthEngine {
        val plan = (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
        val e = SynthEngine(sr, 1024)
        e.installRack(ChannelRack.build(p, sr, 1024, includeMasterFx = true))
        e.setMix(MixState.from(p))
        e.install(EngineProgram.prepare(plan))
        e.play(0)
        return e
    }
    private fun run(e: SynthEngine, seconds: Double, sink: (FloatArray, Int) -> Unit = { _, _ -> }) {
        val buf = FloatArray(512); var left = (seconds * sr).toInt()
        while (left > 0) { val k = minOf(256, left); e.render(buf, k); sink(buf, k); left -= k }
    }

    /** DrumVoice: a cowbell has noiseLevel 0, so noiseEnv never decays and the voice never deactivates. */
    @Test fun cowbellVoiceEndsAfterItDecays() {
        val p = project({ m -> listOf(drumTrack(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(16), listOf(Note(id(), Pitch(56))))), m)) })
        val e = engineFor(p)
        run(e, 10.0)
        assertEquals("cowbell (0.22 s decay) still holds a voice after 10 s", 0, e.voicesInUse)
    }

    /** Notes without a string on a guitar track all map to key t*16+15, so each one cuts the previous one. */
    @Test fun chordWithoutStringNumbersKeepsAllNotes() {
        val chord = listOf(Note(id(), Pitch(40)), Note(id(), Pitch(47)), Note(id(), Pitch(52)))
        val p = project({ m -> listOf(guitarTrack(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(1), chord)), m)) })
        val e = engineFor(p)
        run(e, 0.2)
        assertEquals("three-note chord should hold three voices", 3, e.voicesInUse)
    }

    /** Stolen voice keeps the old string's map entry; the next pick on that string kills the thief's note. */
    @Test fun voiceStealingDoesNotLeaveStaleStringMapEntries() {
        val p = project({ m -> List(5) { guitarTrack(emptyList(), m) } })
        val e = SynthEngine(sr, 1024)
        e.installRack(ChannelRack.build(p, sr, 1024, includeMasterFx = false))
        e.setMix(MixState.from(p))
        fun pick(track: Int, string: Int) = e.preview(ScheduledNote(id(), id(), 0, 10 * sr.toLong(), Pitch(40 + string), 100, string, emptyList(), trackIndex = track))
        val buf = FloatArray(64)
        pick(0, 0); e.render(buf, 16)                           // oldest voice: track 0 / string 0
        for (t in 1..4) for (s in 0 until 16) pick(t, s)        // 64 more voices
        for (s in 1..7) pick(0, s)                              // pool of 72 is full
        e.render(buf, 16)                                       // later start clock: string 8 becomes the newest voice
        pick(0, 8)                                              // steals the voice of track 0 / string 0
        pick(0, 0)                                              // new pick on string 0 -> stale map entry -> releases string 8
        e.render(buf, 32)
        val strings = SynthEngine::class.java.getDeclaredField("strings").apply { isAccessible = true }.get(e) as Array<*>
        val string8 = strings.count { v -> v as StringVoice; v.active && !v.releasing && v.track == 0 && v.string == 8 }
        assertEquals("the newest note (track 0, string 8) must still sound", 1, string8)
    }

    /** MixState.from does not sanitise non-finite volume/pan (Effects.p does for effect parameters). */
    @Test fun nonFiniteTrackPanDoesNotPoisonTheMaster() {
        val p = project({ m -> listOf(guitarTrack(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(1), listOf(Note(id(), Pitch(40), 0, 7)))), m, ChannelMix(pan = Double.NaN, effects = neutral))) })
        val e = engineFor(p)
        var nan = 0
        run(e, 0.5) { b, k -> for (i in 0 until 2 * k) if (!b[i].isFinite()) nan++ }
        assertEquals("non-finite output samples", 0, nan)
    }

    /** A degenerate loop (shorter than the cubic kernel) is rejected when the bank is read instead of overrunning on the render thread. */
    @Test fun degenerateLoopIsRejectedAtLoadTime() {
        val frames = 400
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun s32(v: Int) { for (k in 0 until 4) out.write((v ushr (8 * k)) and 0xFF) }
        out.write("RWSB".toByteArray()); u16(1); s32(44_100); s32(1)
        out.write(byteArrayOf(0, 60, 0, 127, 0, 0)); s32(0f.toBits()); s32(frames - 1); s32(frames); s32(frames)
        repeat(frames) { u16(((it % 50) - 25) * 100) }
        assertTrue(runCatching { SampleBank.read(out.toByteArray()) }.isFailure)
    }

    /** Pitch jumps larger than the loop (extreme bend on a short loop) stay inside the data. */
    @Test fun shortLoopWithLargeStepsStaysInBounds() {
        val bank = SampleBank.synthetic(seconds = 0.05)
        val p = project({ m -> listOf(guitarTrack(emptyList(), m)) })
        val e = SynthEngine(sr, 1024, bank)
        e.installRack(ChannelRack.build(p, sr, 1024, includeMasterFx = false))
        e.setMix(MixState.from(p))
        e.preview(ScheduledNote(id(), id(), 0, sr.toLong(), Pitch(127), 100, 0, emptyList()))
        run(e, 0.5)
    }
}
