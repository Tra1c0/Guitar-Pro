package dev.riffworkbench.adapter.formats.qa21

import dev.riffworkbench.adapter.formats.qa15.Qa15FormatRoundTripTest
import dev.riffworkbench.adapter.formats.qa15.Qa15Gen
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-Zyklus 21 / Export-Import: die Projektformen, die Zyklus 18-20 neu ins Produkt gebracht haben, durch dieselbe
 * Rundreise-Prüfung wie die Zyklus-15-Kampagne (`Qa15FormatRoundTripTest.check`: L1 jede Abweichung durch eine
 * Verlustmeldung erklärt, L2 keine Ausnahme, L3 kein ERROR beim Import des eigenen Exports, L4 idempotent nach einer Runde)
 * für GP7, MusicXML, .mxl, MIDI (voll und klingend verglichen), nativ und ASCII.
 *
 * Formen (alle über EditorCommands wie die UI): Standardprojekt (ProjectFactory.create: 1×4/4, E-Standard, Bass, Drums,
 * „Riff A“, 150 BPM) mit Noten; dasselbe nach Umstimmen auf 7 Saiten (B-Standard) und 8 Saiten (F#-Standard) mit Noten auf
 * den neuen tiefen Saiten; Drum-Groove im 32tel-Raster (Drum-Grid); Pinch/Natural Harmonics und Palm Mute; Tempowechsel und
 * Taktart über das Taktangabe-/Tempo-Sheet (`SetTempoAt`, `SetTempo`, `SetSignature`).
 */
class Qa21FormatShapesTest {
    private var n = 0
    private val ids = IdSource { EntityId("q21f-${n++}") }
    private val EditorSession.p get() = state.value.project
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.at(m: Int, off: Fraction, string: Int = 0, track: Int = 0) = select(Selection(p.tracks[track].id, p.measures[m].id, 0, off, string))
    private fun EditorSession.note(m: Int, off: Fraction, string: Int, fret: Int, d: Int = 8, marks: Set<NoteMark> = emptySet(), vararg arts: Articulation) {
        at(m, off, string); ok(EditorCommand.PutNote(fret = fret, duration = WrittenDuration(d), marks = marks))
        for (a in arts) ok(EditorCommand.ApplyArticulation(a, toggle = false))
    }
    private fun f(a: Long, b: Long) = Fraction.of(a, b)

    /**
     * Standardprojekt + 4 Takte: Riff mit PM, Pinch/Natural Harmonic, Bend, Hammer-on; Bass; Drums 8tel + 32tel-Fill; Takt 5
     * per Sheet 7/8. [harmonics] = false für den klingenden MIDI-Vergleich (MIDI spielt Flageoletts auf ihrer klingenden
     * Tonhöhe und meldet `harmonic`; der Vergleich der Kampagne arbeitet mit der notierten Tonhöhe).
     */
    private fun standard(sheet: Boolean = true, harmonics: Boolean = true): EditorSession {
        val s = EditorSession(ProjectFactory(ids).create("Q21 Standard"), ids)
        repeat(4) { s.ok(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        val pm = setOf(NoteMark.PALM_MUTE)
        fun harm(kind: HarmonicKind) = if (harmonics) arrayOf<Articulation>(Articulation.Harmonic(kind)) else emptyArray()
        for (k in 0 until 4) s.note(0, f(k.toLong(), 8), 0, 0, marks = pm)
        s.note(0, f(1, 2), 2, 7, d = 4, arts = harm(HarmonicKind.PINCH))
        s.note(0, f(3, 4), 5, 12, d = 4, arts = harm(HarmonicKind.NATURAL))
        s.note(1, Fraction.ZERO, 3, 7, d = 4, arts = arrayOf(Articulations.fullBend()))
        s.note(1, f(1, 4), 4, 5, marks = setOf(NoteMark.HAMMER_ON)); s.note(1, f(3, 8), 4, 7)
        s.note(1, f(1, 2), 1, 2, d = 2, marks = pm); s.note(1, f(1, 2), 2, 2, d = 2, marks = pm)
        s.note(2, Fraction.ZERO, 5, 5, d = 4, arts = harm(HarmonicKind.PINCH))
        s.note(2, f(1, 4), 0, 3, d = 4); s.note(3, Fraction.ZERO, 0, 0, d = 1)
        // Bass (Spur 1): Viertel auf A.
        for (m in 0 until 4) for (k in 0 until 4) { s.select(Selection(s.p.tracks[1].id, s.p.measures[m].id, 0, f(k.toLong(), 4), 1)); s.ok(EditorCommand.PutNote(fret = 0, duration = WrittenDuration(4))) }
        // Drums (Spur 2) wie im Drum-Grid: Takt 1-2 8tel, Takt 3 32tel-Raster (Fill auf Zz. 3-4), Takt 4 Crash.
        fun drum(m: Int, off: Fraction, midi: Int, d: Int) { s.select(Selection(s.p.tracks[2].id, s.p.measures[m].id, 0, off, 0)); s.ok(EditorCommand.ToggleDrum(midi, WrittenDuration(d))) }
        for (m in 0 until 2) for (k in 0 until 8) { drum(m, f(k.toLong(), 8), 42, 8); if (k % 4 == 0) drum(m, f(k.toLong(), 8), 36, 8); if (k % 4 == 2) drum(m, f(k.toLong(), 8), 38, 8) }
        for (k in 0 until 16) drum(2, f(1, 2) + f(k.toLong(), 32), if (k % 2 == 0) 38 else 45, 32)
        drum(2, Fraction.ZERO, 36, 32); drum(3, Fraction.ZERO, 49, 4); drum(3, Fraction.ZERO, 36, 4)
        if (sheet) {
            // Taktangabe-/Tempo-Sheet: BPM auf Takt 1 (SetTempo), Tempowechsel auf Takt 3 (SetTempoAt), 7/8 auf Takt 4.
            s.ok(EditorCommand.SetTempo(132.0))
            s.ok(EditorCommand.SetTempoAt(s.p.measures[2].id, 96.0))
            s.ok(EditorCommand.SetSignature(s.p.measures[4].id, TimeSignature(7, 8)))
            s.note(4, Fraction.ZERO, 0, 0, d = 4); s.note(4, f(1, 4), 0, 2, d = 4); s.note(4, f(1, 2), 0, 3, d = 4); s.note(4, f(3, 4), 0, 5, d = 8)
        }
        return s
    }

    /** Umstimmen wie der Stimmungs-Editor (Klang behalten), danach Noten auf den neuen tiefen Saiten. */
    private fun retuned(preset: String, harmonics: Boolean = true): EditorSession {
        val s = standard(harmonics = harmonics)
        val t = TuningPresets.all.first { it.id == preset }
        val g = s.p.tracks[0]
        s.ok(EditorCommand.SetInstrument(g.id, g.instrument.copy(tuning = t.tuning, capoByString = List(t.tuning.size) { 0 }), RetuningPolicy.KEEP_SOUNDING_PITCH))
        s.note(2, f(1, 2), 0, 0, d = 4, marks = setOf(NoteMark.PALM_MUTE)); s.note(2, f(3, 4), 1, 2, d = 4)
        return s
    }

    private fun shapes(harmonics: Boolean = true): List<Pair<String, Project>> = listOf(
        "Standard leer" to ProjectFactory(ids).create("Q21 leer"),
        "Standard + Riff/Drums 32tel/Sheet" to standard(harmonics = harmonics).p,
        "Standard ohne Sheet" to standard(sheet = false, harmonics = harmonics).p,
        "7 Saiten (B-Standard) nach Umstimmen" to retuned("g7-std", harmonics).p,
        "8 Saiten (F#-Standard) nach Umstimmen" to retuned("g8-std", harmonics).p,
    )

    /**
     * Befund (rot vor Zyklus 21): Ein Drum-Fill im 32tel-Raster (Drum-Grid seit Zyklus 19) kam aus dem eigenen MIDI-Export
     * auf 1/16 bzw. Triolen quantisiert zurück (jede zweite 32tel um eine 32tel verschoben, Verlust `quantization`), obwohl
     * die Datei die 32tel tick-genau trägt. Jetzt: Fenster, deren Ereignisse exakt auf dem 1/32- oder 1/64-Raster liegen,
     * behalten es.
     */
    @Test fun drumFillOn32ndGridSurvivesTheMidiRoundTrip() {
        for ((label, d, step) in listOf(Triple("32tel", 32, f(1, 32)), Triple("64tel", 64, f(1, 64)))) {
            val s = EditorSession(ProjectFactory(ids).create("Q21 Drums $label"), ids)
            val drums = s.p.tracks[2]
            val hits = (0 until 8).map { k -> (f(1, 2) + step * Fraction.of(k.toLong())) to (if (k % 2 == 0) 38 else 45) } + (Fraction.ZERO to 36)
            for ((off, midi) in hits) { s.select(Selection(drums.id, s.p.measures[0].id, 0, off, 0)); s.ok(EditorCommand.ToggleDrum(midi, WrittenDuration(d))) }
            val e = (dev.riffworkbench.adapter.formats.midi.MidiExporter().exportNow(s.p) as Outcome.Success).value
            val im = (dev.riffworkbench.adapter.formats.midi.MidiImporter().importNow(e.bytes, ids) as Outcome.Success).value
            fun onsets(p: Project) = p.tracks.filter { it.instrument.kind == InstrumentKind.PERCUSSION }.flatMap { t -> t.staves[0].bars.flatMapIndexed { bi, b -> b.voices.flatMap { v -> v.beats.flatMap { bt -> bt.notes.map { n -> Triple(bi, bt.offset, n.pitch.midiNumber) } } } } }.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            val before = onsets(s.p); val after = onsets(im.project)
            println("Q21F Drum-Fill $label über MIDI: vorher $before | nachher $after | Import-Verluste ${im.losses.map { it.featureId }}")
            assertTrue("$label: Drum-Schläge verschoben: vorher $before, nachher $after", before == after)
            assertTrue("$label: unnötige Quantisierung gemeldet: ${im.losses}", im.losses.none { it.featureId == "quantization" })
        }
    }

    @Test fun newProjectShapesSurviveEveryFormatRoundTrip() {
        val rt = Qa15FormatRoundTripTest()
        val failures = ArrayList<String>()
        val all = shapes().map { it to "" } + shapes(harmonics = false).map { it to "midisafe" }
        for ((shape, only) in all) {
            val (name, p) = shape
            for (fmt in listOf("gp7", "musicxml", "mxl", "midi", "midisafe", "native", "ascii")) {
                // Klingender MIDI-Vergleich nur ohne Flageoletts (siehe [standard]); alle anderen Formate mit allem.
                if ((fmt == "midisafe") != (only == "midisafe")) continue
                val format = if (fmt == "midisafe") "midi" else fmt
                val fs = rt.check(format, p, if (fmt == "midisafe") Qa15Gen.Profile.MIDI_SAFE else Qa15Gen.Profile.FULL)
                println("Q21F $name / $fmt: ${if (fs.isEmpty()) "ohne Befund" else fs.joinToString(" | ") { "${it.inv} ${it.category}: ${it.detail.take(400)}" }}")
                fs.forEach { failures += "$name/$fmt ${it.inv} ${it.category}: ${it.detail.take(300)}" }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
