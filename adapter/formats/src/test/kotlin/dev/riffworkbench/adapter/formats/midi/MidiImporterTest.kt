package dev.riffworkbench.adapter.formats.midi

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MidiImporterTest {
    private fun import(bytes: ByteArray): ImportResult {
        val result = runBlocking { MidiImporter().import(bytes, ScoreKit("i").ids) }
        assertTrue(result.toString(), result is Outcome.Success)
        val value = (result as Outcome.Success).value
        assertValid(value.project)
        return value
    }
    private fun failure(bytes: ByteArray): Outcome.Failure {
        val result = MidiImporter().importNow(bytes, ScoreKit("i").ids)
        assertTrue(result.toString(), result is Outcome.Failure)
        return (result as Outcome.Failure).also { assertEquals("C-FORMAT", it.contractId) }
    }
    private fun beats(track: Track, measure: Int, voice: Int = 0) = track.staves[0].bars[measure].voices[voice].beats
    private fun file(ppq: Int = 960, vararg tracks: SmfTrackWriter.() -> Unit): ByteArray =
        SmfWriter.file(1, ppq, tracks.map { build -> SmfTrackWriter().apply(build).let { it.encode(it.lastTick) } })

    @Test fun roundTripOfOwnExportKeepsMetersTempoSectionsAndNotes() {
        val ref = ReferenceScore()
        val exported = (MidiExporter().exportNow(ref.project) as Outcome.Success).value
        val result = import(exported.bytes)
        val p = result.project
        assertEquals("Testriff", p.metadata.title)
        // Repeats were unfolded on export: six played bars.
        assertEquals(listOf(4 to 4, 4 to 4, 4 to 4, 4 to 4, 7 to 8, 4 to 4), p.measures.map { it.signature.numerator to it.signature.denominator })
        assertTrue(p.measures.all { it.pickupLength == null })
        assertEquals(listOf(Triple(0, Fraction.ZERO, 150.0), Triple(4, Fraction.ZERO, 160.0), Triple(5, Fraction.of(1, 2), 140.0)),
            p.tempo.map { t -> Triple(p.measures.indexOfFirst { it.id == t.position.measureId }, t.position.offset, t.quarterNotesPerMinute) })
        assertEquals(listOf(0 to "Riff A", 2 to "Riff A", 4 to "Bridge"), p.sections.map { s -> p.measures.indexOfFirst { it.id == s.measureId } to s.label })
        assertEquals(listOf("Gitarre · Drop A", "Bass", "Drums"), p.tracks.map { it.name })
        val (guitar, bass, drums) = p.tracks
        assertEquals(InstrumentKind.GUITAR, guitar.instrument.kind)
        assertEquals(listOf(33, 40, 45, 50, 55, 59, 64), guitar.instrument.tuning) // seventh string tuned to the lowest note
        assertEquals(30, guitar.instrument.midiProgram)
        assertEquals(InstrumentKind.BASS, bass.instrument.kind)
        assertEquals(listOf(28, 33, 38, 43), bass.instrument.tuning)
        assertEquals(InstrumentKind.PERCUSSION, drums.instrument.kind)
        assertEquals(-0.49, guitar.mix.pan, 0.011)

        // Palm-muted eighths of bar 1 in both passes, exact positions, velocities and frets.
        for (bar in listOf(0, 2)) {
            val b = beats(guitar, bar)
            assertEquals((0 until 8).map { Fraction.of(it.toLong(), 8) }, b.map { it.offset })
            assertEquals((0 until 8).map { 100 + it }, b.map { it.notes.single().velocity })
            assertTrue(b.all { it.duration == WrittenDuration(8) && it.notes.single().let { n -> n.pitch.midiNumber == 33 && n.string == 0 && n.fret == 0 } })
        }
        // The tie across 7/8 -> 4/4 comes back as two tied notes of the same pitch.
        val last78 = beats(guitar, 4).last()
        assertEquals(Fraction.of(6, 8), last78.offset)
        val target = beats(guitar, 5).first()
        assertEquals(LinkKind.TIE, last78.notes.single().links.single().kind)
        assertEquals(target.notes.single().id, last78.notes.single().links.single().target)
        assertEquals(48, target.notes.single().pitch.midiNumber)
        // Triplet eighths in the second quarter of the last bar share one tuplet group.
        val triplets = beats(guitar, 5).filter { it.offset >= Fraction.of(1, 4) && it.offset < Fraction.of(1, 2) }
        assertEquals(listOf(55, 57, 59), triplets.map { it.notes.single().pitch.midiNumber })
        assertTrue(triplets.all { it.duration == WrittenDuration(8, 0, listOf(TupletRatio(3, 2))) })
        assertEquals(1, triplets.map { it.tupletGroupIds }.distinct().size)
        // The isolated bend survives as a per-note bend curve (+100 cents and back).
        val bent = beats(guitar, 4).single { it.offset == Fraction.of(2, 8) }.notes.single()
        val bend = bent.articulations.filterIsInstance<Articulation.Bend>().single()
        assertEquals(100, bend.points.maxOf { it.cents })
        assertEquals(0, bend.points.last().cents)
        // Fretted pitches agree with the instrument everywhere.
        for (t in listOf(guitar, bass)) for (bar in t.staves[0].bars) for (v in bar.voices) for (beat in v.beats) for (n in beat.notes)
            assertEquals(t.instrument.pitch(n.string!!, n.fret!!), n.pitch.midiNumber)
        // Drums: kick + hi-hat chord on the first eighth with their velocities, no strings.
        val first = beats(drums, 0).first()
        assertEquals(setOf(36 to 110, 42 to 80), first.notes.map { it.pitch.midiNumber to it.velocity }.toSet())
        assertTrue(first.notes.all { it.string == null && it.fret == null })
        assertTrue(result.losses.any { it.featureId == "quantization" })
    }

    @Test fun quantizesToSixteenthOrTripletGridPerBeatAndSplitsVoices() {
        // ppq 96: beat 1 slightly early/late sixteenths, beat 2 triplets, beat 3 a held note against a moving voice.
        val bytes = file(96, {
            meta(0, 0x58, byteArrayOf(4, 2, 24, 8))
        }, {
            channel(0, SmfTrackWriter.CONTROL, 0xC0, 0) // piano
            for ((i, t) in listOf(0L, 25L, 47L, 73L).withIndex()) { channel(t, SmfTrackWriter.NOTE_ON, 0x90, 60 + i, 90); channel(t + 20, SmfTrackWriter.NOTE_OFF, 0x80, 60 + i, 0) }
            for ((i, t) in listOf(96L, 128L, 161L).withIndex()) { channel(t, SmfTrackWriter.NOTE_ON, 0x90, 70 + i, 90); channel(t + 30, SmfTrackWriter.NOTE_OFF, 0x80, 70 + i, 0) }
            channel(192, SmfTrackWriter.NOTE_ON, 0x90, 48, 90); channel(384, SmfTrackWriter.NOTE_OFF, 0x80, 48, 0)
            channel(192, SmfTrackWriter.NOTE_ON, 0x90, 79, 90); channel(240, SmfTrackWriter.NOTE_OFF, 0x80, 79, 0)
            channel(288, SmfTrackWriter.NOTE_ON, 0x90, 81, 90); channel(336, SmfTrackWriter.NOTE_OFF, 0x80, 81, 0)
        })
        val p = import(bytes).project
        val track = p.tracks.single()
        assertEquals(InstrumentKind.PIANO, track.instrument.kind)
        val v0 = beats(track, 0, 0).filter { it.notes.isNotEmpty() }
        assertEquals((0 until 4).map { Fraction.of(it.toLong(), 16) }, v0.take(4).map { it.offset })
        assertTrue(v0.take(4).all { it.duration == WrittenDuration(16) })
        val triplets = v0.filter { it.notes.single().pitch.midiNumber in 70..72 }
        assertEquals(listOf(Fraction.of(1, 4), Fraction.of(1, 3), Fraction.of(5, 12)), triplets.map { it.offset })
        assertTrue(triplets.all { it.duration.tuplets == listOf(TupletRatio(3, 2)) })
        // The held half note and the moving eighths end up in different voices.
        val held = p.tracks.single().staves[0].bars[0].voices.flatMap { v -> v.beats.map { v.index to it } }.single { (_, b) -> b.notes.any { it.pitch.midiNumber == 48 } }
        val moving = p.tracks.single().staves[0].bars[0].voices.flatMap { v -> v.beats.map { v.index to it } }.single { (_, b) -> b.notes.any { it.pitch.midiNumber == 81 } }
        assertNotEquals(held.first, moving.first)
        assertEquals(WrittenDuration(2), held.second.duration)
    }

    @Test fun guitarProgramGetsPlayableFretsAndUnplayableNotesAreDropped() {
        val bytes = file(480, {}, {
            channel(0, SmfTrackWriter.CONTROL, 0xC0, 29) // overdriven guitar
            for (key in listOf(40, 47, 52, 55, 59, 64)) { channel(0, SmfTrackWriter.NOTE_ON, 0x90, key, 100); channel(960, SmfTrackWriter.NOTE_OFF, 0x80, key, 0) }
            channel(960, SmfTrackWriter.NOTE_ON, 0x90, 100, 100); channel(1920, SmfTrackWriter.NOTE_OFF, 0x80, 100, 0) // above fret 24
        })
        val result = import(bytes)
        val track = result.project.tracks.single()
        assertEquals(InstrumentKind.GUITAR, track.instrument.kind)
        assertEquals(DefaultInstruments.GUITAR_STANDARD, track.instrument.tuning)
        val chord = beats(track, 0).first().notes
        assertEquals(6, chord.size)
        assertEquals(6, chord.map { it.string }.distinct().size)
        assertEquals(listOf(0, 2, 2, 0, 0, 0), chord.sortedBy { it.string }.map { it.fret }) // open E minor shape
        assertTrue(result.losses.any { it.featureId == "unplayable" })
        assertTrue(beats(track, 0).none { b -> b.notes.any { it.pitch.midiNumber == 100 } })
    }

    @Test fun typeZeroFileSplitsChannelsAndDrumsGoToPercussion() {
        val t = SmfTrackWriter().apply {
            text(0, 0x03, "Jam")
            meta(0, 0x51, byteArrayOf(0x09, 0x27, 0xC0.toByte())) // 600000 us = 100 BPM
            channel(0, SmfTrackWriter.CONTROL, 0xC1, 33)
            channel(0, SmfTrackWriter.NOTE_ON, 0x91, 28, 100); channel(960, SmfTrackWriter.NOTE_OFF, 0x81, 28, 0)
            channel(0, SmfTrackWriter.NOTE_ON, 0x99, 36, 120); channel(60, SmfTrackWriter.NOTE_OFF, 0x89, 36, 0)
            text(0, 0x06, "Intro")
        }
        val bytes = SmfWriter.file(0, 480, listOf(t.encode(1920)))
        val p = import(bytes).project
        assertEquals("Jam", p.metadata.title)
        assertEquals(listOf(InstrumentKind.BASS, InstrumentKind.PERCUSSION), p.tracks.map { it.instrument.kind })
        assertEquals(100.0, p.tempo.single().quarterNotesPerMinute, 1e-9)
        assertEquals("Intro", p.sections.single().label)
        assertEquals(36, beats(p.tracks[1], 0).first().notes.single().pitch.midiNumber)
    }

    @Test fun runningStatusNoteOnZeroAndSysexAreRead() {
        // Hand-written track: running status, note-on velocity 0 as note-off, a SysEx block.
        val body = byteArrayOf(
            0x00, 0xF0.toByte(), 0x03, 0x7E, 0x7F, 0xF7.toByte(),
            0x00, 0x90.toByte(), 60, 100,
            0x00, 64, 100,
            0x83.toByte(), 0x60, 60, 0,
            0x00, 64, 0,
            0x00, 0xFF.toByte(), 0x2F, 0x00,
        )
        val bytes = header(0, 1, 480) + chunk("MTrk", body)
        val result = import(bytes)
        val notes = beats(result.project.tracks.single(), 0).first().notes
        assertEquals(listOf(60, 64), notes.map { it.pitch.midiNumber })
        assertTrue(result.losses.any { it.featureId == "sysex" })
    }

    @Test fun malformedFilesFailWithTypedErrors() {
        assertEquals(FailureCode.CORRUPT, failure(ByteArray(0)).code)
        assertEquals(FailureCode.CORRUPT, failure("MThd".toByteArray()).code)
        assertEquals(FailureCode.CORRUPT, failure(header(1, 1, 480) + chunk("MTrk", byteArrayOf(0, 0x90.toByte(), 60)).copyOf(14 + 8 + 2)).code)
        // Track length beyond the end of the file.
        assertEquals(FailureCode.CORRUPT, failure(header(1, 1, 480) + "MTrk".toByteArray() + byteArrayOf(0, 0, 0x10, 0)).code)
        // Running status without a preceding status byte.
        assertEquals(FailureCode.CORRUPT, failure(header(1, 1, 480) + chunk("MTrk", byteArrayOf(0, 60, 100))).code)
        // Variable-length quantity longer than four bytes.
        assertEquals(FailureCode.CORRUPT, failure(header(1, 1, 480) + chunk("MTrk", byteArrayOf(0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 0x01, 0x90.toByte(), 60, 100))).code)
        // Meta event longer than the track.
        assertEquals(FailureCode.CORRUPT, failure(header(1, 1, 480) + chunk("MTrk", byteArrayOf(0, 0xFF.toByte(), 0x03, 0x7F, 0x41))).code)
        val type2 = failure(header(2, 1, 480) + chunk("MTrk", byteArrayOf(0, 0xFF.toByte(), 0x2F, 0)))
        assertEquals(FailureCode.INCOMPATIBLE_VERSION, type2.code)
        // A note far in the future would need millions of measures.
        val far = byteArrayOf(0x8F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x90.toByte(), 60, 100, 0x10, 60, 0, 0, 0xFF.toByte(), 0x2F, 0)
        val limit = failure(header(1, 1, 1) + chunk("MTrk", far))
        assertEquals(FailureCode.RESOURCE_LIMIT, limit.code)
        assertTrue(limit.message.contains("Takte"))
        assertEquals(FailureCode.RESOURCE_LIMIT, failure(ByteArray(FormatLimits.MAX_INPUT_BYTES + 1)).code)
    }

    private fun header(format: Int, tracks: Int, division: Int) = byteArrayOf(
        'M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(), 0, 0, 0, 6,
        0, format.toByte(), 0, tracks.toByte(), (division shr 8).toByte(), division.toByte())
    private fun chunk(type: String, body: ByteArray) = type.toByteArray() + byteArrayOf((body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
}
