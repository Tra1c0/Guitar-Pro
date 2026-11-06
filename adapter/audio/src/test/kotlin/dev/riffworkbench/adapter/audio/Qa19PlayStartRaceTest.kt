package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zyklus 19, CI-Befund (PlayStartRegressionTest auf der CI rot, lokal grün): play() setzte `starting = true` vor
 * `postedPlays++`. Ein Poll genau dazwischen sah `snap.plays (0) >= postedPlays (0)`, beendete die Startphase, und der
 * nächste Poll stufte das frisch veröffentlichte PLAYING zu PAUSED herab (Ansicht springt, Folgen aus/an). Der Stresstest
 * pollt ohne Pause und startet sehr oft, damit das Fenster von wenigen Nanosekunden getroffen wird.
 *
 * Zweiter Fall (Diagnose desselben CI-Befunds): RealtimeHost zählte einen Play schon beim Leeren der Befehlsschlange als
 * verarbeitet, das Flag "spielt" kam erst am Blockende. Ein Poll mitten im ersten Block sah "Play verarbeitet, spielt
 * nicht" und meldete PAUSED — am Gerät schaltet das für einen Poll das Folgen ab (Ansicht springt).
 */
class Qa19PlayStartRaceTest {
    private var n = 0
    private fun id() = EntityId("r${n++}")
    private fun project(bars: Int): Project {
        val m = List(bars) { Measure(id()) }
        val beats = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0))))
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }

    @Test fun noPausedFlickerBeforeTheFirstBlockUnderAHotPoller() = runBlocking {
        val host = RealtimeHost({ FakeSink(latency = 0) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 0)
        val p = project(4)
        t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false))
        var flickers = 0
        repeat(3000) {
            t.play()
            // The render thread is paused: nothing may downgrade PLAYING before the first block.
            val until = System.nanoTime() + 300_000
            while (System.nanoTime() < until) if (t.state.value.status != TransportStatus.PLAYING) { flickers++; break }
            t.stop()
            host.renderBlock()
        }
        t.shutdown()
        assertTrue("PLAYING fiel vor dem ersten Block $flickers-mal ab", flickers == 0)
    }

    @Test fun noPausedFlickerWhileTheFirstBlockIsRendered() = runBlocking {
        val host = RealtimeHost({ FakeSink(latency = 0) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val t = EngineTransport(host, pollMillis = 0)
        val p = project(4)
        t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false))
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<TransportStatus>()
        val watching = java.util.concurrent.atomic.AtomicBoolean()
        repeat(400) { round ->
            t.play()
            seen.clear(); watching.set(true)
            val w = Thread { while (watching.get()) { val s = t.state.value.status; if (s != TransportStatus.PLAYING) seen += s } }
            w.start()
            repeat(4) { host.renderBlock() }               // first blocks of the new playback: the engine plays from block 1 on
            watching.set(false); w.join()
            assertTrue("Status fiel während der ersten Blöcke auf $seen (Durchlauf $round)", seen.isEmpty())
            t.stop(); host.renderBlock()
        }
        t.shutdown()
    }
}
