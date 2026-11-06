package dev.riffworkbench.notation

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 23 (Gerätebefund „Partitur größer“): the display scale [ScoreScale.EDITOR] measured on the editor layout of the standard
 * project (WorkspaceViewModel.relayout: TAB only, 16th grid, margin 6, unwrapped), in dp at zoom 1.
 *
 *  S1  readable on a phone: fret chip (GlyphPainter: 1.34 × text size) ≥ 14 dp, string spacing ≥ 18 dp;
 *  S2  as large as fits: one TAB system with its rhythm lane fits the canvas of a 411 × 891 dp phone at this scale, the next
 *      step (+0.25) does not;
 *  S3  what the phone shows horizontally at this scale (printed: column pitch and the visible share of a bar at 360/411 dp;
 *      horizontal scrolling stays).
 *
 * Canvas of a 411 × 891 phone (EditorScreen, computed from the modifiers): 891 − status bar 48 − gesture bar 24 = 819;
 * minus TopBar 48, title row 42, bar map 24, card padding 8 + card row 48, canvas bottom padding 4, controls 272
 * (4 + 54 + 52 + 3 × 46 + 4 × 6), navigation 80 → 293 dp. Width: 411 − 2 × 12 − 2 × 4 = 379 dp (360: 328 dp).
 */
class Qa23ScoreSizeTest {
    private var n = 0
    private val ids = IdSource { EntityId("q23s-${n++}") }
    private val canvasHeight411 = 293f

    private fun layout(p: Project) = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT.copy(showTab = true, showStandard = false), null, Fraction.of(1, 16), 0, null, margin = 6f))
    private fun withNotes(): Project {
        val s = EditorSession(ProjectFactory(ids).create("QA23"), ids)
        repeat(3) { s.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        s.state.value.project.measures.forEach { m ->
            for (k in 0 until 8) {
                s.select(s.state.value.selection.copy(measureId = m.id, offset = Fraction.of(k.toLong(), 8), string = k % 6))
                s.dispatch(EditorCommand.PutNote(fret = 10 + k, duration = WrittenDuration(8)))
            }
        }
        return s.state.value.project
    }

    @Test fun editorScaleIsReadableAndFits() {
        val l = layout(withNotes())
        val scale = ScoreScale.EDITOR
        val sys = l.systems.first()
        val spacing = abs(sys.stringY[0] - sys.stringY[1])
        val chips = l.glyphs.filterIsInstance<TextGlyph>().filter { it.chip >= 0 }
        assertTrue("precondition: fret chips drawn", chips.isNotEmpty())
        val chip = chips.maxOf { it.size } * 1.34f
        val digits = chips.maxOf { it.size }
        val height = sys.bottom - sys.top
        val cols = l.columns.map { it.x }.sorted().zipWithNext { a, b -> b - a }.filter { it > 0.5f }
        val col16 = cols.minOrNull() ?: 0f
        val bar = l.measures.first().width
        println("QA23 size at scale $scale: chip ${chip * scale} dp (digits ${digits * scale}), spacing ${spacing * scale} dp, system ${height * scale} dp " +
            "(zoom 1 before: chip $chip, spacing $spacing, system $height); narrowest column pitch ${col16 * scale} dp, 4/4 bar of eighths with two-digit frets ${bar * scale} dp; " +
            "visible part of that bar at 360 dp: ${(328f / (bar * scale) * 100).toInt()} %, 411 dp: ${(379f / (bar * scale) * 100).toInt()} %")
        assertTrue("S1 chip ≥ 14 dp: ${chip * scale}", chip * scale >= 14f)
        assertTrue("S1 string spacing ≥ 18 dp: ${spacing * scale}", spacing * scale >= 18f)
        assertTrue("S1 larger than before (zoom 1)", scale > 1f)
        assertTrue("S2 one system fits the 411 × 891 canvas: ${height * scale} ≤ $canvasHeight411", height * scale <= canvasHeight411)
        assertTrue("S2 the next step would not fit: ${height * (scale + 0.25f)}", height * (scale + 0.25f) > canvasHeight411)
    }
}
