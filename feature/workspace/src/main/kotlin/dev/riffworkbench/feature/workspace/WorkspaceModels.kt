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
    /** Button on the notice, valid only while [notice] is exactly [NoticeAction.text] (Zyklus 26). */
    val noticeAction: NoticeAction? = null,
)

/** Zyklus 26: what the button of a notice does. */
enum class NoticeActionKind { CALIBRATE_LATENCY }
/** A notice button for the notice [text] only: any other notice text hides it (notices are set from many places). */
data class NoticeAction(val text: String, val kind: NoticeActionKind, val label: String)

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
    /** Following paused after a manual pan/zoom while playing; resumed by the "Folgen" chip, Play, seek or stop. */
    val followSuspended: Boolean = false,
    val compact: Boolean = false,
    val viewportWidthDp: Float = 400f,
)

/**
 * How long a notice stays before it closes itself (QA 23: notices lay over the UI and close on their own). 4 s for a short
 * text, 50 ms more per character for longer ones, at most 10 s; TalkBack may lengthen it further (TransientNotice). A failed
 * save is not timed: its notice keeps "Erneut versuchen" until the user acts.
 */
object NoticeTiming {
    const val SHORT_MILLIS = 4_000L
    const val MAX_MILLIS = 10_000L
    fun millis(text: String): Long = (SHORT_MILLIS + 50L * (text.length - 40).coerceAtLeast(0)).coerceAtMost(MAX_MILLIS)
}

data class TunerState(val running: Boolean = false, val reading: TuningReading? = null, val error: String? = null)

/** Optional collaborators; tests pass none. The composition root wires real implementations. */
data class WorkspaceServices(
    val preview: PreviewPlayer? = null,
    val renderer: OfflineRenderer? = null,
    val formats: FormatCatalog? = null,
    val tuner: Tuner? = null,
    val chords: ChordLibrary = BuiltInChordLibrary(),
    /** Reference audio: original files are stored by hash; the decoder validates them before a clip is added. */
    val assets: AssetStore? = null,
    val audioDecoder: AudioDecoder? = null,
    val waveforms: WaveformCache? = null,
    /** Audio measurements and blind comparisons (diagnostics page). */
    val audioLab: AudioLab? = null,
    /** Zyklus 26: the output device (per-device tap latency, playhead correction, hint); null = unknown device. */
    val audioRoutes: AudioRouteMonitor? = null,
    /** Zyklus 26: „Latenz nicht kalibriert“ once per device and app process (one instance in the composition root). */
    val routeNotices: RouteNoticeMemory = RouteNoticeMemory(),
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

/** Zyklus 25 „Rhythmus tippen“: where the flow is. COUNT_IN/RECORDING/CALIBRATING run the transport. */
enum class RhythmTapPhase { IDLE, SETUP, COUNT_IN, RECORDING, PREVIEW, CALIBRATING }

data class RhythmTapState(
    val phase: RhythmTapPhase = RhythmTapPhase.IDLE,
    /** Target: the visible bar range (Zyklus-23 rules) when there is one and [useRange], else [barCount] bars from the cursor bar. */
    val useRange: Boolean = true,
    val barCount: Int = 2,
    /** Target bars (measure ids, consecutive) and a label such as „Takte 2–3“. */
    val bars: List<dev.riffworkbench.model.EntityId> = emptyList(),
    val label: String = "",
    val grid: dev.riffworkbench.editor.RhythmGrid = dev.riffworkbench.editor.RhythmGrid.SIXTEENTH,
    /** Other tracks play along (the target track stays silent); off: metronome only. */
    val accompaniment: Boolean = false,
    /** Fretted track: string for „Leersaite“ (0 = lowest); drum track: kit piece. */
    val fretted: Boolean = true,
    val drums: Boolean = false,
    val string: Int = 0,
    val drumNote: Int = 38,
    val taps: Int = 0,
    val result: dev.riffworkbench.editor.RhythmResult? = null,
    /** Notes the apply would overwrite in the target voice; > 0 asks first. */
    val overwrites: Int = 0,
    /** Apply waiting for the overwrite confirmation. */
    val confirm: dev.riffworkbench.editor.RhythmMode? = null,
    /** Tap latency in ms applied now: the active device's profile, else AppSettings.tapLatencyMs (Zyklus 26). */
    val latencyMs: Double = 0.0,
    /** Active output device („WH-1000XM4 (Bluetooth)“), null while unknown (Zyklus 26). */
    val latencyDevice: String? = null,
    /** The active device has its own calibration (otherwise [latencyMs] is the device-less fallback). */
    val latencyOwn: Boolean = false,
)
