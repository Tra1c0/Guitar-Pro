package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.*
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * QA cycle 19, live against offline: the same guitar phrase (open, palm mute, dead note, natural and pinch harmonic,
 * bend, hammer-on, legato slide, a let-ring power chord; E standard, 120 bpm) rendered by the live path
 * (EngineTransport -> RealtimeHost.renderBlock, 256-frame blocks, FakeSink) and by OfflineMixdown (1024-frame blocks,
 * 32-bit float WAV), per sampled preset. Compared: lag of the best alignment (cross-correlation), onset of every note,
 * RMS difference per 50 ms window and the residual of the whole render.
 */
class Qa19LiveVsOfflineTest {
    companion object {
        const val SR = 48_000
        const val BLOCK = 256
        val presets = listOf("rw.guitar.clean", "rw.guitar.crunch", "rw.guitar.highgain")
        fun db(x: Double) = if (x <= 1e-15) -300.0 else 20 * log10(x)
        fun f1(x: Double) = String.format(java.util.Locale.ROOT, "%.1f", x)
    }
    private var n = 0
    private fun id() = EntityId("q19lo-${n++}")
    private val guitar = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), midiProgram = 29)
    private fun note(s: Int, fr: Int, v: Int = 96, vararg a: Articulation) = Note(id(), Pitch(guitar.pitch(s, fr)), s, fr, v, a.toList())
    private fun beat(o: Fraction, den: Int, vararg notes: Note) = Beat(id(), o, WrittenDuration(den), notes.toList())

    private fun phrase(preset: String): Project {
        val f = { a: Long, b: Long -> Fraction.of(a, b) }
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(f(1, 3), 200), PitchCurvePoint(Fraction.ONE, 200)))
        val bar1 = listOf(beat(f(0, 1), 4, note(0, 0)), beat(f(1, 4), 8, note(0, 0, 96, pm)), beat(f(3, 8), 8, note(0, 0, 110, pm)),
            beat(f(1, 2), 8, note(2, 7, 96, Articulation.Mark(NoteMark.DEAD))), beat(f(5, 8), 8, note(0, 12, 96, Articulation.Harmonic(HarmonicKind.NATURAL))),
            beat(f(3, 4), 4, note(2, 0, 96, Articulation.Harmonic(HarmonicKind.PINCH))))
        val bar2 = listOf(beat(f(0, 1), 4, note(2, 7, 96, bend)), beat(f(1, 4), 8, note(3, 7, 96, Articulation.Mark(NoteMark.HAMMER_ON))), beat(f(3, 8), 8, note(3, 9)),
            beat(f(1, 2), 8, note(4, 5, 96, Articulation.Slide(SlideKind.LEGATO))), beat(f(5, 8), 8, note(4, 7)),
            beat(f(3, 4), 4, note(0, 0, 100, Articulation.Mark(NoteMark.LET_RING)), note(1, 2, 100, Articulation.Mark(NoteMark.LET_RING)), note(2, 2, 100, Articulation.Mark(NoteMark.LET_RING))))
        val m = listOf(Measure(id()), Measure(id()))
        val bars = listOf(bar1, bar2)
        val staff = Staff(id(), bars = m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0) bars[i] else emptyList()) }) })
        return Project(id = id(), measures = m, tracks = listOf(Track(id(), "G", guitar, listOf(staff), SoundAssignment(presetId = preset))), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }

    private fun offline(p: Project, bank: SampleBank?, tail: Double, from: Long = 0): FloatArray {
        val bytes = java.io.ByteArrayOutputStream()
        val r = OfflineMixdown(ScorePlaybackCompiler(), bank).render(p, RenderOptions(sampleRate = SR, bitDepth = 32, tailSeconds = tail, commonStartFrame = from), { b, o, l -> bytes.write(b, o, l) })
        assertTrue("offline render", r is Outcome.Success)
        val d = bytes.toByteArray(); val frames = (d.size - 44) / 8
        return FloatArray(frames) { i -> val o = 44 + i * 8; Float.fromBits((d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or ((d[o + 2].toInt() and 0xFF) shl 16) or (d[o + 3].toInt() shl 24)) }
    }

    /** Live capture of [frames] frames from Play; [seekBar] > 0 seeks to that bar first (the transport's cursor start). */
    private fun live(p: Project, bank: SampleBank?, frames: Int, block: Int = BLOCK, seekBar: Int = 0): FloatArray = runBlocking {
        val sinks = ArrayList<FakeSink>()
        val host = RealtimeHost({ FakeSink(SR, block, 2048).also { it.capture = true; sinks += it } }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0, bankProvider = { bank })
        val t = EngineTransport(host, pollMillis = 1_000_000)
        try {
            t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false))
            if (seekBar > 0) t.seek(PlaybackOccurrence(ScorePosition(p.measures[seekBar].id), 1, seekBar))
            assertTrue("live play", t.play() is Outcome.Success)
            val mark = sinks.last().captured.size
            repeat(frames / block + 2) { host.renderBlock() }
            val c = sinks.last().captured
            FloatArray(frames) { c[mark + it] }
        } finally { t.shutdown() }
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double { var s = 0.0; val b = minOf(to, x.size); for (i in from until b) s += x[i].toDouble() * x[i]; return sqrt(s / (b - from).coerceAtLeast(1)) }
    /** Lag (frames, live behind offline when positive) of the best normalised cross-correlation within +-[maxLag]. */
    private fun bestLag(a: FloatArray, b: FloatArray, maxLag: Int, len: Int): Int {
        var best = 0; var bestC = -2.0
        for (lag in -maxLag..maxLag) {
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (i in maxLag until len - maxLag) { val u = a[i].toDouble(); val v = b[i + lag].toDouble(); xy += u * v; xx += u * u; yy += v * v }
            val c = if (xx > 0 && yy > 0) xy / sqrt(xx * yy) else -1.0
            if (c > bestC) { bestC = c; best = lag }
        }
        return best
    }
    /** First frame from [from] where the energy accumulated since [from] reaches 1 % of the energy of the next 50 ms. */
    private fun onset(x: FloatArray, from: Int): Int {
        val w = SR / 20; var total = 0.0
        for (i in from until minOf(from + w, x.size)) total += x[i].toDouble() * x[i]
        var acc = 0.0
        for (i in from until minOf(from + w, x.size)) { acc += x[i].toDouble() * x[i]; if (acc >= 0.01 * total) return i }
        return -1
    }

    /**
     * Tolerance: the live and the offline path run the same engine, rack and plan; only the block size differs (256 vs 1024),
     * and every voice and effect runs its control ticks on its own sample clock. So the renders must align at lag 0, every
     * onset within 1 frame, every 50-ms window within 0.1 dB and the residual at least 80 dB below the signal.
     */
    @Test fun livePathRendersTheGuitarPhraseLikeTheOfflineMixdown() {
        val bank = Qa18FirstBeatTest.bank
        assumeTrue("sample bank not found", bank != null)
        val failures = mutableListOf<String>()
        for (preset in presets) {
            val p = phrase(preset)
            val plan = (ScorePlaybackCompiler().compileNow(p, SR) as Outcome.Success).value
            val off = offline(p, bank, 1.0)
            val liv = live(p, bank, off.size)
            val lag = bestLag(off, liv, 512, minOf(off.size, 2 * SR))
            var worstOnset = 0; var worstOnsetAt = -1L
            for (fr in plan.notes.map { it.frame }.distinct()) {
                val a = onset(off, fr.toInt()); val b = onset(liv, fr.toInt())
                if (abs(a - b) > abs(worstOnset)) { worstOnset = b - a; worstOnsetAt = fr }
            }
            var worstWin = 0.0; var worstWinAt = -1
            val w = SR / 20
            for (k in 0 until off.size / w) {
                val ro = rms(off, k * w, (k + 1) * w); val rl = rms(liv, k * w, (k + 1) * w)
                if (db(ro) < -70 && db(rl) < -70) continue
                val d = db(rl) - db(ro)
                if (abs(d) > abs(worstWin)) { worstWin = d; worstWinAt = k }
            }
            var res = 0.0; var sig = 0.0; var maxDiff = 0.0
            for (i in off.indices) { val d = (liv[i] - off[i]).toDouble(); res += d * d; sig += off[i].toDouble() * off[i]; maxDiff = maxOf(maxDiff, abs(d)) }
            val residualDb = 10 * log10((res + 1e-30) / sig)
            val totalDb = db(rms(liv, 0, liv.size)) - db(rms(off, 0, off.size))
            println("Q19L ${preset.removePrefix("rw.guitar.")} (live blocks $BLOCK): ${off.size} frames, ${plan.notes.size} notes | best lag $lag frames | worst onset offset $worstOnset frames (note at $worstOnsetAt) | RMS live - offline ${f1(totalDb)} dB, worst 50-ms window ${"%.3f".format(worstWin)} dB (#$worstWinAt) | residual ${f1(residualDb)} dB re signal, max |diff| ${"%.2e".format(maxDiff)}")
            if (lag != 0) failures += "$preset: live lags offline by $lag frames"
            if (abs(worstOnset) > 1) failures += "$preset: onset offset $worstOnset frames at plan frame $worstOnsetAt"
            if (abs(worstWin) > 0.1) failures += "$preset: 50-ms window #$worstWinAt differs by ${"%.3f".format(worstWin)} dB"
            if (residualDb > -80.0) failures += "$preset: residual ${f1(residualDb)} dB re signal (max |diff| ${"%.2e".format(maxDiff)})"
        }
        assertTrue("live vs offline: " + failures.joinToString("; "), failures.isEmpty())
    }

    /**
     * Same comparison for the other live situations: device block sizes 192 and 480 frames (not a divisor of 1024), and a
     * start from bar 2 (cursor/seek; the export starts at the same frame via commonStartFrame, both chase the sounding notes).
     */
    @Test fun livePathMatchesOfflineForOtherBlockSizesAndAStartInBar2() {
        val bank = Qa18FirstBeatTest.bank
        assumeTrue("sample bank not found", bank != null)
        val failures = mutableListOf<String>()
        for (preset in presets) {
            val p = phrase(preset)
            val plan = (ScorePlaybackCompiler().compileNow(p, SR) as Outcome.Success).value
            val off = offline(p, bank, 1.0)
            for (block in listOf(192, 480)) {
                val liv = live(p, bank, off.size, block)
                var maxDiff = 0.0; for (i in off.indices) maxDiff = maxOf(maxDiff, abs((liv[i] - off[i]).toDouble()))
                println("Q19L ${preset.removePrefix("rw.guitar.")} (live blocks $block): max |live - offline| ${"%.2e".format(maxDiff)}")
                if (maxDiff > 1e-6) failures += "$preset block $block: max |diff| ${"%.2e".format(maxDiff)}"
            }
            val bar2 = plan.frameFor(PlaybackOccurrence(ScorePosition(p.measures[1].id), 1, 1))!!
            val off2 = offline(p, bank, 1.0, from = bar2)
            val liv2 = live(p, bank, off2.size, BLOCK, seekBar = 1)
            var maxDiff = 0.0; var at = -1; for (i in off2.indices) { val d = abs((liv2[i] - off2[i]).toDouble()); if (d > maxDiff) { maxDiff = d; at = i } }
            println("Q19L ${preset.removePrefix("rw.guitar.")} (start in bar 2 at plan frame $bar2): ${off2.size} frames, max |live - offline| ${"%.2e".format(maxDiff)} at frame $at, RMS live ${f1(db(rms(liv2, 0, liv2.size)))} / offline ${f1(db(rms(off2, 0, off2.size)))} dBFS")
            if (maxDiff > 1e-6) failures += "$preset start bar 2: max |diff| ${"%.2e".format(maxDiff)} at frame $at"
        }
        assertTrue("live vs offline: " + failures.joinToString("; "), failures.isEmpty())
    }
}
