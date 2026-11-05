package dev.riffworkbench.data.local

import dev.riffworkbench.model.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal val projectJson = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false; classDiscriminator = "type" }
internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Same-directory temp, fsynced payload, atomic rename. Never falls back to a non-atomic replacement. */
internal fun atomicWrite(file: File, bytes: ByteArray) {
    val parent = requireNotNull(file.parentFile)
    if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Speicherverzeichnis konnte nicht angelegt werden")
    val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
}

internal fun readLimited(file: File, limit: Long = NativeArchiveLimits.MAX_ENTRY): ByteArray {
    require(file.length() <= limit) { "RESOURCE_LIMIT: Datei überschreitet das Größenlimit" }
    return file.inputStream().use { input -> readLimitedStream(input, limit) }
}
internal fun readLimitedStream(input: java.io.InputStream, limit: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "RESOURCE_LIMIT: Dekomprimierte Daten überschreiten das Limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun decodeProject(bytes: ByteArray): Project {
    val text = bytes.toString(Charsets.UTF_8)
    checkJsonDepth(text)
    val tree = projectJson.parseToJsonElement(text).jsonObject
    val version = tree["schemaVersion"]?.jsonPrimitive?.int ?: 1
    require(version == 1) { "INCOMPATIBLE_VERSION: Projektformat $version wird nicht unterstützt" }
    return projectJson.decodeFromJsonElement<Project>(tree).also {
        require(it.tracks.isNotEmpty() && it.measures.isNotEmpty()) { "Leeres Projekt ist ungültig" }
    }
}

internal fun checkJsonDepth(text: String) {
    var depth = 0; var inString = false; var escaped = false
    for (c in text) {
        if (inString) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
        } else when (c) {
            '"' -> inString = true
            '{', '[' -> { depth++; require(depth <= 128) { "RESOURCE_LIMIT: JSON ist zu tief verschachtelt" } }
            '}', ']' -> depth--
        }
    }
}

internal inline fun <T> storageOutcome(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: SerializationException) {
    Outcome.Failure(FailureCode.CORRUPT, e.message ?: "Ungültige Projektdaten")
} catch (e: java.io.EOFException) {
    Outcome.Failure(FailureCode.CORRUPT, e.message ?: "Daten sind abgeschnitten")
} catch (e: java.util.zip.ZipException) {
    Outcome.Failure(FailureCode.CORRUPT, e.message ?: "Beschädigtes Archiv")
} catch (e: IOException) {
    Outcome.Failure(FailureCode.IO, e.message ?: "Dateizugriff fehlgeschlagen")
} catch (e: SecurityException) {
    Outcome.Failure(FailureCode.PERMISSION, e.message ?: "Zugriff verweigert")
} catch (e: IllegalArgumentException) {
    val message = e.message ?: "Ungültige Daten"
    Outcome.Failure(when {
        message.startsWith("INCOMPATIBLE_VERSION:") -> FailureCode.INCOMPATIBLE_VERSION
        message.startsWith("RESOURCE_LIMIT:") -> FailureCode.RESOURCE_LIMIT
        message.startsWith("CONFLICT:") -> FailureCode.CONFLICT
        else -> FailureCode.CORRUPT
    }, message)
}
