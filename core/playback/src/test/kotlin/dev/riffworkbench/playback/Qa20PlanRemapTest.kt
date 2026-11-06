package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA cycle 20: RealtimeHost maps the playhead into a recompiled plan with [PlaybackPlan.frameFromPlan] (no allocation on
 * the audio thread). It must give exactly what the object path `old.positionAt(frame)?.let { new.frameFor(it, orNearest = true) }`
 * gave, for speed changes, tempo changes, repeats, inserted and deleted bars.
 */
class Qa20PlanRemapTest {
    private val k = PlaybackTestKit()
    private fun plan(p: Project, speed: Double = 1.0) = (ScorePlaybackCompiler().compileNow(p, 48_000, PlaybackOptions(speed)) as Outcome.Success).value

    @Test fun primitiveRemapEqualsTheObjectPath() {
        val m = listOf(k.measure(), k.measure(repeat = RepeatMark(starts = true)), k.measure(repeat = RepeatMark(endCount = 3)), k.measure(TimeSignature(7, 8)), k.measure(feel = RhythmicFeel.SWING_EIGHTH))
        val beats = m.indices.associateWith { listOf(k.beat(Fraction.ZERO, 4, k.note(0, 3)), k.beat(Fraction.of(1, 4), 8, k.note(1, 5))) }
        val base = k.project(m, listOf(k.guitar(m, beats)), 97.0, listOf(TempoChange(ScorePosition(m[2].id, Fraction.of(3, 8)), 151.0)))
        val inserted = base.copy(measures = base.measures.take(2) + k.measure() + base.measures.drop(2))
        val deleted = base.copy(measures = base.measures.filterIndexed { i, _ -> i != 1 }, tempo = base.tempo.take(1))
        val noRepeat = base.copy(measures = base.measures.map { it.copy(repeat = RepeatMark()) })
        val old = plan(base)
        val cases = listOf("same" to plan(base), "speed 0.5" to plan(base, 0.5), "speed 1.25" to plan(base, 1.25), "bar inserted" to plan(inserted),
            "bar deleted" to plan(deleted), "repeat removed" to plan(noRepeat), "empty" to PlaybackPlan(0, 48_000, emptyList(), emptyList()))
        var checked = 0
        for ((label, next) in cases) for (from in listOf(old, plan(base, 0.7))) {
            var f = -5L
            while (f < from.totalFrames + 5_000) {
                val expected = from.positionAt(f)?.let { next.frameFor(it, orNearest = true) } ?: -1L
                assertEquals("$label, frame $f", expected, next.frameFromPlan(from, f))
                f += 997; checked++
            }
        }
        println("Q20R remap: $checked frames over ${cases.size} plan pairs equal to positionAt + frameFor")
    }
}
