package com.neiro.aftertone

import com.neiro.aftertone.model.Track
import java.io.IOException
import com.neiro.aftertone.storage.RestoreActionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

internal typealias ExternalAudioDispatchPermit = RestoreActionGate.DispatchPermit

internal class ExternalAudioImportProcessState {
    internal val importMutex = Mutex()
    internal val importsInFlight = mutableMapOf<String, CompletableDeferred<Track>>()
    internal val persistedImports = object : LinkedHashMap<String, Track>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?): Boolean = size > 32
    }
    internal val handoffMutex = Mutex()
    internal val handoffsInFlight = mutableMapOf<String, CompletableDeferred<Track>>()
    internal val confirmedNoDispatchRequestIds = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > 32
    }
    internal val completedHandoffs = object : LinkedHashMap<String, Track>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?): Boolean = size > 32
    }
}

private val defaultExternalAudioImportProcessState = ExternalAudioImportProcessState()

internal enum class ExternalAudioHandoffDispatchOutcome {
    Dispatched,
    NotDispatched,
}

internal class ExternalAudioPlaybackNotStartedException : IOException(
    "The player confirmed that playback did not start; the external-open request remains retryable.",
)

internal class ExternalAudioDispatchAdmissionRejectedException : IOException(
    "Restore admission won before the external playback claim; the request remains retryable.",
)

internal class ExternalAudioImportedTrackMissingException : IOException(
    "The imported track was replaced by restore before the handoff claim; the request remains retryable.",
)

internal suspend fun dispatchExternalAudioPlayback(
    markDispatchStarted: suspend () -> Unit,
    dispatchToPlayer: () -> Boolean,
): ExternalAudioHandoffDispatchOutcome {
    markDispatchStarted()
    return if (dispatchToPlayer()) {
        ExternalAudioHandoffDispatchOutcome.Dispatched
    } else {
        ExternalAudioHandoffDispatchOutcome.NotDispatched
    }
}

internal suspend fun completeExternalAudioOpen(
    requestId: String,
    onHandled: (String) -> Unit,
    importAndPersist: suspend () -> Track,
    prepareHandoff: suspend (Track) -> Unit = {},
    processState: ExternalAudioImportProcessState = defaultExternalAudioImportProcessState,
    dispatchPermitProvider: (() -> ExternalAudioDispatchPermit?)? = null,
    markHandoffClaimed: suspend (Track) -> Track = {
        it.copy(externalImportHandoffClaimed = true, externalImportHandoffDispatchStarted = false)
    },
    markHandoffClaimedWithPermit: suspend (Track, ExternalAudioDispatchPermit?) -> Track = { track, _ ->
        markHandoffClaimed(track)
    },
    afterPersist: suspend (Track) -> Unit = {},
    markHandoffDispatchStarted: suspend (Track) -> Track = {
        it.copy(externalImportHandoffDispatchStarted = true)
    },
    markHandoffDispatchStartedWithPermit: suspend (Track, ExternalAudioDispatchPermit?) -> Track = { track, _ ->
        markHandoffDispatchStarted(track)
    },
    markHandoffNotDispatched: suspend (Track) -> Track = {
        it.copy(externalImportHandoffDispatchStarted = false)
    },
    markHandoffNotDispatchedWithPermit: suspend (Track, ExternalAudioDispatchPermit?) -> Track = { track, _ ->
        markHandoffNotDispatched(track)
    },
    dispatchPlayback: suspend (Track, suspend () -> Track) -> ExternalAudioHandoffDispatchOutcome = { _, markStarted ->
        afterPersist(markStarted())
        ExternalAudioHandoffDispatchOutcome.Dispatched
    },
    dispatchPlaybackWithPermit: suspend (
        Track,
        ExternalAudioDispatchPermit?,
        suspend () -> Track,
    ) -> ExternalAudioHandoffDispatchOutcome = { track, _, markStarted ->
        dispatchPlayback(track, markStarted)
    },
    markHandoffCompleted: suspend (Track) -> Track = {
        it.copy(
            externalImportHandoffClaimed = true,
            externalImportHandoffDispatchStarted = true,
            externalImportHandoffCompleted = true,
        )
    },
    markHandoffCompletedWithPermit: suspend (Track, ExternalAudioDispatchPermit?) -> Track = { track, _ ->
        markHandoffCompleted(track)
    },
    onHandoffCompleted: () -> Unit = {},
    onFailure: (Exception) -> Unit = {},
    reconcilePersistedImport: suspend (Track) -> Track? = { it },
    hasDurableDispatchOutcome: suspend (String) -> Boolean = { false },
): Track {
    val cachedImport = try {
        importOnce(processState, requestId, importAndPersist)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
        throw error
    }
    val reconciledImport = try {
        val persistedImport = reconcilePersistedImport(cachedImport)
        reconcileImportCache(processState, requestId, cachedImport, persistedImport)
        persistedImport ?: throw ExternalAudioImportedTrackMissingException()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
        throw error
    }

    val confirmedNoDispatch = processState.handoffMutex.withLock {
        processState.confirmedNoDispatchRequestIds.containsKey(requestId) &&
            !reconciledImport.externalImportHandoffCompleted
    }
    val imported = if (confirmedNoDispatch) {
        reconciledImport.copy(externalImportHandoffDispatchStarted = false)
    } else {
        reconciledImport
    }

    val dispatchOutcomeKnown = if (confirmedNoDispatch) {
        false
    } else {
        try {
            hasDurableDispatchOutcome(requestId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(error)
            throw error
        }
    }
    var sameProcessDispatchedTrack: Track? = null
    if (dispatchOutcomeKnown) {
        // The marker is persisted before the player returns; wait for an active same-request result before acknowledging.
        val inFlightHandoff = processState.handoffMutex.withLock {
            processState.handoffsInFlight[requestId]
        }
        if (inFlightHandoff != null) {
            try {
                inFlightHandoff.await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(error)
                throw error
            }
            val stillDurable = try {
                hasDurableDispatchOutcome(requestId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(error)
                throw error
            }
            if (!stillDurable) {
                val error = IOException("The request-level dispatch outcome changed before playback could be resolved.")
                onFailure(error)
                throw error
            }
        }
        sameProcessDispatchedTrack = processState.handoffMutex.withLock {
            processState.completedHandoffs[requestId]?.takeIf { track ->
                track.id == imported.id &&
                    track.externalImportHandoffClaimed &&
                    track.externalImportHandoffDispatchStarted == true
            }
        }
        if (sameProcessDispatchedTrack == null) {
            withContext(NonCancellable) { onHandled(requestId) }
            return imported
        }
        if (imported.externalImportHandoffCompleted) {
            withContext(NonCancellable) {
                onHandoffCompleted()
                onHandled(requestId)
            }
            return imported
        }
    }

    if (imported.externalImportHandoffCompleted) {
        withContext(NonCancellable) { onHandled(requestId) }
        return imported
    }

    val dispatchMayHaveStarted = imported.externalImportHandoffDispatchStarted == true ||
        (imported.externalImportHandoffClaimed && imported.externalImportHandoffDispatchStarted == null)
    val handoff = try {
        sameProcessDispatchedTrack?.let { ExternalAudioHandoffResult(it, null) } ?: if (dispatchMayHaveStarted) {
            findLocalHandoff(processState, requestId)?.let { ExternalAudioHandoffResult(it, null) } ?: run {
                // A cold process cannot know whether playback ran after dispatch intent. At-most-once wins: do not replay.
                withContext(NonCancellable) { onHandled(requestId) }
                return imported
            }
        } else {
            handoffOnce(
                processState = processState,
                requestId = requestId,
                track = imported,
                prepareHandoff = prepareHandoff,
                dispatchPermitProvider = dispatchPermitProvider,
                markHandoffClaimed = markHandoffClaimedWithPermit,
                markHandoffDispatchStarted = markHandoffDispatchStartedWithPermit,
                markHandoffNotDispatched = markHandoffNotDispatchedWithPermit,
                dispatchPlayback = dispatchPlaybackWithPermit,
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
        throw error
    }

    val dispatchPermit = handoff.dispatchPermit
    try {
        val completed = try {
            withContext(NonCancellable) { markHandoffCompletedWithPermit(handoff.track, dispatchPermit) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(error)
            throw error
        }
        if (!completed.externalImportHandoffCompleted) {
            val error = IOException("The external handoff completion marker was not persisted.")
            onFailure(error)
            throw error
        }
        withContext(NonCancellable) {
            onHandoffCompleted()
            processState.importMutex.withLock { processState.persistedImports[requestId] = completed }
            processState.handoffMutex.withLock {
                if (processState.completedHandoffs.containsKey(requestId)) processState.completedHandoffs[requestId] = completed
            }
            onHandled(requestId)
        }

        return completed
    } finally {
        dispatchPermit?.close()
    }
}

internal suspend fun completeExternalAudioOpenWhenAllowed(
    requestId: String,
    withLibraryAction: suspend (suspend () -> Track) -> Track?,
    onHandled: (String) -> Unit,
    importAndPersist: suspend () -> Track,
    prepareHandoff: suspend (Track) -> Unit = {},
    processState: ExternalAudioImportProcessState = defaultExternalAudioImportProcessState,
    dispatchPermitProvider: (() -> ExternalAudioDispatchPermit?)? = null,
    markHandoffClaimed: suspend (Track) -> Track = {
        it.copy(externalImportHandoffClaimed = true, externalImportHandoffDispatchStarted = false)
    },
    markHandoffClaimedWithPermit: suspend (Track, ExternalAudioDispatchPermit?) -> Track = { track, _ ->
        markHandoffClaimed(track)
    },
    afterPersist: suspend (Track) -> Unit = {},
    markHandoffDispatchStarted: suspend (Track) -> Track = {
        it.copy(externalImportHandoffDispatchStarted = true)
    },
    markHandoffDispatchStartedWithPermit: suspend (Track, ExternalAudioDispatchPermit?) -> Track = { track, _ ->
        markHandoffDispatchStarted(track)
    },
    markHandoffNotDispatched: suspend (Track) -> Track = {
        it.copy(externalImportHandoffDispatchStarted = false)
    },
    markHandoffNotDispatchedWithPermit: suspend (Track, ExternalAudioDispatchPermit?) -> Track = { track, _ ->
        markHandoffNotDispatched(track)
    },
    dispatchPlayback: suspend (Track, suspend () -> Track) -> ExternalAudioHandoffDispatchOutcome = { _, markStarted ->
        afterPersist(markStarted())
        ExternalAudioHandoffDispatchOutcome.Dispatched
    },
    dispatchPlaybackWithPermit: suspend (
        Track,
        ExternalAudioDispatchPermit?,
        suspend () -> Track,
    ) -> ExternalAudioHandoffDispatchOutcome = { track, _, markStarted ->
        dispatchPlayback(track, markStarted)
    },
    markHandoffCompleted: suspend (Track) -> Track = {
        it.copy(
            externalImportHandoffClaimed = true,
            externalImportHandoffDispatchStarted = true,
            externalImportHandoffCompleted = true,
        )
    },
    markHandoffCompletedWithPermit: suspend (Track, ExternalAudioDispatchPermit?) -> Track = { track, _ ->
        markHandoffCompleted(track)
    },
    onHandoffCompleted: () -> Unit = {},
    onFailure: (Exception) -> Unit = {},
    reconcilePersistedImport: suspend (Track) -> Track? = { it },
    hasDurableDispatchOutcome: suspend (String) -> Boolean = { false },
): Track? = withLibraryAction {
    completeExternalAudioOpen(
        requestId = requestId,
        onHandled = onHandled,
        importAndPersist = importAndPersist,
        reconcilePersistedImport = reconcilePersistedImport,
        hasDurableDispatchOutcome = hasDurableDispatchOutcome,
        prepareHandoff = prepareHandoff,
        processState = processState,
        dispatchPermitProvider = dispatchPermitProvider,
        markHandoffClaimed = markHandoffClaimed,
        markHandoffClaimedWithPermit = markHandoffClaimedWithPermit,
        afterPersist = afterPersist,
        markHandoffDispatchStarted = markHandoffDispatchStarted,
        markHandoffDispatchStartedWithPermit = markHandoffDispatchStartedWithPermit,
        markHandoffNotDispatched = markHandoffNotDispatched,
        markHandoffNotDispatchedWithPermit = markHandoffNotDispatchedWithPermit,
        dispatchPlayback = dispatchPlayback,
        dispatchPlaybackWithPermit = dispatchPlaybackWithPermit,
        markHandoffCompleted = markHandoffCompleted,
        markHandoffCompletedWithPermit = markHandoffCompletedWithPermit,
        onHandoffCompleted = onHandoffCompleted,
        onFailure = onFailure,
    )
}

private suspend fun reconcileImportCache(
    processState: ExternalAudioImportProcessState,
    requestId: String,
    cachedImport: Track,
    persistedImport: Track?,
) {
    processState.importMutex.withLock {
        if (processState.persistedImports[requestId] == cachedImport) {
            if (persistedImport == null) {
                processState.persistedImports.remove(requestId)
            } else {
                processState.persistedImports[requestId] = persistedImport
            }
        }
    }
}

private suspend fun importOnce(
    processState: ExternalAudioImportProcessState,
    requestId: String,
    importAndPersist: suspend () -> Track,
): Track {
    var owner = false
    var cached: Track? = null
    val flight = processState.importMutex.withLock {
        cached = processState.persistedImports[requestId]
        if (cached != null) {
            null
        } else {
            processState.importsInFlight[requestId] ?: CompletableDeferred<Track>().also {
                processState.importsInFlight[requestId] = it
                owner = true
            }
        }
    }
    cached?.let { return it }

    val sharedFlight = requireNotNull(flight)
    if (owner) {
        try {
            withContext(NonCancellable) {
                val imported = importAndPersist()
                processState.importMutex.withLock {
                    processState.persistedImports[requestId] = imported
                    processState.importsInFlight.remove(requestId)
                    sharedFlight.complete(imported)
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                processState.importMutex.withLock {
                    if (processState.importsInFlight[requestId] === sharedFlight) processState.importsInFlight.remove(requestId)
                    sharedFlight.completeExceptionally(error)
                }
            }
            throw error
        }
    }
    return sharedFlight.await()
}

private data class ExternalAudioHandoffResult(
    val track: Track,
    val dispatchPermit: ExternalAudioDispatchPermit?,
)

private suspend fun handoffOnce(
    requestId: String,
    track: Track,
    prepareHandoff: suspend (Track) -> Unit,
    processState: ExternalAudioImportProcessState,
    dispatchPermitProvider: (() -> ExternalAudioDispatchPermit?)?,
    markHandoffClaimed: suspend (Track, ExternalAudioDispatchPermit?) -> Track,
    markHandoffDispatchStarted: suspend (Track, ExternalAudioDispatchPermit?) -> Track,
    markHandoffNotDispatched: suspend (Track, ExternalAudioDispatchPermit?) -> Track,
    dispatchPlayback: suspend (
        Track,
        ExternalAudioDispatchPermit?,
        suspend () -> Track,
    ) -> ExternalAudioHandoffDispatchOutcome,
): ExternalAudioHandoffResult {
    while (true) {
        var owner = false
        var cached: Track? = null
        val flight = processState.handoffMutex.withLock {
            cached = processState.completedHandoffs[requestId]
            if (cached != null) {
                null
            } else {
                processState.handoffsInFlight[requestId] ?: CompletableDeferred<Track>().also {
                    processState.handoffsInFlight[requestId] = it
                    owner = true
                }
            }
        }
        cached?.let { return ExternalAudioHandoffResult(it, null) }

        val sharedFlight = requireNotNull(flight)
        if (owner) {
            var dispatchPermit: ExternalAudioDispatchPermit? = null
            try {
                prepareHandoff(track)
                dispatchPermit = dispatchPermitProvider?.invoke()
                if (dispatchPermitProvider != null && dispatchPermit == null) {
                    throw ExternalAudioDispatchAdmissionRejectedException()
                }
                val handedOff = withContext(NonCancellable) {
                    val claimed = if (track.externalImportHandoffClaimed) track else markHandoffClaimed(track, dispatchPermit)
                    if (!claimed.externalImportHandoffClaimed || claimed.externalImportHandoffDispatchStarted != false) {
                        throw IOException("A definitely-not-dispatched handoff claim was not persisted before playback.")
                    }
                    processState.importMutex.withLock { processState.persistedImports[requestId] = claimed }

                    var dispatchTrack = claimed
                    var dispatchMarkerPersisted = false
                    val markDispatchStarted: suspend () -> Track = {
                        if (dispatchMarkerPersisted) {
                            dispatchTrack
                        } else {
                            val started = markHandoffDispatchStarted(claimed, dispatchPermit)
                            if (started.id != claimed.id || started.externalImportHandoffDispatchStarted != true) {
                                throw IOException("The playback dispatch marker was not persisted before player access.")
                            }
                            dispatchTrack = started
                            dispatchMarkerPersisted = true
                            processState.importMutex.withLock { processState.persistedImports[requestId] = started }
                            processState.handoffMutex.withLock { processState.confirmedNoDispatchRequestIds.remove(requestId) }
                            started
                        }
                    }

                    when (dispatchPlayback(claimed, dispatchPermit, markDispatchStarted)) {
                        ExternalAudioHandoffDispatchOutcome.NotDispatched -> {
                            // The player explicitly confirmed no dispatch, so this process may safely retry even if rollback fails.
                            val knownNotDispatched = dispatchTrack.copy(externalImportHandoffDispatchStarted = false)
                            processState.handoffMutex.withLock {
                                processState.confirmedNoDispatchRequestIds[requestId] = Unit
                            }
                            processState.importMutex.withLock { processState.persistedImports[requestId] = knownNotDispatched }
                            val persistedNotDispatched = markHandoffNotDispatched(dispatchTrack, dispatchPermit)
                            if (persistedNotDispatched.id != claimed.id ||
                                !persistedNotDispatched.externalImportHandoffClaimed ||
                                persistedNotDispatched.externalImportHandoffDispatchStarted != false
                            ) {
                                throw IOException("The not-dispatched playback state could not be persisted for Retry.")
                            }
                            processState.importMutex.withLock { processState.persistedImports[requestId] = persistedNotDispatched }
                            processState.handoffMutex.withLock { processState.confirmedNoDispatchRequestIds.remove(requestId) }
                            throw ExternalAudioPlaybackNotStartedException()
                        }
                        ExternalAudioHandoffDispatchOutcome.Dispatched -> {
                            if (!dispatchMarkerPersisted) {
                                throw IOException("Playback was reported without persisting its dispatch marker first.")
                            }
                            processState.handoffMutex.withLock {
                                processState.completedHandoffs[requestId] = dispatchTrack
                                processState.handoffsInFlight.remove(requestId)
                                sharedFlight.complete(dispatchTrack)
                            }
                            dispatchTrack
                        }
                    }
                }
                return ExternalAudioHandoffResult(handedOff, dispatchPermit)
            } catch (error: Throwable) {
                dispatchPermit?.close()
                withContext(NonCancellable) {
                    if (error is ExternalAudioImportedTrackMissingException) {
                        processState.importMutex.withLock {
                            if (processState.persistedImports[requestId]?.id == track.id) {
                                processState.persistedImports.remove(requestId)
                            }
                        }
                    }
                    processState.handoffMutex.withLock {
                        if (processState.handoffsInFlight[requestId] === sharedFlight) processState.handoffsInFlight.remove(requestId)
                        sharedFlight.completeExceptionally(error)
                    }
                }
                throw error
            }
        }

        try {
            return ExternalAudioHandoffResult(sharedFlight.await(), null)
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            // An unclaimed flight may have been cancelled during preparation; retry with this live caller.
        }
    }
}

private suspend fun findLocalHandoff(processState: ExternalAudioImportProcessState, requestId: String): Track? {
    var cached: Track? = null
    val flight = processState.handoffMutex.withLock {
        cached = processState.completedHandoffs[requestId]
        if (cached == null) processState.handoffsInFlight[requestId] else null
    }
    cached?.let { return it }
    return flight?.await()
}
