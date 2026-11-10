package dev.riffworkbench.adapter.audio

import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.SR
import dev.riffworkbench.model.*
import dev.riffworkbench.synth.ChannelRack
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 25 (Rhythmus tippen), time map „Wanduhr → hörbarer Planframe“: a tap at the instant an audible metronome click
 * leaves the speaker must map to that click's plan frame (±1 ms), through count-in, sink latency and the rack's latency
 * compensation (cabinet: every source, the click included, sounds 64 frames after its plan frame).
 *
 * Live path EngineTransport → RealtimeHost.renderBlock → Qa20Sink (presented frame = written − sink latency). Click onsets
 * are measured in the captured output (first sample ≥ −40 dB re 1 after ≥ 50 ms of silence); the tap instant is the
 * moment that sink frame is presented, on a virtual clock where "now" = presented frame / rate. The map is queried a few
 * blocks later (taps are mapped after the fact), never from the ~30 Hz poll.
 */
class Qa25TapTimingTest {
    private val kit = Qa20Kit("q25t")
    private val block = 192

    private data class Case(val label: String, val qpm: Double, val signature: TimeSignature, val sinkLatency: Int, val guitar: Boolean, val countIn: Int, val speed: Double = 1.0)

    private fun run(c: Case): List<Pair<Long, Double>> = runBlocking {
        val measures = List(2) { Measure(kit.id(), c.signature) }
        val track = if (c.guitar) kit.track("Gitarre", Qa20Kit.guitarDef, emptyList(), measures)
            else kit.track("Drums", Qa20Kit.drumsDef, emptyList(), measures, kind = NotationKind.PERCUSSION)
        val p = kit.project(measures, listOf(track.copy(mix = track.mix.copy(mute = true))), listOf(TempoChange(ScorePosition(measures[0].id), c.qpm)))
        val plan = Qa20Kit.plan(p, c.speed)
        val seconds = plan.totalFrames.toDouble() / SR + c.countIn * c.signature.length().toDouble() * 4 * 60 / c.qpm / c.speed + 0.5
        val frames = (seconds * SR).toInt()
        val sink = Qa20Sink(block, frames + block, c.sinkLatency)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 1_000_000, warmUpBlocks = 0)
        val rackLatency = ChannelRack.build(p, SR, 1024).outputLatencyFrames
        if (c.guitar) assertTrue("Kabinett-Latenz erwartet", rackLatency > 0) else assertEquals(0, rackLatency)
        t.prepare(p, PracticeSettings(speed = c.speed, countInBars = c.countIn, metronome = true))
        check(t.play() is Outcome.Success)
        // Expected plan frames of every audible click: count-in clicks before the start frame (0), then the plan's clicks.
        val perBar = c.signature.numerator.let { if (c.signature.denominator == 8 && it % 3 == 0 && it > 3) it / 3 else it }
        val beatFrames = plan.clicks[1].frame - plan.clicks[0].frame
        val countIn = if (c.countIn == 0) 0L else c.countIn * (plan.spans[0].endFrame - plan.spans[0].startFrame)
        val expected = (0 until c.countIn * perBar).map { -countIn + it * beatFrames } + plan.clicks.map { it.frame }
        val result = ArrayList<Pair<Long, Double>>()
        var onsets = 0
        var silentSince = SR
        val pending = ArrayList<Long>()
        var scanned = 0
        while (sink.frames + block <= frames) {
            host.renderBlock()
            // Detect click onsets in the new output.
            while (scanned < sink.frames) {
                val x = abs(sink.left[scanned])
                if (x >= 0.01f) { if (silentSince >= SR / 20) { pending += scanned.toLong(); onsets++ }; silentSince = 0 } else silentSince++
                scanned++
            }
            // Map every onset once it has been presented for at least two blocks (taps are mapped after the touch).
            val presented = sink.frames - c.sinkLatency
            val it = pending.iterator()
            while (it.hasNext()) {
                val s = it.next()
                if (presented < s + 2 * block) continue
                val nanos = Math.round(s * 1e9 / SR)
                val now = Math.round(presented * 1e9 / SR)
                result += s to host.audiblePlanFrameAt(nanos, now)
                it.remove()
            }
        }
        t.shutdown()
        assertEquals("${c.label}: alle Klicks hörbar", expected.size, result.size)
        expected.zip(result).map { (e, r) -> e to r.second }
    }

    private fun check(c: Case) {
        val pairs = run(c)
        val worst = pairs.maxOf { abs(it.second - it.first) }
        println("Q25T ${c.label}: ${pairs.size} Klicks, größte Abweichung ${"%.1f".format(worst)} Frames (${"%.3f".format(worst * 1000.0 / SR)} ms)")
        for ((e, r) in pairs) assertTrue("${c.label}: Klick bei Planframe $e → $r", abs(r - e) <= SR / 1000.0)
    }

    @Test fun guitarCabinetCountInAndSinkLatency() = check(Case("120 BPM 4/4, Kabinett, Vorzähler, Senke 1536", 120.0, TimeSignature(), 1536, guitar = true, countIn = 1))
    @Test fun drumsNoRackLatencyWithoutCountIn() = check(Case("200 BPM 7/8, ohne Rack-Latenz, Senke 768", 200.0, TimeSignature(7, 8), 768, guitar = false, countIn = 0))
    @Test fun slowedDownPlanFramesFollowTheSpeed() = check(Case("150 BPM 3/4 bei 70 %, Kabinett, Vorzähler", 150.0, TimeSignature(3, 4), 2048, guitar = true, countIn = 1, speed = 0.7))

    /** The map is linear between clicks too: a tap half a beat after a click is half a beat of plan frames later. */
    @Test fun betweenClicksTheMapIsLinearAndNaNWithoutOutput() = runBlocking {
        val host = RealtimeHost({ Qa20Sink(block, 0, 0) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        assertTrue(host.audiblePlanFrameAt(0L, 0L).isNaN())
        val pairs = run(Case("120 BPM 4/4", 120.0, TimeSignature(), 512, guitar = true, countIn = 0))
        val (a, b) = pairs[0] to pairs[1]
        assertEquals(b.first - a.first, Math.round(b.second - a.second))
    }
}
