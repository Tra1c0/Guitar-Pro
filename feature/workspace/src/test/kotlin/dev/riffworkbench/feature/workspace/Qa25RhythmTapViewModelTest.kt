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
 * Zyklus 25 „Rhythmus tippen“, ViewModel flow (lifecycle shim): start → count-in → recording → auto-stop → preview,
 * grid change without new taps, „Verwerfen“ changes nothing, „Übernehmen“ is one undo step, overwrite confirmation,
 * „Raster“ placeholders with fret entry (placeholder duration, tie into the continuation, jump to the next attack),
 * calibration (median stored, applied to later taps), interruption aborts. The transport is a fake whose time map is the
 * identity (a tap's „nanos“ is its audible plan frame), so taps are exact plan frames; Qa25TapTimingTest covers the real map.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa25RhythmTapViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("q25vm-${n++}") }
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

    /** Transport fake: compiles real plans, plays from the seek frame; the test moves the playhead with [advance]. */
    private class FakeTransport(val sr: Int) : Transport {
        val mutable = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
        override val state = mutable
        val prepared = ArrayList<Project>()
        var settings = PracticeSettings()
        var plan: PlaybackPlan? = null
        var start = 0L
        var plays = 0
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> {
            prepared += project; this.settings = settings
            plan = (ScorePlaybackCompiler().compileNow(project, sr, PlaybackOptions(settings.speed)) as Outcome.Success).value
            return Outcome.Success(Unit)
        }
        override suspend fun play(): Outcome<Unit> { plays++; mutable.value = mutable.value.copy(status = TransportStatus.PLAYING, sample = SamplePosition(start, sr), countingIn = settings.countInBars > 0); return Outcome.Success(Unit) }
        override suspend fun pause(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.PAUSED); return Outcome.Success(Unit) }
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> { start = plan!!.frameFor(position)!!; mutable.value = mutable.value.copy(sample = SamplePosition(start, sr)); return Outcome.Success(Unit) }
        override suspend fun setLoop(range: LoopRange?) = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> { mutable.value = mutable.value.copy(status = TransportStatus.STOPPED, countingIn = false); return Outcome.Success(Unit) }
        override fun audiblePlanFrameAt(nanos: Long): Double = if (mutable.value.status == TransportStatus.STOPPED) Double.NaN else nanos.toDouble()
        override fun installedPlan(): PlaybackPlan? = plan
        fun advance(frame: Long) { mutable.value = mutable.value.copy(sample = SamplePosition(frame, sr), countingIn = false) }
    }

    private class Fixture(val vm: WorkspaceViewModel, val t: FakeTransport, val settings: Settings)
    private fun harness(project: Project, block: TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val t = FakeTransport(sr)
        val settings = Settings()
        val vm = WorkspaceViewModel(project.id, Repository(project), Snapshots(), exchange, settings, t, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); assertNotNull(vm.state.value.editor); block(Fixture(vm, t, settings)) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    private fun project(bars: Int = 2) = ProjectFactory(ids).create("Q25", signatures = List(bars) { TimeSignature() }).copy(revision = 0)
    private fun f(a: Long, b: Long) = Fraction.of(a, b)
    private fun editor(x: Fixture) = x.vm.state.value.editor!!
    private fun guitar(p: Project) = p.tracks.first { it.instrument.kind == InstrumentKind.GUITAR }
    private fun beats(p: Project, bar: Int) = guitar(p).staves[0].bars[bar].voices[0].beats
    /** Plan frame of bar/offset in the transport's current plan (150 BPM default: a whole note is 76 800 frames). */
    private fun frame(x: Fixture, bar: Int, o: Fraction) = Math.round(x.t.plan!!.spans[bar].frameAt(o.toDouble()))
    private val ms = sr / 1000L
    /** Quick tap at bar/offset (held 60 ms) or held until [to]. */
    private fun TestScope.tap(x: Fixture, bar: Int, o: Fraction, to: Pair<Int, Fraction>? = null, late: Long = 0) {
        val down = frame(x, bar, o) + late * ms
        x.vm.rhythmTapDown(down)
        x.vm.rhythmTapUp(to?.let { (b, e) -> frame(x, b, e) - 20 * ms + late * ms } ?: (down + 60 * ms))
        advanceUntilIdle()
    }
    /** Held quarter, quick 16th, held half over the barline, quick 16th in bar 2. */
    private fun TestScope.record(x: Fixture, late: Long = 0) {
        x.vm.startRhythmTap(); advanceUntilIdle()
        assertEquals(RhythmTapPhase.COUNT_IN, x.vm.rhythm.value.phase)
        x.vm.rhythmTapDown(frame(x, 0, Fraction.ZERO) - 40_000); x.vm.rhythmTapUp(frame(x, 0, Fraction.ZERO) - 38_000)   // count-in tap
        x.t.advance(frame(x, 0, f(1, 64))); advanceUntilIdle()
        assertEquals(RhythmTapPhase.RECORDING, x.vm.rhythm.value.phase)
        tap(x, 0, Fraction.ZERO, 0 to f(1, 4), late); tap(x, 0, f(1, 4), late = late); tap(x, 0, f(3, 4), 1 to f(1, 4), late); tap(x, 1, f(1, 2), late = late)
        x.t.advance(frame(x, 1, f(1, 1))); advanceUntilIdle()
    }

    @Test fun recordPreviewRequantiseDiscardAndApplyAsOneUndoStep() = harness(project()) { x ->
        val before = editor(x).project
        x.vm.openRhythmTap()
        assertEquals(RhythmTapPhase.SETUP, x.vm.rhythm.value.phase)
        assertEquals("Takte 1–2", x.vm.rhythm.value.label)
        record(x)
        // Played: a copy with every track silent (no accompaniment), metronome and one bar count-in; restored afterwards.
        val tapping = x.t.prepared.first { p -> p.tracks.all { it.mix.mute } }
        assertEquals(before.tracks.map { it.id }, tapping.tracks.map { it.id })
        // Own revision: EngineTransport skips a prepare with equal id/revision/settings, the restore must not be skipped.
        assertNotEquals(before.revision, tapping.revision)
        assertEquals(TransportStatus.STOPPED, x.t.state.value.status)
        assertEquals(editor(x).project, x.t.prepared.last())
        val r = x.vm.rhythm.value
        assertEquals(RhythmTapPhase.PREVIEW, r.phase)
        val result = r.result!!
        assertEquals(1, result.discarded); assertEquals(4, result.onsets)
        assertEquals(listOf(f(0, 1) to RhythmEvent.Kind.ONSET, f(1, 4) to RhythmEvent.Kind.ONSET, f(5, 16) to RhythmEvent.Kind.REST, f(3, 4) to RhythmEvent.Kind.ONSET),
            result.bars[0].events.map { it.offset to it.kind })
        // Grid change re-quantises the same taps: the quick tap is now an eighth.
        x.vm.setRhythmGrid(RhythmGrid.EIGHTH)
        assertEquals(WrittenDuration(8), x.vm.rhythm.value.result!!.bars[0].events[1].duration)
        x.vm.discardRhythm(); advanceUntilIdle()
        assertEquals(RhythmTapPhase.IDLE, x.vm.rhythm.value.phase)
        assertSame(before, editor(x).project)
        // Again, apply as open strings: one undo step.
        x.vm.openRhythmTap(); record(x)
        assertEquals(0, x.vm.rhythm.value.overwrites)
        x.vm.applyRhythm(RhythmMode.OPEN_STRING); advanceUntilIdle()
        val after = editor(x).project
        assertEquals(RhythmTapPhase.IDLE, x.vm.rhythm.value.phase)
        assertEquals(listOf(0 to 0), beats(after, 0).first().notes.map { it.string to it.fret })
        assertTrue(beats(after, 0).last().notes.single().links.any { it.kind == LinkKind.TIE })
        assertTrue(ScoreValidator.validate(after).none { it.severity == Severity.ERROR })
        x.vm.undo(); advanceUntilIdle()
        assertEquals(before.tracks, editor(x).project.tracks)
    }

    @Test fun gridPlaceholdersAskBeforeOverwritingAndGuideTheFretEntry() = harness(project()) { x ->
        // Existing notes in the target: the apply asks first.
        x.vm.enterFret(3); advanceUntilIdle()
        x.vm.select(editor(x).selection.copy(measureId = editor(x).project.measures[0].id, offset = Fraction.ZERO, string = 1)); advanceUntilIdle()
        x.vm.openRhythmTap(); x.vm.setRhythmString(1); record(x)
        assertEquals(1, x.vm.rhythm.value.overwrites)
        val before = editor(x).project
        x.vm.applyRhythm(RhythmMode.GRID); advanceUntilIdle()
        assertEquals(RhythmMode.GRID, x.vm.rhythm.value.confirm)
        assertSame(before, editor(x).project)
        x.vm.applyRhythm(RhythmMode.GRID, confirmed = true); advanceUntilIdle()
        val written = editor(x).project
        assertTrue((beats(written, 0) + beats(written, 1)).all { it.notes.isEmpty() && it.explicitRest })
        // Cursor on the first tapped attack, string 1.
        assertEquals(Fraction.ZERO to 1, editor(x).selection.offset to editor(x).selection.string)
        x.vm.setGrid(WrittenDuration(32)); advanceUntilIdle()   // the entry duration must not matter on a placeholder
        x.vm.enterFret(5); advanceUntilIdle()
        assertEquals(WrittenDuration(4), beats(editor(x).project, 0).first().duration)
        assertEquals(f(1, 4), editor(x).selection.offset)                 // next attack
        x.vm.enterFret(7); advanceUntilIdle()
        assertEquals(f(3, 4), editor(x).selection.offset)                 // the rests after the quick 16th are skipped
        val beforeHeld = editor(x).project
        x.vm.enterFret(9); advanceUntilIdle()
        val p = editor(x).project
        // Held over the barline: the continuation placeholder in bar 2 is tied, in the same undo step.
        val origin = beats(p, 0).last().notes.single(); val cont = beats(p, 1).first().notes.single()
        assertEquals(listOf(NoteLink(LinkKind.TIE, cont.id)), origin.links); assertEquals(9, cont.fret)
        assertEquals(editor(x).project.measures[1].id to f(1, 2), editor(x).selection.measureId to editor(x).selection.offset)
        x.vm.undo(); advanceUntilIdle()
        assertEquals(beforeHeld.tracks, editor(x).project.tracks)
    }

    @Test fun calibrationStoresTheMedianAndLaterTapsAreCorrected() = harness(project()) { x ->
        x.vm.openRhythmTap()
        x.vm.startTapCalibration(); advanceUntilIdle()
        assertEquals(RhythmTapPhase.CALIBRATING, x.vm.rhythm.value.phase)
        val clicks = x.t.plan!!.clicks.map { it.frame }
        assertEquals(8, clicks.size)
        x.t.advance(1); advanceUntilIdle()
        clicks.forEachIndexed { i, c -> x.vm.rhythmTapDown(c + (if (i == 5) 230 else 80 + i % 3 - 1) * ms); x.vm.rhythmTapUp(c + 150 * ms) }
        x.t.advance(x.t.plan!!.totalFrames); advanceUntilIdle()
        assertEquals(RhythmTapPhase.SETUP, x.vm.rhythm.value.phase)
        assertEquals(80.0, x.settings.settings.value.tapLatencyMs, 1.0)
        assertEquals(editor(x).project, x.t.prepared.last())
        // Taps 80 ms late (more than half a 16th at 150 BPM) now land exactly.
        record(x, late = 80)
        assertEquals(f(1, 4), x.vm.rhythm.value.result!!.bars[0].events[1].offset)
        assertEquals(WrittenDuration(4), x.vm.rhythm.value.result!!.bars[0].events[0].duration)
    }

    @Test fun anInterruptionAbortsWithoutWriting() = harness(project()) { x ->
        val before = editor(x).project
        x.vm.openRhythmTap(); x.vm.startRhythmTap(); advanceUntilIdle()
        x.t.advance(frame(x, 0, f(1, 8))); advanceUntilIdle()
        tap(x, 0, f(1, 4))
        x.t.mutable.value = x.t.mutable.value.copy(status = TransportStatus.PAUSED); advanceUntilIdle()   // audio focus lost
        assertEquals(RhythmTapPhase.SETUP, x.vm.rhythm.value.phase)
        assertNull(x.vm.rhythm.value.result)
        assertSame(before, editor(x).project)
        assertEquals(TransportStatus.STOPPED, x.t.state.value.status)
        // Cancel during the count-in: nothing written either, transport stopped.
        x.vm.startRhythmTap(); advanceUntilIdle()
        x.vm.cancelRhythmTap(); advanceUntilIdle()
        assertEquals(RhythmTapPhase.SETUP, x.vm.rhythm.value.phase)
        assertSame(before, editor(x).project)
        assertEquals(TransportStatus.STOPPED, x.t.state.value.status)
    }
}
