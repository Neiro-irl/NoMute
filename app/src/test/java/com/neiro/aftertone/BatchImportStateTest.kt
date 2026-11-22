package com.neiro.aftertone

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BatchImportStateTest {
    @Test
    fun oneUriFailureDoesNotStopOtherImportsAndRetryTargetsOnlyTheFailure() = runBlocking {
        val ids = listOf("item-1", "item-2", "item-3").iterator()
        val initial = BatchImportState.fromDocuments(
            listOf(
                "content://provider/one" to "one.mp3",
                "content://provider/two" to "two.mp3",
                "content://provider/three" to "three.mp3",
            ),
            idFactory = { ids.next() },
        )
        val attempted = mutableListOf<String>()

        val mixed = initial.processPending(
            importItem = { item ->
                attempted += item.id
                if (item.displayName == "two.mp3") throw IOException("read failed")
                "track-${item.id}"
            },
            failureMessage = { "Не удалось прочитать файл." },
        )

        assertEquals(listOf("item-1", "item-2", "item-3"), attempted)
        assertEquals(
            listOf(BatchImportStatus.IMPORTED, BatchImportStatus.FAILED, BatchImportStatus.IMPORTED),
            mixed.items.map(BatchImportItem::status),
        )
        assertEquals(listOf("item-2"), mixed.retryFailed().pendingItems.map(BatchImportItem::id))

        val retriedItems = mutableListOf<String>()
        val completed = mixed.retryFailed().processPending(
            importItem = { item ->
                retriedItems += item.id
                "track-${item.id}"
            },
            failureMessage = { "Не удалось прочитать файл." },
        )

        assertEquals(listOf("item-2"), retriedItems)
        assertEquals(listOf("track-item-1", "track-item-2", "track-item-3"), completed.items.mapNotNull(BatchImportItem::trackId))
        assertEquals(listOf(BatchImportStatus.IMPORTED, BatchImportStatus.IMPORTED, BatchImportStatus.IMPORTED), completed.items.map(BatchImportItem::status))
    }

    @Test
    fun skippingFailedItemsKeepsSuccessfulItemsAndTheirTrackIds() {
        val ids = listOf("success", "failure").iterator()
        val state = BatchImportState.fromDocuments(
            listOf("content://provider/ok" to "ok.mp3", "content://provider/bad" to "bad.mp3"),
            idFactory = { ids.next() },
        )
            .markImported("success", "track-1")
            .markFailed("failure", "Нет доступа к файлу.")

        val skipped = state.skipFailed()

        assertEquals(BatchImportStatus.IMPORTED, skipped.items[0].status)
        assertEquals("track-1", skipped.items[0].trackId)
        assertEquals(BatchImportStatus.SKIPPED, skipped.items[1].status)
        assertEquals("track-1", skipped.items[0].trackId)
    }

    @Test
    fun retryingOneFailedItemLeavesOtherFailuresAndSuccessfulItemsUnchanged() {
        val ids = listOf("success", "retry", "other-failure").iterator()
        val state = BatchImportState.fromDocuments(
            listOf(
                "content://provider/success" to "success.mp3",
                "content://provider/retry" to "retry.mp3",
                "content://provider/other-failure" to "other-failure.mp3",
            ),
            idFactory = { ids.next() },
        )
            .markImported("success", "track-1")
            .markFailed("retry", "retry this file")
            .markFailed("other-failure", "leave this failure alone")

        val retried = state.retryFailedItem("retry")

        assertEquals(
            listOf(BatchImportStatus.IMPORTED, BatchImportStatus.PENDING, BatchImportStatus.FAILED),
            retried.items.map(BatchImportItem::status),
        )
        assertEquals("track-1", retried.items[0].trackId)
        assertEquals("leave this failure alone", retried.items[2].failureMessage)
    }

    @Test
    fun skippingOneFailedItemLeavesOtherFailuresAndSuccessfulItemsUnchanged() {
        val ids = listOf("success", "skip", "other-failure").iterator()
        val state = BatchImportState.fromDocuments(
            listOf(
                "content://provider/success" to "success.mp3",
                "content://provider/skip" to "skip.mp3",
                "content://provider/other-failure" to "other-failure.mp3",
            ),
            idFactory = { ids.next() },
        )
            .markImported("success", "track-1")
            .markFailed("skip", "skip this file")
            .markFailed("other-failure", "leave this failure alone")

        val skipped = state.skipFailedItem("skip")

        assertEquals(
            listOf(BatchImportStatus.IMPORTED, BatchImportStatus.SKIPPED, BatchImportStatus.FAILED),
            skipped.items.map(BatchImportItem::status),
        )
        assertEquals("track-1", skipped.items[0].trackId)
        assertEquals("leave this failure alone", skipped.items[2].failureMessage)
    }

    @Test
    fun onlyItemsStillNeedingReadAccessRetainTheirUriGrants() {
        val state = BatchImportState.fromDocuments(
            listOf(
                "content://provider/pending" to "pending.mp3",
                "content://provider/importing" to "importing.mp3",
                "content://provider/failed" to "failed.mp3",
                "content://provider/imported" to "imported.mp3",
                "content://provider/skipped" to "skipped.mp3",
            ),
            idFactory = listOf("pending", "importing", "failed", "imported", "skipped").iterator()::next,
        )
            .markImporting("importing")
            .markFailed("failed", "retry")
            .markImported("imported", "track-1")
            .markFailed("skipped", "skip")
            .skipFailedItem("skipped")

        assertEquals(
            setOf("content://provider/pending", "content://provider/importing", "content://provider/failed"),
            state.activeReadGrantUris,
        )
    }

    @Test
    fun savedBatchRestoresSuccessfulTrackAndMakesInterruptedItemRetryable() {
        val state = BatchImportState.fromDocuments(
            listOf(
                "content://provider/done" to "done.mp3",
                "content://provider/interrupted" to "interrupted.mp3",
            ),
            idFactory = listOf("stable-success", "stable-interrupted").iterator()::next,
        )
            .markImported("stable-success", "track-1")
            .markImporting("stable-interrupted")

        val restored = BatchImportState.restoreFromSavedState(state.toSavedState())

        assertEquals("track-1", restored.items[0].trackId)
        assertEquals(BatchImportStatus.IMPORTED, restored.items[0].status)
        assertEquals("stable-interrupted", restored.items[1].id)
        assertEquals(BatchImportStatus.FAILED, restored.items[1].status)
        assertNotNull(restored.items[1].failureMessage)
        assertNull(restored.items[1].trackId)
        assertEquals(listOf("stable-interrupted"), restored.retryFailed().pendingItems.map(BatchImportItem::id))
    }
}
