package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/** Defects found by geometric property testing of the layout and SVG output (QA cycle 3). */
class RenderingRegressionTest {
    private var n = 0
    private fun id() = EntityId("rr${n++}")
    private val ins = InstrumentDefinition()
    private fun note(s: Int, f: Int, links: List<NoteLink> = emptyList(), nid: EntityId = id()) = Note(nid, Pitch(ins.pitch(s, f)), s, f, links = links)
    private fun beats(count: Int, den: Int, mk: (Int) -> List<Note>) = List(count) { k -> Beat(id(), Fraction.of(k.toLong(), den.toLong()), WrittenDuration(den), mk(k)) }
    private fun project(measures: List<Measure>, bars: List<List<Beat>>, sections: List<Section> = emptyList(), name: String = "Gitarre", title: String = "T"): Project {
        val track = Track(id(), name, ins, listOf(Staff(id(), bars = measures.mapIndexed { i, m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) bars.getOrElse(i) { emptyList() } else emptyList()) }) })))
        return Project(id = id(), metadata = ProjectMetadata(title = title), measures = measures, tracks = listOf(track), sections = sections, tempo = listOf(TempoChange(ScorePosition(measures[0].id), 120.0)))
    }
    private fun overlap(a: TextGlyph, b: TextGlyph) = a.minX < b.maxX - 0.5f && b.minX < a.maxX - 0.5f

    @Test fun compressedRepeatStartKeepsColumnsInsideTheBar() {
        val m = Measure(id(), repeat = RepeatMark(starts = true))
        for (width in listOf(200f, 300f, 400f)) for (style in listOf(LayoutStyle.READ, LayoutStyle.EDIT, LayoutStyle.READ.copy(showStandard = true, showTab = false))) {
            val l = ScoreLayouter.layout(LayoutRequest(project(listOf(m, Measure(id())), emptyList()), 0, style, width = width, editGrid = Fraction.of(1, 64), editVoice = 2))
            val box = l.measures[0]
            assertTrue("width $width $style", l.columns.filter { it.measureId == box.measureId }.all { it.x < box.x + box.width })
        }
    }

    @Test fun compressedSignaturesDoNotRunIntoTheTabClef() {
        val m = Measure(id(), TimeSignature(12, 8), key = KeySignature(6))
        val p = project(listOf(m), listOf(beats(48, 32) { listOf(note(it % 6, 10 + it % 12)) }))
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT, width = 523f, margin = 0f))
        val tab = l.glyphs.filterIsInstance<TextGlyph>().filter { it.text in setOf("T", "A", "B") && it.bold }.maxOf { it.maxX }
        val sig = l.glyphs.filterIsInstance<TextGlyph>().filter { it.text == "12" || it.text == "8" }
        assertTrue(sig.isNotEmpty() && sig.all { it.minX >= tab - 0.5f })
    }

    @Test fun severalNavigationMarksInOneBarDoNotOverlap() {
        val m0 = Measure(id(), navigation = listOf(NavigationMark(NavigationKind.SEGNO), NavigationMark(NavigationKind.CODA)))
        val m1 = Measure(id(), navigation = listOf(NavigationMark(NavigationKind.DAL_SEGNO), NavigationMark(NavigationKind.FINE)))
        val l = ScoreLayouter.layout(LayoutRequest(project(listOf(m0, m1), listOf(beats(4, 4) { listOf(note(2, 3)) }, beats(4, 4) { listOf(note(2, 5)) })), 0, LayoutStyle.EDIT))
        val marks = l.glyphs.filterIsInstance<TextGlyph>().filter { it.ink == Ink.ACCENT }
        assertEquals(4, marks.size)
        for (i in marks.indices) for (j in i + 1 until marks.size) assertFalse("${marks[i].text} / ${marks[j].text}", overlap(marks[i], marks[j]))
    }

    @Test fun alternateEndingNumberDoesNotSitOnTheSectionLabel() {
        val m0 = Measure(id(), repeat = RepeatMark(starts = true))
        val m1 = Measure(id(), repeat = RepeatMark(endCount = 2, alternateEndings = setOf(1)))
        val l = ScoreLayouter.layout(LayoutRequest(project(listOf(m0, m1), listOf(beats(4, 4) { listOf(note(2, 3)) }, beats(4, 4) { listOf(note(2, 5)) }), sections = listOf(Section(id(), m1.id, "Chorus"))), 0, LayoutStyle.EDIT))
        val pill = l.glyphs.filterIsInstance<RectGlyph>().single { it.ink == Ink.SECTION }
        val ending = l.glyphs.filterIsInstance<TextGlyph>().single { it.text == "1." }
        assertTrue(ending.minX >= pill.x + pill.w)
    }

    @Test fun svgIsWellFormedForControlCharactersAndLoneSurrogates() {
        val p = project(listOf(Measure(id())), listOf(beats(4, 4) { listOf(note(2, 3)) }), name = "Lead\u0001", title = "Song\u000B x\uD800y 🎸 <&\"'>")
        val svg = SvgExport.render(p, listOf(0))
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(svg)))
        assertTrue(svg.contains("🎸"))
    }

    @Test fun svgIsAsWideAsTheLayout() {
        val p = project(listOf(Measure(id())), listOf(beats(64, 64) { listOf(note(it % 6, 10 + it % 12)) }))
        val layout = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.READ.copy(showStandard = true), 760f))
        val width = Regex("width=\"([0-9.]+)\"").find(SvgExport.render(p, listOf(0)))!!.groupValues[1].toFloat()
        assertTrue("svg $width < layout ${layout.width}", width >= layout.width - 0.01f)
    }

    /** Ties used to cost a scan over every note each (quadratic): 4000 tied bars took 5.7 s, untied 0.26 s. */
    @Test(timeout = 4_000) fun tiedScoresLayOutInLinearTime() {
        val count = 4000
        val measures = List(count) { Measure(id()) }
        val firsts = List(count) { id() }
        val bars = List(count) { i ->
            beats(16, 16) { k ->
                val links = if (k == 15 && i + 1 < count) listOf(NoteLink(LinkKind.TIE, firsts[i + 1])) else emptyList()
                listOf(note(2, 5, links, if (k == 0) firsts[i] else id()), note(3, 5))
            }
        }
        val l = ScoreLayouter.layout(LayoutRequest(project(measures, bars), 0, LayoutStyle.EDIT))
        assertEquals(count, l.measures.size)
    }

    @Test fun glyphsBySystemCoverEveryGlyphOnce() {
        val p = project(List(40) { Measure(id()) }, List(40) { beats(8, 8) { listOf(note(it % 6, it)) } })
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.READ.copy(showStandard = true), 400f))
        assertTrue(l.systems.size > 1)
        assertEquals(l.glyphs.size, l.glyphsBySystem.sumOf { it.size })
    }

    @Test fun playheadNeverMovesBackwardsInTheLastBeat() {
        val p = project(List(2) { Measure(id()) }, emptyList())
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT, 200f, editGrid = Fraction.of(1, 16)))
        val m = l.measures[0].measureId
        val last = l.columns.filter { it.measureId == m }.maxBy { it.offset }
        assertEquals(last.x, l.xFor(m, last.offset)!!, 0.01f)
        var prev = l.xFor(m, last.offset)!!
        for (k in 1..20) { val x = l.xFor(m, last.offset.toDouble() + k / 320.0)!!; assertTrue(x >= prev - 1e-3f); prev = x }
    }
}

class LaneLabelRegressionTest {
    private var n = 0
    private fun id() = EntityId("ll${n++}")
    @Test fun topLaneLabelsStayInsideNarrowBars() {
        val ins = InstrumentDefinition()
        val ms = List(3) { Measure(id(), TimeSignature(1, 4)) }
        val track = Track(id(), "G", ins, listOf(Staff(id(), bars = ms.map { m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(ins.pitch(2, 3)), 2, 3)))) else emptyList()) }) })))
        val p = Project(id = id(), measures = ms, tracks = listOf(track), sections = listOf(Section(id(), ms[1].id, "Pre-Chorus")),
            tempo = listOf(TempoChange(ScorePosition(ms[0].id), 120.0), TempoChange(ScorePosition(ms[1].id), 140.0), TempoChange(ScorePosition(ms[2].id), 96.5)))
        val l = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.READ))
        val lane = l.glyphs.filterIsInstance<TextGlyph>().filter { it.y < l.systems[0].top + 15 && it.text.startsWith("♩") }
        for (t in lane) {
            val box = l.measures.first { t.x >= it.x && t.x < it.x + it.width }
            assertTrue("'${t.text}' [${t.minX}..${t.maxX}] leaves bar [${box.x}..${box.x + box.width}]", t.maxX <= box.x + box.width)
        }
    }
}

/** QA cycle 8 (corpus): the clef of an unfretted track was recomputed from all its notes for every bar (32 s for a 1734-bar MIDI file). */
class UnfrettedLayoutScalingRegressionTest {
    private var n = 0
    private fun id() = EntityId("pl${n++}")
    private fun piano(bars: Int): Project {
        val m = List(bars) { Measure(id()) }
        val ins = InstrumentDefinition(kind = InstrumentKind.PIANO, tuning = emptyList(), capoByString = emptyList())
        val track = Track(id(), "Piano", ins, listOf(Staff(id(), setOf(NotationKind.STANDARD), m.map { mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v,
            if (v == 0) List(4) { k -> Beat(id(), Fraction.of(k.toLong(), 4), WrittenDuration(4), listOf(Note(id(), Pitch(72)))) } else emptyList()) }) })))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }
    @Test fun pianoLayoutIsLinearInMeasures() {
        fun time(p: Project): Long { val t0 = System.nanoTime(); ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT.copy(showStandard = true), 760f)); return (System.nanoTime() - t0) / 1_000_000 }
        val small = piano(500); val big = piano(2000)
        time(small); val ts = time(small); val tb = time(big)
        assertTrue("500 bars $ts ms, 2000 bars $tb ms", tb < 8 * maxOf(ts, 50))
    }
}
