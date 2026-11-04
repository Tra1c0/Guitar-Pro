package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

class SequenceCompilerTest {
    private val kit = PlaybackTestKit()
    private val sr = 48_000
    private fun plan(p: Project, speed: Double = 1.0) = (ScorePlaybackCompiler().compileNow(p, sr, PlaybackOptions(speed)) as Outcome.Success).value
    private fun f(n: Long, d: Long) = Fraction.of(n, d)

    @Test fun measuresMapToExactFramesAtTempoAndSpeed() {
        val m = listOf(kit.measure(), kit.measure(TimeSignature(7, 8)))
        val p = kit.project(m, listOf(kit.guitar(m)), bpm = 120.0)
        val plan = plan(p)
        assertEquals(listOf(0L to 96_000L, 96_000L to 180_000L), plan.spans.map { it.startFrame to it.endFrame })
        assertEquals(360_000L, plan(p, speed = 0.5).totalFrames)
        assertEquals(m[1].id, plan.positionAt(100_000)!!.position.measureId)
        assertEquals(f(1, 16), plan.positionAt(96_000 + 6_000)!!.position.offset)
    }

    @Test fun tempoChangeInsideMeasureAndSwing() {
        val m = listOf(kit.measure(), kit.measure(feel = RhythmicFeel.SWING_EIGHTH))
        val p = kit.project(m, listOf(kit.guitar(m)), bpm = 120.0, extraTempo = listOf(TempoChange(ScorePosition(m[0].id, f(1, 2)), 60.0)))
        val plan = plan(p)
        assertEquals(144_000L, plan.spans[0].endFrame) // 1 s + 2 s
        // Bar 2 keeps 60 BPM (written order). Swung second eighth lands at 2/3 of a quarter.
        assertEquals(144_000L + 32_000L, plan.frameOf(1, f(1, 8)))
        assertEquals(144_000L + 48_000L, plan.frameOf(1, f(1, 4)))
    }

    @Test fun fermataStretchesAllTracksAtThatBeat() {
        val m = listOf(kit.measure())
        val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 4, kit.note(0, 0), fermata = Fermata(multiplier = 2.0)), kit.beat(f(1, 4), 4, kit.note(0, 3)))))
        val plan = plan(kit.project(m, listOf(g)))
        assertEquals(listOf(0L, 48_000L), plan.notes.map { it.frame })
        assertEquals(120_000L, plan.totalFrames)
    }

    @Test fun bendOnOneStringLeavesHeldNoteOnOtherStringUntouched() {
        val m = listOf(kit.measure())
        val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(f(1, 2), 200), PitchCurvePoint(Fraction.ONE, 200)))
        val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(Fraction.ZERO, 2, kit.note(5, 7, bend), kit.note(6, 5)))))
        val plan = plan(kit.project(m, listOf(g)))
        val bent = plan.notes.single { it.string == 5 }
        val held = plan.notes.single { it.string == 6 }
        assertEquals(200.0, bent.curve.last().cents, 1e-9)
        assertEquals(24_000L, bent.curve[1].frame)
        assertTrue(held.curve.isEmpty())
        assertEquals(69.0, held.soundingPitch, 1e-9)
    }

    @Test fun tieAcrossBarlineBecomesOneVoice() {
        val m = listOf(kit.measure(), kit.measure())
        val target = kit.note(0, 5)
        val origin = kit.note(0, 5, links = listOf(NoteLink(LinkKind.TIE, target.id)))
        val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(3, 4), 4, origin)), 1 to listOf(kit.beat(Fraction.ZERO, 4, target))))
        val notes = plan(kit.project(m, listOf(g))).notes
        assertEquals(1, notes.size)
        assertEquals(72_000L to 120_000L, notes.single().frame to notes.single().endFrame)
    }

    @Test fun hammerOnAndLegatoSlideDoNotRepick() {
        val m = listOf(kit.measure())
        val g = kit.guitar(m, mapOf(0 to listOf(
            kit.beat(f(0, 1), 4, kit.note(1, 5, Articulation.Mark(NoteMark.HAMMER_ON))),
            kit.beat(f(1, 4), 4, kit.note(1, 7, Articulation.Slide(SlideKind.LEGATO))),
            kit.beat(f(2, 4), 4, kit.note(1, 9, Articulation.Slide(SlideKind.SHIFT))),
            kit.beat(f(3, 4), 4, kit.note(1, 12)))))
        val notes = plan(kit.project(m, listOf(g))).notes
        assertEquals(listOf(AttackKind.PICK, AttackKind.LEGATO, AttackKind.LEGATO, AttackKind.PICK), notes.map { it.attack })
        assertEquals(200.0, notes[1].curve.last().cents, 1e-9)
        assertEquals(300.0, notes[2].curve.last().cents, 1e-9)
    }

    @Test fun techniquesResolveToPerformanceFlags() {
        val m = listOf(kit.measure())
        val g = kit.guitar(m, mapOf(0 to listOf(
            kit.beat(f(0, 1), 16, kit.note(0, 0, Articulation.Mark(NoteMark.PALM_MUTE))),
            kit.beat(f(1, 16), 16, kit.note(0, 0, Articulation.Mark(NoteMark.DEAD))),
            kit.beat(f(1, 8), 8, kit.note(0, 12, Articulation.Harmonic(HarmonicKind.NATURAL))),
            kit.beat(f(1, 4), 8, kit.note(0, 7, Articulation.Harmonic(HarmonicKind.NATURAL))),
            kit.beat(f(3, 8), 8, kit.note(1, 3, Articulation.Harmonic(HarmonicKind.PINCH))))))
        val notes = plan(kit.project(m, listOf(g))).notes
        assertTrue(notes[0].palmMute && !notes[0].dead)
        assertTrue(notes[1].dead)
        assertEquals(45.0, notes[2].soundingPitch, 1e-6)
        assertEquals(33 + 19.0196, notes[3].soundingPitch, 1e-3)
        assertEquals(HarmonicKind.PINCH, notes[4].harmonic)
        assertEquals(43 + 19.02, notes[4].soundingPitch, 1e-6)
    }

    @Test fun naturalHarmonicsCountFretsFromTheCapo() {
        // Capo 2 on Drop A: frets are relative to the capo, so "fret 7" is the 3rd-harmonic node of the shortened string.
        val m = listOf(kit.measure())
        val capo = InstrumentDefinition(capoByString = List(7) { 2 })
        val g = kit.guitar(m).let { t -> t.copy(instrument = capo, staves = t.staves.map { st -> st.copy(bars = st.bars.map { b -> b.copy(voices = b.voices.map { v ->
            if (v.index != 0) v else v.copy(beats = listOf(
                kit.beat(f(0, 1), 4, Note(EntityId("h7"), Pitch(capo.pitch(0, 7)), 0, 7, articulations = listOf(Articulation.Harmonic(HarmonicKind.NATURAL)))),
                kit.beat(f(1, 4), 4, Note(EntityId("h12"), Pitch(capo.pitch(0, 12)), 0, 12, articulations = listOf(Articulation.Harmonic(HarmonicKind.NATURAL))))))
        }) }) }) }
        val notes = plan(kit.project(m, listOf(g))).notes
        assertEquals(35 + 19.0196, notes[0].soundingPitch, 1e-3)
        assertEquals(35 + 12.0, notes[1].soundingPitch, 1e-6)
    }

    @Test fun letRingSustainsUntilSameStringIsPickedAgain() {
        val m = listOf(kit.measure())
        val g = kit.guitar(m, mapOf(0 to listOf(
            kit.beat(f(0, 1), 8, kit.note(2, 2, Articulation.Mark(NoteMark.LET_RING))),
            kit.beat(f(1, 8), 8, kit.note(3, 2)),
            kit.beat(f(3, 4), 8, kit.note(2, 4)))))
        val notes = plan(kit.project(m, listOf(g))).notes.filter { it.string == 2 }
        assertEquals(72_000L, notes[0].endFrame)
    }

    @Test fun tremoloPickingAndTrillExpand() {
        val m = listOf(kit.measure())
        val g = kit.guitar(m, mapOf(0 to listOf(
            kit.beat(f(0, 1), 4, kit.note(0, 0, Articulation.TremoloPicking(WrittenDuration(16)))),
            kit.beat(f(1, 4), 4, kit.note(1, 5, Articulation.Trill(7, WrittenDuration(16)))))))
        val notes = plan(kit.project(m, listOf(g))).notes
        assertEquals(4, notes.count { it.string == 0 })
        val trill = notes.filter { it.string == 1 }
        assertEquals(listOf(45.0, 47.0, 45.0, 47.0), trill.map { it.soundingPitch })
        assertEquals(AttackKind.LEGATO, trill[1].attack)
    }

    @Test fun downStrumStartsOnTheLowestString() {
        val m = listOf(kit.measure())
        val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(Fraction.ZERO, 4, kit.note(4, 2), kit.note(0, 0), kit.note(2, 2), strum = Strum(StrumKind.STRUM, StrokeDirection.DOWN)))))
        val notes = plan(kit.project(m, listOf(g))).notes
        assertEquals(listOf(0, 2, 4), notes.map { it.string })
        assertTrue(notes[0].frame < notes[1].frame && notes[1].frame < notes[2].frame)
    }

    @Test fun percussionArticulationsMapToKitAndChokeGroups() {
        val m = listOf(kit.measure())
        val d = kit.drums(m, mapOf(0 to listOf(Beat(kit.id(), Fraction.ZERO, WrittenDuration(8), listOf(
            Note(kit.id(), Pitch(42), articulations = listOf(Articulation.Percussion(PercussionTechnique.OPEN_HI_HAT))),
            Note(kit.id(), Pitch(36)))))))
        val notes = plan(kit.project(m, listOf(d))).notes
        assertEquals(setOf(46.0, 36.0), notes.map { it.soundingPitch }.toSet())
        assertEquals(1, notes.single { it.soundingPitch == 46.0 }.chokeGroup)
    }

    @Test fun kitElementLevelAndPanReachTheScheduledHits() {
        val m = listOf(kit.measure())
        val base = kit.drums(m, mapOf(0 to listOf(Beat(kit.id(), Fraction.ZERO, WrittenDuration(8), listOf(Note(kit.id(), Pitch(36)), Note(kit.id(), Pitch(38)))))))
        val d = base.copy(instrument = base.instrument.copy(drumKit = base.instrument.drumKit.map { if (it.midiNote == 36) it.copy(volume = 1.5, pan = -0.4) else it }))
        val notes = plan(kit.project(m, listOf(d))).notes
        val kick = notes.single { it.soundingPitch == 36.0 }; val snare = notes.single { it.soundingPitch == 38.0 }
        assertEquals(1.5, kick.elementGain, 1e-9); assertEquals(-0.4, kick.elementPan, 1e-9)
        assertEquals(1.0, snare.elementGain, 1e-9); assertEquals(0.0, snare.elementPan, 1e-9)
    }

    @Test fun metronomeFollowsMeterGrouping() {
        val m = listOf(kit.measure(), kit.measure(TimeSignature(7, 8)), kit.measure(TimeSignature(6, 8)))
        val plan = plan(kit.project(m, listOf(kit.guitar(m))))
        assertEquals(listOf(4, 7, 2), plan.spans.map { s -> plan.clicks.count { it.frame >= s.startFrame && it.frame < s.endFrame } })
        assertEquals(3, plan.clicks.count { it.level == 0 })
    }

    @Test fun repeatsProduceOneEventPerOccurrence() {
        val m = listOf(kit.measure(repeat = RepeatMark(starts = true, endCount = 2)))
        val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(Fraction.ZERO, 1, kit.note(0, 0)))))
        val notes = plan(kit.project(m, listOf(g))).notes
        assertEquals(listOf(0L, 96_000L), notes.map { it.frame })
        assertEquals(listOf(0, 1), notes.map { it.routeIndex })
    }

    @Test fun tempoMapperAndSyncMapperAgreeOnScoreTime() {
        val m = listOf(kit.measure(), kit.measure())
        val p = kit.project(m, listOf(kit.guitar(m)), bpm = 120.0)
        val pos = PlaybackOccurrence(ScorePosition(m[1].id, f(1, 4)), 1, 1)
        assertEquals(2_500_000L, (TimelineTempoMapper().toMicros(pos, p) as Outcome.Success).value)
        val map = SyncMap(kit.id(), SyncMode.AUDIO_REFERENCE, listOf(
            SyncAnchor(kit.id(), PlaybackOccurrence(ScorePosition(m[0].id), 1, 0), 1_000_000),
            SyncAnchor(kit.id(), PlaybackOccurrence(ScorePosition(m[1].id), 1, 1), 4_000_000)))
        // Audio runs 1.5x slower than the score between the anchors and keeps that rate afterwards.
        assertEquals(4_750_000L, (AnchorSyncMapper(p).toAudioMicros(pos, map) as Outcome.Success).value)
    }
}
