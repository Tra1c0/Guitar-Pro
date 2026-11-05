package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * QA 14 findings from the model-based editor test (Qa14ModelBasedEditorTest), shrunk to deterministic cases.
 * Every test here fails on the current code; each states the expected behaviour.
 */
class Qa14EditorFindingsTest {
    private var sequence = 0
    private val ids = IdSource { EntityId("q-${sequence++}") }
    private fun session() = EditorSession(ProjectFactory(ids).demo("QA14"), ids)
    private fun EditorSession.at(offset: Fraction, string: Int = 2, measure: Int = 0, track: Int = 0) =
        select(state.value.selection.copy(trackId = state.value.project.tracks[track].id, measureId = state.value.project.measures[measure].id, offset = offset, string = string, rangeEnd = null))
    private fun EditorSession.ok(c: EditorCommand) { val r = dispatch(c); assertTrue("$c -> $r", r is Outcome.Success) }
    private fun EditorSession.m(i: Int) = state.value.project.measures[i].id
    @Suppress("UNCHECKED_CAST") private fun EditorSession.depth(name: String) = (EditorSession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as ArrayDeque<*>).size

    /** Ties whose target does not start exactly where the origin ends (same voice; same bar or downbeat of the next bar). */
    private fun nonAdjacentTies(p: Project): List<String> {
        data class Loc(val measure: Int, val beat: Beat)
        val index = HashMap<EntityId, Loc>()
        val mi = p.measures.withIndex().associate { it.value.id to it.index }
        for (t in p.tracks) for (st in t.staves) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes) index[n.id] = Loc(mi.getValue(bar.measureId), b)
        val out = mutableListOf<String>()
        for (t in p.tracks) for (st in t.staves) for (bar in st.bars) for (v in bar.voices) for (b in v.beats) for (n in b.notes) for (l in n.links) if (l.kind == LinkKind.TIE) {
            val o = index.getValue(n.id); val z = index[l.target] ?: continue
            val end = o.beat.offset + o.beat.duration.length()
            val m = p.measures[o.measure]
            val ok = if (z.measure == o.measure) z.beat.offset == end else z.measure == o.measure + 1 && z.beat.offset == Fraction.ZERO && end == (m.pickupLength ?: m.signature.length())
            if (!ok) out += "bar ${o.measure} @${o.beat.offset}+${o.beat.duration.length()} -> bar ${z.measure} @${z.beat.offset}"
        }
        return out
    }
    private fun assertTiesAdjacentOrRefused(e: EditorSession, c: EditorCommand) {
        val r = e.dispatch(c)
        val bad = nonAdjacentTies(e.state.value.project)
        assertTrue("$c -> $r left a tie over a gap (shown as tie, re-attacked by playback, dropped by GP7 export): $bad", r is Outcome.Failure || bad.isEmpty())
    }

    /** Two tied quarters on string 2 (fret 5) at 0 and 1/4 of bar 1. */
    private fun tiedInBar(): EditorSession {
        val e = session()
        e.at(Fraction.ZERO); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.at(Fraction.of(1, 4)); e.ok(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        e.ok(EditorCommand.ToggleTie)
        assertTrue(nonAdjacentTies(e.state.value.project).isEmpty())
        return e
    }
    /** A sixteenth at 15/16 of bar 1 tied over the barline into bar 2 @0. */
    private fun tiedOverBarline(): EditorSession {
        val e = session()
        e.at(Fraction.of(15, 16)); e.ok(EditorCommand.PutNote(fret = 7, duration = WrittenDuration(16)))
        e.at(Fraction.ZERO, measure = 1); e.ok(EditorCommand.ToggleTie)
        assertTrue(nonAdjacentTies(e.state.value.project).isEmpty())
        return e
    }

    // ---- F1 (I7): ties stop being adjacent; ToggleTie refuses exactly this state ("Haltebogen nur zur unmittelbar vorherigen Note ohne Pause")
    @Test fun shorteningTheOriginOfABarlineTieLeavesAGap() {
        val e = tiedOverBarline(); e.at(Fraction.of(15, 16))
        assertTiesAdjacentOrRefused(e, EditorCommand.SetDuration(WrittenDuration(32)))
    }

    @Test fun tupletOnTheOriginOfABarlineTieLeavesAGap() {
        val e = tiedOverBarline(); e.at(Fraction.of(15, 16))
        assertTiesAdjacentOrRefused(e, EditorCommand.SetTuplet(TupletRatio(3, 2)))
    }

    @Test fun lengtheningTheOriginBarLeavesAGapBeforeTheBarlineTie() {
        val e = tiedOverBarline()
        assertTiesAdjacentOrRefused(e, EditorCommand.SetSignature(e.m(0), TimeSignature(5, 4)))
    }

    // ---- F2 (I8): non-finite values the editor accepts make the project unsavable (JsonEncodingException in projectJson)

    private fun assertSavable(e: EditorSession, c: EditorCommand) {
        val r = e.dispatch(c)
        val saved = runCatching { Qa14ModelBasedEditorTest.projectJson.encodeToString(Project.serializer(), e.state.value.project) }
        assertTrue("$c -> $r, but the project can no longer be saved: ${saved.exceptionOrNull()}", r is Outcome.Failure || saved.isSuccess)
    }
    private fun fx(value: Double) = EffectUnit(EntityId("fx1"), EffectKind.OVERDRIVE, parameters = mapOf("gain" to value))

    @Test fun masterPanNaNIsAccepted() = assertSavable(session(), EditorCommand.SetMasterMix(ChannelMix(volume = 0.8, pan = Double.NaN)))
    @Test fun masterEffectParameterInfinityIsAccepted() = assertSavable(session(), EditorCommand.SetMasterMix(ChannelMix(effects = listOf(fx(Double.POSITIVE_INFINITY)))))
    @Test fun trackEffectParameterNaNIsAccepted() { val e = session(); assertSavable(e, EditorCommand.SetMix(e.state.value.project.tracks[0].id, ChannelMix(effects = listOf(fx(Double.NaN))))) }
    @Test fun audioClipFilterParameterNaNIsAccepted() {
        val h = AssetHash("a".repeat(64))
        assertSavable(session(), EditorCommand.AddAudioClip(AudioClip(EntityId("clip1"), h, "ref", filters = listOf(fx(Double.NaN))), AssetDescriptor(h, "a.wav", "audio/wav", 100)))
    }

    // ---- F3 (I8, low): lone UTF-16 surrogates are accepted and turn into '?' when the project is written as UTF-8

    @Test fun loneSurrogateInTitleDoesNotSurviveSaving() {
        val e = session()
        val r = e.dispatch(EditorCommand.Rename("Riff \uD83C"))
        val bytes = Qa14ModelBasedEditorTest.projectJson.encodeToString(Project.serializer(), e.state.value.project).toByteArray() // as FileProjectRepository
        val back = Qa14ModelBasedEditorTest.projectJson.decodeFromString(Project.serializer(), bytes.toString(Charsets.UTF_8))
        assertTrue("Rename -> $r; saved title '${back.metadata.title}' != edited title", r is Outcome.Failure || back == e.state.value.project)
    }

    // ---- F4 (I4, low): Replace with identical content is recorded as a change

    @Test fun replaceWithIdenticalContentIsANoOp() {
        val e = session()
        val original = e.state.value.project
        e.ok(EditorCommand.SetTempo(120.0)); e.undo()
        val rev = e.state.value.project.revision; val undoDepth = e.depth("undo")
        e.ok(EditorCommand.Replace(original, expectedRevision = rev)) // e.g. a proposal built on the restored state
        assertEquals("content unchanged, revision must stay", rev, e.state.value.project.revision)
        assertEquals("content unchanged, no undo step that does nothing", undoDepth, e.depth("undo"))
    }

    // ---- F5 (I2/I6, low): a selection offset select() accepts makes later edits fail after the history was changed

    @Test fun failureAfterHugeSelectionOffsetMustNotTouchHistory() {
        val e = session()
        e.ok(EditorCommand.Rename("A")); e.undo()
        assertEquals(1, e.depth("redo"))
        // Fixed by rejecting such offsets in select(); if a select is accepted the history must still hold.
        runCatching { e.select(e.state.value.selection.copy(measureId = e.m(1), offset = Fraction.of(4_000_000_000_000_000_001L, 4_611_686_018_427_387_903L))) }
        val r = e.dispatch(EditorCommand.Rename("B"))
        assertTrue("valid Rename reported $r", r is Outcome.Success || (e.depth("redo") == 1 && e.depth("undo") == 0))
    }

    @Test fun redoMustNotThrowAfterHugeSelectionOffset() {
        val e = session()
        e.ok(EditorCommand.Rename("A"))
        // Fixed by rejecting such offsets in select(); if a select is accepted the history must still hold.
        runCatching { e.select(e.state.value.selection.copy(measureId = e.m(1), offset = Fraction.of(4_000_000_000_000_000_001L, 4_611_686_018_427_387_903L))) }
        assertTrue(e.undo() is Outcome.Success) // stores the selection in the redo entry
        val r = runCatching { e.redo() }
        assertTrue("redo threw ${r.exceptionOrNull()}", r.isSuccess)
    }

    // ---- F6 (I6, low): a malformed clipboard (public constructor) throws out of dispatch

    @Test fun pasteOfInconsistentClipboardReturnsAFailure() {
        val e = session(); val p = e.state.value.project
        val clip = BarClipboard.copy(p, listOf(p.measures[0].id, p.measures[1].id), listOf(p.tracks[0].id))
        val broken = clip.copy(tracks = clip.tracks.map { it.copy(bars = it.bars.take(1)) })
        val r = runCatching { e.dispatch(EditorCommand.PasteBars(broken, p.measures[0].id, p.tracks[0].id)) }
        assertTrue("dispatch threw ${r.exceptionOrNull()}", r.isSuccess && r.getOrNull() is Outcome.Failure)
    }

    // ---- Side observation: MoveTrack delta overflow

    @Test fun moveTrackByIntMaxMovesToTheEnd() {
        val e = session(); val first = e.state.value.project.tracks[0].id
        e.ok(EditorCommand.MoveTrack(e.state.value.project.tracks[1].id, Int.MAX_VALUE))
        assertEquals("moving the second track by Int.MAX_VALUE must not put it first (index + delta overflows, EditorSession.kt:158)", first, e.state.value.project.tracks[0].id)
    }
}
