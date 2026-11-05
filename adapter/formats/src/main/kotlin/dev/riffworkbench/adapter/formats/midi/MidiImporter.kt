package dev.riffworkbench.adapter.formats.midi

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlin.math.abs

/**
 * C-FORMAT: Standard MIDI File import (type 0 and 1, PPQ or SMPTE time base).
 *
 * Measures follow the time-signature events (4/4 until the first one); a signature change inside a bar shortens
 * that bar (pickupLength). Onsets and note ends are quantized per quarter-note window of each measure: the window
 * uses either the 1/16 grid or the triplet 1/12 grid, whichever gives the lower summed distance for all onsets
 * and ends inside it (ties prefer 1/16; windows shorter than a quarter always use 1/16). All positions stay exact
 * rationals. Notes with identical quantized start and end form one beat; overlapping beats are spread over up to
 * four voices; notes crossing bar lines become tied notes, odd lengths are spelled as tied written values.
 *
 * Channel 10 becomes a percussion track (MIDI pitch, no string/fret; each hit lasts until the next hit, at most a
 * quarter, written as one untied value because percussion playback does not merge ties). Named tracks without
 * notes stay as empty tracks. Programs 24–31 become a six-string guitar in standard E tuning (a seventh string tuned to the lowest
 * note is added for material below E2), 32–39 a four-string E A D G bass (fifth string for lower material),
 * everything else piano/other without strings. Fretted notes are placed by [FretAssigner]; unplayable notes are
 * dropped with a loss entry. Pitch bends become per-note bend curves, markers become sections.
 */
class MidiImporter : ScoreImporter {
    override val formatId: String = "midi"

    override suspend fun import(bytes: ByteArray, ids: IdSource, progress: (OperationProgress) -> Unit): Outcome<ImportResult> = importNow(bytes, ids, progress)

    fun importNow(bytes: ByteArray, ids: IdSource, progress: (OperationProgress) -> Unit = {}): Outcome<ImportResult> = guarded("MIDI-Datei") {
        if (bytes.size > FormatLimits.MAX_INPUT_BYTES) resourceLimit("MIDI-Datei ist größer als 64 MiB")
        val smf = SmfReader.read(bytes)
        progress(OperationProgress(1, 3, "MIDI gelesen"))
        val (project, losses) = MidiScoreBuilder(smf, ids).build()
        progress(OperationProgress(3, 3, "Partitur erstellt"))
        validatedImport(project, losses)
    }
}

private class MidiScoreBuilder(private val smf: SmfData, private val ids: IdSource) {
    private val losses = LossLog("import")
    private val smpte = smf.division and 0x8000 != 0
    private val ticksPerWhole: Long
    /** Internal exact time unit: lcm(ticks per whole, 192) so that the 1/16, 1/12 and 1/64 grids are integral. */
    private val units: Long
    private val unitsPerTick: Long
    private val quarter: Long

    init {
        ticksPerWhole = if (smpte) {
            val fps = -((smf.division shr 8).toByte().toInt())
            val perFrame = smf.division and 0xFF
            val frames = when (fps) {
                24, 25, 30 -> fps
                29 -> { losses.add("smpte_drop_frame", "SMPTE 29,97 fps wird als 30 fps gelesen"); 30 }
                else -> corrupt("Ungültige SMPTE-Zeitbasis")
            }
            if (perFrame == 0) corrupt("Ungültige SMPTE-Zeitbasis")
            losses.add("smpte_timebase", "SMPTE-Zeitbasis: Zeit wird bei 120 BPM in Takte umgerechnet")
            2L * frames * perFrame
        } else 4L * smf.division
        units = lcmOrNull(ticksPerWhole, 192) ?: resourceLimit("MIDI-Zeitbasis ist zu fein")
        unitsPerTick = units / ticksPerWhole
        quarter = units / 4
    }

    private class RawNote(val track: Int, val channel: Int, val key: Int, val velocity: Int, val on: Long, val off: Long, val program: Int)
    private class Group(val track: Int, val channel: Int) {
        val notes = ArrayList<RawNote>()
        var program: Int? = null
        var volume: Int? = null
        var pan: Int? = null
        var bendRange = 200
        val bendTicks = ArrayList<Long>()
        val bendValues = ArrayList<Int>()
    }
    private class Span(val start: Long, val length: Long, val signature: TimeSignature) { val end get() = start + length }

    private val groups = LinkedHashMap<Int, Group>()
    private val names = HashMap<Int, String>()
    private var copyright = ""
    private val tempos = ArrayList<Pair<Long, Long>>()
    private val signatures = ArrayList<Pair<Long, TimeSignature>>()
    private val keys = ArrayList<Pair<Long, KeySignature>>()
    private val markers = ArrayList<Pair<Long, String>>()
    private val texts = ArrayList<Triple<Long, TextKind, String>>()
    private var noteCount = 0

    fun build(): Pair<Project, List<LossEntry>> {
        scan()
        val contentEnd = maxOf(
            groups.values.maxOfOrNull { g -> g.notes.maxOfOrNull { it.off } ?: 0L } ?: 0L,
            (tempos.map { it.first } + signatures.map { it.first } + keys.map { it.first } + markers.map { it.first }).maxOrNull() ?: 0L,
        ) * unitsPerTick
        val eot = (smf.tracks.maxOfOrNull { it.endTick } ?: 0L) * unitsPerTick
        val sigs = signatures.map { it.first * unitsPerTick to it.second }.groupBy { it.first }.map { it.value.last() }.sortedBy { it.first }
        var spans = measures(maxOf(contentEnd, eot), sigs) ?: measures(contentEnd, sigs) ?: resourceLimit("MIDI-Datei ergibt mehr als ${FormatLimits.MAX_MEASURES} Takte")
        // A trailing bar that only exists because the end-of-track lies slightly behind the music is dropped.
        while (spans.size > 1 && spans.last().start >= contentEnd && eot - spans.last().start < units / 8) spans = spans.dropLast(1)
        val measures = spans.map { span ->
            val key = keys.lastOrNull { it.first * unitsPerTick < span.end }?.second ?: KeySignature()
            Measure(ids.next(), span.signature, key, pickupLength = if (span.length == units * span.signature.numerator / span.signature.denominator) null else Fraction.of(span.length, units))
        }
        val starts = LongArray(spans.size) { spans[it].start }
        val total = spans.last().end
        fun locate(p: Long): Int { val i = java.util.Arrays.binarySearch(starts, p); return if (i >= 0) i else maxOf(0, -i - 2) }

        val noteGroups = groups.values.filter { it.notes.isNotEmpty() }
        val conductorHasNotes = noteGroups.any { it.track == 0 }
        // A named track without notes (e.g. an empty track of our own export) is kept as an empty track: its first
        // channel with a program change or controller, unless the track already has notes or is the conductor.
        val emptyNamed = groups.values.filter { g ->
            g.notes.isEmpty() && g.track != 0 && !names[g.track].isNullOrBlank() &&
                noteGroups.none { it.track == g.track }
        }.distinctBy { it.track }
        val trackGroups = (noteGroups + emptyNamed).sortedWith(compareBy({ it.track }, { it.channel }))
        val channelsPerTrack = trackGroups.groupBy { it.track }.mapValues { it.value.size }
        val tracks = trackGroups.map { g -> track(g, spans, measures, locate = ::locate, total = total, single = channelsPerTrack[g.track] == 1, conductorHasNotes = conductorHasNotes) }
            .ifEmpty { listOf(emptyTrack(measures)) }

        val tempo = tempoChanges(spans, measures, ::locate, total)
        val sections = markers.mapNotNull { (tick, label) ->
            val p = tick * unitsPerTick
            if (label.isBlank() || p >= total) null else measures[locate(p)].id to label.take(80)
        }.distinct().map { Section(ids.next(), it.first, it.second) }
        if (texts.size > 10_000) losses.add("text", "Mehr als 10.000 Text-/Liedtextereignisse: der Rest wurde verworfen")
        val scoreTexts = texts.take(10_000).mapNotNull { (tick, kind, text) ->
            val p = tick * unitsPerTick
            if (text.isBlank() || p >= total) null else {
                val m = locate(p)
                ScoreText(ids.next(), ScorePosition(measures[m].id, Fraction.of(p - spans[m].start, units)), text.take(500), kind)
            }
        }
        val title = when {
            smf.tracks.size == 1 -> names[0]
            !conductorHasNotes -> names[0]
            else -> null
        }?.takeIf { it.isNotBlank() } ?: "MIDI-Import"
        val project = Project(
            id = ids.next(),
            metadata = ProjectMetadata(title = title.take(200), copyright = copyright.take(500)),
            measures = measures, tracks = tracks, sections = sections, tempo = tempo, text = scoreTexts,
        )
        return project to losses.toList()
    }

    // ---- scanning ----------------------------------------------------------------------------------------------

    private fun scan() {
        var sysex = 0
        for ((ti, track) in smf.tracks.withIndex()) {
            for (meta in track.metas) when (meta.type) {
                0x51 -> if (meta.data.size >= 3) {
                    val micros = ((meta.data[0].toLong() and 0xFF) shl 16) or ((meta.data[1].toLong() and 0xFF) shl 8) or (meta.data[2].toLong() and 0xFF)
                    if (smpte) losses.add("smpte_tempo", "Tempoereignisse werden bei SMPTE-Zeitbasis ignoriert")
                    else if (micros > 0) tempos += meta.tick to micros
                }
                0x58 -> if (meta.data.size >= 2) {
                    val n = meta.data[0].toInt() and 0xFF
                    val dd = meta.data[1].toInt() and 0xFF
                    if (n in 1..64 && dd <= 6) signatures += meta.tick to TimeSignature(n, 1 shl dd)
                    else losses.add("time_signature", "Ungültige Taktart $n/2^$dd wurde ignoriert")
                }
                0x59 -> if (meta.data.size >= 2) keys += meta.tick to KeySignature(meta.data[0].toInt().coerceIn(-7, 7), meta.data[1].toInt() == 1)
                0x06 -> markers += meta.tick to meta.text()
                0x03 -> if (ti !in names) names[ti] = meta.text()
                0x02 -> if (copyright.isEmpty()) copyright = meta.text()
                0x01 -> texts += Triple(meta.tick, TextKind.TEXT, meta.text())
                0x05 -> texts += Triple(meta.tick, TextKind.LYRICS, meta.text())
                else -> Unit
            }
            sysex += track.sysex
            scanChannels(ti, track)
        }
        if (sysex > 0) losses.add("sysex", "SysEx-Ereignisse werden übersprungen")
        if (smf.declaredTracks != smf.tracks.size) losses.add("track_count", "Spuranzahl im MIDI-Kopf weicht von den gefundenen Spuren ab")
        tempos.sortBy { it.first }
        signatures.sortBy { it.first }
        keys.sortBy { it.first }
    }

    private fun scanChannels(ti: Int, track: SmfTrackData) {
        val open = HashMap<Int, ArrayDeque<Long>>()
        val program = IntArray(16)
        val started = BooleanArray(16)
        val rpnMsb = IntArray(16) { 127 }
        val rpnLsb = IntArray(16) { 127 }
        fun group(ch: Int) = groups.getOrPut(ti * 16 + ch) { Group(ti, ch) }
        fun close(ch: Int, key: Int, tick: Long) {
            val packed = open[ch * 128 + key]?.removeFirstOrNull() ?: return
            val on = packed ushr 16
            if (++noteCount > FormatLimits.MAX_NOTES) resourceLimit("MIDI-Datei enthält mehr als ${FormatLimits.MAX_NOTES} Noten")
            group(ch).notes += RawNote(ti, ch, key, (packed and 0xFF).toInt(), on, maxOf(tick, on), ((packed ushr 8) and 0xFF).toInt())
        }
        for (i in 0 until track.size) {
            val tick = track.tick(i)
            val status = track.status(i)
            val ch = status and 0x0F
            val d1 = track.data1(i)
            val d2 = track.data2(i)
            when (status and 0xF0) {
                0x90 -> if (d2 > 0) {
                    started[ch] = true
                    open.getOrPut(ch * 128 + d1) { ArrayDeque() }.addLast((tick shl 16) or (program[ch].toLong() shl 8) or d2.toLong())
                } else close(ch, d1, tick)
                0x80 -> close(ch, d1, tick)
                0xC0 -> {
                    if (started[ch] && program[ch] != d1) losses.add("program_change", "Programmwechsel innerhalb einer Spur: es gilt jeweils das Programm am Notenbeginn, die Spur erhält das erste")
                    program[ch] = d1
                    group(ch).let { if (it.program == null) it.program = d1 }
                }
                0xB0 -> when (d1) {
                    7 -> group(ch).let { if (it.volume == null) it.volume = d2 }
                    10 -> group(ch).let { if (it.pan == null) it.pan = d2 }
                    101 -> rpnMsb[ch] = d2
                    100 -> rpnLsb[ch] = d2
                    99, 98 -> { rpnMsb[ch] = 127; rpnLsb[ch] = 127 }
                    6 -> if (rpnMsb[ch] == 0 && rpnLsb[ch] == 0) group(ch).let { it.bendRange = d2 * 100 + it.bendRange % 100 }
                    38 -> if (rpnMsb[ch] == 0 && rpnLsb[ch] == 0) group(ch).let { it.bendRange = it.bendRange / 100 * 100 + d2.coerceAtMost(99) }
                    0, 32, 120, 121, 123 -> Unit
                    64 -> losses.add("sustain", "Sustain-Pedal (CC64) wird nicht übernommen; Notenlängen folgen den Note-Off-Ereignissen")
                    else -> losses.add("controllers", "MIDI-Controller (z. B. Modulation, Expression) werden nicht übernommen")
                }
                0xE0 -> group(ch).let {
                    if (it.bendTicks.size < 2_000_000) { it.bendTicks += tick; it.bendValues += d1 or (d2 shl 7) }
                    else losses.add("pitch_bend_limit", "Zu viele Pitch-Bend-Ereignisse; weitere wurden verworfen")
                }
                0xA0, 0xD0 -> losses.add("aftertouch", "Aftertouch wird nicht übernommen")
            }
        }
        var unterminated = false
        for ((code, queue) in open) for (packed in queue) {
            unterminated = true
            val on = packed ushr 16
            if (++noteCount > FormatLimits.MAX_NOTES) resourceLimit("MIDI-Datei enthält mehr als ${FormatLimits.MAX_NOTES} Noten")
            group(code / 128).notes += RawNote(ti, code / 128, code % 128, (packed and 0xFF).toInt(), on, maxOf(track.endTick, on + ticksPerWhole / 16), ((packed ushr 8) and 0xFF).toInt())
        }
        if (unterminated) losses.add("unterminated_note", "Noten ohne Note-Off wurden am Spurende beendet")
    }

    private fun measures(end: Long, sigs: List<Pair<Long, TimeSignature>>): List<Span>? {
        val out = ArrayList<Span>()
        var pos = 0L
        var signature = TimeSignature()
        var k = 0
        while (pos < end || out.isEmpty()) {
            while (k < sigs.size && sigs[k].first <= pos) { signature = sigs[k].second; k++ }
            val full = units * signature.numerator / signature.denominator
            val next = sigs.getOrNull(k)?.first
            val length = if (next != null && next < pos + full) next - pos else full
            out += Span(pos, length, signature)
            pos += length
            if (out.size > FormatLimits.MAX_MEASURES) return null
        }
        return out
    }

    private fun tempoChanges(spans: List<Span>, measures: List<Measure>, locate: (Long) -> Int, total: Long): List<TempoChange> {
        val byPosition = LinkedHashMap<Long, Double>()
        for ((tick, micros) in tempos) {
            val p = tick * unitsPerTick
            if (p >= total) continue
            var qpm = Math.round(60_000_000.0 / micros * 1000.0) / 1000.0
            if (qpm !in 10.0..1000.0) { losses.add("tempo_range", "Tempo außerhalb von 10–1000 BPM wurde begrenzt"); qpm = qpm.coerceIn(10.0, 1000.0) }
            byPosition[p] = qpm
        }
        if (byPosition.isEmpty() || byPosition.keys.min() > 0) byPosition[0L] = byPosition[0L] ?: 120.0
        var last: Double? = null
        return byPosition.entries.sortedBy { it.key }.mapNotNull { (p, qpm) ->
            if (qpm == last) null else {
                last = qpm
                val m = locate(p)
                TempoChange(ScorePosition(measures[m].id, Fraction.of(p - spans[m].start, units)), qpm)
            }
        }
    }

    // ---- tracks ------------------------------------------------------------------------------------------------

    private class QNote(val raw: RawNote, val start: Long, var end: Long) { var string: Int? = null; var fret: Int? = null }
    private class Chord(val start: Long, val end: Long) { val notes = ArrayList<QNote>(); var voice = 0; var bendBlocked = false }

    private fun emptyTrack(measures: List<Measure>): Track {
        val kind = InstrumentKind.PIANO
        return Track(ids.next(), "Spur 1", DefaultInstruments.unfretted(kind, 0),
            listOf(Staff(ids.next(), DefaultInstruments.notation(kind), measures.map { Bar(ids.next(), it.id, List(4) { v -> Voice(ids.next(), v) }) })))
    }

    private fun track(g: Group, spans: List<Span>, measures: List<Measure>, locate: (Long) -> Int, total: Long, single: Boolean, conductorHasNotes: Boolean): Track {
        val program = g.notes.minByOrNull { it.on }?.program ?: g.program ?: 0
        val kind = if (g.channel == 9) InstrumentKind.PERCUSSION else DefaultInstruments.kindForProgram(program)
        val lowest = g.notes.minOfOrNull { it.key }
        val instrument = when (kind) {
            InstrumentKind.GUITAR -> DefaultInstruments.guitar(lowest, program)
            InstrumentKind.BASS -> DefaultInstruments.bass(lowest, program)
            else -> DefaultInstruments.unfretted(kind, program)
        }
        val trackId = ids.next()
        val baseName = names[g.track]?.takeIf { it.isNotBlank() && (g.track != 0 || conductorHasNotes || smf.tracks.size == 1) }
        val name = when {
            baseName != null && single -> baseName
            baseName != null -> "$baseName · Kanal ${g.channel + 1}"
            else -> "${DefaultInstruments.defaultName(kind)} · Kanal ${g.channel + 1}"
        }.take(120)

        // Window grid choice (1/16 versus triplet 1/12) from all onsets and ends of this track.
        val step16 = units / 16
        val step12 = units / 12
        val err16 = HashMap<Long, Long>()
        val err12 = HashMap<Long, Long>()
        fun windowOf(p: Long): Triple<Long, Long, Long> { // key, start, end
            val m = locate(minOf(p, total))
            val span = spans[m]
            val lastWindow = (span.length - 1) / quarter
            val w = minOf((minOf(p, total) - span.start) / quarter, lastWindow)
            val ws = span.start + w * quarter
            return Triple(m * 1024L + w, ws, minOf(ws + quarter, span.end))
        }
        fun distance(p: Long, ws: Long, we: Long, step: Long): Long {
            val j = (p - ws) / step
            val a = ws + j * step
            val b = minOf(a + step, we)
            return minOf(abs(p - a), abs(p - b))
        }
        val percussion = kind == InstrumentKind.PERCUSSION
        val events = g.notes.flatMap { if (percussion) listOf(it.on * unitsPerTick) else listOf(it.on * unitsPerTick, it.off * unitsPerTick) }
        for (p in events) {
            val (key, ws, we) = windowOf(p)
            err16[key] = (err16[key] ?: 0L) + distance(p, ws, we, step16)
            if (we - ws == quarter) err12[key] = (err12[key] ?: 0L) + distance(p, ws, we, step12)
        }
        val triplet = err12.filter { (key, e12) -> e12 < (err16[key] ?: 0L) }.keys
        fun stepAt(p: Long) = if (windowOf(p).first in triplet) step12 else step16
        fun snap(p: Long): Long {
            val (key, ws, we) = windowOf(p)
            val step = if (key in triplet) step12 else step16
            val j = (minOf(p, total) - ws) / step
            val a = ws + j * step
            val b = minOf(a + step, we)
            return if (p - a <= b - p) a else b
        }

        var moved = false
        val snapped = ArrayList<QNote>()
        for (n in g.notes) {
            val rawStart = n.on * unitsPerTick
            val rawEnd = n.off * unitsPerTick
            val s = snap(rawStart)
            var e = if (percussion) s else snap(rawEnd)
            if (s != rawStart || (!percussion && e != rawEnd)) moved = true
            if (s >= total) { losses.add("quantization", "Noten am Dateiende außerhalb des letzten Takts wurden verworfen", trackId); continue }
            if (!percussion && e <= s) e = s + stepAt(s)
            snapped += QNote(n, s, minOf(e, total))
        }
        if (moved) losses.add("quantization", "Einsätze und Dauern wurden auf ein 1/16- bzw. Triolenraster quantisiert", trackId)
        // One note per key and onset.
        val unique = snapped.groupBy { it.start to it.raw.key }.values.map { same -> same.maxWith(compareBy({ it.end }, { it.raw.velocity })) }.toMutableList()
        if (percussion) {
            val onsets = unique.map { it.start }.distinct().sorted().toLongArray()
            for (n in unique) {
                val at = java.util.Arrays.binarySearch(onsets, n.start)
                val next = onsets.getOrNull(if (at >= 0) at + 1 else -at - 1) ?: Long.MAX_VALUE
                val m = locate(n.start)
                val end = minOf(next, n.start + quarter, spans[m].end, total)
                // Percussion playback ignores ties: keep a single untied value (the first, largest piece of the span).
                val first = Rhythm.spell(Fraction.of(n.start - spans[m].start, units), Fraction.of(end - spans[m].start, units),
                    Fraction.of(spans[m].length, units)) { w -> (m * 1024L + w) in triplet }.first()
                n.end = n.start + (first.duration.length() * Fraction.of(units)).let { it.numerator / it.denominator }
            }
        }
        val chords = unique.groupBy { it.start to it.end }.map { (span, notes) -> Chord(span.first, span.second).also { it.notes += notes.sortedBy { n -> n.raw.key } } }
            .sortedWith(compareBy({ it.start }, { -it.end }))
        val voiceEnd = LongArray(4) { Long.MIN_VALUE }
        val voiceLast = arrayOfNulls<Chord>(4)
        val placed = ArrayList<Chord>()
        for (c in chords) {
            val v = (0 until 4).firstOrNull { voiceEnd[it] <= c.start }
            if (v != null) { c.voice = v; voiceEnd[v] = c.end; voiceLast[v] = c; placed += c; continue }
            val host = voiceLast.filterNotNull().firstOrNull { it.start == c.start }
            losses.add("voice_limit", "Mehr als vier gleichzeitige Rhythmusebenen: Noten wurden zusammengelegt oder verworfen", trackId)
            if (host != null) host.notes += c.notes.filter { n -> host.notes.none { it.raw.key == n.raw.key } }
        }
        if (instrument.tuning.isNotEmpty()) assignFrets(placed, instrument, trackId)
        blockQuantizedBendOverlaps(placed, g)
        val bars = buildBars(placed.filter { it.notes.isNotEmpty() }, g, spans, measures, triplet, locate, trackId)
        val volume = g.volume?.let { Math.round(it / 127.0 * 100) / 100.0 } ?: ChannelMix().volume
        val pan = g.pan?.let { Math.round(((it - 64) / 63.0).coerceIn(-1.0, 1.0) * 100) / 100.0 } ?: 0.0
        return Track(trackId, name, instrument, listOf(Staff(ids.next(), DefaultInstruments.notation(kind), bars)), mix = ChannelMix(volume = volume, pan = pan))
    }

    private fun assignFrets(chords: List<Chord>, instrument: InstrumentDefinition, trackId: EntityId) {
        val busyUntil = LongArray(instrument.tuning.size) { Long.MIN_VALUE }
        for ((start, together) in chords.groupBy { it.start }.toSortedMap()) {
            val notes = together.flatMap { it.notes }
            val busy = instrument.tuning.indices.filter { busyUntil[it] > start }.toSet()
            val positions = FretAssigner.assign(notes.map { it.raw.key }, instrument, busy)
            notes.forEachIndexed { i, n ->
                val pos = positions[i]
                if (pos == null) {
                    losses.add("unplayable", "Noten außerhalb von Tonumfang oder Saitenzahl des Instruments wurden verworfen", trackId)
                    together.forEach { it.notes.remove(n) }
                } else {
                    n.string = pos.first; n.fret = pos.second
                    busyUntil[pos.first] = maxOf(busyUntil[pos.first], n.end)
                }
            }
        }
    }

    /**
     * Pitch bend is channel-wide. When quantization makes chords overlap whose raw notes did not, a bend can no longer be
     * attributed to one of them (the exporter drops such bends too); the bends of those chords are dropped with a loss.
     * At most four voices overlap, so the active list stays small.
     */
    private fun blockQuantizedBendOverlaps(chords: List<Chord>, g: Group) {
        if (g.channel == 9 || g.bendTicks.isEmpty()) return
        fun rawOverlap(a: Chord, b: Chord) = a.notes.any { x -> b.notes.any { y -> x.raw.on < y.raw.off && y.raw.on < x.raw.off } }
        val active = ArrayList<Chord>()
        for (c in chords.sortedBy { it.start }) {
            active.removeAll { it.end <= c.start }
            for (o in active) if ((o.start != c.start || o.end != c.end) && !rawOverlap(o, c)) { o.bendBlocked = true; c.bendBlocked = true }
            active += c
        }
    }

    private fun buildBars(chords: List<Chord>, g: Group, spans: List<Span>, measures: List<Measure>, triplet: Set<Long>, locate: (Long) -> Int, trackId: EntityId): List<Bar> {
        // beats[measure][voice]
        val beats = Array(spans.size) { Array(4) { ArrayList<Beat>() } }
        val groupIds = HashMap<Long, EntityId>()
        fun tupletGroup(m: Int, voice: Int, window: Int?): List<EntityId> =
            if (window == null) emptyList() else listOf(groupIds.getOrPut((m * 1024L + window) * 4 + voice) { ids.next() })
        fun lengthOf(m: Int) = Fraction.of(spans[m].length, units)
        fun tripletFn(m: Int): (Int) -> Boolean = { w -> (m * 1024L + w) in triplet }
        for (c in chords) {
            // Split the chord at bar lines and spell each part.
            val pieces = ArrayList<Pair<Int, Rhythm.Piece>>()
            var x = c.start
            while (x < c.end) {
                val m = locate(x)
                val pe = minOf(c.end, spans[m].end)
                Rhythm.spell(Fraction.of(x - spans[m].start, units), Fraction.of(pe - spans[m].start, units), lengthOf(m), tripletFn(m)).forEach { pieces += m to it }
                x = pe
            }
            if (pieces.isEmpty()) continue
            val noteIds = c.notes.map { List(pieces.size) { ids.next() } }
            for ((k, mp) in pieces.withIndex()) {
                val (m, piece) = mp
                val from = spans[m].start + piece.offset.toDouble() * units
                val to = from + piece.duration.length().toDouble() * units
                // Pitch bend is channel-wide: notes quantized into one chord share the first note's curve (per-note curves
                // from different raw note spans could not be exported again and would be dropped).
                val bends = c.notes.map { n -> bendPiece(n, g, c, from, to) }.let { list ->
                    if (!c.bendBlocked || list.all { it == null }) list
                    else { losses.add("pitch_bend_overlap", "Pitch-Bend überlappender, erst durch die Quantisierung gleichzeitiger Noten wurde verworfen", trackId); list.map { null } }
                }
                val shared = bends.firstOrNull { it != null }
                val notes = c.notes.mapIndexed { i, n ->
                    val link = if (k + 1 < pieces.size) listOf(NoteLink(LinkKind.TIE, noteIds[i][k + 1])) else emptyList()
                    val bend = if (bends.distinct().size > 1) shared else bends[i]
                    Note(noteIds[i][k], Pitch(n.raw.key), n.string, n.fret, n.raw.velocity.coerceIn(1, 127), listOfNotNull(bend), link)
                }
                beats[m][c.voice] += Beat(ids.next(), piece.offset, piece.duration, notes, tupletGroupIds = tupletGroup(m, c.voice, piece.tripletWindow))
            }
        }
        return spans.indices.map { m ->
            Bar(ids.next(), measures[m].id, List(4) { v ->
                val list = beats[m][v].sortedBy { it.offset }
                Voice(ids.next(), v, if (list.isEmpty()) list else withRests(list, lengthOf(m), m, v, tripletFn(m), ::tupletGroup))
            })
        }
    }

    private fun withRests(beats: List<Beat>, length: Fraction, m: Int, voice: Int, triplet: (Int) -> Boolean, group: (Int, Int, Int?) -> List<EntityId>): List<Beat> {
        val out = ArrayList<Beat>()
        var cursor = Fraction.ZERO
        fun gap(from: Fraction, to: Fraction) {
            if (from < to) Rhythm.spell(from, to, length, triplet).forEach { out += Beat(ids.next(), it.offset, it.duration, explicitRest = true, tupletGroupIds = group(m, voice, it.tripletWindow)) }
        }
        for (b in beats) {
            gap(cursor, b.offset)
            out += b
            cursor = maxF(cursor, b.offset + b.duration.length())
        }
        gap(cursor, length)
        return out
    }

    /** Pitch-bend events during the note as a bend curve for the part [from, to) of the quantized note (units). */
    private fun bendPiece(n: QNote, g: Group, c: Chord, from: Double, to: Double): Articulation.Bend? {
        if (g.channel == 9 || g.bendTicks.isEmpty()) return null
        val on = n.raw.on
        val off = maxOf(n.raw.off, on + 1)
        val i = java.util.Collections.binarySearch(g.bendTicks, on).let { if (it >= 0) { var k = it; while (k + 1 < g.bendTicks.size && g.bendTicks[k + 1] == on) k++; k } else -it - 2 }
        val startValue = if (i >= 0) g.bendValues[i] else 8192
        fun cents(v: Int) = Math.round((v - 8192) * g.bendRange / 8192.0).toInt()
        // Map to the piece of the quantized note.
        val noteSpan = (c.end - c.start).toDouble()
        val a = (from - c.start) / noteSpan
        val b = (to - c.start) / noteSpan
        // Bend events inside the raw note are indices [first, end); only those around this piece are needed. Binary searches keep
        // a bend flood linear overall instead of (events x pieces) or (events x points).
        val firstEvent = i + 1
        fun lowerBound(tick: Double): Int { var lo = maxOf(firstEvent, 0); var hi = g.bendTicks.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (g.bendTicks[m] < tick) lo = m + 1 else hi = m }; return lo }
        val end = lowerBound(off.toDouble())
        val span = (off - on).toDouble()
        val lastValue = if (end - 1 >= firstEvent) g.bendValues[end - 1] else startValue
        // Last event at or before a, first event at or after b (clamped to the note's events).
        val lo = (lowerBound(on + a * span + 1e-9) - 1).coerceAtLeast(firstEvent)
        val hi = minOf(lowerBound(on + b * span), end - 1)
        val raw = ArrayList<Pair<Double, Int>>()
        if (lo >= end || (g.bendTicks[lo] - on) / span > a) raw += 0.0 to cents(startValue)
        for (k in lo..hi) if (k in firstEvent until end) raw += (g.bendTicks[k] - on).toDouble() / span to cents(g.bendValues[k])
        if (hi + 1 >= end || raw.isEmpty() || raw.last().first < b) raw += 1.0 to cents(lastValue)
        if (raw.all { it.second == 0 }) return null
        fun valueAt(x: Double): Double {
            if (x <= raw.first().first) return raw.first().second.toDouble()
            var l = 1; var h = raw.size
            while (l < h) { val m = (l + h) ushr 1; if (raw[m].first < x) l = m + 1 else h = m }
            if (l >= raw.size) return raw.last().second.toDouble()
            val (x0, y0) = raw[l - 1]; val (x1, y1) = raw[l]
            return if (x1 <= x0) y1.toDouble() else y0 + (x - x0) / (x1 - x0) * (y1 - y0)
        }
        val xs = (listOf(a, b) + raw.map { it.first }.filter { it > a && it < b }).sorted()
        // Several events can fall on one 1/96 slot: keep the one nearest to the slot (the last on a tie), so a re-exported
        // curve, whose points sit exactly on their slots, imports to the same values.
        var points = xs.map { x -> val pos = (x - a) / (b - a) * 96; Triple(Math.round(pos).coerceIn(0, 96), Math.round(valueAt(x)).toInt(), pos) }
            .groupBy { it.first }.map { (slot, same) -> same.withIndex().minWith(compareBy({ Math.abs(it.value.third - slot) }, { -it.index })).value }
            .sortedBy { it.first }.map { it.first to it.second }
        points = simplify(points)
        if (points.all { it.second == 0 }) return null
        val curve = points.map { PitchCurvePoint(Fraction.of(it.first, 96), it.second) }
        val first = curve.first().cents
        val peak = curve.maxOf { it.cents }
        val last = curve.last().cents
        val kind = when {
            first != 0 && last < first -> BendKind.PREBEND_RELEASE
            first != 0 -> BendKind.PREBEND
            peak > last -> BendKind.RELEASE
            else -> BendKind.BEND
        }
        return Articulation.Bend(curve, kind)
    }

    /** Drops points that lie on the straight line between their neighbours (within one cent). */
    private fun simplify(points: List<Pair<Long, Int>>): List<Pair<Long, Int>> {
        if (points.size <= 2) return points
        val out = ArrayList<Pair<Long, Int>>()
        out += points.first()
        for (k in 1 until points.size - 1) {
            val p = out.last(); val q = points[k]; val r = points[k + 1]
            val expected = if (r.first == p.first) q.second.toDouble() else p.second + (q.first - p.first).toDouble() / (r.first - p.first) * (r.second - p.second)
            if (abs(expected - q.second) > 1.0) out += q
        }
        out += points.last()
        return if (out.size > 64) out.filterIndexed { k, _ -> k == 0 || k == out.lastIndex || k % ((out.size + 63) / 64) == 0 } else out
    }
}
