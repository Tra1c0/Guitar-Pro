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

/** Zyklus 28: meter of the tapped bars — detected (AUTO), as they are (KEEP) or the one chosen by hand (FIXED). */
enum class RhythmMeterMode { AUTO, KEEP, FIXED }

/** Zyklus 25 „Rhythmus tippen“: where the flow is. COUNT_IN/RECORDING/CALIBRATING run the transport. */
enum class RhythmTapPhase { IDLE, SETUP, COUNT_IN, RECORDING, PREVIEW, CALIBRATING }

data class RhythmTapState(
    val phase: RhythmTapPhase = RhythmTapPhase.IDLE,
    /** Target: the visible bar range (Zyklus-23 rules) when there is one and [useRange], else [barCount] bars from the cursor bar. */
    val useRange: Boolean = true,
    val barCount: Int = 2,
    /**
     * Zyklus 27 „Offen (Takte anhängen)“ (the user's choice): the recording starts at the cursor bar and runs until „Stopp“
     * (or Auto-Stopp); at the end of the piece bars are appended. [openActive]: the choice applies (no marked range used).
     */
    val openEnded: Boolean = false,
    val openActive: Boolean = false,
    /** Open recording: ends by itself after [dev.riffworkbench.editor.RhythmAutoStop.EMPTY_BARS] bars without a touch. */
    val autoStop: Boolean = true,
    /** Target bars that exist (measure ids, consecutive) and a label such as „Takte 2–3“ or „Takte 4–7 (+2 neu)“. */
    val bars: List<dev.riffworkbench.model.EntityId> = emptyList(),
    val label: String = "",
    /** Bars appended at the end of the piece: setup — missing bars of a fixed target; preview — the bars „Übernehmen“ creates. */
    val newBars: Int = 0,
    /**
     * Zyklus 28: default „Auto“ ([dev.riffworkbench.editor.RhythmGrid.SMART], pulse tracking and grid per beat); the manual
     * grids (1/8 … 1/32, the Zyklus-25 „16tel/Triolen“) stay under „Raster manuell“ ([manualOpen], folded by default).
     * Changed default (was 1/16): the new Auto is at least as exact as every fixed grid on the evaluation (QA journal).
     */
    val grid: dev.riffworkbench.editor.RhythmGrid = dev.riffworkbench.editor.RhythmGrid.SMART,
    val manualOpen: Boolean = false,
    /** Zyklus 28 „Taktart erkennen“: Auto (detected meter for bars that may change), keep, or a fixed meter ([meterFixed]). */
    val meterMode: RhythmMeterMode = RhythmMeterMode.AUTO,
    val meterFixed: dev.riffworkbench.model.TimeSignature? = null,
    /** Preview info line („erkannt: 16tel, Triolen in Takt 3“) and the meter line („Erkannt: 7/8“ or why bars keep theirs). */
    val info: String = "",
    val meterInfo: String? = null,
    /** Zyklus 28: the practice speed the metronome of the recording runs at (Übungsseite), shown when ≠ 100 %. */
    val speed: Double = 1.0,
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

/**
 * Zyklus 29: geometry (dp) of the continuous drum grid — one LazyRow item per bar, cells at fixed x so the playhead column
 * can be drawn and followed without measuring. Pure, so the JVM shim tests the follow rule.
 */
object DrumGridGeometry {
    const val CELL = 48f
    const val GAP = 4f
    /** Extra gap before a beat start (the ruler numbers stand there). */
    const val BEAT_GAP = 6f
    /** Room after the barline at the start of each bar item. */
    const val BAR_START = 12f
    const val BAR_END = 4f
    const val HEADER = 22f
    const val RULER = 18f
    const val ROW_GAP = 4f

    fun cellX(cells: List<dev.riffworkbench.editor.DrumGrid.Cell>): FloatArray {
        val xs = FloatArray(cells.size)
        var x = BAR_START
        cells.forEachIndexed { i, c ->
            if (i > 0) x += CELL + GAP + if (c.beatStart) BEAT_GAP else 0f
            xs[i] = x
        }
        return xs
    }
    fun barWidth(cells: List<dev.riffworkbench.editor.DrumGrid.Cell>): Float = (cellX(cells).lastOrNull() ?: BAR_START) + CELL + BAR_END
    /** Start x of every bar and, last, the total width. */
    fun barStarts(bars: List<dev.riffworkbench.editor.DrumGrid.Bar>): FloatArray {
        val out = FloatArray(bars.size + 1)
        bars.forEachIndexed { i, b -> out[i + 1] = out[i] + barWidth(b.cells) }
        return out
    }
    /**
     * Playback follow: the view moves only when the sounding column leaves the visible part (minus one cell of margin), and
     * then just far enough ([dev.riffworkbench.notation.ViewportScroll.minimal]): no jumps, no paging.
     */
    fun follow(columnX: Float, scroll: Float, viewport: Float, content: Float, margin: Float = CELL + GAP): Float =
        dev.riffworkbench.notation.ViewportScroll.minimal(columnX + CELL / 2, CELL / 2, scroll, viewport, content, margin)

    /** Height (dp) of the cell rows of one bar ([rows] kit elements, [ROW_GAP] between them). */
    fun rowsHeight(rows: Int): Float = if (rows <= 0) 0f else rows * CELL + (rows - 1) * ROW_GAP

    /**
     * Zyklus 30: the cell under (x, y) dp of a bar's cell area — (row, cell index) — or null in a gap. The grid draws all
     * cells of a bar in one layer and hit-tests by coordinate instead of one button per cell.
     */
    fun cellAt(xs: FloatArray, rows: Int, x: Float, y: Float): Pair<Int, Int>? {
        if (y < 0f) return null
        val row = (y / (CELL + ROW_GAP)).toInt()
        if (row >= rows || y - row * (CELL + ROW_GAP) > CELL) return null
        for (i in xs.indices) if (x >= xs[i] && x <= xs[i] + CELL) return row to i
        return null
    }
}

/**
 * Zyklus 30: what the continuous drum grid draws per bar beyond the cells — pure (JVM shim), so the glow timing, the guitar
 * lane geometry and the cursor snap are testable without Compose. x values are dp inside the bar item.
 */
object DrumGridActivity {
    /** Height (dp) of the guitar lane above the bar header; it is also part of the positioning strip. */
    const val LANE = 8f
    /** Snap radius (dp) of the positioning strip: half a cell, at least 16 dp. */
    val SNAP_RADIUS = maxOf(DrumGridGeometry.CELL / 2, 16f)

    /**
     * x (dp, inside the bar) of [offset]: piecewise linear between the cell starts (cells are not linear in time because of
     * the wider beat gap), the last cell running to its right edge at the barline.
     */
    fun xAt(cells: List<dev.riffworkbench.editor.DrumGrid.Cell>, xs: FloatArray, length: Fraction, offset: Double): Float {
        if (cells.isEmpty()) return DrumGridGeometry.BAR_START
        if (offset <= cells[0].offset.toDouble()) return xs[0]
        for (i in 1 until cells.size) {
            val o = cells[i].offset.toDouble()
            if (offset < o) {
                val p = cells[i - 1].offset.toDouble()
                return xs[i - 1] + ((offset - p) / (o - p)).toFloat() * (xs[i] - xs[i - 1])
            }
        }
        val p = cells.last().offset.toDouble()
        val span = (length.toDouble() - p).coerceAtLeast(1e-9)
        return xs.last() + ((offset - p) / span).toFloat().coerceIn(0f, 1f) * DrumGridGeometry.CELL
    }

    /** A glowing set cell: bar index, cell index, kit element, intensity 0…1. */
    data class Glow(val bar: Int, val cell: Int, val midi: Int, val intensity: Float)

    /**
     * The set cells that glow at score time [now] (seconds of [dev.riffworkbench.playback.ScoreClock], i.e. the displayed
     * playhead incl. the Bluetooth delay): every hit that lies exactly on a cell of the grid (the cell shows it as set) and
     * started less than [dev.riffworkbench.playback.HitGlow.DECAY_SECONDS] ago (real time: divided by [speed]).
     */
    fun glowing(bars: List<dev.riffworkbench.editor.DrumGrid.Bar>, hits: List<dev.riffworkbench.playback.HitGlow.Hit>, now: Double, speed: Double = 1.0): List<Glow> =
        dev.riffworkbench.playback.HitGlow.glowing(hits, now, speed).mapNotNull { (h, i) ->
            val cell = bars.getOrNull(h.bar)?.cells?.firstOrNull { it.offset == h.offset } ?: return@mapNotNull null
            Glow(h.bar, cell.index, h.midi, i)
        }

    /**
     * Snap candidates of the positioning strip over all bars (global dp: [DrumGridGeometry.barStarts] + cell x): onsets are
     * the set drum cells of [beats] (per bar) and the guitar attacks of [lanes]; the grid is every cell.
     */
    fun snapTargets(bars: List<dev.riffworkbench.editor.DrumGrid.Bar>, beats: List<List<Beat>>, lanes: List<dev.riffworkbench.playback.GuitarActivity.BarLane>):
        Pair<List<dev.riffworkbench.notation.CursorSnap.Target>, List<dev.riffworkbench.notation.CursorSnap.Target>> {
        val starts = DrumGridGeometry.barStarts(bars)
        val onsets = ArrayList<dev.riffworkbench.notation.CursorSnap.Target>()
        val grid = ArrayList<dev.riffworkbench.notation.CursorSnap.Target>()
        bars.forEachIndexed { i, bar ->
            val xs = DrumGridGeometry.cellX(bar.cells)
            bar.cells.forEach { c -> grid += dev.riffworkbench.notation.CursorSnap.Target(starts[i] + xs[c.index], bar.measureId, c.offset) }
            val offsets = sortedSetOf<Fraction>()
            beats.getOrNull(i)?.forEach { b -> if (b.notes.isNotEmpty()) offsets += b.offset }
            lanes.getOrNull(i)?.attacks?.forEach { offsets += it }
            offsets.forEach { o -> if (o < bar.length) onsets += dev.riffworkbench.notation.CursorSnap.Target(starts[i] + xAt(bar.cells, xs, bar.length, o.toDouble()), bar.measureId, o) }
        }
        return onsets to grid
    }
}
