package dev.riffworkbench.adapter.formats.qa12

import dev.riffworkbench.adapter.formats.gpif.Gp7Exporter
import dev.riffworkbench.adapter.formats.gpif.GpifImporter
import dev.riffworkbench.adapter.formats.midi.ScoreKit
import dev.riffworkbench.model.*
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * QA cycle 12 (review of 3bfea04): XmlReader.MAX_NODES dropped from 4,000,000 elements to 1,000,000 elements+attributes.
 * The budget is shared by GPIF (GP7/8, GPX) and MusicXML. A large but legitimate score that the app itself writes as GP7
 * (8 guitar tracks x 450 bars of sixteenths = 57,600 notes, ~18.5 XML nodes per note, 0.8 MB .gp) is now rejected on
 * re-import with "XML enthält zu viele Knoten". Before 3bfea04 (4,000,000 elements, attributes not counted) it imported.
 * Real Guitar Pro files write more properties per note than this exporter, so they hit the cap at fewer notes. The format
 * limits meant to bound such files (GpifBars.MAX_NOTES = 1,000,000; ImportLimits.MAX_NOTES = 1,000,000; GP3-5
 * MAX_TOTAL_NOTES = 400,000) are now unreachable for GPIF/MusicXML.
 */
class Qa12XmlBudgetTest {
    private fun song(k: ScoreKit, tracks: Int, bars: Int): Project {
        val measures = List(bars) { k.measure() }
        val ts = List(tracks) { t ->
            k.track("Gtr ${t + 1}", k.dropA, measures, measures.indices.associateWith { mapOf(0 to List(16) { i -> k.beat(k.f(i.toLong(), 16), k.d(16), k.note(k.dropA, (i % 6) + 1, (i * 3 + t) % 12)) }) })
        }
        return k.project(measures, ts, listOf(TempoChange(ScorePosition(measures[0].id), 140.0)))
    }

    /** Nodes as XmlReader counts them: elements plus attributes. */
    private fun nodes(xml: String): Int {
        var n = 0; var i = 0
        while (true) {
            i = xml.indexOf('<', i); if (i < 0) break
            val c = xml.getOrNull(i + 1)
            if (c != '/' && c != '?' && c != '!') {
                n++
                val end = xml.indexOf('>', i)
                var q = i; while (true) { q = xml.indexOf("=\"", q); if (q < 0 || q > end) break; n++; q += 2 }
            }
            i++
        }
        return n
    }
    private fun elements(xml: String): Int { var n = 0; var i = 0; while (true) { i = xml.indexOf('<', i); if (i < 0) break; val c = xml.getOrNull(i + 1); if (c != '/' && c != '?' && c != '!') n++; i++ }; return n }

    private fun gpif(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; if (e.name.endsWith(".gpif")) return z.readBytes().toString(Charsets.UTF_8) } }
        error("no gpif")
    }

    @Test fun ownGp7ExportOfALargeLegitimateScoreReimports() {
        val k = ScoreKit("q")
        val p = song(k, tracks = 8, bars = 450)
        val exported = runBlocking { Gp7Exporter().export(ExportRequest(p, "gp7")) }
        assertTrue(exported.toString(), exported is Outcome.Success)
        val bytes = (exported as Outcome.Success).value.bytes
        val xml = gpif(bytes)
        println("GPIF: ${xml.length} chars, ${nodes(xml)} nodes, ${elements(xml)} elements for 57600 notes")
        val back = runBlocking { GpifImporter().import(bytes, ScoreKit("r").ids) }
        assertTrue("own GP7 export (8 tracks x 450 bars, ${bytes.size} bytes) must re-import: $back", back is Outcome.Success)
    }
}
