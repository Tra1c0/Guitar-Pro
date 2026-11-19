package dev.riffworkbench.data.local

import dev.riffworkbench.ports.CrashContext
import dev.riffworkbench.ports.CrashEnvironment
import dev.riffworkbench.ports.CrashReportFormat
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 31 „lokales Absturzprotokoll“: file rotation (last 5, ≤ 64 KB each), the unseen marker, id validation, the
 * recorder (report content, OOM path with the released reserve, fallback when the full report fails, a failing write never
 * throws) and the installed handler (previous handler runs after the report).
 */
class Qa31CrashLogTest {
    private val dir: File = Files.createTempDirectory("qa31-crash").toFile()
    private val env = CrashEnvironment("0.151", 1151, "Test Device", "15 (API 35)")
    @After fun tearDown() { dir.deleteRecursively(); CrashContext.route = "start"; CrashContext.page = null; CrashContext.clearProject() }

    @Test fun keepsTheLastFiveNewestFirstAndEachAtMost64Kb() {
        val log = FileCrashLog(File(dir, "crashes"))
        for (k in 1..8) log.write(1_000L * k, "ArtiFret Absturzbericht\nAusnahme: E$k\n" + "x".repeat(if (k == 8) 200_000 else 10))
        val entries = log.list()
        assertEquals(5, entries.size)
        assertEquals(listOf(8000L, 7000L, 6000L, 5000L, 4000L), entries.map { it.epochMillis })
        assertEquals("E8", entries[0].title)
        assertTrue("≤ 64 KB: ${entries[0].sizeBytes}", entries[0].sizeBytes <= CrashReportFormat.MAX_BYTES)
        assertTrue(log.read(entries[1].id)!!.contains("Ausnahme: E7"))
        // Same millisecond twice: both kept.
        log.write(8000L, "Ausnahme: again\n")
        assertEquals(5, log.list().size)
        assertEquals(2, log.list().count { it.epochMillis == 8000L })
    }

    @Test fun unseenOnceAfterAWriteAndClearedByDeleteAll() {
        val log = FileCrashLog(File(dir, "crashes"))
        assertFalse(log.takeUnseen())
        log.write(1L, "Ausnahme: A\n")
        assertTrue(log.takeUnseen())
        assertFalse("only once", log.takeUnseen())
        log.write(2L, "Ausnahme: B\n")
        log.clear()
        assertFalse(log.takeUnseen())
        assertTrue(log.list().isEmpty())
    }

    @Test fun idsOutsideThePatternAreNeverRead() {
        val log = FileCrashLog(File(dir, "crashes"))
        File(dir, "secret.txt").writeText("nope")
        val id = log.write(3L, "Ausnahme: C\n")
        assertNotNull(log.read(id))
        for (bad in listOf("../secret.txt", "secret.txt", "crash-0000000000003.txt/../../secret.txt", "")) assertNull(bad, log.read(bad))
        log.delete("../secret.txt")
        assertTrue(File(dir, "secret.txt").exists())
        log.delete(id)
        assertTrue(log.list().isEmpty())
    }

    @Test fun recorderWritesTheFullReportWithContext() {
        val log = FileCrashLog(File(dir, "crashes"))
        CrashContext.route = "project/{projectId}"; CrashContext.page = "drums"; CrashContext.bars = 300; CrashContext.tracks = 3; CrashContext.grid = "1/32"
        val recorder = CrashRecorder(log, env, clock = { 1_763_550_000_000L }, heap = { 120L to 512L })
        assertTrue(recorder.record(Thread.currentThread(), IllegalStateException("boom", ArrayIndexOutOfBoundsException("Index 29 out of bounds for length 16"))))
        val text = log.read(log.list().single().id)!!
        assertTrue(text, text.contains("Zeit: 2025-11-19T11:00:00.000Z"))
        assertTrue(text.contains("App: 0.151 (1151)"))
        assertTrue(text.contains("Bildschirm: project/{projectId} · Seite drums"))
        assertTrue(text.contains("Projekt: 300 Takte · 3 Spuren · Raster 1/32"))
        assertTrue(text.contains("Speicher: 120 MB von 512 MB"))
        assertTrue(text.contains("Caused by: java.lang.ArrayIndexOutOfBoundsException"))
        assertTrue("an ordinary crash keeps the reserve", recorder.reserved)
    }

    @Test fun outOfMemoryReleasesTheReserveFirst() {
        val log = FileCrashLog(File(dir, "crashes"))
        var reserveAtFormat: Boolean? = null
        lateinit var recorder: CrashRecorder
        recorder = CrashRecorder(log, env, clock = { 5L }, heap = { reserveAtFormat = recorder.reserved; 511L to 512L })
        assertTrue(recorder.reserved)
        assertTrue(recorder.record(Thread.currentThread(), RuntimeException("wrapped", OutOfMemoryError("Failed to allocate a 29 MB allocation"))))
        assertEquals("the reserve is released before the report is built", false, reserveAtFormat)
        assertFalse(recorder.reserved)
        assertTrue(log.read(log.list().single().id)!!.contains("OutOfMemoryError: Failed to allocate"))
    }

    @Test fun aFailingFullReportFallsBackToTheMinimalOne() {
        val log = FileCrashLog(File(dir, "crashes"))
        // A throwable whose trace cannot be printed (as when memory runs out again while formatting).
        val hostile = object : RuntimeException("hostile") {
            override fun printStackTrace(s: java.io.PrintWriter) { throw OutOfMemoryError("again") }
            override fun printStackTrace(s: java.io.PrintStream) { throw OutOfMemoryError("again") }
        }
        val recorder = CrashRecorder(log, env, clock = { 6L })
        assertTrue(recorder.record(Thread.currentThread(), hostile))
        val text = log.read(log.list().single().id)!!
        assertTrue(text, text.contains("Notfassung"))
        assertFalse(recorder.reserved)
    }

    @Test fun aFailingWriteNeverThrows() {
        val blocked = File(dir, "blocked").apply { writeText("a file where the directory should be") }
        val recorder = CrashRecorder(FileCrashLog(blocked), env)
        assertFalse(recorder.record(Thread.currentThread(), IllegalStateException("x")))
    }

    @Test fun installedHandlerReportsThenCallsThePreviousOne() {
        val log = FileCrashLog(File(dir, "crashes"))
        val before = Thread.getDefaultUncaughtExceptionHandler()
        val calls = ArrayList<String>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> calls += "previous:${e.message}:${log.list().size}" }
        try {
            CrashRecorder(log, env).install()
            val t = Thread { throw IllegalStateException("on a worker") }
            t.start(); t.join()
            assertEquals("previous handler after the report was stored", listOf("previous:on a worker:1"), calls)
            assertTrue(log.read(log.list().single().id)!!.contains("Thread: Thread-"))
        } finally { Thread.setDefaultUncaughtExceptionHandler(before) }
    }
}
