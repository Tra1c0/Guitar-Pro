package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 23 / Daten, Auswahl, Raster — Treffer im Notenbild bei feinen Rastern (Gerätebefund „über 16tel nicht nutzbar“,
 * P0 „Noten unbemerkt verschoben“). Editor-Layout wie im Telefon: EDIT-Stil, umbrochen auf 360 Einheiten, Rand 6.
 *
 *  T1 Der leere 1/32- bzw. 1/64-Platz direkt neben einer zweistelligen Bundzahl ist antippbar: vorher gewann jede
 *     Notenbox unter dem Finger (ScoreView), die Eingabe überschrieb die Nachbarnote.
 *  T2 Die Mitte einer Bundzahl wählt weiter ihren eigenen Beat.
 *  T3 Der Platz direkt nach einer punktierten Note bzw. einer Triole ist eine Spalte, auch wenn er kein Rastervielfaches ist.
 *  D  Noten-Drag: gemessen ab der Notenmitte; ein Verrutschen beim Tippen (unter 12 Einheiten bzw. ¾ Saitenabstand) ist
 *     kein Verschieben; ein bewusster Zug um eine Spalte/Saite bleibt möglich.
 */
class Qa23FineGridHitTest {
    private var n = 0
    private fun id() = EntityId("q23n${n++}")
    private val guitar = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = List(6) { 0 })
    private fun note(s: Int, f: Int) = Note(id(), Pitch(guitar.pitch(s, f)), s, f)
    private fun project(beats: List<Beat>): Project {
        val m = Measure(id(), TimeSignature())
        val track = Track(id(), "G", guitar, listOf(Staff(id(), bars = listOf(Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) beats else emptyList()) })))))
        return Project(id = id(), measures = listOf(m), tracks = listOf(track))
    }
    private fun layout(p: Project, grid: Fraction) = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT, width = 360f, editGrid = grid, margin = 6f))
    /** The old ScoreView rule: the first note box under the finger wins. */
    private fun oldRule(l: ScoreLayout, x: Float, y: Float) = l.notes.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }

    @Test fun t1_theEmptyFineSlotNextToATwoDigitFretIsTappable() {
        for (grid in listOf(Fraction.of(1, 32), Fraction.of(1, 64))) {
            val p = project(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(grid.denominator.toInt()), listOf(note(2, 12)))))
            val l = layout(p, grid)
            val box = l.notes.single()
            val next = l.columns.first { it.offset == grid }
            val y = l.systems[0].stringY[2]
            // On the right edge of the number's box, already closer to the slot's dot than to the number's centre
            // (1/32: box ends 3 units before the dot, the anchors are 14 apart; a finger aimed at the dot lands there often).
            val x = box.x + box.w - 0.5f
            assertTrue("$grid: slot ${next.x} closer than the number centre ${box.x + box.w / 2}", abs(next.x - x) < abs(box.x + box.w / 2 - x))
            assertNotNull("$grid: precondition — the old rule hits the note there (bug): box=$box next=${next.x} cols=${l.columns.take(4).map { it.x }}", oldRule(l, x, y))
            val hit = l.tapAt(x, y)!!
            assertEquals("$grid: the slot, not the note", grid, hit.offset)
            assertNull(hit.noteId)
            assertEquals(2, hit.string)
        }
    }

    @Test fun t2_theCentreOfAFretNumberSelectsItsBeat() {
        val g = Fraction.of(1, 64)
        val beats = (0L until 8L).map { k -> Beat(id(), Fraction.of(k, 64), WrittenDuration(64), listOf(note(1 + (k % 3).toInt(), 10 + k.toInt()))) }
        val l = layout(project(beats), g)
        for (b in l.notes) {
            val hit = l.tapAt(b.x + b.w / 2, b.y + b.h / 2)!!
            assertEquals(b.offset, hit.offset); assertEquals(b.noteId, hit.noteId)
        }
        // Every column anchor selects its own column (also the virtual 1/64 slots after the notes).
        for (c in l.columns) assertEquals(c.offset, l.tapAt(c.x, l.systems[0].stringY[0])!!.offset)
    }

    @Test fun t3_theSlotRightAfterADottedNoteOrATupletIsAColumn() {
        val dotted = layout(project(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(8, 1), listOf(note(0, 3))))), Fraction.of(1, 8))
        assertTrue("3/16 after a dotted 1/8 on the 1/8 grid", dotted.columns.any { it.offset == Fraction.of(3, 16) && it.virtual })
        val triplet = layout(project(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2))), listOf(note(0, 3))))), Fraction.of(1, 16))
        assertTrue("1/12 after a triplet 1/8 on the 1/16 grid", triplet.columns.any { it.offset == Fraction.of(1, 12) && it.virtual })
    }

    @Test fun d_aSlipWhileTappingTheSelectedNoteMovesNothing() {
        val g = Fraction.of(1, 32)
        val p = project(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(32), listOf(note(2, 5)))))
        val l = layout(p, g)
        val box = l.notes.single()
        val sys = l.systems[0]
        val spacing = abs(sys.stringY[0] - sys.stringY[1])
        assertNull("slip 8 right", l.dropTarget(box, 2, 8f, 0f))
        assertNull("slip half a string", l.dropTarget(box, 2, 3f, spacing * 0.5f))
        val next = l.columns.first { it.offset == g }
        val right = l.dropTarget(box, 2, abs(next.x - (box.x + box.w / 2)).coerceAtLeast(DRAG_MIN_TRAVEL), 0f)!!
        assertEquals("a deliberate drag of one column", g, right.offset); assertEquals(2, right.string)
        val up = l.dropTarget(box, 2, 0f, sys.stringY[3] - sys.stringY[2])!!
        assertEquals("a deliberate drag of one string", 3, up.string); assertEquals(Fraction.ZERO, up.offset)
    }
}
