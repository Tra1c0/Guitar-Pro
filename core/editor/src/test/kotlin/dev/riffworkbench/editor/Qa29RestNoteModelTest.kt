package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zyklus 29 („Es gibt immer noch Probleme mit Pausen und Noten“): model-based randomised test of the normal editor's note
 * and rest entry, as the editor drives it — tap (snapped to the covering beat start, Zyklus 23), fret entry with
 * auto-advance (WorkspaceViewModel.move: a bar is added after the last one in the same undo step), rest, delete, duration
 * shorter/longer (with dots), triplet on/off, delete beat, undo/redo.
 *
 * Invariants after every step:
 *  M1 Success leaves no validator ERROR; Failure changes nothing.
 *  M2 No overlapping beats; a bar of the edited voice that was exactly full stays exactly full (except DeleteBeat, whose
 *     contract is "later beats move earlier" — it fills the freed end with rests since Zyklus 29, see M2b).
 *  M3 Shorter: every other beat keeps its onset (all bars); a tied origin releases its tie and its continuations become
 *      rests in place (Zyklus 30, user decision). Longer: in the bar, notes behind the beat move by exactly
 *     max(0, delta − free room right behind); no note is lost (ids kept), bars before the edited one are unchanged.
 *  M4 PutNote on an existing beat keeps its duration (Zyklus 25 placeholders); on an empty position the beat has the entry
 *     duration, or its tied first part when it reaches over the barline (Zyklus 29: no longer refused, see M7).
 *  M7 (printed) refusals per command and message: entries are no longer refused for lack of room.
 *  M5 Undo restores exactly the state before the step (project modulo revision), redo exactly the state after it.
 *  M6 Other tracks and other voices are unchanged (bars appended at the end aside).
 *
 * Defaults: 40 sequences × 60 steps from seed 29_000. Campaign: -Dqa29.n=… -Dqa29.len=… -Dqa29.seed=…
 */
class Qa29RestNoteModelTest {
    sealed class Op {
        data class Tap(val seed: Long) : Op()
        data class Put(val seed: Long) : Op()
        data class Rest(val seed: Long) : Op()
        object Delete : Op() { override fun toString() = "Delete" }
        data class Duration(val seed: Long) : Op()
        data class Tuplet(val on: Boolean) : Op()
        object DeleteBeat : Op() { override fun toString() = "DeleteBeat" }
        object Undo : Op() { override fun toString() = "Undo" }
        object Redo : Op() { override fun toString() = "Redo" }
    }

    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    /** Refusals per command and message over the run (printed: what users would see as notices). */
    private val outcomes = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val entries = listOf(WrittenDuration(4), WrittenDuration(8), WrittenDuration(16), WrittenDuration(8, 1), WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2))), WrittenDuration(2), WrittenDuration(32))
    private val values = listOf(WrittenDuration(1), WrittenDuration(2), WrittenDuration(4), WrittenDuration(8), WrittenDuration(16), WrittenDuration(32), WrittenDuration(2, 1), WrittenDuration(4, 1), WrittenDuration(8, 1))

    inner class Runner(seed: Long) {
        private var n = 0
        val ids = IdSource { EntityId("m29-${n++}") }
        val e: EditorSession
        val log = mutableListOf<String>()
        var violation: String? = null
        private var entry = WrittenDuration(8)
        /** Duration a Duration/Tuplet step asked for (the beat itself may be split at the barline). */
        private var requested: WrittenDuration? = null
        private val undo = ArrayDeque<Project>()
        private val redo = ArrayDeque<Project>()
        private val p get() = e.state.value.project
        private val sel get() = e.state.value.selection

        init {
            val r = Random(seed)
            val s = EditorSession(ProjectFactory(ids).create("M29", withBand = true), ids)
            repeat(r.nextInt(0, 3)) { s.dispatch(EditorCommand.AddMeasure(if (r.nextInt(4) == 0) TimeSignature(listOf(3, 7, 6)[r.nextInt(3)], listOf(4, 8)[r.nextInt(2)]) else TimeSignature())) }
            e = EditorSession(s.state.value.project.copy(revision = 0), ids)
        }

        private fun same(a: Project, b: Project) = a.copy(revision = 0) == b.copy(revision = 0)
        private fun cap(m: Measure) = m.pickupLength ?: m.signature.length()
        private fun voice(pr: Project, bar: Int, track: EntityId = sel.trackId, v: Int = sel.voice) =
            pr.tracks.first { it.id == track }.staves[0].bars.firstOrNull { it.measureId == pr.measures[bar].id }?.voices?.firstOrNull { it.index == v }?.beats.orEmpty()
        private fun full(pr: Project, bar: Int): Boolean {
            val beats = voice(pr, bar); if (beats.isEmpty()) return false
            var end = Fraction.ZERO
            for (b in beats.sortedBy { it.offset }) { if (b.offset != end) return false; end = b.offset + b.duration.length() }
            return end == cap(pr.measures[bar])
        }
        private fun fail(msg: String) { if (violation == null) violation = "step ${log.size}: $msg\n" + log.joinToString("\n") }

        private fun beatsHere() = voice(p, p.measures.indexOfFirst { it.id == sel.measureId })
        private fun selectedBeat() = beatsHere().firstOrNull { it.offset == sel.offset }

        /** WorkspaceViewModel.move(1, overTies = true) after a fret: steps over the tied continuations of the entered beat (Zyklus 29). */
        private fun advanceOverTies(sameUndoStep: Boolean) {
            var left = selectedBeat()?.notes.orEmpty()
            advance(sameUndoStep)
            repeat(32) {
                val here = selectedBeat()?.notes.orEmpty()
                if (left.isEmpty() || here.isEmpty() || !here.all { n -> left.any { o -> o.links.any { it.kind == LinkKind.TIE && it.target == n.id } } }) return
                left = here
                advance(sameUndoStep)
            }
        }
        /** WorkspaceViewModel.move(1) after an entry (auto-advance). */
        private fun advance(sameUndoStep: Boolean) {
            val index = p.measures.indexOfFirst { it.id == sel.measureId }
            val step = selectedBeat()?.duration?.length() ?: entry.length()
            val offset = CursorSteps.forward(beatsHere(), sel.offset, step)
            val length = cap(p.measures[index])
            when {
                offset >= length && index < p.measures.lastIndex -> e.select(sel.copy(measureId = p.measures[index + 1].id, offset = Fraction.ZERO))
                offset >= length -> { e.dispatch(EditorCommand.AddMeasure(p.measures[index].signature), sameUndoStep); e.select(sel.copy(measureId = p.measures[index + 1].id, offset = Fraction.ZERO)) }
                else -> e.select(sel.copy(offset = offset))
            }
        }

        fun step(op: Op) {
            log += op.toString()
            val before = p
            val beforeSel = sel
            val bar = before.measures.indexOfFirst { it.id == beforeSel.measureId }
            val fullBefore = before.measures.indices.filter { full(before, it) }.toSet()
            val target = selectedBeat()
            var result: Outcome<Unit>? = null
            var checkUndo = true
            when (op) {
                is Op.Tap -> { checkUndo = false
                    val r = Random(op.seed)
                    val mi = r.nextInt(p.measures.size); val m = p.measures[mi]
                    val grid = listOf(f(1, 4), f(1, 8), f(1, 16), f(1, 12))[r.nextInt(4)]
                    val count = (cap(m) * Fraction.of(grid.denominator, grid.numerator)).let { (it.numerator + it.denominator - 1) / it.denominator }.toInt()
                    var offset = grid * f(r.nextInt(count).toLong(), 1)
                    CursorSteps.covering(voice(p, mi, v = sel.voice), offset)?.let { offset = it.offset }
                    e.select(sel.copy(measureId = m.id, offset = offset, string = r.nextInt(6), rangeEnd = null))
                    entry = entries[r.nextInt(entries.size)]
                }
                is Op.Put -> { val r = Random(op.seed)
                    result = e.dispatch(EditorCommand.PutNote(fret = r.nextInt(0, 13), duration = entry))
                    if (result is Outcome.Success) {
                        val now = selectedBeat()
                        if (target != null && now?.duration != target.duration) fail("M4 PutNote changed an existing beat's duration ${target.duration} -> ${now?.duration}")
                        // Zyklus 29: the entry value, or its first part when it reaches over the barline (tied on in the next bar).
                        if (target == null && now?.duration != entry && !(now != null && now.duration.length() < entry.length() && p.measures.size >= before.measures.size && now.notes.all { n -> n.links.any { it.kind == LinkKind.TIE } })) fail("M4 PutNote on an empty position: ${now?.duration} instead of $entry")
                        if (r.nextInt(5) != 0) { val changed = p.revision != before.revision; if (changed) check(p, before, bar, fullBefore, op); advanceOverTies(changed); afterAdvance(before); return }
                    }
                }
                is Op.Rest -> { val r = Random(op.seed)
                    result = e.dispatch(EditorCommand.PutRest(entry))
                    if (result is Outcome.Success && r.nextInt(5) != 0) { val changed = p.revision != before.revision; if (changed) check(p, before, bar, fullBefore, op); advance(changed); afterAdvance(before); return }
                }
                Op.Delete -> result = e.dispatch(EditorCommand.Delete)
                is Op.Duration -> { if (target == null) return
                    val d = values[Random(op.seed).nextInt(values.size)].let { it.copy(tuplets = target.duration.tuplets) }
                    entry = d; requested = d
                    result = e.dispatch(EditorCommand.SetDuration(d))
                }
                is Op.Tuplet -> { if (target == null) return; requested = target.duration.copy(tuplets = listOfNotNull(if (op.on) TupletRatio(3, 2) else null)); result = e.dispatch(EditorCommand.SetTuplet(if (op.on) TupletRatio(3, 2) else null)) }
                Op.DeleteBeat -> result = e.dispatch(EditorCommand.DeleteBeat)
                Op.Undo -> { checkUndo = false
                    val ok = e.undo()
                    if (ok is Outcome.Success && undo.isNotEmpty()) { val expected = undo.removeLast(); redo.addLast(before); if (!same(p, expected)) fail("M5 undo is not exact") }
                    return
                }
                Op.Redo -> { checkUndo = false
                    val ok = e.redo()
                    if (ok is Outcome.Success && redo.isNotEmpty()) { val expected = redo.removeLast(); undo.addLast(before); if (!same(p, expected)) fail("M5 redo is not exact") }
                    return
                }
            }
            if (result is Outcome.Failure) { outcomes.merge("${op::class.simpleName}: ${(result as Outcome.Failure).message}", 1, Int::plus); if (p !== before && !same(p, before)) fail("M1 failure changed the score: ${(result as Outcome.Failure).message}"); return }
            if (result != null && p.revision != before.revision) {
                check(p, before, bar, fullBefore, op, target)
                if (checkUndo) { undo.addLast(before); redo.clear() }
            }
        }

        private fun afterAdvance(before: Project) {
            // Auto-advance may append a bar in the same undo step: the model's undo entry stays `before`.
            if (ScoreValidator.validate(p).any { it.severity == Severity.ERROR }) fail("M1 after auto-advance")
            if (p.revision != before.revision) { undo.addLast(before); redo.clear() }
        }

        private fun check(after: Project, before: Project, bar: Int, fullBefore: Set<Int>, op: Op, target: Beat? = null) {
            val errors = ScoreValidator.validate(after).filter { it.severity == Severity.ERROR }
            if (errors.isNotEmpty()) fail("M1 $op: $errors")
            // M6: other tracks and voices unchanged (bars compared by measure id, appended bars aside).
            for (t in before.tracks) for ((bi, b) in t.staves[0].bars.withIndex()) {
                val a = after.tracks.first { it.id == t.id }.staves[0].bars.firstOrNull { it.measureId == b.measureId } ?: run { fail("M6 bar removed"); return }
                for (v in b.voices) if (t.id != sel.trackId || v.index != sel.voice) if (a.voices.first { it.index == v.index } != v) fail("M6 $op changed track ${t.name} voice ${v.index} bar ${bi + 1}")
            }
            // M2: bars that were exactly full stay so.
            for (i in fullBefore) if (i < after.measures.size && !full(after, i) && op != Op.DeleteBeat) fail("M2 $op: bar ${i + 1} no longer exactly full (edited bar ${bar + 1}, ${target?.offset} ${target?.duration} -> $requested): edited bar before ${voice(before, bar).map { Triple(it.offset, it.duration, it.notes.size) }} after ${voice(after, bar).map { Triple(it.offset, it.duration, it.notes.size) }}; before ${voice(before, i).map { Triple(it.offset, it.duration.length(), it.notes.size) }} after ${voice(after, i).map { Triple(it.offset, it.duration.length(), it.notes.size) }}")
            if (op == Op.DeleteBeat && bar in fullBefore && !full(after, bar)) fail("M2b DeleteBeat left bar ${bar + 1} with a gap: ${voice(after, bar).map { it.offset to it.duration.length() }}")
            if (op is Op.Duration || op is Op.Tuplet) {
                target ?: return
                val now = voice(after, bar).first { it.id == target.id }
                val oldEnd = target.offset + target.duration.length()
                val delta = (requested ?: now.duration).length() + target.duration.length() * Fraction.of(-1)
                val beforeBeats = voice(before, bar)
                if (delta < Fraction.ZERO) {
                    // M3 shorter: all other beats keep their onsets.
                    for (b in beforeBeats) if (b.id != target.id && voice(after, bar).none { it.id == b.id && it.offset == b.offset }) fail("M3 shorter moved beat at ${b.offset}: ${target.duration} -> ${now.duration} before ${beforeBeats.map { it.offset to it.duration }} after ${voice(after, bar).map { it.offset to it.duration }}")
                    // Zyklus 30 (user decision): a tied origin releases its tie; its continuation chain becomes rests in place.
                    val continued = HashSet<EntityId>()
                    val track = before.tracks.first { it.id == sel.trackId }
                    var frontier = target.notes.flatMap { n -> n.links.filter { it.kind == LinkKind.TIE }.map { it.target } }
                    while (frontier.isNotEmpty()) { continued += frontier; frontier = track.staves.flatMap { st -> st.bars.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { bt -> bt.notes.filter { it.id in frontier }.flatMap { n -> n.links.filter { it.kind == LinkKind.TIE }.map { it.target } } } } } }.filter { it !in continued } }
                    fun released(v: List<Beat>) = v.map { b -> if (b.notes.none { it.id in continued }) b else b.notes.filter { it.id !in continued }.let { kept -> b.copy(notes = kept, explicitRest = kept.isEmpty() || b.explicitRest, strum = if (kept.isEmpty()) null else b.strum) } }
                    for (i in after.measures.indices) if (i != bar && i < before.measures.size && voice(after, i) != released(voice(before, i))) fail("M3 shorter changed bar ${i + 1}")
                } else if (delta > Fraction.ZERO) {
                    val wall = beforeBeats.filter { it.offset >= oldEnd && it.notes.isNotEmpty() }.minByOrNull { it.offset }?.offset
                    val free = (wall ?: cap(before.measures[bar])) + oldEnd * Fraction.of(-1)
                    val shift = if (delta > free) delta + free * Fraction.of(-1) else Fraction.ZERO
                    for (b in beforeBeats) if (b.notes.isNotEmpty() && b.offset >= oldEnd) {
                        val moved = voice(after, bar).firstOrNull { it.id == b.id } ?: continue   // went to the next bar
                        if (moved.offset != b.offset + shift) fail("M3 longer: note at ${b.offset} now ${moved.offset}, expected shift $shift")
                    }
                    for (i in 0 until bar) if (voice(after, i) != voice(before, i)) fail("M3 longer changed an earlier bar")
                    val beforeIds = (0 until before.measures.size).flatMap { i -> voice(before, i).flatMap { it.notes.map { n -> n.id } } }.toSet()
                    val afterIds = (0 until after.measures.size).flatMap { i -> voice(after, i).flatMap { it.notes.map { n -> n.id } } }.toSet()
                    if (!afterIds.containsAll(beforeIds)) fail("M3 longer lost notes ${beforeIds - afterIds}")
                }
            }
        }
    }

    private fun ops(seed: Long, len: Int): List<Op> {
        val r = Random(seed)
        return List(len) {
            val x = r.nextLong()
            when (r.nextInt(100)) {
                in 0..17 -> Op.Tap(x); in 18..42 -> Op.Put(x); in 43..52 -> Op.Rest(x); in 53..57 -> Op.Delete
                in 58..74 -> Op.Duration(x); in 75..79 -> Op.Tuplet(r.nextBoolean()); in 80..84 -> Op.DeleteBeat
                in 85..93 -> Op.Undo; else -> Op.Redo
            }
        }
    }

    @Test fun m_restsAndNotesKeepTheirInvariants() {
        val count = System.getProperty("qa29.n")?.toInt() ?: 40
        val len = System.getProperty("qa29.len")?.toInt() ?: 60
        val first = System.getProperty("qa29.seed")?.toLong() ?: 29_000L
        val failures = mutableListOf<String>()
        for (seed in first until first + count) {
            val runner = Runner(seed)
            for (op in ops(seed, len)) { try { runner.step(op) } catch (t: Throwable) { runner.log += "threw $t"; failures += "seed $seed: $op threw $t\n" + runner.log.joinToString("\n"); break }; if (runner.violation != null) break }
            runner.violation?.let { failures += "seed $seed: $it" }
            if (failures.size >= 3) break
        }
        println("QA29 refusals: " + outcomes.entries.sortedByDescending { it.value }.joinToString { "${it.key} ×${it.value}" })
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }
}
