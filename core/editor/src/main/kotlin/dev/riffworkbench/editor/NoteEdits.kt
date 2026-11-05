package dev.riffworkbench.editor

import dev.riffworkbench.model.*

/** Stable "kind" of an articulation: one per note, except marks, which are keyed by their mark. */
object Articulations {
    fun slot(a: Articulation): String = when (a) {
        is Articulation.Mark -> "mark:${a.kind}"
        is Articulation.Bend -> "bend"; is Articulation.Whammy -> "whammy"; is Articulation.Slide -> "slide"
        is Articulation.Harmonic -> "harmonic"; is Articulation.Vibrato -> "vibrato"; is Articulation.Trill -> "trill"
        is Articulation.TremoloPicking -> "tremolo"; is Articulation.Grace -> "grace"; is Articulation.Dynamic -> "dynamic"
        is Articulation.Pickstroke -> "pickstroke"; is Articulation.Percussion -> "percussion"
    }
    /** Marks that cannot coexist on one note. */
    private val exclusive = listOf(setOf(NoteMark.HAMMER_ON, NoteMark.PULL_OFF), setOf(NoteMark.ACCENT, NoteMark.HEAVY_ACCENT), setOf(NoteMark.STACCATO, NoteMark.LET_RING), setOf(NoteMark.GHOST, NoteMark.DEAD))
    fun conflicts(a: Articulation): Set<String> = if (a is Articulation.Mark) exclusive.filter { a.kind in it }.flatten().map { "mark:$it" }.toSet() - slot(a) else emptySet()

    // Common presets used by the quick bar and the bend editor.
    fun fullBend() = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 3), 200), PitchCurvePoint(Fraction.ONE, 200)))
    fun halfBend() = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 3), 100), PitchCurvePoint(Fraction.ONE, 100)))
    fun bendRelease(cents: Int = 200) = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 4), cents), PitchCurvePoint(Fraction.of(1, 2), cents), PitchCurvePoint(Fraction.of(3, 4), 0), PitchCurvePoint(Fraction.ONE, 0)), BendKind.BEND)
    fun prebendRelease(cents: Int = 200) = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, cents), PitchCurvePoint(Fraction.of(1, 3), cents), PitchCurvePoint(Fraction.of(2, 3), 0), PitchCurvePoint(Fraction.ONE, 0)), BendKind.PREBEND_RELEASE)
    fun prebend(cents: Int = 200) = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, cents), PitchCurvePoint(Fraction.ONE, cents)), BendKind.PREBEND)
    fun quarterBend() = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 3), 50), PitchCurvePoint(Fraction.ONE, 50)))
}

/** Note- and beat-level transformations. Pure: (project, selection) -> project; violations throw IllegalArgumentException. */
internal object NoteEdits {
    fun track(p: Project, s: Selection): Track = p.tracks.firstOrNull { it.id == s.trackId } ?: throw IllegalArgumentException("Spur fehlt")
    private fun measure(p: Project, id: EntityId): Measure = p.measures.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Takt fehlt")
    fun capacity(m: Measure): Fraction = m.pickupLength ?: m.signature.length()
    private fun fretted(t: Track) = t.instrument.tuning.isNotEmpty()

    /** Rewrites the selected voice (first staff) of the selected bar. */
    fun mapVoice(p: Project, s: Selection, transform: (Voice, Track, Measure) -> Voice): Project {
        val m = measure(p, s.measureId)
        return p.copy(tracks = p.tracks.map { t ->
            if (t.id != s.trackId) t else t.copy(staves = t.staves.mapIndexed { i, staff ->
                if (i != 0) staff else staff.copy(bars = staff.bars.map { bar ->
                    if (bar.measureId != s.measureId) bar else bar.copy(voices = bar.voices.map { v -> if (v.index == s.voice) transform(v, t, m) else v })
                })
            })
        })
    }
    fun selectedVoice(p: Project, s: Selection): Voice? = track(p, s).staves.firstOrNull()?.bars?.firstOrNull { it.measureId == s.measureId }?.voices?.firstOrNull { it.index == s.voice }
    fun selectedBeat(p: Project, s: Selection): Beat? = selectedVoice(p, s)?.beats?.firstOrNull { it.offset == s.offset }
    private fun targets(t: Track, beat: Beat, s: Selection): (Note) -> Boolean = if (fretted(t)) { n -> n.string == s.string } else { _ -> true }

    fun updateSelectedBeat(p: Project, s: Selection, transform: (Beat) -> Beat): Project {
        requireNotNull(selectedBeat(p, s)) { "Zuerst einen Beat mit Noten wählen" }
        return mapVoice(p, s) { v, _, _ -> v.copy(beats = v.beats.map { if (it.offset == s.offset) transform(it) else it }) }
    }

    private fun updateSelectedNotes(p: Project, s: Selection, transform: (Note, Track) -> Note): Project {
        val beat = selectedBeat(p, s)
        val t = track(p, s)
        require(beat != null && beat.notes.any(targets(t, beat, s))) { "Zuerst eine Note wählen" }
        return mapVoice(p, s) { v, tr, _ -> v.copy(beats = v.beats.map { b -> if (b.offset != s.offset) b else b.copy(notes = b.notes.map { n -> if (targets(tr, b, s)(n)) transform(n, tr) else n }) }) }
    }

    fun applyArticulation(p: Project, s: Selection, a: Articulation, toggle: Boolean): Project = updateSelectedNotes(p, s) { note, _ ->
        val slot = Articulations.slot(a)
        val existing = note.articulations.firstOrNull { Articulations.slot(it) == slot }
        val removed = note.articulations.filterNot { Articulations.slot(it) == slot || Articulations.slot(it) in Articulations.conflicts(a) }
        note.copy(articulations = if (toggle && existing == a) removed else removed + a)
    }

    fun clearArticulation(p: Project, s: Selection, slot: String): Project = updateSelectedNotes(p, s) { note, _ -> note.copy(articulations = note.articulations.filterNot { Articulations.slot(it) == slot }) }

    fun setVelocity(p: Project, s: Selection, velocity: Int): Project {
        require(velocity in 1..127) { "Velocity muss zwischen 1 und 127 liegen" }
        return updateSelectedNotes(p, s) { n, _ -> n.copy(velocity = velocity) }
    }

    fun moveToString(p: Project, s: Selection, target: Int): Project {
        val t = track(p, s)
        require(fretted(t)) { "Saitenwechsel nur bei Saiteninstrumenten" }
        require(target in t.instrument.tuning.indices) { "Saite existiert nicht" }
        val beat = selectedBeat(p, s) ?: throw IllegalArgumentException("Zuerst eine Note wählen")
        val note = beat.notes.firstOrNull { it.string == s.string } ?: throw IllegalArgumentException("Zuerst eine Note wählen")
        if (target == s.string) return p
        require(beat.notes.none { it.string == target }) { "Zielsaite ist in diesem Beat belegt" }
        val fret = fretFor(t.instrument, target, note.pitch.midiNumber) ?: throw IllegalArgumentException("Ton ist auf dieser Saite nicht spielbar")
        return updateSelectedNotes(p, s) { n, _ -> n.copy(string = target, fret = fret) }
    }

    fun fretFor(i: InstrumentDefinition, string: Int, midi: Int): Int? {
        val fret = midi - (i.tuning[string] + i.capoByString[string] + i.transposition)
        return fret.takeIf { it in 0..i.maxFret }
    }

    /** Places [midi] on [preferred] or the closest free string; null when unplayable. */
    fun place(i: InstrumentDefinition, midi: Int, preferred: Int, occupied: Set<Int>): Pair<Int, Int>? {
        val order = i.tuning.indices.sortedBy { kotlin.math.abs(it - preferred) }
        for (string in order) if (string !in occupied) fretFor(i, string, midi)?.let { return string to it }
        return null
    }

    fun transpose(p: Project, s: Selection, semitones: Int, scope: EditScope): Project {
        require(semitones in -48..48) { "Transposition außerhalb von ±4 Oktaven" }
        if (semitones == 0) return p
        val measures = when (scope) { EditScope.TRACK -> p.measures.map { it.id }.toSet(); EditScope.MEASURES -> s.measureRange(p).toSet(); else -> setOf(s.measureId) }
        return p.copy(tracks = p.tracks.map { t ->
            if (t.id != s.trackId || t.instrument.kind == InstrumentKind.PERCUSSION) t else t.copy(staves = t.staves.map { staff -> staff.copy(bars = staff.bars.map { bar ->
                if (bar.measureId !in measures) bar else bar.copy(voices = bar.voices.map { v ->
                    if (scope != EditScope.TRACK && scope != EditScope.MEASURES && v.index != s.voice) v else v.copy(beats = v.beats.map { b ->
                        if ((scope == EditScope.NOTE || scope == EditScope.BEAT) && b.offset != s.offset) b else transposeBeat(b, t, semitones, if (scope == EditScope.NOTE && fretted(t)) s.string else null)
                    })
                })
            }) })
        })
    }

    private fun transposeBeat(b: Beat, t: Track, semitones: Int, onlyString: Int?): Beat {
        val occupied = b.notes.filter { onlyString != null && it.string != onlyString }.mapNotNull { it.string }.toMutableSet()
        val notes = b.notes.map { n ->
            if (onlyString != null && n.string != onlyString) return@map n
            val midi = n.pitch.midiNumber + semitones
            require(midi in 0..127) { "Transponierter Ton liegt außerhalb des MIDI-Bereichs" }
            val string = n.string
            if (string == null) n.copy(pitch = n.pitch.copy(midiNumber = midi, spelling = null))
            else {
                val (newString, fret) = place(t.instrument, midi, string, occupied) ?: throw IllegalArgumentException("Transponierter Ton ${midi} ist auf keiner freien Saite spielbar")
                occupied += newString
                n.copy(pitch = n.pitch.copy(midiNumber = midi, spelling = null), string = newString, fret = fret)
            }
        }
        return b.copy(notes = notes)
    }

    /** Changes the selected beat's duration and shifts later beats of the voice (GP-style ripple inside the bar). */
    fun setDurationRipple(p: Project, s: Selection, transform: (WrittenDuration) -> WrittenDuration): Project {
        val beat = selectedBeat(p, s) ?: return p
        val next = transform(beat.duration)
        if (next == beat.duration) return p
        val delta = next.length() + beat.duration.length() * Fraction.of(-1)
        return mapVoice(p, s) { v, _, m -> rippled(v.copy(beats = v.beats.map { if (it.offset == beat.offset) it.copy(duration = next) else it }), beat.offset, delta, m) }
    }

    private fun rippled(v: Voice, after: Fraction, delta: Fraction, m: Measure): Voice {
        val shifted = v.beats.map { if (it.offset > after) it.copy(offset = it.offset + delta) else it }
        val end = shifted.maxOfOrNull { it.offset + it.duration.length() } ?: Fraction.ZERO
        require(end <= capacity(m)) { "Takt ist voll – erst Noten löschen oder den Takt verlängern" }
        require(shifted.all { it.offset >= Fraction.ZERO }) { "Ungültige Verschiebung" }
        return v.copy(beats = shifted.sortedBy { it.offset })
    }

    fun deleteBeat(p: Project, s: Selection): Project {
        val beat = selectedBeat(p, s) ?: return p
        val removedIds = beat.notes.map { it.id }.toSet()
        val edited = mapVoice(p, s) { v, _, m -> rippled(v.copy(beats = v.beats.filterNot { it.offset == beat.offset }), beat.offset, beat.duration.length() * Fraction.of(-1), m) }
        return Links.prune(edited, removedIds)
    }

    fun insertBeat(p: Project, s: Selection, duration: WrittenDuration, ids: IdSource): Project = mapVoice(p, s) { v, _, m ->
        val shifted = v.beats.map { if (it.offset >= s.offset) it.copy(offset = it.offset + duration.length()) else it }
        checkCapacity(v.copy(beats = (shifted + Beat(ids.next(), s.offset, duration, explicitRest = true)).sortedBy { it.offset }), m)
    }

    /** GP-style tie: the selected note continues the previous note on the same string (same pitch, no re-pick). */
    fun toggleTie(p: Project, s: Selection, ids: IdSource): Project {
        val t = track(p, s)
        require(t.instrument.kind != InstrumentKind.PERCUSSION) { "Schlagzeugnoten können nicht gebunden werden" }
        val staff = t.staves.firstOrNull() ?: throw IllegalArgumentException("Spur ohne Notensystem")
        val measureIndex = p.measures.indexOfFirst { it.id == s.measureId }
        val current = selectedBeat(p, s)?.notes?.firstOrNull { if (fretted(t)) it.string == s.string else true }
        // Previous note on the same string (or same pitch for unfretted) in this voice, searching back over barlines.
        var origin: Note? = null
        var originBeat: Beat? = null
        var originMeasure = -1
        loop@ for (mi in measureIndex downTo 0) {
            val beats = staff.bars.firstOrNull { it.measureId == p.measures[mi].id }?.voices?.firstOrNull { it.index == s.voice }?.beats.orEmpty()
            for (b in beats.sortedByDescending { it.offset }) {
                if (mi == measureIndex && b.offset >= s.offset) continue
                val n = b.notes.firstOrNull { if (fretted(t)) it.string == s.string else current == null || it.pitch.midiNumber == current.pitch.midiNumber }
                if (n != null) { origin = n; originBeat = b; originMeasure = mi; break@loop }
                if (b.notes.isNotEmpty() || b.explicitRest) break@loop
            }
        }
        val from = origin ?: throw IllegalArgumentException("Keine vorherige Note auf dieser Saite zum Binden")
        if (current != null && from.links.any { it.kind == LinkKind.TIE && it.target == current.id }) {
            return updateNote(p, from.id) { it.copy(links = it.links.filterNot { l -> l.kind == LinkKind.TIE && l.target == current.id }) }
        }
        // A tie continues a note without a gap: the origin must end exactly where the selection starts (same bar, or at the
        // end of the previous bar with the selection at the downbeat). Playback cannot sound a tie across silence.
        val originEnd = (originBeat ?: throw IllegalArgumentException("Keine vorherige Note")).offset + originBeat.duration.length()
        val adjacent = if (originMeasure == measureIndex) originEnd == s.offset
            else originMeasure == measureIndex - 1 && s.offset == Fraction.ZERO && p.measures[originMeasure].let { m -> originEnd == (m.pickupLength ?: m.signature.length()) }
        require(adjacent) { "Haltebogen nur zur unmittelbar vorherigen Note ohne Pause" }
        var edited = p
        val targetId: EntityId
        if (current == null) {
            val note = Note(ids.next(), from.pitch, from.string, from.fret, from.velocity)
            targetId = note.id
            edited = mapVoice(edited, s) { v, _, m ->
                val exists = v.beats.any { it.offset == s.offset }
                val beats = if (exists) v.beats.map { if (it.offset == s.offset) it.copy(notes = it.notes + note, explicitRest = false) else it }
                    else v.beats + Beat(ids.next(), s.offset, originBeat?.duration ?: WrittenDuration(), listOf(note))
                checkCapacity(v.copy(beats = beats.sortedBy { it.offset }), m)
            }
        } else {
            targetId = current.id
            edited = updateNote(edited, current.id) { it.copy(pitch = from.pitch, fret = from.fret, string = from.string) }
        }
        return updateNote(edited, from.id) { it.copy(links = it.links.filterNot { l -> l.kind == LinkKind.TIE } + NoteLink(LinkKind.TIE, targetId)) }
    }

    private fun checkCapacity(v: Voice, m: Measure): Voice {
        val end = v.beats.maxOfOrNull { it.offset + it.duration.length() } ?: Fraction.ZERO
        require(end <= capacity(m)) { "Takt ist voll – erst Noten löschen oder den Takt verlängern" }
        return v
    }

    fun updateNote(p: Project, id: EntityId, transform: (Note) -> Note): Project = p.copy(tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.map { bar ->
        bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { b -> if (b.notes.none { it.id == id }) b else b.copy(notes = b.notes.map { if (it.id == id) transform(it) else it }) }) })
    }) }) })

    fun setText(p: Project, s: Selection, text: String?, kind: TextKind, ids: IdSource): Project {
        val position = ScorePosition(s.measureId, s.offset)
        val kept = p.text.filterNot { it.position == position && it.kind == kind }
        val value = text?.trim().orEmpty()
        require(value.length <= 500) { "Text ist zu lang" }
        return p.copy(text = if (value.isEmpty()) kept else kept + ScoreText(ids.next(), position, value, kind))
    }
}

/** Keeps note links consistent after deletions. */
internal object Links {
    fun prune(p: Project, removed: Set<EntityId> = emptySet()): Project {
        val alive = p.tracks.flatMap { it.staves }.flatMap { it.bars }.flatMap { it.voices }.flatMap { it.beats }.flatMap { it.notes }.map { it.id }.toSet() - removed
        return p.copy(tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.map { bar -> bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { b ->
            b.copy(notes = b.notes.map { n ->
                val links = n.links.filter { it.target in alive }
                val arts = n.articulations.map { a -> if (a is Articulation.Slide && a.target != null && a.target !in alive) a.copy(target = null) else a }
                if (links.size == n.links.size && arts == n.articulations) n else n.copy(links = links, articulations = arts)
            })
        }) }) }) }) })
    }
}
