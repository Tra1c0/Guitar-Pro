package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renders an own demo riff (no third-party material) to build/reports/audio/riff-demo.wav for listening checks.
 * Writing the file is not a passed listening test; it makes the sound reviewable.
 */
class DemoRiffRender {
    private var n = 0
    private fun id() = EntityId("demo${n++}")
    private val guitar = InstrumentDefinition()
    private val bass = InstrumentDefinition(InstrumentKind.BASS, listOf(28, 33, 38, 43), List(4) { 0 }, midiProgram = 34)
    private fun f(a: Long, b: Long) = Fraction.of(a, b)
    private fun g(s: Int, fr: Int, vararg a: Articulation) = Note(id(), Pitch(guitar.pitch(s, fr)), s, fr, articulations = a.toList())
    private fun b(s: Int, fr: Int, vararg a: Articulation) = Note(id(), Pitch(bass.pitch(s, fr)), s, fr, velocity = 104, articulations = a.toList())
    private fun d(midi: Int, v: Int = 100) = Note(id(), Pitch(midi), velocity = v)
    private val pm = Articulation.Mark(NoteMark.PALM_MUTE)
    private fun power(fret: Int, vararg a: Articulation) = arrayOf(g(0, fret, *a), g(1, fret, *a), g(2, fret, *a))

    @Test fun renderDemoRiff() {
        val m = listOf(Measure(id()), Measure(id()), Measure(id()), Measure(id(), TimeSignature(7, 8)))
        fun beat(o: Fraction, den: Int, vararg notes: Note, dots: Int = 0) = Beat(id(), o, WrittenDuration(den, dots), notes.toList())
        // Guitar: chug riff, power chords, dead note, pinch harmonic, bend + release with vibrato, 7/8 tag with a slide.
        val g1 = listOf(beat(f(0, 16), 16, g(0, 0, pm)), beat(f(1, 16), 16, g(0, 0, pm)), beat(f(2, 16), 16, g(0, 0, pm)), beat(f(3, 16), 16, *power(3)),
            beat(f(4, 16), 16, g(0, 0, pm)), beat(f(5, 16), 16, g(0, 0, pm)), beat(f(6, 16), 16, *power(5)), beat(f(7, 16), 16, g(0, 0, pm)),
            beat(f(8, 16), 16, g(0, 0, pm)), beat(f(9, 16), 16, g(0, 0, Articulation.Mark(NoteMark.DEAD))), beat(f(10, 16), 16, g(0, 0, pm)), beat(f(11, 16), 8, *power(7)),
            beat(f(13, 16), 16, g(0, 0, pm)), beat(f(14, 16), 8, g(2, 5, Articulation.Harmonic(HarmonicKind.PINCH), Articulation.Vibrato(45, 5.5))))
        val g2 = g1.map { it.copy(id = id(), notes = it.notes.map { n -> n.copy(id = id()) }) }
        val bend = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(f(1, 4), 200), PitchCurvePoint(f(2, 3), 200), PitchCurvePoint(Fraction.ONE, 0)))
        val g3 = listOf(beat(f(0, 1), 4, g(4, 7, bend)), beat(f(1, 4), 8, g(4, 7, Articulation.Vibrato(40, 5.0)), g(3, 7)), beat(f(3, 8), 8, g(3, 5, Articulation.Mark(NoteMark.HAMMER_ON))),
            beat(f(1, 2), 8, g(3, 7)), beat(f(5, 8), 8, g(4, 5)), beat(f(3, 4), 4, g(2, 7, Articulation.Vibrato(60, 5.0))))
        val g4 = listOf(beat(f(0, 16), 16, g(0, 0, pm)), beat(f(1, 16), 16, g(0, 0, pm)), beat(f(2, 16), 16, g(0, 0, pm)), beat(f(3, 16), 16, g(0, 1, pm)),
            beat(f(4, 16), 16, g(0, 0, pm)), beat(f(5, 16), 16, g(0, 0, pm)), beat(f(6, 16), 16, g(0, 3, pm)), beat(f(7, 16), 16, g(0, 0, pm)),
            beat(f(8, 16), 8, *power(8, Articulation.Slide(SlideKind.OUT_DOWN))), beat(f(10, 16), 4, *power(0, Articulation.Mark(NoteMark.LET_RING))))
        val guitarBars = listOf(g1, g2, g3, g4)
        // Bass doubles the root.
        fun bassOf(beats: List<Beat>) = beats.mapNotNull { bt -> bt.notes.firstOrNull { it.string == 0 }?.let { lowest -> Beat(id(), bt.offset, bt.duration, listOf(b(1, (lowest.fret ?: 0), *if (Articulation.Mark(NoteMark.PALM_MUTE) in lowest.articulations) arrayOf<Articulation>(pm) else emptyArray()))) } }
        val bassBars = listOf(bassOf(g1), bassOf(g2), listOf(Beat(id(), Fraction.ZERO, WrittenDuration(2), listOf(b(2, 2))), Beat(id(), f(1, 2), WrittenDuration(2), listOf(b(1, 5)))), bassOf(g4))
        // Drums: double kick on the chugs, snare on 2 and 4, hats in eighths, crash on bar starts.
        fun drumBar(len: Int, crash: Boolean) = (0 until len).map { k ->
            val notes = mutableListOf(d(36, if (k % 2 == 0) 110 else 92))
            if (k % 2 == 0) notes += d(42, 80)
            if (k % 8 == 4) notes += d(38, 118)
            if (k == 0 && crash) notes += d(49, 120)
            Beat(id(), f(k.toLong(), 16), WrittenDuration(16), notes)
        }
        val drumBars = listOf(drumBar(16, true), drumBar(16, false), (0 until 8).map { k -> Beat(id(), f(k.toLong(), 8), WrittenDuration(8), listOfNotNull(d(51, 90), if (k % 4 == 2) d(38, 110) else null, if (k % 4 == 0) d(36, 105) else null)) }, drumBar(14, true))
        fun staff(bars: List<List<Beat>>) = listOf(Staff(id(), bars = m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0) bars[i] else emptyList()) }) }))
        val project = Project(id = id(), metadata = ProjectMetadata("RiffWorkbench Demo"), measures = m, tempo = listOf(TempoChange(ScorePosition(m[0].id), 150.0)), tracks = listOf(
            Track(id(), "Gitarre L", guitar, staff(guitarBars), SoundAssignment(presetId = "rw.guitar.highgain"), ChannelMix(volume = 0.78, pan = -0.55)),
            Track(id(), "Gitarre R", guitar, staff(guitarBars.map { bars -> bars.map { it.copy(id = id(), notes = it.notes.map { n -> n.copy(id = id(), velocity = 92) }) } }), SoundAssignment(presetId = "rw.guitar.highgain"), ChannelMix(volume = 0.76, pan = 0.55)),
            Track(id(), "Bass", bass, staff(bassBars), SoundAssignment(presetId = "rw.bass.pick"), ChannelMix(volume = 0.8)),
            Track(id(), "Drums", InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0), staff(drumBars), mix = ChannelMix(volume = 0.85))))
        val bankFile = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.firstOrNull { it.isFile }
        val bank = bankFile?.inputStream()?.buffered()?.use { input -> SampleBank.read { b, o, l -> input.read(b, o, l) } }
        for ((name, b) in listOf("riff-demo-modelled" to null, "riff-demo-sampled" to bank)) {
            if (name.endsWith("sampled") && b == null) continue
            val out = File("build/reports/audio/$name.wav").apply { parentFile.mkdirs() }
            out.outputStream().buffered().use { stream ->
                val r = OfflineMixdown(bank = b).render(project, RenderOptions(sampleRate = 48_000, bitDepth = 16, tailSeconds = 2.0), { bytes, o, l -> stream.write(bytes, o, l) })
                assertTrue(r.toString(), r is Outcome.Success)
                println("$name: ${(r as Outcome.Success).value}")
            }
        }
    }
}
