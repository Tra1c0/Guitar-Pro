package dev.riffworkbench.adapter.formats.ascii

import dev.riffworkbench.adapter.formats.midi.ReferenceScore
import dev.riffworkbench.adapter.formats.midi.ScoreKit
import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AsciiTabExporterTest {
    /** Small own project: standard-tuned guitar with the common techniques and a drum grid. */
    private fun smallProject(): Project {
        val kit = ScoreKit("a")
        val e = InstrumentDefinition(InstrumentKind.GUITAR, listOf(40, 45, 50, 55, 59, 64), List(6) { 0 }, midiProgram = 29)
        val m = listOf(kit.measure(repeat = RepeatMark(starts = true)), kit.measure(repeat = RepeatMark(endCount = 2)))
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(kit.f(1, 2), 200), PitchCurvePoint(Fraction.ONE, 200)))
        val guitar = kit.track("Rhythmus", e, m, mapOf(
            0 to mapOf(0 to listOf(
                kit.beat(Fraction.ZERO, kit.d(8), kit.note(e, 0, 0, pm)),
                kit.beat(kit.f(1, 8), kit.d(8), kit.note(e, 0, 0, pm)),
                kit.beat(kit.f(1, 4), kit.d(8), kit.note(e, 2, 5, Articulation.Mark(NoteMark.HAMMER_ON))),
                kit.beat(kit.f(3, 8), kit.d(8), kit.note(e, 2, 7)),
                kit.beat(kit.f(1, 2), kit.d(4), kit.note(e, 3, 7, bend)),
                kit.beat(kit.f(3, 4), kit.d(4), kit.note(e, 4, 12, Articulation.Harmonic(HarmonicKind.NATURAL))),
            )),
            1 to mapOf(0 to listOf(
                kit.beat(Fraction.ZERO, kit.d(2), kit.note(e, 5, 8, Articulation.Vibrato())),
                kit.beat(kit.f(1, 2), kit.d(8), kit.note(e, 1, 5, Articulation.Slide(SlideKind.SHIFT))),
                kit.beat(kit.f(5, 8), kit.d(8), kit.note(e, 1, 7, Articulation.Mark(NoteMark.PULL_OFF))),
                kit.beat(kit.f(3, 4), kit.d(4), kit.note(e, 0, 0), kit.note(e, 1, 0, Articulation.Mark(NoteMark.DEAD))),
            )),
        ))
        val drums = kit.track("Drums", kit.drums, m, mapOf(
            0 to mapOf(0 to List(8) { i ->
                val notes = listOfNotNull(kit.drum(42), if (i % 4 == 0) kit.drum(36) else null, if (i == 2) kit.drum(38) else null,
                    if (i == 6) kit.drum(38, Articulation.Mark(NoteMark.ACCENT)) else null)
                kit.beat(kit.f(i.toLong(), 8), kit.d(8), *notes.toTypedArray())
            }),
            1 to mapOf(0 to listOf(
                kit.beat(Fraction.ZERO, kit.d(4), kit.drum(49), kit.drum(36)),
                kit.beat(kit.f(1, 4), kit.d(4)),
                kit.beat(kit.f(1, 2), kit.d(4), kit.drum(38, Articulation.Mark(NoteMark.GHOST))),
                kit.beat(kit.f(3, 4), kit.d(4), kit.drum(38)),
            )),
        ))
        return kit.project(m, listOf(guitar, drums), listOf(TempoChange(ScorePosition(m[0].id), 120.0)),
            sections = listOf(Section(kit.id(), m[0].id, "Intro")), title = "ASCII-Test")
    }

    private fun export(project: Project): ExportResult {
        val result = runBlocking { AsciiTabExporter().export(ExportRequest(project, "ascii")) }
        assertTrue(result.toString(), result is Outcome.Success)
        return (result as Outcome.Success).value
    }

    @Test fun snapshotOfSmallProject() {
        val result = export(smallProject())
        assertEquals(AsciiTabExporter.MEDIA_TYPE, result.mediaType)
        assertEquals("ASCII-Test.txt", result.fileName)
        val text = result.bytes.toString(Charsets.UTF_8)
        assertEquals(SNAPSHOT, text)
        assertEquals(setOf("rhythm"), result.losses.map { it.featureId }.toSet())
        assertEquals(2, result.losses.size) // once per track
    }

    @Test fun outputIsDeterministicAndAligned() {
        val ref = ReferenceScore()
        val first = export(ref.project)
        val second = export(ref.project)
        assertArrayEquals(first.bytes, second.bytes)
        val text = first.bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains('\t'))
        // All instrument lines of one system have the same length and identical bar-line columns.
        for (system in text.split("\n\n")) {
            val rows = system.lines().filter { it.length > 3 && it[3] == '|' }
            if (rows.isEmpty()) continue
            assertEquals(system, 1, rows.map { it.length }.distinct().size)
            assertEquals(system, 1, rows.map { r -> r.indices.filter { r[it] == '|' || r[it] == ':' } }.distinct().size)
        }
        assertTrue(text.contains("7b8r7")) // bend and release in the 7/8 bar
        assertTrue(text.contains("PM"))
        assertTrue(text.contains("4 7/8 [Bridge] T=160"))
        assertTrue(text.contains("HH |:x-x-x-x-x-x-x-x-|"))
        assertTrue(first.losses.any { it.featureId == "tie" && it.entityId == ref.guitar.id })
        assertTrue(first.losses.any { it.featureId == "voices" })
    }

    @Test fun pitchedTracksWithoutStringsUseNoteNames() {
        val kit = ScoreKit("p")
        val piano = InstrumentDefinition(InstrumentKind.PIANO, emptyList(), emptyList(), midiProgram = 0)
        val m = listOf(kit.measure())
        val chord = listOf(Note(kit.id(), Pitch(60)), Note(kit.id(), Pitch(64)), Note(kit.id(), Pitch(67)))
        val t = kit.track("Keys", piano, m, mapOf(0 to mapOf(0 to listOf(Beat(kit.id(), Fraction.ZERO, kit.d(1), chord)))))
        val result = export(kit.project(m, listOf(t), listOf(TempoChange(ScorePosition(m[0].id), 90.0))))
        val text = result.bytes.toString(Charsets.UTF_8)
        assertTrue(text, text.contains("Noten |-C4+E4+G4"))
        assertTrue(result.losses.any { it.featureId == "pitch_track" })
    }

    @Test fun wrongFormatAndInvalidProjectFail() {
        val p = smallProject()
        assertEquals(FailureCode.VALIDATION, (runBlocking { AsciiTabExporter().export(ExportRequest(p, "midi")) } as Outcome.Failure).code)
        val broken = p.copy(tracks = emptyList())
        assertEquals("C-FORMAT", (AsciiTabExporter().exportNow(broken) as Outcome.Failure).contractId)
    }

    private companion object {
        val SNAPSHOT = """ASCII-Test
Interpret: Eigene Testdaten
Tempo: 120 BPM

Spur 1: Rhythmus
Stimmung (tief -> hoch): E2 A2 D3 G3 B3 E4

     1 4/4 [Intro]                     2                              x2
      PM----
e  |:---------------------------------|-8~------------------------------:|
B  |:-------------------------<12>----|---------------------------------:|
G  |:-----------------7b9-------------|---------------------------------:|
D  |:---------5h--7-------------------|---------------------------------:|
A  |:---------------------------------|-----------------5/--7p--x-------:|
E  |:-0---0---------------------------|-------------------------0-------:|

Spur 2: Drums (Schlagzeug)

     1 4/4 [Intro]    2             x2
CC |:----------------|x---------------:|
HH |:x-x-x-x-x-x-x-x-|----------------:|
SD |:----o-------O---|--------g---o---:|
BD |:o-------o-------|o---------------:|

Legende: h Hammer-on, p Pull-off, b Bending (Zielbund), pb Pre-Bend, r Release, / \ Slide, ~ Vibrato, x Dead Note, (n) Ghost Note, <n> Flageolett, [n] künstliches Flageolett, t Tapping, PM Palm-Mute, |: :| Wiederholung, xN Anzahl der Durchgänge
Schlagzeug: o Schlag, x Becken/Hi-Hat, O X Akzent, g Ghost Note; BD Bass Drum, SD Snare, HH Hi-Hat zu, HO Hi-Hat offen, CC Crash, RD Ride, T1-T4 Toms, FT Floor Tom
"""
    }
}
