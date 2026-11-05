package dev.riffworkbench.adapter.formats.gpif

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler

/**
 * Note relations as GPIF can express them: ties, hammer-ons and legato slides always connect a note to the
 * next note on the same string (same pitch for unfretted notes) in the same voice; slurs connect beats.
 * Links that point elsewhere cannot be written and are reported.
 */
internal class ExportRelations(project: Project, private val losses: LossCollector) {
    val tieOrigin = HashSet<EntityId>()
    val tieDestination = HashSet<EntityId>()
    val hopoOrigin = HashSet<EntityId>()
    val hopoDestination = HashSet<EntityId>()
    val legatoSlide = HashSet<EntityId>()
    val slurOriginBeats = HashSet<EntityId>()
    val slurDestinationBeats = HashSet<EntityId>()

    private fun key(n: Note): Any = n.string ?: ("m" + n.pitch.midiNumber)

    init {
        for (track in project.tracks) for (staff in track.staves) for (v in 0 until 4) {
            val beats = staff.bars.flatMap { bar -> bar.voices.firstOrNull { it.index == v }?.beats.orEmpty().sortedBy { it.offset } }
            val nextOf = HashMap<EntityId, Note>()
            val nextBeatOf = HashMap<EntityId, Beat?>()
            val beatOfNote = HashMap<EntityId, Beat>()
            val upcoming = HashMap<Any, Note>()
            var nextBeat: Beat? = null
            for (beat in beats.asReversed()) {
                for (n in beat.notes) { upcoming[key(n)]?.let { nextOf[n.id] = it }; beatOfNote[n.id] = beat }
                for (n in beat.notes) upcoming[key(n)] = n
                nextBeatOf[beat.id] = nextBeat
                if (beat.notes.isNotEmpty()) nextBeat = beat
            }
            for (beat in beats) for (n in beat.notes) {
                val next = nextOf[n.id]
                for (link in n.links) {
                    val adjacent = next != null && next.id == link.target
                    when (link.kind) {
                        LinkKind.TIE -> if (adjacent) { tieOrigin += n.id; tieDestination += link.target } else losses.add("rhythm.tie", "Haltebogen zu einer nicht direkt folgenden Note derselben Saite/Stimme wird nicht exportiert", n.id)
                        LinkKind.HAMMER_ON, LinkKind.PULL_OFF -> if (adjacent) {
                            hopoOrigin += n.id; hopoDestination += link.target
                            val derived = if (next!!.pitch.midiNumber >= n.pitch.midiNumber) LinkKind.HAMMER_ON else LinkKind.PULL_OFF
                            if (derived != link.kind) losses.add("fx.hammer_pull", "Hammer-on/Pull-off-Richtung ergibt sich in GPIF aus der Tonhöhe", n.id)
                        } else losses.add("fx.hammer_pull", "Hammer-on/Pull-off zu einer nicht direkt folgenden Note wird nicht exportiert", n.id)
                        LinkKind.LEGATO_SLIDE -> if (adjacent) legatoSlide += n.id else losses.add("fx.slide", "Legato-Slide zu einer nicht direkt folgenden Note wird nicht exportiert", n.id)
                        LinkKind.SLUR -> {
                            val targetBeat = beatOfNote[link.target]
                            if (targetBeat != null && targetBeat.offsetAfter(beat, beats)) {
                                slurOriginBeats += beat.id; slurDestinationBeats += targetBeat.id
                                val implied = next ?: nextBeatOf[beat.id]?.notes?.firstOrNull()
                                if (implied?.id != link.target) losses.add("rhythm.slur", "Bindebogen-Ziel wird beim Einlesen aus der Folgenote bestimmt", n.id)
                            } else losses.add("rhythm.slur", "Bindebogen über Stimmen oder Spuren hinweg wird nicht exportiert", n.id)
                        }
                    }
                }
                if (n.articulations.any { it == Articulation.Mark(NoteMark.HAMMER_ON) || it == Articulation.Mark(NoteMark.PULL_OFF) }) {
                    hopoOrigin += n.id
                    if (next != null) hopoDestination += next.id else losses.add("fx.hammer_pull", "Hammer-on/Pull-off ohne Folgenote wird nicht exportiert", n.id)
                }
                n.articulations.filterIsInstance<Articulation.Slide>().forEach { s ->
                    if ((s.kind == SlideKind.SHIFT || s.kind == SlideKind.LEGATO) && s.target != null && s.target != next?.id)
                        losses.add("fx.slide", "Slide-Ziel ist in GPIF immer die nächste Note derselben Saite", n.id)
                }
            }
        }
    }

    private fun Beat.offsetAfter(origin: Beat, ordered: List<Beat>): Boolean = ordered.indexOf(this) > ordered.indexOf(origin)
}

/** Per-track values for the GPIF track, staff and note sections. */
internal class ExportTrack(val track: Track, index: Int, losses: LossCollector) {
    val instrument = track.instrument
    val percussion = instrument.kind == InstrumentKind.PERCUSSION
    val stringed = !percussion && instrument.tuning.isNotEmpty()
    /** Tuning with the model's instrument transposition folded in, so that Midi = Pitches[string] + capo + fret. */
    val tuning = instrument.tuning.map { (it + instrument.transposition).coerceIn(0, 127) }
    val capo: Int
    val partialCapo: Int
    val partialFlags: String
    val port: Int
    val primaryChannel: Int
    val secondaryChannel: Int
    val clef = GpifTracks.defaultClef(instrument.kind)
    /** Drum articulations: (midi, technique label or null, display name), in InstrumentSet order. */
    val drums = ArrayList<Triple<Int, String?, String>>()

    init {
        if (instrument.transposition != 0) losses.add("notes.transpose", "Instrument-Transposition wird in die Stimmung eingerechnet", track.id)
        if (instrument.fineTuneCents != 0.0) losses.add("sound.fine_tuning", "Feinstimmung in Cent wird nicht exportiert", track.id)
        // The main capo is the lowest one (a partial capo always sits above it); the most common value could be the higher.
        val counts = instrument.capoByString.groupingBy { it }.eachCount().entries.sortedBy { it.key }
        capo = counts.firstOrNull()?.key ?: 0
        partialCapo = counts.getOrNull(1)?.key ?: 0
        if (counts.size > 2) losses.add("strings.capo", "Mehr als zwei Kapodaster-Positionen sind in GPIF nicht darstellbar", track.id)
        partialFlags = if (counts.size < 2) "0".repeat(tuning.size)
            else instrument.capoByString.indices.reversed().joinToString("") { if (instrument.capoByString[it] == partialCapo) "1" else "0" }
        if (percussion) {
            port = 0; primaryChannel = 9; secondaryChannel = 9
            instrument.drumKit.forEach { e ->
                if (drums.none { it.first == e.midiNote && it.second == null }) drums += Triple(e.midiNote, null, e.name.ifBlank { GpDrums.entry(e.midiNote).name })
                if (e.volume != 1.0 || e.pan != 0.0) losses.add("drums.element_mix", "Lautstärke/Panorama einzelner Schlagzeugelemente werden nicht exportiert", track.id)
                if (e.chokeGroup != ScorePlaybackCompiler.defaultChokeGroup(e.midiNote)) losses.add("drums.choke", "Eigene Choke-Gruppen werden nicht exportiert", track.id)
            }
        } else {
            val slot = index % 7
            port = index / 7
            val channels = (0..15).filter { it != 9 }
            primaryChannel = channels[slot * 2]; secondaryChannel = channels[slot * 2 + 1]
        }
    }

    /** InstrumentArticulation index for a drum note; extra (midi, technique) pairs are appended on first use. */
    fun drumIndex(midi: Int, technique: String?): Int {
        val existing = drums.indexOfFirst { it.first == midi && it.second == technique }
        if (existing >= 0) return existing
        val base = drums.firstOrNull { it.first == midi }?.third ?: GpDrums.entry(midi).name
        drums += Triple(midi, technique, if (technique == null) base else "$base ($technique)")
        return drums.lastIndex
    }

    fun fretFor(string: Int, midi: Int): Int = midi - tuning[string] - instrument.capoByString[string]
}

/** Rhythm de-duplication with stable ids in order of first use. */
internal class RhythmTable(private val losses: LossCollector) {
    val entries = LinkedHashMap<WrittenDuration, Int>()
    fun id(d: WrittenDuration): Int = entries.getOrPut(d) {
        if (GpifUnits.noteValueOf(d.denominator) == null) losses.add("rhythm.duration", "Notenwerte kürzer als 1/256 sind in GPIF nicht darstellbar; der Takt verschiebt sich")
        if (d.dots > 2) losses.add("rhythm.dots", "Mehr als zwei Punkte werden von Guitar Pro vermutlich nicht unterstützt")
        if (d.tuplets.size > 2) losses.add("rhythm.tuplets", "Mehr als zwei verschachtelte Tuplets sind in GPIF nicht darstellbar; der Takt verschiebt sich")
        entries.size
    }

    companion object {
        /**
         * Splits a gap (whole notes) into writable rest durations: binary values greedily, or for odd
         * denominators an n:m tuplet with m the largest power of two below n. Returns null if impossible.
         */
        fun fill(gap: Fraction): List<WrittenDuration>? {
            if (gap <= Fraction.ZERO) return emptyList()
            var odd = gap.denominator
            while (odd % 2 == 0L) odd /= 2
            val tuplet = if (odd == 1L) null else {
                if (odd > 63) return null
                var normal = 1; while (normal * 2 < odd) normal *= 2
                TupletRatio(odd.toInt(), normal)
            }
            val result = ArrayList<WrittenDuration>()
            var remaining = gap
            for (den in listOf(1, 2, 4, 8, 16, 32, 64, 128, 256)) {
                val d = WrittenDuration(den, 0, listOfNotNull(tuplet))
                val unit = d.length()
                while (remaining >= unit) { result += d; remaining = remaining + Fraction.of(-unit.numerator, unit.denominator); if (result.size > 64) return null }
            }
            return if (remaining == Fraction.ZERO) result else null
        }
    }
}
