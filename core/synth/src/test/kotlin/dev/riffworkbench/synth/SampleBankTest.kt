package dev.riffworkbench.synth

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class SampleBankTest {
    private fun bank(regions: List<Triple<Int, Int, Int>>, frames: Int = 400, loop: Pair<Int, Int> = 100 to 300, magic: String = "RWSB",
                     version: Int = 1, fineTune: Float = 0f, gain: Float = 1f, kind: Int = 0, sample: (Int) -> Int = { it * 37 % 2000 - 1000 }): ByteArray {
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).put(magic.toByteArray()).putShort(version.toShort()).putInt(44_100).putInt(regions.size)
        out.write(head.array())
        for ((root, lo, hi) in regions) {
            val m = ByteBuffer.allocate(if (version == 2) 30 else 22).order(ByteOrder.LITTLE_ENDIAN).put(kind.toByte()).put(root.toByte()).put(lo.toByte()).put(hi.toByte()).put(0).put(0)
                .putFloat(10f).putInt(loop.first).putInt(loop.second).putInt(frames)
            if (version == 2) m.putFloat(fineTune).putFloat(gain)
            out.write(m.array())
            val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN); repeat(frames) { pcm.putShort(sample(it).toShort()) }
            out.write(pcm.array())
        }
        return out.toByteArray()
    }

    @Test fun readsValidBankAndCoversAllKeys() {
        val b = SampleBank.read(bank(listOf(Triple(40, 0, 60), Triple(70, 61, 127))))
        assertEquals(44_100, b.sampleRate); assertEquals(1, b.layers); assertEquals(1600L, b.bytes)
        assertEquals(40, b.note(10, 0, 0).root); assertEquals(70, b.note(127, 0, 5).root)
    }

    @Test fun rejectsBadInput() {
        fun fails(bytes: ByteArray) = runCatching { SampleBank.read(bytes) }.isFailure
        assertTrue(fails(bank(listOf(Triple(40, 0, 127)), magic = "RIFF")))
        assertTrue(fails(bank(listOf(Triple(40, 0, 60)))))                         // gap above 60
        assertTrue(fails(bank(listOf(Triple(40, 0, 127)), loop = 300 to 500)))      // loop beyond data
        val ok = bank(listOf(Triple(40, 0, 127)))
        assertTrue(fails(ok.copyOf(ok.size - 10)))                                 // truncated
    }

    @Test fun readsVersionTwoFineTuneAndGain() {
        val b = SampleBank.read(bank(listOf(Triple(40, 0, 127)), version = 2, fineTune = 7.5f, gain = 0.42f))
        val r = b.note(40, 0, 0)
        assertEquals(7.5f, r.fineTuneCents, 0f); assertEquals(0.42f, r.gain, 0f); assertEquals(1600L / 2, b.bytes)
        fun fails(bytes: ByteArray) = runCatching { SampleBank.read(bytes) }.isFailure
        assertTrue(fails(bank(listOf(Triple(40, 0, 127)), version = 2, fineTune = Float.NaN)))
        assertTrue(fails(bank(listOf(Triple(40, 0, 127)), version = 2, fineTune = 250f)))
        assertTrue(fails(bank(listOf(Triple(40, 0, 127)), version = 2, gain = 0f)))
        assertTrue(fails(bank(listOf(Triple(40, 0, 127)), version = 2, gain = 1000f)))
        assertTrue(fails(bank(listOf(Triple(40, 0, 127)), version = 3)))
    }

    /** A v1 bank gets fine tune 0 and the same level normalisation a rebuilt v2 bank carries (tools/bank_analysis.py gains_rms). */
    @Test fun versionOneRegionsAreNormalisedAtLoad() {
        // Note: sawtooth of amplitude 1000/32768 = -30.3 dBFS peak, RMS of the first 400 ms (here: all 8000 frames) about -35.1 dBFS
        // -> RMS target -23 dBFS asks for +12.1 dB, the peak cap (-3 dBFS) allows +27.3 dB, so the RMS gain wins.
        val note = SampleBank.read(bank(listOf(Triple(40, 0, 127)), frames = 8_000, loop = 100 to 7_000)).note(40, 0, 0)
        assertEquals(0f, note.fineTuneCents, 0f)
        val data = note.data; val n = minOf(data.size, 44_100 * 2 / 5)
        var sum = 0.0; for (i in 0 until n) { val v = data[i] / 32768.0; sum += v * v }
        val rmsDb = 20 * kotlin.math.log10(kotlin.math.sqrt(sum / n))
        assertEquals(10.0.pow((-23.0 - rmsDb) / 20.0), note.gain.toDouble(), 1e-4)
        // A spiky region (one sample at -0.8 dBFS over a quiet signal, crest 22 dB) is held by the peak cap: -3 dBFS peak instead of -21 dBFS RMS.
        val spike = SampleBank.read(bank(listOf(Triple(40, 0, 127)), sample = { if (it == 10) 30_000 else it * 3 % 200 - 100 })).note(40, 0, 0)
        assertEquals(10.0.pow((-3.0 - 20 * kotlin.math.log10(30_000 / 32768.0)) / 20.0), spike.gain.toDouble(), 1e-4)
        // Dead notes peak at -9 dBFS, release noises at -18 dBFS.
        val saw = ShortArray(400) { (it * 37 % 2000 - 1000).toShort() }
        assertEquals(10.0.pow((-9.0 - 20 * kotlin.math.log10(1000 / 32768.0)) / 20.0), SampleBank.Region.normalisation(SampleBank.KIND_DEAD, saw, 44_100).toDouble(), 1e-4)
        assertEquals(10.0.pow((-18.0 - 20 * kotlin.math.log10(1000 / 32768.0)) / 20.0), SampleBank.Region.normalisation(SampleBank.KIND_RELEASE, saw, 44_100).toDouble(), 1e-4)
    }

    @Test fun bundledBankIsConsistent() {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.firstOrNull { it.isFile } ?: return
        val b = f.inputStream().buffered().use { input -> SampleBank.read { buf, o, l -> input.read(buf, o, l) } }
        assertEquals(3, b.layers)
        for (key in 28..100) for (layer in 0 until 3) for (rr in 0 until 3) {
            val r = b.note(key, layer, rr)
            assertTrue("key $key root ${r.root}", kotlin.math.abs(r.root - key) <= 2 || key < 37 || key > 86)
            assertTrue(r.loopEnd > r.loopStart && r.decayDbPerSec >= 0f)
            assertTrue("fine tune ${r.fineTuneCents} c, gain ${r.gain}", kotlin.math.abs(r.fineTuneCents) <= 40f && r.gain in 0.1f..16f)
        }
        assertTrue(b.deadNotes.isNotEmpty() && b.release(52, 0) != null)
        assertTrue(b.deadNotes.all { it.fineTuneCents == 0f } && b.release(52, 0)!!.fineTuneCents == 0f)
    }
}
