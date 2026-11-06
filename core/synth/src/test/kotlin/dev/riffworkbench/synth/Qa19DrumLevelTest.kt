package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.Qa18GainStructureTest.Companion.db
import dev.riffworkbench.synth.Qa18GainStructureTest.Companion.f1
import dev.riffworkbench.synth.Qa18GainStructureTest.Companion.limiterGain
import dev.riffworkbench.synth.Qa18GainStructureTest.Companion.rms
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * QA-Zyklus 19, Drums gegen die Pegelstruktur aus Zyklus 18 (Nutzerbericht: "Drums übersteuern, der erste Hit fehlt
 * manchmal"). Gemessen am Master vor dem Limiter (wie ein Stem-Export: Werks-Mix 0,8 → Kanalgain 1, Master × 0,5), bei
 * 48 kHz in 1024er-Blöcken wie OfflineMixdown. Jede Messung druckt ihre Zahlen ("D19 ...").
 *
 * Budgets (Auftrag Zyklus 19): Spitze je Spur ≤ −3 dBFS, Limiter in der Standardband ≤ 5 % der Zeit aktiv (> 0,5 dB),
 * keine geclippten Samples; erster Treffer auf Takt 1 Zählzeit 1 vollständig und pegelgleich zu späteren Treffern.
 */
open class Qa19DrumLevelTest {
    /** Kit under test: null = the synthetic kit (fallback); Qa24SampledDrumLevelTest runs every check with the sampled kit. */
    protected open val drumKit: DrumKit? get() = null
    private var n = 0
    private fun id() = EntityId("d19-${n++}")
    private fun f(a: Long, b: Long) = Fraction.of(a, b)
    private val guitar = InstrumentDefinition()
    private val bassDef = InstrumentDefinition(InstrumentKind.BASS, listOf(28, 33, 38, 43), List(4) { 0 }, midiProgram = 33)
    /** Standard-Schlagzeugspur wie ProjectFactory (Werks-Kit), dazu die Toms, die ein Nutzer im Kit ergänzt. */
    private val drumsDef = InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0)
    private fun d(midi: Int, v: Int = 96) = Note(id(), Pitch(midi), velocity = v)
    private fun g(s: Int, fr: Int, v: Int = 96, vararg a: Articulation) = Note(id(), Pitch(guitar.pitch(s, fr)), s, fr, velocity = v, articulations = a.toList())
    private fun b(s: Int, fr: Int, vararg a: Articulation) = Note(id(), Pitch(bassDef.pitch(s, fr)), s, fr, velocity = 104, articulations = a.toList())
    private fun beat(o: Fraction, den: Int, vararg notes: Note) = Beat(id(), o, WrittenDuration(den), notes.toList())

    companion object {
        const val SR = 48_000
        const val BLOCK = 1024
        /** 150 BPM, 4/4: ein Takt = 1,6 s = 76 800 Frames, eine Viertel = 19 200. */
        const val BAR = 76_800
        const val QUARTER = 19_200
        val bank: SampleBank? get() = Qa18GainStructureTest.bank
        val elements = listOf(36 to "Kick", 38 to "Snare", 42 to "HH zu", 46 to "HH offen", 44 to "HH Pedal", 41 to "Tom 41", 43 to "Tom 43", 45 to "Tom 45", 47 to "Tom 47", 48 to "Tom 48", 50 to "Tom 50", 49 to "Crash", 57 to "Crash 2", 51 to "Ride", 52 to "China", 55 to "Splash")
        fun peakOf(l: FloatArray, r: FloatArray, from: Int = 0, to: Int = l.size): Double { var p = 0.0; for (i in from until minOf(to, l.size)) p = maxOf(p, abs(l[i].toDouble()), abs(r[i].toDouble())); return p }
        fun rmsDb(x: FloatArray, from: Int, count: Int): Double { var s = 0.0; for (i in from until minOf(from + count, x.size)) s += x[i].toDouble() * x[i]; return 10 * log10(s / count + 1e-30) }
    }

    private fun project(tracks: List<Pair<Track, List<List<Beat>>>>, measures: List<Measure>): Project {
        val built = tracks.map { (t, bars) -> t.copy(staves = listOf(Staff(id(), t.staves.first().notation, measures.mapIndexed { i, m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) bars.getOrElse(i) { emptyList() } else emptyList()) }) }))) }
        return Project(id = id(), metadata = ProjectMetadata("D19"), measures = measures, tracks = built, tempo = listOf(TempoChange(ScorePosition(measures[0].id), 150.0)))
    }
    private fun drumTrack(mix: ChannelMix = ChannelMix()) = Track(id(), "Drums", drumsDef, listOf(Staff(id(), setOf(NotationKind.PERCUSSION), emptyList())), mix = mix)
    private fun drumsOnly(bars: List<List<Beat>>) = project(listOf(drumTrack() to bars), List(bars.size) { Measure(id()) })

    /** Engine wie OfflineMixdown (1024er-Blöcke, MixState.from, Rack mit oder ohne Master-Limiter). */
    private fun render(p: Project, seconds: Double, includeMasterFx: Boolean = false, bk: SampleBank? = null, solo: Int? = null): Pair<FloatArray, FloatArray> {
        val plan = (ScorePlaybackCompiler().compileNow(p, SR) as Outcome.Success).value
        val engine = SynthEngine(SR, BLOCK, bk, drumKit)
        engine.installRack(ChannelRack.build(p, SR, BLOCK, includeMasterFx = includeMasterFx && solo == null)); engine.setMix(MixState.from(p, 0f, solo)); engine.install(EngineProgram.prepare(plan)); engine.play(0)
        val frames = (seconds * SR).toInt(); val l = FloatArray(frames); val r = FloatArray(frames); val buf = FloatArray(BLOCK * 2)
        var done = 0
        while (done < frames) { val k = minOf(BLOCK, frames - done); engine.render(buf, k); for (i in 0 until k) { l[done + i] = buf[2 * i]; r[done + i] = buf[2 * i + 1] }; done += k }
        return l to r
    }
    /** Die Summe der Drum-Stimmen vor jedem Effekt (Kanaleingang = Kompressoreingang), Pan-Gesetz und Master × 0,5 herausgerechnet. */
    private fun dry(p: Project, seconds: Double): FloatArray {
        val ch = Channel(ChannelKind.DRUMS, TonePresets.stringTone(TonePresets.find("rw.drums.rock")!!), KeysMode.PIANO, emptyArray(), BLOCK, voiceSignature = "dry")
        val plan = (ScorePlaybackCompiler().compileNow(p, SR) as Outcome.Success).value
        val engine = SynthEngine(SR, BLOCK, null, drumKit)
        engine.installRack(ChannelRack(arrayOf(ch), emptyArray(), "dry")); engine.setMix(MixState(floatArrayOf(1f), floatArrayOf(0f), 2f, 0f)); engine.install(EngineProgram.prepare(plan)); engine.play(0)
        val frames = (seconds * SR).toInt(); val out = FloatArray(frames); val buf = FloatArray(BLOCK * 2); var done = 0
        while (done < frames) { val k = minOf(BLOCK, frames - done); engine.render(buf, k); for (i in 0 until k) out[done + i] = maxOf(abs(buf[2 * i]), abs(buf[2 * i + 1])); done += k }
        return out
    }

    /** Standard-Groove, 2 Takte 4/4 bei 150 BPM: Crash + Kick auf 1, Snare 2/4, HH-Achtel, Kick auf 3 und 3+, Tom-Fill (16tel) auf Zählzeit 4 von Takt 2. */
    private fun groove(v: Int = 96, accent: Int = 127): List<List<Beat>> {
        fun bar(fill: Boolean) = (0 until 8).map { k ->
            val o = f(k.toLong(), 8)
            val notes = mutableListOf<Note>()
            if (!(fill && k >= 6)) notes += d(42, if (k % 2 == 0) v else v - 16)
            if (k == 0 || k == 4 || k == 5) notes += d(36, if (k == 0) accent else v)
            if (k == 2 || k == 6) notes += d(38, accent)
            if (k == 0 && !fill) notes += d(49, accent)
            o to notes
        }.let { list ->
            val beats = list.filter { (o, _) -> !(fill && o >= f(3, 4)) }.map { (o, notes) -> Beat(id(), o, WrittenDuration(8), notes) }.toMutableList()
            if (fill) listOf(50, 48, 47, 45, 43, 41, 41, 36).forEachIndexed { k, m ->
                val notes = mutableListOf(d(m, accent)); if (k == 0) notes += d(38, accent)
                beats += Beat(id(), f(3, 4) + f(k.toLong(), 32), WrittenDuration(32), notes)
            }
            beats
        }
        return listOf(bar(false), bar(true))
    }

    /** Standardband: Gitarre (Programm 30 → High Gain, gesampelt), Bass, Drums, alle im Werks-Mix (0,8, Mitte). */
    private fun band(drumBars: List<List<Beat>>): Project {
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        fun power(fret: Int) = arrayOf(g(0, fret), g(1, fret), g(2, fret))
        val riff = (0 until 16).mapNotNull { k -> when (k) { 3, 11 -> beat(f(k.toLong(), 16), 8, *power(if (k == 3) 3 else 5)); 4, 12 -> null; else -> beat(f(k.toLong(), 16), 16, g(0, 0, 96, pm)) } }
        val bassBar = riff.map { bt -> Beat(id(), bt.offset, bt.duration, listOf(b(1, bt.notes.first().fret ?: 0))) }
        val m = List(drumBars.size) { Measure(id()) }
        return project(listOf(
            Track(id(), "Gitarre", guitar, listOf(Staff(id(), setOf(NotationKind.TAB), emptyList()))) to List(m.size) { riff.map { it.copy(id = id(), notes = it.notes.map { n -> n.copy(id = id()) }) } },
            Track(id(), "Bass", bassDef, listOf(Staff(id(), setOf(NotationKind.TAB), emptyList())), SoundAssignment(presetId = "rw.bass.pick")) to List(m.size) { bassBar.map { it.copy(id = id(), notes = it.notes.map { n -> n.copy(id = id()) }) } },
            drumTrack() to drumBars), m)
    }

    // ------------------------------------------------------------------------------------------------------------------

    /** D19-1 (Tabelle + Budget): jedes Kit-Element einzeln, v96 und v127, am Master vor dem Limiter und am Kanaleingang. */
    @Test fun everyKitElementPeaksBelowMinus3DbfsAtTheMaster() {
        val rows = ArrayList<String>(); val failures = ArrayList<String>()
        for ((midi, name) in elements) for (v in listOf(96, 127)) {
            val p = drumsOnly(listOf(listOf(beat(Fraction.ZERO, 4, d(midi, v)))))
            val (l, r) = render(p, 1.2)
            val peak = db(peakOf(l, r)); val body = maxOf(rmsDb(l, 0, SR / 10), rmsDb(r, 0, SR / 10))
            val input = db(dry(p, 1.2).maxOrNull()!!.toDouble())
            rows += "$name v$v: Master-Spitze ${f1(peak)} / RMS 0-100 ms ${f1(body)} dBFS, Kanaleingang ${f1(input)} dBFS"
            if (peak > -3.0) failures += "$name v$v ${f1(peak)} dBFS"
        }
        println("D19 Kit-Elemente (Einzelschlag, Werks-Mix):\n  " + rows.joinToString("\n  "))
        assertTrue("Kit-Elemente über −3 dBFS Spitze am Master (vor Limiter): $failures", failures.isEmpty())
    }

    /** D19-2: der Standard-Groove als Stem (OfflineMixdown, soloTrack, kein Limiter) bleibt ≤ −3 dBFS und clippt nicht. */
    @Test fun grooveStemPeaksBelowMinus3DbfsWithoutClipping() {
        val failures = ArrayList<String>()
        for ((label, bars) in listOf("v96, Akzente v127" to groove(), "alles v127" to groove(127, 127), "v80, Akzente v110" to groove(80, 110))) {
            val p = drumsOnly(bars)
            val res = (OfflineMixdown(bank = null, drumKit = drumKit).render(p, RenderOptions(sampleRate = SR, bitDepth = 16, tailSeconds = 1.0), { _, _, _ -> }, soloTrack = 0) as Outcome.Success).value
            val (l, r) = render(p, 4.2)
            val input = db(dry(p, 4.2).maxOrNull()!!.toDouble())
            val rmsL = db(rms(l, 0, 2 * BAR))
            println("D19 Groove-Stem ($label): Spitze ${f1(db(res.peak))} dBFS, RMS 2 Takte ${f1(rmsL)} dBFS, ${res.clippedSamples} geclippte Samples; Kanaleingang (Stimmensumme) ${f1(input)} dBFS")
            if (db(res.peak) > -3.0 || res.clippedSamples > 0) failures += "$label: ${f1(db(res.peak))} dBFS, ${res.clippedSamples} geclippt"
        }
        assertTrue("Drum-Stem zu heiß (Budget ≤ −3 dBFS, kein Clipping): $failures", failures.isEmpty())
    }

    /** D19-3: Standardband (HG-Gitarre, Bass, Drums, Werks-Mix): Master vor Limiter, Limiteranteil, Spitzen je Spur. */
    @Test fun standardBandWithDrumsKeepsTheLimiterMostlyIdle() {
        val bk = bank; assumeTrue("sample bank not found", bk != null)
        val p = band(groove())
        val (l, r) = render(p, 3.4, includeMasterFx = false, bk = bk)
        val frames = 2 * BAR
        val gain = limiterGain(FloatArray(l.size) { maxOf(abs(l[it]), abs(r[it])) })
        var minG = 1f; var engaged = 0
        for (i in 0 until frames) { if (gain[i] < minG) minG = gain[i]; if (gain[i] < 0.944f) engaged++ }
        val share = 100.0 * engaged / frames
        val perTrack = p.tracks.indices.map { t -> val (a, c) = render(p, 3.4, bk = bk, solo = t); Triple(p.tracks[t].name, db(peakOf(a, c, 0, frames)), db(rms(a, 0, frames))) }
        val (ol, or) = render(p, 3.4, includeMasterFx = true, bk = bk)
        var clipped = 0; for (i in 0 until frames) if (abs(ol[i]) >= 1f || abs(or[i]) >= 1f) clipped++
        println("D19 Standardband (HG + Bass + Drums, Werks-Mix): Master vor Limiter ${f1(db(peakOf(l, r, 0, frames)))} dBFS / RMS ${f1(db(rms(l, 0, frames)))} dBFS, Limiter > 0,5 dB für ${f1(share)} % von 2 Takten, max GR ${f1(-db(minG.toDouble()))} dB; nach Limiter ${f1(db(peakOf(ol, or, 0, frames)))} dBFS, $clipped Frames ≥ 1,0; Spuren: " +
            perTrack.joinToString { "${it.first} Spitze ${f1(it.second)} / RMS ${f1(it.third)} dBFS" })
        assertTrue("Limiter ${f1(share)} % aktiv (Budget ≤ 5 %), max GR ${f1(-db(minG.toDouble()))} dB", share <= 5.0)
        val hot = perTrack.filter { it.first == "Drums" && it.second > -3.0 }
        assertTrue("Drum-Spur in der Band über −3 dBFS: $hot", hot.isEmpty())
        assertTrue("Ausgang clippt in $clipped Frames", clipped == 0)
    }

    /**
     * D19-4: erster Treffer. Kick + Crash + HH auf Takt 1 Zählzeit 1 (Frame 0) gegen dieselben Schläge nach einem stillen
     * Takt (eingeschwungene Engine), und eine Kick/Snare-Kette (gleiche Schläge auf jeder Viertel) pegelgleich zu den
     * späteren Treffern. Offline-Pfad (1024er-Blöcke wie OfflineMixdown); der Live-Pfad steht in Qa19DrumFirstHitTest.
     */
    @Test fun firstHitOnBarOneBeatOneIsCompleteAndLevelMatched() {
        val failures = ArrayList<String>()
        for ((name, notes) in listOf("Kick+Crash+HH" to listOf(36 to 110, 49 to 120, 42 to 90), "Kick" to listOf(36 to 96), "Snare" to listOf(38 to 96), "HH zu" to listOf(42 to 96), "Crash" to listOf(49 to 96), "Tom 45" to listOf(45 to 96))) {
            fun hit() = beat(Fraction.ZERO, 4, *notes.map { (m, v) -> d(m, v) }.toTypedArray())
            val (x, _) = render(drumsOnly(listOf(listOf(hit()))), 0.3)
            val (ref, _) = render(drumsOnly(listOf(emptyList(), listOf(hit()))), 1.9)
            val w = listOf(0 to 5, 5 to 10, 10 to 20, 20 to 50).map { (a, bb) -> rmsDb(x, a * SR / 1000, (bb - a) * SR / 1000) - rmsDb(ref, BAR + a * SR / 1000, (bb - a) * SR / 1000) }
            val firstAudible = (0 until SR / 20).firstOrNull { abs(x[it]) > 1e-4f } ?: -1
            val refAudible = (0 until SR / 20).firstOrNull { abs(ref[BAR + it]) > 1e-4f } ?: -1
            println("D19 erster Treffer $name: hörbar ab Frame $firstAudible (Referenz nach stillem Takt: $refAudible), Fensterdifferenzen 0-5/5-10/10-20/20-50 ms ${w.joinToString(" / ") { f1(it) }} dB")
            if (firstAudible !in 0..2 || abs(firstAudible - refAudible) > 2 || w.any { abs(it) > 1.0 }) failures += "$name: ab $firstAudible (ref $refAudible), ${w.map { f1(it) }}"
        }
        // Gleiche Schläge auf jeder Viertel von Takt 1 und 2: der erste darf nicht leiser (oder lauter) sein als die späteren.
        for ((name, midi) in listOf("Kick" to 36, "Snare" to 38, "Tom 45" to 45)) {
            val bars = List(2) { (0 until 4).map { k -> beat(f(k.toLong(), 4), 4, d(midi, 100)) } }
            val (x, _) = render(drumsOnly(bars), 3.4)
            val levels = (0 until 8).map { k -> rmsDb(x, k * QUARTER, SR / 20) }
            val spread = levels.drop(1).map { levels[0] - it }
            println("D19 Kette $name (8 Viertel, v100): 0-50 ms ${levels.joinToString(" / ") { f1(it) }} dBFS → erster minus spätere ${spread.joinToString(" / ") { f1(it) }} dB")
            if (spread.any { abs(it) > 1.0 }) failures += "Kette $name: erster minus spätere ${spread.map { f1(it) }} dB"
        }
        assertTrue("erster Treffer unvollständig/nicht pegelgleich: $failures", failures.isEmpty())
    }
}
