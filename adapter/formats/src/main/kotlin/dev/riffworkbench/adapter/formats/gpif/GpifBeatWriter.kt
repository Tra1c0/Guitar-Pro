package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.xml.XmlWriter
import dev.riffworkbench.model.*

internal fun XmlWriter.prop(name: String, child: String, value: Any): XmlWriter = start("Property", "name" to name).leaf(child, value).end()
internal fun XmlWriter.flag(name: String): XmlWriter = start("Property", "name" to name).start("Enable").end().end()
internal fun XmlWriter.cdataLeaf(name: String, value: String): XmlWriter = start(name).cdata(value).end()

/** Writes bars, voices, beats and notes into deferred section writers with sequential GPIF ids. */
internal class BeatWriter(
    private val project: Project,
    private val tracks: List<ExportTrack>,
    private val relations: ExportRelations,
    private val rhythms: RhythmTable,
    private val losses: LossCollector,
) {
    val bars = ArrayList<(XmlWriter) -> Unit>()
    val voices = ArrayList<(XmlWriter) -> Unit>()
    val beats = ArrayList<(XmlWriter) -> Unit>()
    val notes = ArrayList<(XmlWriter) -> Unit>()
    private var barCount = 0
    private var voiceCount = 0
    private var beatCount = 0
    private var noteCount = 0
    private val running = HashMap<Triple<Int, Int, Int>, DynamicLevel>()
    private val measureIndex = project.measures.withIndex().associate { it.value.id to it.index }
    private val texts = project.text.filter { it.kind == TextKind.TEXT || it.kind == TextKind.LYRICS }
        .groupBy { (measureIndex[it.position.measureId] ?: -1) to it.position.offset }
    val usedTexts = HashSet<EntityId>()

    fun bar(t: Int, s: Int, bar: Bar, m: Int): Int {
        val et = tracks[t]
        val measure = project.measures[m]
        val length = measure.pickupLength ?: measure.signature.length()
        val voiceIds = (0 until 4).map { v -> voice(t, s, v, bar.voices.firstOrNull { it.index == v }, m, length) }
        val id = barCount++
        bars += { w -> w.start("Bar", "id" to id).leaf("Clef", et.clef).leaf("Voices", voiceIds.joinToString(" ")).end() }
        return id
    }

    private fun voice(t: Int, s: Int, v: Int, voice: Voice?, m: Int, length: Fraction): Int {
        val key = Triple(t, s, v)
        val sorted = voice?.beats?.sortedBy { it.offset }.orEmpty()
        if (sorted.isEmpty() && v != 0) return -1
        val ids = ArrayList<Int>()
        if (sorted.isEmpty()) {
            // Guitar Pro keeps at least one beat in the first voice: an empty bar becomes a whole-bar rest.
            val fill = RhythmTable.fill(length) ?: listOf(WrittenDuration(1)).also { losses.add("rhythm.rest", "Leerer Takt ist nicht als Pause darstellbar; eine ganze Pause wird geschrieben") }
            fill.forEach { ids += rest(it, key) }
        } else {
            var cursor = Fraction.ZERO
            for (beat in sorted) {
                if (beat.offset > cursor) {
                    val gap = beat.offset + Fraction.of(-cursor.numerator, cursor.denominator)
                    val fill = RhythmTable.fill(gap)
                    if (fill == null) losses.add("rhythm.rest", "Lücke in einer Stimme ist nicht als Pause darstellbar; Beats verschieben sich", beat.id)
                    else { losses.add("rhythm.rest", "Lücken in einer Stimme werden als Pausen exportiert", beat.id); fill.forEach { ids += rest(it, key) } }
                }
                ids += graces(t, beat, key)
                ids += beat(t, s, v, beat, m, key)
                cursor = beat.offset + beat.duration.length()
            }
        }
        val id = voiceCount++
        voices += { w -> w.start("Voice", "id" to id).leaf("Beats", ids.joinToString(" ")).end() }
        return id
    }

    private fun rest(duration: WrittenDuration, key: Triple<Int, Int, Int>): Int {
        val id = beatCount++
        val rhythm = rhythms.id(duration)
        val level = running[key] ?: DynamicLevel.F
        beats += { w -> w.start("Beat", "id" to id).leaf("Dynamic", level.name).start("Rhythm", "ref" to rhythm).end().end() }
        return id
    }

    private fun graces(t: Int, beat: Beat, key: Triple<Int, Int, Int>): List<Int> {
        val lists = beat.notes.map { n -> n to n.articulations.filterIsInstance<Articulation.Grace>() }.filter { it.second.isNotEmpty() }
        if (lists.isEmpty()) return emptyList()
        val et = tracks[t]
        val ids = ArrayList<Int>()
        for (k in 0 until lists.maxOf { it.second.size }) {
            val entries = lists.mapNotNull { (n, g) -> g.getOrNull(k)?.let { n to it } }
            val duration = entries.first().second.duration
            val before = entries.first().second.beforeBeat
            if (entries.any { it.second.duration != duration || it.second.beforeBeat != before }) losses.add("rhythm.grace", "Vorschlagsnoten eines Beats mit unterschiedlicher Dauer/Lage werden vereinheitlicht", beat.id)
            val noteIds = entries.mapNotNull { (n, g) -> graceNote(et, n, g) }
            if (noteIds.isEmpty()) continue
            val id = beatCount++
            val rhythm = rhythms.id(duration)
            val level = running[key] ?: DynamicLevel.F
            beats += { w ->
                w.start("Beat", "id" to id).leaf("Dynamic", level.name).start("Rhythm", "ref" to rhythm).end()
                    .leaf("Notes", noteIds.joinToString(" ")).leaf("GraceNotes", if (before) "BeforeBeat" else "OnBeat").end()
            }
            ids += id
        }
        return ids
    }

    private fun graceNote(et: ExportTrack, main: Note, g: Articulation.Grace): Int? {
        val midi = g.pitch.midiNumber
        val position: Pair<Int, Int>? = if (et.stringed) {
            val string = g.string ?: main.string
            val fret = string?.let { s -> if (s in et.tuning.indices) g.fret ?: et.fretFor(s, midi) else null }
            if (string == null || fret == null || fret !in 0..48 || string !in et.tuning.indices) { losses.add("rhythm.grace", "Vorschlagsnote ohne spielbare Position wird nicht exportiert", main.id); return null }
            if (et.tuning[string] + et.instrument.capoByString[string] + fret != midi) losses.add("rhythm.grace", "Tonhöhe einer Vorschlagsnote widerspricht Saite/Bund; Saite/Bund gelten", main.id)
            string to fret
        } else null
        val id = noteCount++
        val drum = if (et.percussion) et.drumIndex(midi, null) else null
        notes += { w ->
            w.start("Note", "id" to id)
            if (drum != null) w.leaf("InstrumentArticulation", drum)
            w.start("Properties")
            if (position != null) w.prop("String", "String", position.first).prop("Fret", "Fret", position.second)
            if (!et.percussion) w.prop("Midi", "Number", midi)
            // Like regular notes: readers take the pitch of unfretted notes from Octave/Tone (Midi alone read as pitch 0).
            if (!et.percussion && position == null) w.prop("Octave", "Number", midi / 12).prop("Tone", "Step", midi % 12)
            w.end().end()
        }
        return id
    }

    private fun beat(t: Int, s: Int, v: Int, beat: Beat, m: Int, key: Triple<Int, Int, Int>): Int {
        val et = tracks[t]
        val notes = beat.notes
        val level = if (notes.isEmpty()) running[key] ?: DynamicLevel.F else dynamic(beat)
        if (notes.isNotEmpty()) running[key] = level
        val noteIds = notes.mapNotNull { note(et, beat, it) }
        val id = beatCount++
        val rhythm = rhythms.id(beat.duration)
        fun <T> shared(what: String, feature: String, select: (Note) -> T?): T? {
            val values = notes.map(select)
            val first = values.firstOrNull { it != null } ?: return null
            if (values.any { it != first }) losses.add(feature, "$what gilt in GPIF für den ganzen Beat", beat.id)
            return first
        }
        val tremolo = shared("Tremolo-Picking", "fx.tremolo") { n -> n.articulations.filterIsInstance<Articulation.TremoloPicking>().firstOrNull()?.rate }?.let { rate ->
            val text = GpifUnits.tremoloText(rate.denominator)
            if (text == null || rate.dots != 0 || rate.tuplets.isNotEmpty()) losses.add("fx.tremolo", "Tremolo-Rate wird auf 1/8, 1/16 oder 1/32 gerundet", beat.id)
            text ?: GpifUnits.tremoloText(listOf(8, 16, 32).minBy { kotlin.math.abs(it - rate.denominator) })
        }
        val pick = shared("Anschlagsrichtung", "fx.pickstroke") { n -> n.articulations.filterIsInstance<Articulation.Pickstroke>().firstOrNull()?.direction }
        val slap = shared("Slap", "fx.slap_pop") { n -> Articulation.Mark(NoteMark.SLAP) in n.articulations }.takeIf { it == true }
        val pop = shared("Pop", "fx.slap_pop") { n -> Articulation.Mark(NoteMark.POP) in n.articulations }.takeIf { it == true }
        val whammy = shared("Whammy-Kurve", "fx.whammy") { n -> n.articulations.filterIsInstance<Articulation.Whammy>().firstOrNull()?.points }
        val whammyCurve = whammy?.let { curve(it, "Whammy", beat.id) }
        val strum = beat.strum?.takeIf { notes.isNotEmpty() }
        val ticks = strum?.spread?.takeIf { it > Fraction.ZERO }?.let { spread ->
            val exact = spread * Fraction.of(GpifUnits.TICKS_PER_WHOLE)
            if (exact.denominator != 1L) losses.add("fx.strum", "Anschlagsdauer wird auf MIDI-Ticks gerundet", beat.id)
            Math.round(exact.toDouble()).coerceAtLeast(1)
        }
        if (beat.chordId != null) losses.add("layout.chord_symbols", "Akkordsymbole und -diagramme werden nicht exportiert", beat.id)
        val atPosition = if (t == 0 && s == 0) texts[m to beat.offset].orEmpty().filter { it.id !in usedTexts } else emptyList()
        val freeText = atPosition.firstOrNull { it.kind == TextKind.TEXT }
        val lyric = atPosition.firstOrNull { it.kind == TextKind.LYRICS }
        listOfNotNull(freeText, lyric).forEach { usedTexts += it.id }
        val slurOrigin = beat.id in relations.slurOriginBeats
        val slurDestination = beat.id in relations.slurDestinationBeats
        beats += { w ->
            w.start("Beat", "id" to id).leaf("Dynamic", level.name).start("Rhythm", "ref" to rhythm).end()
            if (noteIds.isNotEmpty()) w.leaf("Notes", noteIds.joinToString(" "))
            if (tremolo != null) w.leaf("Tremolo", tremolo)
            if (strum?.kind == StrumKind.ARPEGGIO) w.leaf("Arpeggio", if (strum.direction == StrokeDirection.UP) "Up" else "Down")
            if (slurOrigin || slurDestination) w.start("Legato", "origin" to slurOrigin, "destination" to slurDestination).end()
            if (freeText != null) w.cdataLeaf("FreeText", freeText.text)
            if (lyric != null) w.start("Lyrics").cdataLeaf("Line", lyric.text).end()
            val brush = strum?.takeIf { it.kind != StrumKind.ARPEGGIO }
            if (brush != null || pick != null || slap != null || pop != null || whammyCurve != null) {
                w.start("Properties")
                if (brush != null) w.prop("Brush", "Direction", if (brush.direction == StrokeDirection.UP) "Up" else "Down")
                if (brush?.kind == StrumKind.RASGUEADO) w.prop("Rasgueado", "Rasgueado", "ii_1")
                if (pick != null) w.prop("PickStroke", "Direction", if (pick == StrokeDirection.UP) "Up" else "Down")
                if (slap != null) w.flag("Slapped")
                if (pop != null) w.flag("Popped")
                if (whammyCurve != null) { w.flag("WhammyBar"); whammyCurve.forEach { (name, value) -> w.prop("WhammyBar$name", "Float", GpifNumbers.decimal(value)) } }
                w.end()
            }
            if (ticks != null) w.start("XProperties").start("XProperty", "id" to BarReader.BRUSH_DURATION).leaf("Int", ticks).end().end()
            w.end()
        }
        return id
    }

    /** Beat dynamic: the note's Dynamic mark, otherwise the dynamic level whose velocity is closest. */
    private fun dynamic(beat: Beat): DynamicLevel {
        val marks = beat.notes.map { n -> n.articulations.filterIsInstance<Articulation.Dynamic>().firstOrNull()?.level }
        val explicit = marks.firstOrNull { it != null }
        val level = explicit ?: GpifUnits.levelOf(beat.notes.first().velocity)
        if (beat.notes.any { GpifUnits.velocityOf(level) != it.velocity } && explicit == null) losses.add("fx.dynamics", "Velocity wird auf die nächste Dynamikstufe gerundet", beat.id)
        if (marks.any { it != null && it != explicit } || beat.notes.map { it.velocity }.distinct().size > 1) losses.add("fx.dynamics", "Dynamik/Velocity einzelner Noten eines Beats wird vereinheitlicht", beat.id)
        return level
    }

    /** GPIF bend/whammy curve: origin, optional held middle (offset 1..2) and destination. */
    fun curve(points: List<PitchCurvePoint>, what: String, entity: EntityId): List<Pair<String, Double>>? {
        val p = points.sortedBy { it.position }
        if (p.isEmpty()) return null
        val origin = p.first(); val destination = p.last()
        val middle: Pair<PitchCurvePoint, PitchCurvePoint>? = when {
            p.size <= 2 -> null
            p.size == 3 -> p[1] to p[1]
            p.size == 4 && p[1].cents == p[2].cents -> p[1] to p[2]
            else -> {
                losses.add(if (what == "Bend") "fx.bend" else "fx.whammy", "$what-Kurven mit mehr als vier Punkten werden vereinfacht", entity)
                val extreme = p.subList(1, p.lastIndex).maxBy { kotlin.math.abs(it.cents - origin.cents) }
                extreme to extreme
            }
        }
        val out = ArrayList<Pair<String, Double>>()
        out += "OriginValue" to GpifUnits.valueOf(origin.cents)
        out += "OriginOffset" to GpifUnits.offsetOf(origin.position)
        if (middle != null) {
            out += "MiddleValue" to GpifUnits.valueOf(middle.first.cents)
            out += "MiddleOffset1" to GpifUnits.offsetOf(middle.first.position)
            out += "MiddleOffset2" to GpifUnits.offsetOf(middle.second.position)
        }
        out += "DestinationValue" to GpifUnits.valueOf(destination.cents)
        out += "DestinationOffset" to GpifUnits.offsetOf(destination.position)
        return out
    }

    private fun note(et: ExportTrack, beat: Beat, note: Note): Int? = NoteWriter(et, relations, losses, this).write(note, beat) { writer ->
        val id = noteCount++
        notes += { w -> writer(w, id) }
        id
    }
}
