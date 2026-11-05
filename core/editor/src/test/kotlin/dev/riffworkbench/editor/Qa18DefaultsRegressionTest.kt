package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 18 / D4: the product defaults of a new project (rewritten from the probe that pinned the old 4/4 + 7/8, Drop-A shape).
 * - a new project has ONE 4/4 bar, 150 BPM, section "Riff A" and the guitar/bass/drums band,
 * - the default guitar is a 6-string E standard named "Gitarre",
 * - ProjectFactory.demo() reproduces the old shape for the editor regressions and the committed fixtures,
 * - the model default InstrumentDefinition() stays Drop A.
 */
class Qa18DefaultsRegressionTest {
    private var n = 0
    private val ids = IdSource { EntityId("d4-${n++}") }
    private val eStandard = listOf(40, 45, 50, 55, 59, 64)
    private val dropA = listOf(33, 40, 45, 50, 55, 59, 64)

    @Test fun newProjectHasExactlyOneFourFourBar() {
        val p = ProjectFactory(ids).create("Neu")
        assertEquals(listOf(TimeSignature(4, 4)), p.measures.map { it.signature })
    }

    @Test fun defaultGuitarIsSixStringEStandardNamedGitarre() {
        val g = ProjectFactory(ids).create("Neu").tracks.first { it.instrument.kind == InstrumentKind.GUITAR }
        assertEquals(eStandard, g.instrument.tuning)
        assertEquals("Gitarre", g.name)
        assertEquals(List(6) { 0 }, g.instrument.capoByString)
        assertEquals(setOf(NotationKind.TAB), g.staves.single().notation)
        assertEquals(1, g.staves.single().bars.size)
    }

    /** Smoke test of the editor on the new default: string 1 (low E) fret 12 sounds E3 = 52. */
    @Test fun putNoteOnTheLowStringOfTheNewDefaultSoundsE3() {
        val e = EditorSession(ProjectFactory(ids).create("Neu"), ids)
        e.select(e.state.value.selection.copy(string = 0))
        assertTrue(e.dispatch(EditorCommand.PutNote(fret = 12)) is Outcome.Success)
        val note = e.state.value.project.tracks[0].staves[0].bars[0].voices[0].beats.single().notes.single()
        assertEquals(52, note.pitch.midiNumber); assertEquals(0, note.string); assertEquals(12, note.fret)
        assertTrue(ScoreValidator.validate(e.state.value.project).none { it.severity == Severity.ERROR })
    }

    @Test fun otherDefaultsStay() {
        val p = ProjectFactory(ids).create("Neu")
        assertEquals("Neu", p.metadata.title)
        assertEquals(150.0, p.tempo.single().quarterNotesPerMinute, 0.0)
        assertEquals(p.measures.first().id, p.tempo.single().position.measureId)
        assertEquals(listOf("Riff A"), p.sections.map { it.label })
        assertEquals(listOf(InstrumentKind.GUITAR, InstrumentKind.BASS, InstrumentKind.PERCUSSION), p.tracks.map { it.instrument.kind })
        assertEquals(listOf("Gitarre", "Bass", "Drums"), p.tracks.map { it.name })
        assertEquals(listOf(InstrumentKind.GUITAR), ProjectFactory(ids).create("Solo", withBand = false).tracks.map { it.instrument.kind })
    }

    @Test fun createIsParameterised() {
        val p = ProjectFactory(ids).create("Eigen", withBand = false, signatures = listOf(TimeSignature(3, 4), TimeSignature(6, 8)),
            guitar = InstrumentDefinition(tuning = listOf(38, 45, 50, 55, 59, 64), capoByString = List(6) { 0 }), guitarName = "Drop D")
        assertEquals(listOf(TimeSignature(3, 4), TimeSignature(6, 8)), p.measures.map { it.signature })
        assertEquals("Drop D", p.tracks.single().name)
        assertEquals(38, p.tracks.single().instrument.tuning.first())
        assertEquals(2, p.tracks.single().staves.single().bars.size)
    }

    /** The shape the editor regressions and the committed fixtures were written against. */
    @Test fun demoReproducesTheOldShape() {
        val p = ProjectFactory(ids).demo("Alt")
        assertEquals(listOf(TimeSignature(4, 4), TimeSignature(7, 8)), p.measures.map { it.signature })
        val g = p.tracks.first { it.instrument.kind == InstrumentKind.GUITAR }
        assertEquals(dropA, g.instrument.tuning)
        assertEquals("Gitarre · Drop A", g.name)
        assertEquals(InstrumentDefinition(), g.instrument)
        assertEquals(3, p.tracks.size)
        assertEquals(1, ProjectFactory(ids).demo("Alt", withBand = false).tracks.size)
    }

    /** The model default is left alone: fixtures, formats and the added-track default rely on it. */
    @Test fun modelDefaultInstrumentIsStillDropA() {
        assertEquals(dropA, InstrumentDefinition().tuning)
        assertEquals(33, InstrumentDefinition().pitch(0, 0))
        assertEquals(dropA, ProjectFactory(ids).track(InstrumentKind.GUITAR, emptyList()).instrument.tuning)
    }

    /** largeFixture keeps its 7-string guitars (the committed layout fixtures are not regenerated). */
    @Test fun largeFixtureKeepsSevenStrings() {
        val p = largeFixture(ids, measures = 3, tracks = 2)
        assertEquals(3, p.measures.size)
        assertTrue(p.tracks.all { it.instrument.tuning == dropA })
    }

    /** An empty Drop-A track retunes to E standard without any problem and the selection string is clamped. */
    @Test fun emptyTrackRetunesToEStandardAndSelectionStringIsClamped() {
        val e = EditorSession(ProjectFactory(ids).demo("Retune"), ids)
        val track = e.state.value.project.tracks[0]
        e.select(e.state.value.selection.copy(string = 6))
        val target = track.instrument.copy(tuning = eStandard, capoByString = List(6) { 0 })
        assertTrue(TrackEdits.retuneProblems(e.state.value.project, track.id, target, RetuningPolicy.KEEP_SOUNDING_PITCH).isEmpty())
        assertTrue(e.dispatch(EditorCommand.SetInstrument(track.id, target, RetuningPolicy.KEEP_SOUNDING_PITCH)) is Outcome.Success)
        assertEquals(eStandard, e.state.value.project.tracks[0].instrument.tuning)
        assertEquals(5, e.state.value.selection.string)
    }
}
