package dev.riffworkbench.editor

import dev.riffworkbench.model.EntityId
import dev.riffworkbench.model.IdSource
import dev.riffworkbench.model.InstrumentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA-Zyklus 22: Die Gitarren-Presets sind nach unten auf gleiche Lautheit normiert (High Gain −9,5 dB); dadurch lag die Gitarre
 * einer neuen Band 8,5 dB leiser gegenüber Bass und Drums als auf ef25d88. Neue Projekte bekommen deshalb Werks-Fader 0,48 für
 * Bass und Drums (Gitarre 0,8): Gitarre−Bass / Gitarre−Drums (Solo-RMS) +5,4 / +4,4 dB wie auf ef25d88 (+5,1 / +4,0 dB), gemessen in
 * `Qa22TransitionPinchLoudnessTest.newProjectBandKeepsItsCycle21Balance` (core/synth). Gespeicherte Projekte behalten ihre Fader.
 */
class Qa22BandFaderTest {
    private var n = 0
    private val ids = IdSource { EntityId("q22f-${n++}") }

    @Test fun newBandProjectsStartWithBassAndDrumsUnderTheGuitar() {
        val p = ProjectFactory(ids).create("Band")
        val byKind = p.tracks.associate { it.instrument.kind to it.mix.volume }
        assertEquals(0.8, byKind[InstrumentKind.GUITAR]!!, 0.0)
        assertEquals(0.48, byKind[InstrumentKind.BASS]!!, 0.0)
        assertEquals(0.48, byKind[InstrumentKind.PERCUSSION]!!, 0.0)
        // A track added later keeps the plain factory fader.
        assertEquals(0.8, ProjectFactory(ids).track(InstrumentKind.BASS, p.measures).mix.volume, 0.0)
    }
}
