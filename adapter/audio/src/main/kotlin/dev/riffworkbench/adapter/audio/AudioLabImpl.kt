package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*
import dev.riffworkbench.synth.OfflineMixdown
import dev.riffworkbench.synth.SampleBank
import dev.riffworkbench.synth.Wav
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Own test material for diagnostics and blind comparisons (no third-party music). */
object AudioTestMaterial {
    private class Ids { var n = 0; fun next() = EntityId("lab-${n++}") }

    /**
     * Eight guitar tracks (Drop A, high gain and crunch), 32 bars at 160 BPM: palm-muted 16th chugs, power chords,
     * 32nd-note repetitions, bends with vibrato and slides on every track at once. Harder than a real mix on purpose.
     */
    fun stressProject(tracks: Int = 8, bars: Int = 32): Project {
        val ids = Ids()
        val g = InstrumentDefinition()
        val measures = List(bars) { Measure(ids.next()) }
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        fun note(s: Int, f: Int, vararg a: Articulation, v: Int = 100) = Note(ids.next(), Pitch(g.pitch(s, f)), s, f, velocity = v, articulations = a.toList())
        fun bar(t: Int, i: Int): List<Beat> {
            val out = ArrayList<Beat>()
            when ((i + t) % 4) {
                0 -> for (k in 0 until 16) out += Beat(ids.next(), Fraction.of(k.toLong(), 16), WrittenDuration(16),
                    if (k % 4 == 3) listOf(note(0, 3), note(1, 3), note(2, 3)) else listOf(note(0, 0, pm, v = 90 + (k % 2) * 20)))
                1 -> for (k in 0 until 32) out += Beat(ids.next(), Fraction.of(k.toLong(), 32), WrittenDuration(32), listOf(note(1, 2 + (k / 8), pm)))
                2 -> {
                    val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 3), 200), PitchCurvePoint(Fraction.ONE, 200)))
                    out += Beat(ids.next(), Fraction.ZERO, WrittenDuration(4), listOf(note(4, 7, bend, Articulation.Vibrato(40, 5.5))))
                    out += Beat(ids.next(), Fraction.of(1, 4), WrittenDuration(8), listOf(note(3, 5, Articulation.Slide(SlideKind.SHIFT))))
                    out += Beat(ids.next(), Fraction.of(3, 8), WrittenDuration(8), listOf(note(3, 7)))
                    out += Beat(ids.next(), Fraction.of(1, 2), WrittenDuration(2), listOf(note(0, 5), note(1, 7), note(2, 7), note(3, 6)))
                }
                else -> for (k in 0 until 8) out += Beat(ids.next(), Fraction.of(k.toLong(), 8), WrittenDuration(8), listOf(note(0, k % 3), note(1, 2 + k % 3), note(2, 2 + k % 3), note(3, 1 + k % 3, v = 80)))
            }
            return out
        }
        val list = List(tracks) { t ->
            val staff = Staff(ids.next(), bars = measures.mapIndexed { i, m -> Bar(ids.next(), m.id, List(4) { v -> Voice(ids.next(), v, if (v == 0) bar(t, i) else emptyList()) }) })
            Track(ids.next(), "Stress ${t + 1}", g, listOf(staff), SoundAssignment(presetId = if (t % 2 == 0) "rw.guitar.highgain" else "rw.guitar.crunch"),
                ChannelMix(volume = 0.5, pan = (t - (tracks - 1) / 2.0) / tracks))
        }
        return Project(id = ids.next(), metadata = ProjectMetadata("Audio-Stresstest"), measures = measures, tracks = list, tempo = listOf(TempoChange(ScorePosition(measures[0].id), 160.0)))
    }

    /** Comparison riff for blind tests: chugs, a chord, a bend, a slide and a sustained chord (about 8 s). */
    fun comparisonRiff(presetId: String): Project {
        val ids = Ids()
        val g = InstrumentDefinition()
        val m = List(4) { Measure(ids.next()) }
        val pm = Articulation.Mark(NoteMark.PALM_MUTE)
        fun n(s: Int, f: Int, vararg a: Articulation) = Note(ids.next(), Pitch(g.pitch(s, f)), s, f, articulations = a.toList())
        fun b(o: Fraction, d: Int, vararg notes: Note) = Beat(ids.next(), o, WrittenDuration(d), notes.toList())
        val bars = listOf(
            (0 until 16).map { k -> if (k % 4 == 3) b(Fraction.of(k.toLong(), 16), 16, n(0, 3), n(1, 3), n(2, 3)) else b(Fraction.of(k.toLong(), 16), 16, n(0, 0, pm)) },
            listOf(b(Fraction.ZERO, 4, n(4, 7, Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.of(1, 3), 200), PitchCurvePoint(Fraction.ONE, 200))), Articulation.Vibrato(40, 5.5))),
                b(Fraction.of(1, 4), 8, n(3, 5, Articulation.Slide(SlideKind.SHIFT))), b(Fraction.of(3, 8), 8, n(3, 7)), b(Fraction.of(1, 2), 2, n(3, 7, Articulation.Vibrato(50, 5.0)))),
            (0 until 8).map { k -> b(Fraction.of(k.toLong(), 8), 8, n(1, 2 + k % 2), n(2, 4 + k % 2), n(3, 4 + k % 2)) },
            listOf(b(Fraction.ZERO, 1, n(0, 0), n(1, 2), n(2, 2), n(3, 1), n(4, 0), n(5, 0))),
        )
        val staff = Staff(ids.next(), bars = m.mapIndexed { i, mm -> Bar(ids.next(), mm.id, List(4) { v -> Voice(ids.next(), v, if (v == 0) bars[i] else emptyList()) }) })
        return Project(id = ids.next(), metadata = ProjectMetadata("Vergleich"), measures = m, tracks = listOf(Track(ids.next(), "Gitarre", g, listOf(staff), SoundAssignment(presetId = presetId))),
            tempo = listOf(TempoChange(ScorePosition(m[0].id), 120.0)))
    }
}

/** [AudioLab] over the live transport and the offline renderer, with device probes and a PCM output from the platform. */
class EngineAudioLab(
    private val transport: EngineTransport,
    private val bankProvider: () -> SampleBank?,
    private val probes: SystemProbes?,
    private val pcm: PcmOutput?,
) : AudioLab {
    override fun stats(): AudioStats? = transport.stats()?.let { s ->
        AudioStats(s.sampleRate, s.blockFrames, s.blocks, s.budgetMicros, s.renderMedianMicros, s.renderP99Micros, s.renderMaxMicros, s.overruns, s.underruns,
            s.outputLatencyMs, s.noteOnLatencyMs, s.voices, s.hasSamples, s.deviceName)
    }

    override suspend fun stressTest(seconds: Int, onSample: (StressSample) -> Unit): Outcome<StressReport> {
        val project = AudioTestMaterial.stressProject()
        val settings = PracticeSettings(speed = 1.0, countInBars = 0, metronome = false)
        transport.prepare(project, settings).let { if (it is Outcome.Failure) return it }
        transport.play().let { if (it is Outcome.Failure) return it }
        val samples = ArrayList<StressSample>()
        val start = stats()
        try {
            for (sec in 1..seconds.coerceIn(5, 3600)) {
                delay(1000)
                if (transport.state.value.status != TransportStatus.PLAYING) {
                    transport.seek(PlaybackOccurrence(ScorePosition(project.measures[0].id), 0, 0)); transport.play()
                }
                val sample = StressSample(sec, stats(), probes?.memoryMb(), probes?.thermal(), probes?.cpuMhz().orEmpty())
                samples += sample; onSample(sample)
            }
        } finally { transport.stop() }
        return Outcome.Success(StressReport(samples.size, samples, report(start, samples)))
    }

    private fun report(start: AudioStats?, samples: List<StressSample>): String {
        val last = samples.lastOrNull()?.stats
        val p99 = samples.mapNotNull { it.stats?.renderP99Micros }
        val med = samples.mapNotNull { it.stats?.renderMedianMicros }
        val mem = samples.mapNotNull { it.memoryMb }
        return buildString {
            appendLine("Audio-Stresstest · ${samples.size} s · 8 Gitarrenspuren (High Gain/Crunch, PM, Akkorde, 32tel, Bends, Slides)")
            last?.let { s ->
                appendLine("Gerät: ${s.deviceName} · ${s.sampleRate} Hz · Block ${s.blockFrames} Frames (Budget ${"%.0f".format(s.budgetMicros)} µs) · Samples ${if (s.hasSamples) "an" else "aus"}")
                appendLine("Renderzeit pro Block: Median ${"%.0f".format(med.sorted().getOrElse(med.size / 2) { 0.0 })} µs, P99 max ${"%.0f".format(p99.maxOrNull() ?: 0.0)} µs, Maximum ${"%.0f".format(s.renderMaxMicros)} µs")
                appendLine("Auslastung (Median/Budget): ${"%.0f".format(100 * (med.sorted().getOrElse(med.size / 2) { 0.0 }) / s.budgetMicros)} %")
                appendLine("Überläufe (Block länger als Budget): ${s.overruns - (start?.overruns ?: 0)} · Underruns (Gerät): ${s.underruns - (start?.underruns ?: 0)}")
                appendLine("Ausgabelatenz: ${s.outputLatencyMs?.let { "%.1f ms".format(it) } ?: "keine Zeitstempel"} · Note-on zuletzt: ${s.noteOnLatencyMs?.let { "%.1f ms".format(it) } ?: "nicht gemessen"}")
            }
            if (mem.isNotEmpty()) appendLine("Speicher: Start ${"%.0f".format(mem.first())} MB, Spitze ${"%.0f".format(mem.max())} MB, Ende ${"%.0f".format(mem.last())} MB")
            appendLine("Thermik: ${samples.firstOrNull()?.thermal ?: "–"} → ${samples.lastOrNull()?.thermal ?: "–"}")
            val f0 = samples.firstOrNull()?.cpuMhz.orEmpty(); val f1 = samples.lastOrNull()?.cpuMhz.orEmpty()
            if (f0.isNotEmpty()) appendLine("CPU-Takt (MHz je Kern): ${f0.joinToString("/")} → ${f1.joinToString("/")}")
        }
    }

    override suspend fun renderComparison(toneId: String, sampled: Boolean): Outcome<FloatArray> = withContext(Dispatchers.Default) {
        val preset = when (toneId) { "clean" -> "rw.guitar.clean"; "crunch" -> "rw.guitar.crunch"; else -> "rw.guitar.highgain" }
        val bank = if (sampled) bankProvider() ?: return@withContext Outcome.Failure(FailureCode.UNAVAILABLE, "Sample-Bank nicht geladen", contractId = "C-AUDIO") else null
        val out = ByteArrayOutputStream()
        when (val r = OfflineMixdown(bank = bank).render(AudioTestMaterial.comparisonRiff(preset), RenderOptions(sampleRate = 48_000, bitDepth = 32, tailSeconds = 1.5), { b, o, l -> out.write(b, o, l) })) {
            is Outcome.Failure -> return@withContext r
            is Outcome.Success -> Unit
        }
        val d = Wav.decode(out.toByteArray())
        val frames = d.frames
        var sum = 0.0
        for (i in 0 until frames) sum += (d.left[i] * d.left[i] + d.right[i] * d.right[i]) / 2.0
        // Loudness match: every render is scaled to the same RMS (-20 dBFS), so the louder one cannot win the comparison.
        val gain = (0.1 / sqrt(sum / frames.coerceAtLeast(1) + 1e-12)).toFloat()
        Outcome.Success(FloatArray(frames * 2) { i -> (if (i % 2 == 0) d.left[i / 2] else d.right[i / 2]) * gain })
    }

    override fun playPcm(stereo: FloatArray, sampleRate: Int) { pcm?.play(stereo, sampleRate) }
    override fun stopPcm() { pcm?.stop() }
}
