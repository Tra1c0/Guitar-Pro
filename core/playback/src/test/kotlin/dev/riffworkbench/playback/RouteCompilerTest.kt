package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

class RouteCompilerTest {
    private val kit = PlaybackTestKit()
    private fun route(vararg measures: Measure): List<Int> {
        val list = measures.toList()
        val p = kit.project(list, listOf(kit.guitar(list)))
        val result = UnfoldingRepeatCompiler().compile(p)
        assertTrue(result.toString(), result is Outcome.Success)
        val ids = list.map { it.id }
        return (result as Outcome.Success).value.map { ids.indexOf(it.position.measureId) }
    }

    @Test fun linearRouteWithoutMarks() {
        assertEquals(listOf(0, 1, 2), route(kit.measure(), kit.measure(), kit.measure()))
    }

    @Test fun simpleRepeatPlaysSectionTwiceWithOccurrences() {
        val m = listOf(kit.measure(repeat = RepeatMark(starts = true)), kit.measure(repeat = RepeatMark(endCount = 2)), kit.measure())
        val p = kit.project(m, listOf(kit.guitar(m)))
        val r = (UnfoldingRepeatCompiler().compile(p) as Outcome.Success).value
        assertEquals(listOf(0, 1, 0, 1, 2), r.map { o -> m.indexOfFirst { it.id == o.position.measureId } })
        assertEquals(listOf(1, 1, 2, 2, 1), r.map { it.occurrence })
        assertEquals((0 until 5).toList(), r.map { it.routeIndex })
    }

    @Test fun alternateEndingsFollowThePass() {
        assertEquals(listOf(0, 1, 2, 0, 1, 3, 4), route(
            kit.measure(repeat = RepeatMark(starts = true)), kit.measure(),
            kit.measure(repeat = RepeatMark(endCount = 2, alternateEndings = setOf(1))),
            kit.measure(repeat = RepeatMark(alternateEndings = setOf(2))), kit.measure()))
    }

    @Test fun threePassesWithSharedEnding() {
        assertEquals(listOf(0, 1, 0, 1, 0, 2), route(
            kit.measure(repeat = RepeatMark(starts = true)),
            kit.measure(repeat = RepeatMark(endCount = 3, alternateEndings = setOf(1, 2))),
            kit.measure(repeat = RepeatMark(alternateEndings = setOf(3)))))
    }

    @Test fun closeWithoutStartRepeatsFromPreviousSection() {
        assertEquals(listOf(0, 1, 0, 1, 2, 3, 2, 3), route(kit.measure(), kit.measure(repeat = RepeatMark(endCount = 2)), kit.measure(), kit.measure(repeat = RepeatMark(endCount = 2))))
    }

    @Test fun daCapoAlFine() {
        assertEquals(listOf(0, 1, 2, 0, 1), route(kit.measure(), kit.measure(nav = listOf(NavigationMark(NavigationKind.FINE))), kit.measure(nav = listOf(NavigationMark(NavigationKind.DA_CAPO)))))
    }

    @Test fun dalSegnoAlCoda() {
        assertEquals(listOf(0, 1, 2, 3, 1, 2, 4), route(kit.measure(), kit.measure(nav = listOf(NavigationMark(NavigationKind.SEGNO))),
            kit.measure(nav = listOf(NavigationMark(NavigationKind.TO_CODA))), kit.measure(nav = listOf(NavigationMark(NavigationKind.DAL_SEGNO))),
            kit.measure(nav = listOf(NavigationMark(NavigationKind.CODA)))))
    }

    @Test fun repeatsAreNotTakenAgainAfterDaCapoAndFinalEndingPlays() {
        assertEquals(listOf(0, 1, 0, 2, 3, 0, 2), route(
            kit.measure(repeat = RepeatMark(starts = true)),
            kit.measure(repeat = RepeatMark(endCount = 2, alternateEndings = setOf(1))),
            kit.measure(repeat = RepeatMark(alternateEndings = setOf(2)), nav = listOf(NavigationMark(NavigationKind.FINE))),
            kit.measure(nav = listOf(NavigationMark(NavigationKind.DA_CAPO)))))
    }

    @Test fun dalSegnoWithoutSegnoFailsWithDiagnostic() {
        val m = listOf(kit.measure(), kit.measure(nav = listOf(NavigationMark(NavigationKind.DAL_SEGNO))))
        val result = UnfoldingRepeatCompiler().compile(kit.project(m, listOf(kit.guitar(m))))
        assertTrue(result is Outcome.Failure)
        assertEquals("missing_segno", (result as Outcome.Failure).diagnostics.single().code)
    }

    @Test fun runawayRoutesAreLimited() {
        val m = List(50) { kit.measure(repeat = RepeatMark(starts = it == 0, endCount = if (it == 49) 32 else null)) }
        val result = UnfoldingRepeatCompiler(routeLimit = 1000).compile(kit.project(m, listOf(kit.guitar(m))))
        assertEquals(FailureCode.RESOURCE_LIMIT, (result as Outcome.Failure).code)
    }
}
