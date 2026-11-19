package dev.riffworkbench.ports

import org.junit.Assert.*
import org.junit.Test

/** Zyklus 31 „lokales Absturzprotokoll“: format of a report — header fields, causes, the 64 KB cut and the fallback. */
class Qa31CrashReportFormatTest {
    private val env = CrashEnvironment("0.151", 1151, "Google Pixel 8", "15 (API 35)")
    private val state = CrashState(route = "project/{projectId}", page = "drums", bars = 1000, tracks = 3, grid = "1/32")

    private fun format(error: Throwable, s: CrashState = state, trace: String = error.stackTraceToString()) =
        CrashReportFormat.format(1_763_550_000_123L, "2025-11-19T11:00:00.123Z", env, "main (id 2)", error, s, 180, 512, trace)

    @Test fun headerHasTimeVersionDeviceThreadScreenSizesAndHeap() {
        val text = format(ArrayIndexOutOfBoundsException("Index 29 out of bounds for length 16"))
        assertTrue(text.startsWith(CrashReportFormat.HEADER + "\n"))
        for (line in listOf("Zeit: 2025-11-19T11:00:00.123Z (1763550000123)", "App: 0.151 (1151)", "Gerät: Google Pixel 8 · Android 15 (API 35)", "Thread: main (id 2)",
            "Bildschirm: project/{projectId} · Seite drums", "Projekt: 1000 Takte · 3 Spuren · Raster 1/32", "Speicher: 180 MB von 512 MB",
            "Ausnahme: java.lang.ArrayIndexOutOfBoundsException: Index 29 out of bounds for length 16")) assertTrue(line, text.lines().contains(line))
        assertTrue("stack trace follows", text.contains("\tat dev.riffworkbench.ports.Qa31CrashReportFormatTest"))
        assertTrue(CrashReportFormat.utf8(text) <= CrashReportFormat.MAX_BYTES)
    }

    @Test fun noProjectOpen() {
        val text = format(IllegalStateException("x"), CrashState(route = "library"))
        assertTrue(text.lines().contains("Bildschirm: library"))
        assertTrue(text.lines().contains("Projekt: keins geöffnet"))
    }

    @Test fun causesAndSuppressedAreInTheTrace() {
        val root = IllegalArgumentException("root cause")
        val error = RuntimeException("outer", IllegalStateException("middle", root)).apply { addSuppressed(java.io.IOException("suppressed one")) }
        val text = format(error)
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: middle"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: root cause"))
        assertTrue(text.contains("Suppressed: java.io.IOException: suppressed one"))
    }

    @Test fun aHugeTraceIsCutTo64KbKeepingStartAndEnd() {
        fun recurse(n: Int): Nothing = if (n == 0) throw StackOverflowError("deep") else recurse(n - 1)
        val error = runCatching { recurse(3000) }.exceptionOrNull()!!
        // Plus a long message with multi-byte characters, so the byte (not char) limit matters.
        val trace = error.stackTraceToString() + "\nCaused by: Ü".repeat(5000) + "\nENDE-DER-SPUR"
        val text = format(error, trace = trace)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        assertTrue("$bytes bytes", bytes <= CrashReportFormat.MAX_BYTES)
        assertTrue("header kept", text.contains("Projekt: 1000 Takte"))
        assertTrue("start of the trace kept", text.contains("java.lang.StackOverflowError: deep"))
        assertTrue("end of the trace kept", text.endsWith("ENDE-DER-SPUR"))
        assertTrue("cut marked", text.contains("[gekürzt: Bericht > 64 KB]"))
    }

    @Test fun cutNeverSplitsASurrogatePair() {
        val s = "a" + "🥁".repeat(10) // 🥁
        for (max in 1..20) {
            val head = CrashReportFormat.cut(s, max)
            val tail = CrashReportFormat.cut(s, max, fromEnd = true)
            assertTrue(CrashReportFormat.utf8(head) <= max && CrashReportFormat.utf8(tail) <= max)
            assertFalse(head.isNotEmpty() && head.last().isHighSurrogate())
            assertFalse(tail.isNotEmpty() && tail.first().isLowSurrogate())
            assertEquals(head.toByteArray(Charsets.UTF_8).size, CrashReportFormat.utf8(head))
        }
    }

    @Test fun titleCutsLongMessages() {
        assertEquals("java.lang.IllegalStateException", CrashReportFormat.title(IllegalStateException()))
        val t = CrashReportFormat.title(IllegalStateException("x".repeat(500)))
        assertTrue(t.length < 260 && t.endsWith("…"))
    }

    @Test fun minimalReportHasClassAndFrames() {
        val text = CrashReportFormat.minimal(5L, env, "main (id 2)", OutOfMemoryError("Failed to allocate"))
        assertTrue(text.contains("Notfassung"))
        assertTrue(text.contains("Ausnahme: java.lang.OutOfMemoryError"))
        assertTrue(text.contains("\tat "))
        // The minimal report carries no message text (it could be large) and no project sizes.
        assertFalse(text.contains("Takte"))
    }

    @Test fun contextSnapshotAndClear() {
        CrashContext.route = "project/{projectId}"; CrashContext.page = "drums"; CrashContext.bars = 12; CrashContext.tracks = 3; CrashContext.grid = "1/16"
        assertEquals(CrashState("project/{projectId}", "drums", 12, 3, "1/16"), CrashContext.snapshot())
        CrashContext.clearProject()
        assertEquals(-1, CrashContext.snapshot().bars)
        assertNull(CrashContext.snapshot().grid)
        CrashContext.route = "start"; CrashContext.page = null
    }
}
