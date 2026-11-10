package dev.riffworkbench.editor

import dev.riffworkbench.model.*

/**
 * Cursor steps inside one bar (Zyklus 23, „Raster feiner als 1/16 nicht nutzbar“): a step never ends inside a beat of the
 * edited voice, so the next entry never collides with an existing beat. Pure; the caller handles bar changes.
 *
 * - [forward]: `from + step` (from a beat start at least to its end), but never past the start of the next beat after [from] (a coarse grid stops at the beat).
 * - [backward]: the closest stop before [from]: `from − step` (clamped to the bar start), moved to the start of the beat
 *   that covers it, or a later beat start that lies before [from]. Inside a bar it never leaves the bar.
 */
object CursorSteps {
    fun forward(beats: List<Beat>, from: Fraction, step: Fraction): Fraction {
        // From a beat start at least to its end (a finer step would end inside it).
        val own = beats.firstOrNull { it.offset == from }?.duration?.length()
        val t = from + if (own != null && own > step) own else step
        val next = beats.filter { it.offset > from }.minOfOrNull { it.offset }
        return if (next != null && next < t) next else t
    }

    fun backward(beats: List<Beat>, from: Fraction, step: Fraction): Fraction {
        var t = from + step * Fraction.of(-1)
        if (t < Fraction.ZERO) t = Fraction.ZERO
        beats.firstOrNull { it.offset < t && t < it.offset + it.duration.length() }?.let { t = it.offset }
        return beats.filter { it.offset > t && it.offset < from }.maxOfOrNull { it.offset } ?: t
    }

    /** The beat of [beats] that strictly contains [offset] (starts before it and ends after it), if any. */
    fun covering(beats: List<Beat>, offset: Fraction): Beat? = beats.firstOrNull { it.offset < offset && offset < it.offset + it.duration.length() }
}

/** Written durations for exact lengths (drum-grid refinement, Zyklus 23). */
object Durations {
    private val denominators = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256)

    /** A written duration of exactly [length] (no dots first, then dots, plain before [tuplets]); null if none exists. */
    fun exact(length: Fraction, tuplets: List<TupletRatio> = emptyList()): WrittenDuration? {
        if (length <= Fraction.ZERO) return null
        for (t in listOf(emptyList(), tuplets).distinct()) for (dots in 0..2) for (d in denominators) {
            val w = WrittenDuration(d, dots, t)
            if (w.length() == length) return w
        }
        return null
    }

    private val greedyValues: List<WrittenDuration> = denominators.flatMap { d -> (0..2).map { WrittenDuration(d, it) } }.sortedByDescending { it.length() }
    private fun dyadic(f: Fraction) = f.denominator > 0 && f.denominator and (f.denominator - 1) == 0L && f.denominator <= 256

    /**
     * [length] as a sequence of writable values, longest first (Zyklus 25: rests behind a shortened beat, tapped rhythms):
     * plain values (dots up to 2) when the length is dyadic, else in the first tuplet family of [tuplets] (then 3:2) whose
     * written length is dyadic; null when neither works. A single exact value ([exact]) is preferred.
     */
    fun decompose(length: Fraction, tuplets: List<TupletRatio> = emptyList()): List<WrittenDuration>? {
        if (length <= Fraction.ZERO) return emptyList()
        exact(length, tuplets.take(1))?.let { return listOf(it) }
        fun greedy(rest0: Fraction, t: List<TupletRatio>): List<WrittenDuration>? {
            var rest = rest0
            val out = ArrayList<WrittenDuration>()
            while (rest > Fraction.ZERO) {
                val v = greedyValues.firstOrNull { it.length() <= rest } ?: return null
                out += if (t.isEmpty()) v else v.copy(tuplets = t)
                rest += v.length() * Fraction.of(-1)
                if (out.size > 64) return null
            }
            return out
        }
        if (dyadic(length)) return greedy(length, emptyList())
        for (t in (tuplets + TupletRatio(3, 2)).distinct()) {
            val written = length * Fraction.of(t.actual.toLong(), t.normal.toLong())
            if (dyadic(written)) return greedy(written, listOf(t))
        }
        return null
    }

    /**
     * [wanted] if it fits into [room]; otherwise the longest duration of the same tuplet family (then plain) that does, so an
     * entry fills a gap instead of overlapping the next beat; null when nothing fits.
     */
    fun fitting(wanted: WrittenDuration, room: Fraction): WrittenDuration? {
        if (wanted.length() <= room) return wanted
        return listOf(wanted.tuplets, emptyList()).distinct().flatMap { t -> (0..2).flatMap { dots -> denominators.map { WrittenDuration(it, dots, t) } } }
            .filter { it.length() <= room }.maxByOrNull { it.length() }
    }
}
