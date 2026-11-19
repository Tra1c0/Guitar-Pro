package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.GuitarActivity
import dev.riffworkbench.playback.HitGlow
import dev.riffworkbench.playback.ScoreClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import dev.riffworkbench.ports.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 30 (JVM shim):
 *  V1  Positioning (drum ruler/lane, score number lane) moves only the cursor: no note changes, the revision stays, no undo step.
 *  V2  While playing: the drag pauses following, ONE seek on release (to the snapped position), following resumes.
 *  V3  Paused: Play starts at the new cursor (Zyklus 18 rule); stopped: likewise.
 *  A1  Which set cells glow at plan time t (DrumGrid cells + HitGlow), the lane x geometry and the drum strip's snap targets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa30PositioningViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("q30vm-${n++}") }
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
    private val settings = object : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override suspend fun load() = Outcome.Success(Unit)
        override suspend fun update(settings: AppSettings): Outcome<Unit> { this.settings.value = settings; return Outcome.Success(Unit) }
    }
    private val exchange = object : NativeProjectExchange {
        override suspend fun export(project: Project): Outcome<ExportResult> = unavailable("C-NATIVE")
        override suspend fun import(bytes: ByteArray, newProjectId: EntityId): Outcome<Project> = unavailable("C-NATIVE")
    }
    /** Records seeks; play/pause switch the status like the engine. */
    private class RecordingTransport : Transport {
        override val state = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
        val seeks = mutableListOf<PlaybackOccurrence>()
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun play(): Outcome<Unit> { state.value = state.value.copy(status = TransportStatus.PLAYING); return Outcome.Success(Unit) }
        override suspend fun pause(): Outcome<Unit> { state.value = state.value.copy(status = TransportStatus.PAUSED); return Outcome.Success(Unit) }
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> { seeks += position; state.value = state.value.copy(position = position); return Outcome.Success(Unit) }
        override suspend fun setLoop(range: LoopRange?): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> { state.value = state.value.copy(status = TransportStatus.STOPPED); return Outcome.Success(Unit) }
    }

    private fun harness(block: TestScope.(WorkspaceViewModel, RecordingTransport) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = EditorSession(ProjectFactory(ids).create("QA30"), ids)
        session.dispatch(EditorCommand.InsertMeasures(1, 3))
        session.select(session.state.value.selection.copy(offset = Fraction.ZERO, string = 0))
        session.dispatch(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(4)))
        val repo = Repository(session.state.value.project.copy(revision = 0))
        val transport = RecordingTransport()
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, settings, transport, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); assertNotNull(vm.state.value.editor); block(vm, transport) } finally { store.clear(); Dispatchers.resetMain() }
    }
    private fun WorkspaceViewModel.project() = state.value.editor!!.project
    private fun WorkspaceViewModel.cursor() = state.value.editor!!.selection

    @Test fun v1_positioningChangesNoNotes() = harness { vm, transport ->
        val before = vm.project()
        val bar3 = before.measures[2].id
        vm.beginPositioning()
        assertTrue(vm.positionCursor(bar3, Fraction.of(3, 8)))
        advanceUntilIdle()
        assertEquals(before.revision, vm.project().revision)
        assertSame("the score is the same instance", before.tracks, vm.project().tracks)
        assertEquals(bar3 to Fraction.of(3, 8), vm.cursor().measureId to vm.cursor().offset)
        assertNull(vm.cursor().rangeEnd)
        assertTrue("stopped: no seek", transport.seeks.isEmpty())
        assertFalse(vm.state.value.editor!!.canUndo)
    }

    @Test fun v2_whilePlayingTheDragPausesFollowingAndSeeksOnceOnRelease() = harness { vm, transport ->
        vm.togglePlay(); advanceUntilIdle()
        assertEquals(TransportStatus.PLAYING, transport.state.value.status)
        val seeksAtStart = transport.seeks.size
        vm.beginPositioning()
        assertTrue("dragging pauses following", vm.view.value.followSuspended)
        // The drag itself (handle following the finger) never reaches the ViewModel; only the release does.
        assertEquals(seeksAtStart, transport.seeks.size)
        val bar2 = vm.project().measures[1].id
        vm.positionCursor(bar2, Fraction.of(1, 2)); advanceUntilIdle()
        assertEquals("one seek on release", seeksAtStart + 1, transport.seeks.size)
        assertEquals(ScorePosition(bar2, Fraction.of(1, 2)), transport.seeks.last().position)
        assertFalse("following resumes", vm.view.value.followSuspended)
    }

    @Test fun v3_pausedOrStoppedPlayStartsAtTheNewCursor() = harness { vm, transport ->
        vm.togglePlay(); advanceUntilIdle()
        vm.togglePlay(); advanceUntilIdle()
        assertEquals(TransportStatus.PAUSED, transport.state.value.status)
        val bar4 = vm.project().measures[3].id
        vm.beginPositioning(); vm.positionCursor(bar4, Fraction.of(1, 4)); advanceUntilIdle()
        val seeks = transport.seeks.size
        vm.togglePlay(); advanceUntilIdle()
        assertEquals("Play seeks to the new cursor", seeks + 1, transport.seeks.size)
        assertEquals(ScorePosition(bar4, Fraction.of(1, 4)), transport.seeks.last().position)
        vm.stop(); advanceUntilIdle()
        val bar2 = vm.project().measures[1].id
        vm.positionCursor(bar2, Fraction.ZERO); advanceUntilIdle()
        vm.togglePlay(); advanceUntilIdle()
        assertEquals(ScorePosition(bar2, Fraction.ZERO), transport.seeks.last().position)
    }

    @Test fun v4_insideABeatTheCursorTakesItsStart() = harness { vm, _ ->
        // The guitar quarter at 0 of bar 1 covers 1/8: the cursor lands on the beat start (Zyklus 23), no edit.
        val bar1 = vm.project().measures[0].id
        vm.positionCursor(bar1, Fraction.of(1, 8))
        assertEquals(Fraction.ZERO, vm.cursor().offset)
    }

    @Test fun a1_glowingCellsLaneGeometryAndSnapTargets() {
        val p0 = ProjectFactory(ids).create("A1")
        val session = EditorSession(p0, ids); session.dispatch(EditorCommand.InsertMeasures(1, 1)); session.dispatch(EditorCommand.SetTempo(120.0))
        var p = session.state.value.project
        val drums = p.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }
        fun hit(at: Fraction, d: Int, midi: Int) = Beat(ids.next(), at, WrittenDuration(d), listOf(Note(ids.next(), Pitch(midi))))
        val bar1 = listOf(hit(Fraction.ZERO, 4, 36), hit(Fraction.of(1, 4), 4, 38), hit(Fraction.of(1, 2), 4, 36), hit(Fraction.of(3, 4), 32, 42), Beat(ids.next(), Fraction.of(25, 32), WrittenDuration(32), listOf(Note(ids.next(), Pitch(42)))))
        val bar2 = listOf(hit(Fraction.ZERO, 4, 49))
        p = p.copy(tracks = p.tracks.map { t -> if (t.id != drums.id) t else t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.mapIndexed { i, b -> b.copy(voices = listOf(Voice(ids.next(), 0, if (i == 0) bar1 else bar2))) }) }) })
        val bars16 = DrumGrid.bars(p, Fraction.of(1, 16))
        val clock = ScoreClock(p)
        val hits = HitGlow.hits(p, clock, p.tracks.first { it.id == drums.id }, 0)
        // 120 BPM: a bar is 2 s. At 1.0 s the kick on beat 3 (cell 9 of 16) glows at full intensity.
        assertEquals(listOf(DrumGridActivity.Glow(0, 8, 36, 1f)), DrumGridActivity.glowing(bars16, hits, 1.0))
        // 25/32 lies between two 1/16 cells: not shown as a set cell, so it never glows in the 1/16 grid …
        val at = clock.seconds(0, 25.0 / 32)
        assertTrue(DrumGridActivity.glowing(bars16, hits, at).none { it.midi == 42 && it.intensity == 1f })
        // … but does in the 1/32 grid (cell 26).
        assertTrue(DrumGridActivity.Glow(0, 25, 42, 1f) in DrumGridActivity.glowing(DrumGrid.bars(p, Fraction.of(1, 32)), hits, at))
        // Downbeat of bar 2 glows; 80 ms later fading, after the decay off.
        assertEquals(1f, DrumGridActivity.glowing(bars16, hits, 2.0).single { it.bar == 1 }.intensity, 1e-6f)
        assertTrue(DrumGridActivity.glowing(bars16, hits, 2.08).single { it.bar == 1 }.intensity in 0.01f..0.99f)
        assertTrue(DrumGridActivity.glowing(bars16, hits, 2.2).none { it.bar == 1 })

        // Lane x: cell starts map exactly, between cells linear, the bar end at the last cell's right edge.
        val cells = bars16[0].cells
        val xs = DrumGridGeometry.cellX(cells)
        assertEquals(xs[4], DrumGridActivity.xAt(cells, xs, Fraction.ONE, 0.25), 1e-4f)
        assertEquals((xs[4] + xs[5]) / 2, DrumGridActivity.xAt(cells, xs, Fraction.ONE, 0.25 + 1.0 / 32), 1e-4f)
        assertEquals(xs.last() + DrumGridGeometry.CELL, DrumGridActivity.xAt(cells, xs, Fraction.ONE, 1.0), 1e-4f)

        // Snap targets: set cells and guitar attacks are onsets; every cell is grid; over the barline in global dp.
        val lanes = GuitarActivity.lanes(p)
        val beats = p.tracks.first { it.id == drums.id }.staves[0].bars.map { it.voices[0].beats }
        val (onsets, grid) = DrumGridActivity.snapTargets(bars16, beats, lanes)
        assertEquals(32, grid.size)
        val starts = DrumGridGeometry.barStarts(bars16)
        val crash = onsets.single { it.measureId == bars16[1].measureId }
        assertEquals(starts[1] + xs[0], crash.x, 1e-4f)
        // A drag 20 dp before bar 2's crash (still in bar 1) snaps onto it; the last cell of bar 1 is farther.
        assertEquals(crash, dev.riffworkbench.notation.CursorSnap.snap(crash.x - 20f, onsets, grid, DrumGridActivity.SNAP_RADIUS))
    }

    /** A2: the bar layer's coordinate hit test replaces one button per cell: every cell centre hits its cell, gaps hit nothing. */
    @Test fun a2_cellHitTestByCoordinate() {
        val cells = DrumGrid.cells(Measure(EntityId("a2"), TimeSignature(4, 4)), Fraction.of(1, 16))
        val xs = DrumGridGeometry.cellX(cells)
        val c = DrumGridGeometry.CELL
        for (row in 0 until 11) for (i in xs.indices) assertEquals(row to i, DrumGridGeometry.cellAt(xs, 11, xs[i] + c / 2, row * (c + DrumGridGeometry.ROW_GAP) + c / 2))
        assertNull("gap between cells", DrumGridGeometry.cellAt(xs, 11, xs[1] - 1f, 10f))
        assertNull("gap between rows", DrumGridGeometry.cellAt(xs, 11, xs[0] + 5f, c + DrumGridGeometry.ROW_GAP / 2))
        assertNull("below the last row", DrumGridGeometry.cellAt(xs, 11, xs[0] + 5f, DrumGridGeometry.rowsHeight(11) + 1f))
        assertNull(DrumGridGeometry.cellAt(xs, 11, xs[0] + 5f, -1f))
    }
}
