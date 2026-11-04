package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioDecodingTest {
    private class MemoryAssets : AssetStore {
        val data = HashMap<AssetHash, ByteArray>()
        override suspend fun put(name: String, mediaType: String, bytes: ByteArray, rights: AssetRights): Outcome<AssetDescriptor> {
            val hash = AssetHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            data[hash] = bytes
            return Outcome.Success(AssetDescriptor(hash, name, mediaType, bytes.size.toLong(), rights))
        }
        override suspend fun get(hash: AssetHash): Outcome<ByteArray> = data[hash]?.let { Outcome.Success(it) } ?: Outcome.Failure(FailureCode.IO, "fehlt")
    }

    /** Own synthetic WAV writer: [format] 1 = PCM, 3 = float; [value] maps frame/channel to a sample in -1..1. */
    private fun wav(rate: Int, channels: Int, bits: Int, frames: Int, format: Int = 1, dataSizeOverride: Long? = null, value: (Int, Int) -> Double): ByteArray {
        val frameBytes = channels * bits / 8
        val data = ByteArray(frames * frameBytes)
        var p = 0
        fun le(v: Long, n: Int) { for (i in 0 until n) data[p++] = ((v ushr (8 * i)) and 0xFF).toByte() }
        for (f in 0 until frames) for (c in 0 until channels) {
            val v = value(f, c)
            when {
                format == 3 && bits == 32 -> le(v.toFloat().toRawBits().toLong() and 0xFFFFFFFFL, 4)
                format == 3 -> le(v.toRawBits(), 8)
                bits == 8 -> le(((v * 127).toInt() + 128).toLong(), 1)
                else -> le((v * ((1L shl (bits - 1)) - 1)).toLong(), bits / 8)
            }
        }
        val header = ByteArray(44)
        fun put(at: Int, v: Long, n: Int) { for (i in 0 until n) header[at + i] = ((v ushr (8 * i)) and 0xFF).toByte() }
        "RIFF".forEachIndexed { i, ch -> header[i] = ch.code.toByte() }; put(4, 36L + data.size, 4)
        "WAVEfmt ".forEachIndexed { i, ch -> header[8 + i] = ch.code.toByte() }; put(16, 16, 4); put(20, format.toLong(), 2); put(22, channels.toLong(), 2)
        put(24, rate.toLong(), 4); put(28, rate.toLong() * frameBytes, 4); put(32, frameBytes.toLong(), 2); put(34, bits.toLong(), 2)
        "data".forEachIndexed { i, ch -> header[36 + i] = ch.code.toByte() }; put(40, dataSizeOverride ?: data.size.toLong(), 4)
        return header + data
    }

    private fun load(bytes: ByteArray, rate: Int = 48_000, maxSeconds: Int = 600) = runBlocking {
        val assets = MemoryAssets()
        val hash = (assets.put("ref.wav", "audio/wav", bytes) as Outcome.Success).value.hash
        DecodingClipLoader(assets, AudioDecoders(), maxSeconds).load(hash, rate)
    }

    @Test fun pcm16StereoIsResampledToTheEngineRateWithImpulsesInPlace() {
        // Impulses every 11025 frames (0.25 s at 44.1 kHz) on the left channel, constant -0.25 on the right.
        val bytes = wav(44_100, 2, 16, 44_100) { f, c -> if (c == 0) (if (f % 11_025 == 0) 0.9 else 0.0) else -0.25 }
        val pcm = (load(bytes) as Outcome.Success).value
        assertEquals(48_000, pcm.sampleRate)
        assertEquals(47_999, pcm.frames) // floor((44100 - 1) / (44100 / 48000)) + 1
        for (k in 0 until 4) assertTrue("impulse $k", pcm.data[2 * (k * 12_000)] > 25_000)
        assertEquals(-8191.0, pcm.data[2 * 1000 + 1].toDouble(), 2.0)
    }

    @Test fun everySupportedSampleFormatDecodesToTheSameLevel() {
        val cases = listOf(Triple(1, 8, 1), Triple(1, 16, 1), Triple(1, 24, 2), Triple(1, 32, 2), Triple(3, 32, 1), Triple(3, 64, 2))
        for ((format, bits, channels) in cases) {
            val pcm = (load(wav(48_000, channels, bits, 4800, format) { _, _ -> 0.5 }) as Outcome.Success).value
            assertEquals("$format/$bits", 4800, pcm.frames)
            val tolerance = if (bits == 8) 300.0 else 3.0
            assertEquals("$format/$bits left", 16_383.0, pcm.data[200].toDouble(), tolerance)
            assertEquals("$format/$bits right", 16_383.0, pcm.data[201].toDouble(), tolerance)
        }
    }

    @Test fun unpatchedDataSizeIsClampedToTheBytesPresent() {
        val pcm = (load(wav(48_000, 1, 16, 1000, dataSizeOverride = 0xFFFFFFFFL) { _, _ -> 0.1 }) as Outcome.Success).value
        assertEquals(1000, pcm.frames)
    }

    @Test fun longClipsAreCutAtTheLimit() {
        val pcm = (load(wav(8_000, 1, 16, 8_000 * 3) { _, _ -> 0.1 }, rate = 8_000, maxSeconds = 2) as Outcome.Success).value
        assertEquals(16_000, pcm.frames)
    }

    @Test fun malformedAndForeignFilesFailWithAMessage() {
        val good = wav(48_000, 1, 16, 100) { _, _ -> 0.0 }
        val noData = good.copyOf(36).also { it[4] = 28 }
        val badBits = good.copyOf().also { it[34] = 12 }
        val corruptChunk = good.copyOf().also { it[16] = 0x7F; it[17] = 0x7F }
        for (bytes in listOf(noData, badBits, corruptChunk, ByteArray(10), "ID3\u0004 not audio".toByteArray())) {
            val r = load(bytes)
            assertTrue(r is Outcome.Failure)
            assertTrue((r as Outcome.Failure).message.isNotBlank())
        }
    }

    @Test fun probeReportsFormatWithoutDecoding() = runBlocking {
        val info = (AudioDecoders().probe(wav(44_100, 2, 24, 44_100 * 2) { _, _ -> 0.0 }) as Outcome.Success).value
        assertEquals(AudioInfo("audio/wav", 44_100, 2, 2_000_000), info)
    }

    @Test fun compressedFormatsNeedThePlatformDecoder() = runBlocking {
        val mp3ish = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(400)
        assertTrue(AudioDecoders().probe(mp3ish) is Outcome.Failure)
        val fake = object : AudioDecoder {
            override suspend fun probe(bytes: ByteArray) = Outcome.Success(AudioInfo("audio/mpeg", 22_050, 1, 1_000_000))
            override suspend fun decode(bytes: ByteArray, sink: PcmSink): Outcome<AudioInfo> {
                val chunk = ShortArray(1000) { 1000 }
                repeat(23) { sink.accept(22_050, 1, chunk, 1000) }
                return probe(bytes)
            }
        }
        val assets = MemoryAssets(); val hash = (assets.put("a.mp3", "audio/mpeg", mp3ish) as Outcome.Success).value.hash
        val pcm = (DecodingClipLoader(assets, AudioDecoders(fake)).load(hash, 44_100) as Outcome.Success).value
        assertEquals(45_999, pcm.frames) // 23000 frames at 22.05 kHz -> 44.1 kHz
        assertEquals(1000, pcm.data[2 * 30_000].toInt())
    }
}
