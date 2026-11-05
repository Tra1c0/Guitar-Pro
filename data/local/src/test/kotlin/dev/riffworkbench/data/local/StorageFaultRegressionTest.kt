package dev.riffworkbench.data.local

import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Defects found by fault injection (QA cycle 3). */
class StorageFaultRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private var n = 0
    private val ids = IdSource { EntityId("f-${n++}") }
    private fun <T> ok(value: Outcome<T>): T { assertTrue(value.toString(), value is Outcome.Success); return (value as Outcome.Success).value }
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.setLevel(9); entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }
    private val mine = AppSettings(darkTheme = false, autoAdvance = false, strictRhythm = true, favoriteEffects = listOf("tap"), shortcuts = mapOf("k" to "put_note"))

    @Test fun settingsFromANewerVersionStillLoad() = runBlocking {
        val file = File(temp.newFolder(), "settings.json")
        ok(FileSettingsRepository(file).update(mine))
        file.writeText(file.readText().replaceFirst("{", "{\n    \"metronomeVolume\": 0.5,"))
        val repo = FileSettingsRepository(file); ok(repo.load())
        assertEquals(mine, repo.settings.value)
    }

    @Test fun unreadableSettingsAreKeptAsideNotOverwritten() = runBlocking {
        val file = File(temp.newFolder(), "settings.json")
        ok(FileSettingsRepository(file).update(mine))
        val original = file.readText(); file.writeText(original.take(original.length / 2))
        val repo = FileSettingsRepository(file)
        assertTrue(repo.load() is Outcome.Failure)
        ok(repo.update(repo.settings.value.copy(darkTheme = true)))
        assertTrue("torn original kept for recovery", file.parentFile.listFiles()!!.any { it.name.startsWith("settings.json.unreadable-") && it.readText() == original.take(original.length / 2) })
    }

    @Test fun aCorruptStoredAssetIsRepairedByAValidPut() = runBlocking {
        val root = temp.newFolder(); val bytes = "payload".toByteArray()
        File(root, sha256(bytes)).writeText("bit rot")
        val store = FileAssetStore(root)
        ok(store.put("a.bin", "application/octet-stream", bytes))
        assertArrayEquals(bytes, ok(store.get(AssetHash(sha256(bytes)))))
    }

    @Test fun undeclaredLargeEntriesAreRejectedBeforeInflating() {
        val big = ByteArray(63 * 1024 * 1024)
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        for (archive in listOf(
            zip(List(4) { "assets/${"%064x".format(it)}" to big }),
            zip(listOf("manifest.json" to """{"projectHash":"x","assets":[]}""".toByteArray()) + List(4) { "assets/${"%064x".format(it)}" to big }),
        )) {
            val before = mx.getThreadAllocatedBytes(Thread.currentThread().id)
            val r = storageOutcome { NativeProjectCodec.decode(archive) }
            val allocated = mx.getThreadAllocatedBytes(Thread.currentThread().id) - before
            assertTrue(r is Outcome.Failure)
            assertTrue("allocated ${allocated / 1048576} MiB", allocated < 32L * 1024 * 1024)
        }
    }

    @Test fun truncatedArchiveIsCorruptNotIo() = runBlocking {
        val p = ProjectFactory(ids).create("T")
        val bytes = ok(FileNativeProjectExchange(FileAssetStore(temp.newFolder())).export(p)).bytes
        val r = FileNativeProjectExchange(FileAssetStore(temp.newFolder())).import(bytes.copyOf(bytes.size * 2 / 3), EntityId("x"))
        assertEquals(FailureCode.CORRUPT, (r as Outcome.Failure).code)
    }

    @Test fun snapshotOfADeletedProjectFailsWithoutResurrectingIt() = runBlocking {
        val root = temp.newFolder(); val repo = FileProjectRepository(root)
        val p = ProjectFactory(ids).create("gone"); ok(repo.save(p)); ok(repo.delete(p.id))
        assertTrue(repo.create(p, "late") is Outcome.Failure)
        ok(repo.refresh())
        assertTrue(repo.issues.value.isEmpty())
    }

    @Test fun oneTornSnapshotMetaDoesNotHideTheOthers() = runBlocking {
        val root = temp.newFolder(); val repo = FileProjectRepository(root)
        val p = ProjectFactory(ids).create("s"); ok(repo.save(p))
        val good = ok(repo.create(p, "good")); val other = ok(repo.create(p, "other"))
        File(root, "projects/${p.id}/snapshots/${good.id}.meta.json").writeText("{")
        assertEquals(listOf(other.id), ok(repo.list(p.id)).map { it.id })
    }

    /** Autosave updates only its own summary; it no longer re-reads (and re-validates) every project under the lock. */
    @Test fun saveDoesNotRescanTheLibrary() = runBlocking {
        val root = temp.newFolder(); val repo = FileProjectRepository(root)
        val a = ProjectFactory(ids).create("A"); ok(repo.save(a))
        File(root, "projects/broken-elsewhere").mkdirs(); File(root, "projects/broken-elsewhere/head.json").writeText("{ not json")
        ok(repo.save(a.copy(revision = 1, metadata = a.metadata.copy(title = "A2")), 0))
        assertEquals(listOf("A2"), repo.projects.value.map { it.title })
        assertTrue(repo.issues.value.isEmpty())
        ok(repo.refresh())
        assertTrue(repo.issues.value.any { it.code == "project_unreadable" })
    }
}

class CompactStorageRegressionTest {
    private var n = 0
    private val ids = IdSource { EntityId("c-${n++}") }
    /** Pretty-printed JSON cost ~2 KB per note (mostly indentation) and pushed mid-size scores past the 64 MiB limit. */
    @Test fun projectsAreStoredCompactlyAndOldPrettyFilesStillLoad() {
        val e = EditorSession(ProjectFactory(ids).create("C"), ids)
        e.dispatch(EditorCommand.PutNote(fret = 3))
        val p = e.state.value.project
        val text = projectJson.encodeToString(p)
        assertFalse(text.contains("\n"))
        val pretty = kotlinx.serialization.json.Json { encodeDefaults = true; prettyPrint = true; classDiscriminator = "type" }.encodeToString(Project.serializer(), p)
        assertEquals(p, decodeProject(pretty.toByteArray()))
        assertEquals(p, decodeProject(text.toByteArray()))
    }
}

/** QA cycle 10: "open most recent project" needs a modification time; the list itself is sorted by title. */
class RecentProjectRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private var n = 0
    private val ids = IdSource { EntityId("r-${n++}") }
    @Test fun summariesCarryTheLastSaveTime() = runBlocking {
        val repo = FileProjectRepository(temp.newFolder())
        val zeta = ProjectFactory(ids).create("Zeta"); val alpha = ProjectFactory(ids).create("Alpha")
        assertTrue(repo.save(alpha) is Outcome.Success); assertTrue(repo.save(zeta) is Outcome.Success)
        Thread.sleep(30)
        assertTrue(repo.save(zeta.copy(revision = 1), 0) is Outcome.Success)
        assertEquals(listOf("Alpha", "Zeta"), repo.projects.value.map { it.title })
        assertEquals("Zeta", repo.projects.value.maxBy { it.modifiedMillis }.title)
        assertTrue(repo.refresh() is Outcome.Success)
        assertEquals("Zeta", repo.projects.value.maxBy { it.modifiedMillis }.title)
    }
}
