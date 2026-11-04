package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AudioClipTest {
    private val sr = 48_000
    private fun project(offsetMicros: Long, speed: Double = 1.0): Pair<Project, PcmBuffer> {
        val m = listOf(Measure(EntityId("m0")), Measure(EntityId("m1")))
        val track = Track(EntityId("t"), "G", InstrumentDefinition(), listOf(Staff(EntityId("s"), bars = m.mapIndexed { i, mm -> Bar(EntityId("b$i"), mm.id, List(4) { v -> Voice(EntityId("v$i$v"), v) }) })))
        // Own synthetic click track: one 64-frame impulse every half second (44.1 kHz source, resampled to 48 kHz).
        val src = 44_100
        val left = FloatArray(src * 4) { if (it % (src / 2) < 64) 0.8f else 0f }
        val pcm = PcmBuffer.from(src, left, left, sr)
        val hash = AssetHash("a".repeat(64))
        val clip = AudioClip(EntityId("c"), hash, "Referenz", offsetMicros = offsetMicros)
        return Project(id = EntityId("p"), measures = m, tracks = listOf(track), tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)),
            audio = AudioArrangement(listOf(AssetDescriptor(hash, "ref.wav", "audio/wav", 0)), listOf(clip))) to pcm
    }
    private fun render(p: Project, pcm: PcmBuffer, frames: Int, speed: Double = 1.0, setup: (SynthEngine) -> Unit = {}): FloatArray {
        val plan = (ScorePlaybackCompiler().compileNow(p, sr, PlaybackOptions(speed)) as Outcome.Success).value
        val e = SynthEngine(sr, 1024)
        e.installRack(ChannelRack.build(p, sr, 1024, includeMasterFx = false)); e.setMix(MixState.from(p)); e.install(EngineProgram.prepare(plan))
        e.installClips(ClipSet.build(p, mapOf(p.audio.clips[0].asset to pcm), sr, speed))
        e.play(0); setup(e)
        val out = FloatArray(frames); val buf = FloatArray(512); var done = 0
        while (done < frames) { val k = minOf(256, frames - done); e.render(buf, k); for (i in 0 until k) out[done + i] = buf[2 * i]; done += k }
        return out
    }
    private fun firstOnset(x: FloatArray, from: Int = 0) = (from until x.size).first { abs(x[it]) > 0.05f }

    @Test fun clipIsSampleLockedToThePlanWithOffset() {
        val (p, pcm) = project(offsetMicros = 250_000)
        val out = render(p, pcm, sr * 2)
        assertEquals(12_000, firstOnset(out))            // 0.25 s offset
        assertEquals(12_000 + 24_000, firstOnset(out, 12_100)) // next impulse 0.5 s later
    }

    @Test fun loopsReplayTheSameAudioFrames() {
        val (p, pcm) = project(offsetMicros = 0)
        val out = render(p, pcm, sr * 3) { it.setLoop(24_000, 72_000) }
        // Impulses at 0, then 24000 (=loop start), 48000, then wrap at 72000 -> 24000 again => output 72000.
        assertEquals(0, firstOnset(out)); assertEquals(24_000, firstOnset(out, 100)); assertEquals(48_000, firstOnset(out, 24_100)); assertEquals(72_000, firstOnset(out, 48_100))
    }

    @Test fun otherSpeedsStaySilentUntilTimeStretchExists() {
        val (p, pcm) = project(offsetMicros = 0)
        val out = render(p, pcm, sr, speed = 0.7)
        assertTrue(out.all { abs(it) < 1e-6f })
    }

    @Test fun mutedClipsAreNotBuilt() {
        val (p, pcm) = project(offsetMicros = 0)
        val muted = p.copy(audio = p.audio.copy(clips = p.audio.clips.map { it.copy(mute = true) }))
        assertEquals(0, ClipSet.build(muted, mapOf(p.audio.clips[0].asset to pcm), sr, 1.0).voices.size)
    }
}
