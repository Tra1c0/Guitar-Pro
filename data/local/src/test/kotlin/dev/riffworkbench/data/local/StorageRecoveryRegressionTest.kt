package dev.riffworkbench.data.local

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageRecoveryRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private var n = 0
    private val ids = IdSource { EntityId("id-${n++}") }
    private fun <T> ok(value: Outcome<T>): T { assertTrue(value.toString(), value is Outcome.Success); return (value as Outcome.Success).value }

    @Test fun recoveredDiagnosticIsPersistedAndReappearsOnEveryOpen() = runBlocking {
        val root = temp.newFolder(); val repo = FileProjectRepository(root); val p = ProjectFactory(ids).create("Safe")
        ok(repo.save(p)); ok(repo.save(p.copy(revision = 1), 0))
        File(root, "projects/${p.id}/revision-1.json").writeText("truncated")
        val recovered = ok(FileProjectRepository(root).open(p.id))
        // Workspace: EditorSession(recovered) -> edit -> autosave (revision 1, expected 0)
        val s = EditorSession(recovered, ids); s.dispatch(EditorCommand.Rename("Edited"))
        ok(repo.save(s.state.value.project, 0))
        val reopened = ok(FileProjectRepository(root).open(p.id))
        assertFalse("healthy reopen must not report recovery", reopened.diagnostics.any { it.code == "recovered" })
    }

    @Test fun corruptHeadRecoveryOverwritesIntactNewestRevision() = runBlocking {
        val root = temp.newFolder(); val repo = FileProjectRepository(root); val p = ProjectFactory(ids).create("Safe")
        ok(repo.save(p)); ok(repo.save(p.copy(revision = 1, metadata = ProjectMetadata("Newest work")), 0))
        val dir = File(root, "projects/${p.id}")
        val intact = File(dir, "revision-1.json").readText()
        File(dir, "head.json").writeText("{") // only the 200-byte pointer is torn; revision-1.json is intact
        val recovered = ok(FileProjectRepository(root).open(p.id))
        assertEquals("Safe", recovered.metadata.title) // fell back to revision 0
        val s = EditorSession(recovered, ids); s.dispatch(EditorCommand.SetTempo(100.0))
        ok(repo.save(s.state.value.project, 0)) // autosave writes revision-1.json again
        val after = File(dir, "revision-1.json").readText()
        assertTrue("intact newer revision must not be destroyed", after.contains("Newest work") || dir.listFiles()!!.any { it.readText().contains("Newest work") })
    }
}
