package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 18 / D5 (start position). After playback ran to its natural end the poller publishes STOPPED (EngineTransport.kt:394).
 * togglePlay() then seeks to the edit cursor and calls play(). play() reads `host.snapshot().ended`
 * (EngineTransport.kt:195-196: `if (snap.ended || from >= totalFrames) from = loopFrames?.first ?: 0L`), but the snapshot's
 * `ended` flag comes from the last *rendered* block (RealtimeHost.kt:171/208, `lastFlags`) and the engine clears it only
 * when the render thread has processed the posted Seek (SynthEngine.kt:193). Until a block has been rendered after the
 * seek, play() discards the cursor and restarts at frame 0 (or the loop start).
 *
 * On a device the window is the time between transport.seek() and play()'s snapshot (microseconds, same coroutine)
 * against one audio block (~4 ms at 192 frames / 48 kHz): the restart from the top is the likely outcome.
 */
class Qa18D5PlayAfterEndTest {
    private var n = 0
    private fun id() = EntityId("d5-${n++}")
    private fun project(bars: Int): Project {
        val m = List(bars) { Measure(id()) }
        val beats = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0))))
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }
    private val settings = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)

    private fun awaitStatus(t: EngineTransport, status: TransportStatus, what: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (t.state.value.status != status) { Thread.sleep(5); if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for $status: $what (is ${t.state.value.status})") }
    }

    /** Plays the whole 4-bar project to its end exactly as a device would (render thread keeps running, poller publishes STOPPED). */
    private fun playToTheEnd(t: EngineTransport, host: RealtimeHost, p: Project) = runBlocking {
        t.prepare(p, settings)
        t.play()
        awaitStatus(t, TransportStatus.PLAYING, "play")
        var blocks = 0
        while (!host.snapshot().ended && blocks < 20_000) { host.renderBlock(); blocks++ }
        assertTrue("engine should reach the end of the plan", host.snapshot().ended)
        awaitStatus(t, TransportStatus.STOPPED, "natural end")
        // The idle render thread keeps producing (silent) blocks and keeps reporting `ended` from the last block.
        repeat(3) { host.renderBlock() }
        assertTrue(host.snapshot().ended)
    }

    @Test fun afterANaturalEndPlayStartsAtTheSeekTargetNotAtTheTop() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        playToTheEnd(t, host, p)
        // WorkspaceViewModel.togglePlay(): status != PAUSED -> seek(cursor) then play(since). The cursor sits in bar 3.
        val cursor = PlaybackOccurrence(ScorePosition(p.measures[2].id), 1, 2)
        t.seek(cursor)
        assertEquals("seek is published", p.measures[2].id, t.state.value.position?.position?.measureId)
        // No audio block between the two calls: on the device both run back to back in one coroutine (~µs) against a ~4 ms block.
        t.play()
        val started = t.state.value.position?.position?.measureId
        assertEquals("Play after a natural end must start at the cursor (bar 3), not at the top", p.measures[2].id, started)
    }

    /** Same sequence, but one block is rendered between seek and play: then the seek target wins. Shows the race window. */
    @Test fun oneRenderedBlockBetweenSeekAndPlayMakesTheSeekTargetWin() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        playToTheEnd(t, host, p)
        t.seek(PlaybackOccurrence(ScorePosition(p.measures[2].id), 1, 2))
        host.renderBlock()          // the render thread processed the Seek: engine.ended is false again
        t.play()
        assertEquals(p.measures[2].id, t.state.value.position?.position?.measureId)
    }

    /** Explicit stop() has no such problem: stop() seeks the engine to 0 and the later seek(cursor) wins (restartFromTop cleared). */
    @Test fun afterAnExplicitStopPlayStartsAtTheSeekTarget() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        t.prepare(p, settings); t.play(); awaitStatus(t, TransportStatus.PLAYING, "play")
        repeat(100) { host.renderBlock() }
        t.stop(); host.renderBlock(); awaitStatus(t, TransportStatus.STOPPED, "stop")
        t.seek(PlaybackOccurrence(ScorePosition(p.measures[2].id), 1, 2))
        t.play()
        assertEquals(p.measures[2].id, t.state.value.position?.position?.measureId)
    }
}
