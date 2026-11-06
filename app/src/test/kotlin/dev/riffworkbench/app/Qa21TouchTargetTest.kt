package dev.riffworkbench.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.runBlocking
import dev.riffworkbench.model.InstrumentKind
import dev.riffworkbench.model.Outcome
import dev.riffworkbench.model.Project
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import kotlin.math.max
import kotlin.math.min

/**
 * QA 21 (Bedienung) — UNVERIFIED (Robolectric, CI only; no Android SDK in the QA environment). Touch-target audit of the
 * core screens on phone sizes: library (empty and with the create card), editor (header, track chip, durations,
 * techniques, fret pad in both ranges, cursor keys), tools sheet, signature/tempo sheet, "all areas" sheet, drums (with a
 * selected hit and a kit element), mixer with effect chain, instruments/tuning and practice.
 *
 * Collected: every semantics node (merged tree) of the topmost window with a click, progress (slider) or text-input action;
 * nodes that cover ≥ 85 % of the window (sheet scrims) are left out. Pure focus containers without an action (the
 * workspace's keyboard-shortcut column) are not targets. Score canvas and bar map are no targets either: the canvas has no
 * click action (it hit-tests fret numbers itself, see ScoreView), the bar map clears its semantics (overview strip with
 * 16-dp bars; reachable alternatives: arrows, PageUp/Down, bar info) — documented in the QA journal, not changed.
 *
 * Rules per screen (sizes in dp, `layout` = the node's unclipped layout bounds):
 *  R1 effective touch size ≥ [FLOOR] (40, the project's minimum from QA 20; Material recommends 48) in both axes. Effective =
 *     layout size extended towards [MIN_TOUCH] (48, Compose's ViewConfiguration.minimumTouchTargetSize: pointer input of a
 *     smaller node also hits up to 48 dp, the nearest node wins) but at most to half the gap to the next target on that side.
 *     The internal SemanticsNode.touchBoundsInRoot is not public API; this mirrors it plus the "nearest wins" split.
 *  R2 visible size ≥ [VISUAL_FLOOR] (24): squeezed or zero-height controls (QA 21: the settings button of the "all areas"
 *     sheet got 0 dp on a 640-dp phone).
 *  R3 no two targets overlap (clipped bounds, more than 1 dp², ancestor/descendant pairs excepted: a row with a delete
 *     button inside).
 *  R4 no target lies (partly) outside the window or is clipped away unless an ancestor scrolls (scroll action / axis range)
 *     or the window is a sheet that can still be expanded.
 *  R5 every target has a label: text, content description or editable text, merged or in its subtree.
 *
 * A violation fails with the complete list (screen, rule, node, sizes). Targets that pass R1 only through the touch
 * extension (visible < 40 dp) and all targets below 48 dp are printed as information (`QA21 info …`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa21TouchTargetTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var container: AppContainer
    private val violations = mutableListOf<String>()
    private val info = mutableListOf<String>()
    private var config = ""

    @Before fun setup() { container = (compose.activity.application as RiffApplication).container }

    @Test @Config(sdk = [35], qualifiers = "de-rDE-w360dp-h640dp", application = CleanTestApplication::class)
    fun phone360() = auditFlow("360×640, Schrift 1,0")

    @Test @Config(sdk = [35], qualifiers = "de-rDE-w360dp-h640dp", fontScale = 1.3f, application = CleanTestApplication::class)
    fun phone360Font13() = auditFlow("360×640, Schrift 1,3")

    @Test @Config(sdk = [35], qualifiers = "de-rDE-w360dp-h640dp", fontScale = 2.0f, application = CleanTestApplication::class)
    fun phone360Font20() = auditFlow("360×640, Schrift 2,0")

    @Test @Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", application = CleanTestApplication::class)
    fun phone411() = auditFlow("411×891, Schrift 1,0")

    @Test @Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", fontScale = 2.0f, application = CleanTestApplication::class)
    fun phone411Font20() = auditFlow("411×891, Schrift 2,0")

    // ------------------------------------------------------------------ flow

    private fun auditFlow(name: String) {
        config = name
        compose.waitForIdle()
        audit("Bibliothek (leer)")
        compose.onNodeWithTag("nav-new").performClick()
        compose.waitForIdle()
        audit("Bibliothek: Neues Projekt")
        compose.onNode(hasSetTextAction()).performTextInput("Audit")
        compose.onNodeWithText("Anlegen").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        audit("Editor (leer)")
        compose.onNodeWithTag("fret-5").performClick()
        compose.waitForIdle()
        audit("Editor (Note gesetzt)")
        compose.onNodeWithTag("fret-range").performClick()
        compose.waitForIdle()
        audit("Editor (Bünde 12+)")
        compose.onNodeWithTag("fret-range").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("tools-more").performClick()
        openedSheet("Werkzeuge-Sheet")
        closeSheet()

        compose.onNodeWithTag("bar-info").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("signature-sheet").fetchSemanticsNodes().isNotEmpty() }
        openedSheet("Taktangabe-Sheet")
        compose.onNodeWithTag("signature-done").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("nav-more").performClick()
        openedSheet("Alle Bereiche")
        page("drums")
        audit("Drums")
        // A hit in the first cell (selected hit: velocity slider and techniques) and one kit element (level/pan sliders).
        val kick = drumElementName()
        compose.onAllNodes(hasContentDescription("$kick, 1,", substring = true) and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText(kick) and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        audit("Drums (Schlag und Kit-Element gewählt)")

        compose.onNodeWithTag("nav-more").performClick()
        compose.waitForIdle()
        page("mixer")
        audit("Mixer/FX")

        compose.onNodeWithTag("nav-more").performClick()
        compose.waitForIdle()
        page("instruments")
        // The drum cell above selected the drum track; the tuning card belongs to the guitar.
        compose.onAllNodes(hasText("Gitarre") and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        audit("Instrumente/Stimmung")

        compose.onNodeWithTag("nav-practice").performClick()
        compose.waitForIdle()
        audit("Üben")

        compose.onNodeWithTag("nav-editor").performClick()
        compose.waitForIdle()
        // Track chip → drum track: the editor shows the note keypad instead of the fret pad.
        compose.onAllNodes(hasText("Gitarre", substring = true) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText("Drums") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        audit("Editor (Drum-Spur: Notenfeld)")
        compose.onNodeWithContentDescription("Zurück: Projekte").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("nav-new").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        audit("Bibliothek (1 Projekt)")

        info.forEach { println("QA21 info [$config] $it") }
        check(violations.isEmpty()) { "QA21 touch-target audit [$config]: ${violations.size} violation(s)\n" + violations.joinToString("\n") }
    }

    private fun page(route: String) {
        compose.onNodeWithTag("page-$route").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun drumElementName(): String = runBlocking {
        val p = (container.projects.open(container.projects.projects.value.single().id) as Outcome.Success<Project>).value
        p.tracks.first { it.instrument.kind == InstrumentKind.PERCUSSION }.instrument.drumKit.first().name
    }

    /** Audits a freshly opened sheet as it opens and, if it can be expanded, expanded. */
    private fun openedSheet(screen: String) {
        compose.waitForIdle()
        audit("$screen (geöffnet)")
        topNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)).firstOrNull()?.let { n ->
            runCatching { interaction(n).performSemanticsAction(SemanticsActions.Expand) }
            compose.waitForIdle()
            audit("$screen (aufgezogen)")
        }
    }

    /** Closes the topmost sheet: its dismiss action (drag handle), else the scrim's click action. */
    private fun closeSheet() {
        val dismiss = topNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).firstOrNull()
        if (dismiss != null) interaction(dismiss).performSemanticsAction(SemanticsActions.Dismiss)
        else {
            val window = topRoot()?.boundsInRoot ?: return
            topNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).firstOrNull { coversWindow(it.boundsInRoot, window) }
                ?.let { interaction(it).performSemanticsAction(SemanticsActions.OnClick) }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ tree helpers

    private fun topRoot(): SemanticsNode? = compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).maxByOrNull { it.id }

    /** Nodes matching [matcher] in the topmost window (sheets and popups are composed after the activity content). */
    private fun topNodes(matcher: SemanticsMatcher): List<SemanticsNode> {
        val top = topRoot()?.layoutInfo ?: return emptyList()
        return compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).filter { n -> generateSequence(n) { it.parent }.last().layoutInfo === top }
    }

    private fun interaction(n: SemanticsNode): SemanticsNodeInteraction {
        val id = n.id; val bounds = n.boundsInRoot
        return compose.onNode(SemanticsMatcher("node $id at $bounds") { it.id == id && it.boundsInRoot == bounds })
    }

    private fun coversWindow(r: Rect, window: Rect) = r.width * r.height >= 0.85f * window.width * window.height

    private fun ancestors(n: SemanticsNode) = generateSequence(n.parent) { it.parent }

    private fun labelled(c: SemanticsConfiguration): Boolean =
        c.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true ||
            c.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true ||
            c.getOrNull(SemanticsProperties.EditableText)?.text?.isNotBlank() == true

    private fun labelledSubtree(n: SemanticsNode, depth: Int = 0): Boolean = labelled(n.config) || (depth < 6 && n.children.any { labelledSubtree(it, depth + 1) })

    private fun scrollsOrExpands(c: SemanticsConfiguration) = SemanticsActions.ScrollBy in c || SemanticsProperties.HorizontalScrollAxisRange in c ||
        SemanticsProperties.VerticalScrollAxisRange in c

    private fun describe(n: SemanticsNode): String {
        val c = n.config
        return listOfNotNull(
            c.getOrNull(SemanticsProperties.TestTag)?.let { "tag=$it" },
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")?.let { "desc=\"${it.take(50)}\"" },
            c.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }?.let { "text=\"${it.take(50)}\"" },
            if (SemanticsProperties.Disabled in c) "disabled" else null,
        ).joinToString(" ").ifEmpty { "node#${n.id}" }
    }

    // ------------------------------------------------------------------ audit

    private class Target(val node: SemanticsNode, val layout: Rect, val clipped: Rect)

    private fun audit(screen: String) {
        compose.waitForIdle()
        val root = topRoot() ?: return
        val window = root.boundsInRoot
        val d = compose.density.density
        val actionable = SemanticsMatcher("click, slider or text input") { n ->
            SemanticsActions.OnClick in n.config || SemanticsActions.SetProgress in n.config || SemanticsActions.SetText in n.config
        }
        val sheetCanExpand = topNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)).isNotEmpty()
        val targets = topNodes(actionable).map { Target(it, Rect(it.positionInRoot, it.size.toSize()), it.boundsInRoot) }.filterNot { coversWindow(it.layout, window) }
        fun dp(px: Float) = "%.1f".format(java.util.Locale.ROOT, px / d)
        fun related(a: Target, b: Target) = ancestors(a.node).any { it.id == b.node.id } || ancestors(b.node).any { it.id == a.node.id }
        val minTouch = MIN_TOUCH * d
        for (t in targets) {
            val r = t.layout
            val name = describe(t.node)
            val others = targets.filter { it !== t && !related(it, t) }
            // Gaps to the nearest target on each side whose span overlaps along the other axis.
            fun gap(side: Int): Float? = others.mapNotNull { o ->
                val q = o.layout
                val overlapsVertically = min(q.bottom, r.bottom) - max(q.top, r.top) > 0.5f
                val overlapsHorizontally = min(q.right, r.right) - max(q.left, r.left) > 0.5f
                when (side) {
                    0 -> if (overlapsVertically && q.right <= r.left + 0.5f) r.left - q.right else null
                    1 -> if (overlapsVertically && q.left >= r.right - 0.5f) q.left - r.right else null
                    2 -> if (overlapsHorizontally && q.bottom <= r.top + 0.5f) r.top - q.bottom else null
                    else -> if (overlapsHorizontally && q.top >= r.bottom - 0.5f) q.top - r.bottom else null
                }
            }.minOrNull()
            fun effective(len: Float, before: Float?, after: Float?): Float {
                val e = max(0f, (minTouch - len) / 2)
                return len + (before?.let { min(e, max(0f, it) / 2) } ?: e) + (after?.let { min(e, max(0f, it) / 2) } ?: e)
            }
            val ew = effective(r.width, gap(0), gap(1))
            val eh = effective(r.height, gap(2), gap(3))
            val sizes = "layout ${dp(r.width)}×${dp(r.height)}, effective ${dp(ew)}×${dp(eh)}"
            // R2 before R1: a squeezed control would otherwise pass through the 48-dp extension.
            if (min(r.width, r.height) < VISUAL_FLOOR * d) violations += "[$screen] R2 visible size < $VISUAL_FLOOR dp: $name ($sizes)"
            else if (min(ew, eh) < FLOOR * d) violations += "[$screen] R1 touch target < $FLOOR dp: $name ($sizes)"
            else {
                if (min(r.width, r.height) < FLOOR * d) info += "[$screen] visible < $FLOOR dp, touch extension makes it ${dp(min(ew, eh))}: $name ($sizes)"
                if (min(ew, eh) < MIN_TOUCH * d) info += "[$screen] below Material's $MIN_TOUCH dp: $name ($sizes)"
            }
            // R4: outside the window / clipped, without a way to scroll it into view.
            val outside = r.left < window.left - 1f || r.top < window.top - 1f || r.right > window.right + 1f || r.bottom > window.bottom + 1f
            val clippedAway = t.clipped.width * t.clipped.height < 0.98f * r.width * r.height - 1f
            if ((outside || clippedAway) && ancestors(t.node).none { scrollsOrExpands(it.config) }) {
                if (sheetCanExpand) info += "[$screen] outside the partially expanded sheet (reachable by expanding it): $name"
                else violations += "[$screen] R4 ${if (outside) "outside the window" else "clipped"} without a scroll container: $name (layout $r, visible ${t.clipped}, window $window)"
            }
            // R5
            if (!labelledSubtree(t.node)) violations += "[$screen] R5 no label (text or content description): $name ($sizes)"
        }
        // R3: overlapping visible bounds.
        for (i in targets.indices) for (j in i + 1 until targets.size) {
            val a = targets[i]; val b = targets[j]
            if (related(a, b) || a.clipped.isEmpty || b.clipped.isEmpty) continue
            val x = a.clipped.intersect(b.clipped)
            if (x.width > 0f && x.height > 0f && x.width * x.height > d * d) violations += "[$screen] R3 overlap ${dp(x.width)}×${dp(x.height)}: ${describe(a.node)} ⟷ ${describe(b.node)}"
        }
        println("QA21 [$config] $screen: ${targets.size} targets")
    }

    private companion object {
        const val FLOOR = 40f
        const val MIN_TOUCH = 48f
        const val VISUAL_FLOOR = 24f
    }
}
