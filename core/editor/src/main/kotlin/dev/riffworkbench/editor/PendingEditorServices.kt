package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*

class PendingEditorServices : AdvancedEditorService, ChordLibrary, ScaleLibrary {
    override suspend fun propose(project: Project, operation: AdvancedEdit): Outcome<EditProposal> = unavailable(when (operation) {
        is AdvancedEdit.Retune -> "C-INSTRUMENT"
        is AdvancedEdit.ApplyArticulation -> "C-ARTICULATION"
        else -> "C-ARRANGEMENT"
    })
    override suspend fun search(query: String, instrument: InstrumentDefinition): Outcome<List<ChordDefinition>> = unavailable("C-INSTRUMENT")
    override suspend fun search(query: String): Outcome<List<ScaleDefinition>> = unavailable("C-INSTRUMENT")
}

class DefaultCommandCatalog : CommandCatalog {
    private val commands = listOf(CommandDescriptor("history.undo", "Undo", "Ctrl+Z", false), CommandDescriptor("history.redo", "Redo", "Ctrl+Shift+Z", false), CommandDescriptor("project.save", "Save", "Ctrl+S", false), CommandDescriptor("note.enter", "Enter note", "Enter", true), CommandDescriptor("note.rest", "Rest", null, true), CommandDescriptor("note.delete", "Delete", "Backspace", true))
    override fun search(query: String) = commands.filter { it.id.contains(query, true) || it.label.contains(query, true) }
}
