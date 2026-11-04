package dev.riffworkbench.notation

import dev.riffworkbench.model.*
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LayoutTest {
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = false; encodeDefaults = true }
    private fun fixture(name: String): Project = json.decodeFromString(Project.serializer(), File("../../fixtures/$name").readText())
    private val out = File("build/reports/notation")
    private var n = 0
    private fun id() = EntityId("l${n++}")

    private fun richProject(): Project {
        val ins = InstrumentDefinition()
        val m = listOf(Measure(id(), repeat = RepeatMark(starts = true)), Measure(id(), TimeSignature(7, 8), repeat = RepeatMark(endCount = 3, alternateEndings = setOf(1))), Measure(id(), feel = RhythmicFeel.SWING_EIGHTH, navigation = listOf(NavigationMark(NavigationKind.DA_CAPO))))
        fun note(s: Int, f: Int, vararg a: Articulation, links: List<NoteLink> = emptyList(), nid: EntityId = id()) = Note(nid, Pitch(ins.pitch(s, f)), s, f, articulations = a.toList(), links = links)
        fun beat(o: Fraction, d: WrittenDuration, vararg notes: Note, strum: Strum? = null, fermata: Fermata? = null) = Beat(id(), o, d, notes.toList(), strum = strum, fermata = fermata)
        val tied = id()
        val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 3), 200), PitchCurvePoint(Fraction.of(2, 3), 200), PitchCurvePoint(Fraction.ONE, 0)))
        val bar0 = List(8) { k -> beat(Fraction.of(k.toLong(), 16), WrittenDuration(16), note(0, 0, Articulation.Mark(NoteMark.PALM_MUTE))) } + listOf(
            beat(Fraction.of(1, 2), WrittenDuration(8), note(0, 0, Articulation.Mark(NoteMark.DEAD))),
            beat(Fraction.of(5, 8), WrittenDuration(8), note(2, 5, Articulation.Slide(SlideKind.SHIFT))),
            beat(Fraction.of(3, 4), WrittenDuration(8), note(2, 7, Articulation.Harmonic(HarmonicKind.PINCH))),
            beat(Fraction.of(7, 8), WrittenDuration(8), note(4, 7, bend, links = listOf(NoteLink(LinkKind.TIE, tied))), note(5, 5)))
        val bar1 = listOf(
            beat(Fraction.ZERO, WrittenDuration(8), note(4, 7, nid = tied)),
            beat(Fraction.of(1, 8), WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2))), note(3, 5, Articulation.Mark(NoteMark.HAMMER_ON))),
            beat(Fraction.of(1, 8) + Fraction.of(1, 12), WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2))), note(3, 7)),
            beat(Fraction.of(1, 8) + Fraction.of(2, 12), WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2))), note(3, 5, Articulation.Vibrato())),
            beat(Fraction.of(3, 8), WrittenDuration(4, 1), note(0, 12, Articulation.Harmonic(HarmonicKind.NATURAL)), note(1, 12, Articulation.Harmonic(HarmonicKind.NATURAL)), fermata = Fermata()),
            beat(Fraction.of(6, 8), WrittenDuration(8), note(0, 3), note(1, 5), note(2, 5), strum = Strum(StrumKind.STRUM, StrokeDirection.DOWN)))
        val bar2 = listOf(beat(Fraction.ZERO, WrittenDuration(4), note(2, 5)), Beat(id(), Fraction.of(1, 4), WrittenDuration(4), explicitRest = true), beat(Fraction.of(1, 2), WrittenDuration(2), note(3, 2, Articulation.Mark(NoteMark.LET_RING)), note(4, 2, Articulation.Mark(NoteMark.LET_RING))))
        val bars = listOf(bar0, bar1, bar2)
        val track = Track(id(), "Gitarre", ins, listOf(Staff(id(), bars = m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0) bars[i] else emptyList()) }) })))
        return Project(id = id(), measures = m, tracks = listOf(track), sections = listOf(Section(id(), m[0].id, "Riff A")), tempo = listOf(TempoChange(ScorePosition(m[0].id), 150.0), TempoChange(ScorePosition(m[2].id), 120.0)))
    }

    @Test fun rendersFixturesInEditReadAndStandardStyles() {
        val metal = fixture("drop-a-metal.json")
        val rich = richProject()
        for ((name, p) in listOf("metal" to metal, "rich" to rich)) {
            val edit = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT, editGrid = Fraction.of(1, 16)))
            Java2dRenderer.render(edit, File(out, "$name-edit.png"))
            val read = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.READ, width = 800f))
            Java2dRenderer.render(read, File(out, "$name-read.png"))
            val both = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.READ.copy(showStandard = true), width = 800f))
            Java2dRenderer.render(both, File(out, "$name-standard.png"))
            assertTrue(edit.glyphs.isNotEmpty() && edit.measures.size == p.measures.size)
        }
        val drums = metal.tracks.indexOfFirst { it.instrument.kind == InstrumentKind.PERCUSSION }
        if (drums >= 0) Java2dRenderer.render(ScoreLayouter.layout(LayoutRequest(metal, drums, LayoutStyle.READ, width = 800f)), File(out, "metal-drums.png"))
    }

    @Test fun hitTestingMapsTapsToColumnsAndStrings() {
        val p = richProject()
        val layout = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT, editGrid = Fraction.of(1, 16)))
        val sys = layout.systems.single()
        val col = layout.columns.first { it.measureIndex == 0 && it.offset == Fraction.of(1, 2) }
        val hit = layout.columnAt(col.x + 2f, sys.stringY[0])!!
        assertEquals(Fraction.of(1, 2), hit.offset)
        assertEquals(0, layout.stringAt(sys.stringY[0] + 3f))
        assertEquals(6, layout.stringAt(sys.stringY[6] - 3f))
        // Cursor interpolation inside a measure and empty grid slots for note entry.
        val empty = ScoreLayouter.layout(LayoutRequest(p.copy(tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { s -> s.copy(bars = s.bars.map { b -> b.copy(voices = b.voices.map { it.copy(beats = emptyList()) }) }) }) }), 0, LayoutStyle.EDIT, editGrid = Fraction.of(1, 8)))
        assertEquals(8, empty.columns.count { it.measureIndex == 0 })
        val x1 = empty.xFor(p.measures[0].id, Fraction.of(1, 8))!!; val x2 = empty.xFor(p.measures[0].id, Fraction.of(3, 16))!!
        assertTrue(x2 > x1)
    }
}

class LayoutScaleTest {
    private var n = 0
    private fun id() = EntityId("p${n++}")
    @Test fun twoHundredBarsLayOutQuicklyAndExportAsSvg() {
        val ins = InstrumentDefinition()
        val measures = List(200) { Measure(id()) }
        val track = Track(id(), "Riff", ins, listOf(Staff(id(), bars = measures.map { m ->
            Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) List(16) { k -> Beat(id(), Fraction.of(k.toLong(), 16), WrittenDuration(16), listOf(Note(id(), Pitch(ins.pitch(k % 7, k % 5)), k % 7, k % 5, articulations = if (k % 4 == 0) listOf(Articulation.Mark(NoteMark.PALM_MUTE)) else emptyList()))) } else emptyList()) })
        })))
        val p = Project(id = id(), measures = measures, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(measures[0].id), 160.0)))
        repeat(3) { ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT, editGrid = Fraction.of(1, 16))) }
        val start = System.nanoTime()
        val layout = ScoreLayouter.layout(LayoutRequest(p, 0, LayoutStyle.EDIT, editGrid = Fraction.of(1, 16)))
        val ms = (System.nanoTime() - start) / 1e6
        println("200 bars x 16 notes: ${layout.glyphs.size} glyphs in ${"%.1f".format(ms)} ms")
        assertEquals(200, layout.measures.size)
        assertTrue("layout took $ms ms", ms < 1500)
        val svg = SvgExport.render(p.copy(measures = measures.take(4), tracks = listOf(track.copy(staves = track.staves.map { s -> s.copy(bars = s.bars.take(4)) }))), listOf(0))
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
        assertEquals("svg", doc.documentElement.tagName)
        assertTrue(doc.getElementsByTagName("text").length > 50)
    }
}
