package com.neiro.aftertone

import android.net.Uri
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal data class ExternalAudioOpenRequestQueueSnapshot(
    val pendingRequests: List<ExternalAudioOpenRequest> = emptyList(),
    val acknowledgedRequestIds: Set<String> = emptySet(),
    val acknowledgedRequestUris: Map<String, String> = emptyMap(),
)

internal enum class ExternalAudioOpenQueueLoadStatus {
    NoData,
    Ready,
    Corrupt,
    Oversized,
    ReadFailure,
}

internal enum class ExternalAudioOpenQueueStoreProblem {
    Corrupt,
    Oversized,
    ReadFailure,
    WriteFailure,
    RecoveryFailure,
    RecoveryWriteFailure,
}

private object ExternalAudioOpenRequestQueueProcessLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    private val unkeyedLock = Any()

    fun forCacheKey(cacheKey: String?): Any =
        cacheKey?.let { locks.computeIfAbsent(it) { Any() } } ?: unkeyedLock
}

internal enum class ExternalAudioOpenRequestQueueRejection {
    QueueFull,
    UriTooLarge,
    SnapshotTooLarge,
    InvalidRequest,
}

internal data class ExternalAudioOpenRequestQueueStoreUpdate(
    val snapshot: ExternalAudioOpenRequestQueueSnapshot,
    val persisted: Boolean,
    val snapshotIsDurable: Boolean = persisted,
    val retrySnapshot: ExternalAudioOpenRequestQueueSnapshot? = null,
    val rejection: ExternalAudioOpenRequestQueueRejection? = null,
    val storeProblem: ExternalAudioOpenQueueStoreProblem? = null,
    val reopenRequiredCount: Int = 0,
)

internal class ExternalAudioOpenRequestQueueStore(
    private val readEncoded: () -> String?,
    private val commitEncoded: (String) -> Boolean,
    private val parseUri: (String) -> Uri = Uri::parse,
    private val processCacheKey: String? = null,
    private val backupCorruptSnapshot: ((String) -> Boolean)? = null,
) {
    private val processLock = ExternalAudioOpenRequestQueueProcessLocks.forCacheKey(processCacheKey)

    fun load(): ExternalAudioOpenRequestQueueSnapshot? = synchronized(processLock) { loadState().snapshot }

    val loadStatus: ExternalAudioOpenQueueLoadStatus
        get() = synchronized(processLock) { loadState().status }

    val loadProblem: ExternalAudioOpenQueueStoreProblem?
        get() = synchronized(processLock) { loadState().problem }

    val rawSnapshotForRecovery: String?
        get() = synchronized(processLock) { loadState().rawSnapshot }

    fun save(snapshot: ExternalAudioOpenRequestQueueSnapshot): Boolean = synchronized(processLock) {
        val previousState = loadState()
        if (previousState.problem != null) return@synchronized false
        val encoded = encodeBoundedSnapshot(snapshot, allowTrimFailureMessages = false) ?: return@synchronized false
        val persistedSnapshot = decode(encoded) ?: return@synchronized false
        val persisted = tryCommitEncoded(encoded)
        val key = processCacheKey
        if (persisted) {
            cacheLoadState(QueueLoadState(persistedSnapshot, ExternalAudioOpenQueueLoadStatus.Ready))
        } else if (key != null) {
            processSnapshotCache[key] = previousState
        }
        persisted
    }

    fun restore(
        savedStateRequests: List<ExternalAudioOpenRequest>?,
        currentIntentRequest: ExternalAudioOpenRequest?,
        suppressAcknowledgedUriOnRecreation: Boolean = false,
        hasPersistedReadPermission: (Uri) -> Boolean = { true },
    ): ExternalAudioOpenRequestQueueStoreUpdate = synchronized(processLock) {
        restoreLocked(savedStateRequests, currentIntentRequest, suppressAcknowledgedUriOnRecreation, hasPersistedReadPermission)
    }

    private fun restoreLocked(
        savedStateRequests: List<ExternalAudioOpenRequest>?,
        currentIntentRequest: ExternalAudioOpenRequest?,
        suppressAcknowledgedUriOnRecreation: Boolean = false,
        hasPersistedReadPermission: (Uri) -> Boolean,
    ): ExternalAudioOpenRequestQueueStoreUpdate {
        val loaded = loadState()
        val durableSnapshot = loaded.snapshot
        val savedRequestIds = savedStateRequests.orEmpty().mapTo(hashSetOf(), ExternalAudioOpenRequest::id)
        val snapshotWithAccessibleRequests = durableSnapshot?.let { snapshot ->
            snapshot.copy(
                pendingRequests = snapshot.pendingRequests.filter { request ->
                    request.uri.scheme?.lowercase(Locale.ROOT) != "content" ||
                        request.id in savedRequestIds ||
                        request.uri == currentIntentRequest?.uri ||
                        runCatching { hasPersistedReadPermission(request.uri) }.getOrDefault(true)
                },
            )
        }
        val reopenRequiredCount = durableSnapshot?.pendingRequests.orEmpty().size -
            snapshotWithAccessibleRequests?.pendingRequests.orEmpty().size
        val savedCandidate = mergeExternalAudioOpenRequestQueue(
            persistedSnapshot = snapshotWithAccessibleRequests,
            savedStateRequests = savedStateRequests,
            currentIntentRequest = null,
        )
        if (loaded.problem != null) {
            val recoveryCandidate = mergeExternalAudioOpenRequestQueue(
                persistedSnapshot = savedCandidate,
                savedStateRequests = null,
                currentIntentRequest = currentIntentRequest,
                suppressAcknowledgedUriOnRecreation = suppressAcknowledgedUriOnRecreation,
            )
            val recoveryRejection = snapshotRejection(recoveryCandidate)
            val retryCandidate = if (recoveryRejection != null && currentIntentRequest != null) {
                savedCandidate
            } else {
                recoveryCandidate
            }
            return ExternalAudioOpenRequestQueueStoreUpdate(
                snapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
                persisted = false,
                snapshotIsDurable = false,
                retrySnapshot = retryCandidate.takeIf { it.pendingRequests.isNotEmpty() },
                rejection = recoveryRejection,
                storeProblem = loaded.problem,
                reopenRequiredCount = reopenRequiredCount,
            )
        }

        val savedRejection = snapshotRejection(savedCandidate)
        if (savedRejection != null) {
            return ExternalAudioOpenRequestQueueStoreUpdate(
                snapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
                persisted = durableSnapshot != null,
                snapshotIsDurable = durableSnapshot != null,
                retrySnapshot = savedCandidate.takeIf { it != durableSnapshot },
                rejection = savedRejection,
                reopenRequiredCount = reopenRequiredCount,
            )
        }

        val savedUpdate = when {
            savedCandidate == durableSnapshot ||
                (durableSnapshot == null && savedCandidate == ExternalAudioOpenRequestQueueSnapshot()) ->
                ExternalAudioOpenRequestQueueStoreUpdate(
                    snapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
                    persisted = true,
                    snapshotIsDurable = true,
                )
            else -> persistCandidate(
                previousSnapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
                proposedSnapshot = savedCandidate,
                previousSnapshotIsDurable = durableSnapshot != null,
            )
        }
        val savedUpdateWithReopenCount = savedUpdate.copy(reopenRequiredCount = reopenRequiredCount)
        if (!savedUpdate.persisted) {
            val retryCandidate = savedUpdate.retrySnapshot ?: return savedUpdateWithReopenCount
            if (savedUpdate.rejection != null || currentIntentRequest == null) return savedUpdateWithReopenCount
            val recoveryCandidate = mergeExternalAudioOpenRequestQueue(
                persistedSnapshot = retryCandidate,
                savedStateRequests = null,
                currentIntentRequest = currentIntentRequest,
                suppressAcknowledgedUriOnRecreation = suppressAcknowledgedUriOnRecreation,
            )
            if (recoveryCandidate == retryCandidate) return savedUpdateWithReopenCount
            val recoveryRejection = snapshotRejection(recoveryCandidate)
            return if (recoveryRejection != null) {
                savedUpdateWithReopenCount.copy(rejection = recoveryRejection)
            } else {
                savedUpdateWithReopenCount.copy(retrySnapshot = recoveryCandidate)
            }
        }

        val candidateWithIntent = mergeExternalAudioOpenRequestQueue(
            persistedSnapshot = savedUpdate.snapshot,
            savedStateRequests = null,
            currentIntentRequest = currentIntentRequest,
            suppressAcknowledgedUriOnRecreation = suppressAcknowledgedUriOnRecreation,
        )
        if (candidateWithIntent == savedUpdate.snapshot) return savedUpdateWithReopenCount
        val intentRejection = snapshotRejection(candidateWithIntent)
        if (intentRejection != null) return savedUpdateWithReopenCount.copy(rejection = intentRejection)
        return persistCandidate(
            previousSnapshot = savedUpdate.snapshot,
            proposedSnapshot = candidateWithIntent,
            previousSnapshotIsDurable = savedUpdate.snapshotIsDurable,
        ).copy(reopenRequiredCount = reopenRequiredCount)
    }

    fun enqueue(
        snapshot: ExternalAudioOpenRequestQueueSnapshot,
        incomingRequest: ExternalAudioOpenRequest,
        useProvidedSnapshotAsBase: Boolean = false,
    ): ExternalAudioOpenRequestQueueStoreUpdate = synchronized(processLock) {
        enqueueLocked(snapshot, incomingRequest, useProvidedSnapshotAsBase)
    }

    private fun enqueueLocked(
        snapshot: ExternalAudioOpenRequestQueueSnapshot,
        incomingRequest: ExternalAudioOpenRequest,
        useProvidedSnapshotAsBase: Boolean = false,
    ): ExternalAudioOpenRequestQueueStoreUpdate {
        val loaded = loadState()
        val durableSnapshot = loaded.snapshot
        val base = if (useProvidedSnapshotAsBase) {
            mergeExternalAudioOpenRequestQueueSnapshots(durableSnapshot, snapshot)
        } else {
            durableSnapshot ?: snapshot
        }
        val incomingUriString = incomingRequest.uri.toString()
        if (!isCanonicalExternalAudioOpenRequestId(incomingRequest.id) || !isAllowedQueueUri(incomingRequest.uri)) {
            return rejectedUpdate(base, durableSnapshot, ExternalAudioOpenRequestQueueRejection.InvalidRequest)
                .copy(storeProblem = loaded.problem)
        }
        if (incomingUriString.length > MAX_EXTERNAL_AUDIO_URI_BYTES ||
            utf8Size(incomingUriString) > MAX_EXTERNAL_AUDIO_URI_BYTES
        ) {
            return rejectedUpdate(base, durableSnapshot, ExternalAudioOpenRequestQueueRejection.UriTooLarge)
                .copy(storeProblem = loaded.problem)
        }
        if (incomingRequest.id in base.acknowledgedRequestIds) {
            if (!useProvidedSnapshotAsBase && durableSnapshot != null) {
                return ExternalAudioOpenRequestQueueStoreUpdate(durableSnapshot, persisted = true, snapshotIsDurable = true)
            }
            return persistCandidate(
                previousSnapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
                proposedSnapshot = base,
                previousSnapshotIsDurable = durableSnapshot != null,
            )
        }
        val sameIdPending = base.pendingRequests.any { it.id == incomingRequest.id }
        if (!sameIdPending && base.pendingRequests.size >= MAX_EXTERNAL_AUDIO_PENDING_REQUESTS) {
            return rejectedUpdate(base, durableSnapshot, ExternalAudioOpenRequestQueueRejection.QueueFull)
                .copy(storeProblem = loaded.problem)
        }
        val requests = if (sameIdPending) base.pendingRequests else base.pendingRequests + incomingRequest
        val updated = replaceExternalAudioOpenRequestQueue(base, requests)
        val rejection = snapshotRejection(updated)
        if (loaded.problem != null) {
            return ExternalAudioOpenRequestQueueStoreUpdate(
                snapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
                persisted = false,
                snapshotIsDurable = false,
                retrySnapshot = if (rejection == null) updated else base,
                rejection = rejection,
                storeProblem = loaded.problem,
            )
        }
        if (durableSnapshot != null && updated == durableSnapshot) {
            return ExternalAudioOpenRequestQueueStoreUpdate(durableSnapshot, persisted = true, snapshotIsDurable = true)
        }
        if (rejection != null) return rejectedUpdate(base, durableSnapshot, rejection)
        return persistCandidate(
            previousSnapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
            proposedSnapshot = updated,
            previousSnapshotIsDurable = durableSnapshot != null,
        )
    }

    fun replace(
        snapshot: ExternalAudioOpenRequestQueueSnapshot,
        pendingRequests: List<ExternalAudioOpenRequest>,
        useProvidedSnapshotAsBase: Boolean = false,
    ): ExternalAudioOpenRequestQueueStoreUpdate = synchronized(processLock) {
        replaceLocked(snapshot, pendingRequests, useProvidedSnapshotAsBase)
    }

    fun updatePendingRequests(
        snapshot: ExternalAudioOpenRequestQueueSnapshot,
        useProvidedSnapshotAsBase: Boolean = false,
        updateRequests: (List<ExternalAudioOpenRequest>) -> List<ExternalAudioOpenRequest>,
    ): ExternalAudioOpenRequestQueueStoreUpdate = synchronized(processLock) {
        val durableSnapshot = loadState().snapshot
        val base = if (useProvidedSnapshotAsBase) {
            mergeExternalAudioOpenRequestQueueSnapshots(durableSnapshot, snapshot)
        } else {
            durableSnapshot ?: snapshot
        }
        replaceLocked(base, updateRequests(base.pendingRequests), useProvidedSnapshotAsBase = true)
    }

    private fun replaceLocked(
        snapshot: ExternalAudioOpenRequestQueueSnapshot,
        pendingRequests: List<ExternalAudioOpenRequest>,
        useProvidedSnapshotAsBase: Boolean = false,
    ): ExternalAudioOpenRequestQueueStoreUpdate {
        val loaded = loadState()
        val durableSnapshot = loaded.snapshot
        val base = if (useProvidedSnapshotAsBase) {
            mergeExternalAudioOpenRequestQueueSnapshots(durableSnapshot, snapshot)
        } else {
            durableSnapshot ?: snapshot
        }
        if (loaded.problem != null) {
            val candidate = replaceExternalAudioOpenRequestQueue(base, pendingRequests)
            return ExternalAudioOpenRequestQueueStoreUpdate(
                snapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
                persisted = false,
                snapshotIsDurable = false,
                retrySnapshot = candidate,
                storeProblem = loaded.problem,
            )
        }
        val updated = replaceExternalAudioOpenRequestQueue(base, pendingRequests)
        if (durableSnapshot != null && updated == durableSnapshot) {
            return ExternalAudioOpenRequestQueueStoreUpdate(durableSnapshot, persisted = true, snapshotIsDurable = true)
        }
        val rejection = snapshotRejection(updated)
        if (rejection != null) return rejectedUpdate(base, durableSnapshot, rejection)
        return persistCandidate(
            previousSnapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
            proposedSnapshot = updated,
            previousSnapshotIsDurable = durableSnapshot != null,
            allowTrimFailureMessages = true,
        )
    }

    fun retry(snapshot: ExternalAudioOpenRequestQueueSnapshot): ExternalAudioOpenRequestQueueStoreUpdate = synchronized(processLock) {
        retryLocked(snapshot)
    }

    private fun retryLocked(snapshot: ExternalAudioOpenRequestQueueSnapshot): ExternalAudioOpenRequestQueueStoreUpdate {
        val durableSnapshot = loadState().snapshot
        val retryCandidate = mergeExternalAudioOpenRequestQueueSnapshots(durableSnapshot, snapshot)
        return persistCandidate(
            previousSnapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
            proposedSnapshot = retryCandidate,
            previousSnapshotIsDurable = durableSnapshot != null,
            allowTrimFailureMessages = true,
        )
    }

    fun recoverCorruptSnapshot(
        retryCandidate: ExternalAudioOpenRequestQueueSnapshot,
    ): ExternalAudioOpenRequestQueueStoreUpdate = synchronized(processLock) {
        recoverCorruptSnapshotLocked(retryCandidate)
    }

    private fun recoverCorruptSnapshotLocked(
        retryCandidate: ExternalAudioOpenRequestQueueSnapshot,
    ): ExternalAudioOpenRequestQueueStoreUpdate {
        val loaded = loadState()
        val proposed = mergeRecoveryCandidate(loaded.snapshot, retryCandidate)
        val rejection = snapshotRejection(proposed)
        if (rejection != null) {
            return ExternalAudioOpenRequestQueueStoreUpdate(
                snapshot = loaded.snapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
                persisted = loaded.snapshot != null,
                snapshotIsDurable = loaded.snapshot != null,
                retrySnapshot = proposed,
                rejection = rejection,
                storeProblem = loaded.problem,
            )
        }

        if (loaded.status == ExternalAudioOpenQueueLoadStatus.Corrupt ||
            loaded.status == ExternalAudioOpenQueueLoadStatus.Oversized
        ) {
            val rawSnapshot = loaded.rawSnapshot
                ?: return recoveryFailure(loaded, proposed, ExternalAudioOpenQueueStoreProblem.RecoveryFailure)
            val backupSucceeded = try {
                backupCorruptSnapshot?.invoke(rawSnapshot) == true
            } catch (_: Exception) {
                false
            }
            if (!backupSucceeded) {
                return recoveryFailure(loaded, proposed, ExternalAudioOpenQueueStoreProblem.RecoveryFailure)
            }
            val encoded = encodeBoundedSnapshot(proposed, allowTrimFailureMessages = true)
                ?: return recoveryFailure(
                    loaded,
                    proposed,
                    ExternalAudioOpenQueueStoreProblem.RecoveryWriteFailure,
                    ExternalAudioOpenRequestQueueRejection.SnapshotTooLarge,
                )
            val savedSnapshot = decode(encoded) ?: proposed
            val persisted = tryCommitEncoded(encoded)
            if (!persisted) {
                return recoveryFailure(loaded, savedSnapshot, ExternalAudioOpenQueueStoreProblem.RecoveryWriteFailure)
            }
            cacheLoadState(QueueLoadState(savedSnapshot, ExternalAudioOpenQueueLoadStatus.Ready))
            return ExternalAudioOpenRequestQueueStoreUpdate(savedSnapshot, persisted = true, snapshotIsDurable = true)
        }

        if (loaded.status == ExternalAudioOpenQueueLoadStatus.ReadFailure) {
            return ExternalAudioOpenRequestQueueStoreUpdate(
                snapshot = ExternalAudioOpenRequestQueueSnapshot(),
                persisted = false,
                snapshotIsDurable = false,
                retrySnapshot = proposed,
                storeProblem = ExternalAudioOpenQueueStoreProblem.ReadFailure,
            )
        }

        val update = persistCandidate(
            previousSnapshot = loaded.snapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
            proposedSnapshot = proposed,
            previousSnapshotIsDurable = loaded.snapshot != null,
            allowTrimFailureMessages = true,
        )
        return if (!update.persisted && update.storeProblem == ExternalAudioOpenQueueStoreProblem.WriteFailure) {
            update.copy(storeProblem = ExternalAudioOpenQueueStoreProblem.RecoveryWriteFailure)
        } else {
            update
        }
    }

    private fun recoveryFailure(
        loaded: QueueLoadState,
        retryCandidate: ExternalAudioOpenRequestQueueSnapshot,
        problem: ExternalAudioOpenQueueStoreProblem,
        rejection: ExternalAudioOpenRequestQueueRejection? = null,
    ) = ExternalAudioOpenRequestQueueStoreUpdate(
        snapshot = loaded.snapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
        persisted = false,
        snapshotIsDurable = loaded.snapshot != null,
        retrySnapshot = retryCandidate,
        rejection = rejection,
        storeProblem = problem,
    )

    private fun rejectedUpdate(
        candidate: ExternalAudioOpenRequestQueueSnapshot,
        durableSnapshot: ExternalAudioOpenRequestQueueSnapshot?,
        rejection: ExternalAudioOpenRequestQueueRejection,
    ): ExternalAudioOpenRequestQueueStoreUpdate = ExternalAudioOpenRequestQueueStoreUpdate(
        snapshot = durableSnapshot ?: ExternalAudioOpenRequestQueueSnapshot(),
        persisted = durableSnapshot != null,
        snapshotIsDurable = durableSnapshot != null,
        retrySnapshot = candidate.takeIf { it != durableSnapshot },
        rejection = rejection,
    )

    private fun persistCandidate(
        previousSnapshot: ExternalAudioOpenRequestQueueSnapshot,
        proposedSnapshot: ExternalAudioOpenRequestQueueSnapshot,
        previousSnapshotIsDurable: Boolean,
        allowTrimFailureMessages: Boolean = false,
    ): ExternalAudioOpenRequestQueueStoreUpdate {
        val loaded = loadState()
        if (loaded.problem != null) {
            return ExternalAudioOpenRequestQueueStoreUpdate(
                snapshot = previousSnapshot,
                persisted = false,
                snapshotIsDurable = previousSnapshotIsDurable,
                retrySnapshot = proposedSnapshot,
                storeProblem = loaded.problem,
            )
        }
        val bounded = encodeBoundedSnapshot(proposedSnapshot, allowTrimFailureMessages)
            ?: return ExternalAudioOpenRequestQueueStoreUpdate(
                snapshot = previousSnapshot,
                persisted = false,
                snapshotIsDurable = previousSnapshotIsDurable,
                retrySnapshot = proposedSnapshot,
                rejection = snapshotRejection(proposedSnapshot) ?: ExternalAudioOpenRequestQueueRejection.SnapshotTooLarge,
            )
        val savedSnapshot = decode(bounded) ?: proposedSnapshot
        val persisted = save(savedSnapshot)
        return if (persisted) {
            ExternalAudioOpenRequestQueueStoreUpdate(savedSnapshot, persisted = true, snapshotIsDurable = true)
        } else {
            ExternalAudioOpenRequestQueueStoreUpdate(
                snapshot = previousSnapshot,
                persisted = false,
                snapshotIsDurable = previousSnapshotIsDurable,
                retrySnapshot = savedSnapshot,
                storeProblem = ExternalAudioOpenQueueStoreProblem.WriteFailure,
            )
        }
    }

    private fun loadState(): QueueLoadState {
        processCacheKey?.let { processSnapshotCache[it]?.let { cached -> return cached } }
        val loaded = try {
            val encoded = readEncoded()
            if (encoded == null) {
                return cacheLoadState(QueueLoadState(null, ExternalAudioOpenQueueLoadStatus.NoData))
            }
            if (encoded.length > MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES ||
                utf8Size(encoded) > MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES
            ) {
                val recoverableRaw = encoded.takeIf {
                    it.length <= MAX_EXTERNAL_AUDIO_QUEUE_QUARANTINE_BYTES &&
                        utf8Size(it) <= MAX_EXTERNAL_AUDIO_QUEUE_QUARANTINE_BYTES
                }
                QueueLoadState(null, ExternalAudioOpenQueueLoadStatus.Oversized, rawSnapshot = recoverableRaw)
            } else {
                val decoded = decode(encoded)
                if (decoded == null) {
                    QueueLoadState(null, ExternalAudioOpenQueueLoadStatus.Corrupt, rawSnapshot = encoded)
                } else {
                    QueueLoadState(decoded, ExternalAudioOpenQueueLoadStatus.Ready)
                }
            }
        } catch (_: Exception) {
            QueueLoadState(null, ExternalAudioOpenQueueLoadStatus.ReadFailure)
        }
        return if (loaded.status == ExternalAudioOpenQueueLoadStatus.ReadFailure) loaded else cacheLoadState(loaded)
    }

    private fun cacheLoadState(value: QueueLoadState): QueueLoadState {
        processCacheKey?.let { processSnapshotCache[it] = value }
        return value
    }

    private fun tryCommitEncoded(encoded: String): Boolean = try {
        commitEncoded(encoded)
    } catch (_: Exception) {
        false
    }

    private fun mergeRecoveryCandidate(
        persistedSnapshot: ExternalAudioOpenRequestQueueSnapshot?,
        retryCandidate: ExternalAudioOpenRequestQueueSnapshot,
    ): ExternalAudioOpenRequestQueueSnapshot =
        mergeExternalAudioOpenRequestQueueSnapshots(persistedSnapshot, retryCandidate)

    private fun snapshotRejection(snapshot: ExternalAudioOpenRequestQueueSnapshot): ExternalAudioOpenRequestQueueRejection? {
        if (snapshot.pendingRequests.size > MAX_EXTERNAL_AUDIO_PENDING_REQUESTS) return ExternalAudioOpenRequestQueueRejection.QueueFull
        if (snapshot.acknowledgedRequestIds.size > MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_IDS ||
            snapshot.acknowledgedRequestUris.size > MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_URIS
        ) return ExternalAudioOpenRequestQueueRejection.SnapshotTooLarge
        if (snapshot.pendingRequests.any { !isCanonicalExternalAudioOpenRequestId(it.id) || !isAllowedQueueUri(it.uri) }) {
            return ExternalAudioOpenRequestQueueRejection.InvalidRequest
        }
        if (snapshot.pendingRequests.any {
                val uri = it.uri.toString()
                uri.length > MAX_EXTERNAL_AUDIO_URI_BYTES || utf8Size(uri) > MAX_EXTERNAL_AUDIO_URI_BYTES
            }
        ) {
            return ExternalAudioOpenRequestQueueRejection.UriTooLarge
        }
        if (snapshot.pendingRequests.map(ExternalAudioOpenRequest::id).distinct().size != snapshot.pendingRequests.size) {
            return ExternalAudioOpenRequestQueueRejection.InvalidRequest
        }
        if (snapshot.pendingRequests.any {
                val failureMessage = it.failureMessage.orEmpty()
                it.attempt < 0 || failureMessage.length > MAX_EXTERNAL_AUDIO_FAILURE_MESSAGE_BYTES ||
                    utf8Size(failureMessage) > MAX_EXTERNAL_AUDIO_FAILURE_MESSAGE_BYTES
            }
        ) {
            return ExternalAudioOpenRequestQueueRejection.InvalidRequest
        }
        if (snapshot.acknowledgedRequestIds.any { !isCanonicalExternalAudioOpenRequestId(it) } ||
            snapshot.acknowledgedRequestUris.any { (id, uri) ->
                id !in snapshot.acknowledgedRequestIds || !isCanonicalExternalAudioOpenRequestId(id) ||
                    uri.length > MAX_EXTERNAL_AUDIO_URI_BYTES || utf8Size(uri) > MAX_EXTERNAL_AUDIO_URI_BYTES ||
                    !isAllowedQueueUri(runCatching { parseUri(uri) }.getOrNull())
            }
        ) return ExternalAudioOpenRequestQueueRejection.InvalidRequest
        if (utf8Size(encodeAcknowledgements(snapshot)) > MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_BYTES ||
            utf8Size(encode(snapshot)) > MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES
        ) {
            return ExternalAudioOpenRequestQueueRejection.SnapshotTooLarge
        }
        return null
    }

    private fun encodeBoundedSnapshot(
        proposed: ExternalAudioOpenRequestQueueSnapshot,
        allowTrimFailureMessages: Boolean,
    ): String? {
        var snapshot = normalizeAcknowledgements(proposed)
        if (snapshot.pendingRequests.size > MAX_EXTERNAL_AUDIO_PENDING_REQUESTS) return null
        if (snapshot.pendingRequests.any {
                !isCanonicalExternalAudioOpenRequestId(it.id) || !isAllowedQueueUri(it.uri) ||
                    it.uri.toString().length > MAX_EXTERNAL_AUDIO_URI_BYTES ||
                    utf8Size(it.uri.toString()) > MAX_EXTERNAL_AUDIO_URI_BYTES || it.attempt < 0
            }
        ) return null
        if (snapshot.pendingRequests.map(ExternalAudioOpenRequest::id).distinct().size != snapshot.pendingRequests.size) return null
        if (snapshot.acknowledgedRequestIds.size > MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_IDS ||
            snapshot.acknowledgedRequestUris.size > MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_URIS
        ) return null
        if (snapshot.acknowledgedRequestIds.any { !isCanonicalExternalAudioOpenRequestId(it) }) return null
        if (snapshot.acknowledgedRequestUris.any { (id, uri) ->
                id !in snapshot.acknowledgedRequestIds || !isCanonicalExternalAudioOpenRequestId(id) ||
                    uri.length > MAX_EXTERNAL_AUDIO_URI_BYTES || utf8Size(uri) > MAX_EXTERNAL_AUDIO_URI_BYTES ||
                    !isAllowedQueueUri(runCatching { parseUri(uri) }.getOrNull())
            }
        ) return null
        if (utf8Size(encodeAcknowledgements(snapshot)) > MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_BYTES) return null

        var encoded = encode(snapshot)
        if (utf8Size(encoded) <= MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES) return encoded
        if (!allowTrimFailureMessages) return null
        while (utf8Size(encoded) > MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES) {
            val index = snapshot.pendingRequests.indexOfLast { !it.failureMessage.isNullOrEmpty() }
            if (index < 0) return null
            val requests = snapshot.pendingRequests.toMutableList()
            val oldMessage = requireNotNull(requests[index].failureMessage)
            requests[index] = requests[index].copy(failureMessage = utf8Prefix(oldMessage, utf8Size(oldMessage) / 2).ifEmpty { null })
            snapshot = snapshot.copy(pendingRequests = requests)
            encoded = encode(snapshot)
        }
        return encoded
    }

    private fun decode(encoded: String): ExternalAudioOpenRequestQueueSnapshot? {
        if (utf8Size(encoded) > MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES) return null
        val lines = encoded.split(RECORD_SEPARATOR)
        if (lines.firstOrNull() != STORE_HEADER) return null

        val acknowledgedIds = linkedSetOf<String>()
        val acknowledgedUris = linkedMapOf<String, String>()
        val requests = mutableListOf<ExternalAudioOpenRequest>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val fields = line.split(FIELD_SEPARATOR, limit = 6)
            when (fields.firstOrNull()) {
                "A" -> {
                    if (fields.size !in 2..3) return null
                    val id = runCatching { decodeField(fields[1]) }.getOrNull() ?: return null
                    if (!isCanonicalExternalAudioOpenRequestId(id)) return null
                    if (!acknowledgedIds.add(id)) return null
                    fields.getOrNull(2)?.let { encodedUri ->
                        val uri = runCatching { decodeField(encodedUri) }.getOrNull() ?: return null
                        if (utf8Size(uri) > MAX_EXTERNAL_AUDIO_URI_BYTES || !isAllowedQueueUri(runCatching { parseUri(uri) }.getOrNull())) return null
                        acknowledgedUris[id] = uri
                    }
                }
                "P" -> {
                    if (fields.size != 6) return null
                    val id = runCatching { decodeField(fields[1]) }.getOrNull() ?: return null
                    if (!isCanonicalExternalAudioOpenRequestId(id) || requests.any { it.id == id }) return null
                    val uriString = runCatching { decodeField(fields[2]) }.getOrNull() ?: return null
                    if (utf8Size(uriString) > MAX_EXTERNAL_AUDIO_URI_BYTES) return null
                    val uri = runCatching { parseUri(uriString) }.getOrNull() ?: return null
                    if (!isAllowedQueueUri(uri)) return null
                    val attempt = fields[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                    val failureKindRaw = runCatching { decodeField(fields[4]) }.getOrNull() ?: return null
                    val failureMessageRaw = runCatching { decodeField(fields[5]) }.getOrNull() ?: return null
                    if (utf8Size(failureMessageRaw) > MAX_EXTERNAL_AUDIO_FAILURE_MESSAGE_BYTES) return null
                    val failureKind = ExternalAudioOpenFailureKind.entries.firstOrNull { it.name == failureKindRaw }
                    if (failureKindRaw.isNotEmpty() && failureKind == null) return null
                    requests += ExternalAudioOpenRequest(
                        id = id,
                        uri = uri,
                        attempt = attempt,
                        failureKind = failureKind,
                        failureMessage = failureMessageRaw.takeIf(String::isNotEmpty),
                    )
                    if (requests.size > MAX_EXTERNAL_AUDIO_PENDING_REQUESTS) return null
                }
                else -> return null
            }
        }
        val boundedAcks = boundAcknowledgements(acknowledgedIds, acknowledgedUris)
        return ExternalAudioOpenRequestQueueSnapshot(
            pendingRequests = requests,
            acknowledgedRequestIds = boundedAcks.first,
            acknowledgedRequestUris = boundedAcks.second,
        ).takeIf { snapshotRejection(it) == null }
    }

    private fun encode(snapshot: ExternalAudioOpenRequestQueueSnapshot): String = buildList {
        add(STORE_HEADER)
        snapshot.acknowledgedRequestIds.forEach { id ->
            val uri = snapshot.acknowledgedRequestUris[id]
            val fields = if (uri == null) listOf("A", encodeField(id)) else listOf("A", encodeField(id), encodeField(uri))
            add(fields.joinToString(FIELD_SEPARATOR.toString()))
        }
        snapshot.pendingRequests.forEach { request ->
            add(
                listOf(
                    "P",
                    encodeField(request.id),
                    encodeField(request.uri.toString()),
                    request.attempt.toString(),
                    encodeField(request.failureKind?.name.orEmpty()),
                    encodeField(request.failureMessage.orEmpty()),
                ).joinToString(FIELD_SEPARATOR.toString()),
            )
        }
    }.joinToString(RECORD_SEPARATOR.toString())

    private fun encodeAcknowledgements(snapshot: ExternalAudioOpenRequestQueueSnapshot): String = buildString {
        snapshot.acknowledgedRequestIds.forEach { id ->
            append("A").append(FIELD_SEPARATOR).append(encodeField(id))
            snapshot.acknowledgedRequestUris[id]?.let { append(FIELD_SEPARATOR).append(encodeField(it)) }
            append(RECORD_SEPARATOR)
        }
    }

    private fun encodeField(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeField(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun normalizeAcknowledgements(snapshot: ExternalAudioOpenRequestQueueSnapshot): ExternalAudioOpenRequestQueueSnapshot {
        val ids = LinkedHashSet<String>().apply {
            addAll(snapshot.acknowledgedRequestIds)
            addAll(snapshot.acknowledgedRequestUris.keys)
        }
        val (boundedIds, boundedUris) = boundAcknowledgements(ids, snapshot.acknowledgedRequestUris)
        return snapshot.copy(acknowledgedRequestIds = boundedIds, acknowledgedRequestUris = boundedUris)
    }

    private fun boundAcknowledgements(
        inputIds: Collection<String>,
        inputUris: Map<String, String>,
    ): Pair<Set<String>, Map<String, String>> = boundAcknowledgedQueueHistory(inputIds, inputUris)

    private fun isAllowedQueueUri(uri: Uri?): Boolean =
        uri != null && uri.scheme?.lowercase(Locale.ROOT) == "content"

    private data class QueueLoadState(
        val snapshot: ExternalAudioOpenRequestQueueSnapshot?,
        val status: ExternalAudioOpenQueueLoadStatus,
        val rawSnapshot: String? = null,
    ) {
        val problem: ExternalAudioOpenQueueStoreProblem?
            get() = when (status) {
                ExternalAudioOpenQueueLoadStatus.NoData,
                ExternalAudioOpenQueueLoadStatus.Ready -> null
                ExternalAudioOpenQueueLoadStatus.Corrupt -> ExternalAudioOpenQueueStoreProblem.Corrupt
                ExternalAudioOpenQueueLoadStatus.Oversized -> ExternalAudioOpenQueueStoreProblem.Oversized
                ExternalAudioOpenQueueLoadStatus.ReadFailure -> ExternalAudioOpenQueueStoreProblem.ReadFailure
            }
    }

    private companion object {
        val processSnapshotCache = ConcurrentHashMap<String, QueueLoadState>()
        const val STORE_HEADER = "aftertone-external-audio-open-queue-v1"
        const val FIELD_SEPARATOR = '\t'
        const val RECORD_SEPARATOR = '\n'
    }
}

internal const val MAX_EXTERNAL_AUDIO_PENDING_REQUESTS = 32
internal const val MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES = 64 * 1024
internal const val MAX_EXTERNAL_AUDIO_QUEUE_QUARANTINE_BYTES = 4 * 1024 * 1024
internal const val MAX_EXTERNAL_AUDIO_QUEUE_QUARANTINE_SNAPSHOTS = 8
internal const val MAX_EXTERNAL_AUDIO_URI_BYTES = 2 * 1024
internal const val MAX_EXTERNAL_AUDIO_DECLARED_MIME_TYPE_BYTES = 256
internal const val MAX_EXTERNAL_AUDIO_FAILURE_MESSAGE_BYTES = 1024
internal const val MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_IDS = 256
internal const val MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_URIS = 32
internal const val MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_BYTES = 24 * 1024

internal fun isCanonicalExternalAudioOpenRequestId(value: String): Boolean =
    value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

internal suspend fun <T> withExternalAudioQueueIo(block: () -> T): T =
    withContext(Dispatchers.IO) { block() }

internal suspend fun retryExternalAudioQueueCandidateIfCurrent(
    mutationMutex: Mutex,
    currentCandidate: () -> ExternalAudioOpenRequestQueueSnapshot?,
    retryCandidate: suspend (ExternalAudioOpenRequestQueueSnapshot) -> Unit,
) {
    mutationMutex.lock()
    try {
        val current = currentCandidate()
        if (current != null) retryCandidate(current)
    } finally {
        mutationMutex.unlock()
    }
}

internal fun utf8Size(value: String): Int {
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val current = value[index]
        val increment = when {
            current <= '\u007f' -> 1
            current <= '\u07ff' -> 2
            Character.isHighSurrogate(current) && index + 1 < value.length &&
                Character.isLowSurrogate(value[index + 1]) -> {
                index++
                4
            }
            Character.isSurrogate(current) -> 1
            else -> 3
        }
        if (bytes > Int.MAX_VALUE - increment) return Int.MAX_VALUE
        bytes += increment
        index++
    }
    return bytes
}

internal fun utf8Prefix(value: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    val result = StringBuilder()
    var index = 0
    var bytes = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val piece = String(Character.toChars(codePoint))
        val pieceBytes = utf8Size(piece)
        if (bytes + pieceBytes > maxBytes) break
        result.append(piece)
        bytes += pieceBytes
        index += Character.charCount(codePoint)
    }
    return result.toString()
}

internal fun mergeExternalAudioOpenRequestQueueSnapshots(
    durableSnapshot: ExternalAudioOpenRequestQueueSnapshot?,
    retryCandidate: ExternalAudioOpenRequestQueueSnapshot,
): ExternalAudioOpenRequestQueueSnapshot {
    val merged = mergeExternalAudioOpenRequestQueue(
        persistedSnapshot = durableSnapshot,
        savedStateRequests = retryCandidate.pendingRequests,
        currentIntentRequest = null,
    )
    val durableIds = LinkedHashSet(merged.acknowledgedRequestIds).apply {
        addAll(merged.acknowledgedRequestUris.keys)
    }
    val candidateIds = LinkedHashSet(retryCandidate.acknowledgedRequestIds).apply {
        addAll(retryCandidate.acknowledgedRequestUris.keys)
    }
    // Retry candidates predate the durable snapshot read here; put their unique history first so
    // the bounded queue drops stale candidate entries before evicting newer durable acknowledgments.
    val acknowledgedIds = LinkedHashSet<String>().apply {
        candidateIds.filterNot { it in durableIds }.forEach(::add)
        addAll(durableIds)
    }
    val acknowledgedUris = LinkedHashMap<String, String>().apply {
        retryCandidate.acknowledgedRequestUris
            .filterKeys { it !in merged.acknowledgedRequestUris }
            .forEach { (id, uri) -> put(id, uri) }
        putAll(merged.acknowledgedRequestUris)
    }
    val (boundedIds, boundedUris) = boundAcknowledgedQueueHistory(acknowledgedIds, acknowledgedUris)
    return merged.copy(
        pendingRequests = merged.pendingRequests.filterNot { it.id in acknowledgedIds },
        acknowledgedRequestIds = boundedIds,
        acknowledgedRequestUris = boundedUris,
    )
}

internal fun mergeExternalAudioOpenRequestQueue(
    persistedSnapshot: ExternalAudioOpenRequestQueueSnapshot?,
    savedStateRequests: List<ExternalAudioOpenRequest>?,
    currentIntentRequest: ExternalAudioOpenRequest?,
    suppressAcknowledgedUriOnRecreation: Boolean = false,
): ExternalAudioOpenRequestQueueSnapshot {
    val persisted = persistedSnapshot ?: ExternalAudioOpenRequestQueueSnapshot()
    val mergedRequests = persisted.pendingRequests.toMutableList()
    savedStateRequests.orEmpty().forEach { savedRequest ->
        if (savedRequest.id in persisted.acknowledgedRequestIds) return@forEach
        val sameIdIndex = mergedRequests.indexOfFirst { it.id == savedRequest.id }
        if (sameIdIndex >= 0) {
            val durableRequest = mergedRequests[sameIdIndex]
            val savedFailureAddsState =
                savedRequest.attempt == durableRequest.attempt &&
                    savedRequest.failureKind != null && durableRequest.failureKind == null
            if (savedRequest.attempt > durableRequest.attempt || savedFailureAddsState) {
                mergedRequests[sameIdIndex] = savedRequest
            }
            return@forEach
        }
        mergedRequests += savedRequest
    }
    val base = if (mergedRequests == persisted.pendingRequests) {
        persisted
    } else {
        persisted.copy(pendingRequests = mergedRequests)
    }
    val incoming = currentIntentRequest ?: return base
    if (incoming.id in base.acknowledgedRequestIds) return base
    if (suppressAcknowledgedUriOnRecreation && incoming.uri.toString() in base.acknowledgedRequestUris.values) return base
    if (base.pendingRequests.any { it.id == incoming.id || it.uri.toString() == incoming.uri.toString() }) return base
    return base.copy(pendingRequests = base.pendingRequests + incoming)
}

internal fun shouldSuppressAcknowledgedUriOnRecreation(
    activityRecreated: Boolean,
    hasCurrentExternalIntent: Boolean,
    requestIdWasSupplied: Boolean,
): Boolean = activityRecreated && hasCurrentExternalIntent && !requestIdWasSupplied

internal fun externalAudioOpenQueueAfterUpdate(
    currentSnapshot: ExternalAudioOpenRequestQueueSnapshot,
    update: ExternalAudioOpenRequestQueueStoreUpdate,
): ExternalAudioOpenRequestQueueSnapshot =
    if (update.snapshotIsDurable) update.snapshot else currentSnapshot

internal fun externalAudioQueueRetryCandidateAfterUpdate(
    currentRetryCandidate: ExternalAudioOpenRequestQueueSnapshot?,
    update: ExternalAudioOpenRequestQueueStoreUpdate,
): ExternalAudioOpenRequestQueueSnapshot? = when {
    update.rejection != null -> currentRetryCandidate ?: update.retrySnapshot
    update.persisted -> null
    else -> update.retrySnapshot ?: currentRetryCandidate
}

internal fun externalAudioOpenRequestIdToBind(
    incomingRequest: ExternalAudioOpenRequest,
    queue: ExternalAudioOpenRequestQueueSnapshot,
    allowAcknowledgedBinding: Boolean,
    bindMatchingUri: Boolean = true,
): String {
    queue.pendingRequests.firstOrNull { it.id == incomingRequest.id }?.let { return it.id }
    if (bindMatchingUri) {
        queue.pendingRequests.firstOrNull { it.uri.toString() == incomingRequest.uri.toString() }?.let { return it.id }
    }
    if (allowAcknowledgedBinding) {
        queue.acknowledgedRequestUris.entries.firstOrNull {
            it.value == incomingRequest.uri.toString()
        }?.key?.let { return it }
    }
    return incomingRequest.id
}

internal fun externalAudioOpenRequestIdToBindAfterUpdate(
    incomingRequest: ExternalAudioOpenRequest,
    update: ExternalAudioOpenRequestQueueStoreUpdate,
    allowAcknowledgedBinding: Boolean,
    bindMatchingUri: Boolean = true,
): String = externalAudioOpenRequestIdToBind(
    incomingRequest = incomingRequest,
    queue = update.retrySnapshot ?: update.snapshot,
    allowAcknowledgedBinding = allowAcknowledgedBinding,
    bindMatchingUri = bindMatchingUri,
)

/**
 * Acknowledged-URI suppression is intentionally finite: only the newest 32 unique URIs within
 * the 24 KiB acknowledgement budget survive. An id-less stale launch older than that horizon may
 * be treated as new after process recreation; every genuinely new onNewIntent remains new.
 */
internal fun replaceExternalAudioOpenRequestQueue(
    snapshot: ExternalAudioOpenRequestQueueSnapshot,
    pendingRequests: List<ExternalAudioOpenRequest>,
): ExternalAudioOpenRequestQueueSnapshot {
    val retainedIds = pendingRequests.mapTo(mutableSetOf(), ExternalAudioOpenRequest::id)
    val removedRequests = snapshot.pendingRequests.filterNot { it.id in retainedIds }
    val ids = LinkedHashSet(snapshot.acknowledgedRequestIds)
    val uris = LinkedHashMap(snapshot.acknowledgedRequestUris)
    removedRequests.forEach { request ->
        ids += request.id
        uris[request.id] = request.uri.toString()
    }
    val (boundedIds, boundedUris) = boundAcknowledgedQueueHistory(ids, uris)
    return snapshot.copy(
        pendingRequests = pendingRequests,
        acknowledgedRequestIds = boundedIds,
        acknowledgedRequestUris = boundedUris,
    )
}

private fun boundAcknowledgedQueueHistory(
    inputIds: Collection<String>,
    inputUris: Map<String, String>,
): Pair<Set<String>, Map<String, String>> {
    val ids = LinkedHashSet(inputIds)
    inputUris.keys.forEach(ids::add)
    while (ids.size > MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_IDS) ids.remove(ids.first())
    val retainedUrisReversed = mutableListOf<Pair<String, String>>()
    val uniqueUris = mutableSetOf<String>()
    fun acknowledgementLineBytes(id: String, uri: String? = null): Int {
        val encodedId = java.net.URLEncoder.encode(id, StandardCharsets.UTF_8.name())
        val encodedUri = uri?.let { java.net.URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }
        val line = if (encodedUri == null) "A\t$encodedId\n" else "A\t$encodedId\t$encodedUri\n"
        return utf8Size(line)
    }
    var bytes = ids.sumOf { id -> acknowledgementLineBytes(id) }
    for ((id, uri) in inputUris.entries.filter { it.key in ids }.asReversed()) {
        if (uri in uniqueUris || uniqueUris.size >= MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_URIS) continue
        val extraBytes = acknowledgementLineBytes(id, uri) - acknowledgementLineBytes(id)
        if (bytes + extraBytes > MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_BYTES) continue
        retainedUrisReversed += id to uri
        uniqueUris += uri
        bytes += extraBytes
    }
    val uris = LinkedHashMap<String, String>()
    retainedUrisReversed.asReversed().forEach { (id, uri) -> uris[id] = uri }
    return ids to uris
}

internal const val EXTERNAL_AUDIO_OPEN_REQUEST_QUEUE_PREFERENCES = "aftertone_external_audio_open_requests"
internal const val EXTERNAL_AUDIO_OPEN_REQUEST_QUEUE_KEY = "queue_snapshot_v1"
internal const val EXTRA_EXTERNAL_AUDIO_OPEN_REQUEST_ID = "com.neiro.aftertone.extra.EXTERNAL_AUDIO_OPEN_REQUEST_ID"
