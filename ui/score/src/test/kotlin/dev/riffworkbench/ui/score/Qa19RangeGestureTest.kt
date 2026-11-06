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
 * Zyklus 19 / Bericht 1 §5 — UNVERIFIED (Robolectric; lokal nicht ausführbar, kein Android-Toolchain offline, nur CI).
 * Langes Drücken auf einen Takt startet über [ScoreCanvas.onRangeStart] eine Bereichsauswahl, Ziehen meldet jeden
 * Taktwechsel über [ScoreCanvas.onRangeExtend], Loslassen/Abbruch [ScoreCanvas.onRangeEnd]. Die Taktangabe behält Vorrang
 * (Signatur-Sheet), ein Tippen bleibt ein Tippen, und auf der ausgewählten Note bleibt Halten+Ziehen ein Noten-Drag.
 * Haptik (LongPress / TextHandleMove) wird hier nicht gemessen (Gerätetest offen).
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default window is 320 × 470 dp: Modifier.size(600.dp, 1200.dp) is clamped to it, and the bar centres
// (x ≈ 321 dp, rows at y ≈ 90/300/511/721 dp) fall outside the node, so their touches never arrive (first CI run red).
@Config(sdk = [35], qualifiers = "w700dp-h1300dp")
class Qa19RangeGestureTest {
    @get:Rule val compose = createComposeRule()
    private var seq = 0
    private val ids = IdSource { EntityId("rg-${seq++}") }
    private val taps = mutableListOf<ScoreTap>()
    private val signatures = mutableListOf<EntityId>()
    private val started = mutableListOf<EntityId>()
    private val extended = mutableListOf<EntityId>()
    private var ended = 0
    private val moves = mutableListOf<Pair<ScoreTap, ScoreTap>>()
    private lateinit var project: Project
    private lateinit var layout: ScoreLayout

    /**
     * Standardprojekt + drei Takte (vier leere 4/4-Takte), TAB, auf 560 Einheiten umbrochen: das ganze Layout passt in die
     * 600 × 1200 dp der Fläche (Layout-Einheiten × Dichte = px bei Zoom 1), der Cursor-Effekt scrollt also nicht.
     * Mit [note] steht in Takt 1 auf Schlag 1 eine Note auf Saite 3, der Cursor darauf (Drag-Griff).
     */
    private fun setUp(note: Boolean = false) {
        val e = EditorSession(ProjectFactory(ids).create("Range", withBand = false), ids)
        repeat(3) { e.dispatch(EditorCommand.AddMeasure(TimeSignature(4, 4))) }
        if (note) {
            e.select(e.state.value.selection.copy(measureId = e.state.value.project.measures[0].id, offset = Fraction.ZERO, string = 2))
            e.dispatch(EditorCommand.PutNote(fret = 5, duration = WrittenDuration(4)))
        }
        project = e.state.value.project
        layout = ScoreLayouter.layout(LayoutRequest(project, 0, width = 560f, editGrid = Fraction.of(1, 16), margin = 6f))
        assertTrue("Layout passt in die Fläche: ${layout.width} × ${layout.height}", layout.width <= 600f && layout.height <= 1200f)
        val drag: (ScoreTap, ScoreTap) -> Unit = { a, b -> moves += a to b }
        compose.setContent {
            ScoreCanvas(layout = layout, cursor = ScoreCursor(project.measures[0].id, Fraction.ZERO, if (note) 2 else 0), playhead = null, follow = false, zoom = 1f,
                onZoom = {}, onTap = { taps += it }, modifier = Modifier.size(600.dp, 1200.dp).testTag("score"),
                onMoveNote = drag.takeIf { note },
                onSignatureLongPress = { signatures += it },
                onRangeStart = { started += it }, onRangeExtend = { extended += it; true }, onRangeEnd = { ended++ })
        }
    }

    /** Mitte des Notenbereichs von Takt [i] (rechts der Taktangabe, Mitte der TAB) in px. */
    private fun barCentre(i: Int, fraction: Float = 0.5f): Offset {
        val m = layout.measures[i]
        val sys = layout.systems[m.system]
        val d = compose.density.density
        val x = m.contentX + (m.x + m.width - m.contentX) * fraction
        val y = (sys.tabTop + sys.tabBottom) / 2
        assertNull("kein Signaturtreffer in Takt ${i + 1}", layout.signatureAt(x, y))
        assertEquals(m.measureId, layout.measureAt(x, y)?.measureId)
        return Offset(x * d, y * d)
    }

    @Test fun longPressOnABarStartsARangeAtThatBarAndIsNoTap() {
        setUp()
        compose.onNodeWithTag("score").performTouchInput { longClick(barCentre(1)) }
        compose.waitForIdle()
        assertEquals("Anker = gedrückter Takt", listOf(project.measures[1].id), started)
        assertTrue("ohne Ziehen kein Taktwechsel: $extended", extended.isEmpty())
        assertEquals("Loslassen beendet die Geste", 1, ended)
        assertTrue("ein langer Druck ist kein Tippen: $taps", taps.isEmpty())
        assertTrue(signatures.isEmpty())
    }

    @Test fun dragAfterTheLongPressReportsEachBarChangeOnceAndReleaseEnds() {
        setUp()
        val a = barCentre(0); val b = barCentre(1); val b2 = barCentre(1, 0.8f); val c = barCentre(2); val back = barCentre(1, 0.3f)
        compose.onNodeWithTag("score").performTouchInput {
            down(a)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(a + Offset(1f, 0f))
            moveTo(b); moveTo(b2); moveTo(c); moveTo(back)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(project.measures[0].id), started)
        assertEquals("taktweise, je Wechsel genau einmal (auch zurück)", listOf(project.measures[1].id, project.measures[2].id, project.measures[1].id), extended)
        assertEquals(1, ended)
        assertTrue("kein Tippen: $taps", taps.isEmpty())
    }

    @Test fun aCancelledRangeDragEndsTheGesture() {
        setUp()
        val a = barCentre(0); val b = barCentre(1)
        compose.onNodeWithTag("score").performTouchInput {
            down(a)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(b)
            cancel()
        }
        compose.waitForIdle()
        assertEquals(listOf(project.measures[0].id), started)
        assertEquals(listOf(project.measures[1].id), extended)
        assertEquals("Abbruch beendet die Geste wie Loslassen", 1, ended)
    }

    @Test fun theTimeSignatureKeepsPriority() {
        setUp()
        val box = layout.signatures.first()
        val d = compose.density.density
        compose.onNodeWithTag("score").performTouchInput { longClick(Offset((box.x + box.w / 2) * d, (box.y + box.h / 2) * d)) }
        compose.waitForIdle()
        assertEquals("Taktangabe öffnet weiter das Sheet", listOf(box.measureId), signatures)
        assertTrue("und startet keinen Bereich: $started", started.isEmpty())
        assertEquals(0, ended)
    }

    @Test fun aPlainTapIsStillATap() {
        setUp()
        compose.onNodeWithTag("score").performTouchInput { click(barCentre(2)) }
        compose.waitForIdle()
        assertTrue("kein Bereich bei einem Tippen: $started", started.isEmpty())
        assertEquals(0, ended)
        assertEquals(project.measures[2].id, taps.lastOrNull()?.measureId)
    }

    @Test fun holdAndDragOnTheSelectedNoteStillMovesTheNote() {
        setUp(note = true)
        val box = layout.notes.first()
        val d = compose.density.density
        val at = Offset((box.x + box.w / 2) * d, (box.y + box.h / 2) * d)
        compose.onNodeWithTag("score").performTouchInput {
            down(at)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(1f, 0f)); moveBy(Offset(40f, 0f)); moveBy(Offset(40f, 0f))
            up()
        }
        compose.waitForIdle()
        assertTrue("auf dem Drag-Griff startet kein Bereich: $started", started.isEmpty())
        assertEquals("Halten + Ziehen verschiebt die Note wie bisher: $moves", 1, moves.size)
    }
}
