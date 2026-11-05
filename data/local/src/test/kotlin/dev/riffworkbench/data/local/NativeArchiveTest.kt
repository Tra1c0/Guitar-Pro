package dev.riffworkbench.data.local

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private var n = 0
    private val ids = IdSource { EntityId("n-${n++}") }
    private fun <T> ok(value: Outcome<T>): T { assertTrue(value.toString(), value is Outcome.Success); return (value as Outcome.Success).value }
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }
    @Test fun nativeRoundTripPreservesOriginalBytesAndAdvancedParameters() = runBlocking {
        val store = FileAssetStore(temp.newFolder()); val exchange = FileNativeProjectExchange(store)
        val original = "own original gp bytes, intentionally not a claimed GP fixture".toByteArray()
        val asset = ok(store.put("original.gp", "application/octet-stream", original))
        var p = ProjectFactory(ids).create("Advanced")
        p = p.copy(assets = listOf(asset), provenance = listOf(ImportProvenance("gp8", asset.hash)), layout = LayoutSettings(mode = LayoutMode.PAGE), scales = listOf(ScaleDefinition("Minor", listOf(0, 2, 3, 5, 7, 8, 10))))
        val result = ok(exchange.export(p))
        val imported = ok(exchange.import(result.bytes, EntityId("imported")))
        assertEquals(p.copy(id = EntityId("imported"), revision = 0), imported)
        assertArrayEquals(original, ok(store.get(imported.provenance.single().originalAsset)))
        assertTrue(result.fileName.endsWith(".riffproj")); assertTrue(result.losses.isEmpty())
    }
    @Test fun traversalAndUnexpectedEntriesAreRejectedBeforeAnyAssetWrite() = runBlocking {
        val root = temp.newFolder(); val exchange = FileNativeProjectExchange(FileAssetStore(root))
        val result = exchange.import(zip(listOf("../../outside" to byteArrayOf(1))), EntityId("p"))
        assertTrue(result is Outcome.Failure)
        assertEquals(0, root.listFiles()!!.size)
    }
    @Test fun absentAssetsCannotBeExportedAsAFalseSuccess() = runBlocking {
        val exchange = FileNativeProjectExchange(FileAssetStore(temp.newFolder()))
        val hash = AssetHash("a".repeat(64)); val p = ProjectFactory(ids).create("Missing").copy(assets = listOf(AssetDescriptor(hash, "missing.wav", "audio/wav", 3)))
        assertTrue(exchange.export(p) is Outcome.Failure)
    }
    @Test fun checksumMismatchIsRejected() {
        val p = ProjectFactory(ids).create("Checksum")
        val manifest = BundleManifest(projectHash = "0".repeat(64), assets = emptyList())
        assertThrows(IllegalArgumentException::class.java) { NativeProjectCodec.decode(zip(listOf("manifest.json" to projectJson.encodeToString(manifest).toByteArray(), "project.json" to projectJson.encodeToString(p).toByteArray()))) }
    }
    @Test fun unsupportedArchiveVersionIsReportedExplicitly() = runBlocking {
        val p = ProjectFactory(ids).create("Future"); val bytes = projectJson.encodeToString(p).toByteArray()
        val manifest = BundleManifest(schemaVersion = 99, projectHash = sha256(bytes), assets = emptyList())
        val exchange = FileNativeProjectExchange(FileAssetStore(temp.newFolder()))
        val result = exchange.import(zip(listOf("manifest.json" to projectJson.encodeToString(manifest).toByteArray(), "project.json" to bytes)), ids.next()) as Outcome.Failure
        assertEquals(FailureCode.INCOMPATIBLE_VERSION, result.code)
    }
    @Test fun deeplyNestedJsonIsRejectedWithoutRecursingIntoTheParser() {
        assertThrows(IllegalArgumentException::class.java) { decodeProject(("[".repeat(200) + "]".repeat(200)).toByteArray()) }
    }
    @Test fun compressedBombIsStoppedByTheUncompressedEntryBudget() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write("""{"projectHash":"x","assets":[]}""".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("project.json"))
            val chunk = ByteArray(1024 * 1024)
            repeat(65) { zip.write(chunk) }
            zip.closeEntry()
        }
        assertTrue(output.size() < 100000)
        val error = assertThrows(IllegalArgumentException::class.java) { NativeProjectCodec.decode(output.toByteArray()) }
        assertTrue(error.message!!.startsWith("RESOURCE_LIMIT:"))
    }
    @Test fun duplicateEntityIdentityIsNotAcceptedAsAValidProject() {
        val p = ProjectFactory(ids).create("Duplicate")
        val broken = p.copy(tracks = p.tracks + p.tracks.first())
        assertThrows(IllegalArgumentException::class.java) { NativeProjectCodec.encode(broken, emptyMap()) }
    }
}
