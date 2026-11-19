package dev.riffworkbench.ports

/**
 * Zyklus 31 „lokales Absturzprotokoll“: what a crash report may say about the app state. Only the route/page and rough
 * sizes of the open project — never titles, names, notes or any other project content. Written by the UI (route, page)
 * and the workspace ViewModel (sizes), read by the crash handler on whatever thread crashes; plain volatile fields so the
 * handler never takes a lock.
 */
object CrashContext {
    /** Navigation route pattern (`library`, `project/{projectId}`, `settings` …) — the pattern, never the project id. */
    @Volatile var route: String = "start"
    /** Workspace page (`drums`, `editor` …) while a project is open, else null. */
    @Volatile var page: String? = null
    /** Bars, tracks and the entry grid of the open project; -1/null while none is open. */
    @Volatile var bars: Int = -1
    @Volatile var tracks: Int = -1
    @Volatile var grid: String? = null

    fun snapshot(): CrashState = CrashState(route, page, bars, tracks, grid)
    fun clearProject() { bars = -1; tracks = -1; grid = null }
}

/** A frozen [CrashContext]. */
data class CrashState(val route: String = "start", val page: String? = null, val bars: Int = -1, val tracks: Int = -1, val grid: String? = null)

/** Build and device facts of a report (no user data). */
data class CrashEnvironment(val versionName: String, val versionCode: Int, val device: String, val android: String)

/** One stored report: [id] is its file name, [title] the exception line, [epochMillis] the time of the crash. */
data class CrashEntry(val id: String, val epochMillis: Long, val title: String, val sizeBytes: Long)

/**
 * Zyklus 31: the crash reports kept on the device (the last [CrashReportFormat.KEEP], each at most
 * [CrashReportFormat.MAX_BYTES]). Nothing leaves the device unless the user shares a report himself.
 */
interface CrashReports {
    /** Newest first. */
    fun list(): List<CrashEntry>
    fun read(id: String): String?
    fun delete(id: String)
    fun clear()
    /** True once after a crash that was not shown yet (the start notice „ArtiFret ist abgestürzt“); resets the flag. */
    fun takeUnseen(): Boolean
}

/**
 * Zyklus 31: text of one crash report — time, app version, device, thread, route/page, project sizes, heap and the full
 * stack trace with causes and suppressed exceptions; cut to [MAX_BYTES] UTF-8 bytes (head and tail of the trace kept).
 * Pure: the recorder passes everything in, so the JVM tests cover the format, the cut and the OOM fallback.
 */
object CrashReportFormat {
    const val MAX_BYTES = 64 * 1024
    const val KEEP = 5
    const val HEADER = "ArtiFret Absturzbericht"

    fun format(epochMillis: Long, utc: String, env: CrashEnvironment, thread: String, error: Throwable, state: CrashState, heapUsedMb: Long, heapMaxMb: Long,
               trace: String = error.stackTraceToString()): String {
        val head = buildString {
            append(HEADER).append('\n')
            append("Zeit: ").append(utc).append(" (").append(epochMillis).append(")\n")
            append("App: ").append(env.versionName).append(" (").append(env.versionCode).append(")\n")
            append("Gerät: ").append(env.device).append(" · Android ").append(env.android).append('\n')
            append("Thread: ").append(thread).append('\n')
            append("Bildschirm: ").append(state.route).append(state.page?.let { " · Seite $it" } ?: "").append('\n')
            append("Projekt: ").append(if (state.bars < 0) "keins geöffnet" else "${state.bars} Takte · ${state.tracks} Spuren · Raster ${state.grid ?: "–"}").append('\n')
            append("Speicher: ").append(heapUsedMb).append(" MB von ").append(heapMaxMb).append(" MB\n")
            append("Ausnahme: ").append(title(error)).append("\n\n")
        }
        return fit(head, trace, MAX_BYTES)
    }

    /** First line of the report list: exception class and message (message cut to 200 characters). */
    fun title(error: Throwable): String {
        val message = error.message?.let { if (it.length > 200) it.take(200) + "…" else it }
        return error.javaClass.name + (message?.let { ": $it" } ?: "")
    }

    /** [head] plus [trace] in at most [maxBytes] UTF-8 bytes: the trace keeps its start and its end around a marker. */
    fun fit(head: String, trace: String, maxBytes: Int): String {
        val full = head + trace
        if (utf8(full) <= maxBytes) return full
        val marker = "\n… [gekürzt: Bericht > ${maxBytes / 1024} KB] …\n"
        val budget = maxBytes - utf8(head) - utf8(marker)
        if (budget <= 0) return cut(head, maxBytes)
        val tail = cut(trace, budget / 4, fromEnd = true)
        val start = cut(trace, budget - utf8(tail))
        return head + start + marker + tail
    }

    /** UTF-8 length without allocating the bytes. */
    fun utf8(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    /** The longest prefix (or, [fromEnd], suffix) of [s] with at most [maxBytes] UTF-8 bytes, never splitting a surrogate pair. */
    fun cut(s: String, maxBytes: Int, fromEnd: Boolean = false): String {
        if (maxBytes <= 0) return ""
        if (utf8(s) <= maxBytes) return s
        var lo = 0; var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (utf8(if (fromEnd) s.substring(s.length - mid) else s.substring(0, mid)) <= maxBytes) lo = mid else hi = mid - 1
        }
        var r = if (fromEnd) s.substring(s.length - lo) else s.substring(0, lo)
        if (!fromEnd && r.isNotEmpty() && r.last().isHighSurrogate()) r = r.dropLast(1)
        if (fromEnd && r.isNotEmpty() && r.first().isLowSurrogate()) r = r.drop(1)
        return r
    }

    /**
     * Zyklus 31: report of a process end the system recorded (Android ApplicationExitInfo, API 30+) — native crashes
     * (Skia/HWUI/GPU), ANRs and low-memory kills never reach the Java handler. [reason] is the system's name
     * („CRASH_NATIVE“, „LOW_MEMORY“ …), [description] its text (cut to 2 000 characters); sizes in kB.
     */
    fun systemExit(epochMillis: Long, utc: String, env: CrashEnvironment, reason: String, description: String?, importance: Int, pssKb: Long, rssKb: Long, status: Int): String = buildString {
        append(HEADER).append(" (vom System gemeldet)\n")
        append("Zeit: ").append(utc).append(" (").append(epochMillis).append(")\n")
        append("App: ").append(env.versionName).append(" (").append(env.versionCode).append(") – Version beim Melden (nächster Start)\n")
        append("Gerät: ").append(env.device).append(" · Android ").append(env.android).append('\n')
        append("Ausnahme: Prozessende ").append(reason).append(description?.takeIf { it.isNotBlank() }?.let { ": " + it.take(200) } ?: "").append('\n')
        append("Wichtigkeit: ").append(importance).append(" · Status: ").append(status).append('\n')
        append("Speicher (PSS/RSS): ").append(pssKb / 1024).append(" MB / ").append(rssKb / 1024).append(" MB\n")
        description?.takeIf { it.length > 200 }?.let { append("\nBeschreibung:\n").append(it.take(2000)).append('\n') }
    }

    /** Minimal report when the full one cannot be built (e.g. memory is exhausted again): class, message, first frames. */
    fun minimal(epochMillis: Long, env: CrashEnvironment, thread: String, error: Throwable): String = buildString {
        append(HEADER).append(" (Notfassung)\n")
        append("Zeit: ").append(epochMillis).append('\n')
        append("App: ").append(env.versionName).append(" (").append(env.versionCode).append(")\n")
        append("Thread: ").append(thread).append('\n')
        append("Ausnahme: ").append(error.javaClass.name).append('\n')
        for (frame in error.stackTrace.take(40)) append("\tat ").append(frame.toString()).append('\n')
    }
}
