package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Zyklus 28: what the autonomous smoothing ([RhythmGrid.SMART], „Auto“) found — for the info line of the preview and the
 * evaluation. [sigmaMs]: tap scatter estimated from the recording itself; [maxDriftMs]: largest smoothed deviation of the
 * tapper from the click (rushing/dragging) that was removed before quantising; [barGrids]: per bar the subdivision used;
 * [repeatedBars]: bars decided together with an identical repeat; [meter]: the meter the taps suggest (null = no clear
 * evidence or not asked).
 */
data class RhythmAnalysis(
    val sigmaMs: Double,
    val maxDriftMs: Double,
    val barGrids: List<String>,
    val summary: String,
    val repeatedBars: Int,
    val tupletWindows: Int,
    val meter: MeterGuess? = null,
)

/**
 * Zyklus 28 „Taktart erkennen“: the period and grouping the taps repeat with. [signature] null means: keep the meter of the
 * recording ([current]); [phase] = where the first full bar begins after the start of the analysed range (a shorter bar
 * of that length comes first), [repetitions] full periods covered by taps, [score]/[currentScore] the self-similarity
 * (0…1, after the complexity penalty) of the guess and of the current meter.
 */
data class MeterGuess(val signature: TimeSignature?, val current: TimeSignature, val phase: Fraction, val score: Double, val currentScore: Double, val repetitions: Int) {
    val changes: Boolean get() = signature != null && (signature != current || phase != Fraction.ZERO)
}

/**
 * Zyklus 28 „Rhythmus glätten (ein Klick)“: the default „Auto“ of the rhythm tapper. Pure logic, JVM-testable. Pipeline:
 * 1. Pulse tracking: tap positions in written whole notes (tempo map of the recording); the smooth deviation w(t) of the
 *    tapper from the click is estimated as a robust (Huber), non-causal, leave-one-out kernel regression of the grid
 *    residuals (Gaussian, σ = [DRIFT_BAND_QUARTERS] quarters, i.e. forward and backward over the whole recording), with a
 *    ridge towards 0 and limited to ±[DRIFT_MAX] (⅛ quarter). Downs and ups are de-warped before quantising. The first
 *    estimate uses the residuals to the 8th grid with a Tukey weight (only taps close to an 8th count); then 3 rounds of
 *    model choice → residuals → new w and tap scatter σ (1.4826 · MAD, 6…40 ms).
 * 2. Grid as model choice per beat window (quarter, dotted quarter in compound meters, the remainder of odd bars) and per
 *    pair of quarters (quarter triplets, nested triplets): an MDL criterion = data cost Σ min(½(e/σ)², [CAP]) (e = residual
 *    in seconds) + coding cost ln(grid points per quarter) per tap + model prior (binary 8/16 free, 32nds/64ths, triplets,
 *    sextuplets, quintuplets, septuplets, nested triplets increasingly expensive). A finer or unusual grid wins only when
 *    several taps fit it clearly better. Bars are a dynamic programme over their windows.
 * 3. Global consistency: bars of the same length and onset count whose taps lie within the grid tolerance of a group's
 *    centroid are one figure — decided together (costs summed, prior once) and written from the centroid, so a repeated
 *    riff is notated identically. Tuplet families used in several places get a context discount. Odd groupings (3+3+2,
 *    5+5+6 16ths), syncopations over barlines and odd meters stay: positions only snap to the chosen grid, never to
 *    „simple“ beats.
 * 4. Durations: the ups are de-warped and snapped with the grid of the window they fall into; the Zyklus-25 rules stay (a
 *    short tap = attack + rests, at most up to the next attack, minimum one step); a release just before the next attack
 *    (human early release) holds to it.
 */
object RhythmSmoother {
    const val SIGMA_START = 0.018
    const val SIGMA_MIN = 0.006
    const val SIGMA_MAX = 0.040
    /** Per-tap data cost limit (nats): an outlier costs the same under every grid. */
    const val CAP = 6.0
    const val DRIFT_BAND_QUARTERS = 1.0
    /** Largest deviation the tracking removes, in whole notes (⅛ quarter). */
    const val DRIFT_MAX = 1.0 / 32
    const val RIDGE = 0.3
    const val P_FINE32 = 3.0
    const val P_FINE64 = 4.5
    const val P_TRIPLET = 3.0
    const val P_SEXTUPLET = 3.5
    const val P_QUINTUPLET = 5.0
    const val P_SEPTUPLET = 6.5
    const val P_NESTED = 7.0
    const val P_NESTED_THIRD = 1.0
    /** Introduction of a non-binary family that the rest of the recording does not use (see [familyDiscount]). */
    const val P_INTRO = 1.5
    /** Prior relief for a tuplet window whose every grid point holds exactly one attack (see [complete]). */
    const val P_COMPLETE = 2.0
    /** A figure needs this many taps before one tap that is a whole grid step off still counts as a slip (see [group]). */
    const val SLIP_MIN_TAPS = 8
    /** Window membership: a tap up to a 64th before a window belongs to it (it is that window's downbeat played early). */
    private val EDGE = 1.0 / 64
    private val T3 = TupletRatio(3, 2)

    enum class Family { BINARY, FINE, TRIPLET, QUARTER_TRIPLET, SEXTUPLET, QUINTUPLET, SEPTUPLET, NESTED }
    private class Seg(val start: Fraction, val end: Fraction, val step: Fraction, val chain: List<TupletRatio>)
    private class Model(val family: Family, val prior: Double, val segs: List<Seg>, val label: String) {
        val start: Fraction get() = segs.first().start
        val end: Fraction get() = segs.last().end
    }
    private class Win(val start: Fraction, val end: Fraction) { val length: Fraction get() = end - start }
    /** One stroke: raw and de-warped positions (whole notes from the range start), seconds per whole note at the down. */
    private class Tap(val x: Double, var xUp: Double, val spw: Double) {
        var y = x; var yUp = xUp; var w = 0.0
        var bar = 0; var q: Fraction = Fraction.ZERO; var rho = 1.0; var r = 0.0
    }
    private class Plan(val models: List<Model>)

    private operator fun Fraction.minus(o: Fraction) = this + o * Fraction.of(-1)
    private fun Fraction.div(n: Long) = this * Fraction.of(1, n)

    /**
     * [compound]: bar index → compound meter (beat = dotted quarter); [meterFor] given: also guess the meter of the bars
     * [meterFrom]… (see [MeterDetector]); the result is reported in [RhythmAnalysis.meter], it does not re-bar.
     */
    fun quantize(taps: List<TapStroke>, spans: List<RouteSpan>, sampleRate: Int, compound: (Int) -> Boolean = { false },
                 current: TimeSignature? = null, meterFrom: Int = -1): RhythmResult {
        require(spans.isNotEmpty()) { "Kein Zielbereich" }
        val lengths = spans.map { it.length }
        val starts = ArrayList<Fraction>(); var acc = Fraction.ZERO
        for (l in lengths) { starts += acc; acc += l }
        val total = acc; val totalD = total.toDouble()
        val startsD = starts.map { it.toDouble() }
        fun slope(s: RouteSpan, seg: Int) = (s.frames[seg + 1] - s.frames[seg]) / (s.offsets[seg + 1] - s.offsets[seg])
        fun posOf(frame: Double): Double {
            val first = spans.first()
            if (frame < first.frames[0]) return (frame - first.frames[0]) / slope(first, 0)
            for ((i, s) in spans.withIndex()) if (frame < s.frames.last() || i == spans.lastIndex) {
                if (frame >= s.frames.last()) return totalD + (frame - s.frames.last()) / slope(s, s.frames.size - 2)
                return startsD[i] + s.offsetAt(frame)
            }
            return totalD
        }
        fun barOf(pos: Double): Int { var b = 0; while (b < spans.lastIndex && pos >= startsD[b + 1] - EDGE) b++; return b }
        fun spwAt(pos: Double): Double {
            val b = barOf(pos.coerceIn(0.0, totalD)); val s = spans[b]; val local = (pos - startsD[b]).coerceIn(0.0, lengths[b].toDouble())
            for (i in 1 until s.offsets.size) if (local <= s.offsets[i] || i == s.offsets.lastIndex) return slope(s, i - 1) / sampleRate
            return slope(s, 0) / sampleRate
        }

        // 1. Range filter and finger bounces (closer than a 64th: one stroke, first down, last release).
        var discarded = 0; var merged = 0
        val kept = ArrayList<Tap>()
        for (t in taps.sortedBy { it.downFrame }) {
            if (t.downFrame.isNaN()) { discarded++; continue }
            val pd = posOf(t.downFrame)
            if (pd < -1.0 / 32 || pd >= totalD) { discarded++; continue }
            val pu = if (t.upFrame.isNaN()) Double.NaN else posOf(maxOf(t.upFrame, t.downFrame))
            val last = kept.lastOrNull()
            if (last != null && pd - last.x < 1.0 / 64) { merged++; if (!pu.isNaN()) last.xUp = if (last.xUp.isNaN()) pu else maxOf(last.xUp, pu); continue }
            kept += Tap(pd, pu, spwAt(pd))
        }
        val windows = lengths.mapIndexed { b, len -> windowsOf(len, compound(b)) }

        // 2./3. Iterate: drift → model choice (with groups) → residuals → σ.
        var sigma = SIGMA_START
        initialDrift(kept, starts, lengths, ::barOf)
        var plans: List<Plan> = emptyList()
        var groups: List<List<Int>> = emptyList()
        var discount = emptyMap<Family, Double>()
        repeat(4) { round ->
            for (t in kept) { t.y = t.x - t.w; t.bar = barOf(maxOf(0.0, t.y)); t.yUp = if (t.xUp.isNaN()) Double.NaN else t.xUp - driftAt(kept, t.xUp) }
            val byBar = List(spans.size) { b -> kept.filter { it.bar == b } }
            // Independent choice per bar, then groups of identical figures decided together.
            val alone = List(spans.size) { b -> choose(windows[b], listOf(byBar[b]), startsD[b], sigma, discount, compound(b)) }
            groups = group(byBar, lengths, startsD, alone, windows, sigma)
            plans = MutableList(spans.size) { alone[it] }
            for (g in groups) if (g.size > 1) {
                val joint = choose(windows[g[0]], g.map { byBar[it] }, 0.0, sigma, discount, compound(g[0]), offsets = g.map { startsD[it] })
                for (b in g) (plans as MutableList)[b] = joint
            }
            // Snap (groups from the centroid of their members), residuals, σ, drift.
            for (g in groups) snapGroup(g, byBar, startsD, plans)
            val residuals = kept.map { (it.y - (starts[it.bar] + it.q).toDouble()) * it.spw }
            if (residuals.size >= 4) sigma = (1.4826 * median(residuals.map { abs(it) })).coerceIn(SIGMA_MIN, SIGMA_MAX)
            if (round < 3) updateDrift(kept, starts, sigma)
            discount = familyDiscount(plans, byBar)
        }
        for (t in kept) t.yUp = if (t.xUp.isNaN()) Double.NaN else t.xUp - driftAt(kept, t.xUp)

        // 4. Onsets: snapped positions; one onset per grid point; a tap snapped onto the range end is dropped.
        val onsets = ArrayList<Tap>()
        for (t in kept.sortedBy { (starts[it.bar] + it.q).toDouble() }) {
            var b = t.bar; var o = t.q
            if (o >= lengths[b]) { if (b == spans.lastIndex) { discarded++; continue }; b++; o = Fraction.ZERO }
            t.bar = b; t.q = o
            val prev = onsets.lastOrNull()
            if (prev != null && prev.bar == b && prev.q == o) { merged++; if (!t.yUp.isNaN()) prev.yUp = if (prev.yUp.isNaN()) t.yUp else maxOf(prev.yUp, t.yUp); continue }
            onsets += t
        }
        // Groups share their releases too (centroid of the members' bar-local releases, majority for „short tap“).
        val ends = noteEnds(onsets, groups, plans, starts, lengths, total, sampleRate, ::barOf)

        // 5. Bars: notes and rests, cut at tuplet segment boundaries, written as values.
        val notes = onsets.mapIndexed { i, t -> (starts[t.bar] + t.q) to ends[i] }
        val bars = try { writeBars(spans, lengths, starts, notes, plans) } catch (x: IllegalArgumentException) {
            // A combination the writer cannot express: the Zyklus-25 Auto result (never a silent loss of taps).
            return RhythmQuantizer.quantize(taps, spans, sampleRate, RhythmGrid.AUTO, compound)
        }
        val tupletWindows = plans.sumOf { p -> p.models.count { it.family != Family.BINARY && it.family != Family.FINE } }
        val labels = plans.map { p -> p.models.filter { it.family != Family.BINARY || it.segs.first().step < Fraction.of(1, 4) }.map { it.label }.distinct().joinToString("/").ifEmpty { "–" } }
        val repeated = groups.filter { it.size > 1 }.sumOf { it.size }
        val drift = kept.maxOfOrNull { abs(it.w) * it.spw } ?: 0.0
        val meter = if (current != null && meterFrom >= 0 && meterFrom < spans.size) MeterDetector.detect(notes, starts[meterFrom], total, current, onsets.map { it.q.denominator }) else null
        val analysis = RhythmAnalysis(sigma * 1000, drift * 1000, labels, summary(plans, groups, onsets.isEmpty()), repeated, tupletWindows, meter)
        return RhythmResult(bars, onsets.size, discarded, merged, tupletWindows, analysis)
    }

    // ------------------------------------------------------------------ windows and models

    private fun windowsOf(len: Fraction, compound: Boolean): List<Win> {
        val beat = if (compound) (if (len.denominator > 8) Fraction.of(3, 16) else Fraction.of(3, 8)) else Fraction.of(1, 4)
        val out = ArrayList<Win>(); var at = Fraction.ZERO
        while (at < len) { val end = if (at + beat <= len) at + beat else len; out += Win(at, end); at = end }
        return out
    }
    private fun dyadic(f: Fraction) = f.numerator == 1L && f.denominator and (f.denominator - 1) == 0L
    private fun uniform(w: Win, step: Fraction, chain: List<TupletRatio>, family: Family, prior: Double, label: String) =
        Model(family, prior, listOf(Seg(w.start, w.end, step, chain)), label)

    /** Candidate grids for one window of length 1/4, 1/8, 3/8 (compound beat) or a remainder. */
    private fun singles(w: Win, compoundBar: Boolean): List<Model> {
        val l = w.length
        val out = ArrayList<Model>()
        if (compoundBar && (l == Fraction.of(3, 8) || l == Fraction.of(3, 16))) {
            out += uniform(w, l, emptyList(), Family.BINARY, 0.0, "")
            out += uniform(w, l.div(3), emptyList(), Family.BINARY, 0.0, if (l == Fraction.of(3, 8)) "8tel" else "16tel")
            out += uniform(w, l.div(6), emptyList(), Family.BINARY, 0.0, if (l == Fraction.of(3, 8)) "16tel" else "32tel")
            out += uniform(w, l.div(12), emptyList(), Family.FINE, P_FINE32, if (l == Fraction.of(3, 8)) "32tel" else "64tel")
            return out
        }
        if (l == Fraction.of(1, 4)) {
            out += uniform(w, l, emptyList(), Family.BINARY, 0.0, "")
            out += uniform(w, Fraction.of(1, 8), emptyList(), Family.BINARY, 0.0, "8tel")
            out += uniform(w, Fraction.of(1, 16), emptyList(), Family.BINARY, 0.0, "16tel")
            out += uniform(w, Fraction.of(1, 32), emptyList(), Family.FINE, P_FINE32, "32tel")
            out += uniform(w, Fraction.of(1, 64), emptyList(), Family.FINE, P_FINE64, "64tel")
            out += uniform(w, Fraction.of(1, 12), listOf(T3), Family.TRIPLET, P_TRIPLET, "Triolen")
            out += uniform(w, Fraction.of(1, 24), listOf(T3), Family.SEXTUPLET, P_SEXTUPLET, "Sextolen")
            out += uniform(w, Fraction.of(1, 20), listOf(TupletRatio(5, 4)), Family.QUINTUPLET, P_QUINTUPLET, "Quintolen")
            out += uniform(w, Fraction.of(1, 28), listOf(TupletRatio(7, 4)), Family.SEPTUPLET, P_SEPTUPLET, "Septolen")
            return out
        }
        if (l == Fraction.of(1, 8)) {
            out += uniform(w, l, emptyList(), Family.BINARY, 0.0, "")
            out += uniform(w, Fraction.of(1, 16), emptyList(), Family.BINARY, 0.0, "16tel")
            out += uniform(w, Fraction.of(1, 32), emptyList(), Family.FINE, P_FINE32, "32tel")
            out += uniform(w, Fraction.of(1, 24), listOf(T3), Family.SEXTUPLET, P_SEXTUPLET, "16tel-Triolen")
            return out
        }
        // Remainders (x/16 meters, pickups): binary steps that divide the window.
        for ((step, fam, prior, label) in listOf(Quad(l, Family.BINARY, 0.0, ""), Quad(Fraction.of(1, 16), Family.BINARY, 0.0, "16tel"), Quad(Fraction.of(1, 32), Family.FINE, P_FINE32, "32tel"))) {
            val k = l * Fraction.of(step.denominator, step.numerator)
            if (k.denominator == 1L && (step != l || dyadic(l))) out += uniform(w, step, emptyList(), fam, prior, label)
        }
        if (out.isEmpty()) out += uniform(w, Fraction.of(1, 64), emptyList(), Family.FINE, P_FINE64, "64tel")
        return out
    }
    private data class Quad(val step: Fraction, val family: Family, val prior: Double, val label: String)

    /** Quarter triplets over two quarters (the coarse case; 8th triplets inside it share its bracket). */
    private fun pairModel(a: Win, b: Win): Model = Model(Family.QUARTER_TRIPLET, P_TRIPLET - 0.1, listOf(Seg(a.start, b.end, Fraction.of(1, 12), listOf(T3))), "Viertel-Triolen")

    // ------------------------------------------------------------------ costs

    /** Grid point of [s] nearest to [y] (window-local whole notes), the end included. */
    private fun nearest(y: Double, s: Seg): Fraction {
        val k = (y - s.start.toDouble()) / s.step.toDouble()
        val n = kotlin.math.round(k).toLong().coerceAtLeast(0)
        val p = s.start + s.step * Fraction.of(n)
        return if (p > s.end) s.end else p
    }
    private fun segAt(m: Model, y: Double): Seg = m.segs.firstOrNull { y < it.end.toDouble() - EDGE } ?: m.segs.last()
    private fun coding(s: Seg): Double = max(0.0, ln(0.25 / s.step.toDouble()))

    /**
     * A point for the model choice: bar-local position, seconds per whole note, weights of the data and coding cost (an
     * attack 1/1; a held release [UP_DATA]/[UP_CODING]: releases scatter more and carry less rhythm information).
     */
    private class Pt(val y: Double, val spw: Double, val data: Double, val code: Double)
    private const val UP_DATA = 0.4
    private const val UP_CODING = 0.5
    /** Releases scatter more than attacks (and come early): their own σ. */
    private const val UP_SIGMA_FACTOR = 2.0
    private const val UP_SIGMA_MIN = 0.03

    /** Data + coding cost of [m] for [pts]. */
    private fun cost(m: Model, pts: List<Pt>, sigma: Double): Double {
        var c = 0.0
        for (p in pts) {
            val s = segAt(m, p.y)
            val e = (p.y - nearest(p.y, s).toDouble()) * p.spw
            val sd = if (p.data < 1.0) max(UP_SIGMA_FACTOR * sigma, UP_SIGMA_MIN) else sigma
            c += p.data * min(0.5 * (e / sd) * (e / sd), CAP) + p.code * coding(s)
        }
        return c
    }

    /** Nested triplets over a pair of quarters: per third a quarter triplet, 8th triplets or 8th triplets of the quarter triplet. */
    private fun nestedModel(a: Win, b: Win, pts: List<Pt>, sigma: Double): Pair<Model, Double>? {
        val third = Fraction.of(1, 6)
        val segs = ArrayList<Seg>(); var total = P_NESTED; var nested = 0
        for (k in 0 until 3) {
            val s0 = a.start + third * Fraction.of(k.toLong()); val s1 = s0 + third
            val inside = pts.filter { it.y >= s0.toDouble() - EDGE && (it.y < s1.toDouble() - EDGE || k == 2) }
            val plain = Seg(s0, s1, Fraction.of(1, 12), listOf(T3))
            val deep = Seg(s0, s1, Fraction.of(1, 18), listOf(T3, T3))
            val cp = cost(Model(Family.TRIPLET, 0.0, listOf(plain), ""), inside, sigma)
            val cd = cost(Model(Family.NESTED, 0.0, listOf(deep), ""), inside, sigma) + P_NESTED_THIRD
            if (cd < cp) { segs += deep; total += cd; nested++ } else { segs += plain; total += cp }
        }
        if (nested == 0) return null
        return Model(Family.NESTED, P_NESTED, segs, "verschachtelte Triolen") to total
    }

    /**
     * Dynamic programme over the windows of a bar for the taps of one or more bars of the same shape ([members], positions
     * relative to their bar start [offsets]); prior once, costs summed.
     */
    private fun choose(ws: List<Win>, members: List<List<Tap>>, start: Double, sigma: Double, discount: Map<Family, Double>, compoundBar: Boolean, offsets: List<Double>? = null): Plan {
        val starts = offsets ?: List(members.size) { start }
        val barEnd = ws.last().end.toDouble()
        fun ptsIn(a: Fraction, b: Fraction, last: Boolean): List<Pt> = buildList {
            fun inside(local: Double) = local >= a.toDouble() - EDGE && (local < b.toDouble() - EDGE || last)
            for ((mi, m) in members.withIndex()) for ((ti, t) in m.withIndex()) {
                val local = t.y - starts[mi]
                if (inside(local)) add(Pt(local, t.spw, 1.0, 1.0))
                // Zyklus 28 rule 4: held releases in the same bar take part in the grid choice (with less weight) - not
                // those just before the next attack (a legato release says nothing the attack does not).
                if (!t.yUp.isNaN() && !roughShort(t)) {
                    val u = t.yUp - starts[mi]
                    val next = m.getOrNull(ti + 1)?.let { it.y - starts[mi] } ?: Double.POSITIVE_INFINITY
                    if (u < barEnd - EDGE && u > local && next - u > 3.0 / 32 && inside(u)) add(Pt(u, t.spw, UP_DATA, UP_CODING))
                }
            }
        }
        val n = ws.size
        val best = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }; best[0] = 0.0
        val pick = arrayOfNulls<Model>(n + 1); val span = IntArray(n + 1)
        fun prior(m: Model) = m.prior * (discount[m.family] ?: 1.0)
        for (i in 1..n) {
            val w = ws[i - 1]
            val pts = ptsIn(w.start, w.end, i == n)
            for (m in singles(w, compoundBar)) {
                val c = best[i - 1] + (if (pts.isEmpty()) 0.0 else prior(m) - (if (complete(m, pts, members.size)) P_COMPLETE else 0.0) + cost(m, pts, sigma))
                if (c < best[i] - 1e-9 || (pts.isEmpty() && pick[i] == null)) { best[i] = c; pick[i] = m; span[i] = 1 }
                if (pts.isEmpty()) break   // an empty window: the coarsest grid
            }
            if (i >= 2 && !compoundBar) {
                val a = ws[i - 2]
                // Aligned pairs of quarters (beats 1+2, 3+4 …).
                if (a.length == Fraction.of(1, 4) && w.length == Fraction.of(1, 4) && (a.start * Fraction.of(2)).denominator == 1L) {
                    val pts2 = ptsIn(a.start, w.end, i == n)
                    if (pts2.isNotEmpty()) {
                        val qt = pairModel(a, w)
                        val c = best[i - 2] + prior(qt) + cost(qt, pts2, sigma)
                        if (c < best[i] - 1e-9) { best[i] = c; pick[i] = qt; span[i] = 2 }
                        nestedModel(a, w, pts2, sigma)?.let { (m, cm) -> val c2 = best[i - 2] + cm * 1.0 + (prior(m) - m.prior); if (c2 < best[i] - 1e-9) { best[i] = c2; pick[i] = m; span[i] = 2 } }
                    }
                }
            }
        }
        val models = ArrayList<Model>(); var i = n
        while (i > 0) { models += pick[i]!!; i -= span[i] }
        return Plan(models.reversed())
    }

    /**
     * Structural evidence for a tuplet window: every grid point of the window holds an attack of each member and no point
     * holds two (a full beat of triplets or quintuplets). Straight 16ths never fill a triplet grid without a collision.
     */
    private fun complete(m: Model, pts: List<Pt>, members: Int): Boolean {
        if (m.segs.size != 1 || m.segs[0].chain.isEmpty()) return false
        val s = m.segs[0]
        val k = (s.end - s.start) * Fraction.of(s.step.denominator, s.step.numerator)
        if (k.denominator != 1L || k.numerator < 3) return false
        val counts = IntArray(k.numerator.toInt())
        for (p in pts) if (p.data == 1.0) {
            val q = nearest(p.y, s)
            if (q >= s.end) continue
            val i = ((q - s.start) * Fraction.of(s.step.denominator, s.step.numerator)).numerator.toInt()
            counts[i]++
        }
        return counts.all { it == members }
    }

    private fun modelAt(plan: Plan, local: Double): Model = plan.models.firstOrNull { local < it.end.toDouble() - EDGE } ?: plan.models.last()

    // ------------------------------------------------------------------ groups

    /**
     * Bars of the same length and tap count whose taps lie within 0.6 grid steps (of the group's grid) and 3.5 σ of the
     * group's centroid are one figure. Greedy in bar order, the closest group wins.
     */
    private fun group(byBar: List<List<Tap>>, lengths: List<Fraction>, starts: List<Double>, alone: List<Plan>, windows: List<List<Win>>, sigma: Double): List<List<Int>> {
        class G(val bars: MutableList<Int>, val members: MutableList<DoubleArray>) {
            var centroid: DoubleArray = members[0]
            fun update() { centroid = DoubleArray(members[0].size) { k -> median(members.map { it[k] }) } }
        }
        val locals = byBar.indices.map { b -> byBar[b].map { it.y - starts[b] }.toDoubleArray() }
        /** Distance of bar [b] to group [g] (sum of squares), or null when it is another figure; [slip]: one tap may be a step off. */
        fun distance(b: Int, g: G, slip: Boolean): Double? {
            val local = locals[b]
            val g0 = g.bars[0]
            if (local.isEmpty() || lengths[g0] != lengths[b] || g.centroid.size != local.size || windows[g0].size != windows[b].size) return null
            val plan = alone[g0]
            var d = 0.0; var mismatches = 0; var positions = 0; var slips = 0
            for (k in local.indices) {
                val diff = abs(local[k] - g.centroid[k])
                val step = max(segAt(modelAt(plan, g.centroid[k]), g.centroid[k]).step.toDouble(), 1.0 / 16)
                val tap = byBar[b][k]
                // Further than 0.85 grid steps: another figure, except (second pass) one slip of up to 1.1 steps.
                if (diff > 0.85 * step) { if (slip && local.size >= SLIP_MIN_TAPS && diff <= 1.1 * step && slips == 0) { slips++; continue }; return null }
                // Position: agreeing means this tap alone snaps where the group's centroid snaps (or lies close to it).
                val own = nearest(local[k], segAt(modelAt(alone[b], local[k]), local[k])).toDouble()
                val groupPoint = nearest(g.centroid[k], segAt(modelAt(plan, g.centroid[k]), g.centroid[k])).toDouble()
                if (abs(own - groupPoint) > 1e-9 && (diff > 0.6 * step || diff * tap.spw > 3.5 * sigma * sqrt(1.0 + 1.0 / g.bars.size))) positions++
                // Same figure means mostly the same articulation as the group's majority (short tap / held, held
                // releases close to the members' median release).
                val shortVotes = g.bars.count { roughShort(byBar[it][k]) }
                val majorityShort = shortVotes * 2 > g.bars.size
                val tie = shortVotes * 2 == g.bars.size
                val ups = g.bars.mapNotNull { m -> byBar[m][k].takeIf { !roughShort(it) && !it.xUp.isNaN() }?.let { it.xUp - starts[m] } }
                val differs = (!tie && roughShort(tap) != majorityShort) ||
                    (!roughShort(tap) && !tap.xUp.isNaN() && ups.isNotEmpty() && abs((tap.xUp - starts[b]) - median(ups)) > 0.75 * step)
                if (differs) mismatches++
                d += diff * diff
            }
            if (mismatches * 3 > local.size || positions * 5 > local.size) return null
            // A slip only in an otherwise clean repeat: every other tap agrees in position and articulation.
            if (slips > 0 && (mismatches > 0 || positions > 0)) return null
            return d
        }
        val groups = ArrayList<G>()
        for (b in byBar.indices) {
            val best = groups.mapNotNull { g -> distance(b, g, slip = false)?.let { g to it } }.minByOrNull { it.second }?.first
            if (best == null) groups += G(mutableListOf(b), mutableListOf(locals[b]))
            else { best.bars += b; best.members += locals[b]; best.update() }
        }
        // Second pass: a bar left alone joins a repeated figure (at least two bars, so the median puts the slip back) when it
        // differs by one tap that is a whole step off - unless another lone bar is the same as it (then it is a variation).
        for (g in groups.filter { it.bars.size == 1 }) {
            val b = g.bars[0]
            if (groups.any { o -> o !== g && o.bars.size == 1 && distance(b, o, slip = false) != null }) continue
            val target = groups.filter { it.bars.size >= 2 }.mapNotNull { o -> distance(b, o, slip = true)?.let { o to it } }.minByOrNull { it.second }?.first ?: continue
            target.bars += b; target.members += locals[b]; g.bars.clear()
        }
        return groups.filter { it.bars.isNotEmpty() }.map { it.bars }
    }

    /** Short tap by the Zyklus-25 threshold for a 16th (the grid is not chosen yet when bars are grouped). */
    private fun roughShort(t: Tap): Boolean = !t.xUp.isNaN() && (t.xUp - t.x) * t.spw < max(RhythmQuantizer.SHORT_TAP_SECONDS, RhythmQuantizer.SHORT_TAP_STEPS / 16 * t.spw)

    /** Snaps the taps of group [g]: from the members' centroid (identical figures), alone from the tap itself. */
    private fun snapGroup(g: List<Int>, byBar: List<List<Tap>>, starts: List<Double>, plans: List<Plan>) {
        val plan = plans[g[0]]
        val count = byBar[g[0]].size
        val cs = DoubleArray(count) { k -> median(g.map { b -> byBar[b][k].y - starts[b] }) }
        val qs = Array(count) { k -> nearest(cs[k], segAt(modelAt(plan, cs[k]), cs[k])) }
        // Two distinct taps on one grid point (an outlier in a dense run, not a finger bounce - those are merged before):
        // the one further from the point moves to the free neighbouring point on its side, if it is within one step.
        for (k in 1 until count) {
            if (qs[k] != qs[k - 1]) continue
            val seg = segAt(modelAt(plan, cs[k]), cs[k])
            val left = qs[k - 1] - seg.step; val right = qs[k] + seg.step
            val leftFree = left >= Fraction.ZERO && (k < 2 || qs[k - 2] < left)
            val rightFree = (k + 1 >= count || qs[k + 1] > right) && right <= plan.models.last().end
            val dl = abs(cs[k - 1] - left.toDouble()); val dr = abs(cs[k] - right.toDouble())
            val step = seg.step.toDouble()
            if (cs[k] - cs[k - 1] < 0.4 * step) continue   // a bounce that escaped the 64th rule: stays one stroke
            if (leftFree && (!rightFree || dl <= dr) && dl <= step) qs[k - 1] = left
            else if (rightFree && dr <= step) qs[k] = right
        }
        for (k in 0 until count) for (b in g) byBar[b][k].q = qs[k]
    }

    /**
     * Context of the recording as a factor on the priors of the next round: a family used in several places is cheaper
     * (×0.5), one that the previous round used at most once - or not at all - pays its introduction ([P_INTRO], MDL: the
     * family is coded once per recording), so a single window with two late taps does not get 32nds or triplets.
     */
    private fun familyDiscount(plans: List<Plan>, byBar: List<List<Tap>>): Map<Family, Double> {
        val counts = HashMap<Family, Int>(); var used = 0
        for ((b, p) in plans.withIndex()) if (byBar[b].isNotEmpty()) for (m in p.models) { counts[m.family] = (counts[m.family] ?: 0) + 1; used++ }
        val priors = mapOf(Family.FINE to P_FINE32, Family.TRIPLET to P_TRIPLET, Family.QUARTER_TRIPLET to P_TRIPLET, Family.SEXTUPLET to P_SEXTUPLET,
            Family.QUINTUPLET to P_QUINTUPLET, Family.SEPTUPLET to P_SEPTUPLET, Family.NESTED to P_NESTED)
        return priors.mapValues { (f, p) ->
            val c = counts[f] ?: 0
            when { c >= max(3, (0.15 * used).toInt()) -> 0.5; c <= 1 -> 1.0 + P_INTRO / p; else -> 1.0 }
        }
    }

    // ------------------------------------------------------------------ drift

    private fun kernel(d: Double): Double { val h = DRIFT_BAND_QUARTERS / 4; return exp(-0.5 * (d / h) * (d / h)) }

    private fun initialDrift(kept: List<Tap>, starts: List<Fraction>, lengths: List<Fraction>, barOf: (Double) -> Int) {
        val c = 0.025
        val r = kept.map { t ->
            val b = barOf(maxOf(0.0, t.x)); val local = t.x - starts[b].toDouble()
            val q = kotlin.math.round(local * 8) / 8
            local - q
        }
        val rho = r.map { if (abs(it) < c) (1 - (it / c) * (it / c)).let { u -> u * u } else 0.0 }
        for ((i, t) in kept.withIndex()) { t.r = r[i]; t.rho = rho[i] }
        for ((i, t) in kept.withIndex()) {
            var num = 0.0; var den = RIDGE
            for ((j, o) in kept.withIndex()) if (j != i) { val k = kernel(t.x - o.x) * rho[j]; num += k * r[j]; den += k }
            t.w = (num / den).coerceIn(-DRIFT_MAX, DRIFT_MAX)
        }
    }

    private fun updateDrift(kept: List<Tap>, starts: List<Fraction>, sigma: Double) {
        val r = kept.map { it.x - (starts[it.bar] + it.q).toDouble() }
        for ((i, t) in kept.withIndex()) {
            val u = abs(r[i] - t.w) * t.spw
            t.rho = if (u <= 1.5 * sigma) 1.0 else 1.5 * sigma / u
        }
        val next = DoubleArray(kept.size)
        for ((i, t) in kept.withIndex()) {
            var num = 0.0; var den = RIDGE
            for ((j, o) in kept.withIndex()) if (j != i) { val k = kernel(t.x - o.x) * o.rho; num += k * r[j]; den += k }
            next[i] = (num / den).coerceIn(-DRIFT_MAX, DRIFT_MAX)
        }
        for ((i, t) in kept.withIndex()) { t.w = next[i]; t.r = r[i] }
    }

    /** The smoothed deviation at a release position (all taps' residuals of the last round, same kernel and weights). */
    private fun driftAt(kept: List<Tap>, x: Double): Double {
        if (kept.isEmpty()) return 0.0
        var num = 0.0; var den = RIDGE
        for (t in kept) { val k = kernel(x - t.x) * t.rho; num += k * t.r; den += k }
        return (num / den).coerceIn(-DRIFT_MAX, DRIFT_MAX)
    }

    // ------------------------------------------------------------------ durations

    private fun noteEnds(onsets: List<Tap>, groups: List<List<Int>>, plans: List<Plan>, starts: List<Fraction>, lengths: List<Fraction>, total: Fraction, sampleRate: Int, barOf: (Double) -> Int): List<Fraction> {
        val globalStart = onsets.map { starts[it.bar] + it.q }
        // Releases of grouped figures: the centroid of the members' bar-local releases (same tap index in each member).
        val shared = HashMap<Tap, Double>()
        val byBar = onsets.groupBy { it.bar }
        for (g in groups) if (g.size > 1) {
            val lists = g.map { byBar[it].orEmpty() }
            val n = lists[0].size
            if (lists.any { it.size != n }) continue
            for (k in 0 until n) {
                val ups = lists.mapIndexedNotNull { mi, l -> l[k].yUp.takeIf { !it.isNaN() }?.let { u -> u - starts[g[mi]].toDouble() } }
                if (ups.size * 2 < lists.size) continue
                // Majority vote on „short tap“ first, then the centroid of the majority.
                val short = lists.map { l -> isShort(l[k], sampleRate, plans, starts) }
                val shortVotes = short.count { it }
                val keepShort = shortVotes * 2 > lists.size
                val chosen = lists.indices.filter { short[it] == keepShort && !lists[it][k].yUp.isNaN() }
                if (chosen.isEmpty()) continue
                val c = chosen.sumOf { lists[it][k].yUp - starts[g[it]].toDouble() } / chosen.size
                for ((mi, l) in lists.withIndex()) shared[l[k]] = if (keepShort) Double.NEGATIVE_INFINITY else c + starts[g[mi]].toDouble()
            }
        }
        return onsets.mapIndexed { i, t ->
            val start = globalStart[i]
            val next = globalStart.getOrNull(i + 1) ?: total
            val plan = plans[t.bar]
            val m = modelAt(plan, t.q.toDouble())
            val seg = segAt(m, t.q.toDouble())
            val shortLen = if (seg.chain.isEmpty()) minOf(seg.step, Fraction.of(1, 16)) else seg.step
            val up = shared[t] ?: t.yUp
            val end = when {
                up.isNaN() -> next
                up == Double.NEGATIVE_INFINITY || (shared[t] == null && isShort(t, sampleRate, plans, starts)) -> start + shortLen
                else -> {
                    val u = up.coerceIn(0.0, total.toDouble())
                    val ub = barOf(minOf(u, total.toDouble() - 1e-12))
                    val ul = u - starts[ub].toDouble()
                    val um = modelAt(plans[ub], ul)
                    val us = segAt(um, ul)
                    var snapped = starts[ub] + nearest(ul, us)
                    // Human early release just before the next attack: hold to it.
                    val gapSec = (next.toDouble() - u) * t.spw
                    val stepSec = maxOf(shortLen.toDouble(), us.step.toDouble()) * t.spw
                    if (snapped < next && gapSec < 0.6 * stepSec + 0.03) snapped = next
                    if (snapped < start + shortLen) start + shortLen else snapped
                }
            }
            if (end > next) next else end
        }
    }

    /** Zyklus-25 short-tap rule: held less than max(120 ms, 0.6 × the short value) is an attack only. */
    private fun isShort(t: Tap, sampleRate: Int, plans: List<Plan>, starts: List<Fraction>): Boolean {
        if (t.yUp.isNaN()) return false
        val m = modelAt(plans[t.bar], t.q.toDouble())
        val seg = segAt(m, t.q.toDouble())
        val shortLen = if (seg.chain.isEmpty()) minOf(seg.step, Fraction.of(1, 16)) else seg.step
        val held = (t.xUp - t.x) * t.spw
        return held < max(RhythmQuantizer.SHORT_TAP_SECONDS, RhythmQuantizer.SHORT_TAP_STEPS * shortLen.toDouble() * t.spw)
    }

    // ------------------------------------------------------------------ writing

    private fun writeBars(spans: List<RouteSpan>, lengths: List<Fraction>, starts: List<Fraction>, notes: List<Pair<Fraction, Fraction>>, plans: List<Plan>): List<RhythmBar> =
        spans.mapIndexed { b, span ->
            val len = lengths[b]; val s0 = starts[b]; val s1 = s0 + len
            // Tuplet segments (merged within a model when they share the chain) are cut points and carry their ratio.
            val tsegs = ArrayList<Triple<Fraction, Fraction, List<TupletRatio>>>()
            for (m in plans[b].models) for (s in m.segs) if (s.chain.isNotEmpty()) {
                val last = tsegs.lastOrNull()
                if (last != null && last.second == s.start && last.third == s.chain && m.family != Family.NESTED) tsegs[tsegs.lastIndex] = Triple(last.first, s.end, s.chain)
                else tsegs += Triple(s.start, s.end, s.chain)
            }
            val cuts = tsegs.flatMap { listOf(it.first, it.second) }.distinct()
            val events = ArrayList<RhythmEvent>()
            fun emit(from: Fraction, to: Fraction, kind: RhythmEvent.Kind) {
                var at = from; var k = kind
                while (at < to) {
                    val cut = cuts.filter { it > at && it < to }.minOrNull() ?: to
                    val chain = tsegs.firstOrNull { at >= it.first && at < it.second }?.third ?: emptyList()
                    val values = writeIn(cut - at, chain) ?: throw IllegalArgumentException("Dauer nicht darstellbar")
                    for (v in values) { events += RhythmEvent(at, v, k); at += v.length(); if (k == RhythmEvent.Kind.ONSET) k = RhythmEvent.Kind.CONTINUATION }
                }
            }
            var cursor = Fraction.ZERO
            for ((ns, ne) in notes) {
                if (ne <= s0 || ns >= s1) continue
                val a = (if (ns < s0) s0 else ns) - s0
                val z = (if (ne > s1) s1 else ne) - s0
                if (a > cursor) emit(cursor, a, RhythmEvent.Kind.REST)
                emit(a, z, if (ns < s0) RhythmEvent.Kind.CONTINUATION else RhythmEvent.Kind.ONSET)
                cursor = z
            }
            if (cursor < len) emit(cursor, len, RhythmEvent.Kind.REST)
            RhythmBar(span.measureId, len, events)
        }

    /** [length] in the tuplet [chain] (written = length × Π actual/normal must be dyadic), plain values otherwise. */
    fun writeIn(length: Fraction, chain: List<TupletRatio>): List<WrittenDuration>? {
        if (length <= Fraction.ZERO) return emptyList()
        if (chain.isEmpty()) return Durations.decompose(length)
        var written = length
        for (t in chain) written *= Fraction.of(t.actual.toLong(), t.normal.toLong())
        val plain = Durations.decompose(written) ?: return null
        if (plain.any { it.tuplets.isNotEmpty() }) return null
        return plain.map { it.copy(tuplets = chain) }
    }

    // ------------------------------------------------------------------ summary

    private fun summary(plans: List<Plan>, groups: List<List<Int>>, empty: Boolean): String {
        if (empty) return "keine Anschläge"
        val binary = plans.flatMap { it.models }.filter { it.family == Family.BINARY || it.family == Family.FINE }.map { it.segs.first().step }
        val finest = binary.minOrNull()
        val base = when {
            finest == null -> null
            finest <= Fraction.of(1, 64) -> "64tel"
            finest <= Fraction.of(1, 32) -> "32tel"
            finest <= Fraction.of(1, 16) -> "16tel"
            finest <= Fraction.of(1, 8) -> "8tel"
            else -> "Viertel"
        }
        val parts = ArrayList<String>()
        base?.let { parts += it }
        val tuplets = LinkedHashMap<String, MutableList<Int>>()
        for ((b, p) in plans.withIndex()) for (m in p.models) if (m.family != Family.BINARY && m.family != Family.FINE) tuplets.getOrPut(m.label) { mutableListOf() }.let { if (b + 1 !in it) it += b + 1 }
        for ((label, bars) in tuplets) parts += "$label in Takt ${bars.joinToString(", ")}"
        val repeats = groups.filter { it.size > 1 }
        if (repeats.isNotEmpty()) parts += repeats.joinToString(", ") { g -> "Takt " + g.sorted().joinToString("=") { (it + 1).toString() } } + " gleich"
        return "erkannt: " + parts.joinToString(", ")
    }

    private fun median(v: List<Double>): Double { val s = v.sorted(); val n = s.size; return if (n == 0) 0.0 else if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2 }
}

/**
 * Zyklus 28 „Taktart erkennen“ (Nutzerwunsch: nicht alles ist 4/4). Works on the quantised notes (after pulse tracking):
 * the click stays the time base, wanted is the period the tapped figure repeats with and its grouping.
 * - Onset vector on the finest dyadic unit the notes need (8th or 16th; tuplet onsets round to it), accent = 1 + longer
 *   held notes + notes after a longer gap.
 * - Every candidate meter (period in units) is scored by the self-similarity of the onsets at a lag of one bar or two
 *   bars (a two-bar riff), Dice coefficient over the overlap, minus a complexity penalty (common meters first). It needs
 *   two full periods of taps; 6/8 vs 3/4 (same length) are told apart by the accent profile (3+3 8ths vs 2+2+2).
 * - Phase: the range start (the user starts on „1“ after the count-in), or the first attack after a leading rest.
 * - A change is only proposed with clear evidence: similarity ≥ [MIN_SCORE] and at least [MARGIN] above the current meter.
 */
object MeterDetector {
    const val MIN_SCORE = 0.85
    const val MARGIN = 0.08
    /** Candidates with their complexity penalty (common meters first). */
    val CANDIDATES: List<Pair<TimeSignature, Double>> = listOf(
        TimeSignature(4, 4) to 0.0, TimeSignature(3, 4) to 0.03, TimeSignature(6, 8) to 0.03, TimeSignature(2, 4) to 0.06, TimeSignature(12, 8) to 0.05,
        TimeSignature(5, 4) to 0.06, TimeSignature(7, 8) to 0.06, TimeSignature(5, 8) to 0.07, TimeSignature(9, 8) to 0.07, TimeSignature(7, 4) to 0.08,
        TimeSignature(11, 8) to 0.09, TimeSignature(13, 8) to 0.10, TimeSignature(15, 16) to 0.10, TimeSignature(11, 16) to 0.11, TimeSignature(15, 8) to 0.11)

    fun penalty(sig: TimeSignature): Double = CANDIDATES.firstOrNull { it.first == sig }?.second ?: 0.12

    /** [notes]: global (start, end) in whole notes; analysed from [from] to [total]. */
    fun detect(notes: List<Pair<Fraction, Fraction>>, from: Fraction, total: Fraction, current: TimeSignature, @Suppress("UNUSED_PARAMETER") denominators: List<Long> = emptyList()): MeterGuess {
        val keep = MeterGuess(null, current, Fraction.ZERO, 0.0, 0.0, 0)
        val inside = notes.filter { it.first >= from && it.first < total }
        if (inside.size < 4) return keep
        val rel = inside.map { (s, e) -> (s.toDouble() - from.toDouble()) to (e.toDouble() - s.toDouble()) }
        val sixteenths = rel.any { (s, _) -> abs(s * 8 - kotlin.math.round(s * 8)) > 1e-6 }
        val unitsPerWhole = if (sixteenths) 16 else 8
        val endD = maxOf(rel.maxOf { it.first + it.second }, rel.maxOf { it.first } + 1.0 / unitsPerWhole)
        val n = kotlin.math.ceil(endD * unitsPerWhole - 1e-9).toInt()
        val onset = BooleanArray(n + 1)
        val accent = DoubleArray(n + 1)
        val iois = rel.zipWithNext { a, b -> b.first - a.first }.sorted()
        val medianIoi = if (iois.isEmpty()) 0.25 else iois[iois.size / 2]
        for ((i, p) in rel.withIndex()) {
            val t = kotlin.math.round(p.first * unitsPerWhole).toInt().coerceIn(0, n)
            onset[t] = true
            val gap = if (i == 0) 0.0 else p.first - rel[i - 1].first
            accent[t] = maxOf(accent[t], 1.0 + 0.5 * minOf(1.0, p.second * unitsPerWhole / 4.0) + (if (gap > 1.5 * medianIoi) 0.5 else 0.0))
        }
        fun dice(lag: Int): Double? {
            if (lag <= 0 || n < 2 * lag) return null
            var both = 0; var count = 0
            for (t in 0 until n - lag) { if (onset[t] && onset[t + lag]) both++; if (onset[t]) count++; if (onset[t + lag]) count++ }
            return if (count == 0) null else 2.0 * both / count
        }
        fun periodOf(sig: TimeSignature): Int? { val u = sig.length().toDouble() * unitsPerWhole; val r = kotlin.math.round(u); return if (abs(u - r) < 1e-9) r.toInt() else null }
        fun score(sig: TimeSignature): Double? {
            val p = periodOf(sig) ?: return null
            val one = dice(p); val two = dice(2 * p)?.let { it - 0.03 }
            val best = listOfNotNull(one, two).maxOrNull() ?: return null
            return best - penalty(sig)
        }
        val cur = score(current) ?: return keep
        val scored = CANDIDATES.mapNotNull { (sig, _) -> score(sig)?.let { sig to it } }
        var best = scored.maxByOrNull { it.second } ?: return keep
        // Same period, other grouping: 3/4 ↔ 6/8 (and 6/4-like 12/8 is a different period) by the accent profile.
        val p = periodOf(best.first)!!
        fun profile(at: Int): Double { var s = 0.0; var c = 0; var t = at; while (t <= n) { s += if (onset[t]) accent[t] else 0.0; c++; t += p }; return if (c == 0) 0.0 else s / c }
        if ((best.first == TimeSignature(3, 4) || best.first == TimeSignature(6, 8)) && p % 6 == 0) {
            val third = profile(p / 3) + profile(2 * p / 3); val half = profile(p / 2)
            best = (if (half > 0.6 * third) TimeSignature(6, 8) else TimeSignature(3, 4)) to best.second
        }
        val raw = best.second + penalty(best.first)
        if (best.first == current || raw < MIN_SCORE || best.second < cur + MARGIN) return MeterGuess(null, current, Fraction.ZERO, best.second, cur, n / maxOf(1, p))
        // Phase: the user starts on „1“ after the count-in, so the bar begins at the range start - or, after a leading
        // rest of at least an 8th, at the first attack (the riff starts there). Accents alone do not tell a pickup from a
        // downbeat reliably (measured: the longest note of a riff is often not its first), so they are not used for it.
        val first = (0..n).firstOrNull { onset[it] } ?: 0
        val phase = if (first * 8 >= unitsPerWhole && first < p) first else 0
        return MeterGuess(best.first, current, Fraction.of(phase.toLong(), unitsPerWhole.toLong()), best.second, cur, (n - phase) / p)
    }
}
