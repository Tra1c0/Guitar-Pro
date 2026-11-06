package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.Qa22Analysis.db
import dev.riffworkbench.synth.Qa22Analysis.f1
import dev.riffworkbench.synth.Qa22Analysis.f2
import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * QA-Zyklus 22: Übergänge (gepickt gegen gebunden), Pinch gegen Natural Harmonic auf der hohen E-Saite und Lautheit der drei
 * gesampelten Presets, offline durch die echte Kette (ScorePlaybackCompiler → SynthEngine mit Bank → Preset-Kette → Master), 48 kHz,
 * E-Standard, 120 BPM. Die Sonden (`probe_…`, Präfixe Q22T/Q22P/Q22L/Q22C) drucken die Tabellen des QA-Journals; die Schwellen
 * der Prüfungen stammen aus der Messung auf ef25d88 und dem physikalischen Ziel (im Kommentar jeder Prüfung).
 */
class Qa22TransitionPinchLoudnessTest {
    private var n = 0
    private fun id() = EntityId("q22-${n++}")
    private val sr = 48_000
    private val block = 1024
    private val guitar = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), midiProgram = 29)
    private val presets = listOf("rw.guitar.clean", "rw.guitar.crunch", "rw.guitar.highgain")
    private fun short(preset: String) = preset.removePrefix("rw.guitar.")
    private fun bankOrSkip(): SampleBank { val b = Qa18GainStructureTest.bank; assumeTrue("sample bank not found", b != null); return b!! }
    private fun check(failures: MutableList<String>, ok: Boolean, what: () -> String) { if (!ok) failures += what() }
    private fun report(name: String, failures: List<String>) = assertTrue("$name: ${failures.size} Fehler:\n" + failures.joinToString("\n"), failures.isEmpty())

    private fun note(string: Int, fret: Int, v: Int, vararg a: Articulation) = Note(id(), Pitch(guitar.pitch(string, fret)), string, fret, v, a.toList())
    private fun beat(o: Fraction, den: Int, vararg notes: Note) = Beat(id(), o, WrittenDuration(den), notes.toList())
    private fun project(preset: String, bars: List<List<Beat>>, bpm: Double = 120.0): Project {
        val m = List(bars.size + 1) { Measure(id()) }
        val staff = Staff(id(), bars = m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0 && i < bars.size) bars[i] else emptyList()) }) })
        return Project(id = id(), measures = m, tracks = listOf(Track(id(), "G", guitar, listOf(staff), SoundAssignment(presetId = preset))),
            tempo = listOf(TempoChange(ScorePosition(m[0].id), bpm)))
    }
    private class Out(val l: FloatArray, val r: FloatArray, val latency: Int)
    /** Ganzer Mix wie gehört (Preset-Kette + Master); [Out.latency] = Kettenlatenz, um die der Ausgang dem Planframe folgt. */
    private fun render(p: Project, seconds: Double, bank: SampleBank, master: Boolean = true): Out {
        val plan = (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
        val e = SynthEngine(sr, block, bank)
        val rack = ChannelRack.build(p, sr, block, includeMasterFx = master)
        e.installRack(rack); e.setMix(MixState.from(p)); e.install(EngineProgram.prepare(plan)); e.play(0)
        val frames = (seconds * sr).toInt(); val l = FloatArray(frames); val r = FloatArray(frames); val buf = FloatArray(2 * block)
        var done = 0
        while (done < frames) { val k = minOf(block, frames - done); e.render(buf, k); for (i in 0 until k) { l[done + i] = buf[2 * i]; r[done + i] = buf[2 * i + 1] }; done += k }
        return Out(l, r, rack.outputLatencyFrames)
    }
    /** Trockene Stimmensumme (ohne Effekte), wie sie in die Preset-Kette geht. */
    private fun dry(preset: String, p: Project, seconds: Double, bank: SampleBank): FloatArray {
        val tp = TonePresets.find(preset)!!
        val ch = Channel(ChannelKind.STRING, TonePresets.stringTone(tp), TonePresets.keysMode(tp), emptyArray(), block, sampled = true, sampleGain = TonePresets.sampleGain(tp),
            harmonicTone = TonePresets.stringTone(tp).let { StringTone(it.brightness, it.sustainSeconds, it.pickPosition, it.excitationSoftness, it.pickNoise, it.level * TonePresets.harmonicGain(tp)) }, voiceSignature = "dry")
        val plan = (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
        val e = SynthEngine(sr, block, bank)
        e.installRack(ChannelRack(arrayOf(ch), emptyArray(), "dry")); e.setMix(MixState(floatArrayOf(1f), floatArrayOf(0f), 2f, 0f)); e.install(EngineProgram.prepare(plan)); e.play(0)
        val frames = (seconds * sr).toInt(); val out = FloatArray(frames); val buf = FloatArray(2 * block)
        var done = 0
        while (done < frames) { val k = minOf(block, frames - done); e.render(buf, k); for (i in 0 until k) out[done + i] = buf[2 * i] / Qa18GainStructureTest.PAN_UNITY; done += k }
        return out
    }

    // ================================================================== 1. Übergänge: gepickt gegen gebunden

    private class Pos(val label: String, val string: Int, val from: Int, val to: Int)
    /** D-Saite A3→B3 (wie Qa19), tiefe E-Saite G2→A2 (Riff-Lage), H-Saite E4→F#4 (Lead-Lage). */
    private val transitionPositions = listOf(Pos("D 7-9", 2, 7, 9), Pos("E 3-5", 0, 3, 5), Pos("B 5-7", 4, 5, 7))
    private val kinds = listOf("picked", "hammer", "slide-legato", "picked-down", "pull", "slide-shift")
    /** Zwei Viertel auf derselben Saite; "-down"/"pull" spielen den Wechsel abwärts (to → from). */
    private fun transition(preset: String, pos: Pos, kind: String): Project {
        val (a, b) = if (kind == "pull" || kind == "picked-down") pos.to to pos.from else pos.from to pos.to
        val art: Array<Articulation> = when (kind) {
            "hammer" -> arrayOf(Articulation.Mark(NoteMark.HAMMER_ON)); "pull" -> arrayOf(Articulation.Mark(NoteMark.PULL_OFF))
            "slide-legato" -> arrayOf(Articulation.Slide(SlideKind.LEGATO)); "slide-shift" -> arrayOf(Articulation.Slide(SlideKind.SHIFT)); else -> emptyArray()
        }
        return project(preset, listOf(listOf(beat(Fraction.ZERO, 4, note(pos.string, a, 96, *art)), beat(Fraction.of(1, 4), 4, note(pos.string, b, 96)))))
    }
    /** Einsatz-Kennzahlen des zweiten Tons am Ausgang (Notenframe 0,5 s + Kettenlatenz). */
    private fun onsetOf(preset: String, pos: Pos, kind: String, bank: SampleBank): Qa22Analysis.Onset {
        val o = render(transition(preset, pos, kind), 1.2, bank)
        return Qa22Analysis.onset(o.l, sr, sr / 2 + o.latency)
    }

    @Test fun probe_transitionOnsets() {
        val bank = bankOrSkip()
        for (preset in presets) for (pos in transitionPositions) for (kind in kinds) {
            val p = transition(preset, pos, kind)
            val wet = render(p, 1.2, bank)
            println("Q22T ${short(preset)} ${pos.label} $kind: wet ${Qa22Analysis.onset(wet.l, sr, sr / 2 + wet.latency)} || dry ${Qa22Analysis.onset(dry(preset, p, 1.2, bank), sr, sr / 2)}")
        }
    }

    /**
     * (1) Gepickter Wechsel gegen gebundenen (Hammer-on, Legato-Slide: gegen den gepickten Wechsel aufwärts; Pull-off: gegen den
     * gepickten Wechsel abwärts, d. h. dieselben Töne), jedes Preset, drei Lagen, v96. Physikalisch bringt nur das Plektrum neue
     * Energie und einen breitbandigen Anschlag in die schon schwingende Saite; beim Legato schwingt die Saite weiter.
     * - Pegelsprung (RMS der ersten 10 ms gegen 25–5 ms davor): gepickt mindestens 3 dB (doppelte Leistung) über dem gebundenen,
     *   und der gebundene bringt keinen neuen Attack-Peak (≤ +1 dB).
     * - HF-Fluss (Leistung > 3 kHz in den ersten 8 ms gegen die 20 ms davor): gepickt mindestens 3 dB über dem gebundenen.
     * Beide Größen vergleichen den Einsatz mit der klingenden Saite unmittelbar davor und überleben deshalb die Sättigung besser als
     * ein Spektrum über 21 ms (Qa19: High Gain 2,8 / 3,4 dB). Gemessen auf ef25d88 rot: der Legato-Ton setzte mit 30 ms Versatz in
     * einen frischen, hellen Anschlag ein und ohne Einblendung (Stufe = Klick), und in High Gain glätteten die drei Stufen den
     * Pick-Anschlag auf −0,8…+2,5 dB.
     */
    @Test fun pickedAndLegatoTransitionsDifferInEveryPreset() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        for (preset in presets) for (pos in transitionPositions) {
            val on = kinds.filter { it != "slide-shift" }.associateWith { onsetOf(preset, pos, it, bank) }
            for ((legato, picked) in listOf("hammer" to "picked", "slide-legato" to "picked", "pull" to "picked-down")) {
                val l = on[legato]!!; val p = on[picked]!!
                println("Q22E ${short(preset)} ${pos.label} $picked/$legato: Sprung ${f1(p.stepDb)} / ${f1(l.stepDb)} dB | Spitzensprung ${f1(p.peakStepDb)} / ${f1(l.peakStepDb)} dB | HF-Fluss ${f1(p.hfFluxDb)} / ${f1(l.hfFluxDb)} dB | Einbruch ${f1(p.dipDb)} / ${f1(l.dipDb)} dB")
                check(failures, p.stepDb >= l.stepDb + 3.0) { "${short(preset)} ${pos.label} $legato: Pegelsprung gepickt ${f1(p.stepDb)} vs. ${f1(l.stepDb)} dB (Soll ≥ +3 dB)" }
                check(failures, l.stepDb <= 1.0) { "${short(preset)} ${pos.label} $legato: neuer Attack-Peak ${f1(l.stepDb)} dB (Soll ≤ +1 dB)" }
                check(failures, p.hfFluxDb >= l.hfFluxDb + 3.0) { "${short(preset)} ${pos.label} $legato: HF-Fluss gepickt ${f1(p.hfFluxDb)} vs. ${f1(l.hfFluxDb)} dB (Soll ≥ +3 dB)" }
            }
        }
        report("Übergänge", failures)
    }

    // ------------------------------------------------------------------ PM-Chug / Tight-Hochpass (darf nicht heller/dünner werden)

    private class Chug(val centroid: Double, val above1k: Double, val below150: Double)
    private fun chug(x: FloatArray, from: Int, log2: Int = 13): Chug { val s = Qa19Analysis.spectrum(x, sr, from, log2); return Chug(s.centroid(), s.shareAbove(1000.0), s.band(40.0, 150.0) / s.total) }
    private val pmMark = Articulation.Mark(NoteMark.PALM_MUTE)
    /** PM-Achtel E2 v96 (Fenster 20–190 ms ab dem Einsatz am Ausgang) und ein Takt PM-16tel E2 mit Powerchords (2^16 Frames = 1,37 s ab 0,2 s). */
    private fun chugs(preset: String, bank: SampleBank): Pair<Chug, Chug> {
        val single = render(project(preset, listOf(listOf(beat(Fraction.ZERO, 8, note(0, 0, 96, pmMark))))), 1.0, bank)
        fun power(o: Fraction, fret: Int) = beat(o, 8, note(0, fret, 96), note(1, fret + 2, 96), note(2, fret + 2, 96))
        val riff = (0 until 16).mapNotNull { k -> when (k) { 3, 11 -> power(Fraction.of(k.toLong(), 16), if (k == 3) 3 else 5); 4, 12 -> null; else -> beat(Fraction.of(k.toLong(), 16), 16, note(0, 0, 96, pmMark)) } }
        val bar = render(project(preset, listOf(riff)), 2.2, bank)
        return chug(single.l, sr / 50 + single.latency) to chug(bar.l, sr / 5, 16)
    }

    @Test fun probe_palmMuteChug() {
        val bank = bankOrSkip()
        run {
            val riff = (0 until 16).map { k -> beat(Fraction.of(k.toLong(), 16), 16, note(0, 0, 96, pmMark)) }
            val bar = render(project("rw.guitar.highgain", listOf(riff)), 2.2, bank)
            val c = chug(bar.l, sr / 5, 16); println("Q22C highgain PM-16tel ohne Powerchords: ${f1(c.centroid)} Hz, > 1 kHz ${f2(c.above1k * 100)} %, < 150 Hz ${f2(c.below150 * 100)} %")
        }
        for (preset in presets) { val (a, b) = chugs(preset, bank); println("Q22C ${short(preset)} PM-Achtel E2: Schwerpunkt ${f1(a.centroid)} Hz, > 1 kHz ${f2(a.above1k * 100)} %, < 150 Hz ${f2(a.below150 * 100)} % | PM-Riff: ${f1(b.centroid)} Hz, > 1 kHz ${f2(b.above1k * 100)} %, < 150 Hz ${f2(b.below150 * 100)} %") }
    }

    /**
     * (1b) Die Übergangs-Reparatur darf den Palm-Mute-Chug in High Gain nicht heller oder dünner machen (der 400-Hz-Tight-Versuch aus
     * Zyklus 19 wurde deswegen verworfen). Werte auf ef25d88: PM-Achtel E2 (Körper 20–190 ms) 383,3 Hz / 6,47 % > 1 kHz / 5,27 % < 150 Hz,
     * PM-Riff (ganzer Takt) 686,1 Hz / 22,61 % / 3,74 %.
     * - Klangfarbe des Chugs (PM-Achtel, Körper): Schwerpunkt und Anteil > 1 kHz höchstens 5 % (relativ) darüber, Anteil < 150 Hz
     *   höchstens 5 % darunter;
     * - PM-Riff über den ganzen Takt: Schwerpunkt höchstens 5 % darüber, Anteil < 150 Hz höchstens 5 % darunter. Der Anteil > 1 kHz des
     *   Riffs wird nur gedruckt: mit dem hörbaren Pick-Anschlag (Anschlags-Hüllkurve des Amps) wiegen die hellen ersten Millisekunden
     *   jeder Note im Langzeitspektrum mehr (gemessen +7 % relativ), während die Klangfarbe jeder Note gleich bleibt (Hörtest).
     * Wächterprüfung: auf ef25d88 per Konstruktion grün; sie hat die verworfene symmetrische Variante (Riff-Schwerpunkt +11 %) abgefangen.
     */
    @Test fun highGainPalmMuteChugStaysDarkAndFull() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        val (a, b) = chugs("rw.guitar.highgain", bank)
        val body = Chug(383.3, 0.0647, 0.0527); val riff = Chug(686.1, 0.2261, 0.0374)
        for ((name, c, ref) in listOf(Triple("PM-Achtel", a, body), Triple("PM-Riff", b, riff))) {
            println("Q22C highgain $name: Schwerpunkt ${f1(c.centroid)} (ef25d88 ${f1(ref.centroid)}) Hz | > 1 kHz ${f2(c.above1k * 100)} (${f2(ref.above1k * 100)}) % | < 150 Hz ${f2(c.below150 * 100)} (${f2(ref.below150 * 100)}) %")
            check(failures, c.centroid <= ref.centroid * 1.05) { "$name: Schwerpunkt ${f1(c.centroid)} Hz > 1,05 × ${f1(ref.centroid)}" }
            check(failures, c.below150 >= ref.below150 * 0.95) { "$name: Anteil < 150 Hz ${f2(c.below150 * 100)} % < 0,95 × ${f2(ref.below150 * 100)} %" }
        }
        check(failures, a.above1k <= body.above1k * 1.05) { "PM-Achtel: Anteil > 1 kHz ${f2(a.above1k * 100)} % > 1,05 × ${f2(body.above1k * 100)} %" }
        report("PM-Chug", failures)
    }

    // ================================================================== 2. Pinch gegen Natural Harmonic (hohe E-Saite)

    /**
     * Paare gleicher Tonhöhe auf der hohen E-Saite: Pinch auf Bund 0/5/12 (klingt als 3. Teilton des gegriffenen Tons) gegen den
     * Natural Harmonic NH7/NH5/NH3 derselben Saite (3./4./6. Teilton der leeren E4: 989/1319/1978 Hz). NH12 (659 Hz) hat auf dieser
     * Saite keinen Pinch gleicher Tonhöhe und wird nur gedruckt.
     */
    private val pinchPairs = listOf(Triple(0, 7, 3), Triple(5, 5, 4), Triple(12, 3, 6))
    private class PinchRow(val pitch: Double, val centroid: Double, val upper: Double, val fretted: Double, val atOvertone: Double)
    private fun upperRe(sp: Qa19Analysis.Spectrum, fh: Double): Double { var u = 0.0; for (k in 2..8) if (fh * k < 16_000) u += sp.shareAt(fh * k); return 10 * log10(u.coerceAtLeast(1e-12) / sp.shareAt(fh).coerceAtLeast(1e-12)) }
    private fun pinchRow(x: FloatArray, fh: Double, fretted: Double): PinchRow {
        val sp = Qa19Analysis.spectrum(x, sr, sr * 3 / 100, 13)
        return PinchRow(Qa19Analysis.pitchHz(x, sr, sr / 10, 60.0, 2500.0), sp.centroid(), upperRe(sp, fh), sp.shareAt(fretted) + sp.shareAt(2 * fretted), sp.shareAt(fh))
    }
    private fun harmonic(preset: String, fret: Int, kind: HarmonicKind, bank: SampleBank) = render(project(preset, listOf(listOf(beat(Fraction.ZERO, 2, note(5, fret, 96, Articulation.Harmonic(kind)))))), 1.2, bank).l

    @Test fun probe_pinchHighE() {
        val bank = bankOrSkip()
        for (preset in presets) {
            for ((pf, nf, k) in pinchPairs) {
                val fh = Dsp.midiToHz(64.0) * k; val ff = Dsp.midiToHz(64.0 + pf)
                val p = pinchRow(harmonic(preset, pf, HarmonicKind.PINCH, bank), fh, ff); val nh = pinchRow(harmonic(preset, nf, HarmonicKind.NATURAL, bank), fh, ff)
                println("Q22P ${short(preset)} Pinch@$pf / NH$nf (${f1(fh)} Hz): Schwerpunkt ${f1(p.centroid)} / ${f1(nh.centroid)} Hz (x${f2(p.centroid / nh.centroid)}) | Obertöne 2-8 re Oberton ${f1(p.upper)} / ${f1(nh.upper)} dB | am Oberton ${f2(p.atOvertone * 100)} / ${f2(nh.atOvertone * 100)} % | gegriffen 1+2 ${f2(p.fretted * 100)} / ${f2(nh.fretted * 100)} % | Tonhöhe ${f1(p.pitch)} / ${f1(nh.pitch)} Hz")
            }
            val nh12 = harmonic(preset, 12, HarmonicKind.NATURAL, bank); val s12 = Qa19Analysis.spectrum(nh12, sr, sr * 3 / 100, 13)
            println("Q22P ${short(preset)} NH12 (659 Hz): Schwerpunkt ${f1(s12.centroid())} Hz | Obertöne 2-8 re Oberton ${f1(upperRe(s12, Dsp.midiToHz(76.0)))} dB | Tonhöhe ${f1(Qa19Analysis.pitchHz(nh12, sr, sr / 10, 60.0, 2500.0))} Hz")
        }
    }

    /**
     * (2) Pinch gegen Natural Harmonic gleicher Tonhöhe auf der hohen E-Saite, jedes Preset (Ziel des Auftrags: Crunch und High Gain):
     * - der Pinch klingt am Oberton, nicht am gegriffenen Ton: Tonhöhe 3 × gegriffen ± 25 Cent (YIN bei 2 kHz: Lag 24 Frames, ±0,1 Frame
     *   ≈ ±7 Cent) und die Teiltöne 1+2 des gegriffenen Tons tragen höchstens 5 % der Leistung (der Pinch-Oberton dominiert);
     * - deutlich anderer Teiltonanteil: die Vielfachen 2–8 des Obertons liegen relativ zum Oberton mindestens 3 dB (doppelte Leistung)
     *   über denen des Natural Harmonic (Pinch: Plektrum am Steg, Teiltöne ~1/k; Natural: ~1/k²);
     * - Schwerpunkt mindestens 1,2 × der des Natural Harmonic, wo das 2. Vielfache des Obertons noch unter dem Box-Abfall liegt
     *   (Oberton < 1,5 kHz: Bund 0 und 5). In High Gain gibt die Sättigung schon dem reinen NH7 (989 Hz) Obertöne −7 dB re Oberton,
     *   sein Schwerpunkt (≈ 1340 Hz) hängt nicht von seinem Pegel ab (gemessen bei −6 und −12 dB); der Pinch erreicht dort ×1,23.
     * Auf ef25d88 rot: Schwerpunkt Crunch/HG Bund 0 ×1,04/×1,01, Obertöne +3,0/+0,2 dB; Bund 12 klang in allen Presets am gegriffenen
     * E5 (662 Hz, Teiltöne 1+2 12–29 %), weil der 3. Teilton der hohen DI-Samples 20–33 dB unter dem Grundton liegt.
     */
    @Test fun pinchDiffersFromTheNaturalHarmonicOnTheHighE() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        for (preset in presets) for ((pf, nf, k) in pinchPairs) {
            val fh = Dsp.midiToHz(64.0) * k; val ff = Dsp.midiToHz(64.0 + pf)
            val p = pinchRow(harmonic(preset, pf, HarmonicKind.PINCH, bank), fh, ff); val nh = pinchRow(harmonic(preset, nf, HarmonicKind.NATURAL, bank), fh, ff)
            val tag = "${short(preset)} Pinch@$pf/NH$nf"
            check(failures, abs(Qa19Analysis.cents(p.pitch, 3 * ff)) <= 25) { "$tag: Tonhöhe ${f1(p.pitch)} Hz, erwartet ${f1(3 * ff)}" }
            check(failures, p.fretted <= 0.05) { "$tag: gegriffener Ton trägt ${f2(p.fretted * 100)} % (Soll ≤ 5 %)" }
            check(failures, p.upper >= nh.upper + 3.0) { "$tag: Obertöne ${f1(p.upper)} vs. ${f1(nh.upper)} dB re Oberton (Soll ≥ +3 dB)" }
            if (fh < 1500.0) check(failures, p.centroid >= 1.2 * nh.centroid) { "$tag: Schwerpunkt ${f1(p.centroid)} vs. ${f1(nh.centroid)} Hz (Soll ≥ 1,2 ×)" }
        }
        report("Pinch hohe E-Saite", failures)
    }

    /**
     * (2b) Pinch-Stems ohne Limiter clippen nicht: Pinch auf der leeren E2-, D3- und E4-Saite und auf Bund 12 der E4-Saite, v127,
     * jedes Preset, als Stem (OfflineMixdown, Spur solo, Werks-Mix 0,8) unter 0 dBFS und ohne geclippte Samples. Rot nach den
     * ersten Pinch- und Trim-Commits (d1d8fb8): die auf den Oberton normierte Neigung fügte Energie hinzu und der Kamm lag in den
     * ersten 6 ms auf dem vollen Ton: Clean E2/D3/E4/E4-Bund-12 v127 +2,6/+3,6/+1,0/+3,0 dBFS (24/42/4/64 geclippte Samples).
     */
    @Test fun pinchStemsDoNotClip() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        for (preset in presets) for ((s, fret) in listOf(0 to 0, 2 to 0, 5 to 0, 5 to 12)) {
            val p = project(preset, listOf(listOf(beat(Fraction.ZERO, 2, note(s, fret, 127, Articulation.Harmonic(HarmonicKind.PINCH))))))
            val stem = (OfflineMixdown(bank = bank).render(p, RenderOptions(sampleRate = sr, bitDepth = 16, tailSeconds = 0.5), { _, _, _ -> }, soloTrack = 0) as Outcome.Success).value
            val picked = (OfflineMixdown(bank = bank).render(project(preset, listOf(listOf(beat(Fraction.ZERO, 2, note(s, fret, 127))))), RenderOptions(sampleRate = sr, bitDepth = 16, tailSeconds = 0.5), { _, _, _ -> }, soloTrack = 0) as Outcome.Success).value
            println("Q22S ${short(preset)} Pinch Saite $s Bund $fret v127: Stem-Spitze ${f1(db(stem.peak))} dBFS (${stem.clippedSamples} geclippt) | gepickt ${f1(db(picked.peak))} dBFS")
            check(failures, stem.clippedSamples == 0L && stem.peak < 1.0) { "${short(preset)} Pinch Saite $s Bund $fret v127: Stem-Spitze ${f1(db(stem.peak))} dBFS, ${stem.clippedSamples} geclippt" }
        }
        report("Pinch-Stems", failures)
    }

    // ================================================================== 3. Preset-Lautheit (ITU-R BS.1770-4)

    /** Referenzphrase, 4 Takte 120 BPM, v96: offene Akkorde (E, A), Single Notes (Achtel D–E-Saite), PM-Riff mit Powerchords, Powerchords. */
    private fun referencePhrase(preset: String): Project {
        fun chord(o: Fraction, den: Int, vararg sf: Pair<Int, Int>) = beat(o, den, *sf.map { (s, f) -> note(s, f, 96) }.toTypedArray())
        val eMajor = arrayOf(0 to 0, 1 to 2, 2 to 2, 3 to 1, 4 to 0, 5 to 0)
        val aMajor = arrayOf(1 to 0, 2 to 2, 3 to 2, 4 to 2, 5 to 0)
        val bar1 = listOf(chord(Fraction.ZERO, 4, *eMajor), chord(Fraction.of(1, 4), 8, *eMajor), chord(Fraction.of(3, 8), 8, *eMajor), chord(Fraction.of(1, 2), 2, *aMajor))
        val line = listOf(2 to 7, 2 to 9, 3 to 7, 3 to 9, 4 to 7, 4 to 8, 4 to 10, 5 to 7)
        val bar2 = line.mapIndexed { k, (s, f) -> beat(Fraction.of(k.toLong(), 8), 8, note(s, f, 96)) }
        fun power(o: Fraction, den: Int, fret: Int) = beat(o, den, note(0, fret, 96), note(1, fret + 2, 96), note(2, fret + 2, 96))
        val bar3 = (0 until 16).mapNotNull { k -> when (k) { 3, 11 -> power(Fraction.of(k.toLong(), 16), 8, if (k == 3) 3 else 5); 4, 12 -> null; else -> beat(Fraction.of(k.toLong(), 16), 16, note(0, 0, 96, pmMark)) } }
        val bar4 = listOf(power(Fraction.ZERO, 4, 0), power(Fraction.of(1, 4), 4, 3), power(Fraction.of(1, 2), 2, 5))
        return project(preset, listOf(bar1, bar2, bar3, bar4))
    }
    private class Loudness(val lufs: Double, val preLufs: Double, val prePeakDb: Double, val limiterShare: Double, val clipped: Int, val stemPeakDb: Double, val stemClipped: Long)
    private fun loudness(preset: String, bank: SampleBank): Loudness {
        val p = referencePhrase(preset)
        val o = render(p, 8.5, bank)
        val pre = render(p, 8.5, bank, master = false)
        var pk = 0.0; for (i in pre.l.indices) pk = maxOf(pk, abs(pre.l[i].toDouble()), abs(pre.r[i].toDouble()))
        val g = Qa18GainStructureTest.limiterGain(FloatArray(pre.l.size) { maxOf(abs(pre.l[it]), abs(pre.r[it])) })
        var engaged = 0; for (x in g) if (x < 0.944f) engaged++
        var clipped = 0; for (i in o.l.indices) if (abs(o.l[i]) >= 1f || abs(o.r[i]) >= 1f) clipped++
        val stem = (OfflineMixdown(bank = bank).render(p, RenderOptions(sampleRate = sr, bitDepth = 16, tailSeconds = 0.5), { _, _, _ -> }, soloTrack = 0) as Outcome.Success).value
        return Loudness(Qa22Analysis.integratedLufs(listOf(o.l, o.r), sr), Qa22Analysis.integratedLufs(listOf(pre.l, pre.r), sr), db(pk), 100.0 * engaged / g.size, clipped, db(stem.peak), stem.clippedSamples)
    }

    @Test fun probe_presetLoudness() {
        val bank = bankOrSkip()
        for (preset in presets) {
            val l = loudness(preset, bank)
            println("Q22L ${short(preset)}: integriert ${f1(l.lufs)} LUFS (vor Limiter ${f1(l.preLufs)}) | Spitze vor Limiter ${f1(l.prePeakDb)} dBFS, Limiter > 0,5 dB ${f2(l.limiterShare)} %, ${l.clipped} Samples ≥ 1,0 | Stem-Spitze ${f1(l.stemPeakDb)} dBFS, ${l.stemClipped} geclippt")
        }
    }

    /**
     * (3) Wahrgenommene Lautheit der drei Presets bei gleicher Spur-Lautstärke (Werks-Mix 0,8): integrierte Lautheit nach
     * BS.1770-4 (K-Gewichtung, 400-ms-Blöcke, Gates −70 LUFS / −10 LU) über die Referenzphrase am Master. Ziel: alle innerhalb
     * 1,5 LU zueinander, ohne Clipping am Ausgang und im Stem (ohne Limiter), Limiter für diese Phrase nie > 0,5 dB.
     * Auf ef25d88 rot: Clean −23,2, Crunch −17,0, High Gain −15,9 LUFS (Spreizung 7,2 LU).
     */
    @Test fun presetLoudnessIsMatchedWithoutClipping() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        val l = presets.associateWith { loudness(it, bank) }
        for ((preset, x) in l) {
            println("Q22L ${short(preset)}: ${f1(x.lufs)} LUFS, Spitze vor Limiter ${f1(x.prePeakDb)} dBFS, Limiter ${f2(x.limiterShare)} %, Stem-Spitze ${f1(x.stemPeakDb)} dBFS")
            check(failures, x.clipped == 0 && x.stemClipped == 0L) { "${short(preset)}: ${x.clipped} Samples ≥ 1,0 am Ausgang, ${x.stemClipped} im Stem" }
            check(failures, x.limiterShare == 0.0) { "${short(preset)}: Limiter > 0,5 dB für ${f2(x.limiterShare)} % der Phrase" }
        }
        val spread = l.values.maxOf { it.lufs } - l.values.minOf { it.lufs }
        println("Q22L Spreizung ${f2(spread)} LU")
        check(failures, spread <= 1.5) { "Lautheit " + l.entries.joinToString { "${short(it.key)} ${f1(it.value.lufs)}" } + " LUFS: Spreizung ${f1(spread)} LU (Soll ≤ 1,5)" }
        report("Preset-Lautheit", failures)
    }

    /**
     * (3b) Die Ausgangstrims ändern nur den Pegel: der Klangcharakter aus Qa19 (Werte auf ef25d88: Schwerpunkt A3+E2 v96
     * 462/519/1445 Hz, THD 1,1/20,8/24,3 %, Dynamik v40→v127 12,8/8,5/1,4 dB, Ausklang nach Note-off bis −50 dB 192/125/67 ms)
     * bleibt in denselben Bereichen: Schwerpunkt ±10 %, THD ±15 % (relativ), Dynamik ±1 dB, Ausklang ±20 %.
     */
    @Test fun presetCharacterStaysInItsRange() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        val ref = mapOf("rw.guitar.clean" to doubleArrayOf(462.0, 1.1, 12.8, 192.0), "rw.guitar.crunch" to doubleArrayOf(519.0, 20.8, 8.5, 125.0), "rw.guitar.highgain" to doubleArrayOf(1445.0, 24.3, 1.4, 67.0))
        for (preset in presets) {
            var c = 0.0
            for ((s, fr) in listOf(2 to 7, 0 to 0)) c += Qa19Analysis.spectrum(render(project(preset, listOf(listOf(beat(Fraction.ZERO, 2, note(s, fr, 96))))), 1.0, bank).l, sr, sr * 3 / 100, 13).centroid()
            val centroid = c / 2
            val lvl = Qa19Analysis.peak(dry(preset, project(preset, listOf(listOf(beat(Fraction.ZERO, 2, note(2, 7, 96))))), 1.0, bank), 0, sr / 2)
            var sine = FloatArray(sr) { (lvl * kotlin.math.sin(2 * Math.PI * 220 * it / sr)).toFloat() }
            for (u in TonePresets.find(preset)!!.defaultChain) sine = Qa18GainStructureTest.run(Effects.create(u, sr, block)!!, sine)
            val thd = Qa19Analysis.thdPercent(sine, sr, 220.0)
            fun level(v: Int) = db(Qa19Analysis.rms(render(project(preset, listOf(listOf(beat(Fraction.ZERO, 2, note(2, 7, v))))), 1.0, bank).l, 0, sr * 8 / 10))
            val dyn = level(127) - level(40)
            var t = 0.0
            for ((notes, off) in listOf(listOf(beat(Fraction.ZERO, 8, note(0, 0, 96, pmMark))) to sr / 4, listOf(beat(Fraction.ZERO, 8, note(0, 0, 96, Articulation.Mark(NoteMark.STACCATO)))) to sr / 8,
                    listOf(beat(Fraction.ZERO, 8, note(2, 7, 96, pmMark))) to sr / 4)) {
                val x = render(project(preset, listOf(notes)), 1.5, bank).l
                t += Qa19Analysis.timeBelowMs(x, sr, db(Qa19Analysis.peak(x)) - 50, off, 2.0) ?: 1500.0
            }
            val tail = t / 3
            val r = ref[preset]!!
            println("Q22D ${short(preset)}: Schwerpunkt ${f1(centroid)} (${f1(r[0])}) Hz | THD ${f1(thd)} (${f1(r[1])}) % | Dynamik ${f1(dyn)} (${f1(r[2])}) dB | Ausklang ${f1(tail)} (${f1(r[3])}) ms")
            check(failures, abs(centroid / r[0] - 1) <= 0.10) { "${short(preset)}: Schwerpunkt ${f1(centroid)} Hz außerhalb ${f1(r[0])} ± 10 %" }
            check(failures, abs(thd / r[1] - 1) <= 0.15) { "${short(preset)}: THD ${f1(thd)} % außerhalb ${f1(r[1])} ± 15 %" }
            check(failures, abs(dyn - r[2]) <= 1.0) { "${short(preset)}: Dynamik ${f1(dyn)} dB außerhalb ${f1(r[2])} ± 1 dB" }
            check(failures, abs(tail / r[3] - 1) <= 0.20) { "${short(preset)}: Ausklang ${f1(tail)} ms außerhalb ${f1(r[3])} ± 20 %" }
        }
        report("Preset-Charakter", failures)
    }

    /**
     * Selbstprüfung der K-Gewichtung und des Gatings: Koeffizienten bei 48 kHz gleich den Tabellen 1 und 2 der BS.1770-4 (1e-6);
     * ein 1-kHz-Sinus −20 dBFS (Spitze) nur links ergibt −23,0 LUFS (±0,1; 0 dBFS Sinus in einem Kanal = −3,01 LKFS); Stille hinter dem
     * Signal ändert den integrierten Wert nicht (absolutes Gate), ein 30 dB leiserer Teil auch nicht (relatives Gate); ±0,2 dB, weil die
     * drei Blöcke über der Grenze teilweise gefüllt sind.
     */
    @Test fun kWeightingAndGatingFollowBs1770() {
        val k = Qa22Analysis.kWeighting(48_000)
        val expected = listOf(doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585),
            doubleArrayOf(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621))
        val failures = mutableListOf<String>()
        for (i in 0..1) { val s = k[i]; val got = doubleArrayOf(s.b0, s.b1, s.b2, s.a1, s.a2); for (j in 0..4) check(failures, abs(got[j] - expected[i][j]) < 1e-6) { "Stufe ${i + 1} Koeffizient $j: ${got[j]} statt ${expected[i][j]}" } }
        fun sine(seconds: Double, peak: Double) = FloatArray((seconds * sr).toInt()) { (peak * kotlin.math.sin(2 * Math.PI * 1000 * it / sr)).toFloat() }
        val a = sine(5.0, 0.1)
        val silent = FloatArray(a.size)
        val lone = Qa22Analysis.integratedLufs(listOf(a, silent), sr)
        val withSilence = Qa22Analysis.integratedLufs(listOf(a + FloatArray(5 * sr), FloatArray(10 * sr)), sr)
        val withQuiet = Qa22Analysis.integratedLufs(listOf(a + sine(5.0, 0.1 / 31.6), FloatArray(10 * sr)), sr)
        println("Q22K 1 kHz −20 dBFS links: ${f2(lone)} LUFS | mit 5 s Stille ${f2(withSilence)} | mit 5 s −30 dB ${f2(withQuiet)} LUFS")
        check(failures, abs(lone + 23.01) <= 0.1) { "1-kHz-Sinus: ${f2(lone)} LUFS statt −23,0" }
        check(failures, abs(withSilence - lone) <= 0.2) { "absolutes Gate: ${f2(withSilence)} vs. ${f2(lone)}" }
        check(failures, abs(withQuiet - lone) <= 0.2) { "relatives Gate: ${f2(withQuiet)} vs. ${f2(lone)}" }
        report("BS.1770", failures)
    }
}
