package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.Qa22Analysis.db
import dev.riffworkbench.synth.Qa22Analysis.f1
import dev.riffworkbench.synth.Qa22Analysis.f2
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The bundled sampled kit (app/src/main/assets/banks/artifret-pearl-kit.rwdk), loaded once for the QA-24 tests. */
object Qa24Kit {
    val file: File? = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/artifret-pearl-kit.rwdk") }.firstOrNull { it.isFile }
    val kit: DrumKit? by lazy { file?.inputStream()?.buffered(1 shl 16)?.use { input -> DrumKit.read { b, o, l -> input.read(b, o, l) } } }
    fun kitOrSkip(): DrumKit { val k = kit; assumeTrue("drum kit not found", k != null); return k!! }
}

/** Every D19 drum check (element peaks, groove stems, standard band limiter, first hit) with the sampled kit. */
class Qa24SampledDrumLevelTest : Qa19DrumLevelTest() {
    override val drumKit: DrumKit? get() = Qa24Kit.kitOrSkip()
}

/** Every Q21 export check (latency compensation from frame 0, Σ stems = mix, latent master chain) with the sampled kit. */
class Qa24SampledExportAlignmentTest : Qa21ExportAlignmentTest() {
    override val drumKit: DrumKit? get() = Qa24Kit.kitOrSkip()
}

/**
 * QA-Zyklus 24, gesampeltes Schlagzeug (ArtiFret Pearl Kit aus AVL Black Pearl 4): Bankvertrag, Velocity-Kurve über die
 * Layer, Chokes nach exclusiveClass, Rimshot, Rückfall auf das synthetische Kit, Pegel in der Standardband (Balance wie
 * Zyklus 22) und clipfreie Stems bei Track-Fader 1,0. Offline durch die echte Kette (48 kHz, 1024er-Blöcke wie OfflineMixdown).
 * Jede Messung druckt ihre Zahlen ("Q24 ...").
 */
class Qa24SampledDrumsTest {
    private var n = 0
    private fun id() = EntityId("q24-${n++}")
    private val sr = 48_000
    private val block = 1024
    private val drumsDef = InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0)
    private val bassDef = InstrumentDefinition(InstrumentKind.BASS, listOf(28, 33, 38, 43), List(4) { 0 }, midiProgram = 33)
    private fun d(midi: Int, v: Int = 100, vararg a: Articulation) = Note(id(), Pitch(midi), velocity = v, articulations = a.toList())
    private fun beat(o: Fraction, den: Int, vararg notes: Note) = Beat(id(), o, WrittenDuration(den), notes.toList())
    private fun check(failures: MutableList<String>, ok: Boolean, what: () -> String) { if (!ok) failures += what() }
    private fun report(name: String, failures: List<String>) = assertTrue("$name: ${failures.size} Fehler:\n" + failures.joinToString("\n"), failures.isEmpty())

    private fun drums(bars: List<List<Beat>>, bpm: Double = 120.0, mix: ChannelMix = ChannelMix()): Project {
        val m = List(bars.size + 1) { Measure(id()) }
        val staff = Staff(id(), setOf(NotationKind.PERCUSSION), m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0 && i < bars.size) bars[i] else emptyList()) }) })
        return Project(id = id(), measures = m, tracks = listOf(Track(id(), "Drums", drumsDef, listOf(staff), mix = mix)), tempo = listOf(TempoChange(ScorePosition(m[0].id), bpm)))
    }
    private class Out(val l: FloatArray, val r: FloatArray)
    /** Engine like OfflineMixdown without the master chain (pre-limiter master, factory mix). */
    private fun render(p: Project, seconds: Double, kit: DrumKit?, bank: SampleBank? = null, solo: Int? = null, master: Boolean = false): Out {
        val plan = (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
        val e = SynthEngine(sr, block, bank, kit)
        e.installRack(ChannelRack.build(p, sr, block, includeMasterFx = master && solo == null)); e.setMix(MixState.from(p, 0f, solo)); e.install(EngineProgram.prepare(plan)); e.play(0)
        val frames = (seconds * sr).toInt(); val l = FloatArray(frames); val r = FloatArray(frames); val buf = FloatArray(2 * block); var done = 0
        while (done < frames) { val k = minOf(block, frames - done); e.render(buf, k); for (i in 0 until k) { l[done + i] = buf[2 * i]; r[done + i] = buf[2 * i + 1] }; done += k }
        return Out(l, r)
    }
    /** The drum voices alone (no compressor/EQ/reverb, unity channel and master gain): chokes are measured without the nonlinear chain. */
    private fun dry(p: Project, seconds: Double, kit: DrumKit?): Out {
        val ch = Channel(ChannelKind.DRUMS, TonePresets.stringTone(TonePresets.find("rw.drums.rock")!!), KeysMode.PIANO, emptyArray(), block, voiceSignature = "dry")
        val plan = (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
        val e = SynthEngine(sr, block, null, kit)
        e.installRack(ChannelRack(arrayOf(ch), emptyArray(), "dry")); e.setMix(MixState(floatArrayOf(1f), floatArrayOf(0f), 2f, 0f)); e.install(EngineProgram.prepare(plan)); e.play(0)
        val frames = (seconds * sr).toInt(); val l = FloatArray(frames); val r = FloatArray(frames); val buf = FloatArray(2 * block); var done = 0
        while (done < frames) { val k = minOf(block, frames - done); e.render(buf, k); for (i in 0 until k) { l[done + i] = buf[2 * i]; r[done + i] = buf[2 * i + 1] }; done += k }
        return Out(l, r)
    }
    private fun rmsDb(o: Out, from: Int, to: Int): Double { var s = 0.0; val b = minOf(to, o.l.size); for (i in from until b) s += o.l[i].toDouble() * o.l[i] + o.r[i].toDouble() * o.r[i]; return 10 * log10(s / (2.0 * (b - from).coerceAtLeast(1)) + 1e-30) }
    private fun peakDb(o: Out, from: Int = 0, to: Int = o.l.size): Double { var p = 0.0; for (i in from until minOf(to, o.l.size)) p = maxOf(p, abs(o.l[i].toDouble()), abs(o.r[i].toDouble())); return db(p) }

    // ============================================================================================ 1. Bankvertrag

    @Test fun bundledKitHoldsTheFactoryKitTheTomsAndTheAttribution() {
        val kit = Qa24Kit.kitOrSkip()
        val f = Qa24Kit.file!!
        println("Q24 Bank ${f.name}: ${f.length()} Bytes (${f2(f.length() / 1e6)} MB), ${kit.sampleRate} Hz, ${kit.samples.size} Samples, ${kit.noteCount} GM-Noten ${kit.midiNotes}; Info: ${kit.info}")
        val failures = mutableListOf<String>()
        check(failures, f.length() <= 20_000_000L) { "Bank ${f.length()} Bytes > 20 MB" }
        check(failures, kit.sampleRate == 44_100) { "Samplerate ${kit.sampleRate}" }
        check(failures, kit.info.contains("AVL Black Pearl") && kit.info.contains("Glen MacArthur") && kit.info.contains("CC BY-SA 3.0")) { "Namensnennung fehlt im Bankkopf: ${kit.info}" }
        // Werks-Kit (defaultDrumKit + ProjectFactory) und alle GM-Toms/Becken des Standard-Grooves.
        for (m in listOf(36, 38, 42, 46, 49, 44, 41, 43, 45, 47, 48, 50, 51, 53, 55, 57, 37, 40)) check(failures, kit.note(m) != null) { "GM $m fehlt im Kit" }
        for (m in kit.midiNotes) check(failures, kit.note(m)!!.normal.layers.size == 5) { "GM $m: ${kit.note(m)!!.normal.layers.size} Velocity-Layer statt 5" }
        val hh = listOf(42, 44, 46).map { kit.note(it)!!.exclusiveClass }
        check(failures, hh.all { it != 0 && it == hh[0] }) { "Hi-Hat offen/zu/Pedal ohne gemeinsame Choke-Klasse: $hh" }
        check(failures, kit.note(49)!!.exclusiveClass != 0 && kit.note(49)!!.exclusiveClass != kit.note(57)!!.exclusiveClass && kit.note(49)!!.exclusiveClass != hh[0]) { "Crash 1/2 Klassen ${kit.note(49)!!.exclusiveClass}/${kit.note(57)!!.exclusiveClass}" }
        check(failures, kit.note(38)!!.rimshot != null && kit.note(45)!!.rimshot != null) { "Rimshot-Artikulation fehlt" }
        check(failures, kit.note(36)!!.exclusiveClass == 0 && !kit.note(36)!!.cymbal && kit.note(49)!!.cymbal) { "Kick/Crash-Flags" }
        report("Bankvertrag", failures)
    }

    @Test fun converterOutputIsDeterministicAndMatchesTheBundledKit() {
        val kit = Qa24Kit.kitOrSkip()
        // The file is parsed twice into equal content (the converter writes no timestamps; see tools/make_drum_bank.py).
        val again = DrumKit.read(Qa24Kit.file!!.readBytes())
        assertEquals(kit.samples.size, again.samples.size)
        for (i in kit.samples.indices) assertTrue("Sample $i", kit.samples[i].contentEquals(again.samples[i]))
        assertEquals(kit.midiNotes, again.midiNotes)
    }

    // ============================================================================================ 2. Velocity, Chokes, Rimshot

    /** Lautheit (0–50 ms) jedes Elements über v = 8..127: monoton (±0,5 dB) und ohne Sprung > 3 dB an Layer-Grenzen. */
    @Test fun loudnessGrowsSmoothlyWithVelocityAcrossLayers() {
        val kit = Qa24Kit.kitOrSkip()
        val failures = mutableListOf<String>()
        for (m in listOf(36, 38, 42, 46, 44, 45, 41, 49, 51, 57)) {
            val vs = (8..127 step 7).toList() + 127
            val levels = vs.map { v -> rmsDb(render(drums(listOf(listOf(beat(Fraction.ZERO, 4, d(m, v))))), 0.1, kit), 0, sr / 20) }
            println("Q24V GM $m: " + vs.zip(levels).joinToString(" ") { (v, l) -> "v$v ${f1(l)}" })
            for (i in 1 until levels.size) {
                val step = levels[i] - levels[i - 1]
                check(failures, step >= -0.5) { "GM $m: v${vs[i - 1]}→v${vs[i]} leiser um ${f1(-step)} dB" }
                check(failures, step <= 3.0 + 1.3 * 20 * log10(vs[i].toDouble() / vs[i - 1])) { "GM $m: v${vs[i - 1]}→v${vs[i]} Sprung ${f1(step)} dB" }
            }
        }
        report("Velocity-Kurve", failures)
    }

    /**
     * Choke-Gruppen aus exclusiveClass: offene Hi-Hat endet ≤ 20 ms nach geschlossener/Pedal-Hi-Hat (Rest ≥ 40 dB unter der
     * offenen allein), ein zweiter Crash-1-Schlag beendet den ersten, Crash 2 und Kick lassen Crash 1 klingen.
     */
    @Test fun exclusiveClassesChokeHiHatAndCymbalsOnly() {
        val kit = Qa24Kit.kitOrSkip()
        val failures = mutableListOf<String>()
        val at = Fraction.of(1, 4); val atFrame = sr / 2   // 120 BPM: a quarter is 0.5 s
        fun residual(first: Int, second: Int): Double {
            val both = dry(drums(listOf(listOf(beat(Fraction.ZERO, 4, d(first, 110)), beat(at, 4, d(second, 110))))), 1.2, kit)
            val alone = dry(drums(listOf(listOf(beat(at, 4, d(second, 110))))), 1.2, kit)
            val ref = dry(drums(listOf(listOf(beat(Fraction.ZERO, 4, d(first, 110))))), 1.2, kit)
            // What remains of the first stroke 20..120 ms after the second, relative to the first stroke alone there.
            val from = atFrame + sr / 50; val to = atFrame + sr / 8
            val diff = Out(FloatArray(both.l.size) { both.l[it] - alone.l[it] }, FloatArray(both.r.size) { both.r[it] - alone.r[it] })
            return rmsDb(diff, from, to) - rmsDb(ref, from, to)
        }
        for ((a, b, choked) in listOf(Triple(46, 42, true), Triple(46, 44, true), Triple(49, 49, true), Triple(51, 59, true), Triple(49, 57, false), Triple(49, 36, false), Triple(46, 38, false))) {
            val r = residual(a, b)
            println("Q24C GM $a dann GM $b: Rest des ersten Schlags 20–125 ms danach ${f1(r)} dB gegen allein (${if (choked) "soll abgewürgt" else "soll weiterklingen"})")
            if (choked) check(failures, r <= -40.0) { "GM $b würgt GM $a nicht ab (Rest ${f1(r)} dB)" } else check(failures, abs(r) <= 0.5) { "GM $b verändert GM $a um ${f1(r)} dB" }
        }
        report("Chokes", failures)
    }

    /** Rimshot auf Snare und Tom spielt die Rand-Aufnahme (anderes Sample), Cross-Stick den Side Stick (GM 37). */
    @Test fun rimshotPlaysTheEdgeRecording() {
        val kit = Qa24Kit.kitOrSkip()
        val failures = mutableListOf<String>()
        for (m in listOf(38, 45, 41)) {
            val normal = render(drums(listOf(listOf(beat(Fraction.ZERO, 4, d(m, 110))))), 0.3, kit)
            val rim = render(drums(listOf(listOf(beat(Fraction.ZERO, 4, d(m, 110, Articulation.Percussion(PercussionTechnique.RIMSHOT)))))), 0.3, kit)
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (i in 0 until sr / 10) { xy += normal.l[i].toDouble() * rim.l[i]; xx += normal.l[i].toDouble() * normal.l[i]; yy += rim.l[i].toDouble() * rim.l[i] }
            val corr = xy / sqrt(xx * yy + 1e-30)
            println("Q24R GM $m normal ${f1(rmsDb(normal, 0, sr / 10))} / Rimshot ${f1(rmsDb(rim, 0, sr / 10))} dBFS, Korrelation ${f2(corr)}")
            check(failures, corr < 0.9 && rmsDb(rim, 0, sr / 10) > -60) { "GM $m: Rimshot klingt wie der normale Schlag (Korrelation ${f2(corr)})" }
        }
        report("Rimshot", failures)
    }

    /** GM-Noten ohne Sample (z. B. 60 Hi Bongo, 76 Woodblock) klingen wie bisher synthetisch, bitgleich zur Engine ohne Kit. */
    @Test fun notesOutsideTheKitPlayTheSyntheticKitBitForBit() {
        val kit = Qa24Kit.kitOrSkip()
        val p = drums(listOf(listOf(beat(Fraction.ZERO, 4, d(60)), beat(Fraction.of(1, 4), 4, d(76)), beat(Fraction.of(1, 2), 4, d(81)))))
        val a = render(p, 1.5, kit); val b = render(p, 1.5, null)
        var diff = 0f; for (i in a.l.indices) diff = maxOf(diff, abs(a.l[i] - b.l[i]), abs(a.r[i] - b.r[i]))
        println("Q24F GM 60/76/81 mit Kit gegen synthetisch: max |Δ| $diff, Spitze ${f1(peakDb(a))} dBFS")
        assertTrue("Rückfall nicht bitgleich: $diff", diff == 0f && peakDb(a) > -40)
    }

    // ============================================================================================ 3. Pegel

    /** Standard-Groove: Kick/Snare/HH-Achtel, Crash auf 1, Tom-Fill (16tel, sechs GM-Toms) auf Zählzeit 4 von Takt 2. */
    private fun groove(v: Int = 96, accent: Int = 127): List<List<Beat>> {
        fun bar(fill: Boolean): List<Beat> {
            val beats = (0 until 8).filter { !(fill && it >= 6) }.map { k ->
                val notes = mutableListOf(d(42, if (k % 2 == 0) v else v - 16))
                if (k == 0 || k == 4 || k == 5) notes += d(36, if (k == 0) accent else v)
                if (k == 2 || k == 6) notes += d(38, accent)
                if (k == 0 && !fill) notes += d(49, accent)
                beat(Fraction.of(k.toLong(), 8), 8, *notes.toTypedArray())
            }.toMutableList()
            if (fill) listOf(50, 48, 47, 45).forEachIndexed { k, m -> beats += beat(Fraction.of(3, 4) + Fraction.of(k.toLong(), 16), 16, d(m, accent)) }
            return beats
        }
        return listOf(bar(false), bar(true))
    }

    /** Groove-Stem bei Track-Fader 1,0 (+3,9 dB über Werk) und bei 0,8: kein Clipping, Spitze ≤ −0,3 dBFS (wie Qa22 für die Gitarre). */
    @Test fun drumStemsDoNotClipAtTrackFaderOne() {
        val kit = Qa24Kit.kitOrSkip()
        val failures = mutableListOf<String>()
        for ((label, bars) in listOf("v96/Akzente v127" to groove(), "alles v127" to groove(127, 127))) for (fader in listOf(0.8, 1.0)) {
            val p = drums(bars, 150.0, ChannelMix(volume = fader))
            val res = (OfflineMixdown(bank = null, drumKit = kit).render(p, RenderOptions(sampleRate = sr, bitDepth = 24, tailSeconds = 1.0), { _, _, _ -> }, soloTrack = 0) as Outcome.Success).value
            val syn = (OfflineMixdown(bank = null).render(p, RenderOptions(sampleRate = sr, bitDepth = 24, tailSeconds = 1.0), { _, _, _ -> }, soloTrack = 0) as Outcome.Success).value
            println("Q24S Groove-Stem $label, Fader $fader: gesampelt ${f1(db(res.peak))} dBFS / ${res.clippedSamples} geclippt (synthetisch ${f1(db(syn.peak))} dBFS)")
            check(failures, db(res.peak) <= -0.3 && res.clippedSamples == 0L) { "$label Fader $fader: ${f1(db(res.peak))} dBFS, ${res.clippedSamples} geclippt" }
        }
        report("Drum-Stems", failures)
    }

    /** Standardband wie Qa22 (HG-Gitarre, Bass, Drums, Fader 0,8, 150 BPM): Solo-RMS, Lautheit und Spitze vor dem Limiter. */
    private fun band(): Project {
        val factoryGuitar = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = List(6) { 0 })
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        fun gn(s: Int, f: Int, vararg a: Articulation) = Note(id(), Pitch(factoryGuitar.pitch(s, f)), s, f, 96, a.toList())
        fun bn(s: Int, f: Int) = Note(id(), Pitch(bassDef.pitch(s, f)), s, f, 104)
        val riff = (0 until 16).mapNotNull { k -> when (k) { 3, 11 -> Triple(k, 8, if (k == 3) 3 else 5); 4, 12 -> null; else -> Triple(k, 16, -1) } }
        fun guitarBar() = riff.map { (k, den, fr) -> beat(Fraction.of(k.toLong(), 16), den, *(if (fr < 0) arrayOf(gn(0, 0, pm)) else arrayOf(gn(0, fr), gn(1, fr + 2), gn(2, fr + 2)))) }
        fun bassBar() = riff.map { (k, den, fr) -> beat(Fraction.of(k.toLong(), 16), den, bn(1, if (fr < 0) 0 else fr)) }
        fun drumBar(crash: Boolean) = (0 until 8).map { k ->
            val notes = mutableListOf(d(42, if (k % 2 == 0) 96 else 80))
            if (k == 0 || k == 4 || k == 5) notes += d(36, if (k == 0) 127 else 96)
            if (k == 2 || k == 6) notes += d(38, 127)
            if (k == 0 && crash) notes += d(49, 127)
            beat(Fraction.of(k.toLong(), 8), 8, *notes.toTypedArray())
        }
        val m = List(3) { Measure(id()) }
        fun staff(bars: List<List<Beat>>, kind: NotationKind) = listOf(Staff(id(), setOf(kind), m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0 && i < bars.size) bars[i] else emptyList()) }) }))
        return Project(id = id(), measures = m, tracks = listOf(
            Track(id(), "Gitarre", factoryGuitar, staff(listOf(guitarBar(), guitarBar()), NotationKind.TAB)),
            Track(id(), "Bass", bassDef, staff(listOf(bassBar(), bassBar()), NotationKind.TAB)),
            Track(id(), "Drums", drumsDef, staff(listOf(drumBar(true), drumBar(false)), NotationKind.PERCUSSION))), tempo = listOf(TempoChange(ScorePosition(m[0].id), 150.0)))
    }

    /**
     * Balance der Standardband wie in Zyklus 22 festgeschrieben (Gitarre−Bass +3,6, Gitarre−Drums +2,5 dB Solo-RMS, je ±2 dB;
     * die Qa22-Prüfung selbst verlangt +5,1/+4,0 ±2 gegen ef25d88, beides muss gelten), Lautheit ±2 LU zum synthetischen Kit,
     * Limiter 0 %, Spitze vor dem Limiter < −0,5 dBFS.
     */
    @Test fun standardBandKeepsTheCycle22BalanceWithSampledDrums() {
        val kit = Qa24Kit.kitOrSkip()
        val bank = Qa18GainStructureTest.bank; assumeTrue("sample bank not found", bank != null)
        val p = band()
        val frames = (3.2 * sr).toInt()
        val failures = mutableListOf<String>()
        val rows = mutableMapOf<String, DoubleArray>()
        for ((name, k) in listOf("synthetisch" to null, "gesampelt" to kit)) {
            val mix = render(p, 3.2, k, bank)
            val solo = p.tracks.indices.map { t -> db(Qa22Analysis.rms(render(p, 3.2, k, bank, solo = t).l, 0, frames)) }
            val lufs = Qa22Analysis.integratedLufs(listOf(mix.l, mix.r), sr)
            val lim = Qa18GainStructureTest.limiterGain(FloatArray(frames) { maxOf(abs(mix.l[it]), abs(mix.r[it])) })
            var engaged = 0; for (x in lim) if (x < 0.944f) engaged++
            val share = 100.0 * engaged / frames
            val drumStem = render(p, 3.2, k, bank, solo = 2)
            rows[name] = doubleArrayOf(solo[0] - solo[1], solo[0] - solo[2], lufs, peakDb(mix), share)
            println("Q24B Standardband ($name): Gitarre−Bass ${f1(solo[0] - solo[1])} / Gitarre−Drums ${f1(solo[0] - solo[2])} dB | Solo-RMS ${solo.joinToString(" / ") { f1(it) }} dBFS | ${f1(lufs)} LUFS | Spitze vor Limiter ${f1(peakDb(mix))} dBFS | Limiter ${f2(share)} % | Drum-Stem Spitze ${f1(peakDb(drumStem))} / RMS ${f1(rmsDb(drumStem, 0, frames))} dBFS")
        }
        val s = rows.getValue("gesampelt"); val y = rows.getValue("synthetisch")
        check(failures, abs(s[0] - 3.6) <= 2.0 && abs(s[0] - 5.1) <= 2.0) { "Gitarre−Bass ${f1(s[0])} dB (Soll +3,6 ±2 und Qa22 +5,1 ±2)" }
        check(failures, abs(s[1] - 2.5) <= 2.0 && abs(s[1] - 4.0) <= 2.0) { "Gitarre−Drums ${f1(s[1])} dB (Soll +2,5 ±2 und Qa22 +4,0 ±2)" }
        check(failures, abs(s[2] - y[2]) <= 2.0) { "Band ${f1(s[2])} LUFS gegen synthetisch ${f1(y[2])} (Soll ±2 LU)" }
        check(failures, s[4] == 0.0 && s[3] < -0.5) { "Limiter ${f2(s[4])} %, Spitze ${f1(s[3])} dBFS" }
        report("Standardband", failures)
    }

    /** Tabelle fürs Journal: jedes Element v96/v127 einzeln, synthetisch gegen gesampelt (Master vor Limiter, Werks-Mix). */
    @Test fun probe_elementLevels() {
        val kit = Qa24Kit.kitOrSkip()
        for ((m, name) in Qa19DrumLevelTest.elements) {
            val row = listOf(96, 127).joinToString(" | ") { v ->
                val p = drums(listOf(listOf(beat(Fraction.ZERO, 4, d(m, v)))))
                val a = render(p, 1.2, null); val b = render(p, 1.2, kit)
                "v$v synthetisch ${f1(peakDb(a))}/${f1(rmsDb(a, 0, sr / 10))}, gesampelt ${f1(peakDb(b))}/${f1(rmsDb(b, 0, sr / 10))}"
            }
            println("Q24E $name (GM $m) Spitze/RMS 0–100 ms dBFS: $row")
        }
    }
}
