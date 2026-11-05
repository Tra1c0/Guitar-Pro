package dev.riffworkbench.adapter.formats

import dev.riffworkbench.adapter.formats.gpif.GpifImporter
import dev.riffworkbench.adapter.formats.midi.MidiImporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlImporter
import dev.riffworkbench.model.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Small hostile files that used to take 20 s to minutes (quadratic work) and a silent integer overflow.
 * Before the fixes these inputs took 26 s (GPIF), 18 s (MIDI), 23 s and 31 s (MusicXML) on a desktop JVM.
 */
class ImportComplexityRegressionTest {
    private fun ids(): IdSource { var n = 0; return IdSource { EntityId("q${n++}") } }

    private fun gpZip(gpif: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("Content/score.gpif")); z.write(gpif.toByteArray()); z.closeEntry() }
        return out.toByteArray()
    }

    /** One <Beat> with many children, referenced many times from one <Voice>. */
    fun sharedFatBeat(refs: Int, junk: Int): String = buildString {
        append("<GPIF><Score><Title>x</Title></Score><MasterTrack><Tracks>0</Tracks></MasterTrack>")
        append("<Tracks><Track id=\"0\"><Name>A</Name><Staves><Staff><Properties><Property name=\"Tuning\"><Pitches>40 45 50 55 59 64</Pitches></Property></Properties></Staff></Staves></Track></Tracks>")
        append("<MasterBars><MasterBar><Time>4/4</Time><Bars>0</Bars></MasterBar></MasterBars>")
        append("<Bars><Bar id=\"0\"><Voices>0 -1 -1 -1</Voices></Bar></Bars>")
        append("<Voices><Voice id=\"0\"><Beats>"); repeat(refs) { append("0 ") }; append("</Beats></Voice></Voices>")
        append("<Beats><Beat id=\"0\"><Rhythm ref=\"0\"/>"); repeat(junk) { append("<x/>") }; append("</Beat></Beats>")
        append("<Rhythms><Rhythm id=\"0\"><NoteValue>Quarter</NoteValue></Rhythm></Rhythms></GPIF>")
    }

    @Test(timeout = 60_000) fun gpifSharedFatBeatIsQuadratic() {
        for ((refs, junk) in listOf(2_000 to 20_000, 20_000 to 200_000)) {
            val bytes = gpZip(sharedFatBeat(refs, junk))
            val t0 = System.nanoTime()
            val r = runBlocking { GpifImporter().import(bytes, ids()) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("GPIF refs=$refs junk=$junk zip=${bytes.size}B -> ${r.javaClass.simpleName} in $ms ms")
        }
    }

    /** SMF: one long note with a pitch-bend event on every tick (running status). */
    fun bendFlood(ppq: Int, bends: Int): ByteArray {
        val body = ByteArrayOutputStream()
        fun vlq(v: Int) { var b = v and 0x7F; var x = v ushr 7; val st = ArrayList<Int>(); st += b; while (x > 0) { st += (x and 0x7F) or 0x80; x = x ushr 7 }; st.reversed().forEach { body.write(it) } }
        vlq(0); body.write(0x90); body.write(60); body.write(100)
        vlq(1); body.write(0xE0); body.write(0); body.write(0x50)
        for (i in 1 until bends) { vlq(1); body.write(i and 0x7F); body.write(0x40 + (i % 32)) }
        vlq(1); body.write(0x80); body.write(60); body.write(0)
        vlq(0); body.write(0xFF); body.write(0x2F); body.write(0)
        val b = body.toByteArray()
        val out = ByteArrayOutputStream()
        fun i32(v: Int) { out.write(v ushr 24); out.write(v ushr 16 and 0xFF); out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        out.write("MThd".toByteArray()); i32(6); out.write(0); out.write(0); out.write(0); out.write(1); out.write(ppq ushr 8); out.write(ppq and 0xFF)
        out.write("MTrk".toByteArray()); i32(b.size); out.write(b)
        return out.toByteArray()
    }

    private fun header(divisions: Int, beats: Int, type: Int) =
        "<score-partwise version=\"4.0\"><part-list><score-part id=\"P1\"><part-name>Piano</part-name></score-part></part-list><part id=\"P1\">" +
        "<measure number=\"1\"><attributes><divisions>$divisions</divisions><time><beats>$beats</beats><beat-type>$type</beat-type></time></attributes>"

    /** Eighth notes alternating C4/D4, every one with an unresolvable tie start. */
    fun danglingTies(notes: Int): ByteArray = buildString {
        append(header(2, 4, 4))
        for (i in 0 until notes) {
            if (i > 0 && i % 8 == 0) append("</measure><measure number=\"${i / 8 + 1}\">")
            val step = if (i % 2 == 0) "C" else "D"
            append("<note><pitch><step>$step</step><octave>4</octave></pitch><duration>1</duration><tie type=\"start\"/><type>eighth</type></note>")
        }
        append("</measure></part></score-partwise>")
    }.toByteArray()

    /** Many tiny notes in one long measure: voice-slot placement scans all earlier beats of the slot. */
    fun denseMeasure(notes: Int): ByteArray = buildString {
        append(header(256, 64, 1))
        repeat(notes) { append("<note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><type>1024th</type></note>") }
        append("</measure></part></score-partwise>")
    }.toByteArray()

    @Test(timeout = 10_000) fun gpifSharedWideBeatIsLinear() {
        val r = runBlocking { GpifImporter().import(gpZip(sharedFatBeat(20_000, 200_000)), ids()) }
        assertTrue(r.toString(), r is Outcome.Success || r is Outcome.Failure)
    }

    @Test(timeout = 10_000) fun midiBendFloodIsLinear() {
        val r = MidiImporter().importNow(bendFlood(32_000, 128_000), ids())
        assertTrue(r is Outcome.Success)
    }

    @Test(timeout = 10_000) fun musicXmlUnresolvedTiesAreLinear() {
        val r = MusicXmlImporter().importNow(danglingTies(20_000), ids())
        assertTrue(r is Outcome.Success)
    }

    @Test(timeout = 10_000) fun musicXmlDenseMeasureIsNotQuadratic() {
        val r = MusicXmlImporter().importNow(denseMeasure(16_000), ids())
        assertTrue(r is Outcome.Success)
    }

    @Test fun compositeTimeSignatureDoesNotOverflow() {
        val xml = "<score-partwise version=\"4.0\"><part-list><score-part id=\"P1\"><part-name>Piano</part-name></score-part></part-list><part id=\"P1\">" +
            "<measure number=\"1\"><attributes><divisions>1</divisions><time><beats>1073741825</beats><beat-type>4</beat-type><beats>1</beats><beat-type>16</beat-type></time></attributes>" +
            "<note><rest/><duration>1</duration><type>quarter</type></note></measure></part></score-partwise>"
        val r = MusicXmlImporter().importNow(xml.toByteArray(), ids()) as Outcome.Success
        assertEquals(TimeSignature(), r.value.project.measures.first().signature)
        assertTrue(r.value.losses.any { it.featureId == "time_signature" })
    }
}
