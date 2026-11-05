package dev.riffworkbench.data.local

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.editor.ScoreValidator
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable internal data class ProjectHead(val revision: Long, val file: String, val sha256: String)

private const val RECOVERED = "recovered"

class FileProjectRepository(private val root: File) : ProjectRepository, SnapshotRepository {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<List<ProjectSummary>>(emptyList())
    override val projects = mutable.asStateFlow()
    private val mutableIssues = MutableStateFlow<List<Diagnostic>>(emptyList())
    override val issues = mutableIssues.asStateFlow()
    private val projectsDir = File(root, "projects")

    override suspend fun refresh(): Outcome<Unit> = withContext(Dispatchers.IO) { mutex.withLock { storageOutcome { refreshIndex() } } }
    override suspend fun open(id: EntityId): Outcome<Project> = withContext(Dispatchers.IO) { mutex.withLock {
        storageOutcome {
            val dir = File(projectsDir, id.value)
            val (_, recovered, read) = validHead(dir)
            val project = read.let { it.copy(diagnostics = it.diagnostics.filterNot { d -> d.code == RECOVERED }) }
            require(project.id == id) { "Projekt-ID stimmt nicht mit dem Speicherort überein" }
            if (recovered) project.copy(diagnostics = project.diagnostics + Diagnostic(RECOVERED, "Letzte intakte Revision wiederhergestellt", severity = Severity.WARNING)) else project
        }
    } }
    override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> = withContext(Dispatchers.IO) { mutex.withLock {
        storageOutcome {
            require(project.schemaVersion == 1 && project.revision >= 0)
            require(ScoreValidator.validate(project).none { it.severity == Severity.ERROR }) { "Ungültiges Projekt kann nicht gespeichert werden" }
            val dir = File(projectsDir, project.id.value)
            val existing = if (File(dir, "head.json").exists() || File(dir, "head.previous.json").exists()) validHead(dir).head else null
            require(if (expectedRevision == null) existing == null else existing?.revision == expectedRevision) { "CONFLICT: Gespeicherte Revision hat sich geändert" }
            require(existing == null || project.revision > existing.revision) { "CONFLICT: Revision muss steigen" }
            // The recovery notice belongs to this open, not to the music: never persist it.
            val bytes = projectJson.encodeToString(project.copy(diagnostics = project.diagnostics.filterNot { it.code == RECOVERED })).toByteArray()
            require(bytes.size <= NativeArchiveLimits.MAX_ENTRY) { "RESOURCE_LIMIT: Projekt zu groß" }
            val head = ProjectHead(project.revision, "revision-${project.revision}.json", sha256(bytes))
            // After a fallback from a torn head the target name can still hold newer, intact work: keep it aside instead of overwriting.
            val target = File(dir, head.file)
            if (target.exists() && existing?.file != head.file) java.nio.file.Files.move(target.toPath(), File(dir, "orphan-${head.file.removeSuffix(".json")}-${UUID.randomUUID()}.json").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            atomicWrite(target, bytes)
            if (existing != null) atomicWrite(File(dir, "head.previous.json"), projectJson.encodeToString(existing).toByteArray())
            atomicWrite(File(dir, "head.json"), projectJson.encodeToString(head).toByteArray())
            // Only this project changed: update its summary instead of re-reading the whole library under the lock.
            val summary = ProjectSummary(project.id, project.metadata.title, project.revision, project.tracks.size)
            mutable.value = (mutable.value.filterNot { it.id == project.id } + summary).sortedBy { it.title.lowercase() }
            mutableIssues.value = mutableIssues.value.filterNot { it.message.startsWith("${project.id.value}:") }
        }
    } }
    override suspend fun delete(id: EntityId): Outcome<Unit> = withContext(Dispatchers.IO) { mutex.withLock {
        storageOutcome {
            val source = File(projectsDir, id.value)
            require(source.exists()) { "Projekt fehlt" }
            val trash = File(root, "trash").apply { mkdirs() }
            java.nio.file.Files.move(source.toPath(), File(trash, "${id.value}-${UUID.randomUUID()}").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            refreshIndex()
        }
    } }

    override suspend fun list(projectId: EntityId): Outcome<List<SnapshotInfo>> = withContext(Dispatchers.IO) { mutex.withLock {
        storageOutcome {
            // One torn meta file (crash during create) must not hide every other snapshot.
            File(projectsDir, "${projectId.value}/snapshots").listFiles()?.filter { it.name.endsWith(".meta.json") }?.mapNotNull {
                runCatching { projectJson.decodeFromString<SnapshotInfo>(readLimited(it, 1024 * 1024).toString(Charsets.UTF_8)) }.getOrNull()
            }?.sortedByDescending { it.createdAtMillis } ?: emptyList()
        }
    } }
    override suspend fun create(project: Project, name: String): Outcome<SnapshotInfo> = withContext(Dispatchers.IO) { mutex.withLock {
        storageOutcome {
            require(name.isNotBlank() && name.length <= 200)
            // A snapshot of a deleted project would recreate an unreadable project directory.
            require(File(projectsDir, "${project.id.value}/head.json").exists() || File(projectsDir, "${project.id.value}/head.previous.json").exists()) { "Projekt existiert nicht mehr" }
            val snapshot = SnapshotInfo(UUID.randomUUID().toString(), project.id, project.revision, name.trim(), System.currentTimeMillis())
            val dir = File(projectsDir, "${project.id.value}/snapshots")
            atomicWrite(File(dir, "${snapshot.id}.json"), projectJson.encodeToString(project).toByteArray())
            atomicWrite(File(dir, "${snapshot.id}.meta.json"), projectJson.encodeToString(snapshot).toByteArray())
            snapshot
        }
    } }
    override suspend fun load(projectId: EntityId, snapshotId: String): Outcome<Project> = withContext(Dispatchers.IO) { mutex.withLock {
        storageOutcome {
            EntityId(snapshotId)
            decodeProject(readLimited(File(projectsDir, "${projectId.value}/snapshots/$snapshotId.json"))).also { require(it.id == projectId) }
        }
    } }

    private data class ValidHead(val head: ProjectHead, val recovered: Boolean, val project: Project)
    private fun validHead(dir: File): ValidHead {
        var last: Exception? = null
        for ((index, name) in listOf("head.json", "head.previous.json").withIndex()) {
            try {
                val head = projectJson.decodeFromString<ProjectHead>(readLimited(File(dir, name), 4096).toString(Charsets.UTF_8))
                require(head.file.matches(Regex("revision-[0-9]+\\.json")))
                return ValidHead(head, index > 0, readRevision(dir, head))
            } catch (e: IOException) { last = e } catch (e: IllegalArgumentException) {
                if (e.message?.startsWith("INCOMPATIBLE_VERSION:") == true) throw e
                last = e
            }
        }
        throw IOException("Keine intakte Projektrevision: ${last?.message}", last)
    }
    private fun readRevision(dir: File, head: ProjectHead): Project {
        val bytes = readLimited(File(dir, head.file))
        require(sha256(bytes) == head.sha256) { "Prüfsumme der Revision stimmt nicht" }
        return decodeProject(bytes).also { require(it.revision == head.revision); require(ScoreValidator.validate(it).none { d -> d.severity == Severity.ERROR }) { "Ungültige gespeicherte Partitur" } }
    }
    private fun refreshIndex() {
        val issues = mutableListOf<Diagnostic>()
        mutable.value = projectsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            try { val p = validHead(dir).project; ProjectSummary(p.id, p.metadata.title, p.revision, p.tracks.size) }
            catch (e: IOException) { issues += Diagnostic("project_unreadable", "${dir.name}: ${e.message}", severity = Severity.ERROR); null }
            catch (e: IllegalArgumentException) { issues += Diagnostic("project_unreadable", "${dir.name}: ${e.message}", severity = Severity.ERROR); null }
        }?.sortedBy { it.title.lowercase() } ?: emptyList()
        mutableIssues.value = issues
    }
}
