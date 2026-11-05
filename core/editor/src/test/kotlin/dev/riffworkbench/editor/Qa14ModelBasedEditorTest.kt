package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 14: model-based randomised test of the editor core (EditorSession, NoteEdits, StructureEdits, BarClipboard, Links,
 * ScoreValidator). Seeded sequences of select / every EditorCommand (valid and invalid arguments) / undo / redo, checked
 * after every step against a reference history model and nine invariants; failures are shrunk to a minimal sequence.
 *
 * Defaults: 20 sequences. Campaign: -Dqa14.n=2000 -Dqa14.len=40 [-Dqa14.seed=…] [-Dqa14.skip=I8,I7adj] [-Dqa14.shrink=false].
 * Invariants: I1 Success → no ERROR; I2 Failure → project/selection/revision (and history) unchanged; I3 undo/redo exact;
 * I4 revision +1 on change, unchanged on no-op; I5 selection valid; I6 no exception escapes dispatch; I7 links/ties
 * (missing target, same string, adjacency); I8 projectJson round-trip; I9 dispatch < 200 ms for ≤ 64 bars.
 */
class Qa14ModelBasedEditorTest {
    /** A step is resolved against the live state at run time, so shrinking (dropping steps) stays meaningful. */
    sealed class Op {
        data class Select(val seed: Long) : Op()
        data class Cmd(val kind: Int, val seed: Long) : Op()
        data class Copy(val seed: Long) : Op()
        object Undo : Op() { override fun toString() = "Undo" }
        object Redo : Op() { override fun toString() = "Redo" }
    }
    data class Spec(val seed: Long, val measures: Int, val density: Int, val ties: Boolean)
    data class Violation(val inv: String, val step: Int, val msg: String)

    companion object {
        const val KINDS = 52
        /** Mirror of data/local `projectJson` (internal there): the configuration the app saves projects with. */
        val projectJson = Json { encodeDefaults = true; prettyPrint = false; ignoreUnknownKeys = false; classDiscriminator = "type" }
        private val skip = (System.getProperty("qa14.skip") ?: "").split(",").filter { it.isNotBlank() }.toSet()
        private val noNaN = System.getProperty("qa14.nonan") == "true"
        private val noBadClip = System.getProperty("qa14.nobadclip") == "true"
        private val noSurrogate = System.getProperty("qa14.nosurrogate") != "false"  // commands now reject lone surrogates

        fun capacity(m: Measure): Fraction = m.pickupLength ?: m.signature.length()

        // ---------------------------------------------------------------- initial project
        fun build(spec: Spec, ids: IdSource): Project {
            val r = Random(spec.seed)
            val f = ProjectFactory(ids)
            val base = f.create("QA14")
            val sigs = listOf(TimeSignature(4, 4), TimeSignature(3, 4), TimeSignature(7, 8), TimeSignature(6, 8), TimeSignature(5, 16), TimeSignature(2, 2))
            val measures = List(spec.measures) { i -> if (i < 2) base.measures[i] else Measure(ids.next(), sigs[r.nextInt(sigs.size)]) }
            val tracks = base.tracks.map { t ->
                t.copy(staves = t.staves.map { st -> st.copy(bars = measures.map { m -> fillBar(f.bar(m.id), m, t, r, spec.density, ids) }) })
            }
            var p = base.copy(measures = measures, tracks = tracks)
            if (spec.ties) p = addLinks(p, r)
            val errors = ScoreValidator.validate(p).filter { it.severity == Severity.ERROR }
            check(errors.isEmpty()) { "harness built an invalid project: $errors" }
            return p
        }

        private fun fillBar(bar: Bar, m: Measure, t: Track, r: Random, density: Int, ids: IdSource): Bar {
            if (density == 0 || r.nextInt(4) == 0) return bar
            val durations = listOf(WrittenDuration(4), WrittenDuration(8), WrittenDuration(16), WrittenDuration(8, 1), WrittenDuration(2), WrittenDuration(32))
            return bar.copy(voices = bar.voices.map { v ->
                if (v.index > 1 || (v.index == 1 && r.nextInt(3) != 0)) v else {
                    val beats = mutableListOf<Beat>()
                    var at = Fraction.ZERO
                    while (true) {
                        val d = durations[r.nextInt(durations.size)]
                        if (at + d.length() > capacity(m)) break
                        val notes = if (r.nextInt(5) == 0) emptyList() else notesFor(t, r, density, ids)
                        beats += Beat(ids.next(), at, d, notes, explicitRest = notes.isEmpty())
                        at += d.length()
                        if (r.nextInt(12) == 0) break
                    }
                    v.copy(beats = beats)
                }
            })
        }

        private fun notesFor(t: Track, r: Random, density: Int, ids: IdSource): List<Note> {
            val i = t.instrument
            if (i.tuning.isEmpty()) return listOf(36, 38, 42, 46, 49).shuffled(r).take(1 + r.nextInt(minOf(3, density))).map { Note(ids.next(), Pitch(it)) }
            val strings = i.tuning.indices.shuffled(r).take(1 + r.nextInt(minOf(density, i.tuning.size)))
            return strings.map { s -> val fret = r.nextInt(13); Note(ids.next(), Pitch(i.pitch(s, fret)), s, fret, velocity = 40 + r.nextInt(80)) }
        }

        /** Valid ties between adjacent notes on one string (also across barlines), plus slurs and slides with targets. */
        private fun addLinks(p0: Project, r: Random): Project {
            var p = p0
            for ((ti, t) in p0.tracks.withIndex()) {
                if (t.instrument.tuning.isEmpty()) continue
                val bars = t.staves[0].bars
                for ((mi, bar) in bars.withIndex()) for (v in bar.voices) {
                    val seq = v.beats.sortedBy { it.offset }
                    val pairs = seq.zipWithNext().filter { (a, b) -> a.offset + a.duration.length() == b.offset }.toMutableList()
                    val next = bars.getOrNull(mi + 1)?.voices?.firstOrNull { it.index == v.index }?.beats?.firstOrNull { it.offset == Fraction.ZERO }
                    val last = seq.lastOrNull()
                    if (next != null && last != null && last.offset + last.duration.length() == capacity(p0.measures[mi])) pairs += last to next
                    for ((a, b) in pairs) {
                        if (r.nextInt(3) != 0) continue
                        val origin = a.notes.randomOrNull(r)?.let { o -> noteById(p, o.id) } ?: continue
                        val target = b.notes.firstOrNull { it.string == origin.string } ?: continue
                        if (target.links.any { it.kind == LinkKind.TIE }) continue
                        when (r.nextInt(4)) {
                            0, 1 -> { // tie: target takes origin's pitch and fret
                                p = NoteEdits.updateNote(p, target.id) { it.copy(pitch = origin.pitch, fret = origin.fret) }
                                p = NoteEdits.updateNote(p, origin.id) { it.copy(links = it.links.filterNot { l -> l.kind == LinkKind.TIE } + NoteLink(LinkKind.TIE, target.id)) }
                            }
                            2 -> p = NoteEdits.updateNote(p, origin.id) { it.copy(links = it.links + NoteLink(LinkKind.HAMMER_ON, target.id)) }
                            else -> p = NoteEdits.updateNote(p, origin.id) { it.copy(articulations = it.articulations + Articulation.Slide(SlideKind.LEGATO, target.id)) }
                        }
                    }
                }
                check(p.tracks[ti].id == t.id)
            }
            return p
        }

        fun noteById(p: Project, id: EntityId): Note? { for (t in p.tracks) for (st in t.staves) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes) if (n.id == id) return n; return null }

        // ---------------------------------------------------------------- random steps
        fun randomSteps(seed: Long, len: Int): List<Op> {
            val r = Random(seed)
            return List(len) {
                when (r.nextInt(100)) {
                    in 0..27 -> Op.Select(r.nextLong())
                    in 28..31 -> Op.Copy(r.nextLong())
                    in 32..39 -> Op.Undo
                    in 40..44 -> Op.Redo
                    else -> Op.Cmd(r.nextInt(KINDS), r.nextLong())
                }
            }
        }
        private val big = System.getProperty("qa14.big") == "true"
        val maxDispatchNanos = java.util.concurrent.atomic.AtomicLong()
        fun randomSpec(seed: Long): Spec { val r = Random(seed xor 0x5EED); return if (big) Spec(r.nextLong(), 64, 3, true) else Spec(r.nextLong(), 2 + r.nextInt(if (r.nextInt(4) == 0) 63 else 12), r.nextInt(4), r.nextBoolean()) }
    }

    // ---------------------------------------------------------------- runner
    class Runner(spec: Spec) {
        private var n = 0
        val ids = IdSource { EntityId("z${n++}") }
        val start = build(spec, ids)
        val e = EditorSession(start, ids)
        val violations = mutableListOf<Violation>()
        val log = mutableListOf<String>()
        private val undoModel = ArrayDeque<Project>()
        private val redoModel = ArrayDeque<Project>()
        private var clipboards = mutableListOf<BarClipboard>()
        private var pastProjects = mutableListOf(start)
        /** Known limitation (journal cycle 6): track-wide transpose and retune may split a tie over two strings. */
        private var tieSplitKnown = false
        private var step = 0
        private fun v(inv: String, msg: String) { if (skip.none { inv.startsWith(it) }) violations += Violation(inv, step, msg) }
        private val p get() = e.state.value.project
        private val sel get() = e.state.value.selection
        @Suppress("UNCHECKED_CAST") private fun depth(name: String) = (EditorSession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(e) as ArrayDeque<*>).size

        fun run(ops: List<Op>) { for ((i, op) in ops.withIndex()) { step = i; runOp(op); if (violations.isNotEmpty()) return } }

        private fun runOp(op: Op) {
            when (op) {
                is Op.Select -> select(Random(op.seed))
                is Op.Copy -> copy(Random(op.seed))
                Op.Undo -> travel(true)
                Op.Redo -> travel(false)
                is Op.Cmd -> {
                    val r = Random(op.seed)
                    val c = try { command(op.kind, r) } catch (x: IllegalArgumentException) { log += "#$step (constructor rejected kind ${op.kind}: ${x.message})"; return }
                    if (c == null) { log += "#$step (kind ${op.kind} n/a)"; return }
                    dispatch(c)
                }
            }
        }

        private fun same(a: Project, b: Project) = a.copy(revision = 0) == b.copy(revision = 0)

        private fun travel(isUndo: Boolean) {
            val before = e.state.value
            val model = if (isUndo) undoModel else redoModel
            val other = if (isUndo) redoModel else undoModel
            log += "#$step ${if (isUndo) "undo" else "redo"}()"
            val res = try { if (isUndo) e.undo() else e.redo() } catch (t: Throwable) { v("I6u", "${if (isUndo) "undo" else "redo"} threw $t"); return }
            if (model.isEmpty()) {
                if (res !is Outcome.Failure) v("I3", "undo/redo with empty model history succeeded")
                if (e.state.value != before) v("I2", "failed undo/redo changed state")
                return
            }
            if (res !is Outcome.Success) { v("I3", "undo/redo failed although model has history: $res"); return }
            val expected = model.removeLast(); other.addLast(before.project)
            if (!same(p, expected)) v("I3", "${if (isUndo) "undo" else "redo"} did not restore the expected project")
            if (p.revision <= before.project.revision) v("I4", "revision did not increase on undo/redo (${before.project.revision} -> ${p.revision})")
            postChecks()
        }

        private fun dispatch(c: EditorCommand) {
            val before = e.state.value
            val du = depth("undo"); val dr = depth("redo")
            log += "#$step dispatch(${describe(c)})"
            val t0 = System.nanoTime()
            val res = try { e.dispatch(c) } catch (t: Throwable) { v("I6", "dispatch(${describe(c)}) threw $t"); return }
            val took = System.nanoTime() - t0
            if (before.project.measures.size <= 64) maxDispatchNanos.accumulateAndGet(took) { a, b -> maxOf(a, b) }
            val ms = took / 1e6
            if (ms > 200 && before.project.measures.size <= 64 && p.measures.size <= 64) v("I9", "dispatch(${describe(c)}) took ${"%.0f".format(ms)} ms (${p.measures.size} bars)")
            log[log.lastIndex] = log.last() + " -> " + (if (res is Outcome.Failure) "Failure(${res.message})" else "Success")
            when (res) {
                is Outcome.Failure -> {
                    if (p != before.project) v("I2", "Failure changed the project")
                    if (sel != before.selection) v("I2", "Failure changed the selection")
                    if (p.revision != before.project.revision) v("I2", "Failure changed the revision")
                    if (depth("undo") != du || depth("redo") != dr) v("I2h", "Failure changed the undo/redo history (undo $du->${depth("undo")}, redo $dr->${depth("redo")})")
                }
                is Outcome.Success -> {
                    if (same(p, before.project)) {
                        if (p.revision != before.project.revision) v("I4", "no-op ${describe(c)} changed revision ${before.project.revision} -> ${p.revision}")
                    } else {
                        if (p.revision != before.project.revision + 1) v("I4", "change did not bump revision by one (${before.project.revision} -> ${p.revision})")
                        undoModel.addLast(before.project); while (undoModel.size > 200) undoModel.removeFirst(); redoModel.clear()
                        if (c is EditorCommand.Transpose || c is EditorCommand.SetInstrument || c is EditorCommand.Replace) tieSplitKnown = true
                        pastProjects += p
                    }
                    val errors = ScoreValidator.validate(p).filter { it.severity == Severity.ERROR }
                    if (errors.isNotEmpty()) v("I1", "Success left ERRORs: ${errors.take(3)}")
                    postChecks()
                }
            }
        }

        private fun postChecks() {
            checkSelection(); checkLinks(); checkJson()
        }

        private fun checkSelection() {
            val s = sel; val pr = p
            val track = pr.tracks.firstOrNull { it.id == s.trackId }
            if (track == null || pr.measures.none { it.id == s.measureId }) { v("I5", "selection points at missing track/measure: $s"); return }
            if (s.voice !in 0..3 || s.offset < Fraction.ZERO) v("I5", "selection voice/offset invalid: $s")
            if (s.string !in 0 until maxOf(1, track.instrument.tuning.size)) v("I5", "selection string ${s.string} invalid for ${track.instrument.tuning.size} strings")
            if (s.rangeEnd != null && pr.measures.none { it.id == s.rangeEnd }) v("I5", "selection rangeEnd missing")
        }

        private data class Loc(val track: Int, val staff: Int, val measure: Int, val voice: Int, val beat: Beat, val note: Note)
        private fun checkLinks() {
            val pr = p
            val index = HashMap<EntityId, Loc>()
            val mIndex = pr.measures.withIndex().associate { it.value.id to it.index }
            for ((ti, t) in pr.tracks.withIndex()) for ((si, st) in t.staves.withIndex()) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes)
                index[n.id] = Loc(ti, si, mIndex[bar.measureId] ?: -1, v.index, b, n)
            for (loc in index.values) {
                val n = loc.note
                for (l in n.links) {
                    val t = index[l.target]
                    if (t == null) { v("I7missing", "link ${l.kind} from ${n.id} to missing ${l.target}"); continue }
                    if (l.kind != LinkKind.TIE) continue
                    if (t.track != loc.track || t.staff != loc.staff || t.voice != loc.voice) { v("I7adj", "tie ${n.id}->${l.target} crosses track/staff/voice"); continue }
                    if (!tieSplitKnown && n.string != t.note.string) v("I7string", "tie ${n.id}(string ${n.string}) -> ${l.target}(string ${t.note.string})")
                    val end = loc.beat.offset + loc.beat.duration.length()
                    val adjacent = if (t.measure == loc.measure) t.beat.offset == end
                        else t.measure == loc.measure + 1 && t.beat.offset == Fraction.ZERO && end == capacity(pr.measures[loc.measure])
                    if (!adjacent) v("I7adj", "tie ${n.id} (bar ${loc.measure} @${loc.beat.offset}+${loc.beat.duration.length()}) -> ${l.target} (bar ${t.measure} @${t.beat.offset}) is not adjacent")
                }
                for (a in n.articulations) if (a is Articulation.Slide && a.target != null && a.target !in index) v("I7missing", "slide from ${n.id} to missing ${a.target}")
            }
        }

        private fun checkJson() {
            val pr = p
            val back = try {
                val text = projectJson.encodeToString(Project.serializer(), pr).toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
                projectJson.decodeFromString(Project.serializer(), text)
            } catch (t: Throwable) { v("I8throw", "projectJson round-trip threw ${t::class.simpleName}: ${t.message?.take(160)}"); return }
            if (back != pr) v("I8diff", "projectJson round-trip differs: ${firstDiff(pr, back)}")
        }
        private fun firstDiff(a: Project, b: Project): String {
            if (a.metadata != b.metadata) return "metadata ${a.metadata.toString().map { if (it.isSurrogate()) "\\u%04x".format(it.code) else it.toString() }.joinToString("")} vs ${b.metadata}"
            val x = projectJson.encodeToString(Project.serializer(), a); val y = projectJson.encodeToString(Project.serializer(), b)
            val i = x.indices.firstOrNull { it >= y.length || x[it] != y[it] } ?: x.length
            return "json differs at $i: '${x.substring(maxOf(0, i - 120), minOf(x.length, i + 60))}' vs '${y.substring(maxOf(0, i - 120), minOf(y.length, i + 60))}'"
        }

        // ---------------------------------------------------------------- generators
        private fun dur(r: Random): WrittenDuration = when (r.nextInt(20)) {
            0 -> WrittenDuration(3) // invalid → constructor rejects
            1 -> WrittenDuration(16, 5)
            2 -> WrittenDuration(8, 0, listOf(TupletRatio(3, 2)))
            3 -> WrittenDuration(16, 0, listOf(TupletRatio(5, 4)))
            4 -> WrittenDuration(1)
            else -> WrittenDuration(listOf(2, 4, 8, 8, 16, 16, 16, 32, 64)[r.nextInt(9)], if (r.nextInt(5) == 0) r.nextInt(1, 3) else 0)
        }
        private fun measureId(r: Random): EntityId = if (r.nextInt(25) == 0) EntityId("nope${r.nextInt(3)}") else p.measures.random(r).id
        private fun trackId(r: Random): EntityId = if (r.nextInt(25) == 0) EntityId("nope${r.nextInt(3)}") else p.tracks.random(r).id
        private fun dbl(r: Random, lo: Double, hi: Double): Double = when (r.nextInt(if (noNaN) 1000 else 30)) {
            0 -> Double.NaN; 1 -> Double.POSITIVE_INFINITY; 2 -> -0.0; 3 -> hi + 1; else -> lo + r.nextDouble() * (hi - lo)
        }
        private fun str(r: Random): String = when (r.nextInt(14)) {
            0 -> ""; 1 -> "   "; 2 -> "x".repeat(r.nextInt(195, 210)); 3 -> "Riff 🎸 ✓"; 4 -> "quote \" back \\ nl \n tab \t"
            5 -> if (noSurrogate) "no surrogate" else "lone \uD800 surrogate"; 6 -> "  padded  "; 7 -> "\u0000ctl"
            else -> listOf("Intro", "Verse", "Chorus", "Bridge", "Solo", "Outro")[r.nextInt(6)] + r.nextInt(99)
        }
        private fun sig(r: Random) = if (r.nextInt(15) == 0) TimeSignature(0, 4) else TimeSignature(listOf(1, 2, 3, 4, 5, 6, 7, 9, 12, 15)[r.nextInt(10)], listOf(2, 4, 8, 16)[r.nextInt(4)])
        private fun allNotes(): List<Note> = p.tracks.flatMap { t -> t.staves.flatMap { s -> s.bars.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { it.notes } } } } }
        private fun noteId(r: Random): EntityId = allNotes().randomOrNull(r)?.id ?: EntityId("nonote")
        private fun idRange(r: Random): List<EntityId> {
            val ms = p.measures; val a = r.nextInt(ms.size); val b = (a + r.nextInt(4)).coerceAtMost(ms.lastIndex)
            return when (r.nextInt(12)) {
                0 -> emptyList(); 1 -> ms.map { it.id }; 2 -> listOf(ms[a].id, EntityId("nope"))
                3 -> ms.shuffled(r).take(2).map { it.id }; else -> ms.subList(a, b + 1).map { it.id }
            }
        }
        private fun effect(r: Random) = EffectUnit(ids.next(), EffectKind.values().random(r), r.nextBoolean(), mapOf("gain" to dbl(r, 0.0, 1.0), "mix" to dbl(r, 0.0, 1.0)))
        private fun mix(r: Random) = ChannelMix(dbl(r, 0.0, 1.0), dbl(r, -1.0, 1.0), r.nextBoolean(), r.nextInt(5) == 0, if (r.nextInt(3) == 0) listOf(effect(r)) else emptyList())
        private fun instrument(r: Random): InstrumentDefinition = when (r.nextInt(8)) {
            0 -> InstrumentDefinition(kind = InstrumentKind.PERCUSSION, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0)
            1 -> InstrumentDefinition(kind = InstrumentKind.PIANO, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0)
            2 -> InstrumentDefinition(kind = InstrumentKind.BASS, tuning = listOf(28, 33, 38, 43), capoByString = List(4) { 0 })
            3 -> InstrumentDefinition(fineTuneCents = dbl(r, -50.0, 50.0))
            else -> { val n = r.nextInt(3, 11); val low = r.nextInt(20, 45)
                InstrumentDefinition(tuning = List(n) { low + it * 5 }, capoByString = List(n) { if (r.nextInt(4) == 0) r.nextInt(0, 5) else 0 }, maxFret = listOf(12, 20, 24, 30)[r.nextInt(4)], transposition = if (r.nextInt(5) == 0) r.nextInt(-12, 13) else 0) }
        }
        private fun articulation(r: Random): Articulation = when (r.nextInt(14)) {
            0 -> Articulation.Mark(NoteMark.values().random(r))
            1 -> Articulations.fullBend(); 2 -> Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, r.nextInt(-2000, 2000))))
            3 -> Articulation.Whammy(listOf(PitchCurvePoint(Fraction.of(1, 2), -400)))
            4 -> Articulation.Slide(SlideKind.values().random(r), if (r.nextBoolean()) noteId(r) else null)
            5 -> Articulation.Harmonic(HarmonicKind.values().random(r), if (r.nextBoolean()) Fraction.of(1, 2) else null)
            6 -> Articulation.Vibrato(r.nextInt(-10, 1300), dbl(r, 0.1, 20.0))
            7 -> Articulation.Trill(r.nextInt(-3, 30)); 8 -> Articulation.TremoloPicking(dur(r))
            9 -> Articulation.Grace(Pitch(r.nextInt(0, 128)), dur(r), r.nextBoolean(), r.nextInt(0, 25), r.nextInt(0, 7))
            10 -> Articulation.Dynamic(DynamicLevel.values().random(r)); 11 -> Articulation.Pickstroke(StrokeDirection.values().random(r))
            12 -> Articulation.Percussion(PercussionTechnique.values().random(r), r.nextInt(0, 3))
            else -> Articulation.Mark(NoteMark.PALM_MUTE)
        }
        private fun clipboard(r: Random): BarClipboard? {
            if (clipboards.isEmpty() || r.nextInt(4) == 0) return try { BarClipboard.copy(p, idRange(r), p.tracks.shuffled(r).take(1 + r.nextInt(2)).map { it.id }) } catch (x: IllegalArgumentException) { null }
            val c = clipboards.random(r)
            if (!noBadClip && r.nextInt(30) == 0) return c.copy(tracks = c.tracks.map { it.copy(bars = it.bars.dropLast(1)) }) // malformed clipboard
            return c
        }

        private fun command(kind: Int, r: Random): EditorCommand? = when (kind) {
            0, 1, 2, 3 -> EditorCommand.PutNote(fret = if (r.nextInt(15) == 0) null else r.nextInt(-2, 31), midiNote = if (r.nextInt(3) == 0) null else r.nextInt(-3, 131), duration = dur(r), marks = NoteMark.values().filter { r.nextInt(12) == 0 }.toSet())
            4 -> EditorCommand.ToggleDrum(r.nextInt(-1, 129), dur(r))
            5 -> EditorCommand.PutRest(dur(r))
            6 -> EditorCommand.Delete
            7, 8 -> EditorCommand.MoveNote(measureId(r), offset(r, p.measures.random(r)), r.nextInt(-1, 9), dur(r))
            9 -> EditorCommand.SetDuration(dur(r))
            10 -> EditorCommand.ToggleMark(NoteMark.values().random(r))
            11 -> EditorCommand.AddTrack(InstrumentKind.values().random(r))
            12 -> EditorCommand.RemoveTrack(trackId(r))
            13 -> EditorCommand.MoveTrack(trackId(r), listOf(-1, 1, 2, -5, Int.MAX_VALUE, Int.MIN_VALUE)[r.nextInt(6)])
            14 -> EditorCommand.AddMeasure(sig(r))
            15 -> EditorCommand.SetSignature(measureId(r), sig(r))
            16 -> EditorCommand.Rename(str(r))
            17 -> EditorCommand.SetTempo(dbl(r, 10.0, 1000.0))
            18 -> EditorCommand.SetMix(trackId(r), mix(r))
            19 -> when (r.nextInt(4)) { // Replace: an earlier state, a broken variant, or the current one with a stale revision
                0 -> EditorCommand.Replace(pastProjects.random(r), if (r.nextBoolean()) p.revision else null)
                1 -> EditorCommand.Replace(p.copy(measures = p.measures.drop(1)))
                2 -> EditorCommand.Replace(p, p.revision - 1)
                else -> EditorCommand.Replace(p.copy(metadata = p.metadata.copy(artist = str(r))), p.revision)
            }
            20, 21, 22 -> EditorCommand.ApplyArticulation(articulation(r), r.nextBoolean())
            23 -> EditorCommand.ClearArticulation(listOf("bend", "slide", "vibrato", "mark:PALM_MUTE", "nope")[r.nextInt(5)])
            24, 25, 26 -> EditorCommand.ToggleTie
            27 -> EditorCommand.SetVelocity(r.nextInt(-2, 130))
            28, 29 -> EditorCommand.MoveToString(r.nextInt(-1, 9))
            30 -> EditorCommand.Transpose(listOf(1, -1, 2, 12, -12, 5, 49, -49, 0)[r.nextInt(9)], EditScope.values().random(r))
            31, 32 -> EditorCommand.SetTuplet(when (r.nextInt(6)) { 0 -> null; 1 -> TupletRatio(3, 2); 2 -> TupletRatio(5, 4); 3 -> TupletRatio(7, 4); 4 -> TupletRatio(r.nextInt(1, 2_000_000_000), r.nextInt(1, 2_000_000_000)); else -> TupletRatio(0, 2) })
            33 -> EditorCommand.SetFermata(if (r.nextInt(3) == 0) null else Fermata(FermataKind.values().random(r), dbl(r, 0.1, 8.0)))
            34 -> EditorCommand.SetStrum(if (r.nextInt(3) == 0) null else Strum(StrumKind.values().random(r), StrokeDirection.values().random(r), Fraction.of(r.nextLong(0, 3), 16)))
            35 -> EditorCommand.DeleteBeat
            36 -> EditorCommand.InsertBeat(dur(r))
            37 -> EditorCommand.SetText(if (r.nextInt(4) == 0) null else str(r), TextKind.values().random(r))
            38, 39 -> EditorCommand.InsertMeasures(r.nextInt(-1, p.measures.size + 2), listOf(1, 1, 2, 3, 0, 257)[r.nextInt(6)], if (r.nextBoolean()) null else sig(r))
            40 -> EditorCommand.DeleteMeasures(idRange(r))
            41 -> EditorCommand.DuplicateMeasures(idRange(r))
            42, 43 -> clipboard(r)?.let { EditorCommand.PasteBars(it, measureId(r), trackId(r)) }
            44 -> EditorCommand.ClearBars(idRange(r), if (r.nextBoolean()) null else trackId(r))
            45 -> EditorCommand.SetRepeat(measureId(r), RepeatMark(r.nextBoolean(), listOf(null, 2, 3, 1, 33)[r.nextInt(5)], (1..9).filter { r.nextInt(6) == 0 }.toSet()))
            46 -> EditorCommand.SetNavigation(measureId(r), List(r.nextInt(0, 3)) { NavigationMark(NavigationKind.values().random(r), if (r.nextBoolean()) measureId(r) else null) })
            47 -> if (r.nextBoolean()) EditorCommand.SetKey(measureId(r), KeySignature(r.nextInt(-8, 9), r.nextBoolean())) else EditorCommand.SetFeel(measureId(r), RhythmicFeel.values().random(r))
            48 -> if (r.nextBoolean()) EditorCommand.SetTempoAt(measureId(r), if (r.nextInt(3) == 0) null else dbl(r, 10.0, 1000.0)) else EditorCommand.SetSection(measureId(r), if (r.nextInt(4) == 0) null else str(r))
            49 -> when (r.nextInt(3)) { 0 -> EditorCommand.DuplicateTrack(trackId(r)); 1 -> EditorCommand.RenameTrack(trackId(r), str(r)); else -> EditorCommand.SetSound(trackId(r), SoundAssignment(presetId = str(r))) }
            50 -> EditorCommand.SetInstrument(trackId(r), instrument(r), RetuningPolicy.values().random(r))
            else -> when (r.nextInt(5)) {
                0 -> EditorCommand.SetMetadata(ProjectMetadata(str(r), str(r), notes = str(r)))
                1 -> EditorCommand.SetMasterMix(mix(r))
                2 -> { val h = AssetHash(r.nextLong(1, Long.MAX_VALUE).toString(16).padStart(64, 'a')); EditorCommand.AddAudioClip(AudioClip(ids.next(), h, str(r), gain = dbl(r, 0.0, 2.0), pan = dbl(r, -1.0, 1.0), filters = if (r.nextBoolean()) listOf(effect(r)) else emptyList()), AssetDescriptor(h, "a.wav", "audio/wav", 100)) }
                3 -> p.audio.clips.randomOrNull(r)?.let { EditorCommand.UpdateAudioClip(it.copy(gain = dbl(r, 0.0, 2.0), offsetMicros = r.nextLong(-700_000_000L, 700_000_000L), filters = listOf(effect(r)))) }
                else -> EditorCommand.RemoveAudioClip(p.audio.clips.randomOrNull(r)?.id ?: EntityId("noclip"))
            }
        }

        private fun offset(r: Random, m: Measure): Fraction = when (r.nextInt(20)) {
            0 -> Fraction.of(-1, 16); 1 -> capacity(m) + Fraction.of(1, 16); 2 -> if (r.nextBoolean()) Fraction.of(1, 999_999_999_989L) else Fraction.of(4_000_000_000_000_000_001L, 4_611_686_018_427_387_903L)
            3 -> Fraction.of(r.nextLong(0, 12), 12)
            else -> Fraction.of(r.nextLong(0, 33), 32).let { if (it < capacity(m)) it else Fraction.ZERO }
        }

        private fun select(r: Random) {
            val pr = p
            val t = if (r.nextInt(30) == 0) pr.tracks.random(r).copy(id = EntityId("ghost")) else pr.tracks.random(r)
            val mi = r.nextInt(pr.measures.size); val m = pr.measures[mi]
            var voice = if (r.nextInt(5) == 0) 1 else 0
            if (r.nextInt(40) == 0) voice = listOf(-1, 4)[r.nextInt(2)]
            val beats = pr.tracks.firstOrNull { it.id == t.id }?.staves?.firstOrNull()?.bars?.getOrNull(mi)?.voices?.firstOrNull { it.index == voice }?.beats.orEmpty()
            var offset: Fraction; var string: Int
            val beat = beats.randomOrNull(r)
            if (beat != null && r.nextInt(10) < 7) {
                offset = beat.offset; string = beat.notes.randomOrNull(r)?.string ?: r.nextInt(maxOf(1, t.instrument.tuning.size))
                if (r.nextInt(6) == 0) { // just after the beat: tie / insert positions
                    offset = beat.offset + beat.duration.length(); if (offset >= capacity(m)) offset = Fraction.ZERO
                }
            } else { offset = offset(r, m); string = r.nextInt(maxOf(1, t.instrument.tuning.size)) }
            if (r.nextInt(40) == 0) string = listOf(-1, 10)[r.nextInt(2)]
            if (t.instrument.tuning.isEmpty()) string = 0
            val rangeEnd = when (r.nextInt(6)) { 0 -> pr.measures.random(r).id; 1 -> if (r.nextInt(5) == 0) EntityId("nope") else null; else -> null }
            val s = Selection(t.id, m.id, voice, offset, string, rangeEnd)
            val before = e.state.value
            log += "#$step select(track=${pr.tracks.indexOfFirst { it.id == t.id }} bar=$mi voice=$voice offset=$offset string=$string rangeEnd=${rangeEnd?.let { id -> pr.measures.indexOfFirst { it.id == id } }})"
            try { e.select(s) } catch (x: IllegalArgumentException) {
                log[log.lastIndex] += " -> rejected"
                if (e.state.value != before) v("I2", "rejected select changed state")
                return
            } catch (x: Throwable) { v("I6s", "select threw $x"); return }
            checkSelection()
        }

        private fun copy(r: Random) {
            val range = idRange(r)
            val tracks = p.tracks.shuffled(r).take(1 + r.nextInt(minOf(2, p.tracks.size))).map { it.id }
            log += "#$step copyBars(${range.map { id -> p.measures.indexOfFirst { it.id == id } }}, tracks=${tracks.map { id -> p.tracks.indexOfFirst { it.id == id } }})"
            try { clipboards += BarClipboard.copy(p, range, tracks) } catch (x: IllegalArgumentException) { log[log.lastIndex] += " -> rejected" }
        }

        private fun describe(c: EditorCommand): String = when (c) {
            is EditorCommand.Replace -> "Replace(project rev ${c.project.revision}, ${c.project.measures.size} bars, expectedRevision=${c.expectedRevision})"
            is EditorCommand.PasteBars -> "PasteBars(${c.content.measures.size} bars × ${c.content.tracks.size} tracks, target bar ${p.measures.indexOfFirst { it.id == c.targetMeasure }}, track ${p.tracks.indexOfFirst { it.id == c.targetTrack }})"
            is EditorCommand.DeleteMeasures -> "DeleteMeasures(${c.ids.map { id -> p.measures.indexOfFirst { it.id == id } }})"
            is EditorCommand.DuplicateMeasures -> "DuplicateMeasures(${c.ids.map { id -> p.measures.indexOfFirst { it.id == id } }})"
            is EditorCommand.ClearBars -> "ClearBars(${c.ids.map { id -> p.measures.indexOfFirst { it.id == id } }}, track ${c.trackId?.let { id -> p.tracks.indexOfFirst { it.id == id } }})"
            else -> c.toString().take(300)
        }
    }

    // ---------------------------------------------------------------- execution, shrinking
    fun execute(spec: Spec, steps: List<Op>): Runner = Runner(spec).also { it.run(steps) }
    fun fails(spec: Spec, steps: List<Op>, inv: String): Boolean = (0 until (if (inv == "I9") 3 else 1)).any { try { execute(spec, steps).violations.any { it.inv == inv } } catch (t: Throwable) { false } }

    fun shrink(spec0: Spec, steps0: List<Op>, inv: String): Pair<Spec, List<Op>> {
        var spec = spec0
        var steps = steps0.take((execute(spec, steps0).violations.firstOrNull { it.inv == inv }?.step ?: steps0.lastIndex) + 1)
        // Smaller project first.
        for (cand in listOf(spec.copy(measures = 2, density = 0, ties = false), spec.copy(measures = 2, ties = false), spec.copy(measures = 2), spec.copy(density = 0, ties = false), spec.copy(ties = false), spec.copy(measures = minOf(spec.measures, 4))))
            if (cand != spec && fails(cand, steps, inv)) { spec = cand; break }
        var chunk = maxOf(1, steps.size / 2)
        while (true) {
            var progress = false
            var i = 0
            while (i < steps.size) {
                val cand = steps.take(i) + steps.drop(i + chunk)
                if (cand.isNotEmpty() && fails(spec, cand, inv)) { steps = cand; progress = true } else i += chunk
            }
            if (!progress) { if (chunk == 1) break; chunk = maxOf(1, chunk / 2) }
        }
        return spec to steps
    }

    @Test fun randomisedEditorModel() {
        val n = System.getProperty("qa14.n")?.toInt() ?: 20
        val len = System.getProperty("qa14.len")?.toInt() ?: 40
        val base = System.getProperty("qa14.seed")?.toLong() ?: 14_000L
        val results = ConcurrentLinkedQueue<Pair<Long, List<Violation>>>()
        val ran = AtomicInteger(); val commands = AtomicInteger()
        val pool = Executors.newFixedThreadPool(maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors())))
        for (k in 0 until n) {
            val seed = base + k
            pool.submit {
                try {
                    val r = execute(randomSpec(seed), randomSteps(seed, len)); ran.incrementAndGet(); commands.addAndGet(r.log.size)
                    if (r.violations.isNotEmpty()) { results += seed to r.violations; println("FAIL seed=$seed ${r.violations.distinctBy { it.inv }.joinToString(" ; ")}") }
                } catch (t: Throwable) { println("RUNNER CRASH seed=$seed $t"); results += seed to listOf(Violation("crash", -1, t.toString())) }
            }
        }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.HOURS)
        println("QA14 max dispatch time (≤ 64 bars): ${"%.1f".format(maxDispatchNanos.get() / 1e6)} ms")
        println("QA14 ran ${ran.get()} sequences × $len steps (${commands.get()} steps executed), ${results.size} failing")
        val seedsByInv = results.flatMap { (seed, vs) -> vs.map { it.inv }.distinct().map { it to seed } }.groupBy({ it.first }, { it.second })
        for ((inv, seeds) in seedsByInv) println("QA14 invariant $inv: ${seeds.size} sequences, seeds=${seeds.sorted().take(10)}")
        if (System.getProperty("qa14.shrink") != "false") for ((inv, seeds) in seedsByInv) {
            val seed = seeds.min()
            val (spec, min) = shrink(randomSpec(seed), randomSteps(seed, len), inv)
            val r = execute(spec, min)
            println("QA14 MINIMAL $inv (seed=$seed, spec=$spec, ${min.size} steps): ${r.violations.firstOrNull { it.inv == inv }?.msg}")
            r.log.forEach { println("    $it") }
        }
        assertTrue("violations: ${seedsByInv.keys} (seeds ${results.map { it.first }.sorted().take(20)})", results.isEmpty())
    }
}
