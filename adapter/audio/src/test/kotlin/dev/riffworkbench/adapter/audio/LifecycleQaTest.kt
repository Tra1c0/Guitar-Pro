package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.synth.PcmBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** QA cycle 11: state-machine and lifecycle transitions of the transport (unusual orderings). */
class LifecycleQaTest {
    private var n = 0
    private fun id() = EntityId("lq${n++}")
    private val hash = AssetHash("a".repeat(64))
    private fun project(bars: Int, withClip: Boolean = false): Project {
        val m = List(bars) { Measure(id()) }
        val beats = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0))))
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })))
        val audio = if (!withClip) AudioArrangement() else AudioArrangement(listOf(AssetDescriptor(hash, "ref.wav", "audio/wav", 100)), listOf(AudioClip(id(), hash, "ref")))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)), audio = audio)
    }
    private fun manualHost(sink: FakeSink) = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
    private val plain = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)

    /**
     * play() holds the mutex while it compiles and decodes reference audio (seconds for a long MP3). stop()/pause() do not
     * take the mutex: they publish STOPPED, then the in-flight play() clears `halted`, posts Play and publishes PLAYING.
     * Real paths: pressing Stop while the first play is still loading, or ON_STOP / audio-focus loss (both call pause())
     * during that window -> playback starts afterwards, also in the background.
     */
    @Test fun stopDuringAnInFlightPlayIsNotOverriddenByThatPlay() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val loader = ClipLoader { _, rate -> entered.complete(Unit); gate.await(); Outcome.Success(PcmBuffer(rate, 4800, ShortArray(9600))) }
        val t = EngineTransport(host, clipLoader = loader, pollMillis = 1_000_000)
        t.prepare(project(4, withClip = true), plain)
        val playing = async(Dispatchers.Default) { t.play() }
        withTimeout(5000) { entered.await() }      // play() is now decoding the clip
        t.stop()
        assertEquals(TransportStatus.STOPPED, t.state.value.status)
        gate.complete(Unit)
        playing.await()
        repeat(4) { host.renderBlock() }
        assertEquals("stop pressed while play was loading must win", TransportStatus.STOPPED, t.state.value.status)
        assertFalse("engine must not be playing", host.snapshot().playing)
    }

    /** Same race with pause() (what ON_STOP and PlaybackGuard call). */
    @Test fun pauseDuringAnInFlightPlayIsNotOverriddenByThatPlay() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val loader = ClipLoader { _, rate -> entered.complete(Unit); gate.await(); Outcome.Success(PcmBuffer(rate, 4800, ShortArray(9600))) }
        val t = EngineTransport(host, clipLoader = loader, pollMillis = 1_000_000)
        t.prepare(project(4, withClip = true), plain)
        val playing = async(Dispatchers.Default) { t.play() }
        withTimeout(5000) { entered.await() }
        t.pause()                                   // app goes to background (MainActivity ON_STOP)
        gate.complete(Unit)
        playing.await()
        repeat(4) { host.renderBlock() }
        assertNotEquals("background pause must not be undone by the pending play", TransportStatus.PLAYING, t.state.value.status)
        assertFalse(host.snapshot().playing)
    }

    /**
     * pause() from STOPPED publishes PAUSED. MainActivity calls transport.pause() on every ON_STOP, so after any
     * background/foreground cycle a stopped transport reads PAUSED, and WorkspaceViewModel.togglePlay then skips
     * "seek to cursor" (it treats PAUSED as resume) and plays from the old stop position instead of the cursor.
     */
    @Test fun pauseWhileStoppedStaysStopped() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val t = EngineTransport(host, pollMillis = 1_000_000)
        t.prepare(project(4), plain); t.play(); repeat(10) { host.renderBlock() }
        t.stop(); host.renderBlock()
        assertEquals(TransportStatus.STOPPED, t.state.value.status)
        t.pause()                                   // ON_STOP while nothing plays
        assertEquals("pause from STOPPED must be a no-op", TransportStatus.STOPPED, t.state.value.status)
    }

    /** Never-played transport: one ON_STOP turns the initial STOPPED into PAUSED as well. */
    @Test fun pauseBeforeAnyPlayStaysStopped() = runBlocking {
        val t = EngineTransport(manualHost(FakeSink(latency = 0)), pollMillis = 1_000_000)
        t.pause()
        assertEquals(TransportStatus.STOPPED, t.state.value.status)
    }

    /**
     * The diagnostics stress test restarts playback once a second whenever the transport is not PLAYING. An external pause
     * (headphones unplugged -> ACTION_AUDIO_BECOMING_NOISY, audio-focus loss for a phone call, ON_STOP) is therefore undone
     * within one second: playback resumes on the loudspeaker / in the background.
     */
    @Test fun stressTestDoesNotResumeAfterAnExternalPause() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val t = EngineTransport(host, pollMillis = 1_000_000)
        val lab = EngineAudioLab(t, { null }, null, null)
        val job = launch(Dispatchers.Default) { lab.stressTest(5) { } }
        withTimeout(10_000) { while (t.state.value.status != TransportStatus.PLAYING) kotlinx.coroutines.delay(5) }
        t.pause()                                   // PlaybackGuard.onInterrupt / ON_STOP
        assertEquals(TransportStatus.PAUSED, t.state.value.status)
        Thread.sleep(1600)
        val after = t.state.value.status
        job.cancel(); job.join()
        // The test ends (and stops) on an external pause; what matters is that playback does not resume.
        assertNotEquals("an interruption must not be undone by the stress test", TransportStatus.PLAYING, after)
    }

    /** A rejected setLoop() still overwrites the stored loop; the next recompile (speed change) then drops the loop that was active. */
    @Test fun rejectedSetLoopKeepsTheActiveLoop() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val t = EngineTransport(host, pollMillis = 1_000_000)
        val p = project(4)
        t.prepare(p, plain); t.play(); host.renderBlock()
        val a = LoopRange(PlaybackOccurrence(ScorePosition(p.measures[1].id), 1, 1), PlaybackOccurrence(ScorePosition(p.measures[2].id), 1, 2))
        assertTrue(t.setLoop(a) is Outcome.Success)
        val gone = EntityId("deleted-bar")
        val b = LoopRange(PlaybackOccurrence(ScorePosition(gone), 1, 0), PlaybackOccurrence(ScorePosition(gone), 1, 0))
        assertTrue(t.setLoop(b) is Outcome.Failure)
        assertEquals(a, t.state.value.loop)
        t.setSpeed(0.9); host.renderBlock()
        assertEquals("a failed setLoop must not change the loop later", a, t.state.value.loop)
    }

    /** stop() means "back to the start"; a seek that was parked before the first plan existed survives stop() and wins. */
    @Test fun stopDiscardsASeekParkedBeforeThePlanExisted() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val t = EngineTransport(host, pollMillis = 1_000_000)
        val p = project(4)
        t.prepare(p, plain)                         // host idle: no plan yet
        t.seek(PlaybackOccurrence(ScorePosition(p.measures[2].id), 1, 2))
        t.stop()
        t.play(); host.renderBlock()
        assertTrue("play after stop must start at 0, was ${host.snapshot().planFrame}", host.snapshot().planFrame <= 256)
    }
}
