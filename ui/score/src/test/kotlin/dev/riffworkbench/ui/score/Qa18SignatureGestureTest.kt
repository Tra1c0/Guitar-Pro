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
 * QA 18 / D4 — UNVERIFIED (Robolectric; not run locally, no Android toolchain offline). The long press on a drawn time
 * signature ([ScoreLayout.signatureAt]) reaches [ScoreCanvas.onSignatureLongPress] from the same detectTapGestures as the
 * tap, so a plain tap there still selects beat 0 of the bar and the pan/pinch/drag detectors keep their order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Qa18SignatureGestureTest {
    @get:Rule val compose = createComposeRule()
    private var seq = 0
    private val ids = IdSource { EntityId("sg-${seq++}") }
    private val taps = mutableListOf<ScoreTap>()
    private val pressed = mutableListOf<EntityId>()
    private lateinit var project: Project
    private lateinit var layout: ScoreLayout

    /** Default project (4/4 then 7/8), TAB only, as the editor lays it out; returns the centre of bar 1's signature in px. */
    private fun setUp(): Offset {
        project = ProjectFactory(ids).create("Signature", withBand = false)
        layout = ScoreLayouter.layout(LayoutRequest(project, 0, editGrid = Fraction.of(1, 16), margin = 6f))
        compose.setContent {
            ScoreCanvas(layout = layout, cursor = ScoreCursor(project.measures[0].id, Fraction.ZERO, 0), playhead = null, follow = false, zoom = 1f,
                onZoom = {}, onTap = { taps += it }, modifier = Modifier.size(600.dp, 1200.dp).testTag("score"), onSignatureLongPress = { pressed += it })
        }
        val box = layout.signatures.first()
        assertEquals(project.measures[0].id, box.measureId)
        val d = compose.density.density
        return Offset((box.x + box.w / 2) * d, (box.y + box.h / 2) * d)
    }

    @Test fun longPressOnTheSignatureReportsThatBarAndNoTap() {
        val at = setUp()
        compose.onNodeWithTag("score").performTouchInput { longClick(at) }
        compose.waitForIdle()
        assertEquals("long press hands over the bar whose signature was pressed", listOf(project.measures[0].id), pressed)
        assertTrue("a long press is not also a tap: $taps", taps.isEmpty())
    }

    @Test fun clickOnTheSignatureStillSelectsBeatZeroOfThatBar() {
        val at = setUp()
        compose.onNodeWithTag("score").performTouchInput { click(at) }
        compose.waitForIdle()
        assertTrue("no long press on a click: $pressed", pressed.isEmpty())
        val tap = taps.lastOrNull()
        assertNotNull("tap must reach onTap at $at", tap)
        assertEquals(project.measures[0].id, tap!!.measureId)
        assertEquals(Fraction.ZERO, tap.offset)
        assertNull(tap.noteId)
    }

    @Test fun longPressOffTheSignatureDoesNothing() {
        val at = setUp()
        val d = compose.density.density
        // Inside bar 1, well right of the signature: a note column, no signature box.
        val m = layout.measures[0]
        val sys = layout.systems[0]
        val inBar = Offset((m.contentX + m.width * 0.5f) * d, (sys.tabTop + sys.tabBottom) / 2 * d)
        assertNull(layout.signatureAt(inBar.x / d, inBar.y / d))
        compose.onNodeWithTag("score").performTouchInput { longClick(inBar) }
        compose.waitForIdle()
        assertTrue("no signature under the finger: $pressed", pressed.isEmpty())
        assertTrue("a held press does not select on release either: $taps", taps.isEmpty())
        assertNotNull(at)
    }
}
