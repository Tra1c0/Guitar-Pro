package dev.riffworkbench.data.local

import dev.riffworkbench.ports.CrashContext
import dev.riffworkbench.ports.CrashEntry
import dev.riffworkbench.ports.CrashEnvironment
import dev.riffworkbench.ports.CrashReportFormat
import dev.riffworkbench.ports.CrashReports
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Zyklus 31 „lokales Absturzprotokoll“: crash reports as files `crash-<epoch ms>.txt` in the app's private [dir] — the last
 * [CrashReportFormat.KEEP], each at most [CrashReportFormat.MAX_BYTES]; a marker file `unseen` remembers that the next start
 * should say so once. Never sent anywhere; the diagnostics page lists, shows, shares (user action) and deletes them.
 */
class FileCrashLog(private val dir: File) : CrashReports {
    private val unseen = File(dir, "unseen")

    /** Writes one report synchronously (fsync), then drops the oldest beyond [CrashReportFormat.KEEP]. Returns the id. */
    fun write(epochMillis: Long, text: String): String {
        if (!dir.mkdirs() && !dir.isDirectory) throw java.io.IOException("crash directory")
        var name = "crash-%013d.txt".format(Locale.ROOT, epochMillis)
        var k = 1
        while (File(dir, name).exists() && k < 100) name = "crash-%013d-%d.txt".format(Locale.ROOT, epochMillis, k++)
        val bytes = CrashReportFormat.cut(text, CrashReportFormat.MAX_BYTES).toByteArray(Charsets.UTF_8)
        val temp = File(dir, ".$name.tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        if (!temp.renameTo(File(dir, name))) { temp.delete(); throw java.io.IOException("crash rename") }
        FileOutputStream(unseen).use { it.write(name.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        prune()
        return name
    }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && NAME.matches(f.name) }.orEmpty().sortedByDescending { it.name }

    private fun prune() { files().drop(CrashReportFormat.KEEP).forEach { it.delete() } }

    override fun list(): List<CrashEntry> = files().map { f ->
        val millis = NAME.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val title = runCatching { f.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.take(40).firstOrNull { it.startsWith("Ausnahme: ") }?.removePrefix("Ausnahme: ") } }.getOrNull()
        CrashEntry(f.name, millis, title ?: "?", f.length())
    }

    override fun read(id: String): String? {
        if (!NAME.matches(id)) return null
        val f = File(dir, id)
        if (!f.isFile || f.length() > CrashReportFormat.MAX_BYTES * 2L) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    override fun delete(id: String) { if (NAME.matches(id)) File(dir, id).delete() }

    override fun clear() { files().forEach { it.delete() }; unseen.delete() }

    override fun takeUnseen(): Boolean {
        if (!unseen.exists()) return false
        unseen.delete()
        return files().isNotEmpty()
    }

    /**
     * Zyklus 31: process ends the system recorded (Android ApplicationExitInfo, read by the app at start) that the Java
     * handler cannot see — native crashes, ANRs, low-memory kills in the foreground — become reports, each once. The first
     * run looks back [FIRST_LOOK_BACK_MS] (so a crash of the previous version is reported after the update); a Java crash
     * the handler already wrote (within 10 s) is not repeated. Returns the number of new reports.
     */
    fun recordSystemExits(exits: List<SystemExit>, environment: CrashEnvironment, now: Long): Int {
        val seenFile = File(dir, "exit-seen")
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrNull()
        val since = seen ?: (now - FIRST_LOOK_BACK_MS)
        val written = list().map { it.epochMillis }
        var count = 0
        for (exit in exits.filter { it.timestamp > since && it.relevant }.sortedBy { it.timestamp }.takeLast(CrashReportFormat.KEEP)) {
            if (exit.reason == "CRASH" && written.any { kotlin.math.abs(it - exit.timestamp) < 10_000L }) continue
            write(exit.timestamp, CrashReportFormat.systemExit(exit.timestamp, CrashRecorder.utc(exit.timestamp), environment, exit.reason, exit.description, exit.importance, exit.pssKb, exit.rssKb, exit.status))
            count++
        }
        val newest = maxOf(seen ?: 0L, exits.maxOfOrNull { it.timestamp } ?: 0L, if (seen == null) now else 0L)
        if (!dir.mkdirs() && !dir.isDirectory) return count
        FileOutputStream(seenFile).use { it.write(newest.toString().toByteArray(Charsets.UTF_8)) }
        return count
    }

    companion object {
        val NAME = Regex("crash-(\\d{13})(?:-\\d{1,2})?\\.txt")
        const val FIRST_LOOK_BACK_MS = 14L * 24 * 3600 * 1000
    }
}

/**
 * One process end as the system recorded it ([reason]: the ApplicationExitInfo name without `REASON_`; [importance] of the
 * process at that moment, 100 = foreground, 200 = visible).
 */
data class SystemExit(val timestamp: Long, val reason: String, val description: String?, val importance: Int, val pssKb: Long, val rssKb: Long, val status: Int) {
    /** Crashes, ANRs and start failures always; kills (low memory, signal) only while the app was in the foreground or visible. */
    val relevant: Boolean get() = when (reason) {
        "CRASH", "CRASH_NATIVE", "ANR", "INITIALIZATION_FAILURE" -> true
        "LOW_MEMORY", "SIGNALED", "EXCESSIVE_RESOURCE_USAGE" -> importance in 1..200
        else -> false
    }
}

/**
 * Zyklus 31: the default uncaught-exception handler of the app. Writes the report synchronously into [log] and then hands
 * the crash to the previous handler, so Android ends the process as usual. Nothing in here may throw: every step is
 * guarded, a failing full report falls back to [CrashReportFormat.minimal], a failing write is given up. For an
 * OutOfMemoryError (anywhere in the cause chain) a [RESERVE_BYTES] buffer held since the start is released first, so the
 * report can still be built.
 */
class CrashRecorder(
    private val log: FileCrashLog,
    private val environment: CrashEnvironment,
    private val clock: () -> Long = System::currentTimeMillis,
    private val heap: () -> Pair<Long, Long> = { Runtime.getRuntime().let { r -> (r.totalMemory() - r.freeMemory()) / MB to r.maxMemory() / MB } },
) {
    @Volatile private var reserve: ByteArray? = ByteArray(RESERVE_BYTES)

    /** The reserve is still held (tests). */
    val reserved: Boolean get() = reserve != null

    /** Writes the report of [error] on [thread]; true when a report (full or minimal) was stored. Never throws. */
    fun record(thread: Thread, error: Throwable): Boolean {
        try {
            if (outOfMemory(error)) reserve = null
            val now = try { clock() } catch (_: Throwable) { 0L }
            val threadName = try { "${thread.name} (id ${thread.id})" } catch (_: Throwable) { "?" }
            val text = try {
                val (used, max) = try { heap() } catch (_: Throwable) { -1L to -1L }
                CrashReportFormat.format(now, utc(now), environment, threadName, error, CrashContext.snapshot(), used, max)
            } catch (_: Throwable) {
                reserve = null
                try { CrashReportFormat.minimal(now, environment, threadName, error) } catch (_: Throwable) { CrashReportFormat.HEADER + "\n" + error.javaClass.name + "\n" }
            }
            log.write(now, text)
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    /**
     * Installs this recorder as the default handler; the previous handler (Android's, which ends the process) runs after
     * the report. Without one, [whenNoPrevious] ends the process.
     */
    fun install(whenNoPrevious: (Thread, Throwable) -> Unit = { _, _ -> kotlin.system.exitProcess(10) }): Thread.UncaughtExceptionHandler {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, error ->
            record(thread, error)
            if (previous != null) previous.uncaughtException(thread, error) else whenNoPrevious(thread, error)
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
        return handler
    }

    companion object {
        const val RESERVE_BYTES = 512 * 1024
        private const val MB = 1024L * 1024L

        fun outOfMemory(error: Throwable): Boolean {
            var e: Throwable? = error
            var depth = 0
            while (e != null && depth++ < 16) { if (e is OutOfMemoryError) return true; e = e.cause }
            return false
        }

        fun utc(epochMillis: Long): String = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMillis))
        } catch (_: Throwable) { epochMillis.toString() }
    }
}
