package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*

/**
 * C-REPEAT: unfolds repeats, alternate endings and D.C./D.S./To Coda/Fine into a finite route.
 *
 * Rules (documented in CONTRACTS.md):
 * - `RepeatMark.endCount` is the total number of plays of the section (2 = play twice). Values below 2 do not repeat.
 * - A section starts at the last `starts` bar or, without one, after the previous completed section.
 * - A bar with alternate endings is played only in the passes it lists.
 * - D.C./D.S. are taken once. After such a jump, repeats are not taken again and the final ending is played;
 *   To Coda and Fine only apply after the jump.
 */
class UnfoldingRepeatCompiler(private val routeLimit: Int = 20_000) : RepeatCompiler {
    override fun compile(project: Project): Outcome<List<PlaybackOccurrence>> {
        val bars = project.measures
        if (bars.isEmpty()) return Outcome.Failure(FailureCode.VALIDATION, "Projekt enthält keine Takte")
        val indexById = bars.withIndex().associate { it.value.id to it.index }
        fun closeCount(i: Int): Int? = bars[i].repeat.endCount?.takeIf { it >= 2 }
        fun target(kind: NavigationKind, mark: NavigationMark?): Int? =
            mark?.target?.let { indexById[it] } ?: bars.indexOfFirst { m -> m.navigation.any { it.kind == kind } }.takeIf { it >= 0 }
        val route = ArrayList<PlaybackOccurrence>()
        val visits = IntArray(bars.size)
        var index = 0
        var pass = 1
        var repeatStart = 0
        var pendingReset = false
        var viaRepeatJump = false
        var jumped = false
        fun finalPassFrom(i: Int): Int {
            for (k in i until bars.size) {
                if (k != i && bars[k].repeat.starts) return 1
                closeCount(k)?.let { return it }
            }
            return 1
        }
        fun enterSection(i: Int) { repeatStart = i; pass = if (jumped) finalPassFrom(i) else 1; pendingReset = false }
        enterSection(0)
        while (index < bars.size) {
            if (route.size >= routeLimit) return Outcome.Failure(FailureCode.RESOURCE_LIMIT, "Wiederholungen ergeben mehr als $routeLimit Takte", contractId = "C-REPEAT")
            val bar = bars[index]
            val endings = bar.repeat.alternateEndings
            if (!viaRepeatJump && endings.isEmpty() && (bar.repeat.starts || pendingReset)) enterSection(index)
            viaRepeatJump = false
            if (endings.isNotEmpty() && pass !in endings) {
                if (closeCount(index) != null) pendingReset = true
                index++
                continue
            }
            visits[index]++
            route += PlaybackOccurrence(ScorePosition(bar.id), visits[index], route.size)
            val marks = bar.navigation
            if (jumped && marks.any { it.kind == NavigationKind.FINE }) break
            if (jumped && marks.any { it.kind == NavigationKind.TO_CODA }) {
                val coda = target(NavigationKind.CODA, marks.first { it.kind == NavigationKind.TO_CODA })
                if (coda != null && coda > index) { index = coda; pendingReset = true; continue }
            }
            val count = closeCount(index)
            if (count != null) {
                // Jumping back starts the next pass of this section: a skipped earlier volta must not reset it to pass 1.
                if (!jumped && pass < count) { pass++; index = repeatStart; viaRepeatJump = true; pendingReset = false; continue }
                pendingReset = true
            }
            if (!jumped) {
                val dc = marks.firstOrNull { it.kind == NavigationKind.DA_CAPO }
                val ds = marks.firstOrNull { it.kind == NavigationKind.DAL_SEGNO }
                if (dc != null) { jumped = true; index = 0; enterSection(0); viaRepeatJump = true; continue }
                if (ds != null) {
                    val segno = target(NavigationKind.SEGNO, ds)
                        ?: return Outcome.Failure(FailureCode.VALIDATION, "Dal Segno ohne Segno in Takt ${index + 1}", listOf(Diagnostic("missing_segno", "Dal Segno ohne Segno", bar.id, Severity.ERROR)), "C-REPEAT")
                    jumped = true; index = segno; enterSection(segno); viaRepeatJump = true; continue
                }
            }
            index++
        }
        if (route.isEmpty()) return Outcome.Failure(FailureCode.VALIDATION, "Wiederholungen schließen alle Takte aus", contractId = "C-REPEAT")
        return Outcome.Success(route)
    }
}
