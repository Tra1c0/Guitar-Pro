package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.PcmBuffer
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * QA cycle 13: model-based randomised testing of [EngineTransport] on a manually driven [RealtimeHost].
 * Run with -Dqa13.n=2000 -Dqa13.len=40 for the full campaign; the default is a short smoke run.
 */
class Qa13ModelBasedTransportTest {
    // ---------------------------------------------------------------- operations (parameters only: replayable)
    sealed class Op {
        data class Switch(val seed: Long, val settings: Long) : Op()
        data class Edit(val kind: Int, val param: Int, val settings: Long?) : Op()
        data class PrepareSame(val settings: Long?) : Op()
        data object Play : Op()
        data object Pause : Op()
        data object Stop : Op()
        data class Seek(val param: Int, val stale: Boolean) : Op()
        data class SetLoop(val a: Int, val b: Int, val kind: Int) : Op()
        data class Render(val k: Int, val sleep: Long) : Op()
        data class Sleep(val ms: Long) : Op()
        /** togglePlay (prepare [+ seek] + play) racing a halt issued while a reference clip is decoding. */
        data class Race(val seed: Long, val settings: Long, val stop: Boolean, val seek: Int?) : Op()
    }
    data class Step(val op: Op, val settle: Long?)
    data class Violation(val inv: String, val step: Int, val msg: String)

    // ---------------------------------------------------------------- project specs
    data class BarSpec(val id: EntityId, val num: Int, val note: Boolean, val repStart: Boolean = false, val repEnd: Int? = null)
    data class ProjSpec(val id: EntityId, val revision: Long, val bars: List<BarSpec>, val bpm: Double, val track: EntityId, val clip: AssetHash?)

    class Ids(private val prefix: String) { private var n = 0; fun next() = EntityId("$prefix${n++}") }

    companion object {
        val compiler = ScorePlaybackCompiler()
        private val hashCounter = AtomicInteger()
        fun freshHash() = AssetHash(hashCounter.incrementAndGet().toString(16).padStart(64, '0'))

        fun build(s: ProjSpec): Project {
            val m = s.bars.map { Measure(it.id, TimeSignature(it.num, 4), repeat = RepeatMark(starts = it.repStart, endCount = it.repEnd)) }
            val bars = s.bars.mapIndexed { i, b ->
                val beats = if (b.note) listOf(Beat(EntityId("${b.id}be"), Fraction.ZERO, WrittenDuration(4), listOf(Note(EntityId("${b.id}n"), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0)))) else emptyList()
                Bar(EntityId("${b.id}b$i"), b.id, List(4) { v -> Voice(EntityId("${b.id}v$v"), v, if (v == 0) beats else emptyList()) })
            }
            val track = Track(s.track, "G", InstrumentDefinition(), listOf(Staff(EntityId("${s.track}s"), bars = bars)))
            val audio = if (s.clip == null) AudioArrangement() else AudioArrangement(listOf(AssetDescriptor(s.clip, "ref.wav", "audio/wav", 100)), listOf(AudioClip(EntityId("${s.id}c"), s.clip, "ref")))
            return Project(id = s.id, revision = s.revision, measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), s.bpm)), audio = audio)
        }

        fun randomSpec(seed: Long, ids: Ids, clip: AssetHash? = null): ProjSpec {
            val r = Random(seed)
            val n = 1 + r.nextInt(6)
            var bars = List(n) { BarSpec(ids.next(), listOf(2, 3, 4)[r.nextInt(3)], r.nextInt(3) != 0) }
            if (r.nextBoolean()) { val a = r.nextInt(n); val b = a + r.nextInt(n - a); bars = bars.mapIndexed { i, x -> x.copy(repStart = i == a, repEnd = if (i == b) 2 else null) } }
            return ProjSpec(ids.next(), 0, bars, 300.0 + r.nextDouble() * 600.0, ids.next(), clip)
        }

        fun edit(s: ProjSpec, kind: Int, param: Int, ids: Ids): ProjSpec {
            val n = s.bars.size
            val bars = s.bars.toMutableList()
            var bpm = s.bpm
            when (kind % 6) {
                0 -> bpm = (bpm * (0.8 + (param % 46) / 100.0)).coerceIn(200.0, 1200.0)
                1 -> bars += BarSpec(ids.next(), 4, param % 2 == 0)
                2 -> if (n > 1) bars.removeAt(param % n)
                3 -> bars.add(param % (n + 1), BarSpec(ids.next(), 3, true))
                4 -> { val i = param % n; bars[i] = bars[i].copy(note = !bars[i].note) }
                5 -> { val a = param % n; val b = a + (param / 7) % (n - a); val clear = bars.any { it.repStart }
                    for (i in bars.indices) bars[i] = bars[i].copy(repStart = !clear && i == a, repEnd = if (!clear && i == b) 2 else null) }
            }
            // keep repeat marks well formed after deletions
            if (bars.none { it.repEnd != null }) for (i in bars.indices) bars[i] = bars[i].copy(repStart = false)
            return s.copy(revision = s.revision + 1, bars = bars, bpm = bpm)
        }

        fun planOf(p: Project): PlaybackPlan = (compiler.compileNow(p, 48_000) as Outcome.Success).value
        fun occ(plan: PlaybackPlan, i: Int) = plan.route[i].let { PlaybackOccurrence(ScorePosition(it.position.measureId), it.occurrence, i) }

        fun settingsFor(seed: Long, p: Project): PracticeSettings {
            val r = Random(seed)
            val speed = 0.25 + r.nextDouble() * 1.25
            val plan = planOf(p)
            val loop = if (r.nextBoolean()) { val a = r.nextInt(plan.route.size); val b = a + r.nextInt(plan.route.size - a); LoopRange(occ(plan, a), occ(plan, b)) } else null
            val trainer = SpeedTrainer(r.nextBoolean(), speed, 0.25 + r.nextDouble() * 1.25, 0.05 + r.nextDouble() * 0.25, 1 + r.nextInt(3))
            return PracticeSettings(speed = speed, countInBars = r.nextInt(3), metronome = r.nextBoolean(), loop = loop, trainer = trainer)
        }

        fun randomSteps(seed: Long, len: Int): List<Step> {
            val r = Random(seed)
            val steps = ArrayList<Step>()
            steps += Step(Op.Switch(r.nextLong(), r.nextLong()), if (r.nextBoolean()) 5L + r.nextInt(36) else null)
            while (steps.size < len) {
                val w = r.nextInt(100)
                val op = when {
                    w < 15 -> Op.Play
                    w < 25 -> Op.Pause
                    w < 33 -> Op.Stop
                    w < 43 -> Op.Seek(r.nextInt(1000), r.nextInt(6) == 0)
                    w < 51 -> Op.SetLoop(r.nextInt(1000), r.nextInt(1000), r.nextInt(5))
                    w < 71 -> Op.Render(1 + r.nextInt(40), 5L + r.nextInt(36))
                    w < 75 -> Op.Sleep(5L + r.nextInt(36))
                    w < 81 -> Op.PrepareSame(if (r.nextBoolean()) r.nextLong() else null)
                    w < 88 -> Op.Edit(r.nextInt(6), r.nextInt(1000), if (r.nextInt(3) == 0) r.nextLong() else null)
                    w < 92 -> Op.Switch(r.nextLong(), r.nextLong())
                    else -> Op.Race(r.nextLong(), r.nextLong(), r.nextBoolean(), if (r.nextBoolean()) r.nextInt(1000) else null)
                }
                steps += Step(op, if (r.nextInt(10) < 6) 5L + r.nextInt(36) else null)
            }
            return steps
        }
    }

    // ---------------------------------------------------------------- runner with reference model
    class Runner(val label: String) {
        val ids = Ids("${label}x")
        val host = RealtimeHost({ FakeSink(latency = 0, block = 1024) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> errors += e })
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var entered: CompletableDeferred<Unit>? = null
        val loader = ClipLoader { _, rate -> gate?.let { g -> entered?.complete(Unit); g.await() }; Outcome.Success(PcmBuffer(rate, 4800, ShortArray(9600))) }
        val t = EngineTransport(host, clipLoader = loader, scope = scope, pollMillis = 5)
        // reference model
        var spec: ProjSpec? = null
        var settings: PracticeSettings? = null
        var model = TransportStatus.STOPPED
        var stoppedNoSeek = false
        var lastOp: Op? = null
        var trainerRaised = false
        var racesRun = 0
        val tolerate = System.getProperty("qa13.tolerate") == "true"
        /** Operations between the last stop() and now (for classifying I2). */
        val sinceStop = HashSet<String>()
        /** Why the playhead may legitimately (or not) be outside the loop while playing; null = it must be inside. */
        var escape: String? = null
        fun noteEscape(why: String) {
            val lf = loopFrames ?: return
            val f = t.state.value.sample?.frame ?: return
            if (model == TransportStatus.PLAYING && f !in lf.first until lf.second) escape = (if (f < lf.first) "$why-before-loop" else "$why-after-loop")
        }
        val violations = ArrayList<Violation>()
        var step = 0

        @Suppress("UNCHECKED_CAST") private fun <T> tf(name: String): T = EngineTransport::class.java.getDeclaredField(name).apply { isAccessible = true }.get(t) as T
        val plan: PlaybackPlan? get() = tf("plan")
        val loopFrames: Pair<Long, Long>? get() = tf("loopFrames")
        @Suppress("UNCHECKED_CAST") val commands: ConcurrentLinkedQueue<HostCommand> get() =
            RealtimeHost::class.java.getDeclaredField("commands").apply { isAccessible = true }.get(host) as ConcurrentLinkedQueue<HostCommand>

        fun v(inv: String, msg: String) { violations += Violation(inv, step, msg) }

        private suspend fun prepare(s: ProjSpec, settingsSeed: Long?) {
            val p = build(s)
            val st = settingsSeed?.let { settingsFor(it, p) } ?: (settings ?: settingsFor(0, p))
            spec = s; settings = st
            sinceStop += "prepare"
            val r = t.prepare(p, st)
            if (r is Outcome.Failure) v("I4-outcome", "prepare failed: ${r.message}")
            noteEscape("prepare")
        }

        suspend fun run(op: Op) {
            lastOp = op
            when (op) {
                is Op.Switch -> prepare(randomSpec(op.seed, ids), op.settings)
                is Op.Edit -> spec?.let { prepare(edit(it, op.kind, op.param, ids), op.settings) }
                is Op.PrepareSame -> spec?.let { prepare(it, op.settings) }
                Op.Play -> {
                    val expectStart = stoppedNoSeek
                    val before = commands.size
                    val r = t.play()
                    if (spec == null) return
                    if (r is Outcome.Failure) { v("I4-outcome", "play failed: ${r.message}"); return }
                    model = TransportStatus.PLAYING; stoppedNoSeek = false; escape = null
                    val play = commands.toList().drop(before).filterIsInstance<HostCommand.Play>().lastOrNull()
                    if (play == null) { v("I6-play-dropped", "sequential play() posted no Play command"); return }
                    val p = plan!!
                    val lf = loopFrames
                    if (play.from !in 0 until p.totalFrames) v("I3-start", "Play.from=${play.from} outside route 0..${p.totalFrames}")
                    if (lf != null && play.from !in lf.first until lf.second) v("I3-start", "Play.from=${play.from} outside loop $lf")
                    if (expectStart) { val want = lf?.first ?: 0L; if (play.from != want) v("I2-start-after-stop" + (if ("setLoop" in sinceStop) "-loopchange" else if ("prepare" in sinceStop) "-prepare" else ""), "Play.from=${play.from} expected $want (loop=$lf, pos=${p.positionAt(play.from)?.let { "ri=${it.routeIndex} m=${it.position.measureId}" }})") }
                }
                Op.Pause -> { t.pause(); if (model == TransportStatus.PLAYING) model = TransportStatus.PAUSED else stopThenPause() }
                Op.Stop -> { t.stop(); model = TransportStatus.STOPPED; stoppedNoSeek = true; escape = null; sinceStop.clear() }
                is Op.Seek -> {
                    val p = plan
                    val target = if (op.stale || spec == null) PlaybackOccurrence(ScorePosition(EntityId("stale${op.param}")), 1, op.param % 7)
                        else (p ?: planOf(build(spec!!))).let { p -> occ(p, op.param % p.route.size).let { o -> val len = p.spans[o.routeIndex].length; o.copy(position = o.position.copy(offset = Fraction.of(((op.param / 3) % 4).toLong() * len.numerator, 4 * len.denominator))) } }
                    val r = t.seek(target)
                    if (r is Outcome.Success) { stoppedNoSeek = false; noteEscape("seek") }
                    else if (!op.stale && p != null && plan === p) v("I4-outcome", "valid seek failed: ${(r as Outcome.Failure).message}")
                }
                is Op.SetLoop -> {
                    val p = plan
                    val range: LoopRange? = when {
                        op.kind == 4 -> null
                        p == null -> null
                        op.kind == 3 -> LoopRange(PlaybackOccurrence(ScorePosition(EntityId("stale${op.a}")), 1, 0), PlaybackOccurrence(ScorePosition(EntityId("stale${op.b}")), 1, 0))
                        else -> { val a = op.a % p.route.size; val b = op.b % p.route.size
                            if (op.kind == 2 && a != b) LoopRange(occ(p, maxOf(a, b)), occ(p, minOf(a, b)).copy(position = ScorePosition(p.route[minOf(a, b)].position.measureId, Fraction.of(1, 1000))))
                            else LoopRange(occ(p, minOf(a, b)), occ(p, maxOf(a, b))) }
                    }
                    if (p == null && op.kind != 4) return
                    val r = t.setLoop(range)
                    val valid = op.kind == 4 || op.kind == 0 || op.kind == 1 || (op.kind == 2 && op.a % p!!.route.size == op.b % p.route.size)
                    if (valid && r is Outcome.Failure) v("I4-outcome", "valid setLoop failed: ${r.message}")
                    if (r is Outcome.Success) { settings = settings?.copy(loop = range); noteEscape("setLoop"); sinceStop += "setLoop" }
                }
                is Op.Render -> { repeat(op.k) { host.renderBlock() }; delay(op.sleep); observeEnd() }
                is Op.Sleep -> delay(op.ms)
                is Op.Race -> {
                    val s = randomSpec(op.seed, ids, freshHash())
                    val p = build(s)
                    val st = settingsFor(op.settings, p)
                    val warm = host.isRunning
                    racesRun++
                    val g = CompletableDeferred<Unit>(); val e = CompletableDeferred<Unit>()
                    entered = e; gate = g
                    val job = scope.async {
                        t.prepare(p, st)
                        op.seek?.let { t.seek(occ(planOf(p), it % planOf(p).route.size)) }
                        t.play()
                    }
                    try { withTimeout(3000) { e.await() } } catch (x: TimeoutCancellationException) { v("I4-race-setup", "clip loader never entered (warm=$warm)") }
                    if (op.stop) t.stop() else t.pause()
                    gate = null; g.complete(Unit)
                    withTimeout(5000) { job.await() }
                    spec = s; settings = st
                    if (op.stop) { model = TransportStatus.STOPPED; stoppedNoSeek = !(warm && op.seek != null); sinceStop.clear(); if (warm) sinceStop += "prepare"; escape = null }
                    else { if (model == TransportStatus.PLAYING) model = TransportStatus.PAUSED else stopThenPause(); if (op.seek != null) stoppedNoSeek = false; sinceStop += "prepare" }
                }
            }
        }

        /** pause() right after stop() (before the next audio block) turns STOPPED into PAUSED. */
        fun stopThenPause() {
            if (model == TransportStatus.STOPPED && t.state.value.status == TransportStatus.PAUSED) {
                if (tolerate) model = TransportStatus.PAUSED else v("I0-stop-then-pause", "pause() after stop() published PAUSED (engine snapshot still playing)")
            }
        }

        /** Natural end of playback is an engine-driven transition of the model. */
        suspend fun observeEnd() {
            val snap = host.snapshot()
            if (model == TransportStatus.PLAYING && snap.ended && !snap.playing && !snap.countingIn) {
                model = TransportStatus.STOPPED
                withTimeoutOrNull(300) { while (t.state.value.status == TransportStatus.PLAYING) delay(2) }
            }
        }

        fun cheapChecks() {
            val st = settings ?: return
            val cap = maxOf(st.speed, if (st.trainer.enabled) st.trainer.endSpeed else st.speed)
            val sp = t.currentSettings().speed
            if (st.trainer.enabled && sp > st.speed + 1e-9) trainerRaised = true
            if (sp > cap + 1e-9) v("I5-trainer", "effective speed $sp > cap $cap (requested ${st.speed}, trainer ${st.trainer})")
        }

        private fun fullCheckOnce(): List<String> {
            val out = ArrayList<String>()
            val s = t.state.value
            val snap = host.snapshot()
            val engineOn = snap.playing || snap.countingIn
            if (s.status != model) out += "I0-model|status=${s.status} model=$model after $lastOp"
            if (s.status == TransportStatus.PLAYING && !engineOn) out += "I1-engine|status PLAYING but engine idle (ended=${snap.ended})"
            if ((s.status == TransportStatus.PAUSED || s.status == TransportStatus.STOPPED) && engineOn) out += "I1-engine|status ${s.status} but engine playing=${snap.playing} countingIn=${snap.countingIn}"
            val p = plan
            if (p != null) {
                s.position?.let { pos ->
                    val span = p.spans.getOrNull(pos.routeIndex)
                    if (span == null || span.measureId != pos.position.measureId || span.occurrence != pos.occurrence || pos.position.offset.toDouble() < 0 || pos.position.offset.toDouble() > span.length.toDouble() + 1e-9)
                        out += (if (spec?.bars?.none { it.id == pos.position.measureId } != false) "I3-route-foreign" else "I3-route-mismatch") + "|published position ri=${pos.routeIndex} m=${pos.position.measureId} occ=${pos.occurrence} not on plan route (${p.route.size} entries, span=${span?.measureId})"
                }
                s.sample?.let { if (it.frame < 0 || it.frame > p.totalFrames) out += (if (it.frame <= p.totalFrames + 1024) "I3-overshoot" else "I3-route-frame") + "|published frame ${it.frame} outside 0..${p.totalFrames}" }
                val lf = loopFrames
                if (lf != null && s.status == TransportStatus.PLAYING && !s.countingIn && snap.playing) s.sample?.let {
                    if (it.frame in lf.second..lf.second + 1024 && escape == null) out += "I3-overshoot|published frame ${it.frame} past loop end $lf"
                    else if (it.frame !in lf.first until lf.second && !(tolerate && escape != null)) out += "I3-loop${escape?.let { "-$it" } ?: ""}|published frame ${it.frame} outside loop $lf"
                }
            }
            if (s.status == TransportStatus.PLAYING && snap.playing) settings?.let { st ->
                val cap = maxOf(st.speed, if (st.trainer.enabled) st.trainer.endSpeed else st.speed)
                if (s.speed > cap + 1e-9) out += "I5-trainer|published speed ${s.speed} > cap $cap"
            }
            return out
        }

        suspend fun fullCheck() {
            var found = fullCheckOnce()
            var tries = 0
            while (found.isNotEmpty() && tries++ < 20) { delay(10); found = fullCheckOnce() }
            for (f in found) { val (inv, msg) = f.split("|", limit = 2); if (!(tolerate && inv == "I3-overshoot")) v(inv, msg) }
            // campaign mode: resynchronise the model so later steps are still meaningful
            if (found.any { it.startsWith("I0") }) model = t.state.value.status
        }

        fun close() { t.shutdown(); scope.cancel() }
    }

    private val trainerSeqs = AtomicInteger(); private val raceSeqs = AtomicInteger()
    private val continueMode = System.getProperty("qa13.continue") == "true"
    /** Runs [steps]; returns the violations (first one per invariant) or empty. */
    fun execute(steps: List<Step>, label: String): List<Violation> {
        val r = Runner(label)
        try {
            runBlocking {
                for ((i, s) in steps.withIndex()) {
                    r.step = i
                    try {
                        withTimeout(10_000) {
                            r.run(s.op)
                            r.cheapChecks()
                            if (s.settle != null) { host(r).renderBlock(); delay(s.settle); r.observeEnd(); r.fullCheck() }
                            else if (s.op is Op.Render) r.fullCheck()
                        }
                    } catch (e: TimeoutCancellationException) { r.v("I4-timeout", "step $i ${s.op} timed out (deadlock?)"); break }
                    catch (e: Throwable) { r.v("I4-exception", "step $i ${s.op}: $e"); break }
                    r.errors.poll()?.let { r.v("I4-exception", "background: $it") }
                    if (r.violations.isNotEmpty() && !continueMode) break
                    if (r.violations.any { it.inv.startsWith("I4") }) break
                }
            }
        } finally { r.close(); if (r.trainerRaised) trainerSeqs.incrementAndGet(); if (r.racesRun > 0) raceSeqs.incrementAndGet() }
        return r.violations
    }
    private fun host(r: Runner) = r.host

    /** Each candidate is executed up to [tries] times (timing); it fails when any run shows [inv]. */
    fun fails(steps: List<Step>, inv: String, tries: Int = 3): Boolean = (0 until tries).any { k -> execute(steps, "s$k").any { it.inv == inv } }

    fun shrink(steps: List<Step>, inv: String): List<Step> {
        var cur = steps
        // truncate after the failing step
        var chunk = cur.size / 2
        while (chunk >= 1) {
            var i = 1   // keep the initial Switch-prepare; it can still be removed in the final pass
            var changed = false
            while (i < cur.size) {
                val cand = cur.take(i) + cur.drop(i + chunk)
                if (cand.isNotEmpty() && fails(cand, inv)) { cur = cand; changed = true } else i += chunk
            }
            if (!changed) chunk /= 2
        }
        // simplify settles
        for (i in cur.indices) if (cur[i].settle != null) { val cand = cur.toMutableList().also { it[i] = it[i].copy(settle = null) }; if (fails(cand, inv)) cur = cand }
        return cur
    }

    @Test fun randomisedTransportModel() {
        val n = System.getProperty("qa13.n")?.toInt() ?: 600
        val len = System.getProperty("qa13.len")?.toInt() ?: 40
        val base = System.getProperty("qa13.seed")?.toLong() ?: 13_000L
        val pool = Executors.newFixedThreadPool(4)
        val results = ConcurrentLinkedQueue<Pair<Long, List<Violation>>>()
        val ran = AtomicInteger()
        for (k in 0 until n) {
            val seed = base + k
            pool.submit { try { val v = execute(randomSteps(seed, len), "r$k-"); ran.incrementAndGet(); if (v.isNotEmpty()) { results += seed to v; println("FAIL seed=$seed ${v.distinctBy { it.inv }.joinToString(" ; ")}") } } catch (t: Throwable) { println("RUNNER CRASH seed=$seed $t"); results += seed to listOf(Violation("I4-crash", -1, t.toString())) } }
        }
        pool.shutdown(); pool.awaitTermination(3, TimeUnit.HOURS)
        val seedsByInv = results.flatMap { (seed, vs) -> vs.map { it.inv }.distinct().map { it to seed } }.groupBy({ it.first }, { it.second })
        println("QA13 sequences=${ran.get()} len=$len failures=${results.size} trainerRaisedIn=${trainerSeqs.get()} withRace=${raceSeqs.get()}")
        for ((inv, seeds) in seedsByInv) println("QA13 invariant $inv: ${seeds.size} sequences, seeds=${seeds.sorted().take(10)}")
        if (System.getProperty("qa13.shrink") != "false") for ((inv, seeds) in seedsByInv) {
            val seed = seeds.min()
            val min = shrink(randomSteps(seed, len), inv)
            println("QA13 MINIMAL $inv (seed=$seed, ${min.size} steps):")
            min.forEachIndexed { i, s -> println("   $i: ${s.op} settle=${s.settle}") }
            println("   -> ${execute(min, "m").firstOrNull()}")
        }
        assertTrue("violations: ${seedsByInv.keys} (seeds ${results.map { it.first }.sorted().take(20)})", results.isEmpty())
    }
}
