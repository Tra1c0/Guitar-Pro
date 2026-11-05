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

/** QA cycle 12: adversarial review of 82f8a71 (haltEpoch / "pause only from PLAYING"). */
class Qa12TransportReviewTest {
    private var n = 0
    private fun id() = EntityId("q12-${n++}")
    private val hash = AssetHash("b".repeat(64))
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
     * pause() now acts only when the *published* status is PLAYING. Right after play() the poller can publish PAUSED
     * (or STOPPED after an ended run) for one poll period: the render thread has not consumed HostCommand.Play yet, so
     * HostSnapshot.playing is still false and EngineTransport.startPolling maps "status PLAYING, engine not playing" to PAUSED.
     * A pause() in that window (button, ON_STOP, PlaybackGuard.onInterrupt) is silently dropped: no HostCommand.Pause, no
     * `halted`, and playback runs on. Before 82f8a71 pause() always posted Pause and set `halted`.
     */
    @Test fun pauseRightAfterResumeIsNotDroppedByAStalePolledStatus() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val t = EngineTransport(host, pollMillis = 5)
        t.prepare(project(8), plain); t.play(); repeat(10) { host.renderBlock() }
        t.pause(); host.renderBlock()
        assertEquals(TransportStatus.PAUSED, t.state.value.status)
        t.play()                                    // resume; the audio thread has not rendered a block yet
        Thread.sleep(80)                            // a poll lands before that block: it publishes PAUSED
        t.pause()                                   // user / ON_STOP / audio-focus loss
        repeat(4) { host.renderBlock() }            // audio thread consumes the queued Play
        Thread.sleep(80)
        val status = t.state.value.status
        val playing = host.snapshot().playing
        t.shutdown()
        assertFalse("pause() after play() must stop the engine (status=$status)", playing)
        assertNotEquals(TransportStatus.PLAYING, status)
    }

    /**
     * The epoch is read at the start of play(), but WorkspaceViewModel.togglePlay runs prepare() first
     * (WorkspaceViewModel.kt:284-287). With the output already open (any play within the last 20 s), prepare() does the
     * whole compile + clip decode (installLocked), and play() itself is fast. A stop()/pause() during that prepare bumps
     * the epoch *before* play() reads it, so the following play() starts anyway - also in the background after ON_STOP.
     * The commit's claim "Stop/pause win over a play that is still compiling" holds only for a cold output.
     */
    @Test fun stopDuringTogglePlaysPrepareWinsOverTheFollowingPlay() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val loader = ClipLoader { _, rate -> entered.complete(Unit); gate.await(); Outcome.Success(PcmBuffer(rate, 4800, ShortArray(9600))) }
        val t = EngineTransport(host, clipLoader = loader, pollMillis = 1_000_000)
        t.prepare(project(4), plain); t.play(); repeat(4) { host.renderBlock() }
        t.stop(); host.renderBlock()                // output stays open (warm)
        val edited = project(4, withClip = true)
        // Exactly what togglePlay() does from STOPPED: prepare, seek to the cursor, play.
        val toggle = async(Dispatchers.Default) {
            t.prepare(edited, plain)
            t.seek(PlaybackOccurrence(ScorePosition(edited.measures[0].id), 1, 0))
            t.play()
        }
        withTimeout(5000) { entered.await() }      // prepare() is decoding the reference clip
        t.stop()                                    // user presses Stop (or ON_STOP -> pause())
        gate.complete(Unit)
        toggle.await()
        repeat(4) { host.renderBlock() }
        val status = t.state.value.status
        val playing = host.snapshot().playing
        t.shutdown()
        assertFalse("stop pressed while the play request was compiling must win (status=$status)", playing)
    }

    /**
     * EngineAudioLab.stressTest now ends only when it sees PAUSED (AudioLabImpl.kt:97). The 48 s stress project ends
     * every pass; the poller then publishes STOPPED and the loop restarts it up to 1 s later. An external pause() in that
     * window is a no-op (status is not PLAYING, EngineTransport.kt:168), the loop never sees PAUSED and restarts playback:
     * the interruption (call, headphones unplugged, ON_STOP) is undone - the case 82f8a71 meant to close.
     */
    @Test fun stressTestDoesNotResumeAfterAnExternalPauseBetweenPasses() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val t = EngineTransport(host, pollMillis = 5)
        val lab = EngineAudioLab(t, { null }, null, null)
        val material = AudioTestMaterial.stressProject()
        val job = launch(Dispatchers.Default) { lab.stressTest(10) { } }
        withTimeout(10_000) { while (t.state.value.status != TransportStatus.PLAYING) kotlinx.coroutines.delay(5) }
        host.renderBlock()
        t.seek(PlaybackOccurrence(ScorePosition(material.measures.last().id), 1, material.measures.lastIndex))
        // Let the pass run out: the poller publishes STOPPED (snap.ended).
        withTimeout(60_000) { while (t.state.value.status != TransportStatus.STOPPED) { repeat(20) { host.renderBlock() }; kotlinx.coroutines.delay(1) } }
        t.pause()                                   // external interruption between two passes
        assertFalse(host.snapshot().playing)
        Thread.sleep(1500)                          // one stress-loop tick
        repeat(4) { host.renderBlock() }            // audio thread drains what the loop posted
        val playing = host.snapshot().playing
        job.cancel(); job.join(); t.shutdown()
        assertFalse("an interruption between passes must not be undone by the stress test", playing)
    }
}
