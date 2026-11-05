package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device report: after tapping a note and pressing Play the score view jumped back and forth. One cause: until the
 * render thread has processed the posted Play command (one audio block), the poller saw a running host that does not
 * play yet and downgraded PLAYING to PAUSED, which switched playhead-follow off and on again.
 */
class PlayStartRegressionTest {
    private var n = 0
    private fun id() = EntityId("p${n++}")
    private fun project(bars: Int): Project {
        val m = List(bars) { Measure(id()) }
        val beats = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0))))
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }

    @Test fun statusStaysPlayingUntilTheRenderThreadHasStarted() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false))
        t.seek(PlaybackOccurrence(ScorePosition(p.measures[2].id), 1, 2))
        t.play()
        val seen = mutableSetOf<TransportStatus>()
        repeat(20) { seen += t.state.value.status; Thread.sleep(5) }
        assertEquals("no PLAYING→PAUSED flicker before the first audio block", setOf(TransportStatus.PLAYING), seen)
        host.renderBlock()
        repeat(10) { seen += t.state.value.status; Thread.sleep(5) }
        assertEquals(setOf(TransportStatus.PLAYING), seen)
    }

    @Test fun publishedPositionIsTheSeekTargetNotTheOldFrameWhileStarting() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false))
        t.play(); repeat(4) { host.renderBlock() }; t.pause(); host.renderBlock(); Thread.sleep(30)
        t.seek(PlaybackOccurrence(ScorePosition(p.measures[2].id), 1, 2))
        val target = t.state.value.position?.position?.measureId
        t.play()
        val seen = mutableSetOf<EntityId?>()
        repeat(20) { seen += t.state.value.position?.position?.measureId; Thread.sleep(5) }
        assertEquals("playhead must not jump back to the old position before the first block", setOf(target), seen)
    }
}
