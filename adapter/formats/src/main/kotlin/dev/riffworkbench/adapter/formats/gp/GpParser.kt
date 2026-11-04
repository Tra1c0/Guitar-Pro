package dev.riffworkbench.adapter.formats.gp

import dev.riffworkbench.model.StrokeDirection

/** Supported headers; [code] orders the format revisions (300 < 400 < 406 < 500 < 510). */
internal enum class GpVersion(val formatId: String, val code: Int, val header: String) {
    V300("gp3", 300, "FICHIER GUITAR PRO v3.00"),
    V400("gp4", 400, "FICHIER GUITAR PRO v4.00"),
    V406("gp4", 406, "FICHIER GUITAR PRO v4.06"),
    V406L("gp4", 406, "FICHIER GUITAR PRO L4.06"),
    V500("gp5", 500, "FICHIER GUITAR PRO v5.00"),
    V510("gp5", 510, "FICHIER GUITAR PRO v5.10");

    companion object {
        /** Length byte plus a 30-byte field. */
        const val HEADER_SIZE = 31

        fun header(bytes: ByteArray): String? {
            val length = (bytes.firstOrNull() ?: return null).toInt() and 0xFF
            if (length > 30 || bytes.size < 1 + length) return null
            return String(bytes, 1, length, Charsets.ISO_8859_1)
        }

        fun detect(bytes: ByteArray): GpVersion? = header(bytes)?.let { h -> entries.firstOrNull { it.header == h } }
    }
}

// Raw, version-normalised file content. GP string numbers stay 1-based with 1 = highest string.
internal class GpSong(
    val version: GpVersion, val info: GpInfo, val lyricsTrack: Int, val lyrics: List<GpLyricLine>, val tempo: Int,
    val tempoName: String, val hideTempo: Boolean, val octave: Int, val rse: Boolean, val channels: List<GpChannel>,
    val directions: List<Int>, val headers: List<GpHeader>, val tracks: List<GpTrack>, val bars: List<List<GpBar>>,
)
internal class GpInfo(
    val title: String, val subtitle: String, val artist: String, val album: String, val words: String, val music: String,
    val copyright: String, val tab: String, val instructions: String, val notice: List<String>,
)
internal class GpLyricLine(val startMeasure: Int, val text: String)
internal class GpChannel(val program: Int, val volume: Int, val balance: Int, val effects: List<Int>)
internal class GpMarker(val title: String, val rgb: Int)
internal class GpHeader(
    val numerator: Int, val denominator: Int, val repeatOpen: Boolean, val repeatPlays: Int?, val endings: Int,
    val marker: GpMarker?, val keyFifths: Int, val keyMinor: Boolean, val doubleBar: Boolean, val beams: List<Int>?,
    val tripletFeel: Int,
)
internal class GpTrack(
    val name: String, val flags: Int, val tuning: List<Int>, val channel: Int, val fretCount: Int, val capo: Int,
    val settings: Int?, val rse: Boolean, val clefs: Pair<Int, Int>, val bank: Int,
) {
    val percussion get() = flags and 0x01 != 0
}
internal class GpBar(val voices: List<List<GpBeat>>, val lineBreak: Int)
internal class GpBeat(
    val status: Int, val duration: Int, val dotted: Boolean, val tuplet: Int, val chord: GpChord?, val text: String?,
    val effects: GpBeatEffects?, val mix: GpMix?, val notes: List<GpNote>, val display: Int,
) {
    companion object { const val EMPTY = 0; const val NORMAL = 1; const val REST = 2 }
}
internal class GpChord(val name: String, val firstFret: Int, val frets: List<Int>, val fingers: List<Int>, val details: Boolean)
internal class GpStroke(val direction: StrokeDirection, val speed: Int)
internal class GpBeatEffects(
    val noteVibrato: Boolean, val wideVibrato: Boolean, val harmonic: Int, val fadeIn: Boolean, val slap: Int,
    val tremoloBar: GpBend?, val stroke: GpStroke?, val rasgueado: Boolean, val pickStroke: Int,
)
internal class GpMix(
    val instrument: Int, val mixValues: List<Int>, val tempo: Int, val tempoName: String, val tempoDuration: Int,
    val hideTempo: Boolean, val wah: Int, val rse: Boolean,
)
internal class GpNote(
    val string: Int, val type: Int, val fret: Int, val dynamic: Int, val flags: Int, val fingers: Pair<Int, Int>,
    val durationPercent: Double, val swapAccidentals: Boolean, val effects: GpNoteEffects?,
) {
    companion object { const val TIE = 2; const val DEAD = 3 }
}
internal class GpNoteEffects(
    val bend: GpBend?, val hammer: Boolean, val letRing: Boolean, val grace: GpGrace?, val staccato: Boolean,
    val palmMute: Boolean, val tremoloPicking: Int, val slides: Int, val harmonic: GpHarmonic?, val trill: Pair<Int, Int>?,
    val vibrato: Boolean,
)
internal class GpBend(val type: Int, val points: List<GpBendPoint>)
/** [position] 0..60 of the note duration; [value] in 1/100 tone (100 = whole tone = 200 cents). */
internal class GpBendPoint(val position: Int, val value: Int, val vibrato: Boolean)
internal class GpGrace(val fret: Int, val dynamic: Int, val transition: Int, val duration: Int, val dead: Boolean, val onBeat: Boolean)
/** [kind]: 1 natural, 2 artificial, 3 tapped, 4 pinch, 5 semi. [octaveHarmonic] is false for other intervals. */
internal class GpHarmonic(val kind: Int, val octaveHarmonic: Boolean = true, val pitchClass: Int? = null, val tappedFret: Int? = null)

/** Slide bits, normalised to the GP5 layout. */
internal object GpSlide {
    const val SHIFT = 0x01; const val LEGATO = 0x02; const val OUT_DOWN = 0x04; const val OUT_UP = 0x08
    const val IN_BELOW = 0x10; const val IN_ABOVE = 0x20; const val UNKNOWN = 0x100
}

/**
 * Reads Guitar Pro 3.00, 4.0x and 5.x binary files into [GpSong]. The layout follows the published GP3/GP4 format
 * description and the structure PyGuitarPro 0.11 reads and writes (cross-checked by the fixture oracles).
 * Throws [GpFormatException] / [GpLimitException]; nothing is read beyond the input.
 */
internal class GpParser private constructor(bytes: ByteArray, private val version: GpVersion, private val onMeasure: (Int, Int) -> Unit) {
    private val input = GpInput(bytes)
    private val v4 = version.code >= 400
    private val v5 = version.code >= 500
    private val v51 = version.code >= 510
    private var totalBeats = 0
    private var totalNotes = 0

    companion object {
        fun parse(bytes: ByteArray, version: GpVersion, onMeasure: (Int, Int) -> Unit = { _, _ -> }): GpSong =
            GpParser(bytes, version, onMeasure).song()
    }

    private fun song(): GpSong {
        input.skip(GpVersion.HEADER_SIZE)
        val info = info()
        val songFeel = if (!v5 && input.bool()) 1 else 0
        var lyricsTrack = 0
        var lyrics = emptyList<GpLyricLine>()
        if (v4) {
            lyricsTrack = input.i32()
            lyrics = List(5) { GpLyricLine(input.i32(), input.intString()) }
        }
        var rse = false
        var tempoName = ""
        if (v5) {
            if (v51) {
                val volume = input.i32()
                input.skip(4)
                val equalizer = List(11) { input.i8() }
                rse = volume != 100 || equalizer.any { it != 0 }
            }
            input.skip(30) // page setup: size, margins, proportion, header/footer flags
            repeat(10) { input.intByteString() }
            tempoName = input.intByteString()
        }
        val tempo = input.i32()
        val hideTempo = v51 && input.bool()
        val key = if (v5) input.i8() else input.i32()
        val octave = when { v5 -> input.i32(); v4 -> input.i8(); else -> 0 }
        val channels = List(64) {
            GpChannel(input.i32(), input.i8(), input.i8(), List(4) { input.i8() }).also { input.skip(2) }
        }
        val directions = if (v5) List(19) { input.i16() } else emptyList()
        if (v5 && input.i32() != 0) rse = true // master reverb
        val measureCount = input.count(GpLimits.MAX_MEASURES, "Takte")
        val trackCount = input.count(GpLimits.MAX_TRACKS, "Spuren")
        if (measureCount == 0 || trackCount == 0) throw GpFormatException("Datei enthält keine Takte oder keine Spuren")
        val headers = headers(measureCount, songFeel, key)
        val tracks = List(trackCount) { track(it) }
        if (v5) input.skip(if (v51) 1 else 2)
        val bars = List(measureCount) { m -> onMeasure(m, measureCount); tracks.map { bar(it) } }
        return GpSong(version, info, lyricsTrack, lyrics, tempo, tempoName, hideTempo, octave, rse, channels, directions, headers, tracks, bars)
    }

    private fun info(): GpInfo {
        val title = input.intByteString(); val subtitle = input.intByteString()
        val artist = input.intByteString(); val album = input.intByteString()
        val words = input.intByteString(); val music = if (v5) input.intByteString() else ""
        val copyright = input.intByteString(); val tab = input.intByteString(); val instructions = input.intByteString()
        val notice = List(input.count(GpLimits.MAX_NOTICE_LINES, "Hinweiszeilen")) { input.intByteString() }
        return GpInfo(title, subtitle, artist, album, words, music, copyright, tab, instructions, notice)
    }

    private fun headers(count: Int, songFeel: Int, songKey: Int): List<GpHeader> {
        val headers = ArrayList<GpHeader>(count)
        repeat(count) { index ->
            val previous = headers.lastOrNull()
            if (v5 && previous != null) input.skip(1)
            val flags = input.u8()
            val numerator = if (flags and 0x01 != 0) input.i8() else previous?.numerator ?: 4
            val denominator = if (flags and 0x02 != 0) input.i8() else previous?.denominator ?: 4
            if (numerator !in 1..64 || denominator !in listOf(1, 2, 4, 8, 16, 32, 64)) {
                throw GpFormatException("Ungültige Taktart $numerator/$denominator in Takt ${index + 1}")
            }
            val close = if (flags and 0x08 != 0) input.u8() else null
            var endings = if (!v5 && flags and 0x10 != 0) gp3Endings(input.u8(), headers) else 0
            val marker = if (flags and 0x20 != 0) GpMarker(input.intByteString(), color()) else null
            var keyFifths = previous?.keyFifths ?: songKey
            var keyMinor = previous?.keyMinor ?: false
            if (flags and 0x40 != 0) { keyFifths = input.i8(); keyMinor = input.i8() == 1 }
            if (v5 && flags and 0x10 != 0) endings = input.u8()
            var beams: List<Int>? = null
            var feel = songFeel
            if (v5) {
                if (flags and 0x03 != 0) beams = List(4) { input.u8() }
                if (flags and 0x10 == 0) input.skip(1)
                feel = input.u8()
            }
            // GP3/GP4 store the number of repeats, GP5 the total number of plays.
            val plays = close?.let { if (v5) it else it + 1 }
            headers += GpHeader(numerator, denominator, flags and 0x04 != 0, plays, endings, marker, keyFifths, keyMinor, flags and 0x80 != 0, beams, feel)
        }
        return headers
    }

    /** GP3/GP4 store the highest ending number; endings already used since the repeat start are excluded. */
    private fun gp3Endings(number: Int, previous: List<GpHeader>): Int {
        var used = 0
        for (header in previous.asReversed()) { if (header.repeatOpen) break; used = used or header.endings }
        return ((1 shl number.coerceAtMost(8)) - 1) and used.inv()
    }

    private fun color(): Int = ((input.u8() shl 16) or (input.u8() shl 8) or input.u8()).also { input.skip(1) }

    private fun track(index: Int): GpTrack {
        if (v5 && (index == 0 || !v51)) input.skip(1)
        val flags = input.u8()
        val name = input.byteString(40)
        val stringCount = input.i32()
        if (stringCount !in 1..7) throw GpFormatException("Spur ${index + 1}: ungültige Saitenzahl $stringCount")
        val tuning = List(7) { input.i32() }.take(stringCount)
        input.skip(4) // MIDI port
        val channel = input.i32() - 1
        if (channel !in 0..63) throw GpFormatException("Spur ${index + 1}: ungültiger MIDI-Kanal ${channel + 1}")
        input.skip(4) // effect channel
        val fretCount = input.i32()
        val capo = input.i32()
        input.skip(4) // colour (cosmetic)
        if (!v5) return GpTrack(name, flags, tuning, channel, fretCount, capo, null, false, 0 to 0, 0)
        val settings = input.i16() and 0xFFFF
        val autoAccentuation = input.u8(); val bank = input.u8(); val humanize = input.u8()
        val clefs = input.i32() to input.i32()
        input.skip(16) // unknown int and 12 reserved bytes
        input.skip(12); input.skip(if (v51) 4 else 3) // RSE instrument, sound bank, effect number
        var rse = flags and 0x40 != 0 || autoAccentuation != 0 || humanize != 0
        if (v51) {
            if (List(4) { input.i8() }.any { it != 0 }) rse = true // track equalizer
            if (input.intByteString().isNotBlank() or input.intByteString().isNotBlank()) rse = true // effect and category
        }
        return GpTrack(name, flags, tuning, channel, fretCount, capo, settings, rse, clefs, bank)
    }

    private fun bar(track: GpTrack): GpBar {
        val voices = List(if (v5) 2 else 1) { voice(track) }
        // PyGuitarPro tolerates a missing final line-break byte at the end of the file.
        val lineBreak = if (v5 && input.remaining > 0) input.u8() else 0
        return GpBar(voices, lineBreak)
    }

    private fun voice(track: GpTrack): List<GpBeat> {
        val count = input.count(GpLimits.MAX_BEATS_PER_VOICE, "Beats")
        totalBeats += count
        if (totalBeats > GpLimits.MAX_TOTAL_BEATS) throw GpLimitException("mehr als ${GpLimits.MAX_TOTAL_BEATS} Beats")
        return List(count) { beat(track) }
    }

    private fun beat(track: GpTrack): GpBeat {
        val flags = input.u8()
        val status = if (flags and 0x40 != 0) input.u8() else GpBeat.NORMAL
        if (status !in 0..2) throw GpFormatException("Ungültiger Beat-Status $status bei Byte ${input.position}")
        val code = input.i8()
        if (code !in -2..6) throw GpFormatException("Ungültige Notendauer $code bei Byte ${input.position}")
        val tuplet = if (flags and 0x20 != 0) input.i32() else 1
        val chord = if (flags and 0x02 != 0) chord() else null
        val text = if (flags and 0x04 != 0) input.intByteString() else null
        val effects = if (flags and 0x08 != 0) beatEffects() else null
        val mix = if (flags and 0x10 != 0) mix() else null
        val strings = input.u8()
        val notes = ArrayList<GpNote>()
        for (string in 1..7) if (strings and (1 shl (7 - string)) != 0) {
            if (string > track.tuning.size) throw GpFormatException("Note auf nicht vorhandener Saite $string bei Byte ${input.position}")
            notes += note(string)
        }
        totalNotes += notes.size
        if (totalNotes > GpLimits.MAX_TOTAL_NOTES) throw GpLimitException("mehr als ${GpLimits.MAX_TOTAL_NOTES} Noten")
        var display = 0
        if (v5) {
            display = input.i16() and 0xFFFF
            if (display and 0x0800 != 0) input.u8()
        }
        return GpBeat(status, 1 shl (code + 2), flags and 0x01 != 0, tuplet, chord, text, effects, mix, notes, display)
    }

    private fun chord(): GpChord {
        if (!input.bool()) { // GP3-style diagram: name, base fret and (if set) six frets
            val name = input.intByteString()
            val firstFret = input.i32()
            return GpChord(name, firstFret, if (firstFret != 0) List(6) { input.i32() } else emptyList(), emptyList(), false)
        }
        if (!v4) { // GP3 extended diagram
            input.skip(25)
            val name = input.byteString(22)
            input.skip(12)
            val firstFret = input.i32()
            val frets = List(6) { input.i32() }
            val barres = input.i32()
            input.skip(24)
            val omissions = List(7) { input.bool() }
            input.skip(1)
            return GpChord(name, firstFret, frets, emptyList(), barres != 0 || !omissions.all { it })
        }
        input.skip(16) // accidental mode, root, type, extension, bass, tonality, add
        val name = input.byteString(22)
        input.skip(3) // fifth, ninth, eleventh
        val firstFret = input.i32()
        val frets = List(7) { input.i32() }
        val barres = input.u8()
        input.skip(15)
        val omissions = List(7) { input.bool() }
        input.skip(1)
        val fingers = List(7) { input.i8() }
        input.skip(1) // show fingering
        return GpChord(name, firstFret, frets, fingers, barres != 0 || !omissions.all { it })
    }

    private fun beatEffects(): GpBeatEffects {
        val f1 = input.u8()
        val f2 = if (v4) input.u8() else 0
        var slap = 0
        var tremoloBar: GpBend? = null
        if (f1 and 0x20 != 0) {
            slap = input.u8()
            if (!v4) { // GP3: tremolo-bar dip depth or an unused int after tapping/slapping/popping
                val value = input.i32()
                if (slap == 0) tremoloBar = GpBend(6, listOf(GpBendPoint(0, 0, false), GpBendPoint(30, -value, false), GpBendPoint(60, 0, false)))
            }
        }
        if (f2 and 0x04 != 0) tremoloBar = bend()
        val stroke = if (f1 and 0x40 != 0) stroke() else null
        val pickStroke = if (f2 and 0x02 != 0) input.i8() else 0
        val harmonic = when { v4 -> 0; f1 and 0x08 != 0 -> 2; f1 and 0x04 != 0 -> 1; else -> 0 }
        return GpBeatEffects(!v4 && f1 and 0x01 != 0, f1 and 0x02 != 0, harmonic, f1 and 0x10 != 0, slap, tremoloBar, stroke, f2 and 0x01 != 0, pickStroke)
    }

    /** Two speed bytes; GP3/GP4 store (down, up), GP5 (up, down). The second byte wins, as in PyGuitarPro. */
    private fun stroke(): GpStroke? {
        val first = input.i8()
        val second = input.i8()
        val (firstDirection, secondDirection) = if (v5) StrokeDirection.UP to StrokeDirection.DOWN else StrokeDirection.DOWN to StrokeDirection.UP
        return when {
            second > 0 -> GpStroke(secondDirection, second)
            first > 0 -> GpStroke(firstDirection, first)
            else -> null
        }
    }

    private fun bend(): GpBend {
        val type = input.i8()
        input.skip(4) // overall height, implied by the points
        val count = input.count(GpLimits.MAX_BEND_POINTS, "Bendpunkte")
        return GpBend(type, List(count) { GpBendPoint(input.i32(), input.i32(), input.bool()) })
    }

    private fun mix(): GpMix {
        val instrument = input.i8()
        var rse = false
        if (v5) input.skip(16) // RSE instrument (and one reserved byte in 5.00)
        val values = List(6) { input.i8() } // volume, balance, chorus, reverb, phaser, tremolo
        val tempoName = if (v5) input.intByteString() else ""
        val tempo = input.i32()
        values.forEach { if (it >= 0) input.skip(1) } // transition durations
        var tempoDuration = 0
        var hideTempo = false
        if (tempo >= 0) {
            tempoDuration = input.i8()
            hideTempo = v51 && input.bool()
        }
        if (v4) input.skip(1) // "apply to all tracks" flags
        var wah = -1
        if (v5) {
            wah = input.i8()
            if (v51 && (input.intByteString().isNotBlank() or input.intByteString().isNotBlank())) rse = true
        }
        return GpMix(instrument, values, tempo, tempoName, tempoDuration, hideTempo, wah, rse)
    }

    private fun note(string: Int): GpNote {
        val flags = input.u8()
        val type = if (flags and 0x20 != 0) input.u8() else 1
        if (!v5 && flags and 0x01 != 0) input.skip(2) // GP3/GP4 time-independent duration
        val dynamic = if (flags and 0x10 != 0) input.i8() else 6
        val fret = if (flags and 0x20 != 0) input.i8() else 0
        val fingers = if (flags and 0x80 != 0) input.i8() to input.i8() else -1 to -1
        var durationPercent = 1.0
        var swap = false
        if (v5) {
            if (flags and 0x01 != 0) durationPercent = input.f64()
            swap = input.u8() and 0x02 != 0
        }
        val effects = if (flags and 0x08 != 0) noteEffects() else null
        return GpNote(string, type, fret, dynamic, flags, fingers, durationPercent, swap, effects)
    }

    private fun noteEffects(): GpNoteEffects {
        val f1 = input.u8()
        val f2 = if (v4) input.u8() else 0
        val bend = if (f1 and 0x01 != 0) bend() else null
        val grace = if (f1 and 0x10 != 0) grace() else null
        val tremoloPicking = if (f2 and 0x04 != 0) input.i8() else 0
        var slides = if (!v4 && f1 and 0x04 != 0) GpSlide.SHIFT else 0
        if (f2 and 0x08 != 0) slides = if (v5) input.u8() else gp4Slide(input.i8())
        val harmonic = if (f2 and 0x10 != 0) harmonic() else null
        val trill = if (f2 and 0x20 != 0) input.i8() to input.i8() else null
        return GpNoteEffects(bend, hammer = f1 and 0x02 != 0, letRing = f1 and 0x08 != 0, grace, staccato = f2 and 0x01 != 0,
            palmMute = f2 and 0x02 != 0, tremoloPicking, slides, harmonic, trill, vibrato = f2 and 0x40 != 0)
    }

    private fun gp4Slide(type: Int): Int = when (type) {
        0 -> 0
        1 -> GpSlide.SHIFT; 2 -> GpSlide.LEGATO; 3 -> GpSlide.OUT_DOWN; 4 -> GpSlide.OUT_UP
        -1 -> GpSlide.IN_BELOW; -2 -> GpSlide.IN_ABOVE
        else -> GpSlide.UNKNOWN
    }

    /** Fret, dynamic, transition, duration (as documented; PyGuitarPro 0.11 swaps the last two for GP3/GP4), GP5 flags. */
    private fun grace(): GpGrace {
        val fret = if (v5) input.u8() else input.i8()
        val dynamic = input.u8()
        val transition = input.u8()
        val duration = input.u8()
        val flags = if (v5) input.u8() else 0
        val dead = if (v5) flags and 0x01 != 0 else fret < 0
        return GpGrace(if (dead && !v5) 0 else fret, dynamic, transition, duration, dead, flags and 0x02 != 0)
    }

    private fun harmonic(): GpHarmonic {
        val type = input.i8()
        if (!v5) return when (type) {
            15, 17 -> GpHarmonic(2, octaveHarmonic = false) // artificial harmonic at n+5 / n+7 frets
            22 -> GpHarmonic(2)
            else -> GpHarmonic(type)
        }
        return when (type) {
            2 -> { // natural pitch class, accidental, octave (1 = one octave above)
                val semitone = input.u8(); val accidental = input.i8(); val octave = input.u8()
                GpHarmonic(2, octave == 1, Math.floorMod(semitone + accidental, 12))
            }
            3 -> GpHarmonic(3, tappedFret = input.u8())
            else -> GpHarmonic(type)
        }
    }
}
