package dev.riffworkbench.data.local

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * QA 20 (Editor-Robustheit): crash consistency of the autosave for the edits added since cycle 14 — retune (6/7/8 strings,
 * drop tunings, both policies, with/without allowDrop), the signature/tempo sheet, long-press range operations (copy/paste,
 * delete, clear, duplicate, transpose) and undo/redo. Each successful edit is saved like WorkspaceViewModel.persist does
 * (expected revision = last saved one). For every save the process is "killed" at each single write point (open-for-write,
 * rename, delete; crash recorder of Qa14ModelBasedStorageTest, JDK 17 SecurityManager hook); the crash image must reopen
 * with exactly the previous saved revision or the new one (never unreadable, never older), the library must not report
 * an issue, and the retried save from the reopened state must succeed and reopen as the new revision.
 *
 * Defaults: 6 seeded sequences × 12 edits (≈ 550 crash images, ~8 s). Campaign: -Dqa20s.n=200 [-Dqa20s.seed=…].
 */
class Qa20AutosaveCrashTest {
    @get:Rule val temp = TemporaryFolder()

    private class Edits(seed: Long, prefix: String) {
        private var n = 0
        val ids = IdSource { EntityId("$prefix${n++}") }
        val r = Random(seed)
        val e: EditorSession = EditorSession(ProjectFactory(ids).create("QA20 Autosave"), ids)
        var clipboard: BarClipboard? = null
        init {
            repeat(3) { e.dispatch(EditorCommand.AddMeasure(TimeSignature())) }
            for (m in e.state.value.project.measures) for (k in 0 until 4) {
                e.select(e.state.value.selection.copy(measureId = m.id, offset = Fraction.of(k.toLong(), 4), string = r.nextInt(6)))
                e.dispatch(EditorCommand.PutNote(fret = r.nextInt(0, 13), duration = WrittenDuration(4)))
            }
        }
        val p get() = e.state.value.project
        private val sel get() = e.state.value.selection
        /** One editor action as the UI would trigger it; returns a label. */
        fun step(): String {
            val ms = p.measures
            return when (r.nextInt(10)) {
                0, 1 -> { val t = p.tracks.first { it.id == sel.trackId }
                    val preset = TuningPresets.all.filter { it.kind == t.instrument.kind }.ifEmpty { TuningPresets.all }.random(r)
                    val policy = RetuningPolicy.values().random(r); val drop = r.nextBoolean()
                    "retune ${preset.name} $policy drop=$drop: " + e.dispatch(EditorCommand.SetInstrument(t.id, t.instrument.copy(tuning = preset.tuning, capoByString = List(preset.tuning.size) { 0 }), policy, drop)) }
                2 -> { val m = ms.random(r); val sig = listOf(TimeSignature(3, 4), TimeSignature(5, 4), TimeSignature(7, 8), TimeSignature(4, 4), TimeSignature(12, 8))[r.nextInt(5)]
                    "signature ${sig.numerator}/${sig.denominator}: " + e.dispatch(EditorCommand.SetSignature(m.id, sig)) }
                3 -> { val i = r.nextInt(ms.size); "tempo: " + e.dispatch(if (i == 0) EditorCommand.SetTempo(listOf(90.0, 180.0)[r.nextInt(2)]) else EditorCommand.SetTempoAt(ms[i].id, 132.0)) }
                4 -> { val a = r.nextInt(ms.size); val g = BarRangeGesture()
                    e.select(g.begin(sel, ms[a].id)); g.extend(sel, ms[(a + r.nextInt(-2, 3)).coerceIn(ms.indices)].id)?.let { e.select(it) }; g.end()
                    clipboard = BarClipboard.copy(p, sel.measureRange(p), if (r.nextBoolean()) listOf(sel.trackId) else p.tracks.map { it.id })
                    "range ${sel.measureRange(p).size} bars copied" }
                5 -> { val c = clipboard ?: return "paste: empty"; e.select(sel.copy(measureId = ms.random(r).id, offset = Fraction.ZERO, rangeEnd = null))
                    "paste: " + e.dispatch(EditorCommand.PasteBars(c, sel.measureId, if (c.tracks.size > 1) p.tracks.first().id else sel.trackId)) }
                6 -> when (r.nextInt(4)) {
                    0 -> "delete range: " + e.dispatch(EditorCommand.DeleteMeasures(sel.measureRange(p)))
                    1 -> "clear range: " + e.dispatch(EditorCommand.ClearBars(sel.measureRange(p), sel.trackId))
                    2 -> "duplicate range: " + e.dispatch(EditorCommand.DuplicateMeasures(sel.measureRange(p)))
                    else -> "transpose range: " + e.dispatch(EditorCommand.Transpose(listOf(1, -1, 12, -12)[r.nextInt(4)], EditScope.MEASURES))
                }
                7 -> "insert bar: " + e.dispatch(EditorCommand.InsertMeasures(r.nextInt(ms.size + 1)))
                8 -> "undo: " + e.undo()
                else -> "redo: " + e.redo()
            }
        }
    }

    private fun clean(p: Project) = p.copy(diagnostics = p.diagnostics.filterNot { it.code == "recovered" })

    @Test fun everyCrashPointWhileAutosavingNewEditsKeepsTheLastOrTheNewRevision() = runBlocking {
        val count = System.getProperty("qa20s.n")?.toInt() ?: 6
        val base = System.getProperty("qa20s.seed")?.toLong() ?: 20_200L
        Qa14ModelBasedStorageTest.Recorder.install()
        var images = 0; var saves = 0
        val failures = mutableListOf<String>()
        for (k in 0 until count) {
            val seed = base + k
            val trace = mutableListOf<String>()
            try {
                val edits = Edits(seed, "q20s$k-")
                val root = temp.newFolder("live$k")
                val repo = FileProjectRepository(root)
                var saved = edits.p
                assertTrue(repo.save(saved, null) is Outcome.Success)
                repeat(12) { step ->
                    trace += "#$step ${edits.step()}"
                    val current = edits.p
                    if (current.revision == saved.revision) return@repeat
                    saves++
                    // Crash at every write point of this save (k-th mutating file call), on a copy of the live library.
                    var point = 1
                    while (true) {
                        val work = temp.newFolder("work$k-$step-$point"); Qa14ModelBasedStorageTest.Recorder.restore(work, Qa14ModelBasedStorageTest.Recorder.image(root))
                        val rec = Qa14ModelBasedStorageTest.Recorder.arm(work, point)
                        val result = try { FileProjectRepository(work).save(current, saved.revision) } finally { Qa14ModelBasedStorageTest.Recorder.disarm(rec) }
                        assertTrue("save failed without a crash: $result", result is Outcome.Success)
                        val image = rec.image ?: break                                   // the save needed fewer writes: done
                        images++
                        val crashed = temp.newFolder("crash$k-$step-$point"); Qa14ModelBasedStorageTest.Recorder.restore(crashed, image)
                        // Every other image also loses the tail of the temp file being written (no fsync before the kill).
                        if (point % 2 == 0) crashed.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.forEach { f -> f.writeBytes(f.readBytes().copyOf(f.length().toInt() / 2)) }
                        val reopened = FileProjectRepository(crashed)
                        assertTrue(reopened.refresh() is Outcome.Success)
                        assertEquals("crash at write $point of revision ${current.revision}: library issues", emptyList<String>(), reopened.issues.value.map { it.message })
                        val opened = reopened.open(current.id)
                        assertTrue("crash at write $point of revision ${current.revision}: project unreadable: $opened", opened is Outcome.Success)
                        val project = clean((opened as Outcome.Success).value)
                        assertTrue("crash at write $point: reopened revision ${project.revision}, expected ${saved.revision} or ${current.revision}", project == clean(saved) || project == clean(current))
                        // The app retries with the reopened state as the expected revision (next autosave after restart).
                        assertTrue("retry after the crash", reopened.save(current, project.revision).let { it is Outcome.Success || project == clean(current) })
                        assertEquals(clean(current), clean((FileProjectRepository(crashed).open(current.id) as Outcome.Success).value))
                        crashed.deleteRecursively(); work.deleteRecursively()
                        point++
                    }
                    assertTrue(repo.save(current, saved.revision) is Outcome.Success)
                    saved = current
                }
                assertEquals(clean(saved), clean((FileProjectRepository(root).open(saved.id) as Outcome.Success).value))
            } catch (t: Throwable) { failures += "seed=$seed: $t\n    " + trace.joinToString("\n    ") }
        }
        println("QA20S $count sequences, $saves saves, $images crash images, ${failures.size} failing")
        failures.forEach { println("QA20S FAIL $it") }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
