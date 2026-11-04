package dev.riffworkbench.adapter.formats

import dev.riffworkbench.model.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write(byteArrayOf(1, 2, 3)); z.closeEntry() } }
        return out.toByteArray()
    }
    private fun id(bytes: ByteArray, name: String? = null) = (DefaultFormatCatalog().detect(bytes, name) as? Outcome.Success)?.value?.id

    @Test fun detectsByContentNotExtension() {
        assertEquals("riffproj", id(zip("manifest.json", "project.json"), "song.gp"))
        assertEquals("gp7", id(zip("VERSION", "Content/score.gpif"), "song.zip"))
        assertEquals("musicxml", id(zip("META-INF/container.xml", "score.xml")))
        assertEquals("gp5", id(byteArrayOf(24) + "FICHIER GUITAR PRO v5.00".toByteArray() + ByteArray(40)))
        assertEquals("gp3", id(byteArrayOf(24) + "FICHIER GUITAR PRO v3.00".toByteArray() + ByteArray(40)))
        assertEquals("gpx", id("BCFZ".toByteArray() + ByteArray(10)))
        assertEquals("midi", id("MThd".toByteArray() + ByteArray(10)))
        assertEquals("musicxml", id("﻿<?xml version=\"1.0\"?><!DOCTYPE score-partwise><score-partwise version=\"4.0\"/>".toByteArray()))
        assertNull(id(zip("random.txt")))
        assertNull(id(ByteArray(2)))
    }

    @Test fun registryImportsEveryGuitarProBinaryFixtureThroughTheCatalog() = kotlinx.coroutines.runBlocking {
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "fixtures/gp") }.first { it.isDirectory }
        val catalog = FormatRegistry.catalog()
        var n = 0
        val ids = IdSource { EntityId("c${n++}") }
        for (name in listOf("drop-a-band.gp3", "drop-a-band.gp4", "drop-a-band.gp5", "drop-a-band-v500.gp5")) {
            val bytes = java.io.File(dir, name).readBytes()
            val format = (catalog.detect(bytes, null) as Outcome.Success).value
            assertEquals(name.substringAfterLast('.'), format.id)
            val result = catalog.importer(format.id)!!.import(bytes, ids) {}
            assertTrue("$name: $result", result is Outcome.Success)
            assertTrue((result as Outcome.Success).value.project.tracks.isNotEmpty())
        }
        // "L4.06" (a GP4 variant) is still GP4, not an unknown version.
        assertEquals("gp4", id(byteArrayOf(24) + "FICHIER GUITAR PRO L4.06".toByteArray() + ByteArray(40)))
    }

    @Test fun registryRoundTripsGp7ThroughTheCatalog() = kotlinx.coroutines.runBlocking {
        var n = 0
        val ids = IdSource { EntityId("r${n++}") }
        val catalog = FormatRegistry.catalog()
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "fixtures/gp") }.first { it.isDirectory }
        val source = (catalog.importer("gp5")!!.import(java.io.File(dir, "drop-a-band.gp5").readBytes(), ids) {} as Outcome.Success).value.project
        val exported = (catalog.exporter("gp7")!!.export(ExportRequest(source, "gp7")) {} as Outcome.Success).value
        assertEquals("gp7", (catalog.detect(exported.bytes, null) as Outcome.Success).value.id)
        val again = (catalog.importer("gp7")!!.import(exported.bytes, ids) {} as Outcome.Success).value.project
        assertEquals(source.tracks.map { it.instrument.tuning }, again.tracks.map { it.instrument.tuning })
        assertEquals(source.measures.size, again.measures.size)
        fun notes(p: Project) = p.tracks.flatMap { t -> t.staves.flatMap { it.bars }.flatMap { it.voices }.flatMap { it.beats }.flatMap { it.notes } }.size
        assertEquals(notes(source), notes(again))
    }

    @Test fun everyRegisteredExporterProducesAFileTheCatalogRecognisesAgain() = kotlinx.coroutines.runBlocking {
        var n = 0
        val ids = IdSource { EntityId("x${n++}") }
        val catalog = FormatRegistry.catalog()
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "fixtures/gp") }.first { it.isDirectory }
        val source = (catalog.importer("gp5")!!.import(java.io.File(dir, "drop-a-band.gp5").readBytes(), ids) {} as Outcome.Success).value.project
        for (id in listOf("gp7", "midi", "musicxml", "ascii")) {
            val file = catalog.exporter(id)!!.export(ExportRequest(source, id)) {}
            assertTrue("$id: $file", file is Outcome.Success)
            val bytes = (file as Outcome.Success).value.bytes
            assertTrue("$id is empty", bytes.isNotEmpty())
            if (id == "ascii") continue // plain-text tab has no import path
            assertEquals(id, (catalog.detect(bytes, null) as Outcome.Success).value.id)
            val again = catalog.importer(id)!!.import(bytes, ids) {}
            assertTrue("$id re-import: $again", again is Outcome.Success)
            assertEquals(id, source.tracks.size, (again as Outcome.Success).value.project.tracks.size)
        }
    }
}
