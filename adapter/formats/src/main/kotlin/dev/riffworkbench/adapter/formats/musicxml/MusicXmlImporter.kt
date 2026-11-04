package dev.riffworkbench.adapter.formats.musicxml

import dev.riffworkbench.adapter.formats.midi.*
import dev.riffworkbench.adapter.formats.xml.XmlElement
import dev.riffworkbench.adapter.formats.xml.XmlReader
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.ports.*
import java.util.TreeMap

/**
 * C-FORMAT: MusicXML import (score-partwise; score-timewise is converted), uncompressed .musicxml/.xml or
 * compressed .mxl (see [MxlContainer]).
 *
 * Parts become tracks. Positions follow divisions, backup and forward exactly; chords share a beat, voices are
 * mapped per staff to the four model voices (voice n -> (n-1) mod 4, collisions by order of appearance). A fretted
 * part with a TAB staff takes tuning, capo and string/fret from it; when its standard staff only duplicates the TAB
 * staff (as written by [MusicXmlExporter], Guitar Pro and others) one model staff with standard + TAB notation is
 * created. Fretted parts without TAB data get positions from [FretAssigner] like the MIDI import. Ties, tuplets
 * (nested via tuplet-actual/normal), dots, rests, grace notes, repeats/endings, key/time, tempo, navigation
 * (segno, coda, D.C., D.S., Fine, To Coda), rehearsal marks and the guitar techniques the exporter writes are read
 * back; everything else is listed as a loss.
 */
class MusicXmlImporter : ScoreImporter {
    override val formatId: String = "musicxml"

    override suspend fun import(bytes: ByteArray, ids: IdSource, progress: (OperationProgress) -> Unit): Outcome<ImportResult> = importNow(bytes, ids, progress)

    fun importNow(bytes: ByteArray, ids: IdSource, progress: (OperationProgress) -> Unit = {}): Outcome<ImportResult> = guarded("MusicXML-Datei") {
        if (bytes.size > FormatLimits.MAX_INPUT_BYTES) resourceLimit("MusicXML-Datei ist größer als 64 MiB")
        val xml = if (MxlContainer.isZip(bytes)) MxlContainer.rootScore(bytes) else bytes
        progress(OperationProgress(1, 3, "XML lesen"))
        val root = XmlReader.parse(xml)
        val score = when (root.name) {
            "score-partwise" -> root
            "score-timewise" -> timewiseToPartwise(root)
            else -> corrupt("Keine MusicXML-Partitur (Wurzelelement <${root.name}>)")
        }
        progress(OperationProgress(2, 3, "Partitur erstellen"))
        val (project, losses) = MusicXmlScoreBuilder(score, ids).build()
        validatedImport(project, losses)
    }

    internal companion object {
        /** score-timewise (measures containing parts) -> score-partwise (parts containing measures). */
        fun timewiseToPartwise(root: XmlElement): XmlElement {
            val header = root.elements.filter { it.name != "measure" }
            val order = ArrayList<String>()
            val measuresByPart = LinkedHashMap<String, MutableList<XmlElement>>()
            for (measure in root.children("measure")) for (part in measure.children("part")) {
                val id = part.attr("id") ?: corrupt("Timewise-Takt ohne Stimmen-ID")
                if (id !in measuresByPart) order += id
                measuresByPart.getOrPut(id) { ArrayList() } += XmlElement("measure", measure.attributes, part.children)
            }
            val parts = order.map { XmlElement("part", mapOf("id" to it), measuresByPart.getValue(it)) }
            return XmlElement("score-partwise", root.attributes, header + parts)
        }
    }
}

private class MusicXmlScoreBuilder(private val score: XmlElement, private val ids: IdSource) {
    private val losses = LossLog("import")
    private var noteCount = 0

    private class Instrument(val id: String, val name: String?, val channel: Int?, val program: Int?, val unpitched: Int?, val volume: Double?, val pan: Double?)
    private class PartInfo(val id: String, val name: String, val instruments: Map<String, Instrument>)
    private class XNote(
        val el: XmlElement, val measure: Int, val start: Fraction, val duration: Fraction, val staff: Int, val voice: String,
        val chord: Boolean, val grace: Boolean, val cue: Boolean, val rest: Boolean, val transpose: Int, val velocity: Int?,
    )
    private class XEvent(val measure: Int, val offset: Fraction, val el: XmlElement)
    private class StaffInfo { var clef: String? = null; var lines: Int? = null; val tuning = TreeMap<Int, Int>(); var capo = 0 }
    private class ParsedPart(val info: PartInfo) {
        val notes = ArrayList<XNote>()
        val events = ArrayList<XEvent>()
        val staves = TreeMap<Int, StaffInfo>()
        var staffCount = 1
        val lengths = ArrayList<Fraction>()
        val times = HashMap<Int, TimeSignature>()
        val keys = HashMap<Int, KeySignature>()
        val barlines = HashMap<Int, MutableList<XmlElement>>()
        val newSystems = HashSet<Int>()
        fun staff(n: Int) = staves.getOrPut(n) { StaffInfo() }
    }

    fun build(): Pair<Project, List<LossEntry>> {
        val infos = partList()
        val partElements = score.children("part")
        if (partElements.isEmpty()) corrupt("MusicXML enthält keine Stimmen (<part>)")
        val version = score.attr("version")
        if (version != null && (version.substringBefore('.').toIntOrNull() ?: 0) > 4) losses.add("version", "MusicXML-Version $version ist neuer als 4.0; unbekannte Elemente werden ignoriert")
        val parts = partElements.mapIndexed { i, el -> parsePart(el, infos[el.attr("id")] ?: PartInfo(el.attr("id") ?: "P${i + 1}", "Spur ${i + 1}", emptyMap())) }
        val count = parts.maxOf { it.lengths.size }
        if (count == 0) corrupt("MusicXML enthält keine Takte")
        var signature = TimeSignature()
        var key = KeySignature()
        val signatures = (0 until count).map { m -> parts.firstNotNullOfOrNull { it.times[m] }?.let { signature = it }; signature }
        val keys = (0 until count).map { m -> parts.firstNotNullOfOrNull { it.keys[m] }?.let { key = it }; key }
        val lengths = (0 until count).map { m -> parts.mapNotNull { it.lengths.getOrNull(m) }.maxOrNull()?.takeIf { it > Fraction.ZERO } ?: signatures[m].length() }
        lengths.forEachIndexed { m, l -> if (l > signatures[m].length()) losses.add("overfull_measure", "Takt ${m + 1} ist länger als seine Taktart und wurde verlängert") }
        val measureIds = List(count) { ids.next() }
        val starts = ArrayList<Fraction>(count + 1).apply { add(Fraction.ZERO); lengths.forEach { add(last() + it) } }

        val global = GlobalEvents(parts, count, lengths, measureIds)
        val repeats = repeats(parts, count)
        val measures = (0 until count).map { m ->
            Measure(measureIds[m], signatures[m], keys[m], pickupLength = lengths[m].takeIf { it != signatures[m].length() },
                repeat = repeats[m], navigation = global.navigation[m], feel = global.feels[m], forcedSystemBreak = parts.any { m in it.newSystems })
        }
        val tracks = parts.mapIndexed { i, p -> TrackBuilder(p, i, measures, lengths, starts).build() }
        val meta = metadata()
        val project = Project(id = ids.next(), metadata = meta, measures = measures, tracks = tracks, sections = global.sections,
            tempo = global.tempo.ifEmpty { listOf(TempoChange(ScorePosition(measureIds.first()), 120.0)) }, text = global.texts)
        return project to losses.toList()
    }

    // ---- header -------------------------------------------------------------------------------------------------

    private fun metadata(): ProjectMetadata {
        val identification = score.child("identification")
        val creators = identification?.children("creator").orEmpty()
        fun creator(type: String) = creators.firstOrNull { it.attr("type") == type }?.text.orEmpty()
        val misc = identification?.child("miscellaneous")?.children("miscellaneous-field").orEmpty()
        fun field(name: String) = misc.firstOrNull { it.attr("name") == name }?.text.orEmpty()
        val title = score.textOf("work", "work-title")?.takeIf { it.isNotBlank() } ?: score.textOf("movement-title")?.takeIf { it.isNotBlank() } ?: "MusicXML-Import"
        if (score.children("credit").isNotEmpty()) losses.add("credits", "Seitentexte (credit) werden nicht übernommen")
        if (score.child("defaults") != null) losses.add("layout", "Layout-Vorgaben (defaults) werden nicht übernommen")
        return ProjectMetadata(title = title.take(200), artist = creator("artist").ifBlank { creator("lyricist") }.take(200), album = field("album").take(200),
            composer = creator("composer").take(200), copyright = identification?.children("rights")?.joinToString(" ") { it.text }.orEmpty().take(500), notes = field("notes").take(2000))
    }

    private fun partList(): Map<String, PartInfo> {
        val list = score.child("part-list") ?: corrupt("MusicXML ohne part-list")
        return list.children("score-part").associate { sp ->
            val id = sp.attr("id") ?: corrupt("score-part ohne ID")
            val names = sp.children("score-instrument").associate { (it.attr("id") ?: "") to it.textOf("instrument-name") }
            val midi = sp.children("midi-instrument").associateBy { it.attr("id") ?: "" }
            val instrumentIds = (names.keys + midi.keys).filter { it.isNotEmpty() }.distinct()
            val instruments = instrumentIds.associateWith { iid ->
                val mi = midi[iid]
                Instrument(iid, names[iid], mi?.textOf("midi-channel")?.toIntOrNull(), mi?.textOf("midi-program")?.toIntOrNull()?.minus(1),
                    mi?.textOf("midi-unpitched")?.toIntOrNull()?.minus(1), parseDecimal(mi?.textOf("volume"))?.toDouble(), parseDecimal(mi?.textOf("pan"))?.toDouble())
            }
            id to PartInfo(id, sp.textOf("part-name")?.trim().orEmpty(), instruments)
        }
    }

    // ---- part parsing -------------------------------------------------------------------------------------------

    private fun duration(text: String?, divisions: Fraction): Fraction {
        val d = parseDecimal(text) ?: corrupt("Ungültige Dauer: $text")
        if (d < Fraction.ZERO) corrupt("Negative Dauer")
        return d / (divisions * Fraction.of(4))
    }

    private fun parsePart(el: XmlElement, info: PartInfo): ParsedPart {
        val p = ParsedPart(info)
        var divisions = Fraction.ONE
        var transpose = 0
        var velocity: Int? = null
        val maxLength = Fraction.of(64)
        for ((mi, measure) in el.children("measure").withIndex()) {
            if (mi >= FormatLimits.MAX_MEASURES) resourceLimit("MusicXML enthält mehr als ${FormatLimits.MAX_MEASURES} Takte")
            var pos = Fraction.ZERO
            var maxPos = Fraction.ZERO
            var lastStart = Fraction.ZERO
            for (child in measure.elements) {
                when (child.name) {
                    "attributes" -> {
                        child.child("divisions")?.let { d -> divisions = parseDecimal(d.text)?.takeIf { it > Fraction.ZERO } ?: corrupt("Ungültige divisions: ${d.text}") }
                        child.child("key")?.let { k -> key(k)?.let { p.keys[mi] = it } }
                        child.child("time")?.let { t -> time(t)?.let { p.times[mi] = it } }
                        child.child("staves")?.text?.toIntOrNull()?.let { p.staffCount = maxOf(p.staffCount, it.coerceIn(1, 16)) }
                        child.children("clef").forEach { c -> p.staff(c.attr("number")?.toIntOrNull() ?: 1).clef = c.textOf("sign") }
                        child.children("staff-details").forEach { sd ->
                            val s = p.staff(sd.attr("number")?.toIntOrNull() ?: 1)
                            sd.textOf("staff-lines")?.toIntOrNull()?.let { s.lines = it }
                            sd.children("staff-tuning").forEach { st ->
                                val line = st.attr("line")?.toIntOrNull() ?: return@forEach
                                val step = st.textOf("tuning-step")?.firstOrNull()?.takeIf { it in "CDEFGAB" } ?: return@forEach
                                val alter = parseDecimal(st.textOf("tuning-alter"))?.toDouble()?.let { Math.round(it).toInt() } ?: 0
                                val octave = st.textOf("tuning-octave")?.toIntOrNull() ?: return@forEach
                                s.tuning[line] = PitchSpelling.midi(step, alter, octave)
                            }
                            sd.textOf("capo")?.toIntOrNull()?.let { s.capo = it.coerceIn(0, 36) }
                        }
                        child.child("transpose")?.let { t -> transpose = (t.textOf("chromatic")?.toIntOrNull() ?: 0) + 12 * (t.textOf("octave-change")?.toIntOrNull() ?: 0) }
                        if (child.child("measure-style") != null) losses.add("measure_style", "Taktstile (Mehrtaktpausen, Slash-Notation) werden nicht übernommen")
                    }
                    "note" -> {
                        if (++noteCount > FormatLimits.MAX_NOTES) resourceLimit("MusicXML enthält mehr als ${FormatLimits.MAX_NOTES} Noten")
                        val grace = child.child("grace") != null
                        val chord = child.child("chord") != null
                        val dur = if (grace) Fraction.ZERO else duration(child.textOf("duration"), divisions)
                        val start = if (chord) lastStart else pos
                        if (!chord && !grace) { lastStart = pos; pos += dur }
                        maxPos = maxF(maxPos, maxF(pos, start + dur))
                        val noteVelocity = parseDecimal(child.attr("dynamics"))?.toDouble()?.let { Math.round(it * 0.9).toInt().coerceIn(1, 127) } ?: velocity
                        p.notes += XNote(child, mi, start, dur, child.textOf("staff")?.toIntOrNull()?.coerceIn(1, 16) ?: 1, child.textOf("voice") ?: "1",
                            chord, grace, child.child("cue") != null, child.child("rest") != null, transpose, noteVelocity)
                    }
                    "backup" -> pos = maxF(Fraction.ZERO, pos - duration(child.textOf("duration"), divisions))
                    "forward" -> { pos += duration(child.textOf("duration"), divisions); maxPos = maxF(maxPos, pos) }
                    "direction" -> {
                        val offsetEl = child.child("offset")
                        val offset = if (offsetEl != null && offsetEl.attr("sound") == "yes") duration(offsetEl.text, divisions).let { maxF(Fraction.ZERO, pos + it) } else pos
                        p.events += XEvent(mi, offset, child)
                        child.children("direction-type").flatMap { it.children("dynamics") }.firstOrNull()?.let { d -> dynamicLevel(d)?.let { velocity = ScorePlaybackCompiler.dynamicVelocity(it) } }
                    }
                    "sound" -> p.events += XEvent(mi, pos, child)
                    "barline" -> p.barlines.getOrPut(mi) { ArrayList() } += child
                    "print" -> if (child.attr("new-system") == "yes" || child.attr("new-page") == "yes") p.newSystems += mi
                    "harmony" -> losses.add("chord_symbols", "Akkordsymbole (harmony) werden nicht übernommen")
                    "figured-bass" -> losses.add("figured_bass", "Generalbassbezifferung wird nicht übernommen")
                    else -> Unit
                }
                if (pos > maxLength || maxPos > maxLength) corrupt("Takt ${mi + 1} ist unplausibel lang")
            }
            p.lengths += maxPos
        }
        p.staffCount = maxOf(p.staffCount, p.notes.maxOfOrNull { it.staff } ?: 1)
        return p
    }

    private fun key(k: XmlElement): KeySignature? {
        val fifths = k.textOf("fifths")?.toIntOrNull()
        if (fifths == null) { losses.add("key_signature", "Nicht-traditionelle Vorzeichen werden nicht übernommen"); return null }
        return KeySignature(fifths.coerceIn(-7, 7), k.textOf("mode") == "minor")
    }

    private fun time(t: XmlElement): TimeSignature? {
        if (t.child("senza-misura") != null) { losses.add("senza_misura", "Senza misura wird als 4/4 gelesen"); return null }
        val beats = t.children("beats").map { b -> b.text.split('+').sumOf { it.trim().toIntOrNull() ?: 0 } }
        val types = t.children("beat-type").mapNotNull { it.text.trim().toIntOrNull() }
        if (beats.isEmpty() || types.isEmpty() || beats.size != types.size) return null
        val denominator = types.max()
        if (denominator !in 1..64 || denominator.countOneBits() != 1 || types.any { denominator % it != 0 }) { losses.add("time_signature", "Taktart ${t.textOf("beats")}/${t.textOf("beat-type")} ist nicht darstellbar"); return null }
        val numerator = beats.indices.sumOf { beats[it] * (denominator / types[it]) }
        if (beats.size > 1) losses.add("composite_time", "Zusammengesetzte Taktarten werden als Summe gelesen")
        if (numerator !in 1..64) { losses.add("time_signature", "Taktart mit $numerator Schlägen ist nicht darstellbar"); return null }
        return TimeSignature(numerator, denominator)
    }

    private fun dynamicLevel(d: XmlElement): DynamicLevel? = d.elements.firstNotNullOfOrNull { e -> DynamicLevel.entries.firstOrNull { it.name.equals(e.name, ignoreCase = true) } }

    // ---- repeats and score-wide directions ----------------------------------------------------------------------

    private fun repeats(parts: List<ParsedPart>, count: Int): List<RepeatMark> {
        val starts = BooleanArray(count)
        val ends = arrayOfNulls<Int>(count)
        val endings = arrayOfNulls<Set<Int>>(count)
        var open: Set<Int>? = null
        for (m in 0 until count) {
            val barlines = parts.flatMap { it.barlines[m].orEmpty() }
            for (b in barlines) {
                val repeat = b.child("repeat")
                when (repeat?.attr("direction")) {
                    "forward" -> starts[m] = true
                    "backward" -> {
                        val times = repeat.attr("times")?.toIntOrNull()?.coerceIn(2, 64) ?: 2
                        if (b.attr("location") == "left" && m > 0) ends[m - 1] = times else ends[m] = times
                    }
                }
            }
            val endingElements = barlines.mapNotNull { it.child("ending") }
            endingElements.firstOrNull { it.attr("type") == "start" }?.let { e ->
                val numbers = (e.attr("number") ?: "").split(',', ' ').flatMap { token ->
                    val t = token.trim().removeSuffix(".")
                    val range = t.split('-').mapNotNull { it.toIntOrNull() }
                    if (range.size == 2) (range[0]..range[1]).toList() else listOfNotNull(t.toIntOrNull())
                }.filter { it in 1..64 }.toSet()
                open = numbers.ifEmpty { null }
            }
            endings[m] = open
            if (endingElements.any { it.attr("type") == "stop" || it.attr("type") == "discontinue" }) open = null
        }
        return (0 until count).map { RepeatMark(starts[it], ends[it], endings[it].orEmpty()) }
    }

    private inner class GlobalEvents(parts: List<ParsedPart>, count: Int, lengths: List<Fraction>, measureIds: List<EntityId>) {
        val navigation: List<MutableList<NavigationMark>> = List(count) { ArrayList() }
        val feels = ArrayList<RhythmicFeel>()
        val tempo = ArrayList<TempoChange>()
        val sections = ArrayList<Section>()
        val texts = ArrayList<ScoreText>()

        init {
            val segnos = HashMap<String, Int>()
            val codas = HashMap<String, Int>()
            val pendingDs = ArrayList<Pair<Int, String?>>()
            val pendingToCoda = ArrayList<Pair<Int, String?>>()
            val kinds = Array(count) { LinkedHashSet<NavigationKind>() }
            val feelChanges = TreeMap<Int, RhythmicFeel>()
            val tempoAt = LinkedHashMap<Pair<Int, Fraction>, Double>()
            val sectionKeys = LinkedHashSet<Pair<Int, String>>()
            val textKeys = LinkedHashSet<Triple<Int, Fraction, String>>()
            for (part in parts) for (event in part.events) {
                val m = event.measure
                val offset = minF(event.offset, lengths[m])
                val sound = if (event.el.name == "sound") event.el else event.el.child("sound")
                var navigationFromSound = false
                if (sound != null) {
                    parseDecimal(sound.attr("tempo"))?.toDouble()?.let { tempoAt.putIfAbsent(m to offset, it) }
                    if (sound.attr("dacapo") == "yes") { kinds[m] += NavigationKind.DA_CAPO; navigationFromSound = true }
                    sound.attr("segno")?.let { segnos.putIfAbsent(it, m); kinds[m] += NavigationKind.SEGNO; navigationFromSound = true }
                    sound.attr("coda")?.let { codas.putIfAbsent(it, m); kinds[m] += NavigationKind.CODA; navigationFromSound = true }
                    sound.attr("dalsegno")?.let { pendingDs += m to it; navigationFromSound = true }
                    sound.attr("tocoda")?.let { pendingToCoda += m to it; navigationFromSound = true }
                    if (sound.attr("fine") != null) { kinds[m] += NavigationKind.FINE; navigationFromSound = true }
                    sound.child("swing")?.let { swing ->
                        val type = swing.textOf("swing-type")
                        val words = event.el.children("direction-type").flatMap { it.children("words") }.joinToString(" ") { it.text }
                        feelChanges[m] = when {
                            swing.child("straight") != null -> RhythmicFeel.STRAIGHT
                            words.contains("triplet", ignoreCase = true) -> RhythmicFeel.TRIPLET
                            type == "16th" -> RhythmicFeel.SWING_SIXTEENTH
                            else -> RhythmicFeel.SWING_EIGHTH
                        }
                        if (swing.child("straight") == null && (swing.textOf("first") != "2" || swing.textOf("second") != "1")) losses.add("swing_ratio", "Swing-Verhältnisse außer 2:1 werden als 2:1 gelesen")
                    }
                }
                if (event.el.name != "direction") continue
                for (type in event.el.children("direction-type")) for (d in type.elements) when (d.name) {
                    "rehearsal" -> d.text.takeIf { it.isNotBlank() }?.let { sectionKeys += m to it.take(80) }
                    "segno" -> kinds[m] += NavigationKind.SEGNO
                    "coda" -> kinds[m] += NavigationKind.CODA
                    "metronome" -> if (sound?.attr("tempo") == null) metronome(d)?.let { tempoAt.putIfAbsent(m to offset, it) }
                    "words" -> {
                        val text = d.text
                        val nav = wordsNavigation(text)
                        when {
                            nav != null && !navigationFromSound -> { kinds[m] += nav; if (nav == NavigationKind.DAL_SEGNO) pendingDs += m to null; if (nav == NavigationKind.TO_CODA) pendingToCoda += m to null }
                            nav != null || sound?.child("swing") != null -> Unit
                            text.isNotBlank() -> textKeys += Triple(m, offset, text.take(500))
                        }
                    }
                    "dynamics", "octave-shift", "other-direction" -> Unit
                    else -> losses.add("direction_${d.name}", "Vortragsangabe <${d.name}> wird nicht übernommen")
                }
            }
            val segnoFallback = (0 until count).firstOrNull { NavigationKind.SEGNO in kinds[it] }
            val codaFallback = (0 until count).firstOrNull { NavigationKind.CODA in kinds[it] }
            val targets = HashMap<Pair<Int, NavigationKind>, EntityId?>()
            pendingDs.forEach { (m, name) -> kinds[m] += NavigationKind.DAL_SEGNO; targets[m to NavigationKind.DAL_SEGNO] = (name?.let { segnos[it] } ?: segnoFallback)?.let { measureIds[it] } }
            pendingToCoda.forEach { (m, name) -> kinds[m] += NavigationKind.TO_CODA; targets[m to NavigationKind.TO_CODA] = (name?.let { codas[it] } ?: codaFallback)?.let { measureIds[it] } }
            for (m in 0 until count) navigation[m].addAll(kinds[m].map { NavigationMark(it, targets[m to it]) })
            var feel = RhythmicFeel.STRAIGHT
            for (m in 0 until count) { feelChanges[m]?.let { feel = it }; feels += feel }
            var last: Double? = null
            tempoAt.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).forEach { (pos, qpm) ->
                var q = qpm
                if (!q.isFinite() || q !in 10.0..1000.0) { losses.add("tempo_range", "Tempo außerhalb von 10–1000 BPM wurde begrenzt"); q = if (q.isFinite()) q.coerceIn(10.0, 1000.0) else 120.0 }
                if (q != last) { tempo += TempoChange(ScorePosition(measureIds[pos.first], pos.second), q); last = q }
            }
            if (tempo.isNotEmpty() && (tempo.first().position.measureId != measureIds.first() || tempo.first().position.offset != Fraction.ZERO))
                tempo.add(0, TempoChange(ScorePosition(measureIds.first()), 120.0))
            sectionKeys.forEach { (m, label) -> sections += Section(ids.next(), measureIds[m], label) }
            textKeys.forEach { (m, offset, text) -> texts += ScoreText(ids.next(), ScorePosition(measureIds[m], offset), text) }
        }

        private fun metronome(d: XmlElement): Double? {
            val perMinute = Regex("[0-9]+(?:\\.[0-9]+)?").find(d.textOf("per-minute") ?: return null)?.value?.toDoubleOrNull() ?: return null
            val unit = noteLength(d.textOf("beat-unit") ?: "quarter") ?: return null
            var length = unit
            var dot = unit
            repeat(d.children("beat-unit-dot").size.coerceAtMost(3)) { dot *= Fraction.of(1, 2); length += dot }
            return perMinute * (length / QUARTER).toDouble()
        }

        private fun wordsNavigation(text: String): NavigationKind? {
            val t = text.trim()
            return when {
                Regex("^(D\\.\\s?C\\.|Da Capo)", RegexOption.IGNORE_CASE).containsMatchIn(t) -> NavigationKind.DA_CAPO
                Regex("^(D\\.\\s?S\\.|Dal Segno)", RegexOption.IGNORE_CASE).containsMatchIn(t) -> NavigationKind.DAL_SEGNO
                Regex("^To Coda", RegexOption.IGNORE_CASE).containsMatchIn(t) -> NavigationKind.TO_CODA
                t.equals("Fine", ignoreCase = true) -> NavigationKind.FINE
                else -> null
            }
        }
    }

    private fun noteLength(type: String): Fraction? = typeDenominator(type)?.let { Fraction.of(1, it.toLong()) }
    private fun typeDenominator(type: String?): Int? = when (type?.trim()) {
        "whole" -> 1; "half" -> 2; "quarter" -> 4; "eighth" -> 8; "16th" -> 16; "32nd" -> 32; "64th" -> 64
        "128th" -> 128; "256th" -> 256; "512th" -> 512; "1024th" -> 1024; else -> null
    }

    // ---- tracks -------------------------------------------------------------------------------------------------

    private inner class TrackBuilder(val part: ParsedPart, val index: Int, val measures: List<Measure>, val lengths: List<Fraction>, val starts: List<Fraction>) {
        val info = part.info
        val trackId = ids.next()
        val firstInstrument = info.instruments.values.firstOrNull()
        val program = firstInstrument?.program?.takeIf { it in 0..127 }
        val percussion = part.staves.values.any { it.clef == "percussion" } || part.notes.any { it.el.child("unpitched") != null } ||
            info.instruments.values.any { it.channel == 10 || it.unpitched != null && part.notes.none { n -> n.el.child("pitch") != null } }
        val tabStaves = part.staves.filter { (_, s) -> s.clef == "TAB" || s.tuning.isNotEmpty() }.keys
        val lowerName = info.name.lowercase()

        /** Per built note: what is needed to resolve ties and slurs afterwards. */
        inner class Built(val x: XNote, val noteId: EntityId, val absStart: Fraction, val absEnd: Fraction, val voice: Int, var note: Note)

        fun build(): Track {
            val name = info.name.ifBlank { firstInstrument?.name.orEmpty() }.ifBlank { "Spur ${index + 1}" }.take(120)
            val instrument = instrument()
            val fretted = instrument.tuning.isNotEmpty()
            if (part.notes.any { it.cue }) losses.add("cue_notes", "Stichnoten werden nicht übernommen", trackId)
            if (!percussion && info.instruments.size > 1) losses.add("multiple_instruments", "Mehrere Instrumente in einer Stimme: es gilt das erste", trackId)
            val staffGroups = staffGroups(fretted)
            val staves = staffGroups.map { (numbers, notation) -> staff(numbers, notation, instrument) }
            val volume = firstInstrument?.volume?.let { (it / 100.0).coerceIn(0.0, 1.0) } ?: ChannelMix().volume
            val pan = firstInstrument?.pan?.let { (it / 90.0).coerceIn(-1.0, 1.0) } ?: 0.0
            return Track(trackId, name, instrument, staves, mix = ChannelMix(volume = Math.round(volume * 100) / 100.0, pan = Math.round(pan * 100) / 100.0))
        }

        private fun pitches(): List<Int> = part.notes.mapNotNull { n -> if (n.rest || n.cue) null else pitchOf(n)?.first }

        private fun instrument(): InstrumentDefinition {
            if (percussion) return DefaultInstruments.unfretted(InstrumentKind.PERCUSSION, program ?: 0)
            val tab = tabStaves.firstOrNull()?.let { part.staves.getValue(it) }
            if (tab != null) {
                var tuning = tab.tuning.values.toList()
                val lines = tab.lines ?: tuning.size
                if (tuning.size != lines || tuning.size !in 3..10 || tuning.any { it !in 0..127 }) {
                    if (tuning.isNotEmpty()) losses.add("tab_tuning", "TAB-Stimmung ist unvollständig; Standardstimmung angenommen", trackId)
                    tuning = when (lines) { 4 -> DefaultInstruments.BASS_STANDARD; 5 -> listOf(23) + DefaultInstruments.BASS_STANDARD; 7 -> listOf(35) + DefaultInstruments.GUITAR_STANDARD; 8 -> listOf(30, 35) + DefaultInstruments.GUITAR_STANDARD; else -> DefaultInstruments.GUITAR_STANDARD }
                }
                val bass = program in 32..39 || "bass" in lowerName || (tuning.size <= 5 && tuning.first() < 36)
                val maxFret = maxOf(24, part.notes.mapNotNull { it.el.path("notations", "technical", "fret")?.text?.toIntOrNull() }.maxOrNull() ?: 0).coerceAtMost(48)
                return InstrumentDefinition(if (bass) InstrumentKind.BASS else InstrumentKind.GUITAR, tuning, List(tuning.size) { tab.capo }, maxFret,
                    midiProgram = program ?: if (bass) 33 else 25)
            }
            val kind = when {
                program != null -> DefaultInstruments.kindForProgram(program)
                "bass" in lowerName -> InstrumentKind.BASS
                "guitar" in lowerName || "gitarre" in lowerName -> InstrumentKind.GUITAR
                "piano" in lowerName || "klavier" in lowerName -> InstrumentKind.PIANO
                else -> InstrumentKind.OTHER
            }
            val lowest = pitches().minOrNull()
            return when (kind) {
                InstrumentKind.GUITAR -> DefaultInstruments.guitar(lowest, program ?: 25)
                InstrumentKind.BASS -> DefaultInstruments.bass(lowest, program ?: 33)
                else -> DefaultInstruments.unfretted(kind, program ?: if (kind == InstrumentKind.PIANO) 0 else 48)
            }
        }

        /** MusicXML staff numbers per model staff with their notation. */
        private fun staffGroups(fretted: Boolean): List<Pair<List<Int>, Set<NotationKind>>> {
            val numbers = (1..part.staffCount).filter { n -> n in part.staves || part.notes.any { it.staff == n } || n == 1 }
            if (percussion) return numbers.map { listOf(it) to setOf(NotationKind.PERCUSSION) }
            val tab = numbers.filter { it in tabStaves }
            val standard = numbers.filter { it !in tabStaves }
            if (fretted && tab.size == 1 && standard.size == 1 && duplicates(standard.single(), tab.single()))
                return listOf(listOf(tab.single()) to setOf(NotationKind.STANDARD, NotationKind.TAB))
            return numbers.map { listOf(it) to if (it in tabStaves) setOf(NotationKind.TAB) else setOf(NotationKind.STANDARD) }
        }

        private fun duplicates(a: Int, b: Int): Boolean {
            fun signature(staff: Int) = part.notes.filter { it.staff == staff && !it.grace && !it.cue && !it.rest }.map { Triple(it.measure, it.start, pitchOf(it)?.first) }.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            return signature(a) == signature(b)
        }

        /** MIDI pitch, cents and stored spelling of a pitched or unpitched note. */
        fun pitchOf(n: XNote): Triple<Int, Int, String?>? {
            n.el.child("pitch")?.let { p ->
                val step = p.textOf("step")?.firstOrNull()?.takeIf { it in "CDEFGAB" } ?: return null
                val octave = p.textOf("octave")?.toIntOrNull() ?: return null
                val alter = parseDecimal(p.textOf("alter"))?.toDouble() ?: 0.0
                val whole = if (Math.abs(alter - Math.rint(alter)) < 1e-9) Math.rint(alter).toInt() else if (Math.abs(alter - alter.toInt()) == 0.5) alter.toInt() else Math.round(alter).toInt()
                val cents = Math.round((alter - whole) * 100).toInt()
                val midi = PitchSpelling.midi(step, whole, octave) + n.transpose
                val spelling = if (n.transpose == 0 && cents == 0 && whole in -2..2) Spelled(step, whole, octave).text() else null
                return Triple(midi, cents, spelling)
            }
            n.el.child("unpitched")?.let { u ->
                val instrument = n.el.child("instrument")?.attr("id")?.let { info.instruments[it] } ?: info.instruments.values.singleOrNull()
                instrument?.unpitched?.let { return Triple(it, 0, null) }
                val step = u.textOf("display-step")?.firstOrNull()
                val octave = u.textOf("display-octave")?.toIntOrNull()
                val midi = if (step != null && octave != null) DrumNotation.midiFor(step, octave, n.el.textOf("notehead")) else null
                if (midi == null) losses.add("unpitched", "Schlagzeugnoten ohne Instrumentzuordnung wurden als Snare gelesen", trackId)
                return Triple(midi ?: 38, 0, null)
            }
            return null
        }

        private inner class BeatBuild(val first: XNote, val start: Fraction, val written: WrittenDuration, val length: Fraction, val rest: Boolean, val tuplets: List<TupletRatio>, val groups: List<EntityId>) {
            val notes = ArrayList<XNote>()
            val graces = ArrayList<XNote>()
            var vibrato = false
        }

        private fun staff(numbers: List<Int>, notation: Set<NotationKind>, instrument: InstrumentDefinition): Staff {
            val staffId = ids.next()
            val notes = part.notes.filter { it.staff in numbers && !it.cue }
            // Voice mapping: voice n -> (n-1) mod 4, collisions by order of appearance.
            val voiceIndex = HashMap<String, Int>()
            val used = HashSet<Int>()
            for (n in notes) if (n.voice !in voiceIndex) {
                val preferred = n.voice.toIntOrNull()?.takeIf { it > 0 }?.let { (it - 1) % 4 }
                val slot = if (preferred != null && preferred !in used) preferred else (0..3).firstOrNull { it !in used }
                if (slot == null) { losses.add("voice_limit", "Mehr als vier Stimmen pro System: weitere Stimmen werden zusammengelegt", trackId); voiceIndex[n.voice] = 3 }
                else { voiceIndex[n.voice] = slot; used += slot }
            }
            // Beats per (staff, voice) in document order.
            val beatsByVoice = HashMap<Int, MutableList<BeatBuild>>()
            for ((key, voiceNotes) in notes.groupBy { it.staff to it.voice }) {
                val v = voiceIndex.getValue(key.second)
                val list = beatsByVoice.getOrPut(v) { ArrayList() }
                var current: BeatBuild? = null
                val pendingGraces = ArrayList<XNote>()
                val openTuplets = TreeMap<Int, Pair<EntityId, TupletRatio?>>()
                var vibratoOpen = false
                for (n in voiceNotes) {
                    if (n.grace) { if (!n.rest) pendingGraces += n; continue }
                    if (n.chord && current != null && current.start == n.start && current.first.measure == n.measure && !current.rest && !n.rest) {
                        current.notes += n
                        if (hasWavy(n, "start")) current.vibrato = true
                        continue
                    }
                    // Tuplet starts on this note open new groups before the beat is created.
                    val tupletElements = n.el.children("notations").flatMap { it.children("tuplet") }
                    for (t in tupletElements.filter { it.attr("type") == "start" }) {
                        val number = t.attr("number")?.toIntOrNull() ?: 1
                        val actual = t.path("tuplet-actual", "tuplet-number")?.text?.toIntOrNull()
                        val normal = t.path("tuplet-normal", "tuplet-number")?.text?.toIntOrNull()
                        openTuplets[number] = ids.next() to if (actual != null && normal != null && actual > 0 && normal > 0) TupletRatio(actual, normal) else null
                    }
                    val beat = newBeat(n, openTuplets)
                    if (beat == null) {
                        tupletElements.filter { it.attr("type") == "stop" }.forEach { openTuplets.remove(it.attr("number")?.toIntOrNull() ?: 1) }
                        continue
                    }
                    if (!beat.rest) {
                        beat.notes += n
                        beat.graces += pendingGraces
                        pendingGraces.clear()
                        if (hasWavy(n, "start")) vibratoOpen = true
                        beat.vibrato = vibratoOpen
                        if (hasWavy(n, "stop")) vibratoOpen = false
                    } else if (pendingGraces.isNotEmpty()) {
                        losses.add("grace_without_note", "Vorschlagsnoten vor Pausen werden verworfen", trackId); pendingGraces.clear()
                    }
                    list += beat
                    current = beat
                    tupletElements.filter { it.attr("type") == "stop" }.forEach { openTuplets.remove(it.attr("number")?.toIntOrNull() ?: 1) }
                }
                if (pendingGraces.isNotEmpty()) losses.add("grace_without_note", "Vorschlagsnoten ohne Hauptnote werden verworfen", trackId)
            }
            // Place beats into voice slots per measure; overlapping beats move to a free voice or are dropped.
            val slots = Array(measures.size) { Array(4) { ArrayList<BeatBuild>() } }
            for (v in 0..3) for (b in beatsByVoice[v].orEmpty().sortedWith(compareBy({ it.first.measure }, { it.start }))) {
                val m = b.first.measure
                if (m >= measures.size) continue
                fun fits(slot: Int) = slots[m][slot].none { o -> o.start < b.start + b.length && b.start < o.start + o.length }
                val target = if (fits(v)) v else (0..3).firstOrNull { fits(it) }
                if (target == null) { losses.add("voice_overlap", "Überlappende Noten einer Stimme wurden verworfen", trackId); continue }
                if (target != v) losses.add("voice_overlap", "Überlappende Noten einer Stimme wurden in eine freie Stimme verschoben", trackId)
                slots[m][target] += b
            }
            val built = ArrayList<Built>()
            val bars = measures.indices.map { m ->
                val voices = (0..3).map { v ->
                    val beats = slots[m][v].sortedBy { it.start }.mapNotNull { b -> modelBeat(b, m, v, instrument, built) }
                    Voice(ids.next(), v, beats)
                }
                Bar(ids.next(), measures[m].id, voices)
            }
            val withPositions = if (instrument.tuning.isNotEmpty()) assignPositions(bars, instrument, built, numbers.any { it in tabStaves }) else bars
            return Staff(staffId, notation, resolveLinks(withPositions, built))
        }

        private fun hasWavy(n: XNote, type: String) = n.el.children("notations").flatMap { it.children("ornaments") }.flatMap { it.children("wavy-line") }.any { it.attr("type") == type }

        private fun newBeat(n: XNote, openTuplets: TreeMap<Int, Pair<EntityId, TupletRatio?>>): BeatBuild? {
            val measureLength = lengths.getOrNull(n.measure) ?: return null
            val printed = n.el.attr("print-object") != "no"
            if (n.rest) {
                val restEl = n.el.child("rest")!!
                val type = n.el.textOf("type")
                if (!printed) return null
                if ((restEl.attr("measure") == "yes" || type == null || type == "whole") && n.start == Fraction.ZERO && n.duration >= measureLength) return null
            }
            if (n.duration <= Fraction.ZERO) { losses.add("zero_duration", "Noten ohne Dauer werden verworfen", trackId); return null }
            val denominator = typeDenominator(n.el.textOf("type"))
            val dots = n.el.children("dot").size.coerceAtMost(4)
            val tm = n.el.child("time-modification")
            val actualNotes = tm?.textOf("actual-notes")?.toIntOrNull()
            val normalNotes = tm?.textOf("normal-notes")?.toIntOrNull()
            val combined = if (actualNotes != null && normalNotes != null && actualNotes > 0 && normalNotes > 0 && actualNotes != normalNotes) TupletRatio(actualNotes, normalNotes) else null
            val levels = openTuplets.values.toList()
            val explicit = levels.mapNotNull { it.second }
            val (tuplets, groups) = when {
                combined == null && levels.isEmpty() -> emptyList<TupletRatio>() to emptyList()
                levels.isNotEmpty() && explicit.size == levels.size && combined != null &&
                    explicit.fold(1L) { a, r -> a * r.actual } * combined.normal == explicit.fold(1L) { a, r -> a * r.normal } * combined.actual -> explicit to levels.map { it.first }
                combined != null && levels.size == 1 -> listOf(combined) to listOf(levels.single().first)
                combined != null -> listOf(combined) to emptyList()
                else -> emptyList<TupletRatio>() to emptyList()
            }
            var written = denominator?.let { runCatching { WrittenDuration(it, dots, tuplets) }.getOrNull() }
            val length = minF(n.duration, measureLength - n.start)
            if (written == null || written.length() != length) {
                if (written != null) losses.add("duration_mismatch", "Notenwert und Dauer widersprechen sich; die Dauer wurde übernommen", trackId)
                written = Rhythm.single(length) ?: Rhythm.exact(length)
                return BeatBuild(n, n.start, written, length, n.rest, written.tuplets, if (written.tuplets.isEmpty()) emptyList() else groups.takeIf { it.size == written.tuplets.size }.orEmpty())
            }
            return BeatBuild(n, n.start, written, length, n.rest, tuplets, groups)
        }

        private fun modelBeat(b: BeatBuild, m: Int, voice: Int, instrument: InstrumentDefinition, built: MutableList<Built>): Beat? {
            val beatId = ids.next()
            if (b.rest) return Beat(beatId, b.start, b.written, explicitRest = true, tupletGroupIds = b.groups, fermata = fermata(b.first))
            val notes = ArrayList<Note>()
            val seen = HashSet<Pair<Int, Int>>()
            var strum: Strum? = null
            var fermata: Fermata? = null
            for (x in b.notes) {
                val (midi, cents, spelling) = pitchOf(x) ?: continue
                if (midi !in 0..127) { losses.add("pitch_range", "Tonhöhen außerhalb von MIDI 0–127 wurden verworfen", trackId); continue }
                if (!seen.add(midi to cents) && instrument.tuning.isEmpty()) continue
                val parsed = techniques(x, instrument, b.vibrato)
                val note = Note(ids.next(), Pitch(midi, cents, spelling), parsed.string, parsed.fret, x.velocity ?: 96, parsed.articulations, fingering = parsed.fingering)
                notes += note
                built += Built(x, note.id, starts[m] + b.start, starts[m] + b.start + b.length, voice, note)
                if (strum == null) strum = arpeggiate(x)
                if (fermata == null) fermata = fermata(x)
            }
            if (notes.isEmpty()) return Beat(beatId, b.start, b.written, explicitRest = true, tupletGroupIds = b.groups)
            attachGraces(b, notes, instrument)
            return Beat(beatId, b.start, b.written, notes, tupletGroupIds = b.groups, fermata = fermata, strum = strum.takeIf { notes.size > 1 })
        }

        private fun attachGraces(b: BeatBuild, notes: MutableList<Note>, instrument: InstrumentDefinition) {
            if (b.graces.isEmpty()) return
            val sorted = notes.indices.sortedBy { notes[it].pitch.midiNumber }
            val taken = HashSet<Int>()
            b.graces.sortedBy { pitchOf(it)?.first ?: 0 }.forEachIndexed { gi, g ->
                val (midi, cents, _) = pitchOf(g) ?: return@forEachIndexed
                val tech = g.el.path("notations", "technical")
                val mxlString = tech?.textOf("string")?.toIntOrNull()
                val string = mxlString?.let { instrument.tuning.size - it }?.takeIf { it in instrument.tuning.indices }
                val fret = tech?.textOf("fret")?.toIntOrNull()?.takeIf { string != null && it in 0..instrument.maxFret && instrument.pitch(string, it) == midi }
                val target = (if (string != null) sorted.firstOrNull { notes[it].string == string } else null) ?: sorted.getOrNull(gi) ?: sorted.first()
                if (!taken.add(target)) { losses.add("grace_multiple", "Mehrere Vorschlagsnoten vor einer Note: nur die erste wird übernommen", trackId); return@forEachIndexed }
                val denominator = typeDenominator(g.el.textOf("type")) ?: 32
                val graceEl = g.el.child("grace")!!
                val before = graceEl.attr("slash") == "yes" || graceEl.attr("steal-time-previous") != null
                val grace = Articulation.Grace(Pitch(midi.coerceIn(0, 127), cents), WrittenDuration(denominator, g.el.children("dot").size.coerceAtMost(4)), before, fret, if (fret != null) string else null)
                notes[target] = notes[target].copy(articulations = notes[target].articulations + grace)
            }
        }

        private inner class Parsed(val string: Int?, val fret: Int?, val articulations: List<Articulation>, val fingering: Fingering?)

        private fun techniques(x: XNote, instrument: InstrumentDefinition, vibrato: Boolean): Parsed {
            val articulations = ArrayList<Articulation>()
            val marks = LinkedHashSet<NoteMark>()
            var string: Int? = null
            var fret: Int? = null
            var harmonic: HarmonicKind? = null
            var leftHand: Int? = null
            var rightHand: String? = null
            val bends = ArrayList<XmlElement>()
            val notations = x.el.children("notations")
            val tabFret = notations.flatMap { it.children("technical") }.firstNotNullOfOrNull { it.textOf("fret")?.toIntOrNull() }
            val slurStart = notations.flatMap { it.children("slur") }.any { it.attr("type") == "start" }
            for (n in notations) for (e in n.elements) when (e.name) {
                "technical" -> for (t in e.elements) when (t.name) {
                    "string" -> t.text.toIntOrNull()?.let { s -> (instrument.tuning.size - s).takeIf { it in instrument.tuning.indices }?.let { string = it } }
                    "fret" -> fret = t.text.toIntOrNull()?.takeIf { it >= 0 }
                    "hammer-on" -> if (t.attr("type") == "start") marks += NoteMark.HAMMER_ON
                    "pull-off" -> if (t.attr("type") == "start") marks += NoteMark.PULL_OFF
                    "bend" -> bends += t
                    "harmonic" -> harmonic = if (t.child("artificial") != null) HarmonicKind.ARTIFICIAL else HarmonicKind.NATURAL
                    "tap" -> marks += NoteMark.TAPPING
                    "up-bow" -> articulations += Articulation.Pickstroke(StrokeDirection.UP)
                    "down-bow" -> articulations += Articulation.Pickstroke(StrokeDirection.DOWN)
                    "fingering" -> leftHand = t.text.trim().toIntOrNull()
                    "pluck" -> rightHand = t.text.trim().takeIf { it.isNotEmpty() }?.take(8)
                    "other-technical" -> when (t.text.trim().lowercase()) {
                        "let ring" -> marks += NoteMark.LET_RING
                        "p.m.", "pm", "palm mute" -> marks += NoteMark.PALM_MUTE
                        "slap" -> marks += NoteMark.SLAP
                        "pop" -> marks += NoteMark.POP
                        "pick scrape" -> marks += NoteMark.PICK_SCRAPE
                        "rimshot" -> articulations += Articulation.Percussion(PercussionTechnique.RIMSHOT)
                        "choke" -> articulations += Articulation.Percussion(PercussionTechnique.CHOKE)
                        "p.h." -> harmonic = HarmonicKind.PINCH
                        "t.h." -> harmonic = HarmonicKind.TAPPED
                        "s.h." -> harmonic = HarmonicKind.SEMI
                        "f.h." -> harmonic = HarmonicKind.FEEDBACK
                        "a.h." -> harmonic = HarmonicKind.ARTIFICIAL
                        else -> losses.add("technical_other", "Unbekannte Spieltechnik \"${t.text.take(40)}\" wird nicht übernommen", trackId)
                    }
                    else -> losses.add("technical_${t.name}", "Spieltechnik <${t.name}> wird nicht übernommen", trackId)
                }
                "articulations" -> for (a in e.elements) when (a.name) {
                    "accent" -> marks += NoteMark.ACCENT
                    "strong-accent" -> marks += NoteMark.HEAVY_ACCENT
                    "staccato", "staccatissimo", "spiccato" -> marks += NoteMark.STACCATO
                    "scoop" -> articulations += Articulation.Slide(SlideKind.INTO_FROM_BELOW)
                    "plop" -> articulations += Articulation.Slide(SlideKind.INTO_FROM_ABOVE)
                    "doit" -> articulations += Articulation.Slide(SlideKind.OUT_UP)
                    "falloff" -> articulations += Articulation.Slide(SlideKind.OUT_DOWN)
                    else -> losses.add("articulation_${a.name}", "Artikulation <${a.name}> wird nicht übernommen", trackId)
                }
                "ornaments" -> for (o in e.elements) when (o.name) {
                    "wavy-line" -> Unit
                    "trill-mark" -> {
                        val step = if (o.attr("trill-step") == "whole") 2 else 1
                        val f = tabFret
                        if (f != null && f + step <= instrument.maxFret) articulations += Articulation.Trill(f + step)
                        else losses.add("trill", "Triller ohne Bundangabe wird nicht übernommen", trackId)
                    }
                    "tremolo" -> if (o.attr("type") == null || o.attr("type") == "single") {
                        val beams = o.text.trim().toIntOrNull()?.coerceIn(1, 6) ?: 3
                        articulations += Articulation.TremoloPicking(WrittenDuration(1 shl (beams + 2)))
                    } else losses.add("tremolo_double", "Zweinoten-Tremolo wird nicht übernommen", trackId)
                    "accidental-mark" -> Unit
                    else -> losses.add("ornament_${o.name}", "Verzierung <${o.name}> wird nicht übernommen", trackId)
                }
                "slide", "glissando" -> if (e.attr("type") == "start") articulations += Articulation.Slide(if (slurStart && e.name == "slide") SlideKind.LEGATO else SlideKind.SHIFT)
                "dynamics" -> dynamicLevel(e)?.let { articulations += Articulation.Dynamic(it) } ?: losses.add("dynamics", "Dynamikangaben außer ppp–fff werden nicht übernommen", trackId)
                "tied", "slur", "tuplet", "fermata", "arpeggiate", "non-arpeggiate" -> Unit
                else -> losses.add("notation_${e.name}", "Notationselement <${e.name}> wird nicht übernommen", trackId)
            }
            if (vibrato) articulations += Articulation.Vibrato()
            val notehead = x.el.child("notehead")
            if (notehead?.text == "x" && !percussion) marks += NoteMark.DEAD
            if (notehead?.attr("parentheses") == "yes") marks += NoteMark.GHOST
            if (x.el.child("play")?.children("mute")?.any { it.text == "palm" } == true) marks += NoteMark.PALM_MUTE
            if (x.el.children("lyric").isNotEmpty()) losses.add("lyrics", "Liedtexte werden nicht übernommen", trackId)
            harmonic?.let { articulations += Articulation.Harmonic(it) }
            if (bends.isNotEmpty()) bendArticulations(bends)?.let { articulations += it }
            val fingering = if (leftHand != null || rightHand != null) Fingering(leftHand, rightHand) else null
            return Parsed(string, fret, marks.map { Articulation.Mark(it) } + articulations, fingering)
        }

        /** MusicXML bends (bend-alter = change in semitones, pre-bend, release, with-bar) -> one bend or whammy curve. */
        private fun bendArticulations(bends: List<XmlElement>): Articulation? {
            val points = ArrayList<Pair<Fraction, Int>>()
            var value = 0
            points += Fraction.ZERO to 0
            var withBar = false
            var pre = false
            var release = false
            for (b in bends) {
                val alter = parseDecimal(b.textOf("bend-alter"))?.toDouble() ?: continue
                val cents = Math.round(alter * 100).toInt()
                if (b.child("with-bar") != null) withBar = true
                if (b.child("pre-bend") != null) {
                    pre = true; value = Math.abs(cents)
                    points.clear(); points += Fraction.ZERO to value
                    continue
                }
                val delta = if (b.child("release") != null) { release = true; -Math.abs(cents) } else cents
                val first = parseDecimal(b.attr("first-beat"))?.let { it / Fraction.of(100) } ?: Fraction.of(1, 4)
                val last = parseDecimal(b.attr("last-beat"))?.let { it / Fraction.of(100) } ?: Fraction.of(3, 4)
                val from = minF(maxF(first, points.last().first), Fraction.ONE)
                val to = minF(maxF(last, from), Fraction.ONE)
                if (from > points.last().first) points += from to value
                value += delta
                points += to to value
            }
            if (points.last().first < Fraction.ONE) points += Fraction.ONE to value
            val curve = points.map { PitchCurvePoint(it.first, it.second.coerceIn(-4800, 4800)) }
            if (curve.all { it.cents == 0 }) return null
            if (withBar) return Articulation.Whammy(curve)
            val kind = when {
                pre && release -> BendKind.PREBEND_RELEASE
                pre -> BendKind.PREBEND
                release -> BendKind.RELEASE
                else -> BendKind.BEND
            }
            return Articulation.Bend(curve, kind)
        }

        private fun arpeggiate(x: XNote): Strum? {
            val a = x.el.children("notations").flatMap { it.children("arpeggiate") }.firstOrNull() ?: return null
            return Strum(StrumKind.STRUM, if (a.attr("direction") == "down") StrokeDirection.UP else StrokeDirection.DOWN)
        }

        private fun fermata(x: XNote): Fermata? {
            val f = x.el.children("notations").flatMap { it.children("fermata") }.firstOrNull() ?: return null
            val kind = when (f.text.trim()) { "angled", "double-angled" -> FermataKind.SHORT; "square", "double-square" -> FermataKind.LONG; else -> FermataKind.MEDIUM }
            return Fermata(kind, MusicXmlExporter.fermataMultiplier(kind))
        }

        /** Keeps valid TAB positions, repairs inconsistent ones and places notes without TAB data. */
        private fun assignPositions(bars: List<Bar>, instrument: InstrumentDefinition, built: MutableList<Built>, hasTab: Boolean): List<Bar> {
            val replaced = HashMap<EntityId, Note>()
            val busyUntil = Array(instrument.tuning.size) { Fraction.of(-1) }
            data class Item(val m: Int, val beat: Beat, val note: Note)
            val byStart = TreeMap<Fraction, MutableList<Item>>()
            bars.forEachIndexed { m, bar -> bar.voices.forEach { v -> v.beats.forEach { b -> b.notes.forEach { n -> byStart.getOrPut(starts[m] + b.offset) { ArrayList() } += Item(m, b, n) } } } }
            for ((start, items) in byStart) {
                val fixed = HashMap<Note, Pair<Int, Int>>()
                val usedStrings = HashSet<Int>()
                for (item in items) {
                    val s = item.note.string
                    val f = item.note.fret
                    if (s != null && f != null && s !in usedStrings) {
                        if (f in 0..instrument.maxFret && instrument.pitch(s, f) == item.note.pitch.midiNumber) { fixed[item.note] = s to f; usedStrings += s; continue }
                        val repaired = item.note.pitch.midiNumber - instrument.pitch(s, 0)
                        if (repaired in 0..instrument.maxFret) { fixed[item.note] = s to repaired; usedStrings += s; losses.add("tab_mismatch", "Bundangaben, die der Tonhöhe widersprechen, wurden korrigiert", trackId); continue }
                    }
                }
                val open = items.filter { it.note !in fixed }
                if (open.isNotEmpty()) {
                    if (hasTab) losses.add("tab_position", "Noten ohne gültige TAB-Position wurden automatisch gesetzt", trackId)
                    val busy = instrument.tuning.indices.filter { busyUntil[it] > start }.toSet()
                    val positions = FretAssigner.assign(open.map { it.note.pitch.midiNumber }, instrument, busy, excluded = usedStrings)
                    open.forEachIndexed { i, item -> positions[i]?.let { fixed[item.note] = it } }
                }
                for (item in items) {
                    val pos = fixed[item.note]
                    if (pos == null) { replaced[item.note.id] = item.note.copy(string = null, fret = null); continue }
                    replaced[item.note.id] = item.note.copy(string = pos.first, fret = pos.second)
                    val end = start + item.beat.duration.length()
                    if (end > busyUntil[pos.first]) busyUntil[pos.first] = end
                }
            }
            val dropped = replaced.values.filter { it.string == null }.map { it.id }.toSet()
            if (dropped.isNotEmpty()) losses.add("unplayable", "Noten außerhalb von Tonumfang oder Saitenzahl des Instruments wurden verworfen", trackId)
            built.removeAll { it.noteId in dropped }
            built.forEach { b -> replaced[b.noteId]?.let { b.note = it } }
            return bars.map { bar -> bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { beat ->
                val notes = beat.notes.mapNotNull { n -> if (n.id in dropped) null else replaced[n.id] ?: n }
                if (notes.isEmpty() && beat.notes.isNotEmpty()) beat.copy(notes = emptyList(), explicitRest = true, strum = null) else beat.copy(notes = notes)
            }) }) }
        }

        /** Ties (tie/tied start -> next note of the same pitch where this one ends) and plain slurs. */
        private fun resolveLinks(bars: List<Bar>, built: List<Built>): List<Bar> {
            val links = HashMap<EntityId, MutableList<NoteLink>>()
            val byStartPitch = built.groupBy { it.absStart to (it.note.pitch.midiNumber to it.note.pitch.cents) }
            val used = HashSet<EntityId>()
            val ordered = built.sortedBy { it.absStart }
            for (b in ordered) {
                val tieStart = b.x.el.children("tie").any { it.attr("type") == "start" } ||
                    b.x.el.children("notations").flatMap { it.children("tied") }.any { it.attr("type") == "start" }
                if (!tieStart) continue
                val pitch = b.note.pitch.midiNumber to b.note.pitch.cents
                val candidates = byStartPitch[b.absEnd to pitch].orEmpty().filter { it.noteId !in used && it.noteId != b.noteId }
                val target = candidates.firstOrNull { it.voice == b.voice && it.note.string == b.note.string } ?: candidates.firstOrNull { it.voice == b.voice } ?: candidates.firstOrNull()
                    ?: ordered.firstOrNull { it.absStart >= b.absEnd && it.voice == b.voice && it.note.pitch.midiNumber == pitch.first && it.note.pitch.cents == pitch.second && it.noteId !in used && it.absStart - b.absEnd <= Fraction.of(1, 16) }
                if (target == null) { losses.add("tie_unresolved", "Haltebögen ohne passende Folgenote wurden verworfen", trackId); continue }
                used += target.noteId
                links.getOrPut(b.noteId) { ArrayList() } += NoteLink(LinkKind.TIE, target.noteId)
            }
            // Plain slurs: start -> stop with the same number, in document order of the part.
            val byElement = built.associateBy { it.x.el }
            val open = HashMap<Int, Built>()
            for (x in part.notes) {
                val b = byElement[x.el] ?: continue
                for (s in x.el.children("notations").flatMap { it.children("slur") }) {
                    val number = s.attr("number")?.toIntOrNull() ?: 1
                    when (s.attr("type")) {
                        "start" -> open[number] = b
                        "stop" -> open.remove(number)?.let { origin ->
                            val legato = origin.note.articulations.any { a -> a is Articulation.Slide || (a is Articulation.Mark && (a.kind == NoteMark.HAMMER_ON || a.kind == NoteMark.PULL_OFF)) }
                            if (!legato && origin.noteId != b.noteId) links.getOrPut(origin.noteId) { ArrayList() } += NoteLink(LinkKind.SLUR, b.noteId)
                        }
                    }
                }
            }
            if (links.isEmpty()) return bars
            return bars.map { bar -> bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { beat ->
                beat.copy(notes = beat.notes.map { n -> links[n.id]?.let { n.copy(links = n.links + it) } ?: n })
            }) }) }
        }
    }
}
