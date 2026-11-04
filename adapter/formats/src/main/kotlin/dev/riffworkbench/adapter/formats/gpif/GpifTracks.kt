package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.xml.XmlElement
import dev.riffworkbench.model.*

/** Named <Property> children of an element's <Properties> block (first occurrence wins). */
internal fun XmlElement.props(): Map<String, XmlElement> {
    val container = child("Properties") ?: return emptyMap()
    val map = LinkedHashMap<String, XmlElement>()
    for (p in container.children("Property")) { val n = p.attr("name") ?: continue; map.putIfAbsent(n, p) }
    return map
}

internal class DrumArticulation(val midi: Int, val name: String, val technique: PercussionTechnique?)
internal class ChordInfo(val name: String, val frets: Map<Int, Int>)

/** Everything about one GPIF track that the bar conversion needs. */
internal class TrackPlan(
    val gpifId: String,
    val name: String,
    val kind: InstrumentKind,
    val tuning: List<Int>,
    val capoByString: List<Int>,
    val midiProgram: Int,
    val staffCount: Int,
    val notation: Set<NotationKind>,
    val drumArticulations: List<DrumArticulation>,
    val sound: SoundAssignment,
    val mix: ChannelMix,
    val chords: Map<String, ChordInfo>,
    val expectedClef: String,
) {
    val stringed: Boolean get() = tuning.isNotEmpty() && kind != InstrumentKind.PERCUSSION
    val percussion: Boolean get() = kind == InstrumentKind.PERCUSSION
    fun pitch(string: Int, fret: Int) = tuning[string] + capoByString[string] + fret
}

internal object GpifTracks {
    const val MAX_STAVES = 4
    private val standardGuitar = listOf(40, 45, 50, 55, 59, 64)
    private val standardBass = listOf(28, 33, 38, 43)

    /** Clef Guitar Pro writes by default for an instrument kind; differences are reported as notation loss. */
    fun defaultClef(kind: InstrumentKind) = when (kind) { InstrumentKind.BASS -> "F4"; InstrumentKind.PERCUSSION -> "Neutral"; else -> "G2" }

    /** Conventional written-to-sounding transposition in semitones (guitar and bass are notated an octave up). */
    fun conventionalTranspose(kind: InstrumentKind) = if (kind == InstrumentKind.GUITAR || kind == InstrumentKind.BASS) -12 else 0

    fun kindOf(type: String, percussion: Boolean, hasTuning: Boolean, program: Int): InstrumentKind {
        val t = type.lowercase()
        return when {
            percussion || "drum" in t || "percussion" in t -> InstrumentKind.PERCUSSION
            "bass" in t -> InstrumentKind.BASS
            "guitar" in t || "gtr" in t || "ukulele" in t || "banjo" in t || "mandolin" in t -> InstrumentKind.GUITAR
            "piano" in t || "keyboard" in t || "organ" in t -> InstrumentKind.PIANO
            hasTuning && program in 32..39 -> InstrumentKind.BASS
            hasTuning -> InstrumentKind.GUITAR
            program in 0..7 -> InstrumentKind.PIANO
            else -> InstrumentKind.OTHER
        }
    }

    fun parse(el: XmlElement, index: Int, partFlags: Int?, losses: LossCollector): TrackPlan {
        val gpifId = el.attr("id") ?: index.toString()
        val name = el.textOf("Name")?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: "Spur ${index + 1}"
        val staves = el.child("Staves")?.children("Staff").orEmpty()
        // GP7/8 keep tuning and capo per staff; GP6 keeps them on the track.
        val props = if (staves.isNotEmpty()) staves.first().props() else el.props()
        val type = el.textOf("InstrumentSet", "Type") ?: el.child("Instrument")?.attr("ref") ?: ""
        val sounds = el.child("Sounds")?.children("Sound").orEmpty()
        val program = (GpifNumbers.parseIntOrNull(sounds.firstOrNull()?.textOf("MIDI", "Program")) ?: GpifNumbers.parseIntOrNull(el.textOf("GeneralMidi", "Program")) ?: 25).coerceIn(0, 127)
        val channel = GpifNumbers.parseIntOrNull(el.textOf("MidiConnection", "PrimaryChannel") ?: el.textOf("GeneralMidi", "PrimaryChannel"))
        val percussionTable = el.child("GeneralMidi")?.attr("table") == "Percussion"
        val pitches = props["Tuning"]?.textOf("Pitches")?.let { GpifNumbers.ints(it) }.orEmpty()
        var kind = kindOf(type, percussionTable || channel == 9 || el.textOf("InstrumentSet", "Type") == "drumKit", pitches.isNotEmpty(), program)

        var tuning = if (kind == InstrumentKind.PERCUSSION) emptyList() else pitches
        if (tuning.any { it !in 0..127 }) { losses.add("strings.tuning", "Stimmung außerhalb des MIDI-Bereichs wird begrenzt"); tuning = tuning.map { it.coerceIn(0, 127) } }
        if ((kind == InstrumentKind.GUITAR || kind == InstrumentKind.BASS) && tuning.isEmpty()) {
            losses.add("strings.tuning", "Saiteninstrument ohne Stimmung: Standardstimmung angenommen")
            tuning = if (kind == InstrumentKind.BASS) standardBass else standardGuitar
        }
        if ((kind == InstrumentKind.GUITAR || kind == InstrumentKind.BASS) && tuning.size !in 3..10) kind = InstrumentKind.OTHER
        if (tuning.size > 16) { losses.add("strings.count", "Mehr als 16 Saiten werden nicht unterstützt"); tuning = tuning.take(16) }

        val capo = capo(props, tuning.size, losses)
        if (staves.size > 1) {
            val other = staves.drop(1).map { s -> s.props()["Tuning"]?.textOf("Pitches")?.let { GpifNumbers.ints(it) }.orEmpty() }
            if (other.any { it.isNotEmpty() && it != pitches }) losses.add("strings.tuning", "Abweichende Stimmungen weiterer Notensysteme werden nicht übernommen")
        }
        if (staves.size > MAX_STAVES) losses.add("notation.standard", "Mehr als $MAX_STAVES Notensysteme pro Spur werden nicht übernommen")
        val drums = if (kind == InstrumentKind.PERCUSSION) drumArticulations(el) else emptyList()
        val notation = partFlags?.takeIf { it != 0 }?.let { PartConfiguration.notationOf(it, kind == InstrumentKind.PERCUSSION) }?.takeIf { it.isNotEmpty() }
            ?: defaultNotation(kind, tuning.isNotEmpty())

        reportTrackExtras(el, sounds, kind, losses)
        val soundName = sounds.firstOrNull()?.textOf("Name")?.trim().orEmpty()
        val soundPath = sounds.firstOrNull()?.textOf("Path")?.trim().orEmpty()
        val sound = SoundAssignment(
            presetId = soundPath.takeIf { it.isNotEmpty() }?.take(200),
            mappingDescription = buildString {
                append("Guitar Pro")
                if (soundName.isNotEmpty()) append(": ").append(soundName.take(80))
                append(" · GM-Programm ").append(program)
            },
        )
        return TrackPlan(gpifId, name, kind, tuning, capo, program, staves.size.coerceIn(1, MAX_STAVES), notation, drums, sound, mix(el, losses), chords(props), defaultClef(kind))
    }

    fun defaultNotation(kind: InstrumentKind, hasTuning: Boolean): Set<NotationKind> = when {
        kind == InstrumentKind.PERCUSSION -> setOf(NotationKind.PERCUSSION)
        hasTuning -> setOf(NotationKind.TAB)
        else -> setOf(NotationKind.STANDARD)
    }

    /**
     * CapoFret applies to all strings. PartialCapoFret applies to the strings flagged in PartialCapoStringFlags.
     * The flag string is read as a binary number (last character = lowest string, index 0); this orientation is
     * unverified against Guitar Pro and only self-consistent with our exporter.
     */
    private fun capo(props: Map<String, XmlElement>, strings: Int, losses: LossCollector): List<Int> {
        var base = GpifNumbers.parseIntOrNull(props["CapoFret"]?.textOf("Fret")) ?: 0
        if (base !in 0..36) { losses.add("strings.capo", "Kapodaster außerhalb 0–36 wird begrenzt"); base = base.coerceIn(0, 36) }
        val fractional = GpifNumbers.parseDoubleOrNull(props["FractionalCapoFret"]?.textOf("Float")) ?: 0.0
        if (fractional != 0.0 && fractional != base.toDouble()) losses.add("strings.capo", "Bruchteil-Kapodaster wird nicht unterstützt")
        val result = MutableList(strings) { base }
        val partial = GpifNumbers.parseIntOrNull(props["PartialCapoFret"]?.textOf("Fret")) ?: 0
        val flags = props["PartialCapoStringFlags"]?.textOf("Bitset")?.trim().orEmpty()
        if (partial > 0 && flags.contains('1')) {
            if (partial > 36) losses.add("strings.capo", "Teilkapodaster außerhalb 0–36 wird begrenzt")
            for (s in 0 until strings) {
                val c = flags.getOrNull(flags.length - 1 - s)
                if (c == '1') result[s] = partial.coerceIn(0, 36)
            }
            if (flags.length != strings) losses.add("strings.capo", "Teilkapodaster-Saitenmaske passt nicht zur Saitenzahl")
        }
        return result
    }

    /** GP7 drum kits: notes reference the flattened list of InstrumentSet/Elements/Element/Articulations/Articulation. */
    private fun drumArticulations(el: XmlElement): List<DrumArticulation> {
        val out = ArrayList<DrumArticulation>()
        for (element in el.path("InstrumentSet", "Elements")?.children("Element").orEmpty()) {
            val elementName = element.textOf("Name")?.trim().orEmpty()
            for (a in element.child("Articulations")?.children("Articulation").orEmpty()) {
                val midi = GpifNumbers.parseIntOrNull(a.textOf("OutputMidiNumber")) ?: -1
                val label = a.textOf("Name")?.trim().orEmpty().ifEmpty { elementName }
                out += DrumArticulation(midi, label, GpDrums.techniqueOf(label))
                if (out.size > 4096) resourceLimit("Schlagzeug-Definition ist zu groß")
            }
        }
        return out
    }

    private fun mix(el: XmlElement, losses: LossCollector): ChannelMix {
        val state = el.textOf("PlaybackState")?.trim()
        var volume = 0.8
        var pan = 0.0
        val parameters = el.textOf("RSE", "ChannelStrip", "Parameters")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
        // Best knowledge: channel strip parameter 11 is the pan (0..1, centre 0.5) and 12 the volume (0..1).
        if (parameters.size >= 13 && parameters[11] in 0.0..1.0 && parameters[12] in 0.0..1.0) {
            pan = (parameters[11] * 2.0 - 1.0).coerceIn(-1.0, 1.0)
            volume = parameters[12]
            losses.add("mix.eq_fx", "Kanalzug-EQ und weitere RSE-Kanalparameter werden nicht übernommen")
        }
        return ChannelMix(volume = volume, pan = pan, mute = state == "Mute", solo = state == "Solo")
    }

    private fun chords(props: Map<String, XmlElement>): Map<String, ChordInfo> {
        val out = LinkedHashMap<String, ChordInfo>()
        for (collection in listOf("DiagramCollection", "ChordCollection")) {
            for (item in props[collection]?.child("Items")?.children("Item").orEmpty()) {
                val id = item.attr("id") ?: continue
                val name = item.attr("name")?.trim().orEmpty()
                val frets = item.child("Diagram")?.children("Fret").orEmpty().mapNotNull { f ->
                    val s = GpifNumbers.parseIntOrNull(f.attr("string")); val v = GpifNumbers.parseIntOrNull(f.attr("fret"))
                    if (s == null || v == null || s !in 0..15 || v !in 0..48) null else s to v
                }.toMap()
                val existing = out[id]
                if (existing == null) out[id] = ChordInfo(name, frets)
                else if (existing.frets.isEmpty() && frets.isNotEmpty()) out[id] = ChordInfo(existing.name.ifEmpty { name }, frets)
            }
        }
        return out
    }

    private fun reportTrackExtras(el: XmlElement, sounds: List<XmlElement>, kind: InstrumentKind, losses: LossCollector) {
        val rse = el.child("RSE")?.elements.orEmpty().isNotEmpty() || sounds.any { it.child("RSE")?.elements.orEmpty().isNotEmpty() }
        if (rse) losses.add("sound.rse_mapping", "RSE-Klänge, Effektketten und Pickup-Einstellungen werden nicht übernommen; es gilt das MIDI-Programm")
        if (sounds.size > 1) losses.add("sound.rse_mapping", "Weitere Klänge einer Spur (Klangwechsel) werden nicht übernommen")
        val automations = el.path("Automations")?.children("Automation").orEmpty() + el.path("RSE", "ChannelStrip", "Automations")?.children("Automation").orEmpty()
        if (automations.isNotEmpty()) losses.add("mix.automation", "Spur-Automationen (Lautstärke, Panorama, Klang) werden nicht übernommen")
        val lyrics = el.child("Lyrics")?.children("Line").orEmpty().any { !it.textOf("Text").isNullOrBlank() }
        if (lyrics) losses.add("layout.text_lyrics", "Spur-Liedtext (Lyrics-Zeilen) wird nicht übernommen")
        if (el.child("Color") != null || !el.textOf("ShortName").isNullOrBlank() || el.child("IconId") != null)
            losses.add("layout.stylesheet", "Spurdarstellung (Farbe, Kurzname, Symbol, Systemlayout) wird nicht übernommen")
        val octave = GpifNumbers.parseIntOrNull(el.textOf("Transpose", "Octave")) ?: 0
        val chromatic = GpifNumbers.parseIntOrNull(el.textOf("Transpose", "Chromatic")) ?: 0
        val gp6 = GpifNumbers.parseIntOrNull(el.textOf("PartSounding", "TranspositionPitch"))
        val transpose = gp6 ?: (octave * 12 + chromatic)
        val present = el.child("Transpose") != null || gp6 != null
        if (present && transpose != conventionalTranspose(kind)) losses.add("notation.standard", "Notations-Transposition ($transpose Halbtöne) wird nicht übernommen; klingende Tonhöhen bleiben erhalten")
    }
}
