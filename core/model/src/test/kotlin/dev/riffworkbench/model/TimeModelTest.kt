package dev.riffworkbench.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TimeModelTest {
    @Test fun nestedTupletsRemainExact() {
        val duration = WrittenDuration(8, tuplets = listOf(TupletRatio(3, 2), TupletRatio(5, 4)))
        assertEquals(Fraction.of(1, 15), duration.length())
        assertEquals(Fraction.ONE, List(15) { duration.length() }.fold(Fraction.ZERO) { a, b -> a + b })
    }
    @Test fun dottedDurationsAndNegativeOffsetsNormalize() {
        assertEquals(Fraction.of(3, 16), WrittenDuration(8, 1).length())
        assertEquals(Fraction.of(-1, 2), Fraction.of(-8, 16))
        assertEquals(Fraction.ZERO, Fraction.of(1, 3) + Fraction.of(-1, 3))
    }
    @Test fun rationalOverflowIsExplicit() {
        assertThrows(IllegalArgumentException::class.java) { Fraction(Long.MAX_VALUE) + Fraction.ONE }
        assertThrows(IllegalArgumentException::class.java) { Fraction(Long.MAX_VALUE) * Fraction(2) }
        assertThrows(IllegalArgumentException::class.java) { Fraction.of(1, 0) }
    }
    @Test fun cancellationBeforeMultiplicationAvoidsUnnecessaryOverflow() {
        assertEquals(Fraction.ONE, Fraction.of(Long.MAX_VALUE, 2) * Fraction.of(2, Long.MAX_VALUE))
    }
    @Test fun dropAHasCorrectOctaveAndPartialCapo() {
        val instrument = InstrumentDefinition(capoByString = listOf(2, 0, 0, 0, 0, 0, 0))
        assertEquals(listOf(33, 40, 45, 50, 55, 59, 64), instrument.tuning)
        assertEquals(35, instrument.pitch(0, 0))
        assertEquals(64, instrument.pitch(6, 0))
    }
    @Test fun techniqueFamiliesRoundTripWithoutFlatteningParameters() {
        val articulations: List<Articulation> = listOf(
            *NoteMark.entries.map { Articulation.Mark(it) }.toTypedArray(),
            Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.ONE, 125)), BendKind.PREBEND_RELEASE),
            Articulation.Whammy(listOf(PitchCurvePoint(Fraction.ONE, -200))),
            Articulation.Slide(SlideKind.LEGATO, EntityId("target")), Articulation.Harmonic(HarmonicKind.PINCH, Fraction.of(1, 3)),
            Articulation.Vibrato(), Articulation.Trill(7), Articulation.TremoloPicking(), Articulation.Grace(Pitch(62)),
            Articulation.Dynamic(DynamicLevel.FF), Articulation.Pickstroke(StrokeDirection.DOWN), Articulation.Percussion(PercussionTechnique.CHOKE, 2),
        )
        val json = Json { encodeDefaults = true }
        assertEquals(articulations, json.decodeFromString<List<Articulation>>(json.encodeToString(articulations)))
    }
    @Test fun independentBendsAndRepeatOccurrencesHaveDistinctIdentity() {
        val a = Note(EntityId("a"), Pitch(45), articulations = listOf(Articulation.Bend(listOf(PitchCurvePoint(Fraction.ONE, 200)))))
        val b = Note(EntityId("b"), Pitch(52))
        assertTrue(b.articulations.isEmpty())
        assertNotEquals(a.id, b.id)
        val position = ScorePosition(EntityId("bar"))
        assertNotEquals(PlaybackOccurrence(position, 1, 0), PlaybackOccurrence(position, 2, 3))
    }
    @Test fun invalidStorageIdentifiersAreRejectedAtTheBoundary() {
        assertThrows(IllegalArgumentException::class.java) { EntityId("../outside") }
        assertThrows(IllegalArgumentException::class.java) { AssetHash("../../asset") }
    }
    @Test fun polymorphicWireTagsAreIndependentOfKotlinClassNames() {
        val articulation: Articulation = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ONE, 100)))
        val encoded = Json.encodeToString(articulation)
        assertTrue(encoded.contains("\"type\":\"bend\""))
        assertFalse(encoded.contains("dev.riffworkbench"))
    }
}
