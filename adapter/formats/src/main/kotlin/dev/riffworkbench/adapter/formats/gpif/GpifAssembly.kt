package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler

/** Collects bar drafts per track/staff and turns them into model objects once every bar is known. */
internal class GpifAssembly(private val plans: List<TrackPlan>, private val masters: List<MasterBarPlan>, private val losses: LossCollector) {
    private val staves: List<List<MutableList<BarDraft>>> = plans.map { p -> List(p.staffCount) { ArrayList<BarDraft>() } }

    fun add(track: Int, staff: Int, bar: BarDraft) { staves[track][staff] += bar }

    fun finish(anacrusis: Boolean): List<Measure> {
        plans.forEachIndexed { t, plan -> for (bars in staves[t]) for (v in 0 until 4) resolveVoice(plan, bars.flatMap { it.voices[v].beats }) }
        masters.forEachIndexed { m, master ->
            for ((offset, fermata) in master.fermatas) {
                var hit = false
                for (track in staves) for (bars in track) for (voice in bars[m].voices) for (beat in voice.beats) if (beat.offset == offset) { beat.fermata = fermata; hit = true }
                if (!hit) losses.add("rhythm.fermata", "Fermate ohne Beat an ihrer Position wird verworfen", master.measure.id)
            }
        }
        val measures = masters.map { it.measure }.toMutableList()
        if (anacrusis) {
            var content = Fraction.ZERO
            for (track in staves) for (bars in track) for (voice in bars[0].voices) voice.beats.lastOrNull()?.let { val end = it.offset + it.duration.length(); if (end > content) content = end }
            if (content > Fraction.ZERO && content < measures[0].signature.length()) measures[0] = measures[0].copy(pickupLength = content)
        }
        // A bar that only holds whole-bar rests in voice 1 is Guitar Pro's representation of an empty bar.
        for (track in staves) for (bars in track) for (bar in bars) {
            val length = measures[bar.measureIndex].let { it.pickupLength ?: it.signature.length() }
            val first = bar.voices[0].beats
            if (first.isEmpty() || first.any { it.notes.isNotEmpty() || it.fermata != null || it.hasText }) continue
            val end = first.last().let { it.offset + it.duration.length() }
            if (end == length && bar.voices.drop(1).all { v -> v.beats.all { it.notes.isEmpty() } }) first.clear()
        }
        return measures
    }

    private fun key(n: NoteDraft): Any = n.string ?: ("m" + n.midi)

    private fun resolveVoice(plan: TrackPlan, beats: List<BeatDraft>) {
        if (beats.isEmpty()) return
        // Tie destinations may omit the fret; Guitar Pro repeats the origin's position.
        val previous = HashMap<Any, NoteDraft>()
        for (beat in beats) {
            for (n in beat.notes) {
                val s = n.string
                if (s != null) {
                    if (n.fret == null) {
                        val origin = previous[s]
                        if (origin?.fret != null && (n.tieDestination || origin.tieOrigin)) n.fret = origin.fret
                        else { losses.add("notes.fret", "Note ohne Bundangabe wird verworfen"); n.dropped = true; continue }
                    }
                    val midi = plan.pitch(s, n.fret!!)
                    if (midi !in 0..127) { losses.add("notes.fret", "Tonhöhe außerhalb des MIDI-Bereichs wird verworfen"); n.dropped = true; continue }
                    n.midi = midi
                    if (n.fileMidi != null && n.fileMidi != midi) losses.add("notes.fret", "Gespeicherte MIDI-Tonhöhe weicht von Saite/Bund/Kapo ab; Saite und Bund gelten")
                }
                previous[key(n)] = n
            }
            beat.notes.removeAll { it.dropped }
            if (beat.notes.isEmpty()) beat.strum = null
        }
        var level = DynamicLevel.F
        for (beat in beats) {
            if (beat.notes.isEmpty()) continue
            val current = beat.dynamic ?: level
            for (n in beat.notes) {
                n.velocity = GpifUnits.velocityOf(current)
                if (current != level) n.articulations += Articulation.Dynamic(current)
            }
            level = current
        }
        val next = HashMap<Any, NoteDraft>()
        var nextBeat: BeatDraft? = null
        val nextBeatOf = HashMap<BeatDraft, BeatDraft?>()
        for (i in beats.indices.reversed()) {
            val beat = beats[i]
            for (n in beat.notes) n.next = next[key(n)]
            for (n in beat.notes) next[key(n)] = n
            nextBeatOf[beat] = nextBeat
            if (beat.notes.isNotEmpty()) nextBeat = beat
        }
        for (beat in beats) for (n in beat.notes) {
            val target = n.next
            if (n.tieOrigin) {
                if (target != null && target.midi == n.midi) n.links += NoteLink(LinkKind.TIE, target.id)
                else losses.add("rhythm.tie", "Haltebogen ohne passende Zielnote wird verworfen", n.id)
            }
            if (n.slideFlags and NoteReader.SLIDE_SHIFT != 0) n.articulations += Articulation.Slide(SlideKind.SHIFT, target?.id)
            if (n.slideFlags and NoteReader.SLIDE_LEGATO != 0) n.articulations += Articulation.Slide(SlideKind.LEGATO, target?.id)
            if (n.hopoOrigin) {
                if (target != null) n.links += NoteLink(if (target.midi!! >= n.midi!!) LinkKind.HAMMER_ON else LinkKind.PULL_OFF, target.id)
                else { n.marks += NoteMark.HAMMER_ON; losses.add("fx.hammer_pull", "Hammer-on/Pull-off ohne Zielnote wird als Markierung übernommen", n.id) }
            }
            if (beat.legatoOrigin) {
                val slurTarget = target ?: nextBeatOf[beat]?.notes?.firstOrNull()
                if (slurTarget != null) n.links += NoteLink(LinkKind.SLUR, slurTarget.id)
                else losses.add("rhythm.slur", "Bindebogen ohne Zielnote wird verworfen", n.id)
            }
            n.trillMidi?.let { trill ->
                val s = n.string
                val fret = s?.let { trill - plan.tuning[it] - plan.capoByString[it] }
                if (fret != null && fret in 0..48) n.articulations += Articulation.Trill(fret, WrittenDuration(16))
                else losses.add("fx.trill", "Triller ohne spielbare Zielposition wird verworfen", n.id)
            }
        }
    }

    fun track(t: Int, id: EntityId, staffIds: List<EntityId>, measures: List<Measure>): Track {
        val plan = plans[t]
        var maxFret = 24
        for (bars in staves[t]) for (bar in bars) for (voice in bar.voices) for (beat in voice.beats) for (n in beat.notes) {
            n.fret?.let { if (it > maxFret) maxFret = it }
            n.articulations.forEach { a ->
                if (a is Articulation.Grace) a.fret?.let { if (it > maxFret) maxFret = it }
                if (a is Articulation.Trill && a.targetFret > maxFret) maxFret = a.targetFret
            }
        }
        val drumKit = if (plan.percussion) plan.drumArticulations.filter { it.midi in 0..127 }.distinctBy { it.midi }
            .map { DrumElement(it.midi, it.name.ifEmpty { GpDrums.entry(it.midi).name }.take(80), chokeGroup = ScorePlaybackCompiler.defaultChokeGroup(it.midi)) }.ifEmpty { defaultDrumKit() }
        else defaultDrumKit()
        val instrument = InstrumentDefinition(plan.kind, plan.tuning, plan.capoByString, maxFret.coerceAtMost(48), plan.midiProgram, drumKit = drumKit)
        val staffs = staves[t].mapIndexed { s, bars ->
            Staff(staffIds[s], plan.notation, bars.map { bar ->
                Bar(bar.id, measures[bar.measureIndex].id, bar.voices.map { v -> Voice(v.id, v.index, v.beats.map { b -> beat(b) }) })
            })
        }
        return Track(id, plan.name, instrument, staffs, plan.sound, plan.mix)
    }

    private fun beat(b: BeatDraft) = Beat(
        b.id, b.offset, b.duration,
        b.notes.map { n -> Note(n.id, Pitch(n.midi!!, spelling = n.spelling), n.string, n.fret, n.velocity, canonical(n.marks, n.articulations), n.links.toList(), n.fingering) },
        explicitRest = b.notes.isEmpty(), fermata = b.fermata, strum = b.strum, chordId = b.chordId,
    )

    companion object {
        private fun rank(a: Articulation): Int = when (a) {
            is Articulation.Mark -> 0; is Articulation.Bend -> 1; is Articulation.Whammy -> 2; is Articulation.Slide -> 3
            is Articulation.Harmonic -> 4; is Articulation.Vibrato -> 5; is Articulation.Trill -> 6; is Articulation.TremoloPicking -> 7
            is Articulation.Grace -> 8; is Articulation.Dynamic -> 9; is Articulation.Pickstroke -> 10; is Articulation.Percussion -> 11
        }
        private fun subRank(a: Articulation): Int = when (a) { is Articulation.Mark -> a.kind.ordinal; is Articulation.Slide -> a.kind.ordinal; else -> 0 }

        /** Deterministic articulation order: marks by enum order, then by technique type; graces keep their order. */
        fun canonical(marks: Collection<NoteMark>, others: List<Articulation>): List<Articulation> =
            (marks.distinct().map { Articulation.Mark(it) } + others).sortedWith(compareBy({ rank(it) }, { subRank(it) }))
    }
}
