package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import java.io.File
import org.junit.Test

/** Prints the level of the sampled vs. modelled guitar per preset (used to set TonePresets.sampleGain). */
class SampleCalibrationProbe {
    @Test fun probe() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.firstOrNull { it.isFile } ?: return
        val bank = file.inputStream().buffered().use { i -> SampleBank.read { b, o, l -> i.read(b, o, l) } }
        val g = InstrumentDefinition()
        var n = 0
        fun id() = EntityId("c${n++}")
        val m = listOf(Measure(id()), Measure(id()))
        fun bar(i: Int) = (0 until 8).map { j -> val k = i * 8 + j; Beat(id(), Fraction.of(j.toLong(), 8), WrittenDuration(8), listOf(Note(id(), Pitch(g.pitch(k % 3, (k * 5) % 12)), k % 3, (k * 5) % 12))) }
        for (preset in listOf("rw.guitar.clean", "rw.guitar.crunch", "rw.guitar.highgain")) {
            val track = Track(id(), "G", g, listOf(Staff(id(), bars = m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0) bar(i) else emptyList()) }) })), SoundAssignment(presetId = preset))
            val p = Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
            fun rms(b: SampleBank?): Double {
                var sum = 0.0; var count = 0L
                val r = OfflineMixdown(bank = b).render(p, RenderOptions(sampleRate = 48_000, bitDepth = 16, tailSeconds = 0.5, includeMasterFx = false), { bytes, o, l ->
                    var k = o; while (k + 1 < o + l) { val v = ((bytes[k].toInt() and 0xFF) or (bytes[k + 1].toInt() shl 8)).toShort() / 32768.0; sum += v * v; count++; k += 2 } })
                return kotlin.math.sqrt(sum / count.coerceAtLeast(1))
            }
            val a = rms(null); val s = rms(bank)
            println("CAL $preset modelled=${"%.4f".format(a)} sampled=${"%.4f".format(s)} ratio=${"%.3f".format(a / s)}")
        }
    }
}
