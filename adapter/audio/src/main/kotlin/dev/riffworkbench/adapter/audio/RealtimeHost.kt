package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.AudioSink
import dev.riffworkbench.ports.SinkFormat
import dev.riffworkbench.synth.*
import java.util.concurrent.ConcurrentLinkedQueue

sealed interface HostCommand {
    class Install(val program: EngineProgram?) : HostCommand
    class Rack(val rack: ChannelRack) : HostCommand
    class Mix(val mix: MixState) : HostCommand
    class Play(val from: Long, val countInFrames: Long, val clickOffsets: LongArray) : HostCommand
    data object Pause : HostCommand
    class Seek(val frame: Long) : HostCommand
    class Loop(val start: Long, val end: Long) : HostCommand
    class Preview(val note: ScheduledNote, val postedNanos: Long = System.nanoTime()) : HostCommand
    class Clips(val clips: ClipSet) : HostCommand
    /** Replaces the program at a new speed and maps the playhead via the old plan's route position. */
    /** New plan plus its loop frames (-1 = none), applied in one step so no block plays the new plan with the old loop. */
    class Reprogram(val program: EngineProgram, val mapFrom: PlaybackPlan?, val loopStart: Long = -2, val loopEnd: Long = -2) : HostCommand
}

/** What the UI may read; produced by the control thread from data the audio thread published. */
data class HostSnapshot(val playing: Boolean, val countingIn: Boolean, val ended: Boolean, val planFrame: Long, val loopsCompleted: Long, val underruns: Long, val running: Boolean)

/**
 * Measured audio performance (diagnostics page). Render times are the DSP work per callback block, against the block
 * budget (frames / rate). Latency comes from the device timestamps, the note-on figure from command posting to the
 * first sample leaving the speaker. All values come from the running device, nothing is estimated from buffer sizes.
 */
data class HostStats(
    val sampleRate: Int, val blockFrames: Int, val blocks: Long, val budgetMicros: Double,
    val renderMedianMicros: Double, val renderP99Micros: Double, val renderMaxMicros: Double, val overruns: Long,
    val underruns: Long, val outputLatencyMs: Double?, val noteOnLatencyMs: Double?, val voices: Int, val hasSamples: Boolean, val deviceName: String,
)

/**
 * Owns the engine and the render thread. Control threads only post immutable commands; the audio thread
 * drains them between blocks, renders and writes. Positions are published per block into primitive rings,
 * so reading the playhead never locks the audio thread and never drives the transport.
 */
class RealtimeHost(
    private val sinkFactory: () -> AudioSink,
    private val threadStarter: (String, () -> Unit) -> Thread = { name, body -> Thread(body, name).apply { isDaemon = true; start() } },
    private val preferredRate: Int = 48_000,
    private val preferredBlock: Int = 192,
    private val idleStopSeconds: Double = 20.0,
    /** Sample bank for the electric guitars; read when the engine is created (null = modelled strings only). */
    private val bankProvider: () -> SampleBank? = { null },
) {
    // Render-time ring (ns per block) and counters for [stats]; written only by the audio thread.
    private val timingSize = 8192
    private val timing = LongArray(timingSize)
    @Volatile private var overruns = 0L
    @Volatile private var noteOnNanos = -1L
    @Volatile private var lastVoices = 0
    private val commands = ConcurrentLinkedQueue<HostCommand>()
    private val sinkRef = java.util.concurrent.atomic.AtomicReference<AudioSink?>(null)
    private val sink: AudioSink? get() = sinkRef.get()
    @Volatile var format: SinkFormat? = null; private set
    @Volatile private var running = false
    /** Set when the render thread stopped because of an unexpected error (device or driver). */
    @Volatile var lastError: String? = null; private set
    @Volatile private var thread: Thread? = null
    private var engine: SynthEngine? = null
    private var buffer = FloatArray(0)
    // Published by the audio thread.
    private val ringSize = 1024
    private val ringSink = LongArray(ringSize)
    private val ringPlan = LongArray(ringSize)
    private val ringFlags = IntArray(ringSize)
    @Volatile private var blocks = 0L
    @Volatile private var written = 0L
    @Volatile private var loops = 0L
    @Volatile private var lastFlags = 0
    @Volatile private var lastPlan = 0L
    private var idleBlocks = 0L

    val isRunning: Boolean get() = running

    @Synchronized fun start(): Outcome<SinkFormat> {
        format?.let { if (running) return Outcome.Success(it) }
        // A render thread that is winding down (idle stop or error) must finish before a new session reuses the engine;
        // its cleanup never takes this monitor, so joining here cannot deadlock.
        thread?.let { old -> if (old !== Thread.currentThread() && old.isAlive) { old.join(2000); if (old.isAlive) return Outcome.Failure(FailureCode.UNAVAILABLE, "Audio-Thread reagiert nicht", contractId = "C-AUDIO") } }
        val s = sinkFactory()
        val opened = s.open(preferredRate, preferredBlock)
        if (opened is Outcome.Failure) { s.close(); return opened }
        val f = (opened as Outcome.Success).value
        sinkRef.getAndSet(s)?.close(); format = f
        val previous = engine
        val bank = bankProvider()
        engine = if (previous != null && previous.sampleRate == f.sampleRate && previous.hasBank == (bank != null)) previous else SynthEngine(f.sampleRate, maxOf(1024, f.blockFrames), bank)
        buffer = FloatArray(f.blockFrames * 2)
        written = 0; blocks = 0; idleBlocks = 0
        running = true
        val session = ++generation
        thread = threadStarter("riff-audio") {
            try { while (running && renderBlock()) Unit }
            catch (t: Throwable) { lastError = t.message ?: t::class.java.simpleName }
            finally {
                // Only this session's state: a newer start() may already own `running` and the current sink.
                if (generation == session) running = false
                if (sinkRef.compareAndSet(s, null)) s.close()
            }
        }
        return Outcome.Success(f)
    }

    @Synchronized fun stop() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) it.join(2000) }
        thread = null
        closeSink()
    }

    @Volatile private var generation = 0
    @Synchronized private fun closeSink() { sinkRef.getAndSet(null)?.close() }

    fun post(command: HostCommand) { commands.add(command) }

    /** One audio cycle. Public for deterministic tests that drive the host without a thread. */
    fun renderBlock(): Boolean {
        val s = sink ?: return false
        val e = engine ?: return false
        val f = format ?: return false
        while (true) {
            when (val c = commands.poll() ?: break) {
                is HostCommand.Install -> e.install(c.program)
                is HostCommand.Rack -> e.installRack(c.rack)
                is HostCommand.Mix -> e.setMix(c.mix)
                is HostCommand.Play -> e.play(c.from, c.countInFrames, c.clickOffsets)
                HostCommand.Pause -> e.pause()
                is HostCommand.Seek -> e.seek(c.frame)
                is HostCommand.Loop -> e.setLoop(c.start, c.end)
                is HostCommand.Preview -> {
                    e.preview(c.note)
                    // Note-on response: command queue delay plus the audio already queued in front of this block.
                    val queued = (written - (s.presentedFrame() ?: (written - f.latencyFrames))).coerceAtLeast(0)
                    noteOnNanos = (System.nanoTime() - c.postedNanos) + queued * 1_000_000_000L / f.sampleRate
                }
                is HostCommand.Clips -> e.installClips(c.clips)
                is HostCommand.Reprogram -> {
                    val target = c.mapFrom?.positionAt(e.position)?.let { pos -> c.program.plan.frameFor(pos, orNearest = true) }
                    val wasPlaying = e.playing
                    e.install(c.program)
                    if (c.loopStart != -2L) e.setLoop(c.loopStart, c.loopEnd)
                    // During a count-in the engine is "playing" but must keep counting: only move the start point.
                    if (target != null) { if (wasPlaying && !e.countingIn) e.play(target) else e.seek(target) }
                }
            }
        }
        val frames = f.blockFrames
        val flags = (if (e.playing) 1 else 0) or (if (e.countingIn) 2 else 0) or (if (e.ended) 4 else 0)
        val slot = (blocks % ringSize).toInt()
        ringSink[slot] = written; ringPlan[slot] = e.position; ringFlags[slot] = flags
        val t0 = System.nanoTime()
        e.render(buffer, frames)
        val dt = System.nanoTime() - t0
        timing[(blocks % timingSize).toInt()] = dt
        if (dt * f.sampleRate > frames * 1_000_000_000L) overruns++
        lastVoices = e.voicesInUse
        blocks++
        lastFlags = (if (e.playing) 1 else 0) or (if (e.countingIn) 2 else 0) or (if (e.ended) 4 else 0)
        lastPlan = e.position
        loops = e.loopsCompleted
        val accepted = s.write(buffer, frames)
        written += accepted
        idleBlocks = if (e.playing || e.countingIn || e.voicesInUse > 0) 0 else idleBlocks + 1
        if (idleBlocks * frames > idleStopSeconds * f.sampleRate) { running = false; return false }
        return accepted == frames
    }

    /** Statistics over the last ~[timingSize] blocks; computed on the caller's thread (allocates, never the audio thread). */
    fun stats(): HostStats? {
        val f = format ?: return null
        val count = blocks
        val n = minOf(count, timingSize.toLong()).toInt()
        val sorted = LongArray(n) { timing[((count - 1 - it) % timingSize).toInt()] }.also { it.sort() }
        fun pct(p: Double) = if (n == 0) 0.0 else sorted[((n - 1) * p).toInt()] / 1000.0
        val presented = sink?.presentedFrame()
        return HostStats(f.sampleRate, f.blockFrames, count, f.blockFrames * 1e6 / f.sampleRate, pct(0.5), pct(0.99), if (n == 0) 0.0 else sorted[n - 1] / 1000.0,
            overruns, sink?.underruns() ?: 0, presented?.let { (written - it).coerceAtLeast(0) * 1000.0 / f.sampleRate }, noteOnNanos.takeIf { it >= 0 }?.let { it / 1e6 },
            lastVoices, engine?.hasBank == true, f.deviceName)
    }

    /** Playhead from the newest block that has already reached the speaker. */
    fun snapshot(): HostSnapshot {
        val f = format
        val count = blocks
        if (f == null || count == 0L) return HostSnapshot(false, false, false, lastPlan, loops, 0, running)
        val presented = sink?.presentedFrame() ?: (written - f.latencyFrames).coerceAtLeast(0)
        var i = count - 1
        val oldest = maxOf(0L, count - ringSize + 8)
        while (i > oldest && ringSink[(i % ringSize).toInt()] > presented) i--
        val slot = (i % ringSize).toInt()
        val flags = ringFlags[slot]
        val playing = flags and 1 != 0 && flags and 2 == 0
        val within = (presented - ringSink[slot]).coerceIn(0, f.blockFrames.toLong())
        val plan = ringPlan[slot] + if (playing) within else 0
        return HostSnapshot(lastFlags and 1 != 0, flags and 2 != 0, lastFlags and 4 != 0, plan, loops, sink?.underruns() ?: 0, running)
    }
}
