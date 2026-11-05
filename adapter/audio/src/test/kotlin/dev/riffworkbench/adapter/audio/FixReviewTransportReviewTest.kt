package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FixReviewTransportReviewTest {
    private var n = 0
    private fun tid() = EntityId("r${n++}")

    /** Stop, then play again (WorkspaceViewModel.togglePlay: prepare(project, practice) + play): the trainer run restarts at its start speed. */
    @Test fun trainerRestartsAtStartSpeedAfterStop() = runBlocking {
        val m = List(2) { Measure(tid()) }
        val bars = m.map { Bar(tid(), it.id, List(4) { v -> Voice(tid(), v, if (v == 0) listOf(Beat(tid(), Fraction.ZERO, WrittenDuration(1), listOf(Note(tid(), Pitch(52), 0, 12)))) else emptyList()) }) }
        val p = Project(id = tid(), measures = m, tracks = listOf(Track(tid(), "G", InstrumentDefinition(), listOf(Staff(tid(), bars = bars)))), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)), revision = 1)
        val host = RealtimeHost({ FakeSink(latency = 0) }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, pollMillis = 1_000_000)
        val vmPractice = PracticeSettings(speed = 0.5, countInBars = 0, metronome = false, trainer = SpeedTrainer(enabled = true, startSpeed = 0.5, endSpeed = 1.0, increment = 0.25, repetitions = 1))
        transport.prepare(p, vmPractice); transport.play(); repeat(4) { host.renderBlock() }
        transport.setSpeed(0.75) // what onLoopPasses does after a pass
        transport.stop(); repeat(2) { host.renderBlock() }
        // togglePlay()
        transport.prepare(p, vmPractice); transport.play(); repeat(2) { host.renderBlock() }
        println("speed after stop + play: ${transport.currentSettings().speed}; UI (not playing) shows practice.speed=${vmPractice.speed}")
        assertEquals(0.5, transport.currentSettings().speed, 1e-9)
        transport.shutdown()
    }
}
