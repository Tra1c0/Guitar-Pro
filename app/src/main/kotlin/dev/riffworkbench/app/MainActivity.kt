package dev.riffworkbench.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dev.riffworkbench.feature.library.*
import dev.riffworkbench.feature.settings.*
import dev.riffworkbench.feature.workspace.*
import dev.riffworkbench.model.*
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.score.PdfExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RiffApp((application as RiffApplication).container) }
    }
}

object AppRoutes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val PROJECT = "project/{projectId}"
    fun project(id: EntityId) = "project/${id.value}"
}

@Composable fun RiffApp(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    val pending = container.pendingExports
    var pendingExport by pending::bytes
    var pendingStream by pending::stream
    var pendingPdf by pending::pdf
    val success = context.getString(dev.riffworkbench.ui.components.R.string.export_success)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val result = pendingExport; pendingExport = null
        if (uri != null && result != null) container.backgroundScope.launch { val write = container.documents.write(uri, result.bytes)
            withContext(Dispatchers.Main) { notice = if (write is Outcome.Failure) write.message else success }
        }
        else if (uri != null) notice = "Export nach Prozessneustart bitte erneut starten."
    }
    DisposableEffect(exportLauncher) {
        pending.launchBytes = { name -> exportLauncher.launch(name) }
        if (pending.bytesQueued) { pending.bytesQueued = false; pending.bytes?.let { exportLauncher.launch(it.fileName) } }
        onDispose { pending.launchBytes = null }
    }
    val streamLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val job = pendingStream; pendingStream = null
        // Rendering a long mix takes seconds to minutes: run it outside the composition so a rotation does not truncate the file.
        if (uri != null && job != null) container.backgroundScope.launch {
            val r = container.documents.stream(uri) { sink -> job.second.renderAudio(job.first, sink) }
            withContext(Dispatchers.Main) { notice = if (r is Outcome.Failure) r.message else success }
        }
        else if (uri != null) notice = "Export nach Prozessneustart bitte erneut starten."
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val vm = pendingPdf; pendingPdf = null
        val project = vm?.currentProject()
        if (uri != null && project != null) container.backgroundScope.launch {
            val r = container.documents.stream(uri) { sink ->
                withContext(Dispatchers.Default) {
                    val out = object : java.io.OutputStream() {
                        override fun write(b: Int) { sink.write(byteArrayOf(b.toByte()), 0, 1) }
                        override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len) }
                    }
                    PdfExport.write(project, project.tracks.indices.toList(), out)
                }
                Outcome.Success(Unit)
            }
            withContext(Dispatchers.Main) { notice = if (r is Outcome.Failure) r.message else success }
        }
        else if (uri != null) notice = "Export nach Prozessneustart bitte erneut starten."
    }
    LaunchedEffect(container) { when (val result = container.settings.load()) { is Outcome.Failure -> notice = result.message; is Outcome.Success -> Unit } }
    // The app-level notice (export result, unreadable settings) lies over the screen above the navigation bar and closes
    // itself (QA 23); before, it was a row above the NavHost that pushed every screen down until "×" was tapped.
    RiffTheme(settings.darkTheme) { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = AppRoutes.LIBRARY, modifier = Modifier.fillMaxSize()) {
            composable(AppRoutes.LIBRARY) {
                val vm: LibraryViewModel = viewModel(factory = factory { LibraryViewModel(container.projects, container.nativeExchange, container.ids, container.formats, container.assets) })
                // A double tap during the transition must not stack two sessions of the same project.
                val open: (EntityId) -> Unit = { if (nav.currentBackStackEntry?.destination?.route == AppRoutes.LIBRARY) nav.navigate(AppRoutes.project(it)) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
                    when (val read = container.documents.read(uri)) { is Outcome.Failure -> vm.showError(read.message); is Outcome.Success -> vm.importFile(read.value, container.documents.displayName(uri), open) }
                } }
                LibraryScreen(vm, onOpen = open, onImport = { launcher.launch(arrayOf("*/*")) }, onSettings = { nav.navigate(AppRoutes.SETTINGS) })
            }
            composable(AppRoutes.PROJECT, arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { entry ->
                val id = EntityId(requireNotNull(entry.arguments?.getString("projectId")))
                val vm: WorkspaceViewModel = viewModel(factory = factory { WorkspaceViewModel(id, container.projects, container.projects, container.nativeExchange, container.settings, container.transport, container.ids, container.workspaceServices) })
                // flush() may resume on the thread that released the save mutex (unconfined test dispatchers do that);
                // the NavController must only be touched on the main thread.
                val leave: () -> Unit = { scope.launch { container.transport.stop(); when (val result = vm.flush()) { is Outcome.Failure -> vm.showNotice(result.message); is Outcome.Success -> withContext(Dispatchers.Main.immediate) { if (nav.currentBackStackEntry == entry) nav.popBackStack() } } }; Unit }
                BackHandler(onBack = leave)
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle, vm) {
                    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) scope.launch { container.transport.pause(); vm.flush() } }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
                    when (val read = container.documents.read(uri)) { is Outcome.Failure -> vm.showNotice(read.message); is Outcome.Success -> vm.importAudio(read.value, container.documents.displayName(uri)) }
                } }
                val actions = remember(vm) { ExportActions(
                    bytes = { result -> if (pendingExport == null) { pendingExport = result; pending.launchBytes?.invoke(result.fileName) ?: run { pending.bytesQueued = true } } },
                    stream = { target -> if (pendingStream == null) { pendingStream = target to vm; streamLauncher.launch(vm.suggestedName(target)) } },
                    pdf = { if (pendingPdf == null) { pendingPdf = vm; pdfLauncher.launch(vm.suggestedName(ExportTarget.PDF)) } },
                    importAudio = { audioLauncher.launch(arrayOf("audio/*", "application/ogg")) },
                ) }
                WorkspaceScreen(vm, onBack = leave, onExport = actions.bytes, onSettings = { nav.navigate(AppRoutes.SETTINGS) }, exportActions = actions)
            }
            composable(AppRoutes.SETTINGS) { entry ->
                val vm: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(container.settings) })
                // A second tap during the exit transition must not pop the screen below (library or project without leave()).
                SettingsScreen(vm, onBack = { if (nav.currentBackStackEntry == entry) nav.popBackStack() })
            }
        }
        notice?.let { message ->
            TransientNotice(message, NoticeTiming.millis(message), { if (notice == message) notice = null },
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 88.dp))
        }
    } } }
}

private fun factory(creator: () -> ViewModel) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = creator().also { require(modelClass.isInstance(it)) } as T
}
