package dev.riffworkbench.notation

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-Zyklus 21 / Export-Import: Notensatz-Exporte mit den Layoutregeln aus Zyklus 19/20 (fester Partitur-Kopf,
 * inhaltsbezogener Bend-/Rhythmus-Platz, Notenkopf-Mindestabstand).
 *
 * PDF (`ui:score` PdfExport, nur auf CI baubar) wird hier nachgestellt: A4 595 × 842, Rand 36, READ + Standardnotation
 * + TAB, Breite 523, Layoutrand 0; jedes System wird auf `[top − 2, bottom]` zugeschnitten (clipRect) und nie geteilt.
 * Geprüft je Spur: keine Tinte außerhalb des Clip-Bands (abgeschnitten), keine Tinte außerhalb der Seite (x), jedes System
 * passt auf eine Seite, Seitenumbruch ohne leere Seiten, Systeme überlappen nicht. SVG (`SvgExport.render`, Breite 760):
 * jede Glyphe im Dokument. Projekte über EditorCommands wie die UI. Jede Messung druckt ihre Zahlen ("Q21P ...").
 */
class Qa21PrintLayoutTest {
    private var n = 0
    private val ids = IdSource { EntityId("q21p-${n++}") }
    private val EditorSession.p get() = state.value.project
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.note(m: Int, off: Fraction, string: Int, fret: Int, d: Int = 8, marks: Set<NoteMark> = emptySet(), track: Int = 0, vararg arts: Articulation) {
        select(Selection(p.tracks[track].id, p.measures[m].id, 0, off, string)); ok(EditorCommand.PutNote(fret = fret, duration = WrittenDuration(d), marks = marks))
        for (a in arts) ok(EditorCommand.ApplyArticulation(a, toggle = false))
    }
    private fun f(a: Long, b: Long) = Fraction.of(a, b)

    // PdfExport constants (ui/score/PdfExport.kt).
    private val pageW = 595f; private val pageH = 842f; private val margin = 36f
    private val pdfStyle = LayoutStyle.READ.copy(showStandard = true)

    /**
     * Standardprojekt mit [bars] Takten: auf jedem Takt Effekte, die Platz brauchen — Bends auf der hohen Saite, Palm Mute,
     * Pinch/Natural Harmonic (Effektzeilen), Hammer-on, Abschnitt und Tempowechsel per Sheet, 32tel-Drums.
     */
    private fun dense(bars: Int, strings: String? = null): Project {
        val s = EditorSession(ProjectFactory(ids).create("Q21 Druck"), ids)
        repeat(bars - 1) { s.ok(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        strings?.let { id -> val t = TuningPresets.all.first { it.id == id }; val g = s.p.tracks[0]
            s.ok(EditorCommand.SetInstrument(g.id, g.instrument.copy(tuning = t.tuning, capoByString = List(t.tuning.size) { 0 }), RetuningPolicy.KEEP_SOUNDING_PITCH)) }
        val top = s.p.tracks[0].instrument.tuning.size - 1
        val pm = setOf(NoteMark.PALM_MUTE)
        for (m in 0 until bars) {
            s.note(m, Fraction.ZERO, top, 12 + m % 5, d = 8, track = 0, arts = arrayOf(Articulations.fullBend()))
            s.note(m, f(1, 8), 0, 0, marks = pm); s.note(m, f(1, 4), 0, 0, marks = pm)
            s.note(m, f(3, 8), 2, 7, arts = arrayOf(Articulation.Harmonic(HarmonicKind.PINCH)))
            s.note(m, f(1, 2), top, 12, d = 8, arts = arrayOf(Articulation.Harmonic(HarmonicKind.NATURAL)))
            s.note(m, f(5, 8), top - 1, 5, marks = setOf(NoteMark.HAMMER_ON)); s.note(m, f(3, 4), top - 1, 7)
            s.note(m, f(7, 8), 0, 3, marks = setOf(NoteMark.ACCENT))
            if (m % 4 == 0) s.ok(EditorCommand.SetSection(s.p.measures[m].id, "Teil ${m / 4 + 1}"))
            if (m % 3 == 2) s.ok(EditorCommand.SetTempoAt(s.p.measures[m].id, 100.0 + m))
            // Drums: 32tel-Fill auf Zz. 4.
            for (k in 0 until 8) { s.select(Selection(s.p.tracks[2].id, s.p.measures[m].id, 0, f(3, 4) + f(k.toLong(), 32), 0)); s.ok(EditorCommand.ToggleDrum(if (k % 2 == 0) 38 else 45, WrittenDuration(32))) }
            s.select(Selection(s.p.tracks[1].id, s.p.measures[m].id, 0, Fraction.ZERO, 1)); s.ok(EditorCommand.PutNote(fret = 0, duration = WrittenDuration(1)))
        }
        return s.p
    }

    private fun yRange(g: Glyph): Pair<Float, Float> = when (g) {
        is LineGlyph -> min(g.y1, g.y2) - g.width / 2 to max(g.y1, g.y2) + g.width / 2
        is RectGlyph -> g.y to g.y + g.h
        is TextGlyph -> if (g.chip >= 0) (g.y - g.size * 0.36f - g.size * 0.67f) to (g.y - g.size * 0.36f + g.size * 0.67f) else g.y - g.size * 0.78f to g.y + g.size * 0.22f
        is OvalGlyph -> g.cy - max(g.rx, g.ry) to g.cy + max(g.rx, g.ry)
        is PathGlyph -> { var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var i = 0; val o = g.ops
            while (i < o.size) { val k = when (o[i].toInt()) { 0, 1 -> 2; 2 -> 6; else -> 0 }; var j = 1; while (j < k) { lo = min(lo, o[i + 1 + j]); hi = max(hi, o[i + 1 + j]); j += 2 }; i += 1 + k }
            lo to hi }
    }
    private fun describe(g: Glyph) = when (g) {
        is TextGlyph -> "Text('${g.text}' ${g.ink})"; is PathGlyph -> "Pfad(${g.ink})"; is LineGlyph -> "Linie(${g.ink})"; is RectGlyph -> "Rechteck(${g.ink})"; is OvalGlyph -> "Oval(${g.ink})"
    }

    /** PdfExport.write nachgestellt; Befunde (abgeschnitten, außerhalb der Seite, Seitenumbruch) für [tracks]. */
    private fun pdfFindings(name: String, p: Project, tracks: List<Int>): List<String> {
        val out = ArrayList<String>()
        var pages = 1; var y = margin + 48f; var drawnOnPage = 1 // title block counts as content
        val usable = pageH - margin - 12f
        var clipped = 0; var outside = 0; var tallest = 0f; var systems = 0
        fun newPage() { if (drawnOnPage == 0) out += "$name: leere Seite ${pages}"; pages++; y = margin; drawnOnPage = 0 }
        for (t in tracks) {
            val layout = ScoreLayouter.layout(LayoutRequest(p, t, pdfStyle, width = pageW - 2 * margin, margin = 0f))
            val firstHeight = layout.systems.firstOrNull()?.let { it.bottom - it.top } ?: 22f
            if (y + 18f + firstHeight > usable) newPage()
            y += 18f; drawnOnPage++
            for ((i, sys) in layout.systems.withIndex()) {
                val h = sys.bottom - sys.top
                tallest = maxOf(tallest, h); systems++
                if (h > usable - margin) out += "$name/${p.tracks[t].name}: System $i ist ${"%.1f".format(h)} hoch, eine Seite fasst ${"%.1f".format(usable - margin)}"
                if (y + h > usable) newPage()
                for (g in layout.glyphsBySystem[i]) {
                    val (y0, y1) = yRange(g)
                    if (y0 < sys.top - 2f - 0.01f || y1 > sys.bottom + 0.01f) { clipped++; if (clipped <= 8) out += "$name/${p.tracks[t].name}: System $i schneidet ${describe(g)} ab (y ${"%.1f".format(y0)}..${"%.1f".format(y1)}, Clip ${"%.1f".format(sys.top - 2)}..${"%.1f".format(sys.bottom)})" }
                    if (g.minX < -margin - 0.01f || g.maxX > pageW - margin + 0.01f) { outside++; if (outside <= 8) out += "$name/${p.tracks[t].name}: ${describe(g)} außerhalb der Seite (x ${"%.1f".format(g.minX)}..${"%.1f".format(g.maxX)})" }
                }
                if (i > 0 && sys.top < layout.systems[i - 1].bottom - 0.01f) out += "$name/${p.tracks[t].name}: System $i überlappt System ${i - 1}"
                y += h; drawnOnPage++
            }
            if (layout.glyphsBySystem.last().isNotEmpty()) out += "$name/${p.tracks[t].name}: ${layout.glyphsBySystem.last().size} Glyphen außerhalb jedes Systems (vom PDF verworfen)"
            out += overlaps("$name/${p.tracks[t].name}", layout)
            y += 12f
        }
        println("Q21P PDF $name: ${tracks.size} Spuren, $systems Systeme auf $pages Seiten, höchstes System ${"%.1f".format(tallest)} (Seite fasst ${"%.1f".format(usable - margin)}), $clipped Glyphen abgeschnitten, $outside außerhalb der Seite")
        return out
    }

    /**
     * Überlappungen im gedruckten Bild: zwei Notenköpfe (gefüllte/leere Ovale der Noten-Tinte) oder zwei Bund-Chips (TAB),
     * deren Boxen sich um mehr als 1 Einheit in beiden Richtungen überdecken. In diesen Projekten gibt es keine
     * Sekund-Akkorde (die bekannte Ausnahme der Zyklus-15-Kampagne), also muss beides 0 sein.
     */
    private fun overlaps(name: String, l: ScoreLayout): List<String> {
        fun box(g: Glyph): FloatArray { val (y0, y1) = yRange(g); val x = if (g is TextGlyph && g.chip >= 0) { val cw = max(g.width + g.size * 0.6f, g.size * 1.34f); val cx = g.minX + g.width / 2; floatArrayOf(cx - cw / 2, cx + cw / 2) } else floatArrayOf(g.minX, g.maxX); return floatArrayOf(x[0], y0, x[1], y1) }
        fun hit(a: FloatArray, b: FloatArray) = min(a[2], b[2]) - max(a[0], b[0]) > 1f && min(a[3], b[3]) - max(a[1], b[1]) > 1f
        val heads = l.glyphs.filter { it is OvalGlyph && it.ink == Ink.NOTE }.map { box(it) }
        val chips = l.glyphs.filter { it is TextGlyph && it.chip >= 0 }.map { box(it) }
        var headPairs = 0; var chipPairs = 0
        for (i in heads.indices) for (j in i + 1 until heads.size) if (hit(heads[i], heads[j])) headPairs++
        for (i in chips.indices) for (j in i + 1 until chips.size) if (hit(chips[i], chips[j])) chipPairs++
        println("Q21P Überlappung $name: ${heads.size} Notenköpfe, $headPairs überdeckte Paare; ${chips.size} Bund-Chips, $chipPairs überdeckte Paare")
        return buildList { if (headPairs > 0) add("$name: $headPairs Notenkopf-Paare überdecken sich"); if (chipPairs > 0) add("$name: $chipPairs Bund-Chip-Paare überdecken sich") }
    }

    private fun svgFindings(name: String, p: Project): List<String> {
        val out = ArrayList<String>()
        val svg = SvgExport.render(p, p.tracks.indices.toList())
        val w = Regex("width=\"([0-9.]+)\"").find(svg)!!.groupValues[1].toFloat()
        val h = Regex("height=\"([0-9.]+)\"").find(svg)!!.groupValues[1].toFloat()
        var y = 64f; var bad = 0
        for (t in p.tracks.indices) {
            val l = ScoreLayouter.layout(LayoutRequest(p, t, pdfStyle, 760f))
            for (g in l.glyphs) {
                val (y0, y1) = yRange(g)
                if (y + 18 + y0 < -0.01f || y + 18 + y1 > h + 0.01f || g.minX < -0.01f || g.maxX > w + 0.01f) { bad++; if (bad <= 5) out += "$name SVG: ${describe(g)} außerhalb des Dokuments" }
            }
            y += l.height + 28f
        }
        println("Q21P SVG $name: ${"%.0f".format(w)} × ${"%.0f".format(h)}, $bad Glyphen außerhalb")
        return out
    }

    private fun cases(): List<Pair<String, Project>> = listOf(
        "Standard leer" to ProjectFactory(ids).create("Q21 leer"),
        "Standard 24 Takte, Bends/Effektzeilen/32tel" to dense(24),
        "7 Saiten nach Umstimmen, 12 Takte" to dense(12, "g7-std"),
        "8 Saiten nach Umstimmen, 12 Takte" to dense(12, "g8-std"),
        "Demo (4/4 + 7/8, Drop A)" to ProjectFactory(ids).demo("Demo"),
    )

    @Test fun printAndSvgExportsKeepEverySystemWhole() {
        val failures = ArrayList<String>()
        for ((name, p) in cases()) {
            failures += pdfFindings(name, p, p.tracks.indices.toList())
            failures += svgFindings(name, p)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
