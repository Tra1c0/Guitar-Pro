package dev.riffworkbench.editor

import dev.riffworkbench.model.*

class ProjectFactory(private val ids: IdSource) {
    /**
     * Product default for a new project: one 4/4 bar, 150 BPM, section "Riff A", a 6-string E-standard guitar named
     * "Gitarre" plus bass and drums. The model default InstrumentDefinition() stays Drop A (fixtures and formats rely on it).
     */
    fun create(
        title: String,
        withBand: Boolean = true,
        signatures: List<TimeSignature> = listOf(TimeSignature()),
        guitar: InstrumentDefinition = E_STANDARD,
        guitarName: String = "Gitarre",
    ): Project {
        require(signatures.isNotEmpty()) { "Mindestens ein Takt" }
        val measures = signatures.map { Measure(ids.next(), it) }
        return Project(
            id = ids.next(), metadata = ProjectMetadata(title = title.trim().ifBlank { "Neues Riff" }),
            measures = measures,
            tracks = (if (withBand) listOf(InstrumentKind.GUITAR, InstrumentKind.BASS, InstrumentKind.PERCUSSION) else listOf(InstrumentKind.GUITAR))
                .map { if (it == InstrumentKind.GUITAR) track(it, measures, guitarName, guitar) else track(it, measures) },
            sections = listOf(Section(ids.next(), measures.first().id, "Riff A")),
            tempo = listOf(TempoChange(ScorePosition(measures.first().id), 150.0)),
        )
    }

    /** The shape the app shipped before the one-bar default: 4/4 + 7/8 and a 7-string Drop A guitar. Used by tests and fixtures. */
    fun demo(title: String, withBand: Boolean = true): Project =
        create(title, withBand, listOf(TimeSignature(), TimeSignature(7, 8)), InstrumentDefinition(), "Gitarre · Drop A")

    fun track(kind: InstrumentKind, measures: List<Measure>, name: String? = null, guitar: InstrumentDefinition = InstrumentDefinition()): Track {
        val instrument = when (kind) {
            InstrumentKind.GUITAR -> guitar
            InstrumentKind.BASS -> InstrumentDefinition(kind = kind, tuning = listOf(28, 33, 38, 43), capoByString = List(4) { 0 }, midiProgram = 33)
            InstrumentKind.PERCUSSION -> InstrumentDefinition(kind = kind, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0, drumKit = factoryDrumKit())
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

    companion object {
        /** 6-string E standard, low to high: E2 A2 D3 G3 B3 E4. */
        val E_STANDARD = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = List(6) { 0 })
    }
}

/** Own synthetic data only. No third-party tabs or sounds. */
fun largeFixture(ids: IdSource, measures: Int = 200, tracks: Int = 8): Project {
    val factory = ProjectFactory(ids)
    val template = factory.create("200 Takte · Belastungsfixture")
    val bars = List(measures) { Measure(ids.next()) }
    return template.copy(measures = bars, sections = emptyList(), tempo = listOf(TempoChange(ScorePosition(bars.first().id))), tracks = List(tracks) { factory.track(InstrumentKind.GUITAR, bars, "Track ${it + 1}") })
}
