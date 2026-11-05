package dev.riffworkbench.editor

import dev.riffworkbench.model.*

object ScoreValidator {
    fun validate(project: Project, strict: Boolean = false): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        if (project.measures.isEmpty() || project.tracks.isEmpty()) diagnostics += Diagnostic("empty_project", "Projekt benötigt Takte und Spuren", severity = Severity.ERROR)
        val seen = mutableSetOf<EntityId>()
        fun id(value: EntityId) { if (!seen.add(value)) diagnostics += Diagnostic("duplicate_id", "Doppelte ID: $value", value, Severity.ERROR) }
        id(project.id)
        val measureMap = project.measures.associateBy { it.id }
        project.measures.forEach { id(it.id) }
        val noteMap = project.tracks.flatMap { t -> t.staves.flatMap { s -> s.bars.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { it.notes } } } } }.associateBy { it.id }
        val noteIds = noteMap.keys
        project.tracks.forEach { track ->
            id(track.id)
            if (track.staves.isEmpty()) diagnostics += Diagnostic("missing_staff", "Spur benötigt ein Notensystem", track.id, Severity.ERROR)
            track.staves.forEach { staff ->
                id(staff.id)
                if (staff.bars.map { it.measureId } != project.measures.map { it.id }) diagnostics += Diagnostic("bar_alignment", "Spurtakte stimmen nicht mit dem Arrangement überein", staff.id, Severity.ERROR)
                staff.bars.forEach { bar ->
                    id(bar.id)
                    val measure = measureMap[bar.measureId]
                    if (measure == null) diagnostics += Diagnostic("missing_measure", "Taktreferenz fehlt", bar.id, Severity.ERROR)
                    if (bar.voices.map { it.index }.toSet() != setOf(0, 1, 2, 3) || bar.voices.size != 4) diagnostics += Diagnostic("voices", "Vier eindeutig zugeordnete Stimmen erforderlich", bar.id, Severity.ERROR)
                    bar.voices.forEach { voice ->
                        id(voice.id)
                        var end = Fraction.ZERO
                        voice.beats.sortedBy { it.offset }.forEach { beat ->
                            id(beat.id)
                            if (beat.offset < Fraction.ZERO || beat.offset < end) diagnostics += Diagnostic("overlap", "Beats derselben Stimme überlappen", beat.id, Severity.ERROR)
                            end = beat.offset + beat.duration.length()
                            if (measure != null && end > (measure.pickupLength ?: measure.signature.length())) diagnostics += Diagnostic("overfull", "Beat überschreitet den Takt", beat.id, Severity.ERROR)
                            if (beat.explicitRest && beat.notes.isNotEmpty()) diagnostics += Diagnostic("rest_with_notes", "Pause enthält Noten", beat.id, Severity.ERROR)
                            if (beat.notes.mapNotNull { it.string }.distinct().size != beat.notes.count { it.string != null }) diagnostics += Diagnostic("string_conflict", "Zwei Noten auf derselben Saite", beat.id, Severity.ERROR)
                            beat.notes.forEach { note ->
                                id(note.id)
                                if (note.velocity !in 1..127 || note.pitch.midiNumber !in 0..127) diagnostics += Diagnostic("note_range", "Ungültige Tonhöhe oder Velocity", note.id, Severity.ERROR)
                                if ((note.string == null) != (note.fret == null)) diagnostics += Diagnostic("fret_string", "Saite und Bund müssen gemeinsam gesetzt werden", note.id, Severity.ERROR)
                                val string = note.string; val fret = note.fret
                                if (string != null && fret != null) {
                                    if (string !in track.instrument.tuning.indices || fret !in 0..track.instrument.maxFret) diagnostics += Diagnostic("unplayable", "Position ist nicht spielbar", note.id, Severity.ERROR)
                                    else if (track.instrument.pitch(string, fret) != note.pitch.midiNumber) diagnostics += Diagnostic("pitch_mismatch", "Bund und Tonhöhe widersprechen sich", note.id, Severity.ERROR)
                                }
                                note.links.filter { it.target !in noteIds }.forEach { diagnostics += Diagnostic("dangling_link", "Notenverknüpfung fehlt", note.id, Severity.ERROR) }
                                note.links.filter { it.kind == LinkKind.TIE && it.target in noteMap }.forEach { link ->
                                    val target = noteMap.getValue(link.target)
                                    if (target.pitch.midiNumber != note.pitch.midiNumber || target.pitch.cents != note.pitch.cents) diagnostics += Diagnostic("tie_pitch", "Gebundene Noten müssen dieselbe Tonhöhe behalten", note.id, Severity.ERROR)
                                }
                            }
                        }
                        if (voice.beats.isNotEmpty() && measure != null && end < (measure.pickupLength ?: measure.signature.length())) diagnostics += Diagnostic("incomplete_bar", "Takt ist noch unvollständig", bar.id, if (strict) Severity.ERROR else Severity.WARNING)
                    }
                }
            }
        }
        project.sections.forEach { id(it.id); if (it.measureId !in measureMap) diagnostics += Diagnostic("section_position", "Abschnitt ohne Takt", it.id, Severity.ERROR) }
        project.tempo.forEach { if (it.position.measureId !in measureMap || !it.quarterNotesPerMinute.isFinite() || it.quarterNotesPerMinute !in 10.0..1000.0) diagnostics += Diagnostic("tempo", "Ungültige Tempoänderung", severity = Severity.ERROR) }
        // Jump marks that playback cannot follow: warned here so the score view explains why playback/MIDI export will refuse.
        val marks = project.measures.flatMap { m -> m.navigation.map { m to it } }
        val kinds = marks.map { it.second.kind }.toSet()
        if (NavigationKind.DAL_SEGNO in kinds && NavigationKind.SEGNO !in kinds) diagnostics += Diagnostic("navigation_segno", "Dal Segno ohne Segno kann nicht abgespielt werden", marks.first { it.second.kind == NavigationKind.DAL_SEGNO }.first.id, Severity.WARNING)
        marks.forEach { (m, mark) -> if (mark.target != null && mark.target !in measureMap) diagnostics += Diagnostic("navigation_target", "Sprungziel existiert nicht", m.id, Severity.WARNING) }
        return diagnostics
    }
}
