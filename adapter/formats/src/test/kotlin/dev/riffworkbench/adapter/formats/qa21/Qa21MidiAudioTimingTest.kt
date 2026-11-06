package dev.riffworkbench.adapter.formats.qa21

import dev.riffworkbench.adapter.formats.midi.MidiExporter
import dev.riffworkbench.adapter.formats.midi.SmfReader
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.playback.ScorePlaybackCompiler
import dev.riffworkbench.synth.OfflineMixdown
import dev.riffworkbench.synth.SampleBank
import dev.riffworkbench.synth.Wav
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-Zyklus 21 / Export-Import, MIDI gegen Audio: Die Stems-ZIP enthält WAVs und eine MIDI-Datei, die in der DAW
 * übereinanderliegen sollen ("tick 0 is frame 0 of every rendered WAV stem", MidiExporter). Geprüft am Standardprojekt
 * (ProjectFactory.create, HG-Gitarre gesampelt sofern die Bank vorliegt, sonst modelliert; Drums) mit Tempowechsel per Sheet
 * (132 → 96 BPM ab Takt 3) und 7/8 in Takt 4:
 * 1. jeder Note-on der MIDI-Datei (Tempo-Map aus den Tempo-Events integriert) liegt höchstens 1 ms (48 Frames bei 48 kHz)
 *    neben dem Planframe seiner Note;
 * 2. der hörbare Einsatz jedes Schlags im WAV-Stem (erstes Sample ≥ −40 dB unter der Schlagspitze, nur Schläge nach Stille)
 *    liegt höchstens 1 ms neben dem Note-on der MIDI-Datei. Rot vor Zyklus 21: der Export begann 64 Frames (Cabinet-Latenz)
 *    zu spät, Drums 64–67 Frames hinter dem Note-on.
 */
class Qa21MidiAudioTimingTest {
    companion object {
        const val SR = 48_000
        const val TOL = 48
        val bank: SampleBank? by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/assets/banks/guitar-emily-di.rwsb") }.firstOrNull { it.isFile }
                ?.inputStream()?.buffered()?.use { input -> SampleBank.read { b, o, l -> input.read(b, o, l) } }
        }
    }
    private var n = 0
    private val ids = IdSource { EntityId("q21m-${n++}") }
    private val EditorSession.p get() = state.value.project
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun f(a: Long, b: Long) = Fraction.of(a, b)

    /** Standardprojekt, 4 Takte: Gitarre und Drums (Snare) je ein kurzer Schlag (16tel) pro Zählzeit, Sheet-Tempowechsel, 7/8. */
    private fun project(): Project {
        val s = EditorSession(ProjectFactory(ids).create("Q21 MIDI"), ids)
        repeat(3) { s.ok(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        s.ok(EditorCommand.SetTempo(132.0))
        s.ok(EditorCommand.SetTempoAt(s.p.measures[2].id, 96.0))
        s.ok(EditorCommand.SetSignature(s.p.measures[3].id, TimeSignature(7, 8)))
        for (m in 0 until 4) {
            val beats = if (m == 3) (0 until 7).map { f(it.toLong(), 8) } else (0 until 4).map { f(it.toLong(), 4) }
            for ((k, off) in beats.withIndex()) {
                s.select(Selection(s.p.tracks[0].id, s.p.measures[m].id, 0, off, listOf(0, 1, 2)[k % 3])); s.ok(EditorCommand.PutNote(fret = 0, duration = WrittenDuration(16)))
                // Snare (in 7/8 nur auf den Vierteln: auf jeder Achtel bei 96 BPM liegt der Ausklang der vorigen noch über −20 dB).
                if (m < 3 || k % 2 == 0) { s.select(Selection(s.p.tracks[2].id, s.p.measures[m].id, 0, off, 0)); s.ok(EditorCommand.ToggleDrum(38, WrittenDuration(16))) }
            }
        }
        return s.p
    }

    /** Note-ons (velocity > 0) per (drum?, key) in frames at [SR], from the exported SMF and its tempo events. */
    private fun midiOnsets(bytes: ByteArray): Map<Pair<Boolean, Int>, List<Long>> {
        val smf = SmfReader.read(bytes)
        val ppq = smf.division
        val tempo = smf.tracks.flatMap { t -> t.metas.filter { it.type == 0x51 && it.data.size == 3 }.map { it.tick to ((it.data[0].toLong() and 0xFF) shl 16 or ((it.data[1].toLong() and 0xFF) shl 8) or (it.data[2].toLong() and 0xFF)) } }.sortedBy { it.first }
        fun micros(tick: Long): Double {
            var us = 0.0; var lastTick = 0L; var mpq = 500_000L
            for ((t, v) in tempo) { if (t > tick) break; us += (t - lastTick) * mpq.toDouble() / ppq; lastTick = t; mpq = v }
            return us + (tick - lastTick) * mpq.toDouble() / ppq
        }
        val out = HashMap<Pair<Boolean, Int>, MutableList<Long>>()
        for (t in smf.tracks) for (i in 0 until t.size) {
            val st = t.status(i)
            if (st and 0xF0 == 0x90 && t.data2(i) > 0) out.getOrPut((st and 0x0F == 9) to t.data1(i)) { ArrayList() } += Math.round(micros(t.tick(i)) * SR / 1e6)
        }
        out.values.forEach { it.sort() }
        return out
    }

    private fun stem(p: Project, track: Int): FloatArray {
        val bytes = ByteArrayOutputStream()
        val r = OfflineMixdown(ScorePlaybackCompiler(), bank).render(p, RenderOptions(sampleRate = SR, bitDepth = 32, includeMasterFx = false, tailSeconds = 1.0), { b, o, l -> bytes.write(b, o, l) }, track)
        assertTrue("stem $track: $r", r is Outcome.Success)
        val d = Wav.decode(bytes.toByteArray())
        return FloatArray(d.frames) { maxOf(abs(d.left[it]), abs(d.right[it])) }
    }

    /** Audible onset of the hit at [e] relative to e (as Qa20SyncTest: -40 dB re the hit's peak, after >= 20 dB of silence). */
    private fun onset(x: FloatArray, e: Long): Int? {
        val a = (e - SR / 100).toInt().coerceAtLeast(0); val b = minOf(x.size, (e + SR / 20).toInt())
        var peak = 0f; for (i in a until b) peak = maxOf(peak, x[i])
        if (peak < 1e-4f) return null
        var floor = 0f; for (i in maxOf(0, a - SR / 50) until a) floor = maxOf(floor, x[i])
        if (floor > peak * 0.1f) return null
        val th = maxOf(peak * 0.01f, floor * 3.16f)
        for (i in a until b) if (x[i] >= th) return (i - e).toInt()
        return null
    }

    @Test fun midiNoteOnsMatchThePlanAndTheExportedAudio() {
        val p = project()
        val plan = (ScorePlaybackCompiler().compileNow(p, SR) as Outcome.Success).value
        val midi = midiOnsets((MidiExporter().exportNow(p) as Outcome.Success).value.bytes)
        val failures = ArrayList<String>()
        // 1. MIDI note-on against the plan frame of every note.
        var worstPlan = 0L
        for (note in plan.notes) {
            val drum = p.tracks[note.trackIndex].instrument.kind == InstrumentKind.PERCUSSION
            val ons = midi[drum to Math.round(note.soundingPitch).toInt()].orEmpty()
            val d = ons.minOfOrNull { abs(it - note.frame) } ?: Long.MAX_VALUE
            worstPlan = maxOf(worstPlan, d)
            if (d > TOL) failures += "Note ${note.noteId} (Spur ${note.trackIndex}, Frame ${note.frame}): nächster Note-on $d Frames entfernt"
        }
        // 2. Audible onset in the stem against the MIDI note-on.
        val report = StringBuilder()
        for ((t, name) in listOf(0 to "Gitarre", 2 to "Drums")) {
            val x = stem(p, t)
            val drum = t == 2
            val ons = plan.notes.filter { it.trackIndex == t }.map { it.frame }.distinct().sorted()
            var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE; var measured = 0
            for (fr in ons) {
                val note = plan.notes.first { it.trackIndex == t && it.frame == fr }
                val m = midi[drum to Math.round(note.soundingPitch).toInt()].orEmpty().minByOrNull { abs(it - fr) } ?: continue
                val o = onset(x, m) ?: continue
                measured++; lo = minOf(lo, o); hi = maxOf(hi, o)
                if (abs(o) > TOL) failures += "$name: hörbarer Einsatz $o Frames neben dem Note-on bei Frame $m"
            }
            report.append(" | $name: $measured von ${ons.size} Schlägen gemessen, Einsatz − Note-on $lo..$hi Frames")
            if (measured < ons.size * 9 / 10) failures += "$name: nur $measured von ${ons.size} Schlägen messbar"
        }
        println("Q21M MIDI gegen Plan: größte Abweichung $worstPlan Frames bei ${plan.notes.size} Noten$report (Bank ${if (bank != null) "geladen" else "fehlt: modellierte Gitarre"})")
        assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
    }
}
