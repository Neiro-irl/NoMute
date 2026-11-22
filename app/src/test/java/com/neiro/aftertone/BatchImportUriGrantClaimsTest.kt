package com.neiro.aftertone

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchImportUriGrantClaimsTest {
    @Test
    fun skippingOneFailedBatchItemRequestsOnlyItsUriGrantRelease() {
        val skippedUri = "content://media/external/audio/skip"
        val remainingUri = "content://media/external/audio/retry"
        val ids = listOf("skip-id", "retry-id").iterator()
        val pending = BatchImportState.fromDocuments(
            listOf(skippedUri to "skip.mp3", remainingUri to "retry.mp3"),
            idFactory = { ids.next() },
        ).markFailed("skip-id", "Permission denied")
            .markFailed("retry-id", "Permission denied")
        val requestedReleases = linkedSetOf<String>()

        val skipped = pending.skipFailedItemAndRequestGrantRelease("skip-id") { requestedReleases += it }

        assertEquals(setOf(skippedUri), requestedReleases)
        assertEquals(BatchImportStatus.SKIPPED, skipped.items[0].status)
        assertEquals(BatchImportStatus.FAILED, skipped.items[1].status)
        assertEquals(setOf(remainingUri), skipped.activeReadGrantUris)
    }

    @Test
    fun skippingAllFailedBatchItemsRequestsTheirUriGrantReleases() {
        val firstUri = "content://media/external/audio/first"
        val secondUri = "content://media/external/audio/second"
        val importedUri = "content://media/external/audio/imported"
        val ids = listOf("failed-one", "failed-two", "imported-three").iterator()
        val pending = BatchImportState.fromDocuments(
            listOf(firstUri to "first.mp3", secondUri to "second.mp3", importedUri to "imported.mp3"),
            idFactory = { ids.next() },
        ).markFailed("failed-one", "Permission denied")
            .markFailed("failed-two", "Permission denied")
            .markImported("imported-three", "track-three")
        val requestedReleases = linkedSetOf<String>()

        val skipped = pending.skipFailedAndRequestGrantReleases { requestedReleases += it }

        assertEquals(setOf(firstUri, secondUri), requestedReleases)
        assertEquals(
            listOf(BatchImportStatus.SKIPPED, BatchImportStatus.SKIPPED, BatchImportStatus.IMPORTED),
            skipped.items.map { it.status },
        )
        assertTrue(skipped.activeReadGrantUris.isEmpty())
    }

    @Test
    fun pickerClaimSurvivesStaleBatchStateUntilTheItemTakesOwnership() {
        val selectedUri = TestUri("content://media/external/audio/selected")
        val selected = BatchImportUriGrantClaims()
            .withPickerSelection(setOf(selectedUri))

        val staleRecompositionClaims = selected.allUris(emptySet())
        val promotedToBatchItem = selected.promoteBatchItems(setOf(selectedUri))
        val releasedAfterSkip = promotedToBatchItem.allUris(emptySet())

        assertEquals(setOf(selectedUri), staleRecompositionClaims)
        assertEquals(setOf(selectedUri), promotedToBatchItem.allUris(setOf(selectedUri)))
        assertTrue(promotedToBatchItem.pickerSelectionUris.isEmpty())
        assertTrue(releasedAfterSkip.isEmpty())
    }
}
