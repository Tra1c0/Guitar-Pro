package dev.riffworkbench.feature.workspace

import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.adapter.audio.AudioDecoders
import dev.riffworkbench.adapter.audio.PendingAudioServices
import dev.riffworkbench.editor.ProjectFactory
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.synth.Wav
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceAudioTest {
    private var n = 0
    private val ids = IdSource { EntityId("ra-${n++}") }
    private class Repository(var stored: Project) : ProjectRepository {
        override val projects = MutableStateFlow(emptyList<ProjectSummary>())
        override val issues = MutableStateFlow(emptyList<Diagnostic>())
        override suspend fun refresh() = Outcome.Success(Unit)
        override suspend fun open(id: EntityId) = Outcome.Success(stored)
        override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
        override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> { stored = project; return Outcome.Success(Unit) }
    }
    private class Assets : AssetStore {
        val data = HashMap<AssetHash, ByteArray>()
        override suspend fun put(name: String, mediaType: String, bytes: ByteArray, rights: AssetRights): Outcome<AssetDescriptor> {
            val hash = AssetHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            data[hash] = bytes
            return Outcome.Success(AssetDescriptor(hash, name, mediaType, bytes.size.toLong(), rights))
        }
        override suspend fun get(hash: AssetHash) = data[hash]?.let { Outcome.Success(it) } ?: Outcome.Failure(FailureCode.IO, "fehlt")
    }
    private val snapshots = object : SnapshotRepository {
        override suspend fun list(projectId: EntityId) = Outcome.Success(emptyList<SnapshotInfo>())
        override suspend fun create(project: Project, name: String) = Outcome.Success(SnapshotInfo("s", project.id, project.revision, name, 0))
        override suspend fun load(projectId: EntityId, snapshotId: String): Outcome<Project> = unavailable("C-SNAPSHOT")
    }
    private val settings = object : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override suspend fun load() = Outcome.Success(Unit)
        override suspend fun update(settings: AppSettings): Outcome<Unit> { this.settings.value = settings; return Outcome.Success(Unit) }
    }
    private val exchange = object : NativeProjectExchange {
        override suspend fun export(project: Project): Outcome<ExportResult> = unavailable("C-NATIVE")
        override suspend fun import(bytes: ByteArray, newProjectId: EntityId): Outcome<Project> = unavailable("C-NATIVE")
    }

    private suspend fun TestScope.harness(block: suspend TestScope.(WorkspaceViewModel, Repository, Assets) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repository(ProjectFactory(ids).create("Referenz")); val assets = Assets()
        val services = WorkspaceServices(assets = assets, audioDecoder = AudioDecoders())
        val vm = WorkspaceViewModel(repo.stored.id, repo, snapshots, exchange, settings, PendingAudioServices(), ids, services, background = StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); store.put("test", vm)
        try { advanceUntilIdle(); block(vm, repo, assets) }
        finally { store.clear(); Dispatchers.resetMain() }
    }

    private fun wav(frames: Int) = Wav.header(frames.toLong(), 48_000, 1, 16) + ByteArray(frames * 2) { if (it % 200 == 1) 0x20 else 0 }

    @Test fun importedAudioBecomesAStoredClipThatCanBeAlignedAndRemoved() = runTest { harness { vm, repo, assets ->
        val bytes = wav(4800)
        vm.importAudio(bytes, "Aufnahme.wav"); advanceUntilIdle()
        val clip = repo.stored.audio.clips.single()
        assertEquals("Aufnahme", clip.name)
        assertEquals(clip.asset, repo.stored.audio.assets.single().hash)
        assertArrayEquals(bytes, assets.data.getValue(clip.asset))
        assertFalse(repo.stored.audio.assets.single().rights.redistributionAllowed)

        vm.updateClip(clip.copy(offsetMicros = -2_350_000, gain = 0.5, solo = true)); advanceUntilIdle()
        assertEquals(-2_350_000L, repo.stored.audio.clips.single().offsetMicros)
        assertTrue(repo.stored.audio.clips.single().solo)

        vm.removeClip(clip.id); advanceUntilIdle()
        assertTrue(repo.stored.audio.clips.isEmpty())
        assertTrue(repo.stored.audio.assets.isEmpty())
        vm.undo(); advanceUntilIdle()
        assertEquals(1, repo.stored.audio.clips.size)
    } }

    @Test fun undecodableFilesAreRejectedBeforeAnythingIsStored() = runTest { harness { vm, repo, assets ->
        vm.importAudio("not audio at all".toByteArray(), "notiz.txt"); advanceUntilIdle()
        assertTrue(repo.stored.audio.clips.isEmpty())
        assertTrue(assets.data.isEmpty())
        assertNotNull(vm.state.value.notice)
        assertNull(vm.state.value.busy)
    } }
}
