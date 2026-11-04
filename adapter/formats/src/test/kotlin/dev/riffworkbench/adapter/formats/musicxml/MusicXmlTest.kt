package dev.riffworkbench.adapter.formats.musicxml

import dev.riffworkbench.adapter.formats.midi.ReferenceScore
import dev.riffworkbench.adapter.formats.midi.ScoreKit
import dev.riffworkbench.adapter.formats.midi.assertValid
import dev.riffworkbench.adapter.formats.xml.XmlElement
import dev.riffworkbench.adapter.formats.xml.XmlReader
import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MusicXmlTest {
    private fun export(project: Project): ExportResult {
        val result = runBlocking { MusicXmlExporter().export(ExportRequest(project, "musicxml")) }
        assertTrue(result.toString(), result is Outcome.Success)
        return (result as Outcome.Success).value
    }
    private fun import(bytes: ByteArray): ImportResult {
        val result = runBlocking { MusicXmlImporter().import(bytes, ScoreKit("x").ids) }
        assertTrue(result.toString(), result is Outcome.Success)
        return (result as Outcome.Success).value.also { assertValid(it.project) }
    }
    private fun failure(bytes: ByteArray): Outcome.Failure {
        val result = MusicXmlImporter().importNow(bytes, ScoreKit("x").ids)
        assertTrue(result.toString(), result is Outcome.Failure)
        return (result as Outcome.Failure).also { assertEquals("C-FORMAT", it.contractId) }
    }

    /** Sum of note and forward durations per voice (chord and grace notes do not advance time). */
    private fun voiceDurations(measure: XmlElement): Map<String, Long> {
        val sums = LinkedHashMap<String, Long>()
        for (e in measure.elements) when (e.name) {
            "note" -> if (e.child("chord") == null && e.child("grace") == null) sums.merge(e.textOf("voice")!!, e.textOf("duration")!!.toLong(), Long::plus)
            "forward" -> sums.merge(e.textOf("voice")!!, e.textOf("duration")!!.toLong(), Long::plus)
        }
        return sums
    }

    @Test fun exportIsWellFormedAndStructurallyExact() {
        val ref = ReferenceScore()
        val result = export(ref.project)
        assertEquals(MusicXmlExporter.MEDIA_TYPE, result.mediaType)
        assertEquals("Testriff.musicxml", result.fileName)
        val root = XmlReader.parse(result.bytes)
        assertEquals("score-partwise", root.name)
        assertEquals("4.0", root.attr("version"))
        assertEquals("Testriff", root.textOf("work", "work-title"))
        val parts = root.children("part")
        assertEquals(listOf("P1", "P2", "P3"), parts.map { it.attr("id") })
        assertEquals(listOf("Gitarre · Drop A", "Bass", "Drums"), root.child("part-list")!!.children("score-part").map { it.textOf("part-name") })

        val guitar = parts[0].children("measure")
        val attributes = guitar[0].child("attributes")!!
        // LCM of all denominators: triplets (1/3 quarter) and nested 3:2 x 5:4 sixteenths (2/15 quarter) -> 30.
        assertEquals("30", attributes.textOf("divisions"))
        assertEquals("2", attributes.textOf("staves"))
        assertEquals(listOf("G", "TAB"), attributes.children("clef").map { it.textOf("sign") })
        val details = attributes.child("staff-details")!!
        assertEquals("2", details.attr("number"))
        assertEquals("7", details.textOf("staff-lines"))
        assertEquals(listOf("A1", "E2", "A2", "D3", "G3", "B3", "E4"), details.children("staff-tuning").map { it.textOf("tuning-step") + it.textOf("tuning-octave") })

        // Every voice of every measure of every part fills its measure exactly.
        val lengths = listOf(120L, 120L, 120L, 105L, 120L)
        for (part in parts) part.children("measure").forEachIndexed { i, m ->
            val sums = voiceDurations(m)
            assertTrue("measure ${i + 1} of ${part.attr("id")}", sums.isNotEmpty())
            sums.forEach { (voice, sum) -> assertEquals("part ${part.attr("id")} measure ${i + 1} voice $voice", lengths[i], sum) }
        }

        // TAB notes carry string (1 = highest) and fret; the standard staff carries the same pitches.
        val firstTab = guitar[0].children("note").first { it.textOf("staff") == "2" }
        assertEquals("7", firstTab.textOf("notations", "technical", "string"))
        assertEquals("0", firstTab.textOf("notations", "technical", "fret"))
        assertEquals("palm", firstTab.textOf("play", "mute"))
        assertEquals("A1", guitar[0].children("note").first().let { it.textOf("pitch", "step") + it.textOf("pitch", "octave") })
        // Dead note and bends.
        val m2 = guitar[1].children("note").filter { it.textOf("staff") == "1" }
        assertEquals("x", m2[1].textOf("notehead"))
        assertEquals("2", m2[0].textOf("notations", "technical", "bend", "bend-alter"))
        val release = guitar[3].children("note").first { it.path("notations", "technical", "bend") != null }.path("notations", "technical")!!.children("bend")
        assertEquals(listOf("1", "-1"), release.map { it.textOf("bend-alter") })
        assertNotNull(release[1].child("release"))
        // Tie across the bar line 4 -> 5.
        val tieStart = guitar[3].children("note").filter { it.textOf("staff") == "1" }.last()
        assertEquals("start", tieStart.child("tie")!!.attr("type"))
        assertEquals("start", tieStart.path("notations", "tied")!!.attr("type"))
        assertEquals("stop", guitar[4].children("note").first().child("tie")!!.attr("type"))
        // Triplets and nested tuplets.
        val m5 = guitar[4].children("note").filter { it.textOf("staff") == "1" }
        assertEquals(listOf("3/2", "3/2", "3/2"), m5.subList(1, 4).map { it.textOf("time-modification", "actual-notes") + "/" + it.textOf("time-modification", "normal-notes") })
        assertEquals(listOf("15/8"), m5.subList(4, 9).map { it.textOf("time-modification", "actual-notes") + "/" + it.textOf("time-modification", "normal-notes") }.distinct())
        val nestedStarts = m5[4].path("notations")!!.children("tuplet").map { it.attr("type") + it.attr("number") + ":" + it.textOf("tuplet-actual", "tuplet-number") }
        assertEquals(listOf("start1:3", "start2:5"), nestedStarts)
        assertEquals(listOf("stop2"), m5[8].path("notations")!!.children("tuplet").map { it.attr("type") + it.attr("number") })
        assertEquals(listOf("stop1"), m5[10].path("notations")!!.children("tuplet").map { it.attr("type") + it.attr("number") })
        assertTrue(m5.subList(4, 9).all { it.textOf("duration") == "4" })
        // Repeats and endings.
        assertEquals("forward", guitar[0].children("barline").single().child("repeat")!!.attr("direction"))
        val right2 = guitar[1].children("barline").single { it.attr("location") == "right" }
        assertEquals("2", right2.child("repeat")!!.attr("times"))
        assertEquals("1" to "stop", right2.child("ending")!!.let { it.attr("number") to it.attr("type") })
        assertEquals("2" to "start", guitar[2].children("barline").first().child("ending")!!.let { it.attr("number") to it.attr("type") })
        assertEquals("discontinue", guitar[2].children("barline").single { it.attr("location") == "right" }.child("ending")!!.attr("type"))
        // Tempo in bar 1, bar 4 and in the middle of bar 5 (directly before the beat at 1/2), only in the first part.
        val tempos = guitar.flatMapIndexed { i, m -> m.children("direction").mapNotNull { d -> d.child("sound")?.attr("tempo")?.let { Triple(i, it, d.textOf("offset")) } } }
        assertEquals(listOf(Triple(0, "150", null), Triple(3, "160", null), Triple(4, "140", null)), tempos)
        val bar5 = guitar[4].elements
        val tempoIndex = bar5.indexOfFirst { it.name == "direction" && it.child("sound")?.attr("tempo") == "140" }
        assertEquals(4, bar5.subList(0, tempoIndex).count { it.name == "note" }) // the quarter and three triplets precede it
        assertTrue(parts.drop(1).all { p -> p.children("measure").none { it.child("direction") != null } })
        assertEquals(listOf("Riff A", "Bridge"), guitar.flatMap { m -> m.children("direction").mapNotNull { it.textOf("direction-type", "rehearsal") } })
        // Percussion: unpitched notes with instrument references on MIDI channel 10.
        val drumNote = parts[2].children("measure")[0].children("note").first()
        assertNotNull(drumNote.child("unpitched"))
        assertTrue(drumNote.child("instrument")!!.attr("id")!!.startsWith("P3-I"))
        assertTrue(root.child("part-list")!!.children("score-part")[2].children("midi-instrument").all { it.textOf("midi-channel") == "10" })
        assertTrue(result.losses.none { it.featureId in setOf("bend", "palm_mute", "dead_note", "divisions") })
    }

    @Test fun exportImportRoundTripKeepsMusic() {
        val ref = ReferenceScore()
        val imported = import(export(ref.project).bytes).project
        assertEquals(normalize(ref.project), normalize(imported))
        assertEquals(ref.project.metadata.title, imported.metadata.title)
        assertEquals(ref.project.metadata.artist, imported.metadata.artist)
        assertEquals(setOf(NotationKind.STANDARD, NotationKind.TAB), imported.tracks[0].staves.single().notation)
        assertEquals(-0.5, imported.tracks[0].mix.pan, 1e-9)
        assertEquals(0.8, imported.tracks[0].mix.volume, 1e-9)
    }

    @Test fun techniquesSurviveTheRoundTrip() {
        val kit = ScoreKit()
        val g = kit.dropA
        val base = listOf(kit.measure(nav = listOf(NavigationMark(NavigationKind.SEGNO)), feel = RhythmicFeel.SWING_EIGHTH, key = KeySignature(-3, true)),
            kit.measure(), kit.measure(), kit.measure(nav = listOf(NavigationMark(NavigationKind.CODA))))
        val m = listOf(base[0], base[1].copy(navigation = listOf(NavigationMark(NavigationKind.TO_CODA, base[3].id))),
            base[2].copy(navigation = listOf(NavigationMark(NavigationKind.DAL_SEGNO, base[0].id))), base[3])
        val hammerTarget = kit.note(g, 3, 7)
        val grace = Articulation.Grace(Pitch(g.pitch(3, 5)), WrittenDuration(32), beforeBeat = true, fret = 5, string = 3)
        val beats = listOf(
            kit.beat(Fraction.ZERO, kit.d(8), kit.note(g, 3, 5, Articulation.Mark(NoteMark.HAMMER_ON))),
            kit.beat(kit.f(1, 8), kit.d(8), hammerTarget),
            kit.beat(kit.f(1, 4), kit.d(8), kit.note(g, 2, 7, Articulation.Harmonic(HarmonicKind.NATURAL), Articulation.Mark(NoteMark.LET_RING))),
            kit.beat(kit.f(3, 8), kit.d(8), kit.note(g, 2, 5, Articulation.Harmonic(HarmonicKind.PINCH), Articulation.Vibrato())),
            kit.beat(kit.f(1, 2), kit.d(8), kit.note(g, 4, 9, Articulation.Slide(SlideKind.LEGATO))),
            kit.beat(kit.f(5, 8), kit.d(8), kit.note(g, 4, 11, Articulation.Slide(SlideKind.OUT_DOWN), Articulation.Mark(NoteMark.ACCENT))),
            kit.beat(kit.f(3, 4), kit.d(8), kit.note(g, 3, 7, grace, Articulation.Mark(NoteMark.TAPPING), Articulation.Mark(NoteMark.GHOST))),
            kit.beat(kit.f(7, 8), kit.d(8), kit.note(g, 0, 0, Articulation.Mark(NoteMark.STACCATO), Articulation.Pickstroke(StrokeDirection.UP)),
                kit.note(g, 1, 2, Articulation.Mark(NoteMark.STACCATO), Articulation.Pickstroke(StrokeDirection.UP)), strum = Strum(StrumKind.STRUM, StrokeDirection.DOWN), fermata = Fermata(FermataKind.LONG, 2.0)),
        )
        val prebend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 200), PitchCurvePoint(kit.f(1, 2), 200), PitchCurvePoint(Fraction.ONE, 0)), BendKind.PREBEND_RELEASE)
        val track = kit.track("Lead", g, m, mapOf(0 to mapOf(0 to beats), 1 to mapOf(0 to listOf(kit.beat(Fraction.ZERO, kit.d(1), kit.note(g, 3, 9, prebend, Articulation.TremoloPicking(WrittenDuration(16))))))))
        val project = kit.project(m, listOf(track), listOf(TempoChange(ScorePosition(m[0].id), 96.5)))
        val result = export(project)
        assertTrue(result.losses.toString(), result.losses.isEmpty())
        val back = import(result.bytes).project
        assertEquals(normalize(project), normalize(back))
        assertEquals(RhythmicFeel.SWING_EIGHTH, back.measures[0].feel)
        assertEquals(KeySignature(-3, true), back.measures[0].key)
        assertEquals(back.measures[0].id, back.measures[2].navigation.single().target)
        assertEquals(back.measures[3].id, back.measures[1].navigation.single().target)
        assertEquals(96.5, back.tempo.single().quarterNotesPerMinute, 1e-9)
    }

    @Test fun importsForeignStyleFileWithoutTab() {
        // Typical notation-program output: treble clef with octave transposition, two voices via backup,
        // triplet without tuplet-actual numbers, chord, tie, measure rest, D.S. al Coda via sound attributes.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 3.1 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
            <score-partwise version="3.1">
              <movement-title>Fremde Datei</movement-title>
              <part-list><score-part id="P1"><part-name>Classical Guitar</part-name>
                <score-instrument id="P1-I1"><instrument-name>Guitar</instrument-name></score-instrument>
                <midi-instrument id="P1-I1"><midi-channel>1</midi-channel><midi-program>25</midi-program></midi-instrument></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>6</divisions><key><fifths>1</fifths></key><time><beats>3</beats><beat-type>4</beat-type></time>
                    <clef><sign>G</sign><line>2</line></clef><transpose><diatonic>0</diatonic><chromatic>0</chromatic><octave-change>-1</octave-change></transpose></attributes>
                  <direction><direction-type><words>Allegro</words></direction-type><sound tempo="132"/></direction>
                  <direction><direction-type><segno/></direction-type><sound segno="s"/></direction>
                  <note><pitch><step>E</step><octave>3</octave></pitch><duration>6</duration><voice>1</voice><type>quarter</type></note>
                  <note><pitch><step>G</step><octave>3</octave></pitch><duration>2</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification><notations><tuplet type="start"/></notations></note>
                  <note><pitch><step>B</step><octave>3</octave></pitch><duration>2</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification></note>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><type>eighth</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification><notations><tuplet type="stop"/></notations></note>
                  <note><pitch><step>G</step><octave>4</octave></pitch><duration>6</duration><tie type="start"/><voice>1</voice><type>quarter</type></note>
                  <note><chord/><pitch><step>B</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><type>quarter</type></note>
                  <backup><duration>18</duration></backup>
                  <note><pitch><step>E</step><octave>2</octave></pitch><duration>18</duration><voice>2</voice><type>half</type><dot/></note>
                </measure>
                <measure number="2">
                  <note><pitch><step>G</step><octave>4</octave></pitch><duration>6</duration><tie type="stop"/><voice>1</voice><type>quarter</type></note>
                  <note><rest/><duration>12</duration><voice>1</voice><type>half</type></note>
                  <direction><direction-type><words>D.S. al Coda</words></direction-type><sound dalsegno="s"/></direction>
                </measure>
                <measure number="3">
                  <direction><direction-type><rehearsal>B</rehearsal></direction-type></direction>
                  <note><rest measure="yes"/><duration>18</duration><voice>1</voice></note>
                </measure>
              </part>
            </score-partwise>"""
        val result = import(xml.toByteArray())
        val p = result.project
        assertEquals("Fremde Datei", p.metadata.title)
        assertEquals(3, p.measures.size)
        assertEquals(TimeSignature(3, 4), p.measures[0].signature)
        assertEquals(1, p.measures[0].key.fifths)
        assertEquals(132.0, p.tempo.single().quarterNotesPerMinute, 1e-9)
        assertEquals(NavigationKind.SEGNO, p.measures[0].navigation.single().kind)
        assertEquals(NavigationMark(NavigationKind.DAL_SEGNO, p.measures[0].id), p.measures[1].navigation.single())
        assertEquals("B", p.sections.single().label)
        assertEquals("Allegro", p.text.single().text)
        val track = p.tracks.single()
        assertEquals(InstrumentKind.GUITAR, track.instrument.kind) // program 25 (0-based 24)
        assertEquals(24, track.instrument.midiProgram)
        val bar = track.staves.single().bars[0]
        val v0 = bar.voices[0].beats
        // Transpose octave-change -1: written E3 sounds E2 (MIDI 40).
        assertEquals(40, v0[0].notes.single().pitch.midiNumber)
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(1, 3), Fraction.of(5, 12), Fraction.of(1, 2)), v0.map { it.offset })
        assertTrue(v0.subList(1, 4).all { it.duration == WrittenDuration(8, 0, listOf(TupletRatio(3, 2))) && it.tupletGroupIds.size == 1 })
        assertEquals(1, v0.subList(1, 4).map { it.tupletGroupIds }.distinct().size)
        assertEquals(2, v0[4].notes.size)
        assertEquals(WrittenDuration(2, 1), bar.voices[1].beats.single().duration)
        assertEquals(28, bar.voices[1].beats.single().notes.single().pitch.midiNumber)
        // Frets were computed and agree with the instrument.
        for (b in track.staves.single().bars) for (v in b.voices) for (beat in v.beats) for (n in beat.notes)
            assertEquals(track.instrument.pitch(n.string!!, n.fret!!), n.pitch.midiNumber)
        assertTrue(v0[4].notes.map { it.string }.distinct().size == 2)
        // Tie across the bar line, rest, and an empty bar from the measure rest.
        val target = track.staves.single().bars[1].voices[0].beats.first().notes.single()
        assertEquals(55, target.pitch.midiNumber)
        assertEquals(target.id, v0[4].notes.single { it.pitch.midiNumber == 55 }.links.single { it.kind == LinkKind.TIE }.target)
        assertTrue(v0[0].notes.single().links.isEmpty())
        assertTrue(track.staves.single().bars[1].voices[0].beats[1].explicitRest)
        assertTrue(track.staves.single().bars[2].voices.all { it.beats.isEmpty() })
    }

    @Test fun timewiseScoresAreConverted() {
        val xml = """<score-timewise version="4.0"><part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
            <measure number="1"><part id="P1"><attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
            <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>whole</type></note></part></measure>
            <measure number="2"><part id="P1"><note><pitch><step>D</step><alter>1</alter><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>whole</type></note></part></measure>
            </score-timewise>"""
        val p = import(xml.toByteArray()).project
        assertEquals(InstrumentKind.PIANO, p.tracks.single().instrument.kind)
        assertEquals(listOf(60, 63), p.tracks.single().staves.single().bars.map { it.voices[0].beats.single().notes.single().pitch.midiNumber })
        assertEquals("D#4", p.tracks.single().staves.single().bars[1].voices[0].beats.single().notes.single().pitch.spelling)
    }

    @Test fun malformedXmlFails() {
        assertEquals(FailureCode.CORRUPT, failure("<score-partwise><part-list></score-partwise>".toByteArray()).code)
        assertEquals(FailureCode.CORRUPT, failure("""<!DOCTYPE x [<!ENTITY a "b">]><score-partwise/>""".toByteArray()).code)
        assertEquals(FailureCode.CORRUPT, failure("<opus/>".toByteArray()).code)
        assertEquals(FailureCode.CORRUPT, failure("<score-partwise version=\"4.0\"><part-list/></score-partwise>".toByteArray()).code)
        assertEquals(FailureCode.CORRUPT, failure(ByteArray(0)).code)
        // XmlReader throws StringIndexOutOfBoundsException for input ending after '='; it must still be a typed failure.
        assertEquals(FailureCode.CORRUPT, failure("<score-partwise version=".toByteArray()).code)
        val badDuration = """<score-partwise><part-list><score-part id="P1"><part-name>A</part-name></score-part></part-list><part id="P1"><measure>
            <note><pitch><step>C</step><octave>4</octave></pitch><duration>-4</duration></note></measure></part></score-partwise>"""
        assertEquals(FailureCode.CORRUPT, failure(badDuration.toByteArray()).code)
        val huge = """<score-partwise><part-list><score-part id="P1"><part-name>A</part-name></score-part></part-list><part id="P1"><measure>
            <attributes><divisions>1</divisions></attributes><forward><duration>100000000</duration></forward></measure></part></score-partwise>"""
        assertEquals(FailureCode.CORRUPT, failure(huge.toByteArray()).code)
        assertEquals(FailureCode.RESOURCE_LIMIT, failure(ByteArray(64 * 1024 * 1024 + 1)).code)
    }

    // ---- .mxl container ------------------------------------------------------------------------------------------

    private val container = """<?xml version="1.0" encoding="UTF-8"?><container><rootfiles><rootfile full-path="score.musicxml" media-type="application/vnd.recordare.musicxml+xml"/></rootfiles></container>"""

    @Test fun compressedMxlImportsLikeUncompressed() {
        val ref = ReferenceScore()
        val xml = export(ref.project).bytes
        val mxl = TestZip.build(listOf(TestZip.Entry("mimetype", "application/vnd.recordare.musicxml".toByteArray(), deflate = false),
            TestZip.Entry("META-INF/container.xml", container.toByteArray()), TestZip.Entry("score.musicxml", xml)))
        assertEquals(normalize(import(xml).project), normalize(import(mxl).project))
    }

    @Test fun unsafeMxlContainersAreRejected() {
        val score = export(ReferenceScore().project).bytes
        fun mxl(vararg entries: TestZip.Entry) = TestZip.build(entries.toList())
        fun rootEntries(path: String) = arrayOf(TestZip.Entry("META-INF/container.xml", container.replace("score.musicxml", path).toByteArray()), TestZip.Entry(path, score))
        for (bad in listOf("../score.musicxml", "/score.musicxml", "a\\score.musicxml", "C:score.musicxml", "a/../score.musicxml", "./score.musicxml")) {
            val f = failure(mxl(*rootEntries(bad)))
            assertEquals(bad, FailureCode.CORRUPT, f.code)
        }
        // Duplicate entry names (also differing only in case).
        assertEquals(FailureCode.CORRUPT, failure(mxl(TestZip.Entry("META-INF/container.xml", container.toByteArray()), TestZip.Entry("score.musicxml", score), TestZip.Entry("score.musicxml", score))).code)
        assertEquals(FailureCode.CORRUPT, failure(mxl(TestZip.Entry("META-INF/container.xml", container.toByteArray()), TestZip.Entry("score.musicxml", score), TestZip.Entry("SCORE.musicxml", score))).code)
        // More than 256 entries.
        val many = (0 until 257).map { TestZip.Entry("img$it.png", byteArrayOf(1)) } + TestZip.Entry("META-INF/container.xml", container.toByteArray()) + TestZip.Entry("score.musicxml", score)
        assertEquals(FailureCode.RESOURCE_LIMIT, failure(TestZip.build(many)).code)
        // Decompression bomb: 70 MiB of zeros in one entry (> 64 MiB per entry).
        val bomb = TestZip.Entry("score.musicxml", ByteArray(70 * 1024 * 1024))
        assertEquals(FailureCode.RESOURCE_LIMIT, failure(mxl(TestZip.Entry("META-INF/container.xml", container.toByteArray()), bomb)).code)
        // Total over 128 MiB across entries, each one below the per-entry limit.
        val zeros = ByteArray(45 * 1024 * 1024)
        val parts = (0 until 3).map { TestZip.Entry("extra$it.bin", zeros) }
        assertEquals(FailureCode.RESOURCE_LIMIT, failure(TestZip.build(listOf(TestZip.Entry("META-INF/container.xml", container.toByteArray()), TestZip.Entry("score.musicxml", score)) + parts)).code)
        // Header lies about the size: counting while inflating catches it.
        val lying = TestZip.build(listOf(TestZip.Entry("META-INF/container.xml", container.toByteArray()), TestZip.Entry("score.musicxml", score, declaredSize = 100)))
        assertEquals(FailureCode.CORRUPT, failure(lying).code)
        // CRC mismatch, truncation, missing root file, missing container without unique score.
        assertEquals(FailureCode.CORRUPT, failure(TestZip.build(listOf(TestZip.Entry("META-INF/container.xml", container.toByteArray()), TestZip.Entry("score.musicxml", score, crcOverride = 1234)))).code)
        val good = mxl(*rootEntries("score.musicxml"))
        assertEquals(FailureCode.CORRUPT, failure(good.copyOf(good.size - 30)).code)
        assertEquals(FailureCode.CORRUPT, failure(mxl(TestZip.Entry("META-INF/container.xml", container.toByteArray()))).code)
        assertEquals(FailureCode.CORRUPT, failure(mxl(TestZip.Entry("a.xml", score), TestZip.Entry("b.xml", score))).code)
        // Without container.xml a single root-level score is accepted.
        assertTrue(MusicXmlImporter().importNow(mxl(TestZip.Entry("only.musicxml", score)), ScoreKit("z").ids) is Outcome.Success)
    }

    companion object {
        /** Comparable, ID-free view of a project: measures, tempo, sections, tracks, beats, notes and links. */
        fun normalize(p: Project): List<String> = buildList {
            val mIndex = p.measures.withIndex().associate { it.value.id to it.index }
            p.measures.forEachIndexed { i, m -> add("M$i ${m.signature} ${m.key} ${m.pickupLength} ${m.repeat} ${m.navigation.map { it.kind to it.target?.let(mIndex::get) }} ${m.feel}") }
            p.tempo.forEach { add("T ${mIndex[it.position.measureId]} ${it.position.offset} ${it.quarterNotesPerMinute}") }
            p.sections.forEach { add("S ${mIndex[it.measureId]} ${it.label}") }
            val where = HashMap<EntityId, String>()
            for ((ti, t) in p.tracks.withIndex()) for ((si, s) in t.staves.withIndex()) for ((mi, b) in s.bars.withIndex()) for (v in b.voices) for (beat in v.beats) for (n in beat.notes)
                where[n.id] = "t$ti/s$si/m$mi/v${v.index}@${beat.offset}#${n.string}:${n.pitch.midiNumber}"
            for ((ti, t) in p.tracks.withIndex()) {
                val i = t.instrument
                add("TR$ti ${t.name} ${i.kind} ${i.tuning} ${i.capoByString} ${i.maxFret} ${i.midiProgram} ${t.mix.volume} ${t.mix.pan} ${t.staves.map { it.notation.sorted() }}")
                for ((si, s) in t.staves.withIndex()) for ((mi, b) in s.bars.withIndex()) for (v in b.voices) {
                    val groups = HashMap<EntityId, Int>()
                    for (beat in v.beats.sortedBy { it.offset }) {
                        val g = beat.tupletGroupIds.map { groups.getOrPut(it) { groups.size } }
                        add("B$ti/$si/$mi/${v.index} ${beat.offset} ${beat.duration} rest=${beat.explicitRest || beat.notes.isEmpty()} g=$g ${beat.fermata} ${beat.strum}")
                        for (n in beat.notes.sortedWith(compareBy({ it.string ?: -1 }, { it.pitch.midiNumber }))) {
                            val arts = n.articulations.map { it.toString() }.sorted()
                            add("  N ${n.pitch.midiNumber}/${n.pitch.cents} ${n.string}:${n.fret} v${n.velocity} $arts ${n.links.map { it.kind to where[it.target] }} ${n.fingering}")
                        }
                    }
                }
            }
        }
    }
}

/** Minimal ZIP writer for container tests; can produce duplicate names, lying sizes and bad CRCs on purpose. */
object TestZip {
    class Entry(val name: String, val data: ByteArray, val deflate: Boolean = true, val declaredSize: Long? = null, val crcOverride: Long? = null)

    fun build(entries: List<Entry>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val central = java.io.ByteArrayOutputStream()
        fun le16(o: java.io.OutputStream, v: Int) { o.write(v and 0xFF); o.write((v ushr 8) and 0xFF) }
        fun le32(o: java.io.OutputStream, v: Long) { le16(o, (v and 0xFFFF).toInt()); le16(o, ((v ushr 16) and 0xFFFF).toInt()) }
        for (e in entries) {
            val name = e.name.toByteArray(Charsets.UTF_8)
            val payload = if (e.deflate) {
                val d = java.util.zip.Deflater(9, true)
                d.setInput(e.data); d.finish()
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(1 shl 16)
                while (!d.finished()) { val n = d.deflate(chunk); buf.write(chunk, 0, n) }
                d.end(); buf.toByteArray()
            } else e.data
            val crc = e.crcOverride ?: java.util.zip.CRC32().apply { update(e.data) }.value
            val size = e.declaredSize ?: e.data.size.toLong()
            val offset = out.size().toLong()
            val method = if (e.deflate) 8 else 0
            le32(out, 0x04034b50); le16(out, 20); le16(out, 0x800); le16(out, method); le16(out, 0); le16(out, 0)
            le32(out, crc); le32(out, payload.size.toLong()); le32(out, size); le16(out, name.size); le16(out, 0); out.write(name); out.write(payload)
            le32(central, 0x02014b50); le16(central, 20); le16(central, 20); le16(central, 0x800); le16(central, method); le16(central, 0); le16(central, 0)
            le32(central, crc); le32(central, payload.size.toLong()); le32(central, size); le16(central, name.size); le16(central, 0); le16(central, 0)
            le16(central, 0); le16(central, 0); le32(central, 0); le32(central, offset); central.write(name)
        }
        val cdOffset = out.size().toLong()
        val cd = central.toByteArray()
        out.write(cd)
        le32(out, 0x06054b50); le16(out, 0); le16(out, 0); le16(out, entries.size and 0xFFFF); le16(out, entries.size and 0xFFFF)
        le32(out, cd.size.toLong()); le32(out, cdOffset); le16(out, 0)
        return out.toByteArray()
    }
}
