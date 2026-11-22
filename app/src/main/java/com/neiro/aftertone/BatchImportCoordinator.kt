package com.neiro.aftertone

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.storage.LocalLibraryRepository
import com.neiro.aftertone.storage.RestoreActionGate
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface BatchImportWorkflowHost {
    var batchState: BatchImportState
    var pickerClaims: BatchImportUriGrantClaims
    var importInProgress: Boolean

    val unavailableMessage: String

    fun canStartImport(): Boolean
    fun onReadGrantClaimsChanged(uris: Set<Uri>)
    fun onReadGrantNoLongerNeeded(uri: Uri)
    fun onLibraryReloaded(snapshot: LibrarySnapshot)
    fun showMessage(message: String)
}

internal class BatchImportCoordinator(
    private val contentResolver: ContentResolver,
    private val repository: LocalLibraryRepository,
    private val restoreActionGate: RestoreActionGate,
) {
    fun activeReadGrantClaims(state: BatchImportState): Set<Uri> = state.activeReadGrantUris.mapNotNull { uriValue ->
        runCatching { uriValue.toUri() }.getOrNull()
            ?.takeIf { it.scheme?.lowercase(Locale.ROOT) == "content" }
    }.toSet()

    fun allReadGrantClaims(
        state: BatchImportState,
        pickerClaims: BatchImportUriGrantClaims,
    ): Set<Uri> = pickerClaims.allUris(activeReadGrantClaims(state))

    fun retryItem(host: BatchImportWorkflowHost, itemId: String) {
        host.batchState = host.batchState.retryFailedItem(itemId)
    }

    fun retryFailed(host: BatchImportWorkflowHost) {
        host.batchState = host.batchState.retryFailed()
    }

    fun dismiss(host: BatchImportWorkflowHost) {
        host.batchState.items.forEach { item -> requestReadGrantRelease(host, item.uri) }
        host.batchState = BatchImportState()
    }

    suspend fun importSelectedUris(host: BatchImportWorkflowHost, uris: List<Uri>) {
        val uniqueUris = uris.distinctBy(Uri::toString)
        if (uniqueUris.isEmpty()) return

        val selectedClaims = uniqueUris
            .filter { it.scheme?.lowercase(Locale.ROOT) == "content" }
            .toSet()
        val pendingPickerClaims = host.pickerClaims.withPickerSelection(selectedClaims)
        host.pickerClaims = pendingPickerClaims
        host.onReadGrantClaimsChanged(allReadGrantClaims(host.batchState, pendingPickerClaims))

        var batchStateAssigned = false
        try {
            val documents = withContext(Dispatchers.IO) {
                uniqueUris.map { uri ->
                    retainReadPermission(uri)
                    val displayName = runCatching {
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                        }
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
                        ?: "Аудиофайл"
                    uri.toString() to displayName
                }
            }

            val newBatchState = BatchImportState.fromDocuments(documents)
            host.batchState = newBatchState
            val newBatchClaims = activeReadGrantClaims(newBatchState)
            host.pickerClaims = host.pickerClaims
                .promoteBatchItems(newBatchClaims)
                .discardPickerSelection(selectedClaims - newBatchClaims)
            host.onReadGrantClaimsChanged(allReadGrantClaims(newBatchState, host.pickerClaims))
            batchStateAssigned = true
            processPending(host)
        } finally {
            if (!batchStateAssigned) {
                host.pickerClaims = host.pickerClaims.discardPickerSelection(selectedClaims)
                host.onReadGrantClaimsChanged(allReadGrantClaims(host.batchState, host.pickerClaims))
            }
        }
    }

    suspend fun processPending(host: BatchImportWorkflowHost) {
        if (host.importInProgress || host.batchState.pendingItems.isEmpty()) return
        if (!host.canStartImport()) {
            markPendingItemsFailed(host, host.unavailableMessage)
            return
        }

        val started = restoreActionGate.withLibraryAction {
            if (host.importInProgress || !host.canStartImport()) {
                markPendingItemsFailed(host, host.unavailableMessage)
                false
            } else {
                host.importInProgress = true
                try {
                    val processed = host.batchState.processPending(
                        importItem = { item ->
                            withContext(Dispatchers.IO) {
                                repository.importBatch(item.uri.toUri(), item.id).id
                            }
                        },
                        failureMessage = ::failureMessage,
                        onStateChanged = { host.batchState = it },
                    )
                    host.batchState = processed
                    try {
                        val reloadedLibrary = withContext(Dispatchers.IO) { repository.load() }
                        host.onLibraryReloaded(reloadedLibrary)
                        val importedIds = processed.items
                            .filter { it.status == BatchImportStatus.IMPORTED }
                            .mapNotNull { it.trackId }
                            .toSet()
                        val reloadedIds = reloadedLibrary.tracks.mapTo(hashSetOf()) { it.id }
                        val counts = reconcileImportedTrackIds(importedIds, processed.failedCount, reloadedIds)
                        if (counts.importedCount < importedIds.size) {
                            processed.items
                                .filter { it.status == BatchImportStatus.IMPORTED && it.trackId !in reloadedIds }
                                .forEach { item ->
                                    host.batchState = host.batchState.markFailed(
                                        item.id,
                                        "Не удалось подтвердить сохранение этого трека в медиатеке.",
                                    )
                                }
                        }
                        host.batchState.items
                            .filter { it.status == BatchImportStatus.IMPORTED }
                            .forEach { item -> requestReadGrantRelease(host, item.uri) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        host.showMessage("Не удалось обновить медиатеку после импорта. Импортированные файлы останутся сохранены.")
                    }
                    true
                } finally {
                    host.importInProgress = false
                }
            }
        }
        if (started != true) markPendingItemsFailed(host, host.unavailableMessage)
    }

    private fun markPendingItemsFailed(host: BatchImportWorkflowHost, message: String) {
        host.batchState = host.batchState.pendingItems.fold(host.batchState) { current, item ->
            current.markFailed(item.id, message)
        }
    }

    private fun retainReadPermission(uri: Uri) {
        if (uri.scheme?.lowercase(Locale.ROOT) != "content") return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun requestReadGrantRelease(host: BatchImportWorkflowHost, uriValue: String) {
        val uri = runCatching { uriValue.toUri() }.getOrNull() ?: return
        if (uri.scheme?.lowercase(Locale.ROOT) != "content") return
        host.onReadGrantNoLongerNeeded(uri)
    }

    private fun failureMessage(error: Exception): String = when (error) {
        is SecurityException -> "Файл больше недоступен. Выбери его снова или пропусти."
        is IllegalArgumentException -> "Файл не содержит поддерживаемую аудиозапись."
        is IOException -> "Не удалось прочитать или сохранить файл. Проверь свободное место и повтори попытку."
        else -> "Не удалось импортировать этот файл."
    }
}
