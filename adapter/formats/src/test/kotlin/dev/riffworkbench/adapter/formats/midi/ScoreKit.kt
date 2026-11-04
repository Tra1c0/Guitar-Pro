package dev.riffworkbench.adapter.formats.midi

import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import org.junit.Assert.assertEquals

/** Builds small own projects for format tests. No third-party tabs or sounds. */
class ScoreKit(private val prefix: String = "k") {
    private var n = 0
    fun id() = EntityId("$prefix${n++}")
    val ids = IdSource { id() }

    /** Seven-string Drop A: A1 E2 A2 D3 G3 B3 E4. */
    val dropA = InstrumentDefinition(InstrumentKind.GUITAR, listOf(33, 40, 45, 50, 55, 59, 64), List(7) { 0 }, midiProgram = 30)
    val bass = InstrumentDefinition(InstrumentKind.BASS, listOf(28, 33, 38, 43), List(4) { 0 }, midiProgram = 33)
    val drums = InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0)

    fun f(n: Long, d: Long = 1) = Fraction.of(n, d)
    fun d(denominator: Int, dots: Int = 0, vararg tuplets: Pair<Int, Int>) = WrittenDuration(denominator, dots, tuplets.map { TupletRatio(it.first, it.second) })

    fun measure(signature: TimeSignature = TimeSignature(), repeat: RepeatMark = RepeatMark(), nav: List<NavigationMark> = emptyList(), feel: RhythmicFeel = RhythmicFeel.STRAIGHT, key: KeySignature = KeySignature()) =
        Measure(id(), signature, key, repeat = repeat, navigation = nav, feel = feel)

    fun note(instrument: InstrumentDefinition, string: Int, fret: Int, vararg articulations: Articulation, links: List<NoteLink> = emptyList(), velocity: Int = 96, id: EntityId = id()) =
        Note(id, Pitch(instrument.pitch(string, fret)), string, fret, velocity, articulations.toList(), links)

    fun drum(midi: Int, vararg articulations: Articulation, velocity: Int = 96) = Note(id(), Pitch(midi), velocity = velocity, articulations = articulations.toList())

    fun beat(offset: Fraction, duration: WrittenDuration, vararg notes: Note, groups: List<EntityId> = emptyList(), fermata: Fermata? = null, strum: Strum? = null) =
        Beat(id(), offset, duration, notes.toList(), explicitRest = notes.isEmpty(), tupletGroupIds = groups, fermata = fermata, strum = strum)

    /** [content]: measure index -> voice index -> beats. */
    fun track(name: String, instrument: InstrumentDefinition, measures: List<Measure>, content: Map<Int, Map<Int, List<Beat>>>, mix: ChannelMix = ChannelMix()): Track {
        val notation = when (instrument.kind) {
            InstrumentKind.PERCUSSION -> setOf(NotationKind.PERCUSSION)
            InstrumentKind.GUITAR, InstrumentKind.BASS -> setOf(NotationKind.TAB, NotationKind.STANDARD)
            else -> setOf(NotationKind.STANDARD)
        }
        return Track(id(), name, instrument, listOf(Staff(id(), notation, measures.mapIndexed { i, m ->
            Bar(id(), m.id, List(4) { v -> Voice(id(), v, content[i]?.get(v).orEmpty().sortedBy { it.offset }) })
        })), mix = mix)
    }

    fun project(measures: List<Measure>, tracks: List<Track>, tempo: List<TempoChange>, sections: List<Section> = emptyList(), title: String = "Testriff") =
        Project(id = id(), metadata = ProjectMetadata(title = title, artist = "Eigene Testdaten"), measures = measures, tracks = tracks, tempo = tempo, sections = sections)

    /** Eighth notes filling [count] eighths starting at 0. */
    fun eighths(count: Int, make: (Int) -> Note): List<Beat> = List(count) { beat(f(it.toLong(), 8), d(8), make(it)) }
}

fun assertValid(project: Project) {
    val errors = ScoreValidator.validate(project).filter { it.severity == Severity.ERROR }
    assertEquals("Validator-Fehler: $errors", emptyList<Diagnostic>(), errors)
}

/**
 * Reference project: 7-string Drop A guitar, bass and drums; 4/4 and 7/8; a repeat with two alternate endings;
 * tempo changes at a bar line and inside a bar; triplets, nested 3:2 x 5:4, ties, bends, palm mute and dead notes.
 * Played route: m0 m1 m0 m2 m3 m4.
 */
class ReferenceScore(val kit: ScoreKit = ScoreKit()) {
    val m = listOf(
        kit.measure(repeat = RepeatMark(starts = true)),
        kit.measure(repeat = RepeatMark(endCount = 2, alternateEndings = setOf(1))),
        kit.measure(repeat = RepeatMark(alternateEndings = setOf(2))),
        kit.measure(TimeSignature(7, 8)),
        kit.measure(),
    )
    private val g = kit.dropA
    val isolatedBend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(kit.f(1, 2), 100), PitchCurvePoint(Fraction.ONE, 0)), BendKind.RELEASE)
    val overlappedBend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(kit.f(1, 2), 200), PitchCurvePoint(Fraction.ONE, 200)))
    val tieTarget = kit.note(g, 2, 3)
    val tieOrigin = kit.note(g, 2, 3, links = listOf(NoteLink(LinkKind.TIE, tieTarget.id)))
    val tripletGroup = kit.id()
    val outerGroup = kit.id()
    val innerGroup = kit.id()
    private val pm = Articulation.Mark(NoteMark.PALM_MUTE)

    val guitar = kit.track("Gitarre · Drop A", g, m, mapOf(
        0 to mapOf(0 to kit.eighths(8) { kit.note(g, 0, 0, pm, velocity = 100 + it) }),
        1 to mapOf(
            0 to listOf(
                kit.beat(Fraction.ZERO, kit.d(4), kit.note(g, 3, 7, overlappedBend)),
                kit.beat(kit.f(1, 4), kit.d(4), kit.note(g, 1, 5, Articulation.Mark(NoteMark.DEAD))),
                kit.beat(kit.f(1, 2), kit.d(2), kit.note(g, 0, 3), kit.note(g, 1, 5)),
            ),
            1 to listOf(kit.beat(Fraction.ZERO, kit.d(2), kit.note(g, 4, 5))),
        ),
        2 to mapOf(0 to listOf(kit.beat(Fraction.ZERO, kit.d(1), kit.note(g, 0, 0), kit.note(g, 1, 0), kit.note(g, 2, 0)))),
        3 to mapOf(0 to listOf(
            kit.beat(Fraction.ZERO, kit.d(8), kit.note(g, 0, 5, Articulation.Mark(NoteMark.STACCATO))),
            kit.beat(kit.f(1, 8), kit.d(8), kit.note(g, 0, 7)),
            kit.beat(kit.f(2, 8), kit.d(8), kit.note(g, 3, 7, isolatedBend)),
            kit.beat(kit.f(3, 8), kit.d(8), kit.note(g, 0, 8)),
            kit.beat(kit.f(4, 8), kit.d(8), kit.note(g, 0, 7)),
            kit.beat(kit.f(5, 8), kit.d(8), kit.note(g, 0, 5)),
            kit.beat(kit.f(6, 8), kit.d(8), tieOrigin),
        )),
        4 to mapOf(0 to listOf(
            kit.beat(Fraction.ZERO, kit.d(4), tieTarget),
            kit.beat(kit.f(1, 4), kit.d(8, 0, 3 to 2), kit.note(g, 3, 5), groups = listOf(tripletGroup)),
            kit.beat(kit.f(1, 4) + kit.f(1, 12), kit.d(8, 0, 3 to 2), kit.note(g, 3, 7), groups = listOf(tripletGroup)),
            kit.beat(kit.f(1, 4) + kit.f(2, 12), kit.d(8, 0, 3 to 2), kit.note(g, 3, 9), groups = listOf(tripletGroup)),
        ) + List(5) { i ->
            kit.beat(kit.f(1, 2) + kit.f(i.toLong(), 30), kit.d(16, 0, 3 to 2, 5 to 4), kit.note(g, 4, 5 + i), groups = listOf(outerGroup, innerGroup))
        } + listOf(
            kit.beat(kit.f(2, 3), kit.d(4, 0, 3 to 2), kit.note(g, 5, 5), groups = listOf(outerGroup)),
            kit.beat(kit.f(5, 6), kit.d(4, 0, 3 to 2), kit.note(g, 5, 7), groups = listOf(outerGroup)),
        )),
    ), mix = ChannelMix(volume = 0.8, pan = -0.5))

    val bassTrack = kit.track("Bass", kit.bass, m, m.indices.associateWith { i ->
        val count = if (i == 3) 7 else 8
        mapOf(0 to kit.eighths(count) { kit.note(kit.bass, 0, if (i == 3) 5 else 0) })
    })

    val drumTrack = kit.track("Drums", kit.drums, m, m.indices.associateWith { i ->
        val count = if (i == 3) 7 else 8
        mapOf(0 to List(count) { e ->
            val notes = buildList {
                add(kit.drum(42, velocity = 80))
                if (e % 4 == 0) add(kit.drum(36, velocity = 110))
                if (e % 4 == 2) add(kit.drum(38, velocity = 105))
            }
            kit.beat(kit.f(e.toLong(), 8), kit.d(8), *notes.toTypedArray())
        })
    })

    val project = kit.project(m, listOf(guitar, bassTrack, drumTrack),
        tempo = listOf(
            TempoChange(ScorePosition(m[0].id), 150.0),
            TempoChange(ScorePosition(m[3].id), 160.0),
            TempoChange(ScorePosition(m[4].id, kit.f(1, 2)), 140.0),
        ),
        sections = listOf(Section(kit.id(), m[0].id, "Riff A"), Section(kit.id(), m[3].id, "Bridge")),
    )
}
