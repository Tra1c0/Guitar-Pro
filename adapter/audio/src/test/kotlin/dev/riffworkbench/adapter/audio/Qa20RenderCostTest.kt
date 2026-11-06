package dev.riffworkbench.adapter.audio

import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.SR
import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.plan
import dev.riffworkbench.model.*
import dev.riffworkbench.synth.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA cycle 20, CPU per block: render time of RealtimeHost.renderBlock's DSP work (the host's own timing ring, the numbers
 * the diagnostics page shows) for the standard band (high-gain guitar, bass, drums) and a dense case (two guitars with
 * six-string chords in 16ths, drums in 16ths, metronome) at 48 kHz, blocks of 192 and 256 frames, after JIT warm-up.
 *
 * These are desktop-JVM values (HotSpot C2 on the build machine), not Android numbers; a phone core is several times
 * slower and ART compiles differently. The assertion only guards a gross regression (median below the block budget);
 * the printed median/p99/max against the budget are the measurement ("Q20C").
 */
class Qa20RenderCostTest {
    private val kit = Qa20Kit("q20c")

    data class Cost(val median: Double, val p99: Double, val max: Double, val budget: Double, val voicesMax: Int)

    /** Plays [p] from the top with the loop over the whole piece, [blocks] blocks after [warmBlocks] on a twin host. */
    fun measure(p: Project, block: Int, metronome: Boolean, blocks: Int = 4000, warmBlocks: Int = 6000): Cost {
        val bk = Qa18FirstBeatTest.bank
        fun run(n: Int): Pair<RealtimeHost, Int> {
            val host = RealtimeHost({ Qa20Sink(block) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0, bankProvider = { bk })
            check(host.start() is Outcome.Success)
            val pl = plan(p)
            host.post(HostCommand.Rack(ChannelRack.build(p, SR, 1024))); host.post(HostCommand.Mix(MixState.from(p, if (metronome) 1f else 0f)))
            host.post(HostCommand.Install(EngineProgram.prepare(pl))); host.post(HostCommand.Loop(0, pl.totalFrames)); host.post(HostCommand.Play(0, 0, LongArray(0)))
            // Nothing else allocates on this thread meanwhile (stats() would: a GC pause would land in the timing ring).
            var voices = 0
            repeat(n) { k -> host.renderBlock(); if (k % 512 == 511) voices = maxOf(voices, host.stats()?.voices ?: 0) }
            return host to voices
        }
        run(warmBlocks).first.stop()
        val (host, voices) = run(blocks)
        val s = host.stats()!!
        host.stop()
        return Cost(s.renderMedianMicros, s.renderP99Micros, s.renderMaxMicros, s.budgetMicros, voices)
    }

    @Test fun renderTimePerBlockStaysWellInsideTheBudget() {
        val bank = Qa18FirstBeatTest.bank
        val cases = listOf("Standardband (HG, Bass, Drums)" to (kit.standardBand(4, 150.0) to false), "dicht (2 × 6-stimmige 16tel-Akkorde, Drums 16tel, Metronom)" to (kit.denseBand(4, 150.0) to true))
        val failures = ArrayList<String>()
        for ((label, pc) in cases) for (block in listOf(192, 256)) {
            val c = measure(pc.first, block, pc.second)
            fun pct(x: Double) = "%.0f %%".format(java.util.Locale.ROOT, 100 * x / c.budget)
            println("Q20C $label, Block $block (Budget ${"%.0f".format(c.budget)} µs, Bank ${bank != null}): Median ${"%.0f".format(c.median)} µs (${pct(c.median)}), p99 ${"%.0f".format(c.p99)} µs (${pct(c.p99)}), Max ${"%.0f".format(c.max)} µs (${pct(c.max)}), Stimmen (Stichprobe) max ${c.voicesMax} [JVM-Desktop, kein Android-Wert]")
            if (c.median > c.budget) failures += "$label block $block: median ${c.median} µs > budget ${c.budget} µs"
        }
        assertTrue("render time per block: $failures", failures.isEmpty())
    }
}
