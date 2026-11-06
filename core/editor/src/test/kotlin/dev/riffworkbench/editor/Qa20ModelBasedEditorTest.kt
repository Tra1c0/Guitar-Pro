package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 20 (Editor-Robustheit): model-based randomised test of the editor features added after cycle 14 — long-press bar
 * range ([BarRangeGesture]) and the commands the editor runs on a range (copy, cut = copy + clear, paste, delete, clear,
 * duplicate, transpose, articulation), the signature/tempo sheet (SetSignature/SetTempo/SetTempoAt exactly as the sheet
 * builds them, also on bars whose notes then no longer fit), bars inserted/deleted inside and at the edge of a range,
 * retuning (6/7/8 strings, drop tunings, bass, capo; both policies, with/without allowDrop, dry run first like the
 * ViewModel) and note drag (MoveNote as WorkspaceViewModel.moveNote drives it). Steps are UI-level operations resolved
 * against the live state, so shrinking stays meaningful.
 *
 * Invariants after every step:
 *  R1 no exception escapes select/dispatch/undo/redo/gesture (only documented Outcome.Failure);
 *  R2 Success leaves no validator ERROR; Failure changes neither project, selection, revision nor history;
 *  R3 selection points at existing track/bar, rangeEnd exists or is null, string/voice valid, offset inside the bar;
 *  R4 undo restores exactly the state before the command (project modulo revision, and the selection), redo exactly the
 *     state undo left; revision strictly increases;
 *  R5 the project JSON (data/local configuration) round-trips bit-identically (sampled);
 *  R6 bar 1 carries a tempo; tempo/sections/texts only reference existing bars;
 *  R7 gesture: begin → cursor bar = anchor = rangeEnd; extend → cursor stays on the anchor, rangeEnd = finger bar;
 *  R8 command semantics: delete removes exactly the range, clear empties the range of the track, transpose changes only
 *     notes inside the range, signature as requested, retune keeps every note unless the dry run listed it as DROP and
 *     allowDrop was set, octave shifts as listed; drag keeps id and pitch.
 *
 * Defaults: 48 sequences × 60 steps from seed 20_000 (< 10 s). Campaign: -Dqa20.n=3000 -Dqa20.len=80 [-Dqa20.seed=…]
 * [-Dqa20.shrink=false]. Failing seeds are printed with a shrunk, replayable step log.
 */
class Qa20ModelBasedEditorTest {
    sealed class Op {
        data class Tap(val seed: Long) : Op()
        data class Put(val seed: Long) : Op()
        data class Tie(val seed: Long) : Op()
        data class SwitchTrack(val seed: Long) : Op()
        data class BeginRange(val seed: Long) : Op()
        data class ExtendRange(val seed: Long) : Op()
        object EndRange : Op() { override fun toString() = "EndRange" }
        data class Copy(val seed: Long) : Op()
        data class Cut(val seed: Long) : Op()
        data class Paste(val seed: Long) : Op()
        object DeleteRange : Op() { override fun toString() = "DeleteRange" }
        object ClearRange : Op() { override fun toString() = "ClearRange" }
        object DuplicateRange : Op() { override fun toString() = "DuplicateRange" }
        data class TransposeRange(val seed: Long) : Op()
        data class ArticulationRange(val seed: Long) : Op()
        data class Signature(val seed: Long) : Op()
        data class Tempo(val seed: Long) : Op()
        data class InsertAtRange(val seed: Long) : Op()
        data class DeleteAtRange(val seed: Long) : Op()
        data class Retune(val seed: Long) : Op()
        data class Drag(val seed: Long) : Op()
        object Undo : Op() { override fun toString() = "Undo" }
        object Redo : Op() { override fun toString() = "Redo" }
    }
    /** [kind] 0: product default (ProjectFactory.create, 1×4/4 E standard) grown by [bars]; 1: QA14 random dense project. */
    data class Spec(val seed: Long, val kind: Int, val bars: Int, val density: Int, val ties: Boolean)
    data class Violation(val inv: String, val step: Int, val msg: String)

    companion object {
        /** Outcome counts per command class over the whole run (printed: coverage of the generators). */
        val stats = java.util.concurrent.ConcurrentHashMap<String, Int>()
        private val projectJson = Qa14ModelBasedEditorTest.projectJson
        fun capacity(m: Measure): Fraction = m.pickupLength ?: m.signature.length()

        fun build(spec: Spec, ids: IdSource): Project {
            if (spec.kind == 1) return Qa14ModelBasedEditorTest.build(Qa14ModelBasedEditorTest.Spec(spec.seed, spec.bars, spec.density, spec.ties), ids)
            val r = Random(spec.seed)
            val s = EditorSession(ProjectFactory(ids).create("QA20", withBand = r.nextInt(3) != 0), ids)
            repeat(spec.bars - 1) { s.dispatch(EditorCommand.AddMeasure(if (r.nextInt(4) == 0) TimeSignature(listOf(3, 5, 7, 6)[r.nextInt(4)], listOf(4, 8)[r.nextInt(2)]) else TimeSignature())) }
            // A few notes per bar on the guitar, entered like the editor does (cursor + PutNote).
            if (spec.density > 0) for (m in s.state.value.project.measures) {
                var at = Fraction.ZERO
                while (at < capacity(m) && r.nextInt(4) != 0) {
                    val d = WrittenDuration(listOf(4, 8, 8, 16)[r.nextInt(4)])
                    if (at + d.length() > capacity(m)) break
                    s.select(s.state.value.selection.copy(measureId = m.id, offset = at, string = r.nextInt(6)))
                    s.dispatch(EditorCommand.PutNote(fret = r.nextInt(0, 13), duration = d))
                    at += d.length()
                }
            }
            val p = s.state.value.project.copy(revision = 0)
            check(ScoreValidator.validate(p).none { it.severity == Severity.ERROR })
            return p
        }

        fun randomSpec(seed: Long): Spec { val r = Random(seed xor 0x20_20); return if (r.nextInt(3) == 0) Spec(r.nextLong(), 1, 2 + r.nextInt(10), 1 + r.nextInt(3), r.nextBoolean()) else Spec(r.nextLong(), 0, 1 + r.nextInt(8), r.nextInt(3), false) }

        fun randomSteps(seed: Long, len: Int): List<Op> {
            val r = Random(seed)
            return List(len) {
                val x = r.nextLong()
                when (r.nextInt(100)) {
                    in 0..12 -> Op.Tap(x); in 13..21 -> Op.Put(x); in 22..24 -> Op.Tie(x); in 25..26 -> Op.SwitchTrack(x)
                    in 27..33 -> Op.BeginRange(x); in 34..40 -> Op.ExtendRange(x); in 41..42 -> Op.EndRange
                    in 43..45 -> Op.Copy(x); in 46..48 -> Op.Cut(x); in 49..52 -> Op.Paste(x)
                    in 53..55 -> Op.DeleteRange; in 56..57 -> Op.ClearRange; in 58..59 -> Op.DuplicateRange
                    in 60..63 -> Op.TransposeRange(x); in 64..66 -> Op.ArticulationRange(x)
                    in 67..72 -> Op.Signature(x); in 73..75 -> Op.Tempo(x)
                    in 76..78 -> Op.InsertAtRange(x); in 79..80 -> Op.DeleteAtRange(x)
                    in 81..85 -> Op.Retune(x); in 86..89 -> Op.Drag(x)
                    in 90..95 -> Op.Undo; else -> Op.Redo
                }
            }
        }
    }

    class Runner(spec: Spec) {
        private var n = 0
        val ids = IdSource { EntityId("q20-${n++}") }
        val start = build(spec, ids)
        val e = EditorSession(start, ids)
        val gesture = BarRangeGesture()
        val violations = mutableListOf<Violation>()
        val log = mutableListOf<String>()
        private val undoModel = ArrayDeque<Pair<Project, Selection>>()
        private val redoModel = ArrayDeque<Pair<Project, Selection>>()
        private var clipboard: BarClipboard? = null
        private var step = 0
        private val p get() = e.state.value.project
        private val sel get() = e.state.value.selection
        private fun v(inv: String, msg: String) { violations += Violation(inv, step, msg) }
        @Suppress("UNCHECKED_CAST") private fun depth(name: String) = (EditorSession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(e) as ArrayDeque<*>).size
        private fun bar(id: EntityId?) = p.measures.indexOfFirst { it.id == id }
        private fun same(a: Project, b: Project) = a.copy(revision = 0) == b.copy(revision = 0)

        fun run(ops: List<Op>) { for ((i, op) in ops.withIndex()) { step = i; try { runOp(op) } catch (t: Throwable) { v("R1", "$op threw $t") }; if (violations.isNotEmpty()) return } }

        // ------------------------------------------------------------ steps (each mirrors what the editor UI does)
        private fun runOp(op: Op) {
            when (op) {
                is Op.Tap -> { val r = Random(op.seed); val mi = r.nextInt(p.measures.size); val m = p.measures[mi]
                    // WorkspaceViewModel.tap (range mode off): cursor to bar/column/string, range cleared.
                    val t = track(); val string = if (t.instrument.tuning.isEmpty()) 0 else r.nextInt(t.instrument.tuning.size)
                    val voice = if (r.nextInt(8) == 0) 1 - minOf(1, sel.voice) else sel.voice
                    select("tap(bar=$mi, voice=$voice, string=$string)", sel.copy(measureId = m.id, voice = voice, offset = column(r, mi), string = string, rangeEnd = null)) }
                is Op.Put -> { val r = Random(op.seed)
                    val t = track()
                    dispatch(if (t.instrument.kind == InstrumentKind.PERCUSSION) EditorCommand.ToggleDrum(listOf(36, 38, 42)[r.nextInt(3)], WrittenDuration(8))
                        else if (r.nextInt(6) == 0) EditorCommand.PutRest(WrittenDuration(listOf(4, 8, 16)[r.nextInt(3)]))
                        else EditorCommand.PutNote(fret = r.nextInt(0, 15), midiNote = 60 + r.nextInt(12), duration = WrittenDuration(listOf(4, 8, 8, 16, 2)[r.nextInt(5)]))) }
                is Op.Tie -> { val r = Random(op.seed); val t = track()
                    // Like the user: cursor right after a note on its string (same bar, or the next bar's downbeat), then 'tie'.
                    val origins = if (t.instrument.tuning.isEmpty() || r.nextInt(5) == 0) emptyList() else p.measures.indices.flatMap { mi -> beatsOf(t, mi, sel.voice).flatMap { b -> b.notes.map { Triple(mi, b, it) } } }
                    val o = origins.randomOrNull(r)
                    if (o != null) { val (mi, b, n) = o; val end = b.offset + b.duration.length()
                        val (ti, at) = if (end < capacity(p.measures[mi])) mi to end else if (mi < p.measures.lastIndex) mi + 1 to Fraction.ZERO else return
                        if (!select("tie.cursor(bar=$ti, $at, string=${n.string})", sel.copy(measureId = p.measures[ti].id, offset = at, string = n.string ?: 0, rangeEnd = null))) return }
                    dispatch(EditorCommand.ToggleTie) }
                is Op.SwitchTrack -> { val r = Random(op.seed); val t = p.tracks.random(r)
                    select("switchTrack(${p.tracks.indexOf(t)})", sel.copy(trackId = t.id, string = sel.string.coerceIn(0, maxOf(0, t.instrument.tuning.lastIndex)), rangeEnd = null)) }
                is Op.BeginRange -> { val r = Random(op.seed); val mi = r.nextInt(p.measures.size); val anchor = p.measures[mi].id
                    val next = gesture.begin(sel, anchor)
                    if (!select("beginRange(bar=$mi)", next)) { gesture.end(); return }
                    if (sel.measureId != anchor || sel.rangeEnd != anchor || sel.measureRange(p) != listOf(anchor)) v("R7", "begin: selection $sel is not exactly the anchor bar $mi") }
                is Op.ExtendRange -> { val r = Random(op.seed)
                    val anchorIdx = bar(gesture.anchor)
                    // The finger moves to a neighbouring bar mostly, sometimes far, sometimes stays.
                    val target = if (anchorIdx < 0) r.nextInt(p.measures.size) else (bar(sel.rangeEnd).takeIf { it >= 0 } ?: anchorIdx).plus(r.nextInt(-2, 3)).coerceIn(p.measures.indices)
                    val id = p.measures[target].id
                    val next = gesture.extend(sel, id)
                    log += "#$step extendRange(bar=$target) -> ${if (next == null) "no change" else "range ${bar(next.measureId)}..${bar(next.rangeEnd)}"}"
                    if (next == null) { if (gesture.active && bar(gesture.anchor) >= 0 && (sel.measureId != gesture.anchor || sel.rangeEnd != id)) v("R7", "extend returned null although the range changes"); return }
                    if (!select(null, next)) return
                    val range = sel.measureRange(p)
                    if (sel.measureId != gesture.anchor || sel.rangeEnd != id || range.first() !in listOf(gesture.anchor, id) || range.last() !in listOf(gesture.anchor, id)) v("R7", "extend: selection $sel / range $range does not span anchor..bar $target") }
                Op.EndRange -> { log += "#$step endRange() -> ${gesture.end()}" }
                is Op.Copy -> copy(Random(op.seed).nextInt(4) == 0)
                is Op.Cut -> { if (copy(Random(op.seed).nextInt(4) == 0)) clearRange() }
                is Op.Paste -> { val clip = clipboard ?: run { log += "#$step paste() -> clipboard empty"; return }
                    // WorkspaceViewModel.pasteBars: a multi-track clip goes to the first track, otherwise to the cursor track.
                    val target = if (clip.tracks.size > 1) p.tracks.first().id else sel.trackId
                    val at = bar(sel.measureId); val before = p
                    if (dispatch(EditorCommand.PasteBars(clip, sel.measureId, target)) && !same(before, p)) checkPaste(before, clip, at, target) }
                Op.DeleteRange -> { val range = sel.measureRange(p); val before = p
                    if (dispatch(EditorCommand.DeleteMeasures(range)) && !same(before, p)) {
                        if (p.measures.size != before.measures.size - range.size || p.measures.any { it.id in range }) v("R8", "DeleteMeasures($range) left ${p.measures.size} of ${before.measures.size} bars")
                        // F5: the cursor stays where the range was (the bar after it, or the new last bar), at its downbeat.
                        val expected = minOf(before.measures.indexOfFirst { it.id == range.first() }, p.measures.lastIndex)
                        if (bar(sel.measureId) != expected || sel.offset != Fraction.ZERO || sel.rangeEnd != null) v("R3", "after deleting the range the cursor is at bar ${bar(sel.measureId)} @${sel.offset} (range end ${sel.rangeEnd}), expected bar $expected @0")
                    } }
                Op.ClearRange -> clearRange()
                Op.DuplicateRange -> { val range = sel.measureRange(p); val before = p
                    if (dispatch(EditorCommand.DuplicateMeasures(range)) && !same(before, p) && p.measures.size != before.measures.size + range.size) v("R8", "DuplicateMeasures added ${p.measures.size - before.measures.size} bars for ${range.size}") }
                is Op.TransposeRange -> { val r = Random(op.seed); val semis = listOf(1, -1, 2, -2, 12, -12, 5)[r.nextInt(7)]; val before = p; val range = sel.measureRange(p)
                    if (dispatch(EditorCommand.Transpose(semis, EditScope.MEASURES)) && !same(before, p)) checkTranspose(before, range, semis) }
                is Op.ArticulationRange -> { val r = Random(op.seed)
                    val a = when (r.nextInt(6)) { 0 -> Articulation.Mark(NoteMark.PALM_MUTE); 1 -> Articulations.fullBend(); 2 -> Articulation.Harmonic(HarmonicKind.values().random(r)); 3 -> Articulation.Vibrato(70, 4.5); 4 -> Articulation.Mark(NoteMark.values().random(r)); else -> Articulation.Slide(SlideKind.SHIFT, null) }
                    dispatch(EditorCommand.ApplyArticulation(a)) }
                is Op.Signature -> signature(Random(op.seed))
                is Op.Tempo -> tempo(Random(op.seed))
                is Op.InsertAtRange -> { val r = Random(op.seed); val range = sel.measureRange(p).map { bar(it) }
                    // insertBar(after = false/true) at the cursor, or at the range edges / inside the range.
                    val at = listOf(range.first(), range.last() + 1, (range.first() + range.last() + 1) / 2, bar(sel.measureId), bar(sel.measureId) + 1, 0, p.measures.size)[r.nextInt(7)]
                    val before = p
                    if (dispatch(EditorCommand.InsertMeasures(at, if (r.nextInt(4) == 0) 2 else 1)) && !same(before, p) && sel.rangeEnd != null && before.measures.any { it.id == sel.rangeEnd } && p.measures.none { it.id == sel.rangeEnd }) v("R3", "insert dropped the range end") }
                is Op.DeleteAtRange -> { val r = Random(op.seed); val range = sel.measureRange(p)
                    val pick = listOf(range.first(), range.last(), range[range.size / 2], p.measures.first().id, p.measures.last().id)[r.nextInt(5)]
                    dispatch(EditorCommand.DeleteMeasures(listOf(pick))) }
                is Op.Retune -> retune(Random(op.seed))
                is Op.Drag -> drag(Random(op.seed))
                Op.Undo -> travel(true)
                Op.Redo -> travel(false)
            }
        }

        private fun track() = p.tracks.first { it.id == sel.trackId }
        private fun beatsOf(t: Track, mi: Int, voice: Int) = t.staves.first().bars.firstOrNull { it.measureId == p.measures[mi].id }?.voices?.firstOrNull { it.index == voice }?.beats.orEmpty()
        /** A column like the score hit-test returns: an existing beat, or a grid position inside the bar. */
        private fun column(r: Random, mi: Int): Fraction {
            val beats = beatsOf(track(), mi, sel.voice)
            if (beats.isNotEmpty() && r.nextInt(3) != 0) return beats.random(r).offset
            val grid = Fraction.of(1, listOf(4, 8, 16)[r.nextInt(3)].toLong())
            val cap = capacity(p.measures[mi])
            var o = Fraction.ZERO; val k = r.nextInt(0, 16)
            repeat(k) { if (o + grid < cap) o += grid }
            return o
        }

        private fun copy(allTracks: Boolean): Boolean {
            val range = sel.measureRange(p)
            val tracks = if (allTracks) p.tracks.map { it.id } else listOf(sel.trackId)
            log += "#$step copyBars(${range.map { bar(it) }}, allTracks=$allTracks)"
            return try { clipboard = BarClipboard.copy(p, range, tracks); true } catch (x: IllegalArgumentException) { log[log.lastIndex] += " -> ${x.message}"; false }
        }
        private fun clearRange() {
            val range = sel.measureRange(p); val before = p
            if (dispatch(EditorCommand.ClearBars(range, sel.trackId)) && !same(before, p)) {
                val bars = track().staves.flatMap { it.bars }.filter { it.measureId in range }
                if (bars.any { b -> b.voices.any { it.beats.isNotEmpty() } }) v("R8", "ClearBars left beats in the range")
                for (t in p.tracks) if (t.id != sel.trackId && t != before.tracks.firstOrNull { it.id == t.id }) v("R8", "ClearBars changed another track ${t.name}")
            }
        }

        /** The signature sheet: numerator −/+ (1..32), denominator chips 2/4/8/16, six presets; on the cursor bar or a random one. */
        private fun signature(r: Random) {
            val mi = if (r.nextBoolean()) bar(sel.measureId) else r.nextInt(p.measures.size)
            val m = p.measures[mi]; val s = m.signature
            val next = when (r.nextInt(4)) {
                0 -> if (s.numerator > 1) s.copy(numerator = s.numerator - 1) else null
                1 -> if (s.numerator < 32) s.copy(numerator = s.numerator + 1) else null
                2 -> s.copy(denominator = listOf(2, 4, 8, 16)[r.nextInt(4)])
                else -> listOf(TimeSignature(4, 4), TimeSignature(3, 4), TimeSignature(6, 8), TimeSignature(7, 8), TimeSignature(5, 4), TimeSignature(12, 8))[r.nextInt(6)]
            } ?: return
            val before = p
            if (dispatch(EditorCommand.SetSignature(m.id, next)) && !same(before, p) && p.measures.first { it.id == m.id }.signature != next) v("R8", "SetSignature did not set $next on bar $mi")
        }
        /** The sheet's BPM field (digits and '.', 6 chars) → SetTempo on bar 1, else SetTempoAt; 'remove tempo change' after bar 1. */
        private fun tempo(r: Random) {
            val mi = if (r.nextBoolean()) bar(sel.measureId) else r.nextInt(p.measures.size)
            val m = p.measures[mi]
            if (mi > 0 && r.nextInt(4) == 0) { if (p.tempo.any { it.position == ScorePosition(m.id) }) dispatch(EditorCommand.SetTempoAt(m.id, null)); return }
            val text = listOf("120", "60", "240", "999999", "0", "9", "10", "1000", "1000.5", "87.5", ".", "")[r.nextInt(12)]
            val bpm = text.toDoubleOrNull() ?: run { log += "#$step tempo('$text') -> notice 'Ungültiges Tempo'"; return }
            dispatch(if (mi == 0) EditorCommand.SetTempo(bpm) else EditorCommand.SetTempoAt(m.id, bpm))
        }

        private val tunings = TuningPresets.all
        private fun retune(r: Random) {
            val t = track()
            if (t.instrument.tuning.isEmpty()) { log += "#$step retune -> n/a (no strings)"; return }
            val presets = tunings.filter { it.kind == t.instrument.kind }.ifEmpty { tunings }
            val preset = presets.random(r)
            val capo = if (r.nextInt(5) == 0) r.nextInt(1, 6) else t.instrument.capoByString.firstOrNull() ?: 0
            val instrument = t.instrument.copy(tuning = preset.tuning, capoByString = List(preset.tuning.size) { capo })
            val policy = RetuningPolicy.values().random(r)
            val allowDrop = r.nextInt(3) == 0
            val before = p
            // The ViewModel's dry run first (retune()).
            val problems = try { Retuning.problems(before, t.id, instrument, policy) } catch (x: IllegalArgumentException) { null }
            log += "#$step retune(${preset.name}, capo $capo, $policy, allowDrop=$allowDrop) dry run: ${problems?.groupingBy { it.resolution }?.eachCount() ?: "refused"}"
            val ok = dispatch(EditorCommand.SetInstrument(t.id, instrument, policy, allowDrop))
            if (problems == null) { if (ok) v("R8", "dry run refused but SetInstrument succeeded"); return }
            val drops = problems.filter { it.resolution == RetuneResolution.DROP }
            if (!ok) { if (drops.isEmpty() || allowDrop) v("R8", "SetInstrument failed although the dry run allowed it (${problems.size} problems, allowDrop=$allowDrop): ${(violations.lastOrNull()?.msg) ?: log.last()}"); return }
            if (drops.isNotEmpty() && !allowDrop) v("R8", "SetInstrument dropped ${drops.size} notes without allowDrop")
            val after = p.tracks.first { it.id == t.id }
            if (after.instrument != instrument) v("R8", "instrument not applied")
            val old = notes(before.tracks.first { it.id == t.id }); val now = notes(after)
            val dropped = drops.map { it.noteId }.toSet()
            for ((id, n) in old) {
                val m = now[id]
                if (id in dropped) { if (m != null) v("R8", "note listed as DROP survived"); continue }
                if (m == null) { v("R8", "retune lost note $id (${MusicNames.name(n.pitch.midiNumber)}) without a DROP entry"); continue }
                val shift = when (problems.firstOrNull { it.noteId == id }?.resolution) { RetuneResolution.OCTAVE_UP -> 12; RetuneResolution.OCTAVE_DOWN -> -12; else -> 0 }
                if (policy == RetuningPolicy.KEEP_SOUNDING_PITCH && m.pitch.midiNumber != n.pitch.midiNumber + shift) v("R8", "KEEP_SOUNDING_PITCH moved ${n.pitch.midiNumber} to ${m.pitch.midiNumber} (listed shift $shift)")
            }
            if (now.size != old.size - dropped.count { it in old }) v("R8", "retune note count ${old.size} -> ${now.size}, ${dropped.size} drops")
        }
        private fun notes(t: Track): Map<EntityId, Note> = t.staves.flatMap { it.bars }.flatMap { it.voices }.flatMap { it.beats }.flatMap { it.notes }.associateBy { it.id }

        /** WorkspaceViewModel.moveNote: select the source cell, MoveNote, then select the target cell on success. */
        private fun drag(r: Random) {
            val t = track()
            if (t.instrument.tuning.isEmpty()) { log += "#$step drag -> n/a"; return }
            val sources = p.measures.indices.flatMap { mi -> beatsOf(t, mi, sel.voice).flatMap { b -> b.notes.map { Triple(mi, b, it) } } }
            val (mi, beat, note) = sources.randomOrNull(r) ?: run { log += "#$step drag -> no note"; return }
            if (!select("drag.from(bar=$mi, ${beat.offset}, string=${note.string})", sel.copy(measureId = p.measures[mi].id, offset = beat.offset, string = note.string!!, rangeEnd = null))) return
            val ti = (mi + r.nextInt(-1, 2)).coerceIn(p.measures.indices)
            val to = Selection(t.id, p.measures[ti].id, sel.voice, column(r, ti), r.nextInt(t.instrument.tuning.size))
            val before = p
            if (!dispatch(EditorCommand.MoveNote(to.measureId, to.offset, to.string, WrittenDuration(listOf(8, 16, 4)[r.nextInt(3)])))) return
            if (same(before, p)) return
            val moved = p.tracks.first { it.id == t.id }.staves.first().bars.first { it.measureId == to.measureId }.voices.first { it.index == to.voice }.beats.firstOrNull { it.offset == to.offset }?.notes?.firstOrNull { it.string == to.string }
            if (moved?.id != note.id || moved.pitch.midiNumber != note.pitch.midiNumber) v("R8", "drag did not move note ${note.id} with its pitch (found $moved)")
            select("drag.to", sel.copy(measureId = to.measureId, offset = to.offset, string = to.string))
        }

        private fun checkPaste(before: Project, clip: BarClipboard, at: Int, target: EntityId) {
            val ti = p.tracks.indexOfFirst { it.id == target }
            for ((k, ct) in clip.tracks.withIndex()) {
                val t = p.tracks.getOrNull(ti + k) ?: continue
                val bt = before.tracks.first { it.id == t.id }
                // Same string count: string/fret travel unchanged (pitch follows the target tuning); unfretted: pitch.
                if (ct.strings != t.instrument.tuning.size || ct.kind != t.instrument.kind || bt.instrument != t.instrument) continue
                if (ct.strings > 0 && ct.bars.any { b -> b.voices.any { v -> v.beats.any { bb -> bb.notes.any { (it.fret ?: 0) > t.instrument.maxFret } } } }) continue
                for ((i, src) in ct.bars.withIndex()) {
                    val dst = t.staves.first().bars[at + i]
                    fun shape(bar: Bar) = bar.voices.map { v -> v.beats.map { b -> Triple(b.offset, b.duration, b.notes.map { if (it.string != null) it.string to it.fret else null to it.pitch.midiNumber }) } }
                    val a = shape(src); val b = shape(dst)
                    if (a != b) v("R8", "pasted bar ${at + i} of track ${t.name} differs from the clipboard")
                }
            }
        }
        private fun checkTranspose(before: Project, range: List<EntityId>, semis: Int) {
            for ((bt, at) in before.tracks.zip(p.tracks)) {
                if (bt.id != sel.trackId) { if (bt != at) v("R8", "transpose changed another track"); continue }
                for ((bb, ab) in bt.staves.first().bars.zip(at.staves.first().bars)) {
                    if (bb.measureId !in range) { if (bb != ab) v("R8", "transpose changed a bar outside the range"); continue }
                    if (bt.instrument.kind == InstrumentKind.PERCUSSION) continue
                    val old = bb.voices.flatMap { it.beats }.flatMap { it.notes }.associate { it.id to it.pitch.midiNumber }
                    val new = ab.voices.flatMap { it.beats }.flatMap { it.notes }.associate { it.id to it.pitch.midiNumber }
                    if (old.keys != new.keys || old.any { (id, m) -> new[id] != m + semis }) v("R8", "transpose by $semis inside the range is not exact")
                }
            }
        }

        // ------------------------------------------------------------ session access with checks
        private fun select(label: String?, s: Selection): Boolean {
            val before = e.state.value
            if (label != null) log += "#$step $label -> bar ${bar(s.measureId)} @${s.offset} string ${s.string} range ${bar(s.rangeEnd).takeIf { s.rangeEnd != null }}"
            return try { e.select(s); checkSelection(false); true } catch (x: IllegalArgumentException) {
                log[log.lastIndex] += " -> rejected"
                if (e.state.value != before) v("R2", "rejected select changed state")
                false
            }
        }

        private fun dispatch(c: EditorCommand): Boolean {
            val before = e.state.value
            val du = depth("undo"); val dr = depth("redo")
            log += "#$step dispatch(${describe(c)})"
            val res = try { e.dispatch(c) } catch (t: Throwable) { v("R1", "dispatch(${describe(c)}) threw $t"); return false }
            log[log.lastIndex] += " -> " + (if (res is Outcome.Failure) "Failure(${res.message})" else "Success")
            stats.merge(c::class.simpleName + (if (res is Outcome.Failure) " fail" else if (same(p, before.project)) " no-op" else " ok"), 1, Int::plus)
            when (res) {
                is Outcome.Failure -> {
                    if (p != before.project || sel != before.selection) v("R2", "Failure changed project/selection")
                    if (depth("undo") != du || depth("redo") != dr) v("R2", "Failure changed the history")
                    return false
                }
                is Outcome.Success -> {
                    if (same(p, before.project)) { if (p.revision != before.project.revision) v("R4", "no-op changed the revision") }
                    else {
                        if (p.revision != before.project.revision + 1) v("R4", "revision ${before.project.revision} -> ${p.revision}")
                        undoModel.addLast(before.project to before.selection); redoModel.clear()
                    }
                    val errors = ScoreValidator.validate(p).filter { it.severity == Severity.ERROR }
                    if (errors.isNotEmpty()) v("R2", "Success left ERRORs: ${errors.take(3)}")
                    postChecks()
                    return true
                }
            }
        }

        private fun travel(isUndo: Boolean) {
            val before = e.state.value
            val model = if (isUndo) undoModel else redoModel
            val other = if (isUndo) redoModel else undoModel
            log += "#$step ${if (isUndo) "undo" else "redo"}()"
            val res = if (isUndo) e.undo() else e.redo()
            if (model.isEmpty()) { if (res !is Outcome.Failure || e.state.value != before) v("R4", "undo/redo without history changed something"); return }
            if (res !is Outcome.Success) { v("R4", "undo/redo failed with history: $res"); return }
            val (expected, expectedSel) = model.removeLast(); other.addLast(before.project to before.selection)
            if (!same(p, expected)) v("R4", "${if (isUndo) "undo" else "redo"} did not restore the project")
            if (sel != expectedSel) v("R4", "${if (isUndo) "undo" else "redo"} restored selection $sel instead of $expectedSel")
            if (p.revision <= before.project.revision) v("R4", "revision did not increase")
            postChecks()
        }

        private fun postChecks() { checkSelection(true); checkProject(); if (step % 3 == 0 || p.measures.size <= 8) checkJson() }

        private fun checkSelection(insideBar: Boolean) {
            val s = sel; val pr = p
            val t = pr.tracks.firstOrNull { it.id == s.trackId }; val m = pr.measures.firstOrNull { it.id == s.measureId }
            if (t == null || m == null) { v("R3", "selection points at a missing track/bar: $s"); return }
            if (s.rangeEnd != null && pr.measures.none { it.id == s.rangeEnd }) v("R3", "rangeEnd points at a missing bar")
            if (s.voice !in 0..3 || s.string !in 0 until maxOf(1, t.instrument.tuning.size)) v("R3", "selection voice/string invalid: $s (${t.instrument.tuning.size} strings)")
            if (insideBar && s.offset >= capacity(m)) v("R3", "selection offset ${s.offset} outside bar ${bar(m.id)} (${capacity(m)})")
            if (s.measureRange(pr).isEmpty()) v("R3", "empty measure range")
        }
        private fun checkProject() {
            val pr = p; val ids = pr.measures.map { it.id }.toSet()
            if (pr.tempo.none { it.position == ScorePosition(pr.measures.first().id) }) v("R6", "bar 1 carries no tempo: ${pr.tempo}")
            if (pr.tempo.any { it.position.measureId !in ids } || pr.sections.any { it.measureId !in ids } || pr.text.any { it.position.measureId !in ids }) v("R6", "tempo/section/text references a missing bar")
            if (pr.tempo.map { it.position }.distinct().size != pr.tempo.size) v("R6", "two tempo marks at one position: ${pr.tempo}")
        }
        private fun checkJson() {
            val pr = p
            val text = projectJson.encodeToString(Project.serializer(), pr)
            val back = try { projectJson.decodeFromString(Project.serializer(), text) } catch (t: Throwable) { v("R5", "decode threw $t"); return }
            if (back != pr) v("R5", "JSON round-trip changes the project")
            if (projectJson.encodeToString(Project.serializer(), back) != text) v("R5", "JSON round-trip is not bit-identical")
        }

        private fun describe(c: EditorCommand): String = when (c) {
            is EditorCommand.PasteBars -> "PasteBars(${c.content.measures.size} bars × ${c.content.tracks.size} tracks @ bar ${bar(c.targetMeasure)}, track ${p.tracks.indexOfFirst { it.id == c.targetTrack }})"
            is EditorCommand.DeleteMeasures -> "DeleteMeasures(${c.ids.map { bar(it) }})"
            is EditorCommand.DuplicateMeasures -> "DuplicateMeasures(${c.ids.map { bar(it) }})"
            is EditorCommand.ClearBars -> "ClearBars(${c.ids.map { bar(it) }})"
            is EditorCommand.SetSignature -> "SetSignature(bar ${bar(c.measureId)}, ${c.signature.numerator}/${c.signature.denominator})"
            is EditorCommand.SetTempoAt -> "SetTempoAt(bar ${bar(c.measureId)}, ${c.bpm})"
            is EditorCommand.MoveNote -> "MoveNote(bar ${bar(c.toMeasure)} @${c.toOffset} string ${c.toString})"
            is EditorCommand.SetInstrument -> "SetInstrument(${c.instrument.tuning}, capo ${c.instrument.capoByString.firstOrNull()}, ${c.policy}, allowDrop=${c.allowDrop})"
            else -> c.toString().take(160)
        } + " [cursor bar ${bar(sel.measureId)} @${sel.offset} s${sel.string} v${sel.voice}, range ${sel.measureRange(p).map { bar(it) }}]"
    }

    // ------------------------------------------------------------ execution and shrinking
    fun execute(spec: Spec, steps: List<Op>): Runner = Runner(spec).also { it.run(steps) }
    private fun fails(spec: Spec, steps: List<Op>, inv: String) = try { execute(spec, steps).violations.any { it.inv == inv } } catch (t: Throwable) { false }
    fun shrink(spec0: Spec, steps0: List<Op>, inv: String): Pair<Spec, List<Op>> {
        var spec = spec0
        var steps = steps0.take((execute(spec, steps0).violations.firstOrNull { it.inv == inv }?.step ?: steps0.lastIndex) + 1)
        for (cand in listOf(spec.copy(bars = 2, density = 0, ties = false), spec.copy(bars = 2), spec.copy(ties = false), spec.copy(bars = minOf(spec.bars, 4))))
            if (cand != spec && fails(cand, steps, inv)) { spec = cand; break }
        var chunk = maxOf(1, steps.size / 2)
        while (true) {
            var progress = false; var i = 0
            while (i < steps.size) { val cand = steps.take(i) + steps.drop(i + chunk); if (cand.isNotEmpty() && fails(spec, cand, inv)) { steps = cand; progress = true } else i += chunk }
            if (!progress) { if (chunk == 1) break; chunk = maxOf(1, chunk / 2) }
        }
        return spec to steps
    }

    @Test fun randomisedRangeSignatureRetuneDragModel() {
        val n = System.getProperty("qa20.n")?.toInt() ?: 48
        val len = System.getProperty("qa20.len")?.toInt() ?: 60
        val base = System.getProperty("qa20.seed")?.toLong() ?: 20_000L
        val results = ConcurrentLinkedQueue<Pair<Long, List<Violation>>>()
        val ran = AtomicInteger(); val steps = AtomicInteger(); val successes = AtomicInteger()
        val t0 = System.nanoTime()
        val pool = Executors.newFixedThreadPool(maxOf(1, minOf(2, Runtime.getRuntime().availableProcessors())))
        for (k in 0 until n) { val seed = base + k
            pool.submit {
                try { val r = execute(randomSpec(seed), randomSteps(seed, len)); ran.incrementAndGet(); steps.addAndGet(r.log.size); successes.addAndGet(r.log.count { it.endsWith("Success") })
                    if (r.violations.isNotEmpty()) { results += seed to r.violations; println("QA20 FAIL seed=$seed ${r.violations.distinctBy { it.inv }.joinToString(" ; ")}") }
                } catch (t: Throwable) { println("QA20 RUNNER CRASH seed=$seed $t"); results += seed to listOf(Violation("crash", -1, t.toString())) }
            }
        }
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.HOURS)
        println("QA20 ran ${ran.get()} sequences × $len ops (${steps.get()} logged steps, ${successes.get()} successful commands) in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s, ${results.size} failing")
        println("QA20 outcomes: " + stats.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" })
        val byInv = results.flatMap { (seed, vs) -> vs.map { it.inv }.distinct().map { it to seed } }.groupBy({ it.first }, { it.second })
        for ((inv, seeds) in byInv) println("QA20 invariant $inv: ${seeds.size} sequences, seeds=${seeds.sorted().take(10)}")
        if (System.getProperty("qa20.shrink") != "false") for ((inv, seeds) in byInv) {
            val seed = seeds.min()
            val (spec, min) = shrink(randomSpec(seed), randomSteps(seed, len), inv)
            val r = execute(spec, min)
            println("QA20 MINIMAL $inv (seed=$seed, spec=$spec, ${min.size} ops: $min): ${r.violations.firstOrNull { it.inv == inv }?.msg}")
            r.log.forEach { println("    $it") }
        }
        assertTrue("violations: ${byInv.keys} (seeds ${results.map { it.first }.sorted().take(20)})", results.isEmpty())
    }
}
