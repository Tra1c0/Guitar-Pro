package dev.riffworkbench.adapter.formats

import dev.riffworkbench.ports.*

/** The importers and exporters this build actually implements; everything else stays UNAVAILABLE. */
object FormatRegistry {
    fun importers(): Map<String, ScoreImporter> = emptyMap()
    fun exporters(): Map<String, ScoreExporter> = emptyMap()
    fun catalog(): FormatCatalog = DefaultFormatCatalog(importers(), exporters())
}
