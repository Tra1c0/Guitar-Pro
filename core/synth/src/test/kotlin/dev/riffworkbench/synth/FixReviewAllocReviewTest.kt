package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import org.junit.Assert.*
import org.junit.Test

class FixReviewAllocReviewTest {
    private var n = 0
    private fun id() = EntityId("al${n++}")
    private val sr = 48_000
    @Test fun renderDoesNotAllocateWhenAChannelGoesIdle() {
        val note = Beat(id(), Fraction.ZERO, WrittenDuration(16), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(3, 5)), 3, 5)))
        val m = List(1) { Measure(id()) }
        val t = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.map { mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0) listOf(note) else emptyList()) }) })),
            SoundAssignment(presetId = "rw.guitar.highgain"))
        val p = Project(id = id(), measures = m, tracks = listOf(t), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
        val e = SynthEngine(sr, 1024).apply {
            installRack(ChannelRack.build(p, sr, 1024)); setMix(MixState.from(p))
            install(EngineProgram.prepare((ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value)); play(0)
        }
        val buf = FloatArray(2048)
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        // warm-up (JIT) on a twin engine
        repeat(3) { val w = SynthEngine(sr, 1024).apply { installRack(ChannelRack.build(p, sr, 1024)); setMix(MixState.from(p)); install(EngineProgram.prepare((ScorePlaybackCompiler().compileNow(p, sr) as Outcome.Success).value)); play(0) }; repeat(2000) { w.render(buf, 256) } }
        var worst = 0L; var worstBlock = -1; var total = 0L
        for (b in 0 until 2000) { // ~10.7 s
            val a0 = mx.getThreadAllocatedBytes(tid); e.render(buf, 256); val d = mx.getThreadAllocatedBytes(tid) - a0
            total += d; if (d > worst) { worst = d; worstBlock = b }
        }
        println("ALLOC total=$total worst=$worst at block $worstBlock (t=${worstBlock * 256.0 / sr}s)")
        assertEquals("bytes allocated by render()", 0L, total)
    }
}
