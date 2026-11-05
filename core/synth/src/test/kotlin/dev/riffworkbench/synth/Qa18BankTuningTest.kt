package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * QA cycle 18, dimension D1 (two simultaneous notes, bank tuning and levels) through the real pipeline
 * (ScorePlaybackCompiler -> PlaybackPlan -> EngineProgram -> SynthEngine) with the bundled sample bank.
 * Every probe prints a "D1 ..." table and asserts the invariant it checks. The bank probes measure the regions as the
 * engine plays them: pitch relative to root + fineTuneCents, level including the region gain (RWSB v2 / load-time normalisation).
 */
class Qa18BankTuningTest {
    private var n = 0
    private fun id() = EntityId("q18b-${n++}")
    private val sr = 48_000
    private val guitar = InstrumentDefinition() // default project guitar: 7-string Drop A (33,40,45,50,55,59,64)
    private val neutral = listOf(EffectUnit(EntityId("fx-eq"), EffectKind.EQ))
    private val presets = listOf("rw.guitar.highgain", "rw.guitar.crunch", "rw.guitar.clean")

    companion object {
        val bankFile: File? = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.firstOrNull { it.isFile }
        val bank: SampleBank? by lazy { bankFile?.inputStream()?.buffered()?.use { input -> SampleBank.read { b, o, l -> input.read(b, o, l) } } }
    }

    // ------------------------------------------------------------------ score building (the shape EditorSession.PutNote produces)
    private fun note(string: Int, fret: Int, velocity: Int = 96, vararg a: Articulation) = Note(id(), Pitch(guitar.pitch(string, fret)), string, fret, velocity, a.toList())
    private fun beat(offset: Fraction, denominator: Int, vararg notes: Note, strum: Strum? = null) = Beat(id(), offset, WrittenDuration(denominator), notes.toList(), strum = strum)
    private fun projectVoices(voices: List<List<Beat>>, preset: String, effects: List<EffectUnit>, bpm: Double = 120.0): Project {
        val m = listOf(Measure(id()), Measure(id()))
        val bars = m.mapIndexed { i, measure -> Bar(id(), measure.id, List(4) { v -> Voice(id(), v, if (i == 0) voices.getOrElse(v) { emptyList() } else emptyList()) }) }
        val track = Track(id(), "Gitarre · Drop A", guitar, listOf(Staff(id(), bars = bars)), SoundAssignment(presetId = preset), ChannelMix(effects = effects))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m.first().id), bpm)))
    }
    private fun project(beats: List<Beat>, preset: String, effects: List<EffectUnit>, bpm: Double = 120.0) = projectVoices(listOf(beats), preset, effects, bpm)

    private fun engine(p: Project, master: Boolean, withBank: Boolean = true): SynthEngine {
        val plan = (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
        val e = SynthEngine(sr, 1024, if (withBank) bank else null)
        e.installRack(ChannelRack.build(p, sr, 1024, includeMasterFx = master))
        e.setMix(MixState.from(p))
        e.install(EngineProgram.prepare(plan))
        return e
    }
    /** Renders [seconds] of the project (left channel), in blocks of [block] frames. */
    private fun render(p: Project, seconds: Double, master: Boolean = false, block: Int = 256, withBank: Boolean = true, tap: (SynthEngine, Int) -> Unit = { _, _ -> }): FloatArray {
        val e = engine(p, master, withBank)
        e.play(0)
        val frames = (seconds * sr).toInt()
        val out = FloatArray(frames)
        val buf = FloatArray(block * 2)
        var done = 0
        while (done < frames) { val k = minOf(block, frames - done); tap(e, done); e.render(buf, k); for (i in 0 until k) out[done + i] = buf[2 * i]; done += k }
        return out
    }

    // ------------------------------------------------------------------ measurements
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * sr).toInt().coerceIn(0, x.size); val b = (to * sr).toInt().coerceIn(a, x.size)
        if (b <= a) return 0.0
        var s = 0.0; for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / (b - a))
    }
    private fun db(x: Double) = if (x <= 1e-12) -240.0 else 20 * log10(x)
    private fun peak(x: FloatArray): Float { var p = 0f; for (v in x) { val a = abs(v); if (a > p) p = a }; return p }
    private fun hasNaN(x: FloatArray) = x.any { !it.isFinite() }
    /** Hann-windowed Goertzel magnitude of [hz] in [from, to) seconds. */
    private fun tone(x: FloatArray, hz: Double, from: Double, to: Double): Double {
        val a = (from * sr).toInt().coerceIn(0, x.size); val b = (to * sr).toInt().coerceIn(a, x.size)
        if (b - a < 16) return 0.0
        val w = 2 * PI * hz / sr; val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in a until b) { val win = 0.5 - 0.5 * cos(2 * PI * (i - a) / (b - a)); val s0 = x[i] * win + c * s1 - s2; s2 = s1; s1 = s0 }
        return sqrt(max(0.0, s1 * s1 + s2 * s2 - c * s1 * s2)) / (b - a)
    }
    /** Band level of a note: sum of its first three partials, windowed. */
    private fun band(x: FloatArray, midi: Int, from: Double, to: Double): Double {
        val f0 = Dsp.midiToHz(midi.toDouble())
        // The bank's regions sit up to ±15 cents off their root; scan around each partial so detuning does not read as level.
        fun partial(k: Int) = (-20..20 step 4).maxOf { c -> tone(x, k * f0 * Math.pow(2.0, c / 1200.0), from, to) }
        return partial(1) + partial(2) + partial(3)
    }
    /** Spectral cross-check: Hann-Goertzel sweep ±150 cents around [guessHz] in 1-cent steps, the strongest bin wins. */
    private fun pitchBySweep(x: FloatArray, rate: Int, from: Double, to: Double, guessHz: Double): Double {
        val a = (from * rate).toInt().coerceIn(0, x.size); val b = (to * rate).toInt().coerceIn(a, x.size)
        var best = guessHz; var bestMag = -1.0
        for (c in -150..150) {
            val hz = guessHz * Math.pow(2.0, c / 1200.0)
            val w = 2 * PI * hz / rate; val cc = 2 * cos(w)
            var s1 = 0.0; var s2 = 0.0
            for (i in a until b) { val win = 0.5 - 0.5 * cos(2 * PI * (i - a) / (b - a)); val s0 = x[i] * win + cc * s1 - s2; s2 = s1; s1 = s0 }
            val mag = s1 * s1 + s2 * s2 - cc * s1 * s2
            if (mag > bestMag) { bestMag = mag; best = hz }
        }
        return best
    }
    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float { var m = 0f; for (i in a.indices) { val d = abs(a[i] - b[i]); if (d > m) m = d }; return m }
    private fun sum(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] + b[it] }

    /**
     * Autocorrelation pitch estimate (Hz) of [x] in [from, to) around [guessHz] (±6 %): the lag is searched at K periods
     * (K ≤ 8 periods, K*period ≤ 1000 samples) with parabolic interpolation, so the resolution is ≈0.2 cents even at 880 Hz.
     */
    private fun pitchHz(x: FloatArray, rate: Int, from: Double, to: Double, guessHz: Double): Double {
        val a = (from * rate).toInt(); val b = (to * rate).toInt().coerceAtMost(x.size)
        val period = rate / guessHz
        // At most 8 periods: the neighbouring period count (K±1) then lies ≥ 12.5 % away, outside the ±6 % search window.
        val k = (1000.0 / period).toInt().coerceIn(1, 8)
        val lag0 = k * period
        val lo = (lag0 * 0.94).toInt().coerceAtLeast(2); val hi = (lag0 * 1.06).toInt().coerceAtMost((b - a) / 2)
        var best = lo; var bestR = -1.0
        val r = DoubleArray(hi + 2)
        for (lag in lo..hi) {
            var num = 0.0; var d1 = 0.0; var d2 = 0.0
            for (i in a until b - lag) { val p = x[i].toDouble(); val q = x[i + lag].toDouble(); num += p * q; d1 += p * p; d2 += q * q }
            val v = if (d1 > 0 && d2 > 0) num / sqrt(d1 * d2) else -1.0
            r[lag] = v
            if (v > bestR) { bestR = v; best = lag }
        }
        var lag = best.toDouble()
        if (best > lo && best < hi) { val y0 = r[best - 1]; val y1 = r[best]; val y2 = r[best + 1]; val den = y0 - 2 * y1 + y2; if (abs(den) > 1e-12) lag += 0.5 * (y0 - y2) / den }
        return rate * k / lag
    }
    private fun cents(hz: Double, refHz: Double) = 1200.0 * ln(hz / refHz) / ln(2.0)

    // ------------------------------------------------------------------ (a1) every note alone, every electric preset
    @Test fun a1_singleNotesSoundOnEveryStringAndPreset() {
        assumeTrue("bank missing", bank != null)
        val quiet = ArrayList<String>()
        val spreads = LinkedHashMap<String, Double>()
        for (preset in presets) {
            val levels = ArrayList<Double>()
            for (s in guitar.tuning.indices) for (fret in listOf(0, 3, 5, 7, 12, 15)) {
                val p = project(listOf(beat(Fraction.ZERO, 2, note(s, fret))), preset, emptyList())
                val x = render(p, 1.2, master = true)
                val midi = guitar.pitch(s, fret)
                val level = db(rms(x, 0.05, 0.45)); val late = db(rms(x, 0.6, 0.95))
                val f0 = band(x, midi, 0.1, 0.5)
                println("D1 single preset=$preset string=${s + 1} fret=$fret midi=$midi rms=${"%.1f".format(level)}dB late=${"%.1f".format(late)}dB band=${"%.1f".format(db(f0))} peak=${"%.3f".format(peak(x))} nan=${hasNaN(x)}")
                if (hasNaN(x) || level < -40.0) quiet += "$preset s${s + 1}f$fret ($midi): ${"%.1f".format(level)} dB nan=${hasNaN(x)}"
                levels += level
            }
            spreads[preset] = levels.max() - levels.min()
            println("D1 single preset=$preset level spread across the neck (same velocity, 42 notes): ${"%.1f".format(spreads[preset])} dB (${"%.1f".format(levels.min())}..${"%.1f".format(levels.max())} dBFS)")
        }
        assertTrue("silent/NaN single notes: $quiet", quiet.isEmpty())
        // Bank + voice only (flat EQ, no master): what the normalisation must deliver across the neck (Bericht 2 section 3).
        val flat = ArrayList<Double>()
        for (s in guitar.tuning.indices) for (fret in listOf(0, 3, 5, 7, 12, 15)) flat += db(rms(render(project(listOf(beat(Fraction.ZERO, 2, note(s, fret))), "rw.guitar.clean", neutral), 0.6), 0.05, 0.45))
        val flatSpread = flat.max() - flat.min()
        println("D1 single neutral chain level spread across the neck (same velocity, 42 notes): ${"%.1f".format(flatSpread)} dB (${"%.1f".format(flat.min())}..${"%.1f".format(flat.max())} dBFS)")
        assertTrue("bank: same velocity varies ${"%.1f".format(flatSpread)} dB across the neck on a flat chain (budget 3 dB)", flatSpread <= 3.0)
        // Through the Clean preset the chain itself tilts with pitch (treble shelf, presence, cabinet): measured 3.7 dB from midi 45 to 79 with a flat bank.
        assertTrue("clean preset: same velocity varies ${"%.1f".format(spreads["rw.guitar.clean"])} dB across the neck (budget 5.5 dB = bank 3 dB + chain tilt)", spreads["rw.guitar.clean"]!! <= 5.5)
    }

    // ------------------------------------------------------------------ (a2) pair == single + single on a linear chain, all string pairs
    @Test fun a2_pairsAreExactlyTheSumOfSinglesOnALinearChain() {
        assumeTrue("bank missing", bank != null)
        val frets = listOf(0, 3, 5, 7, 12)
        val singles = HashMap<Pair<Int, Int>, FloatArray>()
        fun single(s: Int, f: Int) = singles.getOrPut(s to f) { render(project(listOf(beat(Fraction.ZERO, 2, note(s, f))), "rw.guitar.highgain", neutral), 1.2) }
        val bad = ArrayList<String>()
        var count = 0; var worst = 0f
        for (s1 in guitar.tuning.indices) for (s2 in s1 + 1 until guitar.tuning.size) for (f1 in frets) for (f2 in frets) {
            val pair = render(project(listOf(beat(Fraction.ZERO, 2, note(s1, f1), note(s2, f2))), "rw.guitar.highgain", neutral), 1.2)
            val expected = sum(single(s1, f1), single(s2, f2))
            val diff = maxAbsDiff(pair, expected)
            val ratio = rms(pair, 0.0, 1.2) / rms(expected, 0.0, 1.2).coerceAtLeast(1e-9)
            count++; if (diff > worst) worst = diff
            if (diff > 1e-3f || abs(ratio - 1.0) > 0.01) bad += "s${s1 + 1}f$f1(${guitar.pitch(s1, f1)})+s${s2 + 1}f$f2(${guitar.pitch(s2, f2)}) diff=$diff ratio=${"%.3f".format(ratio)}"
        }
        println("D1 pair-sum: $count pairs, worst max|pair-(a+b)|=$worst, violations=${bad.size}")
        bad.forEach { println("D1 pair-sum VIOLATION $it") }
        assertTrue("pairs that are not the sum of their singles: $bad", bad.isEmpty())
    }

    // ------------------------------------------------------------------ (a3) pairs through the three preset chains + master limiter
    @Test fun a3_pairsKeepBothNotesThroughThePresetChains() {
        assumeTrue("bank missing", bank != null)
        // Unisons, octaves, fifths, seconds, low string, high frets.
        fun pr(s1: Int, f1: Int, s2: Int, f2: Int) = (s1 to f1) to (s2 to f2)
        val pairs = listOf(
            pr(6, 0, 5, 5),   // unison E4 (64) on two strings
            pr(5, 0, 4, 4),   // unison B3 (59)
            pr(2, 0, 1, 5),   // unison A2 (45)
            pr(0, 0, 2, 0),   // octave A1 (33) + A2 (45)
            pr(0, 0, 1, 0),   // A1 + E2 (fifth, lowest power chord)
            pr(0, 0, 1, 2),   // A1 + F#2 (sixth)
            pr(0, 1, 1, 1),   // Bb1 (34) + F2
            pr(0, 3, 1, 3),   // C2 (36) + G2
            pr(0, 2, 1, 2),   // B1 (35) + F#2
            pr(1, 0, 2, 0),   // E2 + A2
            pr(1, 0, 2, 2),   // E2 + B2 (fifth)
            pr(1, 1, 2, 1),   // F2 + Bb2 (fourth)
            pr(1, 0, 2, 1),   // E2 + Bb2 (tritone)
            pr(3, 0, 4, 0),   // D3 + G3
            pr(3, 2, 4, 0),   // E3 + G3 (minor third)
            pr(4, 0, 5, 0),   // G3 + B3
            pr(5, 0, 6, 0),   // B3 + E4
            pr(5, 12, 6, 12), // B4 + E5
            pr(6, 15, 5, 15), // G5 + D5 (upper range)
            pr(6, 0, 5, 4),   // E4 + Eb4 (semitone)
            pr(3, 5, 2, 6),   // G3 + Eb3
        )
        val singles = HashMap<Triple<String, Int, Int>, FloatArray>()
        fun single(preset: String, s: Int, f: Int) = singles.getOrPut(Triple(preset, s, f)) { render(project(listOf(beat(Fraction.ZERO, 2, note(s, f))), preset, emptyList()), 1.2, master = true) }
        val windows = listOf(0.05 to 0.25, 0.25 to 0.5, 0.5 to 0.75, 0.75 to 1.0)
        val flagged = ArrayList<String>()
        for (preset in presets) for ((a, b) in pairs) {
            val (s1, f1) = a; val (s2, f2) = b
            val m1 = guitar.pitch(s1, f1); val m2 = guitar.pitch(s2, f2)
            val pair = render(project(listOf(beat(Fraction.ZERO, 2, note(s1, f1), note(s2, f2))), preset, emptyList()), 1.2, master = true)
            val x1 = single(preset, s1, f1); val x2 = single(preset, s2, f2)
            val sb = StringBuilder("D1 preset=$preset pair s${s1 + 1}f$f1($m1)+s${s2 + 1}f$f2($m2) rmsPair=${"%.1f".format(db(rms(pair, 0.05, 1.0)))} rms1=${"%.1f".format(db(rms(x1, 0.05, 1.0)))} rms2=${"%.1f".format(db(rms(x2, 0.05, 1.0)))} peakPair=${"%.3f".format(peak(pair))}")
            for ((from, to) in windows) {
                val d1 = db(band(pair, m1, from, to)) - db(band(x1, m1, from, to))
                val d2 = db(band(pair, m2, from, to)) - db(band(x2, m2, from, to))
                sb.append(" | ${"%.2f".format(from)}s: n1 ${"%+.1f".format(d1)}dB n2 ${"%+.1f".format(d2)}dB")
                // A note whose own partials drop by more than 12 dB against its solo rendering is "missing or cut".
                // High gain: the amp is a brickwall, so both notes share one output level (each loses ~3-6 dB against its solo
                // rendering by construction) and the quieter-decaying one is captured late in the sustain; with the bank
                // level-matched the defect is an unequal share in the onset and body (up to 0.5 s), checked relative to the other note.
                // Unisons share the band, so only the common band is checked once.
                val highGain = preset == "rw.guitar.highgain"
                if (m1 != m2 && !highGain && (d1 < -12.0 || d2 < -12.0)) flagged += "$preset s${s1 + 1}f$f1($m1)+s${s2 + 1}f$f2($m2) @${from}s: n1 ${"%.1f".format(d1)} dB, n2 ${"%.1f".format(d2)} dB"
                if (m1 != m2 && highGain && to <= 0.5 && abs(d1 - d2) > 12.0) flagged += "$preset s${s1 + 1}f$f1($m1)+s${s2 + 1}f$f2($m2) @${from}s: unequal share n1 ${"%.1f".format(d1)} dB, n2 ${"%.1f".format(d2)} dB"
                if (m1 == m2 && d1 < -6.0) flagged += "$preset unison $m1 @${from}s: ${"%.1f".format(d1)} dB below solo"
            }
            if (hasNaN(pair)) flagged += "$preset NaN in pair $m1+$m2"
            println(sb)
        }
        flagged.forEach { println("D1 FLAG $it") }
        assertTrue("pairs where a note vanished: $flagged", flagged.isEmpty())
    }

    // ------------------------------------------------------------------ (a4) close onsets: 1 frame, 1 ms, 10 ms apart (engine.preview places notes at exact frames)
    @Test fun a4_closeOnsetsOnDifferentStringsBothSound() {
        assumeTrue("bank missing", bank != null)
        val p = project(emptyList<Beat>(), "rw.guitar.highgain", neutral)
        fun scheduled(s: Int, f: Int, frame: Long, len: Long) = ScheduledNote(id(), p.tracks[0].id, frame, frame + len, Pitch(guitar.pitch(s, f)), 96, s, emptyList(), 0, 0)
        val len = (0.9 * sr).toLong()
        fun renderPair(s1: Int, f1: Int, s2: Int, f2: Int, gap: Int): FloatArray {
            val e = engine(p, master = false)
            val frames = (1.2 * sr).toInt()
            val out = FloatArray(frames)
            val buf = FloatArray(2048)
            var done = 0
            var fired = 0
            while (done < frames) {
                if (fired == 0 && done >= 0) { e.preview(scheduled(s1, f1, 0, len)); fired = 1 }
                if (fired == 1 && done >= gap) { e.preview(scheduled(s2, f2, gap.toLong(), len)); fired = 2 }
                // Split the block so the second preview lands exactly on frame `gap`.
                val next = if (fired == 1 && gap > done) minOf(gap - done, 1024, frames - done) else minOf(1024, frames - done)
                e.render(buf, next); for (i in 0 until next) out[done + i] = buf[2 * i]; done += next
            }
            return out
        }
        fun renderSingle(s: Int, f: Int, at: Int): FloatArray = renderPair(s, f, s, f, Int.MAX_VALUE).let { x -> if (at == 0) x else FloatArray(x.size) { i -> if (i >= at) x[i - at] else 0f } }
        val bad = ArrayList<String>()
        for (gap in listOf(1, sr / 1000, sr / 100)) for ((a, b) in listOf((0 to 0) to (1 to 0), (6 to 0) to (5 to 5), (0 to 0) to (2 to 0), (3 to 5) to (4 to 7))) {
            val pair = renderPair(a.first, a.second, b.first, b.second, gap)
            val expected = sum(renderSingle(a.first, a.second, 0), renderSingle(b.first, b.second, gap))
            val diff = maxAbsDiff(pair, expected)
            println("D1 close-onset gap=${gap}f s${a.first + 1}f${a.second}+s${b.first + 1}f${b.second} max|pair-(a+b)|=$diff rmsPair=${"%.1f".format(db(rms(pair, 0.0, 1.2)))} rmsSum=${"%.1f".format(db(rms(expected, 0.0, 1.2)))}")
            if (diff > 1e-3f) bad += "gap=$gap ${a}+${b} diff=$diff"
        }
        assertTrue(bad.toString(), bad.isEmpty())
    }

    // ------------------------------------------------------------------ (a5) full 7-string chord, with and without strum
    @Test fun a5_sevenStringChordWithAndWithoutStrumKeepsEveryString() {
        assumeTrue("bank missing", bank != null)
        val frets = listOf(0, 2, 2, 0, 0, 0, 0) // every string open-ish, distinct pitches except none
        val flagged = ArrayList<String>()
        for (preset in presets) for (strum in listOf(null, Strum(StrumKind.STRUM, StrokeDirection.DOWN), Strum(StrumKind.STRUM, StrokeDirection.UP), Strum(StrumKind.ARPEGGIO, StrokeDirection.DOWN))) {
            val notes = guitar.tuning.indices.map { s -> note(s, frets[s]) }.toTypedArray()
            val p = project(listOf(beat(Fraction.ZERO, 2, *notes, strum = strum)), preset, emptyList())
            val plan = (ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value
            val onsets = plan.notes.map { it.frame }
            val chord = render(p, 1.2, master = true)
            val sb = StringBuilder("D1 chord preset=$preset strum=${strum?.kind}/${strum?.direction} onsets=${onsets} rms=${"%.1f".format(db(rms(chord, 0.05, 1.0)))} peak=${"%.3f".format(peak(chord))}")
            val drops = DoubleArray(guitar.tuning.size)
            for (s in guitar.tuning.indices) {
                val midi = guitar.pitch(s, frets[s])
                val solo = render(project(listOf(beat(Fraction.ZERO, 2, note(s, frets[s]))), preset, emptyList()), 1.2, master = true)
                val d = db(band(chord, midi, 0.3, 0.9)) - db(band(solo, midi, 0.3, 0.9))
                drops[s] = d
                sb.append(" | s${s + 1}($midi) ${"%+.1f".format(d)}dB")
                if (preset != "rw.guitar.highgain" && d < -12.0) flagged += "$preset strum=${strum?.kind} string ${s + 1} ($midi) ${"%.1f".format(d)} dB below solo"
            }
            // High gain: seven notes share one brickwalled output (a fair share is about -8.5 dB against each solo rendering);
            // the defect is a string captured far below the others, checked against the chord's median drop.
            if (preset == "rw.guitar.highgain") {
                val median = drops.sorted()[drops.size / 2]
                sb.append(" | median ${"%+.1f".format(median)}dB")
                for (s in guitar.tuning.indices) if (drops[s] < median - 8.0) flagged += "$preset strum=${strum?.kind} string ${s + 1} (${guitar.pitch(s, frets[s])}) ${"%.1f".format(drops[s])} dB below solo, ${"%.1f".format(median - drops[s])} dB below the chord's median share"
            }
            println(sb)
            assertEquals("all 7 notes scheduled", 7, plan.notes.size)
            if (strum == null) assertTrue("no strum: all onsets equal", onsets.distinct().size == 1)
            else assertTrue("strum: onsets ascending", onsets.zipWithNext().all { (a, b) -> b >= a })
        }
        flagged.forEach { println("D1 FLAG $it") }
        assertTrue(flagged.toString(), flagged.isEmpty())
    }

    // ------------------------------------------------------------------ (a6) two consecutive beats on the same string choke only that string
    @Test fun a6_rePickOnOneStringDoesNotChokeTheOtherString() {
        assumeTrue("bank missing", bank != null)
        // Voice 0: string 3 (D3, 50) half note. Voice 1: string 1 (E2, 40) quarter, then string 1 fret 2 quarter.
        val held = listOf(beat(Fraction.ZERO, 2, note(3, 0)))
        val picks = listOf(beat(Fraction.ZERO, 4, note(1, 0)), beat(Fraction.of(1, 4), 4, note(1, 2)))
        val flagged = ArrayList<String>()
        for (preset in presets) {
            val both = render(projectVoices(listOf(held, picks), preset, emptyList()), 1.2, master = true)
            val heldOnly = render(projectVoices(listOf(held), preset, emptyList()), 1.2, master = true)
            val picksOnly = render(projectVoices(listOf(emptyList(), picks), preset, emptyList()), 1.2, master = true)
            // After the second pick (0.5 s at 120 BPM) the held D3 must still be there at the level it has alone.
            val dHeld = db(band(both, 50, 0.6, 0.95)) - db(band(heldOnly, 50, 0.6, 0.95))
            // The first E2 must be gone after the re-pick: its band in picksOnly after 0.55 s vs. its band at 0.3-0.45 s.
            val e2Before = db(band(picksOnly, 40, 0.1, 0.45)); val e2After = db(band(picksOnly, 40, 0.6, 0.95))
            val fs2 = db(band(picksOnly, 42, 0.6, 0.95))
            println("D1 choke preset=$preset heldD3 after re-pick ${"%+.1f".format(dHeld)} dB vs alone; E2 band before=${"%.1f".format(e2Before)} after=${"%.1f".format(e2After)}; F#2 after=${"%.1f".format(fs2)}")
            if (dHeld < -6.0) flagged += "$preset: held D3 lost ${"%.1f".format(-dHeld)} dB when the other string was re-picked"
        }
        // Linear reference: both == held + picks exactly (neutral chain).
        val lin = render(projectVoices(listOf(held, picks), "rw.guitar.clean", neutral), 1.2)
        val linSum = sum(render(projectVoices(listOf(held), "rw.guitar.clean", neutral), 1.2), render(projectVoices(listOf(emptyList(), picks), "rw.guitar.clean", neutral), 1.2))
        println("D1 choke linear max|both-(held+picks)|=${maxAbsDiff(lin, linSum)}")
        assertTrue("re-pick linear superposition", maxAbsDiff(lin, linSum) < 1e-3f)
        assertTrue(flagged.toString(), flagged.isEmpty())
    }

    // ------------------------------------------------------------------ (c) below the bank: MIDI 33..36 are pitch-shifted down from Db2 (37)
    @Test fun c_lowStringBelowBankRangeIsPitchShiftedCleanly() {
        assumeTrue("bank missing", bank != null)
        val flagged = ArrayList<String>()
        for (fret in 0..7) {
            val midi = guitar.pitch(0, fret)
            val root = if (midi <= 38) 37 else if (midi <= 41) 40 else 42
            val ratio = bank!!.sampleRate.toDouble() / sr * Math.pow(2.0, (midi - root) / 12.0)
            val x = render(project(listOf(beat(Fraction.ZERO, 1, note(0, fret))), "rw.guitar.clean", neutral), 1.9)
            val hz = pitchHz(x, sr, 0.3, 1.5, Dsp.midiToHz(midi.toDouble()))
            val c = cents(hz, Dsp.midiToHz(midi.toDouble()))
            println("D1 low string fret=$fret midi=$midi root=$root ratio=${"%.4f".format(ratio)} rms=${"%.1f".format(db(rms(x, 0.05, 0.5)))}dB pitch=${"%.2f".format(hz)}Hz (${"%+.1f".format(c)} cents) peak=${"%.3f".format(peak(x))} nan=${hasNaN(x)}")
            if (hasNaN(x)) flagged += "NaN at $midi"
            if (abs(c) > 25) flagged += "midi $midi off by ${"%.1f".format(c)} cents"
            if (rms(x, 0.05, 0.5) < 1e-3) flagged += "midi $midi silent"
        }
        assertTrue(flagged.toString(), flagged.isEmpty())
    }

    // ------------------------------------------------------------------ limiter: gain reduction on two-note sums vs single notes (highgain, default mix)
    @Test fun limiterOnTwoNoteSumsReportsNoPumping() {
        assumeTrue("bank missing", bank != null)
        for (preset in presets) {
            val single = render(project(listOf(beat(Fraction.ZERO, 2, note(1, 0))), preset, emptyList()), 1.2, master = true)
            val pair = render(project(listOf(beat(Fraction.ZERO, 2, note(1, 0), note(2, 2))), preset, emptyList()), 1.2, master = true)
            val chord = render(project(listOf(beat(Fraction.ZERO, 2, note(1, 0), note(2, 2), note(3, 2))), preset, emptyList()), 1.2, master = true)
            val noLimSingle = render(project(listOf(beat(Fraction.ZERO, 2, note(1, 0))), preset, emptyList()), 1.2, master = false)
            val noLimPair = render(project(listOf(beat(Fraction.ZERO, 2, note(1, 0), note(2, 2))), preset, emptyList()), 1.2, master = false)
            val noLimChord = render(project(listOf(beat(Fraction.ZERO, 2, note(1, 0), note(2, 2), note(3, 2))), preset, emptyList()), 1.2, master = false)
            fun env(x: FloatArray) = (0 until 10).map { k -> "%.2f".format(peak(x.copyOfRange(k * sr / 10, (k + 1) * sr / 10))) }
            println("D1 limiter preset=$preset single peakPre=${"%.2f".format(peak(noLimSingle) * 0.5 * MixState.volumeToGain(0.8))} post=${"%.3f".format(peak(single))} | pair pre=${"%.2f".format(peak(noLimPair) * 0.5 * MixState.volumeToGain(0.8))} post=${"%.3f".format(peak(pair))} | chord pre=${"%.2f".format(peak(noLimChord) * 0.5 * MixState.volumeToGain(0.8))} post=${"%.3f".format(peak(chord))}")
            println("D1 limiter preset=$preset pair 100ms peaks post=${env(pair)}")
            println("D1 limiter preset=$preset chord 100ms peaks post=${env(chord)}")
            assertTrue(peak(single) <= 0.94f + 1e-3f && peak(pair) <= 0.94f + 1e-3f && peak(chord) <= 0.94f + 1e-3f)
        }
    }

    // ------------------------------------------------------------------ bank scan: round robins and layers of each root (a source of "not always")
    @Test fun bankRoundRobinsAreConsistentInPitchAndLevel() {
        val b = bank ?: return
        val regions = b.regions.filter { it.kind == SampleBank.KIND_NOTE }
        val rate = b.sampleRate
        val flagged = ArrayList<String>()
        for (r in regions.sortedWith(compareBy({ it.root }, { it.layer }, { it.rr }))) {
            val x = FloatArray(r.frames) { r.data[it] / 32768f }
            val hz = pitchHz(x, rate, 0.15, 0.75, Dsp.midiToHz(r.root.toDouble()))
            // Residual after the stored fine tune: what the engine's pitch ratio leaves over.
            val c = cents(hz, Dsp.midiToHz(r.root.toDouble())) - r.fineTuneCents
            var dc = 0.0; for (v in x) dc += v; dc /= x.size
            val attack = rmsAt(x, rate, 0.0, 0.1) * r.gain; val body = rmsAt(x, rate, 0.1, 0.5) * r.gain; val loop = rmsAt(x, rate, r.loopStart.toDouble() / rate, r.loopEnd.toDouble() / rate) * r.gain
            val first = abs(x[0]); var pk = 0f; for (v in x) pk = max(pk, abs(v))
            println("D1 bank root=${r.root} layer=${r.layer} rr=${r.rr} pitch=${"%.2f".format(hz)}Hz (residual ${"%+.1f".format(c)}c, fineTune ${"%+.1f".format(r.fineTuneCents)}c) gain=${"%+.1f".format(db(r.gain.toDouble()))}dB attack100ms=${"%.1f".format(db(attack))} body=${"%.1f".format(db(body))} loop=${"%.1f".format(db(loop))} peak=${"%.1f".format(db(pk * r.gain.toDouble()))} first=${"%.4f".format(first)} dc=${"%.5f".format(dc)} decay=${"%.2f".format(r.decayDbPerSec)}dB/s loop=${r.loopStart}..${r.loopEnd}")
            if (abs(c) > 30) flagged += "root ${r.root} L${r.layer} RR${r.rr}: ${"%.1f".format(c)} cents off after fine tune"
            if (first > 0.02f) flagged += "root ${r.root} L${r.layer} RR${r.rr}: starts at ${"%.3f".format(first)} (click)"
        }
        // Level spread between round robins of the same root and layer, as played: the first 100 ms (the onset, so a repeated
        // note never jumps) within 3 dB; the sustained part keeps the recordings' natural decay differences, bounded to 9 dB
        // (root 81 f: one round robin has a stronger attack and decays 5 dB faster than its siblings).
        for ((key, group) in regions.groupBy { it.root to it.layer }) {
            val attack = group.map { r -> db(rmsAt(FloatArray(r.frames) { r.data[it] / 32768f }, rate, 0.0, 0.1) * r.gain) }
            val sustain = group.map { r -> db(rmsAt(FloatArray(r.frames) { r.data[it] / 32768f }, rate, 0.05, 0.4) * r.gain) }
            val attackSpread = attack.max() - attack.min(); val sustainSpread = sustain.max() - sustain.min()
            if (attackSpread > 3.0) flagged += "root ${key.first} layer ${key.second}: round robins differ by ${"%.1f".format(attackSpread)} dB in the first 100 ms"
            if (sustainSpread > 9.0) flagged += "root ${key.first} layer ${key.second}: round robins differ by ${"%.1f".format(sustainSpread)} dB in the sustain"
            if (sustainSpread > 4.0) println("D1 bank round robins root=${key.first} layer=${key.second}: attack spread ${"%.1f".format(attackSpread)} dB, sustain spread ${"%.1f".format(sustainSpread)} dB (natural decay variation)")
        }
        flagged.forEach { println("D1 BANK FLAG $it") }
        assertTrue(flagged.toString(), flagged.isEmpty())
    }

    // ------------------------------------------------------------------ bank tuning: every region within 5 cents of its root? (tools/make_guitar_bank.py does not retune)
    @Test fun bankRegionsAreInTuneWithinFiveCents() {
        val b = bank ?: return
        val rate = b.sampleRate
        val perRoot = HashMap<Int, ArrayList<Double>>()
        val rawPerRoot = HashMap<Int, ArrayList<Double>>()
        val off = ArrayList<String>()
        for (r in b.regions.filter { it.kind == SampleBank.KIND_NOTE }.sortedWith(compareBy({ it.root }, { it.layer }, { it.rr }))) {
            val x = FloatArray(r.frames) { r.data[it] / 32768f }
            val ref = Dsp.midiToHz(r.root.toDouble())
            val early = cents(pitchHz(x, rate, 0.05, 0.25, ref), ref)
            val body = cents(pitchHz(x, rate, 0.25, 0.9, ref), ref)
            val loop = cents(pitchHz(x, rate, r.loopStart.toDouble() / rate, r.loopEnd.toDouble() / rate, ref), ref)
            val sweep = cents(pitchBySweep(x, rate, 0.25, 0.9, ref), ref)
            // What the engine plays: the recording's body pitch minus the stored fine tune.
            val residual = body - r.fineTuneCents
            perRoot.getOrPut(r.root) { ArrayList() } += residual
            rawPerRoot.getOrPut(r.root) { ArrayList() } += body
            println("D1 tuning root=${r.root} layer=${r.layer} rr=${r.rr} early=${"%+.1f".format(early)}c body=${"%+.1f".format(body)}c loop=${"%+.1f".format(loop)}c sweep=${"%+.1f".format(sweep)}c fineTune=${"%+.1f".format(r.fineTuneCents)}c residual=${"%+.1f".format(residual)}c")
            if (abs(sweep - body) > 6.0) println("D1 tuning METHODS DISAGREE root=${r.root} L${r.layer} RR${r.rr}: autocorr ${"%+.1f".format(body)} vs sweep ${"%+.1f".format(sweep)}")
            if (abs(residual) > 5.0) off += "root ${r.root} L${r.layer} RR${r.rr} ${"%+.1f".format(residual)} c"
        }
        val means = perRoot.toSortedMap().mapValues { (_, v) -> v.average() }
        println("D1 tuning mean cents per root as recorded: " + rawPerRoot.toSortedMap().entries.joinToString { "${it.key}=${"%+.1f".format(it.value.average())}" })
        println("D1 tuning mean residual cents per root as played: " + means.entries.joinToString { "${it.key}=${"%+.1f".format(it.value)}" })
        // Intervals the engine builds from two regions: fifth (7 semitones) and octave, per lower root, with the region each key lands on.
        fun regionRoot(key: Int) = b.regions.filter { it.kind == SampleBank.KIND_NOTE && key in it.loKey..it.hiKey }.first().root
        val intervals = ArrayList<String>()
        for (low in 33..72) for (semis in listOf(7, 12)) {
            val high = low + semis
            val d = means[regionRoot(high)]!! - means[regionRoot(low)]!!
            intervals += "$low+$semis: ${"%+.1f".format(d)}c"
            if (abs(d) > 8.0) println("D1 tuning WIDE interval $low+$semis (regions ${regionRoot(low)}/${regionRoot(high)}): ${"%+.1f".format(d)} cents")
        }
        println("D1 tuning intervals: $intervals")
        off.forEach { println("D1 TUNING FLAG $it") }
        assertTrue("regions more than 5 cents off their root after the stored fine tune: ${off.size} of 162 -> $off", off.isEmpty())
    }

    // ------------------------------------------------------------------ bank levels per root (mf layer): spread across the neck
    @Test fun bankRootLevelsAreWithinThreeDecibels() {
        val b = bank ?: return
        val rate = b.sampleRate
        fun table(from: Double, to: Double) = b.regions.filter { it.kind == SampleBank.KIND_NOTE && it.layer == 1 }.groupBy { it.root }.toSortedMap().mapValues { (_, rs) ->
            rs.map { r -> db(rmsAt(FloatArray(r.frames) { r.data[it] / 32768f }, rate, from, to) * r.gain) }.average()
        }
        val attack = table(0.0, 0.1); val sustain = table(0.02, 0.4)
        println("D1 level mf per root, first 100 ms as played: " + attack.entries.joinToString { "${it.key}=${"%.1f".format(it.value)}" })
        println("D1 level mf per root, 0.02-0.4 s as played: " + sustain.entries.joinToString { "${it.key}=${"%.1f".format(it.value)}" })
        val attackSpread = attack.values.max() - attack.values.min(); val sustainSpread = sustain.values.max() - sustain.values.min()
        println("D1 level mf spread across roots: attack ${"%.1f".format(attackSpread)} dB, sustain ${"%.1f".format(sustainSpread)} dB (loudest ${sustain.maxBy { it.value }.key}, quietest ${sustain.minBy { it.value }.key})")
        // The normalisation (RMS over the first 400 ms) keeps both the onset and the sustained level within 3 dB across the roots.
        assertTrue("mf layer attack levels spread ${"%.1f".format(attackSpread)} dB across roots", attackSpread <= 3.0)
        assertTrue("mf layer sustained levels spread ${"%.1f".format(sustainSpread)} dB across roots", sustainSpread <= 3.0)
    }

    // ------------------------------------------------------------------ unison on two strings: both voices play the same sample, in phase
    @Test fun unisonOnTwoStringsIsNotTheSameSampleTwice() {
        assumeTrue("bank missing", bank != null)
        val single = render(project(listOf(beat(Fraction.ZERO, 2, note(6, 0))), "rw.guitar.clean", neutral), 1.2)
        val other = render(project(listOf(beat(Fraction.ZERO, 2, note(5, 5))), "rw.guitar.clean", neutral), 1.2)
        val pair = render(project(listOf(beat(Fraction.ZERO, 2, note(6, 0), note(5, 5))), "rw.guitar.clean", neutral), 1.2)
        val doubled = FloatArray(single.size) { 2f * single[it] }
        println("D1 unison: max|s6f5 - s7f0|=${maxAbsDiff(single, other)}  max|pair - 2*single|=${maxAbsDiff(pair, doubled)}  rms single=${"%.1f".format(db(rms(single, 0.05, 1.0)))} pair=${"%.1f".format(db(rms(pair, 0.05, 1.0)))}")
        // Two different strings must not be bit-identical recordings (the round robin is seeded by string and the start offset differs per string).
        assertTrue("unison voices on two strings are the identical sample (pair == 2 x single)", maxAbsDiff(single, other) > 1e-3f)
        assertTrue("unison pair is a coherent doubling of one voice", maxAbsDiff(pair, doubled) > 1e-3f)
    }

    // ------------------------------------------------------------------ "not always": the same pair played three times (round robins advance) through high gain
    @Test fun repeatedPlaybackOfOnePairVariesWithRoundRobins() {
        assumeTrue("bank missing", bank != null)
        for (preset in listOf("rw.guitar.highgain", "rw.guitar.crunch")) for ((a, b) in listOf((6 to 15) to (5 to 15), (1 to 0) to (2 to 1), (0 to 0) to (1 to 0))) {
            val (s1, f1) = a; val (s2, f2) = b
            val m1 = guitar.pitch(s1, f1); val m2 = guitar.pitch(s2, f2)
            val p = project(listOf(beat(Fraction.ZERO, 2, note(s1, f1), note(s2, f2))), preset, emptyList())
            val solo1 = project(listOf(beat(Fraction.ZERO, 2, note(s1, f1))), preset, emptyList())
            val solo2 = project(listOf(beat(Fraction.ZERO, 2, note(s2, f2))), preset, emptyList())
            // One engine, played three times in a row (as the app does when the user presses play repeatedly).
            fun playThrice(pr: Project): List<FloatArray> {
                val e = engine(pr, master = true)
                return (0 until 3).map {
                    e.play(0)
                    val frames = (1.2 * sr).toInt(); val out = FloatArray(frames); val buf = FloatArray(512)
                    var done = 0
                    while (done < frames) { val k = minOf(256, frames - done); e.render(buf, k); for (i in 0 until k) out[done + i] = buf[2 * i]; done += k }
                    e.pause(); repeat(60) { e.render(buf, 256) } // let tails die
                    out
                }
            }
            val pairs = playThrice(p); val x1 = playThrice(solo1); val x2 = playThrice(solo2)
            val l1 = (0 until 3).map { db(band(pairs[it], m1, 0.25, 0.75)) }; val l2 = (0 until 3).map { db(band(pairs[it], m2, 0.25, 0.75)) }
            val a1 = (0 until 3).map { db(band(x1[it], m1, 0.25, 0.75)) }; val a2 = (0 until 3).map { db(band(x2[it], m2, 0.25, 0.75)) }
            println("D1 repeat preset=$preset $m1+$m2: note1 in pair ${l1.map { "%.1f".format(it) }} alone ${a1.map { "%.1f".format(it) }} | note2 in pair ${l2.map { "%.1f".format(it) }} alone ${a2.map { "%.1f".format(it) }} | pair-vs-alone note1 ${(0 until 3).map { "%+.1f".format(l1[it] - a1[it]) }} note2 ${(0 until 3).map { "%+.1f".format(l2[it] - a2[it]) }}")
        }
    }


    // ------------------------------------------------------------------ intervals at the engine output: fifths and octaves across the neck, and the audible beat of a power chord
    @Test fun fifthsAndOctavesAtTheEngineOutputAreWithinFiveCents() {
        assumeTrue("bank missing", bank != null)
        val solo = HashMap<Int, Double>()
        fun hzOf(midi: Int): Double = solo.getOrPut(midi) {
            // Put the pitch on the lowest string that reaches it (what KEEP_SOUNDING_PITCH would do), velocity 96 (mf layer).
            val s = guitar.tuning.indices.first { midi - guitar.tuning[it] in 0..24 }
            val x = render(project(listOf(beat(Fraction.ZERO, 1, note(s, midi - guitar.tuning[s]))), "rw.guitar.clean", neutral), 1.5)
            pitchHz(x, sr, 0.3, 1.2, Dsp.midiToHz(midi.toDouble()))
        }
        val wide = ArrayList<String>()
        val rows = ArrayList<String>()
        for (low in 33..76) for (semis in listOf(7, 12)) {
            val high = low + semis
            if (high > 88) continue
            val err = cents(hzOf(high), hzOf(low)) - semis * 100.0
            rows += "$low+$semis=${"%+.1f".format(err)}"
            if (abs(err) > 5.0) wide += "$low+$semis: ${"%+.1f".format(err)} c"
        }
        println("D1 interval errors (cents, mf layer, engine output): $rows")
        println("D1 interval: ${wide.size} of ${rows.size} fifths/octaves are more than 5 cents off: $wide")
        // Beating of the coincident partial (3 x root = 2 x fifth) in a rendered power chord, 100 ms hops over the sustain.
        for ((s1, f1, s2, f2) in listOf(listOf(0, 0, 1, 0), listOf(1, 0, 2, 2), listOf(1, 1, 2, 3), listOf(1, 2, 2, 4), listOf(2, 0, 3, 2), listOf(2, 1, 3, 3))) {
            val low = guitar.pitch(s1, f1); val high = guitar.pitch(s2, f2)
            val x = render(project(listOf(beat(Fraction.ZERO, 1, note(s1, f1), note(s2, f2))), "rw.guitar.clean", neutral), 1.9)
            val fr = 3 * hzOf(low); val ff = 2 * hzOf(high)
            val beatHz = abs(fr - ff)
            val hops = (0 until 14).map { k -> db(tone(x, (fr + ff) / 2, 0.3 + k * 0.1, 0.4 + k * 0.1)) }
            val depth = hops.max() - hops.min()
            println("D1 beat power chord $low+$high: partial 3/2 at ${"%.1f".format(fr)}/${"%.1f".format(ff)} Hz -> ${"%.2f".format(beatHz)} Hz beat, level over 0.3-1.7 s varies ${"%.1f".format(depth)} dB: ${hops.map { "%.0f".format(it) }}")
        }
        assertTrue("intervals off by more than 5 cents: $wide", wide.isEmpty())
    }

    private fun rmsAt(x: FloatArray, rate: Int, from: Double, to: Double): Double {
        val a = (from * rate).toInt().coerceIn(0, x.size); val b = (to * rate).toInt().coerceIn(a, x.size)
        if (b <= a) return 0.0
        var s = 0.0; for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / (b - a))
    }
}
