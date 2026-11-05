package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.AudioInputSource
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.random.Random
import kotlinx.coroutines.*
import org.junit.Test

/** Accuracy measurements of InputTuner / YinPitchDetector against synthetic reference signals. Prints tables. */
class DspKitTuner {
    class Run(val readings: List<Pair<TuningReading, Long>>, val rate: Int)

    /** Feeds [signal] through a real InputTuner at [rate]; returns readings with the input-sample position at emission. */
    fun runTuner(rate: Int, signal: FloatArray, chunk: Int = 4096): Run {
        val delivered = AtomicLong(0)
        val out = Collections.synchronizedList(ArrayList<Pair<TuningReading, Long>>())
        val threads = ArrayList<Thread>()
        val positions = Collections.synchronizedList(ArrayList<Long>())
        val tuner = InputTuner({
            object : AudioInputSource {
                var p = 0
                override fun open(preferredSampleRate: Int) = Outcome.Success(rate)
                override fun read(buffer: FloatArray, frames: Int): Int {
                    if (p >= signal.size) return -1
                    Thread.sleep(0, 200_000)
                    val n = minOf(frames, signal.size - p, chunk)
                    System.arraycopy(signal, p, buffer, 0, n); p += n; delivered.set(p.toLong()); return n
                }
                override fun close() {}
            }
        }, threadStarter = { name, body -> Thread(body, name).also { threads += it } }, clock = { delivered.get().also { positions += it } })
        runBlocking {
            val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { tuner.readings.collect { out += it to it.timestampNanos } }
            tuner.start(null)
            threads.forEach { it.start() }
            threads.forEach { it.join(60_000) }
            delay(50)
            job.cancel()
        }
        return Run(out.toList(), rate)
    }

    fun cents(hz: Double) = 1200 * ln(hz / 440.0) / ln(2.0) + 6900
    fun readingCents(r: TuningReading) = r.pitch.midiNumber * 100.0 + r.deviationCents

    enum class Kind { SINE, HARMONIC, MISSING_FUND, DECAY, NOISE20, NOISE40, DC, CLIP, BRIGHT }

    fun make(kind: Kind, hz: Double, rate: Int, seconds: Double, rnd: Random): FloatArray {
        val n = (rate * seconds).toInt()
        val amps = when (kind) {
            Kind.SINE -> doubleArrayOf(1.0)
            Kind.MISSING_FUND -> doubleArrayOf(0.0, 1.0, 0.8, 0.5, 0.3)
            Kind.BRIGHT -> doubleArrayOf(0.3, 1.0, 0.9, 0.7, 0.5, 0.4, 0.3, 0.2)
            else -> doubleArrayOf(1.0, 0.9, 0.6, 0.35, 0.2, 0.1)
        }
        val ph = DoubleArray(amps.size) { rnd.nextDouble() * 2 * PI }
        val x = FloatArray(n)
        var rms = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            var s = 0.0
            for (h in amps.indices) { val f = hz * (h + 1); if (f < rate / 2.0 && amps[h] > 0) s += amps[h] * sin(2 * PI * f * t + ph[h]) }
            if (kind == Kind.DECAY) s *= exp(-t / 0.8)
            x[i] = s.toFloat(); rms += s * s
        }
        rms = sqrt(rms / n)
        val norm = 0.3 / rms
        for (i in 0 until n) x[i] = (x[i] * norm).toFloat()
        when (kind) {
            Kind.NOISE20, Kind.NOISE40 -> { val k = if (kind == Kind.NOISE20) 0.1 else 0.01; for (i in 0 until n) x[i] += (0.3 * k * sqrt(3.0) * (2 * rnd.nextDouble() - 1)).toFloat() }
            Kind.DC -> for (i in 0 until n) x[i] += 0.3f
            Kind.CLIP -> for (i in 0 until n) x[i] = (x[i] * 6f).coerceIn(-0.5f, 0.5f)
            else -> {}
        }
        return x
    }

    data class Stat(val n: Int, val median: Double, val maxAbs: Double, val octave: Int, val wrong: Int, val firstMs: Double)

    fun stat(run: Run, hz: Double): Stat {
        val target = cents(hz)
        val errs = run.readings.map { readingCents(it.first) - target }
        if (errs.isEmpty()) return Stat(0, Double.NaN, Double.NaN, 0, 0, Double.NaN)
        val settled = errs.drop(2).ifEmpty { errs }
        val oct = errs.count { abs(abs(it) - 1200) < 100 || abs(abs(it) - 1902) < 100 || abs(abs(it) - 2400) < 100 }
        val wrong = errs.count { abs(it) > 50 }
        val sorted = settled.sorted()
        return Stat(errs.size, sorted[sorted.size / 2], settled.maxOf { abs(it) }, oct, wrong, run.readings.first().second * 1000.0 / run.rate)
    }

    val freqs = listOf("B0" to 30.868, "E1" to 41.203, "A1" to 55.0, "B1" to 61.735, "E2" to 82.407, "A2" to 110.0, "D3" to 146.83, "G3" to 196.0,
        "B3" to 246.94, "E4" to 329.63, "E5" to 659.26, "A5" to 880.0, "C6" to 1046.5, "E6" to 1318.5)

    fun accuracyTable() {
        val rnd = Random(7)
        val detune = 2.0.pow(7.3 / 1200) // +7.3 cents so nothing sits on a grid point
        val bad = ArrayList<String>()
        for (rate in listOf(44_100, 48_000, 96_000)) for (kind in Kind.entries) for ((name, f0) in freqs) {
            val hz = f0 * detune
            val st = stat(runTuner(rate, make(kind, hz, rate, 1.5, rnd)), hz)
            val line = "rate=$rate ${kind.name.padEnd(12)} $name ${"%.2f".format(hz)}Hz n=${st.n} med=${"%.2f".format(st.median)} max=${"%.2f".format(st.maxAbs)} oct=${st.octave} wrong=${st.wrong} first=${"%.0f".format(st.firstMs)}ms"
            println(line)
            val clean = kind in setOf(Kind.SINE, Kind.HARMONIC, Kind.DC, Kind.NOISE40)
            if (st.n == 0 || st.wrong > 0 || (clean && st.maxAbs > 3)) bad += line
        }
        println("==== OUTLIERS (${bad.size}) ====")
        bad.forEach { println(it) }
    }

    fun lowRateInputsAndBass() {
        val rnd = Random(3)
        for (rate in listOf(8_000, 11_025, 16_000, 22_050, 32_000, 44_100)) for ((name, hz) in freqs.take(4)) {
            val st = stat(runTuner(rate, make(Kind.HARMONIC, hz, rate, 1.5, rnd)), hz)
            println("LOWRATE rate=$rate $name n=${st.n} med=${"%.2f".format(st.median)} wrong=${st.wrong}")
        }
    }

    fun silenceNoiseAndHighComponents() {
        val rnd = Random(11)
        for (rate in listOf(44_100, 48_000, 96_000)) {
            val n = rate * 2
            val tests = linkedMapOf<String, FloatArray>(
                "zeros" to FloatArray(n),
                "white-60dB" to FloatArray(n) { (0.001 * sqrt(3.0) * (2 * rnd.nextDouble() - 1)).toFloat() },
                "white-30dB" to FloatArray(n) { (0.0316 * sqrt(3.0) * (2 * rnd.nextDouble() - 1)).toFloat() },
                "white-6dB" to FloatArray(n) { (0.5 * sqrt(3.0) * (2 * rnd.nextDouble() - 1)).toFloat() },
                "denormal" to FloatArray(n) { 1e-30f },
            )
            for (f in listOf(2500.0, 3000.0, 5000.0, 7000.0, 8000.0, 10000.0, 11000.0, 13000.0, 15000.0, 20000.0)) if (f < rate / 2) tests["sine$f"] = FloatArray(n) { (0.5 * sin(2 * PI * f * it / rate)).toFloat() }
            for (f in listOf(5000.0, 10000.0, 11000.0)) if (f < rate / 2) tests["A2+sine$f(-6dB)"] = FloatArray(n) { (0.25 * sin(2 * PI * 110.0 * it / rate) + 0.125 * sin(2 * PI * f * it / rate)).toFloat() }
            for ((label, x) in tests) {
                val r = runTuner(rate, x)
                val hzs = r.readings.map { 440.0 * 2.0.pow((readingCents(it.first) - 6900) / 1200) }
                println("SIL rate=$rate $label readings=${r.readings.size} hz=${hzs.take(5).map { "%.1f".format(it) }} conf=${r.readings.take(3).map { "%.2f".format(it.first.confidence) }}")
            }
        }
    }

    fun readingConversionBoundaries() {
        for (c in listOf(-50.01, -50.0, -49.99, 0.0, 49.99, 50.0, 50.01)) {
            val hz = 440.0 * 2.0.pow(c / 1200)
            val r = YinPitchDetector.reading(hz, 1.0, 0)
            println("CONV +${c}c -> midi=${r.pitch.midiNumber} dev=${"%.4f".format(r.deviationCents)}")
        }
        println("CONV A4=432 440Hz -> " + YinPitchDetector.reading(440.0, 1.0, 0, 432.0).let { "${it.pitch.midiNumber} ${it.deviationCents}" })
        for (hz in listOf(7.0, 5.0, 13000.0, 30000.0)) println("CONV $hz -> " + YinPitchDetector.reading(hz, 1.0, 0).let { "${it.pitch.midiNumber} ${it.deviationCents}" })
        for (hz in listOf(0.0, -10.0, Double.NaN, Double.POSITIVE_INFINITY)) println("CONV $hz -> " + runCatching { YinPitchDetector.reading(hz, 1.0, 0).let { "${it.pitch.midiNumber} ${it.deviationCents}" } })
    }

    /** Direct detector fuzz: refined estimate must stay near the integer lag and the result must be finite and positive. */
    fun detectorRefinementFuzz() {
        val rnd = Random(5)
        val yin = YinPitchDetector(12_000, 1024)
        var bad = 0; var total = 0; var outOfRange = 0
        val examples = ArrayList<String>()
        repeat(20_000) { k ->
            val x = FloatArray(1024)
            val mode = k % 4
            val f = when (mode) { 0 -> 1500 + rnd.nextDouble() * 4400; 1 -> 20 + rnd.nextDouble() * 2000; 2 -> 1900 + rnd.nextDouble() * 300; else -> 0.0 }
            for (i in x.indices) x[i] = when (mode) { 3 -> (rnd.nextDouble() * 2 - 1).toFloat() * (if (i % 7 == 0) 1f else 0.1f); else -> (0.5 * sin(2 * PI * f * i / 12_000 + k)).toFloat() + (0.01 * rnd.nextDouble()).toFloat() }
            val e = yin.detect(x) ?: return@repeat
            total++
            if (!e.hz.isFinite() || e.hz <= 0) { bad++; if (examples.size < 5) examples += "mode=$mode f=$f -> ${e.hz}" }
            else if (e.hz > 2100 || e.hz < 23) { outOfRange++; if (examples.size < 10) examples += "RANGE mode=$mode f=${"%.1f".format(f)} -> ${"%.1f".format(e.hz)} conf=${"%.2f".format(e.confidence)}" }
        }
        println("FUZZ detections=$total nonFinite=$bad outOfRange=$outOfRange"); examples.forEach { println("  $it") }
    }
}
