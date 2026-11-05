package dev.riffworkbench.adapter.formats.qa15

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import kotlin.random.Random

/**
 * QA 15 generator: random but valid projects built ONLY through EditorSession commands (ideas from
 * core/editor Qa14ModelBasedEditorTest). A step is (select + command), both resolved against the live state from the
 * step's own seed, so dropping steps while shrinking stays meaningful. Failed commands are skipped.
 */
object Qa15Gen {
    enum class Profile { FULL, MIDI_SAFE }
    data class Spec(val seed: Long, val band: Boolean, val steps: List<Long>, val profile: Profile = Profile.FULL)

    fun randomSpec(seed: Long, profile: Profile = Profile.FULL): Spec {
        val r = Random(seed * 31 + 15)
        val n = 5 + r.nextInt(if (r.nextInt(5) == 0) 120 else 50)
        return Spec(seed, r.nextBoolean(), List(n) { r.nextLong() }, profile)
    }

    class Built(val project: Project, val log: List<String>)

    fun build(spec: Spec): Built {
        var n = 0
        val ids = IdSource { EntityId("g${n++}") }
        val e = EditorSession(ProjectFactory(ids).create("QA15", withBand = spec.band), ids)
        val log = mutableListOf<String>()
        for ((i, s) in spec.steps.withIndex()) {
            val r = Random(s)
            val p = e.state.value.project
            val sel = select(p, r, spec.profile)
            try { e.select(sel) } catch (x: IllegalArgumentException) { continue }
            val c = try { command(p, sel, r, ids, spec.profile) } catch (x: IllegalArgumentException) { null } ?: continue
            val res = try { e.dispatch(c) } catch (t: Throwable) { null }
            if (res is Outcome.Success && e.state.value.project != p) log += "#$i ${describe(p, sel)} ${c.toString().take(200)}"
        }
        return Built(e.state.value.project, log)
    }

    private fun capacity(m: Measure): Fraction = m.pickupLength ?: m.signature.length()

    private fun select(p: Project, r: Random, profile: Profile): Selection {
        val t = p.tracks[r.nextInt(p.tracks.size)]
        val mi = r.nextInt(p.measures.size); val m = p.measures[mi]
        val voice = if (r.nextInt(6) == 0) 1 else 0
        val beats = t.staves.first().bars.getOrNull(mi)?.voices?.firstOrNull { it.index == voice }?.beats.orEmpty()
        val strings = maxOf(1, t.instrument.tuning.size)
        val beat = beats.randomOrNull(r)
        var offset: Fraction; var string = r.nextInt(strings)
        if (beat != null && r.nextInt(10) < 6) {
            offset = beat.offset
            beat.notes.randomOrNull(r)?.string?.let { if (r.nextInt(3) != 0) string = it }
            if (r.nextInt(4) == 0) { offset = beat.offset + beat.duration.length(); if (offset >= capacity(m)) offset = Fraction.ZERO }
        } else {
            val grid = if (profile == Profile.FULL && r.nextInt(5) == 0) 12L else 16L
            offset = Fraction.of(r.nextLong(0, grid), grid).let { if (it < capacity(m)) it else Fraction.ZERO }
        }
        if (t.instrument.tuning.isEmpty()) string = 0
        return Selection(t.id, m.id, voice, offset, string)
    }

    private fun dur(r: Random, profile: Profile): WrittenDuration =
        if (profile == Profile.MIDI_SAFE) listOf(WrittenDuration(4), WrittenDuration(8), WrittenDuration(16), WrittenDuration(2), WrittenDuration(8, 1), WrittenDuration(4, 1), WrittenDuration(1))[r.nextInt(7)]
        else when (r.nextInt(16)) {
            0 -> WrittenDuration(8, 0, listOf(TupletRatio(3, 2)))
            1 -> WrittenDuration(16, 0, listOf(TupletRatio(5, 4)))
            2 -> WrittenDuration(1)
            3 -> WrittenDuration(4, 2)
            else -> WrittenDuration(listOf(2, 4, 8, 8, 16, 16, 16, 32, 64)[r.nextInt(9)], if (r.nextInt(5) == 0) 1 else 0)
        }

    private fun sig(r: Random) = TimeSignature(listOf(2, 3, 4, 4, 4, 5, 6, 7, 9, 12)[r.nextInt(10)], listOf(4, 4, 8, 8, 16, 2)[r.nextInt(6)])

    private fun str(r: Random): String = when (r.nextInt(10)) {
        0 -> "Riff 🎸 ✓"; 1 -> "quote \" & <tag> 'x'"; 2 -> "  padded  "
        else -> listOf("Intro", "Verse", "Chorus", "Bridge", "Solo", "Outro")[r.nextInt(6)] + r.nextInt(99)
    }

    private fun allNotes(p: Project): List<Note> = p.tracks.flatMap { t -> t.staves.flatMap { s -> s.bars.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { it.notes } } } } }

    private fun articulation(p: Project, r: Random, profile: Profile): Articulation {
        if (profile == Profile.MIDI_SAFE) return when (r.nextInt(4)) {
            0 -> Articulation.Mark(listOf(NoteMark.PALM_MUTE, NoteMark.ACCENT, NoteMark.HEAVY_ACCENT, NoteMark.GHOST, NoteMark.STACCATO, NoteMark.LET_RING)[r.nextInt(6)])
            1 -> Articulation.Dynamic(DynamicLevel.values().random(r))
            2 -> Articulation.Pickstroke(StrokeDirection.values().random(r))
            else -> Articulation.Percussion(PercussionTechnique.values().random(r))
        }
        return when (r.nextInt(15)) {
            0 -> Articulation.Mark(NoteMark.values().random(r))
            1 -> Articulations.fullBend()
            2 -> Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 2), listOf(50, 100, 200, 300)[r.nextInt(4)])))
            3 -> Articulation.Whammy(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 2), -400)))
            4 -> Articulation.Slide(SlideKind.values().random(r), if (r.nextInt(3) == 0) allNotes(p).randomOrNull(r)?.id else null)
            5 -> Articulation.Harmonic(HarmonicKind.values().random(r))
            6 -> Articulation.Vibrato(if (r.nextBoolean()) 30 else r.nextInt(10, 100), if (r.nextBoolean()) 5.0 else 6.5)
            7 -> Articulation.Trill(r.nextInt(0, 15), listOf(WrittenDuration(16), WrittenDuration(32))[r.nextInt(2)])
            8 -> Articulation.TremoloPicking(listOf(WrittenDuration(8), WrittenDuration(16), WrittenDuration(32))[r.nextInt(3)])
            9 -> Articulation.Grace(Pitch(r.nextInt(30, 80)), listOf(WrittenDuration(16), WrittenDuration(32))[r.nextInt(2)], r.nextBoolean(), r.nextInt(0, 12), r.nextInt(0, 6))
            10 -> Articulation.Dynamic(DynamicLevel.values().random(r))
            11 -> Articulation.Pickstroke(StrokeDirection.values().random(r))
            12 -> Articulation.Percussion(PercussionTechnique.values().random(r))
            else -> Articulation.Mark(listOf(NoteMark.PALM_MUTE, NoteMark.LET_RING, NoteMark.ACCENT, NoteMark.STACCATO)[r.nextInt(4)])
        }
    }

    private fun instrument(r: Random): InstrumentDefinition = when (r.nextInt(7)) {
        0 -> InstrumentDefinition(kind = InstrumentKind.PIANO, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0)
        1 -> InstrumentDefinition(kind = InstrumentKind.BASS, tuning = listOf(28, 33, 38, 43), capoByString = List(4) { 0 }, midiProgram = 33)
        2 -> InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = List(6) { 2 }, midiProgram = 25)
        3 -> InstrumentDefinition(tuning = listOf(38, 45, 50, 55, 59, 64), capoByString = List(6) { 0 }, midiProgram = 29)
        else -> { val n = r.nextInt(4, 9); val low = r.nextInt(25, 45)
            InstrumentDefinition(tuning = List(n) { low + it * 5 }, capoByString = List(n) { if (r.nextInt(6) == 0) r.nextInt(1, 4) else 0 }, maxFret = listOf(20, 24, 24)[r.nextInt(3)], midiProgram = listOf(24, 27, 30)[r.nextInt(3)]) }
    }

    private fun command(p: Project, s: Selection, r: Random, ids: IdSource, profile: Profile): EditorCommand? {
        val track = p.tracks.first { it.id == s.trackId }
        val fretted = track.instrument.tuning.isNotEmpty()
        val drums = track.instrument.kind == InstrumentKind.PERCUSSION
        val full = profile == Profile.FULL
        val m = p.measures.random(r).id
        return when (r.nextInt(100)) {
            in 0..34 -> when {
                drums -> EditorCommand.ToggleDrum(listOf(35, 36, 38, 40, 42, 44, 46, 49, 51, 57)[r.nextInt(10)], dur(r, profile))
                fretted -> EditorCommand.PutNote(fret = r.nextInt(0, 13), duration = dur(r, profile))
                else -> EditorCommand.PutNote(midiNote = r.nextInt(36, 90), duration = dur(r, profile))
            }
            in 35..36 -> EditorCommand.PutRest(dur(r, profile))
            in 37..48 -> articulation(p, r, profile).let { a ->
                // Keep techniques where they mean something (no percussion techniques on guitars, no bends on drums);
                // grace positions consistent with the track's tuning.
                when {
                    a is Articulation.Percussion && !drums -> null
                    drums && a !is Articulation.Percussion && a !is Articulation.Mark && a !is Articulation.Dynamic && a !is Articulation.Grace -> null
                    a is Articulation.Grace && !fretted -> EditorCommand.ApplyArticulation(a.copy(fret = null, string = null), toggle = false)
                    a is Articulation.Grace -> { val st = a.string!!.coerceAtMost(track.instrument.tuning.lastIndex)
                        EditorCommand.ApplyArticulation(a.copy(string = st, pitch = Pitch(track.instrument.pitch(st, a.fret!!))), toggle = false) }
                    else -> EditorCommand.ApplyArticulation(a, toggle = false)
                }
            }
            in 49..51 -> EditorCommand.ToggleMark(if (full) NoteMark.values().random(r) else listOf(NoteMark.ACCENT, NoteMark.PALM_MUTE, NoteMark.STACCATO)[r.nextInt(3)])
            in 52..57 -> EditorCommand.ToggleTie
            in 58..59 -> if (full) EditorCommand.SetTuplet(listOf(null, TupletRatio(3, 2), TupletRatio(5, 4))[r.nextInt(3)]) else null
            60 -> EditorCommand.SetDuration(dur(r, profile))
            61 -> EditorCommand.SetFermata(if (r.nextInt(4) == 0) null else Fermata(FermataKind.values().random(r), listOf(1.25, 1.5, 2.0, 3.0)[r.nextInt(4)]))
            62 -> if (full) EditorCommand.SetStrum(Strum(StrumKind.values().random(r), StrokeDirection.values().random(r), Fraction.of(r.nextLong(0, 2), 32))) else null
            63 -> EditorCommand.SetVelocity(r.nextInt(20, 128))
            64 -> EditorCommand.MoveToString(r.nextInt(0, 8))
            65, 66 -> EditorCommand.SetText(str(r), if (r.nextInt(4) == 0) TextKind.values().random(r) else TextKind.TEXT)
            in 67..70 -> EditorCommand.AddMeasure(if (r.nextBoolean()) TimeSignature() else sig(r))
            71 -> EditorCommand.InsertMeasures(r.nextInt(0, p.measures.size + 1), 1, if (r.nextBoolean()) null else sig(r))
            72 -> EditorCommand.SetSignature(m, sig(r))
            73, 74 -> EditorCommand.SetRepeat(m, RepeatMark(r.nextBoolean(), listOf(null, 2, 3)[r.nextInt(3)], if (r.nextInt(3) == 0) (1..3).filter { r.nextBoolean() }.toSet() else emptySet()))
            75 -> EditorCommand.SetNavigation(m, List(r.nextInt(0, 2)) { NavigationMark(NavigationKind.values().random(r), if (r.nextInt(4) == 0) p.measures.random(r).id else null) })
            76, 77 -> EditorCommand.SetKey(m, KeySignature(r.nextInt(-7, 8), r.nextBoolean()))
            78 -> EditorCommand.SetFeel(m, RhythmicFeel.values().random(r))
            79, 80 -> EditorCommand.SetTempoAt(m, if (r.nextInt(5) == 0) null else listOf(60.0, 90.0, 120.0, 133.5, 150.0, 200.0)[r.nextInt(6)])
            81, 82 -> EditorCommand.SetSection(m, if (r.nextInt(4) == 0) null else str(r))
            83 -> EditorCommand.RenameTrack(track.id, str(r))
            84 -> EditorCommand.Rename(str(r))
            85 -> EditorCommand.SetMetadata(ProjectMetadata(str(r), str(r), str(r), str(r), str(r), notes = str(r)))
            86 -> if (p.tracks.size < 6) EditorCommand.AddTrack(listOf(InstrumentKind.GUITAR, InstrumentKind.BASS, InstrumentKind.PIANO, InstrumentKind.PERCUSSION, InstrumentKind.OTHER)[r.nextInt(5)]) else null
            87 -> if (!drums) EditorCommand.SetInstrument(track.id, instrument(r), RetuningPolicy.values().random(r)) else null
            88 -> EditorCommand.Transpose(listOf(1, -1, 2, 12, -12, 5)[r.nextInt(6)], listOf(EditScope.NOTE, EditScope.BEAT, EditScope.MEASURES)[r.nextInt(3)])
            89 -> EditorCommand.SetMix(track.id, ChannelMix(volume = listOf(0.8, 0.5, 1.0)[r.nextInt(3)], pan = listOf(0.0, -0.5, 0.3)[r.nextInt(3)]))
            90 -> EditorCommand.DuplicateMeasures(listOf(m))
            91 -> EditorCommand.InsertBeat(dur(r, profile))
            92 -> EditorCommand.DeleteBeat
            93 -> EditorCommand.Delete
            94, 95 -> if (fretted && !drums) EditorCommand.PutNote(fret = r.nextInt(0, 13), duration = dur(r, profile), marks = setOf(listOf(NoteMark.PALM_MUTE, NoteMark.ACCENT, NoteMark.DEAD)[r.nextInt(3)])) else null
            else -> EditorCommand.SetTempo(listOf(80.0, 100.0, 120.0, 150.0, 177.0)[r.nextInt(5)])
        }
    }

    private fun describe(p: Project, s: Selection) =
        "sel(t${p.tracks.indexOfFirst { it.id == s.trackId }} m${p.measures.indexOfFirst { it.id == s.measureId }} v${s.voice} @${s.offset} s${s.string})"

    fun stats(p: Project): String {
        val notes = p.tracks.sumOf { t -> t.staves.sumOf { s -> s.bars.sumOf { b -> b.voices.sumOf { v -> v.beats.sumOf { it.notes.size } } } } }
        return "${p.measures.size} bars, ${p.tracks.size} tracks, $notes notes"
    }
    fun noteCount(p: Project) = p.tracks.sumOf { t -> t.staves.sumOf { s -> s.bars.sumOf { b -> b.voices.sumOf { v -> v.beats.sumOf { it.notes.size } } } } }
}
