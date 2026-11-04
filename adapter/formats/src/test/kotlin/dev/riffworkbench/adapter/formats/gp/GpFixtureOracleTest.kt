package dev.riffworkbench.adapter.formats.gp

import dev.riffworkbench.editor.ScoreValidator
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Imports the own PyGuitarPro-generated fixtures (tools/make_gp_fixtures.py) and compares the project with the oracle,
 * i.e. what PyGuitarPro 0.11 reads back from the same bytes. Unit conversions are spelled out here independently
 * of the importer: PyGuitarPro bend values are quarter tones on a 0..12 position grid, channel volume/balance are
 * the raw 0..16 values times 8, durations are note values and offsets whole-note fractions.
 */
class GpFixtureOracleTest {
    private class Fixture(val bytes: ByteArray, val oracle: JsonObject, val result: ImportResult) {
        val project get() = result.project
    }

    /** One played note in time order per (track, voice): oracle beat/note and the imported beat/note. */
    private class Played(val gpString: Int, val oracleBeat: JsonObject, val oracleNote: JsonObject, val note: Note)

    private val common = setOf("gp.track_color", "gp.mix_automation", "gp.mix_instrument", "gp.fade_in", "gp.grace_dynamic",
        "gp.grace_dead", "gp.grace_transition", "gp.channel_effects", "gp.chord_details")

    @Test fun gp3MatchesOracle() {
        val f = load("drop-a-band.gp3")
        compare(f)
        assertEquals(common + "gp.note_length", f.result.losses.map { it.featureId }.toSet())
        assertTrue(ScoreValidator.validate(f.project).any { it.code == "incomplete_bar" && it.severity == Severity.WARNING })
        assertTrue(f.project.text.none { it.kind == TextKind.LYRICS }) // GP3 has no lyrics
    }

    @Test fun gp4MatchesOracle() {
        val f = load("drop-a-band.gp4")
        compare(f)
        assertEquals(common + setOf("gp.overfull_bar", "gp.double_bar", "gp.lyrics_binding", "gp.note_length"), f.result.losses.map { it.featureId }.toSet())
        // The ninth eighth in the 4/4 drum bar is dropped, the first eight are kept.
        val drumBar = f.project.tracks[2].staves.single().bars[0]
        assertEquals(8, drumBar.voices[0].beats.size)
        assertEquals(drumBar.id, f.result.losses.single { it.featureId == "gp.overfull_bar" }.entityId)
        assertTrue(f.project.measures.all { it.feel == RhythmicFeel.SWING_EIGHTH })
        assertLyrics(f.project)
    }

    @Test fun gp510MatchesOracle() = checkGp5("drop-a-band.gp5")

    @Test fun gp500MatchesOracle() = checkGp5("drop-a-band-v500.gp5")

    private fun checkGp5(name: String) {
        val f = load(name)
        compare(f)
        assertLyrics(f.project)
        val gp5 = setOf("gp.layout", "gp.tempo_name", "gp.tempo_transition", "gp.wah", "gp.line_break", "gp.grace_dead",
            "gp.double_bar", "gp.clef", "gp.lyrics_binding", "gp.ottava", "gp.beaming", "gp.note_length", "gp.accidental", "gp.rse")
        assertEquals(common + gp5, f.result.losses.map { it.featureId }.toSet())
        assertEquals("gp5", f.project.provenance.single().format)
        // Two GP5 voices map to voices 0 and 1; voices 2 and 3 stay empty.
        val guitar = f.project.tracks[0].staves.single().bars[0]
        assertEquals(listOf(8, 2, 0, 0), guitar.voices.map { it.beats.size })
        assertTrue(f.project.tracks[3].mix.mute)
        assertEquals(RhythmicFeel.SWING_SIXTEENTH, f.project.measures[5].feel)
    }

    @Test fun gp3AndGp5AgreeOnSharedMusic() {
        val gp3 = load("drop-a-band.gp3").project
        val gp5 = load("drop-a-band.gp5").project
        val tie = { p: Project -> p.tracks[0].staves[0].bars[3].voices[0].beats.last().notes.single() }
        assertEquals(tie(gp3).links.map { it.kind }, tie(gp5).links.map { it.kind })
        assertEquals(listOf(150.0, 120.0), gp3.tempo.map { it.quarterNotesPerMinute })
        assertEquals(listOf(150.0, 120.0, 100.0), gp5.tempo.map { it.quarterNotesPerMinute })
    }

    /** Lyric lines "Riff ein-mal Rif-fe" (from bar 1) and "sie-ben" (from bar 4) on the guitar's first voice. */
    private fun assertLyrics(p: Project) {
        val lyrics = p.text.filter { it.kind == TextKind.LYRICS }
        assertEquals(listOf("Riff", "ein-", "mal", "Rif-", "fe", "sie-", "ben"), lyrics.map { it.text })
        val guitar = p.tracks[0].staves[0].bars
        val first = guitar[0].voices[0].beats.take(5).map { ScorePosition(p.measures[0].id, it.offset) }
        val second = guitar[3].voices[0].beats.take(2).map { ScorePosition(p.measures[3].id, it.offset) }
        assertEquals(first + second, lyrics.map { it.position })
    }

    private fun load(name: String): Fixture {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fixtures/gp") }.first { it.isDirectory }
        val bytes = File(dir, name).readBytes()
        val oracle = Json.parseToJsonElement(File(dir, "$name.json").readText()).jsonObject
        var next = 0
        val result = runBlocking { GpBinaryImporter(name.substringAfterLast('.')).import(bytes, IdSource { EntityId("id${next++}") }) }
        assertTrue("$name: $result", result is Outcome.Success)
        return Fixture(bytes, oracle, (result as Outcome.Success).value)
    }

    private fun compare(f: Fixture) {
        val p = f.project
        val o = f.oracle
        assertEquals(emptyList<Diagnostic>(), ScoreValidator.validate(p).filter { it.severity == Severity.ERROR })
        assertEquals(o.str("title"), p.metadata.title)
        assertEquals(o.str("artist"), p.metadata.artist)
        assertEquals(o.str("album"), p.metadata.album)
        assertEquals(o.str("music")!!.ifBlank { o.str("words")!! }, p.metadata.composer)
        assertEquals(o.str("copyright"), p.metadata.copyright)
        assertTrue(p.metadata.notes.contains(o.str("subtitle")!!) && o.arr("notice").all { p.metadata.notes.contains(it.jsonPrimitive.content) })
        val hash = MessageDigest.getInstance("SHA-256").digest(f.bytes).joinToString("") { "%02x".format(it) }
        assertEquals(hash, p.provenance.single().originalAsset.value)
        assertEquals(listOf(hash), p.assets.map { it.hash.value })
        assertEquals(f.result.losses, p.provenance.single().losses)

        val headers = o.arr("measures").map { it.jsonObject }
        assertEquals(headers.size, p.measures.size)
        headers.forEachIndexed { i, h ->
            val m = p.measures[i]
            assertEquals(TimeSignature(h.int("numerator"), h.int("denominator")), m.signature)
            val key = h.arr("key").map { it.jsonPrimitive.int }
            assertEquals(KeySignature(key[0], key[1] == 1), m.key)
            assertEquals(h.bool("repeatOpen"), m.repeat.starts)
            assertEquals(h.int("repeatClose").takeIf { it >= 0 }?.plus(1), m.repeat.endCount) // GP: repeats; model: plays
            assertEquals((0 until 8).filter { h.int("alternative") and (1 shl it) != 0 }.map { it + 1 }.toSet(), m.repeat.alternateEndings)
            assertEquals(listOf(RhythmicFeel.STRAIGHT, RhythmicFeel.SWING_EIGHTH, RhythmicFeel.SWING_SIXTEENTH)[h.int("tripletFeel")], m.feel)
            val marker = h["marker"] as? JsonObject
            val section = p.sections.singleOrNull { it.measureId == m.id }
            if (marker == null) assertNull(section) else {
                val (r, g, b) = marker.arr("color").map { it.jsonPrimitive.int }
                assertEquals(Section(section!!.id, m.id, marker.str("title")!!, 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()), section)
            }
            val navigation = listOfNotNull(h.str("direction"), h.str("fromDirection")).map { NAVIGATION.getValue(it) }
            assertEquals(navigation.toSet(), m.navigation.map { it.kind }.toSet())
        }

        val expectedTempo = mutableListOf(Triple(0, Fraction.ZERO, o.int("tempo")))
        val tracks = o.arr("tracks").map { it.jsonObject }
        assertEquals(tracks.size, p.tracks.size)
        tracks.forEachIndexed { t, ot ->
            val track = p.tracks[t]
            assertEquals(ot.str("name"), track.name)
            val channel = ot["channel"]!!.jsonObject
            val instrument = track.instrument
            assertEquals(channel.int("instrument"), instrument.midiProgram)
            assertEquals(channel.int("volume") / 128.0, track.mix.volume, 1e-9)
            assertEquals((channel.int("balance") / 8 - 8) / 8.0, track.mix.pan, 1e-9)
            val percussion = ot.bool("percussion")
            val strings = ot.arr("strings").map { it.jsonArray[1].jsonPrimitive.int }
            if (percussion) {
                assertEquals(InstrumentKind.PERCUSSION, instrument.kind)
                assertTrue(instrument.tuning.isEmpty() && instrument.capoByString.isEmpty())
            } else {
                assertEquals(strings.reversed(), instrument.tuning) // GP string 1 = highest; model string 0 = lowest
                assertEquals(List(strings.size) { ot.int("capo") }, instrument.capoByString)
                assertTrue(instrument.maxFret >= ot.int("fretCount"))
            }
            val played = List(2) { ArrayList<Played>() }
            ot.arr("measures").forEachIndexed { mi, om ->
                val measure = p.measures[mi]
                val bar = track.staves.single().bars[mi]
                assertEquals(measure.id, bar.measureId)
                om.jsonObject.arr("voices").forEachIndexed { v, ov ->
                    val expected = keptBeats(ov.jsonArray.map { it.jsonObject }, measure.signature.length())
                    val beats = bar.voices[v].beats
                    assertEquals("track $t bar $mi voice $v", expected.size, beats.size)
                    expected.zip(beats).forEach { (ob, beat) ->
                        compareBeat(p, ob, beat, measure, strings.size, instrument, percussion)
                        ob.arr("notes").map { it.jsonObject }.zip(beat.notes).forEach { (on, note) -> played[v] += Played(on.int("string"), ob, on, note) }
                    }
                    ov.jsonArray.map { it.jsonObject }.filter { it["mix"] is JsonObject && (it["mix"]!!.jsonObject["tempo"] is JsonArray) }.forEach {
                        expectedTempo += Triple(mi, fraction(it.str("offset")!!), it["mix"]!!.jsonObject.arr("tempo")[0].jsonPrimitive.int)
                    }
                }
            }
            played.forEach { links(it) }
        }
        val tempo = expectedTempo.groupBy { it.first to it.second }.map { (key, list) -> key to list.last().third }
            .sortedWith(compareBy({ it.first.first }, { it.first.second }))
            .map { (key, qpm) -> TempoChange(ScorePosition(p.measures[key.first].id, key.second), qpm.toDouble()) }
        assertEquals(tempo, p.tempo)
    }

    /** Beats the importer keeps: GP "empty" beats take no time and an overfull bar is cut at the bar line. */
    private fun keptBeats(beats: List<JsonObject>, length: Fraction): List<JsonObject> {
        val kept = ArrayList<JsonObject>()
        for (beat in beats.filter { it.str("status") != "empty" }) {
            if (fraction(beat.str("offset")!!) + duration(beat).length() > length) break
            kept += beat
        }
        return kept
    }

    private fun duration(beat: JsonObject): WrittenDuration {
        val (enters, times) = beat.arr("tuplet").map { it.jsonPrimitive.int }
        return WrittenDuration(beat.int("duration"), if (beat.bool("dotted")) 1 else 0, if (enters == 1) emptyList() else listOf(TupletRatio(enters, times)))
    }

    private fun compareBeat(p: Project, ob: JsonObject, beat: Beat, measure: Measure, stringCount: Int, instrument: InstrumentDefinition, percussion: Boolean) {
        val where = "${measure.id}@${ob.str("offset")}"
        assertEquals(where, fraction(ob.str("offset")!!), beat.offset)
        assertEquals(where, duration(ob), beat.duration)
        val notes = ob.arr("notes").map { it.jsonObject }
        assertEquals(where, notes.size, beat.notes.size)
        assertEquals(where, notes.isEmpty(), beat.explicitRest)
        val kind = if (ob.bool("rasgueado")) StrumKind.RASGUEADO else StrumKind.STRUM
        assertEquals(where, ob["stroke"]?.jsonArray?.let { s ->
            Strum(kind, if (s[0].jsonPrimitive.content == "up") StrokeDirection.UP else StrokeDirection.DOWN, Fraction.of(1, s[1].jsonPrimitive.long))
        } ?: if (ob.bool("rasgueado")) Strum(kind, StrokeDirection.DOWN) else null, beat.strum)
        ob.str("text")?.let { text -> assertTrue(where, p.text.any { it.kind == TextKind.TEXT && it.text == text && it.position == ScorePosition(measure.id, beat.offset) }) }
        val chord = ob["chord"] as? JsonObject
        if (chord == null) assertNull(beat.chordId) else {
            val definition = p.chords.single { it.id == beat.chordId }
            val frets = chord.arr("strings").map { it.jsonPrimitive.int }
            assertEquals(chord.str("name"), definition.name)
            assertEquals(List(stringCount) { i -> frets.getOrNull(stringCount - 1 - i)?.takeIf { it >= 0 } }, definition.frets)
            assertEquals(definition.frets.mapIndexedNotNull { i, fret -> fret?.let { instrument.pitch(i, it) } }, definition.pitches)
        }
        notes.zip(beat.notes).forEach { (on, note) ->
            val at = "$where string ${on.int("string")}"
            if (percussion) {
                assertEquals(at, Pitch(on.int("value")), note.pitch)
                assertNull(at, note.string); assertNull(at, note.fret)
            } else {
                val string = stringCount - on.int("string")
                assertEquals(at, string, note.string)
                assertEquals(at, on.int("value"), note.fret)
                assertEquals(at, instrument.pitch(string, on.int("value")), note.pitch.midiNumber)
            }
            assertEquals(at, ScorePlaybackCompiler.dynamicVelocity(DynamicLevel.entries[(on.int("velocity") - 15) / 16]), note.velocity)
            val marks = note.articulations.filterIsInstance<Articulation.Mark>().map { it.kind }.toSet() - setOf(NoteMark.HAMMER_ON, NoteMark.PULL_OFF)
            val expectedMarks = buildSet {
                if (on.str("type") == "dead") add(NoteMark.DEAD)
                if (on.bool("ghost")) add(NoteMark.GHOST)
                if (on.bool("accent")) add(NoteMark.ACCENT)
                if (on.bool("heavyAccent")) add(NoteMark.HEAVY_ACCENT)
                if (on.bool("letRing")) add(NoteMark.LET_RING)
                if (on.bool("palmMute")) add(NoteMark.PALM_MUTE)
                if (on.bool("staccato")) add(NoteMark.STACCATO)
                when (ob.str("slap")) { "tapping" -> add(NoteMark.TAPPING); "slapping" -> add(NoteMark.SLAP); "popping" -> add(NoteMark.POP) }
            }
            assertEquals(at, expectedMarks, marks)
            val vibrato = when { ob.bool("wideVibrato") -> listOf(Articulation.Vibrato(depthCents = 80)); on.bool("vibrato") -> listOf(Articulation.Vibrato()); else -> emptyList() }
            assertEquals(at, vibrato, note.of<Articulation.Vibrato>())
            assertEquals(at, listOfNotNull((on["harmonic"] as? JsonObject)?.let { Articulation.Harmonic(HARMONICS.getValue(it.int("type"))) }), note.of<Articulation.Harmonic>())
            assertEquals(at, listOfNotNull((on["bend"] as? JsonObject)?.let { Articulation.Bend(curve(it), BendKind.BEND) }), note.of<Articulation.Bend>())
            assertEquals(at, listOfNotNull((ob["tremoloBar"] as? JsonObject)?.let { Articulation.Whammy(curve(it)) }), note.of<Articulation.Whammy>())
            assertEquals(at, on.arr("slides").map { SLIDES.getValue(it.jsonPrimitive.content) }, note.of<Articulation.Slide>().map { it.kind })
            assertEquals(at, listOfNotNull((on["trill"] as? JsonObject)?.let { Articulation.Trill(it.int("fret"), WrittenDuration(it.int("duration"))) }), note.of<Articulation.Trill>())
            assertEquals(at, listOfNotNull(on["tremoloPicking"]?.let { Articulation.TremoloPicking(WrittenDuration(it.jsonPrimitive.int)) }), note.of<Articulation.TremoloPicking>())
            assertEquals(at, listOfNotNull(ob.str("pickStroke")?.let { Articulation.Pickstroke(if (it == "up") StrokeDirection.UP else StrokeDirection.DOWN) }), note.of<Articulation.Pickstroke>())
            val grace = (on["grace"] as? JsonObject)?.let { g ->
                // Documented GP duration codes: 1 = 1/32, 2 = 1/24, 3 = 1/16 (PyGuitarPro reports powers of two instead).
                val length = listOf(WrittenDuration(32), WrittenDuration(16, tuplets = listOf(TupletRatio(3, 2))), WrittenDuration(16))[g.int("durationCode") - 1]
                val fret = if (g.bool("dead") && !percussion) note.fret!! else g.int("fret")
                Articulation.Grace(Pitch(instrument.pitch(note.string!!, fret)), length, !g.bool("onBeat"), fret, note.string)
            }
            assertEquals(at, listOfNotNull(grace), note.of<Articulation.Grace>())
            val fingering = if (on.containsKey("leftFinger") || on.containsKey("rightFinger"))
                Fingering(on["leftFinger"]?.jsonPrimitive?.int, on["rightFinger"]?.jsonPrimitive?.int?.let { "pimac"[it].toString() }) else null
            assertEquals(at, fingering, note.fingering)
        }
    }

    /** Ties, hammer-on/pull-off direction and slide targets relate a note to the next note on the same string. */
    private fun links(played: List<Played>) {
        played.forEachIndexed { i, current ->
            val next = played.drop(i + 1).firstOrNull { it.gpString == current.gpString }
            val tieTargets = current.note.links.filter { it.kind == LinkKind.TIE }.map { it.target }
            if (next != null && next.oracleNote.str("type") == "tie") {
                assertEquals(listOf(next.note.id), tieTargets)
                assertEquals(current.note.pitch, next.note.pitch)
            } else assertEquals(emptyList<EntityId>(), tieTargets)
            val legato = current.note.of<Articulation.Mark>().map { it.kind }.filter { it == NoteMark.HAMMER_ON || it == NoteMark.PULL_OFF }
            val expected = if (!current.oracleNote.bool("hammer")) emptyList()
                else listOf(if (next != null && next.note.pitch.midiNumber < current.note.pitch.midiNumber) NoteMark.PULL_OFF else NoteMark.HAMMER_ON)
            assertEquals(expected, legato)
            current.note.of<Articulation.Slide>().filter { it.kind == SlideKind.SHIFT || it.kind == SlideKind.LEGATO }.forEach { assertEquals(next?.note?.id, it.target) }
        }
    }

    private fun curve(bend: JsonObject) = bend.arr("points").map { point ->
        val (position, value) = point.jsonArray.take(2).map { it.jsonPrimitive.int }
        PitchCurvePoint(Fraction.of(position.toLong(), 12), value * 50) // quarter tones -> cents
    }

    private inline fun <reified T : Articulation> Note.of(): List<T> = articulations.filterIsInstance<T>()
    private fun fraction(text: String) = text.split('/').let { Fraction.of(it[0].toLong(), it[1].toLong()) }
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.arr(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())

    private companion object {
        val NAVIGATION = mapOf("Coda" to NavigationKind.CODA, "Segno" to NavigationKind.SEGNO, "Fine" to NavigationKind.FINE, "Da Capo" to NavigationKind.DA_CAPO,
            "Da Capo al Fine" to NavigationKind.DA_CAPO, "Da Segno" to NavigationKind.DAL_SEGNO, "Da Segno al Fine" to NavigationKind.DAL_SEGNO, "Da Coda" to NavigationKind.TO_CODA)
        val HARMONICS = mapOf(1 to HarmonicKind.NATURAL, 2 to HarmonicKind.ARTIFICIAL, 3 to HarmonicKind.TAPPED, 4 to HarmonicKind.PINCH, 5 to HarmonicKind.SEMI)
        val SLIDES = mapOf("shiftSlideTo" to SlideKind.SHIFT, "legatoSlideTo" to SlideKind.LEGATO, "outDownwards" to SlideKind.OUT_DOWN,
            "outUpwards" to SlideKind.OUT_UP, "intoFromBelow" to SlideKind.INTO_FROM_BELOW, "intoFromAbove" to SlideKind.INTO_FROM_ABOVE)
    }
}
