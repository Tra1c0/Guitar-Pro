package dev.riffworkbench.app

import android.app.Application
import dev.riffworkbench.adapter.alphatab.AlphaTabBoundary
import dev.riffworkbench.adapter.audio.AudioDecoders
import dev.riffworkbench.adapter.audio.DecodingClipLoader
import dev.riffworkbench.adapter.audio.EngineAudioLab
import dev.riffworkbench.adapter.audio.EngineTransport
import dev.riffworkbench.adapter.audio.InputTuner
import dev.riffworkbench.adapter.audio.RealtimeHost
import dev.riffworkbench.adapter.audio.StreamingWaveforms
import dev.riffworkbench.adapter.formats.*
import dev.riffworkbench.adapter.integrations.IntegrationCatalog
import dev.riffworkbench.data.local.*
import dev.riffworkbench.feature.workspace.WorkspaceServices
import dev.riffworkbench.model.*
import dev.riffworkbench.synth.SampleBank
import dev.riffworkbench.platform.AndroidPcmOutput
import dev.riffworkbench.platform.AndroidSystemProbes
import dev.riffworkbench.platform.AudioRecordSource
import dev.riffworkbench.platform.AudioTrackSink
import dev.riffworkbench.platform.Documents
import dev.riffworkbench.platform.MediaCodecAudioDecoder
import dev.riffworkbench.platform.PlaybackGuard
import dev.riffworkbench.platform.audioThreadStarter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class RiffApplication : Application() {
    lateinit var container: AppContainer; private set
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}

/** Only the composition root knows concrete implementations. All offline services share one private root. */
class AppContainer(application: Application) {
    private val root = File(application.filesDir, "workbench")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ids = IdSource { EntityId(UUID.randomUUID().toString()) }
    val projects = FileProjectRepository(root)
    val assets = FileAssetStore(File(root, "assets"))
    val settings = FileSettingsRepository(File(root, "settings.json"))
    val nativeExchange = FileNativeProjectExchange(assets)
    val documents = Documents(application)
    /** Own synthesizer on the device's native rate; the AudioTrack sample clock drives the transport. */
    /** Electric-guitar DI samples (Karoryfer Emilyguitar, CC0), loaded in the background at start. */
    @Volatile var guitarBank: SampleBank? = null; private set
    val audioHost = RealtimeHost({ AudioTrackSink(application) }, audioThreadStarter, bankProvider = { guitarBank })
    /** Reference audio: WAV in Kotlin, everything else through the device's MediaCodec decoders. */
    val audioDecoder = AudioDecoders(MediaCodecAudioDecoder())
    val transport = EngineTransport(audioHost, clipLoader = DecodingClipLoader(assets, audioDecoder), bankProvider = { guitarBank })
    val audioLab = EngineAudioLab(transport, { guitarBank }, AndroidSystemProbes(application), AndroidPcmOutput())
    val tuner = InputTuner({ AudioRecordSource(application) }, audioThreadStarter)
    val notation = AlphaTabBoundary()
    val formats = FormatRegistry.catalog()
    val integrations = IntegrationCatalog.entries
    val workspaceServices = WorkspaceServices(preview = transport, renderer = transport, formats = formats, tuner = tuner, assets = assets, audioDecoder = audioDecoder,
        waveforms = StreamingWaveforms(assets, audioDecoder), audioLab = audioLab)
    private val guard = PlaybackGuard(application) { scope.launch { transport.pause() } }

    init {
        scope.launch(Dispatchers.IO) {
            guitarBank = runCatching { application.assets.open("banks/guitar-emily-di.rwsb").buffered(1 shl 16).use { input -> SampleBank.read { b, o, l -> input.read(b, o, l) } } }.getOrNull()
        }
        scope.launch {
            transport.state.collect { s -> if (s.status == TransportStatus.PLAYING) guard.acquire() else guard.release() }
        }
    }
}
