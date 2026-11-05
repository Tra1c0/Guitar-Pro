package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/** QA cycle 17 (DSP fuzzing): minimal, deterministic repros of the confirmed invariant violations. Expected red until fixed. */
class Qa17DspFindingsTest {
    private val sr = 48_000
    private fun fx(kind: EffectKind, vararg p: Pair<String, Double>) = Effects.create(EffectUnit(EntityId("fx-$kind"), kind, parameters = mapOf(*p)), sr, 4096)!!
    private fun noise(frames: Int, seed: Int = 7) = Noise(seed).let { n -> FloatArray(frames) { 0.5f * n.next() } }
    private fun run(e: Effect, x: FloatArray, block: Int): FloatArray {
        val y = x.copyOf(); val b = FloatArray(block); var i = 0
        while (i < y.size) { val k = minOf(block, y.size - i); System.arraycopy(y, i, b, 0, k); e.process(b, null, k); System.arraycopy(b, 0, y, i, k); i += k }
        return y
    }
    private fun maxDiff(a: FloatArray, b: FloatArray) = a.indices.maxOf { abs(a[it] - b[it]) }

    /** D6: Chorus.reset() clears the buffers but not the LFO phase, so a reset unit does not sound like a fresh one. */
    @Test fun chorusResetEqualsFreshInstance() {
        val used = fx(EffectKind.CHORUS, "rateHz" to 0.8, "depthMs" to 3.0, "mix" to 0.5)
        run(used, noise(sr), 256)                 // one second of playing moves the LFO by 0.8 cycles
        used.reset()
        val probe = noise(4096, 11)
        val d = maxDiff(run(used, probe, 256), run(fx(EffectKind.CHORUS, "rateHz" to 0.8, "depthMs" to 3.0, "mix" to 0.5), probe, 256))
        assertTrue("reset chorus differs from a fresh one by $d", d < 1e-6f)
    }

    /** D5: the compressor's gain computer runs when the in-block index is a multiple of 8, so its output depends on the block size. */
    @Test fun compressorIsBlockSizeIndependent() {
        val x = noise(sr / 2)
        val p = arrayOf("thresholdDb" to -40.0, "ratio" to 20.0, "attackMs" to 0.1, "releaseMs" to 10.0, "makeupDb" to 0.0)
        val a = run(fx(EffectKind.COMPRESSOR, *p), x, 7)
        val b = run(fx(EffectKind.COMPRESSOR, *p), x, 64)
        val d = maxDiff(a, b)
        assertTrue("block 7 vs 64 differ by $d (peak ${b.maxOf { abs(it) }})", d < 1e-4f)
    }

    /** D1: a reference clip with a non-finite pan or gain (only reachable from a file) turns the whole master into NaN. */
    @Test fun nonFiniteClipPanDoesNotPoisonTheMaster() {
        val asset = AssetHash("a".repeat(64))
        val clip = AudioClip(EntityId("clip"), asset, "ref", pan = Double.NaN)
        val track = Track(EntityId("t"), "G", sound = SoundAssignment(presetId = "rw.guitar.clean"))
        val p = Project(id = EntityId("p"), tracks = listOf(track), audio = AudioArrangement(clips = listOf(clip)))
        val pcm = PcmBuffer(sr, sr, ShortArray(2 * sr) { 1000 })
        val e = SynthEngine(sr, 1024)
        e.installRack(ChannelRack.build(p, sr, 1024)); e.setMix(MixState.from(p))
        e.installClips(ClipSet.build(p, mapOf(asset to pcm), sr, 1.0))
        e.install(EngineProgram.prepare(PlaybackPlan(0, sr, emptyList(), listOf(ScheduledNote(EntityId("n"), track.id, 0, sr.toLong(), Pitch(52), 100, 2, emptyList())))))
        e.play(0)
        val buf = FloatArray(2048); var bad = 0
        repeat(20) { e.render(buf, 1024); for (v in buf) if (!v.isFinite()) bad++ }
        assertEquals("non-finite master samples", 0, bad)
    }

    // ---------------- engine helpers ----------------
    private var ids = 0
    private fun id() = EntityId("q17-${ids++}")
    private val neutral = listOf(EffectUnit(EntityId("fx-eq"), EffectKind.EQ))
    private fun track(preset: String, volume: Double = 0.8) = Track(id(), "T",
        if (preset.startsWith("rw.keys")) InstrumentDefinition(InstrumentKind.PIANO, emptyList(), emptyList()) else InstrumentDefinition(),
        sound = SoundAssignment(presetId = preset), mix = ChannelMix(volume = volume, effects = neutral))
    /** A plan shaped like the compiler's: one route span over all notes. */
    private fun plan(notes: List<ScheduledNote>) = PlaybackPlan(0, sr, emptyList(), notes.sortedBy { it.frame },
        spans = listOf(RouteSpan(0, EntityId("m"), 0, 0, Fraction.ZERO, doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, notes.maxOf { it.endFrame }.toDouble()))))
    private fun note(frame: Long, dur: Long, midi: Int, track: Int = 0, string: Int? = null) = ScheduledNote(id(), id(), frame, frame + dur, Pitch(midi), 100, string, emptyList(), trackIndex = track)
    private fun render(p: Project, notes: List<ScheduledNote>, frames: Int, block: Int): FloatArray {
        val e = SynthEngine(sr, 1024)
        e.installRack(ChannelRack.build(p, sr, 1024, includeMasterFx = false)); e.setMix(MixState.from(p))
        e.install(EngineProgram.prepare(plan(notes))); e.play(0)
        val out = FloatArray(frames); val buf = FloatArray(2048); var i = 0
        while (i < frames) { val k = minOf(block, frames - i); e.render(buf, k); for (j in 0 until k) out[i + j] = buf[2 * j]; i += k }
        return out
    }

    /**
     * D8: when a pool is exhausted, the stolen voice is killed outright (SynthEngine.trigger: `keys[index].kill()`, likewise
     * strings, samples, drums), so its signal jumps to zero in one sample. X = 0.01 (-40 dBFS) per-sample step at the output;
     * the engine's own fast release (6-8 ms) stays far below it.
     * Reference [a] lacks the stolen note, so b - a is that voice up to t0 and only what replaces it afterwards (de-click
     * residual, the thief). Against a reference that keeps the voice, b - a would contain the voice's own waveform after any
     * removal (fade or residual); this pad's own per-sample slope is ~0.0146, above X by itself.
     */
    @Test fun stealingAVoiceDoesNotClick() {
        val p = Project(id = id(), tracks = listOf(track("rw.keys.pad"), track("rw.keys.pad", volume = 1e-3)))
        val held = List(48) { k -> note(k * 200L, 8L * sr, 48 + k % 24) }      // fills the 48 keys voices
        val t0 = (0.4 * sr).toLong()
        val thief = note(t0, sr / 2L, 60, track = 1)                              // practically inaudible track
        val a = render(p, held.drop(1), (t0 + sr / 10).toInt(), 256)            // without the voice that gets stolen
        val b = render(p, held + thief, (t0 + sr / 10).toInt(), 256)
        var step = 0f
        for (i in t0.toInt() until a.size) step = maxOf(step, abs((b[i] - a[i]) - (b[i - 1] - a[i - 1])))
        assertTrue("stolen voice removed with a step of $step (${20 * kotlin.math.log10(step)} dBFS)", step <= 0.01f)
    }

    /** D5: Channel.lastGain starts at 0 and mixDown ramps to the target over one block, so the first block fades in over its own length. */
    @Test fun firstNoteIsNotFadedInOverTheFirstBlock() {
        val p = Project(id = id(), tracks = listOf(track("rw.guitar.acoustic")))
        val n = listOf(note(0, sr.toLong(), 52, string = 2))
        val export = render(p, n, 2048, 1024)   // OfflineMixdown block
        val live = render(p, n, 2048, 64)
        val d = export.indices.maxOf { abs(export[it] - live[it]) }
        assertTrue("first 1024 frames differ by $d between block 1024 and 64", d < 1e-4f)
    }

    /** D5: StringVoice smooths release damping once per 16-frame chunk counted from each render call, so release speed depends on call sizes. */
    @Test fun stringReleaseDoesNotDependOnTheBlockSize() {
        val p = Project(id = id(), tracks = listOf(track("rw.guitar.acoustic")))
        val n = listOf(note(3000, sr / 4L, 52, string = 2))
        val a = render(p, n, sr / 2, 7); val b = render(p, n, sr / 2, 256)
        val d = a.indices.maxOf { abs(a[it] - b[it]) }
        assertTrue("release with block 7 vs 256 differs by $d (peak ${b.maxOf { abs(it) }})", d < 1e-4f)
    }

    /**
     * D4/D3: Overdrive's oversampling biquads settle into a limit cycle at the flush-to-zero quantum (~1e-24) instead of reaching
     * zero; softClip then squares values that underflow, and a silent Overdrive costs 3-4x the CPU of a playing one.
     */
    @Test fun overdriveDecaysToExactSilence() {
        val od = fx(EffectKind.OVERDRIVE)
        run(od, noise(sr), 256)
        val l = FloatArray(256); var last = 0f
        repeat(2000) { l.fill(0f); od.process(l, null, 256); last = l.maxOf { abs(it) } }
        assertEquals("output after 10 s of silence", 0f, last)
    }
}
