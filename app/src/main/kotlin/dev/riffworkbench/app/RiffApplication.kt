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
import dev.riffworkbench.ports.RouteNoticeMemory
import dev.riffworkbench.synth.DrumKit
import dev.riffworkbench.synth.SampleBank
import dev.riffworkbench.platform.AndroidAudioRouteMonitor
import dev.riffworkbench.platform.AndroidPcmOutput
import dev.riffworkbench.platform.AndroidSystemProbes
import dev.riffworkbench.platform.AudioRecordSource
import dev.riffworkbench.platform.AudioTrackSink
import dev.riffworkbench.platform.Documents
import dev.riffworkbench.platform.MediaCodecAudioDecoder
import dev.riffworkbench.platform.PlaybackGuard
import dev.riffworkbench.platform.audioThreadStarter
import dev.riffworkbench.platform.AndroidUpdateInstaller
import dev.riffworkbench.platform.GitHubUpdateChecker
import dev.riffworkbench.platform.OfflineUpdateChecker
import dev.riffworkbench.ports.UpdateCoordinator
import android.os.Build
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PendingExports {
    var bytes: dev.riffworkbench.model.ExportResult? = null
    var stream: Pair<dev.riffworkbench.feature.workspace.ExportTarget, dev.riffworkbench.feature.workspace.WorkspaceViewModel>? = null
    var pdf: dev.riffworkbench.feature.workspace.WorkspaceViewModel? = null
    /** Launcher of the current composition; an export finishing after a rotation must not use a disposed one. */
    var launchBytes: ((String) -> Unit)? = null
    /** An in-memory export finished while no composition was attached; launched by the next one. */
    var bytesQueued = false
}

open class RiffApplication : Application() {
    lateinit var container: AppContainer; private set
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}

/** Only the composition root knows concrete implementations. All offline services share one private root. */
class AppContainer(application: Application) {
    private val root = File(application.filesDir, "workbench")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Outlives activity recreation (rotation, dark mode): long document writes must not be cancelled with the UI. */
    val backgroundScope: CoroutineScope get() = scope
    /** Export jobs waiting for the system file picker; kept here so a rotation while the picker is open does not drop them. */
    val pendingExports = PendingExports()
    val ids = IdSource { EntityId(UUID.randomUUID().toString()) }
    val projects = FileProjectRepository(root)
    val assets = FileAssetStore(File(root, "assets"))
    val settings = FileSettingsRepository(File(root, "settings.json"))
    val nativeExchange = FileNativeProjectExchange(assets)
    val documents = Documents(application)
    /** Own synthesizer on the device's native rate; the AudioTrack sample clock drives the transport. */
    /** Electric-guitar DI samples (Karoryfer Emilyguitar, CC0), loaded in the background at start. */
    @Volatile var guitarBank: SampleBank? = null; private set
    /**
     * Sampled drum kit "ArtiFret Pearl Kit" (derived from AVL Black Pearl 4 by Glen MacArthur, CC BY-SA 3.0; licence text in
     * assets/licenses/avl-drumkits-README.txt), loaded in the background at start; until then the synthetic kit plays.
     */
    @Volatile var drumKit: DrumKit? = null; private set
    /** Zyklus 26: the output device (routed device of the running track, else the connected outputs) for per-device latency. */
    val audioRoutes = AndroidAudioRouteMonitor(application)
    /** „<Gerät>: Latenz nicht kalibriert“ once per device and app process, across workspaces. */
    val routeNotices = RouteNoticeMemory()
    val audioHost = RealtimeHost({ AudioTrackSink(application, audioRoutes) }, audioThreadStarter, bankProvider = { guitarBank }, drumKitProvider = { drumKit })
    /** Reference audio: WAV in Kotlin, everything else through the device's MediaCodec decoders. */
    val audioDecoder = AudioDecoders(MediaCodecAudioDecoder())
    val transport = EngineTransport(audioHost, clipLoader = DecodingClipLoader(assets, audioDecoder), bankProvider = { guitarBank }, drumKitProvider = { drumKit })
    val audioLab = EngineAudioLab(transport, { guitarBank }, AndroidSystemProbes(application), AndroidPcmOutput())
    val tuner = InputTuner({ AudioRecordSource(application) }, audioThreadStarter)
    val notation = AlphaTabBoundary()
    val formats = FormatRegistry.catalog()
    val integrations = IntegrationCatalog.entries
    val workspaceServices = WorkspaceServices(preview = transport, renderer = transport, formats = formats, tuner = tuner, assets = assets, audioDecoder = audioDecoder,
        waveforms = StreamingWaveforms(assets, audioDecoder), audioLab = audioLab, audioRoutes = audioRoutes, routeNotices = routeNotices)
    private val guard = PlaybackGuard(application) { scope.launch { transport.pause() } }
    /** Zyklus 27: downloads a release APK into cache/updates and hands it to the system installer. */
    private val updateInstaller = AndroidUpdateInstaller(application)
    /**
     * Zyklus 27: the only network use of the app — GitHub's latest release of this app, automatically at most every 6 h
     * (switchable) or on „Jetzt prüfen“. Robolectric runs of the real app must not reach the network: offline checker and
     * no start check there.
     */
    private val robolectric = Build.FINGERPRINT == "robolectric"
    val updates = UpdateCoordinator(settings, if (robolectric) OfflineUpdateChecker() else GitHubUpdateChecker(BuildConfig.VERSION_CODE),
        updateInstaller, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, scope, automatic = !robolectric) { System.currentTimeMillis() }

    init {
        scope.launch(Dispatchers.IO) {
            guitarBank = runCatching { application.assets.open("banks/guitar-emily-di.rwsb").buffered(1 shl 16).use { input -> SampleBank.read { b, o, l -> input.read(b, o, l) } } }.getOrNull()
        }
        scope.launch(Dispatchers.IO) {
            drumKit = runCatching { application.assets.open("banks/artifret-pearl-kit.rwdk").buffered(1 shl 16).use { input -> DrumKit.read { b, o, l -> input.read(b, o, l) } } }.getOrNull()
        }
        scope.launch(Dispatchers.IO) { runCatching { updateInstaller.discardUpTo(BuildConfig.VERSION_CODE) } }
        scope.launch {
            transport.state.collect { s -> if (s.status == TransportStatus.PLAYING) guard.acquire() else guard.release() }
        }
    }
}
