package dev.riffworkbench.adapter.formats.gpif

/**
 * Guitar Pro 6 container (.gpx), EXPERIMENTAL: implemented from public format descriptions and verified only
 * against our own test encoder, not against files written by Guitar Pro 6.
 *
 * BCFZ = "BCFZ" + int32 LE decompressed length + bit stream. Bits are consumed most-significant first.
 * Each chunk starts with a 1-bit flag:
 *  - 0: literal run. 2 bits (least-significant bit first) give 0..3 bytes, each read as 8 bits MSB first.
 *  - 1: back reference. 4 bits (MSB first) give the word size w; then w bits offset and w bits length, both LSB
 *    first. min(offset, length) bytes are copied from (output size - offset); copies never overlap their source.
 * The decompressed payload is itself a BCFS image (starting with "BCFS").
 *
 * BCFS = "BCFS" + sectors of 0x1000 bytes (the first sector is unused). A sector starting with int32 LE 2 is a
 * file entry: name at +0x04 (127 bytes, NUL terminated), size at +0x8C (int32 LE), and from +0x94 a list of
 * int32 LE absolute data sector indices terminated by 0.
 */
object BcfLimits {
    const val MAX_OUTPUT = 64 * 1024 * 1024
    const val MAX_FILES = 512
}

internal object Bcfz {
    fun isBcfz(bytes: ByteArray) = bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "BCFZ"
    fun isBcfs(bytes: ByteArray) = bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "BCFS"

    private class Bits(private val data: ByteArray, private var index: Int) {
        private var current = 0
        private var position = 8
        var exhausted = false
            private set
        fun bit(): Int {
            if (position >= 8) {
                if (index >= data.size) { exhausted = true; throw EndOfStream }
                current = data[index++].toInt() and 0xFF
                position = 0
            }
            return (current shr (7 - position++)) and 1
        }
        fun bits(count: Int): Int { var v = 0; for (i in count - 1 downTo 0) v = v or (bit() shl i); return v }
        fun bitsReversed(count: Int): Int { var v = 0; for (i in 0 until count) v = v or (bit() shl i); return v }
    }
    private object EndOfStream : RuntimeException("Ende des BCFZ-Datenstroms")

    /** Decompresses a complete BCFZ file and returns the BCFS image (including its "BCFS" magic). */
    fun decompress(bytes: ByteArray, limit: Int = BcfLimits.MAX_OUTPUT): ByteArray {
        if (!isBcfz(bytes)) corrupt("Keine BCFZ-Daten")
        if (bytes.size < 8) corrupt("BCFZ-Kopf ist unvollständig")
        val expected = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8) or ((bytes[6].toInt() and 0xFF) shl 16) or ((bytes[7].toInt() and 0xFF) shl 24)
        if (expected < 0) corrupt("BCFZ: ungültige Länge")
        if (expected > limit) resourceLimit("BCFZ-Inhalt wäre größer als ${limit / (1024 * 1024)} MiB")
        val out = ByteArray(expected)
        var size = 0
        val bits = Bits(bytes, 8)
        try {
            while (size < expected) {
                if (bits.bit() == 1) {
                    val word = bits.bits(4)
                    val offset = bits.bitsReversed(word)
                    val length = bits.bitsReversed(word)
                    if (offset <= 0 || offset > size) corrupt("BCFZ: Rückverweis außerhalb der Daten")
                    val count = minOf(offset, length, expected - size)
                    System.arraycopy(out, size - offset, out, size, count)
                    size += count
                } else {
                    val count = bits.bitsReversed(2)
                    for (i in 0 until count) {
                        val b = bits.bits(8)
                        if (size < expected) out[size++] = b.toByte()
                    }
                }
            }
        } catch (e: RuntimeException) {
            if (e !== EndOfStream) throw e
            // A stream may end a few bits early; what was decoded is checked structurally by the BCFS reader.
        }
        if (size < 4) corrupt("BCFZ-Datenstrom ist unvollständig")
        return if (size == expected) out else out.copyOf(size)
    }
}

internal object Bcfs {
    private const val SECTOR = 0x1000

    private fun int32(data: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > data.size) corrupt("BCFS: Lesezugriff außerhalb der Daten")
        return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or ((data[at + 2].toInt() and 0xFF) shl 16) or ((data[at + 3].toInt() and 0xFF) shl 24)
    }

    /** Lists the files of a BCFS image (with "BCFS" magic); [keep] selects which payloads are copied. */
    fun files(image: ByteArray, keep: (String) -> Boolean = { true }): Map<String, ByteArray> {
        if (!Bcfz.isBcfs(image)) corrupt("Keine BCFS-Daten")
        val data = image.copyOfRange(4, image.size)
        val files = LinkedHashMap<String, ByteArray>()
        var entries = 0
        var total = 0L
        var offset = SECTOR
        while (offset + 4 <= data.size) {
            if (int32(data, offset) == 2) {
                if (++entries > BcfLimits.MAX_FILES) resourceLimit("BCFS enthält zu viele Dateien")
                val nameBytes = data.copyOfRange(offset + 4, minOf(offset + 4 + 127, data.size))
                val nameEnd = nameBytes.indexOf(0.toByte()).let { if (it < 0) nameBytes.size else it }
                val name = String(nameBytes, 0, nameEnd, Charsets.UTF_8)
                val size = int32(data, offset + 0x8C)
                if (size < 0 || size > BcfLimits.MAX_OUTPUT) resourceLimit("BCFS-Datei $name ist zu groß")
                val store = keep(name)
                val payload = if (store) ByteArray(size) else null
                var written = 0
                var last = offset
                var pointer = offset + 0x94
                while (pointer + 4 <= offset + SECTOR) {
                    val sector = int32(data, pointer)
                    pointer += 4
                    if (sector == 0) break
                    val start = sector.toLong() * SECTOR
                    if (sector < 0 || start >= data.size) corrupt("BCFS: ungültiger Sektor $sector")
                    val available = minOf(SECTOR.toLong(), data.size - start).toInt()
                    if (payload != null && written < size) {
                        val n = minOf(available, size - written)
                        System.arraycopy(data, start.toInt(), payload, written, n)
                        written += n
                    } else written += available
                    last = maxOf(last, start.toInt())
                }
                if (written < size) corrupt("BCFS-Datei $name ist unvollständig")
                if (payload != null) {
                    if (name in files) corrupt("Doppelte BCFS-Datei $name")
                    total += size
                    if (total > BcfLimits.MAX_OUTPUT) resourceLimit("BCFS-Inhalt ist zu groß")
                    files[name] = payload
                }
                // Continue after the furthest data sector; never move backwards (guards against cycles).
                offset = maxOf(offset, last)
            }
            offset += SECTOR
        }
        return files
    }
}

/** Extracts score.gpif (and optional PartConfiguration) from GPX bytes (BCFZ or BCFS). */
internal object GpxContainer {
    class Extracted(val gpif: ByteArray, val partConfiguration: ByteArray?, val otherFiles: List<String>)

    fun extract(bytes: ByteArray): Extracted {
        if (bytes.size > GpZipLimits.MAX_INPUT) resourceLimit("GPX-Datei ist größer als 64 MiB")
        val image = when {
            Bcfz.isBcfz(bytes) -> Bcfz.decompress(bytes)
            Bcfz.isBcfs(bytes) -> bytes
            else -> corrupt("Keine GPX-Datei (BCFZ/BCFS-Kennung fehlt)")
        }
        val wanted = setOf("score.gpif", "PartConfiguration")
        val names = ArrayList<String>()
        val files = Bcfs.files(image) { names += it; it in wanted }
        val gpif = files["score.gpif"] ?: corrupt("GPX enthält keine score.gpif")
        return Extracted(gpif, files["PartConfiguration"], names.filter { it !in wanted })
    }
}
