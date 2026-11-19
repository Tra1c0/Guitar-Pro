package dev.riffworkbench.ui.score

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.riffworkbench.editor.*
import dev.riffworkbench.model.*
import dev.riffworkbench.notation.CursorSnap
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
 * Zyklus 30 „Cursor selbst schieben“ — Robolectric, nur CI (lokal als Compose-Desktop-Variante). Die Taktnummern-Zone über
 * einem System ist eine Positionierfläche: Ziehen/Tippen dort meldet genau EINE Position beim Loslassen
 * ([ScoreCanvas.onPosition], eingerastet auf den nächsten Noten-Onset im Fangradius, sonst die nächste Spalte), wählt
 * keine Note, verschiebt keine, startet keinen Bereich. Außerhalb der Zone bleiben Tippen, Noten-Drag und Bereich wie bisher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w700dp-h1300dp")
class Qa30PositionLaneGestureTest {
    @get:Rule val compose = createComposeRule()
    private var seq = 0
    private val ids = IdSource { EntityId("pl-${seq++}") }
    private val taps = mutableListOf<ScoreTap>()
    private val positions = mutableListOf<Pair<EntityId, Fraction>>()
    private var starts = 0
    private val ranges = mutableListOf<EntityId>()
    private val moves = mutableListOf<Pair<ScoreTap, ScoreTap>>()
    private lateinit var project: Project
    private lateinit var layout: ScoreLayout

    /** Four 4/4 bars, TAB, wrapped to 560 units (fits 600 × 1200 dp); a note on string 3 at 1/4 of bar 2, the cursor on it. */
    private fun setUp() {
        val e = EditorSession(ProjectFactory(ids).create("Lane", withBand = false), ids)
        repeat(3) { e.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        e.select(e.state.value.selection.copy(measureId = e.state.value.project.measures[1].id, offset = Fraction.of(1, 4), string = 2))
        e.dispatch(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        project = e.state.value.project
        layout = ScoreLayouter.layout(LayoutRequest(project, 0, width = 560f, editGrid = Fraction.of(1, 16), margin = 6f))
        assertTrue(layout.width <= 600f && layout.height <= 1200f)
        compose.setContent {
            ScoreCanvas(layout = layout, cursor = ScoreCursor(project.measures[1].id, Fraction.of(1, 4), 2), playhead = null, follow = false, zoom = 1f,
                onZoom = {}, onTap = { taps += it }, modifier = Modifier.size(600.dp, 1200.dp).testTag("score"),
                onMoveNote = { a, b -> moves += a to b }, onRangeStart = { ranges += it }, onRangeExtend = { true }, onRangeEnd = {},
                onPositionStart = { starts++ }, onPosition = { m, o -> positions += m to o })
        }
    }
    private fun px(x: Float, y: Float) = compose.density.density.let { Offset(x * it, y * it) }
    /** A point in the number lane above bar [i] at [fraction] of its width. */
    private fun lane(i: Int, fraction: Float): Offset {
        val m = layout.measures[i]; val sys = layout.systems[m.system]
        val y = sys.top + 3f
        assertNotNull(CursorSnap.laneAt(layout, y))
        return px(m.contentX + (m.x + m.width - m.contentX) * fraction, y)
    }
    private fun noteX(): Float = layout.xFor(project.measures[1].id, Fraction.of(1, 4))!!

    @Test fun l1_dragInTheLaneSnapsOntoTheNoteAndReportsOnceOnRelease() {
        setUp()
        val sys = layout.systems[layout.measures[1].system]
        val target = px(noteX() + 6f, sys.top + 3f)
        compose.onNodeWithTag("score").performTouchInput { down(lane(0, 0.3f)); moveTo(lane(0, 0.8f)); moveTo(lane(1, 0.1f)); moveTo(target); up() }
        compose.waitForIdle()
        assertEquals(1, starts)
        assertEquals("one position, on the note onset", listOf(project.measures[1].id to Fraction.of(1, 4)), positions)
        assertTrue("no tap/selection: $taps", taps.isEmpty())
        assertTrue("no note drag: $moves", moves.isEmpty())
        assertTrue("no range: $ranges", ranges.isEmpty())
    }

    @Test fun l2_farFromNotesItSnapsToTheNearestColumn() {
        setUp()
        val m = layout.measures[2]
        val column = layout.columns.filter { it.measureId == m.measureId }.sortedBy { it.x }[5]
        val sys = layout.systems[m.system]
        compose.onNodeWithTag("score").performTouchInput { click(px(column.x + 1f, sys.top + 3f)) }
        compose.waitForIdle()
        assertEquals(listOf(m.measureId to column.offset), positions)
        assertTrue(taps.isEmpty())
    }

    @Test fun l3_aLongPressInTheLaneIsNoRangeAndOutsideTheLaneTapsStayTaps() {
        setUp()
        compose.onNodeWithTag("score").performTouchInput { longClick(lane(2, 0.5f)) }
        compose.waitForIdle()
        assertTrue("no range from the lane: $ranges", ranges.isEmpty())
        assertEquals(1, positions.size)
        // On the TAB of bar 3: a tap as before, no positioning.
        val m = layout.measures[2]; val sys = layout.systems[m.system]
        compose.onNodeWithTag("score").performTouchInput { click(px(m.contentX + (m.x + m.width - m.contentX) * 0.5f, (sys.tabTop + sys.tabBottom) / 2)) }
        compose.waitForIdle()
        assertEquals(1, taps.size)
        assertEquals(1, positions.size)
    }
}
