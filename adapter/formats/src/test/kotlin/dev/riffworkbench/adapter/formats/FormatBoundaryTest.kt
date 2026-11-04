package dev.riffworkbench.adapter.formats

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FormatBoundaryTest {
    @Test fun zipSignatureNeverClaimsGp8Fidelity() {
        val result = SignatureFormatDetector().detect(byteArrayOf(80, 75, 3, 4))
        assertTrue(result is Outcome.Failure)
        assertEquals(FailureCode.UNAVAILABLE, (result as Outcome.Failure).code)
    }
    @Test fun unfinishedGpExportCannotReturnAnEmptySuccessfulFile() = runBlocking {
        val result = PendingFormatAdapter("gp7").export(ExportRequest(Project(id = EntityId("p")), "gp7"))
        assertTrue(result is Outcome.Failure)
        assertEquals("C-FORMAT", (result as Outcome.Failure).contractId)
    }
}
