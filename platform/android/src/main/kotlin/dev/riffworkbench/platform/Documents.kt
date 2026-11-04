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
    suspend fun write(uri: Uri, bytes: ByteArray): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                ?: return@withContext Outcome.Failure(FailureCode.IO, "Ausgabedatei konnte nicht geöffnet werden")
            Outcome.Success(Unit)
        } catch (e: SecurityException) { Outcome.Failure(FailureCode.PERMISSION, e.message ?: "Dateizugriff verweigert") }
        catch (e: IOException) { Outcome.Failure(FailureCode.IO, e.message ?: "Schreiben fehlgeschlagen") }
    }
}
