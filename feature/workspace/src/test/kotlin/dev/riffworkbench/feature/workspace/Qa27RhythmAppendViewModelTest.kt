package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.ports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 27 „Rhythmus tippen: Takte anhängen“, ViewModel flow (lifecycle shim): the open target runs from the cursor bar
 * until „Stopp“ and extends its tapping copy at the end of the piece while it plays; Auto-Stopp after two empty bars (and
 * off); a fixed target past the end appends the missing bars; only tapped bars are created, in one undo step with the
 * rhythm; „Verwerfen“ changes nothing (no bars either); repeats ahead end an open recording before them. The transport is
 * the fake of Qa25RhythmTapViewModelTest (identity time map: a tap's „nanos“ is its audible plan frame).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa27RhythmAppendViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("q27vm-${n++}") }
    private val sr = 48_000

    private class Repository(var stored: Project) : ProjectRepository {
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> { stored = project; return Outcome.Success(Unit) }
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

    /** Transport fake: compiles real plans (a prepare while playing keeps the position), the test moves the playhead. */
    private class FakeTransport(val sr: Int) : Transport {
        val mutable = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
        override val state = mutable
        val prepared = ArrayList<Project>()
        var settings = PracticeSettings()
        var plan: PlaybackPlan? = null
        var start = 0L
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> {
            prepared += project; this.settings = settings
            plan = (ScorePlaybackCompiler().compileNow(project, sr, PlaybackOptions(settings.speed)) as Outcome.Success).value
            return Outcome.Success(Unit)
        }
        override suspend fun play(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.PLAYING, sample = SamplePosition(start, sr), countingIn = settings.countInBars > 0); return Outcome.Success(Unit) }
        override suspend fun pause(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.PAUSED); return Outcome.Success(Unit) }
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> { start = plan!!.frameFor(position)!!; mutable.value = mutable.value.copy(sample = SamplePosition(start, sr)); return Outcome.Success(Unit) }
        override suspend fun setLoop(range: LoopRange?) = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.STOPPED, countingIn = false); return Outcome.Success(Unit) }
        override fun audiblePlanFrameAt(nanos: Long): Double = if (mutable.value.status == TransportStatus.STOPPED) Double.NaN else nanos.toDouble()
        override fun installedPlan(): PlaybackPlan? = plan
        fun advance(frame: Long) { mutable.value = mutable.value.copy(sample = SamplePosition(frame, sr), countingIn = false) }
    }

    private class Fixture(val vm: WorkspaceViewModel, val t: FakeTransport)
    private fun harness(project: Project, block: TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val t = FakeTransport(sr)
        val vm = WorkspaceViewModel(project.id, Repository(project), Snapshots(), exchange, Settings(), t, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); assertNotNull(vm.state.value.editor); block(Fixture(vm, t)) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    private fun project(bars: Int = 2) = ProjectFactory(ids).create("Q27", signatures = List(bars) { TimeSignature() }).copy(revision = 0)
    private fun f(a: Long, b: Long) = Fraction.of(a, b)
    private fun editor(x: Fixture) = x.vm.state.value.editor!!
    private fun guitar(p: Project) = p.tracks.first { it.instrument.kind == InstrumentKind.GUITAR }
    private fun noErrors(p: Project) { val errors = ScoreValidator.validate(p).filter { it.severity == Severity.ERROR }; assertTrue(errors.toString(), errors.isEmpty()) }
    /** Plan frame of route bar [bar] (the copy plays in written order) at [o] in the transport's current plan. */
    private fun frame(x: Fixture, bar: Int, o: Fraction) = Math.round(x.t.plan!!.spans[bar].frameAt(o.toDouble()))
    private val ms = sr / 1000L
    private fun TestScope.at(x: Fixture, bar: Int, o: Fraction = Fraction.ZERO) { x.t.advance(frame(x, bar, o)); advanceUntilIdle() }
    /** Quick tap (60 ms) or held until bar/offset [to] (released 20 ms early). */
    private fun TestScope.tap(x: Fixture, bar: Int, o: Fraction, to: Pair<Int, Fraction>? = null) {
        val down = frame(x, bar, o)
        x.vm.rhythmTapDown(down)
        x.vm.rhythmTapUp(to?.let { (b, e) -> frame(x, b, e) - 20 * ms } ?: (down + 60 * ms))
        advanceUntilIdle()
    }
    private fun TestScope.cursorAt(x: Fixture, bar: Int) { x.vm.select(editor(x).selection.copy(measureId = editor(x).project.measures[bar].id, offset = Fraction.ZERO, rangeEnd = null)); advanceUntilIdle() }
    private fun TestScope.begin(x: Fixture) {
        x.vm.startRhythmTap(); advanceUntilIdle()
        assertEquals(RhythmTapPhase.COUNT_IN, x.vm.rhythm.value.phase)
        at(x, editor(x).project.measures.indexOfFirst { it.id == x.vm.rhythm.value.bars.first() }, f(1, 64))
        assertEquals(RhythmTapPhase.RECORDING, x.vm.rhythm.value.phase)
    }

    @Test fun openRecordingExtendsAtTheEndStopsOnStoppAndAppendsOnlyTappedBarsInOneUndoStep() = harness(project()) { x ->
        cursorAt(x, 1)
        val before = editor(x).project
        x.vm.openRhythmTap(); x.vm.setRhythmOpenEnded(true)
        x.vm.setRhythmAutoStop(false)   // bars 5–6 stay empty here (Auto-Stopp: next test)
        assertTrue(x.vm.rhythm.value.openActive)
        assertEquals("Ab Takt 2, offen", x.vm.rhythm.value.label)
        begin(x)
        // The tapping copy: the score plus 4 buffer bars, every track silent, its own revision.
        val copy = x.t.prepared.last()
        assertEquals(before.measures.size + WorkspaceViewModel.OPEN_BUFFER_BARS, copy.measures.size)
        assertEquals(before.measures, copy.measures.take(2))
        assertTrue(copy.tracks.all { it.mix.mute }); assertTrue(copy.revision < 0)
        tap(x, 1, Fraction.ZERO, 1 to f(1, 2))              // bar 2 (existing): held half
        tap(x, 1, f(3, 4), 2 to f(1, 4))                    // held over the old end into bar 3 (buffer)
        at(x, 3, f(1, 2)); tap(x, 3, f(1, 2))               // bar 4 (buffer)
        // Bar 5 is the copy's second-to-last bar: the copy grows by 4 bars at the same position, the plan is reloaded.
        val prepares = x.t.prepared.size
        at(x, 4)
        assertEquals(prepares + 1, x.t.prepared.size)
        val longer = x.t.prepared.last()
        assertEquals(copy.id, longer.id); assertTrue(longer.revision < copy.revision)
        assertEquals(copy.measures, longer.measures.take(copy.measures.size)); assertEquals(copy.measures.size + WorkspaceViewModel.OPEN_EXTEND_BARS, longer.measures.size)
        assertEquals(TransportStatus.PLAYING, x.t.state.value.status)
        assertEquals(RhythmTapPhase.RECORDING, x.vm.rhythm.value.phase)
        // Bar 7 lies in the extension; „Stopp“ in bar 8.
        at(x, 6); tap(x, 6, f(1, 4))
        at(x, 7, f(1, 2)); x.vm.stopRhythmTap(frame(x, 7, f(1, 2))); advanceUntilIdle()
        val r = x.vm.rhythm.value
        assertEquals(RhythmTapPhase.PREVIEW, r.phase)
        assertEquals(TransportStatus.STOPPED, x.t.state.value.status)
        assertEquals(editor(x).project, x.t.prepared.last())   // the transport plays the score again
        assertSame(before, editor(x).project)
        val result = r.result!!
        assertEquals(4, result.onsets)
        // Bars 2–7: bar 8 (stop bar, empty) and the buffer after it are dropped; 5 new bars.
        assertEquals(6, result.bars.size); assertEquals(5, r.newBars); assertEquals(0, r.overwrites)
        assertEquals(listOf(RhythmEvent.Kind.CONTINUATION, RhythmEvent.Kind.REST), result.bars[1].events.map { it.kind })
        x.vm.applyRhythm(RhythmMode.OPEN_STRING); advanceUntilIdle()
        val after = editor(x).project
        assertEquals(RhythmTapPhase.IDLE, x.vm.rhythm.value.phase)
        assertEquals(7, after.measures.size)
        noErrors(after)
        for (t in after.tracks) assertEquals(after.measures.map { it.id }, t.staves[0].bars.map { it.measureId })
        val bars = guitar(after).staves[0].bars
        assertEquals(listOf(0 to 0), bars[1].voices[0].beats.first().notes.map { it.string to it.fret })
        assertTrue(bars[1].voices[0].beats.last().notes.single().links.any { it.kind == LinkKind.TIE })   // into the first new bar
        assertEquals(1, bars[6].voices[0].beats.count { it.notes.isNotEmpty() })
        assertTrue(after.tracks.filter { it.id != guitar(after).id }.all { t -> t.staves[0].bars.drop(2).all { b -> b.voices.all { it.beats.isEmpty() } } })
        // One undo step takes the bars and the rhythm back.
        x.vm.undo(); advanceUntilIdle()
        assertEquals(before.measures, editor(x).project.measures)
        assertEquals(before.tracks, editor(x).project.tracks)
    }

    @Test fun autoStopEndsAfterTwoEmptyBarsAndCanBeSwitchedOff() = harness(project()) { x ->
        x.vm.openRhythmTap(); x.vm.setRhythmOpenEnded(true)
        assertTrue(x.vm.rhythm.value.autoStop)
        begin(x)
        tap(x, 0, Fraction.ZERO); tap(x, 1, f(1, 2)); at(x, 2); tap(x, 2, f(1, 4))   // last touch in bar 3 (buffer)
        at(x, 3); at(x, 4, f(1, 2))
        assertEquals(RhythmTapPhase.RECORDING, x.vm.rhythm.value.phase)       // bar 5 not over yet
        at(x, 5)                                                              // bars 4 and 5 were empty
        assertEquals(RhythmTapPhase.PREVIEW, x.vm.rhythm.value.phase)
        assertEquals(3, x.vm.rhythm.value.result!!.bars.size); assertEquals(1, x.vm.rhythm.value.newBars)
        x.vm.discardRhythm(); advanceUntilIdle()
        // Off: two, three empty bars go by, the copy keeps growing; only „Stopp“ ends it.
        x.vm.openRhythmTap(); x.vm.setRhythmAutoStop(false)
        begin(x)
        tap(x, 0, Fraction.ZERO)
        for (bar in 1..9) at(x, bar)
        assertEquals(RhythmTapPhase.RECORDING, x.vm.rhythm.value.phase)
        assertTrue(x.t.plan!!.spans.size >= 12)
        x.vm.stopRhythmTap(frame(x, 9, f(1, 8))); advanceUntilIdle()
        assertEquals(RhythmTapPhase.PREVIEW, x.vm.rhythm.value.phase)
        assertEquals(1, x.vm.rhythm.value.result!!.bars.size); assertEquals(0, x.vm.rhythm.value.newBars)
    }

    @Test fun fixedTargetPastTheEndAppendsTheMissingBars() = harness(project()) { x ->
        cursorAt(x, 1)
        val before = editor(x).project
        x.vm.openRhythmTap(); x.vm.setRhythmBarCount(4)
        assertEquals("Takte 2–5 (+3 neu)", x.vm.rhythm.value.label)
        assertEquals(3, x.vm.rhythm.value.newBars)
        begin(x)
        assertEquals(5, x.t.prepared.last().measures.size)
        tap(x, 1, Fraction.ZERO); at(x, 3); tap(x, 3, f(1, 2), 4 to f(1, 4))   // bar 4 (new) held into bar 5 (new)
        at(x, 4, f(1, 1))                                                     // after the last target bar: stops by itself
        val r = x.vm.rhythm.value
        assertEquals(RhythmTapPhase.PREVIEW, r.phase)
        assertEquals(4, r.result!!.bars.size); assertEquals(3, r.newBars)
        x.vm.applyRhythm(RhythmMode.GRID); advanceUntilIdle()
        val after = editor(x).project
        assertEquals(5, after.measures.size); noErrors(after)
        x.vm.undo(); advanceUntilIdle()
        assertEquals(before.measures, editor(x).project.measures); assertEquals(before.tracks, editor(x).project.tracks)
        // Tapped only in the first two bars: the empty new bars are not created (the existing target bar stays).
        x.vm.openRhythmTap(); x.vm.setRhythmBarCount(4)
        begin(x)
        tap(x, 1, Fraction.ZERO); at(x, 2); tap(x, 2, f(1, 4))
        at(x, 4, f(1, 1))
        assertEquals(2, x.vm.rhythm.value.result!!.bars.size); assertEquals(1, x.vm.rhythm.value.newBars)
        x.vm.applyRhythm(RhythmMode.OPEN_STRING); advanceUntilIdle()
        assertEquals(3, editor(x).project.measures.size); noErrors(editor(x).project)
    }

    @Test fun discardChangesNothingStopInTheCountInCancelsAndRepeatsStayRefused() = harness(project()) { x ->
        cursorAt(x, 1)
        val before = editor(x).project
        x.vm.openRhythmTap(); x.vm.setRhythmOpenEnded(true)
        begin(x)
        at(x, 2); tap(x, 2, Fraction.ZERO); tap(x, 3, f(1, 2))
        x.vm.stopRhythmTap(frame(x, 3, f(3, 4))); advanceUntilIdle()
        assertEquals(2, x.vm.rhythm.value.newBars)
        x.vm.discardRhythm(); advanceUntilIdle()
        assertSame(before, editor(x).project)
        assertEquals(before, x.t.prepared.last())
        assertFalse(editor(x).canUndo)
        // „Stopp“ during the count-in: nothing recorded, cancelled.
        x.vm.openRhythmTap(); x.vm.startRhythmTap(); advanceUntilIdle()
        x.vm.stopRhythmTap(0); advanceUntilIdle()
        assertEquals(RhythmTapPhase.SETUP, x.vm.rhythm.value.phase)
        assertSame(before, editor(x).project)
        x.vm.closeRhythmTap()
        // A repeat over the last two bars: a fixed target past the end is refused with a clear message, nothing changes.
        val repeated = before.copy(measures = before.measures.mapIndexed { i, m -> if (i == 1) m.copy(repeat = RepeatMark(endCount = 2)) else m })
        x.vm.command(EditorCommand.Replace(repeated)); advanceUntilIdle()
        cursorAt(x, 1)
        val withRepeat = editor(x).project
        x.vm.openRhythmTap(); x.vm.setRhythmBarCount(2)
        x.vm.startRhythmTap(); advanceUntilIdle()
        assertEquals(RhythmTapPhase.SETUP, x.vm.rhythm.value.phase)
        assertTrue(x.vm.state.value.notice.orEmpty(), x.vm.state.value.notice.orEmpty().contains("Wiederholungen"))
        assertSame(withRepeat, editor(x).project)
        // Open from bar 1: it ends before the repeat, nothing is appended.
        cursorAt(x, 0)
        x.vm.setRhythmOpenEnded(true); begin(x)
        assertTrue(x.vm.state.value.notice.orEmpty(), x.vm.state.value.notice.orEmpty().contains("endet dort"))
        tap(x, 0, Fraction.ZERO)
        at(x, 1, f(1, 1))
        assertEquals(RhythmTapPhase.PREVIEW, x.vm.rhythm.value.phase)
        assertEquals(0, x.vm.rhythm.value.newBars)
    }
}
