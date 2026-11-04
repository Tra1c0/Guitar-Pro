package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.ScoreExporter

/**
 * C-FORMAT: GP7-compatible .gp export. The archive holds the entries Guitar Pro 7 writes as far as known:
 *  - VERSION ("7.0")
 *  - Content/ (directory entry)
 *  - Content/PartConfiguration (track notation flags, see [PartConfiguration])
 *  - Content/score.gpif (GPIF version 7)
 * Not written: Content/BinaryStylesheet and Content/LayoutConfiguration (formats not known well enough;
 * Guitar Pro is expected to fall back to its defaults). Opening in GP7/GP8 Desktop is NOT verified.
 * Output is deterministic: fixed ZIP timestamps, sequential GPIF ids, no time- or locale-dependent data.
 */
class Gp7Exporter : ScoreExporter {
    override val formatId: String = "gp7"

    override suspend fun export(request: ExportRequest, progress: (OperationProgress) -> Unit): Outcome<ExportResult> = gpifOutcome {
        if (request.formatId != formatId) throw GpifException(FailureCode.VALIDATION, "Gp7Exporter schreibt nur gp7, nicht ${request.formatId}")
        val project = request.project
        val errors = ScoreValidator.validate(project).filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) throw GpifException(FailureCode.VALIDATION, "Projekt ist ungültig und wird nicht exportiert: ${errors.first().message}")
        progress(OperationProgress(0, 3, "GPIF schreiben"))
        val losses = LossCollector("export")
        val encoder = GpifEncoder(project, losses)
        val xml = encoder.encode().toByteArray(Charsets.UTF_8)
        progress(OperationProgress(2, 3, "Archiv schreiben"))
        val zip = GpZipWriter()
        zip.add("VERSION", "7.0".toByteArray(Charsets.US_ASCII), store = true)
        zip.directory("Content/")
        zip.add(GP_PART_ENTRY, PartConfiguration.write(encoder.partFlags))
        zip.add(GP_SCORE_ENTRY, xml)
        val bytes = zip.finish()
        if (bytes.size > GpZipLimits.MAX_INPUT) resourceLimit("Exportdatei wäre größer als 64 MiB")
        progress(OperationProgress(3, 3, "Fertig"))
        val name = project.metadata.title.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(80).ifBlank { "score" }
        ExportResult(bytes, "application/octet-stream", "$name.gp", losses.entries())
    }
}
