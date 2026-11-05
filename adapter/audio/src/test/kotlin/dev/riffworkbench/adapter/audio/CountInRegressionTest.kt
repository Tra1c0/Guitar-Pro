package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import org.junit.Test

/** Count-in is one regular bar of the meter at the starting tempo (QA cycle 4). */
class CountInRegressionTest {
    private var n = 0
    private fun id() = EntityId("c${n++}")
    private fun countIn(plan: PlaybackPlan, from: Long, bars: Int, project: Project): Pair<Long, List<Long>> {
        val t = EngineTransport(RealtimeHost({ error("no sink") }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0))
        val f = EngineTransport::class.java.getDeclaredField("settings"); f.isAccessible = true
        f.set(t, PracticeSettings(speed = 1.0, countInBars = bars, metronome = true))
        EngineTransport::class.java.getDeclaredField("project").apply { isAccessible = true }.set(t, project)
        val m = EngineTransport::class.java.getDeclaredMethod("countIn", PlaybackPlan::class.java, Long::class.javaPrimitiveType); m.isAccessible = true
        @Suppress("UNCHECKED_CAST") val r = m.invoke(t, plan, from) as Pair<Long, LongArray>
        return r.first to r.second.toList()
    }
    private fun project(ms: List<Measure>, tempo: List<TempoChange> = listOf(TempoChange(ScorePosition(ms[0].id), 120.0)), fermataAt: Int? = null): Project {
        val track = Track(id(), "g", InstrumentDefinition(), listOf(Staff(id(), bars = ms.mapIndexed { i, m -> Bar(id(), m.id, listOf(Voice(id(), 0,
            if (fermataAt == i) listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(40), 1, 0)), fermata = Fermata(multiplier = 2.0))) else emptyList()))) })))
        return Project(id = id(), measures = ms, tracks = listOf(track), tempo = tempo)
    }
    private fun plan(p: Project, speed: Double = 1.0) = (ScorePlaybackCompiler().compileNow(p, 48_000, PlaybackOptions(speed)) as Outcome.Success).value
    private fun check(name: String, e: Any?, a: Any?) = org.junit.Assert.assertEquals(name, e, a)

    @Test fun countIns() {
        // plain 4/4 at 120: 1 bar = 96000 frames, clicks every 24000
        run { val pr = project(listOf(Measure(id()))); val p = plan(pr); check("4/4 count-in", 96_000L to listOf(0L, 24_000L, 48_000L, 72_000L), countIn(p, 0, 1, pr)) }
        run { val pr = project(listOf(Measure(id(), TimeSignature(7, 8)))); val p = plan(pr); check("7/8 count-in clicks", 7, countIn(p, 0, 1, pr).second.size) }
        run { val pr = project(listOf(Measure(id(), TimeSignature(6, 8)))); val p = plan(pr, 0.5); check("6/8 count-in at speed 0.5", 144_000L to listOf(0L, 72_000L), countIn(p, 0, 1, pr)) }
        run { val pr = project(listOf(Measure(id(), TimeSignature(5, 4)))); val p = plan(pr); check("5/4 x2", 240_000L to List(10) { it * 24_000L }, countIn(p, 0, 2, pr)) }
        // pickup of one quarter: a count-in bar should still be a full 4/4 bar
        run { val ms = listOf(Measure(id(), pickupLength = Fraction.of(1, 4)), Measure(id())); val pr = project(ms); val p = plan(pr); check("pickup 1/4 count-in", 96_000L to listOf(0L, 24_000L, 48_000L, 72_000L), countIn(p, 0, 1, pr)) }
        // start mid-bar (seek to beat 3): count-in should end on a beat so that beat 3 follows in time
        run { val ms = listOf(Measure(id())); val pr = project(ms); val p = plan(pr); val r = countIn(p, 48_000, 1, pr); check("from beat 3: count-in length multiple of beat and clicks", true, r.first % 24_000L == 0L && r.first >= 48_000L) ; println("INFO from beat 3 -> $r") }
        // fermata in the start bar must not stretch the count-in
        run { val ms = listOf(Measure(id()), Measure(id())); val pr = project(ms, fermataAt = 0); val p = plan(pr); check("fermata bar count-in", 96_000L to listOf(0L, 24_000L, 48_000L, 72_000L), countIn(p, 0, 1, pr)) }
        // ritardando inside the start bar (tempo 60 from beat 3)
        run { val ms = listOf(Measure(id())); val pr = project(ms, listOf(TempoChange(ScorePosition(ms[0].id), 120.0), TempoChange(ScorePosition(ms[0].id, Fraction.of(1, 2)), 60.0))); val p = plan(pr); check("tempo change in start bar count-in (tempo at start)", 96_000L to listOf(0L, 24_000L, 48_000L, 72_000L), countIn(p, 0, 1, pr)) }
        // swing bar: clicks on quarters, so unaffected
        run { val pr = project(listOf(Measure(id(), feel = RhythmicFeel.SWING_EIGHTH))); val p = plan(pr); check("swing count-in", 96_000L to listOf(0L, 24_000L, 48_000L, 72_000L), countIn(p, 0, 1, pr)) }
    }
}
