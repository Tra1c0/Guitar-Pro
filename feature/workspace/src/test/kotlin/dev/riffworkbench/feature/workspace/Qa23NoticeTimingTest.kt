package dev.riffworkbench.feature.workspace

import org.junit.Assert.*
import org.junit.Test

/**
 * QA 23 (Meldungen schließen sich selbst): the display time of a notice ([NoticeTiming]); the UI (TransientNotice) runs the
 * timer and then clears `WorkspaceState.notice`, so the ViewModel state stays the only source and VM tests still read it.
 */
class Qa23NoticeTimingTest {
    @Test fun shortNoticesStayFourSecondsLongOnesLongerButAtMostTen() {
        assertEquals(4_000L, NoticeTiming.millis("Zwischenablage ist leer"))
        assertEquals(4_000L, NoticeTiming.millis("x".repeat(40)))
        val long = NoticeTiming.millis("3 Merkmal(e) konnten in Guitar Pro 7 (.gp) nicht exakt übertragen werden – Details im Austausch-Bereich.")
        assertTrue("long text gets more time: $long", long in 4_001L..10_000L)
        assertEquals(10_000L, NoticeTiming.millis("x".repeat(1000)))
        val lengths = (0..400 step 10).map { NoticeTiming.millis("x".repeat(it)) }
        assertTrue("monotonic in the length", lengths.zipWithNext().all { (a, b) -> b >= a })
    }
}
