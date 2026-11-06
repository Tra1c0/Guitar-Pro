package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA cycle 20, found while running the suites five times: Qa19PlayStartRaceTest (hot poller) failed in 2 of 15 runs of this
 * tree ("PLAYING fiel vor dem ersten Block 1-mal ab"). Cause: the poller's write checked the caller-side write epoch inside
 * `MutableStateFlow.update`, whose compare-and-set compares by *equality*. A poll that read PLAYING of one play, then saw the
 * caller's stop() + the next play() write an equal PLAYING state (same position, sample, speed), still passed the
 * compare-and-set and put its stale STOPPED over the fresh PLAYING (ABA). Deterministic reproduction: the poller's change
 * function stands in for the concurrent caller and performs those writes between the epoch check and the commit.
 */
class Qa20PollEpochTest {
    @Test fun aPollDoesNotCommitAfterACallerWroteAnEqualState() {
        val t = EngineTransport(RealtimeHost({ FakeSink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0), pollMillis = 1_000_000)
        val cls = EngineTransport::class.java
        @Suppress("UNCHECKED_CAST") val flow = cls.getDeclaredField("mutable").apply { isAccessible = true }.get(t) as MutableStateFlow<TransportState>
        val writes = cls.getDeclaredField("writes").apply { isAccessible = true }
        val place = cls.getDeclaredMethod("place", TransportState::class.java).apply { isAccessible = true }
        val poll = cls.getDeclaredMethod("poll", Long::class.javaPrimitiveType, Function1::class.java).apply { isAccessible = true }
        val playing = TransportState(TransportStatus.PLAYING, reason = null, sample = SamplePosition(0, 48_000))
        place.invoke(t, playing)
        val seen = writes.getLong(t)
        // The poller decided on STOPPED from what it read; meanwhile the caller stops and plays again from the same frame.
        val stale: (TransportState) -> TransportState = { cur ->
            place.invoke(t, cur.copy(status = TransportStatus.STOPPED))
            place.invoke(t, playing.copy())
            cur.copy(status = TransportStatus.STOPPED)
        }
        poll.invoke(t, seen, stale)
        assertEquals("a poll begun before the caller's writes must not overwrite them", TransportStatus.PLAYING, flow.value.status)
        t.shutdown()
    }
}
