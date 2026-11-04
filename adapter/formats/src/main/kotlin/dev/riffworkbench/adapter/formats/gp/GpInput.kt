package dev.riffworkbench.adapter.formats.gp

/** Malformed content; reported as `FailureCode.CORRUPT`. Messages are user-facing (German). */
internal class GpFormatException(message: String) : Exception(message)

/** Plausible content beyond the importer's memory budget; reported as `FailureCode.RESOURCE_LIMIT`. */
internal class GpLimitException(message: String) : Exception(message)

internal object GpLimits {
    const val MAX_INPUT_BYTES = 64 * 1024 * 1024
    const val MAX_TRACKS = 64
    const val MAX_MEASURES = 10_000
    const val MAX_BEATS_PER_VOICE = 4096
    const val MAX_TOTAL_BEATS = 200_000
    const val MAX_TOTAL_NOTES = 400_000
    const val MAX_STRING_BYTES = 64 * 1024
    const val MAX_BEND_POINTS = 64
    const val MAX_NOTICE_LINES = 4096
}

/**
 * Little-endian reader over untrusted bytes. Every read is bounds-checked before it happens, so a length or count
 * taken from the file can never allocate or skip beyond the actual input.
 */
internal class GpInput(private val bytes: ByteArray) {
    var position = 0
        private set
    val remaining: Int get() = bytes.size - position

    private fun need(count: Int) {
        if (count < 0 || count > remaining) throw GpFormatException("Datei endet unerwartet bei Byte $position")
    }

    fun skip(count: Int) { need(count); position += count }
    fun u8(): Int { need(1); return bytes[position++].toInt() and 0xFF }
    fun i8(): Int { need(1); return bytes[position++].toInt() }
    fun bool(): Boolean = u8() != 0
    fun i16(): Int = little(2).toInt().toShort().toInt()
    fun i32(): Int = little(4).toInt()
    fun f64(): Double = Double.fromBits(little(8))

    private fun little(size: Int): Long {
        need(size)
        var value = 0L
        for (i in size - 1 downTo 0) value = (value shl 8) or (bytes[position + i].toLong() and 0xFF)
        position += size
        return value
    }

    /** Int32 count that must lie in 0..[max]. */
    fun count(max: Int, what: String): Int =
        i32().also { if (it !in 0..max) throw GpFormatException("Ungültige Anzahl $what: $it") }

    /** Length byte followed by a fixed field of [size] bytes. */
    fun byteString(size: Int): String {
        val length = u8()
        need(size)
        return text(minOf(length, size)).also { position += size - minOf(length, size) }
    }

    /** Int32 length followed by that many bytes. */
    fun intString(): String = text(checkLength(i32()))

    /** Int32 field size (length + 1), then a length byte and the field; a zero size means "field = length". */
    fun intByteString(): String {
        val size = i32()
        val length = u8()
        val field = if (size == 0) length else checkLength(size - 1)
        need(field)
        return text(minOf(length, field)).also { position += field - minOf(length, field) }
    }

    private fun checkLength(length: Int): Int {
        if (length !in 0..GpLimits.MAX_STRING_BYTES) throw GpFormatException("Ungültige Textlänge $length bei Byte $position")
        return length
    }

    /** Guitar Pro 3–5 store text in the Windows-1252 code page. */
    private fun text(length: Int): String {
        need(length)
        val result = buildString(length) {
            for (i in position until position + length) {
                val b = bytes[i].toInt() and 0xFF
                append(if (b in 0x80..0x9F) CP1252_HIGH[b - 0x80] else b.toChar())
            }
        }
        position += length
        return result
    }

    private companion object {
        const val CP1252_HIGH = "€�‚ƒ„…†‡ˆ‰Š‹Œ�Ž�" +
            "�‘’“”•–—˜™š›œ�žŸ"
    }
}
