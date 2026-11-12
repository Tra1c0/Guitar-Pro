package dev.riffworkbench.adapter.audio

import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.SR
import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.f
import dev.riffworkbench.model.*
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 27 „Rhythmus tippen: Takte anhängen“, transport: the tapping copy carries buffer bars after the end of the piece
 * and is extended while it plays (EngineTransport.prepare with the longer copy, same project id, new revision). A plan
 * that only appends goes in seamlessly (HostCommand.Reprogram.seamless): no remap, no seek, no released voices.
 *
 * Live path EngineTransport → RealtimeHost.renderBlock → Qa20Sink, rendered block by block on the test thread.
 * - Extending while playing gives the bit-identical output of playing the long copy from the start (metronome only,
 *   with sounding accompaniment over the extension instant, and during the count-in).
 * - The metronome runs on without a gap over the old end: every click onset is exactly one beat after the previous one.
 * - A tap on a click in an appended bar maps to that click's plan frame (±1 frame), like Qa25TapTimingTest.
 */
class Qa27AppendTransportTest {
    private val kit = Qa20Kit("q27t")
    private val block = 192

    /** Two written 4/4 bars at [qpm]; with [band] a guitar holding whole-note chords (cabinet latency) and drums, else muted. */
    private fun piece(qpm: Double, band: Boolean): Project {
        val measures = List(2) { Measure(kit.id()) }
        val chord = { fr: Int -> listOf(kit.beat(Fraction.ZERO, 1, kit.g(0, fr), kit.g(1, fr + 2), kit.g(2, fr + 2))) }
        val guitar = kit.track("Gitarre", Qa20Kit.guitarDef, listOf(chord(3), chord(5)), measures)
        val drums = kit.track("Drums", Qa20Kit.drumsDef, listOf(kit.drumBar(), kit.drumBar(false)), measures, kind = NotationKind.PERCUSSION)
        val tracks = if (band) listOf(guitar, drums) else listOf(guitar.copy(mix = guitar.mix.copy(mute = true)))
        return kit.project(measures, tracks, listOf(TempoChange(ScorePosition(measures[0].id), qpm)))
    }
    private val buffer = List(8) { EntityId("q27-buffer-$it") }
    /** The tapping copy: [p] plus the first [extra] buffer bars (fixed ids, empty in every track), with its own revision. */
    private fun copy(p: Project, extra: Int): Project {
        val added = buffer.take(extra).map { Measure(it, p.measures.last().signature) }
        return p.copy(revision = -1L - extra, measures = p.measures + added, tracks = p.tracks.map { t -> t.copy(staves = t.staves.map { st ->
            st.copy(bars = st.bars + added.map { m -> Bar(kit.id(), m.id, List(4) { v -> Voice(kit.id(), v) }) })
        }) })
    }

    private class Run(val sink: Qa20Sink, val host: RealtimeHost, val transport: EngineTransport, val frames: Int)

    /**
     * Plays [first] with a one-bar count-in and the metronome; once [extendAt] sink frames are written, prepares [extended]
     * (null: never). [onBlock] sees every block after it was rendered.
     */
    private fun play(first: Project, extended: Project?, extendAt: Long, seconds: Double, latency: Int = 512, onBlock: (Run) -> Unit = {}): Run = runBlocking {
        val frames = (seconds * SR).toInt()
        val sink = Qa20Sink(block, frames + block, latency)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 1_000_000, warmUpBlocks = 0)
        val settings = PracticeSettings(speed = 1.0, countInBars = 1, metronome = true)
        t.prepare(first, settings)
        check(t.play() is Outcome.Success)
        val run = Run(sink, host, t, frames)
        var done = extended == null
        while (sink.frames + block <= frames) {
            if (!done && sink.frames >= extendAt) { assertTrue(t.prepare(extended!!, settings) is Outcome.Success); done = true }
            host.renderBlock()
            onBlock(run)
        }
        t.shutdown()
        run
    }

    private fun assertIdentical(label: String, a: Qa20Sink, b: Qa20Sink, frames: Int) {
        var differing = 0; var worst = 0f; var first = -1
        for (i in 0 until frames) {
            val d = maxOf(abs(a.left[i] - b.left[i]), abs(a.right[i] - b.right[i]))
            if (d != 0f) { differing++; if (first < 0) first = i; worst = maxOf(worst, d) }
        }
        var peak = 0f; for (i in 0 until frames) peak = maxOf(peak, abs(a.left[i]))
        println("Q27T $label: $differing abweichende Frames (erster $first, max $worst), Spitze $peak")
        assertTrue("$label: hörbar", peak > 0.01f)
        assertEquals("$label: bitidentisch zum durchgehend langen Plan (erster Unterschied bei Frame $first)", 0, differing)
    }

    @Test fun extendingWhilePlayingIsBitIdenticalToTheLongCopy() {
        for (band in listOf(false, true)) {
            val p = piece(120.0, band)
            val long = copy(p, 4)
            val plan2 = Qa20Kit.plan(copy(p, 2))
            // 1 bar count-in (2 s) + 6 bars (12 s): extend when the playhead is in the first buffer bar (written frame ≈ count-in + 2 bars + latency).
            val seconds = 2.0 + 12.0 + 0.5
            val countIn = plan2.spans[0].endFrame
            // With the band: during bar 2 while its whole-note chord sounds (and the drums ring); metronome only: in buffer bar 1.
            val at = countIn + if (band) plan2.spans[1].startFrame + SR / 2 else plan2.spans[2].startFrame + 1000
            val reference = play(long, null, 0, seconds)
            val extended = play(copy(p, 2), long, at, seconds)
            assertIdentical(if (band) "Begleitung klingt über die Verlängerung" else "nur Metronom", reference.sink, extended.sink, reference.frames)
            assertEquals(Qa20Kit.plan(long).totalFrames, extended.transport.installedPlan()!!.totalFrames)
        }
        // Extended during the count-in (the copy is short and the user starts at its last bar).
        val p = piece(150.0, band = true)
        assertIdentical("Verlängerung im Vorzähler", play(copy(p, 4), null, 0, 10.0).sink, play(copy(p, 1), copy(p, 4), 4_000, 10.0).sink, (10.0 * SR).toInt())
    }

    @Test fun onlyAppendingPlansAreSeamless() {
        val t = EngineTransport(RealtimeHost({ Qa20Sink(block) }, threadStarter = { _, _ -> Thread {} }), pollMillis = 1_000_000, warmUpBlocks = 0)
        val p = piece(120.0, band = true)
        val a = Qa20Kit.plan(copy(p, 2)); val b = Qa20Kit.plan(copy(p, 4))
        assertTrue(t.appendsTo(a, b)); assertTrue(t.appendsTo(a, a))
        assertFalse("kürzer", t.appendsTo(b, a))
        assertFalse("andere Geschwindigkeit", t.appendsTo(a, Qa20Kit.plan(copy(p, 4), 0.9)))
        // An edit inside the written bars (another chord) or another tempo: the ordinary remapping path.
        val edited = copy(p, 4).let { c -> c.copy(tracks = c.tracks.mapIndexed { i, tr -> if (i != 0) tr else tr.copy(staves = tr.staves.map { st -> st.copy(bars = st.bars.mapIndexed { k, bar ->
            if (k != 1) bar else bar.copy(voices = bar.voices.map { v -> v.copy(beats = v.beats.map { bt -> bt.copy(notes = bt.notes.map { n -> n.copy(pitch = Pitch(n.pitch.midiNumber + 2), fret = (n.fret ?: 0) + 2) }) }) })
        }) }) }) }
        assertFalse("Note geändert", t.appendsTo(a, Qa20Kit.plan(edited)))
        assertFalse("Tempo geändert", t.appendsTo(a, Qa20Kit.plan(copy(p, 4).copy(tempo = listOf(TempoChange(ScorePosition(p.measures[0].id), 121.0))))))
        t.shutdown()
    }

    /** Click onsets in the output (first sample ≥ −40 dB after ≥ 50 ms of silence) and the tap map at each, after two blocks. */
    @Test fun metronomeRunsOnWithoutAGapAndTapsInAppendedBarsHitThePlanFrame() {
        for ((qpm, latency) in listOf(120.0 to 1536, 187.0 to 768)) {
            val p = piece(qpm, band = false)   // muted guitar: cabinet latency on every click
            val long = copy(p, 6)
            val plan = Qa20Kit.plan(long)
            val beat = plan.clicks[1].frame - plan.clicks[0].frame
            val countIn = plan.spans[0].endFrame
            val expected = (0 until 4).map { -countIn + it * beat } + plan.clicks.map { it.frame }
            // Extended twice, each time when the playhead (written frames ≈ presented + latency) enters the second-to-last bar.
            val c2 = copy(p, 2); val c4 = copy(p, 4)
            val onsets = ArrayList<Long>(); val mapped = ArrayList<Double>()
            val pending = ArrayList<Long>()
            var scanned = 0; var silent = SR
            var stage = 0
            val seconds = (countIn + plan.totalFrames).toDouble() / SR + 0.5
            val run = play(c2, null, 0, seconds, latency) { r ->
                val presentedPlan = r.sink.frames - latency - countIn
                if (stage == 0 && presentedPlan >= Qa20Kit.plan(c2).spans[2].startFrame) { runBlocking { r.transport.prepare(c4, PracticeSettings(speed = 1.0, countInBars = 1, metronome = true)) }; stage = 1 }
                else if (stage == 1 && presentedPlan >= Qa20Kit.plan(c4).spans[4].startFrame) { runBlocking { r.transport.prepare(long, PracticeSettings(speed = 1.0, countInBars = 1, metronome = true)) }; stage = 2 }
                while (scanned < r.sink.frames) {
                    if (abs(r.sink.left[scanned]) >= 0.01f) { if (silent >= SR / 20) { onsets += scanned.toLong(); pending += scanned.toLong() }; silent = 0 } else silent++
                    scanned++
                }
                val presented = r.sink.frames - latency
                val it = pending.iterator()
                while (it.hasNext()) {
                    val s = it.next()
                    if (presented < s + 2 * block) continue
                    mapped += r.host.audiblePlanFrameAt(Math.round(s * 1e9 / SR), Math.round(presented * 1e9 / SR))
                    it.remove()
                }
            }
            assertEquals("$qpm BPM: beide Verlängerungen", 2, stage)
            assertEquals("$qpm BPM: alle Klicks hörbar (Vorzähler + 8 Takte)", expected.size, onsets.size)
            val gaps = onsets.zipWithNext { a, b -> b - a }
            println("Q27T $qpm BPM: ${onsets.size} Klicks, Abstände ${gaps.minOrNull()}–${gaps.maxOrNull()} Frames (Schlag $beat)")
            for ((i, g) in gaps.withIndex()) assertTrue("$qpm BPM: Klickabstand $i = $g statt $beat", abs(g - beat) <= 1)
            assertEquals(expected.size, mapped.size)
            val worst = expected.zip(mapped).maxOf { (e, m) -> abs(m - e) }
            println("Q27T $qpm BPM: Tipp → Planframe größte Abweichung ${"%.1f".format(worst)} Frames, davon ${plan.clicks.count { it.frame >= plan.spans[2].startFrame }} Klicks in angehängten Takten")
            expected.zip(mapped).forEachIndexed { i, (e, m) -> assertTrue("$qpm BPM: Klick $i bei Planframe $e → $m", abs(m - e) <= 1.0) }
            assertEquals(plan.totalFrames, run.transport.installedPlan()!!.totalFrames)
        }
    }
}
