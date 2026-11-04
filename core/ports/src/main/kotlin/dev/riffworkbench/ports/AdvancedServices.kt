package dev.riffworkbench.ports

import dev.riffworkbench.model.*
import kotlinx.coroutines.flow.Flow

/** C-ARRANGEMENT: proposal is detached; publish only via one validated Replace command with baseRevision. */
interface AdvancedEditorService { suspend fun propose(project: Project, operation: AdvancedEdit): Outcome<EditProposal> }
interface ChordLibrary { suspend fun search(query: String, instrument: InstrumentDefinition): Outcome<List<ChordDefinition>> }
interface ScaleLibrary { suspend fun search(query: String): Outcome<List<ScaleDefinition>> }
interface Tuner {
    val readings: Flow<TuningReading>
    suspend fun start(inputDeviceId: String?): Outcome<Unit>
    suspend fun stop(): Outcome<Unit>
}
/** C-TRANSCRIBE: suggestions only; caller explicitly accepts into a new editable project. */
interface TranscriptionService { suspend fun transcribe(asset: AssetHash, ids: IdSource, progress: (OperationProgress) -> Unit = {}): Outcome<TranscriptionProposal> }
interface CommandCatalog { fun search(query: String): List<CommandDescriptor> }
