package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.adapter.audio.EngineTransport
import dev.riffworkbench.adapter.audio.RealtimeHost
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 23 / Daten, Auswahl, Raster — Gerätebefunde des Nutzers (P0 „Effekte oder Notenwerte werden random auf andere
 * Noten umgeschrieben“, P1 „Bereich aktiviert/entmarkiert zufällig Bereiche“, P1 „alles über 16tel effektiv nicht
 * nutzbar“). Fixture: Standardprojekt + 3 Takte (vier 4/4-Takte), in jedem Takt eine Achtel (Bund 3, Saite 0) auf Schlag 1.
 *
 * Regeln (siehe QA-Journal Zyklus 23):
 *  A1 Ein sichtbarer Bereich (rangeEnd ≠ null) ist das Ziel von Artikulationen/Markierungen/Velocity: sie treffen alle
 *     Noten der Spur und Stimme im Bereich, nicht eine verdeckte Cursor-Note.
 *  A2 Notenwert/Triole mit Bereich ändern nur den Eingabewert (Raster), nie einen Beat.
 *  A3 Eingabe/Löschen/Beat-Befehle treffen die sichtbare Cursor-Position und heben den Bereich auf.
 *  A4 Bereich-Schalter aus ⇒ rangeEnd = null und keine laufende Geste; Schalter an ⇒ der Cursor-Takt ist sofort als
 *     Bereich sichtbar; Stimmen-/Spurwahl wirkt auch im Bereichsmodus.
 *  R1 Schritte (Pfeile, Auto-Advance) landen nie innerhalb eines Beats, sondern am Beat-Anfang; vorwärts nie über einen Beat
 *     hinweg.
 *  M  Modellbasiert: jeder Befehl ändert nur die adressierten Takte, Undo stellt exakt her, die Auswahl ist konsistent.
 *
 * JVM über den Lifecycle-Shim des QA-Harness geprüft; mit dem echten androidx-Lifecycle UNVERIFIED (CI).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa23SelectionViewModelTest {
    private var n = 0
    private val ids = IdSource { EntityId("q23vm-${n++}") }
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
    private fun harness(notes: Boolean = true, block: TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 5)
        val session = EditorSession(ProjectFactory(ids).create("QA23 VM"), ids)
        repeat(3) { session.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        if (notes) session.state.value.project.measures.forEach { m ->
            session.select(session.state.value.selection.copy(measureId = m.id, offset = Fraction.ZERO, string = 0))
            session.dispatch(EditorCommand.PutNote(fret = 3, duration = WrittenDuration(8)))
        }
        val repo = Repository(session.state.value.project.copy(revision = 0))
        val vm = WorkspaceViewModel(repo.stored.id, repo, Snapshots(), exchange, Settings(), transport, ids, WorkspaceServices(), background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); await("project open") { vm.state.value.editor != null && vm.layout.value != null }; block(Fixture(vm, repo)) }
        finally { transport.shutdown(); store.clear(); Dispatchers.resetMain() }
    }
    private fun editor(f: Fixture) = f.vm.state.value.editor!!
    private fun bar(f: Fixture, i: Int): EntityId = editor(f).project.measures[i - 1].id
    private fun index(f: Fixture, id: EntityId?) = editor(f).project.measures.indexOfFirst { it.id == id } + 1
    private fun guitar(p: Project) = p.tracks.first()
    private fun beats(f: Fixture, barNo: Int, voice: Int = 0) = guitar(editor(f).project).staves.first().bars[barNo - 1].voices.first { it.index == voice }.beats
    private fun range(f: Fixture): List<Int> = editor(f).let { e -> e.selection.measureRange(e.project).map { index(f, it) } }
    private val pm = Articulation.Mark(NoteMark.PALM_MUTE)

    // ---------------------------------------------------------------- P0: edits on a hidden cursor note
    /**
     * Gerätebefund P0, Weg 1: Bereich-Schalter an, Tippen auf Takt 3 (der Bereich wird 1–3 angezeigt), dann P.M.: vorher
     * bekam nur die verdeckte Cursor-Note in Takt 1 den Effekt (der Tipp bewegt im Bereichsmodus den Cursor nicht).
     */
    @Test fun p0_rangeModeTapThenAnEffectHitsTheVisibleRangeNotTheHiddenCursorNote() = harness { f ->
        f.vm.setRangeMode(true)
        f.vm.tap(bar(f, 3), Fraction.ZERO, 0); settle(f.vm)
        assertEquals(listOf(1, 2, 3), range(f))
        f.vm.toggleMark(NoteMark.PALM_MUTE); settle(f.vm)
        for (b in 1..3) assertTrue("Takt $b im Bereich hat P.M.", pm in beats(f, b).single().notes.single().articulations)
        assertFalse("Takt 4 außerhalb", pm in beats(f, 4).single().notes.single().articulations)
        f.vm.toggleMark(NoteMark.PALM_MUTE); settle(f.vm)
        for (b in 1..4) assertFalse("zweites Tippen nimmt P.M. im ganzen Bereich wieder weg (Takt $b)", pm in beats(f, b).single().notes.single().articulations)
        f.vm.articulation(Articulation.Harmonic(HarmonicKind.NATURAL)); settle(f.vm)
        assertEquals(listOf(true, true, true, false), (1..4).map { b -> beats(f, b).single().notes.single().articulations.any { it is Articulation.Harmonic } })
        f.vm.undo(); settle(f.vm)
        assertTrue("ein Undo-Schritt für den ganzen Bereich", (1..4).none { b -> beats(f, b).single().notes.single().articulations.any { it is Articulation.Harmonic } })
    }

    /**
     * Gerätebefund P0, Weg 2: langer Druck auf Takt 3 (der Cursor springt dort auf Schlag 1), dann eine Notenwert-Taste:
     * vorher wurde die Achtel in Takt 3 zur Viertel und alle späteren Beats rückten nach (unsichtbar „umgeschrieben“).
     */
    @Test fun p0_aDurationKeyWithALongPressRangeOnlySetsTheEntryValue() = harness { f ->
        f.vm.beginRange(bar(f, 3)); f.vm.extendRange(bar(f, 4)); f.vm.endRange(); settle(f.vm)
        val before = editor(f).project
        f.vm.setDuration(4); settle(f.vm)
        assertEquals("Eingabewert = Viertel", WrittenDuration(4), f.vm.input.value.duration)
        assertEquals("kein Beat geändert", before.tracks, editor(f).project.tracks)
        f.vm.setTuplet(TupletRatio(3, 2)); settle(f.vm)
        assertEquals("Triole ebenso nur Eingabewert", before.tracks, editor(f).project.tracks)
        assertEquals(listOf(3, 4), range(f))
    }

    /** A3: Eingabe mit sichtbarem Bereich schreibt an den sichtbaren Cursor und hebt den Bereich auf (auch mit Schalter). */
    @Test fun a3_entryWithARangeWritesAtTheCursorAndClearsTheRange() = harness { f ->
        f.vm.setRangeMode(true)
        f.vm.tap(bar(f, 3), Fraction.ZERO, 0); settle(f.vm)
        f.vm.enterFret(7); settle(f.vm)
        assertEquals("Takt 1, Schlag 1 (Cursor)", 7, beats(f, 1).single().notes.single().fret)
        assertNull("Bereich aufgehoben", editor(f).selection.rangeEnd)
        assertEquals(listOf(3, 3, 3), (2..4).map { b -> beats(f, b).single().notes.single().fret })
    }

    // ---------------------------------------------------------------- P1: switch, gesture and selection state
    /** A4: Schalter aus während einer laufenden Geste (zweiter Finger): vorher zog die nicht beendete Geste später den Bereich. */
    @Test fun p1_switchingRangeModeOffEndsARunningGesture() = harness { f ->
        f.vm.beginRange(bar(f, 2))
        f.vm.setRangeMode(true); f.vm.setRangeMode(false); settle(f.vm)
        assertNull(editor(f).selection.rangeEnd)
        assertFalse("keine Geste mehr", f.vm.extendRange(bar(f, 4)))
        assertNull("kein Bereich durch die verwaiste Geste", editor(f).selection.rangeEnd)
    }

    /** A4: Stimmenwahl (Stimmen-Menü, Drum-Raster) im Bereichsmodus: vorher wurde sie verschluckt (select = Bereichsende). */
    @Test fun p1_voiceAndTrackChoiceWorkInRangeMode() = harness { f ->
        f.vm.setRangeMode(true)
        f.vm.tap(bar(f, 2), Fraction.ZERO, 0); settle(f.vm)
        f.vm.select(editor(f).selection.copy(voice = 1)); settle(f.vm)
        assertEquals("Stimme 2", 1, editor(f).selection.voice)
        assertEquals("Bereich bleibt", listOf(1, 2), range(f))
        val drums = editor(f).project.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }
        f.vm.select(editor(f).selection.copy(trackId = drums.id, string = 0)); settle(f.vm)
        assertEquals(drums.id, editor(f).selection.trackId)
    }

    /** A4: Schalter an zeigt den Cursor-Takt sofort als Bereich (der Modus ist auf der Partitur sichtbar). */
    @Test fun a4_rangeModeOnShowsTheCursorBarAsRange() = harness { f ->
        f.vm.tap(bar(f, 2), Fraction.ZERO, 0); settle(f.vm)
        f.vm.setRangeMode(true); settle(f.vm)
        assertEquals(bar(f, 2), editor(f).selection.rangeEnd)
        f.vm.setRangeMode(false); settle(f.vm)
        assertNull(editor(f).selection.rangeEnd)
        f.vm.tap(bar(f, 3), Fraction.ZERO, 0); settle(f.vm)
        assertEquals("Tippen ohne Schalter setzt den Cursor", 3, index(f, editor(f).selection.measureId))
    }

    // ---------------------------------------------------------------- P1: finer than 1/16
    /**
     * Gerätebefund „über 16tel nicht nutzbar“: Viertel auf Schlag 1, Cursor auf Schlag 2, Raster 1/32, ← : vorher stand
     * der Cursor bei 7/32 mitten in der Viertel; jede Eingabe dort scheiterte („Beats derselben Stimme überlappen“).
     */
    @Test fun p1_stepBackOnAFineGridLandsOnTheBeatStartNotInsideIt() = harness(notes = false) { f ->
        f.vm.setDuration(4); f.vm.enterFret(5); settle(f.vm)            // quarter at 0, cursor 1/4
        assertEquals(Fraction.of(1, 4), editor(f).selection.offset)
        f.vm.setGrid(WrittenDuration(32)); settle(f.vm)
        f.vm.move(-1); settle(f.vm)
        assertEquals("← von 1/4 mit 1/32-Raster: Anfang der Viertel", Fraction.ZERO, editor(f).selection.offset)
        f.vm.move(1); f.vm.enterFret(6); settle(f.vm)
        f.vm.enterFret(7); settle(f.vm)
        assertEquals(listOf(Fraction.ZERO to 5, Fraction.of(1, 4) to 6, Fraction.of(9, 32) to 7), beats(f, 1).map { it.offset to it.notes.single().fret })
        assertEquals(WrittenDuration(32), beats(f, 1)[1].duration)
    }

    /** R1 vorwärts: von einer leeren Position mit grobem Raster nie über den nächsten Beat hinweg. */
    @Test fun r1_stepForwardStopsAtTheNextBeat() = harness(notes = false) { f ->
        f.vm.setDuration(32); f.vm.enterFret(5); settle(f.vm)           // 1/32 at 0, cursor 1/32
        f.vm.select(editor(f).selection.copy(offset = Fraction.of(1, 8))); f.vm.setDuration(8); f.vm.enterFret(6); settle(f.vm)   // 1/8 at 1/8
        f.vm.select(editor(f).selection.copy(offset = Fraction.of(1, 32))); settle(f.vm)
        f.vm.setGrid(WrittenDuration(4)); settle(f.vm)
        f.vm.move(1); settle(f.vm)
        assertEquals("→ hält am Beat bei 1/8", Fraction.of(1, 8), editor(f).selection.offset)
    }

    // ---------------------------------------------------------------- M: model-based addressing check
    @Test fun m_everyStepChangesOnlyWhatItAddresses() {
        val count = System.getProperty("qa23vm.n")?.toInt() ?: 6
        val len = System.getProperty("qa23vm.len")?.toInt() ?: 60
        val base = System.getProperty("qa23vm.seed")?.toLong() ?: 23_000L
        val failures = mutableListOf<String>()
        for (k in 0 until count) {
            val seed = base + k
            val log = mutableListOf<String>()
            try { harness { f -> sequence(f, Random(seed), len, log) } }
            catch (t: Throwable) { failures += "seed=$seed: $t\n    " + log.takeLast(10).joinToString("\n    ") }
        }
        println("QA23VM ran $count sequences × $len steps from seed $base, ${failures.size} failing")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** Notes of the guitar track per bar index, for diffs outside the addressed bars. */
    private fun barsOf(p: Project, track: EntityId) = p.tracks.first { it.id == track }.staves.first().bars

    private fun TestScope.sequence(f: Fixture, r: Random, len: Int, log: MutableList<String>) {
        fun check(cond: Boolean, msg: () -> String) { if (!cond) throw AssertionError("step ${log.size}: ${msg()}") }
        val grids = listOf(WrittenDuration(4), WrittenDuration(8), WrittenDuration(16), WrittenDuration(32), WrittenDuration(64), WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2))), WrittenDuration(16, tuplets = listOf(TupletRatio(3, 2))))
        for (step in 0 until len) {
            val e = editor(f); val sel = e.selection; val p = e.project; val ms = p.measures
            val rangeBars = if (sel.rangeEnd != null) sel.measureRange(p).toSet() else null
            val cursorBar = sel.measureId
            val rangeMode = f.vm.input.value.rangeMode
            // Expected addressed bars of an edit: the visible range for effects, the cursor bar for entries.
            var addressed: Set<EntityId>? = null
            var undoable = false
            when (r.nextInt(100)) {
                in 0..14 -> { val bi = r.nextInt(ms.size); val beatsHere = barsOf(p, sel.trackId)[bi].voices.first { it.index == sel.voice }.beats
                    val off = (beatsHere.map { it.offset } + Fraction.of(r.nextLong(0, 8), 8)).random(r).let { o -> if (o < ms[bi].signature.length()) o else Fraction.ZERO }
                    log += "tap(bar ${bi + 1}, $off) rangeMode=$rangeMode"
                    val beforeSel = sel
                    f.vm.tap(ms[bi].id, off, r.nextInt(6)); settle(f.vm)
                    val now = editor(f).selection
                    if (rangeMode) check(now.measureId == beforeSel.measureId && now.offset == beforeSel.offset && now.rangeEnd == ms[bi].id) { "range-mode tap: $now" }
                    else { val expected = beatsHere.firstOrNull { it.offset < off && off < it.offset + it.duration.length() }?.offset ?: off   // inside a beat: its start
                        check(now.measureId == ms[bi].id && now.offset == expected && now.rangeEnd == null) { "tap: $now" } } }
                in 15..24 -> { val bi = r.nextInt(ms.size); val to = (bi + r.nextInt(-2, 3)).coerceIn(ms.indices)
                    log += "longPress(bar ${bi + 1}) drag to ${to + 1}"
                    f.vm.beginRange(ms[bi].id); f.vm.extendRange(ms[to].id); f.vm.endRange(); settle(f.vm)
                    check(editor(f).selection.measureId == ms[bi].id && editor(f).selection.rangeEnd == ms[to].id) { "gesture: ${editor(f).selection}" } }
                in 25..29 -> { log += "setRangeMode(${!rangeMode})"; f.vm.setRangeMode(!rangeMode); settle(f.vm)
                    if (rangeMode) check(editor(f).selection.rangeEnd == null && !f.vm.extendRange(ms.first().id)) { "switch off left a range or gesture" } }
                in 30..39 -> { val kind = listOf(NoteMark.PALM_MUTE, NoteMark.ACCENT, NoteMark.LET_RING, NoteMark.STACCATO).random(r)
                    log += "toggleMark($kind) range=${rangeBars?.map { index(f, it) }}"
                    addressed = rangeBars ?: setOf(cursorBar); undoable = true; f.vm.toggleMark(kind) }
                in 40..44 -> { log += "articulation(harmonic) range=${rangeBars?.map { index(f, it) }}"; addressed = rangeBars ?: setOf(cursorBar); undoable = true
                    f.vm.articulation(Articulation.Harmonic(HarmonicKind.NATURAL)) }
                in 45..52 -> { val g = grids.random(r); log += "setDuration/grid($g) range=${rangeBars?.map { index(f, it) }}"
                    if (rangeBars != null) addressed = emptySet() else addressed = setOf(cursorBar)
                    undoable = rangeBars == null
                    if (g.tuplets.isEmpty()) f.vm.setDuration(g.denominator) else f.vm.setTuplet(g.tuplets.first())
                    settle(f.vm)
                    if (r.nextBoolean()) { log += "  setGrid($g)"; f.vm.setGrid(g) } }
                in 53..64 -> { val fret = r.nextInt(0, 13); log += "enterFret($fret) at bar ${index(f, cursorBar)} @${sel.offset} s${sel.string}"
                    addressed = setOf(cursorBar); undoable = true; f.vm.enterFret(fret) }
                in 65..69 -> { log += "rest()"; addressed = setOf(cursorBar); undoable = true; f.vm.rest() }
                in 70..79 -> { val d = if (r.nextInt(3) == 0) -1 else 1; log += "move($d) from bar ${index(f, cursorBar)} @${sel.offset}"; f.vm.move(d) }
                in 80..83 -> { val d = if (r.nextBoolean()) 1 else -1; log += "moveString($d)"; f.vm.moveString(d) }
                in 84..86 -> { val v = r.nextInt(2); log += "selectVoice($v)"; f.vm.select(sel.copy(voice = v)); settle(f.vm)
                    check(editor(f).selection.voice == v) { "voice choice swallowed: ${editor(f).selection}" } }
                in 87..90 -> { log += "backspace()"; addressed = setOf(cursorBar); undoable = true; f.vm.backspace() }
                in 91..95 -> { log += "undo()"; f.vm.undo() }
                else -> { log += "clearBars() range=${sel.measureRange(p).map { index(f, it) }}"; addressed = sel.measureRange(p).toSet(); undoable = true; f.vm.clearBars() }
            }
            settle(f.vm)
            val now = editor(f); val q = now.project
            log[log.lastIndex] = log.last() + " -> bar ${index(f, now.selection.measureId)} @${now.selection.offset} s${now.selection.string} v${now.selection.voice} range=${now.selection.rangeEnd?.let { index(f, it) }}"
            // Selection valid and never inside a beat of the edited voice.
            check(q.measures.any { it.id == now.selection.measureId } && (now.selection.rangeEnd == null || q.measures.any { it.id == now.selection.rangeEnd })) { "selection invalid" }
            val voiceBeats = barsOf(q, now.selection.trackId).first { it.measureId == now.selection.measureId }.voices.first { it.index == now.selection.voice }.beats
            check(voiceBeats.none { b -> b.offset < now.selection.offset && now.selection.offset < b.offset + b.duration.length() }) { "cursor ${now.selection.offset} inside a beat $voiceBeats" }
            check(ScoreValidator.validate(q).none { it.severity == Severity.ERROR }) { "project invalid" }
            // Addressing: no bar outside the addressed ones changed (bars added by auto-advance are new and not compared).
            val a = addressed
            if (a != null && q.revision != p.revision) {
                for (t in p.tracks) {
                    val old = barsOf(p, t.id); val new = barsOf(q, t.id)
                    for (bar in old) { val nb = new.firstOrNull { it.id == bar.id } ?: continue
                        if (t.id == sel.trackId && bar.measureId in a) continue
                        check(nb == bar) { "bar ${index(f, bar.measureId)} of track ${t.name} changed although the step addressed ${a.map { index(f, it) }}" } }
                }
                if (undoable) {
                    f.vm.undo(); settle(f.vm)
                    check(editor(f).project.tracks == p.tracks) { "undo did not restore the score exactly" }
                    f.vm.redo(); settle(f.vm)
                    check(editor(f).project.tracks == q.tracks) { "redo did not restore the edit" }
                }
            }
        }
    }
}
