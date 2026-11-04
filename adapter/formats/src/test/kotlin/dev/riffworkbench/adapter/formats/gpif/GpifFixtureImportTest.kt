package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.gpif.GpifTestSupport.f
import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GpifFixtureImportTest {
    private val gpif = javaClass.getResourceAsStream("/gpif/handwritten-gp7.gpif")!!.readBytes().toString(Charsets.UTF_8)
    private val result: ImportResult by lazy {
        val r = runBlocking { GpifImporter().import(GpifTestSupport.gpZip(gpif), GpifTestSupport.ids("h")) }
        assertTrue(r.toString(), r is Outcome.Success)
        (r as Outcome.Success).value
    }
    private val p get() = result.project
    private fun voice(track: Int, measure: Int, voice: Int = 0) = p.tracks[track].staves.first().bars[measure].voices.first { it.index == voice }.beats
    private fun note(track: Int, measure: Int, beat: Int, voice: Int = 0, n: Int = 0) = voice(track, measure, voice)[beat].notes.sortedBy { it.string ?: it.pitch.midiNumber }[n]

    @Test fun projectIsValidAndMetadataIsMapped() {
        assertTrue(ScoreValidator.validate(p).filter { it.severity == Severity.ERROR }.toString(), ScoreValidator.validate(p).none { it.severity == Severity.ERROR })
        assertEquals(ProjectMetadata("Handgeschriebene Probe", "RiffWorkbench", "Fixtures", "Eigenkomposition", "CC0-1.0", "Eigene Testdaten"), p.metadata)
    }

    @Test fun tracksTuningCapoProgramAndMixer() {
        val lead = p.tracks[0]
        assertEquals("Lead", lead.name)
        assertEquals(InstrumentKind.GUITAR, lead.instrument.kind)
        assertEquals(listOf(40, 45, 50, 55, 59, 64), lead.instrument.tuning)
        assertEquals(List(6) { 2 }, lead.instrument.capoByString)
        assertEquals(29, lead.instrument.midiProgram)
        assertEquals(ChannelMix(volume = 0.6, pan = -0.5, solo = true), lead.mix)
        assertEquals(setOf(NotationKind.TAB), lead.staves.first().notation)
        val kit = p.tracks[1]
        assertEquals(InstrumentKind.PERCUSSION, kit.instrument.kind)
        assertTrue(kit.instrument.tuning.isEmpty() && kit.instrument.capoByString.isEmpty())
        assertEquals(listOf(36, 38, 37, 91), kit.instrument.drumKit.map { it.midiNote })
        assertEquals(setOf(NotationKind.PERCUSSION), kit.staves.first().notation)
    }

    @Test fun measuresRepeatsNavigationTempoAndPickup() {
        assertEquals(listOf(TimeSignature(3, 4), TimeSignature(3, 4), TimeSignature(6, 8)), p.measures.map { it.signature })
        assertTrue(p.measures.all { it.key == KeySignature(-2, false) })
        assertEquals(f(1, 4), p.measures[0].pickupLength)
        assertEquals(RepeatMark(starts = true), p.measures[1].repeat)
        assertEquals(RepeatMark(endCount = 3), p.measures[2].repeat)
        assertEquals(RhythmicFeel.SWING_SIXTEENTH, p.measures[2].feel)
        assertEquals(listOf(NavigationKind.FINE, NavigationKind.DA_CAPO), p.measures[2].navigation.map { it.kind })
        assertEquals(listOf(Section(p.sections.single().id, p.measures[0].id, "Auftakt")), p.sections)
        // 80 dotted quarters per minute = 120 quarters; position 0.5 of a 3/4 bar = 3/8.
        assertEquals(listOf(TempoChange(ScorePosition(p.measures[0].id), 90.0), TempoChange(ScorePosition(p.measures[1].id, f(3, 8)), 120.0)), p.tempo)
    }

    @Test fun rhythmGraceTieLegatoAndPositions() {
        val first = note(0, 0, 0)
        assertEquals(Pitch(66, spelling = "F#"), first.pitch)
        assertEquals(80, first.velocity)
        assertTrue(Articulation.Dynamic(DynamicLevel.MF) in first.articulations)
        assertEquals("Bm", p.chords.single { it.id == voice(0, 0)[0].chordId }.name)
        val bar1 = voice(0, 1)
        assertEquals(listOf(f(0), f(1, 12), f(1, 6), f(1, 4), f(3, 8), f(5, 8)), bar1.map { it.offset })
        assertEquals(WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2))), bar1[0].duration)
        val tied = bar1[0].notes.single()
        assertEquals(Articulation.Grace(Pitch(59), WrittenDuration(32), true, 2, 3), tied.articulations.filterIsInstance<Articulation.Grace>().single())
        val destination = bar1[1].notes.single()
        assertEquals(listOf(NoteLink(LinkKind.TIE, destination.id)), tied.links)
        assertEquals(4, destination.fret); assertEquals(61, destination.pitch.midiNumber)
        assertEquals(listOf(NoteLink(LinkKind.PULL_OFF, bar1[3].notes.single().id)), bar1[2].notes.single().links)
        assertEquals(Fermata(FermataKind.MEDIUM, 1.5), bar1[3].fermata)
        assertEquals(Fermata(FermataKind.MEDIUM, 1.5), voice(1, 1)[1].fermata)
    }

    @Test fun bendHarmonicSlideAndBeatTechniques() {
        val bent = note(0, 1, 4)
        assertEquals(Articulation.Bend(listOf(PitchCurvePoint(f(0), 0), PitchCurvePoint(f(3, 10), 200), PitchCurvePoint(f(3, 5), 200), PitchCurvePoint(f(1), 0)), BendKind.RELEASE),
            bent.articulations.filterIsInstance<Articulation.Bend>().single())
        assertTrue(bent.articulations.containsAll(listOf(Articulation.Vibrato(depthCents = 60), Articulation.Mark(NoteMark.ACCENT), Articulation.Mark(NoteMark.GHOST), Articulation.Mark(NoteMark.LET_RING), Articulation.Dynamic(DynamicLevel.FF))))
        assertEquals(112, bent.velocity)
        val harmonic = note(0, 1, 5)
        assertEquals(Articulation.Harmonic(HarmonicKind.ARTIFICIAL, f(1, 3)), harmonic.articulations.filterIsInstance<Articulation.Harmonic>().single())
        val bar2 = voice(0, 2)
        assertEquals(Articulation.Slide(SlideKind.LEGATO, bar2[0].notes.single().id), harmonic.articulations.filterIsInstance<Articulation.Slide>().single())
        assertEquals(WrittenDuration(4, 2), bar2[0].duration)
        assertTrue(Articulation.Pickstroke(StrokeDirection.UP) in bar2[0].notes.single().articulations)
        assertTrue(Articulation.TremoloPicking(WrittenDuration(32)) in bar2[1].notes.single().articulations)
        assertEquals(Strum(StrumKind.ARPEGGIO, StrokeDirection.DOWN, f(1, 32)), bar2[2].strum)
        assertEquals(listOf("Fill" to TextKind.TEXT, "ah" to TextKind.LYRICS), p.text.map { it.text to it.kind })
        val second = voice(0, 2, voice = 1).single().notes.single()
        assertTrue(second.articulations.containsAll(listOf(Articulation.Mark(NoteMark.PALM_MUTE), Articulation.Mark(NoteMark.DEAD))))
    }

    @Test fun drumsUseArticulationOutputNotesAndTechniques() {
        val beats = voice(1, 1)
        assertEquals(listOf(36, 37), beats[0].notes.map { it.pitch.midiNumber }.sorted())
        assertTrue(beats.flatMap { it.notes }.all { it.string == null && it.fret == null })
        assertEquals(listOf(Articulation.Percussion(PercussionTechnique.CROSS_STICK)), beats[0].notes.first { it.pitch.midiNumber == 37 }.articulations.filterIsInstance<Articulation.Percussion>())
        assertTrue(Articulation.Mark(NoteMark.GHOST) in beats[1].notes.single().articulations)
        assertEquals(Articulation.Percussion(PercussionTechnique.RIMSHOT), beats[2].notes.single().articulations.filterIsInstance<Articulation.Percussion>().single())
        assertTrue("Pickup-Takt ohne Drums und reiner Pausentakt bleiben leer", voice(1, 0).isEmpty() && voice(1, 2).isEmpty())
    }

    @Test fun unsupportedDataIsReportedNotDropped() {
        val features = result.losses.map { it.featureId }.toSet()
        val expected = setOf("projects.metadata", "layout.page", "layout.multitrack", "sound.rse_mapping", "layout.stylesheet", "layout.text_lyrics",
            "tempo.changes", "arrangement.sections", "signature.time", "import.unknown_fields", "mix.eq_fx")
        assertTrue("Fehlend: ${expected - features}", features.containsAll(expected))
        assertTrue(result.losses.all { it.direction == "import" && it.reason.isNotBlank() })
    }
}
