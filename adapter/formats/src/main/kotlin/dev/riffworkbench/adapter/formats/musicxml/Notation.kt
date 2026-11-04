package dev.riffworkbench.adapter.formats.musicxml

import dev.riffworkbench.model.*

internal data class Spelled(val step: Char, val alter: Int, val octave: Int) {
    /** Compact form stored in [Pitch.spelling], e.g. "F#3", "Bb2", "C##5". */
    fun text(): String = step.toString() + when (alter) { 2 -> "##"; 1 -> "#"; -1 -> "b"; -2 -> "bb"; else -> "" } + octave
}

/** Pitch spelling from MIDI numbers using the key signature. */
internal object PitchSpelling {
    private const val STEPS = "CDEFGAB"
    private val stepPc = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private const val SHARP_ORDER = "FCGDAEB"

    fun stepIndex(step: Char) = STEPS.indexOf(step)
    fun stepPitchClass(step: Char) = stepPc[STEPS.indexOf(step)]

    fun keyAlter(step: Char, fifths: Int): Int = when {
        fifths > 0 && SHARP_ORDER.indexOf(step) < fifths -> 1
        fifths < 0 && SHARP_ORDER.reversed().indexOf(step) < -fifths -> -1
        else -> 0
    }

    /**
     * Diatonic notes of the key keep their key spelling; other pitch classes use a natural when one exists,
     * otherwise a sharp in sharp keys and a flat in flat keys (C major: C#, Eb, F#, G#, Bb).
     */
    fun spell(midi: Int, fifths: Int): Spelled {
        val pc = Math.floorMod(midi, 12)
        for (i in 0..6) {
            val alter = keyAlter(STEPS[i], fifths)
            if (Math.floorMod(stepPc[i] + alter, 12) == pc) return make(STEPS[i], alter, midi)
        }
        for (i in 0..6) if (stepPc[i] == pc) return make(STEPS[i], 0, midi)
        val sharp = when { fifths > 0 -> true; fifths < 0 -> false; else -> pc == 1 || pc == 6 || pc == 8 }
        return if (sharp) make(STEPS[stepPc.indexOf(pc - 1)], 1, midi) else make(STEPS[stepPc.indexOf((pc + 1) % 12)], -1, midi)
    }

    /** Uses a stored spelling such as "Gb4" when it names the same pitch class; the octave follows the MIDI number. */
    fun fromSpelling(spelling: String?, midi: Int): Spelled? {
        val m = Regex("([A-G])(##|#|x|bb|b)?(-?\\d+)?").matchEntire(spelling?.trim() ?: return null) ?: return null
        val step = m.groupValues[1][0]
        val alter = when (m.groupValues[2]) { "##", "x" -> 2; "#" -> 1; "b" -> -1; "bb" -> -2; else -> 0 }
        if (Math.floorMod(stepPitchClass(step) + alter, 12) != Math.floorMod(midi, 12)) return null
        return make(step, alter, midi)
    }

    fun make(step: Char, alter: Int, midi: Int): Spelled = Spelled(step, alter, Math.floorDiv(midi - alter - stepPitchClass(step), 12) - 1)

    fun midi(step: Char, alter: Int, octave: Int): Int = (octave + 1) * 12 + stepPitchClass(step) + alter

    /** Short note name for labels, e.g. "E", "F#", "Bb". */
    fun name(midi: Int): String = spell(midi, 0).let { it.step.toString() + when (it.alter) { 1 -> "#"; -1 -> "b"; else -> "" } }
}

/** Display positions and names for GM percussion on a five-line percussion staff. */
internal object DrumNotation {
    data class Display(val step: Char, val octave: Int, val notehead: String?)
    private data class Entry(val display: Display, val name: String)

    private val table: Map<Int, Entry> = mapOf(
        35 to Entry(Display('E', 4, null), "Bass Drum 2"),
        36 to Entry(Display('F', 4, null), "Kick"),
        37 to Entry(Display('C', 5, "x"), "Side Stick"),
        38 to Entry(Display('C', 5, null), "Snare"),
        39 to Entry(Display('B', 4, "x"), "Hand Clap"),
        40 to Entry(Display('C', 5, "slash"), "Electric Snare"),
        41 to Entry(Display('G', 4, null), "Low Floor Tom"),
        42 to Entry(Display('G', 5, "x"), "Closed HH"),
        43 to Entry(Display('A', 4, null), "High Floor Tom"),
        44 to Entry(Display('D', 4, "x"), "Pedal HH"),
        45 to Entry(Display('B', 4, null), "Low Tom"),
        46 to Entry(Display('G', 5, "circle-x"), "Open HH"),
        47 to Entry(Display('D', 5, null), "Low-Mid Tom"),
        48 to Entry(Display('E', 5, null), "Hi-Mid Tom"),
        49 to Entry(Display('A', 5, "x"), "Crash"),
        50 to Entry(Display('F', 5, null), "High Tom"),
        51 to Entry(Display('F', 5, "x"), "Ride"),
        52 to Entry(Display('B', 5, "x"), "China"),
        53 to Entry(Display('F', 5, "diamond"), "Ride Bell"),
        54 to Entry(Display('E', 5, "x"), "Tambourine"),
        55 to Entry(Display('B', 5, "circle-x"), "Splash"),
        56 to Entry(Display('E', 5, "triangle"), "Cowbell"),
        57 to Entry(Display('A', 5, "circle-x"), "Crash 2"),
        59 to Entry(Display('F', 5, "cross"), "Ride 2"),
    )

    fun display(midi: Int): Display = table[midi]?.display ?: Display('C', 5, "triangle")
    fun name(midi: Int): String = table[midi]?.name ?: "Percussion $midi"
    fun isCymbal(midi: Int) = midi in setOf(42, 44, 46, 49, 51, 52, 53, 55, 57, 59)

    /** Reverse lookup used when a MusicXML file has unpitched notes without instrument references. */
    fun midiFor(step: Char, octave: Int, notehead: String?): Int? {
        val head = notehead?.takeIf { it != "normal" }
        return table.entries.firstOrNull { it.value.display == Display(step, octave, head) }?.key
            ?: table.entries.firstOrNull { it.value.display.step == step && it.value.display.octave == octave }?.key
    }

    /** Effective GM note after the hi-hat/side-stick techniques, as the sequencer plays it. */
    fun effectiveNote(note: Note): Int = when (note.articulations.filterIsInstance<Articulation.Percussion>().firstOrNull()?.kind) {
        PercussionTechnique.OPEN_HI_HAT -> 46
        PercussionTechnique.CLOSED_HI_HAT -> 42
        PercussionTechnique.PEDAL_HI_HAT -> 44
        PercussionTechnique.CROSS_STICK -> 37
        else -> note.pitch.midiNumber
    }
}
