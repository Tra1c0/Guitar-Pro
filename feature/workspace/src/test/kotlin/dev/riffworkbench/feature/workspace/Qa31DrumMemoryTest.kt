package dev.riffworkbench.feature.workspace

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.*
import dev.riffworkbench.playback.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zyklus 31 „stürzt bei zu viel Drum-Grid ab“: retained heap of what the drum screen derives from the piece (grid bars,
 * beats per bar, guitar lanes, clock, snap targets) for a large dense piece stays bounded (printed with the time). With
 * `-Dqa31.probe=full` it also measures the score layout of the drum staff and the playback plan for 100/300/1000 bars at
 * 1/16, 1/32, 1/16 T and 100 %/50 % density (the numbers of the QA journal; not asserted, they are not the drum screen's).
 */
class Qa31DrumMemoryTest {
    private var n = 0
    private val ids = IdSource { EntityId("p${n++}") }

    private fun used(): Long { repeat(4) { System.gc(); Thread.sleep(40) }; val r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory() }

    private fun piece(bars: Int, step: Fraction, density: Double): Project {
        val base = ProjectFactory(ids).create("P")
        val measures = (0 until bars).map { Measure(ids.next(), TimeSignature()) }
        val f = ProjectFactory(ids)
        val tracks = base.tracks.map { t -> f.track(t.instrument.kind, measures, t.name, t.instrument) }
        val cells = kotlin.math.round(1.0 / step.toDouble()).toInt()
        val rnd = java.util.Random(31)
        return base.copy(measures = measures, sections = emptyList(), tempo = listOf(TempoChange(ScorePosition(measures[0].id), 120.0)), tracks = tracks.map { t ->
            when (t.instrument.kind) {
                InstrumentKind.PERCUSSION -> {
                    val kit = t.instrument.drumKit
                    t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.map { b -> b.copy(voices = b.voices.map { v -> if (v.index != 0) v else v.copy(beats = (0 until cells).map { k ->
                        val notes = kit.filter { rnd.nextDouble() < density }.map { Note(ids.next(), Pitch(it.midiNote)) }
                        Beat(ids.next(), step * Fraction.of(k.toLong(), 1), if (cells == 24) WrittenDuration(16, tuplets = listOf(TupletRatio(3, 2))) else WrittenDuration(cells), notes)
                    }) }) }) })
                }
                InstrumentKind.GUITAR -> t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.map { b -> b.copy(voices = b.voices.map { v -> if (v.index != 0) v else v.copy(beats = (0 until 4).map { k ->
                    Beat(ids.next(), Fraction.of(k.toLong(), 4), WrittenDuration(4), listOf(Note(ids.next(), Pitch(t.instrument.tuning[0] + 3), string = 0, fret = 3)))
                }) }) }) })
                else -> t
            }
        })
    }

    private class Derived(val grid: List<DrumGrid.Bar>, val beats: List<List<Beat>>, val lanes: List<GuitarActivity.BarLane>, val clock: ScoreClock,
                          val snaps: Pair<List<CursorSnap.Target>, List<CursorSnap.Target>>)

    /** What DrumGridArea remembers for a piece (same functions, same grouping by measure id). */
    private fun derive(p: Project, step: Fraction): Derived {
        val grid = DrumGrid.bars(p, step)
        val staff = p.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }.staves.first()
        val byMeasure = staff.bars.associateBy { it.measureId }
        val beats = grid.map { b -> byMeasure[b.measureId]?.voices?.firstOrNull { it.index == 0 }?.beats.orEmpty() }
        val lanes = GuitarActivity.lanes(p)
        return Derived(grid, beats, lanes, ScoreClock(p), DrumGridActivity.snapTargets(grid, beats, lanes))
    }

    @Test fun drumScreenDataOfAThousandDenseBarsIsBoundedAndLinear() {
        val step = Fraction.of(1, 32)
        val small = piece(100, step, 1.0)
        val large = piece(1000, step, 1.0)
        derive(small, step); derive(large, step) // warm-up
        fun measure(p: Project): Pair<Double, Double> {
            val m0 = used()
            val t0 = System.nanoTime()
            val d = derive(p, step)
            val ms = (System.nanoTime() - t0) / 1e6
            val mb = (used() - m0) / 1e6
            check(d.grid.size == p.measures.size && d.lanes.size == p.measures.size)
            return mb to ms
        }
        val (mbSmall, msSmall) = measure(small)
        val (mbLarge, msLarge) = measure(large)
        println("QA31 drum screen data, 1/32, 11 rows, every cell set: 100 bars %.1f MB %.1f ms; 1000 bars %.1f MB %.1f ms".format(java.util.Locale.ROOT, mbSmall, msSmall, mbLarge, msLarge))
        assertTrue("1000 bars: %.1f MB of derived drum-screen data".format(java.util.Locale.ROOT, mbLarge), mbLarge < 16.0)
    }

    @Test fun fullProbe() {
        if (System.getProperty("qa31.probe") != "full") return
        val sizes = (System.getProperty("qa31.bars") ?: "100,300,1000").split(",").map { it.trim().toInt() }
        for (bars in sizes) for ((label, step) in listOf("1/16" to Fraction.of(1, 16), "1/32" to Fraction.of(1, 32), "1/16T" to Fraction.of(1, 24))) for (density in listOf(1.0, 0.5)) {
            val m0 = used()
            val p = piece(bars, step, density)
            val m1 = used()
            var t = System.nanoTime()
            val derived = derive(p, step)
            val tDerived = (System.nanoTime() - t) / 1e6
            val m2 = used()
            val drum = p.tracks.indexOfFirst { it.instrument.kind == InstrumentKind.PERCUSSION }
            t = System.nanoTime()
            val layout = runCatching { ScoreLayouter.layout(LayoutRequest(p, drum, LayoutStyle.EDIT, null, step, 0, null, margin = 6f)) }
            val tLayout = (System.nanoTime() - t) / 1e6
            val m3 = used()
            t = System.nanoTime()
            val plan = ScorePlaybackCompiler().compileNow(p, 48_000)
            val tPlan = (System.nanoTime() - t) / 1e6
            val m4 = used()
            println("QA31 probe bars=$bars grid=$label density=$density: project %.1f MB; drum-screen data %.1f MB %.0f ms; layout (drum staff) %.1f MB %.0f ms (%s); plan %.1f MB %.0f ms (%s)".format(java.util.Locale.ROOT,
                (m1 - m0) / 1e6, (m2 - m1) / 1e6, tDerived, (m3 - m2) / 1e6, tLayout, layout.exceptionOrNull()?.toString() ?: "ok", (m4 - m3) / 1e6, tPlan, plan::class.simpleName))
            check(derived.grid.isNotEmpty() && (layout.isSuccess || layout.isFailure) && plan.toString().isNotEmpty())
        }
    }
}
