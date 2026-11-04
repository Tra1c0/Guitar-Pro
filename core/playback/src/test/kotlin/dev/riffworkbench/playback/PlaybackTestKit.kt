package dev.riffworkbench.playback

import dev.riffworkbench.model.*

/** Builds small own projects for compiler tests. */
class PlaybackTestKit {
    private var n = 0
    fun id() = EntityId("k${n++}")
    fun measure(signature: TimeSignature = TimeSignature(), repeat: RepeatMark = RepeatMark(), nav: List<NavigationMark> = emptyList(), feel: RhythmicFeel = RhythmicFeel.STRAIGHT) =
        Measure(id(), signature, repeat = repeat, navigation = nav, feel = feel)
    fun guitar(measures: List<Measure>, beats: Map<Int, List<Beat>> = emptyMap(), voice1: Map<Int, List<Beat>> = emptyMap()) = Track(id(), "Gitarre", InstrumentDefinition(),
        listOf(Staff(id(), bars = measures.mapIndexed { i, m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) beats[i].orEmpty() else if (v == 1) voice1[i].orEmpty() else emptyList()) }) })))
    fun drums(measures: List<Measure>, beats: Map<Int, List<Beat>>) = Track(id(), "Drums", InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0),
        listOf(Staff(id(), setOf(NotationKind.PERCUSSION), measures.mapIndexed { i, m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) beats[i].orEmpty() else emptyList()) }) })))
    fun project(measures: List<Measure>, tracks: List<Track>, bpm: Double = 120.0, extraTempo: List<TempoChange> = emptyList()) =
        Project(id = id(), measures = measures, tracks = tracks, tempo = listOf(TempoChange(ScorePosition(measures.first().id), bpm)) + extraTempo)
    fun note(string: Int, fret: Int, vararg articulations: Articulation, links: List<NoteLink> = emptyList(), id: EntityId = id()): Note {
        val instrument = InstrumentDefinition()
        return Note(id, Pitch(instrument.pitch(string, fret)), string, fret, articulations = articulations.toList(), links = links)
    }
    fun beat(offset: Fraction, denominator: Int, vararg notes: Note, strum: Strum? = null, fermata: Fermata? = null) =
        Beat(id(), offset, WrittenDuration(denominator), notes.toList(), strum = strum, fermata = fermata)
}
