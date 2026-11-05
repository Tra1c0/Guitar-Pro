package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 18 / D5 follow-up (CI flake of Qa18StartPositionViewModelTest.r1): the poller publishes the engine's presented block.
 * After a seek that block still belongs to the old playback until the render thread has applied the Seek and the block has
 * reached the speaker, so the poller wrote the old playhead back over the seek position: on a device the playhead jumps
 * back for a poll (Bericht 1 §4 "springt hin und her"), in a test that pauses the render thread it stays wrong.
 * Red before the seek counter in [HostSnapshot.seeks] and the write epoch in EngineTransport's poller.
 */
class Qa18SeekPublishingTest {
    private var n = 0
    private fun id() = EntityId("sk-${n++}")
    private fun project(bars: Int): Project {
        val m = List(bars) { Measure(id()) }
        val beats = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0))))
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }
    private val settings = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)
    private fun awaitStatus(t: EngineTransport, status: TransportStatus) {
        val deadline = System.currentTimeMillis() + 5_000
        while (t.state.value.status != status) { Thread.sleep(5); if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for $status (is ${t.state.value.status})") }
    }
    private fun bar(t: EngineTransport, p: Project) = p.measures.indexOfFirst { it.id == t.state.value.position?.position?.measureId } + 1

    /** Seek while playing: the published playhead never goes back to the pre-seek bar, however many polls pass before the render thread catches up. */
    @Test fun aSeekWhilePlayingIsNotOverwrittenByTheStaleSnapshot() = runBlocking {
        val host = RealtimeHost({ FakeSink(latency = 0) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 2)
        val p = project(4)
        t.prepare(p, settings); t.play(); awaitStatus(t, TransportStatus.PLAYING)
        repeat(20) { host.renderBlock() }
        Thread.sleep(30)
        assertEquals(1, bar(t, p))
        t.seek(PlaybackOccurrence(ScorePosition(p.measures[2].id), 1, 2))
        assertEquals(3, bar(t, p))
        // The render thread has not applied the Seek yet (no block rendered): dozens of polls see the old block.
        val seen = HashSet<Int>()
        val until = System.currentTimeMillis() + 150
        while (System.currentTimeMillis() < until) { seen += bar(t, p); Thread.sleep(1) }
        assertEquals("playhead must stay at the seek target while the engine catches up, saw bars $seen", setOf(3), seen)
        // Once rendered and presented, the poller follows the engine again, from bar 3 on.
        repeat(4) { host.renderBlock() }
        val deadline = System.currentTimeMillis() + 2_000
        while (t.state.value.sample?.frame?.let { it > 0 && host.snapshot().seeks >= 1 } != true) { Thread.sleep(2); if (System.currentTimeMillis() > deadline) break }
        assertEquals(3, bar(t, p))
        assertTrue(host.snapshot().seeks >= 1)
        t.shutdown()
    }

    /** Pause right after a poll began: the poll's PLAYING must not land over the PAUSED the caller published. */
    @Test fun aPauseIsNotUndoneByAPollThatStartedBeforeIt() = runBlocking {
        val host = RealtimeHost({ FakeSink(latency = 0) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 1)
        val p = project(4)
        t.prepare(p, settings)
        repeat(50) { round ->
            t.play(); awaitStatus(t, TransportStatus.PLAYING)
            repeat(3) { host.renderBlock() }
            t.pause()
            // Whatever the poller does with the block it already read, the published status stays PAUSED.
            Thread.sleep(6)
            assertEquals("round $round", TransportStatus.PAUSED, t.state.value.status)
            host.renderBlock()
        }
        t.shutdown()
    }
}
