package dev.riffworkbench.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.editor.ProjectFactory
import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(
    private val repository: ProjectRepository,
    private val exchange: NativeProjectExchange,
    private val ids: IdSource,
    private val formats: FormatCatalog? = null,
    private val assets: AssetStore? = null,
    /** Parsing runs here; some importers do not switch threads themselves. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    val projects = repository.projects
    val issues = repository.issues
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    init { refresh() }
    fun refresh() { viewModelScope.launch { report(repository.refresh()) } }
    fun create(title: String, onOpen: (EntityId) -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            val project = ProjectFactory(ids).create(title)
            when (val result = repository.save(project)) {
                is Outcome.Failure -> mutableError.value = result.message
                is Outcome.Success -> onOpen(project.id)
            }
            mutableBusy.value = false
        }
    }
    fun importNative(bytes: ByteArray, onOpen: (EntityId) -> Unit) = importFile(bytes, null, onOpen)

    /** Detects the format by content, imports it, keeps the original bytes as an asset and records losses. */
    fun importFile(bytes: ByteArray, fileName: String?, onOpen: (EntityId) -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                val format = formats?.let { catalog -> withContext(background) { catalog.detect(bytes, fileName) } }
                if (format == null || (format is Outcome.Success && format.value.id == "riffproj")) { importNativeNow(bytes, onOpen); return@launch }
                if (format is Outcome.Failure) { mutableError.value = format.message; return@launch }
                val info = (format as Outcome.Success).value
                val importer = formats.importer(info.id) ?: run { mutableError.value = "${info.displayName}-Import ist nicht verfügbar"; return@launch }
                val imported = when (val r = withContext(background) { importer.import(bytes, ids) }) { is Outcome.Failure -> { mutableError.value = r.message; return@launch }; is Outcome.Success -> r.value }
                var project = imported.project.copy(revision = 0)
                val original = assets?.put(fileName?.take(120) ?: "original.${info.extension}", "application/octet-stream", bytes, AssetRights("Vom Nutzer importierte Originaldatei", "unverified", false))
                // One canonical provenance entry per import. Importers may describe the original themselves; the library owns the
                // stored bytes, so it replaces those descriptors. Without stored bytes no descriptor may point at a missing asset.
                val referenced = project.provenance.map { it.originalAsset }.toSet()
                project = if (original is Outcome.Success) {
                    val hash = original.value.hash
                    project.copy(assets = project.assets.filterNot { it.hash == hash || it.hash in referenced } + original.value,
                        provenance = listOf(ImportProvenance(info.id, hash, unknownData = project.provenance.flatMap { it.unknownData }, losses = imported.losses)))
                } else project.copy(assets = project.assets.filterNot { it.hash in referenced }, provenance = emptyList())
                val errors = ScoreValidator.validate(project).filter { it.severity == Severity.ERROR }
                if (errors.isNotEmpty()) { mutableError.value = "Import ergab eine ungültige Partitur: ${errors.first().message}"; return@launch }
                when (val saved = repository.save(project)) {
                    is Outcome.Failure -> mutableError.value = saved.message
                    is Outcome.Success -> {
                        mutableNotice.value = if (imported.losses.isEmpty()) "${info.displayName} importiert" else
                            "${info.displayName} importiert · ${imported.losses.size} Merkmal(e) nicht oder nur angenähert übernommen: " + imported.losses.map { it.featureId }.distinct().take(5).joinToString(", ")
                        onOpen(project.id)
                    }
                }
            } finally { mutableBusy.value = false }
        }
    }

    private suspend fun importNativeNow(bytes: ByteArray, onOpen: (EntityId) -> Unit) {
        when (val result = exchange.import(bytes, ids.next())) {
            is Outcome.Failure -> mutableError.value = result.message
            is Outcome.Success -> when (val saved = repository.save(result.value)) {
                is Outcome.Failure -> mutableError.value = saved.message
                is Outcome.Success -> onOpen(result.value.id)
            }
        }
    }
    fun delete(id: EntityId) { viewModelScope.launch { report(repository.delete(id)) } }
    fun showError(message: String?) { mutableError.value = message }
    fun dismissNotice() { mutableNotice.value = null }
    fun showNotice(message: String?) { mutableNotice.value = message }
    private fun report(result: Outcome<Unit>) { if (result is Outcome.Failure) mutableError.value = result.message }
}
