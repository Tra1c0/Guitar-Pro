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
 * Zyklus 30 „Drum-Raster ruckelt beim Scrollen und Spielen“ — measurement and acceptance (Robolectric, CI only; the same
 * body runs locally as a Compose-Desktop variant, where the frame times in the QA journal come from).
 *
 * Piece: 64 bars 4/4, drums (11 kit rows) with a rock pattern (hi-hat 1/8, kick 1+3, snare 2+4), guitar quarters.
 *
 *  P1  Layout nodes per bar (reflection on the LayoutNode tree; printed, -1 where the internal API differs).
 *  P2  Scroll over the piece in single-frame touch steps at 1/16 and 1/32: every bar composes at most once when it comes
 *      into view (no recomposition of visible bars), frame times printed (median / p95 / max).
 *  P3  Playback at 120 BPM with transport polls at 30 Hz (every second 60-Hz frame), following through 4 bars: polls inside
 *      the visible part recompose nothing of the grid (0 per poll); entering bars compose once; frame times printed.
 *
 * Timing on Robolectric is not meaningful and never asserted — only the composition counts are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w891dp-h411dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa30DrumGridPerfUiTest {
    @get:Rule val compose = createComposeRule()

    private var n = 0
    private val ids = IdSource { EntityId("q30p-${n++}") }
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
    @After fun tearDown() { RenderCounts.enabled = false; RenderCounts.reset(); store.clear() }

    private fun eighth(at: Fraction, vararg midi: Int) = Beat(ids.next(), at, WrittenDuration(8), midi.map { Note(ids.next(), Pitch(it)) })

    /** 64 bars 4/4 at 120 BPM: drums with a rock pattern, the guitar in quarters (string 0, fret 3). */
    private fun piece(bars: Int = 64): Project {
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
        })
    }

    private fun start(): Fixture {
        val project = piece()
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
    private fun visibleBars(): List<Int> = compose.onAllNodes(SemanticsMatcher("drum bar") { it.config.getOrNull(SemanticsProperties.TestTag)?.matches(Regex("drum-bar-\\d+")) == true })
        .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("drum-bar-").toInt() }.sorted()

    /** LayoutNodes below [node] (internal API through reflection; -1 where it is not available). */
    private fun layoutNodes(node: SemanticsNode): Pair<Int, Int> = runCatching {
        val root: Any = node.layoutInfo
        val getter = root.javaClass.methods.first { it.name.startsWith("getChildren") && it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
        var nodes = 0; var modifiers = 0
        fun walk(x: Any) {
            nodes++; modifiers += (x as androidx.compose.ui.layout.LayoutInfo).getModifierInfo().size
            (getter.invoke(x) as List<*>).forEach { walk(it!!) }
        }
        walk(root)
        nodes to modifiers
    }.getOrDefault(-1 to -1)

    private fun stats(name: String, nanos: List<Long>): String {
        if (nanos.isEmpty()) return "$name: no frames"
        val ms = nanos.map { it / 1e6 }.sorted()
        fun q(p: Double) = ms[((ms.size - 1) * p).toInt()]
        return "$name: frames=${ms.size} median=%.2f ms p95=%.2f ms max=%.2f ms total=%.0f ms".format(java.util.Locale.ROOT, q(0.5), q(0.95), ms.last(), ms.sum())
    }

    @Test fun p1_layoutNodesPerBar() {
        start()
        for (label in listOf("1/16", "1/32")) {
            grid(label)
            val (nodes, modifiers) = layoutNodes(compose.onNodeWithTag("drum-bar-1").fetchSemanticsNode())
            println("QA30 P1 grid $label: bar 1 = $nodes layout nodes, $modifiers modifiers; visible bars ${visibleBars()}")
        }
    }

    private fun scrollThrough(label: String, steps: Int, stepPx: Float) {
        grid(label)
        RenderCounts.enabled = true
        RenderCounts.reset()
        val before = visibleBars()
        compose.mainClock.autoAdvance = false
        val times = ArrayList<Long>()
        val bounds = compose.onNodeWithTag("drum-grid").fetchSemanticsNode().boundsInRoot
        // Over the cell rows (the top strip — guitar lane, header, ruler — positions the cursor since Zyklus 30).
        compose.onNodeWithTag("drum-grid").performTouchInput { down(Offset(width * 0.8f, visibleSize.height - 8f)); moveBy(Offset(-viewConfiguration.touchSlop - 1f, 0f)) }
        compose.mainClock.advanceTimeByFrame()
        var seen = before.toSet()
        for (k in 0 until steps) {
            val t0 = System.nanoTime()
            compose.onNodeWithTag("drum-grid").performTouchInput { moveBy(Offset(-stepPx, 0f)) }
            compose.mainClock.advanceTimeByFrame()
            times += System.nanoTime() - t0
            if (k % 10 == 9) seen = seen + visibleBars()
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("drum-grid").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        seen = seen + visibleBars()
        val composed = RenderCounts.of("DrumGrid.bar")
        println("QA30 P2 scroll $label (${bounds.width.toInt()} px viewport, $steps × ${stepPx.toInt()} px): bars seen ${seen.size} (up to ${seen.max()}), DrumGrid.bar compositions $composed, cells ${RenderCounts.of("DrumGrid.cells")}; " + stats("frames", times.drop(5)))
        assertTrue("P2 $label: the grid moved (${seen.max()})", seen.max() > before.max())
        // Each bar composes once when it comes into view (plus one per bar of slack for a reused item); visible bars never again.
        assertTrue("P2 $label: $composed bar compositions for ${seen.size} bars", composed <= 2 * seen.size)
        for (name in listOf("WorkspaceScreen", "DrumScreen", "DrumGrid")) assertEquals("P2 $label '$name'", 0, RenderCounts.of(name))
        RenderCounts.enabled = false
    }

    @Test fun p2_scroll16() { start(); scrollThrough("1/16", 240, 24f) }
    @Test fun p2_scroll32() { start(); scrollThrough("1/32", 240, 24f) }

    @Test fun p3_playbackWithPolls() {
        val f = start()
        grid("1/16")
        val measures = project(f).measures
        RenderCounts.enabled = true
        fun poll(seconds: Double, k: Int) {
            // 120 BPM: one whole note = 2 s.
            val whole = seconds / 2.0
            val bar = whole.toInt().coerceAtMost(measures.lastIndex)
            val offset = Fraction.of(kotlin.math.floor((whole - bar) * 3840).toLong(), 3840)
            f.transport.state.value = f.transport.state.value.copy(status = TransportStatus.PLAYING,
                position = PlaybackOccurrence(ScorePosition(measures[bar].id, offset), 0, bar), sample = SamplePosition((seconds * 48_000).toLong() + k, 48_000))
        }
        poll(0.0, 0)
        compose.waitForIdle()
        RenderCounts.reset()
        compose.mainClock.autoAdvance = false
        val times = ArrayList<Long>()
        var quietPolls = 0
        var quietBars = -1
        // 8 s = 4 bars at 30 Hz polls and 60 Hz frames.
        for (frame in 1..480) {
            val t0 = System.nanoTime()
            if (frame % 2 == 0) poll(frame / 60.0, frame)
            compose.mainClock.advanceTimeByFrame()
            times += System.nanoTime() - t0
            if (frame == 60) { quietPolls = 30; quietBars = RenderCounts.of("DrumGrid.bar") + RenderCounts.of("DrumGrid.cells") }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val c = RenderCounts.snapshot()
        println("QA30 P3 playback 1/16, 240 polls over 4 bars: compositions $c; first $quietPolls polls (inside bar 1): $quietBars; " + stats("frames", times.drop(5)))
        assertEquals("P3 polls inside the visible part recompose no bar/cell: $c", 0, quietBars)
        for (name in listOf("WorkspaceScreen", "DrumScreen", "DrumGrid")) assertEquals("P3 '$name' recomposed by polls: $c", 0, RenderCounts.of(name))
        // Followed into bar 4: at most the bars that came into view composed (each once, one of slack each).
        assertTrue("P3 bar compositions while following: $c", RenderCounts.of("DrumGrid.bar") <= 8)
    }
}
