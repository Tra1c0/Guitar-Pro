package dev.riffworkbench.adapter.integrations

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import kotlinx.coroutines.flow.emptyFlow

class PendingIntegration(override val id: String, private val licenseBound: Boolean = false) : ExternalIntegration {
    override suspend fun devices(): Outcome<List<ExternalDevice>> = unavailable("C-INTEGRATION", if (licenseBound) "$id benötigt zusätzlich eine technische und vertragliche Freigabe." else "$id wird separat evaluiert.")
    override suspend fun connect(deviceId: String) = unavailable("C-INTEGRATION")
}

object IntegrationCatalog {
    val entries = listOf(PendingIntegration("fretlight", true), PendingIntegration("mysongbook", true), PendingIntegration("vst3"), PendingIntegration("auv3"), PendingIntegration("audio-to-midi"))
}

class PendingTranscription : TranscriptionService {
    override suspend fun transcribe(asset: AssetHash, ids: IdSource, progress: (OperationProgress) -> Unit): Outcome<TranscriptionProposal> = unavailable("C-TRANSCRIBE")
}
class PendingTuner : Tuner {
    override val readings = emptyFlow<TuningReading>()
    override suspend fun start(inputDeviceId: String?) = unavailable("C-TUNER")
    override suspend fun stop() = unavailable("C-TUNER")
}
