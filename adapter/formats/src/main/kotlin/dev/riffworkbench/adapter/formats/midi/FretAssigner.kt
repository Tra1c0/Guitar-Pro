package dev.riffworkbench.adapter.formats.midi

import dev.riffworkbench.model.*

/**
 * Playable string/fret heuristic for imported pitches (MIDI and MusicXML without TAB data).
 *
 * All notes that start together are placed jointly on distinct strings. The search minimises, in this order:
 * notes that cannot be placed, use of strings that are still sounding from earlier notes, and then the sum of
 * frets plus a penalty for stretches wider than four frets, which prefers the lowest fret position. The search
 * is a bounded depth-first search; the first descent is the greedy solution, so a result always exists.
 */
internal object FretAssigner {
    private const val DROP_PENALTY = 1_000_000L
    private const val BUSY_PENALTY = 1_000L
    private const val STRETCH_PENALTY = 10L
    private const val NODE_BUDGET = 40_000

    /** Returns a (string, fret) per pitch, or null for pitches that cannot be placed. [excluded] strings are never used. */
    fun assign(pitches: List<Int>, instrument: InstrumentDefinition, busy: Set<Int> = emptySet(), excluded: Set<Int> = emptySet()): List<Pair<Int, Int>?> {
        val n = pitches.size
        if (n == 0 || instrument.tuning.isEmpty()) return List(n) { null }
        val options = pitches.map { p ->
            instrument.tuning.indices.filter { it !in excluded }.mapNotNull { s ->
                val fret = p - instrument.pitch(s, 0)
                if (fret in 0..instrument.maxFret) s to fret else null
            }.sortedWith(compareBy({ if (it.first in busy) 1 else 0 }, { it.second }))
        }
        val order = (0 until n).sortedWith(compareBy({ options[it].size }, { -pitches[it] }))
        val best = arrayOfNulls<Pair<Int, Int>>(n)
        val current = arrayOfNulls<Pair<Int, Int>>(n)
        var bestCost = Long.MAX_VALUE
        val used = BooleanArray(instrument.tuning.size)
        var nodes = 0

        fun leafCost(base: Long): Long {
            val fretted = current.filterNotNull().map { it.second }.filter { it > 0 }
            val span = if (fretted.isEmpty()) 0 else fretted.max() - fretted.min()
            return base + STRETCH_PENALTY * maxOf(0, span - 4)
        }
        fun search(k: Int, cost: Long) {
            if (cost >= bestCost) return
            if (++nodes > NODE_BUDGET && bestCost != Long.MAX_VALUE) return
            if (k == n) {
                val total = leafCost(cost)
                if (total < bestCost) { bestCost = total; current.copyInto(best) }
                return
            }
            val i = order[k]
            for ((s, f) in options[i]) {
                if (used[s]) continue
                used[s] = true; current[i] = s to f
                search(k + 1, cost + f + if (s in busy) BUSY_PENALTY else 0)
                used[s] = false; current[i] = null
            }
            search(k + 1, cost + DROP_PENALTY)
        }
        search(0, 0)
        return best.toList()
    }
}

/** Default tunings used when a source carries no TAB information. */
internal object DefaultInstruments {
    val GUITAR_STANDARD = listOf(40, 45, 50, 55, 59, 64)
    val BASS_STANDARD = listOf(28, 33, 38, 43)

    /**
     * Standard six-string E tuning; when the material goes below E2 a seventh string is added and tuned to the
     * lowest note (not below E1), so seven-string riffs such as Drop A survive a MIDI round trip.
     */
    fun guitar(lowestPitch: Int?, program: Int): InstrumentDefinition {
        val tuning = if (lowestPitch != null && lowestPitch < GUITAR_STANDARD.first()) listOf(maxOf(lowestPitch, 28)) + GUITAR_STANDARD else GUITAR_STANDARD
        return InstrumentDefinition(InstrumentKind.GUITAR, tuning, List(tuning.size) { 0 }, midiProgram = program.coerceIn(0, 127))
    }

    /** Standard four-string E A D G bass; a fifth low string (not below B0) is added for lower material. */
    fun bass(lowestPitch: Int?, program: Int): InstrumentDefinition {
        val tuning = if (lowestPitch != null && lowestPitch < BASS_STANDARD.first()) listOf(maxOf(lowestPitch, 23)) + BASS_STANDARD else BASS_STANDARD
        return InstrumentDefinition(InstrumentKind.BASS, tuning, List(tuning.size) { 0 }, midiProgram = program.coerceIn(0, 127))
    }

    fun unfretted(kind: InstrumentKind, program: Int) = InstrumentDefinition(kind, emptyList(), emptyList(), midiProgram = program.coerceIn(0, 127))

    /** GM program families: 24–31 guitar, 32–39 bass, 0–7 piano, everything else OTHER. */
    fun kindForProgram(program: Int): InstrumentKind = when (program) {
        in 24..31 -> InstrumentKind.GUITAR
        in 32..39 -> InstrumentKind.BASS
        in 0..7 -> InstrumentKind.PIANO
        else -> InstrumentKind.OTHER
    }

    fun defaultName(kind: InstrumentKind) = when (kind) {
        InstrumentKind.GUITAR -> "Gitarre"
        InstrumentKind.BASS -> "Bass"
        InstrumentKind.PERCUSSION -> "Drums"
        InstrumentKind.PIANO -> "Piano"
        InstrumentKind.OTHER -> "Instrument"
    }

    fun notation(kind: InstrumentKind): Set<NotationKind> = when (kind) {
        InstrumentKind.GUITAR, InstrumentKind.BASS -> setOf(NotationKind.TAB, NotationKind.STANDARD)
        InstrumentKind.PERCUSSION -> setOf(NotationKind.PERCUSSION)
        else -> setOf(NotationKind.STANDARD)
    }
}
