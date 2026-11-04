package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** GP6 container code tested only against our own synthetic BCFZ/BCFS writer (no Guitar Pro 6 files available). */
class GpxContainerTest {
    /** GP6-style score.gpif written by us: tuning/capo on the track, GeneralMidi instead of Sounds. */
    private val gp6 = """<?xml version="1.0" encoding="UTF-8"?>
<GPIF><GPRevision>11011</GPRevision>
<Score><Title><![CDATA[GP6-Probe]]></Title><Artist><![CDATA[Test]]></Artist></Score>
<MasterTrack><Tracks>0</Tracks><Automations><Automation><Type>Tempo</Type><Linear>false</Linear><Bar>0</Bar><Position>0</Position><Visible>true</Visible><Value>100 2</Value></Automation></Automations></MasterTrack>
<Tracks><Track id="0"><Name><![CDATA[Gitarre]]></Name><Instrument ref="e-gtr6"/>
<Properties><Property name="Tuning"><Pitches>38 45 50 55 59 64</Pitches></Property><Property name="CapoFret"><Fret>0</Fret></Property></Properties>
<GeneralMidi table="Instrument"><Program>29</Program><Port>0</Port><PrimaryChannel>0</PrimaryChannel><SecondaryChannel>1</SecondaryChannel><ForeOneChannelPerString>false</ForeOneChannelPerString></GeneralMidi></Track></Tracks>
<MasterBars><MasterBar><Key><AccidentalCount>0</AccidentalCount><Mode>Major</Mode></Key><Time>4/4</Time><Bars>0</Bars></MasterBar></MasterBars>
<Bars><Bar id="0"><Clef>G2</Clef><Voices>0 -1 -1 -1</Voices></Bar></Bars>
<Voices><Voice id="0"><Beats>0 1</Beats></Voice></Voices>
<Beats><Beat id="0"><Rhythm ref="0"/><Notes>0 1</Notes></Beat><Beat id="1"><Rhythm ref="0"/><Notes>2</Notes></Beat></Beats>
<Notes><Note id="0"><Properties><Property name="String"><String>0</String></Property><Property name="Fret"><Fret>0</Fret></Property></Properties></Note>
<Note id="1"><Properties><Property name="String"><String>1</String></Property><Property name="Fret"><Fret>2</Fret></Property><Property name="PalmMuted"><Enable/></Property></Properties></Note>
<Note id="2"><Properties><Property name="String"><String>2</String></Property><Property name="Fret"><Fret>2</Fret></Property></Properties></Note></Notes>
<Rhythms><Rhythm id="0"><NoteValue>Half</NoteValue></Rhythm></Rhythms></GPIF>
"""
    private val files = listOf("misc.xml" to "<misc/>".toByteArray(), "score.gpif" to gp6.toByteArray())

    private fun import(bytes: ByteArray) = runBlocking { GpxImporter().import(bytes, GpifTestSupport.ids("x")) }
    private fun failure(bytes: ByteArray, vararg codes: FailureCode) {
        val r = import(bytes)
        assertTrue(r.toString(), r is Outcome.Failure)
        assertTrue((r as Outcome.Failure).toString(), r.code in codes)
        assertEquals("C-FORMAT", r.contractId)
    }

    @Test fun ownEncoderRoundTripsThroughTheDecoder() {
        val random = Random(7)
        val samples = listOf(ByteArray(0), "BCFS".toByteArray(), ByteArray(5000) { 0 }, ByteArray(9000) { (it % 7).toByte() }, ByteArray(3000) { random.nextInt().toByte() }, gp6.toByteArray())
        for (sample in samples) {
            val data = "BCFS".toByteArray() + sample
            assertArrayEquals(data, Bcfz.decompress(GpifTestSupport.bcfz(data)))
        }
        val compressed = GpifTestSupport.bcfz(GpifTestSupport.bcfs(files))
        assertTrue("Testencoder muss Rückverweise nutzen", compressed.size < GpifTestSupport.bcfs(files).size / 4)
    }

    @Test fun compressedGpxImportsAsGp6Score() {
        val result = import(GpifTestSupport.bcfz(GpifTestSupport.bcfs(files)))
        assertTrue(result.toString(), result is Outcome.Success)
        val imported = (result as Outcome.Success).value
        val p = imported.project
        assertTrue(ScoreValidator.validate(p).none { it.severity == Severity.ERROR })
        assertEquals("GP6-Probe", p.metadata.title)
        val track = p.tracks.single()
        assertEquals(InstrumentKind.GUITAR, track.instrument.kind)
        assertEquals(listOf(38, 45, 50, 55, 59, 64), track.instrument.tuning)
        assertEquals(29, track.instrument.midiProgram)
        val beats = track.staves.single().bars.single().voices.first().beats
        assertEquals(listOf(listOf(38, 47), listOf(52)), beats.map { b -> b.notes.map { it.pitch.midiNumber }.sorted() })
        assertTrue(Articulation.Mark(NoteMark.PALM_MUTE) in beats[0].notes.first { it.string == 1 }.articulations)
        assertEquals(listOf(TempoChange(ScorePosition(p.measures.single().id), 100.0)), p.tempo)
        assertTrue(imported.losses.any { it.featureId == "import.gpx" })
    }

    @Test fun uncompressedBcfsIsAccepted() {
        val result = import(GpifTestSupport.bcfs(files))
        assertTrue(result.toString(), result is Outcome.Success)
    }

    @Test fun corruptStreamsFailCleanly() {
        // Back reference before any output.
        val bits = GpifTestSupport.BitWriter().apply { bit(1); bits(4, 4); reversed(3, 4); reversed(3, 4) }
        failure("BCFZ".toByteArray() + GpifTestSupport.le32(100) + bits.finish(), FailureCode.CORRUPT)
        // Truncated stream: BCFS image incomplete, score.gpif missing or partial.
        val good = GpifTestSupport.bcfz(GpifTestSupport.bcfs(files))
        failure(good.copyOf(good.size / 3), FailureCode.CORRUPT)
        failure(good.copyOf(6), FailureCode.CORRUPT)
        // Declared output above the 64 MiB budget.
        failure("BCFZ".toByteArray() + GpifTestSupport.le32(65 * 1024 * 1024) + ByteArray(16), FailureCode.RESOURCE_LIMIT)
        failure("BCFZ".toByteArray() + GpifTestSupport.le32(-1) + ByteArray(16), FailureCode.CORRUPT)
        // Decompresses fine, but the payload is not a BCFS image.
        failure(GpifTestSupport.bcfz("XXXX".toByteArray() + ByteArray(5000)), FailureCode.CORRUPT)
        // Sector index outside the image, missing score and oversized file size.
        val image = GpifTestSupport.bcfs(files)
        val badSector = image.copyOf().also { GpifTestSupport.le32(9999).copyInto(it, 4 + 0x1000 + 0x94) }
        failure(badSector, FailureCode.CORRUPT)
        failure(GpifTestSupport.bcfs(listOf("misc.xml" to byteArrayOf(1))), FailureCode.CORRUPT)
        val bigSize = image.copyOf().also { GpifTestSupport.le32(Int.MAX_VALUE).copyInto(it, 4 + 0x1000 + 0x8C) }
        failure(bigSize, FailureCode.RESOURCE_LIMIT)
        failure("BCFZ".toByteArray(), FailureCode.CORRUPT)
        failure("BXYZ1234".toByteArray(), FailureCode.CORRUPT)
    }

    @Test fun sectorCyclesTerminate() {
        // The second file entry points back to the first data sector; the reader must still terminate.
        val image = GpifTestSupport.bcfs(files)
        val entry = 4 + 3 * 0x1000
        GpifTestSupport.le32(1).copyInto(image, entry + 0x94)
        val r = import(image)
        assertTrue(r.toString(), r is Outcome.Failure || r is Outcome.Success)
    }
}
