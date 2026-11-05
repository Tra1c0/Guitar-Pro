package dev.riffworkbench.adapter.formats.qa15

import dev.riffworkbench.adapter.formats.gpif.Gp7Exporter
import dev.riffworkbench.adapter.formats.gpif.GpifImporter
import dev.riffworkbench.adapter.formats.midi.MidiExporter
import dev.riffworkbench.adapter.formats.midi.MidiImporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlExporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlImporter
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.playback.UnfoldingRepeatCompiler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** QA 15: deterministic reproductions of the shrunk round-trip findings (all minimal projects built via EditorSession). */
class Qa15FormatFindingsTest {
    private var n = 0
    private val ids = IdSource { EntityId("q${n++}") }
    private fun session(band: Boolean = false) = EditorSession(ProjectFactory(ids).create("QA15", withBand = band), ids)
    private val EditorSession.p get() = state.value.project
    private fun EditorSession.at(m: Int, off: Fraction, string: Int = 0, track: Int = 0, voice: Int = 0) =
        select(Selection(p.tracks[track].id, p.measures[m].id, voice, off, string))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun <T> v(o: Outcome<T>): T { assertTrue(o.toString(), o is Outcome.Success); return (o as Outcome.Success).value }
    private fun f(a: Long, b: Long = 1) = Fraction.of(a, b)
    private fun notes(p: Project, t: Int = 0) = p.tracks[t].staves[0].bars.flatMap { b -> b.voices.flatMap { it.beats } }.flatMap { it.notes }
    private fun xml(p: Project): Pair<ExportResult, Project> { val e = v(MusicXmlExporter().exportNow(p)); return e to v(MusicXmlImporter().importNow(e.bytes, ids)).project }
    private fun gp7(p: Project): Pair<ExportResult, Project> { val e = v(runBlocking { Gp7Exporter().export(ExportRequest(p, "gp7")) }); return e to v(runBlocking { GpifImporter().import(e.bytes, ids) }).project }
    private fun midi(p: Project): Pair<ExportResult, Project> { val e = v(MidiExporter().exportNow(p)); return e to v(MidiImporter().importNow(e.bytes, ids)).project }
    private fun plan(p: Project) = v(ScorePlaybackCompiler().compileNow(p, 48_000))

    // F1 (MIDI, L1+L4): one drum hit comes back as two hits. MidiImporter gives a hit the length "until the next hit, at most
    // a quarter" and spells it as TIED pieces; SequenceCompiler never merges ties on percussion tracks -> re-struck.
    @Test fun f1MidiDrumHitIsNotStruckTwiceAfterReimport() {
        val e = session(band = true)
        e.at(0, f(1, 8), track = 2); e.ok(EditorCommand.ToggleDrum(42, WrittenDuration(8)))
        val (out, back) = midi(e.p)
        val before = plan(e.p).notes.size; val after = plan(back).notes.size
        val drum = back.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }
        val beats = drum.staves[0].bars[0].voices[0].beats.filter { it.notes.isNotEmpty() }.map { "${it.offset}:${it.duration.denominator}${".".repeat(it.duration.dots)} ties=${it.notes.flatMap { n -> n.links }.size}" }
        assertEquals("sounding notes before/after MIDI round-trip; imported drum beats=$beats losses=${out.losses.map { it.featureId }}", before, after)
    }

    // F2 (GP7, L1): a Dynamic on one note of a chord is applied to the whole beat; the other note's velocity changes
    // (96 -> 112) without the "fx.dynamics" loss (GpifBeatWriter.dynamic only compares raw velocities).
    @Test fun f2Gp7DynamicOfOneChordNoteChangesTheOtherWithoutLoss() {
        val e = session()
        e.at(0, Fraction.ZERO, string = 0); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.at(0, Fraction.ZERO, string = 2); e.ok(EditorCommand.PutNote(fret = 7, duration = WrittenDuration(4)))
        e.at(0, Fraction.ZERO, string = 0); e.ok(EditorCommand.ApplyArticulation(Articulation.Dynamic(DynamicLevel.FF), toggle = false))
        val (out, back) = gp7(e.p)
        val s2 = plan(back).notes.first { it.string == 2 }.velocity
        val expected = plan(e.p).notes.first { it.string == 2 }.velocity
        assertTrue("string 2 sounds with velocity $expected, after GP7 $s2; losses=${out.losses.map { it.featureId }}", s2 == expected || out.losses.any { it.featureId == "fx.dynamics" })
    }

    // F3 (MusicXML, L1): a whammy dip (0 -> -400 cents) comes back as a rise from +400 to 0 (sounding pitch inverted).
    // The exporter writes the dip as <bend-alter>-4</bend-alter><release/><with-bar/>; the importer's "release without
    // previous bend = pre-bend" rule (journal cycle 8 #5) also fires for with-bar curves.
    @Test fun f3MusicXmlWhammyDipKeepsItsDirection() {
        val e = session()
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.ok(EditorCommand.ApplyArticulation(Articulation.Whammy(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(f(1, 2), -400))), toggle = false))
        val (out, back) = xml(e.p)
        val w = notes(back).single().articulations.filterIsInstance<Articulation.Whammy>().single()
        assertTrue("whammy after MusicXML: ${w.points}; losses=${out.losses}", w.points.first().cents == 0 && w.points.minOf { it.cents } == -400)
    }

    // F3b (MusicXML, L1): bend + whammy on one note: all <bend> elements are merged into ONE articulation, the bend is lost.
    @Test fun f3bMusicXmlBendAndWhammyOnOneNoteBothSurvive() {
        val e = session()
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.ok(EditorCommand.ApplyArticulation(Articulations.fullBend(), toggle = false))
        e.ok(EditorCommand.ApplyArticulation(Articulation.Whammy(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(f(1, 2), -400))), toggle = false))
        val (out, back) = xml(e.p)
        val arts = notes(back).single().articulations
        assertTrue("after MusicXML: $arts; losses=${out.losses}", arts.any { it is Articulation.Bend } || out.losses.isNotEmpty())
    }

    // F4 (MusicXML, L1): vibrato on one note of a chord spreads to every note of the chord (importer keeps vibrato per beat).
    @Test fun f4MusicXmlVibratoStaysOnItsChordNote() {
        val e = session()
        e.at(0, Fraction.ZERO, string = 0); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.at(0, Fraction.ZERO, string = 2); e.ok(EditorCommand.PutNote(fret = 7, duration = WrittenDuration(4)))
        e.ok(EditorCommand.ApplyArticulation(Articulation.Vibrato(), toggle = false))
        val (_, back) = xml(e.p)
        val withVibrato = notes(back).filter { n -> n.articulations.any { it is Articulation.Vibrato } }.map { it.string }
        assertEquals(listOf(2), withVibrato)
    }

    // F5 (MusicXML, L1): an explicit D.S. target (honoured by playback, RouteCompiler.target) is replaced by the first segno
    // without a loss entry; the played route changes. GP7 reports "repeats.navigation" for the same project.
    @Test fun f5MusicXmlExplicitDalSegnoTargetIsReportedOrKept() {
        val e = session()
        e.ok(EditorCommand.AddMeasure(TimeSignature()))
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.ok(EditorCommand.SetNavigation(e.p.measures[0].id, listOf(NavigationMark(NavigationKind.SEGNO))))
        e.ok(EditorCommand.SetNavigation(e.p.measures[2].id, listOf(NavigationMark(NavigationKind.DAL_SEGNO, e.p.measures[1].id))))
        val (out, back) = xml(e.p)
        fun route(p: Project) = v(UnfoldingRepeatCompiler().compile(p)).map { o -> p.measures.indexOfFirst { it.id == o.position.measureId } }
        assertTrue("route ${route(e.p)} -> ${route(back)}, losses=${out.losses}", route(e.p) == route(back) || out.losses.isNotEmpty())
    }

    // F6 (MusicXML, L1): text kind (SECTION, FINGERING) becomes TEXT without a loss entry (GP7 reports layout.text_lyrics).
    @Test fun f6MusicXmlTextKindIsKeptOrReported() {
        val e = session()
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.SetText("pima", TextKind.FINGERING))
        val (out, back) = xml(e.p)
        assertTrue("text after MusicXML: ${back.text}; losses=${out.losses}", back.text.single().kind == TextKind.FINGERING || out.losses.isNotEmpty())
    }

    // F7 (MusicXML, L1): HAMMER_ON/PULL_OFF mark or a SHIFT/LEGATO slide without a following note on the string is dropped
    // silently (MusicXmlWriter.link returns on target == null). GP7 reports "fx.hammer_pull".
    @Test fun f7MusicXmlHammerOnWithoutFollowingNoteIsReported() {
        val e = session()
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.ok(EditorCommand.ToggleMark(NoteMark.HAMMER_ON))
        val (out, back) = xml(e.p)
        val kept = notes(back).single().let { n -> n.articulations.contains(Articulation.Mark(NoteMark.HAMMER_ON)) || n.links.isNotEmpty() }
        assertTrue("hammer-on lost silently; losses=${out.losses}", kept || out.losses.isNotEmpty())
    }

    // F8 (MusicXML, L1): a strum/arpeggio on a single-note beat is not written (beat.notes.size > 1) and not reported.
    @Test fun f8MusicXmlStrumOnSingleNoteIsReported() {
        val e = session()
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.ok(EditorCommand.SetStrum(Strum(StrumKind.ARPEGGIO, StrokeDirection.UP)))
        val (out, back) = xml(e.p)
        val strum = back.tracks[0].staves[0].bars[0].voices[0].beats.first { it.notes.isNotEmpty() }.strum
        assertTrue("strum lost silently; losses=${out.losses}", strum != null || out.losses.isNotEmpty())
    }

    // F9 (MIDI, L1): tracks without notes disappear on import (MidiImporter keeps only groups with notes), no loss entry.
    @Test fun f9MidiEmptyTrackIsKeptOrReported() {
        val e = session(band = true)
        e.at(0, Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        val (out, back) = midi(e.p)
        assertTrue("tracks ${e.p.tracks.map { it.name }} -> ${back.tracks.map { it.name }}; losses=${out.losses.map { it.featureId }}",
            back.tracks.size == e.p.tracks.size || out.losses.any { it.featureId !in setOf("tab_position") })
    }

    // F10 (GP7 + MusicXML, L1, low): leading/trailing blanks in metadata are trimmed by XmlElement.text without a loss.
    // Fixed in the editor: SetMetadata trims every field (like Rename), so the canonical form has no surrounding blanks
    // and must survive both XML formats unchanged.
    @Test fun f10MetadataWhitespaceSurvivesXmlFormats() {
        val e = session()
        e.ok(EditorCommand.SetMetadata(ProjectMetadata(title = "  padded  ", artist = " a ", album = "\tb ", composer = " c", copyright = "d ", notes = " n\n")))
        val canonical = ProjectMetadata(title = "padded", artist = "a", album = "b", composer = "c", copyright = "d", notes = "n")
        assertEquals(canonical, e.p.metadata)
        val (o1, b1) = gp7(e.p); val (o2, b2) = xml(e.p)
        assertTrue("GP7 metadata '${b1.metadata}' losses=${o1.losses.map { it.featureId }}; MusicXML metadata '${b2.metadata}' losses=${o2.losses.map { it.featureId }}",
            b1.metadata.title == canonical.title && b2.metadata.title == canonical.title)
    }

    // F11 (GP7, L1): a drum technique leaks to other hits of the same kit piece. GpifTrackPlan.drumIndex names a plain
    // element after the FIRST entry of that MIDI note, which already carries the technique suffix ("… (side stick)");
    // the importer parses the technique from that label, so the plain 57 comes back as CROSS_STICK (sounds as 37).
    @Test fun f11Gp7DrumTechniqueDoesNotLeakToPlainHits() {
        val e = session(band = true)
        e.at(0, Fraction.ZERO, track = 2); e.ok(EditorCommand.ToggleDrum(57, WrittenDuration(4)))
        e.ok(EditorCommand.ApplyArticulation(Articulation.Percussion(PercussionTechnique.CROSS_STICK), toggle = false))
        e.at(0, f(1, 2), track = 2); e.ok(EditorCommand.ToggleDrum(57, WrittenDuration(4)))
        val (out, back) = gp7(e.p)
        val hits = back.tracks[2].staves[0].bars[0].voices[0].beats.filter { it.notes.isNotEmpty() }.associate { b -> b.offset to b.notes.single().articulations }
        assertEquals("hit at 1/2 after GP7: ${hits[f(1, 2)]}; losses=${out.losses.map { it.featureId }}", emptyList<Articulation>(), hits[f(1, 2)])
    }

    // F12 (MusicXML, L1, low): trill rate (1/16) is not written; the import uses 1/32 and no loss is reported
    // (GP7 reports fx.trill for the same project).
    @Test fun f12MusicXmlTrillRateIsReported() {
        val e = session()
        e.at(0, Fraction.ZERO, string = 3); e.ok(EditorCommand.PutNote(fret = 8, duration = WrittenDuration(4)))
        e.ok(EditorCommand.ApplyArticulation(Articulation.Trill(9, WrittenDuration(16)), toggle = false))
        val (out, back) = xml(e.p)
        val t = notes(back).single().articulations.filterIsInstance<Articulation.Trill>().single()
        assertTrue("trill after MusicXML: $t; losses=${out.losses}", t.rate == WrittenDuration(16) || out.losses.isNotEmpty())
    }
}
