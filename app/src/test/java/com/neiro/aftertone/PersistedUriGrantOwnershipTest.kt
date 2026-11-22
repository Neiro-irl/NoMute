package com.neiro.aftertone

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedUriGrantOwnershipTest {
    @Test
    fun externalQueueRemovalCannotReleaseGrantWhileBatchStillClaimsTheUri() {
        val sharedUri = TestUri("content://media/external/audio/shared")
        val ownership = PersistedUriGrantOwnership()

        ownership.updateExternalAudioQueueUris(emptySet())
        ownership.updateBatchImportUriClaims(setOf(sharedUri))
        val afterExternalRequestRemoval = ownership.requestRelease(sharedUri)
        val afterBatchItemIsSkipped = ownership.updateBatchImportUriClaims(emptySet())

        assertTrue(afterExternalRequestRemoval.isEmpty())
        assertEquals(setOf(sharedUri), afterBatchItemIsSkipped)
    }

    @Test
    fun releaseWaitsUntilRestoredBatchAndExternalQueueClaimsAreKnown() {
        val sharedUri = TestUri("content://media/external/audio/restored")
        val ownership = PersistedUriGrantOwnership()

        val beforeClaimsRestore = ownership.requestRelease(sharedUri)
        val afterBatchClaimsRestore = ownership.updateBatchImportUriClaims(emptySet())
        val afterExternalQueueRestore = ownership.updateExternalAudioQueueUris(emptySet())

        assertTrue(beforeClaimsRestore.isEmpty())
        assertTrue(afterBatchClaimsRestore.isEmpty())
        assertEquals(setOf(sharedUri), afterExternalQueueRestore)
    }

    @Test
    fun externalQueueClaimDefersReleaseUntilItsRequestIsRemoved() {
        val sharedUri = TestUri("content://media/external/audio/queued")
        val ownership = PersistedUriGrantOwnership()

        ownership.updateBatchImportUriClaims(emptySet())
        ownership.updateExternalAudioQueueUris(setOf(sharedUri))
        val whileExternalRequestRemains = ownership.requestRelease(sharedUri)
        val afterExternalRequestIsRemoved = ownership.updateExternalAudioQueueUris(emptySet())

        assertTrue(whileExternalRequestRemains.isEmpty())
        assertEquals(setOf(sharedUri), afterExternalRequestIsRemoved)
    }

    @Test
    fun incomingExternalRequestOwnsUriUntilQueueInsertionFinishes() {
        val sharedUri = TestUri("content://media/external/audio/in-flight")
        val ownership = PersistedUriGrantOwnership()
        ownership.updateBatchImportUriClaims(emptySet())
        ownership.updateExternalAudioQueueUris(emptySet())
        val inFlight = ownership.claimIncomingExternalAudioRequest(sharedUri)

        val afterBatchCompletion = ownership.requestRelease(sharedUri)
        ownership.updateExternalAudioQueueUris(setOf(sharedUri))
        val afterQueueInsertion = ownership.finishIncomingExternalAudioRequest(inFlight)
        val afterExternalRequestRemoval = ownership.updateExternalAudioQueueUris(emptySet())

        assertTrue(afterBatchCompletion.isEmpty())
        assertTrue(afterQueueInsertion.isEmpty())
        assertEquals(setOf(sharedUri), afterExternalRequestRemoval)
    }

    @Test
    fun rejectedIncomingExternalRequestReleasesItsGrantAfterInFlightClaimEnds() {
        val sharedUri = TestUri("content://media/external/audio/rejected")
        val ownership = PersistedUriGrantOwnership()
        ownership.updateBatchImportUriClaims(emptySet())
        ownership.updateExternalAudioQueueUris(emptySet())
        val inFlight = ownership.claimIncomingExternalAudioRequest(sharedUri)

        val whileInFlight = ownership.requestRelease(sharedUri)
        val afterRejectedRequestFinishes = ownership.finishIncomingExternalAudioRequest(inFlight)

        assertTrue(whileInFlight.isEmpty())
        assertEquals(setOf(sharedUri), afterRejectedRequestFinishes)
    }

    @Test
    fun overlappingIncomingRequestsKeepSharedUriClaimedUntilBothFinish() {
        val sharedUri = TestUri("content://media/external/audio/two-in-flight")
        val ownership = PersistedUriGrantOwnership()
        ownership.updateBatchImportUriClaims(emptySet())
        ownership.updateExternalAudioQueueUris(emptySet())
        val first = ownership.claimIncomingExternalAudioRequest(sharedUri)
        val second = ownership.claimIncomingExternalAudioRequest(sharedUri)
        ownership.requestRelease(sharedUri)

        val afterFirstFinishes = ownership.finishIncomingExternalAudioRequest(first)
        val afterSecondFinishes = ownership.finishIncomingExternalAudioRequest(second)

        assertTrue(afterFirstFinishes.isEmpty())
        assertEquals(setOf(sharedUri), afterSecondFinishes)
    }
}
