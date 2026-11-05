package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.SinkFormat
import dev.riffworkbench.synth.PcmBuffer
import dev.riffworkbench.synth.SampleBank
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA cycle 18 fixes for the playback start (D5-1 safety net, D3-1 restart path, D3-4 engine swap, D3-2 warm-up).
 * Companion to [Qa18D5PlayAfterEndTest] (the red case) and [Qa18FirstBeatTest] (onset measurements).
 */
class Qa18StartFixesTest {
    private var n = 0
    private fun id() = EntityId("q18f-${n++}")
    private val plain = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)
    private val hash = AssetHash("c".repeat(64))

    private fun project(bars: Int, withClip: Boolean = false, presetId: String? = null): Project {
        val m = List(bars) { Measure(id()) }
        val beats = listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(InstrumentDefinition().pitch(0, 0)), 0, 0))))
        val track = Track(id(), "G", InstrumentDefinition(), listOf(Staff(id(), bars = m.mapIndexed { i, b -> Bar(id(), b.id, List(4) { v -> Voice(id(), v, if (v == 0 && i == 0) beats else emptyList()) }) })),
            sound = SoundAssignment(presetId = presetId))
        val p = Project(id = id(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
        return if (withClip) p.copy(audio = p.audio.copy(clips = listOf(AudioClip(id(), hash, "ref")))) else p
    }
    private fun manualHost(sink: FakeSink, idleStop: Double = 1000.0, bank: SampleBank? = null) = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = idleStop, bankProvider = { bank })
    private fun awaitStatus(t: EngineTransport, status: TransportStatus, what: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (t.state.value.status != status) { Thread.sleep(5); if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for $status: $what (is ${t.state.value.status})") }
    }

    // ------------------------------------------------------------------ D5-1 safety net

    /**
     * The poller publishes the position only while PLAYING, so after a natural end the published frame is the one of
     * the last PLAYING poll - up to a poll interval before the end. Without a seek since, Play must still start at the top.
     */
    @Test fun afterANaturalEndWithoutASeekPlayStartsAtTheTop() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        t.prepare(p, plain); t.play(); awaitStatus(t, TransportStatus.PLAYING, "play")
        val total = 4 * 2 * 48_000L                                    // 4 bars of 4/4 at 120 bpm = 8 s
        // Render to one block short of the end, let the poller publish that position, then finish.
        var blocks = 0
        while (sink.frames < total - 256 && blocks++ < 20_000) host.renderBlock()
        Thread.sleep(60)
        val before = t.state.value.sample!!.frame
        assertTrue("precondition: published frame $before lies before the end $total", before < total)
        blocks = 0
        while (!host.snapshot().ended && blocks++ < 100) host.renderBlock()
        awaitStatus(t, TransportStatus.STOPPED, "natural end")
        assertTrue("precondition: the end left the published frame stale ($before)", t.state.value.sample!!.frame < total)
        repeat(3) { host.renderBlock() }                              // idle render thread keeps reporting `ended`
        t.play()
        assertEquals("Play after a natural end without a seek restarts at bar 1", p.measures[0].id, t.state.value.position?.position?.measureId)
        assertEquals(0L, t.state.value.sample?.frame)
        t.shutdown()
    }

    /** Edits after the end (prepare/Reprogram) do not count as repositioning; a seek does. */
    @Test fun endFlagSurvivesAPrepareAndIsClearedByASeek() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink)
        val t = EngineTransport(host, pollMillis = 5)
        val p = project(4)
        t.prepare(p, plain); t.play(); awaitStatus(t, TransportStatus.PLAYING, "play")
        var blocks = 0
        while (!host.snapshot().ended && blocks++ < 20_000) host.renderBlock()
        awaitStatus(t, TransportStatus.STOPPED, "natural end")
        t.prepare(p.copy(revision = p.revision + 1), plain)             // an edit: Reprogram while stopped
        host.renderBlock()
        t.play()
        assertEquals("edit after the end: still from the top", p.measures[0].id, t.state.value.position?.position?.measureId)
        blocks = 0
        while (!host.snapshot().ended && blocks++ < 20_000) host.renderBlock()
        awaitStatus(t, TransportStatus.STOPPED, "second natural end")
        t.seek(PlaybackOccurrence(ScorePosition(p.measures[1].id), 1, 1))
        t.play()
        assertEquals("seek after the end wins", p.measures[1].id, t.state.value.position?.position?.measureId)
        t.shutdown()
    }

    // ------------------------------------------------------------------ D3-1: post() handshake and restart path

    /** post() tells whether a live thread drains the command; start() replays state commands but drops a dead session's Plays. */
    @Test fun hostReportsADeadQueueAndDropsStalePlaysOnRestart() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink, idleStop = 0.05)
        assertTrue(host.start() is Outcome.Success)
        assertTrue("running host drains", host.post(HostCommand.Loop(-1, -1)))
        var guard = 0
        while (host.isRunning && guard++ < 10_000) host.renderBlock()
        assertFalse(host.isRunning)
        assertFalse("a stopped host does not drain", host.post(HostCommand.Play(0, 0, LongArray(0))))
        host.post(HostCommand.Seek(1234))
        val playsBefore = host.snapshot().plays
        assertTrue(host.start() is Outcome.Success)
        assertEquals("the stale Play is dropped and counted as consumed", playsBefore + 1, host.snapshot().plays)
        assertEquals(1L, host.droppedPlays)
        host.renderBlock()
        assertFalse("the dropped Play did not start playback", host.snapshot().playing)
        assertEquals("the Seek was replayed", 1234L, host.snapshot().planFrame)
        host.stop()
    }

    /**
     * play() read a running host, then suspended in installLocked (clip decode) while the host idled out: its Play goes into
     * a dead session's queue, post() returns false, and play() reopens the host and posts the Play again - exactly once.
     * This is the control-thread side of the idle-stop race (the render-thread side is Qa18FirstBeatTest.playPostedInTheBlock...).
     */
    @Test fun playWhoseHostDiesWhileItCompilesRestartsAndPostsOnce() = runBlocking {
        val sink = FakeSink(latency = 0)
        var opened = 0
        val host = RealtimeHost({ opened++; sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 0.05)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val loader = ClipLoader { _, rate -> entered.complete(Unit); gate.await(); Outcome.Success(PcmBuffer(rate, 4800, ShortArray(9600))) }
        val t = EngineTransport(host, clipLoader = loader, pollMillis = 5)
        val p = project(4, withClip = true)
        t.prepare(p, plain)                                              // host closed: no plan yet
        t.preview(p, p.tracks[0].id, p.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0])   // opens the host
        assertEquals(1, opened)
        repeat(2) { host.renderBlock() }
        val play = async(Dispatchers.Default) { t.play() }
        withTimeout(5000) { entered.await() }                           // play() is decoding inside installLocked
        var guard = 0
        while (host.isRunning && guard++ < 10_000) host.renderBlock()   // ...and the host idles out meanwhile
        assertFalse(host.isRunning)
        gate.complete(Unit)
        assertTrue(play.await() is Outcome.Success)
        assertTrue("play() reopened the host", host.isRunning)
        assertEquals("one new sink", 2, opened)
        assertEquals(TransportStatus.PLAYING, t.state.value.status)
        assertEquals("the Play of the dead session was dropped", 1L, host.droppedPlays)
        host.renderBlock()
        val snap = host.snapshot()
        assertTrue("the engine plays", snap.playing)
        assertEquals("posted and consumed Plays agree (dropped + applied)", 2L, snap.plays)
        repeat(20) { host.renderBlock() }
        Thread.sleep(60)
        assertEquals("the poller follows the new session", TransportStatus.PLAYING, t.state.value.status)
        assertTrue("position advances", t.state.value.sample!!.frame > 0)
        t.shutdown()
    }

    /** A stop() during the restart wins: no Play is re-posted and the status stays STOPPED. */
    @Test fun stopDuringTheRestartWins() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 0.05)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val loader = ClipLoader { _, rate -> entered.complete(Unit); gate.await(); Outcome.Success(PcmBuffer(rate, 4800, ShortArray(9600))) }
        val t = EngineTransport(host, clipLoader = loader, pollMillis = 5)
        val p = project(4, withClip = true)
        t.prepare(p, plain)
        t.preview(p, p.tracks[0].id, p.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0])
        val since = t.halts
        val play = async(Dispatchers.Default) { t.play(since) }
        withTimeout(5000) { entered.await() }
        var guard = 0
        while (host.isRunning && guard++ < 10_000) host.renderBlock()
        t.stop()                                                          // user presses Stop while play() is still decoding
        gate.complete(Unit)
        assertTrue(play.await() is Outcome.Success)
        Thread.sleep(40)
        assertEquals(TransportStatus.STOPPED, t.state.value.status)
        if (host.isRunning) { host.renderBlock(); assertFalse("no Play was applied", host.snapshot().playing) }
        t.shutdown()
    }

    // ------------------------------------------------------------------ D3-2: warm-up does not change the output

    private val bankFile = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.firstOrNull { it.isFile }

    private fun bandProject(): Project {
        val g = project(2, presetId = "rw.guitar.highgain")
        val clean = g.tracks[0].copy(id = id(), name = "Clean", sound = SoundAssignment(presetId = "rw.guitar.clean"))
        val bass = Track(id(), "Bass", InstrumentDefinition(kind = InstrumentKind.BASS, tuning = listOf(28, 33, 38, 43), capoByString = List(4) { 0 }, midiProgram = 33),
            listOf(Staff(id(), bars = g.measures.map { m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(33), 1, 5)))) else emptyList()) }) })))
        val drums = Track(id(), "Drums", InstrumentDefinition(kind = InstrumentKind.PERCUSSION, tuning = emptyList(), capoByString = emptyList(), midiProgram = 0),
            listOf(Staff(id(), setOf(NotationKind.PERCUSSION), g.measures.map { m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, if (v == 0) listOf(Beat(id(), Fraction.ZERO, WrittenDuration(4), listOf(Note(id(), Pitch(38), null, null)))) else emptyList()) }) })))
        return g.copy(tracks = g.tracks + clean + bass + drums)
    }

    private fun renderLive(p: Project, bank: SampleBank?, warmUpBlocks: Int): Pair<FloatArray, Int> = runBlocking {
        val sink = FakeSink(latency = 0).also { it.capture = true }
        val host = manualHost(sink, bank = bank)
        val t = EngineTransport(host, bankProvider = { bank }, pollMillis = 1_000_000, warmUpBlocks = warmUpBlocks)
        t.prepare(p, plain); t.play()
        repeat(200) { host.renderBlock() }                           // 51 200 frames incl. the first note
        t.preview(p, p.tracks[0].id, p.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0])
        repeat(50) { host.renderBlock() }
        val out = FloatArray(sink.captured.size) { sink.captured[it] }
        val warmUps = t.warmUps
        t.shutdown()
        out to warmUps
    }

    @Test fun warmUpRunsOncePerHostStartAndLeavesTheOutputBitIdentical() {
        val bank = bankFile?.let { f -> f.inputStream().buffered(1 shl 16).use { i -> SampleBank.read { b, o, l -> i.read(b, o, l) } } }
        val p = bandProject()
        val (cold, noWarm) = renderLive(p, bank, 0)
        val (warm, warmUps) = renderLive(p, bank, EngineTransport.WARM_UP_BLOCKS)
        assertEquals("no warm-up when disabled", 0, noWarm)
        assertEquals("one warm-up per host start and rack signature (play + preview share it)", 1, warmUps)
        assertEquals(cold.size, warm.size)
        for (i in cold.indices) if (cold[i] != warm[i]) fail("output differs at frame $i: ${cold[i]} vs ${warm[i]} (bank=${bank != null})")
        assertTrue("the render carries sound", cold.any { it != 0f })
    }

    /** Warm-up happens once per host session; a restarted host warms up again, an unchanged rack within a session does not. */
    @Test fun warmUpRepeatsOnlyForANewHostSessionOrRack() = runBlocking {
        val sink = FakeSink(latency = 0)
        val host = manualHost(sink, idleStop = 0.05)
        val t = EngineTransport(host, pollMillis = 5)
        val p = project(2)
        t.prepare(p, plain); t.play(); repeat(4) { host.renderBlock() }
        assertEquals(1, t.warmUps)
        t.stop(); repeat(2) { host.renderBlock() }
        t.play(); repeat(4) { host.renderBlock() }
        assertEquals("same session, same rack", 1, t.warmUps)
        t.prepare(p.copy(revision = p.revision + 1, tracks = p.tracks.map { it.copy(sound = SoundAssignment(presetId = "rw.guitar.clean")) }), plain)
        assertEquals("new rack signature", 2, t.warmUps)
        t.pause()
        var guard = 0
        while (host.isRunning && guard++ < 10_000) host.renderBlock()
        Thread.sleep(40)
        t.play(); repeat(4) { host.renderBlock() }
        assertEquals("new host session", 3, t.warmUps)
        t.shutdown()
    }

    // ------------------------------------------------------------------ D3-4: engine swap

    /** refreshEngine() swaps only when the bank state changed, never while playing (the transport guards), and the rack is re-installed. */
    @Test fun bankSwapIsPostedOnceAndTheNewEnginePlays() = runBlocking {
        var loaded: SampleBank? = null
        val sink = FakeSink(latency = 0).also { it.capture = true }
        val host = RealtimeHost({ sink }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0, bankProvider = { loaded })
        val t = EngineTransport(host, pollMillis = 5)
        val p = project(2, presetId = "rw.guitar.highgain")
        t.prepare(p, plain); t.play(); repeat(4) { host.renderBlock() }
        assertFalse(host.refreshEngine())                              // nothing changed
        loaded = SampleBank.read(bankFile?.readBytes() ?: return@runBlocking)
        t.stop(); repeat(2) { host.renderBlock() }
        assertFalse("releasing voices of the old engine are not cut by a swap", host.refreshEngine())
        var guard = 0
        while ((host.stats()?.voices ?: 0) > 0 && guard++ < 10_000) host.renderBlock()
        val before = sink.captured.size
        t.play()
        repeat(40) { host.renderBlock() }
        assertEquals("engine with samples", true, host.stats()?.hasSamples)
        assertTrue("the swapped engine renders the note", (before until sink.captured.size).any { sink.captured[it] != 0f })
        assertFalse("no second swap for the same bank state", host.refreshEngine())
        Thread.sleep(40)
        assertEquals(TransportStatus.PLAYING, t.state.value.status)
        t.shutdown()
    }

    /** Sanity for the sink format used above. */
    @Test fun fakeSinkFormat() { assertEquals(SinkFormat(48_000, 256, 0, "fake"), (FakeSink(latency = 0).open(48_000, 256) as Outcome.Success).value) }
}
