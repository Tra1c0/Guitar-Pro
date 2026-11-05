package dev.riffworkbench.adapter.audio

import dev.riffworkbench.adapter.audio.ClipLoader
import dev.riffworkbench.adapter.audio.EngineTransport
import dev.riffworkbench.adapter.audio.RealtimeHost
import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.synth.PcmBuffer
import java.lang.ref.WeakReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * QA cycle 17: the process-wide EngineTransport keeps the decoded PCM of reference clips of projects that are no longer
 * open (clipCache, EngineTransport.kt:47/162-176). Eviction runs only when the cache holds more than 8 entries and only
 * while a project *with* clips is prepared on a running host; a project without clips returns early (line 164) and
 * never evicts. Real clips are capped at 600 s: 600 × 48 000 × 2 ch × 2 B = 115.2 MB per entry, i.e. up to ~920 MB of
 * stale PCM after eight open/play/close cycles — far beyond an Android app heap (OOM long before the bound is reached).
 */
class Qa17ClipCacheFindingsTest {
    private var n = 0
    private val ids = IdSource { EntityId("q17c-${n++}") }
    private class Sink : AudioSink {
        var frames = 0L
        override fun open(preferredSampleRate: Int, preferredBlockFrames: Int): Outcome<SinkFormat> = Outcome.Success(SinkFormat(48_000, 256, 0, "fake"))
        override fun write(buffer: FloatArray, frames: Int): Int { this.frames += frames; return frames }
        override fun presentedFrame(): Long = frames
        override fun close() {}
    }
    private fun hash(i: Int) = AssetHash(i.toString(16).padStart(64, '0'))
    private fun projectWithClip(i: Int?): Project {
        val m = Measure(ids.next())
        val track = Track(ids.next(), "G", InstrumentDefinition(), listOf(Staff(ids.next(), bars = listOf(Bar(ids.next(), m.id, List(4) { v -> Voice(ids.next(), v) })))))
        val p = Project(id = ids.next(), measures = listOf(m), tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m.id), 120.0)))
        return if (i == null) p else p.copy(audio = p.audio.copy(clips = listOf(AudioClip(ids.next(), hash(i), "ref$i"))))
    }
    private fun gc() { repeat(6) { System.gc(); Thread.sleep(50) } }

    /** Open → play → stop → close for 8 projects with one reference clip each, then a project without audio. */
    @Test fun closedProjectsDoNotKeepTheirDecodedClips() = runBlocking {
        val frames = 1_000_000 // 4 MB per clip here; 115 MB for a real 10-minute clip
        val loaded = ArrayList<WeakReference<PcmBuffer>>()
        val loader = ClipLoader { _, rate -> Outcome.Success(PcmBuffer(rate, frames, ShortArray(2 * frames)).also { loaded += WeakReference(it) }) }
        val host = RealtimeHost({ Sink() }, threadStarter = { _, _ -> Thread {} }, idleStopSeconds = 1000.0)
        val transport = EngineTransport(host, clipLoader = loader, pollMillis = 1_000_000)
        try {
            for (i in 0 until 8) {
                val p = projectWithClip(i)
                assertTrue(transport.prepare(p, PracticeSettings(1.0, 0, false)) is Outcome.Success)
                assertTrue(transport.play() is Outcome.Success)
                repeat(4) { host.renderBlock() }
                transport.stop(); repeat(2) { host.renderBlock() }
            }
            // The user now works on a project without reference audio (and plays it).
            val plain = projectWithClip(null)
            transport.prepare(plain, PracticeSettings(1.0, 0, false)); transport.play(); repeat(4) { host.renderBlock() }; transport.stop(); repeat(4) { host.renderBlock() }
            gc()
            val alive = loaded.count { it.get() != null }
            println("QA17 clip cache: ${loaded.size} clips decoded, $alive still reachable after closing their projects (${alive * 4L * frames / 1_000_000} MB here, ${"%.0f".format(alive * 115.2)} MB with 10-minute clips)")
            // Deterministic check on the cache itself (whether System.gc() clears the weak references varies between JVMs/CI).
            val cache = EngineTransport::class.java.getDeclaredField("clipCache").apply { isAccessible = true }.get(transport) as Map<*, *>
            assertEquals("PCM of clips whose project is closed must not stay cached", 0, cache.size)
        } finally { transport.shutdown() }
    }
}
