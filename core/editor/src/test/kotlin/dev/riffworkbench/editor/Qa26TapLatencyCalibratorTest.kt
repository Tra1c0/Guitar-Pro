package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.Transport
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 26: the reusable tap calibration (settings page „Latenz“): the piece without a project (8 clicks at 100 BPM after
 * a one-bar count-in, every track silent), the robust median, cancel and interruption without a result, transport stopped
 * afterwards. The transport fake's time map is the identity (a tap's nanos is its audible plan frame).
 */
class Qa26TapLatencyCalibratorTest {
    private val sr = 48_000
    private val beat = 28_800L   // 100 BPM

    private class FakeTransport(val plan: PlaybackPlan) : Transport {
        val mutable = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
        override val state = mutable
        val prepared = ArrayList<Pair<Project, PracticeSettings>>()
        var stops = 0
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> { prepared += project to settings; return Outcome.Success(Unit) }
        override suspend fun play(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.PLAYING, sample = SamplePosition(-4 * 28_800, 48_000), countingIn = true); return Outcome.Success(Unit) }
        override suspend fun pause(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.PAUSED); return Outcome.Success(Unit) }
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun setLoop(range: LoopRange?) = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> { stops++; mutable.value = mutable.value.copy(status = TransportStatus.STOPPED, sample = SamplePosition(0, 48_000), countingIn = false); return Outcome.Success(Unit) }
        override fun audiblePlanFrameAt(nanos: Long): Double = if (mutable.value.status == TransportStatus.PLAYING) nanos.toDouble() else Double.NaN
        override fun installedPlan(): PlaybackPlan = plan
        fun advance(frame: Long) { mutable.value = mutable.value.copy(sample = SamplePosition(frame, 48_000), countingIn = frame < 0) }
    }

    /** What the compiler makes of [calibrationProject]: two 4/4 bars of 4 beats at 100 BPM, a click per beat. */
    private fun plan(p: Project) = PlaybackPlan(p.revision, sr, emptyList(), emptyList(),
        spans = p.measures.mapIndexed { i, m -> RouteSpan(i, m.id, i, 1, Fraction.ONE, doubleArrayOf(0.0, 1.0), doubleArrayOf(i * 4.0 * beat, (i + 1) * 4.0 * beat)) },
        clicks = List(8) { MetronomeClick(it * beat, if (it % 4 == 0) 1 else 0) })

    @Test fun calibrationPieceWithoutAProject() {
        val p = calibrationProject(null)
        assertEquals(2, p.measures.size)
        assertTrue(p.tracks.isEmpty())
        assertEquals(listOf(100.0), p.tempo.map { it.quarterNotesPerMinute })
        assertEquals(Long.MIN_VALUE, p.revision)
        assertTrue(p.measures.all { it.signature == TimeSignature(4, 4) })
    }

    @Test fun medianOfCleanTapsIsReturnedAndTheTransportStops() = runTest {
        val t = FakeTransport(plan(calibrationProject(null)))
        val c = TapLatencyCalibrator(t)
        val run = async { c.run() }
        t.state.first { it.status == TransportStatus.PLAYING }
        yield()
        assertTrue(c.progress.value.running); assertTrue(c.progress.value.countingIn)
        val settings = t.prepared.single().second
        assertEquals(1, settings.countInBars); assertTrue(settings.metronome); assertEquals(1.0, settings.speed, 0.0)
        c.tapDown(-2 * beat + 150 * 48)                 // count-in tap: further than 250 ms from every click of the plan
        t.advance(10); yield()
        assertFalse(c.progress.value.countingIn)
        // 7 taps 170 ± 4 ms late, one outlier (+240 ms), one missed click.
        val late = listOf(170, 166, 174, 240, 171, 169, 173)
        late.forEachIndexed { i, ms -> c.tapDown(i * beat + ms * 48L) }
        assertEquals(8, c.progress.value.taps)
        t.advance(8 * beat); yield()
        val r = run.await()
        assertEquals(170.5, (r as Outcome.Success).value, 0.01)   // median of 166, 169, 170, 171, 173, 174
        assertEquals(TransportStatus.STOPPED, t.state.value.status)
        assertFalse(c.progress.value.running)
        c.tapDown(5); assertEquals(0, c.progress.value.taps)
    }

    @Test fun tooFewTapsCancelAndInterruptionGiveNoResult() = runTest {
        val t = FakeTransport(plan(calibrationProject(null)))
        val c = TapLatencyCalibrator(t)
        var run = async { c.run() }
        t.state.first { it.status == TransportStatus.PLAYING }; yield()
        t.advance(10); yield()
        listOf(0, 1, 2).forEach { c.tapDown(it * beat + 80 * 48L) }
        t.advance(8 * beat); yield()
        assertEquals(FailureCode.VALIDATION, (run.await() as Outcome.Failure).code)
        // Cancel (device switch, leaving the page).
        run = async { c.run() }
        t.state.first { it.status == TransportStatus.PLAYING }; yield()
        t.advance(10); yield()
        (0 until 8).forEach { c.tapDown(it * beat + 80 * 48L) }
        c.cancel()
        assertEquals(FailureCode.CANCELLED, (run.await() as Outcome.Failure).code)
        assertEquals(TransportStatus.STOPPED, t.state.value.status)
        // Interruption (audio focus): no result either.
        run = async { c.run() }
        t.state.first { it.status == TransportStatus.PLAYING }; yield()
        (0 until 8).forEach { c.tapDown(it * beat + 80 * 48L) }
        t.pause(); yield()
        assertEquals(FailureCode.CANCELLED, (run.await() as Outcome.Failure).code)
        assertEquals(TransportStatus.STOPPED, t.state.value.status)
    }
}
