package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.*
import kotlin.math.abs
import kotlin.math.log10
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * QA-Zyklus 19, Nutzerbericht "der erste Drum-Hit fehlt manchmal": der Onset-Vergleich aus Qa18FirstBeatTest für eine
 * Schlagzeugspur. Takt 1 Zählzeit 1 trägt Kick + Crash + HH (wie ein typischer Songanfang), Zählzeit 2 Snare, Zählzeit 3
 * Kick. Verglichen wird mit derselben Spur nach einem stillen Takt (eingeschwungene Engine, identischer Live-Stack):
 * erster hörbarer Frame, Energie in 0–5/5–10/10–20/20–50 ms. Live-Pfad: EngineTransport → RealtimeHost (manuelle Blöcke,
 * FakeSink) → SynthEngine; Export: OfflineMixdown. Drums sind bis auf das Rauschen deterministisch (Seed hängt vom
 * Plan-Frame ab), daher Toleranz 1 dB für 0–50 ms und 2 dB je Teilfenster; ein verschluckter Hit verliert > 20 dB.
 */
class Qa19DrumFirstHitTest {
    companion object {
        const val SR = 48_000
        const val BLOCK = 256
        const val LATENCY = 2048
        const val BAR = 76_800
        const val QUARTER = 19_200
        val plain = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)
        val windowsMs = listOf(0 to 5, 5 to 10, 10 to 20, 20 to 50)
    }

    private var n = 0
    private fun id() = EntityId("q19-${n++}")
    private val drums = InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0)
    private fun d(midi: Int, v: Int) = Note(id(), Pitch(midi), velocity = v)

    /** [lead] stille Takte, dann der Songanfang (Kick+Crash+HH, Snare, Kick), dann ein Takt mit Kick auf 1. */
    private fun project(lead: Int = 0): Project {
        val measures = List(lead + 2) { Measure(id()) }
        val bars = measures.mapIndexed { i, m ->
            val beats = when (i) {
                lead -> listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(d(36, 110), d(49, 120), d(42, 90))),
                    Beat(id(), Fraction.of(1, 4), WrittenDuration(4), listOf(d(38, 110))), Beat(id(), Fraction.of(1, 2), WrittenDuration(4), listOf(d(36, 100))))
                lead + 1 -> listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(d(36, 110))))
                else -> emptyList()
            }
            Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) beats else emptyList()) })
        }
        val track = Track(id(), "Drums", drums, listOf(Staff(id(), setOf(NotationKind.PERCUSSION), bars)))
        return Project(id = id(), measures = measures, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(measures[0].id), 150.0)))
    }
    private fun at(p: Project, bar: Int) = PlaybackOccurrence(ScorePosition(p.measures[bar].id, Fraction.ZERO), 1, bar)

    private inner class Rig(idleStop: Double = 1000.0) {
        val sinks = ArrayList<FakeSink>()
        val sink: FakeSink get() = sinks.last()
        val host = RealtimeHost({ FakeSink(SR, BLOCK, LATENCY).also { it.capture = true; sinks += it } }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = idleStop, bankProvider = { null })
        val t = EngineTransport(host, pollMillis = 5)
        fun render(blocks: Int) { repeat(blocks) { host.renderBlock() } }
        fun settle(blocks: Int = 1) { render(blocks); Thread.sleep(40) }
        fun mark() = sink.captured.size
        fun left(): FloatArray = FloatArray(sink.captured.size) { sink.captured[it] }
        fun close() = t.shutdown()
    }

    private fun rmsDb(x: FloatArray, from: Int, count: Int): Double { var s = 0.0; for (i in from until minOf(from + count, x.size)) s += x[i].toDouble() * x[i]; return 10 * log10(s / count + 1e-30) }
    private class Onset(val first: Int, val windows: List<Double>, val total: Double) {
        override fun toString() = "hörbar ab +$first Frames, Fenster ${windows.joinToString(" / ") { "%.1f".format(java.util.Locale.ROOT, it) }} dBFS, 0-50 ms ${"%.1f".format(java.util.Locale.ROOT, total)} dBFS"
    }
    private fun onset(x: FloatArray, at: Int): Onset {
        val first = (at until minOf(x.size, at + SR / 20)).firstOrNull { abs(x[it]) > 1e-4f }?.minus(at) ?: -1
        return Onset(first, windowsMs.map { (a, b) -> rmsDb(x, at + a * SR / 1000, (b - a) * SR / 1000) }, rmsDb(x, at, SR / 20))
    }
    private fun assertOnset(name: String, ref: Onset, got: Onset) {
        println("D19 MEASURE $name: $got | Referenz: $ref")
        assertTrue("$name: kein hörbarer Onset ($got, Referenz $ref)", got.first >= 0)
        assertTrue("$name: Onset um ${got.first - ref.first} Frames verschoben ($got, Referenz $ref)", abs(got.first - ref.first) <= 8)
        assertTrue("$name: 0-50 ms weichen ${"%.1f".format(got.total - ref.total)} dB ab ($got, Referenz $ref)", abs(got.total - ref.total) <= 1.0)
        windowsMs.forEachIndexed { i, (a, b) -> assertTrue("$name: Fenster $a-$b ms weicht ${"%.1f".format(got.windows[i] - ref.windows[i])} dB ab ($got, Referenz $ref)", abs(got.windows[i] - ref.windows[i]) <= 2.0) }
    }

    /** Referenz: ein stiller Takt vorab, dann Songanfang; liefert die Onsets von Takt-1-Zählzeit-1, Zählzeit 2 und dem Kick auf Takt 2. */
    private fun reference(): List<Onset> = runBlocking {
        val rig = Rig(); val p = project(lead = 1)
        rig.t.prepare(p, plain); rig.t.play(); rig.render(3 * BAR / BLOCK + 2)
        val x = rig.left(); rig.close()
        listOf(onset(x, BAR), onset(x, BAR + QUARTER), onset(x, 2 * BAR))
    }

    @Test fun freshStartPlaysTheFirstDrumHitComplete() = runBlocking {
        val ref = reference()
        val rig = Rig(); val p = project()
        rig.t.prepare(p, plain); rig.t.play()
        val m = rig.mark(); rig.render(BAR / BLOCK + 8)
        val x = rig.left()
        assertOnset("Kaltstart, Kick+Crash+HH auf Frame 0", ref[0], onset(x, m))
        assertOnset("Kaltstart, Snare auf Zählzeit 2", ref[1], onset(x, m + QUARTER))
        rig.close()
    }

    /**
     * DrumScreen: ein Tipp ins Raster hört den Schlag vor (Preview); danach Play. Ebenso Stop direkt nach einem Schlag und
     * erneutes Play. Abstand zwischen dem letzten Klang vor Play und Play:
     * 10 ms (Maschine), 50/100/200 ms (schneller Doppeltipp, Stop/Play), 400 ms. Der erste Hit muss in jedem Fall wie nach
     * einem stillen Takt klingen.
     */
    @Test fun firstDrumHitAfterAPreviewOrAStopAtAnyGap() = runBlocking {
        val ref = reference()
        val failures = ArrayList<String>()
        for (gapMs in listOf(10, 50, 100, 200, 400)) {
            val blocks = (gapMs * SR / 1000 / BLOCK).coerceAtLeast(1)
            for (kind in listOf("Preview Snare", "Preview Kick", "Stop nach Snare")) {
                val rig = Rig(); val p = project()
                if (kind.startsWith("Preview")) {
                    rig.t.prepare(p, plain)
                    rig.t.preview(p, p.tracks[0].id, d(if (kind.endsWith("Kick")) 36 else 38, 96))
                } else {
                    rig.t.prepare(p, plain); rig.t.seek(PlaybackOccurrence(ScorePosition(p.measures[0].id, Fraction.of(1, 4)), 1, 0)); rig.t.play()
                    rig.render(2); rig.t.stop()
                }
                rig.render(blocks)
                rig.t.seek(at(p, 0)); rig.t.play()
                val m = rig.mark(); rig.render(QUARTER / BLOCK + 8)
                val got = onset(rig.left(), m)
                val dev = listOf(got.total - ref[0].total) + got.windows.indices.map { got.windows[it] - ref[0].windows[it] }
                println("D19 MEASURE $kind, Play nach $gapMs ms: $got | Abweichung 0-50 ms / Fenster ${dev.joinToString(" / ") { "%.1f".format(java.util.Locale.ROOT, it) }} dB")
                if (got.first < 0 || abs(dev[0]) > 1.0 || dev.drop(1).any { abs(it) > 2.0 }) failures += "$kind nach $gapMs ms: ${dev.map { "%.1f".format(java.util.Locale.ROOT, it) }}"
                rig.close()
            }
        }
        assertTrue("erster Hit nach Vorhören/Stop gedämpft: $failures", failures.isEmpty())
    }

    @Test fun stopThenPlayAndIdleStopThenPlayKeepTheFirstDrumHit() = runBlocking {
        val ref = reference()
        val rig = Rig(idleStop = 0.3); val p = project()
        rig.t.prepare(p, plain); rig.t.play(); rig.settle(40)
        rig.t.stop(); rig.settle(100)
        rig.t.play()
        val m = rig.mark(); rig.render(QUARTER / BLOCK + 8)
        assertOnset("Stop → Play, Kick+Crash+HH auf Frame 0", ref[0], onset(rig.left(), m))
        rig.t.pause(); rig.settle(4)
        var guard = 0
        while (rig.host.isRunning && guard++ < 10_000) rig.host.renderBlock()
        assertFalse("Host hat sich nicht selbst gestoppt", rig.host.isRunning)
        Thread.sleep(60)
        rig.t.seek(at(p, 0))
        assertTrue(rig.t.play() is Outcome.Success)
        val m2 = rig.mark(); rig.render(QUARTER / BLOCK + 8)
        assertOnset("Leerlaufstopp → Play, Kick+Crash+HH auf Frame 0", ref[0], onset(rig.left(), m2))
        rig.close()
    }

    @Test fun countInAndLoopWrapKeepTheFirstDrumHit() = runBlocking {
        val ref = reference()
        val rig = Rig(); val p = project()
        rig.t.prepare(p, plain.copy(countInBars = 1)); rig.t.play()
        val m = rig.mark(); rig.render(BAR / BLOCK + QUARTER / BLOCK + 8)
        assertOnset("Vorzähler 1 Takt, Kick+Crash+HH einen Takt nach Start", ref[0], onset(rig.left(), m + BAR))
        rig.t.stop(); rig.settle(4)
        rig.t.prepare(p, plain.copy(loop = LoopRange(at(p, 0), at(p, 0)))); rig.t.seek(at(p, 0)); rig.t.play()
        val m2 = rig.mark(); rig.render(2 * BAR / BLOCK + 8)
        val x = rig.left()
        assertOnset("Loop Takt 1, Kick+Crash+HH auf Frame 0", ref[0], onset(x, m2))
        // Nach dem Umlauf klingt das Crash vom ersten Durchlauf noch (1,7 s) — der Loop-Rücksprung choked alle Stimmen (releaseAll fast), daher Referenz = Start.
        assertOnset("Loop Takt 1, Kick+Crash+HH nach dem Umlauf", ref[0], onset(x, m2 + BAR))
        rig.close()
    }

    /** Live (256er-Blöcke) und Export (OfflineMixdown, 1024er-Blöcke) beginnen gleich. */
    @Test fun liveAndExportAgreeOnTheFirstDrumHit() = runBlocking {
        val p = project()
        val bytes = java.io.ByteArrayOutputStream()
        assertTrue(OfflineMixdown(ScorePlaybackCompiler(), null).render(p, RenderOptions(sampleRate = SR, bitDepth = 32, tailSeconds = 0.0), { b, o, l -> bytes.write(b, o, l) }) is Outcome.Success)
        val data = bytes.toByteArray()
        val x = FloatArray(QUARTER * 2) { i -> val o = 44 + i * 8; Float.fromBits((data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8) or ((data[o + 2].toInt() and 0xFF) shl 16) or (data[o + 3].toInt() shl 24)) }
        val rig = Rig()
        rig.t.prepare(p, plain); rig.t.play()
        val m = rig.mark(); rig.render(2 * QUARTER / BLOCK + 8)
        val live = rig.left()
        assertOnset("Live vs Export, Kick+Crash+HH auf Frame 0", onset(x, 0), onset(live, m))
        assertOnset("Live vs Export, Snare auf Zählzeit 2", onset(x, QUARTER), onset(live, m + QUARTER))
        var maxDiff = 0f; for (i in 0 until QUARTER) maxDiff = maxOf(maxDiff, abs(x[i] - live[m + i]))
        println("D19 MEASURE Live vs Export, erste Viertel: max |Differenz| ${"%.2e".format(maxDiff)}")
        rig.close()
    }
}
