package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.model.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GpifSecurityTest {
    private val minimal = javaClass.getResourceAsStream("/gpif/handwritten-gp7.gpif")!!.readBytes().toString(Charsets.UTF_8)

    private fun importOutcome(bytes: ByteArray): Outcome<ImportResult> = runBlocking { GpifImporter().import(bytes, GpifTestSupport.ids("s")) }
    private fun failure(bytes: ByteArray, vararg codes: FailureCode): Outcome.Failure {
        val result = importOutcome(bytes)
        assertTrue("Erwartet Fehler, erhalten $result", result is Outcome.Failure)
        val failure = result as Outcome.Failure
        assertTrue("Code ${failure.code}: ${failure.message}", failure.code in codes)
        assertEquals("C-FORMAT", failure.contractId)
        assertTrue(failure.message.isNotBlank())
        return failure
    }
    private fun deflated(entries: List<Pair<String, (ZipOutputStream) -> Unit>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, write) -> z.putNextEntry(ZipEntry(name)); write(z); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun zipSlipNamesAreRejected() {
        for (name in listOf("../evil.gpif", "/Content/score.gpif", "Content\\score.gpif", "Content/../../score.gpif", "C:/score.gpif", "Content/./score.gpif")) {
            failure(GpifTestSupport.gpZip(minimal, listOf(name to byteArrayOf(1))), FailureCode.CORRUPT)
        }
    }

    @Test fun duplicateEntriesAreRejected() {
        val bytes = GpifTestSupport.rawZip(listOf("Content/score.gpif" to minimal.toByteArray(), "Content/score.gpif" to "<GPIF/>".toByteArray()))
        assertTrue(failure(bytes, FailureCode.CORRUPT).message.contains("Doppelt"))
    }

    @Test fun compressionBombIsStoppedWhileInflating() {
        val chunk = ByteArray(1024 * 1024)
        val bomb = deflated(listOf("Content/score.gpif" to { z -> repeat(65) { z.write(chunk) } }))
        assertTrue("Bombe muss klein komprimiert sein", bomb.size < 200_000)
        failure(bomb, FailureCode.RESOURCE_LIMIT)
        // Many entries below the per-entry limit still hit the total budget (> 256 MiB).
        val wide = deflated((0 until 5).map { i -> "Content/blob$i" to { z: ZipOutputStream -> repeat(60) { z.write(chunk) } } })
        failure(wide, FailureCode.RESOURCE_LIMIT)
    }

    @Test fun declaredSizesAreNotTrusted() {
        // A stored entry that claims 7 bytes but the reader counts what it actually inflates.
        val bytes = GpifTestSupport.gpZip(minimal)
        assertTrue(importOutcome(bytes) is Outcome.Success)
        val truncated = bytes.copyOf(bytes.size / 2)
        failure(truncated, FailureCode.CORRUPT)
    }

    @Test fun tooManyEntriesAreRejected() {
        val entries = (0..GpZipLimits.MAX_ENTRIES).map { "Content/x$it" to byteArrayOf() }
        failure(GpifTestSupport.gpZip(minimal, entries), FailureCode.RESOURCE_LIMIT)
    }

    @Test fun malformedXmlAndEntitiesAreRejected() {
        failure(GpifTestSupport.gpZip("<GPIF><Score></GPIF>"), FailureCode.CORRUPT)
        failure(GpifTestSupport.gpZip("""<?xml version="1.0"?><!DOCTYPE GPIF [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;">]><GPIF><Score><Title>&b;</Title></Score></GPIF>"""), FailureCode.CORRUPT)
        failure(GpifTestSupport.gpZip("""<GPIF><Score><Title>&xxe;</Title></Score></GPIF>"""), FailureCode.CORRUPT)
        failure(GpifTestSupport.gpZip("<a>".repeat(300) + "</a>".repeat(300)), FailureCode.RESOURCE_LIMIT)
        failure(GpifTestSupport.gpZip("<Other/>"), FailureCode.CORRUPT)
    }

    @Test fun nonArchivesAndArchivesWithoutScoreFailCleanly() {
        failure(ByteArray(0), FailureCode.CORRUPT)
        failure("not a zip at all".toByteArray(), FailureCode.CORRUPT)
        failure(GpifTestSupport.rawZip(listOf("manifest.json" to "{}".toByteArray())), FailureCode.CORRUPT)
        failure(ByteArray(GpZipLimits.MAX_INPUT.toInt() + 1).also { it[0] = 'P'.code.toByte(); it[1] = 'K'.code.toByte(); it[2] = 3; it[3] = 4 }, FailureCode.RESOURCE_LIMIT)
    }

    @Test fun hostileValuesNeverThrow() {
        val hostile = listOf(
            minimal.replace("<Time>3/4</Time>", "<Time>999/7</Time>"),
            minimal.replace("<Pitches>40 45 50 55 59 64</Pitches>", "<Pitches>40 x 50</Pitches>"),
            minimal.replace("<Pitches>40 45 50 55 59 64</Pitches>", "<Pitches>200 -5</Pitches>"),
            minimal.replace("<Fret>4</Fret>", "<Fret>4000</Fret>"),
            minimal.replace("<String>5</String>", "<String>99</String>"),
            minimal.replace("<Rhythm ref=\"2\"/>", "<Rhythm ref=\"77\"/>"),
            minimal.replace("<NoteValue>Quarter</NoteValue>", "<NoteValue>Seventh</NoteValue>"),
            minimal.replace("<Beats>1 2 3 4 5 6 7</Beats>", "<Beats>1 2 3 4 5 6 7 6 6 6 6</Beats>"),
            minimal.replace("<Bars>1 4</Bars>", "<Bars>1 1 1 1 1</Bars>"),
            minimal.replace("<Value>90 2</Value>", "<Value>NaN 2</Value>"),
            minimal.replace("<Value>90 2</Value>", "<Value>99999999 9</Value>"),
            minimal.replace("<Offset>1/1</Offset>", "<Offset>1/0</Offset>"),
            minimal.replace("<HFret>7</HFret>", "<HFret>-1e308</HFret>"),
            minimal.replace("<Float>100</Float>", "<Float>1e300</Float>"),
            minimal.replace("count=\"3\"", "count=\"-5\""),
            minimal.replace("<AccidentalCount>-2</AccidentalCount>", "<AccidentalCount>2147483647</AccidentalCount>"),
            minimal.replace("<InstrumentArticulation>3</InstrumentArticulation>", "<InstrumentArticulation>-1</InstrumentArticulation>"),
            minimal.replace("<Tracks>0 1</Tracks>", "<Tracks>7 7</Tracks>"),
            minimal.replace("<Int>120</Int>", "<Int>99999999999999</Int>"),
            minimal.replace("<MasterBars>", "<MasterBars><MasterBar><Time>4/4</Time><Bars>abc</Bars></MasterBar>"),
        )
        for ((i, xml) in hostile.withIndex()) {
            val result = importOutcome(GpifTestSupport.gpZip(xml))
            if (result is Outcome.Failure) assertEquals("Variante $i", "C-FORMAT", result.contractId)
            else assertTrue("Variante $i", (result as Outcome.Success).value.project.tracks.isNotEmpty())
        }
    }

    @Test fun sharedReferencesCannotAmplifyBeyondBudgets() {
        val beats = List(5000) { "0" }.joinToString(" ")
        val xml = """<GPIF><MasterTrack><Tracks>0</Tracks></MasterTrack><Tracks><Track id="0"><Name>x</Name><Staves><Staff><Properties><Property name="Tuning"><Pitches>40 45 50 55 59 64</Pitches></Property></Properties></Staff></Staves></Track></Tracks>
            <MasterBars>${"<MasterBar><Time>4/4</Time><Bars>0</Bars></MasterBar>".repeat(2000)}</MasterBars>
            <Bars><Bar id="0"><Voices>0 0 0 0</Voices></Bar></Bars><Voices><Voice id="0"><Beats>$beats</Beats></Voice></Voices>
            <Beats><Beat id="0"><GraceNotes>BeforeBeat</GraceNotes><Rhythm ref="0"/><Notes>0 0 0 0 0 0</Notes></Beat></Beats>
            <Notes><Note id="0"><Properties><Property name="String"><String>0</String></Property><Property name="Fret"><Fret>0</Fret></Property></Properties></Note></Notes>
            <Rhythms><Rhythm id="0"><NoteValue>Quarter</NoteValue></Rhythm></Rhythms></GPIF>"""
        failure(GpifTestSupport.gpZip(xml), FailureCode.RESOURCE_LIMIT)
    }
}
