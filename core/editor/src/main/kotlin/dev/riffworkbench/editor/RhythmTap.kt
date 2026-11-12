package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Zyklus 25 „Rhythmus tippen“: taps to the running metronome become written durations. Pure logic (no Android, no clock):
 * the UI maps each touch-down/-up to an audible plan frame (Transport.audiblePlanFrameAt, calibration subtracted) and hands
 * the frames with the route spans of the target bars to [RhythmQuantizer]; [EditorCommand.ApplyRhythm] writes the result.
 */
enum class RhythmGrid(val label: String, val step: Fraction?) {
    EIGHTH("1/8", Fraction.of(1, 8)), SIXTEENTH("1/16", Fraction.of(1, 16)), THIRTY_SECOND("1/32", Fraction.of(1, 32)),
    /** Per beat straight 16ths or 8th/16th triplets, by the smallest error with a penalty against triplets. */
    AUTO("Auto", null),
}

/** GRID: rest placeholders with the tapped durations (frets are entered afterwards); OPEN_STRING: open-string notes (drums: one kit piece). */
enum class RhythmMode { GRID, OPEN_STRING }

/** One stroke in audible plan frames (calibration already subtracted); [upFrame] NaN while not released. */
data class TapStroke(val downFrame: Double, val upFrame: Double = Double.NaN)

data class RhythmEvent(val offset: Fraction, val duration: WrittenDuration, val kind: Kind) {
    /** ONSET: a tapped attack; CONTINUATION: the same held note after a barline or a value split; REST: silence. */
    enum class Kind { ONSET, CONTINUATION, REST }
}
data class RhythmBar(val measureId: EntityId, val length: Fraction, val events: List<RhythmEvent>)
data class RhythmResult(val bars: List<RhythmBar>, val onsets: Int, val discarded: Int, val merged: Int, val tripletBeats: Int)

/** Where a tapped attack sits (GRID mode): its placeholder beat and the continuation beats that belong to the same held note. */
data class RhythmSlot(val measureId: EntityId, val offset: Fraction, val continuations: List<Pair<EntityId, Fraction>>)

fun RhythmResult.slots(): List<RhythmSlot> {
    val out = ArrayList<RhythmSlot>()
    for (bar in bars) for (e in bar.events) when (e.kind) {
        RhythmEvent.Kind.ONSET -> out += RhythmSlot(bar.measureId, e.offset, emptyList())
        RhythmEvent.Kind.CONTINUATION -> if (out.isNotEmpty()) out[out.lastIndex] = out.last().let { it.copy(continuations = it.continuations + (bar.measureId to e.offset)) }
        RhythmEvent.Kind.REST -> Unit
    }
    return out
}

/**
 * Quantisation rules (Zyklus 25, Nutzerentscheidung „Haltedauer = Notenwert“):
 * - Onset: nearest grid point of its bar (bar-local grid; the barline itself counts, it is the next bar's downbeat).
 *   AUTO decides per beat (a quarter; the remainder of odd meters is its own window; compound meters stay straight)
 *   between straight 16ths and 8th/16th triplets: triplets only when their summed error plus [TRIPLET_PENALTY] is smaller
 *   than the straight error and below [TRIPLET_RATIO] of it („nur wenn klar besser“).
 * - Taps closer than a quarter grid step (AUTO: of a 16th) or on the same grid point are one stroke (finger bounce): the
 *   first down counts, the last release.
 * - Outside the range: before the start (count-in) and after the end are discarded, with half a grid step tolerance at
 *   the start (a slightly early downbeat counts); a tap that snaps onto the end of the range is discarded.
 * - Duration = held time: the release is snapped to the grid like an onset (at least one step). A short tap (held less than
 *   max([SHORT_TAP_SECONDS], [SHORT_TAP_STEPS] grid steps)) is an attack only: one grid step. The note ends at the next
 *   onset at the latest; the rest up to it is rests. A note held over a barline continues in the next bar (CONTINUATION).
 * - Every bar is filled exactly: durations are split into writable values (dots allowed), inside triplet beats into
 *   triplet values, at beat boundaries next to a triplet beat.
 */
object RhythmQuantizer {
    const val SHORT_TAP_SECONDS = 0.12
    const val SHORT_TAP_STEPS = 0.6
    const val TRIPLET_PENALTY = 1.0 / 192
    const val TRIPLET_RATIO = 0.75
    private val SIXTEENTH = Fraction.of(1, 16)
    private val QUARTER = Fraction.of(1, 4)
    private val TRIPLET = TupletRatio(3, 2)

    private class Window(val start: Fraction, val end: Fraction, val ternaryAllowed: Boolean) { var step: Fraction = SIXTEENTH; val triplet: Boolean get() = step.denominator % 3 == 0L }
    /** [down]/[up]: positions in whole notes from the range start; [local]: bar-local down after the bar assignment. */
    private class Tap(val down: Double, var up: Double, var bar: Int = 0, var local: Double = 0.0, var offset: Fraction = Fraction.ZERO)

    fun quantize(taps: List<TapStroke>, spans: List<RouteSpan>, sampleRate: Int, grid: RhythmGrid, compound: (Int) -> Boolean = { false }): RhythmResult {
        require(spans.isNotEmpty()) { "Kein Zielbereich" }
        val lengths = spans.map { it.length }
        val starts = ArrayList<Fraction>(); var acc = Fraction.ZERO
        for (l in lengths) { starts += acc; acc += l }
        val total = acc
        val totalD = total.toDouble()
        val base = grid.step ?: SIXTEENTH
        // Beat windows (AUTO): quarters, the remainder of the bar as its own window.
        val windows = lengths.mapIndexed { b, len ->
            val list = ArrayList<Window>(); var at = Fraction.ZERO
            while (at < len) {
                val end = if (at + QUARTER <= len) at + QUARTER else len
                list += Window(at, end, grid == RhythmGrid.AUTO && !compound(b) && (end + at * Fraction.of(-1)).let { it == QUARTER || it == Fraction.of(1, 8) })
                at = end
            }
            list
        }
        fun posOf(frame: Double): Double {
            val first = spans.first()
            if (frame < first.frames[0]) return (frame - first.frames[0]) / slope(first, 0)
            for ((i, s) in spans.withIndex()) if (frame < s.frames.last() || i == spans.lastIndex) {
                if (frame >= s.frames.last()) return totalD + (frame - s.frames.last()) / slope(s, s.frames.size - 2)
                return starts[i].toDouble() + s.offsetAt(frame)
            }
            return totalD
        }
        fun barOf(pos: Double): Int { var b = 0; while (b < spans.lastIndex && pos >= starts[b + 1].toDouble()) b++; return b }
        fun windowOf(b: Int, local: Fraction): Window = windows[b].firstOrNull { local >= it.start && local < it.end } ?: windows[b].last()
        fun stepOf(b: Int, local: Fraction): Fraction = if (grid == RhythmGrid.AUTO) windowOf(b, local).step else base

        // 1. Positions, range filter, finger bounces.
        var discarded = 0; var merged = 0
        val kept = ArrayList<Tap>()
        val half = base.toDouble() / 2
        for (t in taps.sortedBy { it.downFrame }) {
            if (t.downFrame.isNaN()) { discarded++; continue }
            val pd = posOf(t.downFrame)
            if (pd < -half || pd >= totalD) { discarded++; continue }
            val pu = if (t.upFrame.isNaN()) Double.NaN else posOf(maxOf(t.upFrame, t.downFrame))
            val last = kept.lastOrNull()
            if (last != null && pd - last.down < base.toDouble() / 4) { merged++; if (!pu.isNaN()) last.up = if (last.up.isNaN()) pu else maxOf(last.up, pu); continue }
            kept += Tap(pd, pu)
        }
        // 2. Bar and straight pre-assignment (a tap just before a barline belongs to the next bar's downbeat).
        for (t in kept) {
            var b = barOf(maxOf(0.0, t.down)); var local = t.down - starts[b].toDouble()
            if (snap(local, Fraction.ZERO, base, lengths[b]) == lengths[b] && b < spans.lastIndex) { local -= lengths[b].toDouble(); b++ }
            t.bar = b; t.local = local
        }
        // 3. AUTO: grid per beat window.
        var tripletBeats = 0
        if (grid == RhythmGrid.AUTO) for ((b, list) in windows.withIndex()) for (w in list) {
            if (!w.ternaryAllowed) continue
            val inside = kept.filter { it.bar == b && snap(it.local, Fraction.ZERO, SIXTEENTH, lengths[b]).let { s -> s >= w.start && s < w.end } }
            if (inside.isEmpty()) continue
            fun cost(step: Fraction) = inside.sumOf { abs(it.local - snap(it.local, w.start, step, w.end).toDouble()) }
            val straight = cost(SIXTEENTH)
            // Coarser first: on equal error 8th triplets win over 16th triplets (all 8th-triplet points are 16th-triplet points).
            val candidates = listOfNotNull(if (w.end + w.start * Fraction.of(-1) == QUARTER) Fraction.of(1, 12) else null, Fraction.of(1, 24))
            val best = candidates.minByOrNull { cost(it) }!!
            val c = cost(best)
            if (c + TRIPLET_PENALTY < straight && c < TRIPLET_RATIO * straight) { w.step = best; tripletBeats++ }
        }
        // 4. Snap onsets; same grid point = one stroke.
        val onsets = ArrayList<Tap>()
        for (t in kept) {
            var b = t.bar
            val w = if (grid == RhythmGrid.AUTO) windowOf(b, snap(t.local, Fraction.ZERO, SIXTEENTH, lengths[b])) else null
            var o = if (w != null) snap(t.local, w.start, w.step, w.end) else snap(t.local, Fraction.ZERO, base, lengths[b])
            if (o >= lengths[b]) { if (b == spans.lastIndex) { discarded++; continue }; b++; o = Fraction.ZERO }
            t.bar = b; t.offset = o
            val prev = onsets.lastOrNull()
            if (prev != null && prev.bar == b && prev.offset == o) { merged++; if (!t.up.isNaN()) prev.up = if (prev.up.isNaN()) t.up else maxOf(prev.up, t.up); continue }
            onsets += t
        }
        // 5. Note ends from the held time.
        fun global(b: Int, o: Fraction) = starts[b] + o
        val notes = ArrayList<Pair<Fraction, Fraction>>()   // global [start, end)
        for ((i, t) in onsets.withIndex()) {
            val start = global(t.bar, t.offset)
            val next = onsets.getOrNull(i + 1)?.let { global(it.bar, it.offset) } ?: total
            val step = stepOf(t.bar, t.offset)
            var end: Fraction
            if (t.up.isNaN()) end = next
            else {
                // Held time = release − touch-down (both calibrated), in frames at the local tempo.
                val heldFrames = (t.up - t.down) * framesPerWhole(spans[t.bar], t.offset.toDouble())
                val threshold = maxOf(SHORT_TAP_SECONDS * sampleRate, SHORT_TAP_STEPS * step.toDouble() * framesPerWhole(spans[t.bar], t.offset.toDouble()))
                end = if (heldFrames < threshold) start + step else {
                    val upPos = t.up.coerceIn(0.0, totalD)
                    val ub = barOf(minOf(upPos, totalD - 1e-12)); val ul = upPos - starts[ub].toDouble()
                    val uw = if (grid == RhythmGrid.AUTO) windowOf(ub, snap(ul, Fraction.ZERO, SIXTEENTH, lengths[ub])) else null
                    val snapped = global(ub, if (uw != null) snap(ul, uw.start, uw.step, uw.end) else snap(ul, Fraction.ZERO, base, lengths[ub]))
                    if (snapped < start + step) start + step else snapped
                }
            }
            if (end > next) end = next
            notes += start to end
        }
        // 6. Bars: notes and rests, split at barlines and next to triplet beats, written as values.
        val bars = spans.mapIndexed { b, span ->
            val len = lengths[b]; val s0 = starts[b]; val s1 = s0 + len
            val events = ArrayList<RhythmEvent>()
            fun emit(from: Fraction, to: Fraction, kind: RhythmEvent.Kind) {
                var at = from; var k = kind
                val cuts = windows[b].flatMap { listOf(it.start, it.end) }.filter { c -> windows[b].any { w -> w.triplet && (w.start == c || w.end == c) } }
                while (at < to) {
                    val cut = cuts.filter { it > at && it < to }.minOrNull() ?: to
                    val triplet = grid == RhythmGrid.AUTO && windowOf(b, at).triplet
                    val values = write(cut + at * Fraction.of(-1), triplet) ?: throw IllegalArgumentException("Dauer nicht darstellbar")
                    for (v in values) { events += RhythmEvent(at, v, k); at += v.length(); if (k == RhythmEvent.Kind.ONSET) k = RhythmEvent.Kind.CONTINUATION }
                }
            }
            var cursor = Fraction.ZERO
            for ((ns, ne) in notes) {
                if (ne <= s0 || ns >= s1) continue
                val a = (if (ns < s0) s0 else ns) + s0 * Fraction.of(-1)
                val z = (if (ne > s1) s1 else ne) + s0 * Fraction.of(-1)
                if (a > cursor) emit(cursor, a, RhythmEvent.Kind.REST)
                emit(a, z, if (ns < s0) RhythmEvent.Kind.CONTINUATION else RhythmEvent.Kind.ONSET)
                cursor = z
            }
            if (cursor < len) emit(cursor, len, RhythmEvent.Kind.REST)
            RhythmBar(span.measureId, len, events)
        }
        return RhythmResult(bars, onsets.size, discarded, merged, tripletBeats)
    }

    /** Nearest of origin + k·step (k ≥ 0, not past [end]) and [end] itself, for a position [x] in whole notes. */
    private fun snap(x: Double, origin: Fraction, step: Fraction, end: Fraction): Fraction {
        val k = (x - origin.toDouble()) / step.toDouble()
        val cands = listOf(floor(k), ceil(k)).map { origin + step * Fraction.of(maxOf(0L, it.toLong())) }.filter { it <= end } + end
        return cands.minByOrNull { abs(it.toDouble() - x) }!!
    }
    private fun slope(s: RouteSpan, seg: Int): Double = (s.frames[seg + 1] - s.frames[seg]) / (s.offsets[seg + 1] - s.offsets[seg])
    private fun framesPerWhole(s: RouteSpan, offset: Double): Double {
        for (i in 1 until s.offsets.size) if (offset < s.offsets[i] || i == s.offsets.lastIndex) return slope(s, i - 1)
        return slope(s, 0)
    }

    private val plainValues = listOf(1, 2, 4, 8, 16, 32, 64, 128).flatMap { d -> listOf(WrittenDuration(d, 1), WrittenDuration(d)) }.sortedByDescending { it.length() }
    /** Straight: one exact value if there is one (dots allowed), else longest first. Triplet beat: 3:2 values without dots. */
    fun write(length: Fraction, triplet: Boolean): List<WrittenDuration>? {
        if (length <= Fraction.ZERO) return emptyList()
        if (!triplet) return Durations.decompose(length)
        var written = length * Fraction.of(3, 2)
        val out = ArrayList<WrittenDuration>()
        while (written > Fraction.ZERO) {
            val v = plainValues.firstOrNull { it.dots == 0 && it.length() <= written } ?: return null
            out += v.copy(tuplets = listOf(TRIPLET)); written += v.length() * Fraction.of(-1)
            if (out.size > 32) return null
        }
        return out
    }
}

/**
 * Latency calibration („Kalibrieren“: 8 clicks, the user taps along): the median of tap − click in milliseconds after
 * dropping outliers — taps more than [MAX_ABS_MS] from their click (missed or extra), then taps further than
 * max(3 · 1.4826 · MAD, [MIN_SPREAD_MS]) from the median. Needs [MIN_TAPS] remaining, else null (repeat).
 */
object TapCalibration {
    const val CLICKS = 8
    const val MIN_TAPS = 4
    const val MAX_ABS_MS = 250.0
    const val MIN_SPREAD_MS = 20.0

    fun offsetMillis(deltasMs: List<Double>): Double? {
        val plausible = deltasMs.filter { it.isFinite() && abs(it) <= MAX_ABS_MS }
        if (plausible.size < MIN_TAPS) return null
        val m = median(plausible)
        val mad = median(plausible.map { abs(it - m) })
        val spread = maxOf(3 * 1.4826 * mad, MIN_SPREAD_MS)
        val inliers = plausible.filter { abs(it - m) <= spread }
        return if (inliers.size < MIN_TAPS) null else median(inliers)
    }

    /** Tap − nearest click in ms for each tap (plan frames at [sampleRate]); clicks further than [MAX_ABS_MS] give no delta. */
    fun deltasMillis(tapFrames: List<Double>, clickFrames: List<Long>, sampleRate: Int): List<Double> = tapFrames.mapNotNull { t ->
        val c = clickFrames.minByOrNull { abs(it - t) } ?: return@mapNotNull null
        ((t - c) * 1000.0 / sampleRate).takeIf { abs(it) <= MAX_ABS_MS }
    }

    private fun median(v: List<Double>): Double { val s = v.sorted(); val n = s.size; return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2 }
}

/**
 * Zyklus 27: appends one empty bar per id of [measureIds] at the end of [p] — signature, key and feel of the last bar (the
 * tempo in effect at the end goes on by itself), an empty bar in every staff of every track, like „Takt anhängen“. The
 * tapping copy and [EditorCommand.ApplyRhythm] both use it, so the bars that were played are the bars that are written.
 */
fun appendEmptyMeasures(p: Project, measureIds: List<EntityId>, ids: IdSource): Project {
    if (measureIds.isEmpty()) return p
    require(p.measures.size + measureIds.size <= 10_000) { "Zu viele Takte" }
    val last = p.measures.lastOrNull()
    val added = measureIds.map { Measure(it, last?.signature ?: TimeSignature(), last?.key ?: KeySignature(), feel = last?.feel ?: RhythmicFeel.STRAIGHT) }
    val factory = ProjectFactory(ids)
    return p.copy(measures = p.measures + added, tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars + added.map { factory.bar(it.id) }) }) })
}

/**
 * Zyklus 27: the result without its trailing bars that hold nothing but rests (no attack, no held note), keeping at least
 * the first [keep] bars (the existing target bars of a fixed range are replaced as a whole; an open recording keeps its
 * start bar). Bars after the last tapped content are not written: buffer bars are discarded, existing bars stay untouched.
 */
fun RhythmResult.trimmed(keep: Int): RhythmResult {
    val lastContent = bars.indexOfLast { b -> b.events.any { it.kind != RhythmEvent.Kind.REST } }
    val count = maxOf(lastContent + 1, keep.coerceIn(0, bars.size))
    return if (count == bars.size) this else copy(bars = bars.take(count))
}

/**
 * Zyklus 27 „Offen (Takte anhängen)“: the recording runs over the bars that are played in written order from the start
 * span on — [spans] from the start, cut before the first repeat or jump (a span that is not the next written bar).
 */
fun linearRun(spans: List<RouteSpan>): List<RouteSpan> {
    if (spans.isEmpty()) return spans
    var n = 1
    while (n < spans.size && spans[n].measureIndex == spans[n - 1].measureIndex + 1) n++
    return spans.subList(0, n)
}

/**
 * Zyklus 27, Auto-Stopp of an open recording: after the first tap, [EMPTY_BARS] whole bars without a touch (no down, no
 * release, no finger held) end it. Reason: the hands are on the tap surface or the instrument, so a hands-free end is
 * welcome; one empty bar would cut phrases with a whole-bar rest, more than two only wait longer. Switchable off for
 * music with longer rests (then only „Stopp“ ends it).
 */
object RhythmAutoStop {
    const val EMPTY_BARS = 2
    /** Plan frame at which the recording stops by itself, or null (no tap yet, a finger is down, or not enough bars yet). */
    fun stopFrame(spans: List<RouteSpan>, strokes: List<TapStroke>): Long? {
        val first = spans.firstOrNull() ?: return null
        // A sixteenth of a bar of tolerance: a slightly early downbeat counts, and a touch just before a barline belongs
        // to the next bar (it snaps onto its downbeat), so the empty bars are counted after that one.
        fun tolerance(s: RouteSpan) = (s.endFrame - s.startFrame) / 16.0
        val inside = strokes.filter { !it.downFrame.isNaN() && it.downFrame >= first.startFrame - tolerance(first) }
        if (inside.isEmpty() || inside.any { it.upFrame.isNaN() }) return null
        val lastTouch = inside.maxOf { maxOf(it.downFrame, it.upFrame) }
        val bar = spans.indexOfFirst { lastTouch + tolerance(it) < it.endFrame }
        if (bar < 0) return null
        return spans.getOrNull(bar + EMPTY_BARS)?.endFrame
    }
}

/** Notes that applying a rhythm to [voice] of [trackId] in [measures] would overwrite (confirmation „Überschreibt N Noten“). */
fun rhythmOverwrites(p: Project, trackId: EntityId, voice: Int, measures: Collection<EntityId>): Int = RhythmEdits.notesIn(p, trackId, voice, measures)

/** [EditorCommand.ApplyRhythm]: replaces the voice content of the target bars. */
internal object RhythmEdits {
    /** Notes in [voice] of [trackId] (first staff) in [measures]: what applying a rhythm there overwrites. */
    fun notesIn(p: Project, trackId: EntityId, voice: Int, measures: Collection<EntityId>): Int =
        p.tracks.firstOrNull { it.id == trackId }?.staves?.firstOrNull()?.bars?.filter { it.measureId in measures }
            ?.sumOf { b -> b.voices.filter { it.index == voice }.sumOf { v -> v.beats.sumOf { it.notes.size } } } ?: 0

    fun apply(original: Project, c: EditorCommand.ApplyRhythm, ids: IdSource): Project {
        val p = if (c.appended.isEmpty()) original else {
            // Zyklus 27: new bars first (same undo step); only as many as the rhythm needs.
            require(c.appended.distinct().size == c.appended.size && c.appended.none { id -> original.measures.any { it.id == id } }) { "Neue Takte ungültig" }
            require(c.bars.size >= c.appended.size && c.bars.takeLast(c.appended.size).map { it.measureId } == c.appended) { "Neue Takte müssen am Ende des Zielbereichs liegen" }
            val existing = c.bars.dropLast(c.appended.size)
            require(existing.isEmpty() || existing.last().measureId == original.measures.last().id) { "Neue Takte schließen nicht an das Stückende an" }
            require(c.bars.last().events.any { it.kind != RhythmEvent.Kind.REST }) { "Leere neue Takte werden nicht angelegt" }
            appendEmptyMeasures(original, c.appended, ids)
        }
        val track = p.tracks.firstOrNull { it.id == c.trackId } ?: throw IllegalArgumentException("Spur fehlt")
        require(c.voice in 0..3) { "Stimme existiert nicht" }
        require(c.bars.isNotEmpty() && c.bars.map { it.measureId }.distinct().size == c.bars.size) { "Zieltakte ungültig" }
        val fretted = track.instrument.tuning.isNotEmpty()
        val drums = track.instrument.kind == InstrumentKind.PERCUSSION
        if (c.mode == RhythmMode.OPEN_STRING) {
            require(fretted || drums) { "Leersaite nur für Saiten- und Schlagzeugspuren" }
            if (fretted) require(c.string in track.instrument.tuning.indices) { "Saite existiert nicht" } else require(c.drumNote in 0..127) { "Kit-Element ungültig" }
        }
        val index = p.measures.withIndex().associate { it.value.id to it.index }
        for (bar in c.bars) {
            val m = p.measures.firstOrNull { it.id == bar.measureId } ?: throw IllegalArgumentException("Takt fehlt")
            require(bar.length == NoteEdits.capacity(m)) { "Taktart hat sich seit der Aufnahme geändert" }
            var at = Fraction.ZERO
            for (e in bar.events) { require(e.offset == at) { "Rhythmus lückenhaft" }; at += e.duration.length() }
            require(at == bar.length) { "Rhythmus füllt den Takt nicht genau" }
        }
        val beatsByBar = HashMap<EntityId, List<Beat>>()
        var last: Note? = null          // last note written (for ties), with its bar index
        var lastBar = -2
        val ties = HashMap<EntityId, EntityId>()   // origin note -> continuation note
        for (bar in c.bars) {
            val bi = index.getValue(bar.measureId)
            val beats = ArrayList<Beat>()
            for (e in bar.events) {
                val sounding = c.mode == RhythmMode.OPEN_STRING && e.kind != RhythmEvent.Kind.REST &&
                    (e.kind == RhythmEvent.Kind.ONSET || fretted)
                if (!sounding) { beats += Beat(ids.next(), e.offset, e.duration, explicitRest = true); if (e.kind == RhythmEvent.Kind.REST) last = null; continue }
                val note = if (fretted) Note(ids.next(), Pitch(track.instrument.pitch(c.string, 0)), c.string, 0) else Note(ids.next(), Pitch(c.drumNote))
                val previous = last
                // A continuation is tied to the note before it when that note ends right here (same bar or the bar before).
                if (e.kind == RhythmEvent.Kind.CONTINUATION && previous != null && (lastBar == bi || lastBar == bi - 1)) ties[previous.id] = note.id
                beats += Beat(ids.next(), e.offset, e.duration, listOf(note))
                last = note; lastBar = bi
            }
            beatsByBar[bar.measureId] = beats
        }
        fun linked(b: Beat) = if (ties.isEmpty()) b else b.copy(notes = b.notes.map { n -> ties[n.id]?.let { t -> n.copy(links = listOf(NoteLink(LinkKind.TIE, t))) } ?: n })
        return p.copy(tracks = p.tracks.map { t ->
            if (t.id != c.trackId) t else t.copy(staves = t.staves.mapIndexed { si, st ->
                if (si != 0) st else st.copy(bars = st.bars.map { bar ->
                    val beats = beatsByBar[bar.measureId] ?: return@map bar
                    bar.copy(voices = bar.voices.map { v -> if (v.index != c.voice) v else v.copy(beats = beats.map(::linked)) })
                })
            })
        })
    }
}
