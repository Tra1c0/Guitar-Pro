package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

/** Undo history must share unchanged parts of the score (QA cycle 5: every edit copied the whole tree). */
class HistorySharingRegressionTest {
    private var n = 0
    private val ids = IdSource { EntityId("h-${n++}") }

    @Test fun anEditKeepsEveryOtherBarAsTheSameInstance() {
        val e = EditorSession(ProjectFactory(ids).create("Big"), ids)
        assertTrue(e.dispatch(EditorCommand.InsertMeasures(2, 200)) is Outcome.Success)
        val before = e.state.value.project
        assertTrue(e.dispatch(EditorCommand.PutNote(fret = 3)) is Outcome.Success)
        val after = e.state.value.project
        for ((t, track) in after.tracks.withIndex()) {
            val oldBars = before.tracks[t].staves[0].bars; val newBars = track.staves[0].bars
            val shared = newBars.indices.count { newBars[it] === oldBars[it] }
            val expected = if (t == 0) newBars.size - 1 else newBars.size
            assertEquals("track $t", expected, shared)
        }
        assertTrue(e.dispatch(EditorCommand.Rename("Renamed")) is Outcome.Success)
        assertSame(after.tracks, e.state.value.project.tracks)
    }

    @Test fun undoRestoresWithoutRevalidatingAndKeepsDiagnostics() {
        val e = EditorSession(ProjectFactory(ids).create("U"), ids)
        val initial = e.state.value.diagnostics
        assertTrue(e.dispatch(EditorCommand.PutNote(fret = 3)) is Outcome.Success)
        assertTrue(e.undo() is Outcome.Success)
        assertEquals(initial, e.state.value.diagnostics)
        assertEquals(ScoreValidator.validate(e.state.value.project), e.state.value.diagnostics)
    }
}
