package dev.riffworkbench.data.local

import dev.riffworkbench.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** QA cycle 11 (security). */
class SecurityReviewRegressionTest {
    companion object {
        /**
         * ~250 KiB .riffproj: the manifest *declares* four 63 MiB assets (with made-up hashes), so the cycle-3 guard
         * ("assets only up to their declared size") lets every one of them inflate and stay in memory before any hash,
         * project or consistency check runs. No project.json is needed at all.
         */
        fun declaredBomb(): ByteArray {
            val size = 63L * 1024 * 1024
            val hashes = List(4) { "%064x".format(it + 1) }
            val manifest = """{"projectHash":"x","assets":[${hashes.joinToString(",") { """{"hash":"$it","name":"a","mediaType":"audio/wav","sizeBytes":$size}""" }}]}"""
            val out = ByteArrayOutputStream()
            val zeros = ByteArray(1024 * 1024)
            ZipOutputStream(out).use { z ->
                z.setLevel(9)
                z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry()
                for (h in hashes) { z.putNextEntry(ZipEntry("assets/$h")); repeat(63) { z.write(zeros) }; z.closeEntry() }
            }
            return out.toByteArray()
        }
        @JvmStatic fun main(args: Array<String>) {
            val bytes = declaredBomb()
            println("INPUT ${bytes.size}")
            val r = try { runBlocking { FileNativeProjectExchange(FileAssetStore(File(args[0]))).import(bytes, EntityId("x")) }.let { if (it is Outcome.Failure) "FAILURE ${it.code}" else "SUCCESS" } }
                catch (t: Throwable) { "ESCAPED ${t.javaClass.name}" }
            println("RESULT $r")
        }
    }

    @Test fun declaredAssetSizesDoNotLetASmallArchiveInflateHundredsOfMiB() {
        val archive = declaredBomb()
        assertTrue("input ${archive.size}", archive.size < 512 * 1024)
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = mx.getThreadAllocatedBytes(Thread.currentThread().id)
        val r = storageOutcome { NativeProjectCodec.decode(archive) }
        val allocated = mx.getThreadAllocatedBytes(Thread.currentThread().id) - before
        assertTrue(r is Outcome.Failure)
        // A forged asset can be rejected once its first entry fails the hash; it must not take the whole 256 MiB budget.
        assertTrue("allocated ${allocated / 1048576} MiB for a ${archive.size / 1024} KiB archive", allocated < 160L * 1024 * 1024)
    }

    /** Same heap bar as the cycle-3 bomb fix (≤ 320 MiB): the import must fail with an Outcome, not with an Error. */
    @Test fun declaredBombIsAFailureNotAnOutOfMemoryErrorOn320MiB() {
        val dir = kotlin.io.path.createTempDirectory("qa11").toFile()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-Xmx320m", "-cp", System.getProperty("java.class.path"), SecurityReviewRegressionTest::class.java.name, dir.path)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(); dir.deleteRecursively()
        println(out)
        assertTrue(out, out.contains("RESULT FAILURE"))
    }
}
