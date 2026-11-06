package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-Zyklus 21 / Export-Import: Zeitlage des Offline-Exports (WAV-Mix und Stems, `OfflineMixdown`) nach dem
 * Latenzausgleich aus Zyklus 20. Live hört man jede Quelle [ChannelRack.latencyFrames] (Cabinet-Faltung, 64 Frames) nach
 * dem Planframe; ein DAW-üblicher Export gleicht diese Verzögerung aus: Takt 1, Zählzeit 1 liegt im WAV auf Frame 0, die
 * Datei enthält Plan + Ausklang vollständig, und alle Stems liegen untereinander und zum Mix sample-genau.
 *
 * Referenz ("roh"): dieselbe Engine wie `OfflineMixdown` (1024er-Blöcke ab Planframe 0, gleicher Rack/Mix), nur ohne
 * Ausgleich und um die Latenz länger gerendert. Der Export muss bitgleich `roh[i + Latenz]` sein (32-Bit-Float-WAV).
 * Projekte: Standardband 120 BPM (Clean-Gitarre mit Box + Hall, gesampelt wenn die Bank vorliegt; Bass mit Box; Drums
 * ohne Box; Pad mit langem Hall), je Zählzeit ein Schlag. Jede Messung druckt ihre Zahlen ("Q21E ...").
 */
class Qa21ExportAlignmentTest {
    companion object {
        const val SR = 48_000
        const val BLOCK = 1024
        /** 120 BPM: eine Viertel = 24 000 Frames. */
        const val QUARTER = 24_000
        val bank: SampleBank? get() = Qa18GainStructureTest.bank
        fun db(x: Double) = if (x <= 1e-12) -240.0 else 20 * log10(x)
        fun f1(x: Double) = String.format(java.util.Locale.ROOT, "%.1f", x)
    }

    private var n = 0
    private fun id() = EntityId("q21-${n++}")
    private val guitar = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), midiProgram = 27)
    private val bassDef = InstrumentDefinition(InstrumentKind.BASS, listOf(28, 33, 38, 43), List(4) { 0 }, midiProgram = 34)
    private val drumsDef = InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0)
    private val padDef = InstrumentDefinition(InstrumentKind.OTHER, emptyList(), emptyList(), midiProgram = 48)

    private fun quarters(bars: Int, note: (Int) -> List<Note>): List<List<Beat>> = List(bars) { b -> List(4) { k -> Beat(id(), Fraction.of(k.toLong(), 4), WrittenDuration(4), note(b * 4 + k)) } }

    /** Standardband (Gitarre, Bass, Drums, optional Pad), 2 Takte 4/4 bei 120 BPM; jede Spur spielt jede Zählzeit. */
    private fun band(pad: Boolean = false, master: List<EffectUnit> = emptyList()): Project {
        val m = List(2) { Measure(id()) }
        val tracks = mutableListOf(
            Track(id(), "Gitarre", guitar, emptyList()) to quarters(2) { listOf(Note(id(), Pitch(guitar.pitch(1, 2)), 1, 2, velocity = 96)) },
            Track(id(), "Bass", bassDef, emptyList()) to quarters(2) { listOf(Note(id(), Pitch(bassDef.pitch(1, 0)), 1, 0, velocity = 104)) },
            Track(id(), "Drums", drumsDef, emptyList()) to quarters(2) { k -> listOf(Note(id(), Pitch(if (k % 2 == 0) 36 else 38), velocity = 100), Note(id(), Pitch(42), velocity = 90)) })
        if (pad) tracks += Track(id(), "Pad", padDef, emptyList()) to listOf(listOf(Beat(id(), Fraction.ZERO, WrittenDuration(1), listOf(Note(id(), Pitch(60), velocity = 100), Note(id(), Pitch(64), velocity = 100)))), emptyList())
        val built = tracks.map { (t, bars) ->
            val notation = when (t.instrument.kind) { InstrumentKind.PERCUSSION -> setOf(NotationKind.PERCUSSION); InstrumentKind.OTHER -> setOf(NotationKind.STANDARD); else -> setOf(NotationKind.TAB) }
            t.copy(staves = listOf(Staff(id(), notation, m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0) bars.getOrElse(i) { emptyList() } else emptyList()) }) })))
        }
        return Project(id = id(), metadata = ProjectMetadata("Q21"), measures = m, tracks = built, tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)),
            mix = MixGraph(master = ChannelMix(effects = master)))
    }

    private class Stereo(val l: FloatArray, val r: FloatArray)

    private fun export(p: Project, solo: Int?, tail: Double, masterFx: Boolean = true): Stereo {
        val bytes = ByteArrayOutputStream()
        val r = OfflineMixdown(ScorePlaybackCompiler(), bank).render(p, RenderOptions(sampleRate = SR, bitDepth = 32, includeMasterFx = masterFx, tailSeconds = tail), { b, o, l -> bytes.write(b, o, l) }, solo)
        assertTrue("export $solo: $r", r is Outcome.Success)
        val d = Wav.decode(bytes.toByteArray())
        return Stereo(d.left, d.right)
    }

    /** The same engine as OfflineMixdown (1024-frame blocks from plan frame 0), without compensation, [frames] long. */
    private fun raw(p: Project, solo: Int?, frames: Long, masterFx: Boolean = true): Stereo {
        val plan = (ScorePlaybackCompiler().compileNow(p, SR) as Outcome.Success).value
        val e = SynthEngine(SR, BLOCK, bank)
        e.installRack(ChannelRack.build(p, SR, BLOCK, includeMasterFx = masterFx && solo == null)); e.setMix(MixState.from(p, 0f, solo)); e.install(EngineProgram.prepare(plan)); e.play(0)
        val l = FloatArray(frames.toInt()); val r = FloatArray(frames.toInt()); val buf = FloatArray(BLOCK * 2); var done = 0
        while (done < frames) { val k = minOf(BLOCK.toLong(), frames - done).toInt(); e.render(buf, k); for (i in 0 until k) { l[done + i] = buf[2 * i]; r[done + i] = buf[2 * i + 1] }; done += k }
        return Stereo(l, r)
    }

    private fun planFrames(p: Project) = (ScorePlaybackCompiler().compileNow(p, SR) as Outcome.Success).value.totalFrames
    /** Rack plus master-chain latency (what the export must compensate). */
    private fun latency(p: Project, solo: Int?, masterFx: Boolean = true): Int {
        val rack = ChannelRack.build(p, SR, BLOCK, includeMasterFx = masterFx && solo == null)
        return rack.latencyFrames + rack.master.sumOf { it.latencyFrames }
    }
    /**
     * Onset of the hit expected at [expected]: first frame from [expected] - 100 on that reaches -40 dB re the hit's peak
     * (within 50 ms) and 4x the largest sample of the 300 frames before that search window (the previous hit's tail).
     */
    private fun onset(x: Stereo, expected: Int): Int? {
        val from = (expected - 100).coerceAtLeast(0); val to = minOf(x.l.size, expected + SR / 20)
        var peak = 0f; for (i in from until to) peak = maxOf(peak, abs(x.l[i]), abs(x.r[i]))
        var before = 0f; for (i in (from - 300).coerceAtLeast(0) until from) before = maxOf(before, abs(x.l[i]), abs(x.r[i]))
        if (peak < 1e-4f) return null
        val th = maxOf(peak * 0.01f, before * 4f)
        return (from until to).firstOrNull { abs(x.l[it]) >= th || abs(x.r[it]) >= th }
    }

    /**
     * E1: Mix und jeder Stem liegen ab Frame 0 auf dem Plan (Latenz ausgeglichen): export[i] == roh[i + Latenz] über die
     * ganze Datei, die Datei ist Plan + Ausklang lang, und jeder Drum-Schlag beginnt 0..4 Frames nach seiner Zählzeit
     * (Anschlag; vor dem Ausgleich 64..67). Rot vor Zyklus 21: Abweichung ab Frame 0, Drum-Schläge bei 64..67.
     */
    @Test fun exportCompensatesTheRackLatencyFromFrameZero() {
        val p = band()
        assertEquals("Rack-Latenz der Standardband (Cabinet-Partition)", 64, latency(p, null))
        val failures = alignmentFailures(p, "")
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    private fun alignmentFailures(p: Project, label: String): List<String> {
        val tail = 1.0
        val total = planFrames(p) + (tail * SR).toLong()
        val failures = mutableListOf<String>()
        for (solo in listOf<Int?>(null) + p.tracks.indices) {
            val name = label + (solo?.let { p.tracks[it].name } ?: "Mix")
            val lat = latency(p, solo)
            val out = export(p, solo, tail)
            val ref = raw(p, solo, total + lat)
            if (out.l.size.toLong() != total) failures += "$name: ${out.l.size} Frames statt $total"
            var firstBad = -1
            for (i in 0 until minOf(out.l.size, (ref.l.size - lat))) if (out.l[i] != ref.l[i + lat] || out.r[i] != ref.r[i + lat]) { firstBad = i; break }
            // What the export holds at its end: the last 10 ms against the reference's (compensated) end.
            val endRef = (total - SR / 100 until total).maxOf { abs(ref.l[(it + lat).toInt()]) }
            val endOut = (out.l.size - SR / 100 until out.l.size).maxOf { abs(out.l[it]) }
            val starts = (0 until 8).map { k -> onset(out, k * QUARTER)?.minus(k * QUARTER) }
            println("Q21E ${name}: Latenz $lat Frames, Länge ${out.l.size} (Soll $total), erste Abweichung von roh[i+Latenz] bei ${if (firstBad < 0) "-" else firstBad}, Onsets je Zählzeit relativ zum Planframe $starts, letzte 10 ms ${f1(db(endOut.toDouble()))} dBFS (Soll ${f1(db(endRef.toDouble()))})")
            if (firstBad >= 0) failures += "$name: Export weicht ab Frame $firstBad von roh[i + $lat] ab"
            if (solo != null && p.tracks[solo].instrument.kind == InstrumentKind.PERCUSSION) starts.forEachIndexed { k, s -> if (s == null || s !in 0..4) failures += "$name: Schlag $k beginnt bei $s statt 0..4 Frames nach der Zählzeit" }
        }
        return failures
    }

    /** E2: Die Stems summieren sich zum Mix ohne Master-Kette (dokumentiert: Stems ohne Master-Limiter, gleicher Startframe). */
    @Test fun stemsSumToTheMixWithoutTheMasterChain() {
        val p = band(pad = true)
        val tail = 0.5
        val mix = export(p, null, tail, masterFx = false)
        val stems = p.tracks.indices.map { export(p, it, tail) }
        var worst = 0.0; var at = -1; var peak = 0.0; var first = -1
        for (i in mix.l.indices) {
            var sl = 0.0; var sr = 0.0
            for (s in stems) { sl += s.l[i]; sr += s.r[i] }
            val d = maxOf(abs(mix.l[i] - sl), abs(mix.r[i] - sr))
            peak = maxOf(peak, abs(mix.l[i].toDouble()), abs(mix.r[i].toDouble()))
            if (d > worst) { worst = d; at = i }
            if (first < 0 && d >= 1e-5) first = i
        }
        println("Q21E Summe der Stems gegen Mix ohne Master-Kette: größte Abweichung ${"%.2e".format(worst)} (${f1(db(worst))} dBFS) bei Frame $at, erste ≥ 1e-5 bei Frame $first, Mix-Spitze ${f1(db(peak))} dBFS")
        assertTrue("Σ Stems ≠ Mix: ${"%.2e".format(worst)} bei Frame $at", worst < 1e-5)
    }

    /**
     * E3: Eine Master-Kette mit Latenz (Box auf dem Master; das Modell erlaubt sie, z. B. aus einer nativen Datei) verschiebt
     * den Mix nicht gegen die Stems (die keine Master-Kette haben): Mix bei roh[i + 128], Stems bei roh[i + 64].
     */
    @Test fun aLatentMasterChainKeepsTheMixOnTheStems() {
        val p = band(master = listOf(EffectUnit(id(), EffectKind.CABINET_IR, parameters = mapOf("model" to 1.0))))
        assertEquals(128, latency(p, null)); assertEquals(64, latency(p, 2))
        val failures = alignmentFailures(p, "Master-Box ")
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    /**
     * E4 (Messung, Ausklang): Pegel am Dateiende bei 2 s Ausklang (Produkt-Einstellung) mit langem Hall (Pad) — wie
     * viel vom Hall die Datei abschneidet. Gibt nur Zahlen aus; die Länge ist Plan + Ausklang (dokumentiert).
     */
    @Test fun probeTailAtTheEndOfTheFile() {
        val p = band(pad = true)
        val tail = 2.0
        val mix = export(p, null, tail)
        val total = mix.l.size
        fun win(from: Int, count: Int): Double { var s = 0.0; for (i in from until from + count) s += mix.l[i].toDouble() * mix.l[i]; return 10 * log10(s / count + 1e-30) }
        val levels = listOf(0.5, 1.0, 1.5, 1.9).map { t -> val f = planFrames(p).toInt() + (t * SR).toInt(); "${t}s ${f1(win(f, SR / 20))}" }
        val lastPeak = (total - SR / 100 until total).maxOf { abs(mix.l[it]) }
        println("Q21E Ausklang (Pad, Hall 0,8): RMS nach Planende $levels dBFS; Spitze der letzten 10 ms ${f1(db(lastPeak.toDouble()))} dBFS")
        assertEquals(planFrames(p) + 2L * SR, total.toLong())
    }
}
