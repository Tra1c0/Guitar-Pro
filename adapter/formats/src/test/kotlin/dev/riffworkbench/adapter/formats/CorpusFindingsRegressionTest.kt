package dev.riffworkbench.adapter.formats

import dev.riffworkbench.adapter.formats.gpif.Gp7Exporter
import dev.riffworkbench.adapter.formats.gpif.GpifImporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlImporter
import dev.riffworkbench.editor.ProjectFactory
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipInputStream

/**
 * Synthetic reproductions of findings from real-world corpus testing (QA cycle 8: alphaTab test-data, W3C MusicXML,
 * jazz-soft MIDI; used locally, not bundled). No third-party data in this test.
 */
class CorpusFindingsRegressionTest {
    private class Ids(val prefix: String) : IdSource { var n = 0; override fun next() = EntityId("$prefix-${n++}") }
    private val catalog = FormatRegistry.catalog()
    private val importers: Map<String, ScoreImporter> = FormatRegistry.importers()
    private fun Project.allNotes(): List<Note> = tracks.flatMap { t -> t.staves.flatMap { s -> s.bars.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { it.notes } } } } }
    private fun xml(body: String, partAttrs: String = "") = """<?xml version="1.0" encoding="UTF-8"?>
<score-partwise version="4.0"><part-list><score-part id="P1"><part-name>P</part-name></score-part></part-list>
<part id="P1"><measure number="1"><attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>$body</measure></part></score-partwise>""".toByteArray()
    private fun mx(bytes: ByteArray) = MusicXmlImporter().importNow(bytes, Ids("r"))

    // F1: Guitar Pro 7/8 writes unsigned data descriptors as (crc, uncompressed, compressed); ZipInputStream rejects them.
    private fun gpStyleZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(); val central = ByteArrayOutputStream(); var count = 0
        fun le(n: Int, v: Long) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply { if (n == 2) putShort(v.toShort()) else putInt(v.toInt()) }.array()
        for ((name, data) in entries) {
            val off = out.size().toLong()
            val crc = CRC32().apply { update(data) }.value
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true).apply { setInput(data); finish() }
            val buf = ByteArray(data.size * 2 + 64); val clen = d.deflate(buf); d.end()
            val nb = name.toByteArray()
            out.write(le(4, 0x04034b50)); out.write(le(2, 20)); out.write(le(2, 8)); out.write(le(2, 8)); out.write(le(2, 0)); out.write(le(2, 0x21))
            out.write(le(4, 0)); out.write(le(4, 0)); out.write(le(4, 0)); out.write(le(2, nb.size.toLong())); out.write(le(2, 0)); out.write(nb)
            out.write(buf, 0, clen)
            out.write(le(4, crc)); out.write(le(4, data.size.toLong())); out.write(le(4, clen.toLong())) // swapped sizes, no signature
            central.write(le(4, 0x02014b50)); central.write(le(2, 20)); central.write(le(2, 20)); central.write(le(2, 8)); central.write(le(2, 8)); central.write(le(2, 0)); central.write(le(2, 0x21))
            central.write(le(4, crc)); central.write(le(4, clen.toLong())); central.write(le(4, data.size.toLong())); central.write(le(2, nb.size.toLong())); central.write(le(2, 0)); central.write(le(2, 0))
            central.write(le(2, 0)); central.write(le(2, 0)); central.write(le(4, 0)); central.write(le(4, off)); central.write(nb); count++
        }
        val cdOff = out.size().toLong(); val cd = central.toByteArray(); out.write(cd)
        out.write(le(4, 0x06054b50)); out.write(le(2, 0)); out.write(le(2, 0)); out.write(le(2, count.toLong())); out.write(le(2, count.toLong())); out.write(le(4, cd.size.toLong())); out.write(le(4, cdOff)); out.write(le(2, 0))
        return out.toByteArray()
    }
    private fun exportedGpif(): Map<String, ByteArray> {
        val p = ProjectFactory(Ids("f")).create("Repro", withBand = false)
        val bytes = (runBlocking { Gp7Exporter().export(ExportRequest(p, "gp7")) } as Outcome.Success).value.bytes
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes() } }
        return m
    }

    @Test fun gp7ArchiveWithGuitarProStyleDataDescriptorsIsReadable() {
        val zip = gpStyleZip(exportedGpif())
        assertTrue("detect: ${catalog.detect(zip, "x.gp")}", catalog.detect(zip, "x.gp") is Outcome.Success)
        val r = runBlocking { GpifImporter("gp7").import(zip, Ids("g")) }
        assertTrue("import: $r", r is Outcome.Success)
    }

    // F2: <offset sound="yes"> may be negative (MusicXML: signed divisions); the whole file is rejected.
    @Test fun negativeSoundingDirectionOffsetIsAccepted() {
        val r = mx(xml("""<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><type>whole</type></note>
<direction><direction-type><words>rit.</words></direction-type><offset sound="yes">-1</offset><sound tempo="60"/></direction>"""))
        assertTrue("$r", r is Outcome.Success)
    }

    // F3: <technical><fret> without <string> (or vice versa) yields Note(string=null, fret=0): validator rejects the file.
    @Test fun fretWithoutStringDoesNotRejectTheFile() {
        val r = mx(xml("""<note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><type>whole</type><notations><technical><fret>0</fret></technical></notations></note>"""))
        assertTrue("$r", r is Outcome.Success)
        val r2 = mx(xml("""<note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><type>whole</type><notations><technical><string>1</string></technical></notations></note>"""))
        assertTrue("$r2", r2 is Outcome.Success)
    }

    // F4: GPIF chord diagrams: Fret values are relative to Diagram@baseFret; baseFret is ignored.
    @Test fun gpifChordDiagramHonoursBaseFret() {
        val files = exportedGpif().toMutableMap()
        var gpif = String(files["Content/score.gpif"]!!)
        val diagram = """<Property name="DiagramCollection"><Items><Item id="0" name="D"><Diagram stringCount="6" fretCount="5" baseFret="4">""" +
            """<Fret string="1" fret="1"/><Fret string="2" fret="3"/><Fret string="3" fret="3"/><Fret string="4" fret="3"/><Fret string="5" fret="1"/></Diagram></Item></Items></Property>"""
        val staffProps = gpif.indexOf("<Properties>", gpif.indexOf("<Staff"))
        assertTrue("exported GPIF has a <Staff><Properties>", staffProps > 0)
        gpif = gpif.substring(0, staffProps + "<Properties>".length) + diagram + gpif.substring(staffProps + "<Properties>".length)
        val beatStart = gpif.indexOf(">", gpif.indexOf("<Beat id=")) + 1
        gpif = gpif.substring(0, beatStart) + "<Chord>0</Chord>" + gpif.substring(beatStart)
        files["Content/score.gpif"] = gpif.toByteArray()
        val out = ByteArrayOutputStream(); java.util.zip.ZipOutputStream(out).use { z -> files.forEach { (n, b) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() } }
        val r = runBlocking { GpifImporter("gp7").import(out.toByteArray(), Ids("g")) }
        val chord = (r as Outcome.Success).value.project.chords.single()
        // strings 0..5 high->low in GPIF; model frets low->high
        assertEquals(listOf(5, 7, 7, 7, 5), chord.frets.filterNotNull())
        assertEquals(5, chord.firstFret)
    }

    // F5: a release without a preceding bend dips below the fretted pitch; several bends collapse onto one instant.
    @Test fun musicXmlReleaseWithoutBendStaysAboveFrettedPitch() {
        val r = mx(xml("""<note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><type>whole</type><notations><technical><bend><bend-alter>-2</bend-alter><release/></bend></technical></notations></note>"""))
        val bend = (r as Outcome.Success).value.project.allNotes().single().articulations.filterIsInstance<Articulation.Bend>().single()
        assertTrue("curve ${bend.points}", bend.points.all { it.cents >= 0 })
    }
    @Test fun musicXmlBendSequenceKeepsItsShape() {
        val r = mx(xml("""<note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><type>whole</type><notations><technical>
<bend><bend-alter>1</bend-alter></bend><bend><bend-alter>-1</bend-alter><release/></bend><bend><bend-alter>2</bend-alter></bend></technical></notations></note>"""))
        val pts = (r as Outcome.Success).value.project.allNotes().single().articulations.filterIsInstance<Articulation.Bend>().single().points
        // three bends -> the 100-cent peak, the release to 0 and the final 200 each need their own time position
        val positionsOfTargets = listOf(100, 0, 200).map { c -> pts.indexOfLast { it.cents == c } }.map { pts[it].position }
        assertEquals("points $pts", 3, positionsOfTargets.distinct().size)
    }

    // F8: raw GPIF XML is detected as gp7, but the gp7 importer only accepts ZIP ("Keine ZIP-Datei").
    @Test fun rawGpifDetectionMatchesAnImporter() {
        val gpif = exportedGpif()["Content/score.gpif"]!!
        val det = catalog.detect(gpif, "score.gpif")
        if (det is Outcome.Success) {
            val r = runBlocking { importers[det.value.id]!!.import(gpif, Ids("g")) }
            assertTrue("detected ${det.value.id}, import $r", r is Outcome.Success)
        }
    }
}
