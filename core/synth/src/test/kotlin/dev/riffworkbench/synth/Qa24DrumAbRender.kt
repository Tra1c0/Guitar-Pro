package dev.riffworkbench.synth

import dev.riffworkbench.model.*
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA cycle 24, A/B for listening: a reference groove (kick/snare/hi-hat 8ths, a 16th fill over the six GM toms, crash) with the
 * high-gain guitar (sampled) and the pick bass, at 120 and 180 BPM, rendered with the synthetic kit (before) and the sampled kit
 * (after) to build/reports/audio/qa24/. Mix with the master chain plus the drum stem. Writing the files is not a listening test.
 */
class Qa24DrumAbRender {
    private var n = 0
    private fun id() = EntityId("q24ab-${n++}")
    private val guitar = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = List(6) { 0 }, midiProgram = 30)
    private val bassDef = InstrumentDefinition(InstrumentKind.BASS, listOf(28, 33, 38, 43), List(4) { 0 }, midiProgram = 33)
    private val drumsDef = InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0, drumKit = factoryDrumKit())
    private fun f(a: Long, b: Long) = Fraction.of(a, b)
    private fun beat(o: Fraction, den: Int, vararg notes: Note) = Beat(id(), o, WrittenDuration(den), notes.toList())
    private fun d(midi: Int, v: Int) = Note(id(), Pitch(midi), velocity = v)
    private val pm = Articulation.Mark(NoteMark.PALM_MUTE)
    private fun gn(s: Int, fr: Int, vararg a: Articulation) = Note(id(), Pitch(guitar.pitch(s, fr)), s, fr, 96, a.toList())
    private fun bn(s: Int, fr: Int) = Note(id(), Pitch(bassDef.pitch(s, fr)), s, fr, 104)

    private fun project(bpm: Double): Project {
        val m = List(5) { Measure(id()) }
        val riff = (0 until 16).mapNotNull { k -> when (k) { 3, 11 -> Triple(k, 8, if (k == 3) 3 else 5); 4, 12 -> null; else -> Triple(k, 16, -1) } }
        fun guitarBar() = riff.map { (k, den, fr) -> beat(f(k.toLong(), 16), den, *(if (fr < 0) arrayOf(gn(0, 0, pm)) else arrayOf(gn(0, fr), gn(1, fr + 2), gn(2, fr + 2)))) }
        fun bassBar() = riff.map { (k, den, fr) -> beat(f(k.toLong(), 16), den, bn(1, if (fr < 0) 0 else fr)) }
        fun groove(crash: Boolean, upTo: Int = 8) = (0 until upTo).map { k ->
            val notes = mutableListOf(d(42, if (k % 2 == 0) 100 else 78))
            if (k == 0 || k == 4 || k == 5) notes += d(36, if (k == 0) 120 else 104)
            if (k == 2 || k == 6) notes += d(38, 118)
            if (k == 0 && crash) notes += d(49, 118)
            beat(f(k.toLong(), 8), 8, *notes.toTypedArray())
        }
        val fill = listOf(50, 50, 48, 48, 47, 45, 43, 41).mapIndexed { k, midi -> beat(f(1, 2) + f(k.toLong(), 16), 16, d(midi, 112 + (k % 2) * 8)) }
        val drumBars = listOf(groove(true), groove(false), groove(false), groove(false, 4) + fill, listOf(beat(Fraction.ZERO, 1, d(36, 124), d(49, 124), d(57, 116))))
        val guitarBars = List(4) { guitarBar() } + listOf(listOf(beat(Fraction.ZERO, 1, gn(0, 0), gn(1, 2), gn(2, 2))))
        val bassBars = List(4) { bassBar() } + listOf(listOf(beat(Fraction.ZERO, 1, bn(0, 0))))
        fun staff(bars: List<List<Beat>>, kind: NotationKind) = listOf(Staff(id(), setOf(kind), m.mapIndexed { i, mm -> Bar(id(), mm.id, List(4) { v -> Voice(id(), v, if (v == 0) bars[i] else emptyList()) }) }))
        return Project(id = id(), metadata = ProjectMetadata("Q24 A/B ${bpm.toInt()} BPM"), measures = m, tempo = listOf(TempoChange(ScorePosition(m[0].id), bpm)), tracks = listOf(
            Track(id(), "Gitarre", guitar, staff(guitarBars, NotationKind.TAB)),
            Track(id(), "Bass", bassDef, staff(bassBars, NotationKind.TAB)),
            Track(id(), "Drums", drumsDef, staff(drumBars, NotationKind.PERCUSSION))))
    }

    @Test fun renderReferenceGrooveBeforeAndAfter() {
        val bank = Qa18GainStructureTest.bank
        val kit = Qa24Kit.kit
        val dir = File("build/reports/audio/qa24").apply { mkdirs() }
        for (bpm in listOf(120.0, 180.0)) for ((label, k) in listOf("vorher-synthetisch" to null, "nachher-gesampelt" to kit)) {
            if (k == null && label.startsWith("nachher")) continue
            val p = project(bpm)
            for ((suffix, solo) in listOf("band" to null, "drums" to 2)) {
                val out = File(dir, "groove-${bpm.toInt()}bpm-$label-$suffix.wav")
                out.outputStream().buffered().use { stream ->
                    val r = OfflineMixdown(bank = bank, drumKit = k).render(p, RenderOptions(sampleRate = 48_000, bitDepth = 24, tailSeconds = 3.0), { b, o, l -> stream.write(b, o, l) }, soloTrack = solo)
                    assertTrue(r.toString(), r is Outcome.Success)
                    val s = (r as Outcome.Success).value
                    println("Q24AB ${out.absolutePath}: ${s.frames} Frames, Spitze ${Qa22Analysis.f1(Qa22Analysis.db(s.peak))} dBFS, ${s.clippedSamples} geclippt")
                }
            }
        }
    }
}
