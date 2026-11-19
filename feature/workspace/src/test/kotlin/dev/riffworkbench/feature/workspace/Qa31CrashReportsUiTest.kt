package dev.riffworkbench.feature.workspace

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.ui.components.RiffTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Zyklus 31 „Letzte Abstürze“ on the diagnostics page (Robolectric, CI only): the list (newest first), the text of a
 * report on tap, „Teilen“ hands exactly that text to the share action (ACTION_SEND text/plain), „Löschen“ removes it,
 * „Alle löschen“ empties the list; the section is there even without the audio lab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w891dp-h411dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa31CrashReportsUiTest {
    @get:Rule val compose = createComposeRule()

    private class Reports : CrashReports {
        val texts = linkedMapOf(
            "crash-0000000002000.txt" to "ArtiFret Absturzbericht\nAusnahme: java.lang.ArrayIndexOutOfBoundsException: Index 29 out of bounds for length 16\n\tat dev.riffworkbench.feature.workspace.ProjectScreensKt.DrumBar\n",
            "crash-0000000001000.txt" to "ArtiFret Absturzbericht\nAusnahme: java.lang.OutOfMemoryError: Failed to allocate\n",
        )
        override fun list() = texts.map { (id, text) -> CrashEntry(id, id.substring(6, 19).toLong(), text.lines()[1].removePrefix("Ausnahme: "), text.length.toLong()) }
        override fun read(id: String) = texts[id]
        override fun delete(id: String) { texts.remove(id) }
        override fun clear() = texts.clear()
        override fun takeUnseen() = false
    }

    @Test fun listShowShareDelete() {
        val reports = Reports()
        val shared = ArrayList<String>()
        compose.setContent { RiffTheme { CrashReportsSection(reports, onShare = { shared += it }) } }
        compose.onNodeWithText("Letzte Abstürze").assertExists()
        compose.onNodeWithTag("crash-entry-0").assert(hasText("java.lang.ArrayIndexOutOfBoundsException: Index 29 out of bounds for length 16", substring = true))
        compose.onNodeWithTag("crash-entry-1").assert(hasText("OutOfMemoryError", substring = true))
        compose.onNodeWithTag("crash-text").assertDoesNotExist()
        compose.onNodeWithTag("crash-entry-0").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("crash-text").assert(hasText("ProjectScreensKt.DrumBar", substring = true))
        compose.onNodeWithTag("crash-share").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(reports.texts["crash-0000000002000.txt"]), shared)
        compose.onNodeWithTag("crash-delete").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("crash-0000000001000.txt"), reports.texts.keys.toList())
        compose.onNodeWithTag("crash-entry-0").assert(hasText("OutOfMemoryError", substring = true))
        compose.onNodeWithTag("crash-entry-1").assertDoesNotExist()
        compose.onNodeWithTag("crash-delete-all").assertDoesNotExist()
    }

    @Test fun deleteAllAndEmptyState() {
        val reports = Reports()
        compose.setContent { RiffTheme { CrashReportsSection(reports, onShare = {}) } }
        compose.onNodeWithTag("crash-delete-all").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(reports.texts.isEmpty())
        compose.onNodeWithTag("crash-none").assertExists()
    }

    @Test fun shareIntentIsPlainTextWithTheReport() {
        val intent = crashShareIntent("Bericht")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Bericht", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    // The section on the real diagnostics page of a workspace (no audio lab in this build: the crash reports are still there).
    private var n = 0
    private val ids = IdSource { EntityId("q31c-${n++}") }
    private val store = ViewModelStore()
    @After fun tearDown() { store.clear() }

    @Test fun diagnosticsPageShowsTheSection() {
        val project = ProjectFactory(ids).create("QA31")
        val repo = object : ProjectRepository {
            override val projects = MutableStateFlow(emptyList<ProjectSummary>())
            override val issues = MutableStateFlow(emptyList<Diagnostic>())
            override suspend fun refresh() = Outcome.Success(Unit)
            override suspend fun open(id: EntityId) = Outcome.Success(project)
            override suspend fun delete(id: EntityId) = unavailable("C-PROJECT")
            override suspend fun save(project: Project, expectedRevision: Long?): Outcome<Unit> = Outcome.Success(Unit)
        }
        val snapshots = object : SnapshotRepository {
            override suspend fun list(projectId: EntityId) = Outcome.Success(emptyList<SnapshotInfo>())
            override suspend fun create(project: Project, name: String) = Outcome.Success(SnapshotInfo("snap", project.id, project.revision, name, 0))
            override suspend fun load(projectId: EntityId, snapshotId: String): Outcome<Project> = unavailable("C-SNAPSHOT")
        }
        val settings = object : SettingsRepository {
            override val settings = MutableStateFlow(AppSettings())
            override suspend fun load() = Outcome.Success(Unit)
            override suspend fun update(settings: AppSettings): Outcome<Unit> = Outcome.Success(Unit)
        }
        val exchange = object : NativeProjectExchange {
            override suspend fun export(project: Project): Outcome<ExportResult> = unavailable("C-NATIVE")
            override suspend fun import(bytes: ByteArray, newProjectId: EntityId): Outcome<Project> = unavailable("C-NATIVE")
        }
        val transport = object : Transport {
            override val state = MutableStateFlow(TransportState(TransportStatus.STOPPED, reason = null))
            override suspend fun prepare(project: Project, settings: PracticeSettings): Outcome<Unit> = Outcome.Success(Unit)
            override suspend fun play(): Outcome<Unit> = Outcome.Success(Unit)
            override suspend fun pause(): Outcome<Unit> = Outcome.Success(Unit)
            override suspend fun seek(position: PlaybackOccurrence): Outcome<Unit> = Outcome.Success(Unit)
            override suspend fun setLoop(range: LoopRange?): Outcome<Unit> = Outcome.Success(Unit)
            override suspend fun stop(): Outcome<Unit> = Outcome.Success(Unit)
        }
        val vm = WorkspaceViewModel(project.id, repo, snapshots, exchange, settings, transport, ids, WorkspaceServices(crashReports = Reports()))
        store.put("vm", vm)
        compose.setContent { RiffTheme { WorkspaceScreen(vm, onBack = {}, onExport = {}, onSettings = {}, initialPage = WorkspacePage.DIAGNOSTICS) } }
        compose.waitUntil(20000) { vm.state.value.editor != null }
        compose.waitForIdle()
        compose.onNodeWithTag("crash-reports").assertExists()
        compose.onNodeWithTag("crash-entry-0").assert(hasText("ArrayIndexOutOfBoundsException", substring = true))
        // The project sizes for a crash report (no content): 1 bar, 3 tracks, grid of the entry value; the page.
        assertEquals(1, CrashContext.bars)
        assertEquals(3, CrashContext.tracks)
        assertEquals("diagnostics", CrashContext.page)
    }
}
