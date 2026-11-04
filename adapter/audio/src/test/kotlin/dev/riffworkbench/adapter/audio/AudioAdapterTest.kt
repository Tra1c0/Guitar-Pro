package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.AudioSink
import dev.riffworkbench.ports.SinkFormat
import dev.riffworkbench.synth.TonePresets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Deterministic device: accepts every block immediately and "presents" it after a fixed latency. */
class FakeSink(private val rate: Int = 48_000, private val block: Int = 256, private val latency: Int = 2048) : AudioSink {
    var frames = 0L; var opened = false; var closed = false
    val captured = ArrayList<Float>()
    var capture = false
    override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> { opened = true; return Outcome.Success(SinkFormat(rate, block, latency, "fake")) }
    override fun write(buffer: FloatArray, frames: Int): Int { if (capture) for (i in 0 until frames) captured += buffer[2 * i]; this.frames += frames; return frames }
    override fun presentedFrame(): Long = (frames - latency).coerceAtLeast(0)
    override fun close() { closed = true }
}

class AudioAdapterTest {
    private var n = 0
    private fun id() = EntityId("a${n++}")
    private fun project(bars: Int = 2): Project {
        val m = List(bars) { Measure(id()) }
        val beats = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0))))
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }
    private fun manualHost(sink: FakeSink) = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)

    @Test fun transportPlaysFromTheSampleClockAndReportsMusicalPosition() = runBlocking {
        val sink = FakeSink()
        val host = manualHost(sink)
        val transport = EngineTransport(host, pollMillis = 1_000_000)
        val p = project()
        transport.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false))
        assertFalse("prepare must not open the device", sink.opened)
        assertTrue(transport.play() is Outcome.Success)
        assertTrue(sink.opened)
        repeat(400) { host.renderBlock() } // 102400 frames written, 100352 presented
        val snap = host.snapshot()
        assertTrue(snap.playing)
        assertEquals(100_352L, snap.planFrame)
        transport.pause()
        host.renderBlock()
        assertFalse(host.snapshot().playing)
    }

    @Test fun loopAndSpeedTrainerRaiseTempoAfterRepetitions() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val transport = EngineTransport(host, pollMillis = 1_000_000)
        val p = project()
        val loop = LoopRange(PlaybackOccurrence(ScorePosition(p.measures[0].id), 1, 0), PlaybackOccurrence(ScorePosition(p.measures[0].id), 1, 0))
        transport.prepare(p, PracticeSettings(speed = 0.5, countInBars = 0, metronome = false, loop = loop, trainer = SpeedTrainer(enabled = true, startSpeed = 0.5, endSpeed = 1.0, increment = 0.25, repetitions = 1)))
        transport.play()
        repeat(800) { host.renderBlock() } // one bar at half speed = 4 s = 192000 frames; 800 blocks = 204800
        val snap = host.snapshot()
        assertEquals(1L, snap.loopsCompleted)
        assertTrue(snap.planFrame < 192_000)
    }

    @Test fun countInClicksBeforeThePlanStarts() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val transport = EngineTransport(host, pollMillis = 1_000_000)
        transport.prepare(project(), PracticeSettings(speed = 1.0, countInBars = 1, metronome = true))
        transport.play()
        repeat(300) { host.renderBlock() } // 76800 frames < one 4/4 bar at 120 BPM (96000)
        assertTrue(host.snapshot().countingIn)
        assertEquals(0L, host.snapshot().planFrame)
        repeat(200) { host.renderBlock() }
        assertFalse(host.snapshot().countingIn)
        assertTrue(host.snapshot().planFrame > 0)
    }

    @Test fun previewSoundsThroughTheTrackChannel() = runBlocking {
        val sink = FakeSink(latency = 0).apply { capture = true }
        val host = manualHost(sink)
        val transport = EngineTransport(host, pollMillis = 1_000_000)
        val p = project()
        val note = Note(id(), Pitch(52), 1, 12)
        assertTrue(transport.preview(p, p.tracks[0].id, note) is Outcome.Success)
        repeat(40) { host.renderBlock() }
        assertTrue(sink.captured.any { abs(it) > 1e-3f })
    }

    @Test fun offlineRenderReturnsMixAndOneStemPerTrack() = runBlocking {
        val transport = EngineTransport(manualHost(FakeSink()))
        val p = project(1)
        val result = transport.render(p, RenderOptions(bitDepth = 16, tailSeconds = 0.2)) as Outcome.Success
        assertEquals(2, result.value.size)
        assertTrue(result.value.all { it.mediaType == "audio/wav" && it.bytes.size == result.value[0].bytes.size })
    }

    @Test fun yinDetectsLowAStringAndHighE() {
        val rate = 12_000
        val yin = YinPitchDetector(rate, 1024)
        for (hz in listOf(55.0, 82.41, 110.0, 329.63, 659.26)) {
            val x = FloatArray(1024) { (sin(2 * PI * hz * it / rate) * 0.6 + 0.25 * sin(4 * PI * hz * it / rate)).toFloat() }
            val e = yin.detect(x)!!
            assertEquals("hz $hz", hz, e.hz, hz * 0.004)
        }
        val reading = YinPitchDetector.reading(442.0, 0.9, 0)
        assertEquals(69, reading.pitch.midiNumber)
        assertEquals(7.85, reading.deviationCents, 0.05)
    }

    @Test fun presetsCoverEveryInstrumentKind() {
        InstrumentKind.entries.forEach { kind ->
            val track = Track(id(), "t", InstrumentDefinition(kind, if (kind == InstrumentKind.GUITAR || kind == InstrumentKind.BASS) listOf(40, 45, 50, 55) else emptyList(), if (kind == InstrumentKind.GUITAR || kind == InstrumentKind.BASS) List(4) { 0 } else emptyList()))
            assertTrue(TonePresets.effectiveChain(track).isNotEmpty())
        }
    }
}
