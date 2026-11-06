package dev.riffworkbench.synth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10

/**
 * Sampled drum kit in the RWDK format written by tools/make_drum_bank.py (the bundled "ArtiFret Pearl Kit", derived from
 * AVL Black Pearl 4 by Glen MacArthur, CC BY-SA 3.0). Per General MIDI note: velocity layers (and round robins, when the
 * source has them) of one-shot samples, the kit's pan, level and exclusive (choke) class, and optionally a rimshot
 * articulation. Immutable after loading; the voices only read it, so one kit serves the real-time and the offline engine.
 *
 * File layout (little endian): "RWDK", u16 version (1), u32 sampleRate, u16 sampleCount, u16 noteCount, u16 infoLength,
 * info (UTF-8: name and attribution). Per sample: u32 frames, f32 levelDb (RMS of the first 50 ms), f32 peakDb, frames x i16.
 * Per note: u8 midi, u8 exclusiveClass (0 = none), u8 flags (bit 0: cymbal or hi-hat, keeps ringing when struck again),
 * u8 articulationCount, i16 panPermille, f32 gainDb; per articulation: u8 kind (0 normal, 1 rimshot), f32 pitchCents,
 * u8 layerCount; per layer: u8 velLo, u8 velHi, u8 rrCount, rrCount x u16 sample index.
 *
 * Velocity curve (built once at load): a stroke at velocity v sounds [VELOCITY_EXPONENT] x 20 log10(v/127) dB under the top
 * layer's own level (the synthetic kit's v^1.3), whichever layer plays it. The layers keep their recorded timbre; their
 * level differences (some layers are peak-normalised in the source, some are not) are measured (levelDb) and evened out,
 * so the loudness grows smoothly with velocity across layer borders.
 */
class DrumKit private constructor(val sampleRate: Int, val info: String, internal val samples: Array<ShortArray>, private val notes: Array<Note?>) {

    internal class Articulation(
        /** Playback-rate factor of the transposition (toms of a 4-piece kit spread over the six GM toms). */
        val pitchRatio: Double,
        /** Round-robin sample indices per layer. */
        val layers: Array<IntArray>,
        /** Layer index per velocity 0..127. */
        val layerOf: IntArray,
        /** Linear gain per velocity 0..127 (curve and layer-level compensation). */
        val velocityGain: FloatArray,
    )

    internal class Note(val midi: Int, val exclusiveClass: Int, val cymbal: Boolean, val pan: Float, val gain: Float, val normal: Articulation, val rimshot: Articulation?)

    internal fun note(midi: Int): Note? = if (midi in 0..127) notes[midi] else null
    val noteCount: Int get() = notes.count { it != null }
    val bytes: Long = samples.sumOf { it.size * 2L }
    /** GM notes the kit plays (the others fall back to the synthetic kit). */
    val midiNotes: List<Int> get() = notes.indices.filter { notes[it] != null }

    companion object {
        /** Amplitude exponent of the velocity curve (as the synthetic kit's gain = v^1.3). */
        const val VELOCITY_EXPONENT = 1.3
        /** Largest boost a quiet recorded layer may get from the level compensation. */
        private const val MAX_LAYER_BOOST_DB = 12.0
        private const val MAX_BYTES = 128L * 1024 * 1024

        fun read(reader: ByteReader): DrumKit {
            val head = ByteArray(16).also { readFully(reader, it, 16) }
            require(String(head, 0, 4, Charsets.US_ASCII) == "RWDK") { "Keine ArtiFret-Schlagzeugbank" }
            require(u16(head, 4) == 1) { "Unbekannte Schlagzeugbank-Version" }
            val rate = s32(head, 6); val sampleCount = u16(head, 10); val noteCount = u16(head, 12); val infoLength = u16(head, 14)
            require(rate in 8_000..192_000 && sampleCount in 1..4096 && noteCount in 1..128) { "Ungültiger Schlagzeugbank-Kopf" }
            val infoBytes = ByteArray(infoLength).also { readFully(reader, it, infoLength) }
            val meta = ByteArray(12)
            var total = 0L
            var chunk = ByteArray(0)
            val levels = FloatArray(sampleCount)
            val samples = Array(sampleCount) { s ->
                readFully(reader, meta, 12)
                val frames = s32(meta, 0)
                total += frames * 2L
                require(frames in 1..(60 * rate) && total <= MAX_BYTES) { "Schlagzeugbank zu groß" }
                levels[s] = Float.fromBits(s32(meta, 4))
                require(levels[s].isFinite()) { "Ungültiger Sample-Pegel" }
                if (chunk.size < frames * 2) chunk = ByteArray(frames * 2)
                readFully(reader, chunk, frames * 2)
                ShortArray(frames) { i -> ((chunk[2 * i].toInt() and 0xFF) or (chunk[2 * i + 1].toInt() shl 8)).toShort() }
            }
            val notes = arrayOfNulls<Note>(128)
            val small = ByteArray(10)
            repeat(noteCount) {
                readFully(reader, small, 10)
                val midi = small[0].toInt() and 0xFF; val excl = small[1].toInt() and 0xFF; val flags = small[2].toInt() and 0xFF
                val artCount = small[3].toInt() and 0xFF
                val pan = ((small[4].toInt() and 0xFF) or (small[5].toInt() shl 8)).toShort() / 1000f
                val gainDb = Float.fromBits(s32(small, 6))
                require(midi in 0..127 && artCount in 1..2 && abs(pan) <= 1f && gainDb.isFinite() && abs(gainDb) <= 48f) { "Ungültiger Schlagzeugbank-Eintrag" }
                var normal: Articulation? = null; var rim: Articulation? = null
                repeat(artCount) {
                    val a = ByteArray(6).also { readFully(reader, it, 6) }
                    val kind = a[0].toInt() and 0xFF
                    val cents = Float.fromBits(s32(a, 1))
                    val layerCount = a[5].toInt() and 0xFF
                    require(cents.isFinite() && abs(cents) <= 2400f && layerCount in 1..16) { "Ungültige Schlagzeug-Artikulation" }
                    val ranges = IntArray(layerCount * 2)
                    val layers = Array(layerCount) { l ->
                        val lr = ByteArray(3).also { readFully(reader, it, 3) }
                        ranges[2 * l] = lr[0].toInt() and 0xFF; ranges[2 * l + 1] = lr[1].toInt() and 0xFF
                        val rr = lr[2].toInt() and 0xFF
                        require(rr in 1..16 && ranges[2 * l] <= ranges[2 * l + 1]) { "Ungültige Velocity-Schicht" }
                        val idx = ByteArray(2 * rr).also { readFully(reader, it, 2 * rr) }
                        IntArray(rr) { k -> u16(idx, 2 * k).also { i -> require(i < sampleCount) { "Ungültiger Sample-Verweis" } } }
                    }
                    val art = articulation(cents.toDouble(), layers, ranges, levels)
                    if (kind == 1) rim = art else normal = art
                }
                notes[midi] = Note(midi, excl, flags and 1 != 0, pan, Dsp.dbToGain(gainDb.toDouble()).toFloat(), requireNotNull(normal) { "Schlagzeug-Eintrag ohne Grundartikulation" }, rim)
            }
            return DrumKit(rate, String(infoBytes, Charsets.UTF_8), samples, notes)
        }

        fun read(bytes: ByteArray): DrumKit {
            var pos = 0
            return read { b, off, len -> if (pos >= bytes.size) -1 else { val n = minOf(len, bytes.size - pos); System.arraycopy(bytes, pos, b, off, n); pos += n; n } }
        }

        /** Velocity -> layer and gain (see the class comment); the layer level is the mean of its round robins. */
        private fun articulation(cents: Double, layers: Array<IntArray>, ranges: IntArray, levels: FloatArray): Articulation {
            val layerLevel = DoubleArray(layers.size) { l -> layers[l].sumOf { levels[it].toDouble() } / layers[l].size }
            val top = layers.indices.maxByOrNull { ranges[2 * it + 1] }!!
            val layerOf = IntArray(128); val gain = FloatArray(128)
            for (v in 0..127) {
                val vel = v.coerceAtLeast(1)
                // The layer whose range holds the velocity; outside every range the nearest one.
                var best = 0; var bestDist = Int.MAX_VALUE
                for (l in layers.indices) {
                    val d = if (vel < ranges[2 * l]) ranges[2 * l] - vel else if (vel > ranges[2 * l + 1]) vel - ranges[2 * l + 1] else 0
                    if (d < bestDist) { bestDist = d; best = l }
                }
                layerOf[v] = best
                val target = layerLevel[top] + VELOCITY_EXPONENT * 20.0 * log10(vel / 127.0)
                gain[v] = Dsp.dbToGain((target - layerLevel[best]).coerceAtMost(MAX_LAYER_BOOST_DB)).toFloat()
            }
            return Articulation(exp(cents / 1200.0 * ln(2.0)), layers, layerOf, gain)
        }

        private fun readFully(r: ByteReader, b: ByteArray, n: Int) {
            var got = 0
            while (got < n) { val k = r.read(b, got, n - got); require(k > 0) { "Schlagzeugbank ist abgeschnitten" }; got += k }
        }
        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        private fun s32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)

        /** Test helper: a kit of decaying noise bursts / sines on the given GM notes (5 velocity layers each). */
        internal fun synthetic(rate: Int = 44_100, notes: IntArray = intArrayOf(36, 38, 42, 46, 49), exclusive: Map<Int, Int> = mapOf(42 to 1, 46 to 1, 49 to 2)): DrumKit {
            val samples = ArrayList<ShortArray>(); val levels = ArrayList<Float>()
            val entries = arrayOfNulls<Note>(128)
            for (m in notes) {
                val ranges = intArrayOf(1, 26, 27, 52, 53, 77, 78, 102, 103, 127)
                val layers = Array(5) { l ->
                    val n = (0.4 * rate).toInt(); var x = 0x1234 + m * 31 + l
                    val amp = 6000.0 + 5000.0 * l
                    val data = ShortArray(n) { k -> x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); (amp * exp(-k / (0.05 * rate)) * ((x and 0xFFFF) / 32768.0 - 1.0)).toInt().toShort() }
                    var s = 0.0; val w = minOf(n, rate / 20); for (k in 0 until w) s += data[k].toDouble() * data[k]
                    levels += (10 * log10(s / w / (32768.0 * 32768.0) + 1e-30)).toFloat()
                    samples += data
                    intArrayOf(samples.size - 1)
                }
                val lv = levels.toFloatArray()
                entries[m] = Note(m, exclusive[m] ?: 0, m in 42..59, 0f, 1f, articulation(0.0, layers, ranges, lv), null)
            }
            return DrumKit(rate, "synthetic test kit", samples.toTypedArray(), entries)
        }
    }
}

/**
 * One stroke of the sampled kit: plays the velocity layer's round robin once to its end, resampled with cubic
 * interpolation (kit rate to engine rate, times the articulation's transposition). Same contract as [DrumVoice]: no
 * allocation after construction; a choke fades it out over 15 ms, a stolen voice leaves its last output to the engine's
 * 4-ms residual de-click.
 */
internal class SampledDrumVoice(private val sampleRate: Int) {
    var active = false; private set
    var track = -1; private set
    var chokeGroup: Int? = null; private set
    var exclusiveClass = 0; private set
    var startClock = 0L; private set
    var midi = -1; private set
    /** Drum heads damp their previous vibration when struck again; cymbals and hi-hats keep ringing (their class chokes them). */
    var isHead = true; private set
    var lastL = 0f; private set
    var lastR = 0f; private set
    private var data: ShortArray = EMPTY
    private var pos = 0.0
    private var step = 1.0
    private var gainL = 0f
    private var gainR = 0f
    private var fade = 1f
    private var fadeStep = 0f
    private var chokeAt = Long.MAX_VALUE

    /** [gain] already holds velocity, element and kit level; [pan] -1..1. */
    fun start(kit: DrumKit, note: DrumKit.Note, art: DrumKit.Articulation, velocity: Int, rr: Int, track: Int, clock: Long, gain: Float, pan: Float, chokeGroup: Int?, chokeAfter: Long) {
        this.track = track; startClock = clock; active = true
        midi = note.midi; exclusiveClass = note.exclusiveClass; isHead = !note.cymbal; this.chokeGroup = chokeGroup
        val v = velocity.coerceIn(0, 127)
        val list = art.layers[art.layerOf[v]]
        data = kit.samples[list[(rr and 0x7FFFFFFF) % list.size]]
        pos = 0.0
        step = kit.sampleRate.toDouble() / sampleRate * art.pitchRatio
        val g = gain * art.velocityGain[v]
        // Same balance law as the synthetic kit: the far side is turned down, the near side stays.
        gainL = g * (1f - pan.coerceAtLeast(0f) * 0.9f)
        gainR = g * (1f + pan.coerceAtMost(0f) * 0.9f)
        fade = 1f; fadeStep = 0f; lastL = 0f; lastR = 0f
        chokeAt = if (chokeAfter >= 0) clock + chokeAfter else Long.MAX_VALUE
    }

    fun choke() { if (active && fadeStep == 0f) fadeStep = 1f / (0.015f * sampleRate) }
    /**
     * Transport start (restartDrums): the stroke heard before Play (a previewed grid cell, the last hits before Stop) fades
     * out over 1 ms of its own signal. The 4-ms residual of a stolen voice (a held last sample value) put up to +2.8 dB into
     * the first 5 ms of the first hit 50 ms after a previewed sampled snare (whose body is still loud there), -1.8 dB in 5-10 ms.
     */
    fun fadeOutQuickly() { if (active) { val step = fade / (0.001f * sampleRate); if (step > fadeStep) fadeStep = step } }
    fun kill() { active = false }

    fun render(left: FloatArray, right: FloatArray, offset: Int, count: Int, clock: Long) {
        if (!active) return
        val d = data
        val last = d.size - 1
        for (k in 0 until count) {
            if (clock + k >= chokeAt) choke()
            if (pos >= last) { active = false; lastL = 0f; lastR = 0f; return }
            val base = pos.toInt()
            val f = (pos - base).toFloat()
            val y0 = d[if (base > 0) base - 1 else 0].toFloat(); val y1 = d[base].toFloat(); val y2 = d[base + 1].toFloat(); val y3 = d[if (base + 2 <= last) base + 2 else last].toFloat()
            val c1 = 0.5f * (y2 - y0)
            val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
            val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
            var s = (((c3 * f + c2) * f + c1) * f + y1) * (1f / 32768f)
            pos += step
            if (fadeStep > 0f) { fade -= fadeStep; if (fade <= 0f) { active = false; lastL = 0f; lastR = 0f; return }; s *= fade }
            lastL = s * gainL; lastR = s * gainR
            left[offset + k] += lastL
            right[offset + k] += lastR
        }
    }

    private companion object { val EMPTY = ShortArray(2) }
}
