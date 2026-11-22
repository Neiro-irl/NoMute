package com.neiro.aftertone

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchImportSkipActionsTest {
    @Test
    fun perItemSkipCallbackRequestsGrantReleaseAndPreservesOtherBatchOwnership() {
        val skippedUri = "content://media/external/audio/skip"
        val remainingUri = "content://media/external/audio/retry"
        val ids = listOf("skip-id", "retry-id").iterator()
        var state = BatchImportState.fromDocuments(
            listOf(skippedUri to "skip.mp3", remainingUri to "retry.mp3"),
            idFactory = { ids.next() },
        ).markFailed("skip-id", "Permission denied")
            .markFailed("retry-id", "Permission denied")
        val ownership = PersistedUriGrantOwnership().apply {
            updateExternalAudioQueueUris(emptySet())
            updateBatchImportUriClaims(state.activeReadGrantUris.map { TestUri(it) }.toSet())
        }
        val requestedUris = linkedSetOf<String>()
        val immediatelyReleasedUris = linkedSetOf<String>()
        val actions = BatchImportSkipActions(
            currentState = { state },
            updateState = { state = it },
            requestGrantRelease = { uri ->
                requestedUris += uri
                immediatelyReleasedUris += ownership.requestRelease(TestUri(uri)).map { it.toString() }
            },
        )

        actions.onSkipItem("skip-id")

        assertEquals(setOf(skippedUri), requestedUris)
        assertTrue(immediatelyReleasedUris.isEmpty())
        assertEquals(BatchImportStatus.SKIPPED, state.items[0].status)
        assertEquals(BatchImportStatus.FAILED, state.items[1].status)
        val releasedAfterBatchClaimsUpdate = ownership.updateBatchImportUriClaims(
            state.activeReadGrantUris.map { TestUri(it) }.toSet(),
        ).map { it.toString() }.toSet()
        assertEquals(setOf(skippedUri), releasedAfterBatchClaimsUpdate)
        assertTrue(ownership.requestRelease(TestUri(remainingUri)).isEmpty())
    }

    @Test
    fun bulkSkipCallbackRequestsAllFailedGrantReleasesAndWaitsForBatchClaimsToDrop() {
        val firstUri = "content://media/external/audio/first"
        val secondUri = "content://media/external/audio/second"
        val importedUri = "content://media/external/audio/imported"
        val ids = listOf("failed-one", "failed-two", "imported-three").iterator()
        var state = BatchImportState.fromDocuments(
            listOf(firstUri to "first.mp3", secondUri to "second.mp3", importedUri to "imported.mp3"),
            idFactory = { ids.next() },
        ).markFailed("failed-one", "Permission denied")
            .markFailed("failed-two", "Permission denied")
            .markImported("imported-three", "track-three")
        val ownership = PersistedUriGrantOwnership().apply {
            updateExternalAudioQueueUris(emptySet())
            updateBatchImportUriClaims(state.activeReadGrantUris.map { TestUri(it) }.toSet())
        }
        val requestedUris = linkedSetOf<String>()
        val immediatelyReleasedUris = linkedSetOf<String>()
        val actions = BatchImportSkipActions(
            currentState = { state },
            updateState = { state = it },
            requestGrantRelease = { uri ->
                requestedUris += uri
                immediatelyReleasedUris += ownership.requestRelease(TestUri(uri)).map { it.toString() }
            },
        )

        actions.onSkipFailed()

        assertEquals(setOf(firstUri, secondUri), requestedUris)
        assertTrue(immediatelyReleasedUris.isEmpty())
        assertEquals(
            listOf(BatchImportStatus.SKIPPED, BatchImportStatus.SKIPPED, BatchImportStatus.IMPORTED),
            state.items.map { it.status },
        )
        val releasedAfterBatchClaimsUpdate = ownership.updateBatchImportUriClaims(
            state.activeReadGrantUris.map { TestUri(it) }.toSet(),
        ).map { it.toString() }.toSet()
        assertEquals(setOf(firstUri, secondUri), releasedAfterBatchClaimsUpdate)
    }
}
