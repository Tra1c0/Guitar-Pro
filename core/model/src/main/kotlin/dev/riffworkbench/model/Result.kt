package dev.riffworkbench.model

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val code: FailureCode, val message: String, val diagnostics: List<Diagnostic> = emptyList(), val contractId: String? = null) : Outcome<Nothing>
}
enum class FailureCode { VALIDATION, IO, CORRUPT, INCOMPATIBLE_VERSION, PERMISSION, UNAVAILABLE, CONFLICT, CANCELLED, RESOURCE_LIMIT }
data class OperationProgress(val completed: Long, val total: Long?, val stage: String)
data class FormatInfo(val id: String, val displayName: String, val extension: String, val family: String)
data class ImportResult(val project: Project, val losses: List<LossEntry>)
data class ExportResult(val bytes: ByteArray, val mediaType: String, val fileName: String, val losses: List<LossEntry>)
data class ExportRequest(val project: Project, val formatId: String, val render: RenderOptions = RenderOptions())
data class RenderedScore(val projectRevision: Long, val elements: List<ScoreElementBounds>)
data class ScoreElementBounds(val entityId: EntityId, val x: Float, val y: Float, val width: Float, val height: Float)
data class ExternalDevice(val id: String, val name: String, val integrationId: String)

/** Streaming byte destination for large exports (rendered audio). Implementations may block. */
fun interface ByteSink { fun write(bytes: ByteArray, offset: Int, length: Int) }
data class RenderSummary(val frames: Long, val sampleRate: Int, val channels: Int, val peak: Double, val clippedSamples: Long, val bytes: Long)
