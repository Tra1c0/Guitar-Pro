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
 * Zyklus 28 „Rhythmus glätten (ein Klick)“ and „Taktart erkennen“, ViewModel flow (lifecycle shim, the transport fake of
 * Qa27RhythmAppendViewModelTest): Auto is the default and needs no click (Start → taps → Stopp → preview with the info
 * line → Übernehmen); „Raster manuell“ is folded; a 7/8 riff tapped to the 4/4 click in an open recording at the end of
 * the piece is written in 7/8 bars in the same undo step (tempo unchanged); bars with notes in other tracks keep their
 * meter (hint); a fixed or kept meter by hand; the practice speed is shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa28RhythmAutoViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("q28vm-${n++}") }
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
    private fun project(bars: Int = 2) = ProjectFactory(ids).create("Q28", signatures = List(bars) { TimeSignature() }).copy(revision = 0)
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


    /** Global position (whole notes from bar [bar0]) → taps of the 7/8 riff (16ths) repeated [times] times, at 120 BPM in 4/4 bars. */
    private val riff78 = listOf(0 to 2, 2 to 1, 3 to 1, 4 to 2, 6 to 2, 8 to 2, 10 to 4)
    private fun TestScope.tapRiff(x: Fixture, bar0: Int, riff: List<Pair<Int, Int>>, period: Int, times: Int) {
        for (k in 0 until times) for ((a, d) in riff) {
            val at = k * period + a
            val bar = bar0 + at / 16; val o = f((at % 16).toLong(), 16)
            if (at % 16 == 0) at(x, bar)
            val end = at + d
            if (d == 1) tap(x, bar, o) else tap(x, bar, o, (bar0 + end / 16) to f((end % 16).toLong(), 16))
        }
    }

    @Test fun autoIsTheDefaultAndOneClickApplies() = harness(project(2)) { x ->
        val before = editor(x).project
        x.vm.openRhythmTap()
        val s = x.vm.rhythm.value
        assertEquals(RhythmGrid.SMART, s.grid); assertFalse("Raster manuell eingeklappt", s.manualOpen); assertEquals(RhythmMeterMode.AUTO, s.meterMode)
        begin(x)
        // Gallop on every beat with slightly late/early taps, bar 2 the same.
        for (bar in 0..1) for (b in 0 until 4) { tap(x, bar, f(b.toLong(), 4), bar to f(b * 2L + 1, 8)); tap(x, bar, f(b * 4L + 2, 16)); tap(x, bar, f(b * 4L + 3, 16)) }
        at(x, 1, f(1, 1)); advanceUntilIdle()
        val r = x.vm.rhythm.value
        assertEquals(RhythmTapPhase.PREVIEW, r.phase)
        assertTrue(r.info, r.info.startsWith("erkannt: 16tel") && r.info.contains("Takt 1=2 gleich"))
        assertNull(r.meterInfo)
        assertEquals(r.result!!.bars[0].events, r.result!!.bars[1].events)
        x.vm.applyRhythm(RhythmMode.OPEN_STRING); advanceUntilIdle()
        val after = editor(x).project
        assertEquals(24, guitar(after).staves[0].bars.sumOf { b -> b.voices[0].beats.count { it.notes.isNotEmpty() } })
        assertEquals(before.measures, after.measures); noErrors(after)
        x.vm.undo(); advanceUntilIdle()
        assertEquals(before.tracks, editor(x).project.tracks)
    }

    @Test fun aSevenEightRiffInAnOpenRecordingIsWrittenInSevenEight() = harness(project(1)) { x ->
        val before = editor(x).project
        x.vm.openRhythmTap(); x.vm.setRhythmOpenEnded(true); x.vm.setRhythmAutoStop(false)
        begin(x)
        tapRiff(x, 0, riff78, 14, 4)       // 4 × 7/8 = 3.5 bars of 4/4
        at(x, 4); x.vm.stopRhythmTap(frame(x, 4, f(1, 16))); advanceUntilIdle()
        val r = x.vm.rhythm.value
        assertEquals(RhythmTapPhase.PREVIEW, r.phase)
        assertEquals("Erkannt: 7/8", r.meterInfo)
        val bars = r.result!!.bars
        assertEquals(4, bars.size)
        assertTrue(bars.all { it.length == f(7, 8) })
        assertTrue("jeder Takt dasselbe Riff", bars.all { it.events == bars[0].events })
        assertEquals(listOf(f(0, 1), f(1, 8), f(3, 16), f(1, 4), f(3, 8), f(1, 2), f(5, 8)), bars[0].events.filter { it.kind == RhythmEvent.Kind.ONSET }.map { it.offset })
        assertEquals(3, r.newBars)
        x.vm.applyRhythm(RhythmMode.OPEN_STRING); advanceUntilIdle()
        val after = editor(x).project
        assertEquals(4, after.measures.size); noErrors(after)
        assertTrue(after.measures.all { it.signature == TimeSignature(7, 8) })
        assertEquals(before.tempo, after.tempo)   // quarters per minute stay: every 8th keeps its length
        for (t in after.tracks) assertEquals(after.measures.map { it.id }, t.staves[0].bars.map { it.measureId })
        x.vm.undo(); advanceUntilIdle()
        assertEquals(before.measures, editor(x).project.measures)
        // By hand: kept (4/4) and fixed 3/4.
        x.vm.openRhythmTap(); x.vm.setRhythmOpenEnded(true); x.vm.setRhythmAutoStop(false)
        begin(x); tapRiff(x, 0, riff78, 14, 4); at(x, 4); x.vm.stopRhythmTap(frame(x, 4, f(1, 16))); advanceUntilIdle()
        x.vm.toggleRhythmManual(); assertTrue(x.vm.rhythm.value.manualOpen)
        x.vm.setRhythmMeter(RhythmMeterMode.KEEP)
        assertNull(x.vm.rhythm.value.meterInfo); assertTrue(x.vm.rhythm.value.result!!.bars.all { it.length == Fraction.ONE })
        x.vm.setRhythmMeter(RhythmMeterMode.FIXED, TimeSignature(3, 4))
        assertEquals("Taktart: 3/4", x.vm.rhythm.value.meterInfo)
        assertTrue(x.vm.rhythm.value.result!!.bars.all { it.length == f(3, 4) })
        x.vm.applyRhythm(RhythmMode.GRID); advanceUntilIdle()
        assertTrue(editor(x).project.measures.all { it.signature == TimeSignature(3, 4) }); noErrors(editor(x).project)
    }

    @Test fun barsWithNotesInOtherTracksKeepTheirMeter() = harness(project(4).let { p ->
        // A bass note in every bar: nothing may change its meter.
        val bass = p.tracks.first { it.instrument.kind == InstrumentKind.BASS }
        p.copy(tracks = p.tracks.map { t -> if (t.id != bass.id) t else t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.map { b ->
            b.copy(voices = b.voices.map { v -> if (v.index != 0) v else v.copy(beats = listOf(Beat(ids.next(), Fraction.ZERO, WrittenDuration(1), listOf(Note(ids.next(), Pitch(40), 0, 0))))) }) }) }) }) }) { x ->
        val before = editor(x).project
        x.vm.openRhythmTap(); x.vm.setRhythmBarCount(4)
        begin(x)
        tapRiff(x, 0, riff78, 14, 4)
        at(x, 3, f(1, 1)); advanceUntilIdle()
        val r = x.vm.rhythm.value
        assertEquals(RhythmTapPhase.PREVIEW, r.phase)
        assertEquals("Erkannt: 7/8 – vorhandene Takte behalten 4/4", r.meterInfo)
        assertTrue(r.result!!.bars.all { it.length == Fraction.ONE })
        x.vm.applyRhythm(RhythmMode.OPEN_STRING); advanceUntilIdle()
        assertEquals(before.measures, editor(x).project.measures)
    }

    @Test fun thePracticeSpeedIsShown() = harness(project(1)) { x ->
        x.vm.setSpeed(0.7); advanceUntilIdle()
        x.vm.openRhythmTap()
        assertEquals(0.7, x.vm.rhythm.value.speed, 1e-9)
        assertEquals(0.7, x.vm.practice.value.speed, 1e-9)
    }
}
