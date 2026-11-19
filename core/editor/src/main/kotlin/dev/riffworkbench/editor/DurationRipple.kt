package dev.riffworkbench.editor

import dev.riffworkbench.model.*

private operator fun Fraction.minus(other: Fraction): Fraction = this + other * Fraction.of(-1)
private fun maxOf(a: Fraction, b: Fraction) = if (a >= b) a else b

/**
 * Zyklus 29, Nutzerentscheidung „Notenwert verlängern verschiebt“ (ersetzt die Ablehnung aus Zyklus 25): a longer value
 * first consumes the rests (or empty room) right behind the beat; if they do not suffice, the following beats of the voice
 * move later by the rest of the difference. What then reaches past the barline moves to the start of the next bar, whose
 * content moves on by the same rule (rests right behind first), bar by bar; at the end of the piece a bar is appended (empty
 * in every track, signature of the last bar). Rests at the end of a bar that would only be pushed over the barline are
 * dropped instead (they are free room). A beat that straddles a barline is split into writable values: notes continue
 * with ties (outgoing links move to the last part); on drum tracks the continuation is a rest — drum notes are not tied
 * (ToggleTie refuses them) and a second, tied hit would sound as a new attack in the editor's eyes. Pure and one command,
 * so it is one undo step. Other voices and tracks are untouched (apart from the appended bar).
 */
internal object DurationRipple {
    private class Splitter(private val ids: IdSource, private val drums: Boolean) {
        /** Continuation rests of drum hits and the lengthened beat itself: never dropped as "free room". */
        val material = HashSet<EntityId>()

        /** [beat] cut at [at] (absolute offset inside it): parts before and after, writable values each. */
        fun split(beat: Beat, at: Fraction): Pair<List<Beat>, List<Beat>> {
            val end = beat.offset + beat.duration.length()
            val first = Durations.decompose(at - beat.offset, beat.duration.tuplets) ?: throw IllegalArgumentException("Notenwert lässt sich am Taktstrich nicht teilen")
            val second = Durations.decompose(end - at, beat.duration.tuplets) ?: throw IllegalArgumentException("Notenwert lässt sich am Taktstrich nicht teilen")
            val pieces = pieces(beat, first + second)
            return pieces.take(first.size) to pieces.drop(first.size)
        }

        /** [beat] as consecutive beats with [durations]; the first keeps id, notes and beat marks, notes continue tied. */
        fun pieces(beat: Beat, durations: List<WrittenDuration>): List<Beat> {
            if (durations.size == 1) return listOf(beat.copy(duration = durations[0]))
            val tied = beat.notes.isNotEmpty() && !drums
            val noteIds = durations.indices.map { i -> if (i == 0) beat.notes.map { it.id } else if (tied) beat.notes.map { ids.next() } else emptyList() }
            fun links(i: Int, k: Int, original: Note): List<NoteLink> = if (i == durations.lastIndex) original.links else listOf(NoteLink(LinkKind.TIE, noteIds[i + 1][k]))
            var at = beat.offset
            return durations.mapIndexed { i, d ->
                val piece = when {
                    i == 0 -> beat.copy(duration = d, notes = if (tied) beat.notes.mapIndexed { k, n -> n.copy(links = links(0, k, n)) } else beat.notes)
                    tied -> Beat(ids.next(), at, d, beat.notes.mapIndexed { k, n -> Note(noteIds[i][k], n.pitch, n.string, n.fret, n.velocity, links = links(i, k, n)) }, tupletGroupIds = beat.tupletGroupIds)
                    else -> Beat(ids.next(), at, d, explicitRest = true, tupletGroupIds = beat.tupletGroupIds).also { if (isMaterial(beat)) material += it.id }
                }
                at += d.length()
                piece
            }
        }

        fun rests(from: Fraction, to: Fraction, tuplets: List<TupletRatio>): List<Beat> {
            if (to <= from) return emptyList()
            val parts = Durations.decompose(to - from, tuplets) ?: throw IllegalArgumentException("Die frei werdende Dauer lässt sich nicht als Pause schreiben")
            var at = from
            return parts.map { d -> Beat(ids.next(), at, d, explicitRest = true).also { at += d.length() } }
        }
        fun isMaterial(b: Beat) = b.notes.isNotEmpty() || b.id in material
    }

    fun lengthen(p: Project, s: Selection, beat: Beat, next: WrittenDuration, ids: IdSource): Project {
        val track = NoteEdits.track(p, s)
        val staff = track.staves.firstOrNull() ?: throw IllegalArgumentException("Spur ohne Notensystem")
        var project = p
        val drums = track.instrument.kind == InstrumentKind.PERCUSSION
        val split = Splitter(ids, drums)
        val k0 = p.measures.indexOfFirst { it.id == s.measureId }.also { require(it >= 0) { "Takt fehlt" } }
        fun beatsOf(proj: Project, measureId: EntityId): List<Beat> = proj.tracks.first { it.id == track.id }.staves.first().bars.first { it.measureId == measureId }
            .voices.firstOrNull { it.index == s.voice }?.beats.orEmpty()
        val written = LinkedHashMap<EntityId, List<Beat>>()

        // Bar k0: the beat grows from its old end by delta.
        val oldEnd = beat.offset + beat.duration.length()
        val delta = next.length() - beat.duration.length()
        val grown = beat.copy(duration = next)
        split.material += grown.id
        val beats0 = beatsOf(p, s.measureId)
        var (inBar, carried) = settle(beats0.filter { it.offset < oldEnd && it.id != beat.id } + grown + makeRoom(beats0.filter { it.offset >= oldEnd && it.id != beat.id }, oldEnd, delta, NoteEdits.capacity(p.measures[k0]), split), NoteEdits.capacity(p.measures[k0]), split)
        written[s.measureId] = inBar
        var k = k0
        var guard = 0
        while (carried.isNotEmpty()) {
            require(guard++ < 10_000) { "Zu viele Takte" }
            k++
            if (k >= project.measures.size) project = StructureEdits.insert(project, project.measures.size, 1, null, ids)
            val m = project.measures[k]
            val cap = NoteEdits.capacity(m)
            val existing = beatsOf(project, m.id)
            // Empty room inside the moved material (a voice that was not full) arrives as rests: a full bar stays full
            // (Zyklus 29 model test, seeds 29126/29197: gaps were carried into the next bar, or it then began late).
            carried = withRestsInGaps(carried, split)
            val length = carried.maxOf { it.offset + it.duration.length() }
            val result = settle(carried + makeRoom(existing, Fraction.ZERO, length, cap, split), cap, split)
            var bar = result.first
            // A bar that was empty receives only the carried content: it is filled with rests to the barline.
            if (existing.isEmpty()) bar = bar + split.rests(bar.maxOf { it.offset + it.duration.length() }, cap, emptyList())
            written[m.id] = bar
            carried = result.second
        }
        return project.copy(tracks = project.tracks.map { t ->
            if (t.id != track.id) t else t.copy(staves = t.staves.mapIndexed { i, st ->
                if (i != 0) st else st.copy(bars = st.bars.map { bar ->
                    val beats = written[bar.measureId] ?: return@map bar
                    bar.copy(voices = bar.voices.map { v -> if (v.index == s.voice) v.copy(beats = beats.sortedBy { it.offset }) else v })
                })
            })
        })
    }

    /**
     * [behind] (all at or after [at]) after making [amount] of room at [at]: rests and empty room right behind are consumed
     * (a partly covered rest keeps its tail); if they do not suffice, everything from the first beat that is not free
     * room on moves later by the remainder (it may then reach past [cap], see [settle]).
     */
    private fun makeRoom(behind: List<Beat>, at: Fraction, amount: Fraction, cap: Fraction, split: Splitter): List<Beat> {
        val sorted = behind.sortedBy { it.offset }
        val wall = sorted.firstOrNull { split.isMaterial(it) }?.offset
        val free = (wall ?: maxOf(cap, at)) - at
        val needEnd = at + amount
        if (amount <= free) {
            val out = ArrayList<Beat>()
            for (b in sorted) {
                val end = b.offset + b.duration.length()
                when {
                    split.isMaterial(b) || b.offset >= needEnd -> out += b
                    end <= needEnd -> {}
                    else -> out += split.rests(needEnd, end, b.duration.tuplets)
                }
            }
            return out
        }
        val shift = amount - free
        return sorted.filter { wall != null && it.offset >= wall }.map { it.copy(offset = it.offset + shift) }
    }

    /** [beats] (offsets from the downbeat) with every gap from 0 on filled by rests in the next beat's tuplet family. */
    private fun withRestsInGaps(beats: List<Beat>, split: Splitter): List<Beat> {
        val out = ArrayList<Beat>()
        var at = Fraction.ZERO
        for (b in beats.sortedBy { it.offset }) {
            if (b.offset > at) out += split.rests(at, b.offset, b.duration.tuplets)
            out += b
            at = maxOf(at, b.offset + b.duration.length())
        }
        return out
    }

    /** [beats] fitted into a bar of [cap]: trailing free room past the barline is dropped, the rest moves to the next bar. */
    private fun settle(beats: List<Beat>, cap: Fraction, split: Splitter): Pair<List<Beat>, List<Beat>> {
        val sorted = beats.sortedBy { it.offset }
        val materialEnd = sorted.filter { split.isMaterial(it) }.maxOfOrNull { it.offset + it.duration.length() } ?: Fraction.ZERO
        val inBar = ArrayList<Beat>()
        val carried = ArrayList<Beat>()
        for (b in sorted) {
            val end = b.offset + b.duration.length()
            val trailingRest = !split.isMaterial(b) && b.offset >= materialEnd
            when {
                end <= cap -> inBar += b
                trailingRest -> if (b.offset < cap) inBar += split.rests(b.offset, cap, b.duration.tuplets)
                b.offset >= cap -> carried += b.copy(offset = b.offset - cap)
                else -> {
                    val (first, second) = split.split(b, cap)
                    inBar += first
                    carried += second.map { it.copy(offset = it.offset - cap) }
                }
            }
        }
        return inBar to carried
    }
}
