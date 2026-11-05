package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * C-SEQUENCER: exact score -> per-note performance events with absolute frames.
 * Runs off the real-time thread. Every note owns its pitch curve; string monophony, ties, legato,
 * slides and let-ring are resolved per (track, string) so a bend never touches another string.
 */
class ScorePlaybackCompiler(private val repeats: RepeatCompiler = UnfoldingRepeatCompiler()) : PlaybackCompiler {
    override suspend fun compile(project: Project, sampleRate: Int, options: PlaybackOptions): Outcome<PlaybackPlan> = compileNow(project, sampleRate, options)

    fun compileNow(project: Project, sampleRate: Int, options: PlaybackOptions = PlaybackOptions()): Outcome<PlaybackPlan> {
        if (sampleRate !in 8_000..192_000) return Outcome.Failure(FailureCode.VALIDATION, "Ungültige Samplerate $sampleRate", contractId = "C-SEQUENCER")
        val route = when (val r = repeats.compile(project)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val spans = Timing.spans(project, route, sampleRate, options.speed)
        val builder = Builder(project, sampleRate)
        for (span in spans) for ((trackIndex, track) in project.tracks.withIndex()) for (staff in track.staves) {
            val bar = staff.bars.getOrNull(span.measureIndex)?.takeIf { it.measureId == span.measureId } ?: staff.bars.firstOrNull { it.measureId == span.measureId } ?: continue
            for (voice in bar.voices) for (beat in voice.beats) if (beat.notes.isNotEmpty() && !beat.explicitRest) builder.beat(span, trackIndex, track, beat)
        }
        val notes = builder.finish()
        val clicks = clicks(project, spans)
        return Outcome.Success(PlaybackPlan(project.revision, sampleRate, route, notes, spans, clicks, options.speed, project.tracks.map { it.id }))
    }

    private fun clicks(project: Project, spans: List<RouteSpan>): List<MetronomeClick> = buildList {
        for (span in spans) {
            val signature = project.measures[span.measureIndex].signature
            val beat = if (signature.denominator == 8 && signature.numerator % 3 == 0 && signature.numerator > 3) Fraction.of(3, 8) else Fraction.of(1, signature.denominator.toLong())
            var offset = Fraction.ZERO
            var k = 0
            while (offset < span.length && k < 256) {
                add(MetronomeClick(span.frameAt(offset.toDouble()).roundToLong(), if (k == 0) 0 else 1))
                offset += beat; k++
            }
        }
    }

    private class Ev(
        val noteId: EntityId, val trackIndex: Int, val trackId: EntityId, var frame: Double, var end: Double,
        val pitch: Pitch, var velocity: Int, val string: Int?, val articulations: List<Articulation>,
        val routeIndex: Int, var sounding: Double, val marks: Set<NoteMark>, var links: List<NoteLink>,
        var slide: Articulation.Slide?, val grace: Boolean,
    ) {
        val curve = ArrayList<CurvePoint>()
        var vibrato: VibratoSpec? = null
        var attack = AttackKind.PICK
        var palmMute = false
        var dead = false
        var harmonic: HarmonicKind? = null
        var letRing = false
        var stroke = DrumStroke.NORMAL
        var choke: Int? = null
        var gain = 1.0
        var pan = 0.0
        var removed = false
        var tremolo: WrittenDuration? = null
        var trill: Pair<Double, WrittenDuration>? = null
        var spanRef: RouteSpan? = null
        val legatoOut: Boolean get() = NoteMark.HAMMER_ON in marks || NoteMark.PULL_OFF in marks || links.any { it.kind == LinkKind.HAMMER_ON || it.kind == LinkKind.PULL_OFF }
    }

    private class Builder(val project: Project, val sampleRate: Int) {
        val events = ArrayList<Ev>()
        val maxRing = 8.0 * sampleRate

        fun beat(span: RouteSpan, trackIndex: Int, track: Track, beat: Beat) {
            val length = span.length.toDouble()
            val startOffset = beat.offset.toDouble().coerceIn(0.0, length)
            val endOffset = (beat.offset + beat.duration.length()).toDouble().coerceIn(startOffset, length)
            val f0 = span.frameAt(startOffset)
            val f1 = max(f0 + 1, span.frameAt(endOffset))
            val strum = beat.strum
            val ordered = if (strum != null && beat.notes.size > 1) {
                val byLowString = beat.notes.sortedBy { it.string ?: 0 }
                if (strum.direction == StrokeDirection.DOWN) byLowString else byLowString.reversed()
            } else beat.notes
            val spread = if (strum == null || beat.notes.size < 2) 0.0 else {
                val whole = when {
                    strum.spread > Fraction.ZERO -> strum.spread.toDouble()
                    strum.kind == StrumKind.ARPEGGIO -> (endOffset - startOffset) * 0.6
                    strum.kind == StrumKind.RASGUEADO -> 1.0 / 32
                    else -> 1.0 / 64
                }
                min(span.frameAt(min(length, startOffset + whole)) - f0, (f1 - f0) * 0.9)
            }
            ordered.forEachIndexed { i, note ->
                val delay = if (ordered.size > 1) spread * i / (ordered.size - 1) else 0.0
                note(span, trackIndex, track, note, f0 + delay, f1, startOffset)
            }
        }

        fun note(span: RouteSpan, trackIndex: Int, track: Track, note: Note, startFrame: Double, endFrame: Double, beatOffset: Double) {
            val instrument = track.instrument
            val marks = note.articulations.filterIsInstance<Articulation.Mark>().map { it.kind }.toSet()
            var start = startFrame
            var end = endFrame
            val grace = note.articulations.filterIsInstance<Articulation.Grace>().firstOrNull()
            if (grace != null) {
                val graceFrames = min((span.frameAt(min(span.length.toDouble(), beatOffset + grace.duration.length().toDouble())) - span.frameAt(beatOffset)), (end - start) * 0.5).coerceAtLeast(1.0)
                val gStart = if (grace.beforeBeat) max(0.0, start - graceFrames) else start
                val gEnd = gStart + graceFrames
                val g = Ev(note.id, trackIndex, track.id, gStart, gEnd, grace.pitch, (note.velocity * 0.8).toInt().coerceIn(1, 127), grace.string ?: note.string, emptyList(), span.routeIndex,
                    grace.pitch.midiNumber + grace.pitch.cents / 100.0 + instrument.fineTuneCents / 100.0, emptySet(), emptyList(), null, true)
                g.attack = AttackKind.GRACE
                events += g
                if (!grace.beforeBeat) start = gEnd
            }
            if (NoteMark.STACCATO in marks) end = start + (end - start) * 0.5
            var velocity = note.velocity
            note.articulations.filterIsInstance<Articulation.Dynamic>().firstOrNull()?.let { velocity = dynamicVelocity(it.level) }
            if (NoteMark.GHOST in marks) velocity = (velocity * 0.55).toInt()
            if (NoteMark.ACCENT in marks) velocity = (velocity * 1.18).toInt()
            if (NoteMark.HEAVY_ACCENT in marks) velocity = (velocity * 1.32).toInt()
            var sounding = note.pitch.midiNumber + note.pitch.cents / 100.0 + instrument.fineTuneCents / 100.0
            val harmonic = note.articulations.filterIsInstance<Articulation.Harmonic>().firstOrNull()
            if (harmonic != null) sounding = harmonicPitch(harmonic, note, instrument) ?: sounding
            // Cents are unchecked in files: keep the sounding pitch in a range every voice can render in bounded time.
            sounding = if (sounding.isFinite()) sounding.coerceIn(-24.0, 151.0) else note.pitch.midiNumber.toDouble()
            val ev = Ev(note.id, trackIndex, track.id, start, max(start + 1, end), note.pitch, velocity.coerceIn(1, 127), note.string, note.articulations, span.routeIndex, sounding, marks, note.links,
                note.articulations.filterIsInstance<Articulation.Slide>().firstOrNull(), false)
            ev.spanRef = span
            ev.palmMute = NoteMark.PALM_MUTE in marks
            ev.dead = NoteMark.DEAD in marks || NoteMark.PICK_SCRAPE in marks
            ev.harmonic = harmonic?.kind
            ev.letRing = NoteMark.LET_RING in marks
            ev.attack = when {
                NoteMark.TAPPING in marks -> AttackKind.TAP
                NoteMark.SLAP in marks -> AttackKind.SLAP
                NoteMark.POP in marks -> AttackKind.POP
                else -> AttackKind.PICK
            }
            if (instrument.kind == InstrumentKind.PERCUSSION) percussion(ev, note, instrument)
            val duration = ev.end - ev.frame
            curves(ev, note, duration)
            if (NoteMark.PICK_SCRAPE in marks) ev.curve.apply { clear(); add(CurvePoint(0, 0.0)); add(CurvePoint(duration.roundToLong(), -1200.0)) }
            note.articulations.filterIsInstance<Articulation.Vibrato>().firstOrNull()?.let { v ->
                ev.vibrato = VibratoSpec(v.depthCents.toDouble().coerceIn(0.0, 200.0), (if (v.rateHz.isFinite()) v.rateHz else 5.0).coerceIn(0.5, 12.0), min(0.08 * sampleRate, duration * 0.25).roundToLong())
            }
            note.articulations.filterIsInstance<Articulation.TremoloPicking>().firstOrNull()?.let { ev.tremolo = it.rate }
            note.articulations.filterIsInstance<Articulation.Trill>().firstOrNull()?.let { t ->
                val s = note.string
                if (s != null && s in instrument.tuning.indices && t.targetFret in 0..instrument.maxFret) ev.trill = (instrument.pitch(s, t.targetFret) - note.pitch.midiNumber).toDouble() to t.rate
            }
            events += ev
        }

        private fun curves(ev: Ev, note: Note, duration: Double) {
            val bend = note.articulations.filterIsInstance<Articulation.Bend>().firstOrNull()
            val whammy = note.articulations.filterIsInstance<Articulation.Whammy>().firstOrNull()
            val a = bend?.points?.let { points(it, duration) } ?: emptyList()
            val b = whammy?.points?.let { points(it, duration) } ?: emptyList()
            val merged = mergeCurves(a, b)
            ev.curve.addAll(merged)
            val slide = ev.slide ?: return
            val window = min(duration * 0.3, 0.12 * sampleRate)
            when (slide.kind) {
                SlideKind.INTO_FROM_BELOW, SlideKind.INTO_FROM_ABOVE -> {
                    val from = if (slide.kind == SlideKind.INTO_FROM_BELOW) -400.0 else 400.0
                    prepend(ev, listOf(CurvePoint(0, from), CurvePoint(window.roundToLong().coerceAtLeast(1), 0.0)))
                }
                SlideKind.OUT_DOWN, SlideKind.OUT_UP -> {
                    val to = if (slide.kind == SlideKind.OUT_DOWN) -1000.0 else 700.0
                    val out = min(duration * 0.45, 0.25 * sampleRate)
                    append(ev, listOf(CurvePoint((duration - out).roundToLong().coerceAtLeast(0), 0.0), CurvePoint(duration.roundToLong(), to)))
                }
                else -> Unit
            }
        }

        private fun points(points: List<PitchCurvePoint>, duration: Double): List<CurvePoint> =
            points.sortedBy { it.position }.map { CurvePoint((it.position.toDouble().coerceIn(0.0, 1.0) * duration).roundToLong(), it.cents.toDouble().coerceIn(-4800.0, 4800.0)) }

        fun finish(): List<ScheduledNote> {
            val groups = events.filter { !it.grace }.groupBy { it.trackIndex to (it.string ?: (1000 + it.pitch.midiNumber)) }
            val percussionTracks = project.tracks.withIndex().filter { it.value.instrument.kind == InstrumentKind.PERCUSSION }.map { it.index }.toSet()
            for ((key, list) in groups) {
                if (key.first in percussionTracks) continue
                val sorted = list.sortedBy { it.frame }
                for (i in sorted.indices) {
                    val e = sorted[i]
                    if (e.removed) continue
                    var nextIndex = i + 1
                    fun next(): Ev? { while (nextIndex < sorted.size && sorted[nextIndex].removed) nextIndex++; return sorted.getOrNull(nextIndex) }
                    // Ties: absorb the tied target into this voice, keeping its later pitch curve.
                    while (true) {
                        val n = next() ?: break
                        val tie = e.links.any { it.kind == LinkKind.TIE && it.target == n.noteId }
                        if (!tie || abs(n.frame - e.end) > 0.004 * sampleRate) break
                        val shift = (n.frame - e.frame).roundToLong()
                        if (n.curve.isNotEmpty()) e.curve.addAll(n.curve.map { CurvePoint(it.frame + shift, it.cents) })
                        if (e.vibrato == null) n.vibrato?.let { e.vibrato = it.copy(startFrame = it.startFrame + shift) }
                        e.end = n.end; e.letRing = e.letRing || n.letRing; e.links = n.links; e.slide = n.slide ?: e.slide
                        n.removed = true
                    }
                    val n = next()
                    if (n != null) {
                        val touching = n.frame <= e.end + 0.004 * sampleRate
                        val slide = e.slide
                        if (slide != null && (slide.kind == SlideKind.SHIFT || slide.kind == SlideKind.LEGATO) && (slide.target == null || slide.target == n.noteId) && touching) {
                            val duration = e.end - e.frame
                            val glide = min(duration * 0.35, 0.15 * sampleRate)
                            append(e, listOf(CurvePoint((duration - glide).roundToLong().coerceAtLeast(0), 0.0), CurvePoint(duration.roundToLong(), (n.sounding - e.sounding) * 100.0)))
                            if (slide.kind == SlideKind.LEGATO) n.attack = AttackKind.LEGATO
                        }
                        if (e.legatoOut && touching) n.attack = AttackKind.LEGATO
                        if (e.links.any { it.kind == LinkKind.LEGATO_SLIDE && it.target == n.noteId } && touching) n.attack = AttackKind.LEGATO
                    }
                    if (e.letRing) e.end = max(e.end, min(n?.frame ?: (e.frame + maxRing), e.frame + maxRing))
                    else if (n != null && n.frame < e.end) e.end = max(e.frame + 1, n.frame)
                }
            }
            val expanded = ArrayList<Ev>(events.size)
            for (e in events) {
                if (e.removed) continue
                val tremolo = e.tremolo
                val trill = e.trill
                val span = e.spanRef
                if ((tremolo != null || trill != null) && span != null) {
                    val rate = (tremolo ?: trill!!.second).length().toDouble()
                    val step = max(1.0, span.frameAt(min(span.length.toDouble(), span.offsetAt(e.frame) + rate)) - e.frame)
                    val count = ceil((e.end - e.frame) / step).toInt().coerceIn(1, 512)
                    for (k in 0 until count) {
                        val s = e.frame + k * step
                        val piece = Ev(e.noteId, e.trackIndex, e.trackId, s, min(e.end, s + step), e.pitch, e.velocity, e.string, e.articulations, e.routeIndex,
                            e.sounding + if (trill != null && k % 2 == 1) trill.first else 0.0, e.marks, e.links, null, false)
                        piece.palmMute = e.palmMute; piece.dead = e.dead; piece.harmonic = e.harmonic
                        piece.attack = if (trill != null && k > 0) AttackKind.LEGATO else if (k == 0) e.attack else AttackKind.PICK
                        if (k == 0) piece.velocity = e.velocity else piece.velocity = (e.velocity * 0.92).toInt().coerceIn(1, 127)
                        expanded += piece
                    }
                } else expanded += e
            }
            return expanded.sortedWith(compareBy({ it.frame }, { it.trackIndex }, { it.string ?: -1 })).map { e ->
                ScheduledNote(e.noteId, e.trackId, e.frame.roundToLong(), max(e.frame.roundToLong() + 1, e.end.roundToLong()), e.pitch, e.velocity, e.string, e.articulations,
                    e.trackIndex, e.routeIndex, e.sounding, normalize(e.curve), e.vibrato, e.attack, e.palmMute, e.dead, e.harmonic, e.letRing, e.stroke, e.choke, e.gain, e.pan)
            }
        }

        private fun percussion(ev: Ev, note: Note, instrument: InstrumentDefinition) {
            var midi = note.pitch.midiNumber
            val perc = note.articulations.filterIsInstance<Articulation.Percussion>().firstOrNull()
            when (perc?.kind) {
                PercussionTechnique.OPEN_HI_HAT -> midi = 46
                PercussionTechnique.CLOSED_HI_HAT -> midi = 42
                PercussionTechnique.PEDAL_HI_HAT -> midi = 44
                PercussionTechnique.CROSS_STICK -> { midi = 37; ev.stroke = DrumStroke.CROSS_STICK }
                PercussionTechnique.RIMSHOT -> ev.stroke = DrumStroke.RIMSHOT
                PercussionTechnique.CHOKE -> ev.stroke = DrumStroke.CHOKE
                PercussionTechnique.GHOST -> ev.velocity = (ev.velocity * 0.45).toInt().coerceIn(1, 127)
                PercussionTechnique.NORMAL, null -> Unit
            }
            ev.sounding = midi.toDouble()
            val element = instrument.drumKit.firstOrNull { it.midiNote == midi }
            ev.choke = perc?.chokeGroup ?: element?.chokeGroup ?: defaultChokeGroup(midi)
            ev.gain = element?.volume?.takeIf { it.isFinite() }?.coerceIn(0.0, 2.0) ?: 1.0
            ev.pan = element?.pan?.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0
        }

        private fun prepend(ev: Ev, points: List<CurvePoint>) {
            val shiftEnd = points.last().frame
            val rest = ev.curve.filter { it.frame > shiftEnd }
            ev.curve.clear(); ev.curve.addAll(points); ev.curve.addAll(rest)
        }
        private fun append(ev: Ev, points: List<CurvePoint>) {
            val from = points.first().frame
            val base = valueAt(ev.curve, from)
            val kept = ev.curve.filter { it.frame < from }
            ev.curve.clear(); ev.curve.addAll(kept); ev.curve.addAll(points.map { CurvePoint(it.frame, it.cents + base) })
        }
    }

    companion object {
        fun dynamicVelocity(level: DynamicLevel) = when (level) {
            DynamicLevel.PPP -> 20; DynamicLevel.PP -> 32; DynamicLevel.P -> 48; DynamicLevel.MP -> 64
            DynamicLevel.MF -> 80; DynamicLevel.F -> 96; DynamicLevel.FF -> 112; DynamicLevel.FFF -> 124
        }
        fun defaultChokeGroup(midi: Int): Int? = when (midi) { 42, 44, 46 -> 1; 49, 57 -> 2; 55 -> 3; 52 -> 4; else -> null }

        /** Sounding pitch of a harmonic; natural harmonics follow the string's overtone series at the touched node. */
        fun harmonicPitch(h: Articulation.Harmonic, note: Note, instrument: InstrumentDefinition): Double? {
            h.soundingPitch?.let { return it.midiNumber + it.cents / 100.0 }
            val fretted = note.pitch.midiNumber + note.pitch.cents / 100.0
            val string = note.string
            return when (h.kind) {
                HarmonicKind.NATURAL -> {
                    if (string == null || string !in instrument.tuning.indices) return fretted + 12
                    val open = (instrument.tuning[string] + instrument.capoByString[string] + instrument.transposition).toDouble()
                    val node = h.node?.toDouble()?.takeIf { it > 0.0 && it < 1.0 }
                    // Frets count from the capo (InstrumentDefinition.pitch), so the node sits at that fret of the shortened string.
                    val harmonicNumber = if (node != null) overtoneForNode(node) else overtoneForFret(note.fret ?: 12)
                    open + 12.0 * ln(harmonicNumber.toDouble()) / ln(2.0)
                }
                HarmonicKind.ARTIFICIAL, HarmonicKind.TAPPED -> fretted + 12
                HarmonicKind.PINCH, HarmonicKind.FEEDBACK -> fretted + 19.02
                HarmonicKind.SEMI -> fretted
            }
        }
        private fun overtoneForNode(node: Double): Int {
            for (n in 2..12) for (k in 1 until n) if (abs(node - k.toDouble() / n) < 0.004) return n
            return 2
        }
        /** Nodes at the usual fret positions: 12 -> 2nd, 7/19 -> 3rd, 5/24 -> 4th, 4/9/16 -> 5th, 3 -> 6th harmonic. */
        fun overtoneForFret(fret: Int): Int = when (fret) { 12, 24 -> if (fret == 24) 4 else 2; 7, 19 -> 3; 5 -> 4; 4, 9, 16 -> 5; 3 -> 6; 2 -> 9; else -> 2 }

        fun valueAt(curve: List<CurvePoint>, frame: Long): Double {
            if (curve.isEmpty()) return 0.0
            if (frame <= curve.first().frame) return curve.first().cents
            for (i in 1 until curve.size) if (frame <= curve[i].frame) {
                val a = curve[i - 1]; val b = curve[i]
                val span = (b.frame - a.frame).toDouble()
                return if (span <= 0) b.cents else a.cents + (frame - a.frame) / span * (b.cents - a.cents)
            }
            return curve.last().cents
        }
        fun mergeCurves(a: List<CurvePoint>, b: List<CurvePoint>): List<CurvePoint> {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            return (a.map { it.frame } + b.map { it.frame }).distinct().sorted().map { CurvePoint(it, valueAt(a, it) + valueAt(b, it)) }
        }
        fun normalize(curve: List<CurvePoint>): List<CurvePoint> {
            if (curve.isEmpty()) return curve
            val sorted = curve.sortedBy { it.frame }
            val out = ArrayList<CurvePoint>(sorted.size)
            for (p in sorted) if (out.isEmpty() || p.frame > out.last().frame) out += p else out[out.lastIndex] = p
            return if (out.all { abs(it.cents) < 1e-9 }) emptyList() else out
        }
    }
}
