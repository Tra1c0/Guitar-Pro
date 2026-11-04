package dev.riffworkbench.adapter.formats.gp

import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Untrusted input: malformed, truncated, oversized or hostile files must end in a typed failure, never an exception. */
class GpRobustnessTest {
    private val importer = GpBinaryImporter("gp5")
    private fun import(bytes: ByteArray): Outcome<ImportResult> {
        var next = 0
        return importer.importBytes(bytes, IdSource { EntityId("r${next++}") })
    }
    private fun failure(bytes: ByteArray): Outcome.Failure = import(bytes).let { assertTrue("$it", it is Outcome.Failure); it as Outcome.Failure }
    private val fixtures by lazy {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fixtures/gp") }.first { it.isDirectory }
        listOf("drop-a-band.gp3", "drop-a-band.gp4", "drop-a-band.gp5", "drop-a-band-v500.gp5").associateWith { File(dir, it).readBytes() }
    }

    @Test fun minimalFileImportsAndValidates() {
        val result = import(minimalGp3()) as Outcome.Success
        val project = result.value.project
        assertTrue(ScoreValidator.validate(project).none { it.severity == Severity.ERROR })
        assertEquals(listOf(0, 1, 2, 3), project.tracks.single().staves.single().bars.single().voices.map { it.index })
        assertEquals(4, project.tracks.single().staves.single().bars.single().voices[0].beats.size)
    }

    @Test fun everyTruncationFailsAsCorrupt() {
        for ((name, bytes) in fixtures) for (size in 0 until bytes.size) {
            when (val result = import(bytes.copyOf(size))) {
                is Outcome.Failure -> {
                    assertEquals("$name[$size] ${result.message}", FailureCode.CORRUPT, result.code)
                    assertEquals("C-FORMAT", result.contractId)
                }
                // PyGuitarPro appends 4 padding bytes to GP3 files; GP5 tolerates a missing final line-break byte.
                is Outcome.Success -> assertTrue("$name accepted at $size of ${bytes.size}", size >= bytes.size - 4)
            }
        }
    }

    @Test fun randomCorruptionNeverThrowsAndNeverYieldsInvalidProjects() {
        val random = Random(20261104)
        for ((name, bytes) in fixtures) repeat(1500) { round ->
            val mutated = bytes.copyOf()
            repeat(1 + random.nextInt(6)) { mutated[GpVersion.HEADER_SIZE + random.nextInt(mutated.size - GpVersion.HEADER_SIZE)] = random.nextInt(256).toByte() }
            when (val result = import(mutated)) {
                is Outcome.Failure -> assertTrue("$name/$round ${result.code}", result.code == FailureCode.CORRUPT || result.code == FailureCode.RESOURCE_LIMIT)
                is Outcome.Success -> assertTrue("$name/$round", ScoreValidator.validate(result.value.project).none { it.severity == Severity.ERROR })
            }
        }
    }

    @Test fun absurdCountsAreRejected() {
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(declaredMeasures = Int.MAX_VALUE)).code)
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(declaredMeasures = 10_001)).code)
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(declaredMeasures = -1)).code)
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(declaredMeasures = 0)).code)
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(declaredTracks = 65)).code)
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(declaredBeats = 4097)).code)
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(declaredBeats = Int.MIN_VALUE)).code)
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(stringCount = 0)).code)
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(stringCount = 8)).code)
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(stringCount = 11)).code)
        assertTrue(failure(minimalGp3(titleSize = 1 shl 30)).message.contains("Textlänge"))
        assertTrue(failure(minimalGp3(titleSize = Int.MIN_VALUE)).message.contains("Textlänge"))
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(numerator = 0)).code)
        assertEquals(FailureCode.CORRUPT, failure(minimalGp3(denominator = 3)).code)
    }

    @Test fun totalBeatBudgetIsEnforced() {
        val result = failure(minimalGp3(measureCount = 49, beatCount = 4096, withNotes = false))
        assertEquals(FailureCode.RESOURCE_LIMIT, result.code)
    }

    @Test fun oversizedInputIsRejectedBeforeParsing() {
        val bytes = ByteArray(64 * 1024 * 1024 + 1)
        minimalGp3().copyInto(bytes)
        assertEquals(FailureCode.RESOURCE_LIMIT, failure(bytes).code)
    }

    @Test fun headersAreSniffedAndUnsupportedVersionsNamed() {
        assertEquals(listOf("gp3", "gp4", "gp5", "gp5"), fixtures.values.map { GpBinaryDetector.version(it) })
        assertEquals("gp5", GpBinaryDetector.version(fixtures.getValue("drop-a-band.gp5").copyOf(31)))
        assertNull(GpBinaryDetector.version(ByteArray(0)))
        assertNull(GpBinaryDetector.version("PK\u0003\u0004".toByteArray()))
        for (header in listOf("FICHIER GUITAR PRO v2.21", "FICHIER GUITAR PRO v1.04", "CLIPBOARD GP 5.0")) {
            val bytes = header(header) + ByteArray(64)
            assertNull(GpBinaryDetector.version(bytes))
            val result = failure(bytes)
            assertEquals(FailureCode.INCOMPATIBLE_VERSION, result.code)
            assertTrue(result.message.contains(header))
        }
        assertEquals(FailureCode.CORRUPT, failure(ByteArray(0)).code)
        assertEquals(FailureCode.CORRUPT, failure("not a tab".toByteArray()).code)
        assertEquals(FailureCode.CORRUPT, failure(ByteArray(200) { 0x7F }).code)
    }

    @Test fun shortTracksBecomeOtherInstruments() {
        val project = (import(minimalGp3(stringCount = 2)) as Outcome.Success).value.project
        val instrument = project.tracks.single().instrument
        assertEquals(InstrumentKind.OTHER, instrument.kind)
        assertEquals(listOf(59, 64), instrument.tuning)
    }

    @Test fun overfullBarsAreCutAndReported() {
        val result = (import(minimalGp3(beatCount = 6)) as Outcome.Success).value
        val bar = result.project.tracks.single().staves.single().bars.single()
        assertEquals(4, bar.voices[0].beats.size)
        assertEquals(bar.id, result.losses.single { it.featureId == "gp.overfull_bar" }.entityId)
    }

    @Test fun importIsDeterministicAndReportsProgress() = runBlocking {
        val bytes = fixtures.getValue("drop-a-band.gp5")
        val stages = mutableListOf<OperationProgress>()
        var a = 0
        var b = 0
        val first = GpBinaryImporter("gp5").import(bytes, IdSource { EntityId("x${a++}") }) { stages += it }
        val second = GpBinaryImporter("gp3").import(bytes, IdSource { EntityId("x${b++}") })
        assertEquals((first as Outcome.Success).value, (second as Outcome.Success).value)
        assertEquals(List(6) { OperationProgress(it.toLong(), 6, "Guitar-Pro-Takte lesen") }, stages)
    }

    @Test fun unknownFormatIdIsAProgrammingError() {
        assertTrue(runCatching { GpBinaryImporter("gp7") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun instrumentHeuristicAndLyricSyllables() {
        assertEquals(InstrumentKind.PERCUSSION, classifyInstrument(true, emptyList(), 0))
        assertEquals(InstrumentKind.OTHER, classifyInstrument(false, listOf(40, 45), 25))
        assertEquals(InstrumentKind.BASS, classifyInstrument(false, listOf(28, 33, 38, 43), 0))
        assertEquals(InstrumentKind.BASS, classifyInstrument(false, listOf(23, 28, 33, 38, 43, 48), 0))
        assertEquals(InstrumentKind.BASS, classifyInstrument(false, listOf(40, 45, 50, 55), 33))
        assertEquals(InstrumentKind.GUITAR, classifyInstrument(false, listOf(33, 40, 45, 50, 55, 59, 64), 0))
        assertEquals(InstrumentKind.GUITAR, classifyInstrument(false, listOf(28, 33, 38, 43), 30))
        assertEquals(listOf("Riff", "ein-", "mal", "Rif-", "fe", "a b", "You"), lyricChunks("Riff  ein-mal [Kommentar] Rif-fe a+b You___"))
    }

    private fun header(text: String) = byteArrayOf(text.length.toByte()) + text.toByteArray(Charsets.ISO_8859_1).copyOf(30)

    /** Hand-built GP3 file: one or more tracks, each bar with [beatCount] quarter notes on string 1 (or rests). */
    private fun minimalGp3(
        measureCount: Int = 1, trackCount: Int = 1, stringCount: Int = 6, beatCount: Int = 4,
        declaredMeasures: Int = measureCount, declaredTracks: Int = trackCount, declaredBeats: Int = beatCount,
        titleSize: Int? = null, numerator: Int = 4, denominator: Int = 4, withNotes: Boolean = true,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v)
        fun i32(v: Int) = repeat(4) { out.write((v shr (8 * it)) and 0xFF) }
        fun text(s: String) { i32(s.length + 1); u8(s.length); out.write(s.toByteArray(Charsets.ISO_8859_1)) }
        out.write(header("FICHIER GUITAR PRO v3.00"))
        if (titleSize != null) { i32(titleSize); u8(0) } else text("Minimal")
        repeat(7) { text("") }
        i32(0) // notice lines
        u8(0) // triplet feel
        i32(120)
        i32(0)
        repeat(64) { i32(25); listOf(13, 8, 0, 0, 0, 0, 0, 0).forEach(::u8) }
        i32(declaredMeasures)
        i32(declaredTracks)
        repeat(measureCount) { if (it == 0) { u8(0x03); u8(numerator); u8(denominator) } else u8(0) }
        repeat(trackCount) { t ->
            u8(0)
            out.write(header("Spur ${t + 1}").copyOf(41))
            i32(stringCount)
            listOf(64, 59, 55, 50, 45, 40, 35).forEach(::i32)
            listOf(1, 2 * t + 1, 2 * t + 2, 24, 0, 0).forEach(::i32)
        }
        repeat(measureCount) {
            repeat(trackCount) {
                i32(declaredBeats)
                repeat(beatCount) { beat ->
                    u8(0); u8(0) // flags, quarter note
                    if (withNotes) { u8(0x40); u8(0x20); u8(1); u8(beat % 5) } else u8(0)
                }
            }
        }
        return out.toByteArray()
    }
}
