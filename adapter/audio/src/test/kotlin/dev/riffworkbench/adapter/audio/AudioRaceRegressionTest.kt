package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.AudioInputSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioRaceRegressionTest {
    private var n = 0
    private fun id() = EntityId("qa${n++}")
    private fun project(bars: Int): Project {
        val m = List(bars) { Measure(id()) }
        val beats = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0))))
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }
    private fun manualHost(sink: FakeSink) = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
    private val plain = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)

    /** Speed change while paused: the engine is remapped, but play() resumes from the stale UI frame of the old plan. */
    @Test fun speedChangeWhilePausedResumesAtTheSameBar() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val t = EngineTransport(host, pollMillis = 1_000_000)
        val p = project(4)
        t.prepare(p, plain); t.play(); host.renderBlock()
        t.pause(); host.renderBlock()
        t.seek(PlaybackOccurrence(ScorePosition(p.measures[2].id), 1, 2)); host.renderBlock()   // bar 3 = frame 192000 at speed 1
        t.setSpeed(0.5); host.renderBlock()                                                         // bar 3 = frame 384000 at speed 0.5
        assertEquals(384_000L, host.snapshot().planFrame)                                           // engine was remapped correctly
        t.play(); host.renderBlock()
        assertEquals("resume must stay at bar 3", 384_000L + 256, host.snapshot().planFrame)
    }

    /** stop() publishes STOPPED/0 before the audio thread drained Pause+Seek; the poller then republishes PLAYING and the old position. */
    @Test fun stopIsNotUndoneByThePoller() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val t = EngineTransport(host, pollMillis = 2)
        t.prepare(project(4), plain); t.play()
        repeat(200) { host.renderBlock() }
        Thread.sleep(40)
        t.stop()
        Thread.sleep(40)              // the render thread has not reached its next block yet (real window: up to one block)
        host.renderBlock(); Thread.sleep(40)
        val st = t.state.value.status
        t.play(); host.renderBlock()
        assertEquals(TransportStatus.STOPPED, st)
        assertTrue("play after stop must start at 0, was ${host.snapshot().planFrame}", host.snapshot().planFrame <= 256)
        t.shutdown()
    }

    /** A render thread that idle-stops closes whatever sink is current, including one a concurrent start() just opened. */
    @Test fun dyingRenderThreadDoesNotCloseTheNextSession() {
        val sinks = listOf(FakeSink(), FakeSink())
        var opened = 0
        val threads = ArrayList<Thread>()
        val host = RealtimeHost({ sinks[opened++] }, threadStarter = { name, body ->
            (if (threads.isEmpty()) Thread(body, name) else Thread {}).also { threads += it; it.start() }
        }, idleStopSeconds = 0.01)
        synchronized(host) {
            assertTrue(host.start() is Outcome.Success)
            val t1 = threads[0]
            val deadline = System.currentTimeMillis() + 5000
            // T1 idles out. Before the fix its cleanup blocked on this monitor and later closed whatever sink was current.
            while (t1.state != Thread.State.BLOCKED && t1.state != Thread.State.TERMINATED && System.currentTimeMillis() < deadline) Thread.sleep(1)
            assertFalse(host.isRunning)
            assertTrue(host.start() is Outcome.Success)   // user presses play exactly now: new sink opened
        }
        threads[0].join(2000)
        assertFalse("second sink must stay open", sinks[1].closed)
        assertTrue("host must render into the new sink", host.renderBlock())
    }

    /** InputTuner: when read() fails (device removed) the thread exits but running stays true, so start() never reopens. */
    @Test fun tunerRestartsAfterInputFailure() = runBlocking {
        var opens = 0
        val threads = ArrayList<Thread>()
        val tuner = InputTuner({
            opens++
            object : AudioInputSource {
                override fun open(preferredSampleRate: Int) = Outcome.Success(48_000)
                override fun read(buffer: FloatArray, frames: Int) = -1
                override fun close() {}
            }
        }, threadStarter = { name, body -> Thread(body, name).also { threads += it; it.start() } })
        tuner.start(null)
        threads[0].join(2000)
        tuner.start(null)
        assertEquals("second start must open a new input", 2, opens)
    }
}
