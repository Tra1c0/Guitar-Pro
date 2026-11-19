package dev.riffworkbench.feature.workspace

import androidx.compose.ui.semantics.SemanticsActions
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
 * Zyklus 29 „Drum-Raster“ — Robolectric, CI only (excluded from the JVM shim by its name). The real WorkspaceScreen on the
 * drum page, phone in landscape (the user's screenshots).
 *
 *  G1  Every grid gives every 4/4 bar all its cells — the last beat included (device finding: beat 4 had one cell) — and
 *      the last cell writes the last grid position.
 *  G2  One continuous grid over all bars: a cell of bar N writes into bar N without selecting the bar first (4/4, 3/4, 6/8).
 *  G3  Transport polls do not recompose the grid content; the sounding column follows the shown playhead.
 *  G4  The view follows the playhead into later bars; a swipe pauses the follow, the chip resumes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w891dp-h411dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa29DrumGridUiTest {
    @get:Rule val compose = createComposeRule()

    private var n = 0
    private val ids = IdSource { EntityId("q29ui-${n++}") }
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
        override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun play(): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun pause(): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun setLoop(range: LoopRange?): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun stop(): Outcome<Unit> = Outcome.Success(Unit)
    }
    private class Fixture(val vm: WorkspaceViewModel, val transport: DrivenTransport, val kick: Int, val kickName: String)

    private val store = ViewModelStore()
    @After fun tearDown() { RenderCounts.enabled = false; RenderCounts.reset(); store.clear() }

    /** The default band piece (guitar, bass, drums) with [signatures] as its bars. */
    private fun start(vararg signatures: TimeSignature): Fixture {
        val session = EditorSession(ProjectFactory(ids).create("QA29"), ids)
        if (signatures.size > 1) session.dispatch(EditorCommand.InsertMeasures(1, signatures.size - 1))
        signatures.forEachIndexed { i, sig -> session.dispatch(EditorCommand.SetSignature(session.state.value.project.measures[i].id, sig)) }
        val project = session.state.value.project.copy(revision = 0)
        val drums = project.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }
        val repo = Repository(project)
        val transport = DrivenTransport()
        val vm = WorkspaceViewModel(project.id, repo, Snapshots(), exchange, Settings(), transport, ids)
        store.put("vm", vm)
        compose.setContent { RiffTheme { WorkspaceScreen(vm, onBack = {}, onExport = {}, onSettings = {}, initialPage = WorkspacePage.DRUMS) } }
        compose.waitUntil(20000) { vm.state.value.editor != null && compose.onAllNodesWithTag("drum-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return Fixture(vm, transport, drums.instrument.drumKit.first().midiNote, drums.instrument.drumKit.first().name)
    }
    private fun project(f: Fixture) = f.vm.state.value.editor!!.project
    private fun drumBeats(f: Fixture, bar: Int) = project(f).tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }.staves[0].bars[bar].voices[0].beats
    private fun cellTags(bar: Int, midi: Int): List<String> = compose.onAllNodes(SemanticsMatcher("drum cell of bar $bar") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.let { it.startsWith("drum-cell-$bar-") && it.endsWith("-$midi") } == true
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
    private fun grid(label: String) { compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }
    private fun tapCell(bar: Int, cell: Int, midi: Int) {
        compose.onNodeWithTag("drum-grid").performScrollToNode(hasTestTag("drum-cell-$bar-$cell-$midi"))
        compose.waitForIdle()
        compose.onNodeWithTag("drum-cell-$bar-$cell-$midi").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test fun g1_everyGridHasAllCellsOfTheLastBeat() {
        val f = start(TimeSignature(4, 4))
        for ((label, cells) in listOf("1/8" to 8, "1/16" to 16, "1/32" to 32, "1/8 T" to 12, "1/16 T" to 24)) {
            grid(label)
            assertEquals("G1 $label cells in 4/4", cells, cellTags(1, f.kick).size)
            compose.onNodeWithContentDescription("${f.kickName}, $cells, off").assertExists()
        }
        // The last 1/32 cell writes 31/32, the last 1/8 T cell 11/12.
        grid("1/32"); tapCell(1, 32, f.kick)
        assertEquals(listOf(Fraction.of(31, 32)), drumBeats(f, 0).filter { it.notes.isNotEmpty() }.map { it.offset })
        grid("1/8 T"); tapCell(1, 12, f.kick)
        assertTrue(drumBeats(f, 0).any { it.offset == Fraction.of(11, 12) && it.notes.any { n -> n.pitch.midiNumber == f.kick } })
    }

    @Test fun g2_aCellWritesIntoItsOwnBarWithoutSelectingIt() {
        val f = start(TimeSignature(4, 4), TimeSignature(3, 4), TimeSignature(6, 8))
        grid("1/8")
        val cursorBar = f.vm.state.value.editor!!.selection.measureId
        assertEquals(project(f).measures[0].id, cursorBar)
        tapCell(3, 6, f.kick)
        assertEquals(listOf(Fraction.of(5, 8)), drumBeats(f, 2).filter { it.notes.isNotEmpty() }.map { it.offset })
        assertTrue(drumBeats(f, 0).isEmpty() && drumBeats(f, 1).isEmpty())
        tapCell(2, 6, f.kick)
        assertEquals(listOf(Fraction.of(5, 8)), drumBeats(f, 1).filter { it.notes.isNotEmpty() }.map { it.offset })
        compose.onNodeWithContentDescription("${f.kickName}, 6, on, Takt 2").assertExists()
        assertEquals(6, cellTags(2, f.kick).size)
        // The header names the bar and the meter change.
        compose.onNodeWithText("Takt 2 · 3/4", substring = true).assertExists()
    }

    private fun poll(f: Fixture, bar: Int, offset: Fraction, k: Int) {
        f.transport.state.value = f.transport.state.value.copy(status = TransportStatus.PLAYING,
            position = PlaybackOccurrence(ScorePosition(project(f).measures[bar].id, offset), 0, 0), sample = SamplePosition(1600L * (k + 1), 48_000))
        compose.waitForIdle()
    }
    private fun playhead(): Pair<Int, Int>? = compose.onNodeWithTag("drum-playhead").fetchSemanticsNode().config.getOrNull(DrumPlayheadKey)

    @Test fun g3_pollsDoNotRecomposeTheGridAndTheColumnFollowsThePlayhead() {
        val f = start(*Array(6) { TimeSignature(4, 4) })
        grid("1/16")
        RenderCounts.enabled = true
        poll(f, 0, Fraction.ZERO, 0)
        RenderCounts.reset()
        // Inside the visible part of bar 1: no scroll, so nothing of the grid may compose again.
        for (k in 0 until 24) {
            val cell = k % 6
            poll(f, 0, Fraction.of(cell.toLong(), 16), k + 1)
            assertEquals(0 to cell, playhead())
        }
        val c = RenderCounts.snapshot()
        for (name in listOf("WorkspaceScreen", "DrumScreen", "DrumGrid", "DrumGrid.bar")) assertEquals("G3 '$name' recomposed by polls: $c", 0, RenderCounts.of(name))
        // Between grid positions the column is the cell that started last.
        poll(f, 0, Fraction.of(5, 32), 100)
        assertEquals(0 to 2, playhead())
    }

    @Test fun g4_theViewFollowsAndASwipePausesTheFollow() {
        val f = start(*Array(6) { TimeSignature(4, 4) })
        grid("1/16")
        var k = 0
        for (bar in 0..3) for (cell in 0 until 16) poll(f, bar, Fraction.of(cell.toLong(), 16), k++)
        assertEquals(3 to 15, playhead())
        val gridBox = compose.onNodeWithTag("drum-grid").fetchSemanticsNode().boundsInRoot
        val cell = compose.onNodeWithTag("drum-cell-4-16-${f.kick}").fetchSemanticsNode()
        val x = cell.positionInRoot.x
        assertTrue("G4 the sounding cell is visible: $x in $gridBox", x >= gridBox.left && x + cell.size.width <= gridBox.right + 1f)
        assertFalse(f.vm.view.value.followSuspended)
        compose.onNodeWithTag("drum-grid").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertTrue("G4 a swipe pauses the follow", f.vm.view.value.followSuspended)
        compose.onNodeWithTag("drum-follow-resume").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertFalse(f.vm.view.value.followSuspended)
    }
}
