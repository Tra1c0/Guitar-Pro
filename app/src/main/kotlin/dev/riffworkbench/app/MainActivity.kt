package dev.riffworkbench.app

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
import kotlinx.coroutines.launch

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
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val result = pendingExport; pendingExport = null
        if (uri != null && result != null) scope.launch { when (val write = container.documents.write(uri, result.bytes)) {
            is Outcome.Failure -> notice = write.message
            is Outcome.Success -> notice = context.getString(dev.riffworkbench.ui.components.R.string.export_success)
        } }
        else if (uri != null) notice = "Export nach Prozessneustart bitte erneut starten."
    }
    LaunchedEffect(container) { when (val result = container.settings.load()) { is Outcome.Failure -> notice = result.message; is Outcome.Success -> Unit } }
    RiffTheme(settings.darkTheme) { Surface(Modifier.fillMaxSize()) { Column {
        notice?.let { message -> Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Text(message, Modifier.weight(1f).padding(12.dp))
            TextButton(onClick = { notice = null }) { Text("×") }
        } } }
        NavHost(navController = nav, startDestination = AppRoutes.LIBRARY, modifier = Modifier.weight(1f)) {
            composable(AppRoutes.LIBRARY) {
                val vm: LibraryViewModel = viewModel(factory = factory { LibraryViewModel(container.projects, container.nativeExchange, container.ids) })
                val open: (EntityId) -> Unit = { nav.navigate(AppRoutes.project(it)) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
                    when (val read = container.documents.read(uri)) { is Outcome.Failure -> vm.showError(read.message); is Outcome.Success -> vm.importNative(read.value, open) }
                } }
                LibraryScreen(vm, onOpen = open, onImport = { launcher.launch(arrayOf("*/*")) }, onSettings = { nav.navigate(AppRoutes.SETTINGS) })
            }
            composable(AppRoutes.PROJECT, arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { entry ->
                val id = EntityId(requireNotNull(entry.arguments?.getString("projectId")))
                val vm: WorkspaceViewModel = viewModel(factory = factory { WorkspaceViewModel(id, container.projects, container.projects, container.nativeExchange, container.settings, container.audio, container.ids) })
                val leave: () -> Unit = { scope.launch { when (val result = vm.flush()) { is Outcome.Failure -> vm.showNotice(result.message); is Outcome.Success -> nav.popBackStack() } }; Unit }
                BackHandler(onBack = leave)
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle, vm) {
                    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) scope.launch { vm.flush() } }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                WorkspaceScreen(vm, onBack = leave, onExport = { result ->
                    if (pendingExport == null) { pendingExport = result; exportLauncher.launch(result.fileName) }
                }, onSettings = { nav.navigate(AppRoutes.SETTINGS) })
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
