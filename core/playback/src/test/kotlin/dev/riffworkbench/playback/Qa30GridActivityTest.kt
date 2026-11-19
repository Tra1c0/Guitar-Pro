package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 30: time base of the drum grid's hit glow (score clock over the tempo map, glow intensity over real time) and the
 * guitar lane (spans/attacks per bar, union of several guitars, sounding section).
 */
class Qa30GridActivityTest {
    private val m = List(4) { Measure(EntityId("q30m$it")) }
    private var n = 0
    private fun id() = EntityId("q30-${n++}")
    private fun beat(at: Fraction, d: Int, vararg notes: Note) = Beat(id(), at, WrittenDuration(d), notes.toList(), explicitRest = notes.isEmpty())
    private fun note(midi: Int = 40, tie: EntityId? = null, noteId: EntityId = id()) = Note(noteId, Pitch(midi), string = 0, fret = 0, links = listOfNotNull(tie?.let { NoteLink(LinkKind.TIE, it) }))
    private fun track(kind: InstrumentKind, bars: List<List<Beat>>) = Track(id(), kind.name, InstrumentDefinition(kind = kind),
        staves = listOf(Staff(id(), bars = bars.mapIndexed { i, beats -> Bar(id(), m[i].id, listOf(Voice(id(), 0, beats))) })))
    /** 4 bars 4/4: bars 1–2 at 120 BPM (2 s per bar), bars 3–4 at 60 BPM (4 s). */
    private fun project(vararg tracks: Track) = Project(id = EntityId("q30"), measures = m, tracks = tracks.toList(),
        tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0), TempoChange(ScorePosition(m[2].id), 60.0)))

    @Test fun scoreClockFollowsTheTempoMapOverTheBars() {
        val c = ScoreClock(project())
        assertArrayEquals(doubleArrayOf(0.0, 2.0, 4.0, 8.0, 12.0), c.barStart, 1e-9)
        assertEquals(0.5, c.seconds(0, 0.25), 1e-9)
        assertEquals(2.5, c.seconds(m[1].id, 0.25), 1e-9)
        assertEquals(5.0, c.seconds(2, 0.25), 1e-9)
        assertTrue(c.seconds(EntityId("none"), 0.0).isNaN())
    }

    @Test fun glowStartsAtTheHitAndFadesOverTheDecay() {
        assertEquals(1f, HitGlow.intensity(1.0, 1.0), 1e-6f)
        assertEquals(0f, HitGlow.intensity(1.0, 0.999), 0f)
        val half = HitGlow.intensity(1.0, 1.0 + HitGlow.DECAY_SECONDS / 2)
        assertEquals(0.25f, half, 1e-4f)
        assertEquals(0f, HitGlow.intensity(1.0, 1.0 + HitGlow.DECAY_SECONDS), 0f)
        // Monotonic fade.
        var last = 2f
        for (k in 0..16) { val v = HitGlow.intensity(0.0, k * 0.01); assertTrue(v <= last); last = v }
        // Half speed: the same score distance is twice the real time.
        assertEquals(0f, HitGlow.intensity(0.0, 0.1, speed = 0.5), 0f)
        assertTrue(HitGlow.intensity(0.0, 0.1, speed = 2.0) > HitGlow.intensity(0.0, 0.1))
    }

    @Test fun glowingHitsAtPlanTimeIncludingTheLastHitOfTheBarBefore() {
        val drums = track(InstrumentKind.PERCUSSION, listOf(
            listOf(beat(Fraction.ZERO, 8, note(36)), beat(Fraction.of(1, 8), 8, note(42)), beat(Fraction.of(31, 32), 32, note(38))),
            listOf(beat(Fraction.ZERO, 4, note(36), note(49)))))
        val p = project(drums)
        val clock = ScoreClock(p)
        val hits = HitGlow.hits(p, clock, drums, 0)
        assertEquals(listOf(0.0, 0.25, 1.9375, 2.0, 2.0), hits.map { it.seconds })
        assertEquals(listOf(36), HitGlow.glowing(hits, 0.05).map { it.first.midi })
        assertTrue(HitGlow.glowing(hits, 0.2).isEmpty())
        // Downbeat of bar 2: kick + crash at full glow, the snare 1/32 before the barline (62.5 ms) still fading.
        val atTwo = HitGlow.glowing(hits, 2.0)
        assertEquals(setOf(36 to 1, 49 to 1, 38 to 0), atTwo.map { it.first.midi to it.first.bar }.toSet())
        assertEquals(1f, atTwo.first { it.first.midi == 49 }.second, 1e-6f)
        assertTrue(atTwo.first { it.first.midi == 38 }.second in 0.01f..0.99f)
    }

    @Test fun guitarLaneHasAttacksSpansTiesAndRests() {
        val tied = id()
        val guitar = track(InstrumentKind.GUITAR, listOf(
            listOf(beat(Fraction.ZERO, 8, note()), beat(Fraction.of(1, 8), 8), beat(Fraction.of(1, 4), 4, note()), beat(Fraction.of(1, 2), 2, note(tie = tied))),
            listOf(beat(Fraction.ZERO, 4, note(noteId = tied)), beat(Fraction.of(1, 4), 4), beat(Fraction.of(1, 2), 2)),
            listOf(), listOf()))
        val lanes = GuitarActivity.lanes(project(guitar))
        assertEquals(4, lanes.size)
        assertEquals(listOf(Fraction.ZERO to Fraction.of(1, 8), Fraction.of(1, 4) to Fraction.ONE), lanes[0].spans)
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(1, 2)), lanes[0].attacks)
        // The tied continuation extends a span without an attack; the rests stay empty.
        assertEquals(listOf(Fraction.ZERO to Fraction.of(1, 4)), lanes[1].spans)
        assertTrue(lanes[1].attacks.isEmpty())
        assertTrue(lanes[2].empty)
        // Sounding sections.
        assertEquals(Fraction.of(1, 4) to Fraction.of(1, 2), GuitarActivity.soundingAt(lanes[0], 0.3))
        assertEquals(Fraction.of(1, 2) to Fraction.ONE, GuitarActivity.soundingAt(lanes[0], 0.6))
        assertNull(GuitarActivity.soundingAt(lanes[0], 0.2))
        assertEquals(Fraction.ZERO to Fraction.of(1, 4), GuitarActivity.soundingAt(lanes[1], 0.1))
        assertNull(GuitarActivity.soundingAt(lanes[1], 0.5))
    }

    @Test fun severalGuitarsShowTheirCommonActivityAndNoGuitarNoLane() {
        val a = track(InstrumentKind.GUITAR, listOf(listOf(beat(Fraction.ZERO, 2, note())), listOf(), listOf(), listOf()))
        val b = track(InstrumentKind.GUITAR, listOf(listOf(beat(Fraction.of(1, 4), 4, note()), beat(Fraction.of(3, 4), 4, note())), listOf(), listOf(), listOf()))
        val bass = track(InstrumentKind.BASS, listOf(listOf(beat(Fraction.ZERO, 1, note(28))), listOf(), listOf(), listOf()))
        val lanes = GuitarActivity.lanes(project(a, b, bass))
        assertEquals(listOf(Fraction.ZERO to Fraction.of(1, 2), Fraction.of(3, 4) to Fraction.ONE), lanes[0].spans)
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(3, 4)), lanes[0].attacks)
        assertTrue(GuitarActivity.lanes(project(bass)).isEmpty())
    }
}
