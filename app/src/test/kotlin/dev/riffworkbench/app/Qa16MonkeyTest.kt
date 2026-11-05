package dev.riffworkbench.app

import android.app.Activity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.Collections
import kotlin.random.Random
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * QA cycle 16 — UNVERIFIED (Robolectric, CI only; no Android SDK in the QA environment). Seeded "monkey" over the whole app.
 *
 * Each test method takes one seed, creates a project and then performs [steps] random actions chosen from the semantics
 * tree: clicks (touch and accessibility path), double taps with the clock held (two taps inside one frame), long presses,
 * text input, scrolling, sliders, gestures on the score canvas (tap, swipe, pinch, drag, double tap), system back and
 * rotation (`RuntimeEnvironment.setQualifiers("+land"/"+port")` + `scenario.recreate()`).
 * After every step: no uncaught exception on any thread, the activity is not finishing, and some content node is displayed.
 * System file pickers (ActivityResult launchers) never open under Robolectric; their intents are taken from the shadow,
 * answered with RESULT_CANCELED and logged.
 *
 * Every action is printed as `QA16 seed=… step=…: …`; a failure repeats the last 30 lines. The run is deterministic for a
 * seed as far as the app is (background saves and layout are awaited by [settle]).
 *
 * Parameters (system property, or environment variable for Gradle runs, which do not forward -D to the test JVM):
 * - `qa16.steps` / `QA16_STEPS`: actions per seed, default [DEFAULT_STEPS] (CI budget); the campaign uses 150.
 * - `qa16.seeds` / `QA16_SEEDS`: comma-separated seeds replacing [DEFAULT_SEEDS] (one per test method, at most five).
 *
 * Deliberately excluded (long-running timed jobs, not UI logic): the diagnostics stress test ("1 min"/"10 min") and the
 * blind-comparison render/playback ("Neues Paar", "▶ A", "▶ B").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w411dp-h891dp", application = CleanTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Qa16MonkeyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val uncaught = Collections.synchronizedList(mutableListOf<Pair<String, Throwable>>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val log = mutableListOf<String>()
    private var seed = 0L
    private var landscape = false

    @Before fun installUncaughtHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> uncaught += thread.name to error }
    }

    @After fun cleanUp() {
        // The container (and its audio threads) outlives the test; stop playback so the next seed starts quietly.
        runCatching { val c = (compose.activity.application as RiffApplication).container; c.backgroundScope.launch { c.transport.stop() } }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun seed0() = runSeed(0)
    @Test fun seed1() = runSeed(1)
    @Test fun seed2() = runSeed(2)
    @Test fun seed3() = runSeed(3)
    @Test fun seed4() = runSeed(4)

    /**
     * Static finding of QA 16, reproduced directly: Settings' back button pops unconditionally (MainActivity.kt:140). Two taps
     * inside one frame (or during the exit transition) pop Settings and then the library, the start destination: empty screen.
     * The same pattern was fixed for the project route in cycle 11 (`leave()` pops only its own entry).
     */
    @Test fun doubleTapOnSettingsBackKeepsTheLibrary() {
        compose.onNodeWithTag("nav-settings").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val back = compose.onNodeWithText("‹ Zurück")
        back.performClick()
        back.performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        check(compose.onAllNodesWithTag("nav-new").fetchSemanticsNodes().isNotEmpty()) { "double tap on Settings back popped the library: empty NavHost" }
    }

    // ------------------------------------------------------------------ driver

    private fun runSeed(index: Int) {
        val seeds = (System.getProperty("qa16.seeds") ?: System.getenv("QA16_SEEDS"))?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_SEEDS
        Assume.assumeTrue("no seed for index $index", index < seeds.size)
        val steps = (System.getProperty("qa16.steps") ?: System.getenv("QA16_STEPS"))?.trim()?.toIntOrNull() ?: DEFAULT_STEPS
        seed = seeds[index]
        val rnd = Random(seed)
        record(-1, "start steps=$steps")
        var step = -1
        try {
            createProject()
            settle()
            checkInvariants("project created")
            for (i in 0 until steps) {
                step = i
                performRandomAction(i, rnd)
                settle()
                drainExternalActivities(i)
                checkInvariants("step $i")
            }
            record(steps, "done")
        } catch (t: Throwable) {
            throw AssertionError("QA16 seed=$seed failed at step $step (reproduce: -Dqa16.seeds=$seed -Dqa16.steps=${step + 1})\n" + log.takeLast(30).joinToString("\n"), t)
        }
    }

    private fun createProject() {
        compose.onNodeWithTag("nav-new").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Monkey $seed")
        compose.onNodeWithText("Anlegen").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("fret-0").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun record(step: Int, message: String) {
        val line = "QA16 seed=$seed step=$step: $message"
        log += line
        println(line)
    }

    private fun checkInvariants(context: String) {
        synchronized(uncaught) {
            uncaught.firstOrNull()?.let { (thread, error) -> throw AssertionError("uncaught exception on thread '$thread' after $context", error) }
        }
        check(!compose.activity.isFinishing) { "activity is finishing after $context" }
        val content = SemanticsMatcher("text, description or click action") { n ->
            SemanticsProperties.Text in n.config || SemanticsProperties.ContentDescription in n.config || SemanticsActions.OnClick in n.config
        }
        val all = compose.onAllNodes(content)
        val count = all.fetchSemanticsNodes(atLeastOneRootRequired = false).size
        for (i in 0 until count) if (runCatching { all[i].assertIsDisplayed() }.isSuccess) return
        throw AssertionError("no content node is displayed after $context ($count nodes in the tree)")
    }

    /** Waits for the UI and for results posted by background work (autosave, layout) until the tree stops changing. */
    private fun settle() {
        var last: Int? = null
        repeat(8) {
            compose.waitForIdle()
            val signature = treeSignature()
            if (signature == last) return
            last = signature
            Thread.sleep(25)
        }
    }

    private fun treeSignature(): Int = compose.onAllNodes(SemanticsMatcher("any node") { true }).fetchSemanticsNodes(atLeastOneRootRequired = false).fold(17) { h, n ->
        31 * h + (n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }?.hashCode() ?: 0) +
            7 * (n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")?.hashCode() ?: 0)
    }

    /** ActivityResult launchers (file pickers, permission screens) would leave the app: answer "cancelled" and continue. */
    private fun drainExternalActivities(step: Int) {
        val shadow = shadowOf(compose.activity)
        while (true) {
            val forResult = shadow.nextStartedActivityForResult ?: break
            record(step, "  external activity for result discarded: ${forResult.intent.action} ${forResult.intent.type ?: ""} -> RESULT_CANCELED")
            try {
                compose.runOnUiThread { shadow.receiveResult(forResult.intent, Activity.RESULT_CANCELED, null) }
            } catch (e: RuntimeException) {
                record(step, "  WARN could not deliver RESULT_CANCELED: $e")
            }
        }
        while (true) {
            val intent = shadow.nextStartedActivity ?: break
            record(step, "  external activity discarded: ${intent.action}")
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ actions

    private val clickable = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and isEnabled()
    private val editable = hasSetTextAction() and isEnabled()
    private val scrollable = hasScrollAction()
    private val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and isEnabled()
    private val score = hasContentDescription("Partitur", substring = true) or hasContentDescription("Score", substring = true)

    private val texts = listOf("0", "7", "12", "120", "999999", "-5", "1.5.2", ".", "E5", "Am", "F#m7", "Bbmaj7", "H", "xyz", "Äöü ß", "😀🎸", " ",
        "a".repeat(250), "Riff\nB", "<tag>&\"'", "")

    private fun performRandomAction(step: Int, rnd: Random) {
        val roll = rnd.nextInt(100)
        when {
            roll < 46 -> randomClick(step, rnd, gesture = true)
            roll < 52 -> randomClick(step, rnd, gesture = false)
            roll < 57 -> doubleTap(step, rnd)
            roll < 62 -> longPress(step, rnd)
            roll < 71 -> typeText(step, rnd)
            roll < 77 -> scroll(step, rnd)
            roll < 81 -> moveSlider(step, rnd)
            roll < 94 -> scoreGesture(step, rnd)
            roll < 98 -> systemBack(step)
            else -> rotate(step)
        }
    }

    /** Nodes matching [matcher] in the topmost window (an open dialog, sheet or menu covers everything below it). */
    private fun candidates(matcher: SemanticsMatcher): List<SemanticsNode> {
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false)
        // Popups, dialogs and sheets are composed after the activity content, so their root has the newest (largest) id.
        val top = roots.maxByOrNull { it.id }?.layoutInfo
        return compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).filter { n ->
            n.size.width > 0 && n.size.height > 0 && (roots.size <= 1 || generateSequence(n) { it.parent }.last().layoutInfo === top) && !denied(n)
        }
    }

    private fun denied(n: SemanticsNode): Boolean {
        val labels = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        return labels.any { it in DENIED }
    }

    private fun describe(n: SemanticsNode): String {
        val c = n.config
        val parts = listOfNotNull(
            c.getOrNull(SemanticsProperties.TestTag)?.let { "tag=$it" },
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")?.let { "desc=${it.take(60)}" },
            c.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }?.let { "text=${it.take(60)}" },
        )
        return (parts.joinToString(" ").ifEmpty { "node#${n.id}" }) + " @" + n.boundsInRoot
    }

    private fun interaction(n: SemanticsNode): SemanticsNodeInteraction {
        val id = n.id; val bounds = n.boundsInRoot
        return compose.onNode(SemanticsMatcher("node $id at $bounds") { it.id == id && it.boundsInRoot == bounds })
    }

    /**
     * Runs [action] on a node fetched a moment ago. If the test framework cannot reach it any more (it vanished, it is
     * ambiguous, text input refused), the step is logged as skipped. App exceptions are not AssertionErrors and propagate.
     */
    private fun attempt(step: Int, what: String, action: () -> Unit) {
        record(step, what)
        try { action() } catch (e: AssertionError) { record(step, "  skipped (framework): ${e.message?.lineSequence()?.firstOrNull()}") }
    }

    private fun pick(rnd: Random, nodes: List<SemanticsNode>): SemanticsNode = nodes[rnd.nextInt(nodes.size)]

    private fun randomClick(step: Int, rnd: Random, gesture: Boolean) {
        val nodes = candidates(clickable)
        if (nodes.isEmpty()) { record(step, "click: no clickable node"); return }
        val n = pick(rnd, nodes)
        if (gesture) attempt(step, "click ${describe(n)}") { interaction(n).performClick() }
        else attempt(step, "a11y click ${describe(n)}") { interaction(n).performSemanticsAction(SemanticsActions.OnClick) }
    }

    /** Two taps within one frame: the second lands before any recomposition, like a fast double tap during a transition. */
    private fun doubleTap(step: Int, rnd: Random) {
        val nodes = candidates(clickable)
        if (nodes.isEmpty()) { record(step, "double tap: no clickable node"); return }
        val n = pick(rnd, nodes)
        attempt(step, "double tap ${describe(n)}") {
            compose.mainClock.autoAdvance = false
            try { val target = interaction(n); target.performClick(); target.performClick() } finally { compose.mainClock.autoAdvance = true }
        }
    }

    private fun longPress(step: Int, rnd: Random) {
        val nodes = candidates(clickable)
        if (nodes.isEmpty()) { record(step, "long press: no clickable node"); return }
        val n = pick(rnd, nodes)
        attempt(step, "long press ${describe(n)}") { interaction(n).performTouchInput { longClick() } }
    }

    private fun typeText(step: Int, rnd: Random) {
        val nodes = candidates(editable)
        if (nodes.isEmpty()) { randomClick(step, rnd, gesture = true); return }
        val n = pick(rnd, nodes)
        val text = texts[rnd.nextInt(texts.size)]
        val replace = rnd.nextBoolean()
        val shown = text.take(20).replace("\n", "\\n") + if (text.length > 20) "…(${text.length})" else ""
        attempt(step, "${if (replace) "replace" else "type"} \"$shown\" into ${describe(n)}") {
            val target = interaction(n)
            when {
                text.isEmpty() -> target.performTextClearance()
                replace -> target.performTextReplacement(text)
                else -> target.performTextInput(text)
            }
        }
    }

    private fun scroll(step: Int, rnd: Random) {
        val nodes = candidates(scrollable)
        if (nodes.isEmpty()) { randomClick(step, rnd, gesture = true); return }
        val n = pick(rnd, nodes)
        val kind = rnd.nextInt(5)
        val amount = (rnd.nextInt(1200) - 400).toFloat()
        val horizontal = rnd.nextBoolean()
        attempt(step, "scroll#$kind amount=$amount ${describe(n)}") {
            val target = interaction(n)
            when (kind) {
                0 -> target.performTouchInput { swipeUp() }
                1 -> target.performTouchInput { swipeDown() }
                2 -> target.performTouchInput { swipeLeft() }
                3 -> target.performTouchInput { swipeRight() }
                else -> target.performSemanticsAction(SemanticsActions.ScrollBy) { it(if (horizontal) amount else 0f, if (horizontal) 0f else amount) }
            }
        }
    }

    private fun moveSlider(step: Int, rnd: Random) {
        val nodes = candidates(slider)
        if (nodes.isEmpty()) { randomClick(step, rnd, gesture = true); return }
        val n = pick(rnd, nodes)
        val range = n.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range ?: 0f..1f
        val value = range.start + (range.endInclusive - range.start) * rnd.nextFloat()
        attempt(step, "slider -> $value ${describe(n)}") { interaction(n).performSemanticsAction(SemanticsActions.SetProgress) { it(value) } }
    }

    private fun scoreGesture(step: Int, rnd: Random) {
        val nodes = candidates(score)
        if (nodes.isEmpty()) { randomClick(step, rnd, gesture = true); return }
        val n = pick(rnd, nodes)
        val kind = rnd.nextInt(7)
        val fx = rnd.nextFloat(); val fy = rnd.nextFloat()
        val dx = rnd.nextFloat() - 0.5f; val dy = rnd.nextFloat() - 0.5f
        val zoomIn = rnd.nextBoolean()
        attempt(step, "score gesture#$kind at ($fx,$fy) d=($dx,$dy) zoomIn=$zoomIn ${describe(n)}") {
            interaction(n).performTouchInput {
                val p = Offset(width * fx, height * fy)
                when (kind) {
                    0 -> click(p)
                    1 -> { click(p); click(p) }
                    2 -> longClick(p)
                    3 -> when ((fx * 4).toInt()) { 0 -> swipeLeft(); 1 -> swipeRight(); 2 -> swipeUp(); else -> swipeDown() }
                    4 -> {
                        val near = minOf(width, height) * 0.08f; val far = minOf(width, height) * 0.35f
                        val (from, to) = if (zoomIn) near to far else far to near
                        pinch(Offset(center.x - from, center.y), Offset(center.x - to, center.y), Offset(center.x + from, center.y), Offset(center.x + to, center.y), 300)
                    }
                    5 -> { down(p); moveBy(Offset(dx * width / 2, dy * height / 2)); moveBy(Offset(dx * width / 2, dy * height / 2)); up() }
                    else -> { click(p); down(p); moveBy(Offset(dx * width, dy * height)); up() }   // select, then drag the selection
                }
            }
        }
    }

    private fun systemBack(step: Int) {
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).size
        val dispatcher = compose.activity.onBackPressedDispatcher
        when {
            // A real back press goes to the dialog/sheet window, not to the activity dispatcher.
            roots > 1 -> record(step, "system back skipped: $roots windows open")
            // Nothing would handle it: the activity would finish and leave the app.
            !dispatcher.hasEnabledCallbacks() -> record(step, "system back skipped: would leave the app")
            else -> { record(step, "system back"); compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } }
        }
    }

    private fun rotate(step: Int) {
        landscape = !landscape
        record(step, "rotate to ${if (landscape) "landscape" else "portrait"} (recreate)")
        RuntimeEnvironment.setQualifiers(if (landscape) "+land" else "+port")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    private companion object {
        const val DEFAULT_STEPS = 40
        val DEFAULT_SEEDS = listOf(16_001L, 16_002L, 16_003L, 16_004L, 16_005L)
        val DENIED = setOf("1 min", "10 min", "Neues Paar", "New pair", "▶ A", "▶ B")
    }
}
