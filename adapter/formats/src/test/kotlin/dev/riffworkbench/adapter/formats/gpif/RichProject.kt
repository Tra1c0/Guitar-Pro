package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.gpif.GpifTestSupport.f
import dev.riffworkbench.model.*

/**
 * Own, originally authored test score: 7-string Drop A guitar, 4-string bass and drums with 4/4 + 7/8,
 * repeat with alternate endings, D.S. al Coda, sections, tempo change, nested tuplets, four voices and the
 * metal techniques the GPIF mapping covers.
 */
internal class RichProject(private val ids: IdSource) {
    val guitar = InstrumentDefinition(midiProgram = 30)
    val bass = InstrumentDefinition(kind = InstrumentKind.BASS, tuning = listOf(28, 33, 38, 43), capoByString = List(4) { 0 }, midiProgram = 33)
    val drums = InstrumentDefinition(kind = InstrumentKind.PERCUSSION, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0)

    private fun n(i: InstrumentDefinition, string: Int, fret: Int, vararg a: Articulation, links: List<NoteLink> = emptyList(), velocity: Int = 96, id: EntityId = ids.next()) =
        Note(id, Pitch(i.pitch(string, fret)), string, fret, velocity, a.toList(), links)
    private fun drum(midi: Int, vararg a: Articulation) = Note(ids.next(), Pitch(midi), articulations = a.toList())
    private fun b(offset: Fraction, d: WrittenDuration, vararg notes: Note, rest: Boolean = false, strum: Strum? = null, fermata: Fermata? = null) =
        Beat(ids.next(), offset, d, notes.toList(), explicitRest = rest, strum = strum, fermata = fermata)
    private fun bar(measure: Measure, vararg voices: List<Beat>) = Bar(ids.next(), measure.id, List(4) { Voice(ids.next(), it, voices.getOrNull(it) ?: emptyList()) })
    private fun mark(k: NoteMark) = Articulation.Mark(k)

    fun build(): Project {
        val projectId = ids.next()
        val m = List(7) { ids.next() }
        val measures = listOf(
            Measure(m[0], key = KeySignature(0, minor = true), repeat = RepeatMark(starts = true)),
            Measure(m[1], key = KeySignature(0, true), repeat = RepeatMark(endCount = 2, alternateEndings = setOf(1))),
            Measure(m[2], key = KeySignature(0, true), repeat = RepeatMark(alternateEndings = setOf(2))),
            Measure(m[3], TimeSignature(7, 8), KeySignature(0, true), navigation = listOf(NavigationMark(NavigationKind.SEGNO)), feel = RhythmicFeel.SWING_EIGHTH),
            Measure(m[4], TimeSignature(7, 8), KeySignature(0, true), navigation = listOf(NavigationMark(NavigationKind.TO_CODA, m[6]))),
            Measure(m[5], key = KeySignature(0, true), navigation = listOf(NavigationMark(NavigationKind.DAL_SEGNO, m[3]))),
            Measure(m[6], key = KeySignature(0, true), navigation = listOf(NavigationMark(NavigationKind.CODA))),
        )
        val e = WrittenDuration(8); val q = WrittenDuration(4); val h = WrittenDuration(2); val w = WrittenDuration(1)
        val dq = WrittenDuration(4, 1)
        val g = guitar
        val hammerTarget = ids.next(); val slideTarget = ids.next(); val tieTarget = ids.next(); val slurTarget = ids.next()
        val bendRelease = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(f(1, 2), 200), PitchCurvePoint(Fraction.ONE, 0)), BendKind.RELEASE)
        val guitarBars = listOf(
            bar(measures[0],
                listOf(
                    b(f(0), e, n(g, 0, 0, mark(NoteMark.PALM_MUTE))),
                    b(f(1, 8), e, n(g, 0, 0, mark(NoteMark.DEAD))),
                    b(f(1, 4), e, n(g, 1, 2, links = listOf(NoteLink(LinkKind.HAMMER_ON, hammerTarget)))),
                    b(f(3, 8), e, n(g, 1, 4, id = hammerTarget)),
                    b(f(1, 2), dq, n(g, 2, 2, bendRelease), n(g, 3, 2)),
                    b(f(7, 8), e, n(g, 0, 5, Articulation.Slide(SlideKind.SHIFT, slideTarget))),
                ),
                listOf(b(f(0), h, n(g, 6, 0, mark(NoteMark.LET_RING))), b(f(1, 2), h, n(g, 5, 0, Articulation.Vibrato()))),
                listOf(b(f(0), w, rest = true)),
                listOf(
                    b(f(0), q, n(g, 4, 5, Articulation.Grace(Pitch(g.pitch(4, 3)), WrittenDuration(32), true, 3, 4))),
                    b(f(1, 4), q, n(g, 4, 7, Articulation.TremoloPicking(WrittenDuration(16)))),
                ),
            ),
            bar(measures[1], listOf(
                b(f(0), q, n(g, 0, 7, id = slideTarget)),
                b(f(1, 4), q, n(g, 1, 2), n(g, 2, 2), n(g, 3, 1), strum = Strum(StrumKind.STRUM, StrokeDirection.DOWN, f(1, 64))),
                b(f(1, 2), q, n(g, 3, 12, Articulation.Harmonic(HarmonicKind.NATURAL, f(1, 2)))),
                b(f(3, 4), q, n(g, 2, 5, links = listOf(NoteLink(LinkKind.TIE, tieTarget)))),
            )),
            bar(measures[2], listOf(
                b(f(0), h, n(g, 2, 5, id = tieTarget)),
                b(f(1, 2), q, n(g, 1, 3, Articulation.Harmonic(HarmonicKind.PINCH, f(1, 2)))),
                b(f(3, 4), q, rest = true),
            )),
            bar(measures[3], listOf(
                *Array(5) { i -> val d = WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2), TupletRatio(5, 4))); b(d.length() * f(i.toLong()), d, n(g, 0, 3)) },
                b(f(1, 3), dq, n(g, 0, 3, Articulation.Pickstroke(StrokeDirection.DOWN))),
                b(f(17, 24), WrittenDuration(4, tuplets = listOf(TupletRatio(3, 2))), n(g, 0, 2, Articulation.Pickstroke(StrokeDirection.UP))),
            )),
            bar(measures[4], listOf(
                b(f(0), q, n(g, 2, 7, Articulation.Slide(SlideKind.INTO_FROM_BELOW), Articulation.Slide(SlideKind.OUT_DOWN))),
                b(f(1, 4), q, n(g, 3, 7, Articulation.Trill(9, WrittenDuration(16)))),
                b(f(1, 2), dq, n(g, 4, 5, mark(NoteMark.ACCENT), mark(NoteMark.STACCATO), Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 100), PitchCurvePoint(Fraction.ONE, 100)), BendKind.PREBEND))),
            )),
            bar(measures[5], listOf(b(f(0), w, n(g, 0, 0, mark(NoteMark.GHOST), mark(NoteMark.HEAVY_ACCENT), Articulation.Dynamic(DynamicLevel.FF),
                Articulation.Whammy(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(f(1, 2), -400), PitchCurvePoint(Fraction.ONE, -400))), velocity = 112)))),
            bar(measures[6], listOf(
                b(f(0), h, n(g, 1, 0, mark(NoteMark.LET_RING), Articulation.Dynamic(DynamicLevel.F), links = listOf(NoteLink(LinkKind.SLUR, slurTarget))),
                    n(g, 5, 12, mark(NoteMark.TAPPING), Articulation.Dynamic(DynamicLevel.F), links = listOf(NoteLink(LinkKind.SLUR, slurTarget)))),
                b(f(1, 2), h, n(g, 0, 0, id = slurTarget), fermata = Fermata(FermataKind.MEDIUM, 1.5)),
            )),
        )
        val bs = bass
        val bassBars = listOf(
            bar(measures[0], listOf(b(f(0), h, n(bs, 0, 5, mark(NoteMark.SLAP))), b(f(1, 2), h, n(bs, 1, 7, mark(NoteMark.POP))))),
            bar(measures[1], listOf(b(f(0), w, n(bs, 0, 5)))),
            bar(measures[2], listOf(b(f(0), w, n(bs, 1, 0)))),
            bar(measures[3], listOf(b(f(0), WrittenDuration(2, 1), n(bs, 0, 5)), b(f(3, 4), e, n(bs, 0, 7)))),
            bar(measures[4], listOf(b(f(0), WrittenDuration(2, 2), n(bs, 2, 2)))),
            bar(measures[5]),
            bar(measures[6], listOf(b(f(0), w, n(bs, 0, 0)))),
        )
        val drumBars = listOf(
            bar(measures[0], List(8) { i ->
                val notes = buildList {
                    add(drum(if (i == 7) 46 else 42))
                    if (i % 4 == 0) add(drum(36))
                    if (i == 2) add(drum(38))
                    if (i == 6) add(drum(38, Articulation.Percussion(PercussionTechnique.RIMSHOT)))
                    if (i == 7) add(drum(38, mark(NoteMark.GHOST)))
                }
                b(f(i.toLong(), 8), e, *notes.toTypedArray())
            }),
            bar(measures[1]), bar(measures[2]),
            bar(measures[3], listOf(b(f(0), WrittenDuration(2, 1), drum(36), drum(49)), b(f(3, 4), e, drum(38)))),
            bar(measures[4]), bar(measures[5]), bar(measures[6]),
        )
        val tracks = listOf(
            Track(ids.next(), "Gitarre · Drop A", guitar, listOf(Staff(ids.next(), setOf(NotationKind.TAB, NotationKind.STANDARD), guitarBars))),
            Track(ids.next(), "Bass", bass, listOf(Staff(ids.next(), setOf(NotationKind.TAB), bassBars))),
            Track(ids.next(), "Drums", drums, listOf(Staff(ids.next(), setOf(NotationKind.PERCUSSION), drumBars))),
        )
        return Project(
            id = projectId,
            metadata = ProjectMetadata("GPIF-Rundreise · Drop A", "RiffWorkbench Test", "Eigenes Album", "Eigenkomposition", "CC0-1.0", "Synthetische Testdaten"),
            measures = measures, tracks = tracks,
            sections = listOf(Section(ids.next(), m[0], "Intro"), Section(ids.next(), m[3], "Riff")),
            tempo = listOf(TempoChange(ScorePosition(m[0]), 140.0), TempoChange(ScorePosition(m[2], f(1, 2)), 160.0)),
            text = listOf(ScoreText(ids.next(), ScorePosition(m[0]), "Palm mute locker"), ScoreText(ids.next(), ScorePosition(m[1], f(1, 4)), "la", TextKind.LYRICS)),
        )
    }
}
