package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Zyklus 28, evaluation: generator of „human“ tap sequences (reproducible, seeded) and the metrics. Truth = written notes
 * (bar-local start, duration, short tap or held). Human model per piece: tempo from the class range; slow rushing/dragging
 * d(t) = bias + A·sin(2πt/T + φ) with a slope up to ±6 % and |A| ≤ ⅒ beat; jitter N(0, σ) on every down, N(0, 1.3σ) plus an
 * early release (10–50 ms) on every held note's up; short taps held 50–100 ms; outliers (1.5 %, ±50–110 ms); finger
 * bounces (1.5 %, a second tap 12–30 ms later).
 */
object Qa28HumanTaps {
    const val SR = 48_000
    data class N(val start: Fraction, val dur: Fraction, val short: Boolean = false)
    data class Bar(val sig: TimeSignature, val notes: List<N>, val qpm: Double? = null)
    data class Piece(val bars: List<Bar>, val qpm: Double)
    class Taps(val piece: Piece, val spans: List<RouteSpan>, val strokes: List<TapStroke>, val compound: (Int) -> Boolean)

    fun f(n: Long, d: Long) = Fraction.of(n, d)
    private operator fun Fraction.minus(o: Fraction) = this + o * Fraction.of(-1)

    fun spans(p: Piece): List<RouteSpan> {
        var frame = 0.0; var qpm = p.qpm
        return p.bars.mapIndexed { i, b ->
            qpm = b.qpm ?: qpm
            val len = b.sig.length()
            val fpw = 4 * 60.0 / qpm * SR
            val s = RouteSpan(i, EntityId("e$i"), i, 1, len, doubleArrayOf(0.0, len.toDouble()), doubleArrayOf(frame, frame + len.toDouble() * fpw))
            frame += len.toDouble() * fpw
            s
        }
    }

    /** Global truth notes (start, duration) in whole notes from the first bar. */
    fun truth(p: Piece): List<Pair<Fraction, Fraction>> {
        var at = Fraction.ZERO
        return buildList { for (b in p.bars) { for (n in b.notes) add(at + n.start to n.dur); at += b.sig.length() } }
    }

    fun human(p: Piece, sigmaMs: Double, rng: Random, drift: Double = 0.06): Taps {
        val sp = spans(p)
        val starts = ArrayList<Fraction>(); var acc = Fraction.ZERO
        for (b in p.bars) { starts += acc; acc += b.sig.length() }
        val total = acc
        fun frameOf(pos: Fraction): Double {
            var b = starts.indexOfLast { it <= pos }.coerceAtLeast(0)
            if (pos >= total) b = sp.lastIndex
            return sp[b].frameAt((pos - starts[b]).toDouble()).let { if (pos > total) it + (pos - total).toDouble() * (sp[b].frames[1] - sp[b].frames[0]) / sp[b].length.toDouble() else it }
        }
        val beatSec = 60.0 / p.qpm
        val period = beatSec * (4 + rng.nextDouble() * 8)
        val rate = rng.nextDouble() * drift
        val amp = minOf(rate * period / (2 * PI), 0.1 * beatSec) * (if (rng.nextBoolean()) 1 else -1)
        val phase = rng.nextDouble() * 2 * PI
        val bias = (rng.nextDouble() * 2 - 1) * 0.015
        fun dev(t: Double) = bias + amp * sin(2 * PI * t / period + phase)
        fun gauss() = java.util.Random(rng.nextLong()).nextGaussian()
        val sigma = sigmaMs / 1000
        val strokes = ArrayList<TapStroke>()
        val notes = truth(p)
        for ((i, n) in notes.withIndex()) {
            val (start, dur) = n
            val bar = starts.indexOfLast { it <= start }
            val short = p.bars[bar].notes.first { starts[bar] + it.start == start }.short
            val t = frameOf(start) / SR
            var down = t + dev(t) + gauss() * sigma
            if (rng.nextDouble() < 0.015) down += (if (rng.nextBoolean()) 1 else -1) * (0.05 + rng.nextDouble() * 0.06)
            val up = if (short) down + 0.05 + rng.nextDouble() * 0.05 else {
                val e = frameOf(start + dur) / SR
                maxOf(down + 0.03, e + dev(e) + gauss() * sigma * 1.3 - (0.01 + rng.nextDouble() * 0.04))
            }
            strokes += TapStroke(down * SR, up * SR)
            if (rng.nextDouble() < 0.015) { val b = down + 0.012 + rng.nextDouble() * 0.018; strokes += TapStroke(b * SR, (b + 0.02) * SR) }
            if (i == notes.lastIndex) Unit
        }
        val compound = { b: Int -> p.bars[b].sig.let { s -> s.denominator >= 8 && s.numerator % 3 == 0 && s.numerator > 3 } }
        return Taps(p, sp, strokes.sortedBy { it.downFrame }, compound)
    }

    // ------------------------------------------------------------------ metrics

    /** Output notes (start, total duration incl. continuations) of a result, global whole notes. */
    fun notesOf(r: RhythmResult): List<Pair<Fraction, Fraction>> {
        val out = ArrayList<Pair<Fraction, Fraction>>()
        var at = Fraction.ZERO
        for (b in r.bars) {
            for (e in b.events) when (e.kind) {
                RhythmEvent.Kind.ONSET -> out += (at + e.offset) to e.duration.length()
                RhythmEvent.Kind.CONTINUATION -> if (out.isNotEmpty()) out[out.lastIndex] = out.last().let { it.first to it.second + e.duration.length() }
                RhythmEvent.Kind.REST -> Unit
            }
            at += b.length
        }
        return out
    }

    class Score(val notes: Int, val matched: Int, val output: Int, val repeatBars: Int, val repeatSame: Int, val beats: Int, val tupletBeats: Int)

    fun score(p: Piece, r: RhythmResult): Score {
        val truth = truth(p)
        val out = notesOf(r)
        val outSet = out.toSet()
        val matched = truth.count { it in outSet }
        // Repeats: bars whose truth (notes overlapping the bar, bar-local) is identical must be written identically.
        val starts = ArrayList<Fraction>(); var acc = Fraction.ZERO
        for (b in p.bars) { starts += acc; acc += b.sig.length() }
        fun key(bi: Int): List<Triple<Fraction, Fraction, Boolean>> {
            val s0 = starts[bi]; val s1 = s0 + p.bars[bi].sig.length()
            return truth.filter { (s, d) -> s < s1 && s + d > s0 }.map { (s, d) -> Triple(maxOf(s, s0) - s0, minOf(s + d, s1) - s0, s >= s0) } + listOf(Triple(p.bars[bi].sig.length(), Fraction.ZERO, false))
        }
        val groups = p.bars.indices.groupBy { key(it) }.values.filter { it.size > 1 }
        var repeatBars = 0; var repeatSame = 0
        for (g in groups) {
            if (g.any { it >= r.bars.size }) continue
            val written = g.map { r.bars[it].events.map { e -> Triple(e.offset, e.duration, e.kind) } }
            val mode = written.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            repeatBars += g.size; repeatSame += written.count { it == mode }
        }
        // Tuplet false alarms: quarter windows that contain a tuplet value.
        var beats = 0; var tuplet = 0
        for (b in r.bars) {
            var at = Fraction.ZERO
            while (at < b.length) {
                val end = minOf(at + f(1, 4), b.length)
                beats++
                if (b.events.any { it.offset >= at && it.offset < end && it.duration.tuplets.isNotEmpty() }) tuplet++
                at = end
            }
        }
        return Score(truth.size, matched, out.size, repeatBars, repeatSame, beats, tuplet)
    }

    // ------------------------------------------------------------------ figures

    private val q = f(1, 4); private val e = f(1, 8); private val s = f(1, 16); private val dq = f(3, 8); private val h = f(1, 2)
    private val c44 = TimeSignature(); private val c78 = TimeSignature(7, 8); private val c54 = TimeSignature(5, 4); private val c34 = TimeSignature(3, 4)
    private fun sx(k: Int) = f(k.toLong(), 16)
    private fun bar(sig: TimeSignature = c44, vararg n: N) = Bar(sig, n.toList())
    private fun held(at: Fraction, d: Fraction) = N(at, d)
    private fun tap(at: Fraction, d: Fraction = s) = N(at, d, short = true)

    class Kind(val name: String, val straight: Boolean, val qpm: ClosedFloatingPointRange<Double>, val figures: List<List<Bar>>)

    val rock = Kind("Rock 8tel", true, 100.0..170.0, listOf(
        listOf(bar(c44, *Array(8) { held(e * f(it.toLong(), 1), e) })),
        listOf(bar(c44, held(f(0, 1), q), held(q, e), held(dq, e), held(h, q), held(f(3, 4), e), held(f(7, 8), e))),
        listOf(bar(c44, *Array(8) { if (it % 2 == 0) held(e * f(it.toLong(), 1), e) else tap(e * f(it.toLong(), 1)) })),
        listOf(bar(c44, held(f(0, 1), dq), held(dq, e), held(h, h)))))
    val metal = Kind("Metal 16tel", true, 90.0..150.0, listOf(
        listOf(bar(c44, *Array(16) { tap(sx(it)) })),
        listOf(bar(c44, *listOf(0, 1, 2, 4, 5, 6, 10, 11, 12, 14).map { tap(sx(it)) }.toTypedArray(), held(sx(8), e))),
        listOf(bar(c44, *(0 until 4).flatMap { b -> listOf(held(sx(4 * b), e), tap(sx(4 * b + 2)), tap(sx(4 * b + 3))) }.toTypedArray()))))
    val gallop = Kind("Gallop", true, 100.0..170.0, listOf(
        listOf(bar(c44, *(0 until 4).flatMap { b -> listOf(held(sx(4 * b), e), tap(sx(4 * b + 2)), tap(sx(4 * b + 3))) }.toTypedArray())),
        listOf(bar(c44, *(0 until 4).flatMap { b -> listOf(tap(sx(4 * b)), tap(sx(4 * b + 1)), held(sx(4 * b + 2), e)) }.toTypedArray()))))
    val syncopes = Kind("Synkopen über den Taktstrich", true, 90.0..160.0, listOf(
        listOf(bar(c44, held(f(0, 1), e), held(dq, q), held(f(5, 8), e), held(f(7, 8), dq)), bar(c44, held(q, e), held(dq, dq), held(f(3, 4), q)))))
    val shuffle = Kind("Shuffle/Swing", false, 80.0..140.0, listOf(
        listOf(bar(c44, *(0 until 4).flatMap { b -> listOf(held(q * f(b.toLong(), 1), f(1, 6)), tap(q * f(b.toLong(), 1) + f(1, 6), f(1, 12))) }.toTypedArray())),
        listOf(bar(c44, *(0 until 3).flatMap { b -> listOf(held(q * f(b.toLong(), 1), f(1, 6)), tap(q * f(b.toLong(), 1) + f(1, 6), f(1, 12))) }.toTypedArray(),
            tap(f(3, 4), f(1, 12)), tap(f(3, 4) + f(1, 12), f(1, 12)), tap(f(3, 4) + f(1, 6), f(1, 12))))))
    val tripletFill = Kind("Triolen-Fill", false, 80.0..130.0, listOf(
        listOf(bar(c44, *Array(8) { held(e * f(it.toLong(), 1), e) })),
        listOf(bar(c44, *(0 until 6).map { tap(f(it.toLong(), 12), f(1, 12)) }.toTypedArray(), *(0 until 12).map { tap(h + f(it.toLong(), 24), f(1, 24)) }.toTypedArray()))))
    val quintuplets = Kind("Quintolen", false, 60.0..100.0, listOf(
        listOf(bar(c44, held(f(0, 1), q), *(0 until 5).map { tap(q + f(it.toLong(), 20), f(1, 20)) }.toTypedArray(), held(h, e), held(f(5, 8), e), *(0 until 5).map { tap(f(3, 4) + f(it.toLong(), 20), f(1, 20)) }.toTypedArray())),
        listOf(bar(c44, *Array(8) { held(e * f(it.toLong(), 1), e) }))))
    val sextuplets = Kind("Sextolen", false, 60.0..110.0, listOf(
        listOf(bar(c44, *(0 until 8).map { tap(sx(it)) }.toTypedArray(), *(0 until 12).map { tap(h + f(it.toLong(), 24), f(1, 24)) }.toTypedArray())),
        listOf(bar(c44, *Array(8) { held(e * f(it.toLong(), 1), e) }))))
    val nested = Kind("Verschachtelte Triolen", false, 60.0..100.0, listOf(
        listOf(bar(c44, held(f(0, 1), f(1, 6)), *(0 until 3).map { tap(f(1, 6) + f(it.toLong(), 18), f(1, 18)) }.toTypedArray(), held(f(1, 3), f(1, 6)), *Array(4) { held(h + e * f(it.toLong(), 1), e) })),
        listOf(bar(c44, held(f(0, 1), f(1, 6)), held(f(1, 6), f(1, 6)), held(f(1, 3), f(1, 6)), *Array(4) { held(h + e * f(it.toLong(), 1), e) }))))
    val djent = Kind("Djent/Prog 3+3+2, 5+5+6", true, 90.0..150.0, listOf(
        listOf(bar(c44, *listOf(0 to 3, 3 to 3, 6 to 2, 8 to 3, 11 to 3, 14 to 2).map { (a, d) -> held(sx(a), sx(d)) }.toTypedArray())),
        listOf(bar(c44, *listOf(0 to 5, 5 to 5, 10 to 6).map { (a, d) -> held(sx(a), sx(d)) }.toTypedArray())),
        listOf(bar(c44, *listOf(0 to 3, 3 to 3, 6 to 3, 9 to 3, 12 to 2, 14 to 2).map { (a, d) -> tap(sx(a)).let { if (d == 2) it else held(sx(a), sx(d)) } }.toTypedArray()))))
    val odd = Kind("7/8 und 5/4", true, 100.0..160.0, listOf(
        listOf(bar(c78, held(f(0, 1), e), tap(e), tap(f(3, 16)), held(q, e), held(dq, e), held(h, e), held(f(5, 8), q))),
        listOf(bar(c54, held(f(0, 1), q), held(q, e), held(dq, e), held(h, q), held(f(3, 4), e), held(f(7, 8), e), held(f(1, 1), q)))))
    val meterChanges = Kind("Taktwechsel + Tempowechsel", true, 100.0..150.0, listOf(
        listOf(bar(c44, held(f(0, 1), q), held(q, e), held(dq, e), held(h, q), held(f(3, 4), e), held(f(7, 8), e)), bar(c78, held(f(0, 1), e), tap(e), tap(f(3, 16)), held(q, e), held(dq, e), held(h, e), held(f(5, 8), q)),
            bar(c34, held(f(0, 1), q), tap(q), tap(f(5, 16)), held(dq, dq)), bar(TimeSignature(5, 8), held(f(0, 1), dq), held(dq, q)))))

    /**
     * A riff and its variation that differs in one tap moved by one 16th with the same articulation (all short taps): the
     * repeat pooling must not „correct“ the variation into the riff.
     */
    val variation = Kind("Riff mit 16tel-Variation", true, 90.0..150.0, listOf(
        listOf(bar(c44, *listOf(0, 1, 2, 4, 5, 6, 8, 10, 11, 12, 14).map { tap(sx(it)) }.toTypedArray())),
        listOf(bar(c44, *listOf(0, 1, 2, 4, 5, 6, 8, 9, 11, 12, 14).map { tap(sx(it)) }.toTypedArray()))))

    val kinds = listOf(rock, metal, gallop, syncopes, djent, odd, meterChanges, variation, shuffle, tripletFill, quintuplets, sextuplets, nested)

    /**
     * Meter cases (Zusatzwunsch „Taktart erkennen“): a riff of [riff] = (start, duration) in 16ths with period [period]
     * 16ths, repeated from [phase16] 16ths on (the part of the riff before it opens the recording as a pickup), tapped to a
     * 4/4 click over [bars] bars. Truth meter [meter] (null = stays 4/4), first full riff at [phase16].
     */
    class MeterCase(val name: String, val meter: TimeSignature?, val period: Int, val riff: List<Pair<Int, Int>>, val phase16: Int = 0, val leadingRest: Boolean = false)
    fun meterPiece(c: MeterCase, rng: Random, bars: Int = 6): Piece {
        val qpm = 100 + rng.nextDouble() * 50
        val total = bars * 16
        val notes = ArrayList<Pair<Int, Int>>()
        var origin = c.phase16 - c.period
        while (origin < total) {
            for ((a, d) in c.riff) { val at = origin + a; if (at >= (if (c.leadingRest) c.phase16 else 0) && at < total) notes += at to minOf(d, total - at) }
            origin += c.period
        }
        notes.sortBy { it.first }
        val list = (0 until bars).map { b ->
            Bar(c44, notes.filter { it.first / 16 == b }.map { (a, d) -> if (d == 1) tap(sx(a - 16 * b)) else held(sx(a - 16 * b), sx(d)) })
        }
        return Piece(list, qpm)
    }
    val meterCases = listOf(
        MeterCase("7/8", TimeSignature(7, 8), 14, listOf(0 to 2, 2 to 1, 3 to 1, 4 to 2, 6 to 2, 8 to 2, 10 to 4)),
        MeterCase("7/8 mit Auftakt-Note", TimeSignature(7, 8), 14, listOf(0 to 2, 2 to 1, 3 to 1, 4 to 2, 6 to 2, 8 to 2, 10 to 4), phase16 = 4),
        MeterCase("7/8 nach 1/4 Pause", TimeSignature(7, 8), 14, listOf(0 to 2, 2 to 1, 3 to 1, 4 to 2, 6 to 2, 8 to 2, 10 to 4), phase16 = 4, leadingRest = true),
        MeterCase("5/4", TimeSignature(5, 4), 20, listOf(0 to 4, 4 to 2, 6 to 2, 8 to 4, 12 to 2, 14 to 2, 16 to 4)),
        MeterCase("6/8", TimeSignature(6, 8), 12, listOf(0 to 4, 4 to 2, 6 to 4, 10 to 2)),
        MeterCase("3/4", TimeSignature(3, 4), 12, listOf(0 to 4, 4 to 2, 6 to 2, 8 to 4)),
        MeterCase("11/8", TimeSignature(11, 8), 22, listOf(0 to 2, 2 to 2, 4 to 2, 6 to 4, 10 to 2, 12 to 2, 14 to 4, 18 to 4)),
        MeterCase("15/16", TimeSignature(15, 16), 15, listOf(0 to 3, 3 to 3, 6 to 3, 9 to 2, 11 to 2, 13 to 2)),
        MeterCase("4/4 Riff", null, 16, listOf(0 to 2, 2 to 2, 4 to 1, 5 to 1, 6 to 2, 8 to 3, 11 to 3, 14 to 2)),
        MeterCase("4/4 Synkope über den Taktstrich", null, 32, listOf(0 to 2, 6 to 4, 10 to 2, 14 to 6, 20 to 2, 22 to 6, 28 to 4)),
        MeterCase("4/4 mit 3er-Gruppen (3+3+3+3+4)", null, 16, listOf(0 to 3, 3 to 3, 6 to 3, 9 to 3, 12 to 4)),
        MeterCase("4/4 mit 3er-Gruppen über 2 Takte (3x10+2)", null, 32, (0 until 10).map { it * 3 to 3 } + listOf(30 to 2)),
        MeterCase("4/4 Achtel durchgehend", null, 16, (0 until 8).map { it * 2 to 2 }))

    /** A piece of [bars] bars: figures in an A A B A … order (repeats on purpose), meter-change figures as one block. */
    fun piece(k: Kind, rng: Random, bars: Int = 8): Piece {
        val qpm = k.qpm.start + rng.nextDouble() * (k.qpm.endInclusive - k.qpm.start)
        val out = ArrayList<Bar>()
        val order = listOf(0, 0, 1, 0, 0, 0, 1, 2)
        var i = 0
        while (out.size < bars) {
            val fig = k.figures[order[i % order.size] % k.figures.size]
            out += fig; i++
        }
        val list = out.take(bars).toMutableList()
        if (k === meterChanges) list[4] = list[4].copy(qpm = qpm * 1.2)
        return Piece(list, qpm)
    }
}
