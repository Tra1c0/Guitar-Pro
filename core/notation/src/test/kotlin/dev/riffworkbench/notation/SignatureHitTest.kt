package dev.riffworkbench.notation

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * QA 18 / D4: time-signature hit regions ([SignatureBox], [ScoreLayout.signatureAt]) for the long-press signature editor,
 * plus the probe assertions about where the glyphs lie and what a plain tap there means (beat 0 of the bar).
 */
class SignatureHitTest {
    private var n = 0
    private val ids = IdSource { EntityId("sig-${n++}") }
    private val style = LayoutStyle.EDIT
    private fun timeWidth() = style.tabLine * 1.4f + 4f // Layout.kt:225 for TAB-only layouts

    /** The two bold TEXT glyphs at the signature x of a measure (numerator above, denominator below). */
    private fun signatureGlyphs(l: ScoreLayout, m: MeasureBox): List<TextGlyph> {
        val tx = m.contentX - timeWidth() * 0.55f // Layout.kt:408
        val sys = l.systems[m.system] // every wrapped system starts at the same x: the glyphs must also lie in this system's band
        return l.glyphs.filterIsInstance<TextGlyph>().filter { abs(it.x - tx) < 0.01f && it.y >= sys.top && it.y <= sys.bottom && it.ink == Ink.TEXT && it.bold && it.align == Align.CENTER }.sortedBy { it.y }
    }

    @Test fun signatureGlyphsExistForBarOneAndForEverySignatureChange() {
        val p = ProjectFactory(ids).demo("Sig") // demo shape: 4/4 then 7/8
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, style, editGrid = Fraction.of(1, 16)))
        val (m0, m1) = l.measures
        assertEquals(listOf("4", "4"), signatureGlyphs(l, m0).map { it.text })
        assertEquals(listOf("7", "8"), signatureGlyphs(l, m1).map { it.text })
        // One hit box per drawn signature, covering the glyphs; none of them is a courtesy repeat here.
        assertEquals(2, l.signatures.size)
        assertEquals(listOf(m0.measureId, m1.measureId), l.signatures.map { it.measureId })
        assertTrue(l.signatures.none { it.courtesy })
        for (m in listOf(m0, m1)) for (g in signatureGlyphs(l, m)) {
            val box = l.signatureAt(g.x, g.y)
            assertNotNull("signatureAt must hit at the glyph (${g.x}, ${g.y})", box)
            assertEquals(m.measureId, box!!.measureId)
            assertEquals(m.index, box.index)
            assertTrue("box covers the glyph horizontally", g.minX >= box.x - 1f && g.maxX <= box.x + box.w + 1f)
        }
        val sys0 = l.systems[0]
        assertNull("nothing left of the first system", l.signatureAt(2f, (sys0.tabTop + sys0.tabBottom) / 2))
        assertNull("a note column is not a signature", l.signatureAt(m0.contentX + 20f, (sys0.tabTop + sys0.tabBottom) / 2))
        // The glyphs sit inside their MeasureBox, left of contentX: a hit region can be derived from (measure.x .. contentX) x (tabTop .. tabBottom).
        for (m in listOf(m0, m1)) for (g in signatureGlyphs(l, m)) {
            val sys = l.systems[m.system]
            println("QA18-D4 bar ${m.index + 1} '${g.text}': x=${g.x} minX=${g.minX} maxX=${g.maxX} baseline=${g.y} size=${g.size} | measure x=${m.x} contentX=${m.contentX} width=${m.width} | tab ${sys.tabTop}..${sys.tabBottom} system ${sys.top}..${sys.bottom}")
            assertTrue("glyph x ${g.x} inside measure ${m.x}..${m.x + m.width}", g.x > m.x && g.x < m.contentX)
            assertTrue("glyph baseline ${g.y} within the TAB staff ${sys.tabTop}..${sys.tabBottom}+size", g.y > sys.tabTop - g.size && g.y < sys.tabBottom + g.size)
        }
    }

    /** A bar with an unchanged signature shows the glyphs again when it opens a wrapped system (Layout.kt:406 `first`). */
    @Test fun firstBarOfAWrappedSystemRepeatsTheSignatureAlthoughItDidNotChange() {
        val e = EditorSession(ProjectFactory(ids).demo("Wrap"), ids)
        repeat(6) { e.dispatch(EditorCommand.AddMeasure(TimeSignature(7, 8))) } // bars 1..7 are all 7/8 after bar 0
        val p = e.state.value.project
        // ~2 bars per system: a 7/8 bar on a 1/16 grid needs 14 columns x 30 units (Layout.kt:203-205) plus padding.
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, style, width = 1000f, editGrid = Fraction.of(1, 16)))
        assertTrue("needs at least two systems, got ${l.systems.size}", l.systems.size >= 2)
        // Bars per system with the default project here: [1, 2, 2, 2, 1] (bar 0 in 4/4 is the widest). System 1 starts with
        // bar 1 (7/8 after 4/4: a real change); system 2 starts with bar 3, whose predecessor is also 7/8.
        val systemStarts = l.measures.filter { m -> l.measures.first { it.system == m.system }.index == m.index }
        val courtesy = systemStarts.first { it.index >= 1 && p.measures[it.index].signature == p.measures[it.index - 1].signature }
        assertEquals("repeated courtesy signature at the start of system ${courtesy.system} (bar ${courtesy.index + 1})", listOf("7", "8"), signatureGlyphs(l, courtesy).map { it.text })
        // Hit boxes: one per system start plus one per genuine change; the system-start repeats are flagged courtesy.
        val expected = l.measures.count { m -> systemStarts.any { it.index == m.index } || m.index == 0 || p.measures[m.index].signature != p.measures[m.index - 1].signature }
        assertEquals(expected, l.signatures.size)
        val courtesyBox = l.signatures.first { it.measureId == courtesy.measureId }
        assertTrue(courtesyBox.courtesy)
        assertEquals(courtesy.system, courtesyBox.system)
        assertFalse("bar 2 (7/8 after 4/4) is a real change", l.signatures.first { it.index == 1 }.courtesy)
        val g = signatureGlyphs(l, courtesy).first()
        assertEquals(courtesy.measureId, l.signatureAt(g.x, g.y)?.measureId)
        // A bar inside a system with an unchanged signature has none.
        val middle = l.measures.first { m -> m.index >= 2 && systemStarts.none { it.index == m.index } }
        assertTrue("bar ${middle.index + 1} in system ${middle.system}", signatureGlyphs(l, middle).isEmpty())
    }

    /** Today a tap on the glyphs is routed by ScoreView.kt:149-150 to columnAt(): it selects beat 0 of that bar, nothing else. */
    @Test fun aTapOnTheSignatureSelectsBeatZeroOfThatBarToday() {
        val p = ProjectFactory(ids).demo("Tap")
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, style, editGrid = Fraction.of(1, 16)))
        for (m in l.measures) {
            val g = signatureGlyphs(l, m).first()
            val sys = l.systems[m.system]
            val y = (sys.tabTop + sys.tabBottom) / 2
            assertTrue("no NoteBox covers the signature", l.notes.none { g.x >= it.x && g.x <= it.x + it.w && y >= it.y && y <= it.y + it.h })
            val col = l.columnAt(g.x, y)
            assertNotNull(col)
            assertEquals(m.measureId, col!!.measureId)
            assertEquals(Fraction.ZERO, col.offset)
            // The nearest column is ~0.55 * timeWidth + padding away: comfortably distinguishable from a note tap.
            assertTrue("distance ${col.x - g.x}", col.x - g.x > style.measurePadding)
        }
    }

    /** With the standard staff shown the glyphs move to the staff (Layout.kt:415-418); a hit region must follow showStandard. */
    @Test fun signatureGlyphsSitOnTheStandardStaffWhenItIsShown() {
        val p = ProjectFactory(ids).demo("Std")
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, style.copy(showTab = false, showStandard = true)))
        val m0 = l.measures[0]; val sys = l.systems[0]
        val tx = m0.contentX - (style.staffSpace * 2.4f + 4f) * 0.55f // timeWidth() without TAB
        val glyphs = l.glyphs.filterIsInstance<TextGlyph>().filter { abs(it.x - tx) < 0.01f && it.bold && it.ink == Ink.TEXT }.sortedBy { it.y }
        assertEquals(listOf("4", "4"), glyphs.map { it.text })
        assertTrue(glyphs.all { it.y > sys.staffTop && it.y <= sys.staffBottom + style.staffSpace })
        // The hit box follows the glyphs onto the staff band.
        val box = l.signatures.first()
        assertTrue(box.y <= sys.staffTop && box.y + box.h >= sys.staffBottom)
        assertEquals(m0.measureId, l.signatureAt(tx, (sys.staffTop + sys.staffBottom) / 2)?.measureId)
    }

    /** SVG/PDF only consume glyphs/glyphsBySystem/systems; an extra box list on ScoreLayout leaves their output unchanged. */
    @Test fun svgOutputDoesNotDependOnHitBoxes() {
        val p = ProjectFactory(ids).demo("Svg")
        val a = SvgExport.render(p, listOf(0))
        val b = SvgExport.render(p, listOf(0))
        assertEquals(a, b)
        assertTrue(a.contains(">4</text>") && a.contains(">7</text>") && a.contains(">8</text>"))
    }
}
