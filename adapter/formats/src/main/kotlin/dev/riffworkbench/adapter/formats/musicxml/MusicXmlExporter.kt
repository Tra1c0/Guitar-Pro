package dev.riffworkbench.adapter.formats.musicxml

import dev.riffworkbench.adapter.formats.midi.*
import dev.riffworkbench.adapter.formats.xml.XmlWriter
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*

/**
 * C-FORMAT: MusicXML 4.0 score-partwise export (uncompressed .musicxml).
 *
 * One part per track. Fretted tracks get two staves (standard notation on staff 1, TAB with staff-tuning and capo
 * on staff 2, string/fret in the TAB notes); voices are numbered 1–4 on staff 1 and 5–8 on staff 2. Divisions are
 * the least common multiple of all rhythmic denominators, so every duration is exact. Pitches are spelled from MIDI
 * with the key signature (or the note's stored spelling). Score-wide directions (tempo, rehearsal marks,
 * segno/coda/D.C./D.S./Fine with sound attributes, swing, texts) are written into the first part only, positioned
 * with <offset sound="yes"> inside its first voice. Note velocity is kept in the note's dynamics attribute.
 */
class MusicXmlExporter : ScoreExporter {
    override val formatId: String = "musicxml"

    override suspend fun export(request: ExportRequest, progress: (OperationProgress) -> Unit): Outcome<ExportResult> {
        if (request.formatId != formatId) return formatFailure(FailureCode.VALIDATION, "Exportformat ${request.formatId} passt nicht zum MusicXML-Export")
        return exportNow(request.project, progress)
    }

    fun exportNow(project: Project, progress: (OperationProgress) -> Unit = {}): Outcome<ExportResult> = guarded("MusicXML-Export", FailureCode.VALIDATION) {
        exportBlocker(project)?.let { return it }
        val losses = LossLog("export")
        progress(OperationProgress(0, 1, "MusicXML"))
        val xml = MusicXmlWriter(project, losses).write()
        progress(OperationProgress(1, 1, "Fertig"))
        Outcome.Success(ExportResult(xml.toByteArray(Charsets.UTF_8), MEDIA_TYPE, exportFileName(project, "musicxml"), losses.toList()))
    }

    companion object {
        const val MEDIA_TYPE = "application/vnd.recordare.musicxml+xml"
        internal const val MAX_DIVISIONS = 1_000_000L
        internal fun fermataMultiplier(kind: FermataKind) = when (kind) { FermataKind.SHORT -> 1.25; FermataKind.MEDIUM -> 1.5; FermataKind.LONG -> 2.0 }
        internal fun typeName(denominator: Int) = when (denominator) {
            1 -> "whole"; 2 -> "half"; 4 -> "quarter"; 8 -> "eighth"; 16 -> "16th"; 32 -> "32nd"; 64 -> "64th"
            128 -> "128th"; 256 -> "256th"; 512 -> "512th"; else -> "1024th"
        }
    }
}

private class MusicXmlWriter(private val project: Project, private val losses: LossLog) {
    private val x = XmlWriter()
    private val measures = project.measures
    private var divisionsOverflow = false
    private val divisions: Long = computeDivisions()
    private val tieTargets = HashSet<EntityId>()
    private val spanStarts = HashMap<EntityId, MutableList<Span>>()
    private val spanStops = HashMap<EntityId, MutableList<Span>>()
    private val segnoName = HashMap<Int, String>()
    private val codaName = HashMap<Int, String>()
    private val hasFine = measures.any { m -> m.navigation.any { it.kind == NavigationKind.FINE } }
    private val hasToCoda = measures.any { m -> m.navigation.any { it.kind == NavigationKind.TO_CODA } }
    private val measureIndex = measures.withIndex().associate { it.value.id to it.index }

    private enum class SpanKind { HAMMER, PULL, SLIDE, SLUR }
    private data class Span(val kind: SpanKind, val number: Int)
    private class Layout(val staff: Staff, val number: Int, val tab: Boolean)
    private class Direction(val offset: Fraction, val write: (Long) -> Unit)

    init {
        measures.forEachIndexed { i, m ->
            if (m.navigation.any { it.kind == NavigationKind.SEGNO }) segnoName[i] = "segno${i + 1}"
            if (m.navigation.any { it.kind == NavigationKind.CODA }) codaName[i] = "coda${i + 1}"
        }
        for (track in project.tracks) for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) for (note in beat.notes)
            note.links.filter { it.kind == LinkKind.TIE }.forEach { tieTargets += it.target }
        computeSpans()
    }

    fun write(): String {
        x.declaration().raw("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" \"http://www.musicxml.org/dtds/partwise.dtd\">\n")
        x.start("score-partwise", "version" to "4.0")
        header()
        partList()
        project.tracks.forEachIndexed { ti, track -> part(ti, track) }
        x.end()
        projectLosses()
        if (divisionsOverflow) losses.add("divisions", "Rhythmus erfordert zu feine Divisions; Dauern wurden auf 960 pro Viertel gerundet")
        return x.build()
    }

    // ---- time ----------------------------------------------------------------------------------------------------

    private fun computeDivisions(): Long {
        var lcm = 1L
        fun add(f: Fraction) {
            val q = f * Fraction.of(4)
            val next = lcmOrNull(lcm, q.denominator, MusicXmlExporter.MAX_DIVISIONS)
            if (next == null || next > MusicXmlExporter.MAX_DIVISIONS) divisionsOverflow = true else lcm = next
        }
        measures.forEach { add(it.pickupLength ?: it.signature.length()) }
        for (track in project.tracks) for (staff in track.staves) for ((mi, bar) in staff.bars.withIndex()) for (voice in bar.voices) {
            for (beat in voice.beats) { add(beat.offset); add(beat.duration.length()) }
            // Gap rests are spelled into pieces (a 1/8 gap can become 16th.. + 64th): their grid must be representable too,
            // or a piece gets <duration>0</duration>.
            if (voice.beats.isEmpty() || mi >= measures.size) continue
            val length = length(mi)
            var cursor = Fraction.ZERO
            for (beat in voice.beats.sortedBy { it.offset }) {
                if (beat.offset > cursor) Rhythm.spell(cursor, beat.offset, length).forEach { add(it.offset); add(it.duration.length()) }
                cursor = maxOf(cursor, beat.offset + beat.duration.length())
            }
            if (cursor < length) Rhythm.spell(cursor, length, length).forEach { add(it.offset); add(it.duration.length()) }
        }
        project.tempo.forEach { add(it.position.offset) }
        project.text.forEach { add(it.position.offset) }
        return if (divisionsOverflow) 960 else lcm
    }

    private fun div(f: Fraction): Long {
        val q = f * Fraction.of(4 * divisions)
        return if (q.isInteger()) q.numerator else Math.round(q.toDouble())
    }

    private fun length(mi: Int): Fraction = measures[mi].let { it.pickupLength ?: it.signature.length() }

    // ---- legato spans --------------------------------------------------------------------------------------------

    private fun computeSpans() {
        val allNotes = HashSet<EntityId>()
        for (track in project.tracks) for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) beat.notes.forEach { allNotes += it.id }
        for (track in project.tracks) for (staff in track.staves) for (v in 0..3) {
            val sequence = staff.bars.flatMap { bar -> bar.voices.firstOrNull { it.index == v }?.beats.orEmpty().sortedBy { it.offset } }
            for ((i, beat) in sequence.withIndex()) {
                val sorted = beat.notes.sortedBy { it.pitch.midiNumber }
                for ((k, note) in sorted.withIndex()) {
                    val number = k % 16 + 1
                    fun next(): Note? {
                        for (j in i + 1 until minOf(sequence.size, i + 17)) {
                            val candidates = sequence[j].notes
                            if (candidates.isEmpty()) continue
                            return if (note.string != null) candidates.firstOrNull { it.string == note.string } ?: continue
                            else candidates.minByOrNull { Math.abs(it.pitch.midiNumber - note.pitch.midiNumber) }
                        }
                        return null
                    }
                    fun link(kind: SpanKind, target: EntityId?) {
                        if (target == null || target == note.id || target !in allNotes) {
                            when (kind) {
                                SpanKind.HAMMER, SpanKind.PULL -> losses.add("hammer_pull", "Hammer-on/Pull-off ohne folgende Note auf der Saite wird nicht exportiert", note.id)
                                SpanKind.SLIDE -> losses.add("slide", "Slide ohne folgende Note auf der Saite wird nicht exportiert", note.id)
                                SpanKind.SLUR -> losses.add("slur", "Bindebogen ohne Zielnote wird nicht exportiert", note.id)
                            }
                            return
                        }
                        if (spanStarts[note.id]?.any { it.kind == kind } == true) return
                        spanStarts.getOrPut(note.id) { ArrayList() } += Span(kind, number)
                        spanStops.getOrPut(target) { ArrayList() } += Span(kind, number)
                    }
                    for (l in note.links) when (l.kind) {
                        LinkKind.HAMMER_ON -> link(SpanKind.HAMMER, l.target)
                        LinkKind.PULL_OFF -> link(SpanKind.PULL, l.target)
                        LinkKind.LEGATO_SLIDE -> { link(SpanKind.SLIDE, l.target); link(SpanKind.SLUR, l.target) }
                        LinkKind.SLUR -> link(SpanKind.SLUR, l.target)
                        LinkKind.TIE -> Unit
                    }
                    for (a in note.articulations) when {
                        a is Articulation.Mark && a.kind == NoteMark.HAMMER_ON -> link(SpanKind.HAMMER, next()?.id)
                        a is Articulation.Mark && a.kind == NoteMark.PULL_OFF -> link(SpanKind.PULL, next()?.id)
                        a is Articulation.Slide && (a.kind == SlideKind.SHIFT || a.kind == SlideKind.LEGATO) -> {
                            val target = a.target ?: next()?.id
                            link(SpanKind.SLIDE, target)
                            if (a.kind == SlideKind.LEGATO) link(SpanKind.SLUR, target)
                        }
                    }
                }
            }
        }
    }

    // ---- header and part list ------------------------------------------------------------------------------------

    private fun header() {
        val meta = project.metadata
        x.start("work").leaf("work-title", meta.title).end()
        x.start("identification")
        if (meta.composer.isNotBlank()) x.leaf("creator", meta.composer, "type" to "composer")
        if (meta.artist.isNotBlank()) x.leaf("creator", meta.artist, "type" to "artist")
        if (meta.copyright.isNotBlank()) x.leaf("rights", meta.copyright)
        x.start("encoding")
        x.leaf("software", "ArtiFret")
        for (element in listOf("accidental", "beam", "stem")) x.leaf("supports", null, "element" to element, "type" to "no")
        x.end()
        if (meta.album.isNotBlank() || meta.notes.isNotBlank()) {
            x.start("miscellaneous")
            if (meta.album.isNotBlank()) x.leaf("miscellaneous-field", meta.album, "name" to "album")
            if (meta.notes.isNotBlank()) x.leaf("miscellaneous-field", meta.notes, "name" to "notes")
            x.end()
        }
        x.end()
    }

    private val channels: IntArray = run {
        val melodic = (0..15).filter { it != 9 }
        var next = 0
        IntArray(project.tracks.size) { if (project.tracks[it].instrument.kind == InstrumentKind.PERCUSSION) 9 else melodic[next++ % melodic.size] }
    }

    private fun drumNotes(track: Track): List<Int> {
        val used = sortedSetOf<Int>()
        track.instrument.drumKit.forEach { used += it.midiNote.coerceIn(0, 127) }
        for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) for (note in beat.notes) {
            used += DrumNotation.effectiveNote(note)
            note.articulations.filterIsInstance<Articulation.Grace>().forEach { used += it.pitch.midiNumber }
        }
        return used.toList()
    }

    private fun drumName(track: Track, midi: Int) = track.instrument.drumKit.firstOrNull { it.midiNote == midi }?.name?.takeIf { it.isNotBlank() } ?: DrumNotation.name(midi)

    private fun partList() {
        x.start("part-list")
        project.tracks.forEachIndexed { ti, track ->
            val pid = "P${ti + 1}"
            x.start("score-part", "id" to pid)
            x.leaf("part-name", track.name)
            val volume = formatNumber((track.mix.volume.takeIf { it.isFinite() } ?: 0.8).coerceIn(0.0, 1.0) * 100, 2)
            val pan = formatNumber((track.mix.pan.takeIf { it.isFinite() } ?: 0.0).coerceIn(-1.0, 1.0) * 90, 2)
            val program = track.instrument.midiProgram.coerceIn(0, 127) + 1
            if (track.instrument.kind == InstrumentKind.PERCUSSION) {
                val notes = drumNotes(track)
                notes.forEach { x.start("score-instrument", "id" to "$pid-I$it").leaf("instrument-name", drumName(track, it)).end() }
                notes.forEach {
                    x.start("midi-instrument", "id" to "$pid-I$it").leaf("midi-channel", 10).leaf("midi-program", program)
                        .leaf("midi-unpitched", it + 1).leaf("volume", volume).leaf("pan", pan).end()
                }
            } else {
                x.start("score-instrument", "id" to "$pid-I1").leaf("instrument-name", track.name).end()
                x.start("midi-instrument", "id" to "$pid-I1").leaf("midi-channel", channels[ti] + 1).leaf("midi-program", program)
                    .leaf("volume", volume).leaf("pan", pan).end()
            }
            x.end()
        }
        x.end()
    }

    // ---- parts ---------------------------------------------------------------------------------------------------

    private fun fretted(track: Track) = (track.instrument.kind == InstrumentKind.GUITAR || track.instrument.kind == InstrumentKind.BASS) && track.instrument.tuning.isNotEmpty()

    private fun layouts(track: Track): List<Layout> {
        var n = 1
        return track.staves.flatMap { s -> if (fretted(track)) listOf(Layout(s, n++, false), Layout(s, n++, true)) else listOf(Layout(s, n++, false)) }
    }

    private fun part(ti: Int, track: Track) {
        x.start("part", "id" to "P${ti + 1}")
        val lay = layouts(track)
        val pickup = measures.first().pickupLength != null
        for (mi in measures.indices) {
            val measure = measures[mi]
            x.start("measure", "number" to (if (pickup) mi else mi + 1).toString(), "implicit" to (if (mi == 0 && pickup) "yes" else null))
            if (measure.forcedSystemBreak && mi > 0) x.start("print", "new-system" to "yes").end()
            attributes(track, lay, mi)
            leftBarline(mi)
            val measureDivs = div(length(mi))
            var position = 0L
            var first = true
            for (l in lay) {
                val bar = l.staff.bars[mi]
                for (voice in bar.voices.sortedBy { it.index }) {
                    if (voice.index > 0 && voice.beats.isEmpty()) continue
                    if (position > 0) x.start("backup").leaf("duration", position).end()
                    voiceContent(ti, track, lay, l, voice, mi, if (ti == 0 && first) directions(mi, lay.size > 1) else emptyList())
                    position = measureDivs
                    first = false
                }
            }
            rightBarline(mi)
            x.end()
        }
        x.end()
        trackLosses(track)
    }

    private fun attributes(track: Track, lay: List<Layout>, mi: Int) {
        val m = measures[mi]
        val prev = measures.getOrNull(mi - 1)
        val keyChange = prev == null || prev.key != m.key
        val timeChange = prev == null || prev.signature != m.signature
        if (prev != null && !keyChange && !timeChange) return
        val percussion = track.instrument.kind == InstrumentKind.PERCUSSION
        x.start("attributes")
        if (prev == null) x.leaf("divisions", divisions)
        if (keyChange) x.start("key", "print-object" to (if (percussion) "no" else null)).leaf("fifths", m.key.fifths.coerceIn(-7, 7)).leaf("mode", if (m.key.minor) "minor" else "major").end()
        if (timeChange) x.start("time").leaf("beats", m.signature.numerator).leaf("beat-type", m.signature.denominator).end()
        if (prev == null) {
            if (lay.size > 1) x.leaf("staves", lay.size)
            lay.forEach { clef(track, it, lay.size > 1) }
            lay.filter { it.tab }.forEach { staffDetails(track, it) }
        }
        x.end()
    }

    private fun clef(track: Track, l: Layout, numbered: Boolean) {
        x.start("clef", "number" to (if (numbered) l.number else null))
        when {
            l.tab -> x.leaf("sign", "TAB")
            track.instrument.kind == InstrumentKind.PERCUSSION -> x.leaf("sign", "percussion")
            track.instrument.kind == InstrumentKind.BASS -> x.leaf("sign", "F").leaf("line", 4).leaf("clef-octave-change", -1)
            track.instrument.kind == InstrumentKind.GUITAR -> x.leaf("sign", "G").leaf("line", 2).leaf("clef-octave-change", -1)
            else -> {
                val pitches = l.staff.bars.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { it.notes.map { n -> n.pitch.midiNumber } } } }.sorted()
                if (pitches.isNotEmpty() && pitches[pitches.size / 2] < 60) x.leaf("sign", "F").leaf("line", 4) else x.leaf("sign", "G").leaf("line", 2)
            }
        }
        x.end()
    }

    private fun staffDetails(track: Track, l: Layout) {
        val inst = track.instrument
        val uniform = inst.capoByString.distinct().size <= 1
        if (!uniform) losses.add("partial_capo", "Teil-Capo ist in MusicXML nicht darstellbar; die TAB-Stimmung enthält den Capo, Bünde bleiben relativ zum Capo", track.id)
        if (inst.transposition != 0) losses.add("instrument_transposition", "Instrumententransposition wird in die TAB-Stimmung eingerechnet", track.id)
        x.start("staff-details", "number" to l.number)
        x.leaf("staff-lines", inst.tuning.size)
        inst.tuning.forEachIndexed { s, open ->
            val pitch = open + inst.transposition + if (uniform) 0 else inst.capoByString[s]
            val sp = PitchSpelling.spell(pitch, 0)
            x.start("staff-tuning", "line" to s + 1).leaf("tuning-step", sp.step)
            if (sp.alter != 0) x.leaf("tuning-alter", sp.alter)
            x.leaf("tuning-octave", sp.octave).end()
        }
        val capo = if (uniform) inst.capoByString.firstOrNull() ?: 0 else 0
        if (capo > 0) x.leaf("capo", capo)
        x.end()
    }

    private fun endingNumbers(set: Set<Int>) = set.sorted().joinToString(", ")

    private fun leftBarline(mi: Int) {
        val m = measures[mi]
        val endings = m.repeat.alternateEndings
        val endingStart = endings.isNotEmpty() && (mi == 0 || measures[mi - 1].repeat.alternateEndings != endings)
        if (!m.repeat.starts && !endingStart) return
        x.start("barline", "location" to "left")
        if (m.repeat.starts) x.leaf("bar-style", "heavy-light")
        if (endingStart) x.leaf("ending", endings.sorted().joinToString(", ") { "$it." }, "number" to endingNumbers(endings), "type" to "start")
        if (m.repeat.starts) x.start("repeat", "direction" to "forward").end()
        x.end()
    }

    private fun rightBarline(mi: Int) {
        val m = measures[mi]
        val endings = m.repeat.alternateEndings
        val next = measures.getOrNull(mi + 1)
        val endingStop = endings.isNotEmpty() && (next == null || next.repeat.alternateEndings != endings)
        val times = m.repeat.endCount?.takeIf { it >= 2 }
        val last = mi == measures.lastIndex
        if (!endingStop && times == null && !last) return
        x.start("barline", "location" to "right")
        if (times != null || last) x.leaf("bar-style", "light-heavy")
        if (endingStop) x.start("ending", "number" to endingNumbers(endings), "type" to if (times != null) "stop" else "discontinue").end()
        if (times != null) x.start("repeat", "direction" to "backward", "times" to times).end()
        x.end()
    }

    // ---- directions (first part only) ----------------------------------------------------------------------------

    private fun directions(mi: Int, multiStaff: Boolean): List<Direction> {
        val m = measures[mi]
        val out = ArrayList<Direction>()
        fun direction(offset: Fraction, types: () -> Unit, sound: (() -> Unit)? = null) {
            out += Direction(offset) { offsetDivs ->
                x.start("direction", "placement" to "above")
                types()
                if (offsetDivs > 0) x.leaf("offset", offsetDivs, "sound" to "yes")
                if (multiStaff) x.leaf("staff", 1)
                sound?.invoke()
                x.end()
            }
        }
        fun words(text: String) { x.start("direction-type").leaf("words", text).end() }
        project.sections.filter { it.measureId == m.id }.forEach { s -> direction(Fraction.ZERO, { x.start("direction-type").leaf("rehearsal", s.label).end() }) }
        if (m.navigation.any { it.kind == NavigationKind.SEGNO }) direction(Fraction.ZERO, { x.start("direction-type").start("segno").end().end() }) { x.start("sound", "segno" to segnoName[mi]).end() }
        if (m.navigation.any { it.kind == NavigationKind.CODA }) direction(Fraction.ZERO, { x.start("direction-type").start("coda").end().end() }) { x.start("sound", "coda" to codaName[mi]).end() }
        val previousFeel = measures.getOrNull(mi - 1)?.feel ?: RhythmicFeel.STRAIGHT
        if (m.feel != previousFeel) {
            val (label, type) = when (m.feel) {
                RhythmicFeel.STRAIGHT -> "Straight" to null
                RhythmicFeel.SWING_EIGHTH -> "Swing" to "eighth"
                RhythmicFeel.SWING_SIXTEENTH -> "Swing 16tel" to "16th"
                RhythmicFeel.TRIPLET -> "Triplet Feel" to "eighth"
            }
            direction(Fraction.ZERO, { words(label) }) {
                x.start("sound").start("swing")
                if (type == null) x.start("straight").end() else x.leaf("first", 2).leaf("second", 1).leaf("swing-type", type)
                x.end().end()
            }
        }
        project.tempo.filter { it.position.measureId == m.id }.sortedBy { it.position.offset }.forEach { t ->
            direction(t.position.offset, {
                x.start("direction-type").start("metronome", "parentheses" to "no").leaf("beat-unit", "quarter").leaf("per-minute", formatNumber(t.quarterNotesPerMinute, 2)).end().end()
            }) { x.start("sound", "tempo" to formatNumber(t.quarterNotesPerMinute, 3)).end() }
        }
        project.text.filter { it.position.measureId == m.id && it.kind != TextKind.LYRICS }.forEach { t ->
            if (t.kind != TextKind.TEXT) losses.add("text_kind", "Textart (Abschnitt, Fingersatz) wird nicht exportiert; der Text wird als freier Text gelesen", t.id)
            direction(t.position.offset, { words(t.text) })
        }
        val end = length(mi)
        for (mark in m.navigation) when (mark.kind) {
            NavigationKind.FINE -> direction(end, { words("Fine") }) { x.start("sound", "fine" to "yes").end() }
            NavigationKind.DA_CAPO -> direction(end, { words(if (hasFine) "D.C. al Fine" else if (hasToCoda) "D.C. al Coda" else "D.C.") }) { x.start("sound", "dacapo" to "yes").end() }
            NavigationKind.DAL_SEGNO -> {
                val target = mark.target?.let { measureIndex[it] }?.let { segnoName[it] } ?: segnoName.entries.minByOrNull { it.key }?.value
                if (mark.target != null && mark.target.let { measureIndex[it] }?.let { segnoName[it] } == null)
                    losses.add("navigation", "D.S.-Sprungziel ohne Segno ist in MusicXML nicht darstellbar; es gilt das erste Segno", m.id)
                direction(end, { words(if (hasFine) "D.S. al Fine" else if (hasToCoda) "D.S. al Coda" else "D.S.") }) { x.start("sound", "dalsegno" to (target ?: "segno")).end() }
            }
            NavigationKind.TO_CODA -> {
                val target = mark.target?.let { measureIndex[it] }?.let { codaName[it] } ?: codaName.entries.minByOrNull { it.key }?.value
                if (mark.target != null && mark.target.let { measureIndex[it] }?.let { codaName[it] } == null)
                    losses.add("navigation", "To-Coda-Sprungziel ohne Coda ist in MusicXML nicht darstellbar; es gilt die erste Coda", m.id)
                direction(end, { words("To Coda") }) { x.start("sound", "tocoda" to (target ?: "coda")).end() }
            }
            else -> Unit
        }
        return out.sortedBy { it.offset }
    }

    // ---- voices and notes ----------------------------------------------------------------------------------------

    private fun voiceContent(ti: Int, track: Track, lay: List<Layout>, l: Layout, voice: Voice, mi: Int, directions: List<Direction>) {
        val voiceNumber = (l.number - 1) * 4 + voice.index + 1
        val staffNumber = if (lay.size > 1) l.number else null
        val length = length(mi)
        val pending = ArrayDeque(directions)
        fun flush(start: Fraction, end: Fraction?) {
            while (pending.isNotEmpty() && (end == null || pending.first().offset < end)) {
                val d = pending.removeFirst()
                d.write(maxOf(0L, div(d.offset) - div(start)))
            }
        }
        if (voice.beats.isEmpty()) {
            flush(Fraction.ZERO, null)
            x.start("note").start("rest", "measure" to "yes").end().leaf("duration", div(length)).leaf("voice", voiceNumber)
            if (staffNumber != null) x.leaf("staff", staffNumber)
            x.end()
            return
        }
        val beats = voice.beats.sortedBy { it.offset }
        val brackets = tupletBrackets(beats)
        var cursor = Fraction.ZERO
        fun gap(from: Fraction, to: Fraction) {
            if (from >= to) return
            val pieces = Rhythm.spell(from, to, length)
            if (voice.index == 0 && pieces.all { it.duration.tuplets.isEmpty() }) {
                for (p in pieces) {
                    flush(p.offset, p.offset + p.duration.length())
                    x.start("note").start("rest").end().leaf("duration", div(p.offset + p.duration.length()) - div(p.offset)).leaf("voice", voiceNumber)
                        .leaf("type", MusicXmlExporter.typeName(p.duration.denominator))
                    repeat(p.duration.dots) { x.start("dot").end() }
                    if (staffNumber != null) x.leaf("staff", staffNumber)
                    x.end()
                }
            } else {
                flush(from, to)
                x.start("forward").leaf("duration", div(to) - div(from)).leaf("voice", voiceNumber)
                if (staffNumber != null) x.leaf("staff", staffNumber)
                x.end()
            }
        }
        for ((i, beat) in beats.withIndex()) {
            gap(cursor, beat.offset)
            val end = beat.offset + beat.duration.length()
            flush(beat.offset, end)
            beat(ti, track, l, voiceNumber, staffNumber, beat, brackets[i], mi)
            cursor = maxF(cursor, end)
        }
        gap(cursor, length)
        flush(length, null)
    }

    private class Brackets(val starts: List<Pair<Int, TupletRatio>>, val stops: List<Int>)

    /** Tuplet start/stop per beat and nesting level; explicit group ids win, otherwise groups are inferred. */
    private fun tupletBrackets(beats: List<Beat>): List<Brackets> {
        val n = beats.size
        val groupAt = Array(n) { arrayOfNulls<String>(beats[it].duration.tuplets.size) }
        val maxLevel = beats.maxOfOrNull { it.duration.tuplets.size } ?: 0
        for (level in 0 until maxLevel) {
            var synthetic = 0
            var runKey: String? = null
            var runId = ""
            var accumulated = Fraction.ZERO
            var maxDenominator = 1
            var complete = false
            for (i in 0 until n) {
                val b = beats[i]
                if (b.duration.tuplets.size <= level) { runKey = null; continue }
                val explicit = b.tupletGroupIds.getOrNull(level)
                if (explicit != null) { groupAt[i][level] = "e" + explicit.value; runKey = null; continue }
                val ratio = b.duration.tuplets[level]
                val key = (0 until level).joinToString("/") { groupAt[i][it] ?: "" } + "|${ratio.actual}:${ratio.normal}"
                if (runKey != key || complete) {
                    runKey = key; runId = "s$level-${synthetic++}"; accumulated = Fraction.ZERO; maxDenominator = 1; complete = false
                }
                groupAt[i][level] = runId
                var inner = b.duration.length()
                for (j in 0..level) inner *= Fraction.of(b.duration.tuplets[j].actual.toLong(), b.duration.tuplets[j].normal.toLong())
                accumulated += inner
                maxDenominator = maxOf(maxDenominator, b.duration.denominator)
                val unit = Fraction.of(ratio.actual.toLong(), maxDenominator.toLong())
                complete = (accumulated / unit).isInteger()
            }
        }
        return List(n) { i ->
            val starts = ArrayList<Pair<Int, TupletRatio>>()
            val stops = ArrayList<Int>()
            for (level in beats[i].duration.tuplets.indices) {
                val g = groupAt[i][level]
                val prev = if (i > 0) groupAt[i - 1].getOrNull(level) else null
                val next = if (i + 1 < n) groupAt[i + 1].getOrNull(level) else null
                if (prev != g) starts += level to beats[i].duration.tuplets[level]
                if (next != g) stops += level
            }
            Brackets(starts, stops.sortedDescending())
        }
    }

    private fun beat(ti: Int, track: Track, l: Layout, voiceNumber: Int, staffNumber: Int?, beat: Beat, brackets: Brackets, mi: Int) {
        val divs = div(beat.offset + beat.duration.length()) - div(beat.offset)
        if (beat.chordId != null) losses.add("chord_symbols", "Akkordsymbole und Griffbilder werden nicht exportiert", track.id)
        if (beat.explicitRest || beat.notes.isEmpty()) {
            x.start("note").start("rest").end().leaf("duration", divs).leaf("voice", voiceNumber).leaf("type", MusicXmlExporter.typeName(beat.duration.denominator))
            repeat(beat.duration.dots) { x.start("dot").end() }
            timeModification(beat.duration)
            if (staffNumber != null) x.leaf("staff", staffNumber)
            val items = ArrayList<() -> Unit>()
            tupletItems(brackets, items)
            fermataItem(beat, track, items)
            notations(items)
            x.end()
            return
        }
        val sorted = beat.notes.sortedWith(compareBy({ it.pitch.midiNumber }, { it.pitch.cents }))
        val graces = sorted.mapNotNull { n -> n.articulations.filterIsInstance<Articulation.Grace>().firstOrNull()?.let { n to it } }
        if (sorted.any { n -> n.articulations.count { it is Articulation.Grace } > 1 }) losses.add("grace_multiple", "Nur der erste von mehreren Vorschlägen einer Note wird exportiert", track.id)
        graces.forEachIndexed { i, (note, grace) -> graceNote(ti, track, l, voiceNumber, staffNumber, note, grace, i > 0, mi) }
        sorted.forEachIndexed { i, note -> note(ti, track, l, voiceNumber, staffNumber, beat, note, divs, i, brackets, mi) }
    }

    private fun timeModification(d: WrittenDuration) {
        if (d.tuplets.isEmpty()) return
        var actual = 1L; var normal = 1L
        d.tuplets.forEach { actual *= it.actual; normal *= it.normal }
        x.start("time-modification").leaf("actual-notes", actual).leaf("normal-notes", normal).end()
    }

    private fun pitch(track: Track, midi: Int, cents: Int, spelling: String?, mi: Int) {
        if (track.instrument.kind == InstrumentKind.PERCUSSION) {
            val d = DrumNotation.display(midi)
            x.start("unpitched").leaf("display-step", d.step).leaf("display-octave", d.octave).end()
            return
        }
        val s = PitchSpelling.fromSpelling(spelling, midi) ?: PitchSpelling.spell(midi, measures[mi].key.fifths)
        x.start("pitch").leaf("step", s.step)
        val alter = s.alter + cents / 100.0
        if (alter != 0.0) x.leaf("alter", formatNumber(alter, 2))
        x.leaf("octave", s.octave).end()
    }

    private fun graceNote(ti: Int, track: Track, l: Layout, voiceNumber: Int, staffNumber: Int?, main: Note, grace: Articulation.Grace, chord: Boolean, mi: Int) {
        val percussion = track.instrument.kind == InstrumentKind.PERCUSSION
        x.start("note")
        x.start("grace", "slash" to (if (grace.beforeBeat) "yes" else null)).end()
        if (chord) x.start("chord").end()
        pitch(track, grace.pitch.midiNumber, grace.pitch.cents, grace.pitch.spelling, mi)
        if (percussion) x.start("instrument", "id" to "P${ti + 1}-I${grace.pitch.midiNumber}").end()
        x.leaf("voice", voiceNumber).leaf("type", MusicXmlExporter.typeName(grace.duration.denominator))
        repeat(grace.duration.dots) { x.start("dot").end() }
        if (staffNumber != null) x.leaf("staff", staffNumber)
        if (l.tab) {
            val string = grace.string ?: main.string
            val fret = grace.fret ?: string?.let { s -> (grace.pitch.midiNumber - track.instrument.pitch(s, 0)).takeIf { it in 0..track.instrument.maxFret } }
            if (string != null && fret != null && string in track.instrument.tuning.indices)
                x.start("notations").start("technical").leaf("string", track.instrument.tuning.size - string).leaf("fret", fret).end().end()
        }
        x.end()
    }

    private fun note(ti: Int, track: Track, l: Layout, voiceNumber: Int, staffNumber: Int?, beat: Beat, note: Note, divs: Long, index: Int, brackets: Brackets, mi: Int) {
        val percussion = track.instrument.kind == InstrumentKind.PERCUSSION
        val marks = note.articulations.filterIsInstance<Articulation.Mark>().map { it.kind }.toSet()
        val effective = if (percussion) DrumNotation.effectiveNote(note) else note.pitch.midiNumber
        x.start("note", "dynamics" to formatNumber(note.velocity.coerceIn(1, 127) / 0.9, 2))
        if (index > 0) x.start("chord").end()
        pitch(track, effective, if (percussion) 0 else note.pitch.cents, note.pitch.spelling, mi)
        x.leaf("duration", divs)
        val tieStop = note.id in tieTargets
        val tieStart = note.links.any { it.kind == LinkKind.TIE }
        if (tieStop) x.start("tie", "type" to "stop").end()
        if (tieStart) x.start("tie", "type" to "start").end()
        if (percussion) x.start("instrument", "id" to "P${ti + 1}-I$effective").end()
        x.leaf("voice", voiceNumber).leaf("type", MusicXmlExporter.typeName(beat.duration.denominator))
        repeat(beat.duration.dots) { x.start("dot").end() }
        timeModification(beat.duration)
        val ghost = NoteMark.GHOST in marks || note.articulations.any { it is Articulation.Percussion && it.kind == PercussionTechnique.GHOST }
        val head = when {
            NoteMark.DEAD in marks -> "x"
            percussion -> DrumNotation.display(effective).notehead
            else -> null
        }
        if (head != null || ghost) x.leaf("notehead", head ?: "normal", "parentheses" to (if (ghost) "yes" else null))
        if (staffNumber != null) x.leaf("staff", staffNumber)

        val items = ArrayList<() -> Unit>()
        if (tieStop) items += { x.start("tied", "type" to "stop").end() }
        if (tieStart) items += { x.start("tied", "type" to "start").end() }
        spanStops[note.id]?.filter { it.kind == SpanKind.SLUR }?.forEach { s -> items += { x.start("slur", "type" to "stop", "number" to s.number).end() } }
        spanStarts[note.id]?.filter { it.kind == SpanKind.SLUR }?.forEach { s -> items += { x.start("slur", "type" to "start", "number" to s.number).end() } }
        if (index == 0) tupletItems(brackets, items)
        spanStops[note.id]?.filter { it.kind == SpanKind.SLIDE }?.forEach { s -> items += { x.start("slide", "type" to "stop", "number" to s.number).end() } }
        spanStarts[note.id]?.filter { it.kind == SpanKind.SLIDE }?.forEach { s -> items += { x.start("slide", "type" to "start", "number" to s.number, "line-type" to "solid").end() } }
        val technical = technical(track, l, note, marks)
        if (technical.isNotEmpty()) items += { x.start("technical"); technical.forEach { it() }; x.end() }
        val ornaments = ornaments(track, note)
        if (ornaments.isNotEmpty()) items += { x.start("ornaments"); ornaments.forEach { it() }; x.end() }
        val articulations = articulations(note, marks)
        if (articulations.isNotEmpty()) items += { x.start("articulations"); articulations.forEach { it() }; x.end() }
        note.articulations.filterIsInstance<Articulation.Dynamic>().firstOrNull()?.let { d -> items += { x.start("dynamics").start(d.level.name.lowercase()).end().end() } }
        if (index == 0) fermataItem(beat, track, items)
        val strum = beat.strum
        if (strum != null && beat.notes.size > 1) {
            items += { x.start("arpeggiate", "direction" to if (strum.direction == StrokeDirection.DOWN) "up" else "down").end() }
            if (index == 0 && strum.kind != StrumKind.STRUM) losses.add("strum_kind", "Arpeggio- und Rasgueado-Anschläge werden als Arpeggiate-Zeichen exportiert", track.id)
            if (index == 0 && strum.spread > Fraction.ZERO) losses.add("strum_spread", "Strum-Dauer wird nicht exportiert", track.id)
        } else if (strum != null && beat.notes.size == 1) losses.add("strum", "Anschlag/Arpeggio eines Beats mit nur einer Note wird nicht exportiert", track.id)
        notations(items)
        if (NoteMark.PALM_MUTE in marks) x.start("play").leaf("mute", "palm").end()
        x.end()
    }

    private fun notations(items: List<() -> Unit>) {
        if (items.isEmpty()) return
        x.start("notations")
        items.forEach { it() }
        x.end()
    }

    private fun tupletItems(brackets: Brackets, items: MutableList<() -> Unit>) {
        for ((level, ratio) in brackets.starts) items += {
            x.start("tuplet", "type" to "start", "number" to level + 1, "bracket" to "yes")
            x.start("tuplet-actual").leaf("tuplet-number", ratio.actual).end()
            x.start("tuplet-normal").leaf("tuplet-number", ratio.normal).end()
            x.end()
        }
        for (level in brackets.stops) items += { x.start("tuplet", "type" to "stop", "number" to level + 1).end() }
    }

    private fun fermataItem(beat: Beat, track: Track, items: MutableList<() -> Unit>) {
        val f = beat.fermata ?: return
        if (Math.abs(f.multiplier - MusicXmlExporter.fermataMultiplier(f.kind)) > 1e-9) losses.add("fermata_length", "Fermatenlänge wird nur über die Fermatenform (kurz/normal/lang) exportiert", track.id)
        val shape = when (f.kind) { FermataKind.SHORT -> "angled"; FermataKind.MEDIUM -> "normal"; FermataKind.LONG -> "square" }
        items += { x.leaf("fermata", shape, "type" to "upright") }
    }

    private fun technical(track: Track, l: Layout, note: Note, marks: Set<NoteMark>): List<() -> Unit> {
        val out = ArrayList<() -> Unit>()
        val string = note.string
        val fret = note.fret
        if (l.tab && string != null && fret != null) {
            out += { x.leaf("string", track.instrument.tuning.size - string).leaf("fret", fret) }
        }
        spanStops[note.id]?.forEach { s ->
            when (s.kind) {
                SpanKind.HAMMER -> out += { x.start("hammer-on", "type" to "stop", "number" to s.number).end() }
                SpanKind.PULL -> out += { x.start("pull-off", "type" to "stop", "number" to s.number).end() }
                else -> Unit
            }
        }
        spanStarts[note.id]?.forEach { s ->
            when (s.kind) {
                SpanKind.HAMMER -> out += { x.leaf("hammer-on", "H", "type" to "start", "number" to s.number) }
                SpanKind.PULL -> out += { x.leaf("pull-off", "P", "type" to "start", "number" to s.number) }
                else -> Unit
            }
        }
        for (a in note.articulations) when (a) {
            is Articulation.Bend -> out += { bends(a.points, false) }
            is Articulation.Whammy -> out += { bends(a.points, true) }
            is Articulation.Harmonic -> {
                out += { x.start("harmonic").start(if (a.kind == HarmonicKind.NATURAL) "natural" else "artificial").end().end() }
                val label = when (a.kind) { HarmonicKind.PINCH -> "P.H."; HarmonicKind.TAPPED -> "T.H."; HarmonicKind.SEMI -> "S.H."; HarmonicKind.FEEDBACK -> "F.H."; else -> null }
                if (label != null) out += { x.leaf("other-technical", label) }
                if (a.soundingPitch != null || a.node != null) losses.add("harmonic_pitch", "Klingende Tonhöhe bzw. Knotenpunkt von Flageoletts wird nicht exportiert", track.id)
            }
            is Articulation.Pickstroke -> out += { x.start(if (a.direction == StrokeDirection.UP) "up-bow" else "down-bow").end() }
            is Articulation.Percussion -> {
                if (a.kind == PercussionTechnique.RIMSHOT) out += { x.leaf("other-technical", "rimshot") }
                if (a.kind == PercussionTechnique.CHOKE) out += { x.leaf("other-technical", "choke") }
                if (a.chokeGroup != null) losses.add("drum_choke", "Choke-Gruppen werden nicht exportiert", track.id)
            }
            else -> Unit
        }
        if (NoteMark.TAPPING in marks) out += { x.start("tap").end() }
        if (NoteMark.LET_RING in marks) out += { x.leaf("other-technical", "let ring") }
        if (NoteMark.SLAP in marks) out += { x.leaf("other-technical", "slap") }
        if (NoteMark.POP in marks) out += { x.leaf("other-technical", "pop") }
        if (NoteMark.PICK_SCRAPE in marks) out += { x.leaf("other-technical", "pick scrape") }
        note.fingering?.let { f ->
            f.leftHand?.let { out += { x.leaf("fingering", it) } }
            f.rightHand?.takeIf { it.isNotBlank() }?.let { out += { x.leaf("pluck", it) } }
            if (f.alternative != null) losses.add("fingering_alternative", "Alternative Fingersätze werden nicht exportiert", track.id)
        }
        return out
    }

    /**
     * Bend curve -> pre-bend plus one bend/release element per monotonic run (bend-alter is the change in semitones).
     * Whammy curves (with-bar) keep the sign in bend-alter and never use release: a dip is a negative alter, not a release.
     */
    private fun bends(points: List<PitchCurvePoint>, withBar: Boolean) {
        val pts = points.sortedBy { it.position }
        if (pts.isEmpty()) return
        fun element(alter: Int, kind: String?, first: Fraction?, last: Fraction?) {
            x.start("bend", "first-beat" to first?.let { formatNumber(it.toDouble().coerceIn(0.0, 1.0) * 100, 2) }, "last-beat" to last?.let { formatNumber(it.toDouble().coerceIn(0.0, 1.0) * 100, 2) })
            x.leaf("bend-alter", formatNumber(alter / 100.0, 2))
            if (kind != null) x.start(kind).end()
            if (withBar) x.start("with-bar").end()
            x.end()
        }
        if (pts.first().cents != 0) element(pts.first().cents, "pre-bend", null, null)
        var i = 0
        while (i < pts.size - 1) {
            val dir = Integer.signum(pts[i + 1].cents - pts[i].cents)
            if (dir == 0) { i++; continue }
            var j = i + 1
            while (j + 1 < pts.size && Integer.signum(pts[j + 1].cents - pts[j].cents) == dir) j++
            element(pts[j].cents - pts[i].cents, if (dir < 0 && !withBar) "release" else null, pts[i].position, pts[j].position)
            i = j
        }
    }

    private fun ornaments(track: Track, note: Note): List<() -> Unit> {
        val out = ArrayList<() -> Unit>()
        for (a in note.articulations) when (a) {
            is Articulation.Vibrato -> {
                out += { x.start("wavy-line", "type" to "start", "number" to 1).end(); x.start("wavy-line", "type" to "stop", "number" to 1).end() }
                if (a.depthCents != 30 || a.rateHz != 5.0) losses.add("vibrato_params", "Vibrato-Tiefe und -Rate werden nicht exportiert", track.id)
            }
            is Articulation.Trill -> {
                val step = note.fret?.let { a.targetFret - it }
                when (step) {
                    1 -> out += { x.start("trill-mark", "trill-step" to "half").end() }
                    2 -> out += { x.start("trill-mark", "trill-step" to "whole").end() }
                    else -> { out += { x.start("trill-mark").end() }; losses.add("trill_interval", "Triller-Zielbund ist nur als Halb-/Ganzton darstellbar", track.id) }
                }
                if (a.rate != Articulation.Trill(a.targetFret).rate) losses.add("trill_rate", "Triller-Geschwindigkeit wird nicht exportiert (Import liest 1/32)", track.id)
            }
            is Articulation.TremoloPicking -> {
                val d = a.rate.denominator
                if (d in setOf(8, 16, 32, 64, 128) && a.rate.dots == 0 && a.rate.tuplets.isEmpty()) out += { x.leaf("tremolo", Integer.numberOfTrailingZeros(d) - 2, "type" to "single") }
                else losses.add("tremolo_rate", "Tremolo-Picking-Rate ist nicht als Tremolobalken darstellbar", track.id)
            }
            else -> Unit
        }
        return out
    }

    private fun articulations(note: Note, marks: Set<NoteMark>): List<() -> Unit> {
        val out = ArrayList<() -> Unit>()
        if (NoteMark.ACCENT in marks) out += { x.start("accent").end() }
        if (NoteMark.HEAVY_ACCENT in marks) out += { x.start("strong-accent").end() }
        if (NoteMark.STACCATO in marks) out += { x.start("staccato").end() }
        for (a in note.articulations) if (a is Articulation.Slide) when (a.kind) {
            SlideKind.INTO_FROM_BELOW -> out += { x.start("scoop").end() }
            SlideKind.INTO_FROM_ABOVE -> out += { x.start("plop").end() }
            SlideKind.OUT_UP -> out += { x.start("doit").end() }
            SlideKind.OUT_DOWN -> out += { x.start("falloff").end() }
            else -> Unit
        }
        return out
    }

    // ---- losses --------------------------------------------------------------------------------------------------

    private fun trackLosses(track: Track) {
        val id = track.id
        if (track.instrument.fineTuneCents != 0.0) losses.add("fine_tune", "Feinstimmung des Instruments wird nicht exportiert", id)
        if (track.mix.mute || track.mix.solo) losses.add("track_mute", "Mute und Solo werden in MusicXML nicht gespeichert", id)
        if (track.mix.effects.isNotEmpty()) losses.add("effects", "Effektketten der Spur werden nicht exportiert", id)
        if (track.sound.bankId != null || track.sound.presetId != null || track.sound.originalRseData != null)
            losses.add("sound_assignment", "Klangzuordnung wird nur als MIDI-Programm exportiert", id)
        if (track.instrument.kind == InstrumentKind.PERCUSSION && track.instrument.drumKit != defaultDrumKit() && track.instrument.drumKit.any { it.chokeGroup != null || it.volume != 1.0 || it.pan != 0.0 })
            losses.add("drum_kit", "Choke-Gruppen, Lautstärke und Panorama einzelner Drum-Elemente werden nicht exportiert", id)
        if (track.instrument.maxFret != 24 && fretted(track)) losses.add("max_fret", "Bundanzahl des Instruments wird nicht exportiert", id)
        for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) for (note in beat.notes)
            if (note.articulations.any { it is Articulation.Grace && it.duration.tuplets.isNotEmpty() }) losses.add("grace_tuplet", "Tuplet-Angaben von Vorschlagsnoten werden nicht exportiert", id)
    }

    private fun projectLosses() {
        if (project.text.any { it.kind == TextKind.LYRICS }) losses.add("lyrics", "Liedtexte ohne Notenbezug werden nicht exportiert")
        if (project.chords.isNotEmpty()) losses.add("chord_symbols", "Akkordsymbole und Griffbilder werden nicht exportiert")
        if (project.audio.clips.isNotEmpty()) losses.add("audio_track", "Audiospuren werden nicht in MusicXML exportiert")
        if (project.mix.automation.isNotEmpty()) losses.add("automation", "Mix-Automation wird nicht exportiert")
        if (project.mix.buses.isNotEmpty() || project.mix.master.effects.isNotEmpty()) losses.add("mix_bus", "Busse und Master-Effekte werden nicht exportiert")
        if (measures.drop(1).any { it.pickupLength != null }) losses.add("irregular_measure", "Sondertaktlängen außerhalb des Auftakts werden nur über die Notendauern abgebildet")
    }
}
