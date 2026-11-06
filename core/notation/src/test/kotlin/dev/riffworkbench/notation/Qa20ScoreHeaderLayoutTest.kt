package dev.riffworkbench.notation

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * QA-Zyklus 20 / Partitur-Kopf (Bericht 1 §9: „Partitur oben kompakter machen, ohne Lesbarkeit zu verlieren“).
 *
 * Gemessen wird das Notenbild wie im Editor (WorkspaceViewModel.relayout): EDIT-Stil, nur TAB, Rand 6, Eingaberaster 1/16,
 * ohne Umbruch (Standardansicht) und umbrochen auf 360/411 Einheiten (= dp bei Zoom 1). Glyph-Boxen wie gezeichnet:
 * TAB-Bünde als farbige Chips (GlyphPainter: Höhe 1,34·Größe um die Saite, Breite max(Text + 0,6·Größe, Höhe)), Texte mit
 * Ober-/Unterlänge 0,78/0,22·Größe (wie Qa15), Pfade über ihre Stützpunkte.
 *
 * Eigenschaften: (1) keine Kopf-Label-Box (Taktnummer, Abschnitt, Tempo) überlappt eine andere Kopf-Label-Box oder irgendeine
 * andere Tinte (Bünde, Bögen, Bends, Effekte, Taktangabe); (2) Raum über und unter dem Inhalt eines Systems ist durch den
 * Inhalt begründet: Systemabstand ≤ Grenzwerte, die aus den Messwerten vor der Änderung abgeleitet sind (Journal Zyklus 20).
 */
class Qa20ScoreHeaderLayoutTest {
    private var n = 0
    private val ids = IdSource { EntityId("q20h-${n++}") }
    private val editorMargin = 6f // WorkspaceViewModel.EDITOR_MARGIN

    // ---------------------------------------------------------------- fixtures
    private fun EditorSession.note(bar: Int, offset: Fraction, string: Int, fret: Int, d: Int = 8, marks: Set<NoteMark> = emptySet(), vararg arts: Articulation) {
        val m = state.value.project.measures[bar]
        select(state.value.selection.copy(measureId = m.id, offset = offset, string = string))
        val r = dispatch(EditorCommand.PutNote(fret = fret, duration = WrittenDuration(d), marks = marks))
        check(r is Outcome.Success) { "PutNote $bar $offset s$string: $r" }
        for (a in arts) check(dispatch(EditorCommand.ApplyArticulation(a, toggle = false)) is Outcome.Success)
    }

    /** Standardprojekt (1×4/4, „Riff A“, 150 BPM) + 3 Takte mit Noten unter den Kopf-Labels; [bends]: dazu Bends, Hammer-on, Tempo in der Taktmitte. */
    private fun standard(bends: Boolean): Project {
        val s = EditorSession(ProjectFactory(ids).create("Kopf"), ids)
        repeat(3) { s.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        val top = 5 // hohe E-Saite
        // Takt 1: Noten auf der hohen E-Saite direkt unter „Riff A“ / „♩ = 150“.
        s.note(0, Fraction.ZERO, top, 3, marks = if (bends) setOf(NoteMark.HAMMER_ON) else emptySet())
        s.note(0, Fraction.of(1, 8), top, 5)
        s.note(0, Fraction.of(1, 4), 2, 5, d = 4)
        s.note(0, Fraction.of(1, 2), 0, 0, d = 4)
        // Takt 2: Akkord, Palm-Mute.
        s.note(1, Fraction.ZERO, 0, 0, d = 4, marks = setOf(NoteMark.PALM_MUTE)); s.note(1, Fraction.ZERO, 1, 2, d = 4, marks = setOf(NoteMark.PALM_MUTE))
        s.note(1, Fraction.of(1, 4), top, 12, d = 4)
        // Takt 3: neuer Abschnitt + Tempo, Note auf der hohen Saite unter den Labels.
        s.dispatch(EditorCommand.SetSection(s.state.value.project.measures[2].id, "Riff B"))
        s.dispatch(EditorCommand.SetTempoAt(s.state.value.project.measures[2].id, 120.0))
        s.note(2, Fraction.ZERO, top, 7, d = 4)
        s.note(2, Fraction.of(1, 4), 4, 8, d = 4)
        if (bends) {
            s.note(1, Fraction.of(1, 2), 3, 7, d = 4, arts = arrayOf(Articulations.fullBend()))
            s.note(2, Fraction.of(1, 2), top, 15, d = 4, arts = arrayOf(Articulations.fullBend()))
        }
        // Takt 4: Achteltriole (3:2) auf der B-Saite, direkt im Modell (der Editor setzt Triolen über SetTuplet mit Ripple).
        var p = s.state.value.project
        val m3 = p.measures[3]
        val guitar = p.tracks[0].instrument
        val triplet = (0 until 3).map { k -> Beat(ids.next(), Fraction.of(k.toLong(), 12), WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2))), listOf(Note(ids.next(), Pitch(guitar.pitch(4, 3 + k)), 4, 3 + k))) }
        p = p.copy(tracks = p.tracks.mapIndexed { ti, t -> if (ti != 0) t else t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.map { b ->
            if (b.measureId != m3.id) b else b.copy(voices = b.voices.map { v -> if (v.index == 0) v.copy(beats = triplet) else v })
        }) }) })
        if (bends) p = p.copy(tempo = p.tempo + TempoChange(ScorePosition(p.measures[1].id, Fraction.of(1, 2)), 140.0))
        return p
    }
    private fun demo(): Project = ProjectFactory(ids).demo("Demo")
    /** Demo-Fixture (4/4 + 7/8, 7-Saiter) mit Noten auf der höchsten Saite unter den Kopf-Labels. */
    private fun demoWithNotes(): Project {
        val s = EditorSession(demo(), ids)
        s.note(0, Fraction.ZERO, 6, 5)
        s.note(0, Fraction.of(1, 8), 6, 7, marks = setOf(NoteMark.HAMMER_ON)); s.note(0, Fraction.of(1, 4), 6, 8)
        s.note(1, Fraction.ZERO, 0, 0, d = 4)
        return s.state.value.project
    }

    private fun editorLayout(p: Project, width: Float?) =
        ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT.copy(showTab = true, showStandard = false), width, Fraction.of(1, 16), 0, null, margin = editorMargin))

    // ---------------------------------------------------------------- glyph boxes (as drawn by GlyphPainter in the editor)
    private data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val what: String, val label: Boolean, val bar: Int) {
        fun overlaps(o: Box, eps: Float = 0.01f) = x0 < o.x1 - eps && o.x0 < x1 - eps && y0 < o.y1 - eps && o.y0 < y1 - eps
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
    private fun xRange(g: Glyph): Pair<Float, Float> = when {
        g is TextGlyph && g.chip >= 0 -> { val w = g.width; val cw = max(w + g.size * 0.6f, g.size * 1.34f); val cx = g.minX + w / 2; cx - cw / 2 to cx + cw / 2 }
        g is LineGlyph -> g.minX - g.width / 2 to g.maxX + g.width / 2
        else -> g.minX to g.maxX
    }
    /** Glyphs drawn by the top lane of a bar: bar number, section pill (+ its text), tempo marks. */
    private fun isLaneLabel(g: Glyph): String? = when {
        g is TextGlyph && g.ink == Ink.MUTED && g.size == 8.5f && g.align == Align.START && g.text.all { it.isDigit() } -> "Taktnummer ${g.text}"
        g is RectGlyph && g.ink == Ink.SECTION -> "Abschnitt"
        g is TextGlyph && g.ink == Ink.ON_SECTION -> "Abschnitt '${g.text}'"
        g is TextGlyph && g.text.startsWith("♩ =") -> "Tempo '${g.text}'"
        else -> null
    }
    private fun describe(g: Glyph) = when (g) {
        is TextGlyph -> "Text('${g.text}' ${g.ink}${if (g.chip >= 0) " chip" else ""})"; is PathGlyph -> "Pfad(${g.ink})"; is LineGlyph -> "Linie(${g.ink})"
        is RectGlyph -> "Rechteck(${g.ink})"; is OvalGlyph -> "Oval(${g.ink})"
    }
    private fun boxes(l: ScoreLayout): List<Box> = l.glyphs.map { g ->
        val (x0, x1) = xRange(g); val (y0, y1) = yRange(g)
        val bar = l.measures.firstOrNull { (x0 + x1) / 2 >= it.x && (x0 + x1) / 2 < it.x + it.width && l.systems[it.system].let { s -> (y0 + y1) / 2 >= s.top && (y0 + y1) / 2 < s.bottom } }?.index ?: -1
        val lane = isLaneLabel(g)
        Box(x0, y0, x1, y1, lane ?: describe(g), lane != null, bar)
    }

    /** Overlaps of a top-lane label with another label (another group) or any other ink. A section's text inside its own pill is one label. */
    private fun collisions(l: ScoreLayout): List<String> {
        val all = boxes(l)
        val labels = all.filter { it.label }
        val out = ArrayList<String>()
        for (a in labels) for (b in all) {
            if (a === b) continue
            if (b.label && (a.what.startsWith("Abschnitt") && b.what.startsWith("Abschnitt") && a.bar == b.bar)) continue
            if (b.label && labels.indexOf(b) < labels.indexOf(a)) continue // each label pair once
            if (a.overlaps(b)) out += "Takt ${a.bar + 1}: ${a.what} [${"%.1f".format(a.x0)}..${"%.1f".format(a.x1)} × ${"%.1f".format(a.y0)}..${"%.1f".format(a.y1)}] ∩ ${b.what} [${"%.1f".format(b.x0)}..${"%.1f".format(b.x1)} × ${"%.1f".format(b.y0)}..${"%.1f".format(b.y1)}]"
        }
        return out
    }

    private class Metrics(val name: String, val firstTabLine: Float, val inkTop: Float, val labelBottom: Float, val numberToTab: Float, val laneToNotes: Float,
                          val systemHeight: Float, val pitch: Float?, val bottomAir: Float, val layoutHeight: Float, val systems: Int, val collisions: List<String>) {
        override fun toString() = "%-34s erste TAB-Linie y=%5.1f | Tinte ab y=%4.1f | Label-Unterkante %5.1f | Taktnummer→TAB %4.1f | Label→höchste TAB-Tinte %5.1f | System %5.1f | Pitch %s | Luft unten %4.1f | Höhe %5.1f | %d Sys. | %d Kollisionen"
            .format(name, firstTabLine, inkTop, labelBottom, numberToTab, laneToNotes, systemHeight, pitch?.let { "%5.1f".format(it) } ?: "  —  ", bottomAir, layoutHeight, systems, collisions.size)
    }
    private fun measure(name: String, l: ScoreLayout): Metrics {
        val all = boxes(l)
        val s0 = l.systems[0]
        val sys0 = all.filter { it.y0 < s0.bottom && it.y1 > s0.top - 50f && (it.y0 + it.y1) / 2 < s0.bottom }
        val labels = sys0.filter { it.label }
        val labelBottom = labels.maxOfOrNull { it.y1 } ?: s0.top
        val number = labels.filter { it.what.startsWith("Taktnummer") }.maxOfOrNull { it.y1 } ?: s0.top
        // Highest ink that belongs to the TAB part (everything but the lane labels).
        val tabInkTop = sys0.filter { !it.label }.minOfOrNull { it.y0 } ?: s0.tabTop
        val inkBottom = sys0.maxOf { it.y1 }
        return Metrics(name, s0.tabTop, sys0.minOf { it.y0 }, labelBottom, s0.tabTop - number, tabInkTop - labelBottom, s0.bottom - s0.top,
            l.systems.getOrNull(1)?.let { it.top - s0.top }, s0.bottom - inkBottom, l.height, l.systems.size, collisions(l))
    }

    private fun cases(): List<Pair<String, ScoreLayout>> {
        val fixtures = listOf("Standard leer" to ProjectFactory(ids).create("Leer"), "Standard + Noten" to standard(false), "Standard + Bends/Bögen" to standard(true),
            "Demo" to demo(), "Demo + Noten" to demoWithNotes())
        return fixtures.flatMap { (name, p) -> listOf(null, 360f, 411f).map { w -> "$name @${w?.toInt() ?: "∞"}" to editorLayout(p, w) } }
    }

    @Test fun measureTheScoreHeader() {
        val ms = cases().map { (name, l) -> measure(name, l) }
        println("QA20-Kopf (EDIT, TAB, Rand $editorMargin, Raster 1/16; Einheiten = dp bei Zoom 1)")
        ms.forEach { println(it) }
        ms.filter { it.collisions.isNotEmpty() }.forEach { m -> println("  ${m.name}:"); m.collisions.forEach { println("    $it") } }
        for ((name, l) in cases()) if (l.systems.size > 1) println("  $name je System: " + l.systems.joinToString(" | ") { s ->
            "Takt ${l.measures.filter { it.system == s.index }.joinToString(",") { "${it.index + 1}" }}: Kopf %.1f, Höhe %.1f, Luft unten %.1f".format(s.tabTop - s.top, s.bottom - s.top, s.bottom - inkBottom(l, s)) })
    }
    private fun inkBottom(l: ScoreLayout, s: SystemBox) = boxes(l).filter { (it.y0 + it.y1) / 2 >= s.top && (it.y0 + it.y1) / 2 < s.bottom }.maxOf { it.y1 }
    private fun layoutOf(name: String) = cases().first { it.first == name }.second

    /** (1) Kopf-Labels überlappen weder einander noch Bünde, Bögen, Bends, Effekte oder die Taktangabe. */
    @Test fun headerLabelsDoNotCollide() {
        val failures = cases().flatMap { (name, l) -> collisions(l).map { "$name: $it" } }
        assertTrue("${failures.size} Kollisionen:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** Nichts wird oben abgeschnitten: alle Tinte liegt im Layout und im Band ihres Systems (Bögen/Bends über der hohen Saite). */
    @Test fun nothingLeavesTheTopOfItsSystem() {
        for ((name, l) in cases()) for (b in boxes(l)) {
            assertTrue("$name: ${b.what} bei y=${b.y0} über dem Layout", b.y0 >= 0f)
            val s = l.systems.first { (b.y0 + b.y1) / 2 >= it.top && (b.y0 + b.y1) / 2 < it.bottom || it.index == l.systems.lastIndex }
            assertTrue("$name: ${b.what} bei y=${b.y0} über System ${s.index} (top ${s.top})", b.y0 >= s.top - 0.01f)
        }
    }

    /**
     * (2a) Leerraum über dem ersten System. Vorher lag die erste TAB-Linie immer 24 Einheiten unter dem Systemanfang (Spur
     * 18 + pauschal 6 für Bünde der hohen Saite), bei y = 30 mit Rand 6 — auch ohne eine Note auf der hohen Saite.
     * Jetzt: direkt unter der Kopfspur (18), wenn keine Tinte über der hohen Saite ein Kopf-Label trifft.
     */
    @Test fun firstTabLineSitsRightUnderTheLane() {
        for (name in listOf("Standard leer", "Demo")) for (w in listOf("∞", "360", "411")) {
            val l = layoutOf("$name @$w")
            assertTrue("$name @$w: erste TAB-Linie bei y=${l.systems[0].tabTop}, erwartet ≤ ${editorMargin + 18f} (vorher 30)", l.systems[0].tabTop <= editorMargin + 18f + 0.01f)
        }
    }

    /**
     * (2b) Systemabstand nur so groß wie der Inhalt: unter dem Rhythmus (Hälse/Balken bei tabBottom + 31,2, Triolenziffern
     * bis + 42,7) blieben vorher 35,0 (ohne Triolen) bzw. 23,7 Einheiten bis zum Systemende (Rhythmusspur pauschal 40,4 +
     * Abstand 26). Jetzt Rhythmus nach Inhalt + 14: Luft unten ≤ 15, System ohne Kopf-Kollision ≤ 184 (vorher 210,4).
     */
    @Test fun systemPitchFollowsTheContent() {
        for (w in listOf("360", "411")) {
            val l = layoutOf("Standard + Noten @$w")
            for (s in l.systems) {
                val air = s.bottom - inkBottom(l, s)
                assertTrue("@$w System ${s.index}: Luft unten $air (vorher 35,0)", air <= 15f)
                assertTrue("@$w System ${s.index}: Höhe ${s.bottom - s.top} (vorher 210,4)", s.bottom - s.top <= 193f)
            }
            val plain = l.systems.filter { s -> l.measures.filter { it.system == s.index }.all { it.index == 1 || it.index == 3 } } // Takte ohne Abschnitt/Tempo
            assertTrue(plain.isNotEmpty())
            for (s in plain) assertTrue("@$w System ${s.index}: Höhe ${s.bottom - s.top}, erwartet ≤ 184 (vorher 210,4)", s.bottom - s.top <= 184f)
        }
        // Ohne Umbruch (Standardansicht im Editor): 7-saitiges Demo vorher 246,4 Einheiten hoch.
        val demo = layoutOf("Demo @∞")
        assertTrue("Demo @∞: Layouthöhe ${demo.height} (vorher 246,4)", demo.height <= 222f)
    }

    /** (2c) Bend-Vorhalt (27 Einheiten) nur in Systemen mit Bend/Whammy; vorher in jedem System der Spur, sobald irgendwo ein Bend stand. */
    @Test fun bendHeadroomOnlyWhereThereIsABend() {
        for (w in listOf("360", "411")) {
            val with = layoutOf("Standard + Bends/Bögen @$w"); val without = layoutOf("Standard + Noten @$w")
            // Takt 4 (Triolen) ist in beiden Fixtures gleich und hat keinen Bend.
            val a = with.systems[with.measures[3].system]; val b = without.systems[without.measures[3].system]
            assertTrue("@$w Takt 4: Kopf ${a.tabTop - a.top} mit Bends anderswo, ${b.tabTop - b.top} ohne (vorher 51 gegen 24)", a.tabTop - a.top <= b.tabTop - b.top + 0.01f)
        }
    }

    /** Lesbarkeit: Schrift-/Glyphgrößen und Trefferflächen (Taktangabe, Takt) bleiben, wie sie waren. */
    @Test fun readabilityAndTouchTargetsAreKept() {
        val st = LayoutStyle.EDIT
        assertEquals(24f, st.tabLine, 0f); assertEquals(15f, st.fretText, 0f); assertEquals(8f, st.staffSpace, 0f); assertEquals(30f, st.column16, 0f)
        for ((name, l) in cases()) {
            val texts = l.glyphs.filterIsInstance<TextGlyph>()
            texts.filter { isLaneLabel(it)?.startsWith("Taktnummer") == true }.forEach { assertEquals("$name Taktnummer", 8.5f, it.size, 0f) }
            texts.filter { it.ink == Ink.ON_SECTION }.forEach { assertEquals("$name Abschnitt", 10f, it.size, 0f) }
            texts.filter { it.text.startsWith("♩ =") }.forEach { assertEquals("$name Tempo", 9.5f, it.size, 0f) }
            texts.filter { it.chip >= 0 }.forEach { assertEquals("$name Bund", 15f, it.size, 0f) }
            // Taktangabe (Long-Press): ganze TAB-Höhe + je eine halbe Saite darüber/darunter, so breit wie die Taktangaben-Spalte.
            for (box in l.signatures) {
                val s = l.systems[box.system]
                assertEquals("$name Taktangabe Höhe", s.tabBottom - s.tabTop + st.tabLine, box.h, 0.01f)
                assertEquals("$name Taktangabe Breite", st.tabLine * 1.4f + 4f, box.w, 0.01f)
                assertEquals(box.measureId, l.signatureAt(box.x + box.w / 2, (s.tabTop + s.tabBottom) / 2)?.measureId)
            }
            // Bereichsauswahl (measureAt): die Systeme kacheln die Höhe lückenlos, jeder Takt trifft vom Systemanfang bis zur
            // untersten Tinte seines Systems (inkl. Abstand bis zum nächsten System).
            for (k in 1 until l.systems.size) assertEquals("$name Systeme lückenlos", l.systems[k - 1].bottom, l.systems[k].top, 0.01f)
            for (m in l.measures) {
                val s = l.systems[m.system]
                for (y in listOf(s.top + 0.5f, s.tabTop, (s.tabTop + s.tabBottom) / 2, inkBottom(l, s), s.bottom - 0.5f))
                    assertEquals("$name Takt ${m.index + 1} bei y=$y", m.measureId, l.measureAt(m.x + m.width / 2, y)?.measureId)
                assertTrue("$name Takt ${m.index + 1}: Trefferband ${s.bottom - s.top}", s.bottom - s.top >= 150f)
            }
        }
    }
}
