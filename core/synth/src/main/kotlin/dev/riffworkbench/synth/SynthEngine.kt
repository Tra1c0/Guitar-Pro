package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import kotlin.math.PI
import kotlin.math.cos
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
                       /** Electric guitar: played from the sample bank when one is installed. */ val sampled: Boolean = false, val sampleGain: Float = 1f) {
    val left = FloatArray(maxBlock)
    val right = FloatArray(maxBlock)
    val stereo = kind == ChannelKind.DRUMS
    var idleBlocks = Int.MAX_VALUE / 2
    var lastGainL = 0f; var lastGainR = 0f
}

/** Per-track DSP channels. Rebuilt only when sounds or effect chains change, so reverb tails survive note edits. */
class ChannelRack internal constructor(internal val channels: Array<Channel>, internal val master: Array<Effect>, val signature: String) {
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
                val chain = TonePresets.effectiveChain(track).mapNotNull { Effects.create(it, sampleRate, maxBlock) }.toTypedArray()
                Channel(kind, TonePresets.stringTone(preset), TonePresets.keysMode(preset), chain, maxBlock, kind == ChannelKind.STRING && TonePresets.isSampledGuitar(preset), TonePresets.sampleGain(preset))
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
    private val masterR = FloatArray(maxBlock)
    private var program: EngineProgram? = null
    private var clipSet: ClipSet = ClipSet.EMPTY
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
    private var seed = 0x1234567

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
    fun installRack(next: ChannelRack) { if (rack !== next) { releaseAll(fast = true); rack = next } }
    fun setMix(next: MixState) { mix = next }
    fun currentRack(): ChannelRack? = rack

    fun play(from: Long, countInFrames: Long = 0, countInClickOffsets: LongArray = NO_CLICKS) {
        seekInternal(from, chase = countInFrames == 0L)
        countInLeft = countInFrames.coerceAtLeast(0)
        countInElapsed = 0
        countInClicks = countInClickOffsets
        countInIndex = 0
        countingIn = countInLeft > 0
        playing = true
        ended = false
    }
    fun pause() { playing = false; countingIn = false; countInLeft = 0; releaseAll(fast = false) }
    fun seek(frame: Long) { seekInternal(frame, chase = playing && !countingIn) }
    fun setLoop(start: Long, end: Long) { if (start in 0 until end) { loopStart = start; loopEnd = end } else { loopStart = -1; loopEnd = -1 } }
    val loopActive: Boolean get() = loopEnd > loopStart && loopStart >= 0

    /** Immediate audition outside the plan (note entry). Uses the track's channel and string choke. */
    fun preview(note: ScheduledNote) { trigger(note, 0, note.endFrame - note.frame) }

    private fun seekInternal(frame: Long, chase: Boolean) {
        releaseAll(fast = true)
        position = frame.coerceAtLeast(0)
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
                if (clipSet.exactSpeed && clipLevel > 0f) for (c in clipSet.voices) c.mix(masterL, masterR, done, segEnd - done, position, clipLevel)
                position += (segEnd - done)
                if (loopActive && position >= loopEnd) {
                    position = loopStart
                    releaseAll(fast = true)
                    nextNote = p.firstNoteAtOrAfter(loopStart); nextClick = p.firstClickAtOrAfter(loopStart)
                    loopsCompleted++
                } else if (!loopActive && position >= end && nextNote >= p.noteFrames.size) {
                    playing = false; ended = true
                }
            } else renderVoices(done, segEnd - done)
            done = segEnd
        }
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
        for (v in clicks) if (v.active) v.render(masterL, masterR, offset, count, mix.metronome.coerceAtLeast(0.6f))
        voicesInUse = used
    }

    private fun mixDown(frames: Int) {
        val r = rack ?: return
        val tailBlocks = (6L * sampleRate / frames).toInt()
        for (t in r.channels.indices) {
            val ch = r.channels[t]
            val g = if (t < mix.gains.size) mix.gains[t] else 0f
            val pan = if (t < mix.pans.size) mix.pans[t] else 0f
            val angle = (pan + 1f) * (PI / 4).toFloat()
            val targetL = g * cos(angle) * 1.414f
            val targetR = g * sin(angle) * 1.414f
            if (ch.idleBlocks > tailBlocks && ch.lastGainL == targetL && ch.lastGainR == targetR) { ch.idleBlocks++; continue }
            for (fx in ch.effects) fx.process(ch.left, if (ch.stereo) ch.right else null, frames)
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
        seed = seed * 1103515245 + 12345 + note.frame.toInt() * 31 + note.soundingPitch.toInt()
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
                    samples[index].kill()
                    val rr = if (key >= 0) roundRobin[key]++ and 0xFFFF else seed ushr 16 and 0xFFFF
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
                strings[index].kill()
                strings[index].start(note, t, ch.stringTone, now, endClock, seed)
                if (key >= 0) stringMap[key] = index
            }
            ChannelKind.DRUMS -> {
                val midi = note.soundingPitch.toInt()
                for (v in drums) if (v.active && v.track == t && ((note.chokeGroup == 1 && v.chokeGroup == 1) || (v.midi == midi && v.isHead))) v.choke()
                val index = allocate(drums, { it.active }, { false }, { it.startClock })
                drums[index].kill()
                drums[index].start(note, t, now, seed)
            }
            ChannelKind.KEYS -> {
                val index = allocate(keys, { it.active }, { false }, { it.startClock })
                keys[index].kill()
                keys[index].start(note, t, ch.keysMode, now, endClock)
            }
        }
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

    companion object { const val MAX_TRACKS = 64 }
}
