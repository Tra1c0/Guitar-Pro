package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Defects found by measurement/stress testing (QA cycle 2): hangs, NaN poisoning, loop and count-in races. */
class AudioStressRegressionTest {
    private var n = 0
    private fun id() = EntityId("as${n++}")
    private val sr = 48_000
    private val bank: SampleBank by lazy {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.first { it.isFile }
        SampleBank.read(f.readBytes())
    }
    private fun plan(p: Project, speed: Double = 1.0) = (ScorePlaybackCompiler().compileNow(p, sr, PlaybackOptions(speed)) as Outcome.Success).value
    private fun oneNote(note: Note, preset: String = "rw.guitar.clean", bars: Int = 1): Project {
        val m = List(bars) { Measure(id()) }
        val t = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(note))) else emptyList()) }) })),
            SoundAssignment(presetId = preset))
        return Project(id = id(), measures = m, tracks = listOf(t), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }
    private fun engine(p: Project, b: SampleBank?) = SynthEngine(sr, 1024, b).apply {
        installRack(ChannelRack.build(p, sr, 1024)); setMix(MixState.from(p)); install(EngineProgram.prepare(plan(p))); play(0)
    }

    /** A pitch far outside MIDI (unchecked cents in a file) hung the sampled voice's loop wrap forever. */
    @Test(timeout = 20_000) fun hugePitchRendersInBoundedTime() {
        for (pitch in listOf(Pitch(60, 100_000), Pitch(1000), Pitch(60, Int.MAX_VALUE))) {
            val e = engine(oneNote(Note(id(), pitch, 2, 5)), bank)
            val out = FloatArray(384)
            repeat(500) { e.render(out, 192); for (x in out) assertTrue("non-finite for $pitch", x.isFinite()) }
        }
    }

    /** A NaN vibrato rate (importer/programmatic model) turned the whole mix NaN for seconds and the channel for good. */
    @Test fun nanVibratoDoesNotPoisonTheMix() {
        val p = oneNote(Note(id(), Pitch(InstrumentDefinition().pitch(2, 5)), 2, 5, articulations = listOf(Articulation.Vibrato(40, Double.NaN))), "rw.guitar.highgain", bars = 2)
        val e = engine(p, null)
        val out = FloatArray(512)
        repeat(4 * sr / 256) { e.render(out, 256); for (x in out) assertTrue(x.isFinite()) }
    }

    /** Engine-level guard: notes with non-finite parameters are skipped instead of corrupting voice and effect state. */
    @Test fun nonFiniteScheduledNotesAreIgnored() {
        val p = oneNote(Note(id(), Pitch(52), 1, 7))
        for (b in listOf(null, bank)) {
            val e = engine(p, b)
            e.preview(ScheduledNote(id(), id(), 0, 24_000, Pitch(52), 100, 1, emptyList(), soundingPitch = Double.NaN))
            e.preview(ScheduledNote(id(), id(), 0, 24_000, Pitch(52), 100, 2, emptyList(), soundingPitch = Double.POSITIVE_INFINITY))
            e.preview(ScheduledNote(id(), id(), 0, 24_000, Pitch(52), 100, 3, emptyList(), curve = listOf(CurvePoint(0, Double.NaN))))
            e.preview(ScheduledNote(id(), id(), 0, 24_000, Pitch(52), 100, 4, emptyList(), vibrato = VibratoSpec(30.0, Double.NaN, 0)))
            val out = FloatArray(512)
            repeat(200) { e.render(out, 256); for (x in out) assertTrue(x.isFinite()) }
        }
    }

    private fun manualHost(sink: FakeSink) = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
    private fun bars(count: Int): Project {
        val m = List(count) { Measure(id()) }
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.map { b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0) listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0)))) else emptyList()) }) })),
            SoundAssignment(presetId = "rw.guitar.clean"))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }

    /** Reprogram carries the new loop: a block rendered right after it must not wrap to the old loop start. */
    @Test fun reprogramAppliesTheNewLoopInTheSameBlock() = runBlocking {
        val host = manualHost(FakeSink(latency = 0))
        val t = EngineTransport(host, pollMillis = 1_000_000)
        val p = bars(6)
        val loop = LoopRange(PlaybackOccurrence(ScorePosition(p.measures[2].id), 0, 2), PlaybackOccurrence(ScorePosition(p.measures[3].id, Fraction.ONE), 0, 3))
        t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false, loop = loop)); t.play()
        repeat(1000) { host.renderBlock() }
        val before = host.snapshot()
        val plan1 = plan(p, 1.0); val plan05 = plan(p, 0.5)
        val start05 = plan05.frameOf(2)!!; val end05 = plan05.spans[3].endFrame
        host.post(HostCommand.Reprogram(EngineProgram.prepare(plan05), plan1, start05, end05))
        host.renderBlock()
        val after = host.snapshot()
        assertEquals("no spurious loop pass", before.loopsCompleted, after.loopsCompleted)
        assertTrue("playhead ${after.planFrame} inside [$start05, $end05)", after.planFrame in start05 until end05)
        t.shutdown()
    }

    /** The transport sends the loop with the Reprogram (the first command drained already carries it). */
    @Test fun setSpeedWhileLoopingPostsTheLoopWithTheReprogram() = runBlocking {
        val host = manualHost(FakeSink(latency = 0))
        val t = EngineTransport(host, pollMillis = 1_000_000)
        val p = bars(6)
        val loop = LoopRange(PlaybackOccurrence(ScorePosition(p.measures[2].id), 0, 2), PlaybackOccurrence(ScorePosition(p.measures[3].id, Fraction.ONE), 0, 3))
        t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false, loop = loop)); t.play()
        repeat(1000) { host.renderBlock() }
        t.setSpeed(0.5)
        val queue = RealtimeHost::class.java.getDeclaredField("commands").apply { isAccessible = true }.get(host) as java.util.Queue<*>
        val reprogram = queue.filterIsInstance<HostCommand.Reprogram>().single()
        val plan05 = plan(p, 0.5)
        assertEquals(plan05.frameOf(2)!! to plan05.spans[3].endFrame, reprogram.loopStart to reprogram.loopEnd)
        t.shutdown()
    }

    @Test fun speedChangeAndEditDuringCountInKeepCountingIn() = runBlocking {
        val host = manualHost(FakeSink(latency = 0))
        val t = EngineTransport(host, pollMillis = 1_000_000)
        val settings = PracticeSettings(speed = 1.0, countInBars = 1, metronome = true)
        val p = bars(4)
        t.prepare(p, settings); t.play()
        repeat(50) { host.renderBlock() }
        assertTrue(host.snapshot().countingIn)
        t.setSpeed(0.8); host.renderBlock()
        assertTrue("speed change dropped the count-in", host.snapshot().countingIn)
        t.prepare(p.copy(revision = 1), settings.copy(speed = 0.8)); host.renderBlock()
        assertTrue("edit dropped the count-in", host.snapshot().countingIn)
        t.shutdown()
    }
}
