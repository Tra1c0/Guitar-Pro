package dev.riffworkbench.adapter.formats.ascii

import dev.riffworkbench.adapter.formats.midi.*
import dev.riffworkbench.adapter.formats.musicxml.PitchSpelling
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlin.math.ceil
import kotlin.math.floor

/**
 * C-FORMAT: plain-text TAB (UTF-8, deterministic).
 *
 * Per track: string lines labelled from the tuning (highest string on top), fret numbers placed at their beat
 * positions with spacing proportional to time inside each measure (at least two characters per sixteenth and
 * always room for the widest label), bar lines with |: :| repeats, a measure-number/info line and a PM line for
 * palm mutes. Techniques: h p (hammer/pull), b / pb / r (bend, pre-bend, release with target fret), / \ (slides),
 * ~ (vibrato), x (dead note), (n) ghost, <n> natural and [n] other harmonics, t (tapping). Percussion becomes a
 * drum grid (one character per grid step, e.g. "BD |o---o---|"), pitched tracks without strings a note-name line.
 * Rhythm values, rests, voices and other notation details are not representable and are reported as losses.
 */
class AsciiTabExporter : ScoreExporter {
    override val formatId: String = "ascii"

    override suspend fun export(request: ExportRequest, progress: (OperationProgress) -> Unit): Outcome<ExportResult> {
        if (request.formatId != formatId) return formatFailure(FailureCode.VALIDATION, "Exportformat ${request.formatId} passt nicht zum ASCII-Export")
        return exportNow(request.project, progress)
    }

    fun exportNow(project: Project, progress: (OperationProgress) -> Unit = {}): Outcome<ExportResult> = guarded("ASCII-Export", FailureCode.VALIDATION) {
        exportBlocker(project)?.let { return it }
        val losses = LossLog("export")
        val text = AsciiWriter(project, losses).write()
        progress(OperationProgress(1, 1, "Fertig"))
        Outcome.Success(ExportResult(text.toByteArray(Charsets.UTF_8), MEDIA_TYPE, exportFileName(project, "txt"), losses.toList()))
    }

    companion object {
        const val MEDIA_TYPE = "text/plain; charset=utf-8"
        /** Systems wrap before this many characters (a single wide measure may exceed it). */
        const val LINE_WIDTH = 100
    }
}

private class AsciiWriter(private val project: Project, private val losses: LossLog) {
    private val out = StringBuilder()
    private val measures = project.measures

    /** One rendered measure: equally wide rows (info, pm, one per instrument line). */
    private class Block(val info: String, val pm: String?, val rows: List<String>, val startsRepeat: Boolean, val endsRepeat: Boolean)

    private data class Event(val offset: Fraction, val labels: Map<Int, String>, val palmMute: Boolean)

    fun write(): String {
        val meta = project.metadata
        out.append(meta.title).append('\n')
        if (meta.artist.isNotBlank()) out.append("Interpret: ").append(meta.artist).append('\n')
        if (meta.composer.isNotBlank()) out.append("Komponist: ").append(meta.composer).append('\n')
        val first = project.tempo.firstOrNull { it.position.measureId == measures.first().id && it.position.offset == Fraction.ZERO }
        out.append("Tempo: ").append(formatNumber(first?.quarterNotesPerMinute ?: 120.0, 2)).append(" BPM\n")
        project.tracks.forEachIndexed { i, track -> track(i, track) }
        legend()
        return out.toString()
    }

    // ---- per track -----------------------------------------------------------------------------------------------

    private fun track(index: Int, track: Track) {
        val instrument = track.instrument
        val kind = when {
            instrument.kind == InstrumentKind.PERCUSSION -> "drums"
            instrument.tuning.isNotEmpty() -> "tab"
            else -> "pitch"
        }
        out.append('\n').append("Spur ").append(index + 1).append(": ").append(track.name)
        when (kind) {
            "tab" -> {
                out.append('\n').append("Stimmung (tief -> hoch): ").append(instrument.tuning.joinToString(" ") { PitchSpelling.spell(it, 0).let { s -> s.step + (if (s.alter == 1) "#" else if (s.alter == -1) "b" else "") + s.octave } })
                val capo = instrument.capoByString.distinct()
                if (capo.size == 1 && capo.single() > 0) out.append(", Capo ").append(capo.single())
                if (capo.size > 1) out.append(", Teil-Capo ").append(instrument.capoByString.joinToString("/"))
            }
            "drums" -> out.append(" (Schlagzeug)")
            else -> out.append(" (Notennamen)")
        }
        out.append('\n')
        reportLosses(track, kind)
        val rows = rows(track, kind)
        val tieTargets = HashSet<EntityId>()
        for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) for (note in beat.notes)
            note.links.filter { it.kind == LinkKind.TIE }.forEach { tieTargets += it.target }
        val blocks = measures.indices.map { m -> block(track, kind, m, rows, tieTargets) }
        val labels = rows.map { it.second }
        val labelWidth = maxOf(labels.maxOfOrNull { it.length } ?: 0, 2)
        var start = 0
        while (start < blocks.size) {
            var end = start
            var width = blocks[start].rows.first().length + 2
            while (end + 1 < blocks.size && width + blocks[end + 1].rows.first().length + 1 <= AsciiTabExporter.LINE_WIDTH) { end++; width += blocks[end].rows.first().length + 1 }
            system(blocks.subList(start, end + 1), labels, labelWidth)
            start = end + 1
        }
    }

    /** Instrument lines: (key, label) top to bottom. Keys are string indices, drum notes or 0 for pitched tracks. */
    private fun rows(track: Track, kind: String): List<Pair<Int, String>> = when (kind) {
        "tab" -> {
            val names = track.instrument.tuning.map { PitchSpelling.name(it) }
            val top = track.instrument.tuning.lastIndex
            track.instrument.tuning.indices.reversed().map { s ->
                val name = names[s]
                s to if (s == top && names.count { it.equals(name, ignoreCase = true) } > 1) name.lowercase() else name
            }
        }
        "drums" -> {
            val used = sortedSetOf<Int>()
            for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) for (note in beat.notes) used += drumNote(note)
            // An empty drum track still gets one (snare) line so the block layout has a row to draw.
            if (used.isEmpty()) used += 38
            used.sortedWith(compareBy({ drumOrder(it) }, { it })).map { it to drumLabel(it) }
        }
        else -> listOf(0 to "Noten")
    }

    private fun system(blocks: List<Block>, labels: List<String>, labelWidth: Int) {
        val pad = " ".repeat(labelWidth)
        val info = StringBuilder(pad).append(' ')
        val pm = StringBuilder(pad).append(' ')
        val lines = labels.map { StringBuilder(it.padEnd(labelWidth)).append(' ') }
        blocks.forEachIndexed { i, b ->
            val left = if (i == 0) (if (b.startsRepeat) "|:" else "|") else ""
            val previousEnds = i > 0 && blocks[i - 1].endsRepeat
            val bar = if (i == 0) left else (if (previousEnds) ":" else "") + "|" + (if (b.startsRepeat) ":" else "")
            info.append(" ".repeat(bar.length)).append(b.info)
            pm.append(" ".repeat(bar.length)).append(b.pm ?: " ".repeat(b.rows.first().length))
            lines.forEachIndexed { r, line -> line.append(bar).append(b.rows[r]) }
        }
        val closing = (if (blocks.last().endsRepeat) ":" else "") + "|"
        lines.forEach { it.append(closing) }
        out.append('\n').append(info.toString().trimEnd()).append('\n')
        if (blocks.any { it.pm != null }) out.append(pm.toString().trimEnd()).append('\n')
        lines.forEach { out.append(it).append('\n') }
    }

    // ---- measures --------------------------------------------------------------------------------------------------

    private fun length(m: Int) = measures[m].let { it.pickupLength ?: it.signature.length() }

    private fun block(track: Track, kind: String, m: Int, rows: List<Pair<Int, String>>, tieTargets: Set<EntityId>): Block {
        val length = length(m)
        val events = events(track, kind, m, tieTargets)
        val rowIndex = rows.withIndex().associate { it.value.first to it.index }
        val cells: List<CharArray>
        val columns: Map<Fraction, Int>
        val width: Int
        val grid = if (kind == "drums") drumGrid(events, length) else null
        if (grid != null) {
            val step = grid
            width = ((length / step).numerator).toInt()
            columns = events.associate { it.offset to (it.offset / step).numerator.toInt() }
        } else {
            val positions = (events.map { it.offset } + length).distinct().sorted()
            var scale = if (kind == "drums") 16.0 else 32.0
            for (k in 0 until positions.size - 1) {
                val gap = (positions[k + 1] - positions[k]).toDouble()
                val w = events.firstOrNull { it.offset == positions[k] }?.labels?.values?.maxOfOrNull { it.length } ?: 1
                scale = maxOf(scale, (w + 1) / gap)
            }
            val lead = if (kind == "drums") 0 else 1
            width = lead + ceil(length.toDouble() * scale - 1e-9).toInt()
            columns = events.associate { it.offset to lead + floor(it.offset.toDouble() * scale + 1e-9).toInt() }
        }
        cells = rows.map { CharArray(width) { '-' } }
        for (e in events) {
            val col = columns.getValue(e.offset)
            for ((key, label) in e.labels) {
                val row = rowIndex[key] ?: continue
                label.forEachIndexed { k, c -> if (col + k < width) cells[row][col + k] = c }
            }
        }
        val pm = if (events.any { it.palmMute }) {
            val line = CharArray(width) { ' ' }
            var runEnd = -1
            var inRun = false
            for (e in events.sortedBy { it.offset }) {
                val col = columns.getValue(e.offset)
                val w = maxOf(2, e.labels.values.maxOfOrNull { it.length } ?: 1)
                if (!e.palmMute) { inRun = false; continue }
                if (!inRun) { "PM".forEachIndexed { k, c -> if (col + k < width) line[col + k] = c }; runEnd = col + 2; inRun = true }
                for (c in runEnd until minOf(width, col + w)) line[c] = '-'
                runEnd = maxOf(runEnd, col + w)
            }
            String(line)
        } else null
        val measure = measures[m]
        val info = StringBuilder().append(m + 1)
        val previous = measures.getOrNull(m - 1)
        if (previous == null || previous.signature != measure.signature) info.append(' ').append(measure.signature.numerator).append('/').append(measure.signature.denominator)
        if (measure.repeat.alternateEndings.isNotEmpty() && previous?.repeat?.alternateEndings != measure.repeat.alternateEndings)
            info.append(' ').append(measure.repeat.alternateEndings.sorted().joinToString(",")).append('.')
        project.sections.filter { it.measureId == measure.id }.forEach { info.append(" [").append(it.label).append(']') }
        project.tempo.filter { it.position.measureId == measure.id && (m > 0 || it.position.offset > Fraction.ZERO) }.forEach { info.append(" T=").append(formatNumber(it.quarterNotesPerMinute, 2)) }
        for (mark in measure.navigation) info.append(' ').append(when (mark.kind) {
            NavigationKind.SEGNO -> "Segno"; NavigationKind.CODA -> "Coda"; NavigationKind.DA_CAPO -> "D.C."
            NavigationKind.DAL_SEGNO -> "D.S."; NavigationKind.TO_CODA -> "To Coda"; NavigationKind.FINE -> "Fine"
        })
        val times = measure.repeat.endCount?.takeIf { it >= 2 }
        var infoText = info.toString()
        if (times != null) {
            val suffix = "x$times"
            infoText = if (infoText.length + 1 + suffix.length <= width) infoText.padEnd(width - suffix.length) + suffix else "$infoText $suffix"
        }
        val infoCell = if (infoText.length >= width) infoText.take(maxOf(1, width)) else infoText.padEnd(width)
        return Block(infoCell, pm, cells.map { String(it) }, measure.repeat.starts, times != null)
    }

    /** Drum grid step: gcd of all onsets and the bar length, at least a sixteenth; null when it needs over 96 cells. */
    private fun drumGrid(events: List<Event>, length: Fraction): Fraction? {
        var step = Fraction.of(1, 16)
        for (f in events.map { it.offset } + length) if (!f.isZero()) step = gcd(step, f)
        val cells = length / step
        return if (cells.isInteger() && cells.numerator in 1..96) step else null
    }

    private fun gcd(a: Fraction, b: Fraction): Fraction {
        val common = a.denominator / gcdL(a.denominator, b.denominator) * b.denominator
        val x = a.numerator * (common / a.denominator)
        val y = b.numerator * (common / b.denominator)
        return Fraction.of(gcdL(x, y), common)
    }

    private fun events(track: Track, kind: String, m: Int, tieTargets: Set<EntityId>): List<Event> {
        val byOffset = sortedMapOf<Fraction, MutableMap<Int, String>>()
        val palm = HashSet<Fraction>()
        for (staff in track.staves) {
            val bar = staff.bars.getOrNull(m) ?: continue
            for (voice in bar.voices.sortedBy { it.index }) for (beat in voice.beats.sortedBy { it.offset }) {
                val labels = byOffset.getOrPut(beat.offset) { LinkedHashMap() }
                if (beat.explicitRest || beat.notes.isEmpty()) continue
                val positions = positions(track, beat)
                for (note in beat.notes) {
                    if (note.articulations.any { it is Articulation.Mark && it.kind == NoteMark.PALM_MUTE }) palm += beat.offset
                    if (note.id in tieTargets) continue
                    when (kind) {
                        "tab" -> {
                            val (string, fret) = positions[note.id] ?: continue
                            labels.putIfAbsent(string, fretLabel(track, staff, m, voice, beat, note, fret))
                        }
                        "drums" -> labels.putIfAbsent(drumNote(note), drumSymbol(note).toString())
                        else -> labels[0] = (labels[0]?.let { "$it+" } ?: "") + PitchSpelling.name(note.pitch.midiNumber) + PitchSpelling.spell(note.pitch.midiNumber, 0).octave
                    }
                }
            }
        }
        return byOffset.map { (offset, labels) -> Event(offset, labels, offset in palm) }
    }

    /** String/fret per note; missing positions are computed for display only. */
    private fun positions(track: Track, beat: Beat): Map<EntityId, Pair<Int, Int>> {
        val out = HashMap<EntityId, Pair<Int, Int>>()
        val missing = ArrayList<Note>()
        for (n in beat.notes) { val s = n.string; val f = n.fret; if (s != null && f != null) out[n.id] = s to f else missing += n }
        if (missing.isNotEmpty() && track.instrument.tuning.isNotEmpty()) {
            val assigned = FretAssigner.assign(missing.map { it.pitch.midiNumber }, track.instrument, excluded = out.values.map { it.first }.toSet())
            missing.forEachIndexed { i, n -> assigned[i]?.let { out[n.id] = it } }
        }
        return out
    }

    private fun fretLabel(track: Track, staff: Staff, m: Int, voice: Voice, beat: Beat, note: Note, fret: Int): String {
        val marks = note.articulations.filterIsInstance<Articulation.Mark>().map { it.kind }.toSet()
        var label = if (NoteMark.DEAD in marks) "x" else fret.toString()
        val harmonic = note.articulations.filterIsInstance<Articulation.Harmonic>().firstOrNull()
        if (harmonic != null) label = if (harmonic.kind == HarmonicKind.NATURAL) "<$label>" else "[$label]"
        if (NoteMark.GHOST in marks) label = "($label)"
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        if (NoteMark.TAPPING in marks) prefix.append('t')
        for (a in note.articulations) when (a) {
            is Articulation.Slide -> when (a.kind) {
                SlideKind.INTO_FROM_BELOW -> prefix.append('/')
                SlideKind.INTO_FROM_ABOVE -> prefix.append('\\')
                SlideKind.OUT_UP -> suffix.append('/')
                SlideKind.OUT_DOWN -> suffix.append('\\')
                SlideKind.SHIFT, SlideKind.LEGATO -> {
                    val next = nextOnString(staff, m, voice.index, beat, note)
                    suffix.append(if (next != null && next.pitch.midiNumber < note.pitch.midiNumber) '\\' else '/')
                }
            }
            is Articulation.Bend -> {
                val points = a.points.sortedBy { it.position }
                if (points.isNotEmpty()) {
                    val peak = points.maxOf { it.cents }
                    suffix.append(if (points.first().cents != 0) "pb" else "b").append(target(fret, peak))
                    if (points.last().cents < peak) suffix.append('r').append(target(fret, points.last().cents))
                }
            }
            else -> Unit
        }
        if (NoteMark.HAMMER_ON in marks || note.links.any { it.kind == LinkKind.HAMMER_ON }) suffix.append('h')
        if (NoteMark.PULL_OFF in marks || note.links.any { it.kind == LinkKind.PULL_OFF }) suffix.append('p')
        if (note.articulations.any { it is Articulation.Vibrato }) suffix.append('~')
        return prefix.toString() + label + suffix
    }

    private fun target(fret: Int, cents: Int): String = formatNumber(fret + cents / 100.0, 1)

    private fun nextOnString(staff: Staff, m: Int, voice: Int, beat: Beat, note: Note): Note? {
        for (mi in m until minOf(staff.bars.size, m + 2)) {
            val beats = staff.bars[mi].voices.firstOrNull { it.index == voice }?.beats.orEmpty().sortedBy { it.offset }
            for (b in beats) {
                if (mi == m && b.offset <= beat.offset) continue
                b.notes.firstOrNull { it.string == note.string }?.let { return it }
            }
        }
        return null
    }

    // ---- drums ----------------------------------------------------------------------------------------------------

    private fun drumNote(note: Note): Int = when (note.articulations.filterIsInstance<Articulation.Percussion>().firstOrNull()?.kind) {
        PercussionTechnique.OPEN_HI_HAT -> 46
        PercussionTechnique.CLOSED_HI_HAT -> 42
        PercussionTechnique.PEDAL_HI_HAT -> 44
        PercussionTechnique.CROSS_STICK -> 37
        else -> note.pitch.midiNumber
    }

    private fun drumSymbol(note: Note): Char {
        val midi = drumNote(note)
        val marks = note.articulations.filterIsInstance<Articulation.Mark>().map { it.kind }.toSet()
        val ghost = NoteMark.GHOST in marks || note.articulations.any { it is Articulation.Percussion && it.kind == PercussionTechnique.GHOST }
        val accent = NoteMark.ACCENT in marks || NoteMark.HEAVY_ACCENT in marks
        val cymbal = midi in setOf(42, 44, 49, 51, 52, 53, 55, 57, 59)
        return when {
            ghost -> 'g'
            cymbal -> if (accent) 'X' else 'x'
            else -> if (accent) 'O' else 'o'
        }
    }

    private fun drumOrder(midi: Int): Int = listOf(49, 57, 52, 55, 51, 59, 53, 42, 46, 44, 50, 48, 47, 45, 43, 41, 38, 40, 37, 39, 36, 35).indexOf(midi).let { if (it < 0) 100 else it }

    private fun drumLabel(midi: Int): String = when (midi) {
        49 -> "CC"; 57 -> "C2"; 52 -> "CH"; 55 -> "SP"; 51 -> "RD"; 59 -> "R2"; 53 -> "RB"
        42 -> "HH"; 46 -> "HO"; 44 -> "HP"; 50 -> "T1"; 48 -> "T2"; 47 -> "T3"; 45 -> "T4"; 43 -> "FT"; 41 -> "F2"
        38 -> "SD"; 40 -> "S2"; 37 -> "SS"; 39 -> "CP"; 36 -> "BD"; 35 -> "B2"
        else -> midi.toString()
    }

    // ---- losses and legend ----------------------------------------------------------------------------------------

    private fun reportLosses(track: Track, kind: String) {
        val id = track.id
        var voices = false
        var any = false
        for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) {
            any = true
            if (voice.index > 0 && beat.notes.isNotEmpty()) voices = true
            if (beat.fermata != null) losses.add("fermata", "Fermaten werden im ASCII-TAB nicht dargestellt", id)
            if (beat.strum != null) losses.add("strum", "Anschlagsrichtung von Akkorden wird im ASCII-TAB nicht dargestellt", id)
            for (note in beat.notes) {
                if (note.links.any { it.kind == LinkKind.TIE }) losses.add("tie", "Haltebögen werden nicht dargestellt; gebundene Noten erscheinen nur einmal", id)
                if (kind == "tab" && (note.string == null || note.fret == null)) losses.add("tab_position", "Fehlende Saiten-/Bundangaben wurden nur für die Darstellung berechnet", id)
                if (note.fingering != null) losses.add("fingering", "Fingersätze werden im ASCII-TAB nicht dargestellt", id)
                for (a in note.articulations) when (a) {
                    is Articulation.Mark -> when (a.kind) {
                        NoteMark.LET_RING -> losses.add("let_ring", "Let Ring wird im ASCII-TAB nicht dargestellt", id)
                        NoteMark.STACCATO -> losses.add("staccato", "Staccato wird im ASCII-TAB nicht dargestellt", id)
                        NoteMark.ACCENT, NoteMark.HEAVY_ACCENT -> if (kind != "drums") losses.add("accent", "Akzente werden im ASCII-TAB nicht dargestellt", id)
                        NoteMark.SLAP, NoteMark.POP, NoteMark.PICK_SCRAPE -> losses.add("attack", "Slap, Pop und Pick-Scrape werden im ASCII-TAB nicht dargestellt", id)
                        else -> Unit
                    }
                    is Articulation.Whammy -> losses.add("whammy", "Whammy-Bar-Verläufe werden im ASCII-TAB nicht dargestellt", id)
                    is Articulation.Grace -> losses.add("grace", "Vorschlagsnoten werden im ASCII-TAB nicht dargestellt", id)
                    is Articulation.Trill -> losses.add("trill", "Triller werden im ASCII-TAB nicht dargestellt", id)
                    is Articulation.TremoloPicking -> losses.add("tremolo_picking", "Tremolo-Picking wird im ASCII-TAB nicht dargestellt", id)
                    is Articulation.Dynamic -> losses.add("dynamics", "Dynamik wird im ASCII-TAB nicht dargestellt", id)
                    is Articulation.Pickstroke -> losses.add("pickstroke", "Anschlagrichtung wird im ASCII-TAB nicht dargestellt", id)
                    is Articulation.Percussion -> if (a.kind == PercussionTechnique.RIMSHOT || a.kind == PercussionTechnique.CHOKE) losses.add("drum_stroke", "Rimshot und Choke werden im Drumgrid nicht unterschieden", id)
                    else -> Unit
                }
            }
        }
        if (any) losses.add("rhythm", "ASCII-TAB zeigt Rhythmus nur über Abstände; Notenwerte, Pausen und Tuplets entfallen", id)
        if (voices) losses.add("voices", "Mehrere Stimmen werden in einer TAB-Zeile zusammengelegt", id)
        if (track.staves.size > 1) losses.add("staves", "Mehrere Notensysteme einer Spur werden zusammengelegt", id)
        if (kind == "pitch") losses.add("pitch_track", "Spuren ohne Saiten werden als Notennamen ausgegeben", id)
    }

    private fun legend() {
        out.append('\n').append("Legende: h Hammer-on, p Pull-off, b Bending (Zielbund), pb Pre-Bend, r Release, / \\ Slide, ~ Vibrato, x Dead Note, (n) Ghost Note, ")
            .append("<n> Flageolett, [n] künstliches Flageolett, t Tapping, PM Palm-Mute, |: :| Wiederholung, xN Anzahl der Durchgänge\n")
        if (project.tracks.any { it.instrument.kind == InstrumentKind.PERCUSSION })
            out.append("Schlagzeug: o Schlag, x Becken/Hi-Hat, O X Akzent, g Ghost Note; BD Bass Drum, SD Snare, HH Hi-Hat zu, HO Hi-Hat offen, CC Crash, RD Ride, T1-T4 Toms, FT Floor Tom\n")
    }
}
