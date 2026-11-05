package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.synth.PcmBuilder
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** DSP defects found by accuracy testing (QA cycle 4). The linear resampler (D3) is the documented design and not tested here. */
class DspRegressionTest {
    private fun centsOff(est: Double, hz: Double) = 1200 * ln(est / hz) / ln(2.0)

    /** D1: parabolic refinement on the normalised d' is biased; error grows with pitch (+10 cents at E6). */
    @Test fun d1_yinIsSharpAtHighPitches() {
        val yin = YinPitchDetector(12_000, 1024)
        for (hz in listOf(659.26, 1046.5, 1318.5).map { it * 2.0.pow(7.3 / 1200) }) {
            val x = FloatArray(1024) { (0.5 * sin(2 * PI * hz * it / 12_000)).toFloat() }
            val c = centsOff(yin.detect(x)!!.hz, hz)
            assertTrue("pure sine $hz Hz read ${"%.2f".format(c)} cents off", abs(c) < 3.0)
        }
    }

    /** D2: tones above the tuner band alias / fold into it and produce confident false readings (pure tone, 48 kHz input). */
    @Test fun d2_highFrequencyToneGivesFalsePitch() {
        val t = DspKitTuner()
        for (f in listOf(5_000.0, 10_000.0, 13_000.0)) {
            val x = FloatArray(48_000) { (0.5 * sin(2 * PI * f * it / 48_000)).toFloat() }
            val r = t.runTuner(48_000, x)
            assertEquals("pure $f Hz tone must not yield a pitch, got ${r.readings.firstOrNull()?.first}", 0, r.readings.size)
        }
    }

    /** D4: data chunk size 0 (unpatched header) is rejected although the samples are present. */
    @Test fun d4_unpatchedZeroDataSizeIsRejected() {
        val t = DspKitWav()
        val bytes = t.riff(listOf(t.fmt(1, 1, 48_000, 16), DspKitWav.Chunk("data", ByteArray(2000) { 1 }, sizeOverride = 0)))
        val (r, cap) = t.decode(bytes)
        assertTrue("got $r", r is Outcome.Success && cap.frames == 1000L)
    }

    /** D5: nBlockAlign is ignored: 24-bit samples in 4-byte containers decode as garbage with a wrong frame count. */
    @Test fun d5_blockAlignIgnored() {
        val t = DspKitWav()
        val body = DspKitWav.B().apply { for (i in 0 until 1000) le((i * 4000L) shl 8, 4) }.bytes()
        val bytes = t.riff(listOf(t.fmt(1, 1, 48_000, 24, blockAlign = 4), DspKitWav.Chunk("data", body)))
        val (r, cap) = t.decode(bytes)
        assertTrue("got $r frames=${cap.frames}", r is Outcome.Failure || cap.frames == 1000L)
    }

    /** D6: WaveformLevel.durationSeconds counts the partial last bucket as a full one. */
    @Test fun d6_waveformDurationIncludesPartialBucket() = runBlocking {
        val assets = DspKitMixdown.MemoryAssets()
        val bytes = DspKitMixdown().wav16(48_000, 1, 1000) { _, _ -> 0.1 }
        val hash = (assets.put("w", "audio/wav", bytes) as Outcome.Success).value.hash
        val l = (StreamingWaveforms(assets, AudioDecoders()).peaks(hash, 300) as Outcome.Success).value
        assertEquals(1000 / 48_000.0, l.durationSeconds, 1.0 / 48_000)
    }
}
