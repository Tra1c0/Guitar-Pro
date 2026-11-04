package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.model.*
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Own synthetic test data and helpers only; no third-party tab files. */
internal object GpifTestSupport {
    fun ids(prefix: String = "t"): IdSource { var n = 0; return IdSource { EntityId("$prefix-${n++}") } }
    fun f(n: Long, d: Long = 1) = Fraction.of(n, d)

    /** Raw STORED zip writer that allows the hostile layouts java.util.zip refuses to produce (duplicates, odd names). */
    fun rawZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun i32(v: Long) { i16((v and 0xFFFF).toInt()); i16(((v ushr 16) and 0xFFFF).toInt()) }
        for ((name, data) in entries) {
            val n = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value
            i32(0x04034b50); i16(10); i16(0); i16(0); i16(0); i16(0x21); i32(crc); i32(data.size.toLong()); i32(data.size.toLong()); i16(n.size); i16(0)
            out.write(n); out.write(data)
        }
        i32(0x06054b50); repeat(4) { i16(0) }; i32(0); i32(0); i16(0)
        return out.toByteArray()
    }

    fun gpZip(gpif: String, extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray =
        rawZip(listOf("VERSION" to "7.0".toByteArray(), "Content/score.gpif" to gpif.toByteArray(Charsets.UTF_8)) + extra)

    /** Test-only BCFZ encoder producing literals and non-overlapping back references (window 2047). */
    fun bcfz(data: ByteArray): ByteArray {
        val bits = BitWriter()
        var i = 0
        while (i < data.size) {
            var bestLength = 0; var bestOffset = 0
            for (offset in 1..minOf(i, 2047)) {
                var length = 0
                while (length < offset && length < 2047 && i + length < data.size && data[i - offset + length] == data[i + length]) length++
                if (length > bestLength) { bestLength = length; bestOffset = offset }
            }
            if (bestLength >= 4) {
                val word = maxOf(32 - Integer.numberOfLeadingZeros(bestOffset), 32 - Integer.numberOfLeadingZeros(bestLength))
                bits.bit(1); bits.bits(word, 4); bits.reversed(bestOffset, word); bits.reversed(bestLength, word)
                i += bestLength
            } else {
                val count = minOf(3, data.size - i)
                bits.bit(0); bits.reversed(count, 2)
                repeat(count) { bits.bits(data[i + it].toInt() and 0xFF, 8) }
                i += count
            }
        }
        val header = "BCFZ".toByteArray() + le32(data.size)
        return header + bits.finish()
    }

    class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var count = 0
        fun bit(b: Int) { current = (current shl 1) or (b and 1); if (++count == 8) { out.write(current); current = 0; count = 0 } }
        fun bits(value: Int, n: Int) { for (k in n - 1 downTo 0) bit((value shr k) and 1) }
        fun reversed(value: Int, n: Int) { for (k in 0 until n) bit((value shr k) and 1) }
        fun finish(): ByteArray { if (count > 0) { out.write(current shl (8 - count)); current = 0; count = 0 }; return out.toByteArray() }
    }

    fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte())

    /** BCFS image: "BCFS", unused sector 0, then per file an entry sector followed by its data sectors. */
    fun bcfs(files: List<Pair<String, ByteArray>>): ByteArray {
        val sectors = ArrayList<ByteArray>()
        sectors += ByteArray(0x1000) { 0xFF.toByte() }
        for ((name, data) in files) {
            val entry = ByteArray(0x1000)
            sectors += entry
            le32(2).copyInto(entry, 0)
            name.toByteArray(Charsets.UTF_8).copyInto(entry, 4)
            le32(data.size).copyInto(entry, 0x8C)
            var k = 0
            while (k * 0x1000 < data.size) {
                le32(sectors.size).copyInto(entry, 0x94 + 4 * k)
                sectors += data.copyOfRange(k * 0x1000, minOf(data.size, (k + 1) * 0x1000)).copyOf(0x1000)
                k++
            }
        }
        val out = ByteArrayOutputStream()
        out.write("BCFS".toByteArray())
        sectors.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Text form of a project with every entity id replaced by its musical position, for semantic comparison. */
    fun dump(p: Project, includeSound: Boolean = false): String {
        val path = HashMap<EntityId, String>()
        p.measures.forEachIndexed { m, measure -> path[measure.id] = "m$m" }
        p.tracks.forEachIndexed { t, track ->
            track.staves.forEachIndexed { s, staff ->
                staff.bars.forEachIndexed { m, bar ->
                    bar.voices.forEach { v ->
                        v.beats.forEach { beat ->
                            val bp = "t$t/s$s/m$m/v${v.index}/@${beat.offset}"
                            path[beat.id] = bp
                            beat.notes.forEach { n -> path[n.id] = "$bp/${n.string ?: ("p" + n.pitch.midiNumber)}" }
                        }
                    }
                }
            }
        }
        fun ref(id: EntityId?) = id?.let { path[it] ?: "?" }
        fun art(a: Articulation) = if (a is Articulation.Slide) "Slide(${a.kind},${ref(a.target)})" else a.toString()
        return buildString {
            appendLine("meta ${p.metadata}")
            p.measures.forEachIndexed { m, x ->
                appendLine("m$m ${x.signature} ${x.key} pickup=${x.pickupLength} ${x.repeat.starts}/${x.repeat.endCount}/${x.repeat.alternateEndings.sorted()} ${x.feel} nav=${x.navigation.map { "${it.kind}->${ref(it.target)}" }}")
            }
            p.sections.forEach { appendLine("section ${ref(it.measureId)} ${it.label}") }
            p.tempo.forEach { appendLine("tempo ${ref(it.position.measureId)} ${it.position.offset} ${it.quarterNotesPerMinute}") }
            p.text.forEach { appendLine("text ${ref(it.position.measureId)} ${it.position.offset} ${it.kind} ${it.text}") }
            p.tracks.forEachIndexed { t, track ->
                appendLine("track$t ${track.name} ${track.instrument} mix=${track.mix}" + if (includeSound) " ${track.sound}" else "")
                track.staves.forEachIndexed { s, staff ->
                    appendLine(" staff$s ${staff.notation.sorted()}")
                    staff.bars.forEach { bar ->
                        bar.voices.sortedBy { it.index }.forEach { v ->
                            v.beats.sortedBy { it.offset }.forEach { b ->
                                appendLine("  ${path[b.id]} ${b.duration} rest=${b.explicitRest} fermata=${b.fermata} strum=${b.strum} chord=${b.chordId != null}")
                                b.notes.sortedBy { it.string ?: it.pitch.midiNumber }.forEach { n ->
                                    appendLine("   ${n.pitch} s=${n.string} f=${n.fret} v=${n.velocity} ${n.fingering} ${GpifAssembly.canonical(emptyList(), n.articulations).map(::art)} ${n.links.map { "${it.kind}->${ref(it.target)}" }}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
