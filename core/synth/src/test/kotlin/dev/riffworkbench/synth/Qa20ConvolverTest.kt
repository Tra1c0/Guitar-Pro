package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA cycle 20: the cabinet convolver delays its channel by one 64-frame partition (1.3 ms at 48 kHz). The engine compensates
 * that latency (every other channel, the metronome and the clips are delayed to the slowest chain), which only works if the
 * declared latency is the real one: the output must be the direct linear convolution delayed by exactly [PartitionedConvolver.latency].
 */
class Qa20ConvolverTest {
    private fun direct(ir: FloatArray, x: FloatArray) = FloatArray(x.size) { t -> var s = 0.0; for (k in ir.indices) if (t - k >= 0) s += ir[k].toDouble() * x[t - k]; s.toFloat() }

    @Test fun convolverIsTheDirectConvolutionDelayedByItsDeclaredLatency() {
        var seed = 12345
        fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 32768f - 1f }
        val failures = ArrayList<String>()
        for (taps in listOf(1, 37, 64, 65, 200, 1024, 1500)) for (splits in listOf(intArrayOf(1), intArrayOf(64), intArrayOf(192), intArrayOf(37, 256, 5))) {
            val ir = FloatArray(taps) { rnd() * 0.5f / (1 + it / 40f) }
            val x = FloatArray(4000) { if (it == 0) 1f else rnd() * 0.3f }
            val c = PartitionedConvolver(ir)
            val ref = direct(ir, x)
            val y = x.copyOf()
            var pos = 0; var k = 0
            while (pos < y.size) { val n = minOf(splits[k++ % splits.size], y.size - pos); val tmp = y.copyOfRange(pos, pos + n); c.process(tmp, n); tmp.copyInto(y, pos); pos += n }
            var err = 0.0; var peak = 0.0
            for (i in y.indices) { val r = if (i >= c.latency) ref[i - c.latency] else 0f; err = maxOf(err, abs((y[i] - r).toDouble())); peak = maxOf(peak, abs(r.toDouble())) }
            if (err > 1e-5 * maxOf(1.0, peak)) failures += "taps $taps splits ${splits.toList()}: max |err| ${"%.2e".format(err)} against the convolution delayed by ${c.latency}"
        }
        assertTrue("convolver: $failures", failures.isEmpty())
    }

    /** The rack reports the cabinet latency and delays the channels without a cabinet (drums) by it; a rack without a cabinet has none. */
    @Test fun rackCompensatesTheCabinetLatency() {
        var n = 0
        fun id() = EntityId("q20v${n++}")
        val m = listOf(Measure(id()))
        fun t(def: InstrumentDefinition, preset: String) = Track(id(), preset, def, listOf(Staff(id(), bars = listOf(Bar(id(), m[0].id, List(4) { Voice(id(), it, emptyList()) })))), SoundAssignment(presetId = preset))
        val drums = InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0)
        val band = Project(id = id(), measures = m, tracks = listOf(t(InstrumentDefinition(), "rw.guitar.highgain"), t(InstrumentDefinition(InstrumentKind.BASS, listOf(28, 33, 38, 43), List(4) { 0 }), "rw.bass.finger"), t(drums, "rw.drums.rock")))
        val rack = ChannelRack.build(band, 48_000, 1024)
        println("Q20V rack latency ${rack.latencyFrames}, channel latency/compensation ${rack.channels.map { "${it.latency}/${it.compensation}" }}")
        assertEquals(64, rack.latencyFrames)
        assertEquals(listOf(0, 64, 64), rack.channels.map { it.compensation })
        assertEquals(0, ChannelRack.build(band.copy(tracks = band.tracks.drop(1)), 48_000, 1024).latencyFrames)
    }
}
