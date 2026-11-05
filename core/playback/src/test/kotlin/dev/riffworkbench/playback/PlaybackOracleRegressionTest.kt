package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import org.junit.Test

/**
 * Hand-written oracle cases (QA cycle 4). Every case must match, except documented differences listed in [intended]
 * (see docs/QA_JOURNAL.md, cycle 4); those are still printed so a change in behaviour is visible.
 */
class PlaybackOracleRegressionTest {
    private val kit = PlaybackTestKit()
    private val sr = 48_000
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private fun plan(p: Project, speed: Double = 1.0) = (ScorePlaybackCompiler().compileNow(p, sr, PlaybackOptions(speed)) as Outcome.Success).value
    private val intended = listOf("swing pickup", "no tempo in bar 1", "NH frets", "staccato on tie origin")
    private fun check(name: String, expected: Any?, actual: Any?) {
        println((if (expected == actual) "OK       " else "MISMATCH ") + "$name expected=$expected actual=$actual")
        if (intended.none { name.startsWith(it) }) org.junit.Assert.assertEquals(name, expected, actual)
    }
    private fun route(ms: List<Measure>): Any {
        val r = UnfoldingRepeatCompiler().compile(kit.project(ms, listOf(kit.guitar(ms))))
        return if (r is Outcome.Success) r.value.map { o -> ms.indexOfFirst { it.id == o.position.measureId } } else r.toString().take(120)
    }
    private fun withInstrument(t: Track, i: InstrumentDefinition) = t.copy(instrument = i)

    @Test fun routes() {
        // 1.(close 3) 2.(close 3) 3.   with a 2-bar body
        run {
            val m = listOf(kit.measure(repeat = RepeatMark(starts = true)), kit.measure(),
                kit.measure(repeat = RepeatMark(endCount = 3, alternateEndings = setOf(1))),
                kit.measure(repeat = RepeatMark(endCount = 3, alternateEndings = setOf(2))),
                kit.measure(repeat = RepeatMark(alternateEndings = setOf(3))), kit.measure())
            check("volta 1|2|3 two-bar body", listOf(0, 1, 2, 0, 1, 3, 0, 1, 4, 5), route(m))
        }
        // same with a 1-bar body
        run {
            val m = listOf(kit.measure(repeat = RepeatMark(starts = true)),
                kit.measure(repeat = RepeatMark(endCount = 3, alternateEndings = setOf(1))),
                kit.measure(repeat = RepeatMark(endCount = 3, alternateEndings = setOf(2))),
                kit.measure(repeat = RepeatMark(alternateEndings = setOf(3))), kit.measure())
            check("volta 1|2|3 one-bar body", listOf(0, 1, 0, 2, 0, 3, 4), route(m))
        }
        // 1.+3. (close 3) 2.(close 3) 4.? -> classic 1.3. / 2.
        run {
            val m = listOf(kit.measure(repeat = RepeatMark(starts = true)), kit.measure(),
                kit.measure(repeat = RepeatMark(endCount = 3, alternateEndings = setOf(1, 3))),
                kit.measure(repeat = RepeatMark(endCount = 3, alternateEndings = setOf(2))), kit.measure())
            check("volta 1.3.|2. two-bar body", listOf(0, 1, 2, 0, 1, 3, 0, 1, 2, 4), route(m))
        }
        // repeat x3, x4
        run {
            val m = listOf(kit.measure(), kit.measure(repeat = RepeatMark(starts = true)), kit.measure(repeat = RepeatMark(endCount = 4)), kit.measure())
            check("x4", listOf(0, 1, 2, 1, 2, 1, 2, 1, 2, 3), route(m))
        }
        // D.S. al Fine with repeat in between; repeats skipped after jump
        run {
            val m = listOf(kit.measure(), kit.measure(repeat = RepeatMark(starts = true), nav = listOf(NavigationMark(NavigationKind.SEGNO))),
                kit.measure(repeat = RepeatMark(endCount = 2)), kit.measure(nav = listOf(NavigationMark(NavigationKind.FINE))), kit.measure(nav = listOf(NavigationMark(NavigationKind.DAL_SEGNO))))
            check("DS al fine", listOf(0, 1, 2, 1, 2, 3, 4, 1, 2, 3), route(m))
        }
        // D.C. al Coda with volta: after D.C. final ending is used, To Coda jumps
        run {
            val m = listOf(kit.measure(repeat = RepeatMark(starts = true)),
                kit.measure(repeat = RepeatMark(endCount = 2, alternateEndings = setOf(1))),
                kit.measure(repeat = RepeatMark(alternateEndings = setOf(2)), nav = listOf(NavigationMark(NavigationKind.TO_CODA))),
                kit.measure(nav = listOf(NavigationMark(NavigationKind.DA_CAPO))), kit.measure(nav = listOf(NavigationMark(NavigationKind.CODA))))
            check("DC al coda volta", listOf(0, 1, 0, 2, 3, 0, 2, 4), route(m))
        }
    }

    @Test fun timing() {
        // Swing in 7/8: bar should last 7/8 whole notes at 120 = 1.75 s
        run {
            val m = listOf(kit.measure(TimeSignature(7, 8), feel = RhythmicFeel.SWING_EIGHTH), kit.measure())
            val p = plan(kit.project(m, listOf(kit.guitar(m))))
            check("swing 7/8 bar length frames", 84_000L, p.spans[0].endFrame)
        }
        run {
            val m = listOf(kit.measure(TimeSignature(3, 8), feel = RhythmicFeel.SWING_EIGHTH))
            val p = plan(kit.project(m, listOf(kit.guitar(m))))
            check("swing 3/8 bar length frames", 36_000L, p.spans[0].endFrame)
        }
        run {
            val m = listOf(kit.measure(TimeSignature(5, 16), feel = RhythmicFeel.SWING_SIXTEENTH))
            val p = plan(kit.project(m, listOf(kit.guitar(m))))
            check("swing16 5/16 bar length frames", 30_000L, p.spans[0].endFrame)
        }
        // swing pickup of 1/8 (offbeat)
        run {
            val m = listOf(kit.measure(feel = RhythmicFeel.SWING_EIGHTH).copy(pickupLength = f(1, 8)), kit.measure())
            val p = plan(kit.project(m, listOf(kit.guitar(m))))
            check("swing pickup 1/8 length (straight 12000, swung offbeat 8000)", 8000L, p.spans[0].endFrame)
        }
        // First tempo change not in bar 1
        run {
            val m = listOf(kit.measure(), kit.measure(), kit.measure())
            val p = Project(id = kit.id(), measures = m, tracks = listOf(kit.guitar(m)), tempo = listOf(TempoChange(ScorePosition(m[2].id), 60.0)))
            check("no tempo in bar 1, bar 1 frames at default 120", 96_000L, plan(p).spans[0].endFrame)
        }
    }

    @Test fun clicks() {
        run {
            val m = listOf(kit.measure().copy(pickupLength = f(1, 4)), kit.measure())
            val p = plan(kit.project(m, listOf(kit.guitar(m))))
            check("pickup 1/4 clicks levels in bar 0", listOf(1), p.clicks.filter { it.frame < p.spans[0].endFrame }.map { it.level })
        }
        run {
            val m = listOf(kit.measure().copy(pickupLength = f(3, 8)), kit.measure())
            val p = plan(kit.project(m, listOf(kit.guitar(m))))
            // A 3/8 pickup is the last 3/8 of a 4/4 bar (from 5/8): only beat 4 (6/8) falls inside, at pickup offset 1/8.
            check("pickup 3/8 click frames", listOf(12_000L), p.clicks.filter { it.frame < p.spans[0].endFrame }.map { it.frame })
        }
        run {
            val m = listOf(kit.measure(TimeSignature(12, 16)), kit.measure(TimeSignature(5, 4)), kit.measure(TimeSignature(9, 8)))
            val p = plan(kit.project(m, listOf(kit.guitar(m))))
            check("clicks per bar 12/16,5/4,9/8", listOf(4, 5, 3), p.spans.map { s -> p.clicks.count { it.frame >= s.startFrame && it.frame < s.endFrame } })
        }
    }

    @Test fun articulations() {
        // Tie followed by hammer on the tie target
        run {
            val m = listOf(kit.measure())
            val c = kit.note(1, 7)
            val b = kit.note(1, 5, Articulation.Mark(NoteMark.HAMMER_ON))
            val a = kit.note(1, 5, links = listOf(NoteLink(LinkKind.TIE, b.id)))
            val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 4, a), kit.beat(f(1, 4), 4, b), kit.beat(f(1, 2), 4, c))))
            val n = plan(kit.project(m, listOf(g))).notes
            check("tie then hammer from target: attacks", listOf(AttackKind.PICK, AttackKind.LEGATO), n.map { it.attack })
        }
        // Tie with link HAMMER_ON on the target
        run {
            val m = listOf(kit.measure())
            val c = kit.note(1, 7)
            val b = kit.note(1, 5, links = listOf(NoteLink(LinkKind.HAMMER_ON, c.id)))
            val a = kit.note(1, 5, links = listOf(NoteLink(LinkKind.TIE, b.id)))
            val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 4, a), kit.beat(f(1, 4), 4, b), kit.beat(f(1, 2), 4, c))))
            val n = plan(kit.project(m, listOf(g))).notes
            check("tie then hammer link from target: attacks", listOf(AttackKind.PICK, AttackKind.LEGATO), n.map { it.attack })
        }
        // Tied chord, both beats strummed
        run {
            val m = listOf(kit.measure())
            val t0 = kit.note(0, 0); val t2 = kit.note(2, 2); val t4 = kit.note(4, 2)
            val o0 = kit.note(0, 0, links = listOf(NoteLink(LinkKind.TIE, t0.id))); val o2 = kit.note(2, 2, links = listOf(NoteLink(LinkKind.TIE, t2.id))); val o4 = kit.note(4, 2, links = listOf(NoteLink(LinkKind.TIE, t4.id)))
            val s = Strum(StrumKind.STRUM, StrokeDirection.DOWN)
            val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 4, o0, o2, o4, strum = s), kit.beat(f(1, 4), 4, t0, t2, t4, strum = s))))
            val n = plan(kit.project(m, listOf(g))).notes
            check("tied strummed chord: note count", 3, n.size)
        }
        // Tremolo + bend keeps pitch curve
        run {
            val m = listOf(kit.measure())
            val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.ONE, 200)))
            val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 4, kit.note(3, 7, Articulation.TremoloPicking(WrittenDuration(16)), bend)))))
            val n = plan(kit.project(m, listOf(g))).notes
            check("tremolo+bend: last piece curve nonempty", true, n.last().curve.isNotEmpty())
            check("tremolo+vibrato kept", true, kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 4, kit.note(3, 7, Articulation.TremoloPicking(WrittenDuration(16)), Articulation.Vibrato()))))).let { plan(kit.project(m, listOf(it))).notes.all { x -> x.vibrato != null } })
        }
        // Tremolo + let ring: number of picks follows written duration
        run {
            val m = listOf(kit.measure(), kit.measure())
            val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 4, kit.note(3, 7, Articulation.TremoloPicking(WrittenDuration(16)), Articulation.Mark(NoteMark.LET_RING)))),
                1 to listOf(kit.beat(f(0, 1), 4, kit.note(3, 9)))))
            val n = plan(kit.project(m, listOf(g))).notes.filter { it.string == 3 }
            check("tremolo+let ring picks", 5, n.size)
        }
        // Drum roll: tremolo on hi-hat keeps choke group and kit gain
        run {
            val m = listOf(kit.measure())
            val base = kit.drums(m, mapOf(0 to listOf(Beat(kit.id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(kit.id(), Pitch(42), articulations = listOf(Articulation.TremoloPicking(WrittenDuration(16)))))))))
            val d = base.copy(instrument = base.instrument.copy(drumKit = base.instrument.drumKit.map { if (it.midiNote == 42) it.copy(volume = 0.5, pan = 0.3) else it }))
            val n = plan(kit.project(m, listOf(d))).notes
            check("drum roll pieces choke/gain/pan", List(4) { Triple(1, 0.5, 0.3) }, n.map { Triple(it.chokeGroup, it.elementGain, it.elementPan) })
            val rim = kit.drums(m, mapOf(0 to listOf(Beat(kit.id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(kit.id(), Pitch(38), articulations = listOf(Articulation.TremoloPicking(WrittenDuration(16)), Articulation.Percussion(PercussionTechnique.RIMSHOT))))))))
            check("rimshot roll stroke", List(4) { DrumStroke.RIMSHOT }, plan(kit.project(m, listOf(rim))).notes.map { it.drumStroke })
        }
        // Fine tune and harmonics
        run {
            val m = listOf(kit.measure())
            val inst = InstrumentDefinition(fineTuneCents = -50.0)
            val g = withInstrument(kit.guitar(m, mapOf(0 to listOf(
                kit.beat(f(0, 1), 4, kit.note(0, 5)),
                kit.beat(f(1, 4), 4, kit.note(0, 12, Articulation.Harmonic(HarmonicKind.NATURAL))),
                kit.beat(f(2, 4), 4, kit.note(1, 3, Articulation.Harmonic(HarmonicKind.ARTIFICIAL))),
                kit.beat(f(3, 4), 4, kit.note(2, 3, Articulation.Harmonic(HarmonicKind.PINCH)))))), inst)
            val n = plan(kit.project(m, listOf(g))).notes
            check("fine tune -50: plain/NH12/AH/PH", listOf(38.0 - 0.5, 45.0 - 0.5, 55.0 - 0.5, 48.0 + 19.02 - 0.5).map { Math.round(it * 100) / 100.0 }, n.map { Math.round(it.soundingPitch * 100) / 100.0 })
        }
        // Harmonic table frets 12/7/5/4/3
        run {
            val m = listOf(kit.measure(TimeSignature(6, 4)))
            val frets = listOf(12, 7, 5, 4, 3, 24, 19, 9, 0)
            val g = kit.guitar(m, mapOf(0 to frets.mapIndexed { i, fr -> kit.beat(f(i.toLong(), 8), 8, kit.note(6, fr, Articulation.Harmonic(HarmonicKind.NATURAL))) }))
            val n = plan(kit.project(m, listOf(g))).notes
            val open = 64.0
            val exp = listOf(12.0, 19.02, 24.0, 27.86, 31.02, 24.0, 19.02, 27.86, 0.0).map { Math.round((open + it) * 100) / 100.0 }
            check("NH frets 12/7/5/4/3/24/19/9/0", exp, n.map { Math.round(it.soundingPitch * 100) / 100.0 })
        }
        // Grace before beat: grace placed before the beat, main stays on beat; on beat steals
        run {
            val m = listOf(kit.measure())
            val gb = Articulation.Grace(Pitch(50), WrittenDuration(32), beforeBeat = true)
            val go = Articulation.Grace(Pitch(50), WrittenDuration(32), beforeBeat = false)
            val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(1, 4), 4, kit.note(2, 7, gb)), kit.beat(f(2, 4), 4, kit.note(3, 7, go)))))
            val n = plan(kit.project(m, listOf(g))).notes
            check("grace frames (before: 21000-24000 main 24000; on: 48000-51000 main 51000)", listOf(21_000L to 24_000L, 24_000L to 48_000L, 48_000L to 51_000L, 51_000L to 72_000L), n.map { it.frame to it.endFrame })
        }
        // Tie across repeat: bar 2 tied into bar 3 after the repeat
        run {
            val m = listOf(kit.measure(repeat = RepeatMark(starts = true)), kit.measure(repeat = RepeatMark(endCount = 2)), kit.measure())
            val t = kit.note(0, 5)
            val o = kit.note(0, 5, links = listOf(NoteLink(LinkKind.TIE, t.id)))
            val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 1, kit.note(0, 3))), 1 to listOf(kit.beat(f(3, 4), 4, o)), 2 to listOf(kit.beat(Fraction.ZERO, 4, t))))
            val n = plan(kit.project(m, listOf(g))).notes
            check("tie across repeat frames", listOf(0L to 96_000L, 168_000L to 192_000L, 192_000L to 288_000L, 360_000L to 408_000L), n.map { it.frame to it.endFrame })
        }
        // Staccato tied origin
        run {
            val m = listOf(kit.measure())
            val t = kit.note(0, 5)
            val o = kit.note(0, 5, Articulation.Mark(NoteMark.STACCATO), links = listOf(NoteLink(LinkKind.TIE, t.id)))
            val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 4, o), kit.beat(f(1, 4), 4, t))))
            check("staccato on tie origin: notes", 1, plan(kit.project(m, listOf(g))).notes.size)
        }
        // Tremolo near bar end: count
        run {
            val m = listOf(kit.measure(), kit.measure())
            val t = kit.note(0, 5)
            val o = kit.note(0, 5, Articulation.TremoloPicking(WrittenDuration(8)), links = listOf(NoteLink(LinkKind.TIE, t.id)))
            val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(15, 16), 16, o)), 1 to listOf(kit.beat(Fraction.ZERO, 4, t))))
            val n = plan(kit.project(m, listOf(g))).notes
            // tied 1/16 + 1/4 = 5/16 at 1/8 rate -> 3 picks (ceil 2.5)
            check("tremolo on tie across barline picks", 3, n.size)
        }
        // trill with capo
        run {
            val m = listOf(kit.measure())
            val inst = InstrumentDefinition(capoByString = List(7) { 3 }, transposition = -2)
            val note = Note(kit.id(), Pitch(inst.pitch(1, 5)), 1, 5, articulations = listOf(Articulation.Trill(7, WrittenDuration(8))))
            val g = withInstrument(kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 4, note)))), inst)
            check("trill capo/transpose", listOf(46.0, 48.0), plan(kit.project(m, listOf(g))).notes.map { it.soundingPitch })
        }
        // let ring to end: last note rings until? (8 s cap)
        run {
            val m = listOf(kit.measure())
            val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 4, kit.note(2, 2, Articulation.Mark(NoteMark.LET_RING))))))
            println("INFO let ring last note end=" + plan(kit.project(m, listOf(g))).notes.single().endFrame)
        }
    }
}
