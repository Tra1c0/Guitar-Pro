package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*

data class TuningPreset(val id: String, val name: String, val kind: InstrumentKind, val tuning: List<Int>)

object TuningPresets {
    val all = listOf(
        TuningPreset("g6-std", "6 · Standard E", InstrumentKind.GUITAR, listOf(40, 45, 50, 55, 59, 64)),
        TuningPreset("g6-eb", "6 · Eb Standard", InstrumentKind.GUITAR, listOf(39, 44, 49, 54, 58, 63)),
        TuningPreset("g6-dropd", "6 · Drop D", InstrumentKind.GUITAR, listOf(38, 45, 50, 55, 59, 64)),
        TuningPreset("g6-dstd", "6 · D Standard", InstrumentKind.GUITAR, listOf(38, 43, 48, 53, 57, 62)),
        TuningPreset("g6-dropc", "6 · Drop C", InstrumentKind.GUITAR, listOf(36, 43, 48, 53, 57, 62)),
        TuningPreset("g6-cstd", "6 · C Standard", InstrumentKind.GUITAR, listOf(36, 41, 46, 51, 55, 60)),
        TuningPreset("g6-dropb", "6 · Drop B", InstrumentKind.GUITAR, listOf(35, 42, 47, 52, 56, 61)),
        TuningPreset("g6-dadgad", "6 · DADGAD", InstrumentKind.GUITAR, listOf(38, 45, 50, 55, 57, 62)),
        TuningPreset("g6-openg", "6 · Open G", InstrumentKind.GUITAR, listOf(38, 43, 50, 55, 59, 62)),
        TuningPreset("g6-opend", "6 · Open D", InstrumentKind.GUITAR, listOf(38, 45, 50, 54, 57, 62)),
        TuningPreset("g7-std", "7 · Standard B", InstrumentKind.GUITAR, listOf(35, 40, 45, 50, 55, 59, 64)),
        TuningPreset("g7-dropa", "7 · Drop A", InstrumentKind.GUITAR, listOf(33, 40, 45, 50, 55, 59, 64)),
        TuningPreset("g7-astd", "7 · A Standard", InstrumentKind.GUITAR, listOf(33, 38, 43, 48, 53, 57, 62)),
        TuningPreset("g7-dropgs", "7 · Drop G#", InstrumentKind.GUITAR, listOf(32, 39, 44, 49, 54, 58, 63)),
        TuningPreset("g8-std", "8 · Standard F#", InstrumentKind.GUITAR, listOf(30, 35, 40, 45, 50, 55, 59, 64)),
        TuningPreset("g8-drope", "8 · Drop E", InstrumentKind.GUITAR, listOf(28, 35, 40, 45, 50, 55, 59, 64)),
        TuningPreset("b4-std", "Bass 4 · Standard", InstrumentKind.BASS, listOf(28, 33, 38, 43)),
        TuningPreset("b4-dropd", "Bass 4 · Drop D", InstrumentKind.BASS, listOf(26, 33, 38, 43)),
        TuningPreset("b4-dstd", "Bass 4 · D Standard", InstrumentKind.BASS, listOf(26, 31, 36, 41)),
        TuningPreset("b5-std", "Bass 5 · Standard B", InstrumentKind.BASS, listOf(23, 28, 33, 38, 43)),
        TuningPreset("b5-dropa", "Bass 5 · Drop A", InstrumentKind.BASS, listOf(21, 28, 33, 38, 43)),
        TuningPreset("b6-std", "Bass 6 · Standard", InstrumentKind.BASS, listOf(23, 28, 33, 38, 43, 48)),
    )
    fun matching(tuning: List<Int>) = all.firstOrNull { it.tuning == tuning }
}

object MusicNames {
    private val sharp = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private val flat = listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")
    fun pitchClass(midi: Int, preferFlats: Boolean = false) = (if (preferFlats) flat else sharp)[((midi % 12) + 12) % 12]
    fun name(midi: Int, preferFlats: Boolean = false) = pitchClass(midi, preferFlats) + (midi / 12 - 1)
    /** Parses "C", "F#", "Bb", "H" (German B), "Es" style is not supported. */
    fun parseRoot(text: String): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        val base = when (text[0].uppercaseChar()) { 'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B', 'H' -> 11; else -> return null }
        val accidental = text.getOrNull(1)
        return when (accidental) { '#', '♯' -> ((base + 1) % 12) to 2; 'b', '♭' -> ((base + 11) % 12) to 2; else -> base to 1 }
    }
}

/** C-INSTRUMENT chord library: voicings are searched for the actual tuning, capo and fret range. */
class BuiltInChordLibrary : ChordLibrary {
    private val qualities: Map<String, List<Int>> = linkedMapOf(
        "" to listOf(0, 4, 7), "m" to listOf(0, 3, 7), "5" to listOf(0, 7), "7" to listOf(0, 4, 7, 10), "maj7" to listOf(0, 4, 7, 11),
        "m7" to listOf(0, 3, 7, 10), "6" to listOf(0, 4, 7, 9), "m6" to listOf(0, 3, 7, 9), "sus2" to listOf(0, 2, 7), "sus4" to listOf(0, 5, 7),
        "7sus4" to listOf(0, 5, 7, 10), "add9" to listOf(0, 4, 7, 2), "9" to listOf(0, 4, 7, 10, 2), "dim" to listOf(0, 3, 6), "dim7" to listOf(0, 3, 6, 9),
        "m7b5" to listOf(0, 3, 6, 10), "aug" to listOf(0, 4, 8),
    )
    val qualityNames: List<String> get() = qualities.keys.toList()

    override suspend fun search(query: String, instrument: InstrumentDefinition): Outcome<List<ChordDefinition>> {
        val q = query.trim().replace("maj", "maj").replace("min", "m")
        val (root, length) = MusicNames.parseRoot(q) ?: return Outcome.Failure(FailureCode.VALIDATION, "Akkordname nicht erkannt", contractId = "C-INSTRUMENT")
        val suffix = q.substring(length)
        val intervals = qualities[suffix] ?: return Outcome.Failure(FailureCode.VALIDATION, "Akkordtyp \"$suffix\" unbekannt", contractId = "C-INSTRUMENT")
        if (instrument.tuning.size < 3) return Outcome.Failure(FailureCode.VALIDATION, "Akkordgriffe nur für Saiteninstrumente", contractId = "C-INSTRUMENT")
        return Outcome.Success(voicings(q, root, intervals, instrument))
    }

    fun voicings(name: String, root: Int, intervals: List<Int>, instrument: InstrumentDefinition, limit: Int = 6): List<ChordDefinition> {
        val classes = intervals.map { (root + it) % 12 }.toSet()
        val essential = if (intervals.size >= 4) classes - (root + 7) % 12 else classes
        val n = instrument.tuning.size
        val open = List(n) { instrument.tuning[it] + instrument.capoByString[it] + instrument.transposition }
        data class Candidate(val frets: List<Int?>, val score: Int)
        val found = HashMap<List<Int?>, Int>()
        for (base in 0..minOf(12, instrument.maxFret - 3)) {
            val options = List(n) { s -> listOf<Int?>(null) + (listOf(0) + (maxOf(1, base)..base + 3)).distinct().filter { f -> f <= instrument.maxFret && (open[s] + f) % 12 in classes } }
            val current = arrayOfNulls<Int>(n)
            fun visit(s: Int) {
                if (s == n) {
                    val sounding = (0 until n).filter { current[it] != null }
                    if (sounding.size < (if (intervals.size == 2) 2 else 3)) return
                    val pitches = sounding.map { open[it] + current[it]!! }
                    if (pitches.minOrNull()!! % 12 != root) return
                    if (!essential.all { c -> pitches.any { it % 12 == c } }) return
                    val first = sounding.first(); val last = sounding.last()
                    val gaps = (first..last).count { current[it] == null }
                    if (gaps > 1) return
                    val fretted = sounding.mapNotNull { current[it]?.takeIf { f -> f > 0 } }
                    val minFret = fretted.minOrNull()
                    val fingers = if (minFret == null) 0 else fretted.count { it > minFret } + 1
                    if (fingers > 4) return
                    val span = if (fretted.isEmpty()) 0 else fretted.max() - fretted.min()
                    val score = sounding.size * 10 - gaps * 8 - (minFret ?: 0) * 2 - span * 3 - fingers
                    val key = current.toList()
                    if ((found[key] ?: Int.MIN_VALUE) < score) found[key] = score
                    return
                }
                for (f in options[s]) { current[s] = f; visit(s + 1) }
                current[s] = null
            }
            visit(0)
        }
        return found.entries.sortedByDescending { it.value }.take(limit).mapIndexed { k, (frets, _) ->
            val fretted = frets.filterNotNull().filter { it > 0 }
            ChordDefinition(EntityId("chord-${name.filter { it.isLetterOrDigit() }.ifEmpty { "x" }.take(40)}-$k"), name,
                frets.mapIndexedNotNull { s, f -> f?.let { open[s] + it } }, frets, emptyList(), fretted.minOrNull() ?: 1)
        }
    }
}

class BuiltInScaleLibrary : ScaleLibrary {
    val all = listOf(
        ScaleDefinition("Dur (Ionisch) · Major", listOf(0, 2, 4, 5, 7, 9, 11)), ScaleDefinition("Natürliches Moll (Äolisch) · Natural minor", listOf(0, 2, 3, 5, 7, 8, 10)),
        ScaleDefinition("Harmonisch Moll · Harmonic minor", listOf(0, 2, 3, 5, 7, 8, 11)), ScaleDefinition("Melodisch Moll · Melodic minor", listOf(0, 2, 3, 5, 7, 9, 11)),
        ScaleDefinition("Moll-Pentatonik · Minor pentatonic", listOf(0, 3, 5, 7, 10)), ScaleDefinition("Dur-Pentatonik · Major pentatonic", listOf(0, 2, 4, 7, 9)),
        ScaleDefinition("Blues", listOf(0, 3, 5, 6, 7, 10)), ScaleDefinition("Dorisch · Dorian", listOf(0, 2, 3, 5, 7, 9, 10)),
        ScaleDefinition("Phrygisch · Phrygian", listOf(0, 1, 3, 5, 7, 8, 10)), ScaleDefinition("Lydisch · Lydian", listOf(0, 2, 4, 6, 7, 9, 11)),
        ScaleDefinition("Mixolydisch · Mixolydian", listOf(0, 2, 4, 5, 7, 9, 10)), ScaleDefinition("Lokrisch · Locrian", listOf(0, 1, 3, 5, 6, 8, 10)),
        ScaleDefinition("Phrygisch-dominant · Phrygian dominant", listOf(0, 1, 4, 5, 7, 8, 10)), ScaleDefinition("Ungarisch Moll · Hungarian minor", listOf(0, 2, 3, 6, 7, 8, 11)),
        ScaleDefinition("Ganzton · Whole tone", listOf(0, 2, 4, 6, 8, 10)), ScaleDefinition("Vermindert HG · Diminished half-whole", listOf(0, 1, 3, 4, 6, 7, 9, 10)),
        ScaleDefinition("Chromatisch · Chromatic", (0..11).toList()),
    )
    override suspend fun search(query: String): Outcome<List<ScaleDefinition>> =
        Outcome.Success(all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) })
}

/** C-ARRANGEMENT proposals: detached candidates from the same pure edits the editor commands use. */
class DefaultAdvancedEditor(private val ids: IdSource) : AdvancedEditorService {
    override suspend fun propose(project: Project, operation: AdvancedEdit): Outcome<EditProposal> = try {
        val (candidate, description) = when (operation) {
            is AdvancedEdit.DeleteMeasures -> StructureEdits.delete(project, operation.ids) to "${operation.ids.size} Takte löschen"
            is AdvancedEdit.DuplicateTrack -> TrackEdits.duplicate(project, operation.id, ids) to "Spur duplizieren"
            is AdvancedEdit.Retune -> TrackEdits.setInstrument(project, operation.trackId, operation.instrument, operation.policy) to "Umstimmen"
            is AdvancedEdit.Transpose -> {
                val first = operation.range.start.measureId
                val selection = Selection(operation.range.trackIds.first(), first, rangeEnd = operation.range.end.measureId)
                operation.range.trackIds.fold(project) { p, t -> NoteEdits.transpose(p, selection.copy(trackId = t), operation.semitones, EditScope.MEASURES) } to "Transponieren ${operation.semitones}"
            }
            else -> return unavailable("C-ARRANGEMENT", "Diese Bereichsoperation läuft im Editor über Kopieren/Einfügen-Befehle.")
        }
        val diagnostics = ScoreValidator.validate(candidate)
        Outcome.Success(EditProposal(project.revision, candidate, emptySet(), diagnostics, description))
    } catch (e: IllegalArgumentException) { Outcome.Failure(FailureCode.VALIDATION, e.message ?: "Ungültige Änderung", contractId = "C-ARRANGEMENT") }
}
