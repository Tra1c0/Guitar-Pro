package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.adapter.formats.xml.XmlWriter
import dev.riffworkbench.model.*

/**
 * Project -> GPIF XML as written by Guitar Pro 7 (best knowledge; acceptance by GP7/GP8 Desktop is unverified).
 * Ids are numbered sequentially in score order, so identical projects give identical bytes.
 */
internal class GpifEncoder(private val project: Project, private val losses: LossCollector) {
    private val tracks = project.tracks.mapIndexed { i, t -> ExportTrack(t, i, losses) }
    private val relations = ExportRelations(project, losses)
    private val rhythms = RhythmTable(losses)
    private val writer = BeatWriter(project, tracks, relations, rhythms, losses)

    /** Track notation flags for Content/PartConfiguration, in track order. */
    val partFlags: List<Int> = project.tracks.map { t ->
        val sets = t.staves.map { it.notation }.distinct()
        if (sets.size > 1) losses.add("notation.tab", "Unterschiedliche Notationsarten mehrerer Notensysteme einer Spur werden vereinheitlicht", t.id)
        PartConfiguration.flagsOf(sets.firstOrNull().orEmpty())
    }

    fun encode(): String {
        reportProjectLosses()
        val barIds = project.measures.indices.map { m ->
            tracks.flatMapIndexed { t, et -> et.track.staves.mapIndexed { s, staff -> writer.bar(t, s, staff.bars[m], m) } }
        }
        project.text.filter { it.id !in writer.usedTexts }.forEach {
            losses.add("layout.text_lyrics", "Text ohne Beat der ersten Spur an seiner Position wird nicht exportiert", it.id)
        }
        val w = XmlWriter().declaration()
        w.start("GPIF", "version" to "7")
        w.leaf("GPVersion", "7.6.0")
        w.start("GPRevision", "required" to "12021", "recommended" to "13000").text("13007").end()
        w.start("Encoding").leaf("EncodingDescription", "GP7").end()
        score(w)
        masterTrack(w)
        w.start("Tracks"); tracks.forEachIndexed { i, et -> track(w, i, et) }; w.end()
        w.start("MasterBars"); project.measures.forEachIndexed { m, measure -> masterBar(w, m, measure, barIds[m]) }; w.end()
        w.start("Bars"); writer.bars.forEach { it(w) }; w.end()
        w.start("Voices"); writer.voices.forEach { it(w) }; w.end()
        w.start("Beats"); writer.beats.forEach { it(w) }; w.end()
        w.start("Notes"); writer.notes.forEach { it(w) }; w.end()
        w.start("Rhythms")
        for ((d, id) in rhythms.entries) {
            w.start("Rhythm", "id" to id).leaf("NoteValue", GpifUnits.noteValueOf(d.denominator) ?: "256th")
            if (d.dots > 0) w.start("AugmentationDot", "count" to d.dots).end()
            d.tuplets.getOrNull(0)?.let { w.start("PrimaryTuplet", "num" to it.actual, "den" to it.normal).end() }
            d.tuplets.getOrNull(1)?.let { w.start("SecondaryTuplet", "num" to it.actual, "den" to it.normal).end() }
            w.end()
        }
        w.end()
        w.end()
        return w.build()
    }

    private fun score(w: XmlWriter) {
        val m = project.metadata
        w.start("Score")
        w.cdataLeaf("Title", m.title).cdataLeaf("SubTitle", "").cdataLeaf("Artist", m.artist).cdataLeaf("Album", m.album)
            .cdataLeaf("Words", "").cdataLeaf("Music", m.composer).cdataLeaf("WordsAndMusic", "").cdataLeaf("Copyright", m.copyright)
            .cdataLeaf("Tabber", "").cdataLeaf("Instructions", "").cdataLeaf("Notices", m.notes)
        w.end()
    }

    private fun masterTrack(w: XmlWriter) {
        w.start("MasterTrack")
        w.leaf("Tracks", tracks.indices.joinToString(" "))
        val index = project.measures.withIndex().associate { it.value.id to it.index }
        val changes = project.tempo.filter { it.position.measureId in index }.sortedWith(compareBy({ index.getValue(it.position.measureId) }, { it.position.offset }))
        if (changes.isNotEmpty()) {
            w.start("Automations")
            for (c in changes) {
                val m = index.getValue(c.position.measureId)
                val measure = project.measures[m]
                val length = measure.pickupLength ?: measure.signature.length()
                val ratio = (c.position.offset.toDouble() / length.toDouble()).coerceIn(0.0, 1.0)
                w.start("Automation").leaf("Type", "Tempo").leaf("Linear", "false").leaf("Bar", m).leaf("Position", GpifNumbers.decimal(ratio))
                    .leaf("Visible", "true").leaf("Value", "${GpifNumbers.decimal(c.quarterNotesPerMinute)} 2").end()
            }
            w.end()
        }
        if (project.measures.first().pickupLength != null) w.start("Anacrusis").end()
        w.end()
    }

    private fun track(w: XmlWriter, i: Int, et: ExportTrack) {
        val t = et.track
        val kind = t.instrument.kind
        w.start("Track", "id" to i)
        w.cdataLeaf("Name", t.name).cdataLeaf("ShortName", t.name.take(8).trim())
        w.leaf("Color", COLORS[i % COLORS.size])
        val (setName, setType) = when {
            kind == InstrumentKind.PERCUSSION -> "Drums" to "drumKit"
            kind == InstrumentKind.BASS -> "Electric Bass" to "electricBass"
            kind == InstrumentKind.GUITAR -> "Electric Guitar" to "electricGuitar"
            et.stringed -> "Electric Guitar" to "electricGuitar"
            else -> "Piano" to "acousticPiano"
        }
        if (kind == InstrumentKind.OTHER) losses.add("band.instruments", "Instrumentart 'Sonstiges' wird als ${if (et.stringed) "Gitarre" else "Klavier"} exportiert", t.id)
        w.start("InstrumentSet").leaf("Name", setName).leaf("Type", setType).leaf("LineCount", 5).start("Elements")
        if (et.percussion) et.drums.forEach { (midi, _, name) ->
            val entry = GpDrums.entry(midi)
            w.start("Element").leaf("Name", name).leaf("Type", "unpitched").leaf("SoundbankName", "").start("Articulations")
            articulation(w, name, entry.line, entry.cymbal, midi)
            w.end().end()
        } else {
            w.start("Element").leaf("Name", "Pitched").leaf("Type", "pitched").leaf("SoundbankName", "").start("Articulations")
            articulation(w, "", 0, false, 0)
            w.end().end()
        }
        w.end().end()
        val transpose = GpifTracks.conventionalTranspose(kind)
        w.start("Transpose").leaf("Chromatic", transpose % 12).leaf("Octave", transpose / 12).end()
        w.start("Sounds").start("Sound").cdataLeaf("Name", t.name).cdataLeaf("Label", t.name).leaf("Path", "Midi/Program ${t.instrument.midiProgram}").leaf("Role", "User")
            .start("MIDI").leaf("LSB", 0).leaf("MSB", 0).leaf("Program", t.instrument.midiProgram).end().end().end()
        w.start("MidiConnection").leaf("Port", et.port).leaf("PrimaryChannel", et.primaryChannel).leaf("SecondaryChannel", et.secondaryChannel)
            .leaf("ForeOneChannelPerString", "false").end()
        w.leaf("PlaybackState", when { t.mix.solo -> "Solo"; t.mix.mute -> "Mute"; else -> "Default" })
        w.leaf("AudioEngineState", "MIDI")
        w.start("Staves")
        t.staves.forEach { _ ->
            w.start("Staff").start("Properties")
            if (et.stringed) {
                w.prop("CapoFret", "Fret", et.capo).prop("FractionalCapoFret", "Float", 0).prop("PartialCapoFret", "Fret", et.partialCapo)
                w.prop("PartialCapoStringFlags", "Bitset", et.partialFlags)
                w.start("Property", "name" to "Tuning").leaf("Pitches", et.tuning.joinToString(" ")).leaf("Instrument", if (kind == InstrumentKind.BASS) "Bass" else "Guitar")
                    .cdataLeaf("Label", "").leaf("LabelVisible", "true").end()
            }
            w.end().end()
        }
        w.end()
        w.end()
    }

    private fun articulation(w: XmlWriter, name: String, line: Int, cymbal: Boolean, midi: Int) {
        val heads = if (cymbal) "noteheadXBlack noteheadXHalf noteheadXWhole" else "noteheadBlack noteheadHalf noteheadWhole"
        w.start("Articulation").leaf("Name", name).leaf("StaffLine", line).leaf("Noteheads", heads).leaf("TechniquePlacement", "outside")
            .leaf("TechniqueSymbol", "").leaf("InputMidiNumbers", "").leaf("OutputRSESound", "").leaf("OutputMidiNumber", midi).end()
    }

    private fun masterBar(w: XmlWriter, m: Int, measure: Measure, bars: List<Int>) {
        w.start("MasterBar")
        val fifths = measure.key.fifths.coerceIn(-7, 7)
        if (fifths != measure.key.fifths) losses.add("signature.key", "Tonart außerhalb von 7 Vorzeichen wird begrenzt", measure.id)
        w.start("Key").leaf("AccidentalCount", fifths).leaf("Mode", if (measure.key.minor) "Minor" else "Major").leaf("TransposeAs", "Sharps").end()
        w.leaf("Time", "${measure.signature.numerator}/${measure.signature.denominator}")
        w.leaf("Bars", bars.joinToString(" "))
        val repeat = measure.repeat
        val ends = repeat.endCount?.let { it >= 2 } == true
        if (repeat.endCount != null && !ends) losses.add("repeats.basic", "Wiederholungsende mit weniger als zwei Durchläufen wird nicht exportiert", measure.id)
        if (repeat.starts || ends) w.start("Repeat", "start" to repeat.starts, "end" to ends, "count" to if (ends) repeat.endCount else 0).end()
        if (repeat.alternateEndings.isNotEmpty()) w.leaf("AlternateEndings", repeat.alternateEndings.sorted().joinToString(" "))
        project.sections.filter { it.measureId == measure.id }.let { sections ->
            if (sections.size > 1) losses.add("arrangement.sections", "Nur ein Abschnitt pro Takt ist darstellbar", measure.id)
            sections.firstOrNull()?.let { s ->
                if (s.color != Section(s.id, s.measureId, s.label).color) losses.add("arrangement.sections", "Abschnittsfarbe wird nicht exportiert", s.id)
                w.start("Section").cdataLeaf("Letter", "").cdataLeaf("Text", s.label).end()
            }
        }
        directions(w, measure)
        when (measure.feel) {
            RhythmicFeel.STRAIGHT -> Unit
            RhythmicFeel.SWING_EIGHTH -> w.leaf("TripletFeel", "Triplet8th")
            RhythmicFeel.SWING_SIXTEENTH -> w.leaf("TripletFeel", "Triplet16th")
            RhythmicFeel.TRIPLET -> { losses.add("rhythm.swing", "Triolen-Feel wird als Swing-Achtel exportiert", measure.id); w.leaf("TripletFeel", "Triplet8th") }
        }
        val fermatas = LinkedHashMap<Fraction, Fermata>()
        for (t in project.tracks) for (staff in t.staves) for (voice in staff.bars[m].voices) for (beat in voice.beats) beat.fermata?.let { f ->
            val existing = fermatas[beat.offset]
            if (existing == null) fermatas[beat.offset] = f else if (existing != f) losses.add("rhythm.fermata", "Abweichende Fermaten an derselben Position werden vereinheitlicht", beat.id)
        }
        if (fermatas.isNotEmpty()) {
            w.start("Fermatas")
            for ((offset, f) in fermatas.entries.sortedBy { it.key }) {
                val quarters = offset * Fraction.of(4)
                w.start("Fermata").leaf("Type", when (f.kind) { FermataKind.SHORT -> "Short"; FermataKind.MEDIUM -> "Medium"; FermataKind.LONG -> "Long" })
                    .leaf("Offset", "${quarters.numerator}/${quarters.denominator}").leaf("Length", GpifNumbers.decimal((f.multiplier - 1.0).coerceAtLeast(0.0))).end()
            }
            w.end()
        }
        if (measure.forcedSystemBreak) losses.add("layout.breaks", "Erzwungene Systemumbrüche werden nicht exportiert", measure.id)
        if (m > 0 && measure.pickupLength != null) losses.add("rhythm.pickup", "Auftakt ist nur im ersten Takt darstellbar", measure.id)
        w.end()
    }

    private fun directions(w: XmlWriter, measure: Measure) {
        if (measure.navigation.isEmpty()) return
        val hasToCoda = project.measures.any { m -> m.navigation.any { it.kind == NavigationKind.TO_CODA } }
        val hasFine = project.measures.any { m -> m.navigation.any { it.kind == NavigationKind.FINE } }
        fun first(kind: NavigationKind) = project.measures.firstOrNull { m -> m.navigation.any { it.kind == kind } }?.id
        w.start("Directions")
        for (mark in measure.navigation) when (mark.kind) {
            NavigationKind.SEGNO -> w.leaf("Target", "Segno")
            NavigationKind.CODA -> w.leaf("Target", "Coda")
            NavigationKind.FINE -> w.leaf("Target", "Fine")
            else -> Unit
        }
        for (mark in measure.navigation) {
            val target = when (mark.kind) { NavigationKind.DAL_SEGNO -> first(NavigationKind.SEGNO); NavigationKind.TO_CODA -> first(NavigationKind.CODA); else -> null }
            if (mark.target != null && (mark.kind == NavigationKind.DAL_SEGNO || mark.kind == NavigationKind.TO_CODA) && mark.target != target)
                losses.add("repeats.navigation", "Explizites Sprungziel wird nicht exportiert; Guitar Pro springt zum ersten Segno/Coda", measure.id)
            when (mark.kind) {
                NavigationKind.DA_CAPO, NavigationKind.DAL_SEGNO -> w.leaf("Jump", Navigation.jumpName(mark.kind, hasToCoda, hasFine))
                NavigationKind.TO_CODA -> w.leaf("Jump", "DaCoda")
                else -> Unit
            }
        }
        w.end()
    }

    private fun reportProjectLosses() {
        val p = project
        if (p.layout != LayoutSettings(trackOrder = p.layout.trackOrder) || (p.layout.trackOrder.isNotEmpty() && p.layout.trackOrder != p.tracks.map { it.id }))
            losses.add("layout.stylesheet", "Layout-Einstellungen (Ansicht, Zoom, Seitenformat, Spuransicht) werden nicht exportiert")
        if (p.audio.clips.isNotEmpty() || p.audio.syncMaps.isNotEmpty()) losses.add("audio.reference", "Audiospuren und Synchronisation werden nicht in GP7 exportiert")
        if (p.soundBanks.isNotEmpty()) losses.add("sound.banks", "Klangbanken werden nicht exportiert")
        if (p.mix.master != ChannelMix() || p.mix.buses.isNotEmpty()) losses.add("mix.buses", "Master- und Bus-Mischung werden nicht exportiert")
        if (p.mix.automation.isNotEmpty()) losses.add("mix.automation", "Automationen werden nicht exportiert")
        if (p.chords.isNotEmpty()) losses.add("layout.chord_symbols", "Akkordbibliothek wird nicht exportiert")
        if (p.scales.isNotEmpty()) losses.add("tools.scales", "Skalen werden nicht exportiert")
        p.text.filter { it.kind == TextKind.SECTION || it.kind == TextKind.FINGERING || it.style != TextStyle() }.forEach {
            losses.add("layout.text_lyrics", "Textart/-formatierung wird nicht exportiert", it.id)
        }
        for (t in p.tracks) {
            if (t.mix.volume != 0.8 || t.mix.pan != 0.0) losses.add("mix.channels", "Spurlautstärke und Panorama werden nicht exportiert", t.id)
            if (t.mix.effects.isNotEmpty()) losses.add("mix.eq_fx", "Spureffekte werden nicht exportiert", t.id)
            if (t.sound.bankId != null || t.sound.originalRseData != null) losses.add("sound.rse_mapping", "Klangzuordnung (Bank/RSE) wird nicht exportiert; es gilt das MIDI-Programm", t.id)
            val used = t.staves.flatMap { s -> s.bars.flatMap { b -> b.voices.flatMap { v -> v.beats.flatMap { it.notes.mapNotNull(Note::fret) } } } }.maxOrNull() ?: 0
            if (t.instrument.tuning.isNotEmpty() && t.instrument.maxFret != maxOf(24, used)) losses.add("notes.fret", "Bundanzahl wird in GPIF nicht gespeichert", t.id)
        }
    }

    companion object {
        private val COLORS = listOf("235 152 85", "84 155 217", "120 190 90", "200 90 160", "230 200 70", "110 110 220", "220 90 90", "90 200 190")
    }
}
