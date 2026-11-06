package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.ports.SinkFormat
import dev.riffworkbench.synth.*
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * QA cycle 18 / dimension D3: playback start integrity ("the first beat is sometimes swallowed", Bericht 1 §8).
 *
 * Every case puts a note exactly on the start frame of the playback and compares its onset (energy in 5/10/20/50 ms
 * windows after the expected frame, and the first audible frame) with the same note rendered by a long-running engine
 * (one full bar of silence rendered first). Live path: EngineTransport -> RealtimeHost (manual blocks, FakeSink) -> SynthEngine.
 * Export path: OfflineMixdown at frame granularity.
 *
 * Material: 4/4 at 150 bpm (bar = 76 800 frames at 48 kHz, quarter = 19 200). Three quarter notes on three different
 * strings (each string has its own round-robin counter, so the first trigger of a string is always round robin 0):
 * A = string 1 (E2, 40) on beat 1 of the first content bar, B = string 2 (A2, 45) on beat 3, C = string 3 (D3, 50) on beat 1 of the next bar.
 */
class Qa18FirstBeatTest {
    companion object {
        const val SR = 48_000
        const val BLOCK = 256
        const val LATENCY = 2048
        const val BAR = 76_800L
        const val QUARTER = 19_200L
        private val bankFile = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.firstOrNull { it.isFile }
        val bank: SampleBank? by lazy {
            bankFile?.let { f ->
                val t0 = System.nanoTime()
                val b = f.inputStream().buffered(1 shl 16).use { i -> SampleBank.read { buf, o, l -> i.read(buf, o, l) } }
                println("INFO sample bank ${b.bytes / 1_000_000} MB loaded in ${(System.nanoTime() - t0) / 1_000_000} ms (JVM, warm file cache)")
                b
            }
        }
        const val HIGHGAIN = "rw.guitar.highgain"
        const val CLEAN = "rw.guitar.clean"
        val plain = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)
        /** Windows after the onset (ms) whose energy is compared. */
        val windowsMs = listOf(0 to 5, 5 to 10, 10 to 20, 20 to 50)
    }

    private var n = 0
    private fun id() = EntityId("q18-${n++}")

    /** [lead] silent bars, then A (beat 1), B (beat 3), C (beat 1 of the following bar); [lowA] puts A on the open 7th string (A1 = 33, below the bank). */
    private fun project(presetId: String, lead: Int = 0, lowA: Boolean = false, projectId: EntityId = id()): Project {
        val measures = List(lead + 2) { Measure(id()) }
        val instrument = InstrumentDefinition()
        fun note(string: Int) = Note(id(), Pitch(instrument.pitch(string, 0)), string, 0)
        fun q(offset: Fraction, string: Int) = Beat(id(), offset, WrittenDuration(4), listOf(note(string)))
        val bars = measures.mapIndexed { i, m ->
            val beats = when (i) {
                lead -> listOf(q(Fraction.ZERO, if (lowA) 0 else 1), q(Fraction.of(1, 2), 2))
                lead + 1 -> listOf(q(Fraction.ZERO, 3))
                else -> emptyList()
            }
            Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) beats else emptyList()) })
        }
        val track = Track(EntityId("$projectId-t"), "Gitarre · Drop A", instrument, listOf(Staff(EntityId("$projectId-s"), bars = bars)), sound = SoundAssignment(presetId = presetId))
        return Project(id = projectId, measures = measures, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(measures[0].id), 150.0)))
    }
    private fun at(p: Project, bar: Int, offset: Fraction = Fraction.ZERO) = PlaybackOccurrence(ScorePosition(p.measures[bar].id, offset), 1, bar)

    /** Manual host: no render thread, blocks are rendered by the test; every sink the host opens captures its left channel. */
    private inner class Rig(private val useBank: SampleBank?, idleStop: Double = 1000.0, bankProvider: (() -> SampleBank?)? = null) {
        val sinks = ArrayList<FakeSink>()
        val sink: FakeSink get() = sinks.last()
        val host = RealtimeHost({ FakeSink(SR, BLOCK, LATENCY).also { it.capture = true; sinks += it } }, threadStarter = { _, _ -> Thread {} },
            idleStopSeconds = idleStop, bankProvider = bankProvider ?: { useBank })
        val t = EngineTransport(host, pollMillis = 5)
        fun render(blocks: Int) { repeat(blocks) { host.renderBlock() } }
        fun settle(blocks: Int = 1) { render(blocks); Thread.sleep(40) }
        /** Capture index where the next rendered block starts. */
        fun mark() = sink.captured.size
        fun left(): FloatArray = FloatArray(sink.captured.size) { sink.captured[it] }
        fun close() = t.shutdown()
    }

    // ------------------------------------------------------------------ measurement

    private fun rmsDb(x: FloatArray, from: Int, count: Int): Double {
        var s = 0.0
        for (i in from until minOf(from + count, x.size)) s += x[i].toDouble() * x[i]
        return 10 * log10(s / count + 1e-30)
    }
    private fun firstAbove(x: FloatArray, from: Int, threshold: Float = 1e-4f): Int { for (i in from until x.size) if (abs(x[i]) > threshold) return i - from; return -1 }

    private class Onset(val firstAudible: Int, val windowsDb: List<Double>, val preDb: Double, /** frames from the nominal onset to the first sample above 1e-4 */ val lead: Int = -1, /** RMS of the whole first 50 ms */ val totalDb: Double = 0.0) {
        override fun toString() = "first audible +$firstAudible frames, windows ${windowsDb.joinToString { "%.1f".format(it) }} dBFS (0-50 ms ${"%.1f".format(totalDb)}), 10 ms before: ${"%.0f".format(preDb)} dBFS"
    }
    /**
     * Onset at [at]: the frame where the energy accumulated since [at] reaches 1 % of the energy of the first 50 ms
     * (robust against a faint tail of an earlier note), plus the window energies and the level of the 10 ms before.
     */
    private fun onset(x: FloatArray, at: Int): Onset {
        val preDb = if (at >= 480) rmsDb(x, at - 480, 480) else -300.0
        val span = minOf(SR / 20, x.size - at)
        var total = 0.0
        for (i in at until at + span) total += x[i].toDouble() * x[i]
        var first = -1
        if (total > 1e-12) { var acc = 0.0; for (i in at until at + span) { acc += x[i].toDouble() * x[i]; if (acc >= 0.01 * total) { first = i - at; break } } }
        return Onset(first, windowsMs.map { (a, b) -> rmsDb(x, at + a * SR / 1000, (b - a) * SR / 1000) }, preDb, firstAbove(x, at, 1e-4f), rmsDb(x, at, SR / 20))
    }

    /**
     * Reference ("long-running engine"): the same preset/bank, one bar of silence rendered first, then A, B, C.
     * Returns the onsets of A, B and C. Rendered through the identical live stack so only the start differs.
     */
    private fun reference(presetId: String, b: SampleBank?, lowA: Boolean = false): List<Onset> = runBlocking {
        val rig = Rig(b)
        val p = project(presetId, lead = 1, lowA = lowA)
        rig.t.prepare(p, plain); rig.t.play()
        rig.render((3 * BAR / BLOCK).toInt() + 2)
        val x = rig.left()
        rig.close()
        listOf(onset(x, BAR.toInt()), onset(x, (BAR + 2 * QUARTER).toInt()), onset(x, (2 * BAR).toInt()))
    }

    /** Asserts that [actual] has the same onset time (±[frames]) and window energies (±[db]) as [expected]. */
    private fun assertOnset(name: String, expected: Onset, actual: Onset, db: Double, frames: Int = 8) {
        println("MEASURE $name: $actual   | reference: $expected")
        assertTrue("$name: no audible onset (reference $expected, got $actual)", actual.firstAudible >= 0)
        assertTrue("$name: onset moved by ${actual.firstAudible - expected.firstAudible} frames (reference $expected, got $actual)", abs(actual.firstAudible - expected.firstAudible) <= frames)
        // The whole first 50 ms must carry the reference energy within 3 dB in every case (round robins and modulation
        // redistribute energy between the sub-windows, a swallowed onset loses it); the sub-windows within [db].
        val total = actual.totalDb - expected.totalDb
        assertTrue("$name: first 50 ms deviate ${"%.1f".format(total)} dB from the long-running engine (reference $expected, got $actual)", abs(total) <= minOf(3.0, maxOf(db, 1.0)))
        windowsMs.forEachIndexed { i, (a, b) ->
            val diff = actual.windowsDb[i] - expected.windowsDb[i]
            assertTrue("$name: window $a-$b ms deviates ${"%.1f".format(diff)} dB from the long-running engine (reference $expected, got $actual)", abs(diff) <= db)
        }
    }

    private fun variants(): List<Triple<String, SampleBank?, String>> = buildList {
        for (preset in listOf(HIGHGAIN, CLEAN)) {
            add(Triple(preset, null, "$preset/modelled"))
            bank?.let { add(Triple(preset, it, "$preset/sampled")) }
        }
    }
    /**
     * Sub-window energy tolerance (the 0-50 ms total is always held to <= 3 dB). Sampled High Gain, first trigger of the
     * string (round robin 0): deterministic -> 0.5 dB. Otherwise round robins, the free-running chorus LFO (Clean) and the
     * seeded pick noise of the modelled strings redistribute energy between 5 ms sub-windows. A swallowed onset loses > 6 dB.
     */
    private fun tol(preset: String, b: SampleBank?, firstTrigger: Boolean = true) = when {
        b != null && firstTrigger && preset == HIGHGAIN -> 0.5
        b != null && firstTrigger -> 3.0          // Clean: free-running chorus LFO, reverb tail of earlier notes in the reference
        b != null -> 8.0                           // another round robin (per-string seeded, regions RMS-normalised): 5 ms sub-windows of the pick transient differ by up to ~7 dB; the 0-50 ms total guards the onset
        else -> 3.0                                // modelled strings: pick noise seeded from the note frame
    }
    /**
     * Onset-frame tolerance (1 % energy point): 8 frames where deterministic, 128 frames = 2.7 ms for another round robin
     * (per-string seeded round robins have a different pick transient, so the 1 % point moves), else 64 = 1.3 ms;
     * a late block would be >= 256.
     */
    private fun frameTol(preset: String, b: SampleBank?, firstTrigger: Boolean = true) = when {
        b != null && firstTrigger && preset == HIGHGAIN -> 8
        b != null && !firstTrigger -> 128
        else -> 64
    }

    // ------------------------------------------------------------------ live path

    /** Fresh host, note A exactly on frame 0, Play: the very first rendered block carries the complete onset. */
    @Test fun freshStartPlaysTheNoteOnFrameZeroCompletely() = runBlocking {
        for ((preset, b, name) in variants()) {
            val ref = reference(preset, b)
            val rig = Rig(b)
            rig.t.prepare(project(preset), plain)
            rig.t.play()
            val m = rig.mark()
            rig.render(16)
            assertOnset("fresh start, A at frame 0 [$name]", ref[0], onset(rig.left(), m), db = tol(preset, b), frames = frameTol(preset, b))
            rig.close()
        }
    }

    /** Same with the user's low A (33, below the sampled range: pitch-shifted from Db2). */
    @Test fun freshStartLowAOnTheSeventhString() = runBlocking {
        val b = bank ?: return@runBlocking
        val ref = reference(HIGHGAIN, b, lowA = true)
        val rig = Rig(b)
        rig.t.prepare(project(HIGHGAIN, lowA = true), plain)
        rig.t.play()
        val m = rig.mark()
        rig.render(16)
        assertOnset("fresh start, low A1 (33) at frame 0 [highgain/sampled]", ref[0], onset(rig.left(), m), db = tol(HIGHGAIN, b))
        rig.close()
    }

    /** Seek to bar 2 (parked before a plan exists, as togglePlay does), Play: note C is on the start frame. */
    @Test fun freshStartAfterSeekToBar2PlaysNoteCOnTheStartFrame() = runBlocking {
        for ((preset, b, name) in variants()) {
            val ref = reference(preset, b)
            val rig = Rig(b)
            val p = project(preset)
            rig.t.prepare(p, plain)
            rig.t.seek(at(p, 1))
            rig.t.play()
            val m = rig.mark()
            rig.render(16)
            assertOnset("seek bar 2 + play, C at frame 0 [$name]", ref[2], onset(rig.left(), m), db = tol(preset, b), frames = frameTol(preset, b))
            assertEquals("published start position", p.measures[1].id, rig.t.state.value.position?.position?.measureId)
            rig.close()
        }
    }

    /** Seek onto a note in the middle of a bar (beat 3): the note at the seek frame is inclusive. */
    @Test fun seekOntoANoteMidBarIncludesThatNote() = runBlocking {
        for ((preset, b, name) in variants()) {
            val ref = reference(preset, b)
            val rig = Rig(b)
            val p = project(preset)
            rig.t.prepare(p, plain)
            rig.t.seek(at(p, 0, Fraction.of(1, 2)))
            rig.t.play()
            val m = rig.mark()
            rig.render(16)
            assertOnset("seek beat 3 + play, B at frame 0 [$name]", ref[1], onset(rig.left(), m), db = tol(preset, b), frames = frameTol(preset, b))
            rig.close()
        }
    }

    /** Seek between notes (beat 2): B follows after one quarter and C after three, both complete. */
    @Test fun seekBetweenNotesKeepsTheFollowingOnsetsOnTheGrid() = runBlocking {
        for ((preset, b, name) in variants()) {
            val ref = reference(preset, b)
            val rig = Rig(b)
            val p = project(preset)
            rig.t.prepare(p, plain)
            rig.t.seek(at(p, 0, Fraction.of(1, 4)))
            rig.t.play()
            val m = rig.mark()
            rig.render((3 * QUARTER / BLOCK).toInt() + 16)
            val x = rig.left()
            assertOnset("seek beat 2, B one quarter later [$name]", ref[1], onset(x, m + QUARTER.toInt()), db = tol(preset, b), frames = frameTol(preset, b))
            assertOnset("seek beat 2, C three quarters later [$name]", ref[2], onset(x, m + 3 * QUARTER.toInt()), db = tol(preset, b), frames = frameTol(preset, b))
            rig.close()
        }
    }

    /** Play, Stop (button), Play again: restarts at frame 0 with the full onset of A (second trigger of its string: round robin 1). */
    @Test fun stopThenPlayRestartsWithTheCompleteFirstNote() = runBlocking {
        for ((preset, b, name) in variants()) {
            val ref = reference(preset, b)
            val rig = Rig(b)
            val p = project(preset)
            rig.t.prepare(p, plain); rig.t.play(); rig.settle(40)
            rig.t.stop(); rig.settle(100)                 // ~0.5 s: the user does not re-press within one block
            rig.t.play()
            val m = rig.mark()
            rig.render(16)
            assertOnset("stop -> play, A at frame 0 (round robin 1) [$name]", ref[0], onset(rig.left(), m), db = tol(preset, b, false), frames = frameTol(preset, b, false))
            rig.close()
        }
    }

    /** Play, Pause before beat 3, Play: resumes at the published (presented) frame; B arrives complete at its distance. */
    @Test fun pauseThenPlayResumesWithTheNextNoteComplete() = runBlocking {
        for ((preset, b, name) in variants()) {
            val ref = reference(preset, b)
            val rig = Rig(b)
            val p = project(preset)
            rig.t.prepare(p, plain); rig.t.play(); rig.settle(20)      // 5 120 frames written, 3 072 presented
            rig.t.pause(); rig.settle(4)
            val resume = rig.t.state.value.sample!!.frame
            assertTrue("resume frame $resume must lie before beat 3", resume < 2 * QUARTER)
            rig.t.play()
            val m = rig.mark()
            rig.render(((2 * QUARTER - resume) / BLOCK).toInt() + 16)
            assertOnset("pause -> play (resume at $resume), B at its distance [$name]", ref[1], onset(rig.left(), m + (2 * QUARTER - resume).toInt()), db = tol(preset, b), frames = frameTol(preset, b))
            rig.close()
        }
    }

    /** The host stops itself after the idle time; the next Play reopens the sink and must deliver the first note complete. */
    @Test fun idleStopThenPlayDeliversTheFirstNoteComplete() = runBlocking {
        for ((preset, b, name) in variants()) {
            val ref = reference(preset, b)
            val rig = Rig(b, idleStop = 0.3)
            val p = project(preset)
            rig.t.prepare(p, plain); rig.t.play(); rig.settle(20)
            rig.t.pause(); rig.settle(4)
            var guard = 0
            while (rig.host.isRunning && guard++ < 10_000) rig.host.renderBlock()
            assertFalse("host must have stopped itself", rig.host.isRunning)
            Thread.sleep(60)
            val before = rig.sinks.size
            rig.t.seek(at(p, 1))
            assertTrue(rig.t.play() is Outcome.Success)
            assertEquals("a new sink was opened", before + 1, rig.sinks.size)
            val m = rig.mark()
            rig.render(16)
            assertOnset("idle stop -> seek bar 2 -> play, C at frame 0 [$name]", ref[2], onset(rig.left(), m), db = tol(preset, b), frames = frameTol(preset, b))
            Thread.sleep(40)
            val st = rig.t.state.value; val snap = rig.host.snapshot()
            assertTrue("status after restart: $st; snapshot playing=${snap.playing} running=${snap.running} ended=${snap.ended} plays=${snap.plays} programs=${snap.programs} frame=${snap.planFrame}", st.status == TransportStatus.PLAYING)
            rig.close()
        }
    }

    /** Count-in of one bar: A follows exactly one bar after the start, complete. Also after a seek to bar 2 (C). */
    @Test fun countInOfOneBarDoesNotCoverTheFirstRegularNote() = runBlocking {
        for ((preset, b, name) in variants()) {
            val ref = reference(preset, b)
            val rig = Rig(b)
            val p = project(preset)
            rig.t.prepare(p, plain.copy(countInBars = 1))
            rig.t.play()
            val m = rig.mark()
            rig.render((BAR / BLOCK).toInt() + 16)
            val x = rig.left()
            assertOnset("count-in 1 bar, A one bar after start [$name]", ref[0], onset(x, m + BAR.toInt()), db = tol(preset, b), frames = frameTol(preset, b))
            assertTrue("count-in clicks are audible before the note", rmsDb(x, m, 2400) > -40)
            rig.t.stop(); rig.settle(4)
            rig.t.seek(at(p, 1))
            rig.t.play()
            val m2 = rig.mark()
            rig.render((BAR / BLOCK).toInt() + 16)
            assertOnset("count-in 1 bar after seek to bar 2, C one bar after start [$name]", ref[2], onset(rig.left(), m2 + BAR.toInt()), db = tol(preset, b), frames = frameTol(preset, b))
            rig.close()
        }
    }

    /** Loop bar 2: Play starts at the loop start with C complete, and C is complete again after the wrap. */
    @Test fun loopStartingAtBar2PlaysNoteCOnTheStartFrameAndAfterTheWrap() = runBlocking {
        for ((preset, b, name) in variants()) {
            val ref = reference(preset, b)
            val rig = Rig(b)
            val p = project(preset)
            rig.t.prepare(p, plain.copy(loop = LoopRange(at(p, 1), at(p, 1))))
            rig.t.play()
            val m = rig.mark()
            rig.render((BAR / BLOCK).toInt() + 16)
            val x = rig.left()
            assertOnset("loop bar 2, C at frame 0 [$name]", ref[2], onset(x, m), db = tol(preset, b), frames = frameTol(preset, b))
            assertOnset("loop bar 2, C after the wrap (round robin 1) [$name]", ref[2], onset(x, m + BAR.toInt()), db = tol(preset, b, false), frames = frameTol(preset, b, false))
            rig.close()
        }
    }

    // ------------------------------------------------------------------ export path

    private fun offline(p: Project, b: SampleBank?, from: Long = 0, frames: Long): FloatArray {
        val bytes = java.io.ByteArrayOutputStream()
        val r = OfflineMixdown(ScorePlaybackCompiler(), b).render(p, RenderOptions(sampleRate = SR, bitDepth = 32, tailSeconds = 0.0, commonStartFrame = from), { buf, o, l -> bytes.write(buf, o, l) })
        assertTrue("render ok", r is Outcome.Success)
        val data = bytes.toByteArray()
        // 44-byte header, 32-bit float stereo (Wav.encode writes raw float bits for depth 32): left channel of the first [frames] frames.
        val total = (data.size - 44) / 8
        val count = minOf(frames, total.toLong()).toInt()
        return FloatArray(count) { i -> val o = 44 + i * 8; Float.fromBits((data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8) or ((data[o + 2].toInt() and 0xFF) shl 16) or (data[o + 3].toInt() shl 24)) }
    }

    /** Export: the note on frame 0 of the project equals (per frame) the same note after one bar of silence. */
    @Test fun offlineMixdownRendersTheFirstFramesLikeALongRunningEngine() {
        for ((preset, b, name) in variants()) {
            val ref = offline(project(preset, lead = 1), b, frames = 3 * BAR)
            val x = offline(project(preset), b, frames = 2 * BAR)
            val refOnset = onset(ref, BAR.toInt())
            assertOnset("export, A at frame 0 [$name]", refOnset, onset(x, 0), db = tol(preset, b), frames = frameTol(preset, b))
            var maxDiff = 0f; var at = -1
            for (i in 0 until (SR / 10)) { val d = abs(x[i] - ref[BAR.toInt() + i]); if (d > maxDiff) { maxDiff = d; at = i } }
            println("MEASURE export first 100 ms vs long-running [$name]: max |diff| ${"%.5f".format(maxDiff)} at frame $at")
            // Bit-exact only where nothing runs freely: modelled strings seed their noise from the note frame, the Clean chorus LFO keeps its phase across the silent bar.
            if (b != null && preset == HIGHGAIN) assertTrue("export first 100 ms differ from the long-running engine by up to $maxDiff at frame $at [$name]", maxDiff < 1e-6f)
            // commonStartFrame (stem alignment): starting the lead project at the bar line gives the same first frames.
            val y = offline(project(preset, lead = 1), b, from = BAR, frames = 2 * BAR)
            assertOnset("export from commonStartFrame=bar 2, A at frame 0 [$name]", refOnset, onset(y, 0), db = tol(preset, b), frames = frameTol(preset, b))
        }
    }

    /**
     * Live (256-frame blocks) and export (1024-frame blocks) agree on the onset of the first note. The export compensates
     * the effect latency (QA cycle 21: export frame 0 = live frame [ChannelRack.outputLatencyFrames], the cabinet's 64).
     */
    @Test fun liveAndExportStartsAgree() = runBlocking {
        for ((preset, b, name) in variants()) {
            val x = offline(project(preset), b, frames = QUARTER)
            val lat = ChannelRack.build(project(preset), SR, 1024).outputLatencyFrames
            val rig = Rig(b)
            rig.t.prepare(project(preset), plain); rig.t.play()
            val m = rig.mark()
            rig.render(16)
            assertOnset("live vs export, A at frame 0 [$name]", onset(x, 0), onset(rig.left(), m + lat), db = tol(preset, b), frames = frameTol(preset, b))
            rig.close()
        }
    }

    // ------------------------------------------------------------------ bank availability at the first start

    /**
     * AppContainer loads the sample bank asynchronously (Dispatchers.IO) and RealtimeHost.start() captures bankProvider()
     * once. A Play before the bank is loaded creates an engine without samples. Since the D3-4 fix the transport re-checks
     * the bank on every play/preview from a non-playing state and swaps the engine at a block boundary (RealtimeHost.refreshEngine)
     * once the old engine's voices have released, so the next playback after the load uses the samples; a running playback
     * keeps its engine until it stops.
     */
    @Test fun bankArrivingAfterTheFirstStartIsPickedUpByTheNextPlay() = runBlocking {
        val b = bank ?: return@runBlocking
        var loaded: SampleBank? = null
        val rig = Rig(null, idleStop = 0.3, bankProvider = { loaded })
        val p = project(HIGHGAIN)
        rig.t.prepare(p, plain); rig.t.play(); rig.settle(4)
        assertEquals("first session without bank", false, rig.host.stats()?.hasSamples)
        loaded = b                                     // bank finished loading in the background
        rig.t.prepare(p.copy(revision = p.revision + 1), plain); rig.settle(4)   // an edit while playing
        assertEquals("a running playback keeps its engine (no swap under sounding voices)", false, rig.host.stats()?.hasSamples)
        rig.t.stop(); rig.settle(4)
        var silent = 0                                 // the swap waits until the old engine's voices have released (Qa18StartFixesTest)
        while ((rig.host.stats()?.voices ?: 0) > 0 && silent++ < 10_000) rig.host.renderBlock()
        val m = rig.mark()
        rig.t.play(); rig.settle(4)                    // host still running: the engine is swapped before this Play
        val whileRunning = rig.host.stats()?.hasSamples
        assertEquals("the next play on the running host uses the bank", true, whileRunning)
        assertEquals("playback continues after the swap", TransportStatus.PLAYING, rig.t.state.value.status)
        rig.render(16)
        val ref = reference(HIGHGAIN, b)
        assertOnset("play after the bank arrived, A at frame 0 [highgain/sampled]", ref[0], onset(rig.left(), m), db = tol(HIGHGAIN, b), frames = frameTol(HIGHGAIN, b))
        rig.t.pause(); rig.settle(4)
        var guard = 0
        while (rig.host.isRunning && guard++ < 10_000) rig.host.renderBlock()
        Thread.sleep(60)
        rig.t.play(); rig.settle(4)
        val afterRestart = rig.host.stats()?.hasSamples
        rig.close()
        println("MEASURE bank pickup: while running=$whileRunning, after idle stop/restart=$afterRestart")
        assertEquals("after the idle stop the restarted host uses the bank", true, afterRestart)
    }

    /** Timing figures for the start path (JVM desktop numbers, printed for the report). */
    @Test fun startPathTimings() {
        val b = bank
        val t0 = System.nanoTime(); SynthEngine(SR, 1024, b); val tEngine = (System.nanoTime() - t0) / 1_000_000.0
        val p = project(HIGHGAIN)
        val t1 = System.nanoTime(); ChannelRack.build(p, SR, 1024); val tRack = (System.nanoTime() - t1) / 1_000_000.0
        val t2 = System.nanoTime(); ChannelRack.build(p, SR, 1024); val tRack2 = (System.nanoTime() - t2) / 1_000_000.0
        val t3 = System.nanoTime(); ScorePlaybackCompiler().compileNow(p, SR); val tCompile = (System.nanoTime() - t3) / 1_000_000.0
        println("MEASURE SynthEngine construction ${"%.1f".format(tEngine)} ms (bank=${b != null}); ChannelRack.build (High Gain, 1 track) ${"%.1f".format(tRack)} ms cold / ${"%.1f".format(tRack2)} ms warm; compile ${"%.1f".format(tCompile)} ms")
        assertTrue(tEngine >= 0)
    }

    // ------------------------------------------------------------------ real render thread, real-time paced sink

    /** Like the device: write() blocks for one block's duration, so the render thread runs at real time and drains commands between blocks. */
    private class PacedSink(private val rate: Int = SR, private val block: Int = BLOCK, private val latency: Int = LATENCY) : dev.riffworkbench.ports.AudioSink {
        @Volatile var frames = 0L
        val captured = java.util.concurrent.CopyOnWriteArrayList<FloatArray>()
        @Volatile var closed = false
        override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> = Outcome.Success(SinkFormat(rate, block, latency, "paced"))
        override fun write(buffer: FloatArray, frames: Int): Int {
            captured += FloatArray(frames) { buffer[2 * it] }
            this.frames += frames
            Thread.sleep((frames * 1000L / rate).coerceAtLeast(1))
            return frames
        }
        override fun presentedFrame(): Long = (frames - latency).coerceAtLeast(0)
        override fun close() { closed = true }
        fun left(): FloatArray { val parts = captured.toList(); val out = FloatArray(parts.sumOf { it.size }); var o = 0; for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }; return out }
    }

    /** Guitar (High Gain) + bass + drums like the default project; the guitar plays A/B/C as above. */
    private fun bandProject(lead: Int = 0): Project {
        val g = project(HIGHGAIN, lead)
        val bass = Track(id(), "Bass", InstrumentDefinition(kind = InstrumentKind.BASS, tuning = listOf(28, 33, 38, 43), capoByString = List(4) { 0 }, midiProgram = 33),
            listOf(Staff(id(), bars = g.measures.map { m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v) }) })))
        val drums = Track(id(), "Drums", InstrumentDefinition(kind = InstrumentKind.PERCUSSION, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0),
            listOf(Staff(id(), setOf(NotationKind.PERCUSSION), g.measures.map { m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v) }) })))
        return g.copy(tracks = g.tracks + bass + drums)
    }

    /** Onset of the first note in [x] from [from]: aligned on the first frame above 1e-4 minus the reference's own lead-in (the paced capture has no fixed mark). */
    private fun alignedOnset(x: FloatArray, from: Int, ref: Onset): Onset? {
        val first = firstAbove(x, from, 1e-4f)
        if (first < 0) return null
        // Align on the threshold crossing of the reference measured the same way: the reference starts at a silent frame.
        return onset(x, from + first - ref.lead)
    }

    /** Fresh start, stop -> play and idle-stop -> play on a real render thread with real-time pacing (togglePlay order: prepare, seek, play). */
    @Test fun realRenderThreadDeliversTheFirstNoteCompleteInEveryStartCase() = runBlocking {
        val b = bank ?: return@runBlocking
        val refs = reference(HIGHGAIN, b)
        val sinks = ArrayList<PacedSink>()
        val host = RealtimeHost({ PacedSink().also { sinks += it } }, preferredRate = SR, preferredBlock = BLOCK, idleStopSeconds = 0.4, bankProvider = { b })
        val t = EngineTransport(host, pollMillis = 10)
        val p = bandProject()
        fun waitFor(what: String, timeoutMs: Long = 3000, cond: () -> Boolean) { val t0 = System.nanoTime(); while (!cond()) { assertTrue("timeout waiting for $what", System.nanoTime() - t0 < timeoutMs * 1_000_000); Thread.sleep(5) } }

        // 1. fresh start (togglePlay: prepare -> seek cursor -> play)
        t.prepare(p, plain); t.seek(at(p, 0)); val since = t.halts; assertTrue(t.play(since) is Outcome.Success)
        waitFor("first bar rendered") { sinks.last().frames > BAR / 2 }
        t.stop()
        val fresh = alignedOnset(sinks.last().left(), 0, refs[0])
        assertNotNull("fresh start on a real thread: nothing audible", fresh)
        assertOnset("real thread, fresh start, A [highgain/sampled, band]", refs[0], fresh!!, db = tol(HIGHGAIN, b), frames = frameTol(HIGHGAIN, b))
        println("MEASURE real thread fresh start: first audible after ${firstAbove(sinks.last().left(), 0, 1e-4f) - refs[0].lead} frames of silence (compile + rack build + thread start), underruns ${host.stats()?.underruns}, overruns ${host.stats()?.overruns}")

        // 2. stop -> play after ~0.3 s (host still running)
        Thread.sleep(300)
        val mark2 = sinks.last().frames.toInt()
        t.seek(at(p, 0)); assertTrue(t.play(t.halts) is Outcome.Success)
        waitFor("restart rendered") { sinks.last().frames > mark2 + BAR / 2 }
        t.stop()
        val again = alignedOnset(sinks.last().left(), mark2, refs[0])
        assertNotNull("stop -> play on a real thread: nothing audible", again)
        assertOnset("real thread, stop -> play, A (round robin 1) [highgain/sampled, band]", refs[0], again!!, db = tol(HIGHGAIN, b, false), frames = frameTol(HIGHGAIN, b, false))

        // 3. idle stop (0.4 s without voices) -> play reopens the sink on a new thread
        waitFor("idle stop", 5000) { !host.isRunning }
        Thread.sleep(50)
        val before = sinks.size
        t.seek(at(p, 1)); assertTrue(t.play(t.halts) is Outcome.Success)
        assertEquals("new sink after the idle stop", before + 1, sinks.size)
        waitFor("restart after idle stop rendered") { sinks.last().frames > BAR / 2 }
        t.stop()
        val restarted = alignedOnset(sinks.last().left(), 0, refs[2])
        assertNotNull("idle stop -> play on a real thread: nothing audible", restarted)
        assertOnset("real thread, idle stop -> seek bar 2 -> play, C [highgain/sampled, band]", refs[2], restarted!!, db = tol(HIGHGAIN, b), frames = frameTol(HIGHGAIN, b))
        t.shutdown()
    }

    /**
     * Render time of the very first blocks that carry voices on a cold JVM (code paths run for the first time), versus
     * the warm median. On the device the same cold path runs in the audio callback (debug APK: no AOT); each block over
     * the budget (block/rate) is an underrun on a low-latency AudioTrack. Run alone for cold numbers:
     * --tests '*Qa18FirstBeatTest.coldFirstBlocksRenderTime'.
     */
    @Test fun coldFirstBlocksRenderTime() {
        val b = bank
        val p = bandProject()
        val plan = (ScorePlaybackCompiler().compileNow(p, SR) as Outcome.Success).value
        val tRack0 = System.nanoTime(); val rack = ChannelRack.build(p, SR, 1024); val tRack = (System.nanoTime() - tRack0) / 1e6
        val tEng0 = System.nanoTime(); val e = SynthEngine(SR, 1024, b); val tEng = (System.nanoTime() - tEng0) / 1e6
        e.installRack(rack); e.setMix(MixState.from(p)); e.install(EngineProgram.prepare(plan)); e.play(0)
        val out = FloatArray(BLOCK * 2)
        val first = DoubleArray(12) { val t0 = System.nanoTime(); e.render(out, BLOCK); (System.nanoTime() - t0) / 1e6 }
        repeat(600) { e.render(out, BLOCK) }
        val warm = DoubleArray(200) { val t0 = System.nanoTime(); e.render(out, BLOCK); (System.nanoTime() - t0) / 1e6 }.sorted()
        val budget = BLOCK * 1000.0 / SR
        println("MEASURE cold start (bank=${b != null}, 3 tracks): rack build ${"%.1f".format(tRack)} ms, engine ${"%.1f".format(tEng)} ms; first 12 blocks ${first.joinToString { "%.1f".format(it) }} ms; warm median ${"%.2f".format(warm[100])} ms; budget ${"%.2f".format(budget)} ms/block; blocks over budget among the first 12: ${first.count { it > budget }}")
        assertTrue(first.all { it >= 0 })
    }

    // ------------------------------------------------------------------ idle stop versus a Play posted in the same block

    /** Sink whose write() parks the render thread until the test grants one permit per block: a real thread, stepped deterministically. */
    private class SteppedSink : dev.riffworkbench.ports.AudioSink {
        private val permits = java.util.concurrent.Semaphore(0)
        @Volatile var frames = 0L
        @Volatile var writes = 0
        @Volatile var parked = false
        override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> = Outcome.Success(SinkFormat(SR, BLOCK, LATENCY, "stepped"))
        override fun write(buffer: FloatArray, frames: Int): Int {
            parked = true
            permits.acquireUninterruptibly()
            parked = false
            this.frames += frames; writes++
            return frames
        }
        override fun presentedFrame(): Long = (frames - LATENCY).coerceAtLeast(0)
        override fun close() {}
        /** Waits until the render thread is parked in a write (or has exited). */
        fun awaitParked(host: RealtimeHost, after: Int) { val t0 = System.nanoTime(); while (host.isRunning && !(writes >= after && parked) && System.nanoTime() - t0 < 2_000_000_000L) Thread.sleep(1) }
        /** Lets the parked block through and waits for the next park (or the thread's exit). */
        fun step(host: RealtimeHost) { val before = writes; permits.release(); awaitParked(host, before + 1) }
    }

    /**
     * RealtimeHost.renderBlock drains the queue, renders, writes and then decides the idle stop (RealtimeHost.kt:174-177).
     * A Play posted while the thread is inside write() of the block that trips the idle stop is never drained: the thread
     * exits, the poller turns PLAYING into STOPPED, nothing sounds. EngineTransport.play() saw a running host and did not restart it.
     */
    @Test fun playPostedInTheBlockThatTripsTheIdleStopIsLost() = runBlocking {
        val sink = SteppedSink()
        var opened = 0
        val idleStop = 0.1
        val host = RealtimeHost({ opened++; sink }, preferredRate = SR, preferredBlock = BLOCK, idleStopSeconds = idleStop, bankProvider = { null })
        val t = EngineTransport(host, pollMillis = 10)
        val p = project(HIGHGAIN)
        t.prepare(p, plain); assertTrue(t.play() is Outcome.Success)
        sink.awaitParked(host, 1)
        repeat(4) { sink.step(host) }                 // Play applied, note sounding
        assertEquals(1L, host.snapshot().plays)
        t.stop()                                       // Pause + Seek: voices fade within 6 ms
        val fatal = (idleStop * SR / BLOCK).toInt() + 1 // idle blocks until idleBlocks * frames > idleStop * rate
        var idle = 0
        var guard = 0
        while (idle < fatal && guard++ < 10_000) {
            sink.step(host)
            assertTrue("host stopped earlier than computed", host.isRunning)
            val snap = host.snapshot()
            if (!snap.playing && !snap.countingIn && (host.stats()?.voices ?: 1) == 0) idle++
        }
        // The thread is parked in write() of the block whose idle check will stop the host. The user presses Play now.
        assertTrue(host.isRunning)
        val since = t.halts
        assertTrue(t.play(since) is Outcome.Success)
        assertEquals("play() published PLAYING", TransportStatus.PLAYING, t.state.value.status)
        sink.step(host)
        val t0 = System.nanoTime(); while (host.isRunning && System.nanoTime() - t0 < 2_000_000_000L) Thread.sleep(1)
        Thread.sleep(80)
        val status = t.state.value.status
        val plays = host.snapshot().plays
        val running = host.isRunning
        // Recovery: the next Play restarts the host.
        assertTrue(t.play(t.halts) is Outcome.Success)
        sink.awaitParked(host, sink.writes + 1); sink.step(host)
        val recovered = host.snapshot().plays
        t.shutdown(); sink.step(host)
        println("MEASURE idle-stop race: after Play in the fatal block: running=$running status=$status plays applied=$plays; after a second Play: plays applied=$recovered, sinks opened=$opened")
        assertEquals("a Play posted in the idle-stop block must still be applied (plays applied)", 2L, plays)
        assertEquals("transport must not fall back to STOPPED after the user pressed Play", TransportStatus.PLAYING, status)
    }
}
