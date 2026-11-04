package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.xml.XmlReader
import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpifRoundTripTest {
    @get:Rule val temp = TemporaryFolder()

    private fun <T> ok(o: Outcome<T>): T { assertTrue(o.toString(), o is Outcome.Success); return (o as Outcome.Success).value }
    private fun export(p: Project) = ok(runBlocking { Gp7Exporter().export(ExportRequest(p, "gp7")) })
    private fun import(bytes: ByteArray, prefix: String = "i") = ok(runBlocking { GpifImporter().import(bytes, GpifTestSupport.ids(prefix)) })

    @Test fun richProjectSurvivesExportAndReimportSemantically() {
        val original = RichProject(GpifTestSupport.ids("o")).build()
        assertTrue(ScoreValidator.validate(original).none { it.severity == Severity.ERROR })
        val exported = export(original)
        assertEquals("Export darf für diese Merkmale nichts verlieren: ${exported.losses}", emptyList<LossEntry>(), exported.losses)
        val imported = import(exported.bytes)
        assertTrue(ScoreValidator.validate(imported.project).filter { it.severity == Severity.ERROR }.toString(), ScoreValidator.validate(imported.project).none { it.severity == Severity.ERROR })
        val allowed = setOf("layout.stylesheet", "layout.text_lyrics")
        assertTrue("Unerwartete Importverluste: ${imported.losses}", imported.losses.all { it.featureId in allowed && it.direction == "import" })
        val expected = GpifTestSupport.dump(original)
        val actual = GpifTestSupport.dump(imported.project)
        if (expected != actual) {
            val e = expected.lines(); val a = actual.lines()
            val first = e.indices.firstOrNull { it >= a.size || e[it] != a[it] } ?: a.size
            fail("Erste Abweichung in Zeile $first:\nerwartet: ${e.getOrNull(first)}\nerhalten: ${a.getOrNull(first)}")
        }
    }

    @Test fun reimportedProjectExportsToIdenticalBytes() {
        val original = RichProject(GpifTestSupport.ids("o")).build()
        val first = export(original).bytes
        assertArrayEquals("Export muss deterministisch sein", first, export(original).bytes)
        val second = export(import(first).project).bytes
        assertArrayEquals("Export → Import → Export muss stabil sein", first, second)
    }

    @Test fun archiveHasTheExpectedEntriesAndIsReadableByZipFile() {
        val bytes = export(RichProject(GpifTestSupport.ids("o")).build()).bytes
        val file = File(temp.root, "rich.gp").apply { writeBytes(bytes) }
        ZipFile(file).use { zip ->
            assertEquals(listOf("VERSION", "Content/", "Content/PartConfiguration", "Content/score.gpif"), zip.entries().toList().map { it.name })
            assertEquals("7.0", zip.getInputStream(zip.getEntry("VERSION")).readBytes().toString(Charsets.US_ASCII))
            val gpif = XmlReader.parse(zip.getInputStream(zip.getEntry("Content/score.gpif")).readBytes())
            assertEquals("GPIF", gpif.name)
            assertEquals("7", gpif.attr("version"))
            assertEquals("0 1 2", gpif.textOf("MasterTrack", "Tracks"))
            val part = zip.getInputStream(zip.getEntry("Content/PartConfiguration")).readBytes()
            assertEquals(listOf(3, 2, 1), PartConfiguration.read(part))
        }
        // Local header of the first entry: fixed MS-DOS time 00:00 and date 1980-01-01, independent of the time zone.
        assertEquals(0, (bytes[10].toInt() and 0xFF) or ((bytes[11].toInt() and 0xFF) shl 8))
        assertEquals(0x21, (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8))
        assertEquals(GpifFamily.GP7, GpifDetector.classify(bytes).family)
    }

    @Test fun gpifUnitsAreConvertedExactly() {
        val bytes = export(RichProject(GpifTestSupport.ids("o")).build()).bytes
        val xml = XmlReader.parse(GpZipReader.read(bytes, { it == "Content/score.gpif" }).files.getValue("Content/score.gpif"))
        val notes = xml.child("Notes")!!.children("Note")
        val bend = notes.map { it.props() }.first { it["Bended"] != null && it["BendMiddleValue"] != null }
        // 200 cents = one whole tone = GPIF value 100; middle at 50 % of the note.
        assertEquals("100", bend["BendMiddleValue"]!!.textOf("Float"))
        assertEquals("50", bend["BendMiddleOffset1"]!!.textOf("Float"))
        assertEquals("100", bend["BendDestinationOffset"]!!.textOf("Float"))
        val harmonics = notes.map { it.props() }.filter { it["HarmonicType"] != null }
        assertEquals(setOf("Natural", "Pinch"), harmonics.map { it["HarmonicType"]!!.textOf("HType") }.toSet())
        assertTrue(harmonics.all { it["HarmonicFret"]!!.textOf("HFret") == "12" })
        val rhythms = xml.child("Rhythms")!!.children("Rhythm")
        val nested = rhythms.single { it.child("SecondaryTuplet") != null }
        assertEquals("3", nested.child("PrimaryTuplet")!!.attr("num")); assertEquals("2", nested.child("PrimaryTuplet")!!.attr("den"))
        assertEquals("5", nested.child("SecondaryTuplet")!!.attr("num")); assertEquals("4", nested.child("SecondaryTuplet")!!.attr("den"))
        val tempo = xml.path("MasterTrack", "Automations")!!.children("Automation").map { it.textOf("Bar") + "@" + it.textOf("Position") + "=" + it.textOf("Value") }
        assertEquals(listOf("0@0=140 2", "2@0.5=160 2"), tempo)
        val jumps = xml.child("MasterBars")!!.children("MasterBar").flatMap { mb -> mb.child("Directions")?.elements.orEmpty().map { it.name + ":" + it.text } }
        assertEquals(listOf("Target:Segno", "Jump:DaCoda", "Jump:DaSegnoAlCoda", "Target:Coda"), jumps)
        val beats = xml.child("Beats")!!.children("Beat")
        assertTrue(beats.any { it.textOf("GraceNotes") == "BeforeBeat" })
        assertTrue(beats.any { it.textOf("Tremolo") == "1/4" })
        assertTrue(beats.any { it.props()["Brush"]?.textOf("Direction") == "Down" && it.path("XProperties", "XProperty")?.textOf("Int") == "60" })
        // Whammy dive of -400 cents = -2 semitones = GPIF value -200 (same unit as bends).
        assertTrue(beats.any { it.props()["WhammyBar"] != null && it.props()["WhammyBarMiddleValue"]?.textOf("Float") == "-200" })
        assertTrue(beats.any { it.child("Legato")?.attr("origin") == "true" } && beats.any { it.child("Legato")?.attr("destination") == "true" })
    }

    @Test fun exporterRefusesInvalidProjectsAndOtherFormats() = runBlocking {
        val p = RichProject(GpifTestSupport.ids("o")).build()
        val broken = p.copy(tracks = p.tracks + p.tracks.first())
        val invalid = Gp7Exporter().export(ExportRequest(broken, "gp7")) as Outcome.Failure
        assertEquals(FailureCode.VALIDATION, invalid.code); assertEquals("C-FORMAT", invalid.contractId)
        val wrong = Gp7Exporter().export(ExportRequest(p, "gp8")) as Outcome.Failure
        assertEquals(FailureCode.VALIDATION, wrong.code)
    }

    @Test fun unrepresentableFeaturesAreReportedOnExport() {
        val base = RichProject(GpifTestSupport.ids("o")).build()
        val track = base.tracks.first()
        val changed = base.copy(
            tracks = listOf(track.copy(instrument = track.instrument.copy(fineTuneCents = 12.0), mix = ChannelMix(volume = 0.5))) + base.tracks.drop(1),
            measures = base.measures.mapIndexed { i, m -> if (i == 2) m.copy(feel = RhythmicFeel.TRIPLET, forcedSystemBreak = true) else m },
            scales = listOf(ScaleDefinition("Phrygisch", listOf(0, 1, 3, 5, 7, 8, 10))),
        )
        val features = export(changed).losses.map { it.featureId }.toSet()
        assertTrue(features.toString(), features.containsAll(setOf("sound.fine_tuning", "mix.channels", "rhythm.swing", "layout.breaks", "tools.scales")))
        assertTrue(export(changed).losses.all { it.direction == "export" && it.reason.isNotBlank() })
    }

    @Test fun partialCapoTranspositionAndPickupRoundTrip() {
        val ids = GpifTestSupport.ids("c")
        val instrument = InstrumentDefinition(capoByString = listOf(0, 0, 0, 0, 2, 2, 2), transposition = -2)
        val measures = listOf(Measure(ids.next(), TimeSignature(4, 4), pickupLength = GpifTestSupport.f(1, 4)), Measure(ids.next()))
        fun bar(m: Measure, beats: List<Beat>) = Bar(ids.next(), m.id, List(4) { Voice(ids.next(), it, if (it == 0) beats else emptyList()) })
        val pickup = Beat(ids.next(), Fraction.ZERO, WrittenDuration(4), listOf(Note(ids.next(), Pitch(instrument.pitch(5, 3)), 5, 3)))
        val full = Beat(ids.next(), Fraction.ZERO, WrittenDuration(1), listOf(Note(ids.next(), Pitch(instrument.pitch(0, 7)), 0, 7), Note(ids.next(), Pitch(instrument.pitch(6, 0)), 6, 0)))
        val p = Project(id = ids.next(), measures = measures, tracks = listOf(Track(ids.next(), "Kapo", instrument, listOf(Staff(ids.next(), bars = listOf(bar(measures[0], listOf(pickup)), bar(measures[1], listOf(full))))))))
        val result = export(p)
        assertTrue(result.losses.map { it.featureId }.contains("notes.transpose"))
        val back = import(result.bytes).project
        val i = back.tracks.single().instrument
        assertEquals(listOf(0, 0, 0, 0, 2, 2, 2), i.capoByString)
        assertEquals(instrument.tuning.map { it - 2 }, i.tuning)
        assertEquals(GpifTestSupport.f(1, 4), back.measures.first().pickupLength)
        val sounding = { q: Project -> q.tracks.single().staves.single().bars.flatMap { b -> b.voices.first().beats.flatMap { it.notes.map { n -> Triple(n.string, n.fret, n.pitch.midiNumber) } } } }
        assertEquals(sounding(p), sounding(back))
    }

    @Test fun gapsBecomeRestsAndEmptyBarsBecomeWholeBarRests() {
        val ids = GpifTestSupport.ids("g")
        val factory = dev.riffworkbench.editor.ProjectFactory(ids)
        val p0 = factory.create("Lücken")
        val guitar = p0.tracks.first()
        val bar = guitar.staves.first().bars.first()
        val late = Beat(ids.next(), GpifTestSupport.f(3, 8), WrittenDuration(8), listOf(Note(ids.next(), Pitch(guitar.instrument.pitch(0, 3)), 0, 3)))
        val p = p0.copy(tracks = listOf(guitar.copy(staves = listOf(guitar.staves.first().copy(bars = listOf(bar.copy(voices = bar.voices.map { if (it.index == 0) it.copy(beats = listOf(late)) else it })) + guitar.staves.first().bars.drop(1))))) + p0.tracks.drop(1))
        val result = export(p)
        assertTrue(result.losses.any { it.featureId == "rhythm.rest" })
        val back = import(result.bytes).project
        val beats = back.tracks.first().staves.first().bars.first().voices.first().beats
        assertEquals(listOf(Fraction.ZERO, GpifTestSupport.f(1, 4), GpifTestSupport.f(3, 8)), beats.map { it.offset })
        assertTrue(beats.take(2).all { it.explicitRest })
        assertTrue("Leere Takte bleiben leer", back.tracks[1].staves.first().bars.all { b -> b.voices.all { it.beats.isEmpty() } })
    }
}
