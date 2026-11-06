package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.ports.AudioSink
import dev.riffworkbench.ports.SinkFormat
import dev.riffworkbench.synth.*

/**
 * QA cycle 20 (audio thread, CPU, synchronicity): shared projects and helpers. E standard guitar (program 30 -> high gain,
 * sampled when the bank is there), pick bass, factory drum kit; all at the factory mix like a new project.
 */
class Qa20Kit(private val prefix: String = "q20") {
    companion object {
        const val SR = 48_000
        val guitarDef = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), midiProgram = 30)
        val bassDef = InstrumentDefinition(InstrumentKind.BASS, listOf(28, 33, 38, 43), List(4) { 0 }, midiProgram = 33)
        val drumsDef = InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0)
        fun f(a: Long, b: Long) = Fraction.of(a, b)
        fun plan(p: Project, speed: Double = 1.0) = (ScorePlaybackCompiler().compileNow(p, SR, PlaybackOptions(speed)) as Outcome.Success).value
        /** A fixed SHA-256-shaped hash for synthetic reference audio. */
        val CLIP_HASH = AssetHash("ab".repeat(32))
    }
    private var n = 0
    fun id() = EntityId("$prefix-${n++}")
    fun g(s: Int, fr: Int, v: Int = 96, vararg a: Articulation) = Note(id(), Pitch(guitarDef.pitch(s, fr)), s, fr, velocity = v, articulations = a.toList())
    fun b(s: Int, fr: Int, v: Int = 104, vararg a: Articulation) = Note(id(), Pitch(bassDef.pitch(s, fr)), s, fr, velocity = v, articulations = a.toList())
    fun d(midi: Int, v: Int = 96) = Note(id(), Pitch(midi), velocity = v)
    fun beat(o: Fraction, den: Int, vararg notes: Note) = Beat(id(), o, WrittenDuration(den), notes.toList())
    private fun fresh(beats: List<Beat>) = beats.map { it.copy(id = id(), notes = it.notes.map { n -> n.copy(id = id()) }) }

    fun track(name: String, def: InstrumentDefinition, bars: List<List<Beat>>, measures: List<Measure>, preset: String? = null, kind: NotationKind = NotationKind.TAB) =
        Track(id(), name, def, listOf(Staff(id(), setOf(kind), measures.mapIndexed { i, m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) fresh(bars.getOrElse(i) { emptyList() }) else emptyList()) }) })),
            if (preset != null) SoundAssignment(presetId = preset) else SoundAssignment())

    fun project(measures: List<Measure>, tracks: List<Track>, tempo: List<TempoChange>, clips: List<AudioClip> = emptyList()) =
        Project(id = id(), metadata = ProjectMetadata("Q20"), measures = measures, tracks = tracks, tempo = tempo, audio = AudioArrangement(clips = clips))

    /** Guitar riff (palm-muted 16ths with power chords), bass on the guitar's root, drums (kick/snare/hi-hat 8ths, crash on 1). */
    fun riffBar(): List<Beat> {
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        fun power(fret: Int) = arrayOf(g(0, fret), g(1, fret + 2), g(2, fret + 2))
        return (0 until 16).mapNotNull { k -> when (k) { 3, 11 -> beat(f(k.toLong(), 16), 8, *power(if (k == 3) 3 else 5)); 4, 12 -> null; else -> beat(f(k.toLong(), 16), 16, g(0, 0, 96, pm)) } }
    }
    fun bassBar(riff: List<Beat>) = riff.map { bt -> Beat(id(), bt.offset, bt.duration, listOf(b(1, bt.notes.first().fret ?: 0))) }
    fun drumBar(crash: Boolean = true) = (0 until 8).map { k ->
        val notes = mutableListOf(d(42, if (k % 2 == 0) 96 else 80))
        if (k == 0 || k == 4 || k == 5) notes += d(36, if (k == 0) 120 else 100)
        if (k == 2 || k == 6) notes += d(38, 118)
        if (k == 0 && crash) notes += d(49, 116)
        Beat(id(), f(k.toLong(), 8), WrittenDuration(8), notes)
    }

    /**
     * QA cycle 24: kick/hi-hat 8ths, then a 16th fill over all six GM toms (one tom and the snare as rimshots), a choked crash
     * and a note outside the sampled kit (60, Hi Bongo: synthetic fallback), so the sampled kit's articulations and chokes run.
     */
    fun fillBar(): List<Beat> {
        fun p(midi: Int, v: Int, t: PercussionTechnique) = Note(id(), Pitch(midi), velocity = v, articulations = listOf(Articulation.Percussion(t)))
        val head = (0 until 4).map { k -> Beat(id(), f(k.toLong(), 8), WrittenDuration(8), listOfNotNull(d(42, 90), if (k % 2 == 0) d(36, 110) else null, if (k == 2) p(38, 115, PercussionTechnique.RIMSHOT) else null)) }
        val fill = listOf(50, 48, 47, 45, 43, 41, 41, 36).mapIndexed { k, m -> Beat(id(), f(1, 2) + f(k.toLong(), 16), WrittenDuration(16), listOf(if (k == 3) p(m, 118, PercussionTechnique.RIMSHOT) else d(m, 118)) + (if (k == 7) listOf(p(57, 120, PercussionTechnique.CHOKE), d(60, 90)) else emptyList())) }
        return head + fill
    }

    /** Standard band: high-gain guitar, bass, drums, [bars] bars of 4/4 at [qpm]. */
    fun standardBand(bars: Int = 4, qpm: Double = 150.0, measures: List<Measure> = List(bars) { Measure(id()) }, tempo: List<TempoChange>? = null, clips: List<AudioClip> = emptyList()): Project {
        val riff = List(measures.size) { riffBar() }
        return project(measures, listOf(
            track("Gitarre", guitarDef, riff, measures),
            track("Bass", bassDef, riff.map { bassBar(it) }, measures, "rw.bass.pick"),
            track("Drums", drumsDef, List(measures.size) { drumBar(it % 2 == 0) }, measures, kind = NotationKind.PERCUSSION)),
            tempo ?: listOf(TempoChange(ScorePosition(measures[0].id), qpm)), clips)
    }

    /** Dense case: two guitars with six-string chords in 16ths (alternating shapes), drums in 16ths; metronome is the caller's. */
    fun denseBand(bars: Int = 4, qpm: Double = 150.0): Project {
        val m = List(bars) { Measure(id()) }
        val shapes = listOf(intArrayOf(0, 2, 2, 1, 0, 0), intArrayOf(3, 2, 0, 0, 3, 3), intArrayOf(5, 7, 7, 6, 5, 5), intArrayOf(0, 0, 2, 2, 2, 0))
        fun chordBar(v: Int) = (0 until 16).map { k -> val s = shapes[(k / 4) % shapes.size]; beat(f(k.toLong(), 16), 16, *Array(6) { st -> g(st, s[st], if (k % 4 == 0) v + 20 else v) }) }
        val drums = (0 until 16).map { k ->
            val notes = mutableListOf(d(42, if (k % 2 == 0) 96 else 76), d(36, if (k % 4 == 0) 116 else 92))
            if (k % 8 == 4) notes += d(38, 120)
            if (k == 0) notes += d(49, 116)
            Beat(id(), f(k.toLong(), 16), WrittenDuration(16), notes)
        }
        return project(m, listOf(
            track("Gitarre L", guitarDef, List(bars) { chordBar(90) }, m),
            track("Gitarre R", guitarDef, List(bars) { chordBar(84) }, m),
            track("Drums", drumsDef, List(bars) { drums }, m, kind = NotationKind.PERCUSSION)),
            listOf(TempoChange(ScorePosition(m[0].id), qpm)))
    }

    /** Every guitar path the engine has: pinch/natural/artificial/tapped harmonics, palm mute, dead notes, bends, vibrato, slides, legato, let ring, tremolo, trill, grace. */
    fun articulationBars(): List<List<Beat>> {
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(f(1, 3), 200), PitchCurvePoint(f(2, 3), 200), PitchCurvePoint(Fraction.ONE, 0)))
        val bar1 = listOf(beat(f(0, 1), 8, g(0, 0)), beat(f(1, 8), 16, g(0, 0, 96, pm)), beat(f(3, 16), 16, g(0, 0, 110, pm)),
            beat(f(1, 4), 8, g(2, 7, 96, Articulation.Mark(NoteMark.DEAD))), beat(f(3, 8), 8, g(0, 12, 96, Articulation.Harmonic(HarmonicKind.NATURAL))),
            beat(f(1, 2), 8, g(2, 0, 96, Articulation.Harmonic(HarmonicKind.PINCH))), beat(f(5, 8), 8, g(3, 5, 96, Articulation.Harmonic(HarmonicKind.ARTIFICIAL))),
            beat(f(3, 4), 8, g(4, 7, 96, Articulation.Harmonic(HarmonicKind.TAPPED))), beat(f(7, 8), 8, g(1, 7, 96, Articulation.Harmonic(HarmonicKind.SEMI))))
        val bar2 = listOf(beat(f(0, 1), 4, g(2, 7, 96, bend)), beat(f(1, 4), 8, g(3, 7, 96, Articulation.Mark(NoteMark.HAMMER_ON))), beat(f(3, 8), 8, g(3, 9, 96, Articulation.Mark(NoteMark.PULL_OFF))),
            beat(f(1, 2), 8, g(4, 5, 96, Articulation.Slide(SlideKind.LEGATO))), beat(f(5, 8), 8, g(4, 7, 96, Articulation.Vibrato(50, 5.5))),
            beat(f(3, 4), 4, g(0, 0, 100, Articulation.Mark(NoteMark.LET_RING)), g(1, 2, 100, Articulation.Mark(NoteMark.LET_RING)), g(2, 2, 100, Articulation.Mark(NoteMark.LET_RING))))
        val bar3 = listOf(beat(f(0, 1), 4, g(3, 5, 96, Articulation.TremoloPicking())), beat(f(1, 4), 4, g(2, 5, 96, Articulation.Trill(7))),
            beat(f(1, 2), 8, g(1, 3, 96, Articulation.Grace(Pitch(guitarDef.pitch(1, 1)), fret = 1, string = 1))), beat(f(5, 8), 8, g(5, 12, 96, Articulation.Slide(SlideKind.OUT_DOWN))),
            beat(f(3, 4), 4, g(4, 5, 96, Articulation.Whammy(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(f(1, 2), -300), PitchCurvePoint(Fraction.ONE, 0))))))
        return listOf(bar1, bar2, bar3)
    }

    /** All voice kinds at once: sampled guitar with every articulation, modelled bass, drums with chokes, keys, metronome-ready. */
    fun everything(): Project {
        val m = List(3) { Measure(id()) }
        val drums = listOf(listOf(beat(f(0, 1), 8, d(36, 120), d(49, 116)), beat(f(1, 8), 8, d(42)), beat(f(1, 4), 8, d(38, 120)), beat(f(3, 8), 8, d(46)), beat(f(1, 2), 8, d(36), d(44)),
            beat(f(5, 8), 8, d(42)), beat(f(3, 4), 8, d(38), d(51)), beat(f(7, 8), 8, d(53), d(55), d(52))), drumBar(), fillBar())
        val keys = InstrumentDefinition(InstrumentKind.PIANO, emptyList(), emptyList(), midiProgram = 4)
        return project(m, listOf(
            track("Gitarre", guitarDef, articulationBars(), m),
            track("Bass", bassDef, List(3) { bassBar(riffBar()) }, m, "rw.bass.pick"),
            track("Drums", drumsDef, drums, m, kind = NotationKind.PERCUSSION),
            track("Keys", keys, List(3) { listOf(beat(f(0, 1), 2, Note(id(), Pitch(60)), Note(id(), Pitch(64))), beat(f(1, 2), 2, Note(id(), Pitch(67)))) }, m, "rw.keys.epiano")),
            listOf(TempoChange(ScorePosition(m[0].id), 140.0)))
    }

    /** Reference audio: [frames] stereo frames; [shape] gives the left/right sample at a frame (|x| <= 1). */
    fun pcm(frames: Int, shape: (Int) -> Double): PcmBuffer {
        val data = ShortArray(frames * 2)
        for (i in 0 until frames) { val v = (shape(i).coerceIn(-1.0, 1.0) * 32767).toInt().toShort(); data[2 * i] = v; data[2 * i + 1] = v }
        return PcmBuffer(SR, frames, data)
    }
    fun clip(offsetMicros: Long = 0, gain: Double = 1.0) = AudioClip(id(), CLIP_HASH, "Backing", offsetMicros = offsetMicros, gain = gain)
}

/** Sink without allocation on the render thread (the capturing FakeSink boxes every sample). Captures into a preallocated array. */
class Qa20Sink(private val block: Int, capacityFrames: Int = 0, private val latency: Int = 0) : AudioSink {
    val left = FloatArray(capacityFrames)
    val right = FloatArray(capacityFrames)
    var frames = 0L; private set
    var capture = capacityFrames > 0
    override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> = Outcome.Success(SinkFormat(Qa20Kit.SR, block, latency, "q20"))
    override fun write(buffer: FloatArray, frames: Int): Int {
        if (capture) for (i in 0 until frames) { val k = (this.frames + i).toInt(); if (k < left.size) { left[k] = buffer[2 * i]; right[k] = buffer[2 * i + 1] } }
        this.frames += frames
        return frames
    }
    override fun presentedFrame(): Long? = presentedFrameOrNegative()
    /** Like the device sink, the primitive variant does not box (the render thread calls it for the note-on latency). */
    override fun presentedFrameOrNegative(): Long = (frames - latency).coerceAtLeast(0)
    override fun close() {}
}
