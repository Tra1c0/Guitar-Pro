package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** QA cycle 13: deterministic reproductions of the violations found by [Qa13ModelBasedTransportTest] (all red on the reviewed code). */
class Qa13TransportFindingsTest {
    private var n = 0
    private fun id() = EntityId("q13f${n++}")
    /** Every bar has a quarter note on beat 1; 480 bpm -> one 4/4 bar = 0.5 s = 24 000 frames (93.75 blocks of 256). */
    private fun project(bars: Int, projectId: EntityId = id(), measures: List<Measure> = List(bars) { Measure(id()) }, revision: Long = 0): Project {
        val track = Track(EntityId("${projectId}t"), "G", InstrumentDefinition(), listOf(Staff(EntityId("${projectId}s"), bars = measures.map { m ->
            Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0)))) else emptyList()) })
        })))
        return Project(id = projectId, revision = revision, measures = measures, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(measures[0].id), 480.0)))
    }
    private fun at(p: Project, i: Int) = PlaybackOccurrence(ScorePosition(p.measures[i].id), 1, i)
    private fun loop(p: Project, a: Int, b: Int) = LoopRange(at(p, a), at(p, b))
    private fun manualHost() = RealtimeHost({ FakeSink(latency = 0) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
    private val plain = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)
    private fun settle(host: RealtimeHost, blocks: Int = 1) { repeat(blocks) { host.renderBlock() }; Thread.sleep(40) }

    /** Model step 2-3: Play, Stop, Pause (no audio block between Stop and Pause). EngineTransport.kt:189. */
    @Test fun pauseRightAfterStopKeepsTheTransportStopped() = runBlocking {
        val host = manualHost(); val t = EngineTransport(host, pollMillis = 5)
        t.prepare(project(4), plain); t.play(); settle(host, 4)
        t.stop()
        t.pause()                        // ON_STOP / audio focus right after the Stop button
        settle(host)
        val status = t.state.value.status
        t.shutdown()
        assertEquals("pause() after stop() must not turn STOPPED into PAUSED", TransportStatus.STOPPED, status)
    }

    /** Model: Play (loop bars 1-2), Seek(bar 4) while playing. The engine leaves the loop for good and runs to the end. */
    @Test fun seekBehindTheLoopWhilePlayingKeepsLooping() = runBlocking {
        val host = manualHost(); val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        t.prepare(p, plain.copy(loop = loop(p, 0, 1))); t.play(); settle(host, 10)
        assertTrue(t.seek(at(p, 3)) is Outcome.Success)        // tap on bar 4 / "Bar on" while practising bars 1-2
        settle(host, 400)                                      // > 2 bars
        val snap = host.snapshot()
        val status = t.state.value.status
        t.shutdown()
        assertTrue("loop bars 1-2 must still be active (status=$status, ended=${snap.ended}, frame=${snap.planFrame})", snap.playing && snap.planFrame < 48_000)
    }

    /** Model: Play from bar 4, SetLoop(bars 1-2) while playing. The loop never engages; playback runs to the end and stops. */
    @Test fun loopSetBehindThePlayheadWhilePlayingIsHonoured() = runBlocking {
        val host = manualHost(); val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        t.prepare(p, plain); t.seek(at(p, 3)); t.play(); settle(host, 10)
        assertTrue(t.setLoop(loop(p, 0, 1)) is Outcome.Success)
        settle(host, 400)
        val snap = host.snapshot()
        val status = t.state.value.status
        t.shutdown()
        assertTrue("loop set while playing must take effect (status=$status, ended=${snap.ended})", snap.playing && snap.planFrame < 48_000)
    }

    /** Model: Play at bar 3, Edit (delete bar 1) while playing. Reprogram maps by route index: playhead jumps to the old bar 4. RealtimeHost.kt:145. */
    @Test fun deletingAnEarlierBarWhilePlayingKeepsThePlayheadInItsBar() = runBlocking {
        val host = manualHost(); val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        t.prepare(p, plain); t.seek(at(p, 2)); t.play(); settle(host, 10)
        val edited = project(3, p.id, p.measures.drop(1), revision = 1)
        t.prepare(edited, plain); settle(host, 2)
        val measure = t.state.value.position?.position?.measureId
        t.shutdown()
        assertEquals("playhead must stay in the bar it was playing", p.measures[2].id, measure)
    }

    /** Same, playing the last bar: the mapped route index does not exist, the engine keeps a frame past the new end and playback stops. */
    @Test fun deletingAnEarlierBarWhilePlayingTheLastBarDoesNotEndPlayback() = runBlocking {
        val host = manualHost(); val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        t.prepare(p, plain); t.seek(at(p, 3)); t.play(); settle(host, 10)
        val edited = project(3, p.id, p.measures.drop(1), revision = 1)
        t.prepare(edited, plain); settle(host, 2)
        val status = t.state.value.status
        val frame = t.state.value.sample?.frame
        t.shutdown()
        assertEquals("published frame $frame (new end 72000)", TransportStatus.PLAYING, status)
    }

    /** Model: Play, Seek(bar 6), Pause, Switch to a 2-bar project. The published position stays on the old project's bar 6. EngineTransport.kt:110-114. */
    @Test fun projectSwitchWhilePausedDoesNotKeepAForeignPosition() = runBlocking {
        val host = manualHost(); val t = EngineTransport(host, pollMillis = 5)
        val a = project(6)
        t.prepare(a, plain); t.play(); settle(host, 4); t.seek(at(a, 5)); settle(host, 4); t.pause(); settle(host)
        val b = project(2)
        t.prepare(b, plain); settle(host)
        val measure = t.state.value.position?.position?.measureId
        t.shutdown()
        assertTrue("published position $measure must belong to the prepared project", measure == null || b.measures.any { it.id == measure })
    }

    /** Model: Play, SetLoop(bars 3-4), Stop, SetLoop(bars 2-4), Play: starts at bar 3 (old loop start) instead of bar 2. EngineTransport.kt:200-201/166-169. */
    @Test fun playAfterStopStartsAtTheCurrentLoopStart() = runBlocking {
        val host = manualHost(); val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        t.prepare(p, plain); t.play(); settle(host, 4)
        t.setLoop(loop(p, 2, 3)); t.stop(); settle(host)
        t.setLoop(loop(p, 1, 3))
        t.play(); settle(host)
        val measure = t.state.value.position?.position?.measureId
        t.shutdown()
        assertEquals("after stop, play starts at the loop start", p.measures[1].id, measure)
    }

    /** Model: loop bars 3-4, Play, Stop, Edit (delete bar 1), Play: restart point remapped by route index lands on bar 4. */
    @Test fun editWhileStoppedKeepsTheRestartAtTheLoopStart() = runBlocking {
        val host = manualHost(); val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        val settings = plain.copy(loop = loop(p, 2, 3))
        t.prepare(p, settings); t.play(); settle(host, 4); t.stop(); settle(host)
        val edited = project(3, p.id, p.measures.drop(1), revision = 1)
        t.prepare(edited, settings)
        t.play(); settle(host)
        val measure = t.state.value.position?.position?.measureId
        t.shutdown()
        assertEquals("after stop, play starts at the loop start (bar ${p.measures[2].id})", p.measures[2].id, measure)
    }

    /** Model: Play with loop bar 1 and render across the wrap: the snapshot extrapolates past the loop end. RealtimeHost.kt:201. */
    @Test fun publishedFrameNeverPassesTheLoopEnd() = runBlocking {
        val host = manualHost(); val t = EngineTransport(host, pollMillis = 5)
        val p = project(2)
        t.prepare(p, plain.copy(loop = loop(p, 0, 0))); t.play()
        settle(host, 94)                 // block 94 starts at 23 808 and wraps at 24 000
        val frame = t.state.value.sample?.frame
        t.shutdown()
        assertTrue("published frame $frame must lie inside the loop [0, 24000)", frame != null && frame < 24_000)
    }
}
