package dev.riffworkbench.editor

import dev.riffworkbench.model.*

/** How a retune resolves a note that cannot keep its place: an octave shift (automatic) or removal (explicit opt-in). */
enum class RetuneResolution { OCTAVE_UP, OCTAVE_DOWN, DROP }

/**
 * One note a retune cannot keep as it is. [string] is the note's current (0-based) string, -1 when it had none, [midi] its
 * current pitch, [barIndex] the 0-based bar. [reason] is a short German explanation for the conflict panel.
 */
data class RetuneProblem(val noteId: EntityId, val barIndex: Int, val string: Int, val midi: Int, val reason: String, val resolution: RetuneResolution)

/**
 * Pure dry run and placement planning shared by [TrackEdits.setInstrument] and the tuning editor.
 *
 * Rules: KEEP_SOUNDING_PITCH keeps the pitch; a note that fits no free string (typically below the new lowest open string)
 * is shifted an octave up when that fits, otherwise down. KEEP_FINGER_POSITION keeps string and fret and re-derives the pitch;
 * a note on a string that vanished (strings are top-aligned) falls back to pitch placement and then to the octave rule.
 * Ties are never split silently: all notes of a tie group get the same octave shift, or the whole group is a DROP problem.
 * Notes that still cannot be placed are DROP problems; the command removes them only with an explicit allowDrop.
 */
object Retuning {
    internal class Plan(val staves: List<Staff>, val problems: List<RetuneProblem>)

    fun problems(p: Project, trackId: EntityId, instrument: InstrumentDefinition, policy: RetuningPolicy): List<RetuneProblem> =
        plan(p, trackId, instrument, policy).problems

    /** The command failure for unresolved drops: 'Umstimmen nicht möglich: Ton A1 (Takt 1, Saite 1) nicht spielbar; …'. */
    fun failureMessage(drops: List<RetuneProblem>): String {
        val entries = drops.map { d -> "Ton ${MusicNames.name(d.midi)} (Takt ${d.barIndex + 1}${if (d.string >= 0) ", Saite ${d.string + 1}" else ""})" +
            if (d.reason.startsWith("Haltebogen")) " im Haltebogen nicht spielbar" else " nicht spielbar" }.distinct()
        return "Umstimmen nicht möglich: ${entries.take(3).joinToString("; ")}" + if (entries.size > 3) " …" else ""
    }

    /** Notice after a successful retune, e.g. '3 Noten um eine Oktave versetzt (Takt 1, 2)'; null when nothing was resolved. */
    fun summary(problems: List<RetuneProblem>): String? {
        fun bars(xs: List<RetuneProblem>) = xs.map { it.barIndex + 1 }.distinct().sorted().let { b -> if (b.size <= 6) b.joinToString(", ") else b.take(6).joinToString(", ") + " …" }
        val octaved = problems.filter { it.resolution != RetuneResolution.DROP }
        val dropped = problems.filter { it.resolution == RetuneResolution.DROP }
        val parts = mutableListOf<String>()
        if (octaved.isNotEmpty()) parts += (if (octaved.size == 1) "1 Note" else "${octaved.size} Noten") + " um eine Oktave versetzt (Takt ${bars(octaved)})"
        if (dropped.isNotEmpty()) parts += (if (dropped.size == 1) "1 Note" else "${dropped.size} Noten") + " entfernt (Takt ${bars(dropped)})"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private class Placement(val string: Int?, val fret: Int?, val midi: Int, val shift: Int)

    internal fun plan(p: Project, id: EntityId, instrument: InstrumentDefinition, policy: RetuningPolicy): Plan {
        val track = p.tracks.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Spur fehlt")
        val old = track.instrument
        val oldFretted = old.tuning.isNotEmpty()
        val newFretted = instrument.tuning.isNotEmpty()
        val oldPercussion = old.kind == InstrumentKind.PERCUSSION; val newPercussion = instrument.kind == InstrumentKind.PERCUSSION
        // An empty track may change between drums and a pitched instrument; existing notes cannot be converted.
        val hasNotes = track.staves.any { st -> st.bars.any { bar -> bar.voices.any { v -> v.beats.any { b -> b.notes.isNotEmpty() } } } }
        require(oldPercussion == newPercussion || !hasNotes) { "Umstimmen nicht möglich: Schlagzeug ↔ Tonhöheninstrument" }
        if (newPercussion) return Plan(track.staves, emptyList())
        if (!newFretted) return Plan(track.staves.map { st -> st.copy(bars = st.bars.map { bar -> bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { b -> b.copy(notes = b.notes.map { n -> n.copy(string = null, fret = null) }) }) }) }) }, emptyList())

        val shift = instrument.tuning.size - old.tuning.size
        val measureIndex = p.measures.withIndex().associate { (i, m) -> m.id to i }
        // Tie groups (union-find) and note positions within this track.
        val parent = HashMap<EntityId, EntityId>()
        fun root(x: EntityId): EntityId { var r = x; while (true) { val q = parent[r] ?: return r; if (q == r) return r; r = q } }
        fun union(a: EntityId, b: EntityId) { val ra = root(a); val rb = root(b); if (ra != rb) parent[ra] = rb }
        val allNotes = HashMap<EntityId, Note>()
        val barOf = HashMap<EntityId, Int>()
        for (st in track.staves) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes) { allNotes[n.id] = n; barOf[n.id] = measureIndex[bar.measureId] ?: 0; parent.putIfAbsent(n.id, n.id) }
        for (n in allNotes.values) for (l in n.links) if (l.kind == LinkKind.TIE && l.target in allNotes) union(n.id, l.target)
        val groups = allNotes.keys.groupBy { root(it) }.values.filter { it.size > 1 }

        val pinned = HashMap<EntityId, Int>()      // octave shift fixed by a tie partner
        val dropped = HashSet<EntityId>()
        val reasons = HashMap<EntityId, String>()

        fun tryPlace(n: Note, preferred: Int, occupied: Set<Int>, shifts: List<Int>): Placement? {
            for (s in shifts) {
                val midi = n.pitch.midiNumber + s
                if (midi !in 0..127) continue
                NoteEdits.place(instrument, midi, preferred, occupied)?.let { return Placement(it.first, it.second, midi, s) }
            }
            return null
        }
        val lowestOpen = instrument.tuning.indices.minOf { instrument.pitch(it, 0) }
        fun unplayable(n: Note) = if (n.pitch.midiNumber < lowestOpen) "Ton ${MusicNames.name(n.pitch.midiNumber)} liegt unter der tiefsten Saite" else "Ton ${MusicNames.name(n.pitch.midiNumber)} nicht spielbar"
        /**
         * [octaves] = false: only the note's own pitch (or its pinned shift) on a free string; notes that fit as they are
         * take their strings first so a crowded-out note, not a fitting one, is the one shifted.
         */
        fun placeNote(n: Note, occupied: Set<Int>, octaves: Boolean): Placement? {
            val ns = n.string
            val pin = pinned[n.id]
            val candidates = if (pin != null) listOf(pin) else if (octaves) listOf(0, 12, -12) else listOf(0)
            if (!oldFretted || ns == null) {
                reasons.putIfAbsent(n.id, unplayable(n))
                return tryPlace(n, instrument.tuning.lastIndex, occupied, candidates)
            }
            if (policy == RetuningPolicy.KEEP_FINGER_POSITION) {
                val s = ns + shift; val f = n.fret ?: 0
                if (s in instrument.tuning.indices && f <= instrument.maxFret && s !in occupied && pin == null) return Placement(s, f, instrument.pitch(s, f), 0)
                reasons.putIfAbsent(n.id, if (s !in instrument.tuning.indices) "Saite ${ns + 1} existiert nicht mehr" else unplayable(n))
            } else reasons.putIfAbsent(n.id, unplayable(n))
            return tryPlace(n, (ns + shift).coerceIn(instrument.tuning.indices), occupied, candidates)
        }

        var placements = HashMap<EntityId, Placement?>()
        // Each round either pins or drops at least one tie group, so the loop settles within groups.size + 1 rounds.
        for (round in 0..groups.size + 1) {
            placements = HashMap()
            for (st in track.staves) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) {
                val occupied = HashSet<Int>()
                val deferred = ArrayList<Note>()
                for (n in b.notes) if (n.id !in dropped) { val pl = placeNote(n, occupied, octaves = false); if (pl == null) deferred += n else { placements[n.id] = pl; pl.string?.let { occupied += it } } }
                for (n in deferred) { val pl = placeNote(n, occupied, octaves = true); placements[n.id] = pl; pl?.string?.let { occupied += it } }
            }
            var changed = false
            for (g in groups) {
                val live = g.filter { it !in dropped }
                if (live.isEmpty()) continue
                if (live.any { placements[it] == null }) { dropped += live; live.forEach { reasons[it] = "Haltebogen lässt sich nicht einheitlich versetzen" }; changed = true; continue }
                val shifts = live.map { placements.getValue(it)!!.shift }.distinct()
                if (shifts.size > 1) { val s = shifts.first { it != 0 }; live.forEach { pinned[it] = s }; changed = true }
            }
            if (!changed) break
        }
        // Stable: every remaining unplaceable note is a DROP; ties are uniform or dropped as a group (see loop).
        for ((nid, pl) in placements) if (pl == null) dropped += nid
        // A tie whose ends end up with different pitches (KEEP_FINGER_POSITION on partly vanished strings) would be rejected by
        // the validator anyway; say it clearly instead of letting the generic tie error surface.
        for (g in groups) {
            val live = g.filter { it !in dropped }
            if (live.map { placements[it]?.midi }.distinct().size > 1) throw IllegalArgumentException("Umstimmen nicht möglich: Haltebogen in Takt ${barOf[live.first()]!! + 1} lässt sich nicht einheitlich umstimmen – zuerst den Haltebogen lösen")
        }

        val problems = ArrayList<RetuneProblem>()
        val staves = track.staves.map { st -> st.copy(bars = st.bars.map { bar -> bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { b ->
            val notes = b.notes.mapNotNull { n ->
                if (n.id in dropped) { problems += RetuneProblem(n.id, barOf[n.id] ?: 0, n.string ?: -1, n.pitch.midiNumber, reasons[n.id] ?: "Ton ${MusicNames.name(n.pitch.midiNumber)} nicht spielbar", RetuneResolution.DROP); return@mapNotNull null }
                val pl = placements.getValue(n.id)!!
                if (pl.shift != 0) problems += RetuneProblem(n.id, barOf[n.id] ?: 0, n.string ?: -1, n.pitch.midiNumber, reasons[n.id] ?: "Ton ${MusicNames.name(n.pitch.midiNumber)} nicht spielbar", if (pl.shift > 0) RetuneResolution.OCTAVE_UP else RetuneResolution.OCTAVE_DOWN)
                if (pl.midi == n.pitch.midiNumber) n.copy(string = pl.string, fret = pl.fret) else n.copy(string = pl.string, fret = pl.fret, pitch = n.pitch.copy(midiNumber = pl.midi, spelling = null))
            }
            if (notes.size == b.notes.size) b.copy(notes = notes) else b.copy(notes = notes, explicitRest = notes.isEmpty() || b.explicitRest)
        }) }) }) }
        problems.sortWith(compareBy({ it.barIndex }, { it.string }))
        return Plan(staves, problems)
    }
}
