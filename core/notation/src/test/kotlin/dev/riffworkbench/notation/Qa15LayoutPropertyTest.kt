package dev.riffworkbench.notation

import dev.riffworkbench.editor.ProjectFactory
import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 15: property-based testing of the notation layout. Random valid projects (validator: no ERROR) and random layout
 * requests; nine invariants P1–P9 (see [check]); failures are shrunk to a minimal project.
 *
 * Default: 40 projects. Campaign: -Dqa15.n=1000 [-Dqa15.seed=…] [-Dqa15.skip=P1std,P5top] [-Dqa15.shrink=false].
 */
class Qa15LayoutPropertyTest {
    data class Req(val trackIndex: Int, val style: LayoutStyle, val width: Float?, val editGrid: Fraction?, val editVoice: Int, val activeVoice: Int?, val margin: Float) {
        fun toRequest(p: Project) = LayoutRequest(p, trackIndex, style, width, editGrid, editVoice, activeVoice, margin)
    }
    data class V(val code: String, val msg: String)

    companion object {
        // Default skips are documented known limitations (QA journal cycle 15): hit test ignores the voice; noteheads in
        // notation-only layouts can overlap in chords and in extreme compression; notes beyond the staff range leave the band;
        // labels and the last column stick up to 2 units past the final barline at margin 0; rare bend arrows below a system.
        private val skip = (System.getProperty("qa15.skip") ?: "P2std,P2voice,P5band,P5x,P5y,P5orphan").split(",").filter { it.isNotBlank() }.toSet()
        private const val EPS = 0.51f

        fun capacity(m: Measure): Fraction = m.pickupLength ?: m.signature.length()

        // ---------------------------------------------------------------- generator
        class Gen(seed: Long) {
            val r = Random(seed)
            private var n = 0
            val ids = IdSource { EntityId("q${n++}") }
            private val f = ProjectFactory(ids)

            fun instrument(kind: InstrumentKind): InstrumentDefinition = when (kind) {
                InstrumentKind.GUITAR -> {
                    val strings = r.nextInt(4, 9); val low = r.nextInt(23, 45)
                    InstrumentDefinition(tuning = List(strings) { low + it * 5 - (if (it >= 4) 1 else 0) }, capoByString = List(strings) { if (r.nextInt(6) == 0) r.nextInt(0, 7) else 0 },
                        maxFret = listOf(12, 22, 24, 36)[r.nextInt(4)], transposition = if (r.nextInt(6) == 0) r.nextInt(-12, 13) else 0)
                }
                InstrumentKind.BASS -> { val s = r.nextInt(4, 7); InstrumentDefinition(kind = kind, tuning = List(s) { 23 + it * 5 }, capoByString = List(s) { 0 }, midiProgram = 33) }
                else -> InstrumentDefinition(kind = kind, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0)
            }

            fun duration(): WrittenDuration {
                val den = listOf(1, 2, 4, 4, 8, 8, 8, 16, 16, 16, 32, 64)[r.nextInt(12)]
                val dots = if (r.nextInt(5) == 0) r.nextInt(1, 3) else 0
                val tup = when (r.nextInt(14)) { 0, 1 -> listOf(TupletRatio(3, 2)); 2 -> listOf(TupletRatio(5, 4)); 3 -> listOf(TupletRatio(7, 4)); 4 -> listOf(TupletRatio(3, 2), TupletRatio(3, 2)); 5 -> listOf(TupletRatio(6, 4)); else -> emptyList() }
                return WrittenDuration(den, dots, tup)
            }

            fun articulation(t: Track, string: Int?): Articulation = when (r.nextInt(16)) {
                0, 1 -> Articulation.Mark(NoteMark.values().random(r))
                2 -> Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 2), listOf(50, 100, 200, 300, 400)[r.nextInt(5)]), PitchCurvePoint(Fraction.of(1, 1), if (r.nextBoolean()) 0 else 200)))
                3 -> if (System.getProperty("qa15.noDive") == "true") Articulation.Vibrato() else Articulation.Whammy(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 2), -400)))
                4 -> Articulation.Slide(SlideKind.values().random(r))
                5 -> Articulation.Harmonic(HarmonicKind.values().random(r))
                6 -> Articulation.Vibrato()
                7 -> Articulation.Trill(r.nextInt(0, 25))
                8 -> Articulation.TremoloPicking()
                9 -> if (string != null) Articulation.Grace(Pitch(t.instrument.pitch(string, 2)), WrittenDuration(32), r.nextBoolean(), r.nextInt(0, 20), string) else Articulation.Grace(Pitch(60))
                10 -> Articulation.Dynamic(DynamicLevel.values().random(r))
                11 -> Articulation.Pickstroke(StrokeDirection.values().random(r))
                12 -> Articulation.Percussion(PercussionTechnique.values().random(r))
                13 -> Articulation.Mark(NoteMark.PALM_MUTE)
                14 -> Articulation.Mark(NoteMark.LET_RING)
                else -> Articulation.Mark(NoteMark.GHOST)
            }

            fun notes(t: Track, density: Int): List<Note> {
                val i = t.instrument
                if (i.tuning.isEmpty()) {
                    val pool = if (i.kind == InstrumentKind.PERCUSSION) listOf(35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 55, 56, 57, 59, 60, 76)
                        else (System.getProperty("qa15.pianoRange")?.split("..")?.let { (a, b) -> a.toInt()..b.toInt() } ?: (21..108)).toList()
                    return pool.shuffled(r).take(1 + r.nextInt(density.coerceAtLeast(1))).map { m ->
                        Note(ids.next(), Pitch(m), articulations = if (r.nextInt(4) == 0) listOf(articulation(t, null)) else emptyList())
                    }
                }
                val strings = i.tuning.indices.shuffled(r).take(1 + r.nextInt(minOf(density.coerceAtLeast(1), i.tuning.size)))
                return strings.map { s ->
                    val fret = if (r.nextInt(5) == 0) r.nextInt(0, i.maxFret + 1) else r.nextInt(0, minOf(13, i.maxFret + 1))
                    val arts = List(if (r.nextInt(3) == 0) r.nextInt(1, 5) else 0) { articulation(t, s) }.distinct()
                    Note(ids.next(), Pitch(i.pitch(s, fret)), s, fret, velocity = 40 + r.nextInt(80), articulations = arts)
                }
            }

            fun fill(bar: Bar, m: Measure, t: Track, density: Int, voices: Int): Bar = bar.copy(voices = bar.voices.map { v ->
                if (v.index >= voices || r.nextInt(6) == 0) v else {
                    val beats = mutableListOf<Beat>()
                    var at = Fraction.ZERO
                    var guard = 0
                    while (guard++ < 200) {
                        var d = duration()
                        if (at + d.length() > capacity(m)) { d = WrittenDuration(64); if (at + d.length() > capacity(m)) break }
                        val rest = r.nextInt(6) == 0
                        val ns = if (rest) emptyList() else notes(t, density)
                        val fermata = if (r.nextInt(25) == 0) Fermata(FermataKind.values().random(r)) else null
                        val strum = if (ns.size >= 2 && r.nextInt(12) == 0) Strum(StrumKind.STRUM, StrokeDirection.values().random(r)) else null
                        beats += Beat(ids.next(), at, d, ns, explicitRest = rest, fermata = fermata, strum = strum)
                        at += d.length()
                        if (r.nextInt(30) == 0) { at += Fraction.of(1, 16); if (at >= capacity(m)) break } // a gap
                        if (at >= capacity(m)) break
                    }
                    v.copy(beats = beats)
                }
            })

            fun project(forceCount: Int? = null): Project {
                val big = r.nextInt(10) == 0
                val count = forceCount ?: if (big) r.nextInt(100, 301) else r.nextInt(1, 17)
                val sigs = listOf(TimeSignature(4, 4), TimeSignature(3, 4), TimeSignature(7, 8), TimeSignature(6, 8), TimeSignature(5, 16), TimeSignature(2, 2), TimeSignature(12, 8), TimeSignature(1, 4), TimeSignature(9, 16), TimeSignature(15, 8), TimeSignature(4, 1), TimeSignature(1, 64))
                val measures = List(count) { i ->
                    val sig = if (r.nextInt(3) == 0) sigs[r.nextInt(sigs.size)] else TimeSignature(4, 4)
                    Measure(ids.next(), sig,
                        key = if (r.nextInt(8) == 0) KeySignature(r.nextInt(-7, 8)) else KeySignature(),
                        pickupLength = if (i == 0 && r.nextInt(8) == 0) Fraction.of(1, 8) else null,
                        repeat = RepeatMark(r.nextInt(8) == 0, if (r.nextInt(8) == 0) r.nextInt(2, 5) else null, if (r.nextInt(10) == 0) (1..4).filter { r.nextBoolean() }.toSet() else emptySet()),
                        navigation = if (r.nextInt(12) == 0) List(r.nextInt(1, 3)) { NavigationMark(NavigationKind.values().random(r)) } else emptyList(),
                        forcedSystemBreak = r.nextInt(25) == 0)
                }
                val kinds = List(r.nextInt(1, 4)) { listOf(InstrumentKind.GUITAR, InstrumentKind.GUITAR, InstrumentKind.BASS, InstrumentKind.PERCUSSION, InstrumentKind.PIANO)[r.nextInt(5)] }
                val density = r.nextInt(1, 5)
                val voices = if (r.nextInt(3) == 0) r.nextInt(2, 5) else 1
                val tracks = kinds.map { k ->
                    val t0 = f.track(k, measures).let { it.copy(instrument = instrument(k)) }
                    t0.copy(staves = t0.staves.map { st -> st.copy(bars = measures.map { m -> fill(f.bar(m.id), m, t0, density, voices) }) })
                }
                var p = Project(id = ids.next(), metadata = ProjectMetadata(title = "QA15 <&>"), measures = measures, tracks = tracks)
                p = addLinks(p)
                val sections = measures.filter { r.nextInt(6) == 0 }.map { Section(ids.next(), it.id, listOf("Intro", "Verse", "A very long chorus label", "S", "Ünïcødé 🎸")[r.nextInt(5)]) }
                val tempo = measures.filterIndexed { i, _ -> i == 0 || r.nextInt(10) == 0 }.map { m ->
                    TempoChange(ScorePosition(m.id, if (r.nextInt(4) == 0) Fraction.of(r.nextLong(0, 4), 4).let { if (it < capacity(m)) it else Fraction.ZERO } else Fraction.ZERO),
                        listOf(10.0, 1000.0, 999.99, 120.0, 37.5, 10.01, 333.333)[r.nextInt(7)])
                }
                val text = measures.filter { r.nextInt(8) == 0 }.map { ScoreText(ids.next(), ScorePosition(it.id), "txt" + r.nextInt(99), TextKind.values().random(r)) }
                p = p.copy(sections = sections, tempo = tempo, text = text)
                val errors = ScoreValidator.validate(p).filter { it.severity == Severity.ERROR }
                check(errors.isEmpty()) { "generator built an invalid project: ${errors.take(3)}" }
                return p
            }

            /** Ties (same pitch), hammer-ons and slurs between adjacent notes on one string, also across barlines. */
            fun addLinks(p0: Project): Project {
                val tracks = p0.tracks.map { t ->
                    if (t.instrument.tuning.isEmpty() || r.nextInt(3) == 0) return@map t
                    val bars = t.staves[0].bars.map { b -> b.copy(voices = b.voices.map { v -> v.copy(beats = v.beats.toMutableList()) }) }
                    for ((mi, bar) in bars.withIndex()) for (v in bar.voices) {
                        val seq = v.beats as MutableList<Beat>
                        for (k in seq.indices) {
                            val a = seq[k]
                            val end = a.offset + a.duration.length()
                            val (bIdx, list) = if (k + 1 < seq.size && seq[k + 1].offset == end) k + 1 to seq
                                else if (k == seq.lastIndex && end == capacity(p0.measures[mi])) {
                                    val nv = bars.getOrNull(mi + 1)?.voices?.firstOrNull { it.index == v.index }?.beats as MutableList<Beat>?
                                    if (nv != null && nv.firstOrNull()?.offset == Fraction.ZERO) 0 to nv else continue
                                } else continue
                            if (r.nextInt(3) != 0 || a.notes.isEmpty()) continue
                            val b = list[bIdx]
                            if (b.explicitRest) continue
                            val oi = r.nextInt(a.notes.size); val o = a.notes[oi]
                            val existing = b.notes.indexOfFirst { it.string == o.string }
                            val target = if (existing >= 0) b.notes[existing].copy(pitch = o.pitch, fret = o.fret) else Note(ids.next(), o.pitch, o.string, o.fret)
                            val kind = listOf(LinkKind.TIE, LinkKind.TIE, LinkKind.HAMMER_ON, LinkKind.SLUR, LinkKind.PULL_OFF)[r.nextInt(5)]
                            if (kind == LinkKind.TIE && b.notes.any { it.links.any { l -> l.kind == LinkKind.TIE } }) continue
                            list[bIdx] = b.copy(notes = if (existing >= 0) b.notes.toMutableList().also { it[existing] = target } else b.notes + target)
                            seq[k] = seq[k].copy(notes = a.notes.toMutableList().also { it[oi] = o.copy(links = o.links + NoteLink(kind, target.id)) })
                        }
                    }
                    t.copy(staves = listOf(t.staves[0].copy(bars = bars.map { b -> b.copy(voices = b.voices.map { v -> v.copy(beats = v.beats.toList()) }) })))
                }
                return p0.copy(tracks = tracks)
            }

            fun request(p: Project): Req {
                val ti = r.nextInt(p.tracks.size)
                val base = if (r.nextBoolean()) LayoutStyle.EDIT else LayoutStyle.READ
                val (tab, std) = listOf(true to false, true to true, false to true)[r.nextInt(3)]
                val style = base.copy(showTab = tab, showStandard = std, rhythmInTab = r.nextInt(4) != 0)
                val width = when (r.nextInt(6)) { 0 -> null; 1 -> r.nextInt(100, 260).toFloat(); 2 -> 523f; 3 -> 760f; else -> r.nextInt(300, 2400).toFloat() }
                val grid = when (r.nextInt(8)) { 0 -> Fraction.of(1, 4); 1 -> Fraction.of(1, 8); 2 -> Fraction.of(1, 16); 3 -> Fraction.of(1, 32); 4 -> Fraction.of(1, 64); 5 -> Fraction.of(1, 12); else -> null }
                return Req(ti, style, width, grid, r.nextInt(0, 4), if (r.nextBoolean()) null else r.nextInt(0, 4), listOf(12f, 0f, 30f)[r.nextInt(3)])
            }
        }

        // ---------------------------------------------------------------- invariants
        fun yRange(g: Glyph): Pair<Float, Float> = when (g) {
            is LineGlyph -> minOf(g.y1, g.y2) - g.width / 2 to maxOf(g.y1, g.y2) + g.width / 2
            is RectGlyph -> g.y to g.y + g.h
            is TextGlyph -> g.y - g.size * 0.78f to g.y + g.size * 0.22f
            is OvalGlyph -> g.cy - maxOf(g.rx, g.ry) to g.cy + maxOf(g.rx, g.ry)
            is PathGlyph -> { var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var i = 0; val o = g.ops
                while (i < o.size) { val n = when (o[i].toInt()) { 0, 1 -> 2; 2 -> 6; else -> 0 }; var k = 1; while (k < n) { lo = minOf(lo, o[i + 1 + k]); hi = maxOf(hi, o[i + 1 + k]); k += 2 }; i += 1 + n }
                lo to hi }
        }
        fun floats(g: Glyph): FloatArray = when (g) {
            is LineGlyph -> floatArrayOf(g.x1, g.y1, g.x2, g.y2, g.width); is RectGlyph -> floatArrayOf(g.x, g.y, g.w, g.h)
            is TextGlyph -> floatArrayOf(g.x, g.y, g.size); is OvalGlyph -> floatArrayOf(g.cx, g.cy, g.rx, g.ry); is PathGlyph -> g.ops
        }
        fun describe(g: Glyph) = when (g) { is TextGlyph -> "Text('${g.text}',${g.ink},x=${g.x},y=${g.y})"; is PathGlyph -> "Path(${g.ink},${g.ops.take(6)})"; else -> g.toString() }

        fun fretLabel(n: Note, tieTarget: Boolean): String = when {
            tieTarget -> "(${n.fret})"
            Articulation.Mark(NoteMark.DEAD) in n.articulations -> "x"
            n.articulations.any { it is Articulation.Harmonic && it.kind == HarmonicKind.NATURAL } -> "<${n.fret}>"
            Articulation.Mark(NoteMark.GHOST) in n.articulations -> "(${n.fret})"
            else -> "${n.fret}"
        }

        fun fingerprint(l: ScoreLayout): String {
            val sb = StringBuilder()
            sb.append(l.width).append(',').append(l.height).append('\n')
            for (g in l.glyphs) sb.append(SvgExport.element(g)).append('\n')
            l.measures.forEach { sb.append(it).append('\n') }; l.columns.forEach { sb.append(it).append('\n') }; l.notes.forEach { sb.append(it).append('\n') }
            l.systems.forEach { sb.append("${it.index} ${it.top} ${it.bottom} ${it.x0} ${it.x1} ${it.staffTop} ${it.tabTop} ${it.tabBottom} ${it.stringY.toList()}\n") }
            return sb.toString()
        }

        /** All invariants for one layout; the codes name the invariant and the sub-case. */
        fun check(p: Project, q: Req, svg: Boolean = true): List<V> {
            val out = ArrayList<V>()
            fun v(code: String, msg: String) { if (skip.none { code.startsWith(it) } && out.none { it.code == code }) out += V(code, msg) }
            val req = q.toRequest(p)
            val l = try { ScoreLayouter.layout(req) } catch (t: Throwable) { return listOf(V("P0", "layout threw $t")) }
            val track = p.tracks[q.trackIndex]
            val fretted = track.instrument.tuning.isNotEmpty()
            val showTab = q.style.showTab && fretted
            val mIndex = p.measures.withIndex().associate { it.value.id to it.index }
            val boxByMeasure = l.measures.associateBy { it.measureId }
            val tieTargets = track.staves.flatMap { it.bars }.flatMap { it.voices }.flatMap { it.beats }.flatMap { it.notes }.flatMap { n -> n.links.filter { it.kind == LinkKind.TIE }.map { it.target } }.toSet()

            // P1: one NoteBox per note at its measure, offset and string.
            val boxesById = l.notes.groupBy { it.noteId }
            for (bar in track.staves[0].bars) for (vv in bar.voices) for (b in vv.beats) for (n in b.notes) {
                val boxes = boxesById[n.id].orEmpty()
                val sub = if (!fretted) (if (track.instrument.kind == InstrumentKind.PERCUSSION) "P1perc" else "P1unfretted") else if (!showTab) "P1std" else "P1tab"
                if (boxes.size != 1) v(sub, "note ${n.id} (bar ${mIndex[bar.measureId]}, @${b.offset}, voice ${vv.index}, string ${n.string}) has ${boxes.size} NoteBoxes")
                else { val nb = boxes[0]; if (nb.measureId != bar.measureId || nb.offset != b.offset || nb.string != n.string || nb.voice != vv.index) v("P1pos", "box $nb for note ${n.id} @${b.offset} string ${n.string}") }
            }
            // P2: tap at a box centre (notes first, as ScoreView) returns the note, or at least its beat and string.
            // Same result as ScoreView's notes.firstOrNull { contains }, via an x-sorted index (layouts have up to ~10^4 boxes).
            val order = l.notes.indices.sortedBy { l.notes[it].x }
            val xs = FloatArray(order.size) { l.notes[order[it]].x }
            val maxW = l.notes.maxOfOrNull { it.w } ?: 0f
            fun firstHit(cx: Float, cy: Float): NoteBox? {
                var hi = java.util.Arrays.binarySearch(xs, cx).let { if (it < 0) -it - 1 else it }
                while (hi < xs.size && xs[hi] <= cx) hi++
                var best = Int.MAX_VALUE
                var i = hi - 1
                while (i >= 0 && xs[i] >= cx - maxW - 1f) { val idx = order[i]; val it = l.notes[idx]; if (cx >= it.x && cx <= it.x + it.w && cy >= it.y && cy <= it.y + it.h && idx < best) best = idx; i-- }
                return if (best == Int.MAX_VALUE) null else l.notes[best]
            }
            val tab = l.systems.firstOrNull()?.stringY?.isNotEmpty() == true
            for (nb in l.notes) {
                val cx = nb.x + nb.w / 2; val cy = nb.y + nb.h / 2
                val hit = firstHit(cx, cy)
                if (hit == null) v("P2none", "no hit at centre of $nb")
                else if (hit.noteId != nb.noteId) {
                    if (hit.measureId != nb.measureId || hit.offset != nb.offset || hit.string != nb.string) v((if (!tab) "P2std:" else "") + (if (hit.measureId == nb.measureId) "P2beat" else "P2bar") + (if (hit.noteId in tieTargets || nb.noteId in tieTargets) ":tie" else if (hit.voice != nb.voice) ":voices" else ":plain"), "centre of note ${nb.noteId} (bar ${mIndex[nb.measureId]} @${nb.offset} s${nb.string}, x=${nb.x}..${nb.x + nb.w}) hits ${hit.noteId} (bar ${mIndex[hit.measureId]} @${hit.offset} s${hit.string}, x=${hit.x}..${hit.x + hit.w})")
                    else if (hit.voice != nb.voice) v("P2voice", "centre of ${nb.noteId} (voice ${nb.voice}) hits ${hit.noteId} (voice ${hit.voice}) — known: hit test ignores voice")
                }
                val str = l.stringAt(cy)
                if (tab && nb.string != null && str != nb.string) v("P2string", "stringAt(centre of ${nb.noteId}) = $str, expected ${nb.string}")
            }
            // P3: xFor monotone within a system and inside the measure box.
            val colsByMeasure = l.columns.groupBy { it.measureId }
            for (sys in l.systems) {
                var last = -Float.MAX_VALUE; var lastWhere = ""
                for (mb in l.measures.filter { it.system == sys.index }.sortedBy { it.index }) {
                    val m = p.measures[mb.index]
                    val len = capacity(m).toDouble()
                    val cols = colsByMeasure[mb.measureId].orEmpty().sortedBy { it.offset }
                    for (k in 1 until cols.size) if (cols[k].x <= cols[k - 1].x) v("P3cols", "bar ${mb.index}: column @${cols[k].offset} x=${cols[k].x} not right of @${cols[k - 1].offset} x=${cols[k - 1].x}")
                    val samples = (cols.map { it.offset.toDouble() } + (0..16).map { len * it / 16.0 } + cols.map { it.offset.toDouble() + 1e-6 }).filter { it in 0.0..len }.distinct().sorted()
                    for (o in samples) {
                        val x = l.xFor(mb.measureId, o) ?: run { v("P3null", "xFor(bar ${mb.index}) null"); null } ?: continue
                        if (!x.isFinite()) v("P3nan", "xFor(bar ${mb.index}, $o) = $x")
                        if (x < last - 0.01f) v("P3mono", "xFor(bar ${mb.index}, ${"%.4f".format(o)}) = $x < previous $last ($lastWhere)")
                        if (x < mb.x - 0.01f || x > mb.x + mb.width + 0.01f) v("P3box", "xFor(bar ${mb.index}, ${"%.4f".format(o)}) = $x outside measure ${mb.x}..${mb.x + mb.width}")
                        last = x; lastWhere = "bar ${mb.index} @${"%.4f".format(o)}"
                    }
                }
            }
            // P4: columnAt(column.x, y inside the system) returns the column.
            for (c in l.columns) {
                val sys = l.systems[c.system]
                val ys = listOf(sys.top + 0.5f, (sys.tabTop + sys.tabBottom) / 2, (sys.staffTop + sys.staffBottom) / 2, sys.bottom - 0.5f)
                for (y in ys) {
                    val got = l.columnAt(c.x, y)
                    if (got != c) { v(if (got == null) "P4null" else if (got.measureId != c.measureId) "P4bar" else "P4col", "columnAt(${c.x}, $y) for bar ${c.measureIndex} @${c.offset} returned ${got?.let { "bar ${it.measureIndex} @${it.offset} x=${it.x}" }}"); break }
                }
                val mb = boxByMeasure.getValue(c.measureId)
                if (c.x < mb.contentX - 0.01f || c.x >= mb.x + mb.width) v("P4inside", "column bar ${c.measureIndex} @${c.offset} x=${c.x} outside content ${mb.contentX}..${mb.x + mb.width}")
            }
            // P5: finite, inside the layout, inside its system's band; glyphsBySystem buckets agree.
            val bucketOf = java.util.IdentityHashMap<Glyph, Int>()
            l.glyphsBySystem.forEachIndexed { i, list -> list.forEach { bucketOf[it] = i } }
            for (g in l.glyphs) {
                val fs = floats(g)
                if (fs.any { !it.isFinite() }) { v("P5nan", describe(g)); continue }
                val (y0, y1) = yRange(g)
                if (g.minX < -EPS || g.maxX > l.width + EPS) v("P5x:${g::class.simpleName}:${(g as? TextGlyph)?.let { if (it.text.all { c -> c.isDigit() }) "digits" else it.text } ?: (g as? LineGlyph)?.ink ?: (g as? PathGlyph)?.ink ?: ""}", "${describe(g)} x ${g.minX}..${g.maxX} outside 0..${l.width}")
                if (y0 < -EPS || y1 > l.height + EPS) v("P5y", "${describe(g)} y $y0..$y1 outside 0..${l.height}")
                val bucket = bucketOf[g] ?: -1
                if (bucket == l.systems.size) { v("P5orphan", "${describe(g)} anchored outside every system (dropped by PDF)"); continue }
                val sys = l.systems[bucket]
                if (y0 < sys.top - EPS || y1 > sys.bottom + EPS) v("P5band:${g::class.simpleName}:${(g as? TextGlyph)?.text?.takeIf { it.length < 8 && !it.all { c -> c.isDigit() } } ?: (g as? TextGlyph)?.let { "digits" } ?: (g as? PathGlyph)?.ink ?: (g as? LineGlyph)?.ink ?: (g as? OvalGlyph)?.ink ?: ""}", "${describe(g)} y $y0..$y1 outside system ${sys.index} ${sys.top}..${sys.bottom}")
            }
            for (sys in l.systems) for (k in sys.stringY.indices) if (!sys.stringY[k].isFinite()) v("P5nan", "stringY")
            if (!l.width.isFinite() || !l.height.isFinite()) v("P5nan", "layout size")
            // P6: systems ordered without overlap; measures of a system do not overlap.
            for (k in 1 until l.systems.size) if (l.systems[k].top < l.systems[k - 1].bottom - 0.01f) v("P6sys", "system $k top ${l.systems[k].top} < previous bottom ${l.systems[k - 1].bottom}")
            for (sys in l.systems) {
                val ms = l.measures.filter { it.system == sys.index }.sortedBy { it.index }
                for (k in 1 until ms.size) if (ms[k].x < ms[k - 1].x + ms[k - 1].width - 0.01f) v("P6bar", "bar ${ms[k].index} x=${ms[k].x} overlaps bar ${ms[k - 1].index} ending ${ms[k - 1].x + ms[k - 1].width}")
                if (q.width != null && ms.isNotEmpty() && ms.last().x + ms.last().width > q.width + 0.5f && ms.size > 1) v("P6wide", "system ${sys.index} with ${ms.size} bars ends at ${ms.last().x + ms.last().width} > width ${q.width}")
            }
            // P9: deterministic.
            val again = ScoreLayouter.layout(req)
            if (fingerprint(again) != fingerprint(l)) v("P9", "two layouts of the same request differ")
            // P8: SVG well-formed, every fret number, no NaN.
            if (svg) {
                val text = try { SvgExport.render(p, listOf(q.trackIndex), q.style, q.width ?: 760f) } catch (t: Throwable) { v("P8throw", "$t"); null }
                if (text != null) {
                    try { javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(text))) } catch (t: Throwable) { v("P8xml", "${t.message}") }
                    if (text.contains("NaN") || text.contains("Infinity")) v("P8nan", "SVG contains NaN/Infinity")
                    if (showTab) for (bar in track.staves[0].bars) for (vv in bar.voices) for (b in vv.beats) for (n in b.notes) {
                        if (n.string == null) continue
                        val label = fretLabel(n, n.id in tieTargets).replace("<", "&lt;").replace(">", "&gt;")
                        if (!text.contains(">$label</text>")) v("P8fret", "SVG lacks fret label '$label' of note ${n.id}")
                    }
                }
            }
            return out
        }

        // ---------------------------------------------------------------- shrinking
        fun valid(p: Project) = try { ScoreValidator.validate(p).none { it.severity == Severity.ERROR } } catch (t: Throwable) { false }
        fun pruneLinks(p: Project): Project {
            val ids = p.tracks.flatMap { t -> t.staves.flatMap { s -> s.bars.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { it.notes.map { n -> n.id } } } } } }.toSet()
            return mapNotes(p) { n -> n.copy(links = n.links.filter { it.target in ids }) }
        }
        fun mapNotes(p: Project, f: (Note) -> Note): Project = p.copy(tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { s -> s.copy(bars = s.bars.map { b -> b.copy(voices = b.voices.map { v -> v.copy(beats = v.beats.map { bt -> bt.copy(notes = bt.notes.map(f)) }) }) }) }) })
        fun mapBeats(p: Project, f: (Beat) -> Beat?): Project = p.copy(tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { s -> s.copy(bars = s.bars.map { b -> b.copy(voices = b.voices.map { v -> v.copy(beats = v.beats.mapNotNull(f)) }) }) }) })

        fun candidates(p: Project, q: Req): Sequence<Pair<Project, Req>> = sequence {
            // Other tracks.
            if (p.tracks.size > 1) yield(p.copy(tracks = listOf(p.tracks[q.trackIndex])) to q.copy(trackIndex = 0))
            // Measures: halves, then single ones.
            val n = p.measures.size
            fun dropMeasures(keep: (Int) -> Boolean): Project {
                val ms = p.measures.filterIndexed { i, _ -> keep(i) }.mapIndexed { i, m -> if (i == 0 || m.pickupLength == null) m else m.copy(pickupLength = null) }
                val idSet = ms.map { it.id }.toSet()
                val pp = p.copy(measures = ms, tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { s -> s.copy(bars = s.bars.filter { it.measureId in idSet }) }) },
                    sections = p.sections.filter { it.measureId in idSet }, tempo = p.tempo.filter { it.position.measureId in idSet }, text = p.text.filter { it.position.measureId in idSet })
                return pruneLinks(pp)
            }
            if (n > 1) {
                var chunk = n / 2
                while (chunk >= 1) { var s = 0; while (s < n) { val a = s; val b = s + chunk; yield(dropMeasures { it < a || it >= b } to q); s += chunk }; chunk /= 2 }
            }
            yield(p.copy(sections = emptyList()) to q); yield(p.copy(tempo = emptyList()) to q); yield(p.copy(text = emptyList()) to q)
            yield(p.copy(measures = p.measures.map { it.copy(repeat = RepeatMark(), navigation = emptyList(), forcedSystemBreak = false) }) to q)
            yield(p.copy(measures = p.measures.map { it.copy(key = KeySignature()) }) to q)
            // Request simplifications.
            if (q.width != null) yield(p to q.copy(width = null))
            if (q.editGrid != null) yield(p to q.copy(editGrid = null))
            if (q.activeVoice != null) yield(p to q.copy(activeVoice = null))
            if (q.margin != 12f) yield(p to q.copy(margin = 12f))
            if (!q.style.rhythmInTab) yield(p to q.copy(style = q.style.copy(rhythmInTab = true)))
            if (q.style.showStandard && q.style.showTab) yield(p to q.copy(style = q.style.copy(showStandard = false)))
            // Beats and notes.
            val beats = p.tracks[q.trackIndex].staves[0].bars.flatMap { it.voices }.flatMap { it.beats }
            if (beats.isNotEmpty()) {
                // All other voices.
                yield(pruneLinks(p.copy(tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { s -> s.copy(bars = s.bars.map { b -> b.copy(voices = b.voices.map { v -> if (v.index == 0) v else v.copy(beats = emptyList()) }) }) }) })) to q)
                var chunk = beats.size / 2
                while (chunk >= 1) { var s = 0; while (s < beats.size) { val drop = beats.subList(s, minOf(beats.size, s + chunk)).map { it.id }.toSet(); yield(pruneLinks(mapBeats(p) { if (it.id in drop) null else it }) to q); s += chunk }; chunk /= 2 }
                for (b in beats) {
                    if (b.fermata != null || b.strum != null) yield(mapBeats(p) { if (it.id == b.id) it.copy(fermata = null, strum = null) else it } to q)
                    if (b.notes.size > 1) for (nn in b.notes) yield(pruneLinks(mapBeats(p) { if (it.id == b.id) it.copy(notes = it.notes - nn) else it }) to q)
                    if (b.duration.dots > 0 || b.duration.tuplets.isNotEmpty()) yield(mapBeats(p) { if (it.id == b.id) it.copy(duration = it.duration.copy(dots = 0, tuplets = emptyList())) else it } to q)
                }
                for (nn in beats.flatMap { it.notes }) {
                    if (nn.articulations.isNotEmpty()) { yield(mapNotes(p) { if (it.id == nn.id) it.copy(articulations = emptyList()) else it } to q); for (a in nn.articulations) yield(mapNotes(p) { if (it.id == nn.id) it.copy(articulations = it.articulations - a) else it } to q) }
                    if (nn.links.isNotEmpty()) yield(mapNotes(p) { if (it.id == nn.id) it.copy(links = emptyList()) else it } to q)
                }
            }
        }

        fun shrink(p0: Project, q0: Req, code: String): Pair<Project, Req> {
            var p = p0; var q = q0
            var progress = true; var rounds = 0
            while (progress && rounds++ < 60) {
                progress = false
                for ((cp, cq) in candidates(p, q)) {
                    if (cp == p && cq == q) continue
                    if (!valid(cp) || cq.trackIndex !in cp.tracks.indices) continue
                    if (check(cp, cq, svg = code.startsWith("P8")).any { it.code == code }) { p = cp; q = cq; progress = true; break }
                }
            }
            return p to q
        }

        fun dump(p: Project, q: Req): String {
            val sb = StringBuilder()
            sb.append("REQ track=${q.trackIndex} width=${q.width} grid=${q.editGrid} editVoice=${q.editVoice} active=${q.activeVoice} margin=${q.margin} style=${if (q.style.tabLine == 24f) "EDIT" else "READ"}(tab=${q.style.showTab}, std=${q.style.showStandard}, rhythm=${q.style.rhythmInTab})\n")
            p.tracks.forEachIndexed { ti, t -> sb.append("track $ti ${t.instrument.kind} tuning=${t.instrument.tuning} capo=${t.instrument.capoByString} maxFret=${t.instrument.maxFret} transp=${t.instrument.transposition}\n") }
            sb.append("sections=${p.sections.map { "${p.measures.indexOfFirst { m -> m.id == it.measureId }}:${it.label}" }} tempo=${p.tempo.map { "${p.measures.indexOfFirst { m -> m.id == it.position.measureId }}@${it.position.offset}=${it.quarterNotesPerMinute}" }}\n")
            p.measures.forEachIndexed { i, m ->
                sb.append("bar $i ${m.signature.numerator}/${m.signature.denominator} key=${m.key.fifths} pickup=${m.pickupLength} repeat=${m.repeat} nav=${m.navigation.map { it.kind }} break=${m.forcedSystemBreak}\n")
                val bar = p.tracks[q.trackIndex].staves[0].bars[i]
                for (v in bar.voices) for (b in v.beats) sb.append("   v${v.index} @${b.offset} ${b.duration.denominator}${".".repeat(b.duration.dots)}${b.duration.tuplets.joinToString("") { "(${it.actual}:${it.normal})" }} rest=${b.explicitRest} ferm=${b.fermata != null} strum=${b.strum != null} notes=${b.notes.map { "${it.id}[s${it.string} f${it.fret} m${it.pitch.midiNumber} ${it.articulations} ${it.links}]" }}\n")
            }
            return sb.toString()
        }
    }

    @Test fun randomProjectsSatisfyLayoutInvariants() {
        val count = (System.getProperty("qa15.n") ?: "40").toInt()
        val seed0 = (System.getProperty("qa15.seed") ?: "15").toLong()
        val doShrink = System.getProperty("qa15.shrink") != "false"
        val perProject = (System.getProperty("qa15.req") ?: "4").toInt()
        val counts = sortedMapOf<String, Int>()
        val firsts = LinkedHashMap<String, Triple<Long, Project, Req>>()
        val msgs = HashMap<String, String>()
        var layouts = 0; var notes = 0; var bars = 0
        for (k in 0 until count) {
            val seed = seed0 * 1_000_003 + k
            val g = Gen(seed)
            val p = g.project()
            bars += p.measures.size
            repeat(perProject) {
                val q = g.request(p)
                layouts++
                notes += p.tracks[q.trackIndex].staves[0].bars.sumOf { b -> b.voices.sumOf { v -> v.beats.sumOf { it.notes.size } } }
                for (viol in check(p, q, svg = it == 0)) {
                    counts[viol.code] = (counts[viol.code] ?: 0) + 1
                    if (viol.code !in firsts) { firsts[viol.code] = Triple(seed, p, q); msgs[viol.code] = viol.msg }
                }
            }
        }
        val report = StringBuilder("QA15: $count projects, $layouts layouts, $bars bars, $notes notes in laid-out tracks\n")
        counts.forEach { (c, n) -> report.append("  $c: $n layouts — first: ${msgs[c]}\n") }
        if (doShrink) for ((code, t) in firsts) {
            val (p, q) = shrink(t.second, t.third, code)
            val msg = check(p, q).firstOrNull { it.code == code }?.msg
            report.append("\n=== $code (seed ${t.first}) minimal: ${p.measures.size} bars, ${p.tracks[q.trackIndex].staves[0].bars.sumOf { b -> b.voices.sumOf { v -> v.beats.size } }} beats\n  $msg\n").append(dump(p, q))
        }
        println(report)
        java.io.File(System.getProperty("qa15.out") ?: "build/qa15-report.txt").also { it.parentFile?.mkdirs() }.writeText(report.toString())
        assertTrue(report.toString(), counts.isEmpty())
    }

    /** P7: 300 dense bars lay out in < 300 ms; glyph count grows linearly in the bar count. */
    @Test fun layoutTimeAndGlyphCountScaleLinearly() {
        val report = StringBuilder()
        for (seed in 1L..(System.getProperty("qa15.p7seeds") ?: "6").toLong()) {
            val p = Gen(seed * 7919).project(300)
            for (ti in p.tracks.indices) for (style in listOf(LayoutStyle.EDIT, LayoutStyle.READ.copy(showStandard = true))) for (width in listOf(null, 760f)) {
                val counts = listOf(50, 100, 200, 300).map { n ->
                    val pn = p.copy(measures = p.measures.take(n), tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.take(n)) }) })
                    val req = LayoutRequest(pn, ti, style, width, Fraction.of(1, 16))
                    repeat(2) { ScoreLayouter.layout(req) }
                    val times = List(3) { val t0 = System.nanoTime(); ScoreLayouter.layout(req); (System.nanoTime() - t0) / 1e6 }
                    Triple(n, ScoreLayouter.layout(req).glyphs.size, times.sorted()[1])
                }
                val notes = p.tracks[ti].staves[0].bars.sumOf { b -> b.voices.sumOf { v -> v.beats.sumOf { it.notes.size } } }
                val line = "seed $seed track $ti ${p.tracks[ti].instrument.kind} notes=$notes width=$width std=${style.showStandard}: " + counts.joinToString { "${it.first} bars ${it.second} glyphs ${"%.0f".format(it.third)} ms" }
                report.append(line).append('\n')
                val (_, g300, t300) = counts.last()
                if (t300 > 300) report.append("  P7time: 300 bars took ${"%.0f".format(t300)} ms\n")
                val per50 = counts.first().second / 50.0; val per300 = g300 / 300.0
                if (per300 > per50 * 1.5) report.append("  P7glyphs: glyphs per bar grow ${"%.1f".format(per50)} -> ${"%.1f".format(per300)}\n")
            }
        }
        println(report)
        java.io.File(System.getProperty("qa15.p7out") ?: "build/qa15-p7.txt").also { it.parentFile?.mkdirs() }.writeText(report.toString())
        assertTrue(report.toString(), !report.contains("P7"))
    }
}
