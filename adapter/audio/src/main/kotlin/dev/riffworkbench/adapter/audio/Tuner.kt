package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.AudioInputSource
import dev.riffworkbench.ports.Tuner
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * YIN fundamental-frequency estimator (de Cheveigné & Kawahara 2002): difference function, cumulative mean
 * normalisation, absolute threshold and parabolic refinement. Allocation-free after construction.
 */
class YinPitchDetector(private val sampleRate: Int, private val window: Int = 1024, private val threshold: Float = 0.12f) {
    private val half = window / 2
    private val diff = FloatArray(half)
    data class Estimate(val hz: Double, val confidence: Double)

    fun detect(samples: FloatArray, offset: Int = 0, minHz: Double = 25.0, maxHz: Double = 2000.0): Estimate? {
        require(samples.size - offset >= window) { "Zu wenige Samples" }
        val tauMin = (sampleRate / maxHz).toInt().coerceAtLeast(2)
        val tauMax = (sampleRate / minHz).toInt().coerceAtMost(half - 2)
        var energy = 0f
        for (i in 0 until window) energy += samples[offset + i] * samples[offset + i]
        if (energy / window < 1e-7f) return null
        diff[0] = 0f
        for (tau in 1 until half) {
            var sum = 0f
            for (j in 0 until half) { val d = samples[offset + j] - samples[offset + j + tau]; sum += d * d }
            diff[tau] = sum
        }
        var running = 0f
        diff[0] = 1f
        for (tau in 1 until half) { running += diff[tau]; diff[tau] = if (running == 0f) 1f else diff[tau] * tau / running }
        var tau = tauMin
        var found = -1
        while (tau <= tauMax) {
            if (diff[tau] < threshold) { while (tau + 1 <= tauMax && diff[tau + 1] < diff[tau]) tau++; found = tau; break }
            tau++
        }
        if (found < 0) {
            // No dip below threshold: take the global minimum if it is still reasonably periodic.
            var best = tauMin; for (t in tauMin..tauMax) if (diff[t] < diff[best]) best = t
            if (diff[best] > 0.35f) return null
            found = best
        }
        val x0 = diff[found - 1]; val x1 = diff[found]; val x2 = diff[found + 1]
        val denom = x0 - 2 * x1 + x2
        val refined = if (denom != 0f) found + 0.5 * (x0 - x2) / denom else found.toDouble()
        return Estimate(sampleRate / refined, (1.0 - x1).coerceIn(0.0, 1.0))
    }

    companion object {
        fun reading(hz: Double, confidence: Double, timestampNanos: Long, referenceA4: Double = 440.0): TuningReading {
            val midiExact = 69 + 12 * ln(hz / referenceA4) / ln(2.0)
            val midi = midiExact.roundToInt().coerceIn(0, 127)
            return TuningReading(Pitch(midi), (midiExact - midi) * 100.0, confidence, timestampNanos)
        }
    }
}

/** C-TUNER: reads the input on its own thread, decimates to 12 kHz and emits readings ~25 times per second. */
class InputTuner(
    private val sourceFactory: () -> AudioInputSource,
    private val threadStarter: (String, () -> Unit) -> Thread = { name, body -> Thread(body, name).apply { isDaemon = true; start() } },
    private val clock: () -> Long = System::nanoTime,
) : Tuner {
    private val flow = MutableSharedFlow<TuningReading>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val readings: SharedFlow<TuningReading> = flow
    @Volatile private var running = false
    private var source: AudioInputSource? = null
    private var thread: Thread? = null

    private val lock = Any()

    override suspend fun start(inputDeviceId: String?): Outcome<Unit> = synchronized(lock) { startLocked() }

    private fun startLocked(): Outcome<Unit> {
        if (running) return Outcome.Success(Unit)
        val s = sourceFactory()
        val rate = when (val r = s.open(48_000)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        source = s; running = true
        val factor = (rate / 12_000).coerceAtLeast(1)
        val detector = YinPitchDetector(rate / factor, 1024)
        thread = threadStarter("riff-tuner") {
            val input = FloatArray(512 * factor)
            val ring = FloatArray(1024)
            var filled = 0
            var lp1 = 0f; var lp2 = 0f
            val a = 0.25f
            while (running) {
                val n = s.read(input, input.size)
                if (n < 0) break
                var i = 0
                while (i + factor <= n) {
                    // Two cascaded one-pole low-passes as a cheap anti-alias filter before decimation.
                    var acc = 0f
                    for (k in 0 until factor) { lp1 += a * (input[i + k] - lp1); lp2 += a * (lp1 - lp2); acc = lp2 }
                    ring[filled++] = acc
                    i += factor
                    if (filled == ring.size) {
                        detector.detect(ring)?.let { e -> if (e.confidence > 0.75) flow.tryEmit(YinPitchDetector.reading(e.hz, e.confidence, clock())) }
                        // Hop of 512 samples (~43 ms at 12 kHz): keep the newer half for overlap.
                        System.arraycopy(ring, 512, ring, 0, ring.size - 512)
                        filled = ring.size - 512
                    }
                }
            }
        }
        return Outcome.Success(Unit)
    }

    override suspend fun stop(): Outcome<Unit> = synchronized(lock) {
        running = false
        source?.close(); source = null
        thread?.join(1000); thread = null
        Outcome.Success(Unit)
    }
}
