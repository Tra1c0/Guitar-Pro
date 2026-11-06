package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.adapter.audio.EngineTransport
import dev.riffworkbench.adapter.audio.RealtimeHost
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.ViewportScroll
import dev.riffworkbench.ports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 21 (Bedienung): fast TAB entry as the editor's buttons drive it, on the product's default project (1 × 4/4,
 * E standard, band). WorkspaceViewModel with the real EngineTransport (host never started) and an in-memory repository
 * that only accepts the expected revision, so "stored" really is what autosave wrote.
 *
 *  S   [scenario]: 8 notes over the fret pad with auto-advance, duration and string changes, undo/redo, a chord, palm mute
 *      and natural harmonic, track switch to the bass and back, a pinch zoom. After every step: the repository holds the
 *      editor's revision (autosave), the cursor is where the step puts it, and on a simulated 360-dp phone viewport
 *      (ScoreView's cursor rule, [ViewportScroll.cursor], on the ViewModel's own layout) the cursor stays visible and the
 *      view does not move while the cursor remains inside the old view.
 *  U   one tap = one undo step also when auto-advance adds a bar (red before: the first Undo only removed the new bar).
 *  P   P.M. tile: every tap changes what the tile shows (red before: mode on + unmuted note → the note was muted, the tile stayed lit).
 *  T   track chooser keeps the string where the new track has it and switches also in range mode (red before: string 0;
 *      in range mode the track did not change at all).
 *  F   feedback: inputs that cannot do anything answer with a notice (paste without clipboard, delete the only bar,
 *      technique without note, tie without previous note, third digit, undo without history).
 *
 * Verified on the JVM through the lifecycle shim of the QA harness; UNVERIFIED with the real androidx lifecycle (CI).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa21TabEntryViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("q21vm-${n++}") }
    private class Repository(var stored: Project) : ProjectRepository {
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> {
            if (expectedRevision != stored.revision) return Outcome.Failure(FailureCode.CONFLICT, "stale writer")
            require(ScoreValidator.validate(project).none { it.severity == Severity.ERROR })
            stored = project; return Outcome.Success(Unit)
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

    private class Fixture(val vm: WorkspaceViewModel, val repo: Repository)
    private fun harness(block: TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 5)
        val repo = Repository(ProjectFactory(ids).create("QA21").copy(revision = 0))
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, Settings(), transport, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); await("project open") { vm.state.value.editor != null && vm.layout.value != null }; block(Fixture(vm, repo)) }
        finally { transport.shutdown(); store.clear(); Dispatchers.resetMain() }
    }
    private fun editor(f: Fixture) = f.vm.state.value.editor!!
    private fun guitar(p: Project) = p.tracks.first { it.instrument.kind == InstrumentKind.GUITAR }
    private fun bass(p: Project) = p.tracks.first { it.instrument.kind == InstrumentKind.BASS }
    /** (bar 1-based, offset, string, fret) of every guitar note, in score order. */
    private fun notes(p: Project): List<String> = guitar(p).staves.first().bars.flatMapIndexed { bi, bar ->
        bar.voices.first { it.index == 0 }.beats.sortedBy { it.offset }.flatMap { b -> b.notes.sortedBy { it.string }.map { "${bi + 1}@${b.offset}:s${it.string}f${it.fret}" } }
    }
    private fun cursor(f: Fixture) = editor(f).selection.let { s -> "${editor(f).project.measures.indexOfFirst { it.id == s.measureId } + 1}@${s.offset}:s${s.string}" }

    // ---------------------------------------------------------------- simulated phone viewport (ScoreView cursor effect)
    /**
     * TabCard canvas of a 360×640-dp phone at font scale 1 (EditorScreen: 12-dp card padding, 4-dp canvas padding; height what
     * is left between header, bar map and the control rows, see QA journal cycle 21), in dp with density 1.
     */
    private class Viewport(val width: Float = 328f, val height: Float = 118f) {
        var zoom = 1f; var offsetX = 0f; var offsetY = 0f; var key: ViewportScroll.CursorKey? = null; var pinched = Float.NaN
    }
    private fun cursorAt(f: Fixture, v: Viewport): Pair<Float, Float?>? {
        val l = f.vm.layout.value ?: return null
        val s = editor(f).selection
        val x = l.xFor(s.measureId, s.offset) ?: return null
        val sys = l.measures.firstOrNull { it.measureId == s.measureId }?.let { m -> l.systems.getOrNull(m.system) }
        return x * v.zoom to sys?.stringY?.getOrNull(s.string)?.let { it * v.zoom }
    }
    /** One run of the cursor effect after a step, exactly as ScoreCanvas calls it. */
    private fun scroll(f: Fixture, v: Viewport) {
        val l = f.vm.layout.value ?: return
        val s = editor(f).selection
        val fretted = editor(f).project.tracks.first { it.id == s.trackId }.instrument.tuning.isNotEmpty()
        val r = ViewportScroll.cursor(l, s.measureId, s.offset, s.string.takeIf { fretted }, v.offsetX, v.offsetY, v.width, v.height, v.zoom, 1f, v.key, zoomedByGesture = v.pinched == v.zoom) ?: return
        v.key = r.key; v.offsetX = r.offsetX; v.offsetY = r.offsetY
    }

    private fun TestScope.step(f: Fixture, v: Viewport, log: MutableList<String>, label: String, expectCursor: String? = null, action: () -> Unit) {
        val before = v.offsetX to v.offsetY
        action(); settle(f.vm)
        scroll(f, v)
        val e = editor(f)
        log += "$label → cursor ${cursor(f)}, view (${v.offsetX}, ${v.offsetY}), notes ${notes(e.project).size}"
        val context = "after '$label'\n  " + log.takeLast(6).joinToString("\n  ")
        assertEquals("autosave: repository holds the editor's revision $context", e.project.revision, f.repo.stored.revision)
        assertEquals("autosave: stored notes = editor notes $context", notes(e.project), notes(f.repo.stored))
        expectCursor?.let { assertEquals("cursor $context", it, cursor(f)) }
        val (cx, cy) = cursorAt(f, v) ?: return
        val margin = ViewportScroll.MARGIN
        assertTrue("cursor column visible: x=$cx view ${v.offsetX}..${v.offsetX + v.width} $context", cx >= v.offsetX && cx <= v.offsetX + v.width)
        if (cy != null) assertTrue("cursor row visible: y=$cy view ${v.offsetY}..${v.offsetY + v.height} $context", cy >= v.offsetY && cy <= v.offsetY + v.height)
        // No jump: a cursor that is still inside the old view (with the edge margin) keeps the view where it was.
        val l = f.vm.layout.value!!
        val half = 0.62f * 24f * v.zoom
        val clampedX = ViewportScroll.clamp(before.first, l.width * v.zoom, v.width)
        if (cx - half >= before.first + margin && cx + half <= before.first + v.width - margin)
            assertEquals("view moved although the cursor stayed visible (x) $context", clampedX, v.offsetX, 0.01f)
    }

    @Test fun scenario() = harness { f ->
        val v = Viewport()
        val log = mutableListOf<String>()
        scroll(f, v)
        val p0 = editor(f).project
        val bar1 = p0.measures.first().id
        // Quarter notes on the A string.
        step(f, v, log, "duration quarter", "1@0/1:s0") { f.vm.setDuration(4) }
        step(f, v, log, "string up", "1@0/1:s1") { f.vm.moveString(1) }
        listOf(0, 3, 5, 7).forEachIndexed { i, fret ->
            step(f, v, log, "fret $fret", if (i < 3) "1@${Fraction.of(i + 1L, 4)}:s1" else "2@0/1:s1") { f.vm.enterFret(fret) }
        }
        assertEquals("auto-advance added bar 2", 2, editor(f).project.measures.size)
        // Eighths on the D string in bar 2.
        step(f, v, log, "duration eighth") { f.vm.setDuration(8) }
        assertEquals(WrittenDuration(8), f.vm.input.value.duration)
        step(f, v, log, "string up", "2@0/1:s2") { f.vm.moveString(1) }
        listOf(2, 4, 5, 7).forEachIndexed { i, fret -> step(f, v, log, "fret $fret", "2@${Fraction.of(i + 1L, 8)}:s2") { f.vm.enterFret(fret) } }
        assertEquals("8 notes", listOf("1@0/1:s1f0", "1@1/4:s1f3", "1@1/2:s1f5", "1@3/4:s1f7", "2@0/1:s2f2", "2@1/8:s2f4", "2@1/4:s2f5", "2@3/8:s2f7"), notes(editor(f).project))
        // Undo/redo of the last note: the cursor goes back to it and forward again.
        step(f, v, log, "undo", "2@3/8:s2") { f.vm.undo() }
        assertEquals(7, notes(editor(f).project).size)
        step(f, v, log, "redo", "2@1/2:s2") { f.vm.redo() }
        assertEquals(8, notes(editor(f).project).size)
        // Chord: two strings on the same beat, the cursor stays.
        step(f, v, log, "chord mode on") { f.vm.chordMode(true) }
        step(f, v, log, "chord fret 5 (D)", "2@1/2:s2") { f.vm.enterFret(5) }
        step(f, v, log, "string up", "2@1/2:s3") { f.vm.moveString(1) }
        step(f, v, log, "chord fret 5 (G)", "2@1/2:s3") { f.vm.enterFret(5) }
        step(f, v, log, "chord mode off") { f.vm.chordMode(false) }
        assertTrue("chord on one beat", notes(editor(f).project).containsAll(listOf("2@1/2:s2f5", "2@1/2:s3f5")))
        // Palm mute and natural harmonic on the first note (tap in the score).
        step(f, v, log, "tap note 1", "1@0/1:s1") { f.vm.tap(bar1, Fraction.ZERO, 1) }
        step(f, v, log, "palm mute") { f.vm.togglePalmMute() }
        step(f, v, log, "natural harmonic") { f.vm.articulation(Articulation.Harmonic(HarmonicKind.NATURAL)) }
        val first = guitar(f.repo.stored).staves.first().bars.first().voices.first().beats.first { it.offset == Fraction.ZERO }.notes.first { it.string == 1 }
        assertTrue("stored: palm mute on note 1", Articulation.Mark(NoteMark.PALM_MUTE) in first.articulations)
        assertTrue("stored: natural harmonic on note 1", first.articulations.any { it is Articulation.Harmonic && it.kind == HarmonicKind.NATURAL })
        // Track switch to the bass and back: bar, beat and string stay.
        val bassId = bass(editor(f).project).id; val guitarId = guitar(editor(f).project).id
        step(f, v, log, "track bass", "1@0/1:s1") { f.vm.selectTrack(bassId) }
        assertEquals(bassId, editor(f).selection.trackId)
        step(f, v, log, "track guitar", "1@0/1:s1") { f.vm.selectTrack(guitarId) }
        assertEquals(guitarId, editor(f).selection.trackId)
        // Pinch zoom away from the cursor (ScoreCanvas keeps the content under the fingers): the view stays as the pinch put it.
        step(f, v, log, "move to bar 2 beat 4", "2@3/4:s1") { f.vm.tap(editor(f).project.measures[1].id, Fraction.of(3, 4), 1) }
        val focus = v.width * 0.1f; val old = v.zoom; val next = 1.8f
        v.offsetX = (v.offsetX + focus) * next / old - focus; v.offsetY = (v.offsetY + v.height / 2) * next / old - v.height / 2
        v.pinched = next; v.zoom = next
        f.vm.setZoom(next); settle(f.vm)
        val pinchedView = v.offsetX to v.offsetY
        scroll(f, v)
        assertEquals("the pinch keeps its focus point (x)", pinchedView.first, v.offsetX, 0.01f)
        assertEquals("the pinch keeps its focus point (y)", pinchedView.second, v.offsetY, 0.01f)
        assertEquals("zoom", next, f.vm.view.value.zoom, 0.001f)
        // The next cursor move brings the cursor back into view as usual.
        step(f, v, log, "next beat after the pinch", "2@7/8:s1") { f.vm.move(1) }
        println("QA21VM scenario:\n  " + log.joinToString("\n  "))
    }

    /** U: the fourth quarter of the last bar adds a bar; one Undo takes back the note together with that bar. */
    @Test fun u_undoAfterTheLastNoteOfTheLastBarRemovesTheNoteAndTheAddedBar() = harness { f ->
        f.vm.setDuration(4)
        listOf(1, 2, 3, 4).forEach { f.vm.enterFret(it); settle(f.vm) }
        assertEquals(2, editor(f).project.measures.size)
        assertEquals(4, notes(editor(f).project).size)
        f.vm.undo(); settle(f.vm)
        assertEquals("one undo: the 4th note is gone", 3, notes(editor(f).project).size)
        assertEquals("one undo: the bar auto-advance added is gone", 1, editor(f).project.measures.size)
        assertEquals("cursor back on the undone note", "1@3/4:s0", cursor(f))
        f.vm.redo(); settle(f.vm)
        assertEquals(4, notes(editor(f).project).size)
        assertEquals(2, editor(f).project.measures.size)
        assertEquals("cursor after redo where auto-advance had put it", "2@0/1:s0", cursor(f))
        assertEquals("stored = editor", editor(f).project.revision, f.repo.stored.revision)
        // An unchanged re-entry on the last beat owns no undo step: the bar it adds is a step of its own and must not join
        // (and so take back) the unrelated step before it.
        f.vm.tap(editor(f).project.measures[1].id, Fraction.ZERO, 0); settle(f.vm)
        f.vm.setDuration(1); f.vm.enterFret(9); settle(f.vm)          // whole note fills bar 2, bar 3 added with it
        assertEquals(3, editor(f).project.measures.size)
        f.vm.command(EditorCommand.DeleteMeasures(listOf(editor(f).project.measures[2].id))); settle(f.vm)
        assertEquals(2, editor(f).project.measures.size)
        f.vm.tap(editor(f).project.measures[1].id, Fraction.ZERO, 0); settle(f.vm)
        f.vm.enterFret(9); settle(f.vm)                                // same fret again: no change, but auto-advance adds bar 3
        assertEquals(3, editor(f).project.measures.size)
        f.vm.undo(); settle(f.vm)
        assertEquals("undo removes only the added bar 3", 2, editor(f).project.measures.size)
        assertEquals("the whole note and the deletion of the old bar 3 stay", 5, notes(editor(f).project).size)
    }

    /** P: every tap on P.M. changes what the tile shows (mode on, then an unmuted note selected). */
    @Test fun p_palmMuteTileAlwaysReflectsTheTap() = harness { f ->
        val bar = editor(f).project.measures.first().id
        f.vm.setDuration(4)
        f.vm.enterFret(3); settle(f.vm)                               // note A at 0, unmuted
        f.vm.togglePalmMute(); settle(f.vm)                           // empty beat at 1/4: mode on
        assertTrue(f.vm.input.value.palmMute)
        f.vm.enterFret(5); settle(f.vm)                               // note B at 1/4, muted by the mode
        f.vm.tap(bar, Fraction.ZERO, 0); settle(f.vm)                 // back to note A (unmuted), mode still on
        val history = mutableListOf(f.vm.palmMuteLit(editor(f)))
        repeat(3) { f.vm.togglePalmMute(); settle(f.vm); history += f.vm.palmMuteLit(editor(f)) }
        assertEquals("tile state after each tap on note A (starts unmuted)", listOf(false, true, false, true), history)
        val a = guitar(f.repo.stored).staves.first().bars.first().voices.first().beats.first { it.offset == Fraction.ZERO }.notes.single()
        assertTrue("stored: note A muted after three taps", Articulation.Mark(NoteMark.PALM_MUTE) in a.articulations)
        f.vm.tap(bar, Fraction.of(1, 4), 0); settle(f.vm)
        assertTrue("note B keeps the mute the mode gave it", f.vm.palmMuteLit(editor(f)))
    }

    /** T: the track chooser keeps bar/beat/string where possible and works in range mode. */
    @Test fun t_trackSwitchKeepsTheStringAndWorksInRangeMode() = harness { f ->
        val p = editor(f).project
        val g = guitar(p).id; val b = bass(p).id
        repeat(2) { f.vm.moveString(1) }; settle(f.vm)
        f.vm.selectTrack(b); settle(f.vm)
        assertEquals(b to 2, editor(f).selection.trackId to editor(f).selection.string)
        f.vm.selectTrack(g); settle(f.vm)
        assertEquals(g to 2, editor(f).selection.trackId to editor(f).selection.string)
        repeat(3) { f.vm.moveString(1) }; settle(f.vm)               // high e (5)
        f.vm.selectTrack(b); settle(f.vm)
        assertEquals("high e → the bass's highest string", 3, editor(f).selection.string)
        f.vm.setRangeMode(true); settle(f.vm)
        f.vm.selectTrack(g); settle(f.vm)
        assertEquals("track switch in range mode", g, editor(f).selection.trackId)
    }

    /** F: inputs that cannot do anything give a visible notice instead of silently doing nothing. */
    @Test fun f_inputsWithoutEffectAnswerWithANotice() = harness { f ->
        fun TestScope.expectNotice(label: String, contains: String, action: () -> Unit) {
            f.vm.showNotice(null)
            val rev = editor(f).project.revision
            action(); settle(f.vm)
            val notice = f.vm.state.value.notice
            assertNotNull("$label: no notice", notice)
            assertTrue("$label: notice '$notice' should mention '$contains'", notice!!.contains(contains))
            assertEquals("$label: nothing changed", rev, editor(f).project.revision)
        }
        expectNotice("undo without history", "Keine weitere Änderung") { f.vm.undo() }
        expectNotice("paste without clipboard", "Zwischenablage") { f.vm.pasteBars() }
        expectNotice("delete the only bar", "Mindestens ein Takt") { f.vm.deleteBars() }
        expectNotice("harmonic without note", "Note wählen") { f.vm.articulation(Articulation.Harmonic(HarmonicKind.NATURAL)) }
        expectNotice("let ring without note", "Note wählen") { f.vm.toggleMark(NoteMark.LET_RING) }
        f.vm.enterFret(3); settle(f.vm)
        f.vm.tap(editor(f).project.measures.first().id, Fraction.ZERO, 0); settle(f.vm)
        expectNotice("tie without previous note", "Note") { f.vm.command(EditorCommand.ToggleTie) }
        f.vm.showNotice(null)
        f.vm.digit(1); f.vm.digit(2)
        expectNotice("third digit", "Bund") { f.vm.digit(3) }
    }
}
