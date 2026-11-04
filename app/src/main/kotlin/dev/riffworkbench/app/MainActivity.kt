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
    var pendingExport by remember { mutableStateOf<ExportResult?>(null) }
    var pendingStream by remember { mutableStateOf<Pair<ExportTarget, WorkspaceViewModel>?>(null) }
    var pendingPdf by remember { mutableStateOf<WorkspaceViewModel?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val result = pendingExport; pendingExport = null
        if (uri != null && result != null) scope.launch { when (val write = container.documents.write(uri, result.bytes)) {
            is Outcome.Failure -> notice = write.message
            is Outcome.Success -> notice = context.getString(dev.riffworkbench.ui.components.R.string.export_success)
        } }
        else if (uri != null) notice = "Export nach Prozessneustart bitte erneut starten."
    }
    val streamLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val job = pendingStream; pendingStream = null
        if (uri != null && job != null) scope.launch {
            when (val r = container.documents.stream(uri) { sink -> job.second.renderAudio(job.first, sink) }) {
                is Outcome.Failure -> notice = r.message
                is Outcome.Success -> notice = context.getString(dev.riffworkbench.ui.components.R.string.export_success)
            }
        }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val vm = pendingPdf; pendingPdf = null
        val project = vm?.currentProject()
        if (uri != null && project != null) scope.launch {
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
            notice = if (r is Outcome.Failure) r.message else context.getString(dev.riffworkbench.ui.components.R.string.export_success)
        }
    }
    LaunchedEffect(container) { when (val result = container.settings.load()) { is Outcome.Failure -> notice = result.message; is Outcome.Success -> Unit } }
    RiffTheme(settings.darkTheme) { Surface(Modifier.fillMaxSize()) { Column {
        notice?.let { message -> Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Text(message, Modifier.weight(1f).padding(12.dp))
            TextButton(onClick = { notice = null }) { Text("×") }
        } } }
        NavHost(navController = nav, startDestination = AppRoutes.LIBRARY, modifier = Modifier.weight(1f)) {
            composable(AppRoutes.LIBRARY) {
                val vm: LibraryViewModel = viewModel(factory = factory { LibraryViewModel(container.projects, container.nativeExchange, container.ids, container.formats, container.assets) })
                val open: (EntityId) -> Unit = { nav.navigate(AppRoutes.project(it)) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
                    when (val read = container.documents.read(uri)) { is Outcome.Failure -> vm.showError(read.message); is Outcome.Success -> vm.importFile(read.value, container.documents.displayName(uri), open) }
                } }
                LibraryScreen(vm, onOpen = open, onImport = { launcher.launch(arrayOf("*/*")) }, onSettings = { nav.navigate(AppRoutes.SETTINGS) })
            }
            composable(AppRoutes.PROJECT, arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { entry ->
                val id = EntityId(requireNotNull(entry.arguments?.getString("projectId")))
                val vm: WorkspaceViewModel = viewModel(factory = factory { WorkspaceViewModel(id, container.projects, container.projects, container.nativeExchange, container.settings, container.transport, container.ids, container.workspaceServices) })
                val leave: () -> Unit = { scope.launch { container.transport.stop(); when (val result = vm.flush()) { is Outcome.Failure -> vm.showNotice(result.message); is Outcome.Success -> nav.popBackStack() } }; Unit }
                BackHandler(onBack = leave)
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle, vm) {
                    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) scope.launch { container.transport.pause(); vm.flush() } }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                val actions = remember(vm) { ExportActions(
                    bytes = { result -> if (pendingExport == null) { pendingExport = result; exportLauncher.launch(result.fileName) } },
                    stream = { target -> if (pendingStream == null) { pendingStream = target to vm; streamLauncher.launch(vm.suggestedName(target)) } },
                    pdf = { if (pendingPdf == null) { pendingPdf = vm; pdfLauncher.launch(vm.suggestedName(ExportTarget.PDF)) } },
                ) }
                WorkspaceScreen(vm, onBack = leave, onExport = actions.bytes, onSettings = { nav.navigate(AppRoutes.SETTINGS) }, exportActions = actions)
            }
            composable(AppRoutes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(container.settings) })
                SettingsScreen(vm, onBack = { nav.popBackStack() })
            }
        }
    } } }
}

private fun factory(creator: () -> ViewModel) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = creator().also { require(modelClass.isInstance(it)) } as T
}
