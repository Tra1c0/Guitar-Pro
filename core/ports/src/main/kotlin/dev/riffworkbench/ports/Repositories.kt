package dev.riffworkbench.ports

import dev.riffworkbench.model.*
import kotlinx.coroutines.flow.StateFlow

/** C-PROJECT: IO dispatcher, atomic publish, optimistic revision; cancellation never publishes a partial revision. */
interface ProjectRepository {
    val projects: StateFlow<List<ProjectSummary>>
    val issues: StateFlow<List<Diagnostic>>
    suspend fun refresh(): Outcome<Unit>
    suspend fun open(id: EntityId): Outcome<Project>
    /** null means create-only; otherwise stored revision must match expectedRevision. */
    suspend fun save(project: Project, expectedRevision: Long? = null): Outcome<Unit>
    suspend fun delete(id: EntityId): Outcome<Unit>
}

/** C-SNAPSHOT: immutable named revisions, restored through an editor transaction. */
interface SnapshotRepository {
    suspend fun list(projectId: EntityId): Outcome<List<SnapshotInfo>>
    suspend fun create(project: Project, name: String): Outcome<SnapshotInfo>
    suspend fun load(projectId: EntityId, snapshotId: String): Outcome<Project>
}

/** C-ASSET: SHA-256 addressed immutable bytes; enforce size budgets before buffering. */
interface AssetStore {
    suspend fun put(name: String, mediaType: String, bytes: ByteArray, rights: AssetRights = AssetRights()): Outcome<AssetDescriptor>
    suspend fun get(hash: AssetHash): Outcome<ByteArray>
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    suspend fun load(): Outcome<Unit>
    suspend fun update(settings: AppSettings): Outcome<Unit>
}

/** C-NATIVE: validate the complete archive before publishing a project. Every referenced asset is required. */
interface NativeProjectExchange {
    suspend fun export(project: Project): Outcome<ExportResult>
    suspend fun import(bytes: ByteArray, newProjectId: EntityId): Outcome<Project>
}
