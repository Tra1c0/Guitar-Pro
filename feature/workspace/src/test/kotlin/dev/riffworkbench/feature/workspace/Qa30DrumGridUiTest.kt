package dev.riffworkbench.feature.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.ui.components.RiffTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Zyklus 30 — Drum-Raster: Positionieren getrennt von Bearbeiten, Gitarren-Leiste, Leuchten ohne Recomposition.
 * Robolectric, CI only (locally as Compose-Desktop variant). Phone in landscape.
 *
 *  D1  Drag in the strip (lane/header/ruler) moves only the cursor, snapping onto a hit within the radius; no edit.
 *  D2  A touch on a cell still toggles the hit (coordinate hit test of the bar layer).
 *  D3  „Gitarren-Aktivität“ lane with a guitar; none without one.
 *  D4  While playing: the drag pauses following, no seek while dragging, ONE seek on release, following resumes.
 *  D5  30 polls with glowing hits: no bar/cell composable recomposes (draw phase only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w891dp-h411dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa30DrumGridUiTest {
    @get:Rule val compose = createComposeRule()

    private var n = 0
    private val ids = IdSource { EntityId("q30d-${n++}") }
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
    private class DrivenTransport : Transport {
        override val state = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
        val seeks = mutableListOf<PlaybackOccurrence>()
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun play(): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun pause(): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> { seeks += position; return Outcome.Success(Unit) }
        override suspend fun setLoop(range: LoopRange?): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> = Outcome.Success(Unit)
    }
    private class Fixture(val vm: WorkspaceViewModel, val transport: DrivenTransport)

    private val store = ViewModelStore()
    @After fun tearDown() { RenderCounts.enabled = false; RenderCounts.reset(); store.clear() }

    private fun eighth(at: Fraction, vararg midi: Int) = Beat(ids.next(), at, WrittenDuration(8), midi.map { Note(ids.next(), Pitch(it)) })

    /** 64 bars 4/4 at 120 BPM: drums with a rock pattern, the guitar in quarters (string 0, fret 3). */
    private fun piece(bars: Int = 8, guitar: Boolean = true): Project {
        val session = EditorSession(ProjectFactory(ids).create("QA30"), ids)
        session.dispatch(EditorCommand.InsertMeasures(1, bars - 1))
        session.dispatch(EditorCommand.SetTempo(120.0))
        val p = session.state.value.project
        return p.copy(revision = 0, tracks = p.tracks.map { t ->
            when (t.instrument.kind) {
                InstrumentKind.PERCUSSION -> t.copy(staves = t.staves.mapIndexed { si, st -> if (si != 0) st else st.copy(bars = st.bars.map { b ->
                    b.copy(voices = b.voices.map { v -> if (v.index != 0) v else v.copy(beats = (0 until 8).map { k ->
                        val hits = listOfNotNull(42, if (k == 0 || k == 4) 36 else null, if (k == 2 || k == 6) 38 else null).toIntArray()
                        eighth(Fraction.of(k.toLong(), 8), *hits)
                    }) })
                }) })
                InstrumentKind.GUITAR -> t.copy(staves = t.staves.mapIndexed { si, st -> if (si != 0) st else st.copy(bars = st.bars.map { b ->
                    b.copy(voices = b.voices.map { v -> if (v.index != 0) v else v.copy(beats = (0 until 4).map { k ->
                        Beat(ids.next(), Fraction.of(k.toLong(), 4), WrittenDuration(4), listOf(Note(ids.next(), Pitch(t.instrument.tuning[0] + 3), string = 0, fret = 3)))
                    }) })
                }) })
                else -> t
            }
        }.filter { guitar || it.instrument.kind != InstrumentKind.GUITAR })
    }

    private fun start(guitar: Boolean = true): Fixture {
        val project = piece(guitar = guitar)
        val repo = Repository(project)
        val transport = DrivenTransport()
        val vm = WorkspaceViewModel(project.id, repo, Snapshots(), exchange, Settings(), transport, ids)
        store.put("vm", vm)
        compose.setContent { RiffTheme { WorkspaceScreen(vm, onBack = {}, onExport = {}, onSettings = {}, initialPage = WorkspacePage.DRUMS) } }
        compose.waitUntil(20000) { vm.state.value.editor != null && compose.onAllNodesWithTag("drum-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return Fixture(vm, transport)
    }
    private fun project(f: Fixture) = f.vm.state.value.editor!!.project
    private fun grid(label: String) { compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }
    private fun drumBeats(f: Fixture) = project(f).tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }.staves[0].bars.map { it.voices[0].beats }
    /** x (px) of a cell's left edge, relative to the strip of the same bar. */
    private fun cellX(bar: Int, cell: Int, midi: Int = 36): Float {
        val strip = compose.onNodeWithTag("drum-strip-$bar").fetchSemanticsNode().positionInRoot
        return compose.onNodeWithTag("drum-cell-$bar-$cell-$midi").fetchSemanticsNode().positionInRoot.x - strip.x
    }

    @Test fun d1_draggingTheStripMovesOnlyTheCursorAndSnapsOntoAHit() {
        val f = start()
        grid("1/16")
        val revision = project(f).revision
        val beats = drumBeats(f)
        // Kick on beat 3 of bar 1 = cell 9 (offset 1/2); the drag ends 10 dp right of that cell's start (inside the radius).
        val d = compose.density.density
        val from = cellX(1, 2) + 5f
        val target = cellX(1, 9)
        compose.onNodeWithTag("drum-strip-1").performTouchInput {
            down(Offset(from, 6f * d)); moveBy(Offset(30f * d, 0f)); moveTo(Offset(target + 10f * d, 6f * d)); up()
        }
        compose.waitForIdle()
        val s = f.vm.state.value.editor!!.selection
        assertEquals(project(f).measures[0].id to Fraction.of(1, 2), s.measureId to s.offset)
        assertEquals("no edit", revision, project(f).revision)
        assertEquals("no hit changed", beats, drumBeats(f))
    }

    @Test fun d2_aTapOnACellStillTogglesTheHitByCoordinate() {
        val f = start()
        grid("1/16")
        // Cell 3 of bar 1 (the hi-hat eighth at 1/8) has no kick: a touch click (not a semantics action) goes through the bar's hit test.
        compose.onNodeWithContentDescription("Kick, 3, off").performClick()
        compose.waitForIdle()
        assertTrue(drumBeats(f)[0].any { it.offset == Fraction.of(1, 8) && it.notes.any { n -> n.pitch.midiNumber == 36 } })
        compose.onNodeWithContentDescription("Kick, 3, on").assertExists()
        compose.onNodeWithContentDescription("Kick, 3, on").performClick()
        compose.waitForIdle()
        assertTrue(drumBeats(f)[0].none { it.offset == Fraction.of(1, 8) && it.notes.any { n -> n.pitch.midiNumber == 36 } })
    }

    @Test fun d3_theGuitarLaneIsThereWithAGuitar() {
        start()
        compose.onNodeWithContentDescription("Gitarren-Aktivität").assertExists()
        // Later bars name their bar (composed when scrolled into view).
        compose.onNodeWithTag("drum-grid").performScrollToNode(hasContentDescription("Gitarren-Aktivität, Takt 2"))
        compose.onNodeWithContentDescription("Gitarren-Aktivität, Takt 2").assertExists()
    }

    @Test fun d3b_noGuitarNoLane() {
        start(guitar = false)
        compose.onAllNodesWithContentDescription("Gitarren-Aktivität", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("drum-strip-1").assertExists()
    }

    @Test fun d4_whilePlayingTheDragPausesFollowingAndSeeksOnceOnRelease() {
        val f = start()
        grid("1/16")
        f.transport.state.value = f.transport.state.value.copy(status = TransportStatus.PLAYING,
            position = PlaybackOccurrence(ScorePosition(project(f).measures[0].id, Fraction.ZERO), 0, 0), sample = SamplePosition(1600L, 48_000))
        compose.waitForIdle()
        val seeks = f.transport.seeks.size
        val d = compose.density.density
        val from = cellX(1, 3)
        compose.onNodeWithTag("drum-strip-1").performTouchInput { down(Offset(from, 6f * d)); moveBy(Offset(60f * d, 0f)) }
        compose.waitForIdle()
        assertTrue("dragging pauses following", f.vm.view.value.followSuspended)
        assertEquals("no seek while dragging", seeks, f.transport.seeks.size)
        compose.onNodeWithTag("drum-strip-1").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("one seek on release", seeks + 1, f.transport.seeks.size)
        assertEquals(project(f).measures[0].id, f.transport.seeks.last().position.measureId)
        assertFalse(f.vm.view.value.followSuspended)
    }

    @Test fun d5_pollsAndGlowDoNotRecomposeBarsOrCells() {
        val f = start()
        grid("1/16")
        RenderCounts.enabled = true
        // The first poll switches STOPPED → PLAYING (play/pause button etc. recompose once); counted from the second on.
        f.transport.state.value = f.transport.state.value.copy(status = TransportStatus.PLAYING,
            position = PlaybackOccurrence(ScorePosition(project(f).measures[0].id, Fraction.ZERO), 0, 0), sample = SamplePosition(800L, 48_000))
        compose.waitForIdle()
        RenderCounts.reset()
        // 30 polls through the hits of beats 1 and 2 of bar 1 (glowing hi-hat/kick/snare), 1 s at 120 BPM.
        for (k in 0 until 30) {
            val offset = Fraction.of(k.toLong(), 60)
            f.transport.state.value = f.transport.state.value.copy(status = TransportStatus.PLAYING,
                position = PlaybackOccurrence(ScorePosition(project(f).measures[0].id, offset), 0, 0), sample = SamplePosition(1600L * (k + 1), 48_000))
            compose.waitForIdle()
        }
        val c = RenderCounts.snapshot()
        for (name in listOf("WorkspaceScreen", "DrumScreen", "DrumGrid", "DrumGrid.bar", "DrumGrid.cells")) assertEquals("D5 '$name': $c", 0, RenderCounts.of(name))
    }
}
