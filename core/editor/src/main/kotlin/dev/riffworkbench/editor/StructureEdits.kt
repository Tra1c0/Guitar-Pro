package dev.riffworkbench.editor

import dev.riffworkbench.model.*

/** Bars copied from one or more tracks; pasting assigns fresh IDs and keeps links inside the copied range. */
data class BarClipboard(val measures: List<Measure>, val tracks: List<ClipTrack>) {
    data class ClipTrack(val trackId: EntityId, val kind: InstrumentKind, val strings: Int, val bars: List<Bar>)
    companion object {
        fun copy(p: Project, measureIds: List<EntityId>, trackIds: List<EntityId>): BarClipboard {
            val ordered = p.measures.filter { it.id in measureIds }
            require(ordered.isNotEmpty()) { "Keine Takte ausgewählt" }
            val tracks = p.tracks.filter { it.id in trackIds }.map { t ->
                val staff = t.staves.first()
                ClipTrack(t.id, t.instrument.kind, t.instrument.tuning.size, ordered.map { m -> staff.bars.first { it.measureId == m.id } })
            }
            require(tracks.isNotEmpty()) { "Keine Spur ausgewählt" }
            return BarClipboard(ordered, tracks)
        }
    }
}

/** Deep copy with new IDs; links and slide targets are remapped inside the copied set and dropped otherwise. */
internal class Cloner(private val ids: IdSource) {
    private val notes = HashMap<EntityId, EntityId>()
    private val groups = HashMap<EntityId, EntityId>()
    fun bar(bar: Bar, measureId: EntityId, stringShift: Int = 0, stringCount: Int? = null): Bar = Bar(ids.next(), measureId, bar.voices.map { v ->
        Voice(ids.next(), v.index, v.beats.map { b ->
            b.copy(id = ids.next(), tupletGroupIds = b.tupletGroupIds.map { g -> groups.getOrPut(g) { ids.next() } }, notes = b.notes.mapNotNull { n ->
                val string = n.string?.plus(stringShift)
                if (string != null && stringCount != null && string !in 0 until stringCount) return@mapNotNull null
                val id = ids.next(); notes[n.id] = id
                n.copy(id = id, string = string)
            })
        })
    })
    fun relink(bar: Bar): Bar = bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { b -> b.copy(notes = b.notes.map { n ->
        n.copy(links = n.links.mapNotNull { l -> notes[l.target]?.let { NoteLink(l.kind, it) } },
            articulations = n.articulations.map { a -> if (a is Articulation.Slide && a.target != null) a.copy(target = notes[a.target]) else a })
    }) }) })
}

internal object StructureEdits {
    private fun index(p: Project, id: EntityId) = p.measures.indexOfFirst { it.id == id }.also { require(it >= 0) { "Takt fehlt" } }

    fun updateMeasure(p: Project, id: EntityId, transform: (Measure) -> Measure): Project {
        index(p, id)
        return p.copy(measures = p.measures.map { if (it.id == id) transform(it) else it })
    }

    fun insert(p: Project, at: Int, count: Int, signature: TimeSignature?, ids: IdSource): Project {
        require(count in 1..256) { "Anzahl neuer Takte ungültig" }
        require(at in 0..p.measures.size) { "Einfügeposition ungültig" }
        require(p.measures.size + count <= 10_000) { "Zu viele Takte" }
        val template = signature ?: (p.measures.getOrNull(at - 1) ?: p.measures.getOrNull(at))?.signature ?: TimeSignature()
        val added = List(count) { Measure(ids.next(), template, key = (p.measures.getOrNull(at - 1) ?: p.measures.getOrNull(at))?.key ?: KeySignature()) }
        val factory = ProjectFactory(ids)
        val measures = p.measures.toMutableList().apply { addAll(at, added) }
        // Inserting before the first bar: the start tempo moves to the new first bar (a project always has a tempo at bar 1).
        val oldFirst = p.measures.firstOrNull()?.id
        val tempo = if (at == 0 && oldFirst != null) p.tempo.map { if (it.position.measureId == oldFirst && it.position.offset == Fraction.ZERO) it.copy(position = ScorePosition(added[0].id)) else it } else p.tempo
        return p.copy(measures = measures, tempo = tempo, tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { st ->
            st.copy(bars = st.bars.toMutableList().apply { addAll(at, added.map { factory.bar(it.id) }) })
        }) })
    }

    fun delete(p: Project, ids: List<EntityId>): Project {
        val doomed = ids.toSet()
        require(doomed.isNotEmpty()) { "Keine Takte ausgewählt" }
        require(doomed.all { id -> p.measures.any { it.id == id } }) { "Takt fehlt" }
        val kept = p.measures.filterNot { it.id in doomed }
        require(kept.isNotEmpty()) { "Mindestens ein Takt muss bleiben" }
        val keptIds = kept.map { it.id }.toSet()
        // Tempo in effect is preserved: a change inside a deleted range moves to the next kept bar.
        val tempo = p.tempo.mapNotNull { change ->
            if (change.position.measureId in keptIds) change
            else {
                val i = p.measures.indexOfFirst { it.id == change.position.measureId }
                val next = p.measures.drop(i + 1).firstOrNull { it.id in keptIds } ?: return@mapNotNull null
                TempoChange(ScorePosition(next.id), change.quarterNotesPerMinute)
            }
        }.groupBy { it.position }.map { it.value.last() }.let { list ->
            if (list.none { it.position.measureId == kept.first().id && it.position.offset == Fraction.ZERO })
                (p.tempo.firstOrNull()?.let { listOf(TempoChange(ScorePosition(kept.first().id), it.quarterNotesPerMinute)) } ?: emptyList()) + list else list
        }
        val removedNotes = p.tracks.flatMap { it.staves }.flatMap { it.bars }.filter { it.measureId in doomed }.flatMap { it.voices }.flatMap { it.beats }.flatMap { it.notes }.map { it.id }.toSet()
        val edited = p.copy(
            measures = kept.map { m -> m.copy(navigation = m.navigation.map { nav -> if (nav.target != null && nav.target in doomed) nav.copy(target = null) else nav }) },
            tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.filterNot { it.measureId in doomed }) }) },
            sections = p.sections.filterNot { it.measureId in doomed },
            tempo = tempo,
            text = p.text.filterNot { it.position.measureId in doomed },
            mix = p.mix.copy(automation = p.mix.automation.map { lane -> lane.copy(points = lane.points.filterNot { it.position.measureId in doomed }) }),
            audio = p.audio.copy(syncMaps = p.audio.syncMaps.map { map -> map.copy(anchors = map.anchors.filterNot { it.occurrence.position.measureId in doomed }) }),
        )
        return Links.prune(edited, removedNotes)
    }

    fun duplicate(p: Project, ids: List<EntityId>, idSource: IdSource): Project {
        val indices = ids.map { index(p, it) }.sorted()
        require(indices.isNotEmpty() && indices.last() - indices.first() + 1 == indices.size) { "Nur zusammenhängende Takte duplizieren" }
        val source = indices.map { p.measures[it] }
        val copies = source.map { it.copy(id = idSource.next(), repeat = RepeatMark(), navigation = emptyList()) }
        val at = indices.last() + 1
        val tracks = p.tracks.map { t ->
            val cloner = Cloner(idSource)
            t.copy(staves = t.staves.map { st ->
                val cloned = source.mapIndexed { i, m -> cloner.bar(st.bars.first { it.measureId == m.id }, copies[i].id) }.map { cloner.relink(it) }
                st.copy(bars = st.bars.toMutableList().apply { addAll(at, cloned) })
            })
        }
        return p.copy(measures = p.measures.toMutableList().apply { addAll(at, copies) }, tracks = tracks)
    }

    fun paste(p: Project, content: BarClipboard, target: EntityId, targetTrack: EntityId, ids: IdSource): Project {
        var project = p
        val start = index(project, target)
        val missing = start + content.measures.size - project.measures.size
        if (missing > 0) project = insert(project, project.measures.size, missing, content.measures.last().signature, ids)
        val targets = project.measures.subList(start, start + content.measures.size).map { it.id }
        project = project.copy(measures = project.measures.map { m ->
            val i = targets.indexOf(m.id)
            if (i < 0) m else m.copy(signature = content.measures[i].signature, pickupLength = content.measures[i].pickupLength, feel = content.measures[i].feel)
        })
        val firstTrack = project.tracks.indexOfFirst { it.id == targetTrack }.also { require(it >= 0) { "Spur fehlt" } }
        val replacedNotes = mutableSetOf<EntityId>()
        val tracks = project.tracks.toMutableList()
        content.tracks.forEachIndexed { k, clip ->
            val ti = firstTrack + k
            val t = tracks.getOrNull(ti) ?: return@forEachIndexed
            val fretted = t.instrument.tuning.isNotEmpty()
            require(clip.bars.size >= targets.size) { "Zwischenablage ist unvollständig" }
            require((clip.strings > 0) == fretted && (clip.kind == InstrumentKind.PERCUSSION) == (t.instrument.kind == InstrumentKind.PERCUSSION)) { "Inhalt passt nicht zum Instrument der Zielspur ${t.name}" }
            val shift = t.instrument.tuning.size - clip.strings
            val cloner = Cloner(ids)
            val staff = t.staves.first()
            val newBars = staff.bars.map { bar ->
                val i = targets.indexOf(bar.measureId)
                if (i < 0) bar else {
                    replacedNotes += bar.voices.flatMap { it.beats }.flatMap { it.notes }.map { it.id }
                    cloner.bar(clip.bars[i], bar.measureId, shift, if (fretted) t.instrument.tuning.size else null)
                }
            }.map { if (targets.contains(it.measureId)) cloner.relink(it) else it }
            // Fretted pitches follow the target instrument's tuning on the shifted strings.
            val repitched = newBars.map { bar -> if (!targets.contains(bar.measureId) || !fretted) bar else bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { b -> b.copy(notes = b.notes.mapNotNull { n ->
                val s = n.string ?: return@mapNotNull n; val f = n.fret ?: return@mapNotNull n
                if (f > t.instrument.maxFret) null else n.copy(pitch = n.pitch.copy(midiNumber = t.instrument.pitch(s, f), spelling = null))
            }) }) }) }
            tracks[ti] = t.copy(staves = listOf(staff.copy(bars = repitched)) + t.staves.drop(1))
        }
        return Links.prune(project.copy(tracks = tracks), replacedNotes)
    }

    fun clear(p: Project, ids: List<EntityId>, trackId: EntityId?): Project {
        val set = ids.toSet()
        val removed = mutableSetOf<EntityId>()
        val edited = p.copy(tracks = p.tracks.map { t -> if (trackId != null && t.id != trackId) t else t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.map { bar ->
            if (bar.measureId !in set) bar else { removed += bar.voices.flatMap { it.beats }.flatMap { it.notes }.map { it.id }; bar.copy(voices = bar.voices.map { it.copy(beats = emptyList()) }) }
        }) }) })
        return Links.prune(edited, removed)
    }

    fun setRepeat(p: Project, id: EntityId, repeat: RepeatMark): Project {
        require(repeat.endCount == null || repeat.endCount in 2..32) { "Wiederholungsanzahl muss zwischen 2 und 32 liegen" }
        require(repeat.alternateEndings.all { it in 1..8 }) { "Alternative Enden 1–8" }
        return updateMeasure(p, id) { it.copy(repeat = repeat) }
    }

    fun setTempoAt(p: Project, id: EntityId, bpm: Double?): Project {
        index(p, id)
        val position = ScorePosition(id)
        if (bpm == null) {
            require(id != p.measures.first().id) { "Das Starttempo kann nicht entfernt werden" }
            return p.copy(tempo = p.tempo.filterNot { it.position == position })
        }
        require(bpm.isFinite() && bpm in 10.0..1000.0) { "Tempo muss zwischen 10 und 1000 BPM liegen" }
        val order = p.measures.withIndex().associate { it.value.id to it.index }
        return p.copy(tempo = (p.tempo.filterNot { it.position == position } + TempoChange(position, bpm)).sortedWith(compareBy({ order[it.position.measureId] ?: 0 }, { it.position.offset })))
    }

    fun setSection(p: Project, id: EntityId, label: String?, ids: IdSource): Project {
        index(p, id)
        val text = label?.trim().orEmpty()
        require(text.length <= 80) { "Abschnittsname ist zu lang" }
        val existing = p.sections.firstOrNull { it.measureId == id }
        return when {
            text.isEmpty() -> p.copy(sections = p.sections.filterNot { it.measureId == id })
            existing != null -> p.copy(sections = p.sections.map { if (it.measureId == id) it.copy(label = text) else it })
            else -> {
                val order = p.measures.withIndex().associate { it.value.id to it.index }
                p.copy(sections = (p.sections + Section(ids.next(), id, text)).sortedBy { order[it.measureId] ?: 0 })
            }
        }
    }
}

internal object TrackEdits {
    fun update(p: Project, id: EntityId, transform: (Track) -> Track): Project {
        require(p.tracks.any { it.id == id }) { "Spur fehlt" }
        return p.copy(tracks = p.tracks.map { if (it.id == id) transform(it) else it })
    }

    fun duplicate(p: Project, id: EntityId, ids: IdSource): Project {
        val index = p.tracks.indexOfFirst { it.id == id }.also { require(it >= 0) { "Spur fehlt" } }
        require(p.tracks.size < 64) { "Höchstens 64 Spuren" }
        val source = p.tracks[index]
        val cloner = Cloner(ids)
        val staves = source.staves.map { st -> Staff(ids.next(), st.notation, st.bars.map { cloner.bar(it, it.measureId) }) }
        val copy = source.copy(id = ids.next(), name = (source.name + " (Kopie)").take(120), staves = staves.map { st -> st.copy(bars = st.bars.map { cloner.relink(it) }) },
            mix = source.mix.copy(solo = false, effects = source.mix.effects.map { it.copy(id = ids.next()) }))
        return p.copy(tracks = p.tracks.toMutableList().apply { add(index + 1, copy) })
    }

    /**
     * Changes tuning, string count, capo or instrument. Strings are top-aligned (adding a low string keeps the
     * high strings). KEEP_FINGER_POSITION keeps string/fret and re-derives pitch; KEEP_SOUNDING_PITCH keeps pitch
     * and moves to another free string when the fret leaves the neck. Notes that fit nowhere are shifted an octave
     * (see [Retuning]); notes that still fit nowhere are removed only with [allowDrop], otherwise the edit is rejected
     * with the affected bars in the message.
     */
    fun setInstrument(p: Project, id: EntityId, instrument: InstrumentDefinition, policy: RetuningPolicy, allowDrop: Boolean = false): Project {
        val track = p.tracks.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Spur fehlt")
        val old = track.instrument
        val newFretted = instrument.tuning.isNotEmpty()
        val oldFretted = old.tuning.isNotEmpty()
        val plan = Retuning.plan(p, id, instrument, policy)
        val drops = plan.problems.filter { it.resolution == RetuneResolution.DROP }
        require(allowDrop || drops.isEmpty()) { Retuning.failureMessage(drops) }
        val notation = if (instrument.kind == InstrumentKind.PERCUSSION) setOf(NotationKind.PERCUSSION) else if (newFretted) setOf(NotationKind.TAB) else setOf(NotationKind.STANDARD)
        val updated = track.copy(instrument = instrument, staves = plan.staves.map { if (oldFretted != newFretted || (old.kind == InstrumentKind.PERCUSSION) != (instrument.kind == InstrumentKind.PERCUSSION)) it.copy(notation = notation) else it })
        return p.copy(tracks = p.tracks.map { if (it.id == id) updated else it })
    }

    /** Pure dry run of [setInstrument]: the notes that would be octaved or dropped, with bar and string. */
    fun retuneProblems(p: Project, id: EntityId, instrument: InstrumentDefinition, policy: RetuningPolicy): List<RetuneProblem> = Retuning.problems(p, id, instrument, policy)
}
