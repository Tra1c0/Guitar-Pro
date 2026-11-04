package dev.riffworkbench.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.riffworkbench.editor.ProjectFactory
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: ProjectRepository, private val exchange: NativeProjectExchange, private val ids: IdSource) : ViewModel() {
    val projects = repository.projects
    val issues = repository.issues
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
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
    fun importNative(bytes: ByteArray, onOpen: (EntityId) -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            when (val result = exchange.import(bytes, ids.next())) {
                is Outcome.Failure -> mutableError.value = result.message
                is Outcome.Success -> when (val saved = repository.save(result.value)) {
                    is Outcome.Failure -> mutableError.value = saved.message
                    is Outcome.Success -> onOpen(result.value.id)
                }
            }
            mutableBusy.value = false
        }
    }
    fun showError(message: String?) { mutableError.value = message }
    private fun report(result: Outcome<Unit>) { if (result is Outcome.Failure) mutableError.value = result.message }
}
