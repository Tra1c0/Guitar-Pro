package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import kotlin.math.roundToLong

/**
 * C-RENDER: the same compiler, engine and mix graph as live playback, rendered faster than real time.
 * Every stem starts at frame 0 of the unfolded route and has the same length, so DAW imports line up.
 *
 * Latency compensation (QA cycle 21): live, every source is heard [ChannelRack.outputLatencyFrames] after its plan frame
 * (cabinet convolution 64 frames, cycle 20). The export drops exactly that many leading engine frames, like a DAW's
 * plugin-delay compensation: bar 1, beat 1 lies on frame 0 of the file, the file still holds the whole plan plus the tail
 * (nothing is cut at the end), and mix and stems line up although the stems have no master chain. The engine renders the
 * same blocks as before, so export frame i is bit-identical to live/engine frame i + latency.
 */
class OfflineMixdown(private val compiler: ScorePlaybackCompiler = ScorePlaybackCompiler(), private val bank: SampleBank? = null, private val drumKit: DrumKit? = null) {
    fun framesFor(project: Project, options: RenderOptions): Outcome<Long> = when (val plan = compiler.compileNow(project, options.sampleRate)) {
        is Outcome.Failure -> plan
        is Outcome.Success -> Outcome.Success(plan.value.totalFrames + (options.tailSeconds.coerceIn(0.0, 30.0) * options.sampleRate).roundToLong())
    }

    /** Renders the full mix ([soloTrack] = null) or one isolated track as WAV into [sink]. */
    fun render(project: Project, options: RenderOptions, sink: ByteSink, soloTrack: Int? = null, isCancelled: () -> Boolean = { false }, progress: (OperationProgress) -> Unit = {}, clips: ClipSet = ClipSet.EMPTY): Outcome<RenderSummary> {
        if (options.bitDepth !in setOf(16, 24, 32)) return Outcome.Failure(FailureCode.VALIDATION, "Bittiefe ${options.bitDepth} nicht unterstützt", contractId = "C-RENDER")
        val plan = when (val r = compiler.compileNow(project, options.sampleRate)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val total = plan.totalFrames + (options.tailSeconds.coerceIn(0.0, 30.0) * options.sampleRate).roundToLong()
        if (total <= 0) return Outcome.Failure(FailureCode.VALIDATION, "Nichts zu rendern", contractId = "C-RENDER")
        // WAV sizes are 32-bit: a very long route must fail as a typed limit, not throw from the header writer.
        if (Wav.dataBytes(total, 2, options.bitDepth) + 36 > 0xFFFFFFFFL) return Outcome.Failure(FailureCode.RESOURCE_LIMIT, "Audio-Export wäre größer als 4 GiB (WAV-Grenze); bitte kürzer oder mit geringerer Bittiefe exportieren", contractId = "C-RENDER")
        val block = 1024
        val engine = SynthEngine(options.sampleRate, block, bank, drumKit)
        val rack = ChannelRack.build(project, options.sampleRate, block, includeMasterFx = options.includeMasterFx && soloTrack == null)
        engine.installRack(rack)
        // A stem is for the DAW: the master fader is a listening level, not part of it (QA 22). Stems render at the factory
        // master level, so they never depend on (or clip from) a raised master fader and Σ stems = mix holds at factory settings.
        val mixProject = if (soloTrack == null) project else project.copy(mix = project.mix.copy(master = project.mix.master.copy(volume = ChannelMix().volume)))
        engine.setMix(MixState.from(mixProject, 0f, soloTrack))
        engine.install(EngineProgram.prepare(plan))
        if (soloTrack == null) engine.installClips(clips)
        engine.play(options.commonStartFrame.coerceAtLeast(0))
        val header = Wav.header(total, options.sampleRate, 2, options.bitDepth)
        sink.write(header, 0, header.size)
        val buffer = FloatArray(block * 2)
        val bytes = ByteArray(block * 2 * 4)
        val stats = Wav.Stats()
        var written = 0L
        // Leading engine frames that only carry the effect latency: rendered (the same blocks as before) but not written.
        var skip = rack.outputLatencyFrames.toLong()
        while (written < total) {
            if (isCancelled()) return Outcome.Failure(FailureCode.CANCELLED, "Rendern abgebrochen", contractId = "C-RENDER")
            val n = minOf(block.toLong(), total - written + skip).toInt()
            engine.render(buffer, n)
            val drop = minOf(skip, n.toLong()).toInt()
            skip -= drop
            if (drop == n) continue
            if (drop > 0) buffer.copyInto(buffer, 0, drop * 2, n * 2)
            val count = Wav.encode(buffer, (n - drop) * 2, options.bitDepth, bytes, stats)
            sink.write(bytes, 0, count)
            written += n - drop
            if ((written / block) % 64 == 0L) progress(OperationProgress(written, total, if (soloTrack == null) "Mix" else project.tracks[soloTrack].name))
        }
        progress(OperationProgress(total, total, "Fertig"))
        return Outcome.Success(RenderSummary(total, options.sampleRate, 2, stats.peak.toDouble(), stats.clipped, header.size + Wav.dataBytes(total, 2, options.bitDepth)))
    }
}
