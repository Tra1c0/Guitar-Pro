package dev.riffworkbench.data.local

import dev.riffworkbench.editor.ProjectFactory
import dev.riffworkbench.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * QA cycle 14 (model-based crash consistency, Qa14ModelBasedStorageTest seed 14004, shrunk to one step:
 * CrashDuring(Create, k=6)). A process kill during the *first* save of a project leaves projects/<id>/ without a head.
 * Nothing was committed, yet every later refresh() reports a permanent "project_unreadable" error for it.
 */
class Qa14StorageCrashFindingsTest {
    @get:Rule val temp = TemporaryFolder()
    private var n = 0
    private val ids = IdSource { EntityId("q14-${n++}") }
    private fun <T> ok(value: Outcome<T>): T { assertTrue(value.toString(), value is Outcome.Success); return (value as Outcome.Success).value }

    /** Crash image: revision-0.json renamed into place, head.json not yet published (FileProjectRepository.kt:56 -> :58). */
    @Test fun crashBeforeFirstHeadIsNotReportedAsAnUnreadableProject() = runBlocking {
        val root = temp.newFolder(); val p = ProjectFactory(ids).create("Neu")
        ok(FileProjectRepository(root).save(p))
        File(root, "projects/${p.id}/head.json").delete() // == the on-disk state just before the head rename
        val reopened = FileProjectRepository(root)
        ok(reopened.refresh())
        assertTrue(reopened.projects.value.none { it.id == p.id }) // holds: not a commit, not listed
        assertEquals("uncommitted create must not show up as a library error", emptyList<String>(), reopened.issues.value.map { it.message })
    }

    /** Crash image: only the partially written temp file of revision-0 exists (crash inside atomicWrite, Storage.kt:21-23). */
    @Test fun crashWhileWritingTheFirstRevisionIsNotReportedAsAnUnreadableProject() = runBlocking {
        val root = temp.newFolder(); val p = ProjectFactory(ids).create("Neu")
        val dir = File(root, "projects/${p.id}").apply { mkdirs() }
        File(dir, ".revision-0.json.0b6f6c55-1c7e-4bd7-9a43-3f0b1b4f2a11.tmp").writeText("{\"schemaVersion\":1,\"id\":\"")
        val reopened = FileProjectRepository(root)
        ok(reopened.refresh())
        // The library (LibraryScreen NoticeCard) would keep a non-dismissable error card for a project that never existed.
        assertEquals("uncommitted create must not show up as a library error", emptyList<String>(), reopened.issues.value.map { it.message })
    }
}
