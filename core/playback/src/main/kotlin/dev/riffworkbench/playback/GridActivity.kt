package dev.riffworkbench.playback

import dev.riffworkbench.model.*

/**
 * Zyklus 30: time base of the drum grid's hit glow and guitar lane — written bar order mapped to seconds at speed 1 through
 * the tempo map ([Timing.measureTimings]: tempo changes, swing, fermatas). The grid shows the bars in written order, so this
 * clock is linear over the bars; the displayed playhead (bar, offset; incl. the Bluetooth delay of Zyklus 26) is mapped
 * through it. Pure and allocation-free per query.
 */
class ScoreClock(project: Project) {
    private val timings = Timing.measureTimings(project)
    private val index = HashMap<EntityId, Int>().apply { project.measures.forEachIndexed { i, m -> put(m.id, i) } }
    /** Start of each bar in seconds; the last entry is the end of the piece. */
    val barStart: DoubleArray = DoubleArray(timings.size + 1).also { s -> timings.forEachIndexed { i, t -> s[i + 1] = s[i] + t.durationSeconds } }

    fun barIndex(measureId: EntityId): Int = index[measureId] ?: -1

    /** Seconds of [offset] (whole notes) in bar [bar]; NaN for an unknown bar. */
    fun seconds(bar: Int, offset: Double): Double {
        val t = timings.getOrNull(bar) ?: return Double.NaN
        return barStart[bar] + secondsAt(t, offset)
    }
    fun seconds(measureId: EntityId, offset: Double): Double = seconds(barIndex(measureId), offset)

    private fun secondsAt(t: MeasureTiming, offset: Double): Double {
        val o = t.offsets; val s = t.seconds
        if (offset <= o[0]) return s[0]
        for (i in 1 until o.size) if (offset <= o[i]) {
            val span = o[i] - o[i - 1]
            return if (span <= 0.0) s[i] else s[i - 1] + (offset - o[i - 1]) / span * (s[i] - s[i - 1])
        }
        return s.last()
    }
}

/**
 * Zyklus 30 „Klick-Ereignisse leuchten“: a set drum cell lights up when its hit sounds and fades over [DECAY_SECONDS]
 * (real time; the playback speed stretches the score time).
 */
object HitGlow {
    const val DECAY_SECONDS = 0.16

    /** Glow 0…1 of a hit at score time [onset] when the playhead is at score time [now] (seconds, speed 1). Ease-out. */
    fun intensity(onset: Double, now: Double, speed: Double = 1.0, decay: Double = DECAY_SECONDS): Float {
        if (onset.isNaN() || now.isNaN() || speed <= 0.0) return 0f
        val elapsed = (now - onset) / speed
        if (elapsed < 0.0 || elapsed >= decay - 1e-9) return 0f
        val left = 1.0 - elapsed / decay
        return (left * left).toFloat()
    }

    /** One set cell: bar index, offset in the bar, kit element and its score time. */
    data class Hit(val bar: Int, val offset: Fraction, val midi: Int, val seconds: Double)

    /** Every hit (note) of [voice] of the first staff of [track], with its score time; sorted by time. */
    fun hits(project: Project, clock: ScoreClock, track: Track, voice: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val staff = track.staves.firstOrNull() ?: return out
        for (bar in staff.bars) {
            val i = clock.barIndex(bar.measureId)
            if (i < 0) continue
            for (v in bar.voices) if (v.index == voice) for (b in v.beats) for (n in b.notes) out += Hit(i, b.offset, n.pitch.midiNumber, clock.seconds(i, b.offset.toDouble()))
        }
        out.sortBy { it.seconds }
        return out
    }

    /** The hits that glow at score time [now], with their intensity (> 0). */
    fun glowing(hits: List<Hit>, now: Double, speed: Double = 1.0, decay: Double = DECAY_SECONDS): List<Pair<Hit, Float>> =
        hits.mapNotNull { h -> intensity(h.seconds, now, speed, decay).takeIf { it > 0f }?.let { h to it } }
}

/**
 * Zyklus 30 „dünne Gitarren-Leiste“: when the guitar plays, per bar — attacks (short strokes) and sounding spans (bars;
 * tied continuations extend a span without an attack; rests stay empty).
 *
 * Several guitars: the lane shows their COMMON activity (union of all guitar tracks, all voices). The drum grid is where the
 * groove is built against the guitar part; the drummer locks to every guitar attack, and the drum screen has no guitar
 * selection of its own (its track selection is the drum track), so „the selected guitar“ would be arbitrary.
 */
object GuitarActivity {
    /** One bar of the lane: merged sounding spans and the attack offsets, both sorted (whole notes). */
    data class BarLane(val spans: List<Pair<Fraction, Fraction>>, val attacks: List<Fraction>) {
        val empty: Boolean get() = spans.isEmpty()
    }

    fun guitarTracks(project: Project): List<Track> = project.tracks.filter { it.instrument.kind == InstrumentKind.GUITAR }

    /** Lane per bar (in [Project.measures] order) of [tracks]; empty list when there is no guitar. */
    fun lanes(project: Project, tracks: List<Track> = guitarTracks(project)): List<BarLane> {
        if (tracks.isEmpty()) return emptyList()
        val continuations = HashSet<EntityId>()
        for (t in tracks) for (st in t.staves) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes) for (l in n.links) if (l.kind == LinkKind.TIE) continuations += l.target
        return project.measures.map { m ->
            val spans = ArrayList<Pair<Fraction, Fraction>>()
            val attacks = sortedSetOf<Fraction>()
            val length = m.pickupLength ?: m.signature.length()
            for (t in tracks) for (st in t.staves) for (bar in st.bars) if (bar.measureId == m.id) for (v in bar.voices) for (b in v.beats) {
                if (b.notes.isEmpty()) continue
                val end = minOf(b.offset + b.duration.length(), length)
                spans += b.offset to end
                if (b.notes.any { it.id !in continuations }) attacks += b.offset
            }
            spans.sortBy { it.first }
            val merged = ArrayList<Pair<Fraction, Fraction>>()
            for (s in spans) {
                val last = merged.lastOrNull()
                if (last != null && s.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, s.second) else merged += s
            }
            BarLane(merged, attacks.toList())
        }
    }

    /**
     * The section that sounds at [offset] of a bar: from the last attack at or before it (or the start of its span, for a
     * tied continuation) to the next attack or the end of the span; null in a rest.
     */
    fun soundingAt(lane: BarLane, offset: Double): Pair<Fraction, Fraction>? {
        val span = lane.spans.firstOrNull { offset >= it.first.toDouble() - 1e-9 && offset < it.second.toDouble() - 1e-9 } ?: return null
        val from = lane.attacks.lastOrNull { it >= span.first && it.toDouble() <= offset + 1e-9 } ?: span.first
        val to = lane.attacks.firstOrNull { it > from && it < span.second } ?: span.second
        return from to to
    }
}
