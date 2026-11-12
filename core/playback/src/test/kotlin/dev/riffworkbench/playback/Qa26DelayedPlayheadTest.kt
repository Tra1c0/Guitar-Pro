package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 26: the visible playhead on a Bluetooth output runs the calibrated extra latency behind the transport, only while
 * PLAYING; play start, seek and loop wrap show their target at once and then wait (never before it), stop/pause show the
 * transport position, a delay change never moves it backwards. Frames map to bar/offset through the plan's tempo map.
 */
class Qa26DelayedPlayheadTest {
    private val sr = 48_000
    /** 4 bars 4/4 at 120 BPM (one bar = 2 s = 96 000 frames), then 4 bars at 60 BPM. */
    private val plan: PlaybackPlan = run {
        val measures = List(8) { Measure(EntityId("q26m$it")) }
        val p = Project(id = EntityId("q26"), measures = measures,
            tempo = listOf(TempoChange(ScorePosition(measures[0].id), 120.0), TempoChange(ScorePosition(measures[4].id), 60.0)))
        (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
    }
    private fun nanos(ms: Double) = Math.round(ms * 1_000_000)
    private fun playing(frame: Long, countingIn: Boolean = false) =
        TransportState(TransportStatus.PLAYING, plan.positionAt(frame), SamplePosition(frame, sr), reason = null, countingIn = countingIn)

    @Test fun delayIsAppliedInFramesThroughTheTempoMap() {
        assertEquals(96_000L, plan.spans[1].startFrame)
        assertEquals(4 * 96_000L + 192_000L, plan.spans[5].startFrame)
        val d = DelayedPlayhead()
        // Play from bar 1, polls every 33 ms, 200 ms delay = 9 600 frames.
        var shown = 0L
        for (i in 0..120) {
            val t = i * 33.0
            val raw = Math.round(t * 48)
            shown = d.displayedFrame(TransportStatus.PLAYING, false, raw, sr, 200.0, nanos(t))
            assertEquals("t=$t", maxOf(0L, raw - 9_600), shown)
        }
        // Position: bar 2 at 120 BPM — 200 ms are 1/10 of a whole note (a whole note lasts 2 s).
        val d2 = DelayedPlayhead()
        d2.position(playing(96_000), plan, 200.0, nanos(0.0))
        val at = d2.position(playing(96_000 + 48_000), plan, 200.0, nanos(1000.0))!!
        assertEquals(plan.spans[1].measureId, at.position.measureId)
        assertEquals(0.4, at.position.offset.toDouble(), 1.0 / 3840)   // (1 s − 0.2 s) / 2 s
        // In the 60-BPM part the same 200 ms are 1/20 of a whole note.
        val d3 = DelayedPlayhead()
        val start = plan.spans[5].startFrame
        d3.position(playing(start), plan, 200.0, nanos(0.0))
        val slow = d3.position(playing(start + 48_000), plan, 200.0, nanos(1000.0))!!
        assertEquals(plan.spans[5].measureId, slow.position.measureId)
        assertEquals(0.2, slow.position.offset.toDouble(), 1.0 / 3840)  // (1 s − 0.2 s) / 4 s
    }

    @Test fun withoutDelayOrOutsidePlayingTheTransportPositionIsShownUnchanged() {
        val d = DelayedPlayhead()
        val s = playing(50_000)
        assertSame(s.position, d.position(s, plan, 0.0, nanos(0.0)))
        assertSame(s.position, d.position(playing(51_000).copy(position = s.position), plan, 0.0, nanos(20.0)))
        val counting = playing(-20_000, countingIn = true)
        assertSame(counting.position, d.position(counting, plan, 250.0, nanos(40.0)))
        val paused = s.copy(status = TransportStatus.PAUSED)
        assertSame(paused.position, d.position(paused, plan, 250.0, nanos(60.0)))
        assertNull(d.position(s.copy(status = TransportStatus.STOPPED), plan, 250.0, nanos(80.0)))
        // No plan or another rate: transport position.
        val e = DelayedPlayhead(); e.position(playing(0), null, 200.0, 0)
        val later = playing(48_000)
        assertSame(later.position, e.position(later, null, 200.0, nanos(1000.0)))
        assertSame(later.position, e.position(later.copy(sample = SamplePosition(96_000, 96_000)), plan, 200.0, nanos(1000.0)))
    }

    @Test fun seekStopAndLoopShowTheTargetAtOnceAndThenWait() {
        val d = DelayedPlayhead()
        val delay = 300.0   // 14 400 frames
        var t = 0.0
        fun step(raw: Long, status: TransportStatus = TransportStatus.PLAYING) = d.displayedFrame(status, false, raw, sr, delay, nanos(t))
        assertEquals(0L, step(0))
        t = 500.0; assertEquals(24_000L - 14_400, step(24_000))
        // Forward seek to bar 3: the target at once, then held until the delayed position passes it.
        t = 533.0; assertEquals(192_000L, step(192_000))
        t = 566.0; assertEquals(192_000L, step(192_000 + 1_584))
        t = 833.0; assertEquals(192_000L, step(192_000 + 14_400))
        t = 866.0; assertEquals(192_000L + 1_584, step(192_000 + 14_400 + 1_584))
        // Backward seek (or a loop wrap): the target at once.
        t = 900.0; assertEquals(96_000L, step(96_000))
        t = 933.0; assertEquals(96_000L, step(97_584))
        // Stop / pause: the transport position at once (no delay outside PLAYING).
        t = 966.0; assertEquals(0L, step(0, TransportStatus.STOPPED))
        t = 1000.0; assertEquals(99_000L, step(99_000, TransportStatus.PAUSED))
        // Play again: starts at the start position, never before it.
        t = 1033.0; assertEquals(99_000L, step(99_000))
        t = 1066.0; assertEquals(99_000L, step(100_584))
    }

    @Test fun neverBackwardsBetweenSeeksUnderJitterDelayChangesAndStalls() {
        val random = Random(26)
        repeat(200) { run ->
            val d = DelayedPlayhead()
            var t = 0.0
            var raw = random.nextLong(0, 400_000)
            var delay = listOf(0.0, 120.0, 250.0, 400.0).random(random)
            var last = d.displayedFrame(TransportStatus.PLAYING, false, raw, sr, delay, nanos(t))
            assertEquals(raw, last)
            var anchor = raw
            repeat(400) {
                val dt = 33.0 + random.nextDouble(-12.0, 12.0) + if (random.nextInt(40) == 0) random.nextDouble(0.0, 150.0) else 0.0   // poll jitter, UI stalls
                t += dt
                // Audio clock jitter of ±3 ms against the wall clock.
                val previous = raw
                raw += Math.round(dt * 48 + random.nextDouble(-144.0, 144.0)).coerceAtLeast(0)
                if (random.nextInt(25) == 0) delay = listOf(0.0, 120.0, 250.0, 400.0).random(random)
                val seek = random.nextInt(30) == 0
                // A seek behind the previous sample, or forwards further than playback could go in the elapsed time
                // (shorter forward seeks are indistinguishable from playback and need not jump).
                if (seek) { val target = random.nextLong(0, 600_000); raw = if (target >= previous && target < raw + 30_000) raw + 30_000 else target; anchor = raw }
                val shown = d.displayedFrame(TransportStatus.PLAYING, false, raw, sr, delay, nanos(t))
                if (seek) assertEquals("run $run: seek shows its target", raw, shown)
                else if (delay > 0.0) {
                    assertTrue("run $run: backwards ($last → $shown)", shown >= last)
                    assertTrue("run $run: before the anchor", shown >= anchor)
                    assertTrue("run $run: ahead of the transport", shown <= raw)
                } else assertEquals(raw, shown)
                if (delay > 0.0 && !seek) assertTrue("run $run: too far behind", raw - shown <= Math.round(400.0 * 48) + 1)
                last = shown
                if (delay == 0.0) anchor = raw
            }
        }
    }
}
