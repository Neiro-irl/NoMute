package com.neiro.aftertone

import com.neiro.aftertone.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ExternalAudioOpenRequestGateTest {
    @Test
    fun restoreGateRefusalDoesNotAcknowledgeAndTheSameRequestCanRetry() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        var gateAllowsAction = false
        var importCount = 0
        var handoffCount = 0
        var acknowledgementCount = 0

        suspend fun attempt() = completeExternalAudioOpenWhenAllowed(
            requestId = requestId,
            withLibraryAction = { action -> if (gateAllowsAction) action() else null },
            onHandled = { acknowledgementCount++ },
            importAndPersist = {
                importCount++
                testTrack("gate-track")
            },
            afterPersist = { handoffCount++ },
        )

        assertNull(attempt())
        assertEquals("A closed restore gate must leave the source request pending.", 0, acknowledgementCount)
        assertEquals(0, importCount)

        gateAllowsAction = true
        assertEquals("gate-track", attempt()?.id)
        assertEquals(1, importCount)
        assertEquals(1, handoffCount)
        assertEquals(1, acknowledgementCount)
    }
}

private fun testTrack(id: String) = Track(
    id = id,
    title = id,
    artist = "Test artist",
    filePath = "/test/$id.mp3",
)
