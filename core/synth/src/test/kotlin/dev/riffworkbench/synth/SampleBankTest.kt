package dev.riffworkbench.synth

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleBankTest {
    private fun bank(regions: List<Triple<Int, Int, Int>>, frames: Int = 400, loop: Pair<Int, Int> = 100 to 300, magic: String = "RWSB"): ByteArray {
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).put(magic.toByteArray()).putShort(1).putInt(44_100).putInt(regions.size)
        out.write(head.array())
        for ((root, lo, hi) in regions) {
            val m = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).put(0).put(root.toByte()).put(lo.toByte()).put(hi.toByte()).put(0).put(0)
                .putFloat(10f).putInt(loop.first).putInt(loop.second).putInt(frames)
            out.write(m.array())
            val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN); repeat(frames) { pcm.putShort((it * 37 % 2000 - 1000).toShort()) }
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

    @Test fun bundledBankIsConsistent() {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.firstOrNull { it.isFile } ?: return
        val b = f.inputStream().buffered().use { input -> SampleBank.read { buf, o, l -> input.read(buf, o, l) } }
        assertEquals(3, b.layers)
        for (key in 28..100) for (layer in 0 until 3) for (rr in 0 until 3) {
            val r = b.note(key, layer, rr)
            assertTrue("key $key root ${r.root}", kotlin.math.abs(r.root - key) <= 2 || key < 37 || key > 86)
            assertTrue(r.loopEnd > r.loopStart && r.decayDbPerSec >= 0f)
        }
        assertTrue(b.deadNotes.isNotEmpty() && b.release(52, 0) != null)
    }
}
