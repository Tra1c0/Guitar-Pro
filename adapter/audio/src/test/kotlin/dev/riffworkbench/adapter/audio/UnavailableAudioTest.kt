package dev.riffworkbench.adapter.audio

import dev.riffworkbench.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnavailableAudioTest {
    @Test fun pendingEngineNeverClaimsPlaybackOrAnAudioExport() = runBlocking {
        val audio = PendingAudioServices()
        assertEquals(TransportStatus.UNAVAILABLE, audio.state.value.status)
        assertEquals(FailureCode.UNAVAILABLE, (audio.play() as Outcome.Failure).code)
        assertTrue(audio.render(Project(id = EntityId("p")), RenderOptions()) is Outcome.Failure)
        assertEquals(TransportStatus.UNAVAILABLE, audio.state.value.status)
    }
}
