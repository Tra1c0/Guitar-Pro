package dev.riffworkbench.adapter.formats

import dev.riffworkbench.adapter.formats.ascii.AsciiTabExporter
import dev.riffworkbench.adapter.formats.gpif.Gp7Exporter
import dev.riffworkbench.adapter.formats.gpif.GpifImporter
import dev.riffworkbench.adapter.formats.midi.MidiExporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlExporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlImporter
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.playback.Timing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PropertyFindingsRegressionTest {
    private var n = 0
    private val ids = IdSource { EntityId("r${n++}") }
    private fun session(band: Boolean = true) = EditorSession(ProjectFactory(ids).demo("QA", withBand = band), ids)
    private fun EditorSession.at(m: Int, off: Fraction, string: Int = 0, track: Int = 0) =
        select(Selection(state.value.project.tracks[track].id, state.value.project.measures[m].id, 0, off, string))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private val p get() = 0
    private fun plan(p: Project) = (ScorePlaybackCompiler().compileNow(p, 48000) as Outcome.Success).value

    // R1
    @Test fun asciiExportOfNewProjectWithEmptyDrumTrack() {
        val project = ProjectFactory(ids).create("QA")          // guitar, bass, empty drums
        val r = AsciiTabExporter().exportNow(project)
        assertTrue("ASCII export failed: $r", r is Outcome.Success)
    }

    // R2
    @Test fun gp7FermataMultiplierOneSurvives() {
        val e = session(band = false); e.at(0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.ok(EditorCommand.SetFermata(Fermata(FermataKind.MEDIUM, 1.0)))
        val out = (runBlocking { Gp7Exporter().export(ExportRequest(e.state.value.project, "gp7")) } as Outcome.Success).value
        val back = (runBlocking { GpifImporter().import(out.bytes, ids) } as Outcome.Success).value.project
        val f = back.tracks[0].staves[0].bars[0].voices[0].beats.first { it.notes.isNotEmpty() }.fermata
        assertEquals("export losses=${out.losses}", 1.0, f!!.multiplier, 1e-9)
    }

    // R3
    @Test fun insertMeasureAtStartKeepsStartTempo() {
        val e = session(band = false)
        val oldFirst = e.state.value.project.measures[0].id
        e.ok(EditorCommand.InsertMeasures(0, 1))
        val p1 = e.state.value.project
        assertTrue("start tempo lost: ${p1.tempo}", p1.tempo.any { it.position == ScorePosition(p1.measures[0].id) })
    }
    @Test fun insertMeasureAtStartThenStartTempoCanBeDeleted() {
        val e = session(band = false)
        val oldFirst = e.state.value.project.measures[0].id
        e.ok(EditorCommand.InsertMeasures(0, 1))
        val r = e.dispatch(EditorCommand.SetTempoAt(oldFirst, null))
        val p1 = e.state.value.project
        assertTrue("r=$r tempo=${p1.tempo}; playback qpm bar1 = ${4 * 60.0 / Timing.measureTimings(p1)[0].durationSeconds}", p1.tempo.isNotEmpty())
    }
    @Test fun insertMeasureAtStartMusicXmlRoundTripKeepsTempo() {
        val e = session(band = false); e.ok(EditorCommand.InsertMeasures(0, 1))
        val p1 = e.state.value.project
        val out = (MusicXmlExporter().exportNow(p1) as Outcome.Success).value
        val back = (MusicXmlImporter().importNow(out.bytes, ids) as Outcome.Success).value.project
        val before = Timing.measureTimings(p1)[0].durationSeconds; val after = Timing.measureTimings(back)[0].durationSeconds
        assertEquals("bar 1 duration changes; losses=${out.losses} tempo ${p1.tempo} -> ${back.tempo}", before, after, 1e-6)
    }

    // R4
    @Test fun moveToStringOnTiedNoteKeepsTieOnOneString() {
        val e = session(band = false)
        e.at(0, Fraction.of(15, 16)); e.ok(EditorCommand.PutNote(fret = 7))      // string 0 (A1=33) fret 7 = 40
        e.at(1, Fraction.ZERO); e.ok(EditorCommand.ToggleTie)
        assertEquals(1, plan(e.state.value.project).notes.size)                  // tie merged into one sounding note
        val r = e.dispatch(EditorCommand.MoveToString(1))                       // 40 = string 1 open
        val project = e.state.value.project
        val notes = project.tracks[0].staves[0].bars.flatMap { it.voices[0].beats }.flatMap { it.notes }
        val tied = notes.first { it.links.any { l -> l.kind == LinkKind.TIE } }; val target = notes.first { it.id == tied.links.first().target }
        assertEquals("MoveToString=$r: tie now connects string ${tied.string} -> ${target.string}; plan notes=${plan(project).notes.size}", tied.string, target.string)
    }

    // R5
    @Test fun toggleTieAcrossAGapIsRejected() {
        val e = session(band = false)
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(16)))
        e.at(0, Fraction.of(1, 2)); val r = e.dispatch(EditorCommand.ToggleTie)
        if (r is Outcome.Success) {
            val project = e.state.value.project
            val gp = (runBlocking { Gp7Exporter().export(ExportRequest(project, "gp7")) } as Outcome.Success).value
            fail("tie across 7/16 of silence accepted; plan notes=${plan(project).notes.size} (tie ignored by playback); gp7 losses=${gp.losses.map { it.featureId }}")
        }
    }

    // R6: kept playable-or-explained: the compiler refuses (intended), the validator now warns so the score view says why.
    @Test fun dalSegnoWithoutSegnoIsFlagged() {
        val e = session(band = false)
        e.ok(EditorCommand.SetNavigation(e.state.value.project.measures[1].id, listOf(NavigationMark(NavigationKind.DAL_SEGNO))))
        assertTrue(ScoreValidator.validate(e.state.value.project).any { it.code == "navigation_segno" })
    }
    @Test fun navigationToAMissingBarIsRejected() {
        val e = session(band = false)
        val r = e.dispatch(EditorCommand.SetNavigation(e.state.value.project.measures[1].id, listOf(NavigationMark(NavigationKind.DA_CAPO, EntityId("nowhere")))))
        assertTrue(r is Outcome.Failure)
    }

    // R7
    @Test fun nonFiniteValuesAreRejectedByTheEditor() {
        val e = session(band = false)
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 5))
        val accepted = mutableListOf<String>()
        fun tryCmd(c: EditorCommand) { if (e.dispatch(c) is Outcome.Success) accepted += c.toString() }
        tryCmd(EditorCommand.SetFermata(Fermata(FermataKind.MEDIUM, Double.NaN)))
        tryCmd(EditorCommand.ApplyArticulation(Articulation.Vibrato(rateHz = Double.NaN)))
        tryCmd(EditorCommand.SetInstrument(e.state.value.project.tracks[0].id, InstrumentDefinition(fineTuneCents = Double.NaN), RetuningPolicy.KEEP_SOUNDING_PITCH))
        val h = AssetHash("a".repeat(64))
        tryCmd(EditorCommand.AddAudioClip(AudioClip(EntityId("clip1"), h, "c", gain = Double.NaN), AssetDescriptor(h, "a.wav", "audio/wav", 4)))
        val pl = plan(e.state.value.project)
        assertTrue("accepted: $accepted; plan soundingPitch=${pl.notes.map { it.soundingPitch }}", accepted.isEmpty())
    }
}
