package dev.riffworkbench.feature.library

import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.editor.ProjectFactory
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Foreign import: exactly one provenance entry and never a descriptor for bytes the store does not hold. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryImportTest {
    private var n = 0
    private val ids = IdSource { EntityId("li-${n++}") }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Repository : ProjectRepository {
        val saved = ArrayList<Project>()
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId): Outcome<Project> = saved.firstOrNull { it.id == id }?.let { Outcome.Success(it) } ?: Outcome.Failure(FailureCode.IO, "fehlt")
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> { saved += project; return Outcome.Success(Unit) }
    }
    private class Assets(val fail: Boolean = false) : AssetStore {
        val data = HashMap<AssetHash, ByteArray>()
        override suspend fun put(name: String, mediaType: String, bytes: ByteArray, rights: AssetRights): Outcome<AssetDescriptor> {
            if (fail) return Outcome.Failure(FailureCode.IO, "Speicher voll")
            val hash = AssetHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            data[hash] = bytes
            return Outcome.Success(AssetDescriptor(hash, name, mediaType, bytes.size.toLong(), rights))
        }
        override suspend fun get(hash: AssetHash) = data[hash]?.let { Outcome.Success(it) } ?: Outcome.Failure(FailureCode.IO, "fehlt")
    }
    private val exchange = object : NativeProjectExchange {
        override suspend fun export(project: Project): Outcome<ExportResult> = unavailable("C-NATIVE")
        override suspend fun import(bytes: ByteArray, newProjectId: EntityId): Outcome<Project> = unavailable("C-NATIVE")
    }

    /** Behaves like the GP importers: describes the original itself and lists one loss. */
    private inner class Catalog : FormatCatalog {
        val info = FormatInfo("gp5", "Guitar Pro 5", "gp5", "gp")
        override val formats = listOf(info)
        override fun detect(bytes: ByteArray, fileName: String?): Outcome<FormatInfo> = Outcome.Success(info)
        override fun exporter(formatId: String): ScoreExporter? = null
        override fun importer(formatId: String): ScoreImporter = object : ScoreImporter {
            override val formatId = "gp5"
            override suspend fun import(bytes: ByteArray, ids: IdSource, progress: (OperationProgress) -> Unit): Outcome<ImportResult> {
                val hash = AssetHash(sha(bytes))
                val loss = LossEntry("rse", "import", "RSE nicht übernommen")
                val project = ProjectFactory(ids).create("Import").copy(
                    assets = listOf(AssetDescriptor(hash, "original.gp5", "application/octet-stream", bytes.size.toLong())),
                    provenance = listOf(ImportProvenance("gp5", hash, losses = listOf(loss))))
                return Outcome.Success(ImportResult(project, listOf(loss)))
            }
        }
    }

    private suspend fun TestScope.run(assets: Assets, bytes: ByteArray): Repository {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repository()
        val vm = LibraryViewModel(repo, exchange, ids, Catalog(), assets, background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("lib", vm)
        try {
            var opened: EntityId? = null
            vm.importFile(bytes, "riff.gp5") { opened = it }
            advanceUntilIdle()
            assertNotNull("import should open the project: ${vm.error.value}", opened)
        } finally { store.clear(); Dispatchers.resetMain() }
        return repo
    }

    @Test fun importKeepsOneProvenanceAndTheStoredOriginal() = runTest {
        val bytes = ByteArray(300) { (it * 7).toByte() }
        val assets = Assets()
        val project = run(assets, bytes).saved.single()
        val hash = AssetHash(sha(bytes))
        assertEquals(1, project.provenance.size)
        assertEquals(hash, project.provenance.single().originalAsset)
        assertEquals("gp5", project.provenance.single().format)
        assertEquals(listOf("rse"), project.provenance.single().losses.map { it.featureId })
        assertEquals(listOf(hash), project.assets.map { it.hash })
        assertEquals("riff.gp5", project.assets.single().name)
        assertArrayEquals(bytes, assets.data.getValue(hash))
    }

    @Test fun withoutStoredBytesNoDescriptorPointsAtAMissingAsset() = runTest {
        val project = run(Assets(fail = true), ByteArray(64) { 1 }).saved.single()
        assertTrue(project.provenance.isEmpty())
        assertTrue(project.assets.isEmpty())
    }
}
