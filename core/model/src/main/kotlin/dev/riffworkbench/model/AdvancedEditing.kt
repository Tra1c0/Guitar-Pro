package dev.riffworkbench.model

import kotlinx.serialization.Serializable

@Serializable data class ScoreRange(val start: ScorePosition, val end: ScorePosition, val trackIds: Set<EntityId>, val voices: Set<Int> = setOf(0))
enum class StructuralScope { TRACK_ONLY, ALL_TRACKS, RIPPLE }
data class ClipboardContent(val sourceProjectId: EntityId, val range: ScoreRange, val tracks: List<Track>, val measures: List<Measure>)
sealed interface AdvancedEdit {
    data class Copy(val range: ScoreRange) : AdvancedEdit
    data class Paste(val content: ClipboardContent, val target: ScorePosition, val scope: StructuralScope) : AdvancedEdit
    data class Move(val range: ScoreRange, val target: ScorePosition, val scope: StructuralScope, val duplicate: Boolean) : AdvancedEdit
    data class DeleteMeasures(val ids: List<EntityId>, val scope: StructuralScope) : AdvancedEdit
    data class DuplicateTrack(val id: EntityId) : AdvancedEdit
    data class Retune(val trackId: EntityId, val instrument: InstrumentDefinition, val policy: RetuningPolicy) : AdvancedEdit
    data class Transpose(val range: ScoreRange, val semitones: Int) : AdvancedEdit
    data class ApplyArticulation(val range: ScoreRange, val articulation: Articulation) : AdvancedEdit
}
data class EditProposal(val baseRevision: Long, val candidate: Project, val affectedIds: Set<EntityId>, val diagnostics: List<Diagnostic>, val description: String)
data class TuningReading(val pitch: Pitch, val deviationCents: Double, val confidence: Double, val timestampNanos: Long)
data class TranscriptionProposal(val candidate: Project, val confidenceByNote: Map<EntityId, Double>, val diagnostics: List<Diagnostic>)
data class CommandDescriptor(val id: String, val label: String, val shortcut: String?, val repeatable: Boolean)
