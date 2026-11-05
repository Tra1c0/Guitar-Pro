package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.synth.PcmBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 13 review of 0e2ddda (EngineTransport.prepareEpoch). prepareEpoch is a single shared field, written unlocked at the
 * start of every prepare(). WorkspaceViewModel also calls prepare() for edits while PLAYING/PAUSED (WorkspaceViewModel.kt:502)
 * and for every practice change (:314). A second prepare() that starts after the halt overwrites togglePlay's remembered
 * epoch with the post-halt epoch and then clears it, so togglePlay's play() no longer sees the halt.
 */
class Qa13PrepareEpochReviewTest {
    private var n = 0
    private fun id() = EntityId("q13-${n++}")
    private val hash = AssetHash("c".repeat(64))
    private fun project(bars: Int, withClip: Boolean = false): Project {
        val m = List(bars) { Measure(id()) }
        val beats = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0))))
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })))
        val audio = if (!withClip) AudioArrangement() else AudioArrangement(listOf(AssetDescriptor(hash, "ref.wav", "audio/wav", 100)), listOf(AudioClip(id(), hash, "ref")))
        return Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)), audio = audio)
    }
    private val plain = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)

    @Test fun stopDuringTogglePlayStillWinsWhenAnotherPrepareRunsMeanwhile() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val loader = ClipLoader { _, rate -> entered.complete(Unit); gate.await(); Outcome.Success(PcmBuffer(rate, 4800, ShortArray(9600))) }
        val t = EngineTransport(host, clipLoader = loader, pollMillis = 1_000_000)
        val base = project(4)
        t.prepare(base, plain); t.play(); repeat(4) { host.renderBlock() }
        t.pause(); host.renderBlock()                 // PAUSED, output warm
        val edited = project(4, withClip = true)
        // togglePlay() from PAUSED: prepare (slow: decodes the clip), then play.
        val toggle = async(Dispatchers.Default) { val since = t.halts; t.prepare(edited, plain); t.play(since) }  // as WorkspaceViewModel.togglePlay
        withTimeout(5000) { entered.await() }
        t.stop()                                      // user presses Stop while Play is compiling
        // Meanwhile any other prepare(): a practice change (updatePractice) or an edit.
        val other = async(Dispatchers.Default) { t.prepare(edited, plain.copy(metronome = true)) }
        Thread.sleep(50)                              // `other` has entered prepare() and waits for the mutex
        gate.complete(Unit)
        toggle.await(); other.await()
        repeat(4) { host.renderBlock() }
        val status = t.state.value.status
        val playing = host.snapshot().playing
        t.shutdown()
        assertFalse("Stop pressed while Play was compiling must win, also when another prepare() ran (status=$status)", playing)
        assertNotEquals(TransportStatus.PLAYING, status)
    }

    /** Control: the same sequence without the second prepare() is handled (the commit's own fix). */
    @Test fun controlWithoutSecondPrepare() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val loader = ClipLoader { _, rate -> entered.complete(Unit); gate.await(); Outcome.Success(PcmBuffer(rate, 4800, ShortArray(9600))) }
        val t = EngineTransport(host, clipLoader = loader, pollMillis = 1_000_000)
        t.prepare(project(4), plain); t.play(); repeat(4) { host.renderBlock() }
        t.pause(); host.renderBlock()
        val edited = project(4, withClip = true)
        val toggle = async(Dispatchers.Default) { val since = t.halts; t.prepare(edited, plain); t.play(since) }  // as WorkspaceViewModel.togglePlay
        withTimeout(5000) { entered.await() }
        t.stop(); gate.complete(Unit); toggle.await()
        repeat(4) { host.renderBlock() }
        val playing = host.snapshot().playing
        t.shutdown()
        assertFalse(playing)
    }
}
