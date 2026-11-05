package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/** Minimal deterministic repros of the violations found by [Qa15LayoutPropertyTest] (QA cycle 15). Red before a fix. */
class Qa15LayoutFindingsTest {
    private var n = 0
    private fun id() = EntityId("f${n++}")
    private fun project(ins: InstrumentDefinition, measures: List<Measure>, bars: List<List<Beat>>, voice1: List<List<Beat>> = emptyList()): Project {
        val track = Track(id(), "T", ins, listOf(Staff(id(), bars = measures.mapIndexed { i, m ->
            Bar(id(), m.id, List(4) { v -> Voice(id(), v, when (v) { 0 -> bars.getOrElse(i) { emptyList() }; 1 -> voice1.getOrElse(i) { emptyList() }; else -> emptyList() }) })
        })))
        return Project(id = id(), measures = measures, tracks = listOf(track))
    }
    private val guitar = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = List(6) { 0 })
    private fun gNote(s: Int, f: Int, arts: List<Articulation> = emptyList(), links: List<NoteLink> = emptyList(), nid: EntityId = id()) = Note(nid, Pitch(guitar.pitch(s, f)), s, f, articulations = arts, links = links)

    // ------------------------------------------------------------------ F1 (P1)
    /** Without TAB (notation mode STANDARD, every percussion/piano track) the layout emits no NoteBox at all. */
    @Test fun standardNotationNoteheadsHaveNoteBoxes() {
        val m = Measure(id())
        val p = project(guitar, listOf(m), listOf(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(gNote(2, 5))))))
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT.copy(showTab = false, showStandard = true)))
        assertEquals("one NoteBox per note in standard-only layout", 1, l.notes.size)
    }

    @Test fun percussionNotesHaveNoteBoxes() {
        val drums = InstrumentDefinition(kind = InstrumentKind.PERCUSSION, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0)
        val m = Measure(id())
        val p = project(drums, listOf(m), listOf(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(38)))))))
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT))
        assertEquals("one NoteBox per drum note", 1, l.notes.size)
    }

    // ------------------------------------------------------------------ F2 (P5)
    /** A whammy dive (negative cents) is drawn below the string by half the bend height; nothing reserves that room. */
    @Test fun whammyDiveStaysInsideItsSystem() {
        val m = Measure(id())
        val dive = Articulation.Whammy(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 2), -400)))
        val p = project(guitar, listOf(m), listOf(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(gNote(0, 5, listOf(dive)))))))
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT.copy(showStandard = true)))
        val sys = l.systems.single()
        val lowest = l.glyphs.filterIsInstance<PathGlyph>().maxOf { g -> (1 until g.ops.size).filter { k -> isY(g.ops, k) }.maxOf { g.ops[it] } }
        assertTrue("whammy reaches y=$lowest, system ends at ${sys.bottom}, layout height ${l.height}", lowest <= sys.bottom)
        assertTrue("every glyph is drawn by some system (PDF draws only glyphsBySystem[0 until systems.size])", l.glyphsBySystem.last().isEmpty())
    }
    /** True when ops[k] is a y coordinate of a path op. */
    private fun isY(o: FloatArray, k: Int): Boolean {
        var i = 0
        while (i < o.size) { val n = when (o[i].toInt()) { 0, 1 -> 2; 2 -> 6; else -> 0 }; if (k in i + 1..i + n) return (k - i - 1) % 2 == 1; i += 1 + n }
        return false
    }

    // ------------------------------------------------------------------ F3 (P2)
    /**
     * ScoreView: "A hit fret number selects its own beat, even where compressed note boxes overlap the neighbouring column."
     * Column widths count the fret digits (+2 only for ghost/harmonic), not the parentheses of a tie target "(12)", so in a
     * compressed bar the tie target's box covers the centre of the next fret number and a tap there selects the wrong beat.
     */
    @Test fun tapOnTheCentreOfAFretNumberSelectsItsOwnBeat() {
        // Shrunk from seed 77000288: 4-string tuning, 15/8 bar, READ style, wrap width 186.
        val ins = InstrumentDefinition(tuning = listOf(39, 44, 49, 54), capoByString = List(4) { 0 }, maxFret = 12)
        fun nt(f: Int, links: List<NoteLink> = emptyList(), nid: EntityId = id()) = Note(nid, Pitch(ins.pitch(2, f)), 2, f, links = links)
        val target = id()
        val m = Measure(id(), TimeSignature(15, 8))
        val beats = listOf(
            Beat(id(), Fraction.of(773, 480), WrittenDuration(8), listOf(nt(10, listOf(NoteLink(LinkKind.TIE, target))))),
            Beat(id(), Fraction.of(281, 160), WrittenDuration(64), listOf(nt(10, nid = target))), // tie target, drawn "(10)"
            Beat(id(), Fraction.of(567, 320), WrittenDuration(64), listOf(nt(8))),
            Beat(id(), Fraction.of(143, 80), WrittenDuration(16), explicitRest = true),
        )
        val l = ScoreLayouter.layout(LayoutRequest(project(ins, listOf(m), listOf(beats)), 0, LayoutStyle.READ, width = 186f))
        val eight = l.notes.single { it.offset == Fraction.of(567, 320) }
        val cx = eight.x + eight.w / 2; val cy = eight.y + eight.h / 2
        val hit = l.notes.first { cx >= it.x && cx <= it.x + it.w && cy >= it.y && cy <= it.y + it.h } // as ScoreView's tap handler
        assertEquals("tap at the centre of fret 8 (box ${eight.x}..${eight.x + eight.w}) selects the tie target's box ${hit.x}..${hit.x + hit.w}", eight.offset, hit.offset)
    }
}
