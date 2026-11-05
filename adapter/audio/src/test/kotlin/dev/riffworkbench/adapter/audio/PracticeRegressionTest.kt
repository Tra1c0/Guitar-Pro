package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Practice-mode defects found by scale testing (QA cycle 5): stale loop after structural edits, trainer reset, redundant recompiles. */
class PracticeRegressionTest {
    private var n = 0
    private fun sid() = EntityId("s${n++}")
    private fun tid() = EntityId("t${n++}")
    
    private fun bar(m: Measure) = Bar(sid(), m.id, List(4) { v -> Voice(sid(), v, if (v == 0) listOf(Beat(sid(), Fraction.ZERO, WrittenDuration(1), listOf(Note(sid(), Pitch(52), 0, 12)))) else emptyList()) })
    @Test fun loopAfterInsertingABarBeforeIt() = runBlocking {
        val m = List(6) { Measure(sid()) }
        val track = Track(sid(), "G", InstrumentDefinition(), listOf(Staff(sid(), bars = m.map { bar(it) })))
        val p = Project(id = sid(), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)), revision = 1)
        val host = RealtimeHost({ FakeSink(latency = 0) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 1_000_000)
        // as WorkspaceViewModel.loopSelection: bars 3..4 (indices 2..3), first route occurrence, offset 0
        val loop = LoopRange(PlaybackOccurrence(ScorePosition(m[2].id), 1, 2), PlaybackOccurrence(ScorePosition(m[3].id), 1, 3))
        var settings = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false, loop = loop)
        transport.prepare(p, settings)
        transport.play()
        transport.setLoop(loop)
        repeat(10) { host.renderBlock() }
        println("BENCH before edit: frame=${host.snapshot().planFrame} transportLoop=${transport.state.value.loop != null}")
        // user inserts a bar before bar 1 while playing; ViewModel.publish() calls prepare with the unchanged practice.loop
        val m0 = Measure(sid())
        val p2 = p.copy(revision = 2, measures = listOf(m0) + m, tracks = listOf(track.copy(staves = listOf(track.staves[0].copy(bars = listOf(bar(m0)) + track.staves[0].bars)))),
            tempo = listOf(TempoChange(ScorePosition(m0.id), 120.0)))
        val r = transport.prepare(p2, settings)
        repeat(4) { host.renderBlock() }
        println("BENCH prepare after edit: $r; transport.state.loop still set=${transport.state.value.loop != null}")
        transport.pause(); host.renderBlock()
        transport.play()
        repeat(1600) { host.renderBlock() } // 409600 frames = >4 bars at 96000 frames/bar
        val s = host.snapshot()
        assertTrue("loop lost after the edit: frame ${s.planFrame}, loops ${s.loopsCompleted}", s.loopsCompleted > 0 && s.planFrame in 288_000L until 480_000L)
        assertNotNull(transport.state.value.loop)
    }
    
    @Test fun prepareFromViewModelResetsTrainerSpeed() = runBlocking {
        val m = List(2) { Measure(tid()) }
        val bars = m.map { Bar(tid(), it.id, List(4) { v -> Voice(tid(), v, if (v == 0) listOf(Beat(tid(), Fraction.ZERO, WrittenDuration(1), listOf(Note(tid(), Pitch(52), 0, 12)))) else emptyList()) }) }
        val p = Project(id = tid(), measures = m, tracks = listOf(Track(tid(), "G", InstrumentDefinition(), listOf(Staff(tid(), bars = bars)))), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)), revision = 1)
        val host = RealtimeHost({ FakeSink(latency = 0) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 5)
        val loop = LoopRange(PlaybackOccurrence(ScorePosition(m[0].id), 1, 0), PlaybackOccurrence(ScorePosition(m[0].id), 1, 0))
        // what WorkspaceViewModel holds in practice.value (setTrainer sets speed = startSpeed)
        val vmPractice = PracticeSettings(speed = 0.5, countInBars = 0, metronome = false, loop = loop, trainer = SpeedTrainer(enabled = true, startSpeed = 0.5, endSpeed = 1.0, increment = 0.25, repetitions = 1))
        transport.prepare(p, vmPractice); transport.play(); transport.setLoop(loop)
        repeat(2) { repeat(800) { host.renderBlock() }; Thread.sleep(300) }
        println("BENCH after 2 loop passes: transport speed=${transport.state.value.speed}, loops=${host.snapshot().loopsCompleted}, VM practice.speed still ${vmPractice.speed}")
        // user taps a note / moves the cursor while practising -> WorkspaceViewModel.publish() -> transport.prepare(project, practice.value)
        transport.prepare(p, vmPractice)
        repeat(4) { host.renderBlock() }; Thread.sleep(300)
        assertTrue("trainer speed reset by a cursor tap", transport.currentSettings().speed > 0.5)
    }

    @Test fun anUnchangedPrepareWhilePlayingDoesNotRecompile() = runBlocking {
        val m = List(2) { Measure(tid()) }
        val bars = m.map { Bar(tid(), it.id, List(4) { v -> Voice(tid(), v, if (v == 0) listOf(Beat(tid(), Fraction.ZERO, WrittenDuration(1), listOf(Note(tid(), Pitch(52), 0, 12)))) else emptyList()) }) }
        val p = Project(id = tid(), measures = m, tracks = listOf(Track(tid(), "G", InstrumentDefinition(), listOf(Staff(tid(), bars = bars)))), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)), revision = 1)
        val host = RealtimeHost({ FakeSink(latency = 0) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 1_000_000)
        val settings = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)
        transport.prepare(p, settings); transport.play(); repeat(4) { host.renderBlock() }
        transport.prepare(p, settings)
        val queue = RealtimeHost::class.java.getDeclaredField("commands").apply { isAccessible = true }.get(host) as java.util.Queue<*>
        assertTrue("a cursor move recompiled and reposted: $queue", queue.none { it is HostCommand.Reprogram || it is HostCommand.Install })
        transport.shutdown()
    }
}
