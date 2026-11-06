package dev.riffworkbench.adapter.audio

import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.SR
import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.f
import dev.riffworkbench.adapter.audio.Qa20Kit.Companion.plan
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.*
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * QA cycle 20, synchronicity: guitar (high gain, sampled), bass, drums, metronome click and a reference clip (backing) must
 * start on the same beat to the sample - criterion 1 ms (48 frames) between the sources and between live and offline.
 *
 * Material: one short hit per beat on every source (guitar/bass 16ths, drum cross-stick, click, a 2-ms sine burst in the
 * clip at every beat of the plan), so each onset follows silence. Each source is rendered alone (the others muted) through
 * the live path (EngineTransport -> RealtimeHost.renderBlock, Qa20Sink, 192-frame blocks) and, where it exists, through
 * OfflineMixdown. Onset = first sample at or above -40 dB re the hit's peak (window -10..+50 ms around the expected frame);
 * a hit is only measured when the 20 ms before it stay below that threshold. Expected output frames come from the plan's
 * beat grid (its metronome clicks) mapped through count-in, loop wraps and speed changes.
 */
class Qa20SyncTest {
    private val kit = Qa20Kit("q20s")
    companion object {
        const val BLOCK = 192
        const val TOL = 48
        const val GUITAR = 0; const val BASS = 1; const val DRUMS = 2; const val CLICK = 3; const val CLIP = 4
        val names = listOf("Gitarre", "Bass", "Drums", "Klick", "Clip")
    }

    private fun pulseBar(make: () -> Note, den: Int = 16) = (0 until 4).map { k -> kit.beat(f(k.toLong(), 4), den, make()) }
    /** One hit per beat on guitar, bass and drums; [measures] carry repeats/meters, [tempo] the tempo map. */
    private fun pulseProject(measures: List<Measure>, tempo: List<TempoChange>): Project {
        var s = 0
        val gBars = measures.map { pulseBar({ kit.g(listOf(0, 1, 2)[s++ % 3], 0, 100) }) }
        val bBars = measures.map { pulseBar({ kit.b(1, 0) }) }
        val dBars = measures.map { pulseBar({ kit.d(37, 110) }) }
        return kit.project(measures, listOf(
            kit.track("Gitarre", Qa20Kit.guitarDef, gBars, measures),
            kit.track("Bass", Qa20Kit.bassDef, bBars, measures, "rw.bass.pick"),
            kit.track("Drums", Qa20Kit.drumsDef, dBars, measures, kind = NotationKind.PERCUSSION)), tempo, listOf(kit.clip()))
    }
    /** The backing: a 2-ms 1.5 kHz burst on every beat of the plan at speed 1 (the clip is recorded to the score's timeline). */
    private fun backing(p: Project): PcmBuffer {
        val beats = plan(p).clicks.map { it.frame.toInt() }
        val len = (plan(p).totalFrames + SR).toInt()
        val mark = BooleanArray(len); for (b in beats) for (i in b until minOf(len, b + 96)) mark[i] = true
        return kit.pcm(len) { i -> if (mark[i]) 0.5 * kotlin.math.sin((i - beats.last { it <= i }) * 2 * Math.PI * 1500 / SR) else 0.0 }
    }
    private fun solo(p: Project, source: Int): Project = p.copy(
        tracks = p.tracks.mapIndexed { i, t -> t.copy(mix = t.mix.copy(mute = i != source)) },
        audio = p.audio.copy(clips = p.audio.clips.map { it.copy(mute = source != CLIP) }))

    /** How the run moves through the plan: speed, count-in, loop, a start frame and a speed change after [changeAfterBlocks]. */
    data class Run(val label: String, val speed: Double = 1.0, val countIn: Int = 0, val loop: Pair<Int, Int>? = null, val startBar: Int = 0,
                   val changeAfterBlocks: Int = -1, val changeTo: Double = 1.0, val seconds: Double = 0.0)

    /** Output frames of the plan beats for a live run, and the live capture of one source. */
    private class Capture(val left: FloatArray, val beats: List<Long>, val countInClicks: List<Long>)

    private fun live(p: Project, source: Int, run: Run, pcm: PcmBuffer): Capture = runBlocking {
        val q = solo(p, source)
        val pl = plan(q, run.speed)
        val loopRange = run.loop?.let { (a, b) -> LoopRange(PlaybackOccurrence(ScorePosition(q.measures[a].id), 1, routeIndexOf(pl, a)), PlaybackOccurrence(ScorePosition(q.measures[b].id), 1, routeIndexOf(pl, b))) }
        val seconds = if (run.seconds > 0) run.seconds else pl.totalFrames.toDouble() / SR + 1.0 + run.countIn * 4 * 60.0 / 90.0 / run.speed
        val frames = (seconds * SR).toInt()
        val sink = Qa20Sink(BLOCK, frames + BLOCK)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0, bankProvider = { Qa18FirstBeatTest.bank })
        val t = EngineTransport(host, clipLoader = { _, _ -> Outcome.Success(pcm) }, pollMillis = 1_000_000)
        try {
            t.prepare(q, PracticeSettings(speed = run.speed, countInBars = run.countIn, metronome = source == CLICK, loop = loopRange))
            if (run.startBar > 0) t.seek(PlaybackOccurrence(ScorePosition(q.measures[run.startBar].id), 1, routeIndexOf(pl, run.startBar)))
            check(t.play() is Outcome.Success)
            // Where the engine starts: the transport's published start frame; the count-in precedes it.
            val from = t.state.value.sample!!.frame
            val countIn = countInFrames(t, pl, from, run.countIn)
            val beats = ArrayList<Long>()
            var blocks = 0
            val total = frames / BLOCK
            var cur = pl; var planAt = from; var outAt = countIn.first
            fun emit(plan: PlaybackPlan, a: Long, b: Long, out0: Long) { for (c in plan.clicks) if (c.frame in a until b) beats += out0 + (c.frame - a) }
            if (run.changeAfterBlocks >= 0) {
                while (blocks < run.changeAfterBlocks) { host.renderBlock(); blocks++ }
                // Speed trainer step: recompiled plan, applied by the next block at the remapped position.
                val outChange = blocks.toLong() * BLOCK
                val planChange = from + (outChange - countIn.first)
                emit(cur, planAt, planChange, outAt)
                t.setSpeed(run.changeTo)
                val next = plan(q, run.changeTo)
                cur = next; planAt = next.frameFromPlan(pl, planChange); outAt = outChange
            }
            while (blocks < total) { host.renderBlock(); blocks++ }
            val end = blocks.toLong() * BLOCK
            if (run.loop != null) {
                val (ls, le) = loopFrames(cur, run.loop)
                var a = planAt; var o = outAt
                while (o < end) { emit(cur, a, le, o); o += le - a; a = ls }
            } else emit(cur, planAt, cur.totalFrames, outAt)
            Capture(sink.left.copyOf(frames), beats.filter { it < frames - SR / 10 }, countIn.second)
        } finally { t.shutdown() }
    }
    private fun routeIndexOf(pl: PlaybackPlan, bar: Int) = pl.spans.indexOfFirst { it.measureIndex == bar }
    private fun loopFrames(pl: PlaybackPlan, loop: Pair<Int, Int>): Pair<Long, Long> = pl.spans.first { it.measureIndex == loop.first }.startFrame to pl.spans.first { it.measureIndex == loop.second }.endFrame
    private fun countInFrames(t: EngineTransport, pl: PlaybackPlan, from: Long, bars: Int): Pair<Long, List<Long>> {
        if (bars == 0) return 0L to emptyList()
        val m = EngineTransport::class.java.getDeclaredMethod("countIn", PlaybackPlan::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val r = m.invoke(t, pl, from) as Pair<Long, LongArray>
        return r.first to r.second.toList()
    }

    private fun offline(p: Project, source: Int, pcm: PcmBuffer): FloatArray {
        val q = solo(p, source)
        val bytes = java.io.ByteArrayOutputStream()
        val clips = ClipSet.build(q, mapOf(Qa20Kit.CLIP_HASH to pcm), SR, 1.0)
        val r = OfflineMixdown(ScorePlaybackCompiler(), Qa18FirstBeatTest.bank).render(q, RenderOptions(sampleRate = SR, bitDepth = 32, tailSeconds = 1.0), { b, o, l -> bytes.write(b, o, l) }, clips = clips)
        check(r is Outcome.Success)
        val d = bytes.toByteArray(); val frames = (d.size - 44) / 8
        return FloatArray(frames) { i -> val o = 44 + i * 8; Float.fromBits((d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or ((d[o + 2].toInt() and 0xFF) shl 16) or (d[o + 3].toInt() shl 24)) }
    }

    /** Onset of the hit expected at [e] (frames relative to e), or null when the hit is missing or not measurable. */
    private fun onset(x: FloatArray, e: Long): Int? {
        val a = (e - SR / 100).toInt().coerceAtLeast(0); val b = minOf(x.size, (e + SR / 20).toInt())
        var peak = 0f; for (i in a until b) peak = maxOf(peak, abs(x[i]))
        if (peak < 1e-4f) return null
        // A reverb tail of the previous hit (drums at 250 bpm) may still sound: the threshold sits 10 dB above it, and the
        // hit only counts when that tail is at least 20 dB below its peak.
        var floor = 0f; for (i in maxOf(0, a - SR / 50) until a) floor = maxOf(floor, abs(x[i]))
        if (floor > peak * 0.1f) return null
        val th = maxOf(peak * 0.01f, floor * 3.16f)
        for (i in a until b) if (abs(x[i]) >= th) return (i - e).toInt()
        return null
    }

    private fun scenarios(): List<Pair<Project, Run>> {
        fun bars(n: Int) = List(n) { Measure(kit.id()) }
        fun tempo(m: List<Measure>, qpm: Double) = listOf(TempoChange(ScorePosition(m[0].id), qpm))
        val out = ArrayList<Pair<Project, Run>>()
        for (qpm in listOf(90.0, 150.0, 200.0)) bars(3).let { m -> out += pulseProject(m, tempo(m, qpm)) to Run("Tempo ${qpm.toInt()}") }
        bars(4).let { m -> out += pulseProject(m, listOf(TempoChange(ScorePosition(m[0].id), 90.0), TempoChange(ScorePosition(m[2].id), 150.0), TempoChange(ScorePosition(m[3].id, f(1, 2)), 200.0))) to Run("Tempowechsel 90→150→200 (Takt 3, Takt 4 Zz. 3)") }
        listOf(Measure(kit.id()), Measure(kit.id(), repeat = RepeatMark(starts = true)), Measure(kit.id(), repeat = RepeatMark(endCount = 2)), Measure(kit.id())).let { m -> out += pulseProject(m, tempo(m, 150.0)) to Run("Wiederholung Takt 2–3 ×2, 150") }
        bars(3).let { m -> out += pulseProject(m, tempo(m, 150.0)) to Run("Vorzähler 1 Takt, 150", countIn = 1) }
        bars(3).let { m -> out += pulseProject(m, tempo(m, 150.0)) to Run("Vorzähler 1 Takt ab Takt 2, 150", countIn = 1, startBar = 1) }
        bars(3).let { m -> out += pulseProject(m, tempo(m, 150.0)) to Run("Schleife Takt 2–3, 3 Durchläufe, 150", loop = 1 to 2, seconds = 3 * 3.2 + 0.5) }
        for (speed in listOf(0.5, 1.25)) bars(3).let { m -> out += pulseProject(m, tempo(m, 150.0)) to Run("Speed ${speed}, 150", speed = speed) }
        bars(3).let { m -> out += pulseProject(m, tempo(m, 150.0)) to Run("Speed-Trainer 0,5 → 1,25 in Takt 2, Schleife Takt 1–3", speed = 0.5, loop = 0 to 2, changeAfterBlocks = 1000, changeTo = 1.25, seconds = 12.0) }
        return out
    }

    @Test fun guitarBassDrumsClickAndClipStartOnTheSameFrame() {
        assumeTrue("sample bank not found", Qa18FirstBeatTest.bank != null)
        val failures = ArrayList<String>()
        for ((p, run) in scenarios()) {
            val pcm = backing(p)
            val sources = if (run.speed == 1.0 && run.changeAfterBlocks < 0) listOf(GUITAR, BASS, DRUMS, CLICK, CLIP) else listOf(GUITAR, BASS, DRUMS, CLICK)
            val caps = sources.associateWith { live(p, it, run, pcm) }
            val beats = caps.getValue(GUITAR).beats
            val stats = sources.associateWith { IntArray(3).also { it[0] = Int.MAX_VALUE; it[1] = Int.MIN_VALUE } }
            var worstSpread = 0; var worstAt = -1L; var measured = 0; var missing = 0
            for (e in beats) {
                val on = sources.mapNotNull { s -> onset(caps.getValue(s).left, e)?.let { s to it } }
                if (on.size < sources.size) { missing++; if (missing <= 3) failures += "${run.label}: Schlag bei Ausgabeframe $e fehlt/unmessbar in ${sources.filter { s -> on.none { it.first == s } }.map { names[it] }}"; continue }
                measured++
                for ((s, o) in on) { val st = stats.getValue(s); st[0] = minOf(st[0], o); st[1] = maxOf(st[1], o) }
                val spread = on.maxOf { it.second } - on.minOf { it.second }
                if (spread > worstSpread) { worstSpread = spread; worstAt = e }
            }
            // Count-in clicks: on the beat grid that continues into the first bar.
            var countInWorst = 0
            if (run.countIn > 0) for (c in caps.getValue(CLICK).countInClicks) {
                val o = onset(caps.getValue(CLICK).left, c)
                // Against the metronome's own offset in the bars that follow (the whole mix is delayed by the rack latency).
                if (o == null) failures += "${run.label}: Vorzähler-Klick bei $c fehlt" else countInWorst = maxOf(countInWorst, abs(o - stats.getValue(CLICK)[0]))
            }
            if (countInWorst > TOL) failures += "${run.label}: Vorzähler-Klicks $countInWorst Frames neben dem Raster der Takte"
            println("Q20S ${run.label}: ${beats.size} Schläge, $measured gemessen | Onset je Quelle (min..max Frames nach Soll) " +
                sources.joinToString(", ") { "${names[it]} ${stats.getValue(it)[0]}..${stats.getValue(it)[1]}" } + " | größte Spreizung zwischen Quellen $worstSpread Frames (Schlag bei $worstAt)" +
                (if (run.countIn > 0) " | Vorzähler-Klicks max |Δ| zum Klick-Raster der Takte $countInWorst Frames" else ""))
            if (worstSpread > TOL) failures += "${run.label}: Quellen $worstSpread Frames auseinander (Schlag bei Ausgabeframe $worstAt)"
            if (measured < beats.size * 9 / 10) failures += "${run.label}: nur $measured von ${beats.size} Schlägen messbar"

            // Live against offline (offline has no metronome, count-in, loop or speed): same onsets per source.
            if (run.speed == 1.0 && run.loop == null && run.changeAfterBlocks < 0) {
                val shift = if (run.countIn > 0) beats.first() - plan(p).clicks.first { it.frame >= plan(p).spans[run.startBar].startFrame }.frame else 0L
                var worst = 0; var worstSrc = ""
                for (s in sources - CLICK) {
                    val off = offline(p, s, pcm)
                    for (e in beats) {
                        val a = onset(caps.getValue(s).left, e) ?: continue
                        val b = onset(off, e - shift) ?: run { failures += "${run.label}: ${names[s]} offline ohne Schlag bei ${e - shift}"; null } ?: continue
                        if (abs(a - b) > worst) { worst = abs(a - b); worstSrc = names[s] }
                    }
                }
                println("Q20S ${run.label}: live gegen offline größte Onset-Abweichung $worst Frames ($worstSrc)")
                if (worst > TOL) failures += "${run.label}: live/offline $worst Frames ($worstSrc)"
            }
        }
        assertTrue("Synchronität: " + failures.joinToString("; "), failures.isEmpty())
    }
}
