package dev.riffworkbench.app

import android.app.Application
import dev.riffworkbench.adapter.alphatab.AlphaTabBoundary
import dev.riffworkbench.adapter.audio.PendingAudioServices
import dev.riffworkbench.adapter.formats.*
import dev.riffworkbench.adapter.integrations.IntegrationCatalog
import dev.riffworkbench.data.local.*
import dev.riffworkbench.model.*
import dev.riffworkbench.platform.Documents
import java.io.File
import java.util.UUID

open class RiffApplication : Application() {
    lateinit var container: AppContainer; private set
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}

/** Only composition root knows concrete implementations. All offline services share one private root. */
class AppContainer(application: Application) {
    private val root = File(application.filesDir, "workbench")
    val ids = IdSource { EntityId(UUID.randomUUID().toString()) }
    val projects = FileProjectRepository(root)
    val assets = FileAssetStore(File(root, "assets"))
    val settings = FileSettingsRepository(File(root, "settings.json"))
    val nativeExchange = FileNativeProjectExchange(assets)
    val documents = Documents(application)
    val audio = PendingAudioServices()
    val notation = AlphaTabBoundary()
    val formatDetector = SignatureFormatDetector()
    val formats = ForeignFormats.formats.associate { it.id to PendingFormatAdapter(it.id) }
    val integrations = IntegrationCatalog.entries
}
