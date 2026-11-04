package dev.riffworkbench.adapter.formats.midi

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.playback.Timing
import dev.riffworkbench.playback.UnfoldingRepeatCompiler
import dev.riffworkbench.ports.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * C-FORMAT: Standard MIDI File type 1 export at 960 PPQ (1 whole note = 3840 ticks) for DAW hand-off.
 *
 * - The PLAYED order is exported: repeats, endings, D.C./D.S./Coda/Fine are unfolded by the same
 *   [RepeatCompiler] the app plays and renders, so tick 0 is frame 0 of every rendered WAV stem.
 * - Notes come from [ScorePlaybackCompiler] (ties merged, let-ring, staccato, grace notes, strums, tremolo,
 *   trills, dynamics/accents, drum techniques), compiled on a constant "tick clock" (1000 QPM at 16 kHz,
 *   i.e. 3840 frames per whole note) so that frames are exact ticks.
 * - Real time lives only in the conductor track: the tempo map is derived from [Timing.measureTimings]
 *   per played measure, including tempo changes inside bars, swing and fermatas, so MIDI time equals audio time
 *   up to the integer microseconds-per-quarter resolution of SMF tempo events.
 * - Pitch bend is channel-wide: per-note curves (bends, whammy, slides, microtones) are written only when no
 *   other note overlaps on that channel, or when all overlapping notes share the identical curve (e.g. a power
 *   chord slide). Otherwise the curve is dropped and a [LossEntry] is reported. The bend range is set via RPN 0.
 */
class MidiExporter(private val repeats: RepeatCompiler = UnfoldingRepeatCompiler()) : ScoreExporter {
    override val formatId: String = "midi"

    override suspend fun export(request: ExportRequest, progress: (OperationProgress) -> Unit): Outcome<ExportResult> {
        if (request.formatId != formatId) return formatFailure(FailureCode.VALIDATION, "Exportformat ${request.formatId} passt nicht zum MIDI-Export")
        return exportNow(request.project, progress)
    }

    fun exportNow(project: Project, progress: (OperationProgress) -> Unit = {}): Outcome<ExportResult> = guarded("MIDI-Export", FailureCode.VALIDATION) {
        exportBlocker(project)?.let { return it }
        val route = when (val r = repeats.compile(project)) { is Outcome.Failure -> return formatFailure(r.code, "MIDI-Export: ${r.message}", r.diagnostics); is Outcome.Success -> r.value }
        progress(OperationProgress(0, 3, "Route"))
        val plan = when (val r = ScorePlaybackCompiler(repeats).compileNow(tickClock(project), TICK_CLOCK_RATE)) {
            is Outcome.Failure -> return formatFailure(r.code, "MIDI-Export: ${r.message}", r.diagnostics)
            is Outcome.Success -> r.value
        }
        progress(OperationProgress(1, 3, "Ereignisse"))
        val bytes = MidiWriter(project, route, plan).write()
        progress(OperationProgress(3, 3, "Fertig"))
        Outcome.Success(ExportResult(bytes.first, MEDIA_TYPE, exportFileName(project, "mid"), bytes.second))
    }

    companion object {
        const val PPQ = 960
        const val TICKS_PER_WHOLE = 3840L
        const val MEDIA_TYPE = "audio/midi"
        /** 1000 QPM at 16 kHz gives exactly 3840 frames per whole note. */
        internal const val TICK_CLOCK_QPM = 1000.0
        internal const val TICK_CLOCK_RATE = 16_000
        /** Pitch offsets below this many cents are not worth a channel-wide pitch bend (e.g. just-intonation harmonics). */
        internal const val BEND_THRESHOLD_CENTS = 5.0
        internal const val BEND_STEP_TICKS = 30L
        /** Same cap the sequencer uses for let-ring, measured in real time. */
        internal const val LET_RING_MICROS = 8_000_000.0

        /** Same project, but straight, without fermatas and at the constant tick-clock tempo. */
        internal fun tickClock(project: Project): Project = project.copy(
            tempo = listOf(TempoChange(ScorePosition(project.measures.first().id), TICK_CLOCK_QPM)),
            measures = project.measures.map { if (it.feel == RhythmicFeel.STRAIGHT) it else it.copy(feel = RhythmicFeel.STRAIGHT) },
            tracks = project.tracks.map { t -> t.copy(staves = t.staves.map { s -> s.copy(bars = s.bars.map { b -> b.copy(voices = b.voices.map { v ->
                if (v.beats.none { it.fermata != null }) v else v.copy(beats = v.beats.map { if (it.fermata == null) it else it.copy(fermata = null) })
            }) }) }) },
        )

        internal fun exactTicks(f: Fraction): Long? {
            val scaled = f * Fraction.of(TICKS_PER_WHOLE)
            return if (scaled.isInteger()) scaled.numerator else null
        }
    }
}

/** Tempo map of an exported file: tick <-> microseconds (used for let-ring caps and by tests). */
internal class TempoMap(private val ticks: LongArray, private val microsPerQuarter: LongArray, private val ppq: Int) {
    private val startMicros = DoubleArray(ticks.size).also { for (i in 1 until ticks.size) it[i] = it[i - 1] + (ticks[i] - ticks[i - 1]) * microsPerQuarter[i - 1].toDouble() / ppq }
    private fun segment(tick: Long): Int { var i = 0; while (i + 1 < ticks.size && ticks[i + 1] <= tick) i++; return i }
    fun micros(tick: Long): Double {
        if (ticks.isEmpty()) return tick * 500_000.0 / ppq
        val i = segment(tick)
        return startMicros[i] + (tick - ticks[i]) * microsPerQuarter[i].toDouble() / ppq
    }
    fun tickAt(micros: Double): Long {
        if (ticks.isEmpty()) return (micros * ppq / 500_000.0).toLong()
        var i = 0
        while (i + 1 < ticks.size && startMicros[i + 1] <= micros) i++
        return ticks[i] + ((micros - startMicros[i]) * ppq / microsPerQuarter[i]).toLong()
    }
}

private class MidiWriter(private val project: Project, private val route: List<PlaybackOccurrence>, private val plan: PlaybackPlan) {
    private val losses = LossLog("export")
    private val measureIndex = project.measures.withIndex().associate { it.value.id to it.index }
    private val routeStart = LongArray(route.size + 1)
    private val tempoTicks = ArrayList<Long>()
    private val tempoMicros = ArrayList<Long>()

    private class MidiNote(
        val trackIndex: Int, val channel: Int, val key: Int, val velocity: Int, var start: Long, var end: Long,
        /** Offsets in cents relative to the key incl. residual; ticks relative to [start]. Empty when no bend is needed. */
        val curve: List<CurvePoint>, val features: Set<String>,
    ) { var bent = false }

    fun write(): Pair<ByteArray, List<LossEntry>> {
        for (i in route.indices) {
            val span = plan.spans[i]
            routeStart[i] = Math.round(span.frames.first())
            routeStart[i + 1] = Math.round(span.frames.last())
        }
        val totalTicks = routeStart[route.size]
        checkResolution()
        val conductor = conductor(totalTicks)
        val tempoMap = TempoMap(tempoTicks.toLongArray(), tempoMicros.toLongArray(), MidiExporter.PPQ)
        val channels = assignChannels()
        val notes = collectNotes(channels, tempoMap)
        val byChannel = notes.groupBy { it.channel }
        val rangeByChannel = HashMap<Int, Int>()
        val bendEvents = HashMap<Int, MutableList<Triple<Long, Int, Int>>>() // channel -> (tick, priority, value)
        val baseByChannel = HashMap<Int, Double>()
        byChannel.values.forEach { fixUnisons(it) }
        for ((ti, track) in project.tracks.withIndex()) {
            val ch = channels[ti]
            if (ch == PERCUSSION_CHANNEL) continue
            val base = track.instrument.fineTuneCents.takeIf { it.isFinite() } ?: 0.0
            val existing = baseByChannel[ch]
            if (existing == null) baseByChannel[ch] = base
            else if (abs(existing - base) > 0.5) losses.add("fine_tune", "Feinstimmung kollidiert mit einer anderen Spur auf demselben MIDI-Kanal", track.id)
        }
        for ((channel, list) in byChannel) {
            if (channel == PERCUSSION_CHANNEL) continue
            val alive = list.filter { it.end > it.start }
            bends(channel, alive, baseByChannel[channel] ?: 0.0, rangeByChannel, bendEvents)
        }
        val trackChunks = ArrayList<ByteArray>()
        trackChunks += conductor.encode(totalTicks)
        val rpnSent = HashSet<Int>()
        for ((ti, track) in project.tracks.withIndex()) {
            val ch = channels[ti]
            val w = SmfTrackWriter()
            w.text(0, 0x03, track.name)
            w.channel(0, SmfTrackWriter.CONTROL, 0xC0 or ch, track.instrument.midiProgram.coerceIn(0, 127))
            w.channel(0, SmfTrackWriter.CONTROL, 0xB0 or ch, 7, (track.mix.volume.takeIf { it.isFinite() } ?: 0.8).coerceIn(0.0, 1.0).times(127).roundToLong().toInt())
            w.channel(0, SmfTrackWriter.CONTROL, 0xB0 or ch, 10, (64 + (track.mix.pan.takeIf { it.isFinite() } ?: 0.0).coerceIn(-1.0, 1.0) * 63).roundToLong().toInt().coerceIn(0, 127))
            if (ch != PERCUSSION_CHANNEL && rpnSent.add(ch)) {
                val range = rangeByChannel[ch]
                val base = baseByChannel[ch] ?: 0.0
                if (range != null || abs(base) >= MidiExporter.BEND_THRESHOLD_CENTS) {
                    val semis = range ?: maxOf(2, ceil(abs(base) / 100.0).toInt()).coerceAtMost(24)
                    rangeByChannel[ch] = semis
                    for ((cc, value) in listOf(101 to 0, 100 to 0, 6 to semis, 38 to 0, 101 to 127, 100 to 127)) w.channel(0, SmfTrackWriter.CONTROL, 0xB0 or ch, cc, value)
                    if (abs(base) >= MidiExporter.BEND_THRESHOLD_CENTS) bendValue(base, semis).let { w.channel(0, SmfTrackWriter.CONTROL, 0xE0 or ch, it and 0x7F, it shr 7) }
                    bendEvents[ch]?.forEach { (tick, priority, value) -> w.channel(tick, priority, 0xE0 or ch, value and 0x7F, value shr 7) }
                }
            }
            for (n in notes) if (n.trackIndex == ti && n.end > n.start) {
                w.channel(n.start, SmfTrackWriter.NOTE_ON, 0x90 or ch, n.key, n.velocity)
                w.channel(n.end, SmfTrackWriter.NOTE_OFF, 0x80 or ch, n.key, 64)
            }
            trackChunks += w.encode(maxOf(totalTicks, w.lastTick))
        }
        trackLosses()
        return SmfWriter.file(1, MidiExporter.PPQ, trackChunks) to losses.toList()
    }

    // ---- conductor -------------------------------------------------------------------------------------------

    private fun conductor(totalTicks: Long): SmfTrackWriter {
        val w = SmfTrackWriter()
        project.metadata.title.takeIf { it.isNotBlank() }?.let { w.text(0, 0x03, it) }
        project.metadata.copyright.takeIf { it.isNotBlank() }?.let { w.text(0, 0x02, it) }
        val timings = Timing.measureTimings(project)
        val sections = project.sections.groupBy { it.measureId }
        val texts = project.text.groupBy { it.position.measureId }
        var meter: Pair<Int, Int>? = null
        var key: KeySignature? = null
        var tempo = -1L
        for ((i, occurrence) in route.withIndex()) {
            val m = measureIndex.getValue(occurrence.position.measureId)
            val measure = project.measures[m]
            val start = routeStart[i]
            val thisMeter = meterOf(measure)
            if (thisMeter != meter) {
                meter = thisMeter
                val (nn, dd) = thisMeter
                val clocks = if (dd == 8 && nn % 3 == 0 && nn > 3) 36 else maxOf(1, 96 / dd)
                w.meta(start, 0x58, byteArrayOf(nn.toByte(), Integer.numberOfTrailingZeros(dd).toByte(), clocks.toByte(), 8))
            }
            if (measure.key != key) {
                key = measure.key
                w.meta(start, 0x59, byteArrayOf(measure.key.fifths.coerceIn(-7, 7).toByte(), (if (measure.key.minor) 1 else 0).toByte()))
            }
            sections[measure.id]?.forEach { w.text(start, 0x06, it.label) }
            texts[measure.id]?.forEach { t ->
                val tick = start + Math.round(t.position.offset.toDouble() * MidiExporter.TICKS_PER_WHOLE)
                w.text(tick, if (t.kind == TextKind.LYRICS) 0x05 else 0x01, t.text)
            }
            val timing = timings[m]
            for (k in 1 until timing.offsets.size) {
                val t0 = start + Math.round(timing.offsets[k - 1] * MidiExporter.TICKS_PER_WHOLE)
                val t1 = start + Math.round(timing.offsets[k] * MidiExporter.TICKS_PER_WHOLE)
                if (t1 <= t0) continue
                val seconds = timing.seconds[k] - timing.seconds[k - 1]
                var micros = Math.round(seconds * 1e6 * MidiExporter.PPQ / (t1 - t0))
                if (micros !in 1..0xFFFFFF) {
                    losses.add("tempo_range", "Tempo außerhalb des MIDI-Bereichs (3,6–60.000.000 BPM) wurde begrenzt")
                    micros = micros.coerceIn(1, 0xFFFFFF)
                }
                if (micros != tempo) {
                    tempo = micros
                    w.meta(t0, 0x51, byteArrayOf((micros shr 16).toByte(), (micros shr 8).toByte(), micros.toByte()))
                    tempoTicks += t0; tempoMicros += micros
                }
            }
        }
        if (tempoTicks.isEmpty()) { w.meta(0, 0x51, byteArrayOf(0x07, 0xA1.toByte(), 0x20)); tempoTicks += 0L; tempoMicros += 500_000L }
        return w
    }

    /** Time signature of a played measure; a pickup is written as its own short meter. */
    private fun meterOf(measure: Measure): Pair<Int, Int> {
        val pickup = measure.pickupLength ?: return measure.signature.numerator to measure.signature.denominator
        var d = measure.signature.denominator.toLong()
        while (d <= 64) {
            val n = pickup * Fraction.of(d)
            if (n.isInteger() && n.numerator in 1..127) return n.numerator.toInt() to d.toInt()
            d *= 2
        }
        losses.add("pickup_meter", "Auftakt- bzw. Sondertaktlänge ist nicht als MIDI-Taktart darstellbar", measure.id)
        return measure.signature.numerator to measure.signature.denominator
    }

    private fun checkResolution() {
        for (m in project.measures) if (MidiExporter.exactTicks(m.pickupLength ?: m.signature.length()) == null)
            losses.add("tick_resolution", "Taktlänge ist in 960 PPQ nicht exakt darstellbar und wurde auf den nächsten Tick gerundet", m.id)
        for (track in project.tracks) for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) {
            if (MidiExporter.exactTicks(beat.offset) == null || MidiExporter.exactTicks(beat.offset + beat.duration.length()) == null)
                losses.add("tick_resolution", "Notenwert ist in 960 PPQ nicht exakt darstellbar und wurde auf den nächsten Tick gerundet", track.id)
        }
    }

    // ---- notes -----------------------------------------------------------------------------------------------

    private fun assignChannels(): IntArray {
        val melodic = (0..15).filter { it != PERCUSSION_CHANNEL }
        var next = 0
        val result = IntArray(project.tracks.size)
        for ((i, track) in project.tracks.withIndex()) {
            result[i] = if (track.instrument.kind == InstrumentKind.PERCUSSION) PERCUSSION_CHANNEL else melodic[next++ % melodic.size]
            if (track.instrument.kind != InstrumentKind.PERCUSSION && next > melodic.size)
                losses.add("channel_shared", "Mehr als 15 melodische Spuren: Kanal wird geteilt, Programmwechsel und Pitch-Bend können kollidieren", track.id)
        }
        return result
    }

    private fun collectNotes(channels: IntArray, tempoMap: TempoMap): List<MidiNote> {
        val out = ArrayList<MidiNote>(plan.notes.size)
        for (sn in plan.notes) {
            val track = project.tracks[sn.trackIndex]
            val percussion = track.instrument.kind == InstrumentKind.PERCUSSION
            val fine = if (percussion) 0.0 else track.instrument.fineTuneCents.takeIf { it.isFinite() } ?: 0.0
            val own = if (percussion) sn.soundingPitch else sn.soundingPitch - fine / 100.0
            val key = Math.round(own).toInt()
            if (key !in 0..127) { losses.add("pitch_range", "Tonhöhe außerhalb von MIDI 0–127 wurde weggelassen", track.id); continue }
            var start = sn.frame
            var end = sn.endFrame
            if (sn.letRing) {
                val cap = tempoMap.tickAt(tempoMap.micros(start) + MidiExporter.LET_RING_MICROS)
                if (end > cap) end = maxOf(start + 1, cap)
            }
            // Harmonics keep their just-intonation pitch only to the nearest key; their timbre is a reported loss anyway.
            val residual = if (sn.harmonic != null) 0.0 else (own - key) * 100.0
            val scrape = sn.articulations.any { it is Articulation.Mark && it.kind == NoteMark.PICK_SCRAPE }
            val features = LinkedHashSet<String>()
            sn.articulations.forEach { a ->
                when (a) {
                    is Articulation.Bend -> features += "bend"
                    is Articulation.Whammy -> features += "whammy"
                    is Articulation.Slide -> features += "slide"
                    else -> Unit
                }
            }
            if (abs(sn.pitch.cents) >= MidiExporter.BEND_THRESHOLD_CENTS) features += "microtone"
            val curve = if (percussion || scrape) emptyList() else {
                val raw = if (sn.curve.isEmpty()) listOf(CurvePoint(0, 0.0)) else sn.curve
                val shifted = raw.map { CurvePoint(it.frame.coerceIn(0, end - start), it.cents + residual) }
                if (shifted.all { abs(it.cents) < MidiExporter.BEND_THRESHOLD_CENTS }) emptyList() else shifted
            }
            out += MidiNote(sn.trackIndex, channels[sn.trackIndex], key, sn.velocity.coerceIn(1, 127), start, end, curve, features)
        }
        return out
    }

    /** MIDI cannot hold two notes of the same key on one channel: the earlier one ends where the later starts. */
    private fun fixUnisons(list: List<MidiNote>) {
        for ((_, sameKey) in list.groupBy { it.key }) {
            val sorted = sameKey.sortedWith(compareBy({ it.start }, { -it.end }))
            for (i in 1 until sorted.size) {
                val a = sorted[i - 1]; val b = sorted[i]
                if (a.end <= a.start) continue
                if (b.start < a.end) {
                    losses.add("unison", "Gleichzeitige Noten gleicher Tonhöhe auf einem MIDI-Kanal wurden zusammengefasst", project.tracks[b.trackIndex].id)
                    if (b.start == a.start) { if (b.end > a.end) a.end = b.end; b.end = b.start } else a.end = b.start
                }
            }
        }
    }

    private fun bends(channel: Int, list: List<MidiNote>, base: Double, ranges: MutableMap<Int, Int>, events: MutableMap<Int, MutableList<Triple<Long, Int, Int>>>) {
        val sorted = list.sortedBy { it.start }
        if (sorted.none { it.curve.isNotEmpty() }) return
        val maxDuration = sorted.maxOf { it.end - it.start }
        val starts = LongArray(sorted.size) { sorted[it].start }
        val handled = HashSet<MidiNote>()
        val groups = ArrayList<List<MidiNote>>()
        for (b in sorted) {
            if (b.curve.isEmpty() || b in handled) continue
            var lo = java.util.Arrays.binarySearch(starts, b.start - maxDuration).let { if (it < 0) -it - 1 else it }
            while (lo > 0 && starts[lo - 1] >= b.start - maxDuration) lo--
            val overlapping = ArrayList<MidiNote>()
            var k = lo
            while (k < sorted.size && sorted[k].start < b.end) {
                val o = sorted[k]
                if (o !== b && o.end > b.start) overlapping += o
                k++
            }
            handled += b
            if (overlapping.all { it.start == b.start && it.end == b.end && sameCurve(it.curve, b.curve) }) {
                handled += overlapping
                groups += listOf(b) + overlapping
            } else {
                val track = project.tracks[b.trackIndex]
                for (feature in b.features.ifEmpty { setOf("bend") }) losses.add(feature, overlapReason(feature), track.id)
            }
        }
        if (groups.isEmpty()) return
        var maxCents = abs(base)
        groups.forEach { g -> g.first().curve.forEach { maxCents = maxOf(maxCents, abs(base + it.cents)) } }
        if (maxCents > 2400) groups.forEach { losses.add("bend_range", "Tonhöhenverlauf überschreitet ±24 Halbtöne und wurde begrenzt", project.tracks[it.first().trackIndex].id) }
        val semis = ceil(maxCents / 100.0 - 1e-9).toInt().coerceIn(2, 24)
        ranges[channel] = semis
        val out = events.getOrPut(channel) { ArrayList() }
        val center = bendValue(base, semis)
        for (g in groups) {
            val b = g.first()
            g.forEach { it.bent = true }
            val duration = b.end - b.start
            var last = Int.MIN_VALUE
            fun emit(offset: Long, priority: Int) {
                val v = bendValue(base + ScorePlaybackCompiler.valueAt(b.curve, offset), semis)
                if (v != last) { out += Triple(b.start + offset, priority, v); last = v }
            }
            emit(0, SmfTrackWriter.CONTROL)
            val points = b.curve.map { it.frame }.filter { it in 1 until duration }.toSortedSet()
            var t = MidiExporter.BEND_STEP_TICKS
            while (t < duration) { points += t; t += MidiExporter.BEND_STEP_TICKS }
            for (p in points) emit(p, SmfTrackWriter.CONTROL)
            // A curve that ends exactly at the note end (slide into the next note) reaches its target one tick earlier.
            if (duration > 1 && b.curve.any { it.frame >= duration }) {
                val v = bendValue(base + ScorePlaybackCompiler.valueAt(b.curve, duration), semis)
                if (v != last) { out += Triple(b.end - 1, SmfTrackWriter.CONTROL, v); last = v }
            }
            if (last != center) out += Triple(b.end, SmfTrackWriter.CONTROL, center)
        }
    }

    private fun sameCurve(a: List<CurvePoint>, b: List<CurvePoint>): Boolean =
        a.size == b.size && a.indices.all { a[it].frame == b[it].frame && abs(a[it].cents - b[it].cents) < 1.0 }

    private fun overlapReason(feature: String): String = when (feature) {
        "whammy" -> "Whammy-Bar-Verlauf überlappt mit anderen Noten des MIDI-Kanals; Pitch-Bend ist kanalweit und wurde weggelassen"
        "slide" -> "Slide-Tonhöhenverlauf überlappt mit anderen Noten des MIDI-Kanals; Pitch-Bend ist kanalweit und wurde weggelassen"
        "microtone" -> "Mikrotonale Abweichung überlappt mit anderen Noten des MIDI-Kanals; Pitch-Bend ist kanalweit und wurde weggelassen"
        else -> "Bending überlappt mit anderen Noten des MIDI-Kanals; Pitch-Bend ist kanalweit und wurde weggelassen"
    }

    private fun bendValue(cents: Double, semis: Int): Int = (8192 + Math.round(cents / (semis * 100.0) * 8192)).toInt().coerceIn(0, 16383)

    // ---- losses ----------------------------------------------------------------------------------------------

    private fun trackLosses() {
        for (track in project.tracks) {
            val id = track.id
            val fretted = track.instrument.tuning.isNotEmpty() && track.instrument.kind != InstrumentKind.PERCUSSION
            var hasPositions = false
            for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) {
                if (beat.strum?.kind == StrumKind.RASGUEADO) losses.add("rasgueado", "Rasgueado wird als einfacher Strum exportiert", id)
                for (note in beat.notes) {
                    if (note.string != null) hasPositions = true
                    if (note.fingering != null) losses.add("fingering", "Fingersätze werden in MIDI nicht übertragen", id)
                    if (note.links.any { it.kind != LinkKind.TIE }) losses.add("legato", "Hammer-on, Pull-off, Legato und Bindebögen werden als neu angeschlagene Noten exportiert", id)
                    for (a in note.articulations) when (a) {
                        is Articulation.Mark -> when (a.kind) {
                            NoteMark.PALM_MUTE -> losses.add("palm_mute", "Palm-Mute hat in General MIDI keine Entsprechung; die Noten klingen normal", id)
                            NoteMark.DEAD -> losses.add("dead_note", "Dead Notes werden als normale Noten mit Tonhöhe exportiert", id)
                            NoteMark.PICK_SCRAPE -> losses.add("pick_scrape", "Pick-Scrape wird als normale Note ohne Geräuschverlauf exportiert", id)
                            NoteMark.TAPPING, NoteMark.SLAP, NoteMark.POP -> losses.add("attack", "Tapping, Slap und Pop werden als normale Anschläge exportiert", id)
                            NoteMark.HAMMER_ON, NoteMark.PULL_OFF -> losses.add("legato", "Hammer-on, Pull-off, Legato und Bindebögen werden als neu angeschlagene Noten exportiert", id)
                            else -> Unit
                        }
                        is Articulation.Slide -> if (a.kind == SlideKind.LEGATO) losses.add("legato", "Hammer-on, Pull-off, Legato und Bindebögen werden als neu angeschlagene Noten exportiert", id)
                        is Articulation.Harmonic -> losses.add("harmonic", "Flageoletts werden mit klingender Tonhöhe, aber ohne Flageolett-Klangfarbe exportiert", id)
                        is Articulation.Vibrato -> losses.add("vibrato", "Vibrato wird in MIDI nicht übertragen", id)
                        is Articulation.Pickstroke -> losses.add("pickstroke", "Anschlagrichtung wird in MIDI nicht übertragen", id)
                        is Articulation.Percussion -> if (a.kind == PercussionTechnique.RIMSHOT || a.kind == PercussionTechnique.CHOKE) losses.add("drum_stroke", "Rimshot und Choke werden als normale Schläge exportiert", id)
                        else -> Unit
                    }
                }
            }
            if (fretted && hasPositions) losses.add("tab_position", "Saiten- und Bundangaben werden in MIDI nicht gespeichert", id)
            if (track.mix.mute || track.mix.solo) losses.add("track_mute", "Mute und Solo werden in MIDI nicht gespeichert", id)
            if (track.mix.effects.isNotEmpty()) losses.add("effects", "Effektketten der Spur werden nicht exportiert", id)
            if (track.sound.bankId != null || track.sound.presetId != null || track.sound.originalRseData != null)
                losses.add("sound_assignment", "Klangzuordnung wird nur als General-MIDI-Programm exportiert", id)
            if (track.instrument.kind == InstrumentKind.PERCUSSION && track.instrument.drumKit.any { it.volume != 1.0 || it.pan != 0.0 })
                losses.add("drum_mix", "Lautstärke und Panorama einzelner Drum-Elemente werden nicht exportiert", id)
        }
        if (project.audio.clips.isNotEmpty()) losses.add("audio_track", "Audiospuren werden nicht in MIDI exportiert")
        if (project.mix.automation.isNotEmpty()) losses.add("automation", "Mix-Automation wird nicht exportiert")
        if (project.mix.buses.isNotEmpty() || project.mix.master.effects.isNotEmpty()) losses.add("mix_bus", "Busse und Master-Effekte werden nicht exportiert")
        if (project.chords.isNotEmpty()) losses.add("chord_symbols", "Akkordsymbole und Griffbilder werden nicht exportiert")
    }

    private companion object { const val PERCUSSION_CHANNEL = 9 }
}
