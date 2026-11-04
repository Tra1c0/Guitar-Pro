package dev.riffworkbench.playback

import dev.riffworkbench.model.*
import org.junit.Assert.*
import org.junit.Test

class CompilerBoundaryTest {
    @Test fun straightRouteCarriesStableOccurrenceIdentity() {
        val p = Project(id = EntityId("p"), measures = listOf(Measure(EntityId("a")), Measure(EntityId("b"))))
        val result = LinearRepeatCompiler().compile(p) as Outcome.Success
        assertEquals(listOf(0, 1), result.value.map { it.routeIndex })
        assertEquals(listOf(1, 1), result.value.map { it.occurrence })
    }
    @Test fun unresolvedRepeatsReturnUnavailableInsteadOfAFalseRoute() {
        val p = Project(id = EntityId("p"), measures = listOf(Measure(EntityId("a"), repeat = RepeatMark(endCount = 2))))
        val result = LinearRepeatCompiler().compile(p) as Outcome.Failure
        assertEquals(FailureCode.UNAVAILABLE, result.code); assertEquals("C-REPEAT", result.contractId)
    }
}
