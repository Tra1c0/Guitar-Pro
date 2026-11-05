package dev.riffworkbench.adapter.formats.security

import dev.riffworkbench.adapter.formats.musicxml.MusicXmlImporter
import dev.riffworkbench.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** QA cycle 11 (security): hostile inputs that pass every count limit but exhaust the heap. */
class SecurityReviewRegressionTest {
    companion object {
        /** ~16 MiB of `<a/>`: 3.99 M empty elements, below XmlReader.MAX_NODES (4 M). Compresses to a few KiB. */
        fun wideScore(): ByteArray {
            val sb = StringBuilder(16_100_000)
            sb.append("<?xml version=\"1.0\"?><score-partwise version=\"4.0\">")
            repeat(3_990_000) { sb.append("<a/>") }
            sb.append("</score-partwise>")
            return sb.toString().toByteArray()
        }
        /** One element with 5.8 M distinct attributes (~13 MiB .mxl) (attributes are not counted at all). */
        fun wideAttributes(): ByteArray {
            val sb = StringBuilder(64_000_000)
            sb.append("<?xml version=\"1.0\"?><score-partwise")
            for (k in 0 until 5_800_000) sb.append(" a").append(Integer.toString(k, 36)).append("=\"\"")
            sb.append("/>")
            return sb.toString().toByteArray()
        }
        fun mxl(score: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                z.setLevel(9)
                z.putNextEntry(ZipEntry("META-INF/container.xml"))
                z.write("<container><rootfiles><rootfile full-path=\"s.musicxml\" media-type=\"application/vnd.recordare.musicxml+xml\"/></rootfiles></container>".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("s.musicxml")); z.write(score); z.closeEntry()
            }
            return out.toByteArray()
        }
        private var n = 0
        /** Child-JVM entry point: prints the outcome, or the Throwable that escaped the importer. */
        @JvmStatic fun main(args: Array<String>) {
            val bytes = when (args[0]) { "elements" -> mxl(wideScore()); else -> mxl(wideAttributes()) }
            System.out.println("INPUT ${bytes.size}")
            val r = try { MusicXmlImporter().importNow(bytes, IdSource { EntityId("x${n++}") }).let { if (it is Outcome.Failure) "FAILURE ${it.code} ${it.message}" else "SUCCESS" } }
                catch (t: Throwable) { "ESCAPED ${t.javaClass.name} at " + t.stackTrace.take(4).joinToString(" < ") }
            System.out.println("RESULT $r")
        }
    }

    private fun runChild(mode: String, heap: String): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-Xmx$heap", "-cp", System.getProperty("java.class.path"), SecurityReviewRegressionTest::class.java.name, mode)
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText(); p.waitFor()
        return text
    }

    /** A 512 MiB heap (Android largeHeap on typical devices) must survive a ~16 KiB .mxl: reject it, do not crash the app. */
    @Test fun tinyMxlWithMillionsOfEmptyElementsDoesNotExhaustTheHeap() {
        val out = runChild("elements", "512m")
        println(out)
        assertTrue("small input: $out", out.contains("INPUT ") && out.lines().first { it.startsWith("INPUT") }.removePrefix("INPUT ").trim().toInt() < 200_000)
        assertTrue(out, out.contains("RESULT FAILURE"))
    }

    @Test fun mxlWithMillionsOfAttributesDoesNotExhaustTheHeap() {
        val out = runChild("attributes", "512m")
        println(out)
        assertTrue(out, out.contains("RESULT FAILURE"))
    }
}
