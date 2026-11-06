package dev.riffworkbench.ui.score

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.DRAG_MIN_TRAVEL
import dev.riffworkbench.notation.LayoutRequest
import dev.riffworkbench.notation.ScoreLayout
import dev.riffworkbench.notation.ScoreLayouter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Zyklus 23 / P0 „Noten werden unbemerkt verschoben“ — UNVERIFIED (Robolectric; lokal nicht ausführbar, nur CI).
 * Die Regel selbst ist in core:notation geprüft (Qa23FineGridHitTest, [ScoreLayout.dropTarget]); hier nur die Verdrahtung in
 * [ScoreCanvas]: ein Verrutschen über die Touch-Slop hinaus, aber innerhalb der Totzone, verschiebt die ausgewählte Note
 * nicht; ein Zug um mehrere Spalten verschiebt sie weiter; ein Tippen neben einer Bundzahl auf den leeren 1/32-Platz wählt
 * den Platz ([ScoreLayout.tapAt]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w700dp-h1300dp")
class Qa23NoteSlipGestureTest {
    @get:Rule val compose = createComposeRule()
    private var seq = 0
    private val ids = IdSource { EntityId("slip-${seq++}") }
    private val moves = mutableListOf<Pair<ScoreTap, ScoreTap>>()
    private val taps = mutableListOf<ScoreTap>()
    private lateinit var layout: ScoreLayout

    /** One bar, 1/32 grid, a 1/32 note "12" on string 3 at beat 1, the cursor on it. */
    private fun setUp(): Offset {
        val e = EditorSession(ProjectFactory(ids).create("Slip", withBand = false), ids)
        e.select(e.state.value.selection.copy(string = 2)); e.dispatch(EditorCommand.PutNote(fret = 12, duration = WrittenDuration(32)))
        val p = e.state.value.project
        layout = ScoreLayouter.layout(LayoutRequest(p, 0, width = 360f, editGrid = Fraction.of(1, 32), margin = 6f))
        compose.setContent {
            ScoreCanvas(layout = layout, cursor = ScoreCursor(p.measures[0].id, Fraction.ZERO, 2), playhead = null, follow = false, zoom = 1f,
                onZoom = {}, onTap = { taps += it }, modifier = Modifier.size(600.dp, 1200.dp).testTag("score"), onMoveNote = { a, b -> moves += a to b })
        }
        val box = layout.notes.single()
        val d = compose.density.density
        return Offset((box.x + box.w / 2) * d, (box.y + box.h / 2) * d)
    }

    @Test fun aSlipInsideTheDeadZoneDoesNotMoveTheNote() {
        val at = setUp()
        val d = compose.density.density
        val slip = (DRAG_MIN_TRAVEL - 2f) * d                     // past the touch slop (8 dp), short of the dead zone
        compose.onNodeWithTag("score").performTouchInput { down(at); moveBy(Offset(slip / 2, 0f)); moveBy(Offset(slip / 2, 0f)); up() }
        compose.waitForIdle()
        assertTrue("no move: $moves", moves.isEmpty())
    }

    @Test fun aDeliberateDragStillMoves() {
        val at = setUp()
        val d = compose.density.density
        compose.onNodeWithTag("score").performTouchInput { down(at); moveBy(Offset(20f * d, 0f)); moveBy(Offset(20f * d, 0f)); up() }
        compose.waitForIdle()
        assertEquals(1, moves.size)
        assertNotEquals(Fraction.ZERO, moves.single().second.offset)
    }

    @Test fun aTapOnTheEmptySlotBesideTheNumberSelectsTheSlot() {
        setUp()
        val box = layout.notes.single()
        val next = layout.columns.first { it.offset == Fraction.of(1, 32) }
        val d = compose.density.density
        val x = box.x + box.w - 0.5f
        val y = layout.systems[0].stringY[2]
        assumeCloser(x, next.x, box.x + box.w / 2)
        compose.onNodeWithTag("score").performTouchInput { click(Offset(x * d, y * d)) }
        compose.waitForIdle()
        assertEquals(Fraction.of(1, 32), taps.lastOrNull()?.offset)
        assertNull(taps.last().noteId)
    }
    private fun assumeCloser(x: Float, slot: Float, centre: Float) = assertTrue("precondition", kotlin.math.abs(slot - x) < kotlin.math.abs(centre - x))
}
