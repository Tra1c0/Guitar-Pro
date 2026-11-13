package dev.riffworkbench.editor

import dev.riffworkbench.editor.RhythmEvent.Kind.CONTINUATION
import dev.riffworkbench.editor.RhythmEvent.Kind.ONSET
import dev.riffworkbench.editor.RhythmEvent.Kind.REST
import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 28 „Auto“ (RhythmSmoother), deterministic cases: exact event lists for straight, triplet, quintuplet, quarter- and
 * nested triplets, pulse tracking against a rushing/dragging tapper, repeated figures written identically, a variation
 * kept, the info line, and the Zyklus-25 rules (short tap, held over the barline, bounces, range).
 */
class Qa28RhythmSmootherTest {
    private val sr = 48_000
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private val t3 = listOf(TupletRatio(3, 2))
    private fun spans(n: Int, qpm: Double, sig: TimeSignature = TimeSignature()): List<RouteSpan> {
        val fpw = 4 * 60.0 / qpm * sr
        return List(n) { i -> RouteSpan(i, EntityId("s$i"), i, 1, sig.length(), doubleArrayOf(0.0, sig.length().toDouble()), doubleArrayOf(i * sig.length().toDouble() * fpw, (i + 1) * sig.length().toDouble() * fpw)) }
    }
    /** Stroke at global whole-note [at] (+ [ms] deviation); held to [to] (−20 ms) or a 60-ms tap. */
    private fun tap(sp: List<RouteSpan>, at: Fraction, ms: Double = 0.0, to: Fraction? = null): TapStroke {
        val fpw = (sp[0].frames[1] - sp[0].frames[0]) / sp[0].length.toDouble()
        val down = at.toDouble() * fpw + ms * sr / 1000
        return TapStroke(down, to?.let { it.toDouble() * fpw - 0.02 * sr + ms * sr / 1000 } ?: (down + 0.06 * sr))
    }
    private fun e(o: Fraction, d: WrittenDuration, k: RhythmEvent.Kind) = RhythmEvent(o, d, k)
    private fun w(d: Int, dots: Int = 0, t: List<TupletRatio> = emptyList()) = WrittenDuration(d, dots, t)
    private fun smart(taps: List<TapStroke>, sp: List<RouteSpan>) = RhythmQuantizer.quantize(taps, sp, sr, RhythmGrid.SMART)
    private val jitter = listOf(14.0, -12.0, 8.0, -15.0, 3.0, -6.0, 11.0, -9.0, 15.0, -2.0, 7.0, -13.0)

    @Test fun cycle25PatternAtEveryTempo() {
        // Zyklus-25 pattern A (held quarter, two quick 16ths, held dotted quarter, quick 16th) at 60–240 BPM with ±15 ms.
        val expected = listOf(e(f(0, 1), w(4), ONSET), e(f(1, 4), w(16), ONSET), e(f(5, 16), w(16), REST), e(f(3, 8), w(16), ONSET), e(f(7, 16), w(16), REST),
            e(f(1, 2), w(4, 1), ONSET), e(f(7, 8), w(16), ONSET), e(f(15, 16), w(16), REST))
        for (qpm in listOf(60.0, 120.0, 180.0, 240.0)) {
            val sp = spans(1, qpm)
            val taps = listOf(tap(sp, f(0, 1), 15.0, f(1, 4)), tap(sp, f(1, 4), -15.0), tap(sp, f(3, 8), 9.0), tap(sp, f(1, 2), -11.0, f(7, 8)), tap(sp, f(7, 8), 4.0))
            val r = smart(taps, sp)
            assertEquals("$qpm BPM", expected, r.bars.single().events)
            assertEquals(0, r.tripletBeats)
        }
    }

    @Test fun tripletBeatsAreFoundAndStraightBeatsStayStraight() {
        val sp = spans(2, 100.0)
        // Bar 1: 8th triplets on beats 1–2, straight 8ths on 3–4; bar 2: straight 16ths with ±15 ms (no triplets).
        val taps = (0 until 6).map { tap(sp, f(it.toLong(), 12), jitter[it]) } + (0 until 4).map { tap(sp, f(1, 2) + f(it.toLong(), 8), jitter[it + 6]) } +
            (0 until 16).map { tap(sp, f(1, 1) + f(it.toLong(), 16), jitter[it % jitter.size]) }
        val r = smart(taps, sp)
        val bar1 = r.bars[0].events.filter { it.kind == ONSET }
        assertEquals((0 until 6).map { f(it.toLong(), 12) } + (0 until 4).map { f(1, 2) + f(it.toLong(), 8) }, bar1.map { it.offset })
        assertTrue("Triolenwerte in den Zählzeiten 1–2", r.bars[0].events.filter { it.offset < f(1, 2) }.all { it.duration.tuplets == t3 })
        assertTrue("Takt 2 gerade", r.bars[1].events.all { it.duration.tuplets.isEmpty() })
        assertEquals((0 until 16).map { f(it.toLong(), 16) }, r.bars[1].events.filter { it.kind == ONSET }.map { it.offset })
        assertTrue(r.analysis!!.summary, r.analysis!!.summary.contains("Takt 1") && r.analysis!!.summary.contains("16tel"))
    }

    @Test fun quintupletsQuarterTripletsAndNestedTripletsAreWritten() {
        val sp = spans(3, 80.0)
        val quint = (0 until 5).map { tap(sp, f(1, 4) + f(it.toLong(), 20), jitter[it] / 2) }
        // Bar 2: quarter triplets over beats 1–2 (held), 8ths on 3–4. Bar 3: quarter triplet, its middle third as 8th triplets of it.
        val qt = (0 until 3).map { tap(sp, f(1, 1) + f(it.toLong(), 6), jitter[it] / 2, f(1, 1) + f(it.toLong() + 1, 6)) } + (0 until 4).map { tap(sp, f(3, 2) + f(it.toLong(), 8), 0.0, f(3, 2) + f(it.toLong() + 1, 8)) }
        val nested = listOf(tap(sp, f(2, 1), 0.0, f(2, 1) + f(1, 6))) + (0 until 3).map { tap(sp, f(2, 1) + f(1, 6) + f(it.toLong(), 18), jitter[it] / 3) } +
            listOf(tap(sp, f(2, 1) + f(1, 3), 0.0, f(5, 2))) + (0 until 4).map { tap(sp, f(5, 2) + f(it.toLong(), 8), 0.0, f(5, 2) + f(it.toLong() + 1, 8)) }
        val r = smart(listOf(tap(sp, f(0, 1), 0.0, f(1, 4))) + quint + listOf(tap(sp, f(1, 2), 0.0, f(1, 1))) + qt + nested, sp)
        val q5 = r.bars[0].events.filter { it.offset >= f(1, 4) && it.offset < f(1, 2) }
        assertEquals((0 until 5).map { f(1, 4) + f(it.toLong(), 20) }, q5.filter { it.kind == ONSET }.map { it.offset })
        assertTrue("Quintolen 5:4", q5.all { it.duration.tuplets == listOf(TupletRatio(5, 4)) })
        val b2 = r.bars[1].events.filter { it.offset < f(1, 2) }
        assertEquals(listOf(e(f(0, 1), w(4, 0, t3), ONSET), e(f(1, 6), w(4, 0, t3), ONSET), e(f(1, 3), w(4, 0, t3), ONSET)), b2)
        val b3 = r.bars[2].events.filter { it.offset < f(1, 2) }
        assertEquals(listOf(f(0, 1), f(1, 6), f(1, 6) + f(1, 18), f(1, 6) + f(2, 18), f(1, 3)), b3.filter { it.kind == ONSET }.map { it.offset })
        assertTrue("verschachtelt 3:2 in 3:2", b3.filter { it.offset >= f(1, 6) && it.offset < f(1, 3) }.all { it.duration.tuplets == t3 + t3 })
        assertTrue(r.analysis!!.summary, r.analysis!!.summary.contains("Quintolen in Takt 1") && r.analysis!!.summary.contains("Viertel-Triolen in Takt 2") && r.analysis!!.summary.contains("verschachtelte Triolen in Takt 3"))
        for (b in r.bars) { var at = Fraction.ZERO; for (x in b.events) { assertEquals(at, x.offset); at += x.duration.length() }; assertEquals(b.length, at) }
    }

    @Test fun pulseTrackingFollowsARushingAndDraggingTapper() {
        // 16ths at 140 BPM (107 ms): the tapper drags up to +55 ms and rushes to −55 ms over 4 bars (half a 16th = 54 ms).
        val sp = spans(4, 140.0)
        val taps = (0 until 64).map { k -> val t = k / 64.0; tap(sp, f(k.toLong(), 16), 55 * kotlin.math.sin(2 * Math.PI * t) + jitter[k % jitter.size] / 3) }
        val sixteenth = RhythmQuantizer.quantize(taps, sp, sr, RhythmGrid.SIXTEENTH)
        val r = smart(taps, sp)
        val want = (0 until 16).map { f(it.toLong(), 16) }
        assertTrue("ohne Pulsverfolgung (1/16 fest) daneben", sixteenth.bars.any { b -> b.events.filter { it.kind == ONSET }.map { it.offset } != want })
        for (b in r.bars) assertEquals(want, b.events.filter { it.kind == ONSET }.map { it.offset })
        assertTrue("Drift erkannt: ${r.analysis!!.maxDriftMs} ms", r.analysis!!.maxDriftMs > 30)
    }

    @Test fun repeatedFiguresAreIdenticalAndAVariationStays() {
        // A gallop riff four times (bar 3 is a variation: the last 16th pair is moved), jitter differs per bar.
        val sp = spans(4, 120.0)
        fun gallop(bar: Int, variation: Boolean) = (0 until 4).flatMap { b ->
            val base = f(bar.toLong(), 1) + f(b.toLong(), 4)
            val pair = if (variation && b == 3) listOf(f(1, 16), f(2, 16)) else listOf(f(2, 16), f(3, 16))
            listOf(tap(sp, base, jitter[(bar * 3 + b) % jitter.size], base + f(1, 8).let { if (variation && b == 3) f(1, 16) else it })) +
                pair.mapIndexed { i, o -> tap(sp, base + o, jitter[(bar * 5 + b + i) % jitter.size] * 1.3) }
        }
        val r = smart(gallop(0, false) + gallop(1, false) + gallop(2, true) + gallop(3, false), sp)
        assertEquals(r.bars[0].events, r.bars[1].events)
        assertEquals(r.bars[0].events, r.bars[3].events)
        assertNotEquals(r.bars[0].events, r.bars[2].events)
        assertEquals(listOf(f(3, 4), f(13, 16), f(7, 8)), r.bars[2].events.filter { it.kind == ONSET && it.offset >= f(3, 4) }.map { it.offset })
        assertTrue(r.analysis!!.summary, r.analysis!!.summary.contains("Takt 1=2=4 gleich"))
    }

    @Test fun cycle25RulesStay() {
        val sp = spans(2, 120.0)
        // Held over the barline (continuation), a finger bounce 15 ms later, a count-in tap and one after the end.
        val taps = listOf(TapStroke(-0.5 * sr), tap(sp, f(0, 1), 0.0, f(1, 4)), tap(sp, f(3, 4), 0.0, f(5, 4)), TapStroke(tap(sp, f(3, 4)).downFrame + 0.015 * sr, tap(sp, f(3, 4)).downFrame + 0.03 * sr),
            tap(sp, f(3, 2)), TapStroke(2.2 * 2 * sr))
        val r = smart(taps, sp)
        assertEquals(listOf(e(f(0, 1), w(4), ONSET), e(f(1, 4), w(2), REST), e(f(3, 4), w(4), ONSET)), r.bars[0].events)
        assertEquals(listOf(e(f(0, 1), w(4), CONTINUATION), e(f(1, 4), w(4), REST), e(f(1, 2), w(16), ONSET), e(f(9, 16), w(4, 2), REST)), r.bars[1].events)
        assertEquals(2, r.discarded); assertEquals(1, r.merged); assertEquals(3, r.onsets)
    }

    @Test fun writeInChains() {
        assertEquals(listOf(w(8, 0, t3)), RhythmSmoother.writeIn(f(1, 12), t3))
        assertEquals(listOf(w(8, 0, t3 + t3)), RhythmSmoother.writeIn(f(1, 18), t3 + t3))
        assertEquals(listOf(w(8, 1, listOf(TupletRatio(5, 4)))), RhythmSmoother.writeIn(f(3, 20), listOf(TupletRatio(5, 4))))
        assertNull(RhythmSmoother.writeIn(f(1, 7), t3))
    }
}
