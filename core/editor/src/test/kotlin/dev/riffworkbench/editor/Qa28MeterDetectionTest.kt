package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 28, Zusatzwunsch „Taktart erkennen“: riffs in other meters tapped to the 4/4 click (Qa28HumanTaps.meterCases),
 * σ ∈ {8, 15, 20} ms × [SEEDS] seeds, 6 bars of 4/4. The detector runs on the smoothed result. Metrics: meter hit (4/4
 * material: no change proposed), phase hit, false alarms on 4/4 material. Table printed for the QA journal.
 */
class Qa28MeterDetectionTest {
    companion object { const val SEEDS = 20; val SIGMAS = listOf(8.0, 15.0, 20.0) }

    class Row(val case: Qa28HumanTaps.MeterCase, val sigma: Double, var runs: Int = 0, var hits: Int = 0, var phase: Int = 0, var changed: Int = 0)

    private fun evaluate(): List<Row> {
        val rows = ArrayList<Row>()
        for (c in Qa28HumanTaps.meterCases) for (sigma in SIGMAS) {
            val row = Row(c, sigma)
            for (seed in 0 until SEEDS) {
                val rng = Random(seed * 104729L + c.name.hashCode() * 17L + sigma.toLong())
                val piece = Qa28HumanTaps.meterPiece(c, rng)
                val taps = Qa28HumanTaps.human(piece, sigma, rng)
                val r = RhythmSmoother.quantize(taps.strokes, taps.spans, Qa28HumanTaps.SR, taps.compound, current = TimeSignature(), meterFrom = 0)
                val g = r.analysis!!.meter!!
                row.runs++
                val proposed = if (g.changes) g.signature else null
                if (proposed != null) row.changed++
                if (proposed == c.meter) { row.hits++; if (c.meter == null || g.phase == Fraction.of(c.phase16.toLong(), 16)) row.phase++ }
            }
            rows += row
        }
        return rows
    }

    @Test fun meterDetectionTable() {
        val rows = evaluate()
        fun pct(a: Int, b: Int) = "%.0f".format(100.0 * a / maxOf(1, b))
        println("Q28T | Fall | σ ms | Taktart erkannt % | Phase richtig % | Umdeutung vorgeschlagen % |")
        for (r in rows) println("Q28T | ${r.case.name} | ${r.sigma.toInt()} | ${pct(r.hits, r.runs)} | ${pct(r.phase, r.runs)} | ${pct(r.changed, r.runs)} |")
        val fourFour = rows.filter { it.case.meter == null }
        // Plus all 4/4 classes of the rhythm evaluation (8 bars, figures in A A B A … order, every σ).
        var runs = 0; var changed = 0
        for (k in Qa28HumanTaps.kinds.filter { k -> k.figures.all { f -> f.all { it.sig == TimeSignature() } } }) for (sigma in Qa28RhythmEvaluationTest.SIGMAS) for (seed in 0 until SEEDS) {
            val rng = Random(seed * 7919L + k.name.hashCode() * 31L + sigma.toLong())
            val piece = Qa28HumanTaps.piece(k, rng)
            val taps = Qa28HumanTaps.human(piece, sigma, rng)
            val g = RhythmSmoother.quantize(taps.strokes, taps.spans, Qa28HumanTaps.SR, taps.compound, current = TimeSignature(), meterFrom = 0).analysis!!.meter!!
            runs++; if (g.changes) changed++
        }
        val falseAlarms = (fourFour.sumOf { it.changed } + changed).toDouble() / (fourFour.sumOf { it.runs } + runs)
        println("Q28T 4/4-Klassen der Rhythmus-Evaluation: $changed von $runs umgedeutet")
        println("Q28T Fehlalarm 4/4 gesamt: ${"%.1f".format(100 * falseAlarms)} %")
        val odd = rows.filter { it.case.meter != null && !it.case.name.contains("Auftakt") }
        val hit = odd.sumOf { it.hits }.toDouble() / odd.sumOf { it.runs }
        val phase = odd.sumOf { it.phase }.toDouble() / odd.sumOf { it.runs }
        println("Q28T Treffer andere Taktarten gesamt: ${"%.1f".format(100 * hit)} %, Phase ${"%.1f".format(100 * phase)} %")
        // Acceptance (Zusatzwunsch): 4/4 material re-interpreted in at most 2 % of the runs.
        assertTrue("Fehlalarmrate 4/4 ${100 * falseAlarms} %", falseAlarms <= 0.02)
        assertTrue("Treffer andere Taktarten ${100 * hit} %", hit >= 0.8)
        for (r in rows.filter { it.case.meter != null && it.sigma <= 8.0 && !it.case.name.contains("Auftakt") }) assertTrue("${r.case.name} σ8: ${r.hits}/${r.runs}", r.hits * 10 >= r.runs * 7)
    }
}
