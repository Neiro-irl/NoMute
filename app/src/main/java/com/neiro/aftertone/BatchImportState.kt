package com.neiro.aftertone

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal enum class BatchImportStatus {
    PENDING,
    IMPORTING,
    IMPORTED,
    FAILED,
    SKIPPED,
}

internal data class BatchImportItem(
    val id: String,
    val uri: String,
    val displayName: String,
    val status: BatchImportStatus = BatchImportStatus.PENDING,
    val trackId: String? = null,
    val failureMessage: String? = null,
)

internal data class BatchImportState(val items: List<BatchImportItem> = emptyList()) {
    val pendingItems: List<BatchImportItem>
        get() = items.filter { it.status == BatchImportStatus.PENDING }

    val activeReadGrantUris: Set<String>
        get() = items.asSequence()
            .filter { it.status == BatchImportStatus.PENDING || it.status == BatchImportStatus.IMPORTING || it.status == BatchImportStatus.FAILED }
            .mapTo(linkedSetOf(), BatchImportItem::uri)

    val failedItems: List<BatchImportItem>
        get() = items.filter { it.status == BatchImportStatus.FAILED }

    val importedCount: Int
        get() = items.count { it.status == BatchImportStatus.IMPORTED }

    val failedCount: Int
        get() = items.count { it.status == BatchImportStatus.FAILED }

    val isComplete: Boolean
        get() = items.all { it.status == BatchImportStatus.IMPORTED || it.status == BatchImportStatus.SKIPPED }

    fun markImporting(id: String): BatchImportState = updateItem(id) {
        copy(status = BatchImportStatus.IMPORTING, failureMessage = null)
    }

    fun markImported(id: String, trackId: String): BatchImportState = updateItem(id) {
        copy(status = BatchImportStatus.IMPORTED, trackId = trackId, failureMessage = null)
    }

    fun markFailed(id: String, message: String): BatchImportState = updateItem(id) {
        copy(status = BatchImportStatus.FAILED, trackId = null, failureMessage = message)
    }

    fun retryFailedItem(id: String): BatchImportState = updateItem(id) {
        if (status == BatchImportStatus.FAILED) {
            copy(status = BatchImportStatus.PENDING, failureMessage = null)
        } else {
            this
        }
    }

    fun skipFailedItem(id: String): BatchImportState = updateItem(id) {
        if (status == BatchImportStatus.FAILED) {
            copy(status = BatchImportStatus.SKIPPED, failureMessage = null)
        } else {
            this
        }
    }

    fun skipFailedItemAndRequestGrantRelease(
        id: String,
        requestGrantRelease: (String) -> Unit,
    ): BatchImportState {
        val skipped = skipFailedItem(id)
        (activeReadGrantUris - skipped.activeReadGrantUris).forEach(requestGrantRelease)
        return skipped
    }

    fun retryFailed(): BatchImportState = copy(
        items = items.map { item ->
            if (item.status == BatchImportStatus.FAILED) {
                item.copy(status = BatchImportStatus.PENDING, failureMessage = null)
            } else {
                item
            }
        },
    )

    fun skipFailed(): BatchImportState = copy(
        items = items.map { item ->
            if (item.status == BatchImportStatus.FAILED) {
                item.copy(status = BatchImportStatus.SKIPPED, failureMessage = null)
            } else {
                item
            }
        },
    )

    fun skipFailedAndRequestGrantReleases(requestGrantRelease: (String) -> Unit): BatchImportState {
        val skipped = skipFailed()
        (activeReadGrantUris - skipped.activeReadGrantUris).forEach(requestGrantRelease)
        return skipped
    }

    suspend fun processPending(
        importItem: suspend (BatchImportItem) -> String,
        failureMessage: (Exception) -> String,
        onStateChanged: (BatchImportState) -> Unit = {},
    ): BatchImportState {
        var updated = this
        for (item in pendingItems) {
            updated = updated.markImporting(item.id)
            onStateChanged(updated)
            try {
                updated = updated.markImported(item.id, importItem(item))
            } catch (cancelled: CancellationException) {
                updated = updated.markFailed(item.id, INTERRUPTED_IMPORT_MESSAGE)
                onStateChanged(updated)
                throw cancelled
            } catch (error: Exception) {
                updated = updated.markFailed(item.id, failureMessage(error))
            }
            onStateChanged(updated)
        }
        return updated
    }

    internal fun toSavedState(): List<String> = buildList {
        add(SAVED_STATE_VERSION)
        add(items.size.toString())
        items.forEach { item ->
            add(item.id)
            add(item.uri)
            add(item.displayName)
            add(item.status.name)
            add(item.trackId.orEmpty())
            add(item.failureMessage.orEmpty())
        }
    }

    private fun updateItem(id: String, update: BatchImportItem.() -> BatchImportItem): BatchImportState = copy(
        items = items.map { item -> if (item.id == id) item.update() else item },
    )

    companion object {
        private const val SAVED_STATE_VERSION = "1"
        private const val FIELDS_PER_ITEM = 6
        private const val INTERRUPTED_IMPORT_MESSAGE = "Импорт прерван. Повтори попытку для этого файла."

        fun fromDocuments(
            documents: List<Pair<String, String>>,
            idFactory: () -> String = { UUID.randomUUID().toString() },
        ): BatchImportState = BatchImportState(
            items = documents.distinctBy { it.first }.map { (uri, displayName) ->
                BatchImportItem(
                    id = idFactory(),
                    uri = uri,
                    displayName = displayName.ifBlank { "Аудиофайл" },
                )
            },
        )

        fun restoreFromSavedState(saved: List<String>): BatchImportState {
            if (saved.size < 2 || saved[0] != SAVED_STATE_VERSION) return BatchImportState()
            val count = saved[1].toIntOrNull()?.takeIf { it >= 0 } ?: return BatchImportState()
            if (saved.size != 2 + count * FIELDS_PER_ITEM) return BatchImportState()

            val restoredItems = buildList {
                for (offset in 2 until saved.size step FIELDS_PER_ITEM) {
                    val status = runCatching { BatchImportStatus.valueOf(saved[offset + 3]) }.getOrNull()
                        ?: return BatchImportState()
                    val wasInterrupted = status == BatchImportStatus.IMPORTING
                    add(
                        BatchImportItem(
                            id = saved[offset],
                            uri = saved[offset + 1],
                            displayName = saved[offset + 2],
                            status = if (wasInterrupted) BatchImportStatus.FAILED else status,
                            trackId = saved[offset + 4].ifEmpty { null },
                            failureMessage = if (wasInterrupted) {
                                saved[offset + 5].ifEmpty { INTERRUPTED_IMPORT_MESSAGE }
                            } else {
                                saved[offset + 5].ifEmpty { null }
                            },
                        ),
                    )
                }
            }
            return BatchImportState(restoredItems)
        }
    }
}

internal val BatchImportStateSaver = listSaver<BatchImportState, String>(
    save = { it.toSavedState() },
    restore = { BatchImportState.restoreFromSavedState(it) },
)
