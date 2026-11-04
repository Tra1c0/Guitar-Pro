package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Unit conversions between GPIF and the model. All GPIF numbers below are as read from score.gpif text.
 */
internal object GpifUnits {
    private val noteValues = listOf("Whole" to 1, "Half" to 2, "Quarter" to 4, "Eighth" to 8, "16th" to 16, "32nd" to 32, "64th" to 64, "128th" to 128, "256th" to 256)
    fun denominatorOf(noteValue: String): Int? = noteValues.firstOrNull { it.first == noteValue }?.second
    fun noteValueOf(denominator: Int): String? = noteValues.firstOrNull { it.second == denominator }?.first

    /**
     * Bends and whammy: GPIF values are hundredths of a whole tone (100 = full bend = 2 semitones), so
     * cents = value * 2 exactly. Offsets are percent (0..100) of the note's (beat's) duration -> Fraction 0..1.
     */
    fun centsOf(value: Double): Int = Math.round((value * 2.0).coerceIn(-1e6, 1e6)).toInt()
    fun valueOf(cents: Int): Double = cents / 2.0
    fun positionOf(offsetPercent: Double): Fraction = GpifNumbers.rational(offsetPercent.coerceIn(0.0, 100.0) / 100.0, 1000, 1e-6)
    fun offsetOf(position: Fraction): Double = position.toDouble() * 100.0

    /**
     * Harmonics: GPIF HarmonicFret is the touch point in frets above the open string (natural) or above the
     * fretted note (artificial/pinch/tapped). The node as a fraction of the vibrating length is
     * x = 1 - 2^(-fret/12); we keep the nearest exact overtone node k/n (n <= 12) when it is close enough.
     */
    fun nodeForFret(fret: Double): Fraction? {
        if (!fret.isFinite() || fret <= 0.0 || fret > 48.0) return null
        val x = 1.0 - 2.0.pow(-fret / 12.0)
        var best: Fraction? = null
        var bestError = Double.MAX_VALUE
        for (n in 2..12) for (k in 1 until n) {
            val f = Fraction.of(k.toLong(), n.toLong())
            if (f.denominator != n.toLong()) continue
            val error = abs(x - f.toDouble())
            if (error < bestError) { bestError = error; best = f }
        }
        return best?.takeIf { bestError <= 0.012 }
    }
    fun fretForNode(node: Fraction): Double = -12.0 * ln(1.0 - node.toDouble()) / ln(2.0)
    fun harmonicFretText(fret: Double): String {
        val rounded = Math.round(fret)
        if (abs(fret - rounded) < 0.05) return rounded.toString()
        return GpifNumbers.decimal((fret * 100.0).roundToInt() / 100.0)
    }

    /** Dynamics map onto the same velocities the sequencer uses for the Dynamic articulation. */
    fun velocityOf(level: DynamicLevel): Int = ScorePlaybackCompiler.dynamicVelocity(level)
    fun levelOf(velocity: Int): DynamicLevel = DynamicLevel.entries.minBy { abs(velocityOf(it) - velocity) }

    /**
     * Tempo automation value "<bpm> <reference>": reference 1 = eighth, 2 = quarter, 3 = dotted quarter,
     * 4 = half, 5 = dotted half (2 is what Guitar Pro writes by default; the others are best knowledge).
     * Returns the factor that converts reference beats per minute to quarter notes per minute.
     */
    fun quarterFactor(reference: Int): Double? = when (reference) { 1 -> 0.5; 2 -> 1.0; 3 -> 1.5; 4 -> 2.0; 5 -> 3.0; else -> null }

    /** Tremolo picking: GPIF "1/2" = eighths, "1/4" = sixteenths, "1/8" = thirty-seconds. */
    fun tremoloDenominator(text: String): Int? = when (text.trim()) { "1/2" -> 8; "1/4" -> 16; "1/8" -> 32; else -> null }
    fun tremoloText(denominator: Int): String? = when (denominator) { 8 -> "1/2"; 16 -> "1/4"; 32 -> "1/8"; else -> null }

    /** Brush/arpeggio duration (beat XProperty 687935489) is assumed to be MIDI ticks at 960 per quarter. */
    const val TICKS_PER_WHOLE = 3840L
}

/** General MIDI percussion names and our percussion staff layout (staff line 0 = top line, positive = downwards). */
internal object GpDrums {
    class Entry(val name: String, val line: Int, val cymbal: Boolean)
    private val table = mapOf(
        35 to Entry("Acoustic Kick Drum", 7, false), 36 to Entry("Kick Drum", 7, false), 37 to Entry("Side Stick", 3, true),
        38 to Entry("Snare", 3, false), 39 to Entry("Hand Clap", 3, true), 40 to Entry("Electric Snare", 3, false),
        41 to Entry("Low Floor Tom", 6, false), 42 to Entry("Closed Hi-Hat", -1, true), 43 to Entry("High Floor Tom", 5, false),
        44 to Entry("Pedal Hi-Hat", 9, true), 45 to Entry("Low Tom", 4, false), 46 to Entry("Open Hi-Hat", -1, true),
        47 to Entry("Low-Mid Tom", 2, false), 48 to Entry("Hi-Mid Tom", 1, false), 49 to Entry("Crash Cymbal 1", -2, true),
        50 to Entry("High Tom", 0, false), 51 to Entry("Ride Cymbal 1", 0, true), 52 to Entry("Chinese Cymbal", -3, true),
        53 to Entry("Ride Bell", 0, true), 54 to Entry("Tambourine", -1, true), 55 to Entry("Splash Cymbal", -3, true),
        56 to Entry("Cowbell", 1, true), 57 to Entry("Crash Cymbal 2", -3, true), 59 to Entry("Ride Cymbal 2", 0, true),
    )
    fun entry(midi: Int): Entry = table[midi] ?: Entry("Percussion $midi", 3, false)

    /** Techniques that are not implied by the MIDI note are encoded in the articulation name. */
    fun techniqueLabel(kind: PercussionTechnique): String? = when (kind) {
        PercussionTechnique.RIMSHOT -> "rim shot"; PercussionTechnique.CROSS_STICK -> "side stick"; PercussionTechnique.CHOKE -> "choke"
        else -> null
    }
    fun techniqueOf(articulationName: String): PercussionTechnique? {
        val n = articulationName.lowercase()
        return when {
            "rim shot" in n || "rimshot" in n -> PercussionTechnique.RIMSHOT
            "side stick" in n || "cross stick" in n -> PercussionTechnique.CROSS_STICK
            "choke" in n -> PercussionTechnique.CHOKE
            else -> null
        }
    }
}

/**
 * Content/PartConfiguration (binary, big endian), best knowledge of what Guitar Pro 7/8 write:
 * int32 view count; per view: 1 byte multi-rest flag, int32 item count, one flag byte per track
 * (0x01 standard notation, 0x02 tablature, 0x04 slash, 0x08 numbered/jianpu). View 0 lists all tracks,
 * followed by one single-track view per track.
 */
internal object PartConfiguration {
    const val STANDARD = 0x01
    const val TAB = 0x02
    const val SLASH = 0x04
    const val NUMBERED = 0x08

    fun write(flags: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        fun int32(v: Int) { out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        int32(1 + flags.size)
        out.write(0); int32(flags.size); flags.forEach { out.write(it and 0xFF) }
        flags.forEach { out.write(0); int32(1); out.write(it and 0xFF) }
        return out.toByteArray()
    }

    /** Flags of the multi-track view, or null when the data does not have the expected shape. */
    fun read(data: ByteArray): List<Int>? {
        if (data.size < 9) return null
        fun int32(at: Int): Int = ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
        val views = int32(0)
        if (views < 1 || views > 4096) return null
        val count = int32(5)
        if (count < 0 || count > 4096 || 9 + count > data.size) return null
        return (0 until count).map { data[9 + it].toInt() and 0xFF }
    }

    fun flagsOf(notation: Set<NotationKind>): Int {
        var f = 0
        if (NotationKind.STANDARD in notation || NotationKind.PERCUSSION in notation) f = f or STANDARD
        if (NotationKind.TAB in notation) f = f or TAB
        if (NotationKind.SLASH in notation) f = f or SLASH
        if (NotationKind.JIANPU in notation) f = f or NUMBERED
        return f
    }
    fun notationOf(flags: Int, percussion: Boolean): Set<NotationKind> = buildSet {
        if (flags and STANDARD != 0) add(if (percussion) NotationKind.PERCUSSION else NotationKind.STANDARD)
        if (flags and TAB != 0) add(NotationKind.TAB)
        if (flags and SLASH != 0) add(NotationKind.SLASH)
        if (flags and NUMBERED != 0) add(NotationKind.JIANPU)
    }
}
