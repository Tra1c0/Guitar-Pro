package dev.riffworkbench.adapter.formats

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*

object ForeignFormats {
    val formats = listOf(
        FormatInfo("gp3", "Guitar Pro 3", "gp3", "gp-binary"), FormatInfo("gp4", "Guitar Pro 4", "gp4", "gp-binary"),
        FormatInfo("gp5", "Guitar Pro 5", "gp5", "gp-binary"), FormatInfo("gpx", "Guitar Pro 6", "gpx", "gpx-bcfs"),
        FormatInfo("gp7", "Guitar Pro 7", "gp", "gpif-zip"), FormatInfo("gp8", "Guitar Pro 8", "gp", "gpif-zip"),
        FormatInfo("musicxml", "MusicXML", "musicxml", "xml"), FormatInfo("midi", "MIDI / Tempo-Map", "mid", "midi"),
        FormatInfo("ascii", "ASCII TAB", "txt", "text"), FormatInfo("powertab", "PowerTab", "ptb", "legacy"),
        FormatInfo("tabledit", "TablEdit", "tef", "legacy"), FormatInfo("pdf", "PDF", "pdf", "visual"),
        FormatInfo("png", "PNG", "png", "visual"), FormatInfo("svg", "SVG", "svg", "visual"),
        FormatInfo("wav", "WAV / synchronisierte Stems", "wav", "rendered-audio"),
    )
}

class PendingFormatAdapter(override val formatId: String) : ScoreImporter, ScoreExporter {
    override suspend fun import(bytes: ByteArray, ids: IdSource, progress: (OperationProgress) -> Unit): Outcome<ImportResult> = unavailable("C-FORMAT", "$formatId-Import ist vorbereitet, aber noch nicht implementiert.")
    override suspend fun export(request: ExportRequest, progress: (OperationProgress) -> Unit): Outcome<ExportResult> = unavailable("C-FORMAT", "$formatId-Export ist vorbereitet, aber noch nicht implementiert.")
}

class SignatureFormatDetector : FormatDetector {
    override fun detect(prefix: ByteArray): Outcome<FormatInfo> {
        val text = prefix.take(64).toByteArray().toString(Charsets.ISO_8859_1)
        return when {
            text.startsWith("MThd") -> Outcome.Success(ForeignFormats.formats.first { it.id == "midi" })
            text.startsWith("%PDF-") -> Outcome.Success(ForeignFormats.formats.first { it.id == "pdf" })
            text.startsWith("PK") -> unavailable("C-FORMAT", "ZIP erkannt; native/GP7/GP8-Erkennung benötigt die validierte Containerprüfung.")
            else -> unavailable("C-FORMAT", "Inhaltserkennung dieser Formatfamilie folgt mit dem Importadapter.")
        }
    }
}
