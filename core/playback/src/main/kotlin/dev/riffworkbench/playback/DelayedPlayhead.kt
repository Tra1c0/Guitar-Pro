package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Zyklus 26: the visible playhead on an output with extra latency (Bluetooth) runs [delay][displayedFrame] behind the
 * transport frame, so it matches what is heard. The audio and the transport are untouched; only the displayed position
 * moves. Rules (one instance per score view, called with each transport state):
 *
 *  - Only while PLAYING (not counting in) and with a delay > 0; otherwise the transport position is shown unchanged, so
 *    pause, stop and seek while stopped show their target at once.
 *  - Play start, a seek or a loop wrap (the frame jumps against the elapsed wall-clock time by more than [JUMP_SECONDS],
 *    or goes backwards) show the new frame at once (the anchor); the playhead then waits there until the delay has passed
 *    and moves on from it — never before the anchor, never backwards.
 *  - A change of the delay (device switch) never moves it backwards: a larger delay holds the playhead until the delayed
 *    position has caught up; a smaller one lets it catch up forward.
 *
 * The delay is converted to plan frames (plan frames run at the sample rate in real time, the playback speed is already in
 * the plan) and back to bar/offset through the plan's tempo map ([PlaybackPlan.positionAt]).
 */
class DelayedPlayhead {
    private var active = false
    private var lastRaw = 0L
    private var lastNanos = 0L
    private var anchor = 0L
    private var shown = 0L

    /** Displayed plan frame for a transport sample [raw] at wall-clock [nanos] (monotonic). */
    fun displayedFrame(status: TransportStatus, countingIn: Boolean, raw: Long, sampleRate: Int, delayMs: Double, nanos: Long): Long {
        val delayFrames = if (delayMs.isFinite() && delayMs > 0.0 && sampleRate > 0) (delayMs * sampleRate / 1000.0).roundToLong() else 0L
        if (status != TransportStatus.PLAYING || countingIn || delayFrames <= 0L) { active = false; return raw }
        if (!active) { active = true; anchor = raw; shown = raw; lastRaw = raw; lastNanos = nanos; return raw }
        if (raw == lastRaw) return shown
        val expected = lastRaw + (nanos - lastNanos) * sampleRate / 1_000_000_000.0
        if (raw < lastRaw || abs(raw - expected) > JUMP_SECONDS * sampleRate) { anchor = raw; shown = raw }
        else shown = maxOf(shown, anchor, raw - delayFrames)
        lastRaw = raw; lastNanos = nanos
        return shown
    }

    /**
     * Displayed position for [state] (null where the score shows no playhead: not playing or paused). [plan] is the
     * plan the transport plays ([dev.riffworkbench.ports.Transport.installedPlan]); without it, or when its rate does not
     * match the sample, the transport position is shown.
     */
    fun position(state: TransportState, plan: PlaybackPlan?, delayMs: Double, nanos: Long): PlaybackOccurrence? {
        if (state.status != TransportStatus.PLAYING && state.status != TransportStatus.PAUSED) { active = false; return null }
        val sample = state.sample ?: run { active = false; return state.position }
        val frame = displayedFrame(state.status, state.countingIn, sample.frame, sample.sampleRate, delayMs, nanos)
        if (frame == sample.frame || plan == null || plan.sampleRate != sample.sampleRate) return state.position
        return plan.positionAt(frame) ?: state.position
    }

    companion object {
        /** A frame step this far (in seconds) from the elapsed wall-clock time is a seek, not playback. */
        const val JUMP_SECONDS = 0.3
    }
}
