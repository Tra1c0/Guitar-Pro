package dev.riffworkbench.adapter.audio

import dev.riffworkbench.editor.RhythmEvent
import dev.riffworkbench.editor.RhythmGrid
import dev.riffworkbench.editor.RhythmQuantizer
import dev.riffworkbench.editor.TapStroke
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.AudioSink
import dev.riffworkbench.ports.SinkFormat
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 28, Aufgabe 3: the tap time map „Wanduhr → hörbarer Planframe“ over 32+ bars without drift — bar 1 and bar 32
 * equally exact (±1 ms), with seamlessly appended bars (Zyklus 27), tempo and meter changes, a latency profile (Zyklus 26),
 * an output rate other than 48 kHz, and a clock drift of ±200 ppm between System.nanoTime and the audio clock.
 *
 * The sink models a device: its DAC consumes frames at rate × (1 + ppm) per nanoTime second, the AudioTrack-like timestamp
 * (frame, nanos) is refreshed only every 0.5 s and extrapolated with the *nominal* rate, exactly like AudioTrackSink. A
 * tap is the nanoTime instant a click onset (measured in the output) leaves the speaker, plus the touch latency of the
 * profile; it is mapped [mapDelayMs] later (touch dispatch), the latency subtracted like WorkspaceViewModel does.
 *
 * Expectation: every map comes from fresh (frame, time) pairs, so the error is bounded by ppm × (timestamp age + dispatch
 * delay), never by the time since the start. The same run through a single start anchor (plan frame = elapsed nanoTime ×
 * nominal rate) drifts by ppm × 64 s — the test shows that this would miss the ±1 ms by far (sensitivity of the setup).
 */
class Qa28TapConsistencyTest {
    private val kit = Qa20Kit("q28c")
    private val block = 192

    private class DriftSink(val rate: Int, val ppm: Double, private val block: Int, capacity: Int, private val latency: Int, private val tsPeriodNanos: Long = 500_000_000L) : AudioSink {
        val left = FloatArray(capacity)
        var written = 0L; private set
        /** The test's nanoTime. */
        var clock = 0L
        private var tsFrame = 0L; private var tsNanos = 0L
        private val deviceRate get() = rate * (1 + ppm * 1e-6)
        override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> = Outcome.Success(SinkFormat(rate, block, latency, "drift"))
        override fun write(buffer: FloatArray, frames: Int): Int {
            for (i in 0 until frames) { val k = (written + i).toInt(); if (k < left.size) left[k] = buffer[2 * i] }
            written += frames
            // The device presents what was written [latency] frames ago: the clock is where that frame leaves the DAC.
            clock = maxOf(clock, Math.round((written - latency).coerceAtLeast(0) / deviceRate * 1e9))
            return frames
        }
        /** Device frame leaving the DAC at [nanos] (the hardware clock). */
        fun deviceFrameAt(nanos: Long): Double = nanos * deviceRate / 1e9
        override fun presentedFrame(): Long? = presentedFrameOrNegative()
        override fun presentedFrameOrNegative(): Long {
            if (clock - tsNanos >= tsPeriodNanos || tsNanos == 0L) { tsFrame = deviceFrameAt(clock).toLong(); tsNanos = clock }
            return tsFrame + (clock - tsNanos) * rate / 1_000_000_000L
        }
        override fun close() {}
    }

    private fun bars(n: Int, sig: TimeSignature) = List(n) { Measure(kit.id(), sig) }

    /** 32 bars: 4/4 at 120, 3/4 from bar 9, 7/8 at 140 from bar 17, 4/4 at 96 from bar 25, 5/4 bars 29–32; muted guitar (cabinet). */
    private fun piece(): Project {
        val m = bars(8, TimeSignature()) + bars(8, TimeSignature(3, 4)) + bars(8, TimeSignature(7, 8)) + bars(4, TimeSignature()) + bars(4, TimeSignature(5, 4))
        val g = kit.track("Gitarre", Qa20Kit.guitarDef, emptyList(), m)
        return kit.project(m, listOf(g.copy(mix = g.mix.copy(mute = true))), listOf(
            TempoChange(ScorePosition(m[0].id), 120.0), TempoChange(ScorePosition(m[16].id), 140.0), TempoChange(ScorePosition(m[24].id), 96.0)))
    }
    /** The first [n] bars of [p] (the tapping copy before it is extended), same ids. */
    private fun head(p: Project, n: Int, revision: Long) = p.copy(revision = revision, measures = p.measures.take(n),
        tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.take(n)) }) })

    private class Result(val errorsMs: List<Pair<Int, Double>>, val anchorErrorsMs: List<Double>, val taps: List<TapStroke>, val plan: PlaybackPlan)

    private fun run(rate: Int, ppm: Double, latencyMs: Double, mapDelayMs: Double, extend: Boolean): Result = runBlocking {
        val full = piece()
        val first = if (extend) head(full, 12, -1) else full.copy(revision = -1)
        val plan0 = Qa20Kit.plan(full)  // only for the length estimate
        val seconds = plan0.totalFrames.toDouble() / 48_000 + 2.5 + 1.0
        val frames = (seconds * rate).toInt()
        val sink = DriftSink(rate, ppm, block, frames + block, latency = 1536)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 1_000_000, warmUpBlocks = 0)
        val settings = PracticeSettings(speed = 1.0, countInBars = 1, metronome = true)
        t.prepare(first, settings)
        check(t.play() is Outcome.Success)
        var extended = !extend
        val onsets = ArrayList<Long>(); var silent = rate; var scanned = 0
        val pending = ArrayList<Long>()
        val mapped = ArrayList<Pair<Long, Double>>()   // onset sink frame -> mapped plan frame (latency subtracted)
        val delay = Math.round(mapDelayMs * 1e6)
        val touch = Math.round(latencyMs * 1e6)
        while (sink.written + block <= frames) {
            // Zyklus 27: the copy grows by 20 bars while it plays (about two bars before its end).
            if (!extended && host.snapshot().planFrame > t.installedPlan()!!.spans[10].startFrame) {
                assertTrue(t.prepare(full.copy(revision = -2), settings) is Outcome.Success); extended = true
            }
            host.renderBlock()
            while (scanned < minOf(sink.written, sink.left.size.toLong()).toInt()) {
                if (abs(sink.left[scanned]) >= 0.01f) { if (silent >= rate / 20) { onsets += scanned.toLong(); pending += scanned.toLong() }; silent = 0 } else silent++
                scanned++
            }
            val it = pending.iterator()
            while (it.hasNext()) {
                val s = it.next()
                // The instant sink frame s leaves the DAC (device clock), the finger lands [touch] later.
                val presentedAt = Math.round(s / (rate * (1 + ppm * 1e-6)) * 1e9)
                val tap = presentedAt + touch
                if (sink.clock < tap + delay) continue
                val raw = host.audiblePlanFrameAt(tap, sink.clock)
                mapped += s to raw - latencyMs * rate / 1000.0
                it.remove()
            }
        }
        val plan = t.installedPlan()!!
        t.shutdown()
        assertTrue("verlängert", extended)
        // Expected plan frames: count-in clicks (one 4/4 bar at 120 before frame 0), then every click of the plan.
        val beat = rate * 0.5
        val expected = (0 until 4).map { -4 * beat + it * beat } + plan.clicks.map { it.frame.toDouble() }
        assertEquals("alle Klicks gehört (${onsets.size})", expected.size, mapped.size)
        val errors = expected.zip(mapped).mapIndexed { i, (e, m) ->
            val bar = if (i < 4) 0 else plan.spans.indexOfFirst { e < it.endFrame } + 1
            bar to (m.second - e) * 1000.0 / rate
        }
        // Single start anchor: plan frame = (tap − first click's tap) × nominal rate + first click's frame.
        val t0 = Math.round(mapped.first().first / (rate * (1 + ppm * 1e-6)) * 1e9)
        val anchor = expected.zip(mapped).map { (e, m) ->
            val tapAt = Math.round(m.first / (rate * (1 + ppm * 1e-6)) * 1e9)
            ((tapAt - t0) * rate / 1e9 + expected.first() - e) * 1000.0 / rate
        }
        val taps = mapped.map { (_, f) -> TapStroke(f, f + 0.1 * rate) }
        Result(errors, anchor, taps, plan)
    }

    private fun check(label: String, r: Result) {
        val firstBar = r.errorsMs.filter { it.first == 1 }.maxOf { abs(it.second) }
        val lastBar = r.errorsMs.filter { it.first == 32 }.maxOf { abs(it.second) }
        val worst = r.errorsMs.maxOf { abs(it.second) }
        val anchor = r.anchorErrorsMs.maxOf { abs(it) }
        println("Q28C $label: ${r.errorsMs.size} Klicks, Takt 1 max ${"%.3f".format(firstBar)} ms, Takt 32 max ${"%.3f".format(lastBar)} ms, gesamt ${"%.3f".format(worst)} ms; einmaliger Anker: ${"%.2f".format(anchor)} ms")
        for ((bar, e) in r.errorsMs) assertTrue("$label: Takt $bar um $e ms daneben", abs(e) <= 1.0)
    }

    @Test fun thirtyTwoBarsWithoutDriftAt48k() = check("48 kHz, 0 ppm, Lautsprecher", run(48_000, 0.0, 0.0, 30.0, extend = false))
    @Test fun clockDriftPlus200ppmWithAppendedBars() {
        val r = run(48_000, 200.0, 180.0, 60.0, extend = true)
        check("48 kHz, +200 ppm, BT-Profil 180 ms, angehängt", r)
        assertTrue("der einmalige Anker wäre messbar daneben (Empfindlichkeit)", r.anchorErrorsMs.maxOf { abs(it) } > 5.0)
    }
    @Test fun clockDriftMinus200ppmAt44k() {
        val r = run(44_100, -200.0, 40.0, 250.0, extend = true)
        check("44,1 kHz, −200 ppm, Profil 40 ms, Zustellung 250 ms, angehängt", r)
        assertTrue(r.anchorErrorsMs.maxOf { abs(it) } > 5.0)
    }

    /** End to end: taps exactly on every click over 32 bars, quantised (1/16 and Auto) → every bar the same figure (quarters / clicks). */
    @Test fun tapsOnEveryClickGiveIdenticalBars() {
        val r = run(44_100, 200.0, 120.0, 80.0, extend = true)
        val spans = r.plan.spans
        for (grid in listOf(RhythmGrid.SIXTEENTH, RhythmGrid.AUTO)) {
            // Count-in taps fall before the range and are discarded.
            val q = RhythmQuantizer.quantize(r.taps, spans, 44_100, grid) { false }
            assertEquals("$grid: 32 Takte", 32, q.bars.size)
            val expectedOnsets = r.plan.clicks.size
            assertEquals("$grid: ein Anschlag je Klick", expectedOnsets, q.onsets)
            for ((i, bar) in q.bars.withIndex()) {
                val onsets = bar.events.filter { it.kind == RhythmEvent.Kind.ONSET }.map { it.offset }
                val clicks = r.plan.clicks.filter { it.frame >= spans[i].startFrame && it.frame < spans[i].endFrame }
                    .map { c -> Fraction.of(Math.round(spans[i].offsetAt(c.frame.toDouble()) * 3840), 3840) }
                assertEquals("$grid: Takt ${i + 1} Anschläge auf den Klicks", clicks, onsets)
            }
            // Bars of the same meter are written identically.
            val bySig = q.bars.groupBy { b -> b.length }
            for ((len, group) in bySig) assertEquals("$grid: alle Takte der Länge $len gleich notiert", 1, group.map { b -> b.events.map { it.offset to it.duration } }.distinct().size)
        }
    }
}
