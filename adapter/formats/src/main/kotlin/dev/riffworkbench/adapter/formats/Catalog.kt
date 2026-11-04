package dev.riffworkbench.adapter.formats

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * C-FORMAT catalog. Detection looks at content (ZIP entry names, binary headers, XML roots), never at the file
 * extension alone; the name only decides between families that share a container and are otherwise equal.
 */
class DefaultFormatCatalog(private val importers: Map<String, ScoreImporter> = emptyMap(), private val exporters: Map<String, ScoreExporter> = emptyMap()) : FormatCatalog {
    override val formats: List<FormatInfo> = listOf(NATIVE) + ForeignFormats.formats
    override fun importer(formatId: String): ScoreImporter? = importers[formatId]
    override fun exporter(formatId: String): ScoreExporter? = exporters[formatId]

    override fun detect(bytes: ByteArray, fileName: String?): Outcome<FormatInfo> {
        fun f(id: String) = Outcome.Success(formats.first { it.id == id })
        if (bytes.size < 4) return Outcome.Failure(FailureCode.CORRUPT, "Datei ist leer oder zu kurz", contractId = "C-FORMAT")
        val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.ISO_8859_1)
        return when {
            head.startsWith("PK") -> zipFamily(bytes)
            head.startsWith("BCFZ") || head.startsWith("BCFS") -> f("gpx")
            head.length > 1 && head.substring(1).startsWith("FICHIER GUITAR PRO v3") -> f("gp3")
            head.length > 1 && head.substring(1).startsWith("FICHIER GUITAR PRO v4") -> f("gp4")
            head.length > 1 && head.substring(1).startsWith("FICHIER GUITAR PRO v5") -> f("gp5")
            head.length > 1 && head.substring(1).startsWith("FICHIER GUITAR PRO") -> Outcome.Failure(FailureCode.INCOMPATIBLE_VERSION, "Unbekannte Guitar-Pro-Version", contractId = "C-FORMAT")
            head.startsWith("MThd") -> f("midi")
            head.startsWith("%PDF-") -> Outcome.Failure(FailureCode.UNAVAILABLE, "PDF-Dateien enthalten keine bearbeitbaren Noten", contractId = "C-FORMAT")
            looksLikeXml(bytes) -> xmlFamily(bytes)
            head.startsWith("ptab") -> f("powertab")
            fileName?.endsWith(".tef", true) == true -> f("tabledit")
            else -> Outcome.Failure(FailureCode.UNAVAILABLE, "Dateiformat nicht erkannt", contractId = "C-FORMAT")
        }
    }

    private fun zipFamily(bytes: ByteArray): Outcome<FormatInfo> {
        val names = HashSet<String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var count = 0
                while (count++ < 256) { val e = zip.nextEntry ?: break; names += e.name; if (e.name == "Content/score.gpif" || e.name == "manifest.json" || e.name == "META-INF/container.xml") break }
            }
        } catch (e: Exception) { return Outcome.Failure(FailureCode.CORRUPT, "Beschädigtes ZIP-Archiv", contractId = "C-FORMAT") }
        return when {
            "manifest.json" in names || "project.json" in names -> Outcome.Success(NATIVE)
            "Content/score.gpif" in names -> Outcome.Success(formats.first { it.id == "gp7" })
            "META-INF/container.xml" in names -> Outcome.Success(formats.first { it.id == "musicxml" })
            else -> Outcome.Failure(FailureCode.UNAVAILABLE, "ZIP-Archiv ohne bekanntes Notenformat", contractId = "C-FORMAT")
        }
    }
    private fun looksLikeXml(bytes: ByteArray): Boolean {
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < bytes.size && i < 64 && bytes[i].toInt().toChar().isWhitespace()) i++
        return i < bytes.size && bytes[i] == '<'.code.toByte()
    }
    private fun xmlFamily(bytes: ByteArray): Outcome<FormatInfo> {
        val text = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
        return when {
            text.contains("<score-partwise") || text.contains("<score-timewise") -> Outcome.Success(formats.first { it.id == "musicxml" })
            text.contains("<GPIF") -> Outcome.Success(formats.first { it.id == "gp7" })
            else -> Outcome.Failure(FailureCode.UNAVAILABLE, "XML-Datei ohne bekanntes Notenformat", contractId = "C-FORMAT")
        }
    }

    companion object { val NATIVE = FormatInfo("riffproj", "RiffWorkbench-Projekt", "riffproj", "native") }
}
