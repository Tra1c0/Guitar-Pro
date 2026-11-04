package dev.riffworkbench.adapter.formats

import dev.riffworkbench.adapter.formats.gp.GpBinaryImporter
import dev.riffworkbench.ports.*

/** The importers and exporters this build actually implements; everything else stays UNAVAILABLE. */
object FormatRegistry {
    fun importers(): Map<String, ScoreImporter> = listOf("gp3", "gp4", "gp5").associateWith { GpBinaryImporter(it) }
    fun exporters(): Map<String, ScoreExporter> = emptyMap()
    fun catalog(): FormatCatalog = DefaultFormatCatalog(importers(), exporters())
}
