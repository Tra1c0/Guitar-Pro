package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Zyklus 28 „Taktart erkennen“, writing: re-barred spans keep the frame map (tempo in quarters per minute stays), a pickup
 * bar gets its own short meter, ApplyRhythm changes meters only of bars that are empty in every track (and only with
 * empty bars after them), in one undo step with the rhythm.
 */
class Qa28MeterApplyTest {
    private var n = 0
    private val ids = IdSource { EntityId("q28m-${n++}") }
    private fun f(a: Long, b: Long) = Fraction.of(a, b)

    @Test fun rebarredSpansKeepTheTimeMap() {
        val fpw = 96_000.0
        val spans = List(4) { i -> RouteSpan(i, EntityId("b$i"), i, 1, Fraction.ONE, doubleArrayOf(0.0, 1.0), doubleArrayOf(i * fpw, (i + 1) * fpw)) }
        val (out, sigs) = rebarSpans(spans, 1, TimeSignature(7, 8), f(1, 4)) { EntityId("n$it") }
        assertEquals(spans[0], out[0])
        assertEquals(TimeSignature(2, 8), sigs[0]); assertTrue(sigs.drop(1).all { it == TimeSignature(7, 8) })
        // 3 whole notes from bar 2 on: 1/4 + 4 × 7/8 = 3.75 ≥ 3.
        assertEquals(5, out.size - 1)
        for (s in out.drop(1)) assertEquals(fpw, (s.frames[1] - s.frames[0]) / s.length.toDouble(), 1e-9)
        assertEquals(fpw, out[1].frames[0], 1e-9)
        assertEquals(TimeSignature(3, 16), signatureOf(f(3, 16), TimeSignature(7, 8)))
    }

    @Test fun applyChangesOnlyEmptyBarsInOneUndoStep() {
        val p0 = ProjectFactory(ids).create("M", signatures = List(3) { TimeSignature() })
        val session = EditorSession(p0, ids)
        val guitar = p0.tracks.first().id
        val m = p0.measures
        val bar78 = { id: EntityId -> RhythmBar(id, f(7, 8), listOf(RhythmEvent(Fraction.ZERO, WrittenDuration(4), RhythmEvent.Kind.ONSET), RhythmEvent(f(1, 4), WrittenDuration(4, 1), RhythmEvent.Kind.REST),
            RhythmEvent(f(5, 8), WrittenDuration(4), RhythmEvent.Kind.REST))) }
        val newId = ids.next()
        assertTrue(meterEligible(p0, listOf(m[1].id, m[2].id)))
        val ok = session.dispatch(EditorCommand.ApplyRhythm(guitar, 0, listOf(bar78(m[1].id), bar78(m[2].id), bar78(newId)), RhythmMode.OPEN_STRING, appended = listOf(newId),
            signatures = mapOf(m[1].id to TimeSignature(7, 8), m[2].id to TimeSignature(7, 8), newId to TimeSignature(7, 8))))
        assertTrue(ok.toString(), ok is Outcome.Success)
        val p1 = session.state.value.project
        assertEquals(listOf(TimeSignature(), TimeSignature(7, 8), TimeSignature(7, 8), TimeSignature(7, 8)), p1.measures.map { it.signature })
        assertTrue(ScoreValidator.validate(p1).none { it.severity == Severity.ERROR })
        session.undo()
        assertEquals(p0.measures, session.state.value.project.measures)
        // Bar 3 holds a note in another track: no meter change there (and none for bar 2 before it).
        val bass = p0.tracks[1]
        val withNote = p0.copy(tracks = p0.tracks.map { t -> if (t.id != bass.id) t else t.copy(staves = t.staves.map { st -> st.copy(bars = st.bars.mapIndexed { i, b ->
            if (i != 2) b else b.copy(voices = b.voices.map { v -> if (v.index != 0) v else v.copy(beats = listOf(Beat(ids.next(), Fraction.ZERO, WrittenDuration(1), listOf(Note(ids.next(), Pitch(40), 0, 0))))) }) }) }) })
        assertFalse(meterEligible(withNote, listOf(m[1].id)))
        val s2 = EditorSession(withNote, ids)
        val refused = s2.dispatch(EditorCommand.ApplyRhythm(guitar, 0, listOf(bar78(m[1].id)), RhythmMode.OPEN_STRING, signatures = mapOf(m[1].id to TimeSignature(7, 8))))
        assertTrue(refused is Outcome.Failure)
        assertSame(withNote, s2.state.value.project)
    }
}
