package dev.riffworkbench.editor

import dev.riffworkbench.editor.RhythmEvent.Kind.CONTINUATION
import dev.riffworkbench.editor.RhythmEvent.Kind.ONSET
import dev.riffworkbench.editor.RhythmEvent.Kind.REST
import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 25 „Rhythmus tippen“, quantiser (JVM, no Android): synthetic tap sequences with ±15 ms jitter on every down,
 * releases 20 ms before the intended end (±8 ms), quick taps held 60 ms. Expected results are exact event lists.
 */
class Qa25RhythmQuantizerTest {
    private val sr = 48_000
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private val jitter = listOf(15, -15, 9, -11, 4, -6, 13, -2, -14, 7)
    private val upJitter = listOf(-8, 6, 8, -4, 0, 5, -7, 3)
    private val t3 = listOf(TupletRatio(3, 2))

    /** A bar of the target range: signature, tempo at its start and an optional tempo change inside it. */
    private data class B(val sig: TimeSignature, val qpm: Double, val change: Pair<Fraction, Double>? = null)
    private fun spans(vararg bars: B): List<RouteSpan> {
        var frame = 0.0
        return bars.mapIndexed { i, b ->
            val len = b.sig.length()
            val fpw = { q: Double -> 4 * 60.0 / q * sr }
            val (offsets, frames) = if (b.change == null) doubleArrayOf(0.0, len.toDouble()) to doubleArrayOf(frame, frame + len.toDouble() * fpw(b.qpm))
                else { val x = b.change.first.toDouble(); val mid = frame + x * fpw(b.qpm)
                    doubleArrayOf(0.0, x, len.toDouble()) to doubleArrayOf(frame, mid, mid + (len.toDouble() - x) * fpw(b.change.second)) }
            frame = frames.last()
            RouteSpan(i, EntityId("m$i"), i, 1, len, offsets, frames)
        }
    }
    /** Intended stroke: bar, offset; [to] = end of the held note (global bar/offset) or null for a quick tap. */
    private data class S(val bar: Int, val at: Fraction, val to: Pair<Int, Fraction>? = null)
    private fun frameOf(spans: List<RouteSpan>, bar: Int, o: Fraction): Double =
        if (bar >= spans.size) spans.last().frames.last() + (o.toDouble()) * 0 else spans[bar].frameAt(o.toDouble())
    private fun taps(spans: List<RouteSpan>, strokes: List<S>): List<TapStroke> = strokes.mapIndexed { i, s ->
        val down = frameOf(spans, s.bar, s.at) + jitter[i % jitter.size] * sr / 1000.0
        val up = s.to?.let { (b, o) -> frameOf(spans, b, o) + (upJitter[i % upJitter.size] - 20) * sr / 1000.0 } ?: (down + 0.060 * sr)
        TapStroke(down, up)
    }
    private fun e(o: Fraction, d: WrittenDuration, k: RhythmEvent.Kind) = RhythmEvent(o, d, k)
    private fun w(d: Int, dots: Int = 0, t: List<TupletRatio> = emptyList()) = WrittenDuration(d, dots, t)
    private fun assertFilled(r: RhythmResult) { for (b in r.bars) { var at = Fraction.ZERO; for (x in b.events) { assertEquals(at, x.offset); at += x.duration.length() }; assertEquals(b.length, at) } }

    /** Held quarter, two quick 16ths with rests, held dotted quarter, quick 16th at the end. */
    private val patternA = listOf(S(0, f(0, 1), 0 to f(1, 4)), S(0, f(1, 4)), S(0, f(3, 8)), S(0, f(1, 2), 0 to f(7, 8)), S(0, f(7, 8)))
    private val expectedA = listOf(e(f(0, 1), w(4), ONSET), e(f(1, 4), w(16), ONSET), e(f(5, 16), w(16), REST), e(f(3, 8), w(16), ONSET), e(f(7, 16), w(16), REST),
        e(f(1, 2), w(4, 1), ONSET), e(f(7, 8), w(16), ONSET), e(f(15, 16), w(16), REST))

    @Test fun heldAndQuickTapsAt60To240Bpm() {
        for (qpm in listOf(60.0, 120.0, 180.0, 240.0)) for (grid in listOf(RhythmGrid.SIXTEENTH, RhythmGrid.AUTO)) {
            val sp = spans(B(TimeSignature(), qpm))
            val r = RhythmQuantizer.quantize(taps(sp, patternA), sp, sr, grid)
            assertEquals("$qpm BPM $grid", expectedA, r.bars.single().events)
            assertEquals(0, r.tripletBeats); assertEquals(5, r.onsets); assertFilled(r)
        }
    }

    @Test fun heldOverTheBarlineContinuesInTheNextBar() {
        val sp = spans(B(TimeSignature(), 120.0), B(TimeSignature(), 120.0))
        val r = RhythmQuantizer.quantize(taps(sp, listOf(S(0, f(3, 4), 1 to f(1, 4)), S(1, f(1, 2)))), sp, sr, RhythmGrid.SIXTEENTH)
        assertEquals(listOf(e(f(0, 1), w(2, 1), REST), e(f(3, 4), w(4), ONSET)), r.bars[0].events)
        assertEquals(listOf(e(f(0, 1), w(4), CONTINUATION), e(f(1, 4), w(4), REST), e(f(1, 2), w(16), ONSET), e(f(9, 16), w(4, 2), REST)), r.bars[1].events)
        assertEquals(listOf(RhythmSlot(EntityId("m0"), f(3, 4), listOf(EntityId("m1") to Fraction.ZERO)), RhythmSlot(EntityId("m1"), f(1, 2), emptyList())), r.slots())
    }

    @Test fun sevenEightAt180() {
        val sp = spans(B(TimeSignature(7, 8), 180.0))
        val strokes = listOf(S(0, f(0, 1), 0 to f(1, 4)), S(0, f(1, 4)), S(0, f(3, 8), 0 to f(1, 2)), S(0, f(1, 2), 1 to Fraction.ZERO))
        for (grid in listOf(RhythmGrid.SIXTEENTH, RhythmGrid.AUTO)) {
            val r = RhythmQuantizer.quantize(taps(sp, strokes), sp, sr, grid)
            assertEquals("$grid", listOf(e(f(0, 1), w(4), ONSET), e(f(1, 4), w(16), ONSET), e(f(5, 16), w(16), REST), e(f(3, 8), w(8), ONSET), e(f(1, 2), w(4, 1), ONSET)), r.bars.single().events)
        }
    }

    @Test fun tempoChangesBetweenAndInsideBars() {
        // Bar 1 at 100, bar 2 at 160 changing to 80 at its half: quick 8ths everywhere, a held half over the change.
        val sp = spans(B(TimeSignature(), 100.0), B(TimeSignature(), 160.0, f(1, 2) to 80.0))
        val strokes = (0 until 8).map { S(0, f(it.toLong(), 8)) } + listOf(S(1, f(0, 1)), S(1, f(1, 8)), S(1, f(1, 4), 1 to f(3, 4)), S(1, f(3, 4)), S(1, f(7, 8)))
        val r = RhythmQuantizer.quantize(taps(sp, strokes), sp, sr, RhythmGrid.EIGHTH)
        assertEquals((0 until 8).map { e(f(it.toLong(), 8), w(8), ONSET) }, r.bars[0].events)
        assertEquals(listOf(e(f(0, 1), w(8), ONSET), e(f(1, 8), w(8), ONSET), e(f(1, 4), w(2), ONSET), e(f(3, 4), w(8), ONSET), e(f(7, 8), w(8), ONSET)), r.bars[1].events)
        assertFilled(r)
    }

    @Test fun tripletGrooveIsRecognisedOnlyWhereItIsClearlyBetter() {
        val sp = spans(B(TimeSignature(), 100.0), B(TimeSignature(), 100.0))
        val strokes = (0 until 3).map { S(0, f(it.toLong(), 12)) } +                      // beat 1: 8th triplets
            (0 until 4).map { S(0, f(1, 4) + f(it.toLong(), 16)) } +                       // beat 2: straight 16ths
            listOf(S(0, f(1, 2), 0 to f(3, 4))) +                                          // beat 3: held quarter
            (0 until 6).map { S(0, f(3, 4) + f(it.toLong(), 24)) } +                       // beat 4: 16th triplets
            patternA.map { it.copy(bar = 1, to = it.to?.let { (_, o) -> 1 to o }) }         // bar 2: straight
        val r = RhythmQuantizer.quantize(taps(sp, strokes), sp, sr, RhythmGrid.AUTO)
        val expected = (0 until 3).map { e(f(it.toLong(), 12), w(8, t = t3), ONSET) } + (0 until 4).map { e(f(1, 4) + f(it.toLong(), 16), w(16), ONSET) } +
            listOf(e(f(1, 2), w(4), ONSET)) + (0 until 6).map { e(f(3, 4) + f(it.toLong(), 24), w(16, t = t3), ONSET) }
        assertEquals(expected, r.bars[0].events)
        assertEquals(expectedA, r.bars[1].events)
        assertEquals(2, r.tripletBeats); assertFilled(r)
        // The same taps on a fixed 1/16 grid stay straight (and still fill the bar exactly).
        val straight = RhythmQuantizer.quantize(taps(sp, strokes), sp, sr, RhythmGrid.SIXTEENTH)
        assertEquals(0, straight.tripletBeats); assertTrue(straight.bars[0].events.none { it.duration.tuplets.isNotEmpty() }); assertFilled(straight)
    }

    @Test fun doubleTapsAreOneStroke() {
        val sp = spans(B(TimeSignature(), 120.0))
        val base = taps(sp, patternA)
        // Bounce 20 ms after the first stroke (< 1/4 of a 16th = 31 ms) and a second tap 110 ms later onto the same 1/8 point.
        val bounce = base + TapStroke(base[0].downFrame + 0.020 * sr, base[0].downFrame + 0.030 * sr)
        val r = RhythmQuantizer.quantize(bounce, sp, sr, RhythmGrid.SIXTEENTH)
        assertEquals(expectedA, r.bars.single().events); assertEquals(1, r.merged)
        val p = sp[0].frameAt(0.5)
        val same = listOf(TapStroke(p - 0.050 * sr, p - 0.040 * sr), TapStroke(p + 0.060 * sr, p + 0.070 * sr))
        val r2 = RhythmQuantizer.quantize(same, sp, sr, RhythmGrid.EIGHTH)
        assertEquals(1, r2.onsets); assertEquals(1, r2.merged)
        assertEquals(listOf(e(f(0, 1), w(2), REST), e(f(1, 2), w(8), ONSET), e(f(5, 8), w(4, 1), REST)), r2.bars.single().events)
    }

    @Test fun tapsInTheCountInAndAfterTheEndAreDiscarded() {
        val sp = spans(B(TimeSignature(), 120.0))
        val beat = 0.5 * sr
        val extra = listOf(TapStroke(-beat, -beat + 2000), TapStroke(-beat / 2, -beat / 2 + 2000),   // count-in: discarded
            TapStroke(sp[0].frames.last() - 0.020 * sr, sp[0].frames.last()),                            // snaps onto the end: discarded
            TapStroke(sp[0].frames.last() + 0.2 * sr, sp[0].frames.last() + 0.3 * sr))                    // after the end: discarded
        val early = TapStroke(-0.020 * sr, 0.48 * sr)   // 20 ms early on the downbeat (in the count-in): counts, ½-step tolerance
        val r = RhythmQuantizer.quantize(extra + early + taps(sp, patternA).drop(1), sp, sr, RhythmGrid.SIXTEENTH)
        assertEquals(4, r.discarded)
        assertEquals(e(f(0, 1), w(4), ONSET), r.bars.single().events.first())
        assertEquals(expectedA.drop(1), r.bars.single().events.drop(1))
    }

    @Test fun eighthAndThirtySecondGrids() {
        val sp = spans(B(TimeSignature(), 60.0))
        val r8 = RhythmQuantizer.quantize(taps(sp, patternA), sp, sr, RhythmGrid.EIGHTH)
        assertEquals(listOf(e(f(0, 1), w(4), ONSET), e(f(1, 4), w(8), ONSET), e(f(3, 8), w(8), ONSET), e(f(1, 2), w(4, 1), ONSET), e(f(7, 8), w(8), ONSET)), r8.bars.single().events)
        // 1/32 at 60 BPM (125 ms steps): quick taps are one 32nd.
        val strokes = listOf(S(0, f(0, 1)), S(0, f(1, 32)), S(0, f(3, 32), 0 to f(1, 4)), S(0, f(1, 2)))
        val r32 = RhythmQuantizer.quantize(taps(sp, strokes), sp, sr, RhythmGrid.THIRTY_SECOND)
        assertEquals(listOf(e(f(0, 1), w(32), ONSET), e(f(1, 32), w(32), ONSET), e(f(1, 16), w(32), REST), e(f(3, 32), w(8), ONSET), e(f(7, 32), w(32), CONTINUATION),
            e(f(1, 4), w(4), REST), e(f(1, 2), w(32), ONSET), e(f(17, 32), w(4, 2), REST), e(f(31, 32), w(32), REST)), r32.bars.single().events)
        assertFilled(r32)
    }

    @Test fun notReleasedFillsToTheNextOnset() {
        val sp = spans(B(TimeSignature(), 120.0))
        val r = RhythmQuantizer.quantize(listOf(TapStroke(0.0), TapStroke(sp[0].frameAt(0.5))), sp, sr, RhythmGrid.SIXTEENTH)
        assertEquals(listOf(e(f(0, 1), w(2), ONSET), e(f(1, 2), w(2), ONSET)), r.bars.single().events)
    }

    @Test fun calibrationTakesTheMedianWithoutOutliers() {
        assertEquals(30.5, TapCalibration.offsetMillis(listOf(30.0, 32.0, 28.0, 35.0, 31.0, 400.0, -200.0, 29.0))!!, 1e-9)
        assertEquals(-12.0, TapCalibration.offsetMillis(listOf(-10.0, -12.0, -14.0, -11.0, -13.0, -12.0, 90.0, -12.0))!!, 1e-9)
        assertNull(TapCalibration.offsetMillis(listOf(30.0, 31.0, 300.0)))
        assertNull(TapCalibration.offsetMillis(emptyList()))
        // Deltas against the nearest click; a tap far from every click gives none.
        val clicks = listOf(0L, 24_000L, 96_000L)
        assertEquals(listOf(10.0, -5.0), TapCalibration.deltasMillis(listOf(480.0, 23_760.0, 60_000.0), clicks, sr))
    }
}
