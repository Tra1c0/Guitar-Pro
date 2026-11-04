package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.ForeignFormats
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.FormatDetector
import dev.riffworkbench.ports.ScoreImporter

internal const val GP_SCORE_ENTRY = "Content/score.gpif"
internal const val GP_PART_ENTRY = "Content/PartConfiguration"

/**
 * C-FORMAT: Guitar Pro 7/8 (.gp = ZIP with Content/score.gpif) import. [formatId] "gp7" or "gp8"; both use the
 * same GPIF conversion. GP8-only content (embedded audio, sync points, …) is reported as loss, not converted.
 * The caller should run this on a worker dispatcher and keep the original bytes as a provenance asset.
 */
class GpifImporter(override val formatId: String = "gp7") : ScoreImporter {
    init { require(formatId == "gp7" || formatId == "gp8") { "GpifImporter unterstützt gp7 und gp8" } }

    override suspend fun import(bytes: ByteArray, ids: IdSource, progress: (OperationProgress) -> Unit): Outcome<ImportResult> = gpifOutcome {
        progress(OperationProgress(0, 3, "Archiv prüfen"))
        val zip = GpZipReader.read(bytes, keep = { it == GP_SCORE_ENTRY || it == GP_PART_ENTRY })
        val gpif = zip.files[GP_SCORE_ENTRY] ?: corrupt("Archiv enthält keine Content/score.gpif")
        val losses = LossCollector("import")
        reportEntries(zip.names, losses)
        progress(OperationProgress(1, 3, "score.gpif umwandeln"))
        val project = GpifDecoder.decode(gpif, zip.files[GP_PART_ENTRY], ids, losses)
        progress(OperationProgress(3, 3, "Fertig"))
        ImportResult(project, losses.entries())
    }

    private fun reportEntries(names: List<String>, losses: LossCollector) {
        for (name in names) when {
            name.endsWith("/") || name == GP_SCORE_ENTRY || name == GP_PART_ENTRY || name == "VERSION" || name == "meta.json" -> Unit
            name.startsWith("Content/Assets/") -> losses.add("audio.reference", "Eingebettete Audiodateien (GP8) werden nicht übernommen")
            name == "Content/BinaryStylesheet" || name == "Content/LayoutConfiguration" || name.startsWith("Content/Stylesheets/") || name == "Content/Preferences.json" ->
                losses.add("layout.stylesheet", "Stylesheet und Layoutkonfiguration werden nicht übernommen")
            else -> losses.add("import.unknown_fields", "Unbekannter Archiveintrag wird nicht übernommen")
        }
    }
}

/**
 * C-FORMAT: Guitar Pro 6 (.gpx, BCFZ/BCFS) import, EXPERIMENTAL. The container code is verified only against
 * our own synthetic encoder; GP6 specifics (track-level tuning, GeneralMidi) are read as best knowledge.
 * GP6 drum notes that only carry Element/Variation are reported and dropped.
 */
class GpxImporter : ScoreImporter {
    override val formatId: String = "gpx"

    override suspend fun import(bytes: ByteArray, ids: IdSource, progress: (OperationProgress) -> Unit): Outcome<ImportResult> = gpifOutcome {
        progress(OperationProgress(0, 3, "GPX-Container entpacken"))
        val extracted = GpxContainer.extract(bytes)
        val losses = LossCollector("import")
        losses.add("import.gpx", "GPX-Import ist experimentell und nicht gegen Guitar-Pro-6-Originaldateien geprüft")
        if (extracted.otherFiles.isNotEmpty()) losses.add("layout.stylesheet", "Weitere GPX-Dateien (Stylesheet, misc.xml, …) werden nicht übernommen")
        progress(OperationProgress(1, 3, "score.gpif umwandeln"))
        val project = GpifDecoder.decode(extracted.gpif, extracted.partConfiguration, ids, losses)
        progress(OperationProgress(3, 3, "Fertig"))
        ImportResult(project, losses.entries())
    }
}

enum class GpifFamily { GP7, GP8, GPX, NONE }

/** [verified] is true when the container was opened and score.gpif was found, not just a magic number. */
data class GpifDetection(val family: GpifFamily, val verified: Boolean, val reason: String) {
    val formatId: String? get() = when (family) { GpifFamily.GP7 -> "gp7"; GpifFamily.GP8 -> "gp8"; GpifFamily.GPX -> "gpx"; GpifFamily.NONE -> null }
}

/**
 * Content-based detection of GPIF containers; the file extension is never consulted.
 * GP8 is reported when score.gpif names <GPVersion> 8.x or contains GP8-only sections (BackingTrack,
 * AudioTracks, Assets) or the archive holds Content/Assets/ entries. Without such markers a .gp file is
 * reported as GP7, which then also covers GP8 files that use no GP8-only features.
 */
object GpifDetector {
    fun classify(bytes: ByteArray): GpifDetection {
        if (Bcfz.isBcfz(bytes) || Bcfz.isBcfs(bytes)) {
            val ok = runCatching { GpxContainer.extract(bytes) }.isSuccess
            return GpifDetection(GpifFamily.GPX, ok, if (ok) "BCFZ/BCFS-Container mit score.gpif" else "BCFZ/BCFS-Kennung (Inhalt nicht prüfbar)")
        }
        if (!GpZipReader.isZip(bytes)) return GpifDetection(GpifFamily.NONE, false, "Weder ZIP noch BCFZ/BCFS")
        val zip = try { GpZipReader.read(bytes, keep = { it == GP_SCORE_ENTRY }) } catch (e: GpifException) {
            return GpifDetection(GpifFamily.NONE, false, "ZIP nicht lesbar: ${e.message}")
        } catch (e: java.io.IOException) {
            return GpifDetection(GpifFamily.NONE, false, "ZIP nicht lesbar: ${e.message}")
        } catch (e: RuntimeException) {
            return GpifDetection(GpifFamily.NONE, false, "ZIP nicht lesbar: ${e.message}")
        }
        val gpif = zip.files[GP_SCORE_ENTRY] ?: return GpifDetection(GpifFamily.NONE, false, "ZIP ohne Content/score.gpif")
        val text = String(gpif, Charsets.UTF_8)
        if (!text.contains("<GPIF")) return GpifDetection(GpifFamily.NONE, false, "Content/score.gpif ist kein GPIF")
        val version = Regex("<GPVersion>\\s*([0-9]+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val gp8 = (version != null && version >= 8) || listOf("<BackingTrack", "<AudioTracks", "<Assets").any { it in text } || zip.names.any { it.startsWith("Content/Assets/") }
        return if (gp8) GpifDetection(GpifFamily.GP8, true, "GP8-Merkmale gefunden") else GpifDetection(GpifFamily.GP7, true, "ZIP mit Content/score.gpif")
    }

    fun detect(bytes: ByteArray): Outcome<FormatInfo> {
        val detection = try { classify(bytes) } catch (e: Exception) { GpifDetection(GpifFamily.NONE, false, e.message ?: "Fehler") }
        val id = detection.formatId ?: return Outcome.Failure(FailureCode.VALIDATION, "Keine Guitar-Pro-7/8- oder GPX-Datei: ${detection.reason}", contractId = GPIF_CONTRACT)
        return Outcome.Success(ForeignFormats.formats.first { it.id == id })
    }
}

/** [FormatDetector] port over [GpifDetector]; expects the complete file, not only a prefix. */
class GpifFormatDetector : FormatDetector {
    override fun detect(prefix: ByteArray): Outcome<FormatInfo> = GpifDetector.detect(prefix)
}
