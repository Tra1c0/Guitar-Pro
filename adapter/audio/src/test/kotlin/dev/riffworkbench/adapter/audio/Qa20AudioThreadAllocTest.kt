package dev.riffworkbench.adapter.audio

import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.SR
import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.f
import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.plan
import dev.riffworkbench.model.*
import dev.riffworkbench.synth.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA cycle 20, audio-thread hygiene: bytes allocated on the rendering thread (ThreadMXBean.getThreadAllocatedBytes, as in
 * core/synth FixReviewAllocReviewTest) while the engine and the host run every path changed in cycles 18/19 - sampled guitar
 * with bank v2 (round robin per string, fine tune/gain), pinch/artificial/tapped harmonic comb, natural harmonics, palm-mute
 * filter glide, dead notes, bends/whammy/slides/legato/trill/tremolo, let ring, drums with chokes and restartDrums() on every
 * play, keys, metronome, count-in, reference clip, previews, seek while playing, loop wraps, pause -> play, rack/program
 * swaps - and, at the host level, the command handling of RealtimeHost.renderBlock (Install, Rack, Mix, Clips, Play, Pause,
 * Seek, Loop, Preview, Reprogram).
 *
 * Every step (command objects, programs, racks) is built before the measurement; only the audio-thread work is counted.
 * The scenario first runs [WARM_UPS] times on twin engines, so the JIT has compiled the paths. The budget is 0 bytes.
 */
class Qa20AudioThreadAllocTest {
    companion object {
        const val WARM_UPS = 4
        private val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun allocated(): Long = mx.getThreadAllocatedBytes(Thread.currentThread().id)
    }
    private val kit = Qa20Kit("q20al")
    private val bank get() = Qa18FirstBeatTest.bank

    /** A labelled step on the audio thread; [run] must only touch prepared objects. */
    private class Step(val label: String, val run: () -> Unit)

    /** Engine scenario: everything a session does on the audio thread, in block-sized renders of [block] frames. */
    private fun engineSteps(e: SynthEngine, block: Int): List<Step> {
        val p = kit.everything()
        val pl = plan(p)
        val prog = EngineProgram.prepare(pl)
        val prog2 = EngineProgram.prepare(plan(p.copy(revision = 1)))
        val rack = ChannelRack.build(p, SR, maxOf(1024, block))
        val rackSame = ChannelRack.build(p, SR, maxOf(1024, block))
        val mixOn = MixState.from(p, 1f)
        val pcm = kit.pcm(SR * 4) { i -> 0.3 * kotlin.math.sin(i * 2 * Math.PI * 220 / SR) }
        val clips = ClipSet(arrayOf(ClipVoice(EntityId("clip"), pcm, 0, 1.0, 0.0)), exactSpeed = true)
        val bar = pl.spans[0].endFrame
        val clicks = longArrayOf(0, bar / 4, bar / 2, 3 * bar / 4)
        val guitarNote = pl.notes.first { it.trackIndex == 0 && it.harmonic == HarmonicKind.PINCH }
        val drumNote = pl.notes.first { it.trackIndex == 2 }
        val out = FloatArray(2 * maxOf(1024, block))
        val steps = ArrayList<Step>()
        fun render(label: String, blocks: Int) { repeat(blocks) { k -> steps += Step("$label #$k") { e.render(out, block) } } }
        val second = SR / block
        steps += Step("installRack") { e.installRack(rack) }
        steps += Step("setMix") { e.setMix(mixOn) }
        steps += Step("installClips") { e.installClips(clips) }
        steps += Step("install") { e.install(prog) }
        steps += Step("play with count-in") { e.play(0, bar, clicks) }
        render("count-in and bars 1-2", (bar + 2 * bar) .toInt() / block)
        steps += Step("seek while playing") { e.seek(bar / 2) }
        render("after seek", second)
        steps += Step("preview pinch + drum") { e.preview(guitarNote); e.preview(drumNote) }
        render("after preview", second / 2)
        steps += Step("loop bar 2") { e.setLoop(bar, 2 * bar) }
        steps += Step("seek into loop") { e.seek(bar + bar / 3) }
        render("loop wraps", (3 * bar).toInt() / block)
        steps += Step("pause") { e.pause() }
        render("paused", second / 2)
        steps += Step("play (restartDrums)") { e.play(bar + bar / 8) }
        render("after play", second / 2)
        steps += Step("play again while drums ring") { e.play(bar) }
        render("after replay", second / 2)
        steps += Step("compatible rack swap") { e.installRack(rackSame) }
        steps += Step("install new program") { e.install(prog2) }
        steps += Step("loop off") { e.setLoop(-1, -1) }
        render("to the end and tail", ((pl.totalFrames - bar) + 2 * SR).toInt() / block)
        return steps
    }

    private fun measure(steps: List<Step>): Pair<Long, List<String>> {
        var total = 0L
        val worst = ArrayList<String>()
        for (s in steps) {
            val a0 = allocated(); s.run(); val d = allocated() - a0
            if (d > 0) { total += d; if (worst.size < 12) worst += "${s.label}: $d B" }
        }
        return total to worst
    }

    @Test fun engineRendersEveryNewPathWithoutAllocation() {
        val failures = ArrayList<String>()
        println("Q20A JVM ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, args ${java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-XX") }}")
        for (withBank in listOf(true, false)) {
            val bk = if (withBank) bank else null
            if (withBank && bk == null) { println("Q20A sample bank not found, sampled paths skipped"); continue }
            for (block in listOf(192, 256)) {
                repeat(WARM_UPS) { val w = SynthEngine(SR, maxOf(1024, block), bk); for (s in engineSteps(w, block)) s.run() }
                val e = SynthEngine(SR, maxOf(1024, block), bk)
                val steps = engineSteps(e, block)
                val (total, worst) = measure(steps)
                println("Q20A engine (bank=${bk != null}, block $block, ${steps.size} steps): allocated $total B on the audio thread ${if (worst.isEmpty()) "" else worst.toString()}")
                if (total != 0L) failures += "bank=${bk != null} block $block: $total B $worst"
            }
        }
        assertTrue("allocation on the audio thread: $failures", failures.isEmpty())
    }

    /** Host scenario: commands posted from the control side (allocation there is fine), drained and applied by renderBlock(). */
    private fun hostRun(block: Int, measureIt: Boolean): Pair<Long, List<String>> {
        val bk = bank
        val sink = Qa20Sink(block)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0, bankProvider = { bk })
        check(host.start() is Outcome.Success)
        val p = kit.everything()
        val pl = plan(p); val pl2 = plan(p, 1.25)
        val prog = EngineProgram.prepare(pl); val prog2 = EngineProgram.prepare(pl2); val prog3 = EngineProgram.prepare(plan(p))
        val rack = ChannelRack.build(p, SR, maxOf(1024, block))
        val pcm = kit.pcm(SR * 4) { i -> 0.3 * kotlin.math.sin(i * 2 * Math.PI * 220 / SR) }
        val clips = ClipSet(arrayOf(ClipVoice(EntityId("clip"), pcm, 0, 1.0, 0.0)), exactSpeed = true)
        val bar = pl.spans[0].endFrame
        val preview = pl.notes.first { it.trackIndex == 0 }.copy(frame = 0, endFrame = SR / 2L)
        var total = 0L
        val worst = ArrayList<String>()
        fun blocks(label: String, k: Int) = repeat(k) { i ->
            val a0 = allocated(); host.renderBlock(); val d = allocated() - a0
            if (measureIt && d > 0) { total += d; if (worst.size < 12) worst += "$label #$i: $d B" }
        }
        val second = SR / block
        host.post(HostCommand.Rack(rack)); host.post(HostCommand.Mix(MixState.from(p, 1f))); host.post(HostCommand.Clips(clips)); host.post(HostCommand.Install(prog))
        host.post(HostCommand.Play(0, bar, longArrayOf(0, bar / 4, bar / 2, 3 * bar / 4)))
        blocks("Play with count-in", 3 * second)
        host.post(HostCommand.Seek(bar / 3)); blocks("Seek", second)
        host.post(HostCommand.Preview(preview)); blocks("Preview", second / 2)
        host.post(HostCommand.Loop(bar, 2 * bar)); host.post(HostCommand.Seek(bar)); blocks("Loop", 3 * second)
        host.post(HostCommand.Reprogram(prog2, pl, bar, (2 * bar / 1.25).toLong())); blocks("Reprogram speed 1.25", second)
        host.post(HostCommand.Reprogram(prog3, pl2, -1, -1)); blocks("Reprogram back", second)
        host.post(HostCommand.Pause); blocks("Pause", second / 2)
        host.post(HostCommand.Play(bar / 2, 0, LongArray(0))); blocks("Play", second)
        host.post(HostCommand.Mix(MixState.from(p, 0f))); host.post(HostCommand.Install(prog)); blocks("Mix + Install", second)
        host.stop()
        return total to worst
    }

    /**
     * Locks: while another thread holds every monitor of the host (the instance monitor of start/stop/closeSink and the
     * idle-stop handshake lock), renderBlock() with all commands must neither block nor wait - the only synchronized block
     * on the audio thread is the documented idle stop, which this session (idle stop at 1000 s) never reaches. Measured
     * with ThreadMXBean blocked/waited counts; a monitor taken on the render path would also hang the loop until release.
     */
    @Test fun renderBlockTakesNoMonitorTheControlSideHolds() {
        val bk = bank
        val block = 192
        val host = RealtimeHost({ Qa20Sink(block) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0, bankProvider = { bk })
        check(host.start() is Outcome.Success)
        val p = kit.everything(); val pl = plan(p); val pl2 = plan(p, 0.5)
        val bar = pl.spans[0].endFrame
        val idleLock = RealtimeHost::class.java.getDeclaredField("idleLock").apply { isAccessible = true }.get(host)
        val held = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread { synchronized(host) { synchronized(idleLock) { held.countDown(); release.await() } } }.apply { isDaemon = true; start() }
        check(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val cmds = listOf(HostCommand.Rack(ChannelRack.build(p, SR, 1024)), HostCommand.Mix(MixState.from(p, 1f)), HostCommand.Install(EngineProgram.prepare(pl)),
            HostCommand.Play(0, bar, longArrayOf(0, bar / 2)), HostCommand.Seek(bar / 2), HostCommand.Loop(0, bar), HostCommand.Preview(pl.notes.first().copy(frame = 0, endFrame = 9600)),
            HostCommand.Reprogram(EngineProgram.prepare(pl2), pl), HostCommand.Pause, HostCommand.Play(bar / 4, 0, LongArray(0)))
        val tid = Thread.currentThread().id
        mx.isThreadContentionMonitoringEnabled = true
        val before = mx.getThreadInfo(tid)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val watchdog = Thread { Thread.sleep(20_000); if (done.get() < cmds.size * 50) release.countDown() }.apply { isDaemon = true; start() }
        val t0 = System.nanoTime()
        // post() itself reads `running` under the idle lock (control side): enqueue directly, as post() does before that read.
        @Suppress("UNCHECKED_CAST") val queue = RealtimeHost::class.java.getDeclaredField("commands").apply { isAccessible = true }.get(host) as java.util.Queue<HostCommand>
        for (c in cmds) { queue.add(c); repeat(50) { host.renderBlock(); done.incrementAndGet() } }
        val ms = (System.nanoTime() - t0) / 1e6
        val after = mx.getThreadInfo(tid)
        release.countDown(); holder.join(5000); watchdog.interrupt()
        host.stop()
        println("Q20A locks: ${done.get()} blocks with ${cmds.size} command kinds while the host monitors were held elsewhere: ${"%.0f".format(ms)} ms, blocked ${after.blockedCount - before.blockedCount}x, waited ${after.waitedCount - before.waitedCount}x")
        assertTrue("render thread blocked/waited on a monitor", after.blockedCount == before.blockedCount && after.waitedCount == before.waitedCount)
    }

    @Test fun hostCommandHandlingDoesNotAllocateOnTheAudioThread() {
        val failures = ArrayList<String>()
        for (block in listOf(192, 256)) {
            repeat(WARM_UPS) { hostRun(block, measureIt = false) }
            val (total, worst) = hostRun(block, measureIt = true)
            println("Q20A host (bank=${bank != null}, block $block): allocated $total B in renderBlock() ${if (worst.isEmpty()) "" else worst.toString()}")
            if (total != 0L) failures += "block $block: $total B $worst"
        }
        assertTrue("allocation in RealtimeHost.renderBlock: $failures", failures.isEmpty())
    }
}
