package com.neiro.aftertone

import com.neiro.aftertone.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class ExternalAudioImportCompletionTest {
    @Test
    fun recreatedActivitySharesWholeHandoffAndAcknowledgesOnlyAfterItCompletes() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val importStarted = CompletableDeferred<Unit>()
        val finishImport = CompletableDeferred<Unit>()
        val handoffStarted = CompletableDeferred<Unit>()
        val finishHandoff = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var importCount = 0
        var handoffCount = 0

        val originalActivity = async(start = CoroutineStart.UNDISPATCHED) {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { events += "handled:original" },
                importAndPersist = {
                    importCount++
                    importStarted.complete(Unit)
                    finishImport.await()
                    testTrack("track-1")
                },
                afterPersist = { track ->
                    assertEquals("track-1", track.id)
                    handoffCount++
                    events += "handoff-started"
                    handoffStarted.complete(Unit)
                    finishHandoff.await()
                    events += "handoff-finished"
                },
            )
        }
        importStarted.await()

        val restoredActivity = async(start = CoroutineStart.UNDISPATCHED) {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { events += "handled:restored" },
                importAndPersist = {
                    importCount++
                    testTrack("duplicate-track")
                },
                afterPersist = { handoffCount++ },
            )
        }

        finishImport.complete(Unit)
        handoffStarted.await()
        yield()
        val acknowledgedBeforeHandoffFinished = events.any { it.startsWith("handled:") }
        val handoffCountWhileSuspended = handoffCount
        finishHandoff.complete(Unit)

        assertEquals("track-1", originalActivity.await().id)
        assertEquals("track-1", restoredActivity.await().id)
        assertFalse("The request must remain pending while library/playback handoff is suspended.", acknowledgedBeforeHandoffFinished)
        assertEquals("Activity recreation must wait for one shared handoff.", 1, handoffCountWhileSuspended)
        assertEquals(1, importCount)
        assertEquals(1, handoffCount)
        assertTrue(events.indexOf("handoff-finished") < events.indexOf("handled:original"))
        assertTrue(events.indexOf("handoff-finished") < events.indexOf("handled:restored"))
    }

    @Test
    fun cancellationAfterClaimKeepsSingleHandoffRunningForRestoredActivity() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val handoffStarted = CompletableDeferred<Unit>()
        val finishHandoff = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var importCount = 0
        var preparationCount = 0
        var claimCount = 0
        var handoffCount = 0

        val originalActivity = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                completeExternalAudioOpen(
                    requestId = requestId,
                    onHandled = { events += "handled:original" },
                    importAndPersist = {
                        importCount++
                        testTrack("track-2")
                    },
                    prepareHandoff = { preparationCount++ },
                    markHandoffClaimed = { track ->
                        claimCount++
                        track.copy(
                            externalImportRequestId = requestId,
                            externalImportHandoffClaimed = true,
                            externalImportHandoffDispatchStarted = false,
                        )
                    },
                    afterPersist = {
                        handoffCount++
                        handoffStarted.complete(Unit)
                        finishHandoff.await()
                        events += "handoff-finished:original"
                    },
                    markHandoffCompleted = { track -> track.copy(externalImportHandoffCompleted = true) },
                )
            } catch (_: CancellationException) {
                // Cancellation cannot interrupt a handoff after its durable at-most-once claim.
            }
        }
        handoffStarted.await()
        originalActivity.cancel()
        val acknowledgedBeforeHandoffFinished = events.any { it.startsWith("handled:") }

        val restoredActivity = async(start = CoroutineStart.UNDISPATCHED) {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { events += "handled:restored" },
                importAndPersist = {
                    importCount++
                    testTrack("duplicate-track")
                },
                prepareHandoff = { error("A restored caller must join the claimed handoff, not prepare it again.") },
                markHandoffClaimed = { error("A restored caller must not take a second claim.") },
                afterPersist = { handoffCount++; events += "handoff-finished:restored" },
                markHandoffCompleted = { track -> track.copy(externalImportHandoffCompleted = true) },
            )
        }
        yield()
        val handoffCountWhileSuspended = handoffCount
        finishHandoff.complete(Unit)
        originalActivity.join()

        assertEquals("track-2", restoredActivity.await().id)
        assertFalse("Cancellation must not acknowledge an unfinished handoff.", acknowledgedBeforeHandoffFinished)
        assertEquals(1, handoffCountWhileSuspended)
        assertEquals(1, importCount)
        assertEquals(1, preparationCount)
        assertEquals(1, claimCount)
        assertEquals("A claimed handoff continues once instead of replaying in the recreated Activity.", 1, handoffCount)
        assertTrue(events.contains("handoff-finished:original"))
        assertFalse(events.contains("handoff-finished:restored"))
        assertTrue(events.indexOf("handoff-finished:original") < events.indexOf("handled:restored"))
    }

    @Test
    fun preparationFailureBeforeClaimCanRetryWithoutAcknowledgingOrReimporting() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        var importCount = 0
        var preparationCount = 0
        var claimCount = 0
        var handoffCount = 0
        var acknowledgementCount = 0
        var failureCount = 0

        val failedAttempt = runCatching {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = {
                    importCount++
                    testTrack("track-3")
                },
                prepareHandoff = {
                    preparationCount++
                    throw IOException("Temporary library read failure")
                },
                markHandoffClaimed = { track ->
                    claimCount++
                    track.copy(externalImportRequestId = requestId, externalImportHandoffClaimed = true)
                },
                afterPersist = {},
                onFailure = { failureCount++ },
            )
        }

        assertTrue(failedAttempt.isFailure)
        assertEquals("A pre-claim preparation error must remain pending for retry.", 0, acknowledgementCount)
        assertEquals(1, failureCount)
        assertEquals(0, claimCount)

        val recoveredTrack = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = {
                importCount++
                testTrack("duplicate-track")
            },
            prepareHandoff = { preparationCount++ },
            markHandoffClaimed = { track ->
                claimCount++
                track.copy(
                    externalImportRequestId = requestId,
                    externalImportHandoffClaimed = true,
                    externalImportHandoffDispatchStarted = false,
                )
            },
            afterPersist = { handoffCount++ },
            markHandoffCompleted = { track -> track.copy(externalImportHandoffCompleted = true) },
        )

        assertEquals("track-3", recoveredTrack.id)
        assertEquals(1, importCount)
        assertEquals(2, preparationCount)
        assertEquals(1, claimCount)
        assertEquals(1, handoffCount)
        assertEquals(1, acknowledgementCount)
    }

    @Test
    fun legacyClaimedTrackWithoutDispatchMarkerRemainsAmbiguousAndDoesNotReplay() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val recoveredTrack = testTrack("track-claimed").copy(
            externalImportRequestId = requestId,
            externalImportHandoffClaimed = true,
        )
        var sideEffectCount = 0
        var acknowledgementCount = 0

        val returned = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { recoveredTrack },
            prepareHandoff = { sideEffectCount++ },
            markHandoffClaimed = { sideEffectCount++; it },
            afterPersist = { sideEffectCount++ },
            markHandoffCompleted = { sideEffectCount++; it },
            onHandoffCompleted = { sideEffectCount++ },
        )

        assertEquals(recoveredTrack, returned)
        assertEquals("A durable claim consumes the auto-play attempt even when completion was not committed.", 0, sideEffectCount)
        assertEquals(1, acknowledgementCount)
    }

    @Test
    fun failureAfterClaimButBeforeDispatchCanRetryWithoutReimporting() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        var persistedTrack = testTrack("track-pre-dispatch").copy(externalImportRequestId = requestId)
        var importCount = 0
        var claimCount = 0
        var dispatchCount = 0
        var completionCount = 0
        var acknowledgementCount = 0
        var failureCount = 0

        val firstAttempt = runCatching {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { importCount++; persistedTrack },
                markHandoffClaimed = { track ->
                    claimCount++
                    persistedTrack = track.copy(
                        externalImportRequestId = requestId,
                        externalImportHandoffClaimed = true,
                        externalImportHandoffDispatchStarted = false,
                    )
                    persistedTrack
                },
                dispatchPlayback = { _, _ ->
                    throw IOException("The library changed after the claim but before playback dispatch.")
                },
                markHandoffCompleted = { track ->
                    completionCount++
                    persistedTrack = track.copy(externalImportHandoffCompleted = true)
                    persistedTrack
                },
                onFailure = { failureCount++ },
            )
        }

        assertTrue(firstAttempt.isFailure)
        assertEquals(0, acknowledgementCount)
        assertEquals(0, completionCount)
        assertEquals(1, failureCount)
        assertFalse(requireNotNull(persistedTrack.externalImportHandoffDispatchStarted))

        val recovered = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { importCount++; persistedTrack },
            markHandoffClaimed = { error("A persisted claim must be reused on Retry.") },
            dispatchPlayback = { _, markDispatchStarted ->
                dispatchCount++
                persistedTrack = markDispatchStarted()
                assertTrue(persistedTrack.externalImportHandoffDispatchStarted == true)
                ExternalAudioHandoffDispatchOutcome.Dispatched
            },
            markHandoffCompleted = { track ->
                completionCount++
                persistedTrack = track.copy(externalImportHandoffCompleted = true)
                persistedTrack
            },
        )

        assertEquals(1, importCount)
        assertEquals(1, claimCount)
        assertEquals(1, dispatchCount)
        assertEquals(1, completionCount)
        assertEquals(1, acknowledgementCount)
        assertTrue(recovered.externalImportHandoffCompleted)
    }

    @Test
    fun confirmedNotDispatchedPlayerResultStaysPendingAndRetryCanStartPlayback() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        var persistedTrack = testTrack("track-player-not-started").copy(externalImportRequestId = requestId)
        var dispatchAttempts = 0
        var successfulDispatches = 0
        var completionCount = 0
        var acknowledgementCount = 0
        var failureCount = 0

        val firstAttempt = runCatching {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { persistedTrack },
                markHandoffClaimed = { track ->
                    persistedTrack = track.copy(
                        externalImportRequestId = requestId,
                        externalImportHandoffClaimed = true,
                        externalImportHandoffDispatchStarted = false,
                    )
                    persistedTrack
                },
                markHandoffDispatchStarted = { track ->
                    persistedTrack = track.copy(externalImportHandoffDispatchStarted = true)
                    persistedTrack
                },
                markHandoffNotDispatched = { track ->
                    persistedTrack = track.copy(externalImportHandoffDispatchStarted = false)
                    persistedTrack
                },
                dispatchPlayback = { _, markDispatchStarted ->
                    dispatchAttempts++
                    persistedTrack = markDispatchStarted()
                    if (dispatchAttempts == 1) {
                        ExternalAudioHandoffDispatchOutcome.NotDispatched
                    } else {
                        successfulDispatches++
                        ExternalAudioHandoffDispatchOutcome.Dispatched
                    }
                },
                markHandoffCompleted = { track ->
                    completionCount++
                    persistedTrack = track.copy(externalImportHandoffCompleted = true)
                    persistedTrack
                },
                onFailure = { failureCount++ },
            )
        }

        assertTrue(firstAttempt.isFailure)
        assertFalse(requireNotNull(persistedTrack.externalImportHandoffDispatchStarted))
        assertEquals(0, completionCount)
        assertEquals(0, acknowledgementCount)
        assertEquals(1, failureCount)

        val recovered = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { persistedTrack },
            markHandoffClaimed = { error("The known-not-dispatched claim must not be replaced.") },
            markHandoffDispatchStarted = { track ->
                persistedTrack = track.copy(externalImportHandoffDispatchStarted = true)
                persistedTrack
            },
            markHandoffNotDispatched = { track ->
                persistedTrack = track.copy(externalImportHandoffDispatchStarted = false)
                persistedTrack
            },
            dispatchPlayback = { _, markDispatchStarted ->
                dispatchAttempts++
                persistedTrack = markDispatchStarted()
                successfulDispatches++
                ExternalAudioHandoffDispatchOutcome.Dispatched
            },
            markHandoffCompleted = { track ->
                completionCount++
                persistedTrack = track.copy(externalImportHandoffCompleted = true)
                persistedTrack
            },
        )

        assertEquals(2, dispatchAttempts)
        assertEquals(1, successfulDispatches)
        assertEquals(1, completionCount)
        assertEquals(1, acknowledgementCount)
        assertTrue(recovered.externalImportHandoffCompleted)
    }

    @Test
    fun failedNoDispatchRollbackKeepsTheSameProcessRequestRetryable() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val processState = ExternalAudioImportProcessState()
        var persistedTrack = testTrack("track-failed-no-dispatch-rollback").copy(
            externalImportRequestId = requestId,
            externalImportHandoffClaimed = true,
            externalImportHandoffDispatchStarted = false,
        )
        var durableDispatchOutcome = false
        var rollbackAttempts = 0
        var playerCalls = 0
        var completionCount = 0
        var acknowledgementCount = 0

        suspend fun attempt(): Track = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { persistedTrack },
            processState = processState,
            reconcilePersistedImport = { persistedTrack },
            hasDurableDispatchOutcome = { durableDispatchOutcome },
            markHandoffClaimed = { error("The already-persisted claim must not be replaced.") },
            markHandoffDispatchStarted = { track ->
                durableDispatchOutcome = true
                persistedTrack = track.copy(externalImportHandoffDispatchStarted = true)
                persistedTrack
            },
            markHandoffNotDispatched = { track ->
                rollbackAttempts++
                if (rollbackAttempts == 1) throw IOException("The rollback write failed after player denial.")
                durableDispatchOutcome = false
                persistedTrack = track.copy(externalImportHandoffDispatchStarted = false)
                persistedTrack
            },
            dispatchPlayback = { _, markDispatchStarted ->
                playerCalls++
                persistedTrack = markDispatchStarted()
                if (playerCalls == 1) {
                    ExternalAudioHandoffDispatchOutcome.NotDispatched
                } else {
                    ExternalAudioHandoffDispatchOutcome.Dispatched
                }
            },
            markHandoffCompleted = { track ->
                completionCount++
                persistedTrack = track.copy(externalImportHandoffCompleted = true)
                persistedTrack
            },
        )

        val firstAttempt = runCatching { attempt() }
        assertTrue("The failed rollback must leave the request unacknowledged for Retry.", firstAttempt.isFailure)
        assertTrue("The persisted dispatch marker is still ambiguous on disk.", durableDispatchOutcome)
        assertEquals(1, playerCalls)
        assertEquals(1, rollbackAttempts)
        assertEquals(0, acknowledgementCount)

        val retried = attempt()

        assertEquals("Retry must call the player again instead of consuming the stale durable marker.", 2, playerCalls)
        assertEquals(1, rollbackAttempts)
        assertEquals(1, completionCount)
        assertEquals(1, acknowledgementCount)
        assertTrue(retried.externalImportHandoffCompleted)
    }

    @Test
    fun restoreAdmissionAfterDurableDispatchMarkerAllowsRollbackAndColdRestartRetry() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val gate = com.neiro.aftertone.storage.RestoreActionGate().apply { unblock() }
        val processState = ExternalAudioImportProcessState()
        val markerPersisted = CompletableDeferred<Unit>()
        val denyPlayerCall = CompletableDeferred<Unit>()
        var persistedTrack = testTrack("track-restore-dispatch-race").copy(externalImportRequestId = requestId)
        var claimCount = 0
        var rollbackAttempts = 0
        var playerCalls = 0
        var dispatchAttempts = 0
        var successfulDispatches = 0
        var acknowledgementCount = 0

        val attempt = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                completeExternalAudioOpenWhenAllowed(
                    requestId = requestId,
                    withLibraryAction = { action -> gate.withLibraryAction(action) },
                    processState = processState,
                    dispatchPermitProvider = gate::tryAcquireExternalAudioDispatchPermit,
                    onHandled = { acknowledgementCount++ },
                    importAndPersist = { persistedTrack },
                    markHandoffClaimedWithPermit = { track, permit ->
                        claimCount++
                        val claimed = track.copy(
                            externalImportRequestId = requestId,
                            externalImportHandoffClaimed = true,
                            externalImportHandoffDispatchStarted = false,
                        )
                        if (!gate.runIfAllowed(requireNotNull(permit)) { persistedTrack = claimed }) {
                            throw IOException("The handoff claim was denied by restore admission.")
                        }
                        claimed
                    },
                    markHandoffDispatchStartedWithPermit = { track, permit ->
                        val started = track.copy(externalImportHandoffDispatchStarted = true)
                        if (!gate.runIfAllowed(requireNotNull(permit)) { persistedTrack = started }) {
                            throw IOException("The dispatch marker was denied by restore admission.")
                        }
                        started
                    },
                    markHandoffNotDispatchedWithPermit = { track, permit ->
                        rollbackAttempts++
                        val reset = track.copy(externalImportHandoffDispatchStarted = false)
                        if (!gate.runIfAllowed(requireNotNull(permit)) { persistedTrack = reset }) {
                            throw IOException("The no-dispatch rollback was denied by restore admission.")
                        }
                        reset
                    },
                    dispatchPlaybackWithPermit = { _, _, markDispatchStarted ->
                        dispatchAttempts++
                        dispatchExternalAudioPlayback(
                            markDispatchStarted = {
                                markDispatchStarted()
                                markerPersisted.complete(Unit)
                                denyPlayerCall.await()
                            },
                            dispatchToPlayer = {
                                playerCalls++
                                false
                            },
                        )
                    },
                )
            }
        }

        markerPersisted.await()
        val restore = gate.beginRestoreOperation()
        denyPlayerCall.complete(Unit)

        assertTrue(attempt.await().isFailure)
        assertEquals(1, claimCount)
        assertEquals(1, playerCalls)
        assertEquals(1, rollbackAttempts)
        assertEquals(
            "A confirmed player denial must durably clear the dispatch marker despite restore admission.",
            false,
            persistedTrack.externalImportHandoffDispatchStarted,
        )
        assertEquals(0, acknowledgementCount)
        restore.finish()

        val coldRetry = completeExternalAudioOpenWhenAllowed(
            requestId = requestId,
            withLibraryAction = { action -> gate.withLibraryAction(action) },
            processState = ExternalAudioImportProcessState(),
            dispatchPermitProvider = gate::tryAcquireExternalAudioDispatchPermit,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { persistedTrack },
            markHandoffClaimedWithPermit = { _, _ -> error("A persisted claim must survive a confirmed no-dispatch rollback.") },
            markHandoffDispatchStartedWithPermit = { track, permit ->
                val started = track.copy(externalImportHandoffDispatchStarted = true)
                if (!gate.runIfAllowed(requireNotNull(permit)) { persistedTrack = started }) {
                    throw IOException("The cold-retry dispatch marker was denied.")
                }
                started
            },
            markHandoffNotDispatchedWithPermit = { track, permit ->
                val reset = track.copy(externalImportHandoffDispatchStarted = false)
                if (!gate.runIfAllowed(requireNotNull(permit)) { persistedTrack = reset }) {
                    throw IOException("The cold-retry rollback was denied.")
                }
                reset
            },
            dispatchPlaybackWithPermit = { _, _, markDispatchStarted ->
                dispatchAttempts++
                persistedTrack = markDispatchStarted()
                successfulDispatches++
                ExternalAudioHandoffDispatchOutcome.Dispatched
            },
            markHandoffCompletedWithPermit = { track, _ ->
                persistedTrack = track.copy(externalImportHandoffCompleted = true)
                persistedTrack
            },
        )

        assertTrue(coldRetry?.externalImportHandoffCompleted == true)
        assertEquals(2, dispatchAttempts)
        assertEquals(1, successfulDispatches)
        assertEquals(1, acknowledgementCount)
    }

    @Test
    fun restoreWinningBeforeClaimSkipsClaimCallbackAndTheSameRequestCanRetry() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val gate = com.neiro.aftertone.storage.RestoreActionGate().apply { unblock() }
        val preparationStarted = CompletableDeferred<Unit>()
        val finishPreparation = CompletableDeferred<Unit>()
        var persistedTrack = testTrack("track-restore-claim-race").copy(externalImportRequestId = requestId)
        var claimCount = 0
        var dispatchCount = 0
        var acknowledgementCount = 0

        val firstAttempt = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                completeExternalAudioOpenWhenAllowed(
                    requestId = requestId,
                    withLibraryAction = { action -> gate.withLibraryAction(action) },
                    dispatchPermitProvider = gate::tryAcquireExternalAudioDispatchPermit,
                    onHandled = { acknowledgementCount++ },
                    importAndPersist = { persistedTrack },
                    prepareHandoff = {
                        preparationStarted.complete(Unit)
                        finishPreparation.await()
                    },
                    markHandoffClaimed = { track ->
                        claimCount++
                        val claimed = track.copy(
                            externalImportRequestId = requestId,
                            externalImportHandoffClaimed = true,
                            externalImportHandoffDispatchStarted = false,
                        )
                        if (!gate.runIfAllowed { persistedTrack = claimed }) {
                            throw IOException("The handoff claim was denied by restore admission.")
                        }
                        claimed
                    },
                    markHandoffDispatchStarted = { track ->
                        val started = track.copy(externalImportHandoffDispatchStarted = true)
                        if (!gate.runIfAllowed { persistedTrack = started }) {
                            throw IOException("The dispatch marker was denied by restore admission.")
                        }
                        started
                    },
                    dispatchPlayback = { _, markDispatchStarted ->
                        dispatchCount++
                        persistedTrack = markDispatchStarted()
                        ExternalAudioHandoffDispatchOutcome.Dispatched
                    },
                    markHandoffCompleted = { track ->
                        persistedTrack = track.copy(externalImportHandoffCompleted = true)
                        persistedTrack
                    },
                )
            }
        }

        preparationStarted.await()
        val restore = gate.beginRestoreOperation()
        finishPreparation.complete(Unit)

        assertTrue(firstAttempt.await().isFailure)
        assertEquals("Restore admission must win before the durable claim callback.", 0, claimCount)
        assertFalse(persistedTrack.externalImportHandoffClaimed)
        assertEquals(0, dispatchCount)
        assertEquals(0, acknowledgementCount)
        restore.finish()

        val retried = completeExternalAudioOpenWhenAllowed(
            requestId = requestId,
            withLibraryAction = { action -> gate.withLibraryAction(action) },
            dispatchPermitProvider = gate::tryAcquireExternalAudioDispatchPermit,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { error("Retry must reuse the persisted import.") },
            markHandoffClaimed = { track ->
                claimCount++
                val claimed = track.copy(
                    externalImportRequestId = requestId,
                    externalImportHandoffClaimed = true,
                    externalImportHandoffDispatchStarted = false,
                )
                if (!gate.runIfAllowed { persistedTrack = claimed }) {
                    throw IOException("The retried handoff claim was denied.")
                }
                claimed
            },
            markHandoffDispatchStarted = { track ->
                val started = track.copy(externalImportHandoffDispatchStarted = true)
                if (!gate.runIfAllowed { persistedTrack = started }) {
                    throw IOException("The retried dispatch marker was denied.")
                }
                started
            },
            dispatchPlayback = { _, markDispatchStarted ->
                dispatchCount++
                persistedTrack = markDispatchStarted()
                ExternalAudioHandoffDispatchOutcome.Dispatched
            },
            markHandoffCompleted = { track ->
                persistedTrack = track.copy(externalImportHandoffCompleted = true)
                persistedTrack
            },
        )

        assertTrue(retried?.externalImportHandoffCompleted == true)
        assertEquals(1, claimCount)
        assertEquals(1, dispatchCount)
        assertEquals(1, acknowledgementCount)
    }

    @Test
    fun missingImportedTrackInvalidatesProcessCacheSoRestoreRetryCanReimport() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val processState = ExternalAudioImportProcessState()
        val removedTrack = testTrack("track-cache-before-restore").copy(externalImportRequestId = requestId)
        val restoredTrack = testTrack("track-cache-after-restore").copy(externalImportRequestId = requestId)
        var persistedTrack = removedTrack
        var importCount = 0
        var claimCount = 0
        var dispatchCount = 0
        var acknowledgementCount = 0

        val firstAttempt = runCatching {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { importCount++; removedTrack },
                processState = processState,
                prepareHandoff = { throw ExternalAudioImportedTrackMissingException() },
                markHandoffClaimed = {
                    claimCount++
                    error("A missing track must fail before its claim callback.")
                },
            )
        }

        assertTrue(firstAttempt.isFailure)
        assertEquals(1, importCount)
        assertEquals(0, claimCount)
        assertEquals(0, acknowledgementCount)

        val retried = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { importCount++; restoredTrack },
            processState = processState,
            prepareHandoff = { assertEquals(restoredTrack.id, it.id) },
            markHandoffClaimed = { track ->
                claimCount++
                track.copy(
                    externalImportRequestId = requestId,
                    externalImportHandoffClaimed = true,
                    externalImportHandoffDispatchStarted = false,
                )
            },
            markHandoffDispatchStarted = { track ->
                persistedTrack = track.copy(externalImportHandoffDispatchStarted = true)
                persistedTrack
            },
            dispatchPlayback = { _, markDispatchStarted ->
                dispatchCount++
                persistedTrack = markDispatchStarted()
                ExternalAudioHandoffDispatchOutcome.Dispatched
            },
            markHandoffCompleted = { track -> track.copy(externalImportHandoffCompleted = true) },
        )

        assertEquals(restoredTrack.id, retried.id)
        assertEquals(2, importCount)
        assertEquals(1, claimCount)
        assertEquals(1, dispatchCount)
        assertEquals(1, acknowledgementCount)
    }

    @Test
    fun restoredStartedMarkerOverridesStaleRetryableCacheForSameRequestAndTrack() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val unrelatedRequestId = UUID.randomUUID().toString()
        val processState = ExternalAudioImportProcessState()
        var persistedTrack = testTrack("track-preserved-by-restore").copy(externalImportRequestId = requestId)
        var dispatchCount = 0
        var claimCount = 0
        var acknowledgementCount = 0

        val firstAttempt = runCatching {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { persistedTrack },
                processState = processState,
                markHandoffClaimed = { track ->
                    claimCount++
                    track.copy(
                        externalImportRequestId = requestId,
                        externalImportHandoffClaimed = true,
                        externalImportHandoffDispatchStarted = false,
                    )
                },
                markHandoffDispatchStarted = { track ->
                    persistedTrack = track.copy(externalImportHandoffDispatchStarted = true)
                    persistedTrack
                },
                markHandoffNotDispatched = { track ->
                    persistedTrack = track.copy(externalImportHandoffDispatchStarted = false)
                    persistedTrack
                },
                dispatchPlayback = { _, markDispatchStarted ->
                    dispatchCount++
                    persistedTrack = markDispatchStarted()
                    ExternalAudioHandoffDispatchOutcome.NotDispatched
                },
            )
        }

        assertTrue(firstAttempt.isFailure)
        assertEquals(false, persistedTrack.externalImportHandoffDispatchStarted)
        assertEquals(false, processState.persistedImports[requestId]?.externalImportHandoffDispatchStarted)

        val restoredTrack = persistedTrack.copy(externalImportHandoffDispatchStarted = true)
        assertEquals(persistedTrack.id, restoredTrack.id)
        assertEquals(requestId, restoredTrack.externalImportRequestId)
        persistedTrack = restoredTrack
        val unrelatedTrack = testTrack("unrelated-cache-entry").copy(externalImportRequestId = unrelatedRequestId)
        processState.persistedImports[unrelatedRequestId] = unrelatedTrack

        val recovered = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { error("Reconciliation should use the process cache and current repository value.") },
            processState = processState,
            reconcilePersistedImport = { cached ->
                assertEquals("The same request/track entry should be reconciled.", persistedTrack.id, cached.id)
                assertEquals(false, cached.externalImportHandoffDispatchStarted)
                restoredTrack
            },
            markHandoffClaimed = { claimCount++; error("An ambiguous restored marker must not be claimed again.") },
            dispatchPlayback = { _, _ -> dispatchCount++; error("An ambiguous restored marker must never replay playback.") },
            markHandoffCompleted = { error("An ambiguous restored marker must not be marked completed.") },
        )

        assertEquals(restoredTrack, recovered)
        assertEquals(1, dispatchCount)
        assertEquals(1, claimCount)
        assertEquals(1, acknowledgementCount)
        assertEquals(true, processState.persistedImports[requestId]?.externalImportHandoffDispatchStarted)
        assertEquals("Reconciliation must not invalidate another request's cache entry.", unrelatedTrack, processState.persistedImports[unrelatedRequestId])
    }

    @Test
    fun exceptionAfterDispatchMayHaveStartedDoesNotReplayOnRetry() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val firstProcessState = ExternalAudioImportProcessState()
        var persistedTrack = testTrack("track-ambiguous-dispatch").copy(externalImportRequestId = requestId)
        var dispatchCount = 0
        var acknowledgementCount = 0
        var completionCount = 0

        val firstAttempt = runCatching {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { persistedTrack },
                processState = firstProcessState,
                markHandoffClaimed = { track ->
                    persistedTrack = track.copy(
                        externalImportRequestId = requestId,
                        externalImportHandoffClaimed = true,
                        externalImportHandoffDispatchStarted = false,
                    )
                    persistedTrack
                },
                markHandoffDispatchStarted = { track ->
                    persistedTrack = track.copy(externalImportHandoffDispatchStarted = true)
                    persistedTrack
                },
                dispatchPlayback = { _, markDispatchStarted ->
                    dispatchCount++
                    persistedTrack = markDispatchStarted()
                    throw IOException("The player call failed after dispatch became ambiguous.")
                },
                markHandoffCompleted = { track ->
                    completionCount++
                    track.copy(externalImportHandoffCompleted = true)
                },
            )
        }

        assertTrue(firstAttempt.isFailure)
        assertTrue(persistedTrack.externalImportHandoffDispatchStarted == true)
        assertEquals(0, acknowledgementCount)
        assertEquals(0, completionCount)

        completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { persistedTrack },
            processState = ExternalAudioImportProcessState(),
            dispatchPlayback = { _, _ -> error("An ambiguous dispatch must never be replayed.") },
            markHandoffCompleted = { error("An ambiguous dispatch must not be marked completed.") },
        )

        assertEquals(1, dispatchCount)
        assertEquals(1, acknowledgementCount)
        assertEquals(0, completionCount)
    }

    @Test
    fun falsePlayerResultIsReportedAsNotDispatchedAfterTheMarker() = runBlocking {
        val events = mutableListOf<String>()

        val result = dispatchExternalAudioPlayback(
            markDispatchStarted = { events += "marker" },
            dispatchToPlayer = { events += "player"; false },
        )

        assertEquals(ExternalAudioHandoffDispatchOutcome.NotDispatched, result)
        assertEquals(listOf("marker", "player"), events)
    }

    @Test
    fun persistedCompletedHandoffAcknowledgesWithoutRepeatingSideEffects() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val completedTrack = testTrack("track-4").copy(
            externalImportRequestId = requestId,
            externalImportHandoffCompleted = true,
        )
        var importCount = 0
        var handoffCount = 0
        var notificationCount = 0
        var acknowledgementCount = 0

        val recovered = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { importCount++; completedTrack },
            afterPersist = { handoffCount++ },
            onHandoffCompleted = { notificationCount++ },
        )

        assertEquals(completedTrack, recovered)
        assertEquals(1, importCount)
        assertEquals(0, handoffCount)
        assertEquals(0, notificationCount)
        assertEquals(1, acknowledgementCount)
    }

    @Test
    fun successfulHandoffPersistsCompletionBeforeAcknowledgingRequest() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val events = mutableListOf<String>()
        val result = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { events += "acknowledged" },
            importAndPersist = { events += "imported"; testTrack("track-5") },
            prepareHandoff = { events += "prepared" },
            markHandoffClaimed = { track ->
                events += "claimed"
                track.copy(
                    externalImportRequestId = requestId,
                    externalImportHandoffClaimed = true,
                    externalImportHandoffDispatchStarted = false,
                )
            },
            afterPersist = { track ->
                assertTrue(track.externalImportHandoffClaimed)
                events += "handoff"
            },
            markHandoffCompleted = { track ->
                events += "durable-handoff-complete"
                track.copy(
                    externalImportRequestId = requestId,
                    externalImportHandoffClaimed = true,
                    externalImportHandoffCompleted = true,
                )
            },
            onHandoffCompleted = { events += "notification-enqueued" },
        )

        assertEquals(
            listOf("imported", "prepared", "claimed", "handoff", "durable-handoff-complete", "notification-enqueued", "acknowledged"),
            events,
        )
        assertTrue(result.externalImportHandoffCompleted)
    }

    @Test
    fun failedCompletionMarkerAfterPlaybackRetriesPersistenceWithoutReplayingSideEffects() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val processState = ExternalAudioImportProcessState()
        var durableDispatchOutcome = false
        var importCount = 0
        var preparationCount = 0
        var claimCount = 0
        var playbackCount = 0
        var completionWriteCount = 0
        var acknowledgementCount = 0
        var notificationCount = 0
        var failureCount = 0

        val firstAttempt = runCatching {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { importCount++; testTrack("track-marker-retry") },
                processState = processState,
                prepareHandoff = { preparationCount++ },
                markHandoffClaimed = { track ->
                    claimCount++
                    track.copy(
                        externalImportRequestId = requestId,
                        externalImportHandoffClaimed = true,
                        externalImportHandoffDispatchStarted = false,
                    )
                },
                markHandoffDispatchStarted = { track ->
                    durableDispatchOutcome = true
                    track.copy(externalImportHandoffDispatchStarted = true)
                },
                afterPersist = { track ->
                    assertTrue(track.externalImportHandoffClaimed)
                    playbackCount++
                },
                markHandoffCompleted = { track ->
                    completionWriteCount++
                    if (completionWriteCount == 1) throw IOException("Completion marker write failed after playback.")
                    track.copy(externalImportHandoffCompleted = true)
                },
                onHandoffCompleted = { notificationCount++ },
                onFailure = { failureCount++ },
                hasDurableDispatchOutcome = { durableDispatchOutcome },
            )
        }

        assertTrue(firstAttempt.isFailure)
        assertEquals(0, acknowledgementCount)
        assertEquals(1, playbackCount)
        assertEquals(1, failureCount)
        assertEquals(0, notificationCount)

        val recovered = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { importCount++; testTrack("duplicate-track") },
            processState = processState,
            prepareHandoff = { preparationCount++ },
            markHandoffClaimed = { claimCount++; it },
            afterPersist = { playbackCount++ },
            markHandoffCompleted = { track ->
                completionWriteCount++
                track.copy(externalImportHandoffCompleted = true)
            },
            onHandoffCompleted = { notificationCount++ },
            hasDurableDispatchOutcome = { durableDispatchOutcome },
        )

        assertTrue(recovered.externalImportHandoffCompleted)
        assertEquals(1, importCount)
        assertEquals(1, preparationCount)
        assertEquals(1, claimCount)
        assertEquals("A failed completion write must not replay already-dispatched playback.", 1, playbackCount)
        assertEquals(2, completionWriteCount)
        assertEquals(1, notificationCount)
        assertEquals(1, acknowledgementCount)
    }

    @Test
    fun sameTrackCompletionRetryEnqueuesStableSuccessMessageWhenAppMessageWasLost() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val processState = ExternalAudioImportProcessState()
        val notificationQueue = ExternalAudioOpenNotificationQueue()
        val originalTrack = testTrack("track-notification-retry").copy(externalImportRequestId = requestId)
        var durableDispatchOutcome = false
        var successNotificationMessage: String? = null
        var playerCalls = 0
        var completionAttempts = 0
        var acknowledgementCount = 0

        suspend fun attempt(): Track = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { originalTrack },
            processState = processState,
            markHandoffClaimed = { track ->
                track.copy(
                    externalImportRequestId = requestId,
                    externalImportHandoffClaimed = true,
                    externalImportHandoffDispatchStarted = false,
                )
            },
            markHandoffDispatchStarted = { track ->
                durableDispatchOutcome = true
                track.copy(externalImportHandoffDispatchStarted = true)
            },
            dispatchPlayback = { _, markDispatchStarted ->
                markDispatchStarted()
                playerCalls++
                successNotificationMessage = EXTERNAL_AUDIO_SUCCESS_NOTIFICATION_MESSAGE
                ExternalAudioHandoffDispatchOutcome.Dispatched
            },
            markHandoffCompleted = { track ->
                completionAttempts++
                if (completionAttempts == 1) throw IOException("Completion marker write failed after playback.")
                track.copy(externalImportHandoffCompleted = true)
            },
            hasDurableDispatchOutcome = { durableDispatchOutcome },
            onHandoffCompleted = {
                successNotificationMessage = enqueueExternalAudioSuccessNotification(
                    successNotificationMessage,
                ) { message -> notificationQueue.enqueue(requestId, message) }
            },
        )

        val firstAttempt = runCatching { attempt() }
        assertTrue(firstAttempt.isFailure)
        assertEquals(1, playerCalls)
        assertEquals(1, completionAttempts)
        assertEquals(0, notificationQueue.pending.value.size)
        assertEquals(0, acknowledgementCount)

        // A new LaunchedEffect starts with no message; the durable retry will not call dispatchPlayback again.
        successNotificationMessage = null
        val recovered = attempt()

        assertEquals(originalTrack.id, recovered.id)
        assertEquals(1, playerCalls)
        assertEquals(2, completionAttempts)
        assertEquals(1, acknowledgementCount)
        assertEquals(1, notificationQueue.pending.value.size)
        assertEquals("Трек добавлен в медиатеку и воспроизводится.", notificationQueue.pending.value.single().message)
        assertEquals(requestId, notificationQueue.pending.value.single().requestId)
    }

    @Test
    fun durableDispatchOutcomeResolvesRetryAfterRestoreReimportsWithNewTrackId() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val processState = ExternalAudioImportProcessState()
        val oldTrack = testTrack("track-before-restore").copy(externalImportRequestId = requestId)
        val reimportedTrack = testTrack("track-after-restore-new-id").copy(externalImportRequestId = requestId)
        var durableDispatchOutcome = false
        var importCount = 0
        var playbackCount = 0
        var completionWriteCount = 0
        var acknowledgementCount = 0
        var notificationCount = 0

        val firstAttempt = runCatching {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { importCount++; oldTrack },
                processState = processState,
                markHandoffClaimed = { track ->
                    track.copy(
                        externalImportRequestId = requestId,
                        externalImportHandoffClaimed = true,
                        externalImportHandoffDispatchStarted = false,
                    )
                },
                markHandoffDispatchStarted = { track ->
                    durableDispatchOutcome = true
                    track.copy(externalImportHandoffDispatchStarted = true)
                },
                dispatchPlayback = { _, markDispatchStarted ->
                    playbackCount++
                    markDispatchStarted()
                    ExternalAudioHandoffDispatchOutcome.Dispatched
                },
                markHandoffCompleted = {
                    completionWriteCount++
                    throw IOException("Completion marker failed after the player accepted the Track.")
                },
                hasDurableDispatchOutcome = { durableDispatchOutcome },
                onHandoffCompleted = { notificationCount++ },
            )
        }

        assertTrue(firstAttempt.isFailure)
        assertTrue(durableDispatchOutcome)
        assertEquals(1, playbackCount)
        assertEquals(0, acknowledgementCount)
        assertEquals(1, completionWriteCount)

        val restoreRemovedTrackRetry = runCatching {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { importCount++; error("The restore reconciliation should invalidate the removed Track cache first.") },
                processState = processState,
                reconcilePersistedImport = { null },
                hasDurableDispatchOutcome = { durableDispatchOutcome },
            )
        }

        assertTrue(restoreRemovedTrackRetry.isFailure)
        assertEquals(0, acknowledgementCount)

        val recovered = completeExternalAudioOpen(
            requestId = requestId,
            onHandled = { acknowledgementCount++ },
            importAndPersist = { importCount++; reimportedTrack },
            processState = processState,
            reconcilePersistedImport = { reimportedTrack },
            hasDurableDispatchOutcome = { durableDispatchOutcome },
            dispatchPlayback = { _, _ -> playbackCount++; error("A durable request-level dispatch outcome must never replay.") },
            markHandoffCompleted = {
                completionWriteCount++
                error("Recovery must not depend on completing the deleted Track ID.")
            },
            onHandoffCompleted = { notificationCount++ },
        )

        assertFalse(oldTrack.id == reimportedTrack.id)
        assertEquals(reimportedTrack.id, recovered.id)
        assertEquals(2, importCount)
        assertEquals(1, playbackCount)
        assertEquals(1, completionWriteCount)
        assertEquals(1, acknowledgementCount)
        assertEquals("A reimported Track with a different ID must not inherit a success notification.", 0, notificationCount)
    }

    @Test
    fun durableOutcomeWaitsForInflightPlayerDenialBeforeAcknowledging() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val processState = ExternalAudioImportProcessState()
        val dispatchMarkerPersisted = CompletableDeferred<Unit>()
        val releasePlayer = CompletableDeferred<Unit>()
        var durableDispatchOutcome = false
        var persistedTrack = testTrack("track-inflight-no-dispatch").copy(externalImportRequestId = requestId)
        var acknowledgementCount = 0

        val firstAttempt = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                completeExternalAudioOpen(
                    requestId = requestId,
                    onHandled = { acknowledgementCount++ },
                    importAndPersist = { persistedTrack },
                    processState = processState,
                    markHandoffClaimed = { track ->
                        track.copy(
                            externalImportRequestId = requestId,
                            externalImportHandoffClaimed = true,
                            externalImportHandoffDispatchStarted = false,
                        )
                    },
                    markHandoffDispatchStarted = { track ->
                        persistedTrack = track.copy(externalImportHandoffDispatchStarted = true)
                        durableDispatchOutcome = true
                        dispatchMarkerPersisted.complete(Unit)
                        persistedTrack
                    },
                    dispatchPlayback = { _, markDispatchStarted ->
                        markDispatchStarted()
                        releasePlayer.await()
                        ExternalAudioHandoffDispatchOutcome.NotDispatched
                    },
                    markHandoffNotDispatched = { track ->
                        persistedTrack = track.copy(externalImportHandoffDispatchStarted = false)
                        durableDispatchOutcome = false
                        persistedTrack
                    },
                    hasDurableDispatchOutcome = { durableDispatchOutcome },
                )
            }
        }

        dispatchMarkerPersisted.await()
        val concurrentRetry = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                completeExternalAudioOpen(
                    requestId = requestId,
                    onHandled = { acknowledgementCount++ },
                    importAndPersist = { error("The current process cache should satisfy the concurrent retry.") },
                    processState = processState,
                    hasDurableDispatchOutcome = { durableDispatchOutcome },
                    dispatchPlayback = { _, _ -> error("The concurrent action must not start another player call.") },
                )
            }
        }
        yield()
        val acknowledgedBeforePlayerResult = acknowledgementCount
        releasePlayer.complete(Unit)

        assertTrue(firstAttempt.await().isFailure)
        assertTrue(concurrentRetry.await().isFailure)
        assertEquals(0, acknowledgedBeforePlayerResult)
        assertEquals(0, acknowledgementCount)
        assertFalse(durableDispatchOutcome)
        assertEquals(false, persistedTrack.externalImportHandoffDispatchStarted)
    }

    @Test
    fun importFailureDoesNotAcknowledgeRequestAsCompleted() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        var acknowledgementCount = 0
        var failureCount = 0

        val result = runCatching {
            completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { throw IOException("Temporary provider failure") },
                afterPersist = {},
                onFailure = { failureCount++ },
            )
        }

        assertTrue(result.isFailure)
        assertEquals("A failed import must remain pending for a retry.", 0, acknowledgementCount)
        assertEquals(1, failureCount)
    }
}

private fun testTrack(id: String) = Track(
    id = id,
    title = id,
    artist = "Test artist",
    filePath = "/test/$id.mp3",
)
