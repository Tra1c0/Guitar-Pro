package dev.riffworkbench.adapter.formats.midi

import dev.riffworkbench.model.FailureCode

/** Growable byte buffer without java.io streams. */
internal class ByteBuffer(capacity: Int = 1024) {
    private var data = ByteArray(capacity)
    var size = 0; private set
    fun byte(value: Int) { ensure(1); data[size++] = value.toByte() }
    fun bytes(values: ByteArray) { ensure(values.size); values.copyInto(data, size); size += values.size }
    fun int32(value: Int) { byte(value ushr 24); byte(value ushr 16); byte(value ushr 8); byte(value) }
    fun int16(value: Int) { byte(value ushr 8); byte(value) }
    fun vlq(value: Long) {
        require(value in 0..0x0FFFFFFF) { "MIDI-Zeitabstand außerhalb des darstellbaren Bereichs" }
        var buffer = value and 0x7F
        var v = value ushr 7
        while (v > 0) { buffer = (buffer shl 8) or 0x80 or (v and 0x7F); v = v ushr 7 }
        while (true) { byte(buffer.toInt()); if (buffer and 0x80 == 0L) break; buffer = buffer ushr 8 }
    }
    fun toByteArray(): ByteArray = data.copyOf(size)
    private fun ensure(extra: Int) { if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra)) }
}

/**
 * One MTrk chunk. Events at the same tick are ordered by priority: meta, note-off, controller/program/pitch bend,
 * note-on, then by insertion order. That keeps "note ends, bend resets, next note starts" correct at shared ticks.
 */
internal class SmfTrackWriter {
    private class Event(val tick: Long, val priority: Int, val seq: Int, val bytes: ByteArray)
    private val events = ArrayList<Event>()
    val lastTick: Long get() = events.maxOfOrNull { it.tick } ?: 0L

    fun channel(tick: Long, priority: Int, status: Int, d1: Int, d2: Int? = null) {
        require(tick >= 0 && d1 in 0..127 && (d2 == null || d2 in 0..127))
        val bytes = if (d2 == null) byteArrayOf(status.toByte(), d1.toByte()) else byteArrayOf(status.toByte(), d1.toByte(), d2.toByte())
        events += Event(tick, priority, events.size, bytes)
    }
    fun meta(tick: Long, type: Int, data: ByteArray) {
        val b = ByteBuffer(data.size + 8)
        b.byte(0xFF); b.byte(type); b.vlq(data.size.toLong()); b.bytes(data)
        events += Event(tick, META, events.size, b.toByteArray())
    }
    fun text(tick: Long, type: Int, text: String) = meta(tick, type, text.toByteArray(Charsets.UTF_8).let { if (it.size > 4096) it.copyOf(4096) else it })

    fun encode(endTick: Long): ByteArray {
        val sorted = events.sortedWith(compareBy<Event>({ it.tick }, { it.priority }, { it.seq }))
        val body = ByteBuffer(events.size * 4 + 16)
        var last = 0L
        for (e in sorted) { body.vlq(e.tick - last); body.bytes(e.bytes); last = e.tick }
        body.vlq(maxOf(0L, endTick - last)); body.byte(0xFF); body.byte(0x2F); body.byte(0)
        val out = ByteBuffer(body.size + 8)
        out.bytes("MTrk".toByteArray(Charsets.US_ASCII)); out.int32(body.size); out.bytes(body.toByteArray())
        return out.toByteArray()
    }

    companion object {
        const val META = 0
        const val NOTE_OFF = 1
        const val CONTROL = 2
        const val NOTE_ON = 3
    }
}

internal object SmfWriter {
    fun file(format: Int, division: Int, tracks: List<ByteArray>): ByteArray {
        val out = ByteBuffer(tracks.sumOf { it.size } + 14)
        out.bytes("MThd".toByteArray(Charsets.US_ASCII)); out.int32(6); out.int16(format); out.int16(tracks.size); out.int16(division)
        tracks.forEach { out.bytes(it) }
        return out.toByteArray()
    }
}

internal class SmfMeta(val tick: Long, val type: Int, val data: ByteArray) {
    fun text(): String = decodeText(data)
}

/** Channel events are packed as tick shl 24 | status shl 16 | data1 shl 8 | data2 to keep large files compact. */
internal class SmfTrackData(private val packed: LongArray, val size: Int, val metas: List<SmfMeta>, val endTick: Long, val sysex: Int) {
    fun tick(i: Int): Long = packed[i] ushr 24
    fun status(i: Int): Int = ((packed[i] ushr 16) and 0xFF).toInt()
    fun data1(i: Int): Int = ((packed[i] ushr 8) and 0xFF).toInt()
    fun data2(i: Int): Int = (packed[i] and 0xFF).toInt()
}

internal class SmfData(val format: Int, val division: Int, val declaredTracks: Int, val tracks: List<SmfTrackData>)

/** Text meta events: UTF-8 when valid, otherwise ISO-8859-1 (older sequencers). */
internal fun decodeText(data: ByteArray): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    val text = try { decoder.decode(java.nio.ByteBuffer.wrap(data)).toString() } catch (e: java.nio.charset.CharacterCodingException) { String(data, Charsets.ISO_8859_1) }
    return text.filter { it == '\n' || it == '\t' || it >= ' ' }.trim()
}

/**
 * Strict Standard MIDI File reader with budgets. Supports SMF type 0/1 (type 2 is reported as incompatible),
 * an optional RIFF/RMID wrapper, running status, meta events and skipped SysEx. Unknown chunks are ignored.
 */
internal object SmfReader {
    const val MAX_TRACKS = 1024
    const val MAX_CHANNEL_EVENTS = 4_000_000
    const val MAX_META_EVENTS = 400_000
    const val MAX_TICK = 1L shl 38

    fun read(bytes: ByteArray): SmfData {
        var start = 0
        var end = bytes.size
        if (end >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "RMID")) {
            var p = 12
            var found = false
            while (p + 8 <= end) {
                val len = u32le(bytes, p + 4)
                if (len < 0 || len > end - p - 8) corrupt("RMID-Container ist abgeschnitten")
                if (ascii(bytes, p, "data")) { start = p + 8; end = p + 8 + len.toInt(); found = true; break }
                p += 8 + len.toInt() + (len.toInt() and 1)
            }
            if (!found) corrupt("RMID-Container enthält keine MIDI-Daten")
        }
        if (end - start < 14 || !ascii(bytes, start, "MThd")) corrupt("Keine MIDI-Datei (MThd fehlt)")
        val headerLength = u32(bytes, start + 4)
        if (headerLength < 6 || headerLength > end - start - 8) corrupt("MIDI-Kopf ist beschädigt")
        val format = u16(bytes, start + 8)
        val declared = u16(bytes, start + 10)
        val division = u16(bytes, start + 12)
        if (format == 2) throw FormatException(FailureCode.INCOMPATIBLE_VERSION, "MIDI-Typ 2 (unabhängige Sequenzen) wird nicht unterstützt")
        if (format > 2) corrupt("Unbekannter MIDI-Typ $format")
        if (division == 0) corrupt("MIDI-Zeitbasis ist null")
        var p = start + 8 + headerLength.toInt()
        val tracks = ArrayList<SmfTrackData>()
        var events = 0
        var metas = 0
        while (p + 8 <= end) {
            val length = u32(bytes, p + 4)
            if (length > end - p - 8) corrupt("MIDI-Spur ${tracks.size + 1} ist abgeschnitten")
            if (ascii(bytes, p, "MTrk")) {
                if (tracks.size >= MAX_TRACKS) resourceLimit("MIDI-Datei enthält mehr als $MAX_TRACKS Spuren")
                val track = parseTrack(bytes, p + 8, p + 8 + length.toInt(), MAX_CHANNEL_EVENTS - events, MAX_META_EVENTS - metas, tracks.size + 1)
                events += track.size; metas += track.metas.size
                tracks += track
            }
            p += 8 + length.toInt()
        }
        if (tracks.isEmpty()) corrupt("MIDI-Datei enthält keine Spur")
        return SmfData(format, division, declared, tracks)
    }

    private fun parseTrack(b: ByteArray, from: Int, to: Int, eventBudget: Int, metaBudget: Int, number: Int): SmfTrackData {
        var packed = LongArray(256)
        var size = 0
        val metas = ArrayList<SmfMeta>()
        var sysex = 0
        var i = from
        var tick = 0L
        var running = 0
        fun need(count: Int) { if (i + count > to) corrupt("MIDI-Spur $number endet mitten in einem Ereignis") }
        fun vlq(): Long {
            var value = 0L
            for (k in 0 until 4) {
                need(1)
                val c = b[i++].toInt() and 0xFF
                value = (value shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return value
            }
            corrupt("Ungültige variable Länge in MIDI-Spur $number")
        }
        while (i < to) {
            tick += vlq()
            if (tick > MAX_TICK) resourceLimit("MIDI-Spur $number ist zu lang")
            need(1)
            var status = b[i].toInt() and 0xFF
            if (status < 0x80) {
                if (running == 0) corrupt("Running Status ohne vorheriges Statusbyte in MIDI-Spur $number")
                status = running
            } else i++
            when {
                status == 0xFF -> {
                    need(1)
                    val type = b[i++].toInt() and 0xFF
                    val length = vlq()
                    if (length > to - i) corrupt("Meta-Ereignis in MIDI-Spur $number ist abgeschnitten")
                    if (metas.size >= metaBudget) resourceLimit("MIDI-Datei enthält zu viele Meta-Ereignisse")
                    val data = b.copyOfRange(i, i + length.toInt())
                    i += length.toInt()
                    if (type == 0x2F) return SmfTrackData(packed, size, metas, tick, sysex)
                    metas += SmfMeta(tick, type, data)
                }
                status == 0xF0 || status == 0xF7 -> {
                    val length = vlq()
                    if (length > to - i) corrupt("SysEx in MIDI-Spur $number ist abgeschnitten")
                    i += length.toInt()
                    sysex++
                }
                status > 0xF0 -> corrupt("Ungültiges Statusbyte 0x${status.toString(16)} in MIDI-Spur $number")
                else -> {
                    running = status
                    val count = if (status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0) 1 else 2
                    need(count)
                    val d1 = b[i++].toInt() and 0xFF
                    val d2 = if (count == 2) b[i++].toInt() and 0xFF else 0
                    if (d1 > 127 || d2 > 127) corrupt("Ungültiges Datenbyte in MIDI-Spur $number")
                    if (size >= eventBudget) resourceLimit("MIDI-Datei enthält mehr als $MAX_CHANNEL_EVENTS Kanalereignisse")
                    if (size == packed.size) packed = packed.copyOf(minOf(packed.size * 2, MAX_CHANNEL_EVENTS))
                    packed[size++] = (tick shl 24) or (status.toLong() shl 16) or (d1.toLong() shl 8) or d2.toLong()
                }
            }
        }
        return SmfTrackData(packed, size, metas, tick, sysex)
    }

    private fun ascii(b: ByteArray, at: Int, text: String) = at + text.length <= b.size && text.indices.all { b[at + it] == text[it].code.toByte() }
    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, at: Int): Long = ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
    private fun u32le(b: ByteArray, at: Int): Long = ((b[at + 3].toLong() and 0xFF) shl 24) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 1].toLong() and 0xFF) shl 8) or (b[at].toLong() and 0xFF)
}
