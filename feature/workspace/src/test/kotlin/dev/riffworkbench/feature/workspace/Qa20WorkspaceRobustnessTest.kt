package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.adapter.audio.EngineTransport
import dev.riffworkbench.adapter.audio.RealtimeHost
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 20 (Editor-Robustheit), ViewModel level: WorkspaceViewModel with the real EngineTransport and a manually driven
 * RealtimeHost (as Qa18StartPositionViewModelTest), in-memory repository that only accepts the expected revision.
 *
 *  F1–F3: deterministic reproductions of the cycle-20 findings (red before the fix, see QA journal cycle 20).
 *  M:     model-based random sequences of tap / beginRange / extendRange / endRange / range mode / moveMeasure / move /
 *         moveString / note entry / togglePlay / stop / jumpToStart / render / undo / redo / retune / setRetunePolicy /
 *         signature sheet / copy / paste / delete bars, with invariants after every step:
 *         V1 no exception (also none inside viewModelScope), V2 selection valid (rangeEnd exists or null),
 *         V3 after jumpToStart: cursor bar 1 beat 1, transport STOPPED; V4 Play starts at the cursor when STOPPED or when
 *         the cursor was navigated since the pause, otherwise resumes in the paused bar; V5 navigation moves the cursor
 *         as asked (PageUp/Down, string, also in range mode); V6 autosave: once idle, the repository holds the current
 *         revision without an explicit flush; V7 retune policy persisted.
 *         Defaults: 6 sequences × 40 steps (CI). Campaign: -Dqa20vm.n=200 -Dqa20vm.len=60 [-Dqa20vm.seed=…].
 *
 * Verified on the JVM through the lifecycle shim of the QA harness; UNVERIFIED with the real androidx lifecycle (CI).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa20WorkspaceRobustnessTest {
    private var n = 0
    private val ids = IdSource { EntityId("q20vm-${n++}") }
    private class Repository(var stored: Project) : ProjectRepository {
        var saves = 0
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> {
            if (expectedRevision != stored.revision) return Outcome.Failure(FailureCode.CONFLICT, "stale writer")
            require(ScoreValidator.validate(project).none { it.severity == Severity.ERROR })
            stored = project; saves++; return Outcome.Success(Unit)
        }
    }
    private class Snapshots : SnapshotRepository {
        override suspend fun list(projectId: EntityId) = Outcome.Success(emptyList<SnapshotInfo>())
        override suspend fun create(project: Project, name: String) = Outcome.Success(SnapshotInfo("snap", project.id, project.revision, name, 0))
        override suspend fun load(projectId: EntityId, snapshotId: String): Outcome<Project> = unavailable("C-SNAPSHOT")
    }
    private class Settings : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override suspend fun load() = Outcome.Success(Unit)
        override suspend fun update(settings: AppSettings): Outcome<Unit> { this.settings.value = settings; return Outcome.Success(Unit) }
    }
    private val exchange = object : NativeProjectExchange {
        override suspend fun export(project: Project): Outcome<ExportResult> = unavailable("C-NATIVE")
        override suspend fun import(bytes: ByteArray, newProjectId: EntityId): Outcome<Project> = unavailable("C-NATIVE")
    }
    private class Sink : AudioSink {
        var frames = 0L
        override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> = Outcome.Success(SinkFormat(48_000, 256, 0, "fake"))
        override fun write(buffer: FloatArray, frames: Int): Int { this.frames += frames; return frames }
        override fun presentedFrame(): Long = frames
        override fun close() {}
    }
    private fun TestScope.await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) { advanceUntilIdle(); if (System.currentTimeMillis() > deadline) fail("timeout: $what"); Thread.sleep(2) }
    }
    private fun TestScope.settle(vm: WorkspaceViewModel) { await("ViewModel idle") { vm.viewModelScope.coroutineContext.job.children.count { it.isActive } <= 1 }; advanceUntilIdle() }

    private class Fixture(val vm: WorkspaceViewModel, val transport: EngineTransport, val host: RealtimeHost, val repo: Repository, val settings: Settings)
    /** Default project (1×4/4, E standard, band) + [extraBars] bars, an eighth note at the start of every bar. */
    private fun harness(extraBars: Int = 3, block: TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 5)
        val session = EditorSession(ProjectFactory(ids).create("QA20 VM"), ids)
        repeat(extraBars) { session.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        session.state.value.project.measures.forEach { m ->
            session.select(session.state.value.selection.copy(measureId = m.id, offset = Fraction.ZERO, string = 0))
            session.dispatch(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(8)))
        }
        val repo = Repository(session.state.value.project.copy(revision = 0))
        val settings = Settings()
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, settings, transport, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); await("project open") { vm.state.value.editor != null }; block(Fixture(vm, transport, host, repo, settings)) } finally { transport.shutdown(); store.clear(); Dispatchers.resetMain() }
    }
    private fun TestScope.awaitStatus(f: Fixture, s: TransportStatus) = await("transport $s (is ${f.transport.state.value.status})") { f.transport.state.value.status == s }
    private fun editor(f: Fixture) = f.vm.state.value.editor!!
    private fun bars(f: Fixture) = editor(f).project.measures
    private fun cursorBar(f: Fixture): Int = bars(f).indexOfFirst { it.id == editor(f).selection.measureId } + 1
    private fun playheadBar(f: Fixture): Int = bars(f).indexOfFirst { it.id == f.transport.state.value.position?.position?.measureId } + 1

    // ---------------------------------------------------------------- F1–F3: range mode must not swallow explicit navigation
    /** F1: "Zum Anfang" with the range switch on left the cursor where it was (select() only moved the range end). */
    @Test fun f1_jumpToStartMovesTheCursorToBarOneAlsoInRangeMode() = harness { f ->
        f.vm.tap(bars(f)[2].id, Fraction.ZERO, 0); settle(f.vm)
        f.vm.setRangeMode(true); f.vm.tap(bars(f)[3].id, Fraction.ZERO, 0); settle(f.vm)    // range bars 3–4
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        repeat(20) { f.host.renderBlock() }
        f.vm.jumpToStart(); awaitStatus(f, TransportStatus.STOPPED); settle(f.vm)
        assertEquals("jumpToStart: cursor in bar 1 (range mode on)", 1, cursorBar(f))
        assertEquals(Fraction.ZERO, editor(f).selection.offset)
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals("Play after the jump starts at the top", 1, playheadBar(f))
    }

    /** F2: note entry with auto-advance in range mode overwrote the same beat (move(1) did not move the cursor). */
    @Test fun f2_noteEntryAdvancesAlsoInRangeMode() = harness(extraBars = 0) { f ->
        f.vm.select(editor(f).selection.copy(offset = Fraction.of(1, 4), string = 1)); settle(f.vm)
        f.vm.setDuration(8)                                        // entry grid; no beat at 1/4 yet
        f.vm.setRangeMode(true); settle(f.vm)
        listOf(5, 7, 8).forEach { fret -> f.vm.enterFret(fret); settle(f.vm) }
        val beats = editor(f).project.tracks.first().staves.first().bars.first().voices.first().beats
        assertEquals("three frets entered one after another stay three notes", listOf(5, 7, 8), beats.filter { b -> b.notes.any { it.string == 1 } }.map { b -> b.notes.first { it.string == 1 }.fret })
    }

    /** F3: PageDown / string change in range mode: the cursor did not move; while playing PageDown seeked back to the old cursor. */
    @Test fun f3_pageDownAndStringChangeMoveTheCursorAlsoInRangeMode() = harness { f ->
        f.vm.setRangeMode(true); settle(f.vm)
        f.vm.moveString(1); settle(f.vm)
        assertEquals("string up in range mode", 1, editor(f).selection.string)
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        repeat(10) { f.host.renderBlock() }
        f.vm.moveMeasure(1); f.vm.moveMeasure(1); settle(f.vm)
        assertEquals("PageDown twice in range mode", 3, cursorBar(f))
        val deadline = System.currentTimeMillis() + 10_000
        while (playheadBar(f) != 3 && System.currentTimeMillis() < deadline) { advanceUntilIdle(); f.host.renderBlock(); Thread.sleep(2) }
        assertEquals("playhead follows the cursor while playing", 3, playheadBar(f))
    }

    /**
     * F4: undo while PAUSED moved the cursor back (the history restores the selection of the undone edit), but Play resumed
     * at the paused position instead of the visible start marker ("any selection change while PAUSED", Bericht 1 §3).
     */
    @Test fun f4_undoThatMovesTheCursorWhilePausedMakesPlayStartAtTheCursor() = harness { f ->
        f.vm.enterFret(5); settle(f.vm)                            // note in bar 1, auto-advance
        f.vm.tap(bars(f)[2].id, Fraction.ZERO, 0); settle(f.vm)
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals(3, playheadBar(f))
        repeat(20) { f.host.renderBlock() }
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PAUSED); f.host.renderBlock(); settle(f.vm)
        assertEquals(3, playheadBar(f))
        f.vm.undo(); settle(f.vm)                                  // the note goes, the cursor returns to bar 1
        assertEquals(1, cursorBar(f))
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals("Play after an undo that moved the cursor starts at the cursor", 1, playheadBar(f))
    }

    // ---------------------------------------------------------------- M: model-based sequences
    private class Model { var navigatedSincePause = false; var pausedBar = -1; var structuralSincePause = false }

    @Test fun m_randomisedRangeTransportRetuneSequences() {
        val count = System.getProperty("qa20vm.n")?.toInt() ?: 6
        val len = System.getProperty("qa20vm.len")?.toInt() ?: 40
        val base = System.getProperty("qa20vm.seed")?.toLong() ?: 20_100L
        val failures = mutableListOf<String>()
        for (k in 0 until count) {
            val seed = base + k
            val log = mutableListOf<String>()
            try { harness(extraBars = 1 + (seed % 5).toInt()) { f -> sequence(f, Random(seed), len, log) } }
            catch (t: Throwable) { failures += "seed=$seed: $t\n    " + log.takeLast(12).joinToString("\n    "); println("QA20VM FAIL seed=$seed $t"); log.forEach { println("    $it") } }
            shimFailures()?.let { if (it.isNotEmpty()) { failures += "seed=$seed: coroutine failure ${it.first()}"; it.clear() } }
        }
        println("QA20VM ran $count sequences × $len steps from seed $base, ${failures.size} failing")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** The lifecycle shim records exceptions inside viewModelScope; with the real androidx lifecycle runTest reports them. */
    @Suppress("UNCHECKED_CAST")
    private fun shimFailures(): MutableList<Throwable>? = try {
        val c = Class.forName("androidx.lifecycle.ShimFailures"); c.getDeclaredField("list").apply { isAccessible = true }.get(c.getDeclaredField("INSTANCE").get(null)) as MutableList<Throwable>
    } catch (e: ReflectiveOperationException) { null }

    private fun TestScope.sequence(f: Fixture, r: Random, len: Int, log: MutableList<String>) {
        val m = Model()
        fun status() = f.transport.state.value.status
        fun check(cond: Boolean, msg: () -> String) { if (!cond) throw AssertionError("step ${log.size}: ${msg()}") }
        // Start-marker rule (WorkspaceViewModel.navigatedSincePause): while PAUSED, every navigation call counts, and any other
        // step that moves the cursor (undo/redo, an edit that re-places it, a long press on another bar); a range-end change does not.
        fun navigated(before: Selection, always: Boolean = false) { val now = editor(f).selection; if (status() == TransportStatus.PAUSED && (always || now.measureId != before.measureId || now.offset != before.offset)) m.navigatedSincePause = true }
        for (step in 0 until len) {
            val e = editor(f); val sel = e.selection; val ms = e.project.measures
            val ci = ms.indexOfFirst { it.id == sel.measureId }
            val revBefore = e.project.revision
            when (r.nextInt(100)) {
                in 0..11 -> { val bi = r.nextInt(ms.size); val off = Fraction.of(r.nextLong(0, 4), 4).let { o -> if (o < ms[bi].signature.length()) o else Fraction.ZERO }   // a column of the layout lies inside the bar
                    val rangeMode = f.vm.input.value.rangeMode
                    log += "tap(bar ${bi + 1}, $off) rangeMode=$rangeMode"
                    f.vm.tap(ms[bi].id, off, r.nextInt(6)); if (status() == TransportStatus.PLAYING) f.vm.seekToCursor(); navigated(sel, always = !rangeMode) }
                in 12..27 -> {
                    // One long-press drag as ScoreView delivers it: begin on a bar, the finger crosses bars, release (finally → end).
                    val bi = r.nextInt(ms.size); log += "beginRange(bar ${bi + 1})"; f.vm.beginRange(ms[bi].id); navigated(sel)
                    check(cursorBar(f) == bi + 1 && editor(f).selection.rangeEnd == ms[bi].id) { "beginRange: cursor bar ${cursorBar(f)}, range end ${editor(f).selection.rangeEnd}" }
                    val anchor = editor(f).selection
                    var last = bi
                    repeat(r.nextInt(0, 5)) {
                        val to = (last + r.nextInt(-2, 3)).coerceIn(ms.indices)
                        val changed = f.vm.extendRange(ms[to].id); log += "  extendRange(bar ${to + 1}) -> $changed"
                        check(changed == (to != last)) { "extendRange(bar ${to + 1}) after bar ${last + 1} returned $changed (haptic tick only on a bar change)" }
                        val now = editor(f).selection
                        check(now.measureId == anchor.measureId && now.offset == anchor.offset && now.rangeEnd == ms[to].id) { "extendRange: cursor/range end $now, anchor $anchor" }
                        check(now.measureRange(editor(f).project).size == kotlin.math.abs(to - bi) + 1) { "range size ${now.measureRange(editor(f).project).size} for bars ${bi + 1}..${to + 1}" }
                        last = to
                    }
                    log += "  endRange()"; f.vm.endRange()
                    check(editor(f).selection.rangeEnd == ms[last].id) { "the range must stay after release" } }
                in 28..30 -> { val bi = r.nextInt(ms.size); val before = editor(f).selection; log += "strayExtendRange(bar ${bi + 1})"
                    check(!f.vm.extendRange(ms[bi].id) && editor(f).selection == before) { "extendRange without a running gesture changed the selection" } }
                in 31..33 -> { log += "setRangeMode(${!f.vm.input.value.rangeMode})"; f.vm.setRangeMode(!f.vm.input.value.rangeMode) }
                in 34..40 -> { val d = if (r.nextBoolean()) 1 else -1; log += "moveMeasure($d) from bar ${ci + 1}"; f.vm.moveMeasure(d); navigated(sel, always = true)
                    check(cursorBar(f) == (ci + d).coerceIn(ms.indices) + 1 && editor(f).selection.offset == Fraction.ZERO) { "moveMeasure($d) from bar ${ci + 1}: cursor bar ${cursorBar(f)} @${editor(f).selection.offset}" } }
                in 41..45 -> { val d = if (r.nextInt(3) == 0) -1 else 1; log += "move($d) from bar ${ci + 1} @${sel.offset}"; f.vm.move(d); navigated(sel, always = true)
                    check(editor(f).selection.measureId != sel.measureId || editor(f).selection.offset != sel.offset || (d < 0 && ci == 0 && sel.offset == Fraction.ZERO)) { "move($d) did not move the cursor" } }
                in 46..48 -> { val d = if (r.nextBoolean()) 1 else -1; log += "moveString($d) from ${sel.string}"; f.vm.moveString(d); navigated(sel, always = true)
                    val strings = e.project.tracks.first { it.id == sel.trackId }.instrument.tuning.size
                    check(editor(f).selection.string == (sel.string + d).coerceIn(0, maxOf(0, strings - 1))) { "moveString($d): string ${editor(f).selection.string}" } }
                in 49..52 -> { val fret = r.nextInt(0, 13); log += "enterFret($fret)"; f.vm.enterFret(fret); navigated(sel) }
                in 53..61 -> {
                    val wasStatus = status(); val wasNavigated = m.navigatedSincePause; val cursor = cursorBar(f)
                    log += "togglePlay() from $wasStatus, cursor bar $cursor, navigated=$wasNavigated pausedBar=${m.pausedBar}"
                    f.vm.togglePlay(); settle(f.vm)
                    if (wasStatus == TransportStatus.PLAYING) { awaitStatus(f, TransportStatus.PAUSED); f.host.renderBlock(); settle(f.vm); m.pausedBar = playheadBar(f); m.navigatedSincePause = false; m.structuralSincePause = false }
                    else {
                        awaitStatus(f, TransportStatus.PLAYING)
                        val bar = playheadBar(f)
                        if (wasStatus != TransportStatus.PAUSED || wasNavigated) check(bar == cursor) { "Play from $wasStatus (navigated=$wasNavigated) started in bar $bar, cursor bar $cursor" }
                        else if (!m.structuralSincePause && m.pausedBar > 0) check(bar == m.pausedBar) { "resume started in bar $bar instead of the paused bar ${m.pausedBar}" }
                        m.navigatedSincePause = false
                    } }
                in 62..64 -> { log += "stop()"; f.vm.stop(); awaitStatus(f, TransportStatus.STOPPED) }
                in 65..67 -> { log += "jumpToStart() rangeMode=${f.vm.input.value.rangeMode}"; f.vm.jumpToStart(); awaitStatus(f, TransportStatus.STOPPED); settle(f.vm)
                    check(cursorBar(f) == 1 && editor(f).selection.offset == Fraction.ZERO) { "jumpToStart: cursor bar ${cursorBar(f)} @${editor(f).selection.offset}" }
                    m.navigatedSincePause = false }
                in 68..72 -> { val k = r.nextInt(1, 60); log += "render($k)"; repeat(k) { f.host.renderBlock() } }
                in 73..76 -> { log += "undo()"; f.vm.undo(); m.structuralSincePause = true; navigated(sel) }
                in 77..78 -> { log += "redo()"; f.vm.redo(); m.structuralSincePause = true; navigated(sel) }
                in 79..83 -> {
                    val track = e.project.tracks.first { it.id == sel.trackId }
                    val presets = TuningPresets.all.filter { it.kind == track.instrument.kind }
                    if (track.instrument.tuning.isEmpty() || presets.isEmpty()) { log += "retune n/a"; continue }
                    val preset = presets.random(r); val policy = RetuningPolicy.values().random(r); val drop = r.nextInt(3) == 0
                    log += "retune(${preset.name}, $policy, allowDrop=$drop)"
                    f.vm.retune(track.instrument.copy(tuning = preset.tuning, capoByString = List(preset.tuning.size) { 0 }), policy, drop); m.structuralSincePause = true; navigated(sel)
                    val t = editor(f).project.tracks.first { it.id == editor(f).selection.trackId }
                    check(editor(f).selection.string in 0 until maxOf(1, t.instrument.tuning.size)) { "string ${editor(f).selection.string} outside ${t.instrument.tuning.size} strings" } }
                in 84..85 -> { val p = RetuningPolicy.values().random(r); log += "setRetunePolicy($p)"; f.vm.setRetunePolicy(p); settle(f.vm)
                    check(f.settings.settings.value.retuningPolicy == p && f.vm.retunePolicy() == p) { "retune policy not persisted" } }
                in 86..89 -> { val sig = listOf(TimeSignature(3, 4), TimeSignature(4, 4), TimeSignature(7, 8), TimeSignature(2, 4), TimeSignature(5, 4))[r.nextInt(5)]
                    log += "signatureSheet(bar ${ci + 1}, ${sig.numerator}/${sig.denominator})"; f.vm.command(EditorCommand.SetSignature(sel.measureId, sig)); m.structuralSincePause = true; navigated(sel) }
                in 90..92 -> { log += "copyBars()"; f.vm.copyBars(r.nextInt(3) == 0) }
                in 93..95 -> { log += "pasteBars()"; f.vm.pasteBars(); m.structuralSincePause = true; navigated(sel) }
                else -> { log += "deleteBars() range ${sel.measureRange(e.project).map { id -> ms.indexOfFirst { it.id == id } + 1 }}"; if (ms.size > 1) f.vm.deleteBars(); m.structuralSincePause = true; navigated(sel) }
            }
            settle(f.vm)
            // V2 selection valid
            val now = editor(f); val p = now.project
            check(p.tracks.any { it.id == now.selection.trackId } && p.measures.any { it.id == now.selection.measureId }) { "selection points at a missing track/bar" }
            check(now.selection.rangeEnd == null || p.measures.any { it.id == now.selection.rangeEnd }) { "rangeEnd points at a missing bar" }
            check(ScoreValidator.validate(p).none { it.severity == Severity.ERROR }) { "project invalid" }
            // V6 autosave caught up without flush
            if (p.revision != revBefore || f.repo.stored.revision != p.revision) await("autosave of revision ${p.revision} (stored ${f.repo.stored.revision}, status ${f.vm.state.value.saveStatus})") { f.repo.stored.revision == editor(f).project.revision }
            check(f.vm.state.value.saveStatus == SaveStatus.SAVED) { "save status ${f.vm.state.value.saveStatus} after the autosave" }
            check(f.repo.stored == editor(f).project) { "stored project differs from the editor state" }
        }
    }
}
