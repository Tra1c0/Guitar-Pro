package dev.riffworkbench.data.local

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private var n = 0
    private val ids = IdSource { EntityId("id-${n++}") }
    private fun <T> ok(value: Outcome<T>): T { assertTrue(value.toString(), value is Outcome.Success); return (value as Outcome.Success).value }

    @Test fun noteAndBandSurviveARepositoryRestart() = runBlocking {
        val root = temp.newFolder(); val repo = FileProjectRepository(root)
        val project = ProjectFactory(ids).create("Drop A")
        ok(repo.save(project))
        val session = EditorSession(project, ids); session.dispatch(EditorCommand.PutNote(fret = 12, marks = setOf(NoteMark.PALM_MUTE)))
        val edited = session.state.value.project
        ok(repo.save(edited, 0))
        assertEquals(edited, ok(FileProjectRepository(root).open(project.id)))
    }
    @Test fun staleWriterCannotOverwriteANewerRevision() = runBlocking {
        val repo = FileProjectRepository(temp.newFolder()); val p = ProjectFactory(ids).create("A")
        ok(repo.save(p)); ok(repo.save(p.copy(revision = 1), 0))
        val conflict = repo.save(p.copy(revision = 2, metadata = ProjectMetadata("Stale")), 0) as Outcome.Failure
        assertEquals(FailureCode.CONFLICT, conflict.code)
        assertEquals("A", ok(repo.open(p.id)).metadata.title)
    }
    @Test fun corruptActiveRevisionRecoversPreviousHeadAndSignalsIt() = runBlocking {
        val root = temp.newFolder(); val repo = FileProjectRepository(root); val p = ProjectFactory(ids).create("Safe")
        ok(repo.save(p)); ok(repo.save(p.copy(revision = 1), 0))
        File(root, "projects/${p.id}/revision-1.json").writeText("truncated")
        val recovered = ok(FileProjectRepository(root).open(p.id))
        assertEquals(0, recovered.revision)
        assertTrue(recovered.diagnostics.any { it.code == "recovered" })
        ok(repo.save(recovered.copy(revision = 1), 0))
    }
    @Test fun uncommittedRevisionAndTempFilesDoNotAdvanceTheProject() = runBlocking {
        val root = temp.newFolder(); val repo = FileProjectRepository(root); val p = ProjectFactory(ids).create("Safe")
        ok(repo.save(p))
        File(root, "projects/${p.id}/revision-100.json").writeText(projectJson.encodeToString(p.copy(revision = 100)))
        File(root, "projects/${p.id}/.head.incomplete.tmp").writeText("partial")
        assertEquals(0, ok(FileProjectRepository(root).open(p.id)).revision)
    }
    @Test fun futureVersionDoesNotFallBackAndOverwriteANewerProject() = runBlocking {
        val root = temp.newFolder(); val repo = FileProjectRepository(root); val p = ProjectFactory(ids).create("Safe")
        ok(repo.save(p)); ok(repo.save(p.copy(revision = 1), 0))
        val dir = File(root, "projects/${p.id}")
        val bytes = projectJson.encodeToString(p.copy(schemaVersion = 2, revision = 1)).toByteArray()
        File(dir, "revision-1.json").writeBytes(bytes)
        File(dir, "head.json").writeText(projectJson.encodeToString(ProjectHead(1, "revision-1.json", sha256(bytes))))
        val result = FileProjectRepository(root).open(p.id) as Outcome.Failure
        assertEquals(FailureCode.INCOMPATIBLE_VERSION, result.code)
        assertTrue(File(dir, "head.json").readText().contains("revision-1"))
    }
    @Test fun corruptProjectsAreReportedInsteadOfSilentlyDisappearing() = runBlocking {
        // A damaged head (an empty folder is an interrupted first create and is not listed, see Qa14StorageCrashFindingsTest).
        val root = temp.newFolder(); File(root, "projects/broken").mkdirs(); File(root, "projects/broken/head.json").writeText("{ not json")
        val repo = FileProjectRepository(root); ok(repo.refresh())
        assertEquals(1, repo.issues.value.size)
        assertEquals("project_unreadable", repo.issues.value.single().code)
    }
    @Test fun snapshotsKeepOriginalMusicAndDoNotMutateTheLiveRevision() = runBlocking {
        val repo = FileProjectRepository(temp.newFolder()); val p = ProjectFactory(ids).create("Original")
        ok(repo.save(p)); val snap = ok(repo.create(p, "Before solo"))
        ok(repo.save(p.copy(revision = 1, metadata = ProjectMetadata("Edited")), 0))
        assertEquals(p, ok(repo.load(p.id, snap.id)))
        assertEquals("Edited", ok(repo.open(p.id)).metadata.title)
        assertEquals(listOf(snap), ok(repo.list(p.id)))
    }
    @Test fun invalidSnapshotPathIsRejected() = runBlocking {
        val repo = FileProjectRepository(temp.newFolder())
        assertTrue(repo.load(EntityId("p"), "../../escape") is Outcome.Failure)
    }
    @Test fun settingsPersistAndCorruptionIsVisible() = runBlocking {
        val file = temp.newFile(); file.delete()
        val repo = FileSettingsRepository(file)
        ok(repo.update(AppSettings(darkTheme = false, autoAdvance = false)))
        val restarted = FileSettingsRepository(file); ok(restarted.load())
        assertFalse(restarted.settings.value.darkTheme); assertFalse(restarted.settings.value.autoAdvance)
        file.writeText("broken")
        assertTrue(FileSettingsRepository(file).load() is Outcome.Failure)
    }
    @Test fun assetsAreAddressedByContentAndCheckedOnRead() = runBlocking {
        val root = temp.newFolder(); val store = FileAssetStore(root)
        val first = ok(store.put("first", "text/plain", "abc".toByteArray()))
        val second = ok(store.put("second", "text/plain", "abc".toByteArray()))
        assertEquals(first.hash, second.hash); assertEquals(1, root.listFiles()!!.size)
        File(root, first.hash.value).writeText("changed")
        assertTrue(store.get(first.hash) is Outcome.Failure)
    }
    @Test fun unwritableStorageReturnsAnIoFailureWithoutPublishing() = runBlocking {
        val rootIsAFile = temp.newFile()
        val repo = FileProjectRepository(rootIsAFile)
        val result = repo.save(ProjectFactory(ids).create("Cannot save")) as Outcome.Failure
        assertEquals(FailureCode.IO, result.code)
        assertTrue(repo.projects.value.isEmpty())
    }
}
