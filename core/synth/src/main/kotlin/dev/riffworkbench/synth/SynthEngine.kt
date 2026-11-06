package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** Immutable event program for the audio thread, prepared off-thread from a [PlaybackPlan]. */
class EngineProgram private constructor(
    val plan: PlaybackPlan,
    internal val noteFrames: LongArray,
    internal val notes: Array<ScheduledNote>,
    internal val clickFrames: LongArray,
    internal val clickLevels: IntArray,
) {
    val endFrame: Long get() = plan.totalFrames
    internal fun firstNoteAtOrAfter(frame: Long) = lowerBound(noteFrames, frame)
    internal fun firstClickAtOrAfter(frame: Long) = lowerBound(clickFrames, frame)
    companion object {
        fun prepare(plan: PlaybackPlan): EngineProgram {
            val sorted = plan.notes.sortedBy { it.frame }
            return EngineProgram(plan, LongArray(sorted.size) { sorted[it].frame }, sorted.toTypedArray(),
                LongArray(plan.clicks.size) { plan.clicks[it].frame }, IntArray(plan.clicks.size) { plan.clicks[it].level })
        }
        internal fun lowerBound(values: LongArray, key: Long): Int {
            var lo = 0; var hi = values.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (values[mid] < key) lo = mid + 1 else hi = mid }
            return lo
        }
    }
}

internal enum class ChannelKind { STRING, DRUMS, KEYS }

internal class Channel(val kind: ChannelKind, val stringTone: StringTone, val keysMode: KeysMode, val effects: Array<Effect>, maxBlock: Int,
                       /** Electric guitar: played from the sample bank when one is installed. */ val sampled: Boolean = false, val sampleGain: Float = 1f,
                       /** Timbre of the modelled harmonics while the samples play (level trimmed to the samples); the plain tone when no bank is installed. */
                       val harmonicTone: StringTone = stringTone,
                       /** What the voices depend on (kind, preset, samples); equal voices can keep sounding across a rack swap. */ val voiceSignature: String = "",
                       /** The units behind [effects], to keep the state of unchanged effects across a rack swap. */ val units: List<EffectUnit> = emptyList()) {
    val left = FloatArray(maxBlock)
    val right = FloatArray(maxBlock)
    val stereo = kind == ChannelKind.DRUMS
    var idleBlocks = Int.MAX_VALUE / 2
    var lastGainL = 0f; var lastGainR = 0f
    /** False until the first mixed block: that block starts at the target gain instead of ramping up from 0. */
    var gainPrimed = false
    /** De-click residual of stolen voices (their last output), decaying over a few ms in the track buffer. */
    var residualL = 0f; var residualR = 0f
    /** Consecutive blocks whose processed output was silent; long delay/reverb tails keep the channel alive. */
    var silentBlocks = 0
    var effectsCleared = true
    /** Output delay of this channel's effect chain (cabinet convolution). */
    val latency: Int = effects.sumOf { it.latencyFrames }
    /**
     * Latency compensation (QA cycle 20): a channel with less effect latency than the rack's slowest chain is delayed by the
     * difference, so drums and keys line up with cabinet-convolved guitar and bass (the cabinet partition is 64 frames).
     */
    var compensation = 0; private set
    private var compL = FloatArray(0)
    private var compR = FloatArray(0)
    private var compPos = 0
    internal fun setCompensation(frames: Int) {
        compensation = frames.coerceAtLeast(0)
        compL = FloatArray(compensation); compR = FloatArray(if (stereo) compensation else 0); compPos = 0
    }
    /** Takes over the delay-line contents of the channel this one replaces (compatible rack swap), when the delays match. */
    internal fun takeCompensationFrom(old: Channel) {
        if (old.compensation != compensation || compensation == 0) return
        old.compL.copyInto(compL); if (stereo && old.stereo) old.compR.copyInto(compR); compPos = old.compPos
    }
    internal fun clearCompensation() { compL.fill(0f); compR.fill(0f); compPos = 0 }
    /** Delays the processed block in place by [compensation] frames. */
    internal fun compensate(frames: Int) {
        val n = compensation
        if (n == 0) return
        var p = compPos
        for (i in 0 until frames) {
            val y = compL[p]; compL[p] = left[i]; left[i] = y
            if (stereo) { val z = compR[p]; compR[p] = right[i]; right[i] = z }
            p++; if (p == n) p = 0
        }
        compPos = p
    }
}

/** Per-track DSP channels. Rebuilt only when sounds or effect chains change, so reverb tails survive note edits. */
class ChannelRack internal constructor(internal val channels: Array<Channel>, internal val master: Array<Effect>, val signature: String) {
    /** The slowest channel's effect latency; every other channel, the metronome and the clips are delayed to it. */
    val latencyFrames: Int = channels.maxOfOrNull { it.latency } ?: 0
    /** Total delay from a plan frame to the rack's output: the slowest channel plus the master chain (a cabinet on the master). */
    val outputLatencyFrames: Int = latencyFrames + master.sumOf { it.latencyFrames }
    init { for (ch in channels) ch.setCompensation(latencyFrames - ch.latency) }
    companion object {
        fun signature(project: Project): String = project.tracks.joinToString("|") { t -> "${t.instrument.kind}:${TonePresets.forTrack(t).id}:${TonePresets.effectiveChain(t)}" } + "#" + project.mix.master.effects
        fun build(project: Project, sampleRate: Int, maxBlock: Int, includeMasterFx: Boolean = true): ChannelRack {
            val channels = project.tracks.map { track ->
                val preset = TonePresets.forTrack(track)
                val kind = when {
                    track.instrument.kind == InstrumentKind.PERCUSSION -> ChannelKind.DRUMS
                    track.instrument.tuning.isNotEmpty() || preset.family == TonePreset.Family.GUITAR || preset.family == TonePreset.Family.BASS -> ChannelKind.STRING
                    else -> ChannelKind.KEYS
                }
                val built = TonePresets.effectiveChain(track).mapNotNull { u -> Effects.create(u, sampleRate, maxBlock)?.let { u to it } }
                val units = built.map { it.first }
                val chain = built.map { it.second }.toTypedArray()
                val sampled = kind == ChannelKind.STRING && TonePresets.isSampledGuitar(preset)
                // Harmonics stay modelled inside a sampled preset; their level follows the sample trim so a pinch harmonic sits just under a picked note.
                val tone = TonePresets.stringTone(preset)
                val harmonicTone = if (sampled) StringTone(tone.brightness, tone.sustainSeconds, tone.pickPosition, tone.excitationSoftness, tone.pickNoise, tone.level * TonePresets.harmonicGain(preset)) else tone
                Channel(kind, tone, TonePresets.keysMode(preset), chain, maxBlock, sampled, TonePresets.sampleGain(preset), harmonicTone, "$kind:${preset.id}:$sampled", units)
            }.toTypedArray()
            val master = if (includeMasterFx) (project.mix.master.effects.mapNotNull { Effects.create(it, sampleRate, maxBlock) } + Limiter(sampleRate)).toTypedArray() else emptyArray()
            return ChannelRack(channels, master, signature(project) + includeMasterFx)
        }
    }
}

/** Mixer snapshot read once per block. Gains already include mute/solo. */
class MixState internal constructor(internal val gains: FloatArray, internal val pans: FloatArray, internal val master: Float, val metronome: Float) {
    companion object {
        val SILENT = MixState(FloatArray(0), FloatArray(0), 1f, 0f)
        fun volumeToGain(volume: Double): Float = ((if (volume.isFinite()) volume else 0.8).coerceIn(0.0, 1.0).let { it * it } / 0.64).toFloat()
        fun from(project: Project, metronome: Float = 0f, soloOverride: Int? = null): MixState {
            val anySolo = soloOverride != null || project.tracks.any { it.mix.solo } || project.audio.clips.any { it.solo }
            val gains = FloatArray(project.tracks.size) { i ->
                val mix = project.tracks[i].mix
                val audible = if (soloOverride != null) i == soloOverride else !mix.mute && (!anySolo || mix.solo)
                if (audible) volumeToGain(mix.volume) else 0f
            }
            val pans = FloatArray(project.tracks.size) { project.tracks[it].mix.pan.toFloat().takeIf { p -> p.isFinite() }?.coerceIn(-1f, 1f) ?: 0f }
            return MixState(gains, pans, volumeToGain(project.mix.master.volume), metronome.coerceIn(0f, 2f))
        }
    }
}

/**
 * The real-time renderer. All methods must be called from one thread (the audio thread or an offline loop).
 * After construction, [render] performs no allocation, I/O or locking.
 */
private val NO_CLICKS = LongArray(0)

class SynthEngine(val sampleRate: Int, val maxBlock: Int = 1024, private val bank: SampleBank? = null) {
    val hasBank: Boolean get() = bank != null
    private val strings = Array(72) { StringVoice(sampleRate) }
    /** Sampled electric-guitar strings (only allocated with a bank): 8 tracks x 7 strings plus release tails. */
    private val samples = Array(if (bank != null) 96 else 0) { SampleVoice(sampleRate) }
    private val sampleMap = IntArray(MAX_TRACKS * 16) { -1 }
    private val roundRobin = IntArray(MAX_TRACKS * 16)
    private val drums = Array(48) { DrumVoice(sampleRate) }
    private val keys = Array(48) { KeysVoice(sampleRate) }
    private val clicks = Array(6) { ClickVoice(sampleRate) }
    private val stringMap = IntArray(MAX_TRACKS * 16) { -1 }
    private val masterL = FloatArray(maxBlock)
    /** Per-sample decay of the stolen-voice residual: 1/e after 4 ms. */
    private val residualDecay = Dsp.timeCoefficient(0.004, sampleRate)
    private val masterR = FloatArray(maxBlock)
    /**
     * Metronome and reference clips (QA cycle 20): they are mixed here and reach the master delayed by the rack latency, like
     * every channel with less effect latency than the slowest one, so they stay on the frame of the cabinet-convolved guitars.
     */
    private val auxL = FloatArray(maxBlock)
    private val auxR = FloatArray(maxBlock)
    private val auxDelayL = FloatArray(MAX_COMPENSATION)
    private val auxDelayR = FloatArray(MAX_COMPENSATION)
    private var auxDelay = 0
    private var auxPos = 0
    private var program: EngineProgram? = null
    private var clipSet: ClipSet = ClipSet.EMPTY
    /**
     * Clip de-click (QA cycle 20): reference audio is read at the plan position, so a stop, a seek, a loop wrap or a start in
     * the middle of a clip jumped from one sample value to another (up to -6 dBFS steps on a sine). The old read now fades out
     * ([clipTailPos], [clipTailLeft] frames from block offset [clipTailOffset]) while the new one fades in ([clipInLeft]), 3 ms.
     */
    private val clipFade = (0.003 * sampleRate).toInt().coerceAtLeast(1)
    private var clipTailPos = 0L
    private var clipTailLeft = 0
    private var clipTailOffset = 0
    private var clipInLeft = 0
    /** Reference-audio level relative to the synth (0 = synth only). */
    var clipLevel = 1f
    private var rack: ChannelRack? = null
    private var mix: MixState = MixState.SILENT
    private var nextNote = 0
    private var nextClick = 0
    private var loopStart = -1L
    private var loopEnd = -1L
    private var countInLeft = 0L
    private var countInElapsed = 0L
    private var countInClicks = LongArray(0)
    private var countInIndex = 0
    /** Noise seed per track (slot [MAX_TRACKS] for tracks beyond it); see [trigger]. */
    private val seeds = IntArray(MAX_TRACKS + 1) { 0x1234567 }

    /** Monotonic count of rendered frames. */
    var clock = 0L; private set
    /** Plan frame of the next rendered sample. */
    var position = 0L; private set
    var playing = false; private set
    var countingIn = false; private set
    var ended = false; private set
    var loopsCompleted = 0L; private set
    var voicesInUse = 0; private set

    fun install(next: EngineProgram?) {
        program = next
        nextNote = next?.firstNoteAtOrAfter(position) ?: 0
        nextClick = next?.firstClickAtOrAfter(position) ?: 0
        ended = false
    }
    fun installClips(next: ClipSet) { clipSet = next }
    /**
     * Swaps the DSP rack. When every channel keeps its voice signature (an effect parameter changed), sounding notes go on
     * and unchanged effects keep their state; otherwise all voices stop (track list or sounds changed).
     */
    fun installRack(next: ChannelRack) {
        val old = rack
        if (old === next) return
        // Plain index loop: `indices.all { }` allocated an IntRange and its iterator on the audio thread (QA cycle 20).
        var compatible = old != null && old.channels.size == next.channels.size
        if (compatible) for (t in next.channels.indices) if (old!!.channels[t].voiceSignature != next.channels[t].voiceSignature) { compatible = false; break }
        setAuxDelay(next.latencyFrames)
        if (!compatible) { releaseAll(fast = true); rack = next; return }
        for (t in next.channels.indices) {
            val a = old!!.channels[t]; val b = next.channels[t]
            for (i in b.effects.indices) if (i < a.effects.size && i < a.units.size && i < b.units.size && a.units[i] == b.units[i]) b.effects[i] = a.effects[i]
            b.idleBlocks = a.idleBlocks; b.lastGainL = a.lastGainL; b.lastGainR = a.lastGainR; b.gainPrimed = a.gainPrimed; b.residualL = a.residualL; b.residualR = a.residualR; b.silentBlocks = a.silentBlocks; b.effectsCleared = a.effectsCleared
            b.takeCompensationFrom(a)
        }
        rack = next
    }
    private fun setAuxDelay(frames: Int) {
        val d = frames.coerceIn(0, MAX_COMPENSATION)
        if (d == auxDelay) return
        auxDelay = d; auxPos = 0; auxDelayL.fill(0f); auxDelayR.fill(0f)
    }
    fun setMix(next: MixState) { mix = next }
    fun currentRack(): ChannelRack? = rack

    fun play(from: Long, countInFrames: Long = 0, countInClickOffsets: LongArray = NO_CLICKS) {
        seekInternal(from, chase = countInFrames == 0L)
        restartDrums()
        countInLeft = countInFrames.coerceAtLeast(0)
        countInElapsed = 0
        countInClicks = countInClickOffsets
        countInIndex = 0
        countingIn = countInLeft > 0
        playing = true
        ended = false
    }
    fun pause() { clipCut(0); playing = false; countingIn = false; countInLeft = 0; releaseAll(fast = false) }
    fun seek(frame: Long) { seekInternal(frame, chase = playing && !countingIn) }
    fun setLoop(start: Long, end: Long) { if (start in 0 until end) { loopStart = start; loopEnd = end } else { loopStart = -1; loopEnd = -1 } }
    val loopActive: Boolean get() = loopEnd > loopStart && loopStart >= 0

    /** Immediate audition outside the plan (note entry). Uses the track's channel and string choke. */
    fun preview(note: ScheduledNote) { trigger(note, 0, note.endFrame - note.frame) }

    private fun seekInternal(frame: Long, chase: Boolean) {
        releaseAll(fast = true)
        clipCut(0)
        position = frame.coerceAtLeast(0)
        clipFadeIn()
        val p = program ?: return
        nextNote = p.firstNoteAtOrAfter(position)
        nextClick = p.firstClickAtOrAfter(position)
        ended = false
        if (chase) {
            // Sustained notes already sounding at the seek point restart there (no curves, at least 50 ms left).
            var i = nextNote - 1
            val horizon = position - 4L * sampleRate
            while (i >= 0 && p.noteFrames[i] >= horizon) {
                val n = p.notes[i]
                if (n.endFrame - position > sampleRate / 20 && n.curve.isEmpty() && rack?.channels?.getOrNull(n.trackIndex)?.kind != ChannelKind.DRUMS) trigger(n, 0, n.endFrame - position)
                i--
            }
        }
    }

    /**
     * Transport start (QA 19, "der erste Drum-Hit fehlt manchmal"): what sounded before Play (a previewed grid cell, the
     * last hits before Stop) must not shape the first hit. Measured before: up to 200 ms after such a sound the first hit
     * lost up to 1.8 dB over 50 ms and up to 4.2 dB in its 5 ms attack - the drum compressor was still in gain reduction and the
     * 15 ms choke of the previewed head overlapped the new stroke. Drum voices stop here with the 4 ms residual de-click of
     * a stolen voice, and the drum channels' compressors start from rest; reverb/delay tails and every other channel are untouched.
     */
    private fun restartDrums() {
        val r = rack
        for (v in drums) if (v.active) { if (r != null) stolen(r, v.track, v.lastL, v.lastR); v.kill() }
        if (r != null) for (ch in r.channels) if (ch.kind == ChannelKind.DRUMS) for (fx in ch.effects) if (fx is Compressor) fx.reset()
    }

    fun releaseAll(fast: Boolean) {
        for (v in strings) v.release(fast)
        for (v in samples) v.release(fast)
        for (v in keys) v.release(fast)
        if (fast) for (v in drums) v.choke()
        stringMap.fill(-1)
        sampleMap.fill(-1)
    }

    fun render(out: FloatArray, frames: Int) {
        require(frames in 1..maxBlock) { "Blockgröße außerhalb des Engine-Puffers" }
        val r = rack
        if (r != null) for (ch in r.channels) { ch.left.fill(0f, 0, frames); if (ch.stereo) ch.right.fill(0f, 0, frames) }
        masterL.fill(0f, 0, frames); masterR.fill(0f, 0, frames)
        auxL.fill(0f, 0, frames); auxR.fill(0f, 0, frames)
        var done = 0
        while (done < frames) {
            var segEnd = frames
            if (countInLeft > 0) {
                segEnd = minOf(frames.toLong(), done + countInLeft).toInt()
                while (countInIndex < countInClicks.size && countInClicks[countInIndex] <= countInElapsed) { startClick(if (countInIndex == 0) 0 else 1, force = true); countInIndex++ }
                if (countInIndex < countInClicks.size) segEnd = minOf(segEnd.toLong(), done + (countInClicks[countInIndex] - countInElapsed)).toInt().coerceAtLeast(done + 1)
                renderVoices(done, segEnd - done)
                val n = (segEnd - done).toLong()
                countInLeft -= n; countInElapsed += n
                if (countInLeft <= 0) { countingIn = false; countInLeft = 0 }
                done = segEnd
                continue
            }
            val p = program
            if (playing && p != null) {
                while (nextNote < p.noteFrames.size && p.noteFrames[nextNote] <= position) { trigger(p.notes[nextNote], done, p.notes[nextNote].endFrame - p.notes[nextNote].frame); nextNote++ }
                while (nextClick < p.clickFrames.size && p.clickFrames[nextClick] <= position) { if (mix.metronome > 0f) startClick(p.clickLevels[nextClick], force = false); nextClick++ }
                var limit = position + (frames - done)
                if (loopActive && position < loopEnd) limit = minOf(limit, loopEnd)
                if (nextNote < p.noteFrames.size) limit = minOf(limit, p.noteFrames[nextNote])
                if (nextClick < p.clickFrames.size) limit = minOf(limit, p.clickFrames[nextClick])
                val end = p.endFrame
                if (!loopActive && position < end) limit = minOf(limit, end)
                segEnd = (done + (limit - position)).toInt().coerceIn(done + 1, frames)
                renderVoices(done, segEnd - done)
                if (clipSet.exactSpeed && clipLevel > 0f) {
                    val n = segEnd - done
                    if (clipInLeft > 0) {
                        val g0 = (clipFade - clipInLeft + 1).toFloat() / clipFade
                        for (c in clipSet.voices) c.mixRamp(auxL, auxR, done, n, position, clipLevel, g0, 1f / clipFade)
                        clipInLeft = (clipInLeft - n).coerceAtLeast(0)
                    } else for (c in clipSet.voices) c.mix(auxL, auxR, done, n, position, clipLevel)
                }
                position += (segEnd - done)
                if (loopActive && position >= loopEnd) {
                    clipCut(segEnd)
                    position = loopStart
                    clipFadeIn()
                    releaseAll(fast = true)
                    nextNote = p.firstNoteAtOrAfter(loopStart); nextClick = p.firstClickAtOrAfter(loopStart)
                    loopsCompleted++
                } else if (!loopActive && position >= end && nextNote >= p.noteFrames.size) {
                    playing = false; ended = true
                }
            } else renderVoices(done, segEnd - done)
            done = segEnd
        }
        if (clipTailLeft > 0) mixClipTail(frames)
        mixDown(frames)
        for (i in 0 until frames) { out[2 * i] = masterL[i]; out[2 * i + 1] = masterR[i] }
        clock += frames
    }

    private fun renderVoices(offset: Int, count: Int) {
        if (count <= 0) return
        val r = rack ?: return
        val now = clock + offset
        var used = 0
        for (v in strings) if (v.active) { val ch = r.channels.getOrNull(v.track); if (ch == null) v.kill() else { v.render(ch.left, offset, count, now); ch.idleBlocks = 0; used++ } }
        for (v in samples) if (v.active) { val ch = r.channels.getOrNull(v.track); if (ch == null) v.kill() else { v.render(ch.left, offset, count, now); ch.idleBlocks = 0; used++ } }
        for (v in keys) if (v.active) { val ch = r.channels.getOrNull(v.track); if (ch == null) v.kill() else { v.render(ch.left, offset, count, now); ch.idleBlocks = 0; used++ } }
        for (v in drums) if (v.active) { val ch = r.channels.getOrNull(v.track); if (ch == null) v.kill() else { v.render(ch.left, ch.right, offset, count, now); ch.idleBlocks = 0; used++ } }
        for (v in clicks) if (v.active) v.render(auxL, auxR, offset, count, mix.metronome.coerceAtLeast(0.6f))
        for (ch in r.channels) if (ch.residualL != 0f || ch.residualR != 0f) {
            var rl = ch.residualL; var rr = ch.residualR
            for (k in offset until offset + count) {
                ch.left[k] += rl; rl *= residualDecay
                if (ch.stereo) { ch.right[k] += rr; rr *= residualDecay }
            }
            ch.residualL = if (abs(rl) < 1e-7f) 0f else rl; ch.residualR = if (abs(rr) < 1e-7f) 0f else rr
            ch.idleBlocks = 0
        }
        voicesInUse = used
    }

    private fun mixDown(frames: Int) {
        // Metronome and clips first (the order the master sum always had), delayed to the rack latency.
        val d = auxDelay
        if (d == 0) { auxL.copyInto(masterL, 0, 0, frames); auxR.copyInto(masterR, 0, 0, frames) }
        else {
            var p = auxPos
            for (i in 0 until frames) {
                masterL[i] = auxDelayL[p]; auxDelayL[p] = auxL[i]
                masterR[i] = auxDelayR[p]; auxDelayR[p] = auxR[i]
                p++; if (p == d) p = 0
            }
            auxPos = p
        }
        val r = rack ?: return
        // A channel is skipped once its voices are gone and its processed output has been silent long enough to cover the
        // longest delay gap; then its effects are cleared so no frozen tail comes back on the next note.
        val idleMin = (sampleRate / 4 / frames).coerceAtLeast(1)
        val silentLimit = (3L * sampleRate / frames).toInt()
        for (t in r.channels.indices) {
            val ch = r.channels[t]
            val g = if (t < mix.gains.size) mix.gains[t] else 0f
            val pan = if (t < mix.pans.size) mix.pans[t] else 0f
            val angle = (pan + 1f) * (PI / 4).toFloat()
            val targetL = g * cos(angle) * 1.414f
            val targetR = g * sin(angle) * 1.414f
            if (ch.idleBlocks > idleMin && ch.silentBlocks > silentLimit && ch.lastGainL == targetL && ch.lastGainR == targetR) {
                if (!ch.effectsCleared) { for (fx in ch.effects) fx.reset(); ch.clearCompensation(); ch.effectsCleared = true }
                ch.idleBlocks++; continue
            }
            ch.effectsCleared = false
            for (fx in ch.effects) fx.process(ch.left, if (ch.stereo) ch.right else null, frames)
            ch.compensate(frames)
            var peak = 0f
            for (i in 0 until frames) { val v = abs(ch.left[i]); if (v > peak) peak = v }
            if (ch.stereo) for (i in 0 until frames) { val v = abs(ch.right[i]); if (v > peak) peak = v }
            ch.silentBlocks = if (peak < 1e-5f) ch.silentBlocks + 1 else 0
            if (!ch.gainPrimed) { ch.lastGainL = targetL; ch.lastGainR = targetR; ch.gainPrimed = true }
            val startL = ch.lastGainL; val startR = ch.lastGainR
            for (i in 0 until frames) {
                val a = (i + 1).toFloat() / frames
                val gl = startL + (targetL - startL) * a
                val gr = startR + (targetR - startR) * a
                if (ch.stereo) { masterL[i] += ch.left[i] * gl; masterR[i] += ch.right[i] * gr }
                else { val s = ch.left[i]; masterL[i] += s * gl; masterR[i] += s * gr }
            }
            ch.lastGainL = targetL; ch.lastGainR = targetR
            ch.idleBlocks++
        }
        val m = mix.master * 0.5f
        for (i in 0 until frames) { masterL[i] *= m; masterR[i] *= m }
        // Reference clips were mixed into the master bus above, so they share its headroom scaling and limiter with the synth.
        for (fx in r.master) fx.process(masterL, masterR, frames)
    }

    /** The clip read at [position] stops (stop, seek, wrap at block offset [offset]): it fades out instead of jumping to 0. */
    private fun clipCut(offset: Int) {
        if (!playing || countingIn || !clipSet.exactSpeed || clipSet.voices.isEmpty() || clipLevel <= 0f) return
        clipTailPos = position; clipTailLeft = clipFade; clipTailOffset = offset
    }
    /** A read that starts inside a clip (not at its first frame) fades in; one at a clip's start keeps its first sample. */
    private fun clipFadeIn() {
        clipInLeft = 0
        for (c in clipSet.voices) if (position > c.startFrame && position - c.startFrame < c.pcm.frames) clipInLeft = clipFade
    }
    private fun mixClipTail(frames: Int) {
        val n = minOf(frames - clipTailOffset, clipTailLeft)
        if (n > 0 && clipSet.exactSpeed) {
            val g0 = (clipTailLeft - 1).toFloat() / clipFade
            for (c in clipSet.voices) c.mixRamp(auxL, auxR, clipTailOffset, n, clipTailPos, clipLevel, g0, -1f / clipFade)
        }
        clipTailPos += n.coerceAtLeast(0); clipTailLeft -= n.coerceAtLeast(0); clipTailOffset = 0
        if (!clipSet.exactSpeed) clipTailLeft = 0
    }

    private fun startClick(level: Int, force: Boolean) {
        if (!force && mix.metronome <= 0f) return
        val v = clicks.firstOrNull { !it.active } ?: clicks[0]
        v.start(level)
    }

    private fun trigger(note: ScheduledNote, offset: Int, duration: Long) {
        // One non-finite parameter would turn the channel's effect state (and the master) into NaN until the rack is rebuilt.
        if (!note.soundingPitch.isFinite() || !note.elementGain.isFinite() || !note.elementPan.isFinite()) return
        note.vibrato?.let { if (!it.rateHz.isFinite() || !it.depthCents.isFinite()) return }
        for (i in note.curve.indices) if (!note.curve[i].cents.isFinite()) return
        val r = rack ?: return
        val t = note.trackIndex
        val ch = r.channels.getOrNull(t) ?: return
        if (t < mix.gains.size && mix.gains[t] <= 0f) return
        val now = clock + offset
        val endClock = now + duration.coerceAtLeast(1)
        // One noise seed per track (QA cycle 21): a track draws the same excitation/drum noise in the full mix as alone, so
        // the stem exports (one track soloed) sum to the mix. With one shared seed a stem skipped the other tracks' steps and
        // its snare noise differed from the mix by up to -2.7 dBFS.
        val slot = if (t in 0 until MAX_TRACKS) t else MAX_TRACKS
        val seed = seeds[slot] * 1103515245 + 12345 + note.frame.toInt() * 31 + note.soundingPitch.toInt()
        seeds[slot] = seed
        when (ch.kind) {
            ChannelKind.STRING -> {
                if (t >= MAX_TRACKS) return
                // Notes without a string (unplaced imports, guitar preset on an unfretted track) never choke each other.
                val s = note.string
                val key = if (s != null && s in 0..15) t * 16 + s else -1
                // A map entry is only trusted while its voice still plays this track and string (stolen voices move on).
                fun ownsSample(i: Int) = i >= 0 && samples[i].active && samples[i].track == t && samples[i].string == s
                fun ownsString(i: Int) = i >= 0 && strings[i].active && strings[i].track == t && strings[i].string == s
                val b = bank
                if (b != null && ch.sampled && note.harmonic == null) {
                    if (key >= 0) {
                        val existing = sampleMap[key]
                        if (ownsSample(existing)) {
                            val v = samples[existing]
                            if (note.attack == AttackKind.LEGATO && !v.releasing) { v.legato(note, now, endClock, ch.sampleGain); return }
                            // A new pick on the same string stops the old vibration at once (no damping noise).
                            v.release(fast = true)
                        }
                        stringMap[key].let { if (ownsString(it)) strings[it].release(fast = true) }
                    }
                    val index = allocate(samples, { it.active }, { it.releasing }, { it.startClock })
                    if (samples[index].active) stolen(r, samples[index].track, samples[index].lastOut, 0f)
                    samples[index].kill()
                    // Seeded by string: a unison on two strings starts on different round robins instead of the identical sample.
                    val rr = if (key >= 0) (roundRobin[key]++ + (s ?: 0)) and 0xFFFF else seed ushr 16 and 0xFFFF
                    samples[index].start(note, t, b, now, endClock, rr, ch.sampleGain)
                    if (key >= 0) sampleMap[key] = index
                    return
                }
                if (key >= 0) {
                    sampleMap[key].let { if (ownsSample(it)) samples[it].release(fast = true) }
                    val existing = stringMap[key]
                    if (ownsString(existing)) {
                        val v = strings[existing]
                        if (note.attack == AttackKind.LEGATO && !v.releasing) { v.legato(note, now, endClock); return }
                        v.release(fast = true)
                    }
                }
                val index = allocate(strings, { it.active }, { it.releasing }, { it.startClock })
                if (strings[index].active) stolen(r, strings[index].track, strings[index].lastOut, 0f)
                strings[index].kill()
                // With a bank the modelled string only plays harmonics next to the samples, at their trimmed level.
                strings[index].start(note, t, if (b != null && ch.sampled) ch.harmonicTone else ch.stringTone, now, endClock, seed)
                if (key >= 0) stringMap[key] = index
                // Picked harmonics (pinch, artificial, tapped) also sound the recorded fretted note through the harmonic comb:
                // its pick attack, string noise and natural overtone series ride on the modelled overtone (QA cycle 19).
                val n = if (b != null && ch.sampled) pickedHarmonicNumber(note) else 1
                if (n > 1 && b != null) {
                    val si = allocate(samples, { it.active }, { it.releasing }, { it.startClock })
                    if (samples[si].active) stolen(r, samples[si].track, samples[si].lastOut, 0f)
                    samples[si].kill()
                    val rr = if (key >= 0) (roundRobin[key]++ + (s ?: 0)) and 0xFFFF else seed ushr 16 and 0xFFFF
                    val pinch = note.harmonic == HarmonicKind.PINCH
                    samples[si].start(note, t, b, now, endClock, rr, ch.sampleGain, harmonic = n, combGain = if (pinch) 2.2f else 1.4f, combBleed = if (pinch) 0.1f else 0.04f)
                    if (key >= 0) sampleMap[key] = si
                }
            }
            ChannelKind.DRUMS -> {
                val midi = note.soundingPitch.toInt()
                for (v in drums) if (v.active && v.track == t && ((note.chokeGroup == 1 && v.chokeGroup == 1) || (v.midi == midi && v.isHead))) v.choke()
                val index = allocate(drums, { it.active }, { false }, { it.startClock })
                if (drums[index].active) stolen(r, drums[index].track, drums[index].lastL, drums[index].lastR)
                drums[index].kill()
                drums[index].start(note, t, now, seed)
            }
            ChannelKind.KEYS -> {
                val index = allocate(keys, { it.active }, { false }, { it.startClock })
                if (keys[index].active) stolen(r, keys[index].track, keys[index].lastOut, 0f)
                keys[index].kill()
                keys[index].start(note, t, ch.keysMode, now, endClock)
            }
        }
    }

    /**
     * Overtone number of a picked harmonic (pinch +19.02 = 3rd, artificial/tapped +12 = 2nd) from its sounding pitch above
     * the written (fretted) one; 1 for natural/semi harmonics (sounding at the node's overtone of the open string, played
     * modelled only) and for anything that is not a whole overtone.
     */
    private fun pickedHarmonicNumber(note: ScheduledNote): Int {
        val h = note.harmonic ?: return 1
        if (h != HarmonicKind.PINCH && h != HarmonicKind.ARTIFICIAL && h != HarmonicKind.TAPPED) return 1
        val interval = note.soundingPitch - (note.pitch.midiNumber + note.pitch.cents / 100.0)
        for (k in 2..6) if (abs(interval - 12.0 * ln(k.toDouble()) / ln(2.0)) < 0.3) return k
        return 1
    }

    /** A voice taken from a full pool stops at once; its last output fades out in its track buffer instead of jumping to 0. */
    private fun stolen(r: ChannelRack, track: Int, left: Float, right: Float) {
        val ch = r.channels.getOrNull(track) ?: return
        if (left.isFinite()) ch.residualL += left
        if (right.isFinite()) ch.residualR += right
    }

    private inline fun <T> allocate(pool: Array<T>, active: (T) -> Boolean, releasing: (T) -> Boolean, started: (T) -> Long): Int {
        for (i in pool.indices) if (!active(pool[i])) return i
        var best = 0; var bestScore = Long.MAX_VALUE
        for (i in pool.indices) {
            val score = started(pool[i]) - if (releasing(pool[i])) Long.MAX_VALUE / 4 else 0
            if (score < bestScore) { bestScore = score; best = i }
        }
        return best
    }

    companion object {
        const val MAX_TRACKS = 64
        /** Longest latency compensation (frames); a chain of 64 cabinets would exceed it. */
        const val MAX_COMPENSATION = 4096
    }
}
