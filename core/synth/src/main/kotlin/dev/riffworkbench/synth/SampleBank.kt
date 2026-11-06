package dev.riffworkbench.synth

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Reads bytes like an input stream: fills [buffer] from [offset], returns the count read or -1 at the end. */
fun interface ByteReader { fun read(buffer: ByteArray, offset: Int, length: Int): Int }

/**
 * Multisampled instrument in the RWSB format written by tools/make_guitar_bank.py: sustained notes per
 * velocity layer and round robin (with a seamless loop and their natural decay), release noises and dead notes.
 * Immutable after loading; voices only read it, so one bank serves the real-time and the offline engine.
 *
 * File layout (little endian). Header: "RWSB", u16 version, u32 sampleRate, u32 regionCount. Per region:
 * - v1: u8 kind (0 note, 1 release, 2 dead), u8 rootMidi, u8 loKey, u8 hiKey, u8 layer, u8 rr, f32 decayDbPerSec,
 *   u32 loopStart, u32 loopEnd (0 = no loop), u32 frames, then frames x i16 PCM.
 * - v2: the v1 fields, then f32 fineTuneCents and f32 gain, then the PCM. [Region.fineTuneCents] is the measured pitch
 *   of the sustained body relative to rootMidi (the voice plays the region that much lower, so it lands on the root);
 *   [Region.gain] is the level normalisation measured by tools/bank_analysis.py.
 * A v1 bank gets fineTune 0 and the same normalisation computed at load time ([Region.normalisation]), so old banks
 * are level-matched exactly like a rebuilt v2 bank.
 */
class SampleBank private constructor(val sampleRate: Int, internal val regions: Array<Region>) {
    internal class Region(val kind: Int, val root: Int, val loKey: Int, val hiKey: Int, val layer: Int, val rr: Int,
                          val decayDbPerSec: Float, val loopStart: Int, val loopEnd: Int, val data: ShortArray,
                          /** Measured pitch of the sustained body in cents above [root] (0 for releases and dead notes). */
                          val fineTuneCents: Float = 0f,
                          /** Linear level normalisation applied by the voice (see [normalisation]). */
                          val gain: Float = 1f) {
        val frames get() = data.size
        /**
         * Share of the power of this (sustained) region in the overtone series of its [n]-th partial, index n = 2..6, measured
         * once at load over 30-200 ms (QA cycle 22): the harmonic comb keeps only that series, so a weak recorded partial makes a
         * weak pinch/artificial harmonic (the high DI notes have their 3rd partial 20-33 dB under the fundamental).
         */
        internal var overtoneShare: FloatArray = NO_SHARE
        /** Amplitude factor that gives the +6 dB/octave tilted series (pinch, see SampleVoice) the power of the untilted one, n = 2..6. */
        internal var overtoneTiltGain: FloatArray = NO_SHARE

        companion object {
            private val NO_SHARE = FloatArray(0)

            /**
             * Power share of the comb of n copies spaced a 1/n period of the region's root apart (the voice's harmonic comb) at
             * index n, and in [tiltGain] the amplitude factor that keeps that power once the series is tilted by a first
             * difference normalised to unity at the n-th partial.
             */
            fun overtoneShares(r: Region, sampleRate: Int, tiltGain: FloatArray): FloatArray {
                val hz = 440.0 * Math_pow2((r.root + r.fineTuneCents / 100.0 - 69.0) / 12.0)
                val period = sampleRate / hz
                val from = maxOf((0.03 * sampleRate).toInt(), period.toInt() + 2)
                val to = minOf((0.2 * sampleRate).toInt(), r.data.size - 1)
                val out = FloatArray(7)
                if (to - from < period * 2) return out
                var full = 0.0
                for (i in from until to) { val v = r.data[i].toDouble(); full += v * v }
                for (n in 2..6) {
                    var e = 0.0; var et = 0.0; var prev = Double.NaN
                    val diffGain = 0.5 / kotlin.math.sin(Math.PI * n / period).coerceAtLeast(1e-3)
                    for (i in from until to) {
                        var sum = 0.0
                        for (m in 0 until n) {
                            val p = i - m * period / n
                            val k = kotlin.math.floor(p).toInt(); val f = p - k
                            sum += r.data[k] + (r.data[k + 1] - r.data[k]) * f
                        }
                        val y = sum / n; e += y * y
                        if (!prev.isNaN()) { val d = (y - prev) * diffGain; et += d * d }
                        prev = y
                    }
                    out[n] = if (full > 0) (e / full).toFloat() else 0f
                    tiltGain[n] = if (et > 0) sqrt(e / et).toFloat() else 1f
                }
                return out
            }

            /** Peak target of release noises: their natural level in the source, so note-to-release balance is unchanged. */
            private const val RELEASE_PEAK_DB = -18.0
            private const val DEAD_PEAK_DB = -9.0
            private const val NOTE_RMS_DB = -23.0
            private const val NOTE_PEAK_CAP_DB = -3.0

            /**
             * Level normalisation of a region (identical to tools/bank_analysis.py gains_rms): a note's first 400 ms (attack
             * and early sustain) are brought to -23 dBFS RMS, so zones and velocity layers are loudness-matched, capped so
             * its peak stays at or below -3 dBFS; dead notes peak at -9 dBFS and release noises at -18 dBFS.
             */
            fun normalisation(kind: Int, data: ShortArray, sampleRate: Int): Float {
                var peak = 1
                for (s in data) { val a = abs(s.toInt()); if (a > peak) peak = a }
                val peakDb = 20.0 * log10(peak / 32768.0)
                val targetPeakDb = when (kind) {
                    KIND_DEAD -> DEAD_PEAK_DB
                    KIND_RELEASE -> RELEASE_PEAK_DB
                    else -> {
                        val n = minOf(data.size, sampleRate * 2 / 5)
                        var sum = 0.0
                        for (i in 0 until n) { val v = data[i] / 32768.0; sum += v * v }
                        val rms = sqrt(sum / n.coerceAtLeast(1))
                        val rmsDb = if (rms <= 1e-12) -240.0 else 20.0 * log10(rms)
                        // The RMS gain, expressed as the peak it would produce, limited by the cap.
                        minOf(peakDb + (NOTE_RMS_DB - rmsDb), NOTE_PEAK_CAP_DB)
                    }
                }
                return 10.0.pow((targetPeakDb - peakDb) / 20.0).toFloat()
            }
        }
    }

    /** [key][layer] -> round robins (sustained notes). */
    private val notes: Array<Array<Array<Region>>>
    private val releases: Array<Array<Region>>
    internal val deadNotes: Array<Region>
    val layers: Int
    val bytes: Long = regions.sumOf { it.data.size * 2L }

    init {
        layers = (regions.filter { it.kind == KIND_NOTE }.maxOfOrNull { it.layer } ?: 0) + 1
        notes = Array(128) { key -> Array(layers) { layer -> regions.filter { it.kind == KIND_NOTE && it.layer == layer && key in it.loKey..it.hiKey }.sortedBy { it.rr }.toTypedArray() } }
        releases = Array(128) { key -> regions.filter { it.kind == KIND_RELEASE && key in it.loKey..it.hiKey }.sortedBy { it.rr }.toTypedArray() }
        deadNotes = regions.filter { it.kind == KIND_DEAD }.toTypedArray()
        for (r in regions) if (r.kind == KIND_NOTE) { val tilt = FloatArray(7); r.overtoneShare = Region.overtoneShares(r, sampleRate, tilt); r.overtoneTiltGain = tilt }
        require(notes.all { k -> k.all { it.isNotEmpty() } }) { "Sample-Bank deckt nicht alle Tonhöhen ab" }
    }

    internal fun note(key: Int, layer: Int, rr: Int): Region { val list = notes[key.coerceIn(0, 127)][layer.coerceIn(0, layers - 1)]; return list[rr % list.size] }
    internal fun release(key: Int, rr: Int): Region? { val list = releases[key.coerceIn(0, 127)]; return if (list.isEmpty()) null else list[rr % list.size] }

    companion object {
        const val KIND_NOTE = 0
        const val KIND_RELEASE = 1
        const val KIND_DEAD = 2
        private const val MAX_BYTES = 256L * 1024 * 1024
        private const val META_V1 = 22
        private const val META_V2 = 30

        /** Parses a bank while streaming, so only the PCM (not a second copy of the file) is held in memory. */
        fun read(reader: ByteReader): SampleBank {
            val head = ByteArray(14).also { readFully(reader, it, 14) }
            require(String(head, 0, 4, Charsets.US_ASCII) == "RWSB") { "Keine RiffWorkbench-Sample-Bank" }
            val version = u16(head, 4)
            require(version == 1 || version == 2) { "Unbekannte Sample-Bank-Version" }
            val rate = s32(head, 6); val count = s32(head, 10)
            require(rate in 8_000..192_000 && count in 1..10_000) { "Ungültiger Sample-Bank-Kopf" }
            val metaSize = if (version == 2) META_V2 else META_V1
            val meta = ByteArray(metaSize)
            var total = 0L
            var chunk = ByteArray(0)
            val regions = Array(count) {
                readFully(reader, meta, metaSize)
                val frames = s32(meta, 18)
                total += frames * 2L
                require(frames in 1..(60 * rate) && total <= MAX_BYTES) { "Sample-Bank zu groß" }
                if (chunk.size < frames * 2) chunk = ByteArray(frames * 2)
                readFully(reader, chunk, frames * 2)
                val data = ShortArray(frames) { i -> ((chunk[2 * i].toInt() and 0xFF) or (chunk[2 * i + 1].toInt() shl 8)).toShort() }
                val loopStart = s32(meta, 10); val loopEnd = s32(meta, 14)
                require(loopEnd == 0 || (loopStart >= 0 && loopEnd - loopStart >= 4 && loopEnd <= frames)) { "Ungültige Loop-Punkte" }
                val kind = meta[0].toInt()
                val fineTune = if (version == 2) Float.fromBits(s32(meta, 22)) else 0f
                val gain = if (version == 2) Float.fromBits(s32(meta, 26)) else Region.normalisation(kind, data, rate)
                require(fineTune.isFinite() && abs(fineTune) <= 100f && gain.isFinite() && gain > 0f && gain <= 64f) { "Ungültige Region-Stimmung oder -Pegel" }
                Region(kind, meta[1].toInt() and 0xFF, meta[2].toInt() and 0xFF, meta[3].toInt() and 0xFF, meta[4].toInt(), meta[5].toInt(),
                    Float.fromBits(s32(meta, 6)), loopStart, loopEnd, data, fineTune, gain)
            }
            return SampleBank(rate, regions)
        }

        fun read(bytes: ByteArray): SampleBank {
            var pos = 0
            return read { b, off, len -> if (pos >= bytes.size) -1 else { val n = minOf(len, bytes.size - pos); System.arraycopy(bytes, pos, b, off, n); pos += n; n } }
        }

        /** Test helper: a bank of sine "notes" (one layer, one round robin per minor third). */
        internal fun synthetic(rate: Int = 44_100, seconds: Double = 1.0): SampleBank {
            val roots = (37..86 step 3).toList()
            val regions = roots.mapIndexed { i, root ->
                val hz = 440.0 * Math_pow2((root - 69) / 12.0)
                val n = (seconds * rate).toInt()
                val data = ShortArray(n) { k -> (kotlin.math.sin(2 * Math.PI * hz * k / rate) * 12000 * kotlin.math.exp(-1.5 * k / rate)).toInt().toShort() }
                val period = rate / hz
                val loopEnd = n - 10; val loopStart = (loopEnd - kotlin.math.round(kotlin.math.round(0.2 * rate / period) * period)).toInt()
                Region(KIND_NOTE, root, if (i == 0) 0 else root - 1, if (i == roots.lastIndex) 127 else root + 1, 0, 0, 13f, loopStart, loopEnd, data)
            } + Region(KIND_DEAD, 52, 0, 127, 0, 0, 0f, 0, 0, ShortArray(2000) { k -> ((k * 7919 % 2000) - 1000).toShort() })
            return SampleBank(rate, regions.toTypedArray())
        }
        internal fun Math_pow2(x: Double) = kotlin.math.exp(x * kotlin.math.ln(2.0))

        private fun readFully(r: ByteReader, b: ByteArray, n: Int) {
            var got = 0
            while (got < n) { val k = r.read(b, got, n - got); require(k > 0) { "Sample-Bank ist abgeschnitten" }; got += k }
        }
        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        private fun s32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)
    }
}
