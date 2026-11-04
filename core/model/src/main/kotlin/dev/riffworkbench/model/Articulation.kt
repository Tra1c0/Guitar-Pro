package dev.riffworkbench.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Parameterized techniques are distinct types, not opaque importer flags. */
@Serializable
sealed interface Articulation {
    @Serializable @SerialName("mark") data class Mark(val kind: NoteMark) : Articulation
    @Serializable @SerialName("bend") data class Bend(val points: List<PitchCurvePoint>, val kind: BendKind = BendKind.BEND) : Articulation
    @Serializable @SerialName("whammy") data class Whammy(val points: List<PitchCurvePoint>) : Articulation
    @Serializable @SerialName("slide") data class Slide(val kind: SlideKind, val target: EntityId? = null) : Articulation
    @Serializable @SerialName("harmonic") data class Harmonic(val kind: HarmonicKind, val node: Fraction? = null, val soundingPitch: Pitch? = null) : Articulation
    @Serializable @SerialName("vibrato") data class Vibrato(val depthCents: Int = 30, val rateHz: Double = 5.0) : Articulation
    @Serializable @SerialName("trill") data class Trill(val targetFret: Int, val rate: WrittenDuration = WrittenDuration(32)) : Articulation
    @Serializable @SerialName("tremolo_picking") data class TremoloPicking(val rate: WrittenDuration = WrittenDuration(32)) : Articulation
    @Serializable @SerialName("grace") data class Grace(val pitch: Pitch, val duration: WrittenDuration = WrittenDuration(32), val beforeBeat: Boolean = true, val fret: Int? = null, val string: Int? = null) : Articulation
    @Serializable @SerialName("dynamic") data class Dynamic(val level: DynamicLevel) : Articulation
    @Serializable @SerialName("pickstroke") data class Pickstroke(val direction: StrokeDirection) : Articulation
    @Serializable @SerialName("percussion") data class Percussion(val kind: PercussionTechnique, val chokeGroup: Int? = null) : Articulation
}

enum class NoteMark { PALM_MUTE, DEAD, GHOST, ACCENT, HEAVY_ACCENT, LET_RING, STACCATO, HAMMER_ON, PULL_OFF, TAPPING, PICK_SCRAPE, SLAP, POP }
enum class BendKind { BEND, PREBEND, RELEASE, PREBEND_RELEASE }
enum class SlideKind { SHIFT, LEGATO, INTO_FROM_ABOVE, INTO_FROM_BELOW, OUT_UP, OUT_DOWN }
enum class HarmonicKind { NATURAL, ARTIFICIAL, PINCH, TAPPED, SEMI, FEEDBACK }
enum class DynamicLevel { PPP, PP, P, MP, MF, F, FF, FFF }
enum class StrokeDirection { UP, DOWN }
enum class PercussionTechnique { NORMAL, OPEN_HI_HAT, CLOSED_HI_HAT, PEDAL_HI_HAT, GHOST, CHOKE, RIMSHOT, CROSS_STICK }
@Serializable data class PitchCurvePoint(val position: Fraction, val cents: Int)
@Serializable data class Fermata(val kind: FermataKind = FermataKind.MEDIUM, val multiplier: Double = 1.5)
enum class FermataKind { SHORT, MEDIUM, LONG }
@Serializable data class Strum(val kind: StrumKind, val direction: StrokeDirection, val spread: Fraction = Fraction.ZERO)
enum class StrumKind { STRUM, ARPEGGIO, RASGUEADO }
