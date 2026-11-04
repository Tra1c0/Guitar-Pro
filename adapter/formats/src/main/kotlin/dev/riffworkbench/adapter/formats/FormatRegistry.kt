package dev.riffworkbench.adapter.formats

import dev.riffworkbench.adapter.formats.gp.GpBinaryImporter
import dev.riffworkbench.adapter.formats.gpif.Gp7Exporter
import dev.riffworkbench.adapter.formats.gpif.GpifImporter
import dev.riffworkbench.adapter.formats.gpif.GpxImporter
import dev.riffworkbench.adapter.formats.ascii.AsciiTabExporter
import dev.riffworkbench.adapter.formats.midi.MidiExporter
import dev.riffworkbench.adapter.formats.midi.MidiImporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlExporter
import dev.riffworkbench.adapter.formats.musicxml.MusicXmlImporter
import dev.riffworkbench.ports.*

/** The importers and exporters this build actually implements; everything else stays UNAVAILABLE. */
object FormatRegistry {
    fun importers(): Map<String, ScoreImporter> = buildMap {
        listOf("gp3", "gp4", "gp5").forEach { put(it, GpBinaryImporter(it)) }
        put("gpx", GpxImporter())
        // GP8 files share the GPIF container; the same conversion reads both and reports GP8-only data as losses.
        put("gp7", GpifImporter("gp7")); put("gp8", GpifImporter("gp8"))
        put("midi", MidiImporter()); put("musicxml", MusicXmlImporter())
    }
    fun exporters(): Map<String, ScoreExporter> = mapOf(
        "gp7" to Gp7Exporter(), "midi" to MidiExporter(), "musicxml" to MusicXmlExporter(), "ascii" to AsciiTabExporter(),
    )
    fun catalog(): FormatCatalog = DefaultFormatCatalog(importers(), exporters())
}
