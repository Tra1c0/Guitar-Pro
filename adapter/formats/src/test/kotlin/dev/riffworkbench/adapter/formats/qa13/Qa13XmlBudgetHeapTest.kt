package dev.riffworkbench.adapter.formats.qa13

import dev.riffworkbench.adapter.formats.musicxml.MusicXmlImporter
import dev.riffworkbench.adapter.formats.security.SecurityReviewRegressionTest
import dev.riffworkbench.adapter.formats.xml.XmlReader
import dev.riffworkbench.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 13 review of 0e2ddda: XmlReader.MAX_NODES 1 M -> 3 M. Cycle 11 bounded the retained heap of a hostile .mxl with the
 * budget (~150 MiB) so the import is rejected *before* memory runs out. With 3 M, a few-KiB .mxl just below the budget
 * is parsed completely; the question is whether it still fails early or only via a caught OutOfMemoryError.
 */
class Qa13XmlBudgetHeapTest {
    companion object {
        fun justBelow(): ByteArray {
            val sb = StringBuilder(13_000_000)
            sb.append("<?xml version=\"1.0\"?><score-partwise version=\"4.0\">")
            repeat(XmlReader.MAX_NODES - 10) { sb.append("<a/>") }
            sb.append("</score-partwise>")
            return sb.toString().toByteArray()
        }
        private var n = 0
        @JvmStatic fun main(args: Array<String>) {
            val bytes = SecurityReviewRegressionTest.mxl(justBelow())
            System.out.println("INPUT ${bytes.size}")
            val r = try { MusicXmlImporter().importNow(bytes, IdSource { EntityId("x${n++}") }).let { if (it is Outcome.Failure) "FAILURE ${it.code} ${it.message}" else "SUCCESS" } }
                catch (t: Throwable) { "ESCAPED ${t.javaClass.name}" }
            System.out.println("RESULT $r")
        }
    }
    private fun runChild(heap: String): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-Xmx$heap", "-cp", System.getProperty("java.class.path"), Qa13XmlBudgetHeapTest::class.java.name)
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText(); p.waitFor(); return text
    }

    /** Retained heap of the parsed tree at the budget, measured in-process. */
    @Test fun retainedHeapOfATreeJustBelowTheBudget() {
        val bytes = justBelow()
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(100) }; return rt.totalMemory() - rt.freeMemory() }
        val base = used()
        val tree = XmlReader.parse(bytes)
        val after = used()
        println("QA13 tree of ${tree.children.size} elements retains ${(after - base) / (1 shl 20)} MiB (input ${bytes.size / 1024} KiB)")
        assertTrue("retained ${(after - base) shr 20} MiB, cycle 11 bound was ~150 MiB", after - base < 200L shl 20)
    }

    /** Same input as the cycle-11 test but below the new budget: a 512 MiB heap must reject it without running out of memory. */
    @Test fun fewKiBMxlBelowTheNewBudgetIsRejectedWithoutOutOfMemory() {
        val out = runChild("512m"); println(out)
        val result = out.lines().first { it.startsWith("RESULT") }
        assertFalse("must not be decided by a caught OutOfMemoryError: $result", result.contains("Speicher") || result.contains("ESCAPED"))
    }

    /** largeHeap on older / low-RAM devices is 256-384 MiB: the same 12 KiB file now ends in OutOfMemoryError (caught). */
    @Test fun fewKiBMxlBelowTheNewBudgetOn384MiBHeap() {
        val out = runChild("384m"); println(out)
        val result = out.lines().first { it.startsWith("RESULT") }
        assertFalse("must not be decided by a caught OutOfMemoryError: $result", result.contains("Speicher") || result.contains("ESCAPED"))
    }
}
