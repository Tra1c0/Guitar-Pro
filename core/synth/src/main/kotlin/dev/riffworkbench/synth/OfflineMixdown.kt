package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import kotlin.math.roundToLong

/**
 * C-RENDER: the same compiler, engine and mix graph as live playback, rendered faster than real time.
 * Every stem starts at frame 0 of the unfolded route and has the same length, so DAW imports line up.
 */
class OfflineMixdown(private val compiler: ScorePlaybackCompiler = ScorePlaybackCompiler(), private val bank: SampleBank? = null) {
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
        val block = 1024
        val engine = SynthEngine(options.sampleRate, block, bank)
        engine.installRack(ChannelRack.build(project, options.sampleRate, block, includeMasterFx = options.includeMasterFx && soloTrack == null))
        engine.setMix(MixState.from(project, 0f, soloTrack))
        engine.install(EngineProgram.prepare(plan))
        if (soloTrack == null) engine.installClips(clips)
        engine.play(options.commonStartFrame.coerceAtLeast(0))
        val header = Wav.header(total, options.sampleRate, 2, options.bitDepth)
        sink.write(header, 0, header.size)
        val buffer = FloatArray(block * 2)
        val bytes = ByteArray(block * 2 * 4)
        val stats = Wav.Stats()
        var written = 0L
        while (written < total) {
            if (isCancelled()) return Outcome.Failure(FailureCode.CANCELLED, "Rendern abgebrochen", contractId = "C-RENDER")
            val n = minOf(block.toLong(), total - written).toInt()
            engine.render(buffer, n)
            val count = Wav.encode(buffer, n * 2, options.bitDepth, bytes, stats)
            sink.write(bytes, 0, count)
            written += n
            if ((written / block) % 64 == 0L) progress(OperationProgress(written, total, if (soloTrack == null) "Mix" else project.tracks[soloTrack].name))
        }
        progress(OperationProgress(total, total, "Fertig"))
        return Outcome.Success(RenderSummary(total, options.sampleRate, 2, stats.peak.toDouble(), stats.clipped, header.size + Wav.dataBytes(total, 2, options.bitDepth)))
    }
}
