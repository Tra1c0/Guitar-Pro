package dev.riffworkbench.adapter.formats.gpif

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

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
 * ZIP reader over an in-memory copy, driven by the central directory. Nothing is extracted to disk and no archive path is ever
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

    /** One central-directory record. */
    class Entry(val name: String, val method: Int, val flags: Int, val crc: Long, val compressed: Long, val size: Long, val localOffset: Long)

    /**
     * Entries from the central directory. Guitar Pro writes data descriptors without a signature (crc, uncompressed,
     * compressed), which sequential readers such as ZipInputStream misread; the central directory is authoritative.
     */
    fun entries(bytes: ByteArray): List<Entry> {
        if (bytes.size > GpZipLimits.MAX_INPUT) resourceLimit("Archiv ist größer als 64 MiB")
        if (!isZip(bytes)) corrupt("Keine ZIP-Datei")
        var eocd = -1
        for (i in bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 65535)) if (u32(bytes, i) == 0x06054b50L) { eocd = i; break }
        // Without a usable central directory, fall back to the chain of local headers (sizes must then be in the headers).
        if (eocd < 0 || u16(bytes, eocd + 10) == 0) return localEntries(bytes)
        val count = u16(bytes, eocd + 10)
        val cdSize = u32(bytes, eocd + 12); val cdOffset = u32(bytes, eocd + 16)
        if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL) corrupt("ZIP64-Archive werden nicht unterstützt")
        if (count > GpZipLimits.MAX_ENTRIES) resourceLimit("Archiv enthält mehr als ${GpZipLimits.MAX_ENTRIES} Einträge")
        if (cdOffset + cdSize > eocd) corrupt("Beschädigtes ZIP-Inhaltsverzeichnis")
        val result = ArrayList<Entry>(count)
        var p = cdOffset.toInt()
        repeat(count) {
            if (p + 46 > eocd || u32(bytes, p) != 0x02014b50L) corrupt("Beschädigtes ZIP-Inhaltsverzeichnis")
            val flags = u16(bytes, p + 8); val method = u16(bytes, p + 10); val crc = u32(bytes, p + 16)
            val compressed = u32(bytes, p + 20); val size = u32(bytes, p + 24)
            val nameLen = u16(bytes, p + 28); val extraLen = u16(bytes, p + 30); val commentLen = u16(bytes, p + 32)
            val offset = u32(bytes, p + 42)
            if (p + 46 + nameLen > eocd) corrupt("Beschädigtes ZIP-Inhaltsverzeichnis")
            val name = try {
                java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, p + 46, nameLen)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) { corrupt("Ungültig kodierter Eintragsname im Archiv") }
            result += Entry(name, method, flags, crc, compressed, size, offset)
            p += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    private fun localEntries(bytes: ByteArray): List<Entry> {
        val result = ArrayList<Entry>()
        var p = 0
        while (p + 30 <= bytes.size && u32(bytes, p) == 0x04034b50L) {
            if (result.size >= GpZipLimits.MAX_ENTRIES) resourceLimit("Archiv enthält mehr als ${GpZipLimits.MAX_ENTRIES} Einträge")
            val flags = u16(bytes, p + 6); val method = u16(bytes, p + 8); val crc = u32(bytes, p + 14)
            val compressed = u32(bytes, p + 18); val size = u32(bytes, p + 22)
            val nameLen = u16(bytes, p + 26); val extraLen = u16(bytes, p + 28)
            if (flags and 8 != 0) corrupt("ZIP-Archiv mit Datenbeschreibern, aber ohne Inhaltsverzeichnis")
            if (p + 30 + nameLen > bytes.size) corrupt("Beschädigter Archiveintrag")
            val name = try {
                java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, p + 30, nameLen)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) { corrupt("Ungültig kodierter Eintragsname im Archiv") }
            result += Entry(name, method, flags, crc, compressed, size, p.toLong())
            val next = p.toLong() + 30 + nameLen + extraLen + compressed
            if (next > bytes.size) corrupt("Archiveintrag ${name.take(80)} ist abgeschnitten")
            p = next.toInt()
        }
        return result
    }

    /**
     * Reads all entries under the running budgets; only entries accepted by [keep] are retained in memory.
     * [stopAfter] ends reading early once that entry was read (used by the detector).
     */
    fun read(bytes: ByteArray, keep: (String) -> Boolean, stopAfter: String? = null, keepLimit: Long = GpZipLimits.MAX_ENTRY): GpZipContents {
        val names = ArrayList<String>()
        val seen = HashSet<String>()
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        val buffer = ByteArray(16 * 1024)
        for (entry in entries(bytes)) {
            val name = entry.name
            validateName(name)
            if (!seen.add(name)) corrupt("Doppelter Archiveintrag: ${name.take(80)}")
            names += name
            val directory = name.endsWith("/")
            val retain = !directory && keep(name)
            if (entry.flags and 1 != 0) corrupt("Verschlüsselte Archiveinträge werden nicht unterstützt")
            // Local header: only its variable lengths are used; sizes come from the central directory.
            val lh = entry.localOffset
            if (lh + 30 > bytes.size || u32(bytes, lh.toInt()) != 0x04034b50L) corrupt("Beschädigter Archiveintrag: ${name.take(80)}")
            val dataStart = lh + 30 + u16(bytes, lh.toInt() + 26) + u16(bytes, lh.toInt() + 28)
            if (dataStart + entry.compressed > bytes.size) corrupt("Archiveintrag ${name.take(80)} ist abgeschnitten")
            val out = if (retain) ByteArrayOutputStream() else null
            val crc = CRC32()
            var entrySize = 0L
            fun accept(n: Int) {
                entrySize += n; total += n
                if (entrySize > GpZipLimits.MAX_ENTRY) resourceLimit("Archiveintrag ${name.take(80)} ist entpackt größer als 64 MiB")
                if (total > GpZipLimits.MAX_TOTAL) resourceLimit("Archiv ist entpackt größer als 256 MiB")
                crc.update(buffer, 0, n)
                if (out != null) {
                    if (entrySize > keepLimit) resourceLimit("Archiveintrag ${name.take(80)} überschreitet das Leselimit")
                    out.write(buffer, 0, n)
                }
            }
            when (entry.method) {
                0 -> {
                    var pos = dataStart.toInt(); val end = (dataStart + entry.compressed).toInt()
                    while (pos < end) { val n = minOf(buffer.size, end - pos); System.arraycopy(bytes, pos, buffer, 0, n); accept(n); pos += n }
                }
                8 -> {
                    val inflater = java.util.zip.Inflater(true)
                    try {
                        inflater.setInput(bytes, dataStart.toInt(), entry.compressed.toInt())
                        while (!inflater.finished()) {
                            val n = try { inflater.inflate(buffer) } catch (e: java.util.zip.DataFormatException) { corrupt("Beschädigte Daten in ${name.take(80)}") }
                            if (n > 0) accept(n)
                            else if (!inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) corrupt("Archiveintrag ${name.take(80)} ist abgeschnitten")
                        }
                    } finally { inflater.end() }
                }
                else -> corrupt("Nicht unterstützte Kompression in ${name.take(80)}")
            }
            if (entrySize != entry.size || crc.value != entry.crc) corrupt("Prüfsumme oder Größe von ${name.take(80)} stimmt nicht")
            if (directory && entrySize > 0) corrupt("Verzeichniseintrag mit Inhalt: ${name.take(80)}")
            if (out != null) files[name] = out.toByteArray()
            if (stopAfter != null && name == stopAfter) break
        }
        if (names.isEmpty()) corrupt("Leeres oder beschädigtes ZIP-Archiv")
        return GpZipContents(names, files)
    }

    private fun u16(b: ByteArray, at: Int): Int = if (at + 2 > b.size) corrupt("Beschädigtes ZIP-Archiv") else (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int): Long = if (at < 0 || at + 4 > b.size) corrupt("Beschädigtes ZIP-Archiv") else
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
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
