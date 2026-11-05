package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

class FixReviewReviewTest {
    private val kit = PlaybackTestKit()
    private val sr = 48_000
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private fun plan(p: Project) = (ScorePlaybackCompiler().compileNow(p, sr, PlaybackOptions(1.0)) as Outcome.Success).value

    /** Tremolo note cut short by a later note on the same string (second voice): picks must stop at the cut. */
    @Test fun tremoloCutByNextNoteOnSameStringDoesNotPickOverIt() {
        val m = listOf(kit.measure())
        val trem = kit.note(3, 7, Articulation.TremoloPicking(WrittenDuration(16)))
        val other = kit.note(3, 9)
        val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 2, trem))), voice1 = mapOf(0 to listOf(kit.beat(f(1, 4), 4, other))))
        val notes = plan(kit.project(m, listOf(g))).notes
        val picks = notes.filter { it.noteId == trem.id }
        val otherStart = notes.first { it.noteId == other.id }.frame
        println("picks=" + picks.map { it.frame to it.endFrame } + " other at $otherStart")
        assertTrue("tremolo picks at/after the cutting note: " + picks.filter { it.frame >= otherStart }.map { it.frame to it.endFrame }, picks.none { it.frame >= otherStart })
    }

    /** A short closing bar (complement of a pickup, or an imported irregular bar) starts on beat 1. */
    @Test fun shortClosingBarKeepsDownbeatAccent() {
        val m = listOf(kit.measure(), kit.measure().copy(pickupLength = f(3, 4)))
        val g = kit.guitar(m, mapOf(0 to listOf(kit.beat(f(0, 1), 1, kit.note(3, 7))), 1 to listOf(kit.beat(f(0, 1), 2, kit.note(3, 7)), kit.beat(f(1, 2), 4, kit.note(3, 7)))))
        val p = plan(kit.project(m, listOf(g)))
        val second = p.clicks.filter { it.frame >= 96_000 }
        println("clicks bar2=" + second)
        assertEquals("first click of bar 2 is its downbeat", MetronomeClick(96_000, 0), second.first())
    }
}
