package dev.riffworkbench.data.local

import dev.riffworkbench.editor.ProjectFactory
import dev.riffworkbench.model.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** QA cycle 11 (ruled out): autosave racing delete / snapshot never resurrects a deleted project. */
class LifecycleQaStorageTest {
    @Test fun autosaveRacingDeleteNeverRecreatesTheProject() = runBlocking {
        var n = 0
        repeat(50) {
            val root = Files.createTempDirectory("lq").toFile()
            val repo = FileProjectRepository(root)
            val p = ProjectFactory(IdSource { EntityId("s${n++}") }).create("Race")
            assertTrue(repo.save(p) is Outcome.Success)
            val results = listOf(
                async(Dispatchers.IO) { repo.save(p.copy(revision = 1), 0) },
                async(Dispatchers.IO) { repo.delete(p.id) },
                async(Dispatchers.IO) { repo.save(p.copy(revision = 2), 1) },
            ).awaitAll()
            assertTrue(results[1] is Outcome.Success)
            assertFalse("deleted project must stay deleted", File(root, "projects/${p.id.value}").exists())
            assertTrue(repo.projects.value.none { it.id == p.id })
            results.filterIsInstance<Outcome.Failure>().forEach { assertEquals(FailureCode.CONFLICT, it.code) }
            assertTrue(repo.create(p, "snap") is Outcome.Failure)
            root.deleteRecursively()
        }
    }
}
