package dev.riffworkbench.adapter.formats.midi

import dev.riffworkbench.model.*

/**
 * Rhythm spelling: splits an exact time span inside a measure into written durations that sum to the span.
 *
 * Measures are divided into quarter-note windows counted from the measure start. A window can be "triplet"
 * (positions on the 1/12 grid); there the span is cut at the window borders and spelled with 3:2 values so that
 * every triplet group stays inside one quarter. Elsewhere plain and dotted values are used; a value of a quarter
 * or longer must start on a quarter border, shorter values on a multiple of their own length (dotted values on
 * a multiple of their dot). Anything the grids cannot express falls back to one exact tuplet value, so the sum is
 * always exact.
 */
internal object Rhythm {
    data class Piece(val offset: Fraction, val duration: WrittenDuration, val tripletWindow: Int?)

    private class Candidate(val duration: WrittenDuration, val length: Fraction, val unit: Fraction)

    private val straight: List<Candidate> = buildList {
        var d = 1
        while (d <= 1024) {
            for (dots in 0..2) {
                if (dots > 0 && d > 256) continue
                val w = WrittenDuration(d, dots)
                add(Candidate(w, w.length(), minF(Fraction.of(1, d.toLong() shl dots), QUARTER)))
            }
            d *= 2
        }
    }.sortedByDescending { it.length }

    private val tripletRatio = TupletRatio(3, 2)
    private val triplets: List<Candidate> = listOf(4, 8, 16, 32).map {
        val w = WrittenDuration(it, 0, listOf(tripletRatio))
        // Triplet quarters may start on any triplet eighth; smaller values on multiples of themselves.
        Candidate(w, w.length(), if (it == 4) Fraction.of(1, 12) else w.length())
    }

    /** All single written values (plain, dotted, simple triplet) keyed by exact length. */
    private val singles: Map<Fraction, WrittenDuration> = buildMap {
        straight.forEach { putIfAbsent(it.length, it.duration) }
        listOf(2, 4, 8, 16, 32, 64, 128).forEach { d -> val w = WrittenDuration(d, 0, listOf(tripletRatio)); putIfAbsent(w.length(), w) }
    }

    /** A single written value of exactly [length], preferring plain/dotted over triplets, or null. */
    fun single(length: Fraction): WrittenDuration? = singles[length]

    /** One written value of exactly [length]; uses a tuplet ratio when no plain value fits. */
    fun exact(length: Fraction): WrittenDuration {
        require(length > Fraction.ZERO) { "Dauer muss positiv sein" }
        single(length)?.let { return it }
        var d = 1L
        while (d < 1024 && Fraction.of(1, d * 2) >= length) d *= 2
        val ratio = length * Fraction.of(d) // = normal / actual
        if (ratio.numerator > Int.MAX_VALUE || ratio.denominator > Int.MAX_VALUE) corrupt("Notendauer ist nicht darstellbar")
        return WrittenDuration(d.toInt(), 0, listOf(TupletRatio(ratio.denominator.toInt(), ratio.numerator.toInt())))
    }

    /**
     * Spells [start, end) (offsets relative to the measure start) inside a measure of [measureLength].
     * [tripletWindow] tells whether quarter window k uses the triplet grid.
     */
    fun spell(start: Fraction, end: Fraction, measureLength: Fraction, tripletWindow: (Int) -> Boolean = { false }): List<Piece> {
        val out = ArrayList<Piece>()
        var x = start
        var guard = 0
        while (x < end) {
            if (++guard > 100_000) corrupt("Rhythmus ist nicht darstellbar")
            val k = (x / QUARTER).floor().toInt()
            val wStart = QUARTER * frac(k.toLong())
            val wEnd = minF(wStart + QUARTER, measureLength)
            if (wEnd - wStart == QUARTER && tripletWindow(k)) {
                val segEnd = minF(end, wEnd)
                val len = segEnd - x
                val piece = when {
                    x == wStart && segEnd == wEnd -> Candidate(WrittenDuration(4), QUARTER, QUARTER)
                    else -> triplets.firstOrNull { it.length <= len && (x - wStart).isMultipleOf(it.unit) }
                }
                if (piece != null) {
                    out += Piece(x, piece.duration, if (piece.duration.tuplets.isEmpty()) null else k)
                    x += piece.length
                } else {
                    val w = exact(len)
                    out += Piece(x, w, null)
                    x = segEnd
                }
            } else {
                var segEnd = end
                var j = k + 1
                while (QUARTER * frac(j.toLong()) < segEnd) {
                    val js = QUARTER * frac(j.toLong())
                    if (js + QUARTER <= measureLength && tripletWindow(j)) { segEnd = js; break }
                    j++
                }
                val len = segEnd - x
                val c = straight.firstOrNull { it.length <= len && x.isMultipleOf(it.unit) }
                if (c != null) {
                    out += Piece(x, c.duration, null)
                    x += c.length
                } else {
                    out += Piece(x, exact(len), null)
                    x = segEnd
                }
            }
        }
        return out
    }
}
