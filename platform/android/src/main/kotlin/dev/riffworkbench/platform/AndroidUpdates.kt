package dev.riffworkbench.platform

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dev.riffworkbench.model.FailureCode
import dev.riffworkbench.model.Outcome
import dev.riffworkbench.ports.UpdateChecker
import dev.riffworkbench.ports.UpdateInfo
import dev.riffworkbench.ports.UpdateInstallStep
import dev.riffworkbench.ports.UpdateInstaller
import dev.riffworkbench.ports.Updates
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TIMEOUT_MS = 10_000

/**
 * Zyklus 27: asks GitHub for the newest release of this app (`GET /repos/Tra1c0/Guitar-Pro/releases/latest`). Anonymous (no
 * token, no cookies), User-Agent „ArtiFret-Updater“, 10 s timeouts. Nothing about the user or the device is sent; GitHub sees
 * the IP address and the request like for any web page.
 */
class GitHubUpdateChecker(private val installedVersionCode: Int, private val endpoint: String = Updates.LATEST_RELEASE_URL) : UpdateChecker {
    override suspend fun check(): Outcome<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS; useCaches = false; instanceFollowRedirects = true
                setRequestProperty("User-Agent", Updates.USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val body = connection.inputStream.use { input -> readLimited(input, 1 shl 20) } ?: return@withContext Outcome.Failure(FailureCode.RESOURCE_LIMIT, "Antwort von GitHub zu groß")
                        Updates.parseLatestRelease(body, installedVersionCode)
                    }
                    // No release published yet.
                    HttpURLConnection.HTTP_NOT_FOUND -> Outcome.Success(null)
                    HttpURLConnection.HTTP_FORBIDDEN, 429 -> Outcome.Failure(FailureCode.UNAVAILABLE, "GitHub lehnt gerade ab (Anfragelimit) – später erneut prüfen")
                    else -> Outcome.Failure(FailureCode.IO, "GitHub antwortet mit HTTP $code")
                }
            } finally { connection.disconnect() }
        } catch (e: UnknownHostException) { Outcome.Failure(FailureCode.IO, "Keine Verbindung zu GitHub (offline?)") }
        catch (e: SocketTimeoutException) { Outcome.Failure(FailureCode.IO, "GitHub antwortet nicht (Zeitüberschreitung)") }
        catch (e: IOException) { Outcome.Failure(FailureCode.IO, "Update-Prüfung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
        catch (e: SecurityException) { Outcome.Failure(FailureCode.PERMISSION, "Keine Netzwerkberechtigung") }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

/** Stands in for the network checker where no request may go out (Robolectric runs of the real app). */
class OfflineUpdateChecker(private val reason: String = "Update-Prüfung in dieser Umgebung abgeschaltet") : UpdateChecker {
    override suspend fun check(): Outcome<UpdateInfo?> = Outcome.Failure(FailureCode.UNAVAILABLE, reason)
}

/**
 * Zyklus 27: downloads the release APK into the app cache (`cache/updates`), checks size, SHA-256 (when GitHub reports a
 * digest), package name and version code, and hands it to the system installer via a PackageInstaller session (fallback:
 * FileProvider + ACTION_VIEW). Android always asks the user to confirm; without „Unbekannte Apps installieren“ for this app
 * its system page is opened first.
 */
class AndroidUpdateInstaller(context: Context) : UpdateInstaller {
    private val app = context.applicationContext
    private val dir get() = File(app.cacheDir, "updates")

    override suspend fun install(update: UpdateInfo, progress: (downloaded: Long, total: Long) -> Unit): Outcome<UpdateInstallStep> {
        val apk = withContext(Dispatchers.IO) { obtain(update, progress) }
        val file = when (apk) { is Outcome.Failure -> return apk; is Outcome.Success -> apk.value }
        withContext(Dispatchers.IO) { verifyArchive(update, file) }?.let { return it }
        if (!app.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return withContext(Dispatchers.Main) {
                try { app.startActivity(settings); Outcome.Success(UpdateInstallStep.PERMISSION_NEEDED) }
                catch (e: Exception) { Outcome.Failure(FailureCode.PERMISSION, "Bitte unter Einstellungen → Apps → ArtiFret „Unbekannte Apps installieren“ erlauben") }
            }
        }
        return try {
            withContext(Dispatchers.IO) { commitSession(file) }
            Outcome.Success(UpdateInstallStep.CONFIRMATION_REQUESTED)
        } catch (e: Exception) {
            // Some systems refuse sessions from apps; the classic installer intent still works there.
            withContext(Dispatchers.Main) {
                try {
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                    app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    Outcome.Success(UpdateInstallStep.CONFIRMATION_REQUESTED)
                } catch (fallback: Exception) {
                    Outcome.Failure(FailureCode.IO, "Installation konnte nicht gestartet werden: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Removes downloads of builds that are installed or older (called at app start). */
    fun discardUpTo(installedVersionCode: Int) {
        dir.listFiles()?.forEach { f -> val code = f.name.removePrefix("update-").substringBefore('.').toIntOrNull(); if (code == null || code <= installedVersionCode) f.delete() }
    }

    /** The verified APK of [update], from an earlier download (permission round trip) or freshly downloaded. */
    private suspend fun obtain(update: UpdateInfo, progress: (Long, Long) -> Unit): Outcome<File> {
        val target = File(dir, "update-${update.versionCode}.apk")
        if (target.isFile && Updates.plausibleDownload(update, target.length(), sha256(target))) { progress(target.length(), target.length()); return Outcome.Success(target) }
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "update-${update.versionCode}.part")
        try {
            val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS; useCaches = false; instanceFollowRedirects = true
                setRequestProperty("User-Agent", Updates.USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return Outcome.Failure(FailureCode.IO, "Download fehlgeschlagen (HTTP ${connection.responseCode})")
                // GitHub must redirect only to https storage.
                if (connection.url.protocol != "https") return Outcome.Failure(FailureCode.IO, "Download nicht über HTTPS")
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                connection.inputStream.use { input -> part.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > update.sizeBytes) return Outcome.Failure(FailureCode.CORRUPT, "Download größer als angekündigt – verworfen")
                        out.write(buffer, 0, n); digest.update(buffer, 0, n)
                        if (done - lastReport >= 256 * 1024) { lastReport = done; progress(done, update.sizeBytes) }
                    }
                    out.fd.sync()
                } }
                progress(done, update.sizeBytes)
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!Updates.plausibleDownload(update, done, hex)) return Outcome.Failure(FailureCode.CORRUPT, "Download beschädigt (Größe/Prüfsumme) – bitte erneut versuchen")
                if (!part.renameTo(target)) return Outcome.Failure(FailureCode.IO, "Download konnte nicht gespeichert werden")
                return Outcome.Success(target)
            } finally { connection.disconnect() }
        } catch (e: UnknownHostException) { return Outcome.Failure(FailureCode.IO, "Keine Verbindung zu GitHub (offline?)") }
        catch (e: SocketTimeoutException) { return Outcome.Failure(FailureCode.IO, "Download abgebrochen (Zeitüberschreitung)") }
        catch (e: IOException) { return Outcome.Failure(FailureCode.IO, "Download fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
        finally { if (part.exists()) part.delete() }
    }

    /** The archive must be this app in exactly the announced version (the system checks the signature itself). */
    private fun verifyArchive(update: UpdateInfo, file: File): Outcome.Failure? {
        @Suppress("DEPRECATION") val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: return Outcome.Failure(FailureCode.CORRUPT, "Download ist keine gültige APK").also { file.delete() }
        if (info.packageName != app.packageName || PackageInfoCompat.getLongVersionCode(info) != update.versionCode.toLong()) {
            file.delete()
            return Outcome.Failure(FailureCode.CORRUPT, "Download passt nicht zu ArtiFret ${update.versionName} – verworfen")
        }
        return null
    }

    @SuppressLint("InlinedApi")
    private fun commitSession(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input -> session.openWrite("base.apk", 0, file.length()).use { out -> input.copyTo(out, 1 shl 16); session.fsync(out) } }
                val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION)
                // The system adds the status extras, so the PendingIntent must be mutable (explicit intent: allowed on API 34+).
                val pending = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) { runCatching { installer.abandonSession(id) }; throw e }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(1 shl 16); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Zyklus 27: status of the PackageInstaller session. „Pending user action“ opens the system confirmation (the app is in the
 * foreground: the user just tapped „Installieren“); a failure other than the user's „Abbrechen“ is shown as a toast.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val text = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Update nicht installierbar: andere Signatur. Einmalig: Projekte als .riffproj exportieren, ArtiFret deinstallieren, neue APK installieren."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Update nicht installierbar: zu wenig Speicher"
                    else -> "Update nicht installiert" + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { ": $it" } ?: "")
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object { const val ACTION = "dev.riffworkbench.platform.UPDATE_INSTALL_STATUS" }
}
