package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GpifDetectorTest {
    private val gpif = javaClass.getResourceAsStream("/gpif/handwritten-gp7.gpif")!!.readBytes().toString(Charsets.UTF_8)

    @Test fun detectsByContentNotByExtension() {
        val exported = (runBlocking { Gp7Exporter().export(ExportRequest(RichProject(GpifTestSupport.ids("d")).build(), "gp7")) } as Outcome.Success).value.bytes
        assertEquals(GpifDetection(GpifFamily.GP7, true, "ZIP mit Content/score.gpif"), GpifDetector.classify(exported))
        assertEquals("gp7", (GpifFormatDetector().detect(exported) as Outcome.Success).value.id)
        assertEquals(GpifFamily.GP7, GpifDetector.classify(GpifTestSupport.gpZip(gpif)).family)
    }

    @Test fun gp8MarkersAreRecognised() {
        assertEquals(GpifFamily.GP8, GpifDetector.classify(GpifTestSupport.gpZip(gpif.replace("<GPVersion>7.6.0</GPVersion>", "<GPVersion>8.1.3</GPVersion>"))).family)
        assertEquals(GpifFamily.GP8, GpifDetector.classify(GpifTestSupport.gpZip(gpif, listOf("Content/Assets/backing.ogg" to byteArrayOf(1, 2)))).family)
        assertEquals(GpifFamily.GP8, GpifDetector.classify(GpifTestSupport.gpZip(gpif.replace("<ScoreViews/>", "<BackingTrack/>"))).family)
        assertEquals("gp8", (GpifDetector.detect(GpifTestSupport.gpZip(gpif.replace("7.6.0", "8.0.0"))) as Outcome.Success).value.id)
    }

    @Test fun gpxContainersAreRecognised() {
        val image = GpifTestSupport.bcfs(listOf("score.gpif" to gpif.toByteArray()))
        assertEquals(GpifDetection(GpifFamily.GPX, true, "BCFZ/BCFS-Container mit score.gpif"), GpifDetector.classify(GpifTestSupport.bcfz(image)))
        assertEquals(GpifFamily.GPX, GpifDetector.classify(image).family)
        val prefixOnly = GpifDetector.classify(GpifTestSupport.bcfz(image).copyOf(12))
        assertEquals(GpifFamily.GPX, prefixOnly.family); assertFalse(prefixOnly.verified)
    }

    @Test fun otherContentIsNotGpif() {
        assertEquals(GpifFamily.NONE, GpifDetector.classify(GpifTestSupport.rawZip(listOf("manifest.json" to "{}".toByteArray(), "project.json" to "{}".toByteArray()))).family)
        assertEquals(GpifFamily.NONE, GpifDetector.classify(GpifTestSupport.rawZip(listOf("Content/score.gpif" to "{}".toByteArray()))).family)
        assertEquals(GpifFamily.NONE, GpifDetector.classify("MThd....".toByteArray()).family)
        assertEquals(GpifFamily.NONE, GpifDetector.classify(ByteArray(0)).family)
        assertEquals(GpifFamily.NONE, GpifDetector.classify(GpifTestSupport.rawZip(listOf("../x" to byteArrayOf()))).family)
        val failure = GpifDetector.detect("%PDF-1.7".toByteArray()) as Outcome.Failure
        assertEquals(FailureCode.VALIDATION, failure.code); assertEquals("C-FORMAT", failure.contractId)
    }
}
