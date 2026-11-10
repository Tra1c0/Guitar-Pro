package dev.riffworkbench.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class EntityId(val value: String) {
    init { require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) { "Invalid entity ID" } }
    override fun toString() = value
}

fun interface IdSource { fun next(): EntityId }

@Serializable
data class Project(
    val schemaVersion: Int = 1,
    val id: EntityId,
    val revision: Long = 0,
    val metadata: ProjectMetadata = ProjectMetadata(),
    val measures: List<Measure> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val sections: List<Section> = emptyList(),
    val tempo: List<TempoChange> = emptyList(),
    val assets: List<AssetDescriptor> = emptyList(),
    val soundBanks: List<SoundBank> = emptyList(),
    val audio: AudioArrangement = AudioArrangement(),
    val mix: MixGraph = MixGraph(),
    val layout: LayoutSettings = LayoutSettings(),
    val text: List<ScoreText> = emptyList(),
    val chords: List<ChordDefinition> = emptyList(),
    val scales: List<ScaleDefinition> = emptyList(),
    val provenance: List<ImportProvenance> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

@Serializable
data class ProjectMetadata(val title: String = "Neues Riff", val artist: String = "", val album: String = "", val composer: String = "", val copyright: String = "", val notes: String = "")

@Serializable
data class TimeSignature(val numerator: Int = 4, val denominator: Int = 4) {
    init { require(numerator in 1..64); require(denominator in 1..64 && denominator.countOneBits() == 1) }
    fun length() = Fraction.of(numerator.toLong(), denominator.toLong())
}

@Serializable
data class Measure(
    val id: EntityId,
    val signature: TimeSignature = TimeSignature(),
    val key: KeySignature = KeySignature(),
    val pickupLength: Fraction? = null,
    val repeat: RepeatMark = RepeatMark(),
    val navigation: List<NavigationMark> = emptyList(),
    val feel: RhythmicFeel = RhythmicFeel.STRAIGHT,
    val forcedSystemBreak: Boolean = false,
)

@Serializable data class KeySignature(val fifths: Int = 0, val minor: Boolean = false)
@Serializable data class RepeatMark(val starts: Boolean = false, val endCount: Int? = null, val alternateEndings: Set<Int> = emptySet())
@Serializable data class NavigationMark(val kind: NavigationKind, val target: EntityId? = null)
enum class NavigationKind { DA_CAPO, DAL_SEGNO, SEGNO, TO_CODA, CODA, FINE }
enum class RhythmicFeel { STRAIGHT, SWING_EIGHTH, SWING_SIXTEENTH, TRIPLET }
@Serializable data class Section(val id: EntityId, val measureId: EntityId, val label: String, val color: Long = 0xFFD7F65B)
@Serializable data class TempoChange(val position: ScorePosition, val quarterNotesPerMinute: Double = 150.0)
@Serializable data class ScorePosition(val measureId: EntityId, val offset: Fraction = Fraction.ZERO)
@Serializable data class PlaybackOccurrence(val position: ScorePosition, val occurrence: Int, val routeIndex: Int)

enum class InstrumentKind { GUITAR, BASS, PERCUSSION, PIANO, OTHER }
enum class NotationKind { TAB, STANDARD, SLASH, PERCUSSION, JIANPU }
enum class RetuningPolicy { KEEP_FINGER_POSITION, KEEP_SOUNDING_PITCH }

@Serializable
data class InstrumentDefinition(
    val kind: InstrumentKind = InstrumentKind.GUITAR,
    /** MIDI pitches ordered from lowest physical string to highest. */
    val tuning: List<Int> = listOf(33, 40, 45, 50, 55, 59, 64),
    val capoByString: List<Int> = List(tuning.size) { 0 },
    val maxFret: Int = 24,
    val midiProgram: Int = 30,
    val fineTuneCents: Double = 0.0,
    val transposition: Int = 0,
    val drumKit: List<DrumElement> = defaultDrumKit(),
) {
    init {
        require(kind !in listOf(InstrumentKind.GUITAR, InstrumentKind.BASS) || tuning.size in 3..10)
        require(capoByString.size == tuning.size)
        require(tuning.all { it in 0..127 } && capoByString.all { it in 0..36 })
        require(maxFret in 1..48)
    }
    fun pitch(string: Int, fret: Int) = tuning[string] + capoByString[string] + fret + transposition
}

@Serializable data class DrumElement(val midiNote: Int, val name: String, val volume: Double = 1.0, val pan: Double = 0.0, val chokeGroup: Int? = null)
fun defaultDrumKit() = listOf(DrumElement(36, "Kick"), DrumElement(38, "Snare"), DrumElement(42, "Closed HH", chokeGroup = 1), DrumElement(46, "Open HH", chokeGroup = 1), DrumElement(49, "Crash", chokeGroup = 2))
/**
 * Kit of new drum tracks (ProjectFactory, QA cycle 24): the [defaultDrumKit] elements in their order, then pedal hi-hat, three
 * toms, ride and a second crash - what the sampled kit (ArtiFret Pearl Kit) records. The model default stays [defaultDrumKit],
 * so imports and stored projects keep their own list; choke groups follow the GM defaults of the playback compiler.
 */
fun factoryDrumKit() = defaultDrumKit() + listOf(DrumElement(44, "Pedal HH", chokeGroup = 1), DrumElement(48, "Tom 1"), DrumElement(45, "Tom 2"),
    DrumElement(41, "Floor Tom"), DrumElement(51, "Ride"), DrumElement(57, "Crash 2", chokeGroup = 2))

@Serializable
data class Track(
    val id: EntityId,
    val name: String,
    val instrument: InstrumentDefinition = InstrumentDefinition(),
    val staves: List<Staff> = emptyList(),
    val sound: SoundAssignment = SoundAssignment(),
    val mix: ChannelMix = ChannelMix(),
)

@Serializable data class Staff(val id: EntityId, val notation: Set<NotationKind> = setOf(NotationKind.TAB), val bars: List<Bar> = emptyList())
@Serializable data class Bar(val id: EntityId, val measureId: EntityId, val voices: List<Voice> = emptyList())
@Serializable data class Voice(val id: EntityId, val index: Int = 0, val beats: List<Beat> = emptyList())
@Serializable data class Beat(
    val id: EntityId,
    val offset: Fraction,
    val duration: WrittenDuration = WrittenDuration(),
    val notes: List<Note> = emptyList(),
    val explicitRest: Boolean = false,
    val tupletGroupIds: List<EntityId> = emptyList(),
    val fermata: Fermata? = null,
    val strum: Strum? = null,
    val chordId: EntityId? = null,
)

@Serializable data class Pitch(val midiNumber: Int, val cents: Int = 0, val spelling: String? = null)
@Serializable data class Note(
    val id: EntityId,
    val pitch: Pitch,
    val string: Int? = null,
    val fret: Int? = null,
    val velocity: Int = 96,
    val articulations: List<Articulation> = emptyList(),
    val links: List<NoteLink> = emptyList(),
    val fingering: Fingering? = null,
)
@Serializable data class NoteLink(val kind: LinkKind, val target: EntityId)
enum class LinkKind { TIE, SLUR, HAMMER_ON, PULL_OFF, LEGATO_SLIDE }
@Serializable data class Fingering(val leftHand: Int? = null, val rightHand: String? = null, val alternative: String? = null)

@Serializable data class Diagnostic(val code: String, val message: String, val entityId: EntityId? = null, val severity: Severity = Severity.WARNING)
enum class Severity { INFO, WARNING, ERROR }

@Serializable data class ProjectSummary(val id: EntityId, val title: String, val revision: Long, val tracks: Int, /** Last save (head file time), 0 if unknown. */ val modifiedMillis: Long = 0)
@Serializable data class SnapshotInfo(val id: String, val projectId: EntityId, val revision: Long, val name: String, val createdAtMillis: Long)

@Serializable data class ImportProvenance(val format: String, val originalAsset: AssetHash, val unknownData: List<ForeignData> = emptyList(), val losses: List<LossEntry> = emptyList())
@Serializable data class ForeignData(val path: String, val asset: AssetHash, val referencedIds: Set<EntityId>, val validAtRevision: Long)
@Serializable data class LossEntry(val featureId: String, val direction: String, val reason: String, val entityId: EntityId? = null)

@Serializable data class ScoreText(val id: EntityId, val position: ScorePosition, val text: String, val kind: TextKind = TextKind.TEXT, val style: TextStyle = TextStyle())
enum class TextKind { TEXT, LYRICS, SECTION, FINGERING }
@Serializable data class TextStyle(val size: Double = 12.0, val bold: Boolean = false, val italic: Boolean = false)
@Serializable data class ChordDefinition(val id: EntityId, val name: String, val pitches: List<Int>, val frets: List<Int?> = emptyList(), val fingers: List<Int?> = emptyList(), val firstFret: Int = 1)
@Serializable data class ScaleDefinition(val name: String, val intervals: List<Int>)
@Serializable data class LayoutSettings(val mode: LayoutMode = LayoutMode.CONTINUOUS, val scale: Double = 1.0, val stylesheet: String = "default", val trackOrder: List<EntityId> = emptyList(), val barsPerSystem: Int? = null, val pageWidthMm: Double = 210.0, val pageHeightMm: Double = 297.0)
enum class LayoutMode { PAGE, CONTINUOUS, MULTITRACK }

@Serializable data class AppSettings(val darkTheme: Boolean = true, val autoAdvance: Boolean = true, val strictRhythm: Boolean = false, val retuningPolicy: RetuningPolicy = RetuningPolicy.KEEP_SOUNDING_PITCH, val favoriteEffects: List<String> = listOf("palm_mute", "slide", "bend"), val shortcuts: Map<String, String> = emptyMap(),
    /** Pinch zoom in the score (QA 23): off by default, the score is drawn at a fixed readable scale; older files read as off. */
    val pinchZoom: Boolean = false,
    /**
     * Tap latency (Zyklus 25, „Rhythmus tippen“ → Kalibrieren): how much later than the audible click the user's taps land,
     * in ms (median of 8 calibration taps); subtracted from every tap. Default 0; older files read as 0.
     */
    val tapLatencyMs: Double = 0.0)
