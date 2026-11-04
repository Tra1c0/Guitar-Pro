package dev.riffworkbench.editor

import dev.riffworkbench.model.*

class ProjectFactory(private val ids: IdSource) {
    fun create(title: String, withBand: Boolean = true): Project {
        val measures = listOf(Measure(ids.next()), Measure(ids.next(), TimeSignature(7, 8)))
        return Project(
            id = ids.next(), metadata = ProjectMetadata(title = title.trim().ifBlank { "Neues Riff" }),
            measures = measures,
            tracks = (if (withBand) listOf(InstrumentKind.GUITAR, InstrumentKind.BASS, InstrumentKind.PERCUSSION) else listOf(InstrumentKind.GUITAR))
                .map { track(it, measures) },
            sections = listOf(Section(ids.next(), measures.first().id, "Riff A")),
            tempo = listOf(TempoChange(ScorePosition(measures.first().id), 150.0)),
        )
    }
    fun track(kind: InstrumentKind, measures: List<Measure>, name: String? = null): Track {
        val instrument = when (kind) {
            InstrumentKind.GUITAR -> InstrumentDefinition()
            InstrumentKind.BASS -> InstrumentDefinition(kind = kind, tuning = listOf(28, 33, 38, 43), capoByString = List(4) { 0 }, midiProgram = 33)
            InstrumentKind.PERCUSSION -> InstrumentDefinition(kind = kind, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0)
            InstrumentKind.PIANO -> InstrumentDefinition(kind = kind, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0)
            InstrumentKind.OTHER -> InstrumentDefinition(kind = kind, tuning = emptyList(), capoByString = emptyList(), midiProgram = 48)
        }
        return Track(ids.next(), name ?: when (kind) {
            InstrumentKind.GUITAR -> "Gitarre · Drop A"
            InstrumentKind.BASS -> "Bass"
            InstrumentKind.PERCUSSION -> "Drums"
            InstrumentKind.PIANO -> "Piano"
            InstrumentKind.OTHER -> "Instrument"
        }, instrument, listOf(Staff(ids.next(), setOf(if (kind == InstrumentKind.PERCUSSION) NotationKind.PERCUSSION else if (instrument.tuning.isEmpty()) NotationKind.STANDARD else NotationKind.TAB), measures.map { bar(it.id) })))
    }
    fun bar(measureId: EntityId) = Bar(ids.next(), measureId, List(4) { Voice(ids.next(), it) })
}

/** Own synthetic data only. No third-party tabs or sounds. */
fun largeFixture(ids: IdSource, measures: Int = 200, tracks: Int = 8): Project {
    val factory = ProjectFactory(ids)
    val template = factory.create("200 Takte · Belastungsfixture")
    val bars = List(measures) { Measure(ids.next()) }
    return template.copy(measures = bars, sections = emptyList(), tempo = listOf(TempoChange(ScorePosition(bars.first().id))), tracks = List(tracks) { factory.track(InstrumentKind.GUITAR, bars, "Track ${it + 1}") })
}
