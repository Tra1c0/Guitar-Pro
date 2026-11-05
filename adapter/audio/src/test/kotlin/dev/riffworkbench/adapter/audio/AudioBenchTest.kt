package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * JVM benchmark of the stress arrangement (8 sampled guitar tracks) in 192-frame blocks at 48 kHz. These are desktop
 * numbers: they show headroom and outliers of the code, not the phone. The device numbers come from the diagnostics page.
 */
class AudioBenchTest {
    private val bankFile = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.firstOrNull { it.isFile }

    @Test fun eightSampledGuitarTracksFitTheBlockBudget() {
        val bank = bankFile?.inputStream()?.buffered()?.use { i -> SampleBank.read { b, o, l -> i.read(b, o, l) } }
        val project = AudioTestMaterial.stressProject()
        val sr = 48_000; val block = 192
        val plan = (ScorePlaybackCompiler().compileNow(project, sr) as Outcome.Success).value
        for (b in listOf(null, bank)) {
            if (b == null && bank != null && false) continue
            val e = SynthEngine(sr, 1024, b)
            e.installRack(ChannelRack.build(project, sr, 1024)); e.setMix(MixState.from(project)); e.install(EngineProgram.prepare(plan)); e.play(0)
            val out = FloatArray(block * 2)
            repeat(500) { e.render(out, block) } // warm-up (JIT)
            val times = LongArray(4000)
            var maxVoices = 0
            for (i in times.indices) { val t0 = System.nanoTime(); e.render(out, block); times[i] = System.nanoTime() - t0; maxVoices = maxOf(maxVoices, e.voicesInUse) }
            times.sort()
            val budget = block * 1e6 / sr
            val median = times[times.size / 2] / 1000.0; val p99 = times[(times.size * 99) / 100] / 1000.0
            println("BENCH ${if (b == null) "modelled" else "sampled"}: median ${"%.0f".format(median)} µs, p99 ${"%.0f".format(p99)} µs, max ${times.last() / 1000} µs, budget ${"%.0f".format(budget)} µs, load ${"%.0f".format(100 * median / budget)} %, voices $maxVoices" +
                (b?.let { ", bank ${it.bytes / 1_000_000} MB" } ?: ""))
            assertTrue("median above budget", median < budget)
        }
    }

    @Test fun comparisonRendersAreLoudnessMatched() = kotlinx.coroutines.runBlocking {
        val bank = bankFile?.inputStream()?.buffered()?.use { i -> SampleBank.read { b, o, l -> i.read(b, o, l) } }
        val lab = EngineAudioLab(EngineTransport(RealtimeHost({ FakeSink() })), { bank }, null, null)
        for (tone in listOf("clean", "crunch", "highgain")) for (sampled in listOf(false, true)) {
            if (sampled && bank == null) continue
            val pcm = (lab.renderComparison(tone, sampled) as Outcome.Success).value
            val rms = kotlin.math.sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
            assertEquals("$tone/$sampled", 0.1, rms, 0.002)
        }
    }
}
