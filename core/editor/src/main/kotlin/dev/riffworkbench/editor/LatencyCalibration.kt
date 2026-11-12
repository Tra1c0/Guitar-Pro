package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.Transport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The calibration piece of Zyklus 25 (2 bars 4/4 at 100 BPM = 8 clicks after a one-bar count-in, every track silent):
 * built from [base] (the open project, so the transport keeps its tracks and sounds) or, without one (settings page,
 * Zyklus 26), from nothing. Own revision below every real one: the transport skips a prepare with equal id/revision.
 */
fun calibrationProject(base: Project?): Project {
    val measures = List(2) { Measure(EntityId("tap-cal-m$it")) }
    val tempo = listOf(TempoChange(ScorePosition(measures.first().id), 100.0))
    if (base == null) return Project(id = EntityId("tap-calibration"), revision = Long.MIN_VALUE, measures = measures, tempo = tempo)
    return base.copy(revision = Long.MIN_VALUE + base.revision, measures = measures, sections = emptyList(), text = emptyList(), audio = AudioArrangement(), tempo = tempo,
        tracks = base.tracks.mapIndexed { ti, t -> t.copy(mix = t.mix.copy(mute = true, solo = false), staves = t.staves.take(1).map { st ->
            st.copy(bars = measures.mapIndexed { mi, m -> Bar(EntityId("tap-cal-b$ti-$mi"), m.id, List(4) { v -> Voice(EntityId("tap-cal-v$ti-$mi-$v"), v) }) }) }) })
}

/**
 * Zyklus 26: the tap calibration as a reusable run (settings page „Latenz“): plays [calibrationProject] with metronome and
 * count-in, collects touch-downs ([tapDown], mapped by [Transport.audiblePlanFrameAt]) and returns the robust offset
 * ([TapCalibration.offsetMillis]: 8 clicks, ±250 ms window, MAD outliers, median). The transport is stopped afterwards.
 * [cancel] (or a pause/failure of the transport) ends it without a result.
 */
class TapLatencyCalibrator(private val transport: Transport) {
    data class Progress(val running: Boolean = false, val countingIn: Boolean = false, val taps: Int = 0)

    private val mutable = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = mutable.asStateFlow()
    private val taps = ArrayList<Double>()
    @Volatile private var cancelled = false

    /** Runs one calibration; Success = offset in ms, Failure CANCELLED (cancel/interruption) or VALIDATION (too few clean taps). */
    suspend fun run(base: Project? = null): Outcome<Double> {
        if (mutable.value.running) return Outcome.Failure(FailureCode.CONFLICT, "Kalibrierung läuft bereits")
        cancelled = false
        synchronized(taps) { taps.clear() }
        mutable.value = Progress(running = true, countingIn = true)
        try {
            val project = calibrationProject(base)
            transport.stop()
            transport.prepare(project, PracticeSettings(speed = 1.0, countInBars = 1, metronome = true)).let { if (it is Outcome.Failure) return it }
            val first = project.measures.first().id
            transport.seek(PlaybackOccurrence(ScorePosition(first), 1, 0)).let { if (it is Outcome.Failure) return it }
            if (cancelled) return cancelledOutcome()
            transport.play().let { if (it is Outcome.Failure) return it }
            val plan = transport.installedPlan() ?: return Outcome.Failure(FailureCode.UNAVAILABLE, "Wiedergabe nicht verfügbar", contractId = "C-AUDIO")
            val spans = plan.spans.take(2)
            if (spans.isEmpty()) return Outcome.Failure(FailureCode.UNAVAILABLE, "Wiedergabe nicht verfügbar", contractId = "C-AUDIO")
            val start = spans.first().startFrame; val end = spans.last().endFrame
            var interrupted = false
            transport.state.first { s ->
                if (cancelled) { interrupted = true; return@first true }
                val frame = s.sample?.frame
                if (mutable.value.countingIn && s.status == TransportStatus.PLAYING && !s.countingIn && frame != null && frame > start) mutable.value = mutable.value.copy(countingIn = false)
                when {
                    s.status == TransportStatus.PLAYING && frame != null && frame >= end -> true
                    s.status == TransportStatus.STOPPED && frame != null && frame >= start -> true
                    s.status != TransportStatus.PLAYING && s.status != TransportStatus.PREPARING -> { interrupted = true; true }
                    else -> false
                }
            }
            if (interrupted || cancelled) return cancelledOutcome()
            val downs = synchronized(taps) { taps.toList() }
            val offset = TapCalibration.offsetMillis(TapCalibration.deltasMillis(downs, plan.clicks.map { it.frame }, plan.sampleRate))
                ?: return Outcome.Failure(FailureCode.VALIDATION, "Kalibrierung unsicher – bitte zu jedem Klick tippen und wiederholen")
            return Outcome.Success(offset)
        } finally {
            // Also when the caller's scope is cancelled (page left): the calibration piece must not keep playing.
            withContext(NonCancellable) { transport.stop() }
            mutable.value = Progress()
        }
    }

    /** Touch-down at [nanos] (System.nanoTime base); ignored unless running. */
    fun tapDown(nanos: Long) {
        if (!mutable.value.running) return
        val frame = transport.audiblePlanFrameAt(nanos)
        if (frame.isNaN()) return
        val count = synchronized(taps) { taps += frame; taps.size }
        mutable.value = mutable.value.copy(taps = count)
    }

    /** Ends a running calibration without a result (device switch, „Abbrechen“, leaving the page). */
    suspend fun cancel() {
        if (!mutable.value.running) return
        cancelled = true
        transport.stop()
    }

    private fun cancelledOutcome() = Outcome.Failure(FailureCode.CANCELLED, "Kalibrierung abgebrochen")
}
