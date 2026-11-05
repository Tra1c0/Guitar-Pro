package dev.riffworkbench.ui.score

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.LayoutRequest
import dev.riffworkbench.notation.ScoreLayouter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA 13 — UNVERIFIED (Robolectric; not run locally, no Android toolchain offline). Review of b7041dc, ScoreView.kt:151-177.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Qa13DragGestureTest {
    @get:Rule val compose = createComposeRule()
    private var seq = 0
    private val ids = IdSource { EntityId("g-${seq++}") }
    private val moves = mutableListOf<Pair<ScoreTap, ScoreTap>>()
    private val zooms = mutableListOf<Float>()
    private val taps = mutableListOf<ScoreTap>()
    private var diag = ""

    private fun setUp(): Offset {
        val e = EditorSession(ProjectFactory(ids).create("Drag", withBand = false), ids)
        e.select(e.state.value.selection.copy(string = 2)); e.dispatch(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        val p = e.state.value.project
        val layout = ScoreLayouter.layout(LayoutRequest(p, 0, editGrid = Fraction.of(1, 16)))
        val box = layout.notes.first()
        compose.setContent {
            ScoreCanvas(layout = layout, cursor = ScoreCursor(p.measures[0].id, Fraction.ZERO, 2), playhead = null, follow = false, zoom = 1f,
                onZoom = { zooms += it }, onTap = { taps += it }, modifier = Modifier.size(600.dp, 1200.dp).testTag("score"), onMoveNote = { a, b -> moves += a to b })
        }
        val d = compose.density.density
        diag = "box=$box density=$d slop?"
        return Offset((box.x + box.w / 2) * d, (box.y + box.h / 2) * d)
    }

    /** Control: drag + release moves the note. */
    @Test fun dragAndReleaseMoves() {
        val at = setUp()
        // Sanity: a plain tap at the same point hits the note (the viewport is tall enough not to scroll).
        compose.onNodeWithTag("score").performTouchInput { click(at) }
        compose.waitForIdle()
        assertTrue("tap must hit the note: taps=$taps at=$at $diag", taps.lastOrNull()?.noteId != null)
        compose.onNodeWithTag("score").performTouchInput { down(at); moveBy(Offset(30f, 0f)); moveBy(Offset(30f, 0f)); up() }
        compose.waitForIdle()
        assertEquals("moves=$moves taps=$taps zooms=$zooms at=$at $diag", 1, moves.size)
    }

    /**
     * ACTION_CANCEL (system gesture, parent intercept, dialog/window focus loss) reaches pointerInput as a synthetic event
     * with pressed = false (consumed). The loop at ScoreView.kt:165 only checks `!change.pressed`, so a cancelled drag is
     * committed as a drop instead of being abandoned.
     */
    @Test fun cancelledDragDoesNotMoveTheNote() {
        val at = setUp()
        compose.onNodeWithTag("score").performTouchInput { down(at); moveBy(Offset(60f, 0f)); moveBy(Offset(60f, 0f)); cancel() }
        compose.waitForIdle()
        assertTrue("a cancelled gesture must not drop the note: $moves", moves.isEmpty())
    }
    // Known limitation: a pinch whose first finger passes the touch slop on the selected note starts a drag (journal, cycle 13).
}
