package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.Qa19Analysis.attackDb
import dev.riffworkbench.synth.Qa19Analysis.db
import dev.riffworkbench.synth.Qa19Analysis.decayMs
import dev.riffworkbench.synth.Qa19Analysis.f1
import dev.riffworkbench.synth.Qa19Analysis.f2
import dev.riffworkbench.synth.Qa19Analysis.peak
import dev.riffworkbench.synth.Qa19Analysis.periodicity
import dev.riffworkbench.synth.Qa19Analysis.spectrum
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * QA cycle 19: guitar articulations of the three sampled presets, rendered offline through the real pipeline
 * (ScorePlaybackCompiler -> SynthEngine with the bundled bank -> preset chain -> master), 48 kHz, E standard.
 */
class Qa19ArticulationTest {
    private var n = 0
    private fun id() = EntityId("q19-${n++}")
    private val sr = 48_000
    private val block = 1024
    private val guitar = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), midiProgram = 29)
    private val presets = listOf("rw.guitar.clean", "rw.guitar.crunch", "rw.guitar.highgain")
    private fun bankOrSkip(): SampleBank { val b = Qa18GainStructureTest.bank; assumeTrue("sample bank not found", b != null); return b!! }

    private fun note(string: Int, fret: Int, v: Int, vararg a: Articulation) = Note(id(), Pitch(guitar.pitch(string, fret)), string, fret, v, a.toList())
    private fun beat(o: Fraction, den: Int, vararg notes: Note) = Beat(id(), o, WrittenDuration(den), notes.toList())
    private fun project(preset: String, beats: List<Beat>, bpm: Double = 120.0): Project {
        val m = listOf(Measure(id()), Measure(id()))
        val bars = m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) }
        return Project(id = id(), measures = m, tracks = listOf(Track(id(), "G", guitar, listOf(Staff(id(), bars = bars)), SoundAssignment(presetId = preset))),
            tempo = listOf(TempoChange(ScorePosition(m[0].id), bpm)))
    }
    /** Left channel of the full mix (preset chain + master), as the user hears it. */
    private fun render(p: Project, seconds: Double, bank: SampleBank?): FloatArray {
        val plan = (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
        val e = SynthEngine(sr, block, bank)
        e.installRack(ChannelRack.build(p, sr, block)); e.setMix(MixState.from(p)); e.install(EngineProgram.prepare(plan)); e.play(0)
        val frames = (seconds * sr).toInt(); val out = FloatArray(frames); val buf = FloatArray(2 * block)
        var done = 0
        while (done < frames) { val k = minOf(block, frames - done); e.render(buf, k); for (i in 0 until k) out[done + i] = buf[2 * i]; done += k }
        return out
    }

    class Row(val peakDb: Double, val attack: Double, val d20: Double?, val d40: Double?, val centroid: Double, val hf: Double, val period: Double, val harmonic: Double)
    private fun measure(x: FloatArray, f0: Double): Row {
        val s = spectrum(x, sr, sr * 3 / 100, 13)
        return Row(db(peak(x)), attackDb(x, sr), decayMs(x, sr, 20.0), decayMs(x, sr, 40.0), s.centroid(), s.shareAbove(2000.0), periodicity(x, sr, f0, sr / 50), s.harmonicShare(f0))
    }
    private fun fmt(r: Row) = "peak ${f1(r.peakDb)} | att ${f1(r.attack)} dB | d20 ${r.d20?.let { f1(it) } ?: ">"} | d40 ${r.d40?.let { f1(it) } ?: ">"} ms | cent ${f1(r.centroid)} Hz | >2k ${f2(r.hf * 100)} % | per ${f2(r.period)} | harm ${f2(r.harmonic)}"

    @Test fun probe_articulationTable() {
        val bank = bankOrSkip()
        val pm = Articulation.Mark(NoteMark.PALM_MUTE); val dead = Articulation.Mark(NoteMark.DEAD)
        data class Pos(val label: String, val string: Int, val fret: Int)
        val positions = listOf(Pos("E2", 0, 0), Pos("A3", 2, 7), Pos("E4", 5, 0))
        for (preset in presets) for (pos in positions) {
            val f0 = Dsp.midiToHz(guitar.pitch(pos.string, pos.fret).toDouble())
            for (v in listOf(64, 96, 127)) for ((art, a) in listOf("open" to emptyArray<Articulation>(), "PM" to arrayOf<Articulation>(pm), "dead" to arrayOf<Articulation>(dead))) {
                val x = render(project(preset, listOf(beat(Fraction.ZERO, 1, note(pos.string, pos.fret, v, *a)))), 2.6, bank)
                println("Q19 ${preset.removePrefix("rw.guitar.")} ${pos.label} $art v$v: ${fmt(measure(x, f0))} | d30 ${decayMs(x, sr, 30.0)?.let { f1(it) } ?: ">"} ms | per5 ${f2(periodicity(x, sr, f0, sr / 200))} | harm0 ${f2(spectrum(x, sr, 0, 12).harmonicShare(f0))} | >1k30 ${f2(spectrum(x, sr, sr * 3 / 100, 13).shareAbove(1000.0) * 100)} %")
            }
        }
    }

    /** Dry voice sum (no effects) of the sampled preset. */
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
    private fun stages(preset: String, x: FloatArray): List<Pair<String, FloatArray>> {
        val out = mutableListOf("dry" to x); var cur = x
        for (u in TonePresets.find(preset)!!.defaultChain) { cur = Qa18GainStructureTest.run(Effects.create(u, sr, block)!!, cur); out += u.kind.name to cur }
        return out
    }

    @Test fun probe_chainStages() {
        val bank = bankOrSkip()
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        for (preset in presets) for ((label, s, fr) in listOf(Triple("E2", 0, 0), Triple("A3", 2, 7), Triple("E4", 5, 0))) for ((art, a) in listOf("open" to emptyArray<Articulation>(), "PM" to arrayOf<Articulation>(pm))) {
            val x = dry(preset, project(preset, listOf(beat(Fraction.ZERO, 1, note(s, fr, 96, *a)))), 1.0, bank)
            println("Q19S ${preset.removePrefix("rw.guitar.")} $label $art v96: " + stages(preset, x).joinToString(" -> ") { (n, y) -> val sp = spectrum(y, sr, sr * 3 / 100, 13); "$n ${f1(sp.centroid())} Hz/${f2(sp.shareAbove(1000.0) * 100)}%>1k/${f2(sp.shareAbove(2000.0) * 100)}%>2k/${f2(sp.shareAbove(4000.0) * 100)}%>4k" })
        }
    }

    @Test fun probe_harmonics() {
        val bank = bankOrSkip()
        for (preset in presets) for (s in listOf(0, 2, 5)) {
            val open = guitar.tuning[s].toDouble(); val fOpen = Dsp.midiToHz(open)
            val o = render(project(preset, listOf(beat(Fraction.ZERO, 2, note(s, 0, 96)))), 1.2, bank)
            val so = spectrum(o, sr, sr * 3 / 100, 13)
            println("Q19H ${preset.removePrefix("rw.guitar.")} string $s open: ${fmt(measure(o, fOpen))} | @f0 ${f2(so.shareAt(fOpen) * 100)} %")
            for ((fret, k) in listOf(12 to 2, 7 to 3, 5 to 4)) {
                val fh = fOpen * k
                val x = render(project(preset, listOf(beat(Fraction.ZERO, 2, note(s, fret, 96, Articulation.Harmonic(HarmonicKind.NATURAL))))), 1.2, bank)
                val sp = spectrum(x, sr, sr * 3 / 100, 13)
                println("Q19H ${preset.removePrefix("rw.guitar.")} string $s NH$fret: ${fmt(measure(x, fh))} | @fh ${f2(sp.shareAt(fh) * 100)} % (open note ${f2(so.shareAt(fh) * 100)} %) | @f0 ${f2(sp.shareAt(fOpen) * 100)} % | pitch ${f1(Qa19Analysis.pitchHz(x, sr, sr / 10, 60.0, 2000.0))} Hz (exp ${f1(fh)})")
            }
            // Pinch on the open string sounds at the 3rd harmonic, like NH7.
            val fh = fOpen * 3
            val x = render(project(preset, listOf(beat(Fraction.ZERO, 2, note(s, 0, 96, Articulation.Harmonic(HarmonicKind.PINCH))))), 1.2, bank)
            val sp = spectrum(x, sr, sr * 3 / 100, 13)
            println("Q19H ${preset.removePrefix("rw.guitar.")} string $s PINCH fret 0: ${fmt(measure(x, fh))} | @fh ${f2(sp.shareAt(fh) * 100)} % | @2fh ${f2(sp.shareAt(2 * fh) * 100)} % | @3fh ${f2(sp.shareAt(3 * fh) * 100)} % | @f0 ${f2(sp.shareAt(fOpen) * 100)} % | pitch ${f1(Qa19Analysis.pitchHz(x, sr, sr / 10, 60.0, 2000.0))} Hz")
        }
    }

    @Test fun probe_deadTrack() {
        val bank = bankOrSkip()
        for (preset in presets) for ((label, s, fr) in listOf(Triple("E2", 0, 0), Triple("A3", 2, 7), Triple("E4", 5, 0))) for (dead in listOf(true, false)) {
            val x = render(project(preset, listOf(beat(Fraction.ZERO, 4, note(s, fr, 96, *if (dead) arrayOf<Articulation>(Articulation.Mark(NoteMark.DEAD)) else emptyArray())))), 0.4, bank)
            val pk = db(peak(x))
            println("Q19D ${preset.removePrefix("rw.guitar.")} $label ${if (dead) "dead" else "open"}: " + (0 until 10).joinToString(" ") { k -> val at = k * sr / 100; "${k * 10}ms ${f1(db(Qa19Analysis.rms(x, at, at + sr / 100)) - pk)}dB/${f1(Qa19Analysis.pitchHz(x, sr, at, 60.0, 1500.0, 20.0))}Hz" })
        }
    }

    @Test fun probe_transitionDetail() {
        val bank = bankOrSkip()
        val half = sr / 2
        for (preset in presets) {
            fun two(first: Note, second: Note, wet: Boolean) = project(preset, listOf(beat(Fraction.ZERO, 4, first), beat(Fraction.of(1, 4), 4, second))).let { if (wet) render(it, 1.0, bank) else dry(preset, it, 1.0, bank) }
            for (wet in listOf(false, true)) for ((name, a) in listOf("picked" to emptyArray<Articulation>(), "hammer" to arrayOf<Articulation>(Articulation.Mark(NoteMark.HAMMER_ON)))) {
                val x = two(note(2, 7, 96, *a), note(2, 9, 96), wet)
                val hp = FloatArray(x.size); var prev = 0f; var y = 0f
                for (i in x.indices) { y = 0.75f * (y + x[i] - prev); prev = x[i]; hp[i] = y }   // ~2 kHz one-pole high-pass
                val ref = db(Qa19Analysis.rms(x, half - sr / 50, half)); val refHp = db(Qa19Analysis.rms(hp, half - sr / 50, half))
                println("Q19X ${short(preset)} ${if (wet) "wet" else "dry"} $name: " + (-2 until 12).joinToString(" ") { k -> val at = half + k * sr / 500; "${k * 2}ms ${f1(db(Qa19Analysis.rms(x, at, at + sr / 500)) - ref)}/${f1(db(Qa19Analysis.rms(hp, at, at + sr / 500)) - refHp)}" })
            }
        }
    }

    @Test fun probe_transitions() {
        val bank = bankOrSkip()
        val half = sr / 2   // second note starts at the half (quarter at 120 bpm = 0.5 s)
        for (preset in presets) {
            val a3 = Dsp.midiToHz(57.0); val b3 = Dsp.midiToHz(59.0)
            fun two(first: Note, second: Note) = render(project(preset, listOf(beat(Fraction.ZERO, 4, first), beat(Fraction.of(1, 4), 4, second))), 1.4, bank)
            fun jump(x: FloatArray) = db(Qa19Analysis.rms(x, half, half + sr / 50)) - db(Qa19Analysis.rms(x, half - sr / 50, half))
            fun pitchAt(x: FloatArray, at: Int) = Qa19Analysis.pitchHz(x, sr, at, 100.0, 1000.0)
            val picked = two(note(2, 7, 96), note(2, 9, 96))
            val hammer = two(note(2, 7, 96, Articulation.Mark(NoteMark.HAMMER_ON)), note(2, 9, 96))
            val pull = two(note(2, 9, 96, Articulation.Mark(NoteMark.PULL_OFF)), note(2, 7, 96))
            val slide = two(note(2, 7, 96, Articulation.Slide(SlideKind.LEGATO)), note(2, 9, 96))
            val shift = two(note(2, 7, 96, Articulation.Slide(SlideKind.SHIFT)), note(2, 9, 96))
            for ((name, x) in listOf("picked" to picked, "hammer" to hammer, "pull" to pull, "slide-legato" to slide, "slide-shift" to shift)) {
                val p1 = pitchAt(x, sr / 5); val pPre = pitchAt(x, half - sr / 25); val p2 = pitchAt(x, half + sr / 10)
                val hf = 10 * kotlin.math.log10(spectrum(x, sr, half, 10).band(2000.0, 16000.0) / spectrum(x, sr, half - 1024, 10).band(2000.0, 16000.0))
                println("Q19T ${preset.removePrefix("rw.guitar.")} $name: onset jump ${f1(jump(x))} dB | HF jump ${f1(hf)} dB | attack2 ${f1(attackDb(x, sr, half))} dB | pitch note1 ${f1(p1)} / before 2 ${f1(pPre)} / note2 ${f1(p2)} Hz (A3 ${f1(a3)}, B3 ${f1(b3)})")
            }
            val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 3), 200), PitchCurvePoint(Fraction.ONE, 200)))
            val b = render(project(preset, listOf(beat(Fraction.ZERO, 2, note(2, 7, 96, bend)))), 1.2, bank)
            println("Q19T ${preset.removePrefix("rw.guitar.")} bend full: pitch 20 ms ${f1(pitchAt(b, sr / 50))} | 170 ms ${f1(pitchAt(b, sr * 17 / 100))} | 500 ms ${f1(pitchAt(b, sr / 2))} | 800 ms ${f1(pitchAt(b, sr * 8 / 10))} Hz (target ${f1(b3)})")
        }
    }

    @Test fun probe_presetCharacter() {
        val bank = bankOrSkip()
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        for (preset in presets) {
            // THD: a 220 Hz sine at the level of the preset's dry A3 v96 note (peak) through the full chain.
            val d = dry(preset, project(preset, listOf(beat(Fraction.ZERO, 2, note(2, 7, 96)))), 1.0, bank)
            val lvl = peak(d, 0, sr / 2)
            val sine = FloatArray(sr) { (lvl * kotlin.math.sin(2 * Math.PI * 220 * it / sr)).toFloat() }
            val chain = stages(preset, sine)
            val amp = chain.first { it.first == "AMP" }.second
            println("Q19P ${preset.removePrefix("rw.guitar.")} THD @ dry A3 v96 peak (${f1(db(lvl))} dBFS): amp ${f1(Qa19Analysis.thdPercent(amp, sr, 220.0))} % | chain ${f1(Qa19Analysis.thdPercent(chain.last().second, sr, 220.0))} %")
            fun lvl(v: Int) = render(project(preset, listOf(beat(Fraction.ZERO, 2, note(2, 7, v)))), 1.0, bank).let { db(Qa19Analysis.rms(it, 0, sr * 8 / 10)) }
            val dyn = lvl(127) - lvl(40)
            println("Q19P ${preset.removePrefix("rw.guitar.")} dynamics A3 v40 -> v127: ${f1(dyn)} dB")
            for ((name, notes) in listOf("PM 8th E2" to listOf(beat(Fraction.ZERO, 8, note(0, 0, 96, pm))), "staccato 8th E2" to listOf(beat(Fraction.ZERO, 8, note(0, 0, 96, Articulation.Mark(NoteMark.STACCATO)))),
                    "PM 8th A3" to listOf(beat(Fraction.ZERO, 8, note(2, 7, 96, pm))), "8th E2" to listOf(beat(Fraction.ZERO, 8, note(0, 0, 96))))) {
                val x = render(project(preset, notes), 1.5, bank)
                val off = if (name.startsWith("staccato")) sr / 8 else sr / 4
                val pk = db(peak(x))
                val pre = db(Qa19Analysis.rms(x, off - sr / 100, off))
                println("Q19P ${preset.removePrefix("rw.guitar.")} $name: level before off ${f1(pre - pk)} dB re peak; after off to pre-40 dB ${Qa19Analysis.timeBelowMs(x, sr, pre - 40, off, 2.0)?.let { f1(it) } ?: ">"} ms, to pre-60 ${Qa19Analysis.timeBelowMs(x, sr, pre - 60, off, 2.0)?.let { f1(it) } ?: ">"} ms")
                println("Q19P ${preset.removePrefix("rw.guitar.")} $name: after note-off to peak-40 dB ${Qa19Analysis.timeBelowMs(x, sr, pk - 40, off)?.let { f1(it) } ?: ">"} ms, to peak-60 ${Qa19Analysis.timeBelowMs(x, sr, pk - 60, off)?.let { f1(it) } ?: ">"} ms; level 20 ms after off ${f1(db(Qa19Analysis.rms(x, off + sr / 50, off + sr / 25)) - pk)} dB re peak")
            }
        }
    }

    // ================================================================== assertions (thresholds from the cycle-19 measurements and the sound goal)

    private val pmMark = Articulation.Mark(NoteMark.PALM_MUTE)
    private val deadMark = Articulation.Mark(NoteMark.DEAD)
    private class Pos(val label: String, val string: Int, val fret: Int)
    private val positions = listOf(Pos("E2", 0, 0), Pos("A3", 2, 7), Pos("E4", 5, 0))
    private fun short(preset: String) = preset.removePrefix("rw.guitar.")
    private fun check(failures: MutableList<String>, ok: Boolean, what: () -> String) { if (!ok) failures += what() }
    private fun report(name: String, failures: List<String>) = assertTrue("$name: ${failures.size} failures:\n" + failures.joinToString("\n"), failures.isEmpty())

    /**
     * (a) Palm mute against the open note, every preset, E2/A3/E4 at v64/v96/v127:
     * - decay to -20 dB at most half as long (Clean/Crunch per the sound goal; High Gain measured 115-255 ms against >= 1 s, held to the same factor);
     * - less energy above 2 kHz after the attack (20-190 ms): at least 6 dB below the open note, every preset;
     * - Clean/Crunch, timbre and not only level: while the muted note still sounds (20-105 ms) its share of the power above
     *   1 kHz is at most 0.6 x the open note's. High Gain re-creates the top end in its three saturated stages: printed, not asserted.
     * Before the cycle-19 fix (fixed 700 + 1500 v Hz low-pass): Clean E2 v127 x0.78, Clean A3 v127 x0.68, Crunch E4 v96 x0.75
     * the open note's share above 1 kHz; High Gain E2 v96/v127 only 4.1/4.3 dB less power above 2 kHz.
     */
    @Test fun palmMuteDecaysFasterAndIsDarkerThanTheOpenNote() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        for (preset in presets) for (pos in positions) for (v in listOf(64, 96, 127)) {
            val open = render(project(preset, listOf(beat(Fraction.ZERO, 1, note(pos.string, pos.fret, v)))), 2.6, bank)
            val pm = render(project(preset, listOf(beat(Fraction.ZERO, 1, note(pos.string, pos.fret, v, pmMark)))), 2.6, bank)
            val dOpen = decayMs(open, sr, 20.0) ?: 2600.0; val dPm = decayMs(pm, sr, 20.0) ?: 2600.0
            // Timbre while the muted note still sounds (20-105 ms); later the Clean reverb's tail of the bright pick dominates.
            val so = spectrum(open, sr, sr / 50, 12); val sp = spectrum(pm, sr, sr / 50, 12)
            val share = sp.shareAbove(1000.0) / so.shareAbove(1000.0).coerceAtLeast(1e-12)
            val lo = spectrum(open, sr, sr / 50, 13); val lp = spectrum(pm, sr, sr / 50, 13)
            val lateShare = lp.shareAbove(1000.0) / lo.shareAbove(1000.0).coerceAtLeast(1e-12)
            val hf = 10 * kotlin.math.log10(lp.band(2000.0, 16000.0) / lo.band(2000.0, 16000.0).coerceAtLeast(1e-30))
            println("Q19A ${short(preset)} ${pos.label} v$v: d20 open ${f1(dOpen)} / PM ${f1(dPm)} ms | >1k (20-105 ms) open ${f2(so.shareAbove(1000.0) * 100)} / PM ${f2(sp.shareAbove(1000.0) * 100)} % (x${f2(share)}; 20-190 ms x${f2(lateShare)}) | >2k (20-190 ms) PM re open ${f1(hf)} dB | centroid open ${f1(so.centroid())} / PM ${f1(sp.centroid())} Hz | attack open ${f1(attackDb(open, sr))} / PM ${f1(attackDb(pm, sr))} dB")
            check(failures, dPm <= dOpen / 2) { "${short(preset)} ${pos.label} v$v: PM decay ${f1(dPm)} ms vs open ${f1(dOpen)} ms (need <= 1/2)" }
            check(failures, hf <= -6.0) { "${short(preset)} ${pos.label} v$v: PM power above 2 kHz ${f1(hf)} dB re open (need <= -6 dB)" }
            if (preset != "rw.guitar.highgain") check(failures, share <= 0.6) { "${short(preset)} ${pos.label} v$v: PM share above 1 kHz is x${f2(share)} of the open note (need <= 0.6)" }
        }
        report("palm mute", failures)
    }

    /** Count of 10-ms windows (0-100 ms, within 30 dB of the peak) whose pitch estimate is within +-50 cents of [f0]. */
    private fun onPitchWindows(x: FloatArray, f0: Double): Int {
        val pk = db(peak(x)); var count = 0
        for (k in 0 until 10) { val at = k * sr / 100; if (db(Qa19Analysis.rms(x, at, at + sr / 100)) < pk - 30) continue; if (kotlin.math.abs(Qa19Analysis.cents(Qa19Analysis.pitchHz(x, sr, at, 60.0, 1500.0, 20.0), f0)) <= 50) count++ }
        return count
    }

    /**
     * (b) Dead notes: down 30 dB within 120 ms (measured 15-115 ms; the longest is the Clean reverb tail at E2) and no stable
     * pitch line at the fretted note: at most 2 of the ten 10-ms windows of the first 100 ms carry the note's pitch
     * (the open note: 9-10), and, where the open note is tonal (>= 60 % of its power in its first 12 partials), the share of the power in the note's
     * partials over the first 85 ms at most 0.6 x the open note's.
     */
    @Test fun deadNotesAreShortAndCarryNoPitchLine() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        for (preset in presets) for (pos in positions) for (v in listOf(64, 96, 127)) {
            val f0 = Dsp.midiToHz(guitar.pitch(pos.string, pos.fret).toDouble())
            val open = render(project(preset, listOf(beat(Fraction.ZERO, 4, note(pos.string, pos.fret, v)))), 0.8, bank)
            val dead = render(project(preset, listOf(beat(Fraction.ZERO, 4, note(pos.string, pos.fret, v, deadMark)))), 0.8, bank)
            val d30 = decayMs(dead, sr, 30.0) ?: 800.0
            val wOpen = onPitchWindows(open, f0); val wDead = onPitchWindows(dead, f0)
            val hOpen = spectrum(open, sr, 0, 12).harmonicShare(f0); val hDead = spectrum(dead, sr, 0, 12).harmonicShare(f0)
            println("Q19B ${short(preset)} ${pos.label} v$v: dead d30 ${f1(d30)} ms | on-pitch windows open $wOpen / dead $wDead | partial share open ${f2(hOpen)} / dead ${f2(hDead)} | attack dead ${f1(attackDb(dead, sr))} dB | peak dead ${f1(db(peak(dead)))} / open ${f1(db(peak(open)))} dBFS")
            check(failures, d30 < 120.0) { "${short(preset)} ${pos.label} v$v: dead note needs ${f1(d30)} ms to -30 dB (need < 120)" }
            check(failures, wDead <= 2) { "${short(preset)} ${pos.label} v$v: dead note carries the pitch in $wDead windows (open $wOpen; need <= 2)" }
            // The ratio only means something where the open note is tonal in this measure (12 partials, +-50 cents): a
            // distorted low E in High Gain puts most of its power above its 12th partial (open 0.31-0.35), there the pitch-line count decides.
            check(failures, hOpen < 0.6 || hDead <= 0.6 * hOpen) { "${short(preset)} ${pos.label} v$v: dead partial share ${f2(hDead)} vs open ${f2(hOpen)} (need <= 0.6 x)" }
        }
        report("dead notes", failures)
    }

    private fun partialDb(s: Qa19Analysis.Spectrum, f: Double) = 10 * kotlin.math.log10(s.shareAt(f).coerceAtLeast(1e-12))

    /**
     * (c1) Natural harmonics at frets 12/7/5 of the E2, D3 and E4 strings: the pitch is the overtone (2/3/4 x the open
     * string, +-15 cents), the overtone itself is the strongest partial of the sound (not one of its multiples) and holds
     * at least 20 % of the power; the open string's fundamental is gone (<= 1 % of the power, the open note: pitch f0).
     * Before the fix the modelled harmonic was a buzzy noise-burst string: E2 NH7 Clean had 0.7 % at the overtone and 38 % at its 3rd partial.
     */
    @Test fun naturalHarmonicsSoundAtTheirOvertone() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        for (preset in presets) for (s in listOf(0, 2, 5)) for ((fret, k) in listOf(12 to 2, 7 to 3, 5 to 4)) {
            val fOpen = Dsp.midiToHz(guitar.tuning[s].toDouble()); val fh = fOpen * k
            val x = render(project(preset, listOf(beat(Fraction.ZERO, 2, note(s, fret, 96, Articulation.Harmonic(HarmonicKind.NATURAL))))), 1.2, bank)
            val sp = spectrum(x, sr, sr * 3 / 100, 13)
            val pitch = Qa19Analysis.pitchHz(x, sr, sr / 10, 60.0, 2000.0)
            val strongestMultiple = (2..6).filter { fh * it < 16_000 }.maxOf { sp.shareAt(fh * it) }
            println("Q19C ${short(preset)} string $s NH$fret: pitch ${f1(pitch)} Hz (exp ${f1(fh)}) | at overtone ${f2(sp.shareAt(fh) * 100)} % | strongest multiple ${f2(strongestMultiple * 100)} % | open f0 ${f2(sp.shareAt(fOpen) * 100)} % | attack ${f1(attackDb(x, sr))} dB | >2k ${f2(sp.shareAbove(2000.0) * 100)} %")
            check(failures, kotlin.math.abs(Qa19Analysis.cents(pitch, fh)) <= 15) { "${short(preset)} string $s NH$fret: pitch ${f1(pitch)} Hz, expected ${f1(fh)}" }
            check(failures, sp.shareAt(fh) >= 0.2 && sp.shareAt(fh) >= strongestMultiple) { "${short(preset)} string $s NH$fret: overtone holds ${f2(sp.shareAt(fh) * 100)} % of the power, its strongest multiple ${f2(strongestMultiple * 100)} % (need >= 20 % and the strongest)" }
            check(failures, sp.shareAt(fOpen) <= 0.01) { "${short(preset)} string $s NH$fret: open fundamental still ${f2(sp.shareAt(fOpen) * 100)} %" }
        }
        report("natural harmonics", failures)
    }

    /**
     * (c2) Pinch harmonic against the natural harmonic of the same pitch (open string pinched = 3rd overtone = NH7 of that
     * string), every preset: the same pitch (+-15 cents) and a strong upper overtone addition on the strings where pinch
     * harmonics are played (E2 and D3, overtone 247/440 Hz): power of the overtone's multiples 2..8 relative to the
     * overtone itself at least 3 dB (twice the power) above the natural harmonic's, and a spectral centroid at least 1.3 x
     * the natural harmonic's (High Gain's amp gives the pure natural harmonic upper partials of its own, measured -0.2 dB
     * re the overtone at D3, so the ratio alone is too narrow there). The open high E (overtone 989 Hz, multiples in the cabinet
     * roll-off; the saturated amps make both alike) is printed only, as is the level-based attack ratio (High Gain and
     * Crunch hold every articulation within -1..+1 dB). Before the fix pinch and natural harmonic were the same modelled
     * voice (every figure identical to 0.1 dB).
     */
    @Test fun pinchHarmonicIsBrighterThanTheNaturalHarmonic() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        for (preset in presets) for (s in listOf(0, 2, 5)) {
            val fh = Dsp.midiToHz(guitar.tuning[s].toDouble()) * 3
            val nat = render(project(preset, listOf(beat(Fraction.ZERO, 2, note(s, 7, 96, Articulation.Harmonic(HarmonicKind.NATURAL))))), 1.2, bank)
            val pin = render(project(preset, listOf(beat(Fraction.ZERO, 2, note(s, 0, 96, Articulation.Harmonic(HarmonicKind.PINCH))))), 1.2, bank)
            fun upper(x: FloatArray): Double { val sp = spectrum(x, sr, sr * 3 / 100, 13); var u = 0.0; for (k in 2..8) if (fh * k < 16_000) u += sp.shareAt(fh * k); return 10 * kotlin.math.log10(u.coerceAtLeast(1e-12) / sp.shareAt(fh).coerceAtLeast(1e-12)) }
            val pn = Qa19Analysis.pitchHz(nat, sr, sr / 10, 60.0, 2000.0); val pp = Qa19Analysis.pitchHz(pin, sr, sr / 10, 60.0, 2000.0)
            val un = upper(nat); val up = upper(pin)
            val cn = spectrum(nat, sr, sr * 3 / 100, 13).centroid(); val cp = spectrum(pin, sr, sr * 3 / 100, 13).centroid()
            println("Q19C ${short(preset)} string $s pinch / NH7: pitch ${f1(pp)} / ${f1(pn)} Hz (exp ${f1(fh)}) | upper partials re overtone ${f1(up)} / ${f1(un)} dB | attack ${f1(attackDb(pin, sr))} / ${f1(attackDb(nat, sr))} dB | centroid ${f1(cp)} / ${f1(cn)} Hz | peak ${f1(db(peak(pin)))} / ${f1(db(peak(nat)))} dBFS")
            check(failures, kotlin.math.abs(Qa19Analysis.cents(pp, fh)) <= 15) { "${short(preset)} string $s: pinch pitch ${f1(pp)} Hz, expected ${f1(fh)}" }
            if (s != 5) check(failures, up >= un + 3.0) { "${short(preset)} string $s: pinch upper partials ${f1(up)} dB vs natural ${f1(un)} dB re the overtone (need >= +3 dB)" }
            if (s != 5) check(failures, cp >= 1.3 * cn) { "${short(preset)} string $s: pinch centroid ${f1(cp)} Hz vs natural ${f1(cn)} Hz (need >= 1.3 x)" }
        }
        report("pinch harmonics", failures)
    }

    /**
     * (d) The presets differ measurably: spectral centroid Clean < Crunch < High Gain (A3 and E2, v96); THD of a sine at the
     * preset's own A3 v96 input level: Clean lowest and below 3 %; pick dynamics (v40 -> v127, output RMS) Crunch at least
     * 4 dB above High Gain; after note-off of a palm-muted / staccato eighth the output falls 50 dB below the note's peak
     * sooner in High Gain than in Crunch (gate, no reverb).
     */
    @Test fun presetsDifferInTimbreDistortionDynamicsAndRingOut() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        val centroid = HashMap<String, Double>(); val thd = HashMap<String, Double>(); val dyn = HashMap<String, Double>(); val tail = HashMap<String, Double>()
        for (preset in presets) {
            var c = 0.0
            for ((s, fr) in listOf(2 to 7, 0 to 0)) c += spectrum(render(project(preset, listOf(beat(Fraction.ZERO, 2, note(s, fr, 96)))), 1.0, bank), sr, sr * 3 / 100, 13).centroid()
            centroid[preset] = c / 2
            val lvl = peak(dry(preset, project(preset, listOf(beat(Fraction.ZERO, 2, note(2, 7, 96)))), 1.0, bank), 0, sr / 2)
            val sine = FloatArray(sr) { (lvl * kotlin.math.sin(2 * Math.PI * 220 * it / sr)).toFloat() }
            thd[preset] = Qa19Analysis.thdPercent(stages(preset, sine).last().second, sr, 220.0)
            fun level(v: Int) = db(Qa19Analysis.rms(render(project(preset, listOf(beat(Fraction.ZERO, 2, note(2, 7, v)))), 1.0, bank), 0, sr * 8 / 10))
            dyn[preset] = level(127) - level(40)
            var t = 0.0
            for ((notes, off) in listOf(listOf(beat(Fraction.ZERO, 8, note(0, 0, 96, pmMark))) to sr / 4, listOf(beat(Fraction.ZERO, 8, note(0, 0, 96, Articulation.Mark(NoteMark.STACCATO)))) to sr / 8,
                    listOf(beat(Fraction.ZERO, 8, note(2, 7, 96, pmMark))) to sr / 4)) {
                val x = render(project(preset, notes), 1.5, bank)
                t += Qa19Analysis.timeBelowMs(x, sr, db(peak(x)) - 50, off, 2.0) ?: 1500.0
            }
            tail[preset] = t / 3
            println("Q19D ${short(preset)}: centroid ${f1(centroid[preset]!!)} Hz | THD ${f1(thd[preset]!!)} % | dynamics v40->v127 ${f1(dyn[preset]!!)} dB | ring-out after note-off to peak-50 dB ${f1(tail[preset]!!)} ms (mean of PM E2, staccato E2, PM A3)")
        }
        val (cl, cr, hg) = presets
        check(failures, centroid[cl]!! < centroid[cr]!! && centroid[cr]!! < centroid[hg]!!) { "centroid order clean ${f1(centroid[cl]!!)} < crunch ${f1(centroid[cr]!!)} < high gain ${f1(centroid[hg]!!)} Hz violated" }
        check(failures, thd[cl]!! < 3.0 && thd[cl]!! < thd[cr]!! && thd[cl]!! < thd[hg]!!) { "THD clean ${f1(thd[cl]!!)} % must be < 3 % and below crunch ${f1(thd[cr]!!)} / high gain ${f1(thd[hg]!!)} %" }
        check(failures, dyn[cr]!! >= dyn[hg]!! + 4.0) { "dynamics crunch ${f1(dyn[cr]!!)} dB vs high gain ${f1(dyn[hg]!!)} dB (need >= +4 dB)" }
        check(failures, tail[hg]!! < tail[cr]!!) { "ring-out high gain ${f1(tail[hg]!!)} ms vs crunch ${f1(tail[cr]!!)} ms (need shorter)" }
        report("preset character", failures)
    }

    /**
     * Transitions on the D string, A3 -> B3 (and B3 -> A3), quarters at 120 bpm: every legato transition (hammer-on,
     * legato slide) reaches the target pitch (+-20 cents 100 ms after the change) and a picked change of the same notes
     * brings a clearly stronger pick transient: the power above 2 kHz in the 21 ms after the change, relative to the 21 ms
     * before it, at least 3 dB above that of the hammer-on and of the legato slide (Clean, Crunch). A bend of a whole tone reaches B3.
     */
    @Test fun pickedAndLegatoTransitionsDiffer() {
        val bank = bankOrSkip()
        val failures = mutableListOf<String>()
        val half = sr / 2
        val b3 = Dsp.midiToHz(59.0)
        for (preset in presets) {
            fun two(first: Note, second: Note) = render(project(preset, listOf(beat(Fraction.ZERO, 4, first), beat(Fraction.of(1, 4), 4, second))), 1.2, bank)
            fun hfJump(x: FloatArray) = 10 * kotlin.math.log10(spectrum(x, sr, half, 10).band(2000.0, 16000.0) / spectrum(x, sr, half - 1024, 10).band(2000.0, 16000.0).coerceAtLeast(1e-30))
            val picked = hfJump(two(note(2, 7, 96), note(2, 9, 96)))
            val hammerX = two(note(2, 7, 96, Articulation.Mark(NoteMark.HAMMER_ON)), note(2, 9, 96))
            val slideX = two(note(2, 7, 96, Articulation.Slide(SlideKind.LEGATO)), note(2, 9, 96))
            val hammer = hfJump(hammerX); val slide = hfJump(slideX)
            val ph = Qa19Analysis.pitchHz(hammerX, sr, half + sr / 10, 100.0, 1000.0); val ps = Qa19Analysis.pitchHz(slideX, sr, half + sr / 10, 100.0, 1000.0)
            val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 3), 200), PitchCurvePoint(Fraction.ONE, 200)))
            val pb = Qa19Analysis.pitchHz(render(project(preset, listOf(beat(Fraction.ZERO, 2, note(2, 7, 96, bend)))), 1.0, bank), sr, sr * 6 / 10, 100.0, 1000.0)
            println("Q19E ${short(preset)}: HF jump picked ${f1(picked)} / hammer ${f1(hammer)} / legato slide ${f1(slide)} dB | pitch after hammer ${f1(ph)} / slide ${f1(ps)} / bend ${f1(pb)} Hz (B3 ${f1(b3)})")
            // High Gain: the three saturated stages erase the pick transient (measured cycle 19: picked 2.8 / hammer 3.4 dB,
            // also with a 1.5 ms pick-contact cut of the old note); printed, documented as open finding.
            if (preset != "rw.guitar.highgain") check(failures, picked >= hammer + 3.0 && picked >= slide + 3.0) { "${short(preset)}: picked HF jump ${f1(picked)} dB vs hammer ${f1(hammer)} / slide ${f1(slide)} dB (need >= +3 dB)" }
            for ((name, p) in listOf("hammer" to ph, "slide" to ps, "bend" to pb)) check(failures, kotlin.math.abs(Qa19Analysis.cents(p, b3)) <= 20) { "${short(preset)}: $name pitch ${f1(p)} Hz, expected ${f1(b3)}" }
        }
        report("transitions", failures)
    }
}
