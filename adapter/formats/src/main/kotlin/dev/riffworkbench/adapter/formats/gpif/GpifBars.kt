package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.xml.XmlElement
import dev.riffworkbench.model.*

internal class NoteDraft(val id: EntityId, var string: Int?, var fret: Int?, var midi: Int?, val fileMidi: Int?) {
    val marks = LinkedHashSet<NoteMark>()
    val articulations = ArrayList<Articulation>()
    val links = ArrayList<NoteLink>()
    var tieOrigin = false
    var tieDestination = false
    var slideFlags = 0
    var hopoOrigin = false
    var trillMidi: Int? = null
    var fingering: Fingering? = null
    var spelling: String? = null
    var velocity = 96
    var next: NoteDraft? = null
    var dropped = false
}

internal class BeatDraft(val id: EntityId, val offset: Fraction, val duration: WrittenDuration, val notes: MutableList<NoteDraft>) {
    var strum: Strum? = null
    var fermata: Fermata? = null
    var chordId: EntityId? = null
    var legatoOrigin = false
    var dynamic: DynamicLevel? = null
    var hasText = false
}
internal class VoiceDraft(val id: EntityId, val index: Int, val beats: MutableList<BeatDraft> = ArrayList())
internal class BarDraft(val id: EntityId, val measureIndex: Int, val voices: List<VoiceDraft>)
internal class PendingText(val measureIndex: Int, val offset: Fraction, val text: String, val kind: TextKind)

/** Indexed GPIF collections (id -> element). Duplicate ids keep the first element and are reported. */
internal class GpifIndex(root: XmlElement, losses: LossCollector) {
    val bars = index(root.child("Bars"), "Bar", losses)
    val voices = index(root.child("Voices"), "Voice", losses)
    val beats = index(root.child("Beats"), "Beat", losses)
    val notes = index(root.child("Notes"), "Note", losses)
    val rhythms = index(root.child("Rhythms"), "Rhythm", losses)

    private fun index(container: XmlElement?, name: String, losses: LossCollector): Map<String, XmlElement> {
        val map = HashMap<String, XmlElement>()
        for (e in container?.children(name).orEmpty()) {
            val id = e.attr("id") ?: continue
            if (map.putIfAbsent(id, e) != null) losses.add("import.unknown_fields", "Doppelte GPIF-ID in <$name>: erster Eintrag gilt")
        }
        return map
    }
}

/** Running budgets so that shared references cannot amplify a small file into a huge project. */
internal class ImportBudget {
    var beats = 0
    var notes = 0
    fun beat() { if (++beats > MAX_BEATS) resourceLimit("Partitur enthält mehr als $MAX_BEATS Beats") }
    fun note() { if (++notes > MAX_NOTES) resourceLimit("Partitur enthält mehr als $MAX_NOTES Noten") }
    companion object { const val MAX_BEATS = 500_000; const val MAX_NOTES = 1_000_000 }
}

internal class BarReader(
    private val index: GpifIndex,
    private val ids: IdSource,
    private val losses: LossCollector,
    private val budget: ImportBudget,
    private val chordId: (trackIndex: Int, gpifChord: String) -> EntityId?,
    private val texts: MutableList<PendingText>,
) {
    private val rhythmCache = HashMap<String, WrittenDuration?>()
    private class Grace(val notes: List<NoteDraft>, val duration: WrittenDuration, val beforeBeat: Boolean)

    fun read(barEl: XmlElement?, track: TrackPlan, trackIndex: Int, measureIndex: Int, length: Fraction): BarDraft {
        val barId = ids.next()
        val voiceIds = barEl?.child("Voices")?.text?.let { GpifNumbers.ints(it) }.orEmpty()
        if (barEl != null) {
            reportUnknown(barEl, setOf("Voices", "Clef", "XProperties", "Properties"), "Takt")
            barEl.textOf("Clef")?.trim()?.let { if (it.isNotEmpty() && it != track.expectedClef) losses.add("notation.standard", "Schlüssel $it wird nicht übernommen (abgeleitet aus dem Instrument)") }
            if (voiceIds.drop(4).any { it >= 0 }) losses.add("rhythm.voices", "Mehr als vier Stimmen pro Takt werden nicht übernommen")
        }
        val voices = (0 until 4).map { v ->
            val voice = VoiceDraft(ids.next(), v)
            val id = voiceIds.getOrNull(v)?.takeIf { it >= 0 }?.toString()
            val voiceEl = id?.let { index.voices[it] ?: run { losses.add("import.unknown_fields", "Fehlende Stimmenreferenz wird ignoriert"); null } }
            if (voiceEl != null) readVoice(voiceEl, voice, track, trackIndex, measureIndex, length)
            voice
        }
        return BarDraft(barId, measureIndex, voices)
    }

    private fun readVoice(el: XmlElement, voice: VoiceDraft, track: TrackPlan, trackIndex: Int, measureIndex: Int, length: Fraction) {
        var offset = Fraction.ZERO
        val graces = ArrayList<Grace>()
        var overfull = false
        for (beatRef in GpifNumbers.ints(el.textOf("Beats"))) {
            budget.beat()
            val beatEl = index.beats[beatRef.toString()] ?: run { losses.add("import.unknown_fields", "Fehlende Beatreferenz wird ignoriert"); null } ?: continue
            val duration = rhythm(beatEl.child("Rhythm")?.attr("ref"))
            if (duration == null) { losses.add("rhythm.duration", "Beat ohne gültigen Rhythmus wird verworfen"); continue }
            val graceMode = beatEl.textOf("GraceNotes")?.trim()
            if (graceMode == "BeforeBeat" || graceMode == "OnBeat") {
                val notes = readNotes(beatEl, track)
                if (notes.isNotEmpty()) graces += Grace(notes, duration, graceMode == "BeforeBeat")
                continue
            }
            if (overfull) continue
            val end = offset + duration.length()
            if (end > length) { overfull = true; losses.add("rhythm.duration", "Überzählige Beats eines übervollen Takts werden verworfen"); continue }
            val beat = BeatDraft(ids.next(), offset, duration, readNotes(beatEl, track).toMutableList())
            beatProperties(beatEl, beat, track, trackIndex, measureIndex)
            if (graces.isNotEmpty()) { attachGraces(graces, beat); graces.clear() }
            voice.beats += beat
            offset = end
        }
        if (graces.isNotEmpty()) losses.add("rhythm.grace", "Vorschlagsnote ohne Hauptnote am Taktende wird verworfen")
    }

    private fun attachGraces(graces: List<Grace>, beat: BeatDraft) {
        if (beat.notes.isEmpty()) { losses.add("rhythm.grace", "Vorschlagsnote vor einer Pause wird verworfen"); return }
        for (g in graces) for (gn in g.notes) {
            val main = beat.notes.firstOrNull { it.string != null && it.string == gn.string } ?: beat.notes.first()
            val midi = gn.midi ?: continue
            main.articulations += Articulation.Grace(Pitch(midi), g.duration, g.beforeBeat, gn.fret, gn.string)
            if (gn.marks.isNotEmpty() || gn.articulations.isNotEmpty() || gn.slideFlags != 0 || gn.hopoOrigin || gn.tieOrigin)
                losses.add("rhythm.grace", "Techniken auf Vorschlagsnoten (Slide, Legato, Bend …) werden nicht übernommen")
        }
    }

    fun rhythm(ref: String?): WrittenDuration? {
        if (ref == null) return null
        return rhythmCache.getOrPut(ref) {
            val el = index.rhythms[ref] ?: return@getOrPut null
            val value = el.textOf("NoteValue")?.trim().orEmpty()
            val denominator = GpifUnits.denominatorOf(value) ?: when (value) {
                "DoubleWhole", "Long" -> { losses.add("rhythm.duration", "Doppelganze Noten werden als ganze Noten übernommen"); 1 }
                else -> return@getOrPut null
            }
            val dots = GpifNumbers.parseIntOrNull(el.child("AugmentationDot")?.attr("count")) ?: 0
            if (dots !in 0..4) { losses.add("rhythm.dots", "Mehr als vier Punkte werden nicht unterstützt"); return@getOrPut null }
            val tuplets = listOf("PrimaryTuplet", "SecondaryTuplet").mapNotNull { name ->
                val t = el.child(name) ?: return@mapNotNull null
                val num = GpifNumbers.parseIntOrNull(t.attr("num")) ?: return@mapNotNull null
                val den = GpifNumbers.parseIntOrNull(t.attr("den")) ?: return@mapNotNull null
                if (num == den || num <= 0 || den <= 0) null
                else if (num > 64 || den > 64) { losses.add("rhythm.tuplets", "Tuplet $num:$den wird nicht unterstützt"); null }
                else TupletRatio(num, den)
            }
            WrittenDuration(denominator, dots, tuplets)
        }
    }

    private fun readNotes(beatEl: XmlElement, track: TrackPlan): List<NoteDraft> {
        val refs = GpifNumbers.ints(beatEl.textOf("Notes"))
        if (refs.size > 64) resourceLimit("Beat enthält zu viele Noten")
        val notes = ArrayList<NoteDraft>()
        for (ref in refs) {
            budget.note()
            val el = index.notes[ref.toString()] ?: run { losses.add("import.unknown_fields", "Fehlende Notenreferenz wird ignoriert"); null } ?: continue
            val note = NoteReader.read(el, track, ids, losses) ?: continue
            if (note.string != null && notes.any { it.string == note.string }) { losses.add("notes.fret", "Zweite Note auf derselben Saite im selben Beat wird verworfen"); continue }
            if (note.string == null && note.midi != null && notes.any { it.string == null && it.midi == note.midi }) { losses.add("notes.fret", "Doppelte Note gleicher Tonhöhe im Beat wird verworfen"); continue }
            notes += note
        }
        return notes
    }

    private fun beatProperties(el: XmlElement, beat: BeatDraft, track: TrackPlan, trackIndex: Int, measureIndex: Int) {
        reportUnknown(el, BEAT_CHILDREN, "Beat")
        el.textOf("Dynamic")?.trim()?.let { text -> beat.dynamic = DynamicLevel.entries.firstOrNull { it.name == text } ?: run { losses.add("fx.dynamics", "Unbekannte Dynamik $text"); null } }
        val p = el.props()
        for (name in p.keys) if (name !in BEAT_PROPERTIES) losses.add("import.unknown_fields", "Beat-Eigenschaft '$name' wird nicht unterstützt")
        val spread = el.child("XProperties")?.children("XProperty").orEmpty().let { xs ->
            if (xs.any { it.attr("id") != BRUSH_DURATION }) losses.add("import.unknown_fields", "Interne Beat-Eigenschaften (XProperties) werden nicht übernommen")
            xs.firstOrNull { it.attr("id") == BRUSH_DURATION }?.textOf("Int")?.trim()?.toLongOrNull()
                ?.takeIf { it in 1..GpifUnits.TICKS_PER_WHOLE * 4 }?.let { Fraction.of(it, GpifUnits.TICKS_PER_WHOLE) } ?: Fraction.ZERO
        }
        fun direction(text: String?) = when (text?.trim()) { "Up" -> StrokeDirection.UP; "Down" -> StrokeDirection.DOWN; else -> null }
        val brush = direction(p["Brush"]?.textOf("Direction"))
        val arpeggio = direction(el.textOf("Arpeggio"))
        val rasgueado = p["Rasgueado"]?.textOf("Rasgueado")?.trim()
        beat.strum = when {
            rasgueado != null -> { if (rasgueado != "ii_1") losses.add("fx.rasgueado", "Rasgueado-Muster $rasgueado wird als einfaches Rasgueado übernommen"); Strum(StrumKind.RASGUEADO, brush ?: StrokeDirection.DOWN, spread) }
            arpeggio != null -> Strum(StrumKind.ARPEGGIO, arpeggio, spread)
            brush != null -> Strum(StrumKind.STRUM, brush, spread)
            else -> null
        }
        if (beat.strum != null && beat.notes.isEmpty()) beat.strum = null
        beat.legatoOrigin = el.child("Legato")?.attr("origin") == "true"
        el.textOf("Chord")?.trim()?.takeIf { it.isNotEmpty() }?.let { beat.chordId = chordId(trackIndex, it) ?: run { losses.add("layout.chord_symbols", "Akkordreferenz ohne Diagramm wird ignoriert"); null } }
        el.textOf("FreeText")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            texts += PendingText(measureIndex, beat.offset, it.take(500), TextKind.TEXT); beat.hasText = true
            if (trackIndex > 0) losses.add("layout.text_lyrics", "Freitexte werden ohne Spurzuordnung übernommen")
        }
        val lyricLines = el.child("Lyrics")?.children("Line").orEmpty().map { it.text.trim() }
        lyricLines.firstOrNull { it.isNotEmpty() }?.let {
            texts += PendingText(measureIndex, beat.offset, it.take(500), TextKind.LYRICS); beat.hasText = true
            losses.add("layout.text_lyrics", "Liedtext wird ohne Spurzuordnung übernommen")
            if (lyricLines.count { l -> l.isNotEmpty() } > 1) losses.add("layout.text_lyrics", "Weitere Liedtextstrophen werden nicht übernommen")
        }
        // Beat-level techniques belong to every note of the beat in the model.
        val tremolo = el.textOf("Tremolo")?.let { t -> GpifUnits.tremoloDenominator(t) ?: run { losses.add("fx.tremolo", "Tremolo-Geschwindigkeit $t wird als Zweiunddreißigstel übernommen"); 32 } }
        val pick = direction(p["PickStroke"]?.textOf("Direction"))
        val whammy = if (p["WhammyBar"] != null) curve(p, "WhammyBar")?.let { Articulation.Whammy(it) } else null
        if (p["WhammyBarExtend"] != null) losses.add("fx.whammy", "Über den Beat hinaus verlängerte Whammy-Kurven werden gekürzt")
        val whammyVibrato = p["VibratoWTremBar"]?.textOf("Strength")?.trim()
        if (whammyVibrato != null) losses.add("fx.vibrato", "Whammy-Vibrato wird als Fingervibrato übernommen")
        for (n in beat.notes) {
            if (tremolo != null) n.articulations += Articulation.TremoloPicking(WrittenDuration(tremolo))
            if (pick != null) n.articulations += Articulation.Pickstroke(pick)
            if (p["Slapped"] != null) n.marks += NoteMark.SLAP
            if (p["Popped"] != null) n.marks += NoteMark.POP
            if (whammy != null) n.articulations += whammy
            if (whammyVibrato != null && n.articulations.none { it is Articulation.Vibrato }) n.articulations += NoteReader.vibrato(whammyVibrato)
        }
    }

    private fun reportUnknown(el: XmlElement, known: Set<String>, what: String) {
        for (c in el.elements) if (c.name !in known) {
            val feature = when (c.name) {
                "Fadding", "Wah", "Hairpin" -> "fx.dynamics"
                "Ottavia" -> "notation.standard"
                "TransposedPitchStemOrientation", "ConcertPitchStemOrientation", "UserTransposedPitchStemOrientation", "SimileMark", "Bank", "Timer", "Variation" -> "layout.stylesheet"
                else -> "import.unknown_fields"
            }
            losses.add(feature, "$what-Element '${c.name}' wird nicht übernommen")
        }
    }

    companion object {
        const val BRUSH_DURATION = "687935489"
        val BEAT_CHILDREN = setOf("Rhythm", "Notes", "Dynamic", "GraceNotes", "Tremolo", "Arpeggio", "Properties", "XProperties", "Chord", "FreeText", "Lyrics", "Legato")
        val BEAT_PROPERTIES = setOf("Brush", "PickStroke", "Slapped", "Popped", "VibratoWTremBar", "WhammyBar", "WhammyBarExtend", "WhammyBarOriginValue", "WhammyBarOriginOffset",
            "WhammyBarMiddleValue", "WhammyBarMiddleOffset1", "WhammyBarMiddleOffset2", "WhammyBarDestinationValue", "WhammyBarDestinationOffset", "Rasgueado")

        /**
         * GPIF bend/whammy curve: origin (value, offset), optional middle value held between MiddleOffset1 and
         * MiddleOffset2, destination (value, offset). Returns model points (position 0..1, cents).
         */
        fun curve(p: Map<String, XmlElement>, prefix: String): List<PitchCurvePoint>? {
            fun f(name: String) = GpifNumbers.parseDoubleOrNull(p[prefix + name]?.textOf("Float"))
            val ov = f("OriginValue"); val mv = f("MiddleValue"); val dv = f("DestinationValue")
            if (ov == null && mv == null && dv == null) return null
            val oo = f("OriginOffset") ?: 0.0
            val dOff = f("DestinationOffset") ?: 100.0
            val raw = ArrayList<Pair<Double, Double>>()
            raw += oo to (ov ?: 0.0)
            if (mv != null) {
                val m1 = f("MiddleOffset1") ?: ((oo + dOff) / 2)
                val m2 = f("MiddleOffset2") ?: m1
                raw += m1 to mv
                if (m2 != m1) raw += m2 to mv
            }
            raw += dOff to (dv ?: mv ?: ov ?: 0.0)
            val points = raw.sortedBy { it.first }.map { PitchCurvePoint(GpifUnits.positionOf(it.first), GpifUnits.centsOf(it.second).coerceIn(-4800, 4800)) }
            return points.filterIndexed { i, pt -> i == 0 || pt != points[i - 1] }
        }
    }
}
