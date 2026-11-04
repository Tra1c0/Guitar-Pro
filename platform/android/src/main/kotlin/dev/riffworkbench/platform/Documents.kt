package dev.riffworkbench.platform

import android.content.Context
import android.net.Uri
import dev.riffworkbench.model.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SAF only: no broad storage permission, file paths, or external URI persistence in the project model. */
class Documents(private val context: Context) {
    /** Display name offered by the provider (for format tie-breaks and the stored original's name). */
    fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }
    suspend fun read(uri: Uri, maxBytes: Long = 64L * 1024 * 1024): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0L
                while (true) { val count = stream.read(buffer); if (count < 0) break; total += count
                    if (total > maxBytes) return@withContext Outcome.Failure(FailureCode.RESOURCE_LIMIT, "Datei ist zu groß")
                    output.write(buffer, 0, count)
                }; output.toByteArray()
            } ?: return@withContext Outcome.Failure(FailureCode.IO, "Datei konnte nicht geöffnet werden")
            Outcome.Success(bytes)
        } catch (e: SecurityException) { Outcome.Failure(FailureCode.PERMISSION, e.message ?: "Dateizugriff verweigert") }
        catch (e: IOException) { Outcome.Failure(FailureCode.IO, e.message ?: "Lesen fehlgeschlagen") }
    }
    /** Streams a large export (rendered audio) straight into the provider without buffering it in memory. */
    suspend fun stream(uri: Uri, writer: suspend (ByteSink) -> Outcome<*>): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext Outcome.Failure(FailureCode.IO, "Ausgabedatei konnte nicht geöffnet werden")
            stream.buffered(256 * 1024).use { out ->
                when (val r = writer(ByteSink { b, o, l -> out.write(b, o, l) })) {
                    is Outcome.Failure -> return@withContext r
                    is Outcome.Success -> Unit
                }
                out.flush()
            }
            Outcome.Success(Unit)
        } catch (e: SecurityException) { Outcome.Failure(FailureCode.PERMISSION, e.message ?: "Dateizugriff verweigert") }
        catch (e: IOException) { Outcome.Failure(FailureCode.IO, e.message ?: "Schreiben fehlgeschlagen") }
    }
    suspend fun write(uri: Uri, bytes: ByteArray): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                ?: return@withContext Outcome.Failure(FailureCode.IO, "Ausgabedatei konnte nicht geöffnet werden")
            Outcome.Success(Unit)
        } catch (e: SecurityException) { Outcome.Failure(FailureCode.PERMISSION, e.message ?: "Dateizugriff verweigert") }
        catch (e: IOException) { Outcome.Failure(FailureCode.IO, e.message ?: "Schreiben fehlgeschlagen") }
    }
}
