package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.adapter.audio.EngineTransport
import dev.riffworkbench.adapter.audio.RealtimeHost
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 19 / Bericht 1 §5: Bereichsauswahl per langem Drücken, wie der Editor sie über
 * [WorkspaceViewModel.beginRange]/[WorkspaceViewModel.extendRange]/[WorkspaceViewModel.endRange] treibt (ScoreCanvas
 * ruft sie aus der Geste). Fixture wie [Qa18StartPositionViewModelTest]: echter EngineTransport, RealtimeHost von Hand
 * getaktet, vier 4/4-Takte mit je einer Note.
 *
 *  G1 Anker = gedrückter Takt, Ziehen erweitert/verkleinert taktweise, Loslassen lässt den Bereich stehen.
 *  G2 extendRange meldet nur Taktwechsel (Haptik-Tick), nach endRange nichts mehr.
 *  G3 Ein einfaches Tippen hebt den Bereich auf (rangeEnd = null).
 *  G4 Der Bereich-Schalter funktioniert weiter (Tippen erweitert, Ausschalten hebt auf); die Geste geht auch bei
 *     eingeschaltetem Schalter.
 *  G5 Während PLAYING verschiebt die Geste die Wiedergabe nicht (kein Seek, Takt des Wiedergabekopfs bleibt).
 *  G6 Im PAUSED gilt die Startmarken-Regel: Anker in einem anderen Takt → nächstes Play am Anker; Anker im Cursor-Takt
 *     → Play setzt fort.
 *
 * UNVERIFIED auf der CI (feature/workspace ist nicht im lokalen JVM-Harness); lokal nur über den Lifecycle-Shim des
 * QA-Harness im Scratchpad geprüft, siehe Bericht.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa19RangeSelectionViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("q19r-${n++}") }
    private class Repository(var stored: Project) : ProjectRepository {
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> {
            if (expectedRevision != stored.revision) return Outcome.Failure(FailureCode.CONFLICT, "stale writer")
            stored = project; return Outcome.Success(Unit)
        }
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
    private class Sink : AudioSink {
        var frames = 0L
        override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> = Outcome.Success(SinkFormat(48_000, 256, 0, "fake"))
        override fun write(buffer: FloatArray, frames: Int): Int { this.frames += frames; return frames }
        override fun presentedFrame(): Long = frames
        override fun close() {}
    }
    private fun TestScope.await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) { advanceUntilIdle(); if (System.currentTimeMillis() > deadline) fail("timeout: $what"); Thread.sleep(5) }
    }
    private fun TestScope.settle(vm: WorkspaceViewModel) { await("ViewModel idle") { vm.viewModelScope.coroutineContext.job.children.count { it.isActive } <= 1 }; advanceUntilIdle() }

    private class Fixture(val vm: WorkspaceViewModel, val transport: EngineTransport, val host: RealtimeHost)
    private fun harness(block: TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 5)
        val session = EditorSession(ProjectFactory(ids).create("QA19 range"), ids)
        repeat(3) { session.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        session.state.value.project.measures.forEach { m ->
            session.select(session.state.value.selection.copy(measureId = m.id, offset = Fraction.ZERO, string = 0))
            session.dispatch(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(8)))
        }
        session.select(session.state.value.selection.copy(measureId = session.state.value.project.measures.first().id, offset = Fraction.ZERO, string = 0))
        val repo = Repository(session.state.value.project.copy(revision = 0))
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, settings, transport, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); await("project open") { vm.state.value.editor != null }; block(Fixture(vm, transport, host)) } finally { transport.shutdown(); store.clear(); Dispatchers.resetMain() }
    }
    private fun TestScope.awaitStatus(f: Fixture, s: TransportStatus) = await("transport $s (is ${f.transport.state.value.status})") { f.transport.state.value.status == s }
    private fun bar(f: Fixture, i: Int): EntityId = f.vm.state.value.editor!!.project.measures[i - 1].id
    private fun index(f: Fixture, id: EntityId?): Int = f.vm.state.value.editor!!.project.measures.indexOfFirst { it.id == id } + 1
    private fun playheadBar(f: Fixture): Int = index(f, f.transport.state.value.position?.position?.measureId)
    private fun cursorBar(f: Fixture): Int = index(f, f.vm.state.value.editor!!.selection.measureId)
    /** Ausgewählte Takte (1-basiert), wie Taktbefehle sie sehen. */
    private fun range(f: Fixture): List<Int> = f.vm.state.value.editor!!.let { e -> e.selection.measureRange(e.project).map { index(f, it) } }
    private fun rangeEnd(f: Fixture): EntityId? = f.vm.state.value.editor!!.selection.rangeEnd

    @Test fun g1_anchorIsThePressedBarAndDraggingMovesTheRangeEndBarByBar() = harness { f ->
        f.vm.beginRange(bar(f, 2)); settle(f.vm)
        assertEquals("Anker = gedrückter Takt", 2, cursorBar(f))
        assertEquals(listOf(2), range(f))
        assertEquals("ein Takt ist ein Bereich (nicht null)", bar(f, 2), rangeEnd(f))
        f.vm.extendRange(bar(f, 3)); f.vm.extendRange(bar(f, 4)); settle(f.vm)
        assertEquals(listOf(2, 3, 4), range(f))
        f.vm.extendRange(bar(f, 3)); settle(f.vm)
        assertEquals("zurückziehen verkleinert", listOf(2, 3), range(f))
        f.vm.extendRange(bar(f, 1)); settle(f.vm)
        assertEquals("über den Anker nach links", listOf(1, 2), range(f))
        assertEquals(2, cursorBar(f))
        f.vm.endRange(); settle(f.vm)
        assertEquals("Loslassen lässt den Bereich stehen", listOf(1, 2), range(f))
        // Taktbefehle sehen den Bereich: Leeren trifft beide Takte.
        f.vm.clearBars(); settle(f.vm)
        val p = f.vm.state.value.editor!!.project
        val bars = p.tracks.first().staves.first().bars
        for (i in listOf(1, 2)) assertTrue("Takt $i geleert", bars.first { it.measureId == p.measures[i - 1].id }.voices.all { v -> v.beats.all { it.notes.isEmpty() } })
        assertTrue("Takt 3 unberührt", bars.first { it.measureId == p.measures[2].id }.voices.any { v -> v.beats.any { it.notes.isNotEmpty() } })
    }

    @Test fun g2_extendReportsOnlyBarChangesAndNothingAfterRelease() = harness { f ->
        assertFalse("ohne Geste kein Bereich", f.vm.extendRange(bar(f, 3)))
        assertNull(rangeEnd(f))
        f.vm.beginRange(bar(f, 1))
        assertFalse("selber Takt: kein Tick", f.vm.extendRange(bar(f, 1)))
        assertTrue(f.vm.extendRange(bar(f, 2)))
        assertFalse(f.vm.extendRange(bar(f, 2)))
        assertTrue(f.vm.extendRange(bar(f, 3)))
        f.vm.endRange()
        assertFalse("nach dem Loslassen", f.vm.extendRange(bar(f, 4)))
        settle(f.vm)
        assertEquals(listOf(1, 2, 3), range(f))
    }

    @Test fun g3_aPlainTapClearsTheRange() = harness { f ->
        f.vm.beginRange(bar(f, 1)); f.vm.extendRange(bar(f, 3)); f.vm.endRange(); settle(f.vm)
        assertEquals(listOf(1, 2, 3), range(f))
        f.vm.tap(bar(f, 2), Fraction.ZERO, 0); settle(f.vm)
        assertNull("Tippen hebt den Bereich auf", rangeEnd(f))
        assertEquals(listOf(2), range(f))
        assertEquals(2, cursorBar(f))
    }

    @Test fun g4_theRangeToggleStillWorksAlongsideTheGesture() = harness { f ->
        f.vm.setRangeMode(true)
        f.vm.tap(bar(f, 3), Fraction.ZERO, 0); settle(f.vm)
        assertEquals("Schalter: Tippen erweitert ab dem Cursor", listOf(1, 2, 3), range(f))
        f.vm.beginRange(bar(f, 4)); f.vm.extendRange(bar(f, 2)); f.vm.endRange(); settle(f.vm)
        assertEquals("Geste auch mit Schalter", listOf(2, 3, 4), range(f))
        assertEquals(4, cursorBar(f))
        f.vm.tap(bar(f, 1), Fraction.ZERO, 0); settle(f.vm)
        assertEquals("Schalter an: Tippen setzt das Bereichsende", listOf(1, 2, 3, 4), range(f))
        assertTrue(f.vm.input.value.rangeMode)
        f.vm.setRangeMode(false); settle(f.vm)
        assertNull("Ausschalten hebt den Bereich auf", rangeEnd(f))
    }

    @Test fun g5_theGestureNeverSeeksWhilePlaying() = harness { f ->
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        repeat(20) { f.host.renderBlock() }
        settle(f.vm)
        assertEquals(1, playheadBar(f))
        val seeks = f.host.snapshot().seeks
        val frame = f.transport.state.value.sample?.frame
        assertNotNull(frame)
        f.vm.beginRange(bar(f, 3)); settle(f.vm)
        f.vm.extendRange(bar(f, 4)); settle(f.vm)
        f.vm.endRange(); settle(f.vm)
        repeat(20) { f.host.renderBlock(); advanceUntilIdle(); Thread.sleep(2) }
        settle(f.vm)
        assertEquals(listOf(3, 4), range(f))
        assertEquals(TransportStatus.PLAYING, f.transport.state.value.status)
        assertEquals("die Auswahl postet keinen Seek", seeks, f.host.snapshot().seeks)
        assertEquals("der Wiedergabekopf bleibt in Takt 1", 1, playheadBar(f))
        val later = f.transport.state.value.sample?.frame
        assertTrue("die Wiedergabe läuft weiter vorwärts ($frame → $later)", later != null && later >= frame!!)
    }

    @Test fun g6_whilePausedTheAnchorIsTheNextStartUnlessItStaysInTheCursorBar() = harness { f ->
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        repeat(40) { f.host.renderBlock() }
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PAUSED); f.host.renderBlock()
        assertEquals(1, playheadBar(f))
        // Langer Druck im Cursor-Takt: der Cursor bewegt sich nicht → Play setzt fort.
        f.vm.beginRange(bar(f, 1)); f.vm.extendRange(bar(f, 2)); f.vm.endRange(); settle(f.vm)
        val paused = f.transport.state.value.sample?.frame
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        val resumed = f.transport.state.value.sample?.frame
        assertTrue("Fortsetzen am pausierten Frame ($paused → $resumed)", paused != null && resumed != null && kotlin.math.abs(resumed - paused) <= 512)
        repeat(20) { f.host.renderBlock() }
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PAUSED); f.host.renderBlock()
        // Langer Druck auf Takt 3: Anker = Cursor = Startmarke.
        f.vm.beginRange(bar(f, 3)); f.vm.endRange(); settle(f.vm)
        assertEquals(1, playheadBar(f))
        f.vm.togglePlay(); awaitStatus(f, TransportStatus.PLAYING)
        assertEquals("nächstes Play beginnt am Anker", 3, playheadBar(f))
    }
}
