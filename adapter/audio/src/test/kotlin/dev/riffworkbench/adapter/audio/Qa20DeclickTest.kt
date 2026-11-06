package dev.riffworkbench.adapter.audio

import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.SR
import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.f
import dev.riffworkbench.model.*
import kotlin.math.abs
import kotlin.math.log10
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * QA cycle 20, start/stop/seek clicks: no jump between consecutive output samples above -20 dBFS (0.1) that is not an onset,
 * when the transport stops inside a sounding note, seeks while playing, wraps a loop, pauses and plays on.
 *
 * Material per source, each rendered alone through the live path (EngineTransport -> RealtimeHost.renderBlock, 192-frame
 * blocks): high-gain and clean guitar (whole notes), bass (whole notes), drums (crash + ride), the reference clip (a 221.3 Hz
 * sine at 0.5 - it has no onsets, so every jump is a click; not a whole number of Hz, else events at whole seconds
 * would land on zero crossings and hide the cut). Bar 2 is a rest, so a seek into it, a loop wrapping into it
 * and the cut of a stop carry no new onset. A jump counts when it exceeds 0.1 and twice the largest sample step of the
 * 200 ms before the event (high gain and cymbals step far by themselves).
 */
class Qa20DeclickTest {
    private val kit = Qa20Kit("q20d")
    companion object { const val BLOCK = 192 }

    private enum class Event { STOP, SEEK, LOOP_WRAP, PAUSE, RESUME }
    private data class Source(val name: String, val clip: Boolean, val track: (List<Measure>) -> Track?)

    private fun sources(): List<Source> {
        fun whole(make: () -> Note) = listOf(listOf(kit.beat(Fraction.ZERO, 1, make())), emptyList(), listOf(kit.beat(Fraction.ZERO, 1, make())), listOf(kit.beat(Fraction.ZERO, 1, make())))
        return listOf(
            Source("Gitarre HG", false) { m -> kit.track("G", Qa20Kit.guitarDef, whole { kit.g(2, 2, 100) }, m) },
            Source("Gitarre Clean", false) { m -> kit.track("G", Qa20Kit.guitarDef, whole { kit.g(2, 2, 100) }, m, "rw.guitar.clean") },
            Source("Bass", false) { m -> kit.track("B", Qa20Kit.bassDef, whole { kit.b(1, 3) }, m, "rw.bass.pick") },
            Source("Drums (Crash+Ride)", false) { m -> kit.track("D", Qa20Kit.drumsDef, listOf(listOf(kit.beat(Fraction.ZERO, 1, kit.d(49, 120), kit.d(51, 110))), emptyList(),
                listOf(kit.beat(Fraction.ZERO, 1, kit.d(49, 120), kit.d(51, 110))), listOf(kit.beat(Fraction.ZERO, 1, kit.d(49, 120)))), m, kind = NotationKind.PERCUSSION) },
            Source("Clip (Sinus 221,3 Hz)", true) { _ -> null })
    }

    /** Output of [source] around [event]; returns the capture and the output frame at which the event's block starts. */
    private fun render(source: Source, event: Event): Pair<FloatArray, Long> = runBlocking {
        val m = List(4) { Measure(kit.id()) }
        // A silent guitar track keeps the rack (and its latency) the same for every source.
        val tracks = listOfNotNull(source.track(m), kit.track("G0", Qa20Kit.guitarDef, emptyList(), m))
        val p = kit.project(m, tracks, listOf(TempoChange(ScorePosition(m[0].id), 120.0)), if (source.clip) listOf(kit.clip()) else emptyList())
        val pcm = kit.pcm(SR * 10) { i -> 0.5 * kotlin.math.sin(i * 2 * Math.PI * 221.3 / SR) }
        val frames = 3 * SR
        val sink = Qa20Sink(BLOCK, frames + 2 * BLOCK)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0, bankProvider = { Qa18FirstBeatTest.bank })
        val t = EngineTransport(host, clipLoader = { _, _ -> Outcome.Success(pcm) }, pollMillis = 1_000_000)
        try {
            val bar = 2 * SR
            val loop = if (event == Event.LOOP_WRAP) LoopRange(PlaybackOccurrence(ScorePosition(m[1].id), 1, 1), PlaybackOccurrence(ScorePosition(m[2].id, f(1, 2)), 1, 2)) else null
            t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false, loop = loop))
            // Start where the note sounds: bar 1 (bar 3 for the loop, which then wraps at its half into the rest of bar 2).
            if (event == Event.LOOP_WRAP) { t.seek(PlaybackOccurrence(ScorePosition(m[2].id), 1, 2)) }
            check(t.play() is Outcome.Success)
            val until = if (event == Event.LOOP_WRAP) frames / BLOCK else (1.3 * SR).toInt() / BLOCK
            repeat(until) { host.renderBlock() }
            val at = sink.frames
            when (event) {
                Event.STOP -> t.stop()
                Event.SEEK -> t.seek(PlaybackOccurrence(ScorePosition(m[1].id, f(1, 4)), 1, 1))
                Event.PAUSE -> t.pause()
                // Play on where the pause stopped (1.3 s = 13/20 of bar 1); without a running poller the transport would
                // only know the start frame, the app's poller publishes the paused position.
                Event.RESUME -> { t.pause(); repeat(SR / 4 / BLOCK) { host.renderBlock() }; t.seek(PlaybackOccurrence(ScorePosition(m[0].id, f(13, 20)), 1, 0)); t.play() }
                Event.LOOP_WRAP -> Unit
            }
            // Started at bar 3, the loop end (bar 3 + 1/2) is reached after half a bar of output.
            val eventAt = when (event) { Event.RESUME -> sink.frames; Event.LOOP_WRAP -> (bar / 2).toLong(); else -> at }
            while (sink.frames < frames) host.renderBlock()
            sink.left.copyOf(frames) to eventAt
        } finally { t.shutdown() }
    }

    /** Largest step in [from, to) of [x]. */
    private fun maxStep(x: FloatArray, from: Int, to: Int): Float { var m = 0f; for (i in maxOf(1, from) until minOf(x.size, to)) m = maxOf(m, abs(x[i] - x[i - 1])); return m }

    @Test fun stopSeekLoopAndPauseDoNotClick() {
        assumeTrue("sample bank not found", Qa18FirstBeatTest.bank != null)
        val failures = ArrayList<String>()
        for (s in sources()) for (e in Event.values()) {
            // Without a clip a resume restarts the sounding note (chase): an onset, not a click.
            if (e == Event.RESUME && !s.clip) continue
            val (x, at) = render(s, e)
            val a = at.toInt()
            val steady = maxStep(x, a - SR / 5, a - SR / 500)
            val jump = maxStep(x, a - SR / 500, a + SR / 25)
            var level = 0f; for (i in maxOf(0, a - SR / 5) until a) level = maxOf(level, abs(x[i]))
            val click = jump > 0.1f && jump > 2 * steady
            println("Q20D ${s.name} / $e @ $a: größter Sprung ${"%.1f".format(20 * log10(jump + 1e-9))} dBFS (davor ${"%.1f".format(20 * log10(steady + 1e-9))} dBFS, Pegel ${"%.1f".format(20 * log10(level + 1e-9))} dBFS)${if (click) "  <-- KNACKS" else ""}")
            if (level < 0.01f && e != Event.RESUME) failures += "${s.name} / $e: Quelle klingt vor dem Ereignis nicht (Testaufbau)"
            if (click) failures += "${s.name} / $e: Sprung ${"%.1f".format(20 * log10(jump.toDouble()))} dBFS"
        }
        assertTrue("Knackser: $failures", failures.isEmpty())
    }
}
