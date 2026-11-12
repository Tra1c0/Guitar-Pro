package dev.riffworkbench.ports

import dev.riffworkbench.model.AppSettings
import dev.riffworkbench.model.FailureCode
import dev.riffworkbench.model.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Zyklus 27: a newer build of this app published as a GitHub release (tag „v<versionCode>“, asset „ArtiFret-<versionName>.apk“).
 * [sha256] is the asset digest GitHub reports (lower-case hex), null when the API gives none.
 */
data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String, val sizeBytes: Long, val sha256: String? = null)

/**
 * Zyklus 27: asks the release channel for the newest build. Success(null) = nothing newer (or no installable release);
 * Failure = offline, rate-limited or an unreadable answer. Sends no user data.
 */
interface UpdateChecker {
    suspend fun check(): Outcome<UpdateInfo?>
}

/** What [UpdateInstaller.install] reached. The system always asks the user before it installs. */
enum class UpdateInstallStep {
    /** The system installer shows its confirmation (or has been handed the package). */
    CONFIRMATION_REQUESTED,
    /** „Unbekannte Apps installieren“ is off for this app: its system page was opened; install again after allowing it. */
    PERMISSION_NEEDED,
}

/** Zyklus 27: downloads [UpdateInfo.apkUrl] (progress: bytes so far, total), checks it and hands it to the system installer. */
interface UpdateInstaller {
    suspend fun install(update: UpdateInfo, progress: (downloaded: Long, total: Long) -> Unit): Outcome<UpdateInstallStep>
}

/** Zyklus 27: the release channel, tag and answer parsing, plausibility and the throttle of the automatic check. */
object Updates {
    const val OWNER_REPO = "Tra1c0/Guitar-Pro"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    /** Only assets of this repository's releases are downloaded (GitHub redirects them to its own storage). */
    const val DOWNLOAD_PREFIX = "https://github.com/$OWNER_REPO/releases/download/"
    const val USER_AGENT = "ArtiFret-Updater"
    /** Automatic check at most this often. */
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    /** Larger assets are not plausible for this app (APK today ≈ 60 MB). */
    const val MAX_APK_BYTES = 300L * 1024 * 1024
    private const val MAX_NOTES = 4000

    private val tagPattern = Regex("""^v([0-9]{1,9})$""")
    private val assetPattern = Regex("""^ArtiFret-(.+)\.apk$""", RegexOption.IGNORE_CASE)
    private val hexPattern = Regex("^[0-9a-f]{64}$")
    private val json = Json { ignoreUnknownKeys = true }

    /** „v1042“ → 1042; anything else (other tag schemes, „v“, leading text, overflow) → null. */
    fun versionCodeOfTag(tag: String): Int? = tagPattern.matchEntire(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun isNewer(candidate: Int, installed: Int): Boolean = candidate > installed

    /** Due when switched on and the last start of a check is at least [CHECK_INTERVAL_MS] ago (or in the future: clock changed). */
    fun dueForAutomaticCheck(settings: AppSettings, nowEpochMs: Long): Boolean {
        if (!settings.updateChecks) return false
        val last = settings.lastUpdateCheckEpochMs
        return last <= 0L || nowEpochMs < last || nowEpochMs - last >= CHECK_INTERVAL_MS
    }

    /**
     * Parses the answer of `GET /repos/{owner}/{repo}/releases/latest`. Success(null) when the release is a draft/prerelease,
     * has a tag outside the „v<code>“ scheme, is not newer than [installedVersionCode] or carries no plausible APK asset.
     */
    fun parseLatestRelease(body: String, installedVersionCode: Int): Outcome<UpdateInfo?> {
        val root = try { json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null }
            ?: return Outcome.Failure(FailureCode.CORRUPT, "Antwort von GitHub unlesbar")
        if (root.bool("draft") == true || root.bool("prerelease") == true) return Outcome.Success(null)
        val code = root.string("tag_name")?.let(::versionCodeOfTag) ?: return Outcome.Success(null)
        if (!isNewer(code, installedVersionCode)) return Outcome.Success(null)
        val assets = (root["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val apk = assets.firstOrNull { asset ->
            val name = asset.string("name").orEmpty()
            name.endsWith(".apk", ignoreCase = true) && (asset.string("state") ?: "uploaded") == "uploaded"
        } ?: return Outcome.Success(null)
        val url = apk.string("browser_download_url") ?: return Outcome.Success(null)
        val size = apk.long("size") ?: 0L
        if (!url.startsWith(DOWNLOAD_PREFIX) || size <= 0L || size > MAX_APK_BYTES) return Outcome.Success(null)
        val name = assetPattern.matchEntire(apk.string("name").orEmpty())?.groupValues?.get(1)
            ?: root.string("name")?.removePrefix("ArtiFret")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "v$code"
        val digest = apk.string("digest")?.lowercase()?.removePrefix("sha256:")?.takeIf { hexPattern.matches(it) }
        val notes = root.string("body").orEmpty().trim().let { if (it.length > MAX_NOTES) it.take(MAX_NOTES) + " …" else it }
        return Outcome.Success(UpdateInfo(code, name, url, notes, size, digest))
    }

    /** Size and (when known) hash of a downloaded file against the release data. */
    fun plausibleDownload(update: UpdateInfo, sizeBytes: Long, sha256Hex: String?): Boolean =
        sizeBytes == update.sizeBytes && (update.sha256 == null || update.sha256 == sha256Hex?.lowercase())

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}

/** Phase of the update flow shown in Settings → „Updates“ (Zyklus 27). */
enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, FAILED, DOWNLOADING, PERMISSION_NEEDED, CONFIRMING }

data class UpdateState(
    val installedVersionCode: Int,
    val installedVersionName: String,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    /** Newest installable build, once a check found one. */
    val available: UpdateInfo? = null,
    /** Reason of [UpdatePhase.FAILED]. */
    val message: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** Found by the automatic check at start: the app shows „Update <version> verfügbar“ once. */
    val announcement: UpdateInfo? = null,
)

/**
 * Zyklus 27: the update flow of the app (one instance in the composition root, shared by the start check and the settings).
 * The automatic check runs at most every 6 h when [AppSettings.updateChecks] is on and stays silent unless it finds a newer
 * build (offline, up to date and errors show nothing); the manual check reports every result. Only one check or install
 * runs at a time. [automatic] = false disables the start check altogether (Robolectric runs of the real app: no network,
 * no settings write behind the test's back).
 */
class UpdateCoordinator(
    private val settings: SettingsRepository,
    private val checker: UpdateChecker,
    private val installer: UpdateInstaller,
    installedVersionCode: Int,
    installedVersionName: String,
    private val scope: CoroutineScope,
    private val automatic: Boolean = true,
    private val clock: () -> Long,
) {
    private val mutable = MutableStateFlow(UpdateState(installedVersionCode, installedVersionName))
    val state: StateFlow<UpdateState> = mutable.asStateFlow()
    private val busy = Mutex()

    /** Automatic check at app start: only when switched on and due; records the attempt before it runs. */
    fun checkOnStart(): Job = scope.launch {
        if (!automatic) return@launch
        val now = clock()
        if (!Updates.dueForAutomaticCheck(settings.settings.value, now)) return@launch
        if (!busy.tryLock()) return@launch
        try {
            settings.update(settings.settings.value.copy(lastUpdateCheckEpochMs = now))
            when (val r = checker.check()) {
                is Outcome.Success -> r.value?.let { found -> mutable.update { it.copy(phase = UpdatePhase.AVAILABLE, available = found, message = null, announcement = found) } }
                    ?: mutable.update { it.copy(phase = UpdatePhase.UP_TO_DATE, available = null, message = null) }
                // Silent: offline or GitHub unreachable is no news at start.
                is Outcome.Failure -> Unit
            }
        } finally { busy.unlock() }
    }

    /** „Jetzt prüfen“: reports up to date, newer build or the failure. */
    fun checkNow(): Job = scope.launch {
        if (!busy.tryLock()) return@launch
        try {
            mutable.update { it.copy(phase = UpdatePhase.CHECKING, message = null) }
            when (val r = checker.check()) {
                is Outcome.Success -> mutable.update { s ->
                    val found = r.value
                    if (found == null) s.copy(phase = UpdatePhase.UP_TO_DATE, available = null) else s.copy(phase = UpdatePhase.AVAILABLE, available = found)
                }
                is Outcome.Failure -> mutable.update { it.copy(phase = UpdatePhase.FAILED, message = r.message) }
            }
        } finally { busy.unlock() }
    }

    /** „Installieren“: download (with progress), checks, then the system's confirmation (or its permission page first). */
    fun install(): Job = scope.launch {
        val update = mutable.value.available ?: return@launch
        if (!busy.tryLock()) return@launch
        try {
            mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, message = null, downloadedBytes = 0L, totalBytes = update.sizeBytes, announcement = null) }
            val r = installer.install(update) { done, total -> mutable.update { it.copy(downloadedBytes = done, totalBytes = if (total > 0) total else it.totalBytes) } }
            mutable.update { s ->
                when (r) {
                    is Outcome.Success -> s.copy(phase = if (r.value == UpdateInstallStep.PERMISSION_NEEDED) UpdatePhase.PERMISSION_NEEDED else UpdatePhase.CONFIRMING)
                    is Outcome.Failure -> s.copy(phase = UpdatePhase.FAILED, message = r.message)
                }
            }
        } finally { busy.unlock() }
    }

    /** The start notice was shown or dismissed. */
    fun dismissAnnouncement() { mutable.update { it.copy(announcement = null) } }
}
