package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Engraving parameters. [EDIT] keeps touch targets large; [READ] is compact for practice, pages and export. */
data class LayoutStyle(
    val tabLine: Float = 24f,
    val staffSpace: Float = 8f,
    val fretText: Float = 15f,
    val column16: Float = 30f,
    val columnExponent: Float = 0.6f,
    val showTab: Boolean = true,
    val showStandard: Boolean = false,
    val rhythmInTab: Boolean = true,
    val measurePadding: Float = 10f,
    /** Space below systems without rhythm in the TAB (standard staff, TAB + standard), which may also hold stems and ties. */
    val systemGap: Float = 26f,
    /** Staff and string line width. The editor draws them heavier on the dark surface; READ (pages, SVG, PDF) keeps the fine 0.8. */
    val lineWidth: Float = 1.2f,
    /**
     * Minimum height of the top lane (bar number, section, tempo, endings, jump marks) above the staves. A TAB-only system
     * without effect rows starts its top string right below the lane and moves it down only as far as the ink above the top
     * string (fret chips, arcs, bends) needs where it meets a lane label (QA 20 / Partitur-Kopf).
     */
    val numberLane: Float = 18f,
    /** TAB with rhythm: space between the lowest rhythm ink (stems, beams, tuplet numbers) and the next system. */
    val inkGap: Float = 14f,
) {
    companion object {
        val EDIT = LayoutStyle()
        val READ = LayoutStyle(tabLine = 13f, staffSpace = 7f, fretText = 10.5f, column16 = 19f, measurePadding = 8f, systemGap = 20f, lineWidth = 0.8f, numberLane = 22f, inkGap = 12f)
    }
}

data class LayoutRequest(
    val project: Project,
    val trackIndex: Int,
    val style: LayoutStyle = LayoutStyle.EDIT,
    /** null: one continuous system; otherwise systems wrap to this width. */
    val width: Float? = null,
    /** Empty grid slots for entering notes in the edited voice (null in reading mode). */
    val editGrid: Fraction? = null,
    val editVoice: Int = 0,
    /** Voices other than this one are drawn muted; null draws all voices equally. */
    val activeVoice: Int? = null,
    val margin: Float = 12f,
)

data class MeasureBox(val measureId: EntityId, val index: Int, val system: Int, val x: Float, val contentX: Float, val width: Float, val top: Float, val bottom: Float)
data class Column(val measureId: EntityId, val measureIndex: Int, val offset: Fraction, val x: Float, val width: Float, val system: Int, val virtual: Boolean)
data class NoteBox(val noteId: EntityId, val measureId: EntityId, val offset: Fraction, val voice: Int, val string: Int?, val x: Float, val y: Float, val w: Float, val h: Float)
/**
 * Hit region of a drawn time signature (bar 1, every signature change and the courtesy repeat at the start of a wrapped
 * system). [courtesy] marks a repeat whose signature was set in an earlier bar.
 */
data class SignatureBox(val measureId: EntityId, val index: Int, val system: Int, val x: Float, val y: Float, val w: Float, val h: Float, val courtesy: Boolean)
class SystemBox(val index: Int, val top: Float, val bottom: Float, val x0: Float, val x1: Float, val staffTop: Float, val staffBottom: Float, val tabTop: Float, val tabBottom: Float,
                /** y of each string index (0 = lowest string) in TAB, empty without TAB. */ val stringY: FloatArray)

class ScoreLayout(val width: Float, val height: Float, val glyphs: List<Glyph>, val measures: List<MeasureBox>, val columns: List<Column>, val notes: List<NoteBox>, val systems: List<SystemBox>,
                  val signatures: List<SignatureBox> = emptyList()) {
    /** x position for a cursor or playhead at [offset] in a measure, interpolated between columns. */
    fun xFor(measureId: EntityId, offset: Fraction): Float? = xFor(measureId, offset.toDouble())
    /** The time signature drawn under (x, y), if any; a long press there edits that bar's signature. */
    fun signatureAt(x: Float, y: Float): SignatureBox? = signatures.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
    // Per-measure and per-system indexes, built once: the playhead and the view query them every frame.
    private val columnsByMeasure: Map<EntityId, List<Column>> by lazy { columns.groupBy { it.measureId } }
    private val measureById: Map<EntityId, MeasureBox> by lazy { measures.associateBy { it.measureId } }
    /** Glyphs grouped by the system their anchor lies in; the extra last list holds glyphs outside every system. */
    val glyphsBySystem: List<List<Glyph>> by lazy {
        val buckets = List(systems.size + 1) { ArrayList<Glyph>() }
        for (g in glyphs) {
            val y = g.anchorY()
            var lo = 0; var hi = systems.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (systems[mid].bottom < y) lo = mid + 1 else hi = mid }
            buckets[if (lo < systems.size && y >= systems[lo].top) lo else systems.size] += g
        }
        buckets
    }
    fun xFor(measureId: EntityId, offset: Double): Float? {
        val cols = columnsByMeasure[measureId].orEmpty()
        if (cols.isEmpty()) return measures.firstOrNull { it.measureId == measureId }?.let { it.contentX }
        var prev = cols.first()
        if (offset <= prev.offset.toDouble()) return prev.x
        for (c in cols.drop(1)) {
            if (offset < c.offset.toDouble()) {
                val t = (offset - prev.offset.toDouble()) / (c.offset.toDouble() - prev.offset.toDouble())
                return (prev.x + (c.x - prev.x) * t).toFloat()
            }
            prev = c
        }
        val m = measureById.getValue(measureId)
        val length = prev.offset.toDouble() + 1e-9
        // Towards the barline, but never backwards when the last column already sits close to it.
        val end = max(prev.x, m.x + m.width - 6f)
        return (prev.x + (end - prev.x) * ((offset - prev.offset.toDouble()) / max(1e-9, measureLength(measureId) - length))).toFloat().coerceIn(prev.x, max(prev.x, m.x + m.width - 4f))
    }
    private val lengths = HashMap<EntityId, Double>()
    internal fun registerLength(id: EntityId, length: Double) { lengths[id] = length }
    private fun measureLength(id: EntityId) = lengths[id] ?: 1.0
    fun systemAt(y: Float): SystemBox? = systems.firstOrNull { y >= it.top && y < it.bottom } ?: systems.lastOrNull { y >= it.top }
    /** The bar under (x, y): inside the system under y and between its barlines; null beside the bars. */
    fun measureAt(x: Float, y: Float): MeasureBox? {
        val system = systemAt(y) ?: return null
        return measures.firstOrNull { it.system == system.index && x >= it.x && x < it.x + it.width }
    }
    /**
     * The bar nearest to (x, y) for a drag (long-press range selection): like [measureAt] inside the bars, otherwise the
     * first/last bar of the row when the finger is left/right of it; above the first system the first row counts.
     */
    fun measureNear(x: Float, y: Float): MeasureBox? {
        val system = systemAt(y) ?: systems.firstOrNull() ?: return null
        val row = measures.filter { it.system == system.index }
        if (row.isEmpty()) return null
        row.firstOrNull { x >= it.x && x < it.x + it.width }?.let { return it }
        val first = row.minBy { it.x }
        return if (x < first.x) first else row.maxBy { it.x + it.width }
    }
    /** Nearest column of the measure under (x, y). */
    fun columnAt(x: Float, y: Float): Column? {
        val measure = measureAt(x, y) ?: return null
        return columns.filter { it.measureId == measure.measureId }.minByOrNull { abs(it.x - x) }
    }
    /** String index nearest to y in the system's TAB, or null outside the TAB staff. */
    fun stringAt(y: Float): Int? {
        val system = systemAt(y) ?: return null
        if (system.stringY.isEmpty()) return null
        val spacing = if (system.stringY.size > 1) abs(system.stringY[0] - system.stringY[1]) else 20f
        if (y < system.tabTop - spacing * 1.2f || y > system.tabBottom + spacing * 1.2f) return null
        return system.stringY.indices.minByOrNull { abs(system.stringY[it] - y) }
    }
    fun glyphsBetween(x0: Float, x1: Float): List<Glyph> = glyphs.filter { it.maxX >= x0 && it.minX <= x1 }
}

/** Builds the display list for one track. Pure and deterministic; safe to run off the UI thread. */
object ScoreLayouter {
    fun layout(r: LayoutRequest): ScoreLayout = Builder(r).build()

    internal class ColumnSpec(val offset: Fraction, val duration: Fraction, val virtual: Boolean, val minWidth: Float,
                              /** Standard-notation ink left/right of the anchor (heads, displaced seconds, ledger lines, ...); 0 without heads. */
                              val headLeft: Float = 0f, val headRight: Float = 0f)
    internal class MeasureSpec(val index: Int, val measure: Measure, val bar: Bar?, val columns: List<ColumnSpec>, val showTime: Boolean, val showKey: Boolean, var x: Float = 0f, var width: Float = 0f, var system: Int = 0, var columnXs: FloatArray = FloatArray(0), var columnWs: FloatArray = FloatArray(0), var contentX: Float = 0f)

    internal class PendingTie(val target: EntityId, val x: Float, val y: Float, val system: Int, val systemEnd: Float, val height: Float, val ink: Ink, val standard: Boolean)

    internal class Builder(val r: LayoutRequest) {
        fun add(g: Glyph) { glyphs += g }
        fun styleOf(): LayoutStyle = style
        val p = r.project
        val track = p.tracks[r.trackIndex]
        /** Once per layout: for unfretted tracks it averages every note (it was recomputed per measure, quadratic). */
        val clef: Standard.Clef by lazy { Standard.clefFor(track) }
        val style = r.style
        val fretted = track.instrument.tuning.isNotEmpty()
        val percussion = track.instrument.kind == InstrumentKind.PERCUSSION
        val showTab = style.showTab && fretted
        val showStandard = style.showStandard || !fretted
        val rhythmInTab = showTab && !showStandard && style.rhythmInTab
        val glyphs = ArrayList<Glyph>(4096)
        val measureBoxes = ArrayList<MeasureBox>()
        val columns = ArrayList<Column>()
        val noteBoxes = ArrayList<NoteBox>()
        val signatureBoxes = ArrayList<SignatureBox>()
        val systems = ArrayList<SystemBox>()
        val staff = track.staves.firstOrNull()
        val strings = track.instrument.tuning.size
        val tempoByMeasure = p.tempo.groupBy { it.position.measureId }
        val sectionByMeasure = p.sections.associateBy { it.measureId }
        val noteIndex = HashMap<EntityId, Pair<Int, Note>>() // note id -> measure index, note
        val pendingTies = ArrayList<PendingTie>()
        val standardHeads = HashMap<EntityId, Triple<Float, Float, Int>>()

        fun build(): ScoreLayout {
            val specs = p.measures.mapIndexed { i, m -> spec(i, m) }
            staff?.bars?.forEachIndexed { mi, bar -> bar.voices.forEach { v -> v.beats.forEach { b -> b.notes.forEach { n -> noteIndex[n.id] = mi to n } } } }
            val headerWidth = header()
            val systemsSpecs = arrange(specs, headerWidth)
            var y = r.margin
            var maxX = 0f
            systemsSpecs.forEachIndexed { si, list ->
                y = drawSystem(si, list, y, headerWidth)
                maxX = max(maxX, list.last().x + list.last().width)
            }
            resolveTies()
            val layout = ScoreLayout(maxX + r.margin, y + r.margin, glyphs, measureBoxes, columns, noteBoxes, systems, signatureBoxes)
            specs.forEach { layout.registerLength(it.measure.id, length(it.measure).toDouble()) }
            return layout
        }

        fun length(m: Measure) = m.pickupLength ?: m.signature.length()

        /** Ties are drawn once every note has a position, so they can cross barlines (and end at the system edge). */
        fun resolveTies() {
            // Indexed once: a lookup per tie over all notes made layout quadratic in long tied scores.
            val boxById = HashMap<EntityId, NoteBox>(noteBoxes.size * 2)
            for (nb in noteBoxes) boxById.putIfAbsent(nb.noteId, nb)
            val systemByMeasure = HashMap<EntityId, Int>(measureBoxes.size * 2)
            for (mb in measureBoxes) systemByMeasure.putIfAbsent(mb.measureId, mb.system)
            for (t in pendingTies) {
                val target = if (t.standard) standardHeads[t.target]?.let { Triple(it.first - style.staffSpace * 0.6f, it.second, it.third) }
                    else boxById[t.target]?.let { nb -> Triple(nb.x + 1f, t.y, systemByMeasure[nb.measureId] ?: -1) }
                val x1 = if (target != null && target.third == t.system && target.first > t.x) target.first else t.systemEnd + 4f
                val a = t.x; val b = x1
                glyphs += PathGlyph.Builder().move(a, t.y).cubic(a + (b - a) * 0.25f, t.y + t.height, a + (b - a) * 0.75f, t.y + t.height, b, t.y).build(t.ink, false, 1f)
            }
        }

        fun spec(i: Int, m: Measure): MeasureSpec {
            val bar = staff?.bars?.getOrNull(i)?.takeIf { it.measureId == m.id } ?: staff?.bars?.firstOrNull { it.measureId == m.id }
            val len = length(m)
            val offsets = HashMap<Fraction, Boolean>()
            offsets[Fraction.ZERO] = true
            bar?.voices?.forEach { v -> v.beats.forEach { b -> if (b.offset < len) offsets[b.offset] = false } }
            val grid = r.editGrid
            if (grid != null && grid > Fraction.ZERO) {
                val beats = bar?.voices?.firstOrNull { it.index == r.editVoice }?.beats.orEmpty()
                var o = Fraction.ZERO; var guard = 0
                while (o < len && guard++ < 4096) { // 4096 = longest valid bar (64/1) on a 1/64 grid
                    val covered = beats.any { b -> o >= b.offset && o < b.offset + b.duration.length() && o != b.offset }
                    if (!covered && o !in offsets) offsets[o] = true
                    o += grid
                }
            }
            val sorted = offsets.keys.sorted()
            val multiVoice = (bar?.voices?.count { it.beats.isNotEmpty() } ?: 0) > 1
            val cols = sorted.mapIndexed { k, o ->
                val next = sorted.getOrNull(k + 1) ?: len
                val d = next + o * Fraction.of(-1)
                val content = bar?.voices?.flatMap { v -> v.beats.filter { it.offset == o } }.orEmpty()
                val digits = content.flatMap { it.notes }.maxOfOrNull { (it.fret ?: 0).toString().length + if (Articulation.Mark(NoteMark.GHOST) in it.articulations || it.articulations.any { a -> a is Articulation.Harmonic } || isTieTarget(it)) 2 else 0 } ?: 1
                val grace = content.any { b -> b.notes.any { n -> n.articulations.any { it is Articulation.Grace } } }
                val (headLeft, headRight) = if (showStandard && content.isNotEmpty()) Standard.headExtent(this, bar?.voices?.flatMap { v -> v.beats.filter { it.offset == o }.map { v.index to it } }.orEmpty(), m.key, multiVoice) else 0f to 0f
                ColumnSpec(o, d, offsets[o] == true && content.isEmpty(), digits * style.fretText * 0.62f + style.fretText * 0.9f + if (grace) style.fretText else 0f, headLeft, headRight)
            }
            val prev = p.measures.getOrNull(i - 1)
            return MeasureSpec(i, m, bar, cols, prev == null || prev.signature != m.signature, prev == null || prev.key != m.key)
        }

        fun columnWidth(c: ColumnSpec): Float {
            val ratio = (c.duration.toDouble() * 16.0).coerceAtLeast(0.25)
            return max(c.minWidth, (style.column16 * ratio.pow(style.columnExponent.toDouble())).toFloat())
        }

        fun natural(s: MeasureSpec, first: Boolean): Float {
            var w = style.measurePadding + s.columns.sumOf { columnWidth(it).toDouble() }.toFloat() + style.measurePadding * 0.6f
            if (s.showTime && !first) w += timeWidth()
            if (s.showKey && showStandard && !first) w += keyWidth(s.measure.key)
            if (s.measure.repeat.starts) w += 8f
            if (s.measure.repeat.endCount != null) w += 8f
            // The top lane (number, section, tempo, ending, jump marks) must fit inside the bar, or it runs into the next one.
            return max(w, laneWidth(s.measure))
        }
        fun laneWidth(m: Measure): Float {
            var lane = 16f
            sectionByMeasure[m.id]?.let { lane += TextMetrics.width(it.label, 10f, true) + 16f }
            tempoByMeasure[m.id]?.forEach { t -> if (t.position.offset == Fraction.ZERO) lane += TextMetrics.width("♩ = ${formatBpm(t.quarterNotesPerMinute)}", 9.5f, true) + 6f }
            if (m.repeat.alternateEndings.isNotEmpty()) lane += TextMetrics.width(m.repeat.alternateEndings.sorted().joinToString(", ") + ".", 9f, true) + 6f
            m.navigation.forEach { lane += TextMetrics.width("To Coda", 9.5f, true) + 6f }
            return lane
        }
        fun timeWidth() = (if (showTab) style.tabLine * 1.4f else style.staffSpace * 2.4f) + 4f
        fun keyWidth(k: KeySignature) = abs(k.fifths) * style.staffSpace * 1.1f + if (k.fifths != 0) 4f else 0f
        fun header(): Float = (if (showStandard) style.staffSpace * 3.2f else 0f).coerceAtLeast(if (showTab) style.fretText * 1.6f else 0f) + 10f

        fun arrange(specs: List<MeasureSpec>, header: Float): List<List<MeasureSpec>> {
            val width = r.width
            val start = r.margin + header
            if (width == null) {
                var x = start
                specs.forEachIndexed { i, s ->
                    s.system = 0; s.x = x; s.width = natural(s, i == 0) + (if (i == 0) firstExtras(s) else 0f) + headShift(s, 1f)
                    place(s, i == 0); x += s.width
                }
                return listOf(specs)
            }
            val result = ArrayList<MutableList<MeasureSpec>>()
            var line = ArrayList<MeasureSpec>(); var used = 0f
            val avail = width - start - r.margin
            // Line breaks count the room noteheads need at the natural spacing (headShift), so a full line still fits.
            specs.forEach { s ->
                val w = natural(s, line.isEmpty()) + (if (line.isEmpty()) firstExtras(s) else 0f) + headShift(s, 1f)
                if (line.isNotEmpty() && (used + w > avail || s.measure.forcedSystemBreak)) { result += line; line = ArrayList(); used = 0f }
                val ww = natural(s, line.isEmpty()) + (if (line.isEmpty()) firstExtras(s) else 0f) + headShift(s, 1f)
                line += s; used += ww
            }
            if (line.isNotEmpty()) result += line
            result.forEachIndexed { si, list ->
                val naturalWidths = list.mapIndexed { k, s -> natural(s, k == 0) + if (k == 0) firstExtras(s) else 0f }
                // Signatures and repeat signs keep their size; only the note columns stretch or compress
                // (compressing them pushed signatures onto the clef/TAB letters and columns past the barline).
                val fixed = list.mapIndexed { k, s -> fixedWidth(s, k == 0) }
                val total = naturalWidths.sum(); val fixedTotal = fixed.sum()
                fun widthAt(k: Int, st: Float) = fixed[k] + (naturalWidths[k] - fixed[k]) * st + headShift(list[k], st)
                var stretch = if (si < result.lastIndex || total > avail * 0.7f) ((avail - fixedTotal) / (total - fixedTotal).coerceAtLeast(1f)).coerceIn(0.5f, 3f) else 1f
                // Head shifts grow as the columns shrink; the line width is non-decreasing in the stretch, so the largest
                // stretch that still fits is found by bisection (only where shifts would push the line past the width).
                if (list.indices.sumOf { widthAt(it, stretch).toDouble() } > avail + 0.25f && stretch > 0.5f) {
                    var lo = 0.5f; var hi = stretch
                    repeat(24) { val mid = (lo + hi) / 2; if (list.indices.sumOf { widthAt(it, mid).toDouble() } > avail) hi = mid else lo = mid }
                    stretch = lo
                }
                var x = start
                list.forEachIndexed { k, s -> s.system = si; s.x = x; s.width = widthAt(k, stretch); place(s, k == 0, stretch); x += s.width }
            }
            return result
        }
        fun fixedWidth(s: MeasureSpec, first: Boolean): Float =
            (if (first) firstExtras(s) else (if (s.showTime) timeWidth() else 0f) + (if (s.showKey && showStandard) keyWidth(s.measure.key) else 0f)) +
                (if (s.measure.repeat.starts) 8f else 0f) + (if (s.measure.repeat.endCount != null) 8f else 0f)
        fun firstExtras(s: MeasureSpec): Float = (if (showStandard) keyWidth(s.measure.key) else 0f) + if (s.showTime || true) timeWidth() else 0f

        /**
         * Column anchors from the content start [x0] at [stretch]; writes them into [xs]/[ws] when given and returns the
         * content end. Compression never pushes the noteheads of neighbouring beats into each other (QA 19: a 1/32 drum bar
         * on a phone compressed its columns to 11.4 units and heads with displaced seconds overlapped by 7.7): an anchor
         * moves right until the previous beat's ink, a gap and its own ink fit, and every later column moves with it.
         */
        fun sweep(s: MeasureSpec, x0: Float, stretch: Float, xs: FloatArray?, ws: FloatArray?): Float {
            val gap = style.staffSpace * 0.4f
            var x = x0; var end = x0
            var lastAnchor = Float.NEGATIVE_INFINITY; var lastRight = 0f
            s.columns.forEachIndexed { k, c ->
                val w = columnWidth(c) * stretch
                var anchor = x + w * 0.5f.coerceAtMost(14f / w.coerceAtLeast(1f)).coerceAtLeast(0.32f)
                if (c.headLeft > 0f || c.headRight > 0f) {
                    val need = max(lastAnchor + lastRight + gap + c.headLeft, x0 + c.headLeft)
                    if (anchor < need) { x += need - anchor; anchor = need }
                    lastAnchor = anchor; lastRight = c.headRight
                    end = max(end, anchor + c.headRight + gap)
                }
                if (xs != null) xs[k] = anchor
                if (ws != null) ws[k] = w
                x += w
                end = max(end, x)
            }
            return end
        }
        /** Extra content width the head shifts of [sweep] add at [stretch] (0 without standard-notation heads). */
        fun headShift(s: MeasureSpec, stretch: Float): Float {
            if (!showStandard || s.columns.none { it.headLeft > 0f || it.headRight > 0f }) return 0f
            val nominal = s.columns.sumOf { (columnWidth(it) * stretch).toDouble() }.toFloat()
            return (sweep(s, 0f, stretch, null, null) - nominal).coerceAtLeast(0f)
        }

        fun place(s: MeasureSpec, first: Boolean, stretch: Float = 1f) {
            var x = s.x + style.measurePadding
            if (first) x += firstExtras(s)
            else { if (s.showTime) x += timeWidth(); if (s.showKey && showStandard) x += keyWidth(s.measure.key) }
            if (s.measure.repeat.starts) x += 8f
            s.contentX = x
            s.columnXs = FloatArray(s.columns.size); s.columnWs = FloatArray(s.columns.size)
            sweep(s, x, stretch, s.columnXs, s.columnWs)
        }

        // ---------------------------------------------------------------- systems
        fun drawSystem(si: Int, list: List<MeasureSpec>, top: Float, header: Float): Float {
            val laneTop = top
            var y = top + style.numberLane // measure numbers, sections, tempo, endings
            // Bend headroom only in systems that hold a bend or whammy (it was reserved in every system of the track).
            headroom = if (list.any { s -> s.bar?.voices?.any { v -> v.beats.any { bt -> bt.notes.any { n -> n.articulations.any { it is Articulation.Bend || it is Articulation.Whammy } } } } == true }) style.fretText * 1.8f else 0f
            val staffTop: Float; val staffBottom: Float
            if (showStandard) {
                val (low, high) = Standard.range(this, list.mapNotNull { it.bar })
                y += style.staffSpace * (2.5f + max(0, high - 8) * 0.5f)
                staffTop = y; staffBottom = y + style.staffSpace * 4
                y = staffBottom + style.staffSpace * (2.5f + max(0, -low) * 0.5f)
            } else { staffTop = y; staffBottom = y }
            val effectsTop = y
            rows = effectRows(list)
            val effectsLane = if (showTab) rows.total * style.fretText * 0.72f + style.fretText * 0.4f + bendHeadroom() else 0f
            y += effectsLane
            // TAB on top without effect rows: the top string sits right under the lane unless ink above it meets a label.
            if (showTab && !showStandard && rows.total == 0) y = top + topLead(list)
            val tabTop = y
            val tabBottom = if (showTab) y + (strings - 1) * style.tabLine else y
            val stringY = if (showTab) FloatArray(strings) { s -> tabBottom - s * style.tabLine } else FloatArray(0)
            y = tabBottom
            // Rhythm under the TAB: as deep as its ink (stems, beams, tuplet numbers), then the gap to the next system.
            y += if (rhythmInTab) Rhythm.extent(style, list.mapNotNull { it.bar }) + style.inkGap else style.systemGap
            val x0 = r.margin; val x1 = list.last().x + list.last().width
            val system = SystemBox(si, laneTop, y, x0, x1, staffTop, staffBottom, tabTop, tabBottom, stringY)
            systems += system
            // Staff lines and header
            if (showStandard) for (k in 0..4) glyphs += LineGlyph(x0, staffTop + k * style.staffSpace, x1, staffTop + k * style.staffSpace, style.lineWidth, Ink.STAFF)
            if (showTab) for (s in 0 until strings) glyphs += LineGlyph(x0 + header - 6f, stringY[s], x1, stringY[s], style.lineWidth, Ink.STAFF)
            if (showTab) {
                stringY.forEachIndexed { s, sy -> glyphs += TextGlyph(x0 + 2f, sy + style.fretText * 0.3f, MusicText.pitchClass(track.instrument.tuning[s] + track.instrument.capoByString[s]), style.fretText * 0.62f, Ink.MUTED, Align.START) }
                val mid = (tabTop + tabBottom) / 2
                glyphs += TextGlyph(x0 + header - 13f, mid - style.fretText * 0.55f, "T", style.fretText * 0.75f, Ink.TEXT, bold = true)
                glyphs += TextGlyph(x0 + header - 13f, mid + style.fretText * 0.2f, "A", style.fretText * 0.75f, Ink.TEXT, bold = true)
                glyphs += TextGlyph(x0 + header - 13f, mid + style.fretText * 0.95f, "B", style.fretText * 0.75f, Ink.TEXT, bold = true)
            }
            if (showStandard) Standard.clef(this, x0 + 4f, staffTop)
            list.forEachIndexed { k, s -> drawMeasure(system, s, k == 0, effectsTop) }
            // System barline at the start
            glyphs += LineGlyph(x0 + header - 6f, if (showStandard) staffTop else tabTop, x0 + header - 6f, if (showTab) tabBottom else staffBottom, 1f, Ink.STAFF)
            return y
        }
        /** Rows of the effect lane above the TAB, allocated per system so labels never overlap. */
        class Rows(val pm: Int, val ring: Int, val vibrato: Int, val fermata: Int, val labels: Int, val total: Int)
        var rows = Rows(-1, -1, -1, -1, 0, 0)
        fun effectRows(list: List<MeasureSpec>): Rows {
            val notes = list.mapNotNull { it.bar }.flatMap { it.voices }.flatMap { it.beats }
            fun any(mark: NoteMark) = notes.any { b -> b.notes.any { Articulation.Mark(mark) in it.articulations } }
            var next = 0
            val pm = if (any(NoteMark.PALM_MUTE)) next++ else -1
            val ring = if (any(NoteMark.LET_RING)) next++ else -1
            val vib = if (notes.any { b -> b.notes.any { n -> n.articulations.any { it is Articulation.Vibrato } } }) next++ else -1
            val fer = if (notes.any { it.fermata != null }) next++ else -1
            val labels = notes.maxOfOrNull { b -> beatLabels(b).size } ?: 0
            return Rows(pm, ring, vib, fer, labels, next + labels)
        }
        fun beatLabels(b: Beat): List<String> {
            val labels = ArrayList<String>()
            b.notes.forEach { n ->
                n.articulations.forEach { a ->
                    when (a) {
                        is Articulation.Harmonic -> labels += when (a.kind) { HarmonicKind.NATURAL -> "N.H."; HarmonicKind.ARTIFICIAL -> "A.H."; HarmonicKind.PINCH -> "P.H."; HarmonicKind.TAPPED -> "T.H."; HarmonicKind.SEMI -> "S.H."; HarmonicKind.FEEDBACK -> "Fdbk." }
                        is Articulation.Mark -> when (a.kind) { NoteMark.TAPPING -> labels += "T"; NoteMark.SLAP -> labels += "S"; NoteMark.POP -> labels += "P"; NoteMark.ACCENT -> labels += ">"; NoteMark.HEAVY_ACCENT -> labels += "^"; NoteMark.STACCATO -> labels += "·"; NoteMark.PICK_SCRAPE -> labels += "scrape"; else -> Unit }
                        is Articulation.Trill -> labels += "tr(${a.targetFret})"
                        is Articulation.Pickstroke -> labels += if (a.direction == StrokeDirection.DOWN) "⊓" else "V"
                        is Articulation.Dynamic -> labels += a.level.name.lowercase()
                        is Articulation.TremoloPicking -> labels += "///"
                        else -> Unit
                    }
                }
            }
            return labels.distinct()
        }
        /** Bend headroom of the system being drawn (set by [drawSystem]). */
        private var headroom = 0f
        fun bendHeadroom(): Float = headroom

        // ---------------------------------------------------------------- top lane geometry
        /** Horizontal extent of one top-lane item and its lowest ink below the system top. */
        class LaneSpan(val x0: Float, val x1: Float, val bottom: Float)
        /** Horizontal extent of ink above the top TAB string and how far it rises above the top string line. */
        class AboveSpan(val x0: Float, val x1: Float, val rise: Float)

        /**
         * Distance from the system top to the top TAB string for a TAB-only system without effect rows: at least the lane,
         * every item above the top string stays inside the system, and where such an item lies under a lane label (same x)
         * the string moves down until the item clears the label by [LABEL_CLEAR].
         */
        fun topLead(list: List<MeasureSpec>): Float {
            val lanes = list.flatMap { lane(it, 0f, 0f, emit = false) }
            var lead = max(style.numberLane, (lanes.maxOfOrNull { it.bottom } ?: 0f) + LANE_AIR)
            for (a in list.flatMap { aboveTab(it) }) {
                lead = max(lead, a.rise + 1f)
                for (l in lanes) if (a.x0 < l.x1 && l.x0 < a.x1) lead = max(lead, l.bottom + LABEL_CLEAR + a.rise)
            }
            return lead
        }

        /**
         * Top lane of a bar (number, section, tempo, endings, jump marks) at [top]; draws it when [emit] and returns the
         * extent of each item. [hookEnd]: lowest y of the ending bracket's hook (just above the first staff line).
         */
        fun lane(s: MeasureSpec, top: Float, hookEnd: Float, emit: Boolean): List<LaneSpan> {
            val m = s.measure
            val right = s.x + s.width
            val spans = ArrayList<LaneSpan>(4)
            val number = "${s.index + 1}"
            if (emit) glyphs += TextGlyph(s.x + 3f, top + 9f, number, 8.5f, Ink.MUTED, Align.START)
            spans += LaneSpan(s.x + 3f, s.x + 3f + TextMetrics.width(number, 8.5f), 9f + 8.5f * DESCENT)
            var laneX = s.x + 16f
            sectionByMeasure[m.id]?.let { sec ->
                val w = TextMetrics.width(sec.label, 10f, true) + 10f
                if (emit) {
                    glyphs += RectGlyph(laneX, top + 1f, w, 14f, Ink.SECTION, fill = true, radius = 3f)
                    glyphs += TextGlyph(laneX + w / 2, top + 11.5f, sec.label, 10f, Ink.ON_SECTION, bold = true)
                }
                spans += LaneSpan(laneX, laneX + w, 15f)
                laneX += w + 6f
            }
            tempoByMeasure[m.id]?.forEach { t ->
                val tx = if (t.position.offset == Fraction.ZERO) laneX else (xFor(s, t.position.offset) - 8f)
                val text = "♩ = ${formatBpm(t.quarterNotesPerMinute)}"
                if (emit) glyphs += TextGlyph(tx, top + 11f, text, 9.5f, Ink.TEXT, Align.START, bold = true)
                val w = TextMetrics.width(text, 9.5f, true)
                spans += LaneSpan(tx, tx + w, 11f + 9.5f * DESCENT)
                laneX = tx + w + 6f
            }
            if (m.repeat.alternateEndings.isNotEmpty()) {
                val y = top + 17f
                val label = m.repeat.alternateEndings.sorted().joinToString(", ") + "."
                if (emit) {
                    glyphs += LineGlyph(s.x + 2f, y, right - 3f, y, 1f, Ink.TEXT)
                    glyphs += LineGlyph(s.x + 2f, y, s.x + 2f, max(y + 1f, min(y + 6f, hookEnd)), 1f, Ink.TEXT)
                    glyphs += TextGlyph(laneX, y - 2f, label, 9f, Ink.TEXT, Align.START, bold = true)
                }
                spans += LaneSpan(s.x + 1.5f, right - 2.5f, 17.5f)
                laneX += TextMetrics.width(label, 9f, true) + 6f
            }
            var endX = right - 4f
            m.navigation.forEach { nav ->
                val label = when (nav.kind) { NavigationKind.DA_CAPO -> "D.C."; NavigationKind.DAL_SEGNO -> "D.S."; NavigationKind.SEGNO -> "Segno"; NavigationKind.TO_CODA -> "To Coda"; NavigationKind.CODA -> "Coda"; NavigationKind.FINE -> "Fine" }
                val atEnd = nav.kind == NavigationKind.DA_CAPO || nav.kind == NavigationKind.DAL_SEGNO || nav.kind == NavigationKind.TO_CODA || nav.kind == NavigationKind.FINE
                // Several marks in one bar: start marks continue the lane to the right, end marks stack leftwards from the barline.
                val tw = TextMetrics.width(label, 9.5f, true)
                val w = tw + 6f
                val x = if (atEnd) endX else laneX
                if (emit) glyphs += TextGlyph(x, top + 11f, label, 9.5f, Ink.ACCENT, if (atEnd) Align.END else Align.START, bold = true, italic = true)
                spans += if (atEnd) LaneSpan(x - tw, x, 11f + 9.5f * DESCENT) else LaneSpan(x, x + tw, 11f + 9.5f * DESCENT)
                if (atEnd) endX -= w else laneX += w
            }
            return spans
        }

        /**
         * Ink of a bar that rises above the top TAB string, mirroring the drawing code: fret chips on the top string
         * (GlyphPainter: 1.34 × size high, at least as wide as high), arcs with their H/P/sl. labels, bends with their
         * amount labels and the repeat count. Effect rows are not included (systems with effect rows keep their lane).
         */
        fun aboveTab(s: MeasureSpec): List<AboveSpan> {
            if (!showTab || strings == 0) return emptyList()
            val out = ArrayList<AboveSpan>()
            val bar = s.bar
            if (bar != null) for (v in bar.voices) for (b in v.beats) {
                val x = xFor(s, b.offset)
                for (n in b.notes) {
                    val string = n.string ?: continue
                    if (string !in 0 until strings) continue
                    val depth = (strings - 1 - string) * style.tabLine // top string line down to this string
                    val (label, size) = fretText(n)
                    val w = TextMetrics.width(label, size, true)
                    val cw = max(w + size * 0.6f, size * 1.34f)
                    if (size * 0.67f - depth > 0f) out += AboveSpan(x - cw / 2, x + cw / 2, size * 0.67f - depth)
                    for ((nx, arcLabel) in arcsOf(s, v, b, n)) {
                        val rise = style.tabLine * 0.45f + style.tabLine * 0.28f + (if (arcLabel != null) 2f + style.fretText * 0.55f * ASCENT else 0f) - depth
                        if (rise > 0f) out += AboveSpan(x + 3f, nx - 3f, rise)
                    }
                    bendSpan(n, b, s, x)?.let { (startX, w2) ->
                        val whammy = n.articulations.none { it is Articulation.Bend }
                        val rise = style.fretText * 1.3f + max(6f + style.fretText * 0.6f * ASCENT, if (whammy) 4f + style.fretText * 0.55f * ASCENT else 0f)
                        val x1 = max(startX + w2 + 11f, if (whammy) startX + TextMetrics.width("w/bar", style.fretText * 0.55f) else 0f)
                        out += AboveSpan(startX - 10f, x1, rise)
                    }
                }
            }
            val count = s.measure.repeat.endCount
            if (count != null && count > 2) {
                val right = s.x + s.width
                out += AboveSpan(right - 6f - TextMetrics.width("×$count", 10f, true), right - 6f, 4f + 10f * ASCENT)
            }
            return out
        }

        // ---------------------------------------------------------------- measures
        fun drawMeasure(sys: SystemBox, s: MeasureSpec, first: Boolean, effectsTop: Float) {
            val m = s.measure
            val top = if (showStandard) sys.staffTop else sys.tabTop
            val bottom = if (showTab) sys.tabBottom else sys.staffBottom
            measureBoxes += MeasureBox(m.id, s.index, sys.index, s.x, s.contentX, s.width, sys.top, sys.bottom)
            s.columns.forEachIndexed { k, c -> columns += Column(m.id, s.index, c.offset, s.columnXs[k], s.columnWs[k], sys.index, c.virtual) }
            val right = s.x + s.width
            // Bar line / repeats
            if (m.repeat.endCount != null) {
                glyphs += LineGlyph(right - 1.5f, top, right - 1.5f, bottom, 3f, Ink.STAFF)
                glyphs += LineGlyph(right - 5f, top, right - 5f, bottom, 1f, Ink.STAFF)
                repeatDots(right - 10f, sys)
                if ((m.repeat.endCount ?: 2) > 2) glyphs += TextGlyph(right - 6f, top - 4f, "×${m.repeat.endCount}", 10f, Ink.TEXT, Align.END, bold = true)
            } else glyphs += LineGlyph(right, top, right, bottom, if (s.index == p.measures.lastIndex) 3f else 1f, Ink.STAFF)
            if (m.repeat.starts) {
                val x = s.x + 2f
                glyphs += LineGlyph(x, top, x, bottom, 3f, Ink.STAFF); glyphs += LineGlyph(x + 4f, top, x + 4f, bottom, 1f, Ink.STAFF)
                repeatDots(x + 9f, sys)
            }
            // Top lane: number, section, tempo, endings, navigation
            lane(s, sys.top, (if (showStandard) sys.staffTop else sys.tabTop) - 1f, emit = true)
            // Time signature
            val showTime = s.showTime || first
            if (showTime) {
                val tx = s.contentX - timeWidth() * 0.55f
                // Hit region over the glyphs: the TAB band (or the staff band without TAB), as wide as the signature column.
                val (hitTop, hitBottom) = if (showTab) sys.tabTop - style.tabLine / 2 to sys.tabBottom + style.tabLine / 2 else sys.staffTop - style.staffSpace to sys.staffBottom + style.staffSpace
                signatureBoxes += SignatureBox(m.id, s.index, sys.index, tx - timeWidth() / 2, hitTop, timeWidth(), hitBottom - hitTop, courtesy = first && !s.showTime)
                if (showTab) {
                    val mid = (sys.tabTop + sys.tabBottom) / 2
                    val size = min(style.tabLine * 1.5f, (sys.tabBottom - sys.tabTop) / 2.2f + 6f)
                    glyphs += TextGlyph(tx, mid - 1f, "${m.signature.numerator}", size, Ink.TEXT, bold = true)
                    glyphs += TextGlyph(tx, mid + size * 0.82f, "${m.signature.denominator}", size, Ink.TEXT, bold = true)
                }
                if (showStandard) {
                    glyphs += TextGlyph(tx, sys.staffTop + style.staffSpace * 1.75f, "${m.signature.numerator}", style.staffSpace * 2.3f, Ink.TEXT, bold = true)
                    glyphs += TextGlyph(tx, sys.staffTop + style.staffSpace * 3.75f, "${m.signature.denominator}", style.staffSpace * 2.3f, Ink.TEXT, bold = true)
                }
            }
            if (showStandard && (first || s.showKey)) Standard.key(this, m.key, s.contentX - (if (showTime) timeWidth() else 0f) - keyWidth(m.key), sys.staffTop)
            if (showTab && r.editGrid != null) s.columns.forEachIndexed { k, c -> if (c.virtual) glyphs += OvalGlyph(s.columnXs[k], (sys.tabTop + sys.tabBottom) / 2, 1.6f, 1.6f, 0f, Ink.GRID, true) }
            val bar = s.bar ?: return
            val voices = bar.voices.sortedBy { it.index }.filter { it.beats.isNotEmpty() }
            for (v in voices) {
                val ink = if (r.activeVoice == null || r.activeVoice == v.index) Ink.NOTE else Ink.MUTED
                if (showTab) for (b in v.beats) tabBeat(sys, s, v, b, ink)
                if (rhythmInTab && (r.activeVoice == null || r.activeVoice == v.index || voices.size == 1)) Rhythm.draw(this, sys, s.measure, v, ink) { o -> xFor(s, o) }
                if (showStandard) Standard.voice(this, sys, s, v, ink, voices.size > 1) { o -> xFor(s, o) }
            }
            if (showTab) effects(sys, s, voices, effectsTop)
        }

        fun repeatDots(x: Float, sys: SystemBox) {
            if (showTab && strings >= 2) {
                val mid = (sys.tabTop + sys.tabBottom) / 2; val d = style.tabLine * 0.5f
                glyphs += OvalGlyph(x, mid - d, 2f, 2f, 0f, Ink.STAFF, true); glyphs += OvalGlyph(x, mid + d, 2f, 2f, 0f, Ink.STAFF, true)
            }
            if (showStandard) {
                glyphs += OvalGlyph(x, sys.staffTop + style.staffSpace * 1.5f, 2f, 2f, 0f, Ink.STAFF, true)
                glyphs += OvalGlyph(x, sys.staffTop + style.staffSpace * 2.5f, 2f, 2f, 0f, Ink.STAFF, true)
            }
        }

        fun xFor(s: MeasureSpec, offset: Fraction): Float {
            val idx = s.columns.indexOfFirst { it.offset == offset }
            if (idx >= 0) return s.columnXs[idx]
            val k = s.columns.indexOfLast { it.offset < offset }.coerceAtLeast(0)
            val c = s.columns[k]
            val t = ((offset.toDouble() - c.offset.toDouble()) / c.duration.toDouble().coerceAtLeast(1e-9)).toFloat().coerceIn(0f, 1f)
            return s.columnXs[k] + t * s.columnWs[k]
        }

        // ---------------------------------------------------------------- TAB notes
        fun fretLabel(n: Note): String {
            val fret = n.fret ?: n.pitch.midiNumber
            return when {
                Articulation.Mark(NoteMark.DEAD) in n.articulations -> "x"
                n.articulations.any { it is Articulation.Harmonic && it.kind == HarmonicKind.NATURAL } -> "<$fret>"
                Articulation.Mark(NoteMark.GHOST) in n.articulations -> "($fret)"
                else -> "$fret"
            }
        }

        fun tabBeat(sys: SystemBox, s: MeasureSpec, v: Voice, b: Beat, ink: Ink) {
            val x = xFor(s, b.offset)
            for (n in b.notes) {
                val string = n.string ?: continue
                if (string !in 0 until strings) continue
                val y = sys.stringY[string]
                val tiedTarget = isTieTarget(n)
                val (label, size) = fretText(n)
                glyphs += TextGlyph(x, y + size * 0.36f, label, size, if (tiedTarget) Ink.MUTED else ink, halo = true, bold = !tiedTarget,
                    chip = if (tiedTarget) -1 else strings - 1 - string, chipOutline = label == "x")
                val w = TextMetrics.width(label, size, true) + 4f
                noteBoxes += NoteBox(n.id, s.measure.id, b.offset, v.index, string, x - w / 2, y - style.tabLine / 2, w, style.tabLine)
                n.articulations.filterIsInstance<Articulation.Grace>().firstOrNull()?.let { g ->
                    val gf = g.fret ?: (g.pitch.midiNumber - track.instrument.tuning[string] - track.instrument.capoByString[string])
                    glyphs += TextGlyph(x - style.fretText * 1.05f, y + style.fretText * 0.27f, "$gf", style.fretText * 0.68f, ink, halo = true)
                }
                connectors(sys, s, v, b, n, x, y, ink)
                bend(sys, n, b, s, x, y, ink)
            }
            b.strum?.let { st ->
                val ys = b.notes.mapNotNull { it.string?.let { s2 -> sys.stringY.getOrNull(s2) } }
                if (ys.size >= 2) {
                    val ax = x - style.fretText * 1.1f
                    val y0 = ys.max() + 3f; val y1 = ys.min() - 3f
                    glyphs += LineGlyph(ax, y0, ax, y1, 1.2f, ink)
                    val tip = if (st.direction == StrokeDirection.DOWN) y1 else y0 // down-strum moves low -> high string = upward on the page
                    val dir = if (st.direction == StrokeDirection.DOWN) 1f else -1f
                    glyphs += PathGlyph.Builder().move(ax - 3f, tip + 4f * dir).line(ax, tip).line(ax + 3f, tip + 4f * dir).build(ink, false, 1.2f)
                }
            }
        }

        /** Fret label and text size as drawn in the TAB: tie targets are smaller and in parentheses. */
        fun fretText(n: Note): Pair<String, Float> = if (isTieTarget(n)) "(${n.fret})" to style.fretText * 0.8f else fretLabel(n) to style.fretText
        fun isTieTarget(n: Note): Boolean = tieTargets.contains(n.id)
        val tieTargets: Set<EntityId> by lazy { staff?.bars?.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { bt -> bt.notes.flatMap { it.links.filter { l -> l.kind == LinkKind.TIE }.map { l -> l.target } } } } }.orEmpty().toSet() }

        /** Next note on the same string in the same voice, possibly in the next measure. */
        fun nextOnString(s: MeasureSpec, v: Voice, b: Beat, string: Int): Triple<MeasureSpec?, Beat, Note>? {
            v.beats.filter { it.offset > b.offset }.sortedBy { it.offset }.forEach { nb -> nb.notes.firstOrNull { it.string == string }?.let { return Triple(s, nb, it) }; if (nb.notes.isNotEmpty() || nb.explicitRest) return null }
            return null
        }

        fun connectors(sys: SystemBox, s: MeasureSpec, v: Voice, b: Beat, n: Note, x: Float, y: Float, ink: Ink) {
            val string = n.string ?: return
            val halfText = TextMetrics.width(fretLabel(n), style.fretText, true) / 2 + 2f
            val next = nextOnString(s, v, b, string)
            val nx = next?.let { (ms, nb, _) -> if (ms === s) xFor(s, nb.offset) else null }
            val slide = n.articulations.filterIsInstance<Articulation.Slide>().firstOrNull()
            if (slide != null) {
                val d = style.tabLine * 0.18f
                when (slide.kind) {
                    SlideKind.SHIFT, SlideKind.LEGATO -> if (nx != null && next != null) {
                        val up = next.third.pitch.midiNumber > n.pitch.midiNumber
                        glyphs += LineGlyph(x + halfText, y + if (up) d else -d, nx - halfText, y + if (up) -d else d, 1.3f, ink)
                    }
                    SlideKind.INTO_FROM_BELOW -> glyphs += LineGlyph(x - halfText - 10f, y + d * 2, x - halfText, y, 1.3f, ink)
                    SlideKind.INTO_FROM_ABOVE -> glyphs += LineGlyph(x - halfText - 10f, y - d * 2, x - halfText, y, 1.3f, ink)
                    SlideKind.OUT_DOWN -> glyphs += LineGlyph(x + halfText, y, x + halfText + 12f, y + d * 2, 1.3f, ink)
                    SlideKind.OUT_UP -> glyphs += LineGlyph(x + halfText, y, x + halfText + 12f, y - d * 2, 1.3f, ink)
                }
            }
            for ((ax, label) in arcsOf(s, v, b, n)) arc(x, ax, y - style.tabLine * 0.45f, ink, label)
            n.links.firstOrNull { it.kind == LinkKind.TIE }?.let { tie ->
                pendingTies += PendingTie(tie.target, x + halfText - 2f, y + style.tabLine * 0.42f, sys.index, sys.x1, style.tabLine * 0.28f, Ink.MUTED, standard = false)
            }
        }

        /** Arcs above a note to the next note on its string in the bar: legato slide (sl.), hammer-on/pull-off (H/P), slur. End x and label. */
        fun arcsOf(s: MeasureSpec, v: Voice, b: Beat, n: Note): List<Pair<Float, String?>> {
            val string = n.string ?: return emptyList()
            val next = nextOnString(s, v, b, string) ?: return emptyList()
            if (next.first !== s) return emptyList()
            val nx = xFor(s, next.second.offset)
            val out = ArrayList<Pair<Float, String?>>(1)
            if (n.articulations.filterIsInstance<Articulation.Slide>().firstOrNull()?.kind == SlideKind.LEGATO) out += nx to "sl."
            val hammer = Articulation.Mark(NoteMark.HAMMER_ON) in n.articulations || Articulation.Mark(NoteMark.PULL_OFF) in n.articulations || n.links.any { it.kind == LinkKind.HAMMER_ON || it.kind == LinkKind.PULL_OFF }
            if (hammer) out += nx to if (next.third.pitch.midiNumber >= n.pitch.midiNumber) "H" else "P"
            if (n.links.any { it.kind == LinkKind.SLUR }) out += nx to null
            return out
        }

        fun arc(x0: Float, x1: Float, y: Float, ink: Ink, label: String?, down: Boolean = false) {
            val h = style.tabLine * 0.28f * if (down) 1f else -1f
            val a = x0 + 3f; val b = x1 - 3f
            glyphs += PathGlyph.Builder().move(a, y).cubic(a + (b - a) * 0.25f, y + h, a + (b - a) * 0.75f, y + h, b, y).build(ink, false, 1.1f)
            if (label != null) glyphs += TextGlyph((a + b) / 2, y + h - 2f, label, style.fretText * 0.55f, ink, italic = true)
        }

        fun bend(sys: SystemBox, n: Note, b: Beat, s: MeasureSpec, x: Float, y: Float, ink: Ink) {
            val bend = n.articulations.filterIsInstance<Articulation.Bend>().firstOrNull()
            val whammy = n.articulations.filterIsInstance<Articulation.Whammy>().firstOrNull()
            val points = bend?.points ?: whammy?.points ?: return
            val (startX, w) = bendSpan(n, b, s, x) ?: return
            val topY = sys.tabTop - style.fretText * 1.3f
            val maxCents = points.maxOf { abs(it.cents) }.coerceAtLeast(1)
            // Dives go below the string, but only as far as the system has room (nothing is reserved below the TAB).
            val diveRoom = (sys.bottom - y - style.fretText * 1.1f).coerceAtLeast(style.fretText * 0.25f)
            fun py(c: Int): Float { val ratio = (abs(c).toFloat() / maxCents.toFloat()).coerceIn(0f, 1f)
                return if (c >= 0) y - (y - topY) * ratio else y + minOf((y - topY) * 0.5f, diveRoom) * ratio }
            val sorted = points.sortedBy { it.position }
            var px = startX; var pyv = py(sorted.first().cents)
            if (sorted.first().cents != 0) { glyphs += LineGlyph(startX, y - 4f, startX, pyv, 1.3f, ink); arrow(startX, pyv, up = true, ink); label(startX, pyv, sorted.first().cents, ink) }
            for (k in 1 until sorted.size) {
                val pt = sorted[k]; val prev = sorted[k - 1]
                if (pt.cents == prev.cents) continue
                val nxp = startX + w * pt.position.toDouble().toFloat()
                val npy = py(pt.cents)
                val path = PathGlyph.Builder().move(px, pyv).cubic(px + (nxp - px) * 0.6f, pyv, nxp, pyv + (npy - pyv) * 0.4f, nxp, npy).build(ink, false, 1.3f)
                glyphs += path
                arrow(nxp, npy, up = pt.cents > prev.cents, ink)
                if (pt.cents > prev.cents && pt.cents != 0) label(nxp, npy, pt.cents, ink)
                px = nxp; pyv = npy
            }
            if (whammy != null) glyphs += TextGlyph(startX, topY - 4f, "w/bar", style.fretText * 0.55f, ink, Align.START, italic = true)
        }
        /** Start x and width of the bend/whammy curve of [n] at [x], null without curve points. */
        fun bendSpan(n: Note, b: Beat, s: MeasureSpec, x: Float): Pair<Float, Float>? {
            val points = n.articulations.filterIsInstance<Articulation.Bend>().firstOrNull()?.points ?: n.articulations.filterIsInstance<Articulation.Whammy>().firstOrNull()?.points ?: return null
            if (points.isEmpty()) return null
            val startX = x + TextMetrics.width(fretLabel(n), style.fretText, true) / 2 + 1f
            val w = max(style.fretText * 1.6f, xFor(s, b.offset + b.duration.length()) - x - 6f).coerceAtMost(style.column16 * 4f)
            return startX to w
        }
        fun arrow(x: Float, y: Float, up: Boolean, ink: Ink) {
            val d = if (up) 4f else -4f
            glyphs += PathGlyph.Builder().move(x - 3f, y + d).line(x, y).line(x + 3f, y + d).close().build(ink, true, 1f)
        }
        fun label(x: Float, y: Float, cents: Int, ink: Ink) {
            val text = when (abs(cents)) { 200 -> "full"; 100 -> "½"; 50 -> "¼"; 150 -> "¾"; 300 -> "1½"; 400 -> "2"; else -> if (abs(cents) % 100 == 0) "${abs(cents) / 200.0}".removeSuffix(".0") else "${abs(cents)}c" }
            glyphs += TextGlyph(x, y - 6f, text, style.fretText * 0.6f, ink, bold = true)
        }

        // ---------------------------------------------------------------- effect lane
        fun effects(sys: SystemBox, s: MeasureSpec, voices: List<Voice>, top: Float) {
            val ink = Ink.ACCENT
            val textSize = style.fretText * 0.6f
            val rowHeight = style.fretText * 0.72f
            val base = sys.tabTop - style.fretText * 0.45f - bendHeadroom()
            fun rowY(row: Int) = base - row * rowHeight
            for (v in voices) {
                val beats = v.beats.sortedBy { it.offset }
                fun span(mark: NoteMark, label: String, row: Int) {
                    if (row < 0) return
                    var start: Float? = null; var end = 0f
                    fun flush() {
                        val a = start ?: return
                        glyphs += TextGlyph(a, rowY(row), label, textSize, ink, Align.START, bold = true, italic = true)
                        val tw = TextMetrics.width(label, textSize, true) + 3f
                        if (end > a + tw + 4f) glyphs += LineGlyph(a + tw, rowY(row) - textSize * 0.3f, end, rowY(row) - textSize * 0.3f, 0.9f, ink, dashed = true)
                        start = null
                    }
                    for (b in beats) {
                        val has = b.notes.any { Articulation.Mark(mark) in it.articulations }
                        val x = xFor(s, b.offset)
                        // Clamped to the barline: at the bar end xFor lies a column width past the last note.
                        if (has) { if (start == null) start = x - 6f; end = min(xFor(s, b.offset + b.duration.length()) - 4f, s.x + s.width - 4f) } else flush()
                    }
                    flush()
                }
                span(NoteMark.PALM_MUTE, "P.M.", rows.pm)
                span(NoteMark.LET_RING, "let ring", rows.ring)
                val labelBase = rows.total - rows.labels
                for (b in beats) {
                    val x = xFor(s, b.offset)
                    beatLabels(b).forEachIndexed { k, l -> glyphs += TextGlyph(x, rowY(labelBase + k), l, textSize, ink, bold = true, italic = l.length > 2) }
                    if (rows.vibrato >= 0 && b.notes.any { n -> n.articulations.any { it is Articulation.Vibrato } }) {
                        val end = xFor(s, b.offset + b.duration.length()) - 4f
                        vibrato(x - 4f, max(end, x + 14f), rowY(rows.vibrato) - textSize * 0.35f, ink)
                    }
                    if (rows.fermata >= 0 && b.fermata != null) {
                        val y = rowY(rows.fermata)
                        glyphs += PathGlyph.Builder().move(x - 6f, y).cubic(x - 6f, y - textSize * 1.3f, x + 6f, y - textSize * 1.3f, x + 6f, y).build(ink, false, 1.2f)
                        glyphs += OvalGlyph(x, y - textSize * 0.25f, 1.4f, 1.4f, 0f, ink, true)
                    }
                }
            }
        }
        fun vibrato(x0: Float, x1: Float, y: Float, ink: Ink) {
            val pb = PathGlyph.Builder().move(x0, y)
            var x = x0; var up = true
            while (x < x1) { val nx = min(x1, x + 4f); pb.cubic(x + 1.3f, if (up) y - 3f else y + 3f, nx - 1.3f, if (up) y - 3f else y + 3f, nx, y); x = nx; up = !up }
            glyphs += pb.build(ink, false, 1.1f)
        }
    }

    /** Air between the lowest lane label and the top staff line when nothing above the staff meets a label. */
    internal const val LANE_AIR = 3f
    /** Clearance between a lane label and ink rising above the top TAB string below it. */
    internal const val LABEL_CLEAR = 2f
    /** Text ascent/descent as fractions of the size (same estimate as the layout property tests). */
    internal const val ASCENT = 0.78f
    internal const val DESCENT = 0.22f

    fun formatBpm(bpm: Double): String = if (abs(bpm - bpm.toLong()) < 0.01) bpm.toLong().toString() else ((bpm * 10).toLong() / 10.0).toString()
}

