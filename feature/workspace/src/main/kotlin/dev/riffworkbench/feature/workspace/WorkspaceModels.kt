package dev.riffworkbench.feature.workspace

import dev.riffworkbench.editor.BarClipboard
import dev.riffworkbench.editor.BuiltInChordLibrary
import dev.riffworkbench.editor.EditorState
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*

enum class SaveStatus { SAVED, SAVING, FAILED }

data class WorkspaceState(
    val loading: Boolean = true,
    val editor: EditorState? = null,
    val loadError: String? = null,
    val notice: String? = null,
    val saveStatus: SaveStatus = SaveStatus.SAVED,
    val snapshots: List<SnapshotInfo> = emptyList(),
    val clipboard: BarClipboard? = null,
    /** Running long operation (export/render) with a short German label, or null. */
    val busy: String? = null,
    val busyProgress: Float? = null,
    val lastLosses: List<LossEntry> = emptyList(),
)

data class NoteInput(
    val buffer: String = "",
    val duration: WrittenDuration = WrittenDuration(),
    val palmMute: Boolean = false,
    val chordMode: Boolean = false,
    /** Bar-range selection mode: taps extend the range instead of moving the cursor. */
    val rangeMode: Boolean = false,
)

enum class NotationMode { TAB, TAB_AND_STANDARD, STANDARD }

data class ViewOptions(
    val zoom: Float = 1f,
    val notation: NotationMode = NotationMode.TAB,
    /** Wrapped systems (page-like) instead of one continuous strip. */
    val wrap: Boolean = false,
    val follow: Boolean = true,
    val compact: Boolean = false,
    val viewportWidthDp: Float = 400f,
)

data class TunerState(val running: Boolean = false, val reading: TuningReading? = null, val error: String? = null)

/** Optional collaborators; tests pass none. The composition root wires real implementations. */
data class WorkspaceServices(
    val preview: PreviewPlayer? = null,
    val renderer: OfflineRenderer? = null,
    val formats: FormatCatalog? = null,
    val tuner: Tuner? = null,
    val chords: ChordLibrary = BuiltInChordLibrary(),
)

/** Export targets offered in the exchange screen. */
enum class ExportTarget(val id: String, val labelDe: String, val labelEn: String, val extension: String, val streamed: Boolean = false) {
    NATIVE("riffproj", "Projektdatei (.riffproj, vollständig)", "Project file (.riffproj, complete)", "riffproj"),
    GP7("gp7", "Guitar Pro 7 (.gp)", "Guitar Pro 7 (.gp)", "gp"),
    MIDI("midi", "MIDI (Wiedergabereihenfolge, Tempo-Map)", "MIDI (playback order, tempo map)", "mid"),
    MUSICXML("musicxml", "MusicXML", "MusicXML", "musicxml"),
    ASCII("ascii", "ASCII-Tab (.txt)", "ASCII tab (.txt)", "txt"),
    SVG("svg", "SVG (Notenbild)", "SVG (score image)", "svg"),
    PDF("pdf", "PDF (Druck)", "PDF (print)", "pdf"),
    WAV("wav", "WAV-Mix (48 kHz, 24 Bit)", "WAV mix (48 kHz, 24-bit)", "wav", streamed = true),
    STEMS("stems", "Stems + MIDI für die DAW (.zip)", "Stems + MIDI for the DAW (.zip)", "zip", streamed = true),
}
