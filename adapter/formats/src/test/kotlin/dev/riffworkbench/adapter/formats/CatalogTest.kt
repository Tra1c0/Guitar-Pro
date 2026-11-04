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
}
