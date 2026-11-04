package dev.riffworkbench.adapter.formats.gpif

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipInputStream

/** Budgets for untrusted .gp archives. Sizes are counted while inflating; declared sizes are never trusted. */
object GpZipLimits {
    const val MAX_INPUT = 64L * 1024 * 1024
    const val MAX_ENTRIES = 512
    const val MAX_ENTRY = 64L * 1024 * 1024
    const val MAX_TOTAL = 256L * 1024 * 1024
}

internal class GpZipContents(
    /** Every entry name in archive order (directories included). */
    val names: List<String>,
    /** Payloads of the entries selected by the reader's filter. */
    val files: Map<String, ByteArray>,
)

/**
 * Sequential ZIP reader over an in-memory copy. Nothing is extracted to disk and no archive path is ever
 * used as a file system path; names are only validated and compared.
 */
internal object GpZipReader {
    fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) || (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()))

    fun validateName(name: String) {
        if (name.isEmpty() || name.length > 512) corrupt("Ungültiger Archiveintrag (leerer oder zu langer Name)")
        if (name.startsWith("/") || '\\' in name || name.split('/').any { it == ".." || it == "." } || ':' in name || name.any { it.code < 0x20 })
            corrupt("Unzulässiger Pfad im Archiv: ${name.take(80)}")
    }

    /**
     * Reads all entries under the running budgets; only entries accepted by [keep] are retained in memory.
     * [stopAfter] ends reading early once that entry was read (used by the detector).
     */
    fun read(bytes: ByteArray, keep: (String) -> Boolean, stopAfter: String? = null, keepLimit: Long = GpZipLimits.MAX_ENTRY): GpZipContents {
        if (bytes.size > GpZipLimits.MAX_INPUT) resourceLimit("Archiv ist größer als 64 MiB")
        if (!isZip(bytes)) corrupt("Keine ZIP-Datei")
        val names = ArrayList<String>()
        val seen = HashSet<String>()
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        val buffer = ByteArray(16 * 1024)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = try { zip.nextEntry } catch (e: IllegalArgumentException) { corrupt("Ungültig kodierter Eintragsname im Archiv") } ?: break
                val name = entry.name
                validateName(name)
                if (!seen.add(name)) corrupt("Doppelter Archiveintrag: ${name.take(80)}")
                if (names.size >= GpZipLimits.MAX_ENTRIES) resourceLimit("Archiv enthält mehr als ${GpZipLimits.MAX_ENTRIES} Einträge")
                names += name
                val retain = !entry.isDirectory && keep(name)
                val out = if (retain) ByteArrayOutputStream() else null
                var entrySize = 0L
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    entrySize += n
                    total += n
                    if (entrySize > GpZipLimits.MAX_ENTRY) resourceLimit("Archiveintrag ${name.take(80)} ist entpackt größer als 64 MiB")
                    if (total > GpZipLimits.MAX_TOTAL) resourceLimit("Archiv ist entpackt größer als 256 MiB")
                    if (out != null) {
                        if (entrySize > keepLimit) resourceLimit("Archiveintrag ${name.take(80)} überschreitet das Leselimit")
                        out.write(buffer, 0, n)
                    }
                }
                if (entry.isDirectory && entrySize > 0) corrupt("Verzeichniseintrag mit Inhalt: ${name.take(80)}")
                if (out != null) files[name] = out.toByteArray()
                zip.closeEntry()
                if (stopAfter != null && name == stopAfter) break
            }
        }
        if (names.isEmpty()) corrupt("Leeres oder beschädigtes ZIP-Archiv")
        return GpZipContents(names, files)
    }
}

/**
 * Minimal deterministic ZIP writer: DEFLATE entries with sizes and CRC in the local header (no data
 * descriptors, no extra fields) and a fixed MS-DOS timestamp of 1980-01-01 00:00, independent of the time zone.
 */
internal class GpZipWriter {
    private class Written(val name: ByteArray, val method: Int, val crc: Long, val compressed: Int, val size: Int, val offset: Int)
    private val out = ByteArrayOutputStream()
    private val entries = ArrayList<Written>()
    private val names = HashSet<String>()

    fun directory(name: String) = add(name, ByteArray(0), store = true)

    fun add(name: String, data: ByteArray, store: Boolean = false) {
        require(names.add(name)) { "Doppelter Eintrag $name" }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val payload = if (store) data else deflate(data)
        val method = if (store) 0 else 8
        val offset = out.size()
        int32(0x04034b50); int16(20); int16(0); int16(method); int16(DOS_TIME); int16(DOS_DATE)
        int32(crc); int32(payload.size.toLong()); int32(data.size.toLong()); int16(nameBytes.size); int16(0)
        out.write(nameBytes); out.write(payload)
        entries += Written(nameBytes, method, crc, payload.size, data.size, offset)
    }

    fun finish(): ByteArray {
        val start = out.size()
        for (e in entries) {
            int32(0x02014b50); int16(20); int16(20); int16(0); int16(e.method); int16(DOS_TIME); int16(DOS_DATE)
            int32(e.crc); int32(e.compressed.toLong()); int32(e.size.toLong()); int16(e.name.size); int16(0); int16(0)
            int16(0); int16(0); int32(0); int32(e.offset.toLong())
            out.write(e.name)
        }
        val size = out.size() - start
        int32(0x06054b50); int16(0); int16(0); int16(entries.size); int16(entries.size)
        int32(size.toLong()); int32(start.toLong()); int16(0)
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(data); deflater.finish()
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) { val n = deflater.deflate(buffer); result.write(buffer, 0, n) }
            return result.toByteArray()
        } finally { deflater.end() }
    }
    private fun int16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
    private fun int32(v: Long) { int16((v and 0xFFFF).toInt()); int16(((v ushr 16) and 0xFFFF).toInt()) }

    companion object {
        const val DOS_TIME = 0
        const val DOS_DATE = (0 shl 9) or (1 shl 5) or 1
    }
}
