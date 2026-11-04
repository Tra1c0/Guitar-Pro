package dev.riffworkbench.adapter.formats.musicxml

import dev.riffworkbench.adapter.formats.midi.corrupt
import dev.riffworkbench.adapter.formats.midi.resourceLimit
import dev.riffworkbench.adapter.formats.xml.XmlReader
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Compressed MusicXML (.mxl) reader. The ZIP is read from its central directory, never from header sizes alone:
 * at most [MAX_ENTRIES] entries; every file entry is decompressed once with the real output counted against
 * [MAX_ENTRY] per entry and [MAX_TOTAL] for the whole archive, and its CRC and size are verified. Names with "..",
 * absolute paths, drive letters, backslashes or control characters, duplicate (also case-insensitive) names,
 * encryption, ZIP64, multi-disk archives and unknown compression methods are rejected.
 */
internal object MxlContainer {
    const val MAX_ENTRIES = 256
    const val MAX_ENTRY = 64L * 1024 * 1024
    const val MAX_TOTAL = 128L * 1024 * 1024
    private const val CONTAINER = "META-INF/container.xml"

    private class Entry(val name: String, val nameBytes: ByteArray, val method: Int, val crc: Long, val compressedSize: Long, val size: Long, val localOffset: Long)

    fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        ((bytes[2].toInt() == 3 && bytes[3].toInt() == 4) || (bytes[2].toInt() == 5 && bytes[3].toInt() == 6))

    /** Returns the bytes of the score named by META-INF/container.xml (or the single root-level score file). */
    fun rootScore(bytes: ByteArray): ByteArray {
        val entries = centralDirectory(bytes)
        val byName = entries.associateBy { it.name }
        val rootPath = byName[CONTAINER]?.let { container ->
            val root = XmlReader.parse(read(bytes, container, MAX_ENTRY, keep = true)!!)
            if (root.name != "container") corrupt("META-INF/container.xml ist kein MusicXML-Container")
            val rootfiles = root.child("rootfiles")?.children("rootfile").orEmpty()
            val chosen = rootfiles.firstOrNull { it.attr("media-type")?.contains("musicxml") != false } ?: rootfiles.firstOrNull()
            chosen?.attr("full-path") ?: corrupt("MXL-Container nennt keine Partiturdatei")
        } ?: entries.filter { !it.name.contains('/') && (it.name.endsWith(".musicxml", true) || it.name.endsWith(".xml", true)) }
            .singleOrNull()?.name ?: corrupt("MXL-Archiv enthält keinen eindeutigen MusicXML-Einstieg")
        val rootEntry = byName[rootPath] ?: corrupt("Im MXL-Container genannte Partitur fehlt im Archiv")
        var total = 0L
        var score: ByteArray? = null
        for (e in entries) {
            if (e.name.endsWith("/")) continue
            val keep = e === rootEntry
            val data = read(bytes, e, minOf(MAX_ENTRY, MAX_TOTAL - total), keep)
            total += e.size
            if (keep) score = data
        }
        return score ?: corrupt("MusicXML-Partitur im Archiv konnte nicht gelesen werden")
    }

    private fun centralDirectory(b: ByteArray): List<Entry> {
        var eocd = -1
        val lowest = maxOf(0, b.size - 22 - 65_535)
        var p = b.size - 22
        while (p >= lowest) { if (u32(b, p) == 0x06054b50L) { eocd = p; break }; p-- }
        if (eocd < 0) corrupt("MXL-Archiv ist beschädigt (ZIP-Verzeichnisende fehlt)")
        val disk = u16(b, eocd + 4)
        val cdDisk = u16(b, eocd + 6)
        val onDisk = u16(b, eocd + 8)
        val count = u16(b, eocd + 10)
        val cdSize = u32(b, eocd + 12)
        val cdOffset = u32(b, eocd + 16)
        if (disk != 0 || cdDisk != 0 || onDisk != count) corrupt("Mehrteilige ZIP-Archive werden nicht unterstützt")
        if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) corrupt("ZIP64-Archive werden nicht unterstützt")
        if (count > MAX_ENTRIES) resourceLimit("MXL-Archiv enthält mehr als $MAX_ENTRIES Einträge")
        if (cdOffset + cdSize > eocd) corrupt("ZIP-Verzeichnis liegt außerhalb des Archivs")
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        var q = cdOffset.toInt()
        val end = (cdOffset + cdSize).toInt()
        while (q < end) {
            if (q + 46 > end || u32(b, q) != 0x02014b50L) corrupt("ZIP-Verzeichniseintrag ist beschädigt")
            if (entries.size >= MAX_ENTRIES) resourceLimit("MXL-Archiv enthält mehr als $MAX_ENTRIES Einträge")
            val flags = u16(b, q + 8)
            val method = u16(b, q + 10)
            val crc = u32(b, q + 16)
            val compressed = u32(b, q + 20)
            val size = u32(b, q + 24)
            val nameLength = u16(b, q + 28)
            val extraLength = u16(b, q + 30)
            val commentLength = u16(b, q + 32)
            val local = u32(b, q + 42)
            if (q + 46 + nameLength + extraLength + commentLength > end) corrupt("ZIP-Verzeichniseintrag ist abgeschnitten")
            val nameBytes = b.copyOfRange(q + 46, q + 46 + nameLength)
            val name = decodeName(nameBytes, flags and 0x800 != 0)
            checkName(name)
            if (!seen.add(name.lowercase())) corrupt("MXL-Archiv enthält doppelte Einträge: $name")
            if (flags and 1 != 0) corrupt("Verschlüsselte ZIP-Einträge werden nicht unterstützt")
            if (method != 0 && method != 8) corrupt("ZIP-Kompressionsmethode $method wird nicht unterstützt")
            if (compressed == 0xFFFFFFFFL || size == 0xFFFFFFFFL || local == 0xFFFFFFFFL) corrupt("ZIP64-Archive werden nicht unterstützt")
            entries += Entry(name, nameBytes, method, crc, compressed, size, local)
            q += 46 + nameLength + extraLength + commentLength
        }
        if (entries.size != count) corrupt("ZIP-Verzeichnis widerspricht der Eintragsanzahl")
        return entries
    }

    private fun decodeName(bytes: ByteArray, utf8: Boolean): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (e: java.nio.charset.CharacterCodingException) {
            if (utf8) corrupt("Ungültiger Dateiname im Archiv") else String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun checkName(name: String) {
        if (name.isEmpty() || name.length > 1024) corrupt("Ungültiger Dateiname im Archiv")
        if (name.startsWith("/") || name.contains('\\') || name.contains(':') || name.any { it.code < 0x20 || it.code == 0x7F })
            corrupt("Unsicherer Pfad im Archiv: $name")
        val segments = name.removeSuffix("/").split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) corrupt("Unsicherer Pfad im Archiv: $name")
    }

    /** Decompresses one entry, counting the produced bytes; returns them only when [keep] is set. */
    private fun read(b: ByteArray, e: Entry, limit: Long, keep: Boolean): ByteArray? {
        val at = e.localOffset
        if (at < 0 || at + 30 > b.size || u32(b, at.toInt()) != 0x04034b50L) corrupt("Lokaler ZIP-Eintrag ${e.name} ist beschädigt")
        val o = at.toInt()
        val nameLength = u16(b, o + 26)
        val extraLength = u16(b, o + 28)
        if (o + 30 + nameLength > b.size || !b.copyOfRange(o + 30, o + 30 + nameLength).contentEquals(e.nameBytes)) corrupt("Lokaler ZIP-Eintrag widerspricht dem Verzeichnis: ${e.name}")
        val start = o.toLong() + 30 + nameLength + extraLength
        if (start + e.compressedSize > b.size) corrupt("ZIP-Eintrag ${e.name} ist abgeschnitten")
        if (e.size > limit) resourceLimit("Entpackte MXL-Daten überschreiten das Limit")
        val out = if (keep) ByteArrayOutputStream(minOf(e.size, 1L shl 20).toInt()) else null
        val crc = CRC32()
        var produced = 0L
        if (e.method == 0) {
            if (e.compressedSize != e.size) corrupt("Unkomprimierter ZIP-Eintrag ${e.name} hat widersprüchliche Größen")
            crc.update(b, start.toInt(), e.size.toInt())
            out?.write(b, start.toInt(), e.size.toInt())
            produced = e.size
        } else {
            val inflater = Inflater(true)
            try {
                inflater.setInput(b, start.toInt(), e.compressedSize.toInt())
                val buffer = ByteArray(64 * 1024)
                var stalls = 0
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary() || ++stalls > 4) corrupt("ZIP-Eintrag ${e.name} ist abgeschnitten oder beschädigt")
                        continue
                    }
                    stalls = 0
                    produced += n
                    if (produced > limit) resourceLimit("Entpackte MXL-Daten überschreiten das Limit")
                    if (produced > e.size) corrupt("ZIP-Eintrag ${e.name} ist größer als im Verzeichnis angegeben")
                    crc.update(buffer, 0, n)
                    out?.write(buffer, 0, n)
                }
            } catch (x: DataFormatException) {
                corrupt("ZIP-Eintrag ${e.name} ist beschädigt")
            } finally {
                inflater.end()
            }
        }
        if (produced != e.size) corrupt("Größe des ZIP-Eintrags ${e.name} stimmt nicht")
        if (crc.value != e.crc) corrupt("Prüfsumme des ZIP-Eintrags ${e.name} stimmt nicht")
        return out?.toByteArray()
    }

    private fun u16(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 2 > b.size) corrupt("MXL-Archiv ist abgeschnitten")
        return (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }
    private fun u32(b: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > b.size) corrupt("MXL-Archiv ist abgeschnitten")
        return (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}
