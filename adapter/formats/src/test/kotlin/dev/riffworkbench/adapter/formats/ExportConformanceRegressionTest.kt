package dev.riffworkbench.adapter.formats

import dev.riffworkbench.adapter.formats.gpif.Gp7Exporter
import dev.riffworkbench.adapter.formats.midi.MidiExporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlExporter
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Export conformance (QA cycle 9): findings from validating exports with independent tools (MusicXML 4.0 XSD, mido,
 * Python zipfile/ElementTree, alphaTab's GPIF reading rules). Checks here are independent of our own importers.
 */
class ExportConformanceRegressionTest {
    private fun xml(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    private fun Element.all(tag: String): List<Element> = getElementsByTagName(tag).let { l -> List(l.length) { l.item(it) as Element } }
    private fun Element.child(tag: String): Element? = childNodes.let { l -> (0 until l.length).map { l.item(it) }.firstOrNull { it is Element && it.tagName == tag } as Element? }
    private fun musicXml(p: Project) = (MusicXmlExporter().exportNow(p) as Outcome.Success).value
    private fun midi(p: Project) = (MidiExporter().exportNow(p) as Outcome.Success).value
    private fun gp7(p: Project) = (runBlocking { Gp7Exporter().export(ExportRequest(p, "gp7")) } as Outcome.Success).value
    private fun gpif(p: Project): ByteArray {
        ZipInputStream(ByteArrayInputStream(gp7(p).bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; if (e.name == "Content/score.gpif") return z.readBytes() } }
        error("no score.gpif")
    }

    // ---- MusicXML ------------------------------------------------------------------------------------------------

    /** Rest gap 1/32..5/32 is spelled 16th.. + 64th, but divisions (8) only cover 1/32: <duration>0</duration> (XSD: positive-divisions). */
    @Test fun musicXmlGapRestsFitTheDivisionsGrid() {
        val doc = xml(musicXml(ExportHandmade.gap64()).bytes).documentElement
        val divisions = doc.all("divisions").first().textContent.toLong()
        for (n in doc.all("note")) {
            val d = n.child("duration")?.textContent?.toLong() ?: continue
            assertTrue("non-positive <duration> (divisions=$divisions)", d > 0)
        }
        // type + dots must equal the duration
        val types = mapOf("whole" to 1.0, "half" to 0.5, "quarter" to 0.25, "eighth" to 0.125, "16th" to 1.0 / 16, "32nd" to 1.0 / 32, "64th" to 1.0 / 64)
        for (n in doc.all("note")) {
            val t = n.child("type")?.textContent ?: continue
            val dots = n.all("dot").size
            val expect = types.getValue(t) * (2 - Math.pow(0.5, dots.toDouble())) * 4 * divisions
            assertEquals("type $t with $dots dots", expect, n.child("duration")!!.textContent.toDouble(), 1e-9)
        }
    }

    /** U+FFFF (and U+FFFE) pass XmlWriter.escape unchanged: the document is not well-formed XML. */
    @Test fun musicXmlIsWellFormedWithNonCharacters() {
        xml(musicXml(ExportHandmade.nonchars()).bytes)
    }

    /** A note with two grace notes: only the first is written, no LossEntry. */
    @Test fun musicXmlKeepsOrReportsEveryGraceNote() {
        val b = ExportHandmade.B("g2"); val g = ExportHandmade.gtr6; val m = listOf(Measure(b.id()))
        val note = b.note(g, 2, 7, Articulation.Grace(Pitch(g.pitch(2, 4)), fret = 4, string = 2), Articulation.Grace(Pitch(g.pitch(2, 5)), fret = 5, string = 2))
        val p = b.project("TwoGraces", m, listOf(b.track("Gtr", g, m, listOf(listOf(listOf(b.beat(Fraction.ZERO, b.dur(1), note)))))))
        val r = musicXml(p)
        val graces = xml(r.bytes).documentElement.all("grace").size / 2 // standard + TAB staff
        assertTrue("graces written: $graces, losses ${r.losses.map { it.featureId }}", graces == 2 || r.losses.any { "grace" in it.featureId })
    }

    // ---- GP7 -----------------------------------------------------------------------------------------------------

    /** CDATA keeps control characters (title from a GP3–5 file or the editor): score.gpif is not well-formed. */
    @Test fun gp7IsWellFormedWithControlCharacters() {
        xml(gpif(ExportHandmade.ctrlTitle()))
    }

    /** Grace notes on a non-stringed track get only <Midi>; alphaTab (and GP) read pitch from Octave/Tone -> pitch 0. */
    @Test fun gp7GraceOnPianoHasOctaveAndTone() {
        val doc = xml(gpif(ExportHandmade.pianoGrace())).documentElement
        for (n in doc.child("Notes")!!.all("Note")) {
            val names = n.all("Property").map { it.getAttribute("name") }
            assertTrue("Note ${n.getAttribute("id")} has $names", "Octave" in names && "Tone" in names)
        }
    }

    /** capoByString 2,2,0,2,2,2 -> CapoFret 2 + PartialCapoFret 0: a partial capo below the main capo; GP/alphaTab play string 3 two semitones high. */
    @Test fun gp7PartialCapoNeverBelowMainCapo() {
        val doc = xml(gpif(ExportHandmade.capoMajority())).documentElement
        val props = doc.all("Property")
        val capo = props.first { it.getAttribute("name") == "CapoFret" }.textContent.trim().toInt()
        val partial = props.first { it.getAttribute("name") == "PartialCapoFret" }.textContent.trim().toInt()
        assertTrue("CapoFret $capo, PartialCapoFret $partial", partial == 0 && capo == 0 || partial > capo)
    }

    /** Three same-pitch notes starting together on one channel: fixUnisons skips the degenerate middle note and the third overlaps the first. */
    @Test fun midiNeverStartsASoundingKeyAgain() {
        val (_, _, errors) = readSmf(midi(ExportHandmade.unison3()).bytes)
        assertEquals(emptyList<String>(), errors)
    }

    private fun readSmf(bytes: ByteArray): Triple<List<SmfNote>, List<Pair<Long, Long>>, List<String>> {
        var p = 14; val notes = ArrayList<SmfNote>(); val tempo = ArrayList<Pair<Long, Long>>(); val errors = ArrayList<String>()
        fun u32(i: Int) = ((bytes[i].toInt() and 0xFF) shl 24) or ((bytes[i + 1].toInt() and 0xFF) shl 16) or ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
        var track = -1
        while (p < bytes.size) {
            val len = u32(p + 4); var q = p + 8; val end = q + len; track++
            var t = 0L; var status = 0; val on = HashMap<Int, Long>()
            fun vlq(): Long { var v = 0L; while (true) { val c = bytes[q++].toInt() and 0xFF; v = (v shl 7) or (c and 0x7F).toLong(); if (c and 0x80 == 0) return v } }
            while (q < end) {
                t += vlq()
                var b = bytes[q].toInt() and 0xFF
                if (b == 0xFF) { val type = bytes[q + 1].toInt() and 0xFF; q += 2; val l = vlq().toInt(); if (type == 0x51) tempo += t to (((bytes[q].toLong() and 0xFF) shl 16) or ((bytes[q + 1].toLong() and 0xFF) shl 8) or (bytes[q + 2].toLong() and 0xFF)); q += l; continue }
                if (b and 0x80 != 0) { status = b; q++ }
                val d1 = bytes[q].toInt() and 0xFF
                val n = if (status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0) 1 else 2
                val d2 = if (n == 2) bytes[q + 1].toInt() and 0xFF else 0
                q += n
                val key = (status and 0x0F) * 128 + d1
                if (status and 0xF0 == 0x90 && d2 > 0) { if (key in on) errors += "track $track tick $t key $d1: note-on while sounding"; on[key] = t }
                else if (status and 0xF0 == 0x80 || status and 0xF0 == 0x90) { val s = on.remove(key); if (s == null) errors += "track $track tick $t key $d1: note-off without note-on" else notes += SmfNote(track, d1, s, t) }
            }
            p = end
        }
        return Triple(notes, tempo, errors)
    }
    private fun seconds(tick: Long, tempo: List<Pair<Long, Long>>): Double {
        var s = 0.0; var last = 0L; var us = 500_000L
        for ((tt, v) in tempo) { if (tt > tick) break; s += (tt - last) * us / 1e6 / 960; last = tt; us = v }
        return s + (tick - last) * us / 1e6 / 960
    }
    private class SmfNote(val track: Int, val key: Int, val on: Long, val off: Long)
}
