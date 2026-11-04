package dev.riffworkbench.adapter.formats.gp

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import java.security.MessageDigest

/** Vibrato depth for GP's beat-level "wide vibrato"; plain note vibrato keeps the model default. */
internal const val WIDE_VIBRATO_CENTS = 80

/**
 * Instrument kind heuristic for GP3–5 tracks (tuning low → high):
 * percussion flag or MIDI channel 10 → PERCUSSION; fewer than 3 strings → OTHER (GUITAR/BASS need 3..10);
 * GM bass programs 32–39 → BASS; GM guitar programs 24–31 → GUITAR; otherwise BASS if the highest open string
 * is at most D3 (MIDI 50), else GUITAR.
 */
internal fun classifyInstrument(percussion: Boolean, tuning: List<Int>, program: Int): InstrumentKind = when {
    percussion -> InstrumentKind.PERCUSSION
    tuning.size < 3 -> InstrumentKind.OTHER
    program in 32..39 -> InstrumentKind.BASS
    program in 24..31 -> InstrumentKind.GUITAR
    tuning.max() <= 50 -> InstrumentKind.BASS
    else -> InstrumentKind.GUITAR
}

/** Splits a GP lyric line into beat syllables: whitespace separates, a trailing hyphen stays on its syllable, `+` joins words. */
internal fun lyricChunks(text: String): List<String> {
    val chunks = ArrayList<String>()
    for (word in text.replace(Regex("\\[[^\\]]*]"), " ").split(' ', '\t', '\r', '\n')) {
        var start = 0
        var i = 0
        while (i < word.length) {
            if (word[i] != '-') { i++; continue }
            while (i < word.length && word[i] == '-') i++
            chunks += word.substring(start, i)
            start = i
        }
        if (start < word.length) chunks += word.substring(start)
    }
    return chunks.map { it.replace('+', ' ').trimEnd('_') }.filter { it.isNotBlank() }
}

/**
 * Maps a parsed [GpSong] onto the RiffWorkbench model. Conventions:
 * - model string 0 is the lowest string (GP string 1 is the highest); tuning low → high; capo applies to all strings;
 * - rational whole-note time; GP voice 1/2 → voices 0/1, voices 2/3 stay empty; GP "empty" beats take no time;
 * - the origin of a GP tie gets the `TIE` link, the tied note keeps the origin's fret and pitch;
 * - hammer-on/pull-off from the pitch direction to the next note on the same string (no next note: hammer-on);
 * - bends: position 0..60 → 0..1 of the note, 1/100 tone → 2 cents per unit;
 * - dynamics ppp..fff → the sequencer's [ScorePlaybackCompiler.dynamicVelocity] scale.
 * Everything else that cannot be represented is reported once per feature as a [LossEntry].
 */
internal class GpMapper(private val song: GpSong, private val ids: IdSource) {
    private class Loss(val reason: String, val entity: EntityId?) { var count = 1 }
    private class NoteDraft(val id: EntityId, val string: Int?, val fret: Int?, val pitch: Int, val velocity: Int) {
        val marks = LinkedHashSet<NoteMark>()
        val articulations = ArrayList<Articulation>()
        val links = ArrayList<NoteLink>()
        var slides = emptyList<SlideKind>()
        var slideTarget: EntityId? = null
        var hammer = false
        var fingering: Fingering? = null
        fun build(): Note {
            val slideArticulations = slides.map { kind -> Articulation.Slide(kind, slideTarget.takeIf { kind == SlideKind.SHIFT || kind == SlideKind.LEGATO }) }
            return Note(id, Pitch(pitch), string, fret, velocity, marks.map { Articulation.Mark(it) } + articulations + slideArticulations, links.toList(), fingering)
        }
    }
    private class BeatDraft(
        val id: EntityId, val offset: Fraction, val duration: WrittenDuration, val notes: List<NoteDraft>, val strum: Strum?, val chordId: EntityId?,
    ) {
        fun build() = Beat(id, offset, duration, notes.map { it.build() }, explicitRest = notes.isEmpty(), strum = strum, chordId = chordId)
    }
    private class BarDraft(val id: EntityId, val measureId: EntityId, val voices: List<Pair<EntityId, List<BeatDraft>>>) {
        fun build() = Bar(id, measureId, voices.mapIndexed { index, (voiceId, beats) -> Voice(voiceId, index, beats.map { it.build() }) })
    }
    private class TrackContext(val index: Int, val instrument: InstrumentDefinition, val stringCount: Int) {
        val percussion get() = instrument.kind == InstrumentKind.PERCUSSION
        val last = List(2) { HashMap<Int, NoteDraft>() }
        val hammers = ArrayList<NoteDraft>()
    }
    private data class ChordKey(val name: String, val frets: List<Int?>, val fingers: List<Int?>, val firstFret: Int)

    private val losses = LinkedHashMap<String, Loss>()
    private val measureIds = ArrayList<EntityId>()
    private var measures = emptyList<Measure>()
    private val texts = ArrayList<ScoreText>()
    private val chords = LinkedHashMap<ChordKey, ChordDefinition>()
    private val tempo = LinkedHashMap<Pair<Int, Fraction>, TempoChange>()
    private val tempoFromMix = HashSet<Pair<Int, Fraction>>()
    private val lyricBeats = ArrayList<Pair<Int, Fraction>>()

    private fun loss(feature: String, reason: String, entity: EntityId? = null) {
        val existing = losses[feature]
        if (existing != null) existing.count++ else losses[feature] = Loss(reason, entity)
    }

    fun map(original: ByteArray): ImportResult {
        val projectId = ids.next()
        val navigation = navigation()
        measures = song.headers.mapIndexed { i, header -> measure(header, navigation[i].orEmpty()) }
        val sections = song.headers.mapIndexedNotNull { i, h -> h.marker?.let { Section(ids.next(), measureIds[i], it.title, 0xFF000000L or it.rgb.toLong()) } }
        songLosses()
        putTempo(0, Fraction.ZERO, song.tempo, null, fromMix = false)
        val tracks = song.tracks.mapIndexed { index, track -> track(index, track) }
        lyrics()
        val hash = AssetHash(MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) })
        val entries = losses.map { (feature, loss) ->
            LossEntry(feature, "import", if (loss.count > 1) "${loss.reason} (${loss.count}×)" else loss.reason, loss.entity)
        }
        val project = Project(
            id = projectId, metadata = metadata(), measures = measures, tracks = tracks, sections = sections,
            tempo = tempo.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { it.value },
            assets = listOf(AssetDescriptor(hash, "original.${song.version.formatId}", "application/octet-stream", original.size.toLong())),
            text = texts, chords = chords.values.toList(),
            provenance = listOf(ImportProvenance(song.version.formatId, hash, losses = entries)),
        )
        return ImportResult(project, entries)
    }

    private fun metadata(): ProjectMetadata {
        val info = song.info
        val composer = info.music.ifBlank { info.words }
        val notes = listOfNotNull(
            info.subtitle.takeIf { it.isNotBlank() }?.let { "Untertitel: $it" },
            info.words.takeIf { it.isNotBlank() && it != composer }?.let { "Text: $it" },
            info.tab.takeIf { it.isNotBlank() }?.let { "Tabulatur: $it" },
            info.instructions.takeIf { it.isNotBlank() }?.let { "Hinweise: $it" },
        ) + info.notice.filter { it.isNotBlank() }
        return ProjectMetadata(info.title.trim().ifBlank { "Unbenannter Import" }, info.artist, info.album, composer, info.copyright, notes.joinToString("\n"))
    }

    private fun songLosses() {
        if (song.octave != 0) loss("gp.octave", "Oktavierung der Partitur wird nicht übernommen")
        if (song.tempoName.isNotBlank()) loss("gp.tempo_name", "Tempobezeichnung „${song.tempoName}“ wird nicht übernommen")
        if (song.hideTempo) loss("gp.tempo_display", "Ausgeblendete Tempoanzeige wird nicht übernommen")
        if (song.rse) loss("gp.rse", RSE)
        if (song.version.code >= 500) loss("gp.layout", "Seiten- und Darstellungseinstellungen (Seitenformat, Kopf-/Fußzeilen, Spuranzeige) werden nicht übernommen")
    }

    // GP5 direction table order: Coda, Double Coda, Segno, Segno Segno, Fine, Da Capo (4 variants),
    // Da Segno (4 variants), Da Segno Segno (4 variants), Da Coda, Da Double Coda.
    private fun navigation(): Map<Int, List<NavigationMark>> {
        val kinds = listOf(NavigationKind.CODA, null, NavigationKind.SEGNO, null, NavigationKind.FINE) + List(4) { NavigationKind.DA_CAPO } +
            List(4) { NavigationKind.DAL_SEGNO } + List(4) { null } + listOf(NavigationKind.TO_CODA, null)
        val result = HashMap<Int, MutableSet<NavigationKind>>()
        song.directions.forEachIndexed { k, number ->
            if (number <= 0) return@forEachIndexed
            if (number > song.headers.size) { loss("gp.direction", "Sprungmarke verweist auf einen fehlenden Takt"); return@forEachIndexed }
            val kind = kinds[k]
            if (kind == null || k == 7 || k == 11) loss("gp.direction", "Doppel-Coda- und Segno-Segno-Sprünge werden nicht übernommen")
            if (kind != null) result.getOrPut(number - 1) { LinkedHashSet() } += kind
        }
        return result.mapValues { (_, kinds) -> kinds.map { NavigationMark(it) } }
    }

    private fun measure(h: GpHeader, navigation: List<NavigationMark>): Measure {
        val id = ids.next().also { measureIds += it }
        if (h.doubleBar) loss("gp.double_bar", "Doppelstrich wird nicht übernommen", id)
        if (h.beams != null && h.beams != listOf(2, 2, 2, 2)) loss("gp.beaming", BEAMING, id)
        val key = if (h.keyFifths in -7..7) KeySignature(h.keyFifths, h.keyMinor) else {
            loss("gp.key", "Ungültige Tonart (${h.keyFifths}) durch C-Dur ersetzt", id); KeySignature()
        }
        val feel = when (h.tripletFeel) {
            0 -> RhythmicFeel.STRAIGHT
            1 -> RhythmicFeel.SWING_EIGHTH
            2 -> RhythmicFeel.SWING_SIXTEENTH
            else -> { loss("gp.triplet_feel", "Unbekanntes Triolen-Feeling ${h.tripletFeel} durch gerade Achtel ersetzt", id); RhythmicFeel.STRAIGHT }
        }
        val endings = (0 until 8).filter { h.endings and (1 shl it) != 0 }.map { it + 1 }.toSet()
        val repeat = RepeatMark(h.repeatOpen, h.repeatPlays, endings)
        return Measure(id, TimeSignature(h.numerator, h.denominator), key, repeat = repeat, navigation = navigation, feel = feel)
    }

    private fun putTempo(measure: Int, offset: Fraction, qpm: Int, entity: EntityId?, fromMix: Boolean) {
        val value = qpm.coerceIn(10, 1000)
        if (value != qpm) loss("gp.tempo_range", "Tempo $qpm außerhalb 10–1000 begrenzt", entity)
        val key = measure to offset
        val existing = tempo[key]
        if (existing != null && key in tempoFromMix) {
            if (existing.quarterNotesPerMinute != value.toDouble()) {
                loss("gp.tempo_conflict", "Widersprüchliche Tempoangaben an derselben Position; die erste wird verwendet", entity)
            }
            return
        }
        tempo[key] = TempoChange(ScorePosition(measureIds[measure], offset), value.toDouble())
        if (fromMix) tempoFromMix += key
    }

    private fun track(index: Int, t: GpTrack): Track {
        val channel = song.channels[t.channel]
        val trackId = ids.next()
        val staffId = ids.next()
        val percussion = t.percussion || t.channel % 16 == 9
        val tuning = if (percussion) emptyList() else t.tuning.reversed()
        if (tuning.any { it !in 0..127 }) throw GpFormatException("Spur ${index + 1}: ungültige Stimmung")
        val program = channel.program.coerceIn(0, 127)
        val capo = if (percussion) 0 else t.capo.coerceIn(0, 36)
        if (capo != t.capo && !percussion) loss("gp.capo", "Kapodaster außerhalb 0–36 begrenzt", trackId)
        val kind = classifyInstrument(percussion, tuning, program)
        val instrument = InstrumentDefinition(kind, tuning, List(tuning.size) { capo }, maxFret(index, t, percussion, trackId), program)
        trackLosses(t, channel, trackId, percussion)
        val ctx = TrackContext(index, instrument, t.tuning.size)
        val bars = song.bars.mapIndexed { measure, row -> bar(ctx, measure, row[index]) }
        ctx.hammers.filter { it.hammer }.forEach { it.marks += NoteMark.HAMMER_ON }
        val settings = t.settings
        val notation = when {
            percussion -> setOf(NotationKind.PERCUSSION)
            settings == null -> setOf(NotationKind.TAB, NotationKind.STANDARD)
            else -> setOfNotNull(NotationKind.TAB.takeIf { settings and 0x01 != 0 }, NotationKind.STANDARD.takeIf { settings and 0x02 != 0 })
                .ifEmpty { setOf(NotationKind.TAB) }
        }
        // Channel volume and balance are 0..16 (balance 8 = centre); mute/solo flags exist from GP5 on.
        val gp5 = settings != null
        val mix = ChannelMix((channel.volume / 16.0).coerceIn(0.0, 1.0), ((channel.balance - 8) / 8.0).coerceIn(-1.0, 1.0),
            mute = gp5 && t.flags and 0x20 != 0, solo = gp5 && t.flags and 0x10 != 0)
        return Track(trackId, t.name, instrument, listOf(Staff(staffId, notation, bars.map { it.build() })), mix = mix)
    }

    private fun maxFret(index: Int, t: GpTrack, percussion: Boolean, trackId: EntityId): Int {
        val declared = t.fretCount.coerceIn(1, 48)
        if (percussion) return declared
        if (declared != t.fretCount) loss("gp.fret_count", "Bundzahl ${t.fretCount} auf 1–48 begrenzt", trackId)
        val used = song.bars.flatMap { row -> row[index].voices.flatten() }.flatMap { it.notes }.maxOfOrNull { if (it.fret in 0..48) it.fret else 0 } ?: 0
        if (used > declared) loss("gp.fret_count", "Bundzahl auf den höchsten verwendeten Bund ($used) erhöht", trackId)
        return maxOf(declared, used)
    }

    private fun trackLosses(t: GpTrack, channel: GpChannel, id: EntityId, percussion: Boolean) {
        loss("gp.track_color", "Spurfarben werden nicht übernommen", id)
        if (t.flags and 0x02 != 0) loss("gp.twelve_string", "12-saitige Gitarre wird als normale Gitarre übernommen", id)
        if (t.flags and 0x04 != 0) loss("gp.banjo", "Banjo-Spurtyp wird nicht übernommen", id)
        if (channel.effects.any { it != 0 }) loss("gp.channel_effects", "Kanaleffekte (Chorus, Hall, Phaser, Tremolo) werden nicht übernommen", id)
        if (t.rse) loss("gp.rse", RSE, id)
        if (t.bank != 0) loss("gp.midi_bank", "MIDI-Bankauswahl ${t.bank} wird nicht übernommen", id)
        val settings = t.settings ?: return
        if (settings and 0x0600 != 0) loss("gp.track_auto", "Automatisches Let Ring/Anschlagen der Spur wird nicht übernommen", id)
        if (!percussion && (t.clefs.first != 0 || t.clefs.second != t.clefs.first)) {
            loss("gp.clef", "Notenschlüssel-Transposition der Standardnotation wird nicht übernommen", id)
        }
    }

    private fun bar(ctx: TrackContext, measure: Int, bar: GpBar): BarDraft {
        val barId = ids.next()
        val voiceIds = List(4) { ids.next() }
        if (bar.lineBreak != 0) loss("gp.line_break", "Zeilenumbruch-Markierung wird nicht übernommen", barId)
        val voices = List(4) { v -> voiceIds[v] to (bar.voices.getOrNull(v)?.let { voice(ctx, v, it, measure, barId) } ?: emptyList()) }
        return BarDraft(barId, measureIds[measure], voices)
    }

    private fun voice(ctx: TrackContext, voice: Int, beats: List<GpBeat>, measure: Int, barId: EntityId): List<BeatDraft> {
        val length = measures[measure].signature.length()
        var offset = Fraction.ZERO
        val result = ArrayList<BeatDraft>()
        for (beat in beats) {
            if (beat.status == GpBeat.EMPTY) { positionEvents(beat, measure, offset, barId); continue }
            val duration = duration(beat, barId)
            val end = offset + duration.length()
            if (end > length) { loss("gp.overfull_bar", "Übervoller Takt: überzählige Beats wurden abgeschnitten", barId); break }
            val id = ids.next()
            positionEvents(beat, measure, offset, id)
            val draft = beat(ctx, voice, beat, id, offset, duration)
            if (ctx.index == song.lyricsTrack - 1 && voice == 0 && draft.notes.isNotEmpty()) lyricBeats += measure to offset
            result += draft
            offset = end
        }
        return result
    }

    private fun duration(beat: GpBeat, entity: EntityId): WrittenDuration {
        val tuplet = when (beat.tuplet) {
            0, 1 -> null
            3 -> TupletRatio(3, 2)
            5, 6, 7 -> TupletRatio(beat.tuplet, 4)
            in 9..13 -> TupletRatio(beat.tuplet, 8)
            else -> { loss("gp.tuplet", "Unbekannte N-tole ${beat.tuplet} als gerade Dauer übernommen", entity); null }
        }
        return WrittenDuration(beat.duration, if (beat.dotted) 1 else 0, listOfNotNull(tuplet))
    }

    /** Text and mix-table events also live on GP "empty" beats; they are kept at the current position. */
    private fun positionEvents(beat: GpBeat, measure: Int, offset: Fraction, entity: EntityId) {
        beat.text?.takeIf { it.isNotBlank() }?.let { texts += ScoreText(ids.next(), ScorePosition(measureIds[measure], offset), it) }
        if (beat.status == GpBeat.EMPTY && beat.chord != null) loss("gp.chord", "Akkorddiagramm auf leerem Beat wird nicht übernommen", entity)
        val mix = beat.mix ?: return
        if (mix.tempo >= 0) {
            putTempo(measure, offset, mix.tempo, entity, fromMix = true)
            if (mix.tempoDuration > 0) loss("gp.tempo_transition", "Allmähliche Tempoänderung wird als sofortige Änderung übernommen", entity)
            if (mix.tempoName.isNotBlank()) loss("gp.tempo_name", "Tempobezeichnung „${mix.tempoName}“ wird nicht übernommen", entity)
            if (mix.hideTempo) loss("gp.tempo_display", "Ausgeblendete Tempoanzeige wird nicht übernommen", entity)
        }
        if (mix.instrument >= 0) loss("gp.mix_instrument", "Instrumentwechsel per Mischpult-Ereignis wird nicht übernommen", entity)
        if (mix.mixValues.any { it >= 0 }) loss("gp.mix_automation", "Mischpult-Automation (Lautstärke, Panorama, Effekte) wird nicht übernommen", entity)
        if (mix.wah != -1) loss("gp.wah", "Wah-Wah-Effekt wird nicht übernommen", entity)
        if (mix.rse) loss("gp.rse", RSE, entity)
    }

    private fun beat(ctx: TrackContext, voice: Int, beat: GpBeat, id: EntityId, offset: Fraction, duration: WrittenDuration): BeatDraft {
        var gpNotes = beat.notes
        if (beat.status == GpBeat.REST && gpNotes.isNotEmpty()) { loss("gp.rest_notes", "Noten auf einem Pausen-Beat wurden verworfen", id); gpNotes = emptyList() }
        val fx = beat.effects
        val notes = gpNotes.mapNotNull { note(ctx, voice, beat, it, id) }
        if (fx != null) {
            if (fx.fadeIn) loss("gp.fade_in", "Einblenden (Fade-in) wird nicht übernommen", id)
            val noteEffect = fx.noteVibrato || fx.wideVibrato || fx.harmonic != 0 || fx.slap != 0 || fx.tremoloBar != null || fx.pickStroke != 0
            if (notes.isEmpty() && noteEffect) loss("gp.beat_effect", "Beat-Effekt ohne Noten wird nicht übernommen", id)
            if (fx.slap !in 0..3) loss("gp.slap", "Unbekannter Anschlag (Tapping/Slap/Pop) ${fx.slap}", id)
        }
        if (beat.display and 0x0170 != 0) loss("gp.ottava", "Oktavierungszeichen (8va/8vb/15ma/15mb) werden nicht übernommen", id)
        if (beat.display and 0x0170.inv() != 0) loss("gp.beaming", BEAMING, id)
        val strum = fx?.let { strum(it) }
        val chordId = beat.chord?.let { chord(ctx, it, id) }
        return BeatDraft(id, offset, duration, notes, strum, chordId)
    }

    private fun strum(fx: GpBeatEffects): Strum? {
        val stroke = fx.stroke
        if (stroke == null && !fx.rasgueado) return null
        val speed = stroke?.speed
        val spread = when { speed == null -> Fraction.ZERO; speed in 1..6 -> Fraction.of(1, 128L shr (speed - 1)); else -> Fraction.of(1, 64) }
        return Strum(if (fx.rasgueado) StrumKind.RASGUEADO else StrumKind.STRUM, stroke?.direction ?: StrokeDirection.DOWN, spread)
    }

    private fun velocity(dynamic: Int, entity: EntityId): Int {
        if (dynamic !in 1..8) loss("gp.dynamic", "Ungültige Dynamikstufe $dynamic begrenzt", entity)
        return ScorePlaybackCompiler.dynamicVelocity(DynamicLevel.entries[dynamic.coerceIn(1, 8) - 1])
    }

    private fun note(ctx: TrackContext, voice: Int, beat: GpBeat, n: GpNote, beatId: EntityId): NoteDraft? {
        val last = ctx.last[voice]
        val previous = last[n.string]
        val origin = if (n.type == GpNote.TIE) previous else null
        if (n.type == GpNote.TIE && origin == null) loss("gp.tie_origin", "Haltebogen ohne Ausgangsnote: Bund aus der Datei übernommen", beatId)
        val string: Int?
        val fret: Int?
        val pitch: Int
        if (ctx.percussion) {
            string = null; fret = null
            pitch = origin?.pitch ?: n.fret
        } else {
            string = ctx.stringCount - n.string
            fret = origin?.fret ?: n.fret
            pitch = if (fret in 0..ctx.instrument.maxFret) ctx.instrument.pitch(string, fret) else -1
        }
        if (pitch !in 0..127) { loss("gp.unplayable_note", "Nicht darstellbare Note (Bund oder Tonhöhe außerhalb des Bereichs) verworfen", beatId); return null }
        val draft = NoteDraft(ids.next(), string, fret, pitch, velocity(n.dynamic, beatId))
        origin?.links?.add(NoteLink(LinkKind.TIE, draft.id))
        previous?.let { legato(it, draft) }
        last[n.string] = draft
        if (n.type == GpNote.DEAD) draft.marks += NoteMark.DEAD
        if (n.flags and 0x04 != 0) draft.marks += NoteMark.GHOST
        if (n.flags and 0x40 != 0) draft.marks += NoteMark.ACCENT
        if (n.flags and 0x02 != 0) draft.marks += NoteMark.HEAVY_ACCENT
        if (n.type !in 0..3) loss("gp.note_type", "Unbekannter Notentyp ${n.type} als normale Note übernommen", draft.id)
        if (n.durationPercent != 1.0 || (song.version.code < 500 && n.flags and 0x01 != 0)) {
            loss("gp.note_length", "Abweichende Klingdauer einzelner Noten wird nicht übernommen", draft.id)
        }
        if (n.swapAccidentals) loss("gp.accidental", "Erzwungene Vorzeichen-Schreibweise wird nicht übernommen", draft.id)
        val (left, right) = n.fingers
        if (left in 0..4 || right in 0..4) draft.fingering = Fingering(left.takeIf { it in 0..4 }, right.takeIf { it in 0..4 }?.let { "pimac"[it].toString() })
        n.effects?.let { noteEffects(ctx, it, n, draft) }
        beat.effects?.let { beatEffectsOnNote(ctx, it, draft) }
        return draft
    }

    /** Resolves the hammer-on/pull-off direction and the slide target of [from] once the next note on its string is known. */
    private fun legato(from: NoteDraft, to: NoteDraft) {
        if (from.hammer) { from.marks += if (to.pitch < from.pitch) NoteMark.PULL_OFF else NoteMark.HAMMER_ON; from.hammer = false }
        if (from.slides.isNotEmpty() && from.slideTarget == null) from.slideTarget = to.id
    }

    private fun noteEffects(ctx: TrackContext, e: GpNoteEffects, n: GpNote, draft: NoteDraft) {
        if (e.letRing) draft.marks += NoteMark.LET_RING
        if (e.palmMute) draft.marks += NoteMark.PALM_MUTE
        if (e.staccato) draft.marks += NoteMark.STACCATO
        if (e.hammer) { draft.hammer = true; ctx.hammers += draft }
        e.bend?.let { bend -> curve(bend, draft.id)?.let { draft.articulations += Articulation.Bend(it, bendKind(bend.type, it)) } }
        e.grace?.let { grace(ctx, it, n, draft) }
        if (e.tremoloPicking != 0) {
            val rate = when (e.tremoloPicking) { 1 -> 8; 2 -> 16; 3 -> 32; else -> null }
            if (rate != null) draft.articulations += Articulation.TremoloPicking(WrittenDuration(rate))
            else loss("gp.tremolo_picking", "Unbekannte Tremolo-Picking-Geschwindigkeit ${e.tremoloPicking}", draft.id)
        }
        if (e.slides and GpSlide.UNKNOWN != 0) loss("gp.slide", "Unbekannte Slide-Art wird nicht übernommen", draft.id)
        draft.slides = listOf(GpSlide.SHIFT to SlideKind.SHIFT, GpSlide.LEGATO to SlideKind.LEGATO, GpSlide.OUT_DOWN to SlideKind.OUT_DOWN,
            GpSlide.OUT_UP to SlideKind.OUT_UP, GpSlide.IN_BELOW to SlideKind.INTO_FROM_BELOW, GpSlide.IN_ABOVE to SlideKind.INTO_FROM_ABOVE)
            .filter { e.slides and it.first != 0 }.map { it.second }
        e.harmonic?.let { harmonic(ctx, it, draft) }
        e.trill?.let { (fret, period) ->
            val rate = when (period) { 1 -> 16; 2 -> 32; 3 -> 64; else -> null }
            if (rate != null && fret in 0..48) draft.articulations += Articulation.Trill(fret, WrittenDuration(rate))
            else loss("gp.trill", "Triller mit ungültigem Bund oder Tempo wird nicht übernommen", draft.id)
        }
        if (e.vibrato) draft.articulations += Articulation.Vibrato()
    }

    private fun beatEffectsOnNote(ctx: TrackContext, fx: GpBeatEffects, draft: NoteDraft) {
        if (fx.wideVibrato) {
            draft.articulations.removeAll { it is Articulation.Vibrato }
            draft.articulations += Articulation.Vibrato(depthCents = WIDE_VIBRATO_CENTS)
        } else if (fx.noteVibrato && draft.articulations.none { it is Articulation.Vibrato }) draft.articulations += Articulation.Vibrato()
        if (fx.harmonic != 0) harmonic(ctx, GpHarmonic(fx.harmonic), draft)
        when (fx.slap) { 1 -> draft.marks += NoteMark.TAPPING; 2 -> draft.marks += NoteMark.SLAP; 3 -> draft.marks += NoteMark.POP }
        fx.tremoloBar?.let { bar -> curve(bar, draft.id)?.let { draft.articulations += Articulation.Whammy(it) } }
        when (fx.pickStroke) {
            0 -> Unit
            1 -> draft.articulations += Articulation.Pickstroke(StrokeDirection.UP)
            2 -> draft.articulations += Articulation.Pickstroke(StrokeDirection.DOWN)
            else -> loss("gp.pickstroke", "Unbekannte Anschlagsrichtung ${fx.pickStroke}", draft.id)
        }
    }

    private fun curve(bend: GpBend, entity: EntityId): List<PitchCurvePoint>? {
        if (bend.points.isEmpty()) return null
        if (bend.points.any { it.vibrato }) loss("gp.bend_vibrato", "Vibrato an Bend-Punkten wird nicht übernommen", entity)
        if (bend.points.any { it.position !in 0..60 }) loss("gp.bend_range", "Bend-Punkt außerhalb der Notendauer begrenzt", entity)
        return bend.points.map { PitchCurvePoint(Fraction.of(it.position.coerceIn(0, 60).toLong(), 60), it.value.coerceIn(-2400, 2400) * 2) }
    }

    private fun bendKind(type: Int, points: List<PitchCurvePoint>): BendKind = when (type) {
        1, 2, 3 -> BendKind.BEND
        4 -> BendKind.PREBEND
        5 -> BendKind.PREBEND_RELEASE
        else -> {
            val sorted = points.sortedBy { it.position }
            when { sorted.first().cents <= 0 -> BendKind.BEND; sorted.last().cents < sorted.first().cents -> BendKind.PREBEND_RELEASE; else -> BendKind.PREBEND }
        }
    }

    private fun harmonic(ctx: TrackContext, h: GpHarmonic, draft: NoteDraft) {
        val kind = when (h.kind) {
            1 -> HarmonicKind.NATURAL
            2 -> HarmonicKind.ARTIFICIAL
            3 -> HarmonicKind.TAPPED
            4 -> HarmonicKind.PINCH
            5 -> HarmonicKind.SEMI
            else -> { loss("gp.harmonic", "Unbekannte Flageolett-Art ${h.kind} wird nicht übernommen", draft.id); return }
        }
        val octave = when (kind) {
            // GP5 names the harmonic's pitch class; writers differ on whether the capo is included.
            HarmonicKind.ARTIFICIAL -> h.octaveHarmonic && (h.pitchClass == null || h.pitchClass == draft.pitch % 12 ||
                h.pitchClass == (draft.pitch - (draft.string?.let { ctx.instrument.capoByString[it] } ?: 0)) % 12)
            HarmonicKind.TAPPED -> h.tappedFret == null || draft.fret == null || h.tappedFret == draft.fret + 12
            else -> true
        }
        if (!octave) loss("gp.harmonic_interval", "Flageolett-Intervall außer der Oktave wird nicht übernommen", draft.id)
        draft.articulations.removeAll { it is Articulation.Harmonic }
        draft.articulations += Articulation.Harmonic(kind)
    }

    private fun grace(ctx: TrackContext, g: GpGrace, n: GpNote, draft: NoteDraft) {
        val duration = when (g.duration) {
            1 -> WrittenDuration(32)
            2 -> WrittenDuration(16, tuplets = listOf(TupletRatio(3, 2))) // 1/24
            3 -> WrittenDuration(16)
            else -> { loss("gp.grace_duration", "Unbekannte Dauer der Vorschlagsnote durch 1/32 ersetzt", draft.id); WrittenDuration(32) }
        }
        if (g.transition != 0) loss("gp.grace_transition", "Übergang der Vorschlagsnote (Slide, Bend, Hammer-on) wird nicht übernommen", draft.id)
        if (g.dead) loss("gp.grace_dead", "Gedämpfte Vorschlagsnote wird als normale Vorschlagsnote übernommen", draft.id)
        if (g.dynamic != n.dynamic) loss("gp.grace_dynamic", "Eigene Dynamik der Vorschlagsnote wird nicht übernommen", draft.id)
        val string = draft.string
        if (ctx.percussion || string == null) {
            draft.articulations += Articulation.Grace(Pitch(if (g.fret in 0..127 && !g.dead) g.fret else draft.pitch), duration, !g.onBeat)
            return
        }
        val fret = if (g.dead) draft.fret ?: 0 else g.fret
        val pitch = if (fret in 0..ctx.instrument.maxFret) ctx.instrument.pitch(string, fret) else -1
        if (pitch !in 0..127) { loss("gp.grace", "Vorschlagsnote außerhalb des Griffbretts verworfen", draft.id); return }
        draft.articulations += Articulation.Grace(Pitch(pitch), duration, !g.onBeat, fret, string)
    }

    private fun chord(ctx: TrackContext, c: GpChord, beatId: EntityId): EntityId? {
        if (ctx.percussion) { loss("gp.chord", "Akkorddiagramm auf einer Schlagzeugspur wird nicht übernommen", beatId); return null }
        if (c.details) loss("gp.chord_details", "Barré- und Auslassungsangaben im Akkorddiagramm werden nicht übernommen", beatId)
        val n = ctx.stringCount
        val frets = List(n) { i -> c.frets.getOrNull(n - 1 - i)?.takeIf { it in 0..ctx.instrument.maxFret } }
        val fingers = if (c.fingers.isEmpty()) emptyList() else List(n) { i -> c.fingers.getOrNull(n - 1 - i)?.takeIf { it in 0..4 } }
        val key = ChordKey(c.name, frets, fingers, c.firstFret.coerceAtLeast(1))
        return chords.getOrPut(key) {
            val pitches = frets.mapIndexedNotNull { i, fret -> fret?.let { ctx.instrument.pitch(i, it) } }.filter { it in 0..127 }
            ChordDefinition(ids.next(), c.name, pitches, frets, fingers, key.firstFret)
        }.id
    }

    private fun lyrics() {
        val lines = song.lyrics.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return
        if (song.lyricsTrack !in 1..song.tracks.size) { loss("gp.lyrics", "Liedtext ohne gültige Spur wird nicht übernommen"); return }
        for (line in lines) {
            val start = line.startMeasure - 1
            if (start !in measureIds.indices) { loss("gp.lyrics", "Liedtext beginnt in einem fehlenden Takt und wird nicht übernommen"); continue }
            val chunks = lyricChunks(line.text)
            val targets = lyricBeats.filter { it.first >= start }
            chunks.zip(targets).forEach { (chunk, at) -> texts += ScoreText(ids.next(), ScorePosition(measureIds[at.first], at.second), chunk, TextKind.LYRICS) }
            if (chunks.size > targets.size) loss("gp.lyrics", "Liedtext hat mehr Silben als Noten; überzählige Silben fehlen")
        }
        if (lines.size > 1 || song.tracks.size > 1) {
            loss("gp.lyrics_binding", "Liedtext steht als Silben an den Beat-Positionen; Zuordnung zu Spur und Liedtextzeile ist nicht abbildbar")
        }
    }

    private companion object {
        const val RSE = "RSE-Klang- und Effekteinstellungen werden nicht übernommen"
        const val BEAMING = "Balken- und Klammerdarstellung wird nicht übernommen"
    }
}
