package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlin.math.floor

/**
 * Written time -> seconds for one measure, independent of the route: tempo in effect at the measure,
 * tempo changes inside it, swing/triplet feel and fermatas. Tempo follows written order, so a D.C.
 * back to bar 1 uses bar 1's tempo again.
 */
class MeasureTiming(val length: Fraction, val offsets: DoubleArray, val seconds: DoubleArray) {
    val durationSeconds: Double get() = seconds.last()
}

object Timing {
    const val DEFAULT_QPM = 120.0

    fun measureTimings(project: Project): List<MeasureTiming> {
        val index = project.measures.withIndex().associate { it.value.id to it.index }
        val changes = project.tempo.filter { it.position.measureId in index && it.quarterNotesPerMinute.isFinite() && it.quarterNotesPerMinute > 0 }
            .sortedWith(compareBy({ index.getValue(it.position.measureId) }, { it.position.offset }))
        val fermatas = fermatasByMeasure(project)
        var running = changes.firstOrNull()?.quarterNotesPerMinute ?: DEFAULT_QPM
        return project.measures.mapIndexed { m, measure ->
            val length = measure.pickupLength ?: measure.signature.length()
            val lengthD = length.toDouble()
            // Tempo in effect at the start of the bar: last change before or at offset 0.
            changes.filter { index.getValue(it.position.measureId) < m || (index.getValue(it.position.measureId) == m && it.position.offset == Fraction.ZERO) }
                .lastOrNull()?.let { running = it.quarterNotesPerMinute }
            val inside = changes.filter { index.getValue(it.position.measureId) == m && it.position.offset > Fraction.ZERO && it.position.offset < length }
                .map { it.position.offset.toDouble() to it.quarterNotesPerMinute }
            val startTempo = running
            inside.lastOrNull()?.let { running = it.second }
            val bars = fermatas[measure.id].orEmpty()
            val points = mutableListOf(0.0, lengthD)
            inside.forEach { points += it.first }
            bars.forEach { (start, len, _) -> points += start.coerceIn(0.0, lengthD); points += (start + len).coerceIn(0.0, lengthD) }
            val grid = when (measure.feel) { RhythmicFeel.SWING_SIXTEENTH -> 1.0 / 16; RhythmicFeel.STRAIGHT -> 0.0; else -> 1.0 / 8 }
            if (grid > 0) { var x = grid; while (x < lengthD - 1e-12) { points += x; x += grid } }
            val offsets = uniqueSorted(points)
            val seconds = DoubleArray(offsets.size)
            for (i in 1 until offsets.size) {
                val a = offsets[i - 1]; val b = offsets[i]
                val qpm = inside.lastOrNull { it.first <= a + 1e-12 }?.second ?: startTempo
                val stretch = bars.filter { a >= it.first - 1e-12 && a < it.first + it.second - 1e-12 }.maxOfOrNull { it.third } ?: 1.0
                val performed = swing(b, measure.feel) - swing(a, measure.feel)
                seconds[i] = seconds[i - 1] + performed * 4.0 * 60.0 / qpm * stretch
            }
            MeasureTiming(length, offsets, seconds)
        }
    }

    private fun uniqueSorted(values: List<Double>): DoubleArray {
        val sorted = values.sorted()
        val out = ArrayList<Double>(sorted.size)
        for (v in sorted) if (out.isEmpty() || v - out.last() > 1e-12) out += v
        return out.toDoubleArray()
    }

    /** Piecewise-linear swing warp in whole notes; keeps every quarter (or eighth for 16th swing) boundary fixed. */
    fun swing(x: Double, feel: RhythmicFeel): Double {
        val cell = when (feel) { RhythmicFeel.STRAIGHT -> return x; RhythmicFeel.SWING_SIXTEENTH -> 1.0 / 8; else -> 1.0 / 4 }
        val k = floor(x / cell + 1e-9)
        val r = (x - k * cell).coerceAtLeast(0.0)
        val half = cell / 2
        val warped = if (r <= half) r * (2.0 / 3.0 * cell) / half else 2.0 / 3.0 * cell + (r - half) * (cell / 3.0) / half
        return k * cell + warped
    }

    private fun fermatasByMeasure(project: Project): Map<EntityId, List<Triple<Double, Double, Double>>> {
        val result = HashMap<EntityId, MutableList<Triple<Double, Double, Double>>>()
        for (track in project.tracks) for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) {
            val fermata = beat.fermata ?: continue
            val multiplier = fermata.multiplier.takeIf { it.isFinite() && it >= 1.0 }?.coerceAtMost(8.0) ?: continue
            val list = result.getOrPut(bar.measureId) { mutableListOf() }
            val start = beat.offset.toDouble()
            val existing = list.indexOfFirst { kotlin.math.abs(it.first - start) < 1e-12 }
            val entry = Triple(start, beat.duration.length().toDouble(), multiplier)
            if (existing < 0) list += entry else list[existing] = Triple(start, maxOf(list[existing].second, entry.second), maxOf(list[existing].third, entry.third))
        }
        return result
    }

    /** Spans for a route; frames are fractional so that long pieces never accumulate rounding drift. */
    fun spans(project: Project, route: List<PlaybackOccurrence>, sampleRate: Int, speed: Double, timings: List<MeasureTiming> = measureTimings(project)): List<RouteSpan> {
        val index = project.measures.withIndex().associate { it.value.id to it.index }
        var seconds = 0.0
        val framesPerSecond = sampleRate / speed
        return route.map { occurrence ->
            val m = index.getValue(occurrence.position.measureId)
            val timing = timings[m]
            val frames = DoubleArray(timing.seconds.size) { (seconds + timing.seconds[it]) * framesPerSecond }
            seconds += timing.durationSeconds
            RouteSpan(occurrence.routeIndex, occurrence.position.measureId, m, occurrence.occurrence, timing.length, timing.offsets, frames)
        }
    }
}

/** C-TEMPO: route occurrence -> microseconds from the start of the unfolded route at speed 1. */
class TimelineTempoMapper(private val repeats: RepeatCompiler = UnfoldingRepeatCompiler()) : TempoMapper {
    override fun toMicros(position: PlaybackOccurrence, project: Project): Outcome<Long> {
        val route = when (val r = repeats.compile(project)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val span = Timing.spans(project, route, 1_000_000, 1.0).getOrNull(position.routeIndex)
            ?: return Outcome.Failure(FailureCode.VALIDATION, "Position liegt außerhalb der Wiedergaberoute", contractId = "C-TEMPO")
        if (span.measureId != position.position.measureId) return Outcome.Failure(FailureCode.VALIDATION, "Takt und Routenindex passen nicht zusammen", contractId = "C-TEMPO")
        return Outcome.Success(kotlin.math.round(span.frameAt(position.position.offset.toDouble())).toLong())
    }
}

/**
 * C-SYNC: piecewise-linear map between route positions and audio time. Anchors are ordered along the
 * route; outside the anchored range the nearest segment's rate (or score tempo with one anchor) is used.
 */
class AnchorSyncMapper(private val project: Project, private val repeats: RepeatCompiler = UnfoldingRepeatCompiler()) : AudioSyncMapper {
    private val spans by lazy { (repeats.compile(project) as? Outcome.Success)?.value?.let { Timing.spans(project, it, 1_000_000, 1.0) }.orEmpty() }
    private fun scoreMicros(position: PlaybackOccurrence): Double? =
        spans.getOrNull(position.routeIndex)?.takeIf { it.measureId == position.position.measureId }?.frameAt(position.position.offset.toDouble())

    override fun toAudioMicros(position: PlaybackOccurrence, map: SyncMap): Outcome<Long> {
        val x = scoreMicros(position) ?: return Outcome.Failure(FailureCode.VALIDATION, "Position liegt außerhalb der Route", contractId = "C-SYNC")
        if (map.mode == SyncMode.SCORE_TEMPO || map.anchors.isEmpty()) return Outcome.Success(kotlin.math.round(x).toLong())
        val anchors = map.anchors.mapNotNull { a -> scoreMicros(a.occurrence)?.let { it to a.audioMicros.toDouble() } }.sortedBy { it.first }
        if (anchors.isEmpty()) return Outcome.Failure(FailureCode.VALIDATION, "Sync-Anker liegen außerhalb der Route", contractId = "C-SYNC")
        if (anchors.size == 1) return Outcome.Success(kotlin.math.round(anchors[0].second + (x - anchors[0].first)).toLong())
        val i = anchors.indexOfFirst { it.first >= x }.let { if (it <= 0) 1 else it }.coerceAtMost(anchors.lastIndex)
        val (x0, y0) = anchors[i - 1]; val (x1, y1) = anchors[i]
        val rate = if (x1 > x0) (y1 - y0) / (x1 - x0) else 1.0
        return Outcome.Success(kotlin.math.round(y0 + (x - x0) * rate).toLong())
    }
}
