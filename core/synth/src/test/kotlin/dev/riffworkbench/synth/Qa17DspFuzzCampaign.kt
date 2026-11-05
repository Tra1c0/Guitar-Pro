package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import java.lang.management.ManagementFactory
import kotlin.math.*
import kotlin.random.Random
import org.junit.Test

/**
 * QA cycle 17: property-based fuzzing of effects and the engine (invariants D1–D8). Short by default;
 * campaign: -Dqa17.fx=N -Dqa17.eng=M -Dqa17.seed=S. Prints violations and statistics, never fails by itself.
 */
class Qa17DspFuzzCampaign {
    private val rates = intArrayOf(22_050, 44_100, 48_000, 96_000)
    private val blocks = intArrayOf(1, 7, 64, 256, 1024, 4096)

    companion object {
        val ranges: Map<EffectKind, List<Triple<String, Double, Double>>> = mapOf(
            EffectKind.GATE to listOf(Triple("thresholdDb", -96.0, 0.0), Triple("releaseMs", 5.0, 1000.0)),
            EffectKind.OVERDRIVE to listOf(Triple("drive", 0.0, 1.0), Triple("tone", 0.0, 1.0), Triple("level", -24.0, 12.0)),
            EffectKind.AMP to listOf(Triple("gain", 0.0, 1.0), Triple("bass", -12.0, 12.0), Triple("mid", -12.0, 12.0), Triple("treble", -12.0, 12.0), Triple("presence", -12.0, 12.0), Triple("level", -24.0, 12.0)),
            EffectKind.CABINET_IR to listOf(Triple("model", 0.0, 4.0)),
            EffectKind.EQ to listOf(Triple("low", -18.0, 18.0), Triple("mid", -18.0, 18.0), Triple("high", -18.0, 18.0), Triple("midHz", 150.0, 6000.0)),
            EffectKind.COMPRESSOR to listOf(Triple("thresholdDb", -60.0, 0.0), Triple("ratio", 1.0, 20.0), Triple("attackMs", 0.1, 200.0), Triple("releaseMs", 10.0, 2000.0), Triple("makeupDb", 0.0, 24.0)),
            EffectKind.DELAY to listOf(Triple("timeMs", 10.0, 2000.0), Triple("feedback", 0.0, 0.95), Triple("mix", 0.0, 1.0)),
            EffectKind.REVERB to listOf(Triple("size", 0.0, 1.0), Triple("damping", 0.0, 1.0), Triple("mix", 0.0, 1.0)),
            EffectKind.CHORUS to listOf(Triple("rateHz", 0.05, 8.0), Triple("depthMs", 0.1, 12.0), Triple("mix", 0.0, 1.0)),
            EffectKind.FILTER to listOf(Triple("type", 0.0, 2.0), Triple("cutoffHz", 20.0, 20000.0), Triple("q", 0.1, 12.0)),
        )
        private var uid = 0
        fun id() = EntityId("qa17-${uid++}")
        fun param(r: Random, lo: Double, hi: Double): Double = when (r.nextInt(8)) {
            0 -> lo; 1 -> hi; 2 -> if (0.0 in lo..hi) 0.0 else lo; 3 -> (lo + hi) / 2
            4 -> if (r.nextInt(10) == 0) listOf(Double.NaN, 1e9, -1e9, Double.POSITIVE_INFINITY)[r.nextInt(4)] else lo + (hi - lo) * r.nextDouble()
            else -> lo + (hi - lo) * r.nextDouble()
        }
        fun unit(r: Random, kind: EffectKind = EffectKind.values()[r.nextInt(EffectKind.values().size)]): EffectUnit {
            val ps = ranges[kind]!!.filter { r.nextInt(6) != 0 }.associate { (n, lo, hi) -> n to param(r, lo, hi) }
            return EffectUnit(id(), kind, enabled = r.nextInt(12) != 0, parameters = ps)
        }
        fun describe(u: List<EffectUnit>) = u.joinToString(" -> ") { "${it.kind}${if (it.enabled) "" else "(off)"}${it.parameters.entries.joinToString(",", "{", "}") { e -> "${e.key}=${e.value}" }}" }
    }

    // ---------------- signals ----------------
    enum class Sig { SILENCE, DC, SQUARE, IMPULSE, NOISE, SINE, BURST }
    private fun signal(kind: Sig, amp: Float, hz: Double, sr: Int, frames: Int, seed: Int, ch: Int): FloatArray {
        val n = Noise(seed + 77 * ch)
        return FloatArray(frames) { i ->
            when (kind) {
                Sig.SILENCE -> 0f
                Sig.DC -> amp * (if (ch == 0) 1f else -1f)
                Sig.SQUARE -> if (((i * hz / sr) % 1.0) < 0.5) amp else -amp
                Sig.IMPULSE -> if (i % (sr / 4) == 0) amp else 0f
                Sig.NOISE -> amp * n.next()
                Sig.SINE -> (amp * sin(2 * PI * hz * i / sr + ch)).toFloat()
                Sig.BURST -> if (i < frames / 8) amp * n.next() else 0f
            }
        }
    }

    private fun build(units: List<EffectUnit>, sr: Int, maxBlock: Int): Array<Effect> = units.mapNotNull { Effects.create(it, sr, maxBlock) }.toTypedArray()

    private fun run(chain: Array<Effect>, l: FloatArray, r: FloatArray?, block: Int, from: Int = 0, to: Int = l.size) {
        val bl = FloatArray(block); val br = FloatArray(block)
        var i = from
        while (i < to) {
            val k = minOf(block, to - i)
            System.arraycopy(l, i, bl, 0, k); if (r != null) System.arraycopy(r, i, br, 0, k)
            for (fx in chain) fx.process(bl, if (r != null) br else null, k)
            System.arraycopy(bl, 0, l, i, k); if (r != null) System.arraycopy(br, 0, r, i, k)
            i += k
        }
    }

    /** Seconds until a chain's tail is 120 dB down (rough, upper bound), capped. */
    private fun tailSeconds(units: List<EffectUnit>): Double {
        var t = 0.3
        for (u in units) if (u.enabled) when (u.kind) {
            EffectKind.DELAY -> { val fb = u.p("feedback", 0.3, 0.0..0.95); val time = u.p("timeMs", 380.0, 10.0..2000.0) / 1000; t += time * (1 + if (fb > 1e-3) ln(1e-6) / ln(fb) else 0.0) }
            EffectKind.REVERB -> { val fb = 0.7 + u.p("size", 0.5, 0.0..1.0) * 0.28; t += 0.0372 * ln(1e-6) / ln(fb) + 0.1 }
            EffectKind.GATE, EffectKind.COMPRESSOR -> t += u.p("releaseMs", 100.0, 5.0..2000.0) / 1000 * 14
            else -> t += 0.05
        }
        return t
    }

    data class Finding(val inv: String, val detail: String, val cfg: String)
    private val findings = mutableListOf<Finding>()
    private val counts = sortedMapOf<String, Int>()
    private val outFile = System.getenv("QA17_OUT")?.let { java.io.File(it) }
    private fun log(x: String) { outFile?.appendText(x + "\n") }
    private fun bad(inv: String, detail: String, cfg: String) { counts[inv] = (counts[inv] ?: 0) + 1; if (findings.count { it.inv == inv } < 25) { findings += Finding(inv, detail, cfg); log("RAW $inv: $detail | ${cfg.take(600)}") } }

    private fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Float { var p = 0f; for (i in from until to) { val a = abs(x[i]); if (a > p || a.isNaN()) p = if (a.isNaN()) Float.NaN else a; if (p.isNaN()) return p }; return p }

    data class FxCfg(val seed: Int, val sr: Int, val bA: Int, val bB: Int, val stereo: Boolean, val units: List<EffectUnit>, val sig: Sig, val amp: Float, val hz: Double) {
        override fun toString() = "seed=$seed sr=$sr blocks=$bA/$bB stereo=$stereo sig=$sig amp=$amp hz=$hz chain=${describe(units)}"
    }

    private fun randomFx(seed: Int): FxCfg {
        val r = Random(seed)
        val sr = rates[r.nextInt(rates.size)]
        return FxCfg(seed, sr, blocks[r.nextInt(blocks.size)], blocks[r.nextInt(blocks.size)], r.nextBoolean(), List(r.nextInt(11)) { unit(r) },
            Sig.values()[r.nextInt(Sig.values().size)], listOf(1f, 0.5f, 1e-3f, 1e-30f, r.nextFloat())[r.nextInt(5)], listOf(20.0, 110.0, 1000.0, 7000.0, sr * 0.49)[r.nextInt(5)])
    }

    /** All violated invariants of one effect configuration, with details. */
    private fun checkFx(c: FxCfg, only: String? = null): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val sr = c.sr; val stereo = c.stereo; val units = c.units
        val inLen = (0.25 * sr).toInt()
        val shortLen = inLen + sr
        val l1 = FloatArray(shortLen).also { signal(c.sig, c.amp, c.hz, sr, inLen, c.seed, 0).copyInto(it) }
        val r1 = if (stereo) FloatArray(shortLen).also { signal(c.sig, c.amp, c.hz, sr, inLen, c.seed, 1).copyInto(it) } else null
        val l2 = l1.copyOf(); val r2 = r1?.copyOf()
        val a = build(units, sr, 4096); val b = build(units, sr, 4096)
        run(a, l1, r1, c.bA)
        val pk = maxOf(peak(l1), r1?.let { peak(it) } ?: 0f)
        if (!pk.isFinite()) { out["D1"] = "non-finite output (peak=$pk)"; return out }
        if (pk > 100f) out["D2"] = "peak ${"%.1f".format(20 * log10(pk))} dBFS without limiter"
        if (only == null || only == "D5") {
            run(b, l2, r2, c.bB)
            var d = 0f; var at = -1
            for (i in l1.indices) { val x = abs(l1[i] - l2[i]); if (x > d) { d = x; at = i } }
            if (r1 != null) for (i in r1.indices) d = maxOf(d, abs(r1[i] - r2!![i]))
            if (d > 1e-4f * maxOf(1f, pk)) out["D5"] = "max diff $d at frame $at (peak $pk) between block ${c.bA} and ${c.bB}"
        }
        if (only == null || only == "D4" || only == "D6") {
            val est = tailSeconds(units)
            val tail = if (only == "D6") 0.2 else minOf(est, if (only != null) 12.0 else 30.0)
            val tl = (tail * sr).toInt() + sr / 20
            val ql = FloatArray(tl); val qr = if (stereo) FloatArray(tl) else null
            run(a, ql, qr, c.bA)
            val win = sr / 20
            val last = maxOf(peak(ql, tl - win), qr?.let { peak(it, tl - win) } ?: 0f)
            if (!last.isFinite()) out["D1"] = "non-finite tail"
            else if (c.sig == Sig.SILENCE && last > 0f) out["D4"] = "silence in, ${"%.2e".format(last)} out"
            else if (only == "D6") Unit
            else if (est <= tail && last > 1e-5f * maxOf(1f, pk)) out["D4"] = "tail after ${"%.1f".format(tail)} s still ${"%.2e".format(last)} (signal peak $pk)"
            else if (est > tail) {
                // Long delay feedback: the envelope must keep falling (window = longest delay + 50 ms, compared 4 windows apart).
                val w = ((units.filter { it.enabled && it.kind == EffectKind.DELAY }.maxOfOrNull { it.p("timeMs", 380.0, 10.0..2000.0) } ?: 100.0) / 1000 * sr).toInt() + win
                val e1 = maxOf(peak(ql, tl - w), qr?.let { peak(it, tl - w) } ?: 0f)
                val e0 = maxOf(peak(ql, tl - 5 * w, tl - 4 * w), qr?.let { peak(it, tl - 5 * w, tl - 4 * w) } ?: 0f)
                if (e1 > e0 && e1 > 1e-5f) out["D4"] = "tail not decaying: ${"%.3e".format(e0)} -> ${"%.3e".format(e1)} over 4 windows of $w frames"
            }
            if (only == null || only == "D6") {
                for (fx in a) fx.reset()
                val fresh = build(units, sr, 4096)
                val pl = signal(Sig.NOISE, 0.5f, 0.0, sr, 4096, c.seed + 1, 0); val pr = if (stereo) signal(Sig.NOISE, 0.5f, 0.0, sr, 4096, c.seed + 1, 1) else null
                val al = pl.copyOf(); val ar = pr?.copyOf(); val cl = pl.copyOf(); val cr = pr?.copyOf()
                run(a, al, ar, 256); run(fresh, cl, cr, 256)
                var dr = 0f
                for (i in al.indices) dr = maxOf(dr, abs(al[i] - cl[i]))
                if (ar != null) for (i in ar.indices) dr = maxOf(dr, abs(ar[i] - cr!![i]))
                if (dr > 1e-6f * maxOf(1f, peak(cl))) out["D6"] = "reset differs from fresh by $dr"
            }
        }
        return out
    }

    /** Greedy shrink: drop units, drop parameters, simplify the signal and the blocks while [inv] still fails. */
    private fun shrink(c0: FxCfg, inv: String): FxCfg {
        var c = c0
        val seen = hashSetOf(c0)
        fun fails(x: FxCfg) = runCatching { inv in checkFx(x, inv) }.getOrDefault(false)
        for (t in listOf(c.copy(sr = 22_050), c.copy(stereo = false), c.copy(sig = Sig.NOISE))) if (t != c && fails(t)) c = t
        var changed = true
        while (changed) {
            changed = false
            for (i in c.units.indices.reversed()) { val t = c.copy(units = c.units.filterIndexed { k, _ -> k != i }); if (fails(t)) { c = t; changed = true; break } }
            if (changed) continue
            for ((i, u) in c.units.withIndex()) {
                if (!u.enabled) { val t = c.copy(units = c.units.filterIndexed { k, _ -> k != i }); if (fails(t)) { c = t; changed = true; break } }
                for (key in u.parameters.keys) { val t = c.copy(units = c.units.mapIndexed { k, v -> if (k == i) v.copy(parameters = v.parameters - key) else v }); if (fails(t)) { c = t; changed = true; break } }
                if (changed) break
            }
            if (changed) continue
            for (t in listOf(c.copy(stereo = false), c.copy(amp = 0.5f), c.copy(bA = 256)))
                if (t != c && t !in seen && fails(t)) { seen += t; c = t; changed = true; break }
        }
        return c
    }

    private val shrunk = linkedMapOf<String, MutableSet<String>>()
    private val shrinkTries = hashMapOf<String, Int>()
    private fun fxCase(seed: Int) {
        val c = randomFx(seed)
        counts["fx-cases"] = (counts["fx-cases"] ?: 0) + 1
        counts["fx-units"] = (counts["fx-units"] ?: 0) + c.units.size
        val res = checkFx(c)
        for ((inv0, detail) in res) {
            val on = c.units.filter { it.enabled }.map { it.kind }
            val inv = when {
                inv0 == "D6" && EffectKind.CHORUS in on -> "D6/chorus"
                inv0 == "D5" && EffectKind.COMPRESSOR in on -> "D5/compressor"
                else -> inv0
            }
            counts["cases-failing-$inv0"] = (counts["cases-failing-$inv0"] ?: 0) + 1
            if (inv != inv0) { counts[inv] = (counts[inv] ?: 0) + 1; continue }
            bad(inv, detail, c.toString())
            if (inv != "D2" && (shrunk[inv]?.size ?: 0) < 6 && (shrinkTries[inv] ?: 0) < (System.getenv("QA17_SHRINK")?.toInt() ?: 4)) {
                shrinkTries[inv] = (shrinkTries[inv] ?: 0) + 1
                val m = shrink(c, inv)
                val sig = "$inv " + m.units.joinToString(",") { it.kind.name }
                val set = shrunk.getOrPut(inv) { linkedSetOf() }
                if (set.none { it.startsWith(sig + " |") }) { val line = "$sig | ${checkFx(m, inv)[inv]} | $m"; set += line; log("MIN $line") }
            }
        }
    }

    // ---------------- D3 timing / D7 allocation over effect chains ----------------
    private fun timing(seed: Int, report: MutableList<String>) {
        val r = Random(seed)
        val units = List(1 + r.nextInt(10)) { unit(r) }.map { it.copy(enabled = true) }
        val sr = 48_000; val n = 256
        val chain = build(units, sr, 1024)
        val bl = FloatArray(n); val br = FloatArray(n)
        val noise = Noise(seed)
        val tSig = LongArray(400); val tSil = LongArray(1200)
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        for (b in 0 until 400) { for (i in 0 until n) { bl[i] = noise.next(); br[i] = noise.next() }; val t0 = System.nanoTime(); for (fx in chain) fx.process(bl, br, n); tSig[b] = System.nanoTime() - t0 }
        val alloc0 = mx.getThreadAllocatedBytes(tid)
        for (b in 0 until 1200) { bl.fill(0f); br.fill(0f); val t0 = System.nanoTime(); for (fx in chain) fx.process(bl, br, n); tSil[b] = System.nanoTime() - t0 }
        val alloc = mx.getThreadAllocatedBytes(tid) - alloc0
        tSig.sort(); tSil.sort()
        val medSig = tSig[200] / 1e3; val medSil = tSil[600] / 1e3; val p99 = maxOf(tSig[396], tSil[1188]) / 1e3
        if (medSig > 1000 || medSil > 1000) bad("D3", "median ${"%.0f".format(medSig)} / silence ${"%.0f".format(medSil)} µs per 256-frame block", "seed=$seed chain=${describe(units)}")
        if (medSil > 3 * medSig && medSil > 50) bad("D3", "silence tail slower: ${"%.0f".format(medSil)} vs ${"%.0f".format(medSig)} µs (denormals?)", "seed=$seed chain=${describe(units)}")
        if (alloc > 0) bad("D7", "$alloc bytes allocated in 1200 blocks", "seed=$seed chain=${describe(units)}")
        report += "%.1f %.1f %.1f %d".format(medSig, medSil, p99, units.size)
    }

    // ---------------- D8: voice stealing clicks ----------------
    /** Step (sample-to-sample jump) in the difference signal caused by one stolen voice; 0.01 (-40 dBFS) is the limit X. */
    private val stealSteps = mutableListOf<String>()
    private fun stealCase(seed: Int) {
        val r = Random(seed)
        val sr = rates[r.nextInt(rates.size)]
        val kind = r.nextInt(4) // 0 string, 1 sampled, 2 keys, 3 drums
        val preset = listOf("rw.guitar.acoustic", "rw.guitar.clean", "rw.keys.pad", "rw.drums.rock")[kind]
        val inst = when (kind) { 3 -> InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0); 2 -> InstrumentDefinition(InstrumentKind.PIANO, emptyList(), emptyList()); else -> InstrumentDefinition() }
        val neutral = listOf(EffectUnit(id(), EffectKind.EQ))
        val tracks = listOf(0.8, 1e-3).mapIndexed { i, v -> Track(id(), "T$i", inst, sound = SoundAssignment(presetId = preset), mix = ChannelMix(volume = v, effects = neutral)) }
        val p = Project(id = id(), tracks = tracks)
        val pool = when (kind) { 0 -> 72; 1 -> 96; else -> 48 }
        val fill = pool + r.nextInt(0, 3) - 0
        val t0 = (0.3 * sr).toLong() + r.nextInt(sr / 10)
        val base = List(fill) { k ->
            val midi = when (kind) { 3 -> listOf(49, 57, 52, 55, 51)[r.nextInt(5)]; 2 -> 36 + r.nextInt(60); else -> 40 + r.nextInt(40) }
            val f = (k * (0.2 * sr / fill)).toLong()
            ScheduledNote(id(), id(), f, f + 8L * sr, Pitch(midi), 60 + r.nextInt(68), null, emptyList(), trackIndex = 0)
        }
        val extra = ScheduledNote(id(), id(), t0, t0 + sr / 2, Pitch(60), 100, null, emptyList(), trackIndex = 1)
        fun render(notes: List<ScheduledNote>): FloatArray {
            val e = SynthEngine(sr, 1024, if (kind == 1) bank else null)
            e.installRack(ChannelRack.build(p, sr, 1024, includeMasterFx = false)); e.setMix(MixState.from(p))
            e.install(EngineProgram.prepare(planOf(sr, notes.sortedBy { it.frame }))); e.play(0)
            val n = (t0 + sr / 10).toInt(); val out = FloatArray(n); val buf = FloatArray(2048); var i = 0
            while (i < n) { val k = minOf(256, n - i); e.render(buf, k); for (j in 0 until k) out[i + j] = buf[2 * j]; i += k }
            return out
        }
        val a = render(base); val b = render(base + extra)
        var step = 0f; var removed = 0f
        for (i in (t0 - 2).toInt() until a.size) {
            val d0 = b[i - 1] - a[i - 1]; val d1 = b[i] - a[i]
            step = maxOf(step, abs(d1 - d0)); removed = maxOf(removed, abs(d1))
        }
        val cfg = "seed=$seed sr=$sr kind=${listOf("string", "sampled", "keys", "drums")[kind]} pool=$pool notes=$fill steal at $t0"
        stealSteps += "%s %.4f".format(listOf("string", "sampled", "keys", "drums")[kind], step)
        if (step > 0.01f) bad("D8", "voice steal step ${"%.4f".format(step)} (${"%.1f".format(20 * log10(step))} dBFS), removed ${"%.4f".format(removed)}", cfg)
    }

    // ---------------- engine ----------------
    /** A plan as the compiler builds it: one route span covering all notes (totalFrames is O(1) then). */
    internal fun planOf(sr: Int, notes: List<ScheduledNote>): PlaybackPlan {
        val end = (notes.maxOfOrNull { it.endFrame } ?: 0L).coerceAtLeast(1).toDouble()
        return PlaybackPlan(0, sr, emptyList(), notes, spans = listOf(RouteSpan(0, EntityId("m"), 0, 0, Fraction.ZERO, doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, end))))
    }
    private val presets = listOf("rw.guitar.highgain", "rw.guitar.crunch", "rw.guitar.clean", "rw.guitar.acoustic", "rw.bass.pick", "rw.bass.finger", "rw.drums.rock", "rw.keys.piano", "rw.keys.epiano", "rw.keys.organ", "rw.keys.pad", "rw.keys.lead")
    private val bank by lazy { SampleBank.synthetic() }

    private fun randomProject(r: Random): Project {
        val tracks = List(1 + r.nextInt(8)) {
            val preset = presets[r.nextInt(presets.size)]
            val inst = when {
                preset.startsWith("rw.drums") -> InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0)
                preset.startsWith("rw.keys") -> InstrumentDefinition(InstrumentKind.PIANO, emptyList(), emptyList())
                else -> InstrumentDefinition()
            }
            val chain = if (r.nextBoolean()) emptyList() else List(r.nextInt(11)) { unit(r) }
            val vol = listOf(0.0, 1.0, 0.8, r.nextDouble(), 1e-4)[r.nextInt(5)]
            val pan = listOf(-1.0, 1.0, 0.0, r.nextDouble() * 2 - 1)[r.nextInt(4)]
            Track(id(), "T$it", inst, sound = SoundAssignment(presetId = preset), mix = ChannelMix(vol, pan, mute = r.nextInt(5) == 0, solo = r.nextInt(6) == 0, effects = chain))
        }
        val master = ChannelMix(volume = listOf(0.0, 1.0, 0.8, r.nextDouble())[r.nextInt(4)], effects = List(if (r.nextInt(3) == 0) r.nextInt(4) else 0) { unit(r) })
        return Project(id = id(), tracks = tracks, mix = MixGraph(master = master))
    }

    private fun randomNotes(r: Random, p: Project, sr: Int): List<ScheduledNote> {
        val count = listOf(0, 1, 10, 100, 400)[r.nextInt(5)]
        val span = (r.nextDouble() * 4 * sr).toLong() + 1
        val chordy = r.nextInt(3) == 0
        var at = 0L
        return List(count) {
            if (!chordy || r.nextInt(6) == 0) at = (r.nextDouble() * span).toLong()
            val dur = listOf(1L, 16L, (0.05 * sr).toLong(), (r.nextDouble() * 2 * sr).toLong() + 1, 8L * sr)[r.nextInt(5)]
            val midi = listOf(0, 127, 24, 100, r.nextInt(128))[r.nextInt(5)]
            val cents = if (r.nextInt(4) == 0) r.nextInt(-99, 100) else 0
            val curve = if (r.nextInt(3) == 0) List(1 + r.nextInt(5)) { k -> CurvePoint(k * dur / 5, listOf(0.0, 200.0, -2400.0, 2400.0, r.nextDouble() * 400)[r.nextInt(5)]) } else emptyList()
            val vib = if (r.nextInt(4) == 0) VibratoSpec(listOf(10.0, 50.0, 200.0)[r.nextInt(3)], listOf(0.5, 5.0, 12.0)[r.nextInt(3)], r.nextLong(0, maxOf(1, dur))) else null
            ScheduledNote(id(), id(), at, at + dur, Pitch(midi, cents), 1 + r.nextInt(127), if (r.nextInt(4) == 0) null else r.nextInt(8), emptyList(),
                trackIndex = r.nextInt(p.tracks.size), soundingPitch = (midi + cents / 100.0).coerceIn(-24.0, 151.0), curve = curve, vibrato = vib,
                attack = AttackKind.values()[r.nextInt(AttackKind.values().size)], palmMute = r.nextInt(6) == 0, dead = r.nextInt(10) == 0,
                harmonic = if (r.nextInt(6) == 0) HarmonicKind.values()[r.nextInt(HarmonicKind.values().size)] else null,
                drumStroke = DrumStroke.values()[r.nextInt(DrumStroke.values().size)], chokeGroup = if (r.nextInt(5) == 0) 1 else null)
        }.sortedBy { it.frame }
    }

    private fun engineCase(seed: Int) {
        val r = Random(seed)
        val sr = rates[r.nextInt(rates.size)]
        val block = blocks[r.nextInt(blocks.size)]
        val p = randomProject(r)
        val notes = randomNotes(r, p, sr)
        val masterFx = r.nextInt(4) != 0
        val withBank = r.nextBoolean()
        val cfg = "seed=$seed sr=$sr block=$block masterFx=$masterFx bank=$withBank notes=${notes.size} tracks=${p.tracks.map { "${it.sound.presetId}/v${it.mix.volume}/p${it.mix.pan}/${if (it.mix.mute) "M" else ""}${if (it.mix.solo) "S" else ""}/${describe(it.mix.effects)}" }} master=${p.mix.master.volume}/${describe(p.mix.master.effects)}"
        val e = SynthEngine(sr, maxOf(1024, block), if (withBank) bank else null)
        e.installRack(ChannelRack.build(p, sr, maxOf(1024, block), includeMasterFx = masterFx))
        e.setMix(MixState.from(p))
        val plan = planOf(sr, notes)
        e.install(EngineProgram.prepare(plan)); e.play(0)
        val chainTail = p.tracks.maxOfOrNull { tailSeconds(TonePresets.effectiveChain(it)) } ?: 0.0
        val tail = minOf(40.0, chainTail + (if (masterFx) tailSeconds(p.mix.master.effects) else 0.0) + 4.0)
        val total = plan.totalFrames + (tail * sr).toLong()
        val buf = FloatArray(2 * maxOf(1024, block))
        var done = 0L; var pk = 0f; var lastPk = 0f; var maxVoices = 0
        val pool = 72 + (if (withBank) 96 else 0) + 48 + 48
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        e.render(buf, 1); done = 1
        val alloc0 = mx.getThreadAllocatedBytes(tid)
        while (done < total) {
            val k = minOf(block.toLong(), total - done).toInt()
            e.render(buf, k)
            val bp = peak(buf, 0, 2 * k)
            if (!bp.isFinite()) { bad("D1", "non-finite engine output at frame $done", cfg); return }
            pk = maxOf(pk, bp)
            if (done > total - sr / 20) lastPk = maxOf(lastPk, bp)
            maxVoices = maxOf(maxVoices, e.voicesInUse)
            done += k
        }
        val alloc = mx.getThreadAllocatedBytes(tid) - alloc0
        if (alloc > 0) bad("D7", "$alloc bytes allocated while rendering $total frames", cfg)
        if (masterFx && pk > 0.94f) bad("D2", "peak $pk above the 0.94 limiter ceiling", cfg)
        if (!masterFx && pk > 100f) bad("D2", "peak ${20 * log10(pk)} dBFS without limiter", cfg)
        if (maxVoices > pool) bad("D8", "voices $maxVoices > pool $pool", cfg)
        if (lastPk > 1e-5f * maxOf(1f, pk)) bad("D4", "engine tail after ${"%.1f".format(tail)} s still $lastPk (peak $pk), voices ${e.voicesInUse}", cfg)
        if (e.voicesInUse > 0) bad("D4", "${e.voicesInUse} voices still active ${"%.1f".format(tail)} s after the last note end", cfg)
    }

    /** D5 at engine level: the same project rendered with two block sizes. */
    internal fun engineRender(p: Project, notes: List<ScheduledNote>, sr: Int, block: Int, frames: Int, masterFx: Boolean, withBank: Boolean): FloatArray {
        val e = SynthEngine(sr, maxOf(1024, block), if (withBank) bank else null)
        e.installRack(ChannelRack.build(p, sr, maxOf(1024, block), includeMasterFx = masterFx)); e.setMix(MixState.from(p))
        e.install(EngineProgram.prepare(planOf(sr, notes))); e.play(0)
        val out = FloatArray(2 * frames); val buf = FloatArray(2 * maxOf(1024, block)); var i = 0
        while (i < frames) { val k = minOf(block, frames - i); e.render(buf, k); System.arraycopy(buf, 0, out, 2 * i, 2 * k); i += k }
        return out
    }
    private fun engineBlockCase(seed: Int) {
        val r = Random(seed)
        val sr = rates[r.nextInt(rates.size)]
        val block = blocks[r.nextInt(blocks.size)]
        val p = randomProject(r)
        val notes = randomNotes(r, p, sr)
        val masterFx = r.nextBoolean(); val withBank = r.nextBoolean()
        val frames = minOf(3 * sr, (notes.maxOfOrNull { it.endFrame } ?: 0L).toInt() + sr / 2)
        val a = engineRender(p, notes, sr, block, frames, masterFx, withBank)
        val b = engineRender(p, notes, sr, 256, frames, masterFx, withBank)
        var d = 0f; var at = -1; var pk = 0f
        for (i in a.indices) { val x = abs(a[i] - b[i]); if (x > d) { d = x; at = i / 2 }; pk = maxOf(pk, abs(b[i])) }
        if (d > 1e-4f * maxOf(1f, pk)) bad("D5e", "engine block $block vs 256: max diff $d at frame $at (peak $pk)", "seed=$seed sr=$sr masterFx=$masterFx bank=$withBank notes=${notes.size} tracks=${p.tracks.map { "${it.sound.presetId}:${it.mix.effects.map { u -> u.kind }}" }} master=${p.mix.master.effects.map { it.kind }}")
    }

    // ---------------- PcmBuilder: chunking independence, frame count, finite/bounded ----------------
    private fun pcmCase(seed: Int) {
        val r = Random(seed)
        val rate = listOf(8_000, 11_025, 22_050, 44_100, 48_000, 88_200, 96_000, 192_000, 8_000 + r.nextInt(184_001))[r.nextInt(9)]
        val target = rates[r.nextInt(rates.size)]
        val ch = 1 + r.nextInt(8)
        val frames = listOf(0, 1, 2, 3, 100, r.nextInt(20_000))[r.nextInt(6)]
        val kind = r.nextInt(4)
        val data = ShortArray(frames * ch) { i -> when (kind) { 0 -> 0; 1 -> if ((i / ch / 7) % 2 == 0) 32767 else -32768; 2 -> r.nextInt(-32768, 32768); else -> (if (i % ch == 0) 32767 else -32768) }.toShort() }
        val one = PcmBuilder(target, Int.MAX_VALUE / 2).also { it.append(rate, ch, data, frames) }.build()
        val b = PcmBuilder(target, Int.MAX_VALUE / 2)
        var f = 0
        while (f < frames) { val k = minOf(frames - f, listOf(1, 7, 64, 4096, r.nextInt(1, 3000))[r.nextInt(5)]); b.append(rate, ch, data.copyOfRange(f * ch, (f + k) * ch), k); f += k }
        val two = b.build()
        val cfg = "seed=$seed rate=$rate target=$target ch=$ch frames=$frames kind=$kind"
        if (one.frames != two.frames) bad("P-chunk", "frames ${one.frames} vs chunked ${two.frames}", cfg)
        else for (i in 0 until 2 * one.frames) if (one.data[i] != two.data[i]) { bad("P-chunk", "sample $i: ${one.data[i]} vs ${two.data[i]}", cfg); break }
        val expected = if (frames == 0) 0 else kotlin.math.floor((frames - 1) * target.toDouble() / rate + 1e-9).toInt() + 1
        if (abs(one.frames - expected) > 1) bad("P-count", "frames ${one.frames}, expected about $expected", cfg)
        if (kind == 0 && one.data.take(2 * one.frames).any { it != 0.toShort() }) bad("P-silence", "silence in, non-zero out", cfg)
    }

    @Test fun campaign() {
        val nFx = (System.getProperty("qa17.fx") ?: System.getenv("QA17_FX"))?.toInt() ?: 20
        val nEng = (System.getProperty("qa17.eng") ?: System.getenv("QA17_ENG"))?.toInt() ?: 5
        val nTime = (System.getProperty("qa17.time") ?: System.getenv("QA17_TIME"))?.toInt() ?: 0
        val seed0 = (System.getProperty("qa17.seed") ?: System.getenv("QA17_SEED"))?.toInt() ?: 1
        val t0 = System.currentTimeMillis()
        log("start seed=$seed0 fx=$nFx")
        for (s in 0 until nFx) runCatching { fxCase(seed0 * 100_000 + s) }.onFailure { bad("EXC", it.toString(), "fx seed=${seed0 * 100_000 + s}") }
        val t1 = System.currentTimeMillis()
        for (s in 0 until nEng) runCatching { engineCase(seed0 * 100_000 + s) }.onFailure { bad("EXC", it.toString(), "eng seed=${seed0 * 100_000 + s}") }
        val nEb = (System.getProperty("qa17.eb") ?: System.getenv("QA17_EB"))?.toInt() ?: 3
        for (s in 0 until nEb) runCatching { engineBlockCase(seed0 * 100_000 + s) }.onFailure { bad("EXC", it.toString(), "eb seed=${seed0 * 100_000 + s}") }
        val nPcm = (System.getenv("QA17_PCM"))?.toInt() ?: 20
        for (s in 0 until nPcm) runCatching { pcmCase(seed0 * 100_000 + s) }.onFailure { bad("EXC", it.toString(), "pcm seed=${seed0 * 100_000 + s}") }
        val nSteal = (System.getProperty("qa17.steal") ?: System.getenv("QA17_STEAL"))?.toInt() ?: 4
        for (s in 0 until nSteal) runCatching { stealCase(seed0 * 100_000 + s) }.onFailure { bad("EXC", it.toString(), "steal seed=${seed0 * 100_000 + s}") }
        val t2 = System.currentTimeMillis()
        val tim = mutableListOf<String>()
        for (s in 0 until nTime) timing(seed0 * 100_000 + s, tim)
        val t3 = System.currentTimeMillis()
        counts["pcm-cases"] = nPcm; counts["steal-cases"] = nSteal; counts["eng-cases"] = nEng; counts["eb-cases"] = nEb; counts["timing-cases"] = nTime
        val out = StringBuilder()
        fun println(x: String) { out.appendLine(x) }
        val med = tim.map { it.split(" ")[0].toDouble() }.sorted()
        println("QA17 fx=$nFx (${t1 - t0} ms) eng=$nEng (${t2 - t1} ms) timing=$nTime (${t3 - t2} ms) counts=$counts")
        for (k in listOf("string", "sampled", "keys", "drums")) { val v = stealSteps.filter { it.startsWith(k) }.map { it.split(" ")[1].toDouble() }; if (v.isNotEmpty()) println("QA17 steal $k n=${v.size} max step ${v.max()} median ${v.sorted()[v.size / 2]}") }
        if (med.isNotEmpty()) println("QA17 timing median-of-medians ${med[med.size / 2]} µs, max median ${med.last()} µs")
        log(out.toString())
    }
}
