package dev.riffworkbench.editor

import dev.riffworkbench.editor.RhythmEvent.Kind.CONTINUATION
import dev.riffworkbench.editor.RhythmEvent.Kind.ONSET
import dev.riffworkbench.editor.RhythmEvent.Kind.REST
import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 27 „Rhythmus tippen: Takte anhängen“ (core): ApplyRhythm appends the bars a recording reached past the end of the
 * piece — empty bars in every track, signature/key/feel of the last bar, only as many as hold tapped content, all in one
 * undo step with the rhythm; the quantiser runs over buffer bars like over written ones; trimming, the linear run of the
 * route and the Auto-Stopp rule.
 */
class Qa27AppendRhythmTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("z-${sequence++}") }
    private val sr = 48_000
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private fun w(d: Int, dots: Int = 0) = WrittenDuration(d, dots)
    private fun e(o: Fraction, d: WrittenDuration, k: RhythmEvent.Kind) = RhythmEvent(o, d, k)
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.at(bar: Int, offset: Fraction, string: Int = 0, voice: Int = 0) =
        select(state.value.selection.copy(measureId = state.value.project.measures[bar].id, offset = offset, string = string, voice = voice, rangeEnd = null))
    private fun noErrors(p: Project) { val errors = ScoreValidator.validate(p).filter { it.severity == Severity.ERROR }; assertTrue(errors.toString(), errors.isEmpty()) }
    private fun restBar(id: EntityId, len: Fraction): RhythmBar {
        var at = Fraction.ZERO
        return RhythmBar(id, len, Durations.decompose(len)!!.map { d -> e(at, d, REST).also { at += d.length() } })
    }

    /** Two bars of 3/4 with a swung feel and a key, a guitar riff in bar 2 and a bass track; tempo 100. */
    private fun session(): EditorSession {
        val p0 = ProjectFactory(ids).create("Anhängen", signatures = List(2) { TimeSignature(3, 4) })
        val p = p0.copy(measures = p0.measures.map { it.copy(key = KeySignature(2), feel = RhythmicFeel.SWING_EIGHTH) },
            tempo = listOf(TempoChange(ScorePosition(p0.measures[0].id), 100.0), TempoChange(ScorePosition(p0.measures[1].id, f(1, 4)), 90.0)))
        val e = EditorSession(p, ids)
        e.ok(EditorCommand.AddTrack(InstrumentKind.BASS))
        for (k in 0..2) { e.at(1, f(k.toLong(), 4), string = 1); e.ok(EditorCommand.PutNote(fret = 3 + k, duration = w(4))) }
        return e
    }

    @Test fun appendedBarsGetEmptyBarsInEveryTrackAndOneUndoStep() {
        val e = session()
        val before = e.state.value.project
        val guitar = before.tracks[0].id
        val n1 = EntityId("new-1"); val n2 = EntityId("new-2")
        // Bar 2 (existing): a held half into a held quarter that continues over two barlines into the new bars.
        val bars = listOf(
            RhythmBar(before.measures[1].id, f(3, 4), listOf(e(f(0, 1), w(2), ONSET), e(f(1, 2), w(4), ONSET))),
            RhythmBar(n1, f(3, 4), listOf(e(f(0, 1), w(4), CONTINUATION), e(f(1, 4), w(8), REST), e(f(3, 8), w(8), ONSET), e(f(1, 2), w(4), REST))),
            RhythmBar(n2, f(3, 4), listOf(e(f(0, 1), w(16), ONSET), e(f(1, 16), w(16), REST), e(f(1, 8), w(8), REST), e(f(1, 4), w(2), REST))))
        assertEquals(3, rhythmOverwrites(before, guitar, 0, bars.map { it.measureId }))
        e.ok(EditorCommand.ApplyRhythm(guitar, 0, bars, RhythmMode.OPEN_STRING, string = 0, appended = listOf(n1, n2)))
        val after = e.state.value.project
        noErrors(after)
        assertEquals(before.measures.map { it.id } + listOf(n1, n2), after.measures.map { it.id })
        // Signature, key and feel of the last bar; no repeat/navigation; the tempo map is unchanged (the last tempo goes on).
        for (m in after.measures.takeLast(2)) { assertEquals(TimeSignature(3, 4), m.signature); assertEquals(KeySignature(2), m.key); assertEquals(RhythmicFeel.SWING_EIGHTH, m.feel); assertEquals(RepeatMark(), m.repeat); assertTrue(m.navigation.isEmpty()) }
        assertEquals(before.tempo, after.tempo)
        // Every staff of every track has a bar for each new measure, in order; the bass bars are empty.
        for (t in after.tracks) for (st in t.staves) assertEquals(after.measures.map { it.id }, st.bars.map { it.measureId })
        assertTrue(after.tracks[1].staves[0].bars.takeLast(2).all { b -> b.voices.size == 4 && b.voices.all { it.beats.isEmpty() } })
        // The rhythm is written, the held note tied over the barline into the new bar.
        val g = after.tracks[0].staves[0].bars
        val held = g[1].voices[0].beats[1].notes.single(); val cont = g[2].voices[0].beats[0].notes.single()
        assertEquals(listOf(NoteLink(LinkKind.TIE, cont.id)), held.links)
        assertEquals(listOf(f(0, 1), f(1, 16), f(1, 8), f(1, 4)), g[3].voices[0].beats.map { it.offset })
        // One undo step: bars and rhythm go together; redo brings both back.
        assertTrue(e.undo() is Outcome.Success)
        assertEquals(before.copy(revision = 0), e.state.value.project.copy(revision = 0))
        assertTrue(e.redo() is Outcome.Success)
        assertEquals(after.copy(revision = 0), e.state.value.project.copy(revision = 0))
    }

    @Test fun onlyNeededBarsAndInvalidAppendsChangeNothing() {
        val e = session()
        val p = e.state.value.project
        val guitar = p.tracks[0].id
        val last = p.measures.last().id
        val hit = RhythmBar(EntityId("n-a"), f(3, 4), listOf(e(f(0, 1), w(2, 1), ONSET)))
        val empty = restBar(EntityId("n-b"), f(3, 4))
        fun refused(c: EditorCommand.ApplyRhythm, message: String) {
            val r = e.dispatch(c)
            assertTrue("$c -> $r", r is Outcome.Failure && (r as Outcome.Failure).message.contains(message))
            assertSame(p, e.state.value.project)
            assertFalse(e.state.value.canRedo)
        }
        // A trailing empty new bar is not created.
        refused(EditorCommand.ApplyRhythm(guitar, 0, listOf(hit, empty), RhythmMode.GRID, appended = listOf(hit.measureId, empty.measureId)), "Leere neue Takte")
        // New bars must follow the end of the piece and be the last target bars; ids must be new.
        refused(EditorCommand.ApplyRhythm(guitar, 0, listOf(restBar(p.measures[0].id, f(3, 4)), hit), RhythmMode.GRID, appended = listOf(hit.measureId)), "Stückende")
        refused(EditorCommand.ApplyRhythm(guitar, 0, listOf(hit, restBar(last, f(3, 4))), RhythmMode.GRID, appended = listOf(hit.measureId)), "am Ende")
        refused(EditorCommand.ApplyRhythm(guitar, 0, listOf(restBar(last, f(3, 4)).copy(events = listOf(e(f(0, 1), w(2, 1), ONSET)))), RhythmMode.GRID, appended = listOf(last)), "ungültig")
        // A bar length that does not match the last bar's signature (the copy was made with another meter).
        refused(EditorCommand.ApplyRhythm(guitar, 0, listOf(RhythmBar(EntityId("n-c"), f(1, 1), listOf(e(f(0, 1), w(1), ONSET)))), RhythmMode.GRID, appended = listOf(EntityId("n-c"))), "Taktart")
        // Only new bars (the target starts after the end): fine, two bars.
        e.ok(EditorCommand.ApplyRhythm(guitar, 0, listOf(empty, hit), RhythmMode.GRID, appended = listOf(empty.measureId, hit.measureId)))
        assertEquals(4, e.state.value.project.measures.size)
        noErrors(e.state.value.project)
    }

    @Test fun appendEmptyMeasuresMatchesTheWrittenBars() {
        val e = session()
        val p = e.state.value.project
        val copy = appendEmptyMeasures(p, listOf(EntityId("b1"), EntityId("b2"), EntityId("b3")), ids)
        noErrors(copy)
        assertEquals(p.measures, copy.measures.take(2))
        assertEquals(p.measures.last().copy(id = EntityId("b3")), copy.measures.last())
        assertSame(p, appendEmptyMeasures(p, emptyList(), ids))
    }

    /** Spans of 4/4 bars at [qpm], measure ids m0…, the copy: [written] bars of the piece and buffer bars after them. */
    private fun spans(count: Int, qpm: Double): List<RouteSpan> {
        val fpw = 4 * 60.0 / qpm * sr
        return List(count) { i -> RouteSpan(i, EntityId("m$i"), i, 1, f(1, 1), doubleArrayOf(0.0, 1.0), doubleArrayOf(i * fpw, (i + 1) * fpw)) }
    }

    @Test fun quantiserRunsOverBufferBarsAndTrimKeepsOnlyContent() {
        val sp = spans(6, 120.0)   // 2 written bars + 4 buffer bars
        val q = sr / 2.0           // a quarter at 120 BPM
        fun at(bar: Int, quarter: Double) = sp[bar].frames[0] + quarter * q
        // Bar 1: quarter; bar 3 (buffer): held half from beat 3 over the barline into bar 4 (buffer); bars 5–6 empty.
        val taps = listOf(TapStroke(at(0, 0.0) + 300, at(0, 1.0) - 900), TapStroke(at(2, 2.0) - 400, at(3, 1.0) - 1000), TapStroke(at(3, 2.0) + 200, at(3, 2.0) + 2400))
        val r = RhythmQuantizer.quantize(taps, sp, sr, RhythmGrid.SIXTEENTH)
        assertEquals(6, r.bars.size); assertEquals(3, r.onsets)
        assertEquals(listOf(e(f(0, 1), w(4), CONTINUATION), e(f(1, 4), w(4), REST), e(f(1, 2), w(16), ONSET), e(f(9, 16), w(4, 2), REST)), r.bars[3].events)
        val trimmed = r.trimmed(keep = 1)
        assertEquals(listOf("m0", "m1", "m2", "m3"), trimmed.bars.map { it.measureId.value })
        assertEquals(r.onsets, trimmed.onsets)
        // A fixed target keeps its existing bars even when they end empty; with nothing tapped only [keep] bars remain.
        assertEquals(6, r.trimmed(keep = 6).bars.size)
        val none = RhythmQuantizer.quantize(emptyList(), sp, sr, RhythmGrid.SIXTEENTH)
        assertEquals(2, none.trimmed(keep = 2).bars.size); assertEquals(1, none.trimmed(keep = 1).bars.size)
    }

    @Test fun linearRunStopsBeforeARepeatOrJump() {
        val fpw = 96_000.0
        fun s(route: Int, index: Int, occurrence: Int = 1) = RouteSpan(route, EntityId("m$index"), index, occurrence, f(1, 1), doubleArrayOf(0.0, 1.0), doubleArrayOf(route * fpw, (route + 1) * fpw))
        assertEquals(listOf(2, 3, 4), linearRun(listOf(s(0, 2), s(1, 3), s(2, 4))).map { it.measureIndex })
        // Repeat back from bar 4 to bar 3: the run ends at bar 4.
        assertEquals(listOf(2, 3, 4), linearRun(listOf(s(0, 2), s(1, 3), s(2, 4), s(3, 3, 2), s(4, 4, 2), s(5, 5))).map { it.measureIndex })
        assertTrue(linearRun(emptyList()).isEmpty())
    }

    @Test fun autoStopAfterTwoEmptyBarsOnlyAfterATapAndWithoutAHeldFinger() {
        val sp = spans(8, 120.0)
        val bar = 96_000.0
        assertNull("no tap yet", RhythmAutoStop.stopFrame(sp, emptyList()))
        assertNull("count-in tap only", RhythmAutoStop.stopFrame(sp, listOf(TapStroke(-40_000.0, -38_000.0))))
        // Last touch in bar 2 (index 1): bars 3 and 4 empty → stop at the end of bar 4.
        val taps = listOf(TapStroke(1_000.0, 4_000.0), TapStroke(bar + 10_000, bar + 30_000))
        assertEquals(sp[3].endFrame, RhythmAutoStop.stopFrame(sp, taps))
        // A finger still down: no auto-stop.
        assertNull(RhythmAutoStop.stopFrame(sp, taps + TapStroke(2 * bar + 1_000)))
        // A held note released in bar 3 counts as a touch there.
        assertEquals(sp[4].endFrame, RhythmAutoStop.stopFrame(sp, taps + TapStroke(bar + 50_000, 2 * bar + 20_000)))
        // A touch just before a barline snaps onto the next downbeat: the empty bars count after that bar.
        assertEquals(sp[4].endFrame, RhythmAutoStop.stopFrame(sp, listOf(TapStroke(2 * bar - 2_000, 2 * bar - 1_000))))
        // Not enough bars in the copy (yet): null, the copy is extended first.
        assertNull(RhythmAutoStop.stopFrame(sp.take(3), taps))
    }
}
