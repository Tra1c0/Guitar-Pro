package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.*
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The bundled sampled drum kit (app/src/main/assets/banks/artifret-pearl-kit.rwdk), loaded once for the adapter tests. */
object Qa24AudioKit {
    private val file: File? = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/artifret-pearl-kit.rwdk") }.firstOrNull { it.isFile }
    val kit: DrumKit? by lazy {
        file?.let { f ->
            val t0 = System.nanoTime()
            f.inputStream().buffered(1 shl 16).use { input -> DrumKit.read { b, o, l -> input.read(b, o, l) } }
                .also { println("INFO drum kit ${it.bytes / 1_000_000} MB loaded in ${(System.nanoTime() - t0) / 1_000_000} ms (JVM, warm file cache)") }
        }
    }
    fun kitOrSkip(): DrumKit { val k = kit; assumeTrue("drum kit not found", k != null); return k!! }
}

/** Every first-hit check of cycle 19 (cold start, preview/stop gaps, idle stop, count-in, loop wrap, live vs export) with the sampled kit. */
class Qa24SampledDrumFirstHitTest : Qa19DrumFirstHitTest() {
    override val drumKit: DrumKit? get() = Qa24AudioKit.kitOrSkip()
}

/** The cycle-20 synchronicity run (tempo maps, repeats, count-in, loop, speed) with the sampled kit (cross-stick, GM 37). */
class Qa24SampledDrumSyncTest : Qa20SyncTest() {
    override val drumKit: DrumKit? get() = Qa24AudioKit.kitOrSkip()
}

/**
 * QA-Zyklus 24, Live = Offline mit gesampeltem Schlagzeug: die Standardband (HG-Gitarre gesampelt, Bass, Drums mit Groove,
 * Crash, Tom-Fill mit Rimshot und abgewürgtem Becken) über den Live-Pfad (EngineTransport → RealtimeHost.renderBlock,
 * FakeSink, Blöcke 256/192/480, Start in Takt 2) gegen OfflineMixdown (1024er-Blöcke). Dieselbe Engine, derselbe Plan:
 * max |live − offline| ≤ 1e-6 (Q19L-Kriterium). Dazu: das Kit kommt nach dem Start an (Hintergrund-Laden der App) und wird
 * beim nächsten Play übernommen; die Diagnose meldet es.
 */
class Qa24SampledDrumsLiveTest {
    companion object { const val SR = 48_000 }
    private val q = Qa20Kit("q24l")

    private fun band(): Project {
        val m = List(3) { Measure(q.id()) }
        val riff = List(3) { q.riffBar() }
        return q.project(m, listOf(
            q.track("Gitarre", Qa20Kit.guitarDef, riff, m),
            q.track("Bass", Qa20Kit.bassDef, riff.map { q.bassBar(it) }, m, "rw.bass.pick"),
            q.track("Drums", Qa20Kit.drumsDef, listOf(q.drumBar(), q.fillBar(), q.drumBar()), m, kind = NotationKind.PERCUSSION)),
            listOf(TempoChange(ScorePosition(m[0].id), 150.0)))
    }

    private fun offline(p: Project, kit: DrumKit?, from: Long = 0): FloatArray {
        val bytes = java.io.ByteArrayOutputStream()
        val r = OfflineMixdown(ScorePlaybackCompiler(), Qa18FirstBeatTest.bank, kit).render(p, RenderOptions(sampleRate = SR, bitDepth = 32, tailSeconds = 1.5, commonStartFrame = from), { b, o, l -> bytes.write(b, o, l) })
        assertTrue("offline render", r is Outcome.Success)
        val d = Wav.decode(bytes.toByteArray())
        return FloatArray(d.frames * 2) { i -> if (i % 2 == 0) d.left[i / 2] else d.right[i / 2] }
    }

    /** Live capture (interleaved) aligned like the export (the rack latency after the first live frame). */
    private fun live(p: Project, kit: DrumKit?, frames: Int, block: Int, seekBar: Int = 0): FloatArray = runBlocking {
        val lat = ChannelRack.build(p, SR, 1024).outputLatencyFrames
        val sink = Qa20Sink(block, frames + lat + 4 * block)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0, bankProvider = { Qa18FirstBeatTest.bank }, drumKitProvider = { kit })
        val t = EngineTransport(host, pollMillis = 1_000_000, drumKitProvider = { kit })
        try {
            t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false))
            if (seekBar > 0) t.seek(PlaybackOccurrence(ScorePosition(p.measures[seekBar].id), 1, seekBar))
            assertTrue("live play", t.play() is Outcome.Success)
            repeat((frames + lat) / block + 2) { host.renderBlock() }
            FloatArray(frames * 2) { i -> if (i % 2 == 0) sink.left[lat + i / 2] else sink.right[lat + i / 2] }
        } finally { t.shutdown() }
    }

    @Test fun liveAndOfflineAreIdenticalWithTheSampledKit() {
        val kit = Qa24AudioKit.kitOrSkip()
        assumeTrue("sample bank not found", Qa18FirstBeatTest.bank != null)
        val p = band()
        val failures = mutableListOf<String>()
        val off = offline(p, kit)
        var peak = 0f; for (x in off) peak = maxOf(peak, abs(x))
        for (block in listOf(256, 192, 480)) {
            val liv = live(p, kit, off.size / 2, block)
            var maxDiff = 0f; var at = -1; for (i in off.indices) { val d = abs(liv[i] - off[i]); if (d > maxDiff) { maxDiff = d; at = i / 2 } }
            println("Q24L Standardband mit gesampeltem Kit, Live-Blöcke $block: ${off.size / 2} Frames, Spitze ${"%.1f".format(java.util.Locale.ROOT, 20 * kotlin.math.log10(peak.toDouble()))} dBFS, max |live − offline| ${"%.2e".format(maxDiff)} (Frame $at)")
            if (maxDiff > 1e-6f) failures += "Block $block: max |Δ| ${"%.2e".format(maxDiff)} bei Frame $at"
        }
        val plan = (ScorePlaybackCompiler().compileNow(p, SR) as Outcome.Success).value
        val bar2 = plan.frameFor(PlaybackOccurrence(ScorePosition(p.measures[1].id), 1, 1))!!
        val off2 = offline(p, kit, bar2)
        val liv2 = live(p, kit, off2.size / 2, 256, seekBar = 1)
        var maxDiff = 0f; for (i in off2.indices) maxDiff = maxOf(maxDiff, abs(liv2[i] - off2[i]))
        println("Q24L Start in Takt 2 (Planframe $bar2): max |live − offline| ${"%.2e".format(maxDiff)}")
        if (maxDiff > 1e-6f) failures += "Start Takt 2: max |Δ| ${"%.2e".format(maxDiff)}"
        // The kit really plays: the drum stem differs from the synthetic kit's.
        val syn = offline(p.copy(tracks = p.tracks.mapIndexed { i, t -> t.copy(mix = t.mix.copy(mute = i != 2)) }), null)
        val smp = offline(p.copy(tracks = p.tracks.mapIndexed { i, t -> t.copy(mix = t.mix.copy(mute = i != 2)) }), kit)
        var dd = 0f; for (i in syn.indices) dd = maxOf(dd, abs(syn[i] - smp[i]))
        if (dd < 1e-3f) failures += "gesampeltes Kit klingt wie das synthetische (max |Δ| $dd)"
        assertTrue("live vs offline (Kit): " + failures.joinToString("; "), failures.isEmpty())
    }

    /**
     * The app loads the kit in the background: a Play right after process start may begin with the synthetic kit. The next
     * Play after a stop takes the kit over (RealtimeHost.refreshEngine, as for the guitar bank) and the diagnostics report it.
     */
    @Test fun aKitArrivingAfterTheStartIsTakenOverAtTheNextPlay() = runBlocking {
        val kit = Qa24AudioKit.kitOrSkip()
        var provided: DrumKit? = null
        val sink = Qa20Sink(256, 0)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0, drumKitProvider = { provided })
        val t = EngineTransport(host, pollMillis = 1_000_000, drumKitProvider = { provided })
        try {
            val p = band()
            t.prepare(p, PracticeSettings(speed = 1.0, countInBars = 0, metronome = false))
            assertTrue(t.play() is Outcome.Success)
            repeat(20) { host.renderBlock() }
            assertFalse("synthetic kit before the kit is loaded", host.stats()!!.hasDrumSamples)
            provided = kit
            t.stop()
            // Let the stopped voices ring out, so the engine may be replaced (refreshEngine waits for silence).
            repeat(4000) { host.renderBlock() }
            assertTrue(t.play() is Outcome.Success)
            repeat(4) { host.renderBlock() }
            val s = host.stats()!!
            println("Q24L Kit nach dem Start geladen: hasDrumSamples ${s.hasDrumSamples}, Stimmen ${s.voices}")
            assertTrue("kit not taken over at the next play", s.hasDrumSamples)
        } finally { t.shutdown() }
    }
}
