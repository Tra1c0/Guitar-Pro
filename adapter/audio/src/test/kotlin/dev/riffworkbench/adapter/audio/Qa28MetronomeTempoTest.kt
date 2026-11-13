package dev.riffworkbench.adapter.audio

import dev.riffworkbench.editor.ProjectFactory
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.playback.metronomeBeat
import dev.riffworkbench.ports.AudioSink
import dev.riffworkbench.ports.SinkFormat
import dev.riffworkbench.synth.ChannelRack
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 28 „Das Metronom fühlt sich zu langsam an“ (Nutzer, echtes Handy). One test per hypothesis, all on the live path
 * EngineTransport → RealtimeHost.renderBlock → a test sink; click onsets are measured in the captured output (first sample
 * ≥ 0.01 after ≥ 50 ms of silence), never taken from the plan.
 *
 * a) Sample-rate consistency end to end: a sink that opens at another rate than the preferred 48 kHz (a device that gives
 *    44.1 kHz) must get the engine, the plan and the click spacing at *its* rate; a re-open at another rate (Bluetooth
 *    reconnect after the idle stop) as well.
 * c) Click unit per meter and the default project: shown BPM = played clicks per minute for every x/4 meter.
 * d) Live timing: click spacing = 60/BPM s exactly (±1 frame) at 60/120/150/200 BPM, with count-in, cabinet latency
 *    compensation, a seek while playing, a loop wrap and a seamless plan extension.
 */
class Qa28MetronomeTempoTest {
    private val kit = Qa20Kit("q28m")
    private val block = 192

    /** A sink at [rate] (the device's choice, whatever was asked for); records the requested rate. */
    private class RateSink(val rate: Int, private val block: Int, capacity: Int, private val latency: Int = 0) : AudioSink {
        val left = FloatArray(capacity)
        var frames = 0L; private set
        var requested = 0
        override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> { requested = preferredSampleRate; return Outcome.Success(SinkFormat(rate, block, latency, "q28-$rate")) }
        override fun write(buffer: FloatArray, frames: Int): Int {
            for (i in 0 until frames) { val k = (this.frames + i).toInt(); if (k < left.size) left[k] = buffer[2 * i] }
            this.frames += frames; return frames
        }
        override fun presentedFrame(): Long? = presentedFrameOrNegative()
        override fun presentedFrameOrNegative(): Long = (frames - latency).coerceAtLeast(0)
        override fun close() {}
    }

    private fun onsets(x: FloatArray, n: Int, rate: Int): List<Long> {
        val out = ArrayList<Long>(); var silent = rate
        for (i in 0 until n) { if (abs(x[i]) >= 0.01f) { if (silent >= rate / 20) out += i.toLong(); silent = 0 } else silent++ }
        return out
    }

    /** Metronome-only piece: [bars] bars of [sig] at [qpm]; with [cabinet] a muted high-gain guitar (rack latency > 0). */
    private fun piece(qpm: Double, sig: TimeSignature = TimeSignature(), bars: Int = 4, cabinet: Boolean = false): Project {
        val m = List(bars) { Measure(kit.id(), sig) }
        val g = kit.track("Gitarre", Qa20Kit.guitarDef, emptyList(), m)
        val d = kit.track("Drums", Qa20Kit.drumsDef, emptyList(), m, kind = NotationKind.PERCUSSION)
        val t = if (cabinet) g.copy(mix = g.mix.copy(mute = true)) else d.copy(mix = d.mix.copy(mute = true))
        return kit.project(m, listOf(t), listOf(TempoChange(ScorePosition(m[0].id), qpm)))
    }

    private class Live(val clicks: List<Long>, val transport: EngineTransport, val host: RealtimeHost, val sink: RateSink)

    /** Plays [p] live at the sink's [rate]; [during] runs after every block (seek, extension …). */
    private fun live(p: Project, rate: Int, seconds: Double, countIn: Int = 0, loop: LoopRange? = null, during: suspend (Long, EngineTransport) -> Unit = { _, _ -> }): Live = runBlocking {
        val frames = (seconds * rate).toInt()
        val sink = RateSink(rate, block, frames + block, latency = 768)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 1_000_000, warmUpBlocks = 0)
        t.prepare(p, PracticeSettings(speed = 1.0, countInBars = countIn, metronome = true, loop = loop))
        check(t.play() is Outcome.Success)
        var blocks = 0L
        while (sink.frames + block <= frames) { host.renderBlock(); blocks++; during(blocks, t) }
        val clicks = onsets(sink.left, minOf(frames, sink.frames.toInt()), rate)
        t.shutdown()
        Live(clicks, t, host, sink)
    }

    private fun assertSpacing(label: String, clicks: List<Long>, beatFrames: Long, min: Int) {
        val d = clicks.zipWithNext { a, b -> b - a }
        println("Q28M $label: ${clicks.size} Klicks, Abstände ${d.minOrNull()}–${d.maxOrNull()} Frames (Soll $beatFrames)")
        assertTrue("$label: mindestens $min Klicks, gemessen ${clicks.size}", clicks.size >= min)
        for ((i, x) in d.withIndex()) assertTrue("$label: Abstand $i = $x statt $beatFrames", abs(x - beatFrames) <= 1)
    }

    // ---------------------------------------------------------------- a) sample rates

    @Test fun aSinkWithAnotherRateGetsEngineAndPlanAtItsRate() {
        for (rate in listOf(44_100, 48_000, 96_000)) {
            val r = live(piece(120.0), rate, 4.5)
            assertEquals("bevorzugt bleibt 48 kHz", 48_000, r.sink.requested)
            assertEquals("Plan auf der Senkenrate", rate, r.transport.installedPlan()!!.sampleRate)
            val stats = r.host.stats()!!
            assertEquals("Ausgabe = Senkenrate", rate, stats.sampleRate)
            assertEquals("Engine = Senkenrate (Diagnose: Ausgabe/Engine)", rate, stats.engineRate)
            // 120 BPM: half a second per click at any rate - 8 % slower would be 24 000 frames at 44.1 kHz.
            assertSpacing("120 BPM bei $rate Hz", r.clicks, rate / 2L, 8)
        }
    }

    @Test fun aReopenAtAnotherRateRecompilesTheClicks() = runBlocking {
        // First session at 48 kHz, idle stop; the next open (another route after a Bluetooth reconnect) gives 44.1 kHz.
        val rates = ArrayDeque(listOf(48_000, 44_100))
        val sinks = ArrayList<RateSink>()
        val host = RealtimeHost({ RateSink(rates.removeFirst(), block, 44_100 * 6, 512).also { sinks += it } }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 1_000_000, warmUpBlocks = 0)
        val p = piece(150.0)
        t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = true))
        check(t.play() is Outcome.Success)
        repeat(200) { host.renderBlock() }
        t.stop(); host.stop()
        check(t.play() is Outcome.Success)
        while (sinks.last().frames + block <= 44_100L * 5) host.renderBlock()
        assertEquals(44_100, t.installedPlan()!!.sampleRate)
        assertEquals(44_100, host.stats()!!.engineRate)
        // 150 BPM at 44.1 kHz: 17 640 frames per click (at the old 48 kHz plan it would be 19 200, 8.8 % slower).
        assertSpacing("150 BPM nach Neuöffnen mit 44,1 kHz", onsets(sinks.last().left, 44_100 * 5, 44_100), 17_640, 10)
        t.shutdown()
    }

    /** Mono left channel of an offline render of [p] at [rate] (same engine and plan as live, 32-bit float WAV). */
    private fun render(p: Project, rate: Int, kit: dev.riffworkbench.synth.DrumKit?): FloatArray {
        val bytes = java.io.ByteArrayOutputStream()
        val r = dev.riffworkbench.synth.OfflineMixdown(ScorePlaybackCompiler(), Qa18FirstBeatTest.bank, kit)
            .render(p, RenderOptions(sampleRate = rate, bitDepth = 32, tailSeconds = 0.5), { b, o, l -> bytes.write(b, o, l) })
        check(r is Outcome.Success)
        val d = bytes.toByteArray(); val frames = (d.size - 44) / 8
        return FloatArray(frames) { i -> val o = 44 + i * 8; Float.fromBits((d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or ((d[o + 2].toInt() and 0xFF) shl 16) or (d[o + 3].toInt() shl 24)) }
    }
    /** Fundamental in Hz by autocorrelation over [from, to) seconds, searched between 60 and 400 Hz. */
    private fun pitchHz(x: FloatArray, rate: Int, from: Double, to: Double): Double {
        val a = (from * rate).toInt(); val b = (to * rate).toInt()
        var bestLag = 0; var best = Double.NEGATIVE_INFINITY
        for (lag in rate / 400..rate / 60) { var s = 0.0; for (i in a until b) s += x[i] * x[i + lag]; if (s > best) { best = s; bestLag = lag } }
        // Parabolic refinement around the best lag.
        fun ac(l: Int): Double { var s = 0.0; for (i in a until b) s += x[i] * x[i + l]; return s }
        val y0 = ac(bestLag - 1); val y1 = ac(bestLag); val y2 = ac(bestLag + 1)
        val shift = 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2)
        return rate / (bestLag + shift)
    }
    /** Seconds from the first sample above −40 dB of the peak to the last one (the audible length of a hit). */
    private fun audibleSeconds(x: FloatArray, rate: Int): Double {
        val peak = x.maxOf { abs(it) }; val th = peak * 0.01f
        val first = x.indexOfFirst { abs(it) >= th }; val last = x.indexOfLast { abs(it) >= th }
        return (last - first).toDouble() / rate
    }

    /** a) The banks are 44.1 kHz recordings; at any engine rate the sampled guitar keeps its pitch and a drum hit its length. */
    @Test fun aSampleBanksKeepPitchAndLengthAtEveryEngineRate() {
        val bank = Qa18FirstBeatTest.bank
        org.junit.Assume.assumeTrue("sample bank not found", bank != null)
        val m = listOf(Measure(kit.id()))
        // Open A string (110 Hz), clean preset so the amp adds no pitch-like distortion products.
        val guitar = kit.project(m, listOf(kit.track("Gitarre", Qa20Kit.guitarDef, listOf(listOf(kit.beat(Fraction.ZERO, 1, kit.g(1, 0)))), m, preset = "rw.guitar.clean")),
            listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
        val pitches = listOf(44_100, 48_000, 96_000).map { rate -> rate to pitchHz(render(guitar, rate, null), rate, 0.25, 0.45) }
        println("Q28M Gitarren-Sample A2 je Engine-Rate: " + pitches.joinToString { (r, hz) -> "$r Hz → ${"%.2f".format(hz)} Hz" })
        for ((r, hz) in pitches) assertEquals("Tonhöhe bei $r Hz", pitches[0].second, hz, pitches[0].second * 0.003)
        val drumKit = Qa24AudioKit.kit
        if (drumKit != null) {
            val drums = kit.project(m, listOf(kit.track("Drums", Qa20Kit.drumsDef, listOf(listOf(kit.beat(Fraction.ZERO, 1, kit.d(38, 110)))), m, kind = NotationKind.PERCUSSION)),
                listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
            val lengths = listOf(44_100, 48_000).map { rate -> rate to audibleSeconds(render(drums, rate, drumKit), rate) }
            println("Q28M Snare-Sample hörbare Länge: " + lengths.joinToString { (r, s) -> "$r Hz → ${"%.1f".format(s * 1000)} ms" })
            assertEquals("Snare-Länge in Sekunden gleich", lengths[0].second, lengths[1].second, 0.01)
        }
    }

    // ---------------------------------------------------------------- c) click unit

    @Test fun cClickUnitPerMeterAndShownBpm() {
        val sr = 48_000
        // (meter, clicks per bar, click value) - x/4 click quarters, so the shown BPM (quarters per minute) is the click rate.
        val table = listOf(
            TimeSignature(4, 4) to (4 to Fraction.of(1, 4)), TimeSignature(3, 4) to (3 to Fraction.of(1, 4)), TimeSignature(5, 4) to (5 to Fraction.of(1, 4)),
            TimeSignature(2, 4) to (2 to Fraction.of(1, 4)), TimeSignature(6, 4) to (6 to Fraction.of(1, 4)), TimeSignature(7, 8) to (7 to Fraction.of(1, 8)),
            TimeSignature(5, 8) to (5 to Fraction.of(1, 8)), TimeSignature(3, 8) to (3 to Fraction.of(1, 8)), TimeSignature(6, 8) to (2 to Fraction.of(3, 8)),
            TimeSignature(9, 8) to (3 to Fraction.of(3, 8)), TimeSignature(12, 8) to (4 to Fraction.of(3, 8)), TimeSignature(11, 16) to (11 to Fraction.of(1, 16)),
            TimeSignature(2, 2) to (2 to Fraction.of(1, 2)))
        for ((sig, expect) in table) {
            assertEquals("Klickwert $sig", expect.second, metronomeBeat(sig))
            val plan = (ScorePlaybackCompiler().compileNow(piece(150.0, sig, bars = 2), sr) as Outcome.Success).value
            assertEquals("Klicks pro Takt $sig", expect.first * 2, plan.clicks.size)
            val perMinute = 60.0 * sr / (plan.clicks[1].frame - plan.clicks[0].frame)
            // Quarters per minute (the shown BPM) times quarters per click.
            val expected = 150.0 / (expect.second.toDouble() * 4)
            println("Q28M Klickeinheit ${sig.numerator}/${sig.denominator}: ${expect.first} Klicks/Takt, ${"%.1f".format(perMinute)} Klicks/min bei angezeigt 150 BPM")
            assertEquals("Klicks/min $sig", expected, perMinute, 0.01)
            if (sig.denominator == 4) assertEquals("x/4: angezeigtes BPM = Klicks/min", 150.0, perMinute, 0.01)
        }
    }

    @Test fun cDefaultProjectClicksAtTheShownTempo() {
        var n = 0
        val p = ProjectFactory(IdSource { EntityId("q28d-${n++}") }).create("Neu")
        assertEquals(TimeSignature(4, 4), p.measures.single().signature)
        val shown = p.tempo.first().quarterNotesPerMinute
        assertEquals(150.0, shown, 0.0)
        val muted = p.copy(tracks = p.tracks.map { it.copy(mix = it.mix.copy(mute = true)) }, measures = p.measures)
        val r = live(muted, 48_000, 2.2)
        assertSpacing("Standardprojekt 4/4 · 150 BPM", r.clicks, (48_000 * 60.0 / shown).roundToLong(), 4)
    }

    // ---------------------------------------------------------------- d) live timing

    @Test fun dSpacingAtCommonTemposWithCountInAndCabinet() {
        for (qpm in listOf(60.0, 120.0, 150.0, 200.0)) for (cabinet in listOf(false, true)) {
            val p = piece(qpm, cabinet = cabinet, bars = if (qpm < 100) 2 else 4)
            if (cabinet) assertTrue(ChannelRack.build(p, 48_000, 1024).outputLatencyFrames > 0)
            val beat = (48_000 * 60.0 / qpm).roundToLong()
            val seconds = (p.measures.size + 1) * 4 * 60.0 / qpm + 0.3
            val r = live(p, 48_000, seconds, countIn = 1)
            // Count-in (4) + plan clicks, the count-in → bar 1 transition included.
            assertSpacing("$qpm BPM, Vorzähler, ${if (cabinet) "Kabinett" else "ohne Rack"}", r.clicks, beat, 4 * (p.measures.size + 1))
        }
    }

    @Test fun dSeekWhilePlayingKeepsTheBeatGrid() {
        val qpm = 150.0; val beat = 19_200L
        val p = piece(qpm, bars = 6)
        var seekAt = -1L
        val r = live(p, 48_000, 6.0, during = { blocks, t ->
            // After ~1.3 s jump to bar 4, an eighth after its downbeat (the next click is three eighths later).
            if (blocks == 330L) { seekAt = blocks * block; t.seek(PlaybackOccurrence(ScorePosition(p.measures[3].id, Fraction.of(1, 8)), 1, 3)) }
        })
        val before = r.clicks.filter { it < seekAt }
        val after = r.clicks.filter { it >= seekAt }
        assertSpacing("vor dem Seek", before, beat, 4)
        assertSpacing("nach dem Seek", after, beat, 8)
        // The first click after the seek: three eighths of a beat... = 3/8 whole − 1/8 = the distance to beat 2 (half a beat),
        // counted from the block the seek applies in (the next block after it was posted), ±1 block of posting latency.
        val first = after.first() - seekAt
        val expected = beat / 2
        println("Q28M Seek: erster Klick ${first} Frames nach dem Seek (Soll $expected + ≤1 Block)")
        assertTrue("erster Klick nach dem Seek auf dem Raster: $first", first in expected..expected + block)
    }

    @Test fun dLoopWrapIsOneBeat() {
        val p = piece(200.0, bars = 4)
        val loop = LoopRange(PlaybackOccurrence(ScorePosition(p.measures[1].id), 1, 1), PlaybackOccurrence(ScorePosition(p.measures[2].id), 1, 2))
        val r = live(p, 48_000, 8.0, countIn = 1, loop = loop)
        // Count-in, then bars 2–3 again and again: 200 BPM = 14 400 frames, over three loop wraps.
        assertSpacing("200 BPM Loop Takte 2–3 mit Vorzähler", r.clicks, 14_400, 24)
    }

    @Test fun dSeamlessExtensionAtAnotherRate() {
        val p = piece(120.0, bars = 2)
        val extra = List(4) { Measure(EntityId("q28x-$it"), TimeSignature()) }
        val longer = p.copy(revision = p.revision - 1, measures = p.measures + extra, tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { st ->
            st.copy(bars = st.bars + extra.map { m -> Bar(kit.id(), m.id, List(4) { v -> Voice(kit.id(), v) }) }) }) })
        val r = live(p, 44_100, 10.0, countIn = 1, during = { blocks, t -> if (blocks == 500L) assertTrue(t.prepare(longer, PracticeSettings(speed = 1.0, countInBars = 1, metronome = true)) is Outcome.Success) })
        assertSpacing("120 BPM bei 44,1 kHz über das nahtlose Verlängern", r.clicks, 22_050, 19)
    }
}
