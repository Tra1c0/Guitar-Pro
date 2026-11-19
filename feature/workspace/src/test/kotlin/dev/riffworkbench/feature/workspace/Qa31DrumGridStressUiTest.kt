package dev.riffworkbench.feature.workspace

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
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
 * Zyklus 31 „Er stürzt bei zu viel Drum-Grid ab“ (P0, device, release v1151): crash reproduction and memory bound of the
 * continuous drum grid. Robolectric, CI only; the same body runs locally as a Compose-Desktop variant (there with 1000 bars
 * and 2 minutes of playback, `-Dqa31.bars`, `-Dqa31.playSeconds`; CI uses smaller defaults to keep the run short).
 *
 *  S1  Paused (or playing between two polls) in a late cell of a fine grid, the user switches to a coarser grid: the
 *      sounding column (bar, cell) of the old grid must not index the new bar's cells. Before the fix the draw phase threw
 *      ArrayIndexOutOfBoundsException (a draw exception kills the app immediately).
 *  S2  The bars change while the column stands (time signature change of the sounding bar to fewer cells): same rule.
 *  S3  Large dense piece (11 kit rows, every cell set, meter changes): fling over all bars, playback with following and
 *      30 Hz polls, grid switches, cell taps, positioning drags and recreating the screen (configuration change) — twice.
 *      Heap after GC stays below start + 60 MB and does not grow from the first to the second round; the per-bar draw
 *      data alive after GC stays bounded by the visible bars (no cache over the whole piece).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w891dp-h411dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa31DrumGridStressUiTest {
    @get:Rule val compose = createComposeRule()

    private var n = 0
    private val ids = IdSource { EntityId("q31-${n++}") }
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
    private class Fixture(val vm: WorkspaceViewModel, val transport: DrivenTransport)

    private val store = ViewModelStore()
    private val generation = mutableIntStateOf(0)
    @After fun tearDown() { RenderCounts.enabled = false; RenderCounts.reset(); LiveObjects.enabled = false; LiveObjects.reset(); store.clear() }

    /**
     * [bars] bars at 120 BPM; every 8th bar from bar 5 on switches to 3/4 and back (meter changes); drums: every 1/32 cell
     * of the first [denseBars] bars with [rows] kit elements (the whole kit), the guitar in quarters in the first [guitarBars].
     */
    private fun piece(bars: Int, denseBars: Int = bars, rows: Int = 11, guitarBars: Int = bars): Project {
        val base = ProjectFactory(ids).create("QA31")
        val measures = (0 until bars).map { i -> Measure(ids.next(), if (i >= 4 && i % 8 == 4) TimeSignature(3, 4) else TimeSignature()) }
        val f = ProjectFactory(ids)
        return base.copy(revision = 0, measures = measures, sections = emptyList(), tempo = listOf(TempoChange(ScorePosition(measures[0].id), 120.0)), tracks = base.tracks.map { t ->
            val fresh = f.track(t.instrument.kind, measures, t.name, t.instrument)
            when (t.instrument.kind) {
                InstrumentKind.PERCUSSION -> fresh.copy(staves = fresh.staves.map { st -> st.copy(bars = st.bars.mapIndexed { bi, b ->
                    if (bi >= denseBars) b else b.copy(voices = b.voices.map { v -> if (v.index != 0) v else v.copy(beats = (0 until measures[bi].signature.numerator * 8).map { k ->
                        Beat(ids.next(), Fraction.of(k.toLong(), 32), WrittenDuration(32), t.instrument.drumKit.take(rows).map { Note(ids.next(), Pitch(it.midiNote)) })
                    }) })
                }) })
                InstrumentKind.GUITAR -> fresh.copy(staves = fresh.staves.map { st -> st.copy(bars = st.bars.mapIndexed { bi, b ->
                    if (bi >= guitarBars) b else b.copy(voices = b.voices.map { v -> if (v.index != 0) v else v.copy(beats = (0 until measures[bi].signature.numerator).map { k ->
                        Beat(ids.next(), Fraction.of(k.toLong(), 4), WrittenDuration(4), listOf(Note(ids.next(), Pitch(t.instrument.tuning[0] + 3), string = 0, fret = 3)))
                    }) })
                }) })
                else -> fresh
            }
        })
    }

    private fun start(project: Project): Fixture {
        val repo = Repository(project)
        val transport = DrivenTransport()
        val vm = WorkspaceViewModel(project.id, repo, Snapshots(), exchange, Settings(), transport, ids)
        store.put("vm", vm)
        compose.setContent { RiffTheme { key(generation.intValue) { WorkspaceScreen(vm, onBack = {}, onExport = {}, onSettings = {}, initialPage = WorkspacePage.DRUMS) } } }
        compose.waitUntil(60000) { vm.state.value.editor != null && compose.onAllNodesWithTag("drum-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return Fixture(vm, transport)
    }
    private fun project(f: Fixture) = f.vm.state.value.editor!!.project
    private fun grid(label: String) { compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }
    private fun visibleBars(): List<Int> = compose.onAllNodes(SemanticsMatcher("drum bar") { it.config.getOrNull(SemanticsProperties.TestTag)?.matches(Regex("drum-bar-\\d+")) == true })
        .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("drum-bar-").toInt() }.sorted()
    private fun position(f: Fixture, status: TransportStatus, bar: Int, offset: Fraction, frame: Long) {
        f.transport.state.value = f.transport.state.value.copy(status = status,
            position = PlaybackOccurrence(ScorePosition(project(f).measures[bar].id, offset), 0, bar), sample = SamplePosition(frame, 48_000))
    }
    /**
     * Draws the grid now, as the next frame on the device would (captureToImage records the draw of every visible bar).
     * Only "capture not supported here" is tolerated; a draw exception (the crash) propagates.
     */
    private fun forceDraw() {
        try { compose.onNodeWithTag("drum-grid").captureToImage() }
        catch (e: UnsupportedOperationException) { println("QA31 capture not supported: $e") }
        catch (e: IllegalStateException) { println("QA31 capture not supported: $e") }
    }
    private fun usedMb(): Double {
        repeat(5) { System.gc(); System.runFinalization(); Thread.sleep(30) }
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) / 1e6
    }

    @Test fun s1_coarserGridWhilePausedOrBetweenPollsInALateCell() {
        val f = start(piece(8, denseBars = 2, rows = 3))
        for (status in listOf(TransportStatus.PAUSED, TransportStatus.PLAYING)) {
            grid("1/32")
            // Bar 1, cell 30 of 32 (offset 29/32 lies in the last but two cell); the playhead stands there (pause or before the next poll).
            position(f, TransportStatus.PLAYING, 0, Fraction.of(29, 32), 48_000L)
            compose.waitForIdle()
            position(f, status, 0, Fraction.of(29, 32), 48_000L)
            compose.waitForIdle()
            assertEquals("column of the 1/32 grid", 0 to 29, compose.onNodeWithTag("drum-playhead").fetchSemanticsNode().config[DrumPlayheadKey])
            // The bar of the column in view from its start (following had scrolled to the late cell; a coarser bar is narrower).
            compose.onNodeWithTag("drum-grid").performScrollToIndex(0)
            compose.waitForIdle()
            // Coarser grids: 16, 8 cells — the old column index 29 must never reach the draw of the new cells.
            for (label in listOf("1/16", "1/8", "1/8 T", "1/16 T", "1/8")) {
                grid(label)
                // Force a draw of the grid now (as the next frame on the device would): the old column must not reach it.
                forceDraw()
                val column = compose.onNodeWithTag("drum-playhead").fetchSemanticsNode().config[DrumPlayheadKey]
                val cells = DrumGrid.bars(project(f), DrumGrid.step(f.vm.input.value.duration))[0].cells.size
                assertTrue("S1 $status $label: column $column within the $cells cells of the new grid", column == null || column.second < cells)
            }
        }
    }

    @Test fun s2_meterChangeOfTheSoundingBarWhilePaused() {
        val f = start(piece(8, denseBars = 2, rows = 3, guitarBars = 2))
        grid("1/16")
        // Bar 3 (empty) plays its last cell; pause there.
        position(f, TransportStatus.PLAYING, 2, Fraction.of(15, 16), 48_000L)
        compose.waitForIdle()
        position(f, TransportStatus.PAUSED, 2, Fraction.of(15, 16), 48_000L)
        compose.waitForIdle()
        assertEquals(2 to 15, compose.onNodeWithTag("drum-playhead").fetchSemanticsNode().config[DrumPlayheadKey])
        compose.onNodeWithTag("drum-grid").performScrollToIndex(2)
        compose.waitForIdle()
        // Bar 3 becomes 2/4 (8 cells at 1/16) while the column stands on cell 15.
        assertTrue(f.vm.command(EditorCommand.SetSignature(project(f).measures[2].id, TimeSignature(2, 4))) is Outcome.Success)
        compose.waitForIdle()
        forceDraw()
        val column = compose.onNodeWithTag("drum-playhead").fetchSemanticsNode().config[DrumPlayheadKey]
        assertTrue("S2: column $column within 8 cells", column == null || column.second < 8)
    }

    @Test fun s3_largeDensePieceStaysWithinAMemoryBound() {
        val bars = System.getProperty("qa31.bars")?.toIntOrNull() ?: 80
        val playSeconds = System.getProperty("qa31.playSeconds")?.toIntOrNull() ?: 10
        LiveObjects.enabled = true
        val base = usedMb()
        val f = start(piece(bars))
        grid("1/32")
        val startMb = usedMb()
        println("QA31 S3 $bars bars, 11 rows, every 1/32 cell set: heap %.1f MB before the piece, %.1f MB with the grid open; live %s".format(java.util.Locale.ROOT, base, startMb, LiveObjects.counts()))
        val rounds = ArrayList<Double>()
        repeat(2) { round ->
            val t0 = System.nanoTime()
            // 1. Fling over all bars: every bar comes into view (scroll to each item), plus real touch flings back.
            for (i in 0 until bars) { compose.onNodeWithTag("drum-grid").performScrollToIndex(i); if (i % 50 == 0) compose.waitForIdle() }
            repeat(3) { compose.onNodeWithTag("drum-grid").performTouchInput { swipeRight(startX = width * 0.1f, endX = width * 0.9f, durationMillis = 60) }; compose.waitForIdle() }
            compose.onNodeWithTag("drum-grid").performScrollToIndex(0)
            compose.waitForIdle()
            val afterFling = usedMb()
            // 2. Playback with following: polls at 30 Hz, frames at 60 Hz, 120 BPM (one 4/4 bar = 2 s).
            compose.mainClock.autoAdvance = false
            val ms = project(f).measures
            val starts = DoubleArray(ms.size + 1).also { s -> ms.forEachIndexed { i, m -> s[i + 1] = s[i] + m.signature.length().toDouble() * 2.0 } }
            for (frame in 1..playSeconds * 60) {
                if (frame % 2 == 0) {
                    val seconds = frame / 60.0
                    var bar = 0
                    while (bar + 1 < ms.size && starts[bar + 1] <= seconds) bar++
                    val offset = Fraction.of(kotlin.math.floor((seconds - starts[bar]) / 2.0 * 3840).toLong().coerceAtLeast(0), 3840)
                    position(f, TransportStatus.PLAYING, bar, offset, (seconds * 48_000).toLong() + frame)
                }
                compose.mainClock.advanceTimeByFrame()
            }
            // Pause in a late cell, then grid switches (the S1 situation inside the big piece).
            f.transport.state.value = f.transport.state.value.copy(status = TransportStatus.PAUSED)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val afterPlay = usedMb()
            // 3. Grid switches, several times.
            for (label in listOf("1/16", "1/16 T", "1/8", "1/32", "1/16", "1/32")) grid(label)
            // 4. Cell taps in the visible bars (toggle off and on again: content unchanged at the end).
            val visible = visibleBars().first()
            for (cell in listOf(1, 5, 9)) repeat(2) { compose.onNodeWithTag("drum-cell-$visible-$cell-36").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }
            // 5. Positioning drags in the strip.
            repeat(3) {
                compose.onNodeWithTag("drum-strip-$visible").performTouchInput { down(Offset(40f, 6f)); moveBy(Offset(120f, 0f)); moveBy(Offset(120f, 0f)); up() }
                compose.waitForIdle()
            }
            // 6. Configuration change: the screen is recreated (the ViewModel stays), three times.
            repeat(3) { generation.intValue++; compose.waitForIdle() }
            compose.waitUntil(60000) { compose.onAllNodesWithTag("drum-grid").fetchSemanticsNodes().isNotEmpty() }
            val end = usedMb()
            rounds += end
            println("QA31 S3 round ${round + 1} (%.1f s): heap after fling %.1f MB, after %d s playback %.1f MB, after grid/taps/drags/recreate %.1f MB; live %s".format(java.util.Locale.ROOT,
                (System.nanoTime() - t0) / 1e9, afterFling, playSeconds, afterPlay, end, LiveObjects.counts()))
            assertTrue("S3 round ${round + 1}: heap %.1f MB ≤ start %.1f + 60 MB".format(java.util.Locale.ROOT, end, startMb), end <= startMb + 60.0)
        }
        assertTrue("S3: no growth between the rounds (%.1f → %.1f MB)".format(java.util.Locale.ROOT, rounds[0], rounds[1]), rounds[1] <= rounds[0] + 15.0)
        // Per-bar draw data alive after GC: only what the lazy row holds (visible + prefetched + reuse pool), never the piece.
        usedMb()
        val paints = LiveObjects.count("DrumBarPaint")
        assertTrue("S3: $paints bar paints alive for ${visibleBars().size} visible bars", paints <= 16)
        assertTrue("S3: one grid state alive (${LiveObjects.counts()})", LiveObjects.count("DrumLive") <= 1)
    }
}
