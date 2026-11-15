package com.neiro.aftertone

import android.net.TestUri
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioOpenRequestQueuePersistenceTest {
    @Test
    fun queuedIntentSurvivesActivityProcessRecreationBeforeAnotherStateSave() {
        val requestA = request(ID_A, "content://media/external/audio/101", attempt = 2)
        val requestB = request(ID_B, "content://media/external/audio/202")
        val preferences = InMemoryQueuePreferences()
        val firstActivityStore = preferences.newStore()
        val firstEnqueue = firstActivityStore.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA)
        assertTrue(firstEnqueue.persisted)
        val secondEnqueue = firstActivityStore.enqueue(firstEnqueue.snapshot, requestB)

        assertTrue(secondEnqueue.persisted)

        val restored = preferences.newStore().load()

        assertNotNull("The queue must be durable before Activity state is saved.", restored)
        assertRequests(listOf(requestA, requestB), requireNotNull(restored).pendingRequests)
    }

    @Test
    fun durableQueueAndCurrentLaunchIntentMergeInOrderWithoutDuplicatingRequest() {
        val requestA = request(ID_A, "content://media/external/audio/101")
        val requestB = request(ID_B, "content://media/external/audio/202", attempt = 1)
        val persisted = ExternalAudioOpenRequestQueueSnapshot(listOf(requestA, requestB))

        val merged = mergeExternalAudioOpenRequestQueue(
            persistedSnapshot = persisted,
            savedStateRequests = listOf(requestA),
            currentIntentRequest = requestB.copy(attempt = 0),
        )

        assertRequests(listOf(requestA, requestB), merged.pendingRequests)
    }

    @Test
    fun staleContentUriWithoutPersistedGrantIsRemovedButCurrentIntentRemainsRetryable() {
        val preferences = InMemoryQueuePreferences()
        val queued = request(ID_A, "content://media/external/audio/expired")
        assertTrue(preferences.newStore().enqueue(ExternalAudioOpenRequestQueueSnapshot(), queued).persisted)

        val coldStart = preferences.newStore().restore(
            savedStateRequests = null,
            currentIntentRequest = null,
            hasPersistedReadPermission = { false },
        )

        assertTrue(coldStart.persisted)
        assertEquals(1, coldStart.reopenRequiredCount)
        assertTrue("An inaccessible old request must not block the queue after its task is gone.", coldStart.snapshot.pendingRequests.isEmpty())
        assertTrue(requireNotNull(preferences.newProcessStore().load()).pendingRequests.isEmpty())

        val reopened = preferences.newStore().restore(
            savedStateRequests = null,
            currentIntentRequest = queued,
            hasPersistedReadPermission = { false },
        )

        assertRequests(listOf(queued), reopened.snapshot.pendingRequests)
    }

    @Test
    fun persistedContentGrantKeepsARequestAcrossColdStart() {
        val preferences = InMemoryQueuePreferences()
        val queued = request(ID_A, "content://media/external/audio/retained")
        assertTrue(preferences.newStore().enqueue(ExternalAudioOpenRequestQueueSnapshot(), queued).persisted)

        val restored = preferences.newStore().restore(
            savedStateRequests = null,
            currentIntentRequest = null,
            hasPersistedReadPermission = { true },
        )

        assertTrue(restored.persisted)
        assertEquals(0, restored.reopenRequiredCount)
        assertRequests(listOf(queued), restored.snapshot.pendingRequests)
    }

    @Test
    fun staleActivityMutationPreservesRequestsAddedToTheLatestDurableQueue() {
        val preferences = InMemoryQueuePreferences()
        val staleStore = preferences.newStore()
        val requestA = request(ID_A, "content://media/external/audio/101")
        val requestB = request(ID_B, "content://media/external/audio/202")
        val firstWrite = staleStore.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA)
        val staleActivitySnapshot = firstWrite.snapshot
        val secondWrite = preferences.newStore().enqueue(staleActivitySnapshot, requestB)
        assertTrue(secondWrite.persisted)

        val completion = staleStore.updatePendingRequests(staleActivitySnapshot) { requests ->
            removeExternalAudioOpenRequest(requests, requestA.id, requestA.attempt)
        }

        assertRequests(listOf(requestB), completion.snapshot.pendingRequests)
        assertRequests(listOf(requestB), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun retryCandidateMutationMergesNewDurableRequestsAndItsAcknowledgments() {
        val preferences = InMemoryQueuePreferences()
        val staleStore = preferences.newStore()
        val requestA = request(ID_A, "content://media/external/audio/101")
        val requestB = request(ID_B, "content://media/external/audio/202")
        val firstWrite = staleStore.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA)
        preferences.commitSucceeds = false
        val failedRemoval = staleStore.replace(firstWrite.snapshot, emptyList())
        val retryCandidate = requireNotNull(failedRemoval.retrySnapshot)

        preferences.commitSucceeds = true
        val newerWrite = preferences.newStore().enqueue(firstWrite.snapshot, requestB)
        assertTrue(newerWrite.persisted)

        val update = staleStore.updatePendingRequests(
            snapshot = retryCandidate,
            useProvidedSnapshotAsBase = true,
        ) { it }

        assertRequests(listOf(requestB), update.snapshot.pendingRequests)
        assertTrue(requestA.id in update.snapshot.acknowledgedRequestIds)
        assertRequests(listOf(requestB), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun retryRebasesCandidateOverRequestsAddedAfterTheCandidateWasCreated() {
        val preferences = InMemoryQueuePreferences()
        val staleStore = preferences.newStore()
        val requestA = request(ID_A, "content://media/external/audio/101")
        val requestB = request(ID_B, "content://media/external/audio/202")
        val firstWrite = staleStore.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA)
        preferences.commitSucceeds = false
        val failedRemoval = staleStore.replace(firstWrite.snapshot, emptyList())
        val retryCandidate = requireNotNull(failedRemoval.retrySnapshot)

        preferences.commitSucceeds = true
        val newerWrite = preferences.newStore().enqueue(firstWrite.snapshot, requestB)
        assertTrue(newerWrite.persisted)

        val retried = staleStore.retry(retryCandidate)

        assertRequests(listOf(requestB), retried.snapshot.pendingRequests)
        assertTrue(requestA.id in retried.snapshot.acknowledgedRequestIds)
        assertRequests(listOf(requestB), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun enqueueFromUndurableCandidateDoesNotResurrectAcknowledgedOrDropNewerRequests() {
        val preferences = InMemoryQueuePreferences()
        val staleStore = preferences.newStore()
        val requestA = request(ID_A, "content://media/external/audio/101")
        val requestC = request(ID_C, "content://media/external/audio/303")
        val requestD = request("00000000-0000-0000-0000-000000000004", "content://media/external/audio/404")
        val firstWrite = staleStore.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA)
        preferences.commitSucceeds = false
        val failedEnqueue = staleStore.enqueue(firstWrite.snapshot, requestC)
        val retryCandidate = requireNotNull(failedEnqueue.retrySnapshot)

        preferences.commitSucceeds = true
        val newerRemoval = preferences.newStore().replace(firstWrite.snapshot, emptyList())
        assertTrue(newerRemoval.persisted)

        val update = staleStore.enqueue(retryCandidate, requestD, useProvidedSnapshotAsBase = true)

        assertRequests(listOf(requestC, requestD), update.snapshot.pendingRequests)
        assertTrue(requestA.id in update.snapshot.acknowledgedRequestIds)
        assertRequests(listOf(requestC, requestD), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun restoringSavedStateAndCurrentLaunchIntentPersistsTheMergedQueue() {
        val requestA = request(ID_A, "content://media/external/audio/101", attempt = 1)
        val requestB = request(ID_B, "content://media/external/audio/202")
        val preferences = InMemoryQueuePreferences()

        val restore = preferences.newStore().restore(
            savedStateRequests = listOf(requestA),
            currentIntentRequest = requestB,
        )

        assertTrue(restore.persisted)
        assertRequests(listOf(requestA, requestB), restore.snapshot.pendingRequests)
        assertRequests(listOf(requestA, requestB), requireNotNull(preferences.newStore().load()).pendingRequests)
    }

    @Test
    fun failedEnqueueCommitDoesNotAdvanceTheLiveQueueOrExposeAnUndurableRequest() {
        val preferences = InMemoryQueuePreferences()
        preferences.commitSucceeds = false

        val update = preferences.newStore().enqueue(
            ExternalAudioOpenRequestQueueSnapshot(),
            request(ID_A, "content://media/external/audio/101"),
        )

        assertFalse(update.persisted)
        assertTrue("A request must not become live before its queue snapshot commits.", update.snapshot.pendingRequests.isEmpty())
        assertRequests(
            listOf(request(ID_A, "content://media/external/audio/101")),
            requireNotNull(update.retrySnapshot).pendingRequests,
        )
        assertNull("A failed write must not be trusted by a recreated Activity in this process.", preferences.newStore().load())
        assertNull("A failed write must not exist in the next process's durable preferences.", preferences.newProcessStore().load())
    }

    @Test
    fun failedCandidateMergesLaterIntentsInOrderAndCanBeRetriedWithoutLosingPriorWork() {
        val preferences = InMemoryQueuePreferences()
        val store = preferences.newStore()
        val requestA = request(ID_A, "content://media/external/audio/101")
        val requestB = request(ID_B, "content://media/external/audio/202")
        val requestC = request(ID_C, "content://media/external/audio/303")
        val original = store.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA)
        assertTrue(original.persisted)
        preferences.commitSucceeds = false

        val firstFailure = store.enqueue(original.snapshot, requestB)

        assertFalse(firstFailure.persisted)
        assertRequests(listOf(requestA), firstFailure.snapshot.pendingRequests)
        assertRequests(listOf(requestA, requestB), requireNotNull(firstFailure.retrySnapshot).pendingRequests)

        val secondFailure = store.enqueue(
            snapshot = requireNotNull(firstFailure.retrySnapshot),
            incomingRequest = requestC,
            useProvidedSnapshotAsBase = true,
        )

        assertFalse(secondFailure.persisted)
        assertRequests(listOf(requestA), secondFailure.snapshot.pendingRequests)
        assertRequests(listOf(requestA, requestB, requestC), requireNotNull(secondFailure.retrySnapshot).pendingRequests)

        preferences.commitSucceeds = true
        val retry = store.retry(requireNotNull(secondFailure.retrySnapshot))

        assertTrue(retry.persisted)
        assertRequests(listOf(requestA, requestB, requestC), retry.snapshot.pendingRequests)
        assertRequests(listOf(requestA, requestB, requestC), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun undurableCandidateSurvivesActivityStateRoundTripAndCommitsBeforeBecomingLive() {
        val preferences = InMemoryQueuePreferences()
        val store = preferences.newStore()
        val requestA = request(ID_A, "content://media/external/audio/101")
        val requestB = request(ID_B, "content://media/external/audio/202")
        val original = store.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA)
        assertTrue(original.persisted)
        preferences.commitSucceeds = false
        val failedEnqueue = store.enqueue(original.snapshot, requestB)
        val savedStateRequests = requireNotNull(failedEnqueue.retrySnapshot).pendingRequests

        val recreatedStore = preferences.newStore()
        preferences.commitSucceeds = true
        val restoration = recreatedStore.restore(
            savedStateRequests = savedStateRequests,
            currentIntentRequest = null,
        )

        assertTrue(restoration.persisted)
        assertRequests(listOf(requestA, requestB), restoration.snapshot.pendingRequests)
        assertRequests(listOf(requestA, requestB), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun sameAttemptFailureStateSurvivesCommitFailureAndActivityRecreation() {
        val preferences = InMemoryQueuePreferences()
        val store = preferences.newStore()
        val requestA = request(ID_A, "content://media/external/audio/101", attempt = 2)
        val original = store.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA)
        assertTrue(original.persisted)
        preferences.commitSucceeds = false

        val failureState = markExternalAudioOpenRequestFailed(
            pending = original.snapshot.pendingRequests,
            requestId = ID_A,
            attempt = 2,
            failureKind = ExternalAudioOpenFailureKind.Import,
            message = "Provider access was denied",
        )
        val failedWrite = store.replace(original.snapshot, failureState)
        assertFalse(failedWrite.persisted)
        val savedStateRequests = requireNotNull(failedWrite.retrySnapshot).pendingRequests

        preferences.commitSucceeds = true
        val restoration = preferences.newStore().restore(
            savedStateRequests = savedStateRequests,
            currentIntentRequest = null,
        )

        assertEquals(ExternalAudioOpenFailureKind.Import, restoration.snapshot.pendingRequests.single().failureKind)
        assertEquals("Provider access was denied", restoration.snapshot.pendingRequests.single().failureMessage)
    }

    @Test
    fun failedAcknowledgementCommitLeavesThePreviousHeadPending() {
        val preferences = InMemoryQueuePreferences()
        val requestA = request(ID_A, "content://media/external/audio/101")
        val enqueue = preferences.newStore().enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA)
        assertTrue(enqueue.persisted)
        preferences.commitSucceeds = false

        val acknowledgement = preferences.newStore().replace(enqueue.snapshot, emptyList())

        assertFalse(acknowledgement.persisted)
        assertRequests(listOf(requestA), acknowledgement.snapshot.pendingRequests)
        assertRequests(listOf(requestA), requireNotNull(preferences.newStore().load()).pendingRequests)
        assertRequests(listOf(requestA), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun skippingFailedHeadAcknowledgesItAndMakesTheNextRequestDurableHead() {
        val preferences = InMemoryQueuePreferences()
        val store = preferences.newStore()
        val failedHead = request(
            ID_A,
            "content://media/external/audio/101",
            attempt = 3,
            failureKind = ExternalAudioOpenFailureKind.Import,
            failureMessage = "The source is unavailable.",
        )
        val next = request(ID_B, "content://media/external/audio/202")
        val first = store.enqueue(ExternalAudioOpenRequestQueueSnapshot(), failedHead)
        assertTrue(first.persisted)
        val withNext = store.enqueue(first.snapshot, next)
        assertTrue(withNext.persisted)

        val afterSkip = resolveExternalAudioOpenFailure(
            pending = withNext.snapshot.pendingRequests,
            requestId = failedHead.id,
            attempt = failedHead.attempt,
            choice = ExternalAudioOpenFailureChoice.Skip,
        )
        val update = store.replace(withNext.snapshot, afterSkip)

        assertTrue(update.persisted)
        assertRequests(listOf(next), update.snapshot.pendingRequests)
        assertEquals(setOf(failedHead.id), update.snapshot.acknowledgedRequestIds)
        assertEquals(failedHead.uri.toString(), update.snapshot.acknowledgedRequestUris[failedHead.id])
        assertRequests(listOf(next), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun malformedPersistedQueueEncodingIsRejectedInsteadOfCrashingLoad() {
        val preferences = InMemoryQueuePreferences()
        preferences.writeRaw(
            "aftertone-external-audio-open-queue-v1\n" +
                "P\t$ID_A\tcontent%3A%2F%2Fmedia%2Fexternal%2Faudio%2F101\t0\t\t%",
        )

        assertNull(preferences.newStore().load())
    }

    @Test
    fun persistedNonCanonicalRequestIdIsCorruptAndRetryCannotOverwriteItsRawValue() {
        val raw = "aftertone-external-audio-open-queue-v1\n" +
            "P\t1-1-1-1-1\tcontent%3A%2F%2Fmedia%2Fexternal%2Faudio%2F101\t0\t\t"
        val preferences = InMemoryQueuePreferences().apply { writeRaw(raw) }
        val store = preferences.newStore()

        assertEquals(ExternalAudioOpenQueueLoadStatus.Corrupt, store.loadStatus)
        assertEquals(raw, store.rawSnapshotForRecovery)
        val retry = store.retry(
            ExternalAudioOpenRequestQueueSnapshot(
                pendingRequests = listOf(request(ID_A, "content://media/external/audio/202")),
            ),
        )

        assertFalse(retry.persisted)
        assertEquals(ExternalAudioOpenQueueStoreProblem.Corrupt, retry.storeProblem)
        assertEquals(raw, preferences.activeValue())
    }

    @Test
    fun loadStatusDistinguishesNoDataValidOversizedAndTransientReadFailure() {
        val preferences = InMemoryQueuePreferences()
        val store = preferences.newStore()

        assertEquals(ExternalAudioOpenQueueLoadStatus.NoData, store.loadStatus)
        assertTrue(store.save(ExternalAudioOpenRequestQueueSnapshot()))
        assertEquals(ExternalAudioOpenQueueLoadStatus.Ready, store.loadStatus)

        val oversizedRaw = "x".repeat(MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES + 1)
        val oversizedStore = InMemoryQueuePreferences().apply { writeRaw(oversizedRaw) }.newStore()
        assertEquals(ExternalAudioOpenQueueLoadStatus.Oversized, oversizedStore.loadStatus)
        assertEquals(oversizedRaw, oversizedStore.rawSnapshotForRecovery)

        var readSucceeds = false
        val transientReadStore = ExternalAudioOpenRequestQueueStore(
            readEncoded = { if (readSucceeds) null else error("simulated preference read failure") },
            commitEncoded = { true },
            parseUri = ::TestUri,
        )
        assertEquals(ExternalAudioOpenQueueLoadStatus.ReadFailure, transientReadStore.loadStatus)
        readSucceeds = true
        assertEquals(ExternalAudioOpenQueueLoadStatus.NoData, transientReadStore.loadStatus)
    }

    @Test
    fun corruptQueueRecoveryBacksUpRawSnapshotBeforePersistingSavedAndCurrentRequests() {
        val raw = "aftertone-external-audio-open-queue-v1\nP\tinvalid-record"
        val preferences = InMemoryQueuePreferences().apply {
            writeRaw(raw)
            requireBackupBeforeReplacingRaw = raw
        }
        val store = preferences.newStore()
        val saved = request(ID_A, "content://media/external/audio/101", attempt = 2)
        val current = request(ID_B, "content://media/external/audio/202")
        val failedRestore = store.restore(listOf(saved), current)

        assertFalse(failedRestore.persisted)
        assertEquals(ExternalAudioOpenQueueStoreProblem.Corrupt, failedRestore.storeProblem)
        assertEquals(raw, preferences.activeValue())
        assertRequests(listOf(saved, current), requireNotNull(failedRestore.retrySnapshot).pendingRequests)

        val recovered = store.recoverCorruptSnapshot(requireNotNull(failedRestore.retrySnapshot))

        assertTrue(recovered.persisted)
        assertNull(recovered.storeProblem)
        assertEquals(raw, preferences.quarantinedRaw)
        assertRequests(listOf(saved, current), recovered.snapshot.pendingRequests)
        assertRequests(listOf(saved, current), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun oversizedQueueRecoveryRetainsExactRawSnapshotAndPersistsTheRetryCandidate() {
        val raw = "x".repeat(MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES + 1)
        val preferences = InMemoryQueuePreferences().apply { writeRaw(raw) }
        val store = preferences.newStore()
        val accepted = request(ID_A, "content://media/external/audio/303")
        val restoration = store.restore(savedStateRequests = listOf(accepted), currentIntentRequest = null)

        assertEquals(ExternalAudioOpenQueueStoreProblem.Oversized, restoration.storeProblem)
        assertEquals(raw, preferences.activeValue())
        val recovered = store.recoverCorruptSnapshot(requireNotNull(restoration.retrySnapshot))

        assertTrue(recovered.persisted)
        assertEquals(raw, preferences.quarantinedRaw)
        assertRequests(listOf(accepted), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun rawSnapshotBeyondQuarantineCapIsNotRetainedOrOverwritten() {
        val raw = "x".repeat(MAX_EXTERNAL_AUDIO_QUEUE_QUARANTINE_BYTES + 1)
        val preferences = InMemoryQueuePreferences().apply { writeRaw(raw) }
        val store = preferences.newStore()
        val accepted = request(ID_A, "content://media/external/audio/oversized-raw")

        assertEquals(ExternalAudioOpenQueueLoadStatus.Oversized, store.loadStatus)
        assertNull(store.rawSnapshotForRecovery)
        val restoration = store.restore(emptyList(), accepted)
        val recovery = store.recoverCorruptSnapshot(requireNotNull(restoration.retrySnapshot))

        assertEquals(ExternalAudioOpenQueueStoreProblem.RecoveryFailure, recovery.storeProblem)
        assertEquals(raw, preferences.activeValue())
        assertNull(preferences.quarantinedRaw)
        assertRequests(listOf(accepted), requireNotNull(recovery.retrySnapshot).pendingRequests)
    }

    @Test
    fun backupFailureLeavesCorruptPreferenceAndRetryCandidateUntouched() {
        val raw = "aftertone-external-audio-open-queue-v1\nP\tbroken"
        val preferences = InMemoryQueuePreferences().apply {
            writeRaw(raw)
            backupSucceeds = false
        }
        val store = preferences.newStore()
        val accepted = request(ID_A, "content://media/external/audio/404")
        val restoration = store.restore(emptyList(), accepted)

        val recovery = store.recoverCorruptSnapshot(requireNotNull(restoration.retrySnapshot))

        assertFalse(recovery.persisted)
        assertEquals(ExternalAudioOpenQueueStoreProblem.RecoveryFailure, recovery.storeProblem)
        assertEquals(raw, preferences.activeValue())
        assertRequests(listOf(accepted), requireNotNull(recovery.retrySnapshot).pendingRequests)
    }

    @Test
    fun skippedRequestStaysAcknowledgedWhenCorruptQueueIsRecovered() {
        val preferences = InMemoryQueuePreferences().apply {
            writeRaw("aftertone-external-audio-open-queue-v1\nP\tbroken")
        }
        val store = preferences.newStore()
        val skipped = request(
            ID_A,
            "content://media/external/audio/skipped",
            attempt = 1,
            failureKind = ExternalAudioOpenFailureKind.Import,
            failureMessage = "Unavailable",
        )
        val next = request(ID_B, "content://media/external/audio/next")
        val restoration = store.restore(listOf(skipped), next)
        val candidate = requireNotNull(restoration.retrySnapshot)
        val afterSkip = resolveExternalAudioOpenFailure(
            candidate.pendingRequests,
            skipped.id,
            skipped.attempt,
            ExternalAudioOpenFailureChoice.Skip,
        )
        val skippedUpdate = store.replace(candidate, afterSkip, useProvidedSnapshotAsBase = true)

        assertEquals(setOf(skipped.id), requireNotNull(skippedUpdate.retrySnapshot).acknowledgedRequestIds)
        val recovered = store.recoverCorruptSnapshot(requireNotNull(skippedUpdate.retrySnapshot))

        assertTrue(recovered.persisted)
        assertRequests(listOf(next), recovered.snapshot.pendingRequests)
        assertEquals(setOf(skipped.id), recovered.snapshot.acknowledgedRequestIds)
        assertEquals(skipped.uri.toString(), recovered.snapshot.acknowledgedRequestUris[skipped.id])
    }

    @Test
    fun failedRecoveryWriteKeepsCandidateAndCanBeRetriedAfterStorageRecovers() {
        val raw = "aftertone-external-audio-open-queue-v1\nP\tbroken"
        val preferences = InMemoryQueuePreferences().apply {
            writeRaw(raw)
            commitSucceeds = false
        }
        val store = preferences.newStore()
        val saved = request(ID_A, "content://media/external/audio/505")
        val current = request(ID_B, "content://media/external/audio/606")
        val restoration = store.restore(listOf(saved), current)
        val candidate = requireNotNull(restoration.retrySnapshot)

        val failedRecovery = store.recoverCorruptSnapshot(candidate)

        assertFalse(failedRecovery.persisted)
        assertEquals(ExternalAudioOpenQueueStoreProblem.RecoveryWriteFailure, failedRecovery.storeProblem)
        assertEquals(raw, preferences.activeValue())
        assertEquals(raw, preferences.quarantinedRaw)
        assertRequests(listOf(saved, current), requireNotNull(failedRecovery.retrySnapshot).pendingRequests)

        preferences.commitSucceeds = true
        val retriedRecovery = store.recoverCorruptSnapshot(requireNotNull(failedRecovery.retrySnapshot))

        assertTrue(retriedRecovery.persisted)
        assertRequests(listOf(saved, current), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun additionalIntentDuringCorruptQueueRecoveryIsMergedIntoTheExistingCandidate() {
        val preferences = InMemoryQueuePreferences().apply {
            writeRaw("aftertone-external-audio-open-queue-v1\nP\tbroken")
        }
        val store = preferences.newStore()
        val first = request(ID_A, "content://media/external/audio/707")
        val second = request(ID_B, "content://media/external/audio/808")
        val restoration = store.restore(emptyList(), first)

        val update = store.enqueue(
            snapshot = requireNotNull(restoration.retrySnapshot),
            incomingRequest = second,
            useProvidedSnapshotAsBase = true,
        )

        assertEquals(ExternalAudioOpenQueueStoreProblem.Corrupt, update.storeProblem)
        assertRequests(listOf(first, second), requireNotNull(update.retrySnapshot).pendingRequests)
    }

    @Test
    fun corruptRestoreRejectsOnlyANewIntentWhenSavedAcceptedQueueAlreadyFillsCapacity() {
        val preferences = InMemoryQueuePreferences().apply {
            writeRaw("aftertone-external-audio-open-queue-v1\nP\tbroken")
        }
        val saved = (0 until MAX_EXTERNAL_AUDIO_PENDING_REQUESTS).map { index ->
            request(
                UUID.nameUUIDFromBytes("saved-$index".toByteArray()).toString(),
                "content://media/external/audio/saved-$index",
            )
        }
        val incoming = request(ID_C, "content://media/external/audio/new-arrival")

        val restoration = preferences.newStore().restore(saved, incoming)

        assertEquals(ExternalAudioOpenQueueStoreProblem.Corrupt, restoration.storeProblem)
        assertEquals(ExternalAudioOpenRequestQueueRejection.QueueFull, restoration.rejection)
        assertEquals(saved.map { it.id }, requireNotNull(restoration.retrySnapshot).pendingRequests.map { it.id })
    }

    @Test
    fun normalStorageWriteFailureIsReportedAndRetryPersistsTheAcceptedCandidate() {
        val preferences = InMemoryQueuePreferences().apply { commitSucceeds = false }
        val store = preferences.newStore()
        val accepted = request(ID_A, "content://media/external/audio/909")

        val failed = store.enqueue(ExternalAudioOpenRequestQueueSnapshot(), accepted)

        assertFalse(failed.persisted)
        assertEquals(ExternalAudioOpenQueueStoreProblem.WriteFailure, failed.storeProblem)
        assertRequests(listOf(accepted), requireNotNull(failed.retrySnapshot).pendingRequests)
        preferences.commitSucceeds = true
        val retried = store.retry(requireNotNull(failed.retrySnapshot))
        assertTrue(retried.persisted)
        assertRequests(listOf(accepted), requireNotNull(preferences.newProcessStore().load()).pendingRequests)
    }

    @Test
    fun recreatedActivitySuppressesIdlessCurrentIntentForAnAcknowledgedUri() {
        val preferences = InMemoryQueuePreferences()
        val completed = ExternalAudioOpenRequestQueueSnapshot(
            acknowledgedRequestIds = setOf(ID_A),
            acknowledgedRequestUris = mapOf(ID_A to "content://media/external/audio/101"),
        )
        assertTrue(preferences.newStore().save(completed))

        val restoration = preferences.newStore().restore(
            savedStateRequests = emptyList(),
            currentIntentRequest = request(ID_C, "content://media/external/audio/101"),
            suppressAcknowledgedUriOnRecreation = true,
        )

        assertTrue(restoration.persisted)
        assertTrue("The old launch intent has no durable request-ID extra after process death.", restoration.snapshot.pendingRequests.isEmpty())
    }

    @Test
    fun recreatedActivityStillQueuesARequestForAnUnacknowledgedUri() {
        val preferences = InMemoryQueuePreferences()
        val completed = ExternalAudioOpenRequestQueueSnapshot(
            acknowledgedRequestIds = setOf(ID_A),
            acknowledgedRequestUris = mapOf(ID_A to "content://media/external/audio/101"),
        )
        assertTrue(preferences.newStore().save(completed))
        val newRequest = request(ID_C, "content://media/external/audio/202")

        val restoration = preferences.newStore().restore(
            savedStateRequests = emptyList(),
            currentIntentRequest = newRequest,
            suppressAcknowledgedUriOnRecreation = true,
        )

        assertRequests(listOf(newRequest), restoration.snapshot.pendingRequests)
    }

    @Test
    fun freshActivityLaunchCanReopenTheSameUriAfterEarlierRequestWasHandled() {
        val preferences = InMemoryQueuePreferences()
        val completed = ExternalAudioOpenRequestQueueSnapshot(
            acknowledgedRequestIds = setOf(ID_A),
            acknowledgedRequestUris = mapOf(ID_A to "content://media/external/audio/101"),
        )
        assertTrue(preferences.newStore().save(completed))
        val newRequest = request(ID_C, "content://media/external/audio/101")

        val restoration = preferences.newStore().restore(
            savedStateRequests = null,
            currentIntentRequest = newRequest,
            suppressAcknowledgedUriOnRecreation = shouldSuppressAcknowledgedUriOnRecreation(
                activityRecreated = false,
                hasCurrentExternalIntent = true,
                requestIdWasSupplied = false,
            ),
        )

        assertRequests(listOf(newRequest), restoration.snapshot.pendingRequests)
    }

    @Test
    fun aGenuinelyNewOnNewIntentCanReopenAnAcknowledgedUri() {
        val completed = ExternalAudioOpenRequestQueueSnapshot(
            acknowledgedRequestIds = setOf(ID_A),
            acknowledgedRequestUris = mapOf(ID_A to "content://media/external/audio/101"),
        )

        val reopened = ExternalAudioOpenRequestQueueStore(
            readEncoded = { null },
            commitEncoded = { true },
            parseUri = ::TestUri,
        ).enqueue(completed, request(ID_C, "content://media/external/audio/101"))

        assertRequests(listOf(request(ID_C, "content://media/external/audio/101")), reopened.snapshot.pendingRequests)
    }

    @Test
    fun failedFreshSameUriReopenKeepsItsNewIntentIdInsteadOfBindingAnOldAcknowledgement() {
        val preferences = InMemoryQueuePreferences()
        val completed = ExternalAudioOpenRequestQueueSnapshot(
            acknowledgedRequestIds = setOf(ID_A),
            acknowledgedRequestUris = mapOf(ID_A to "content://media/external/audio/101"),
        )
        assertTrue(preferences.newStore().save(completed))
        preferences.commitSucceeds = false
        val reopened = request(ID_C, "content://media/external/audio/101")
        val update = preferences.newStore().enqueue(completed, reopened)
        assertFalse(update.persisted)

        assertEquals(ID_C, externalAudioOpenRequestIdToBind(reopened, update.snapshot, allowAcknowledgedBinding = false))
        assertEquals(listOf(ID_C), requireNotNull(update.retrySnapshot).pendingRequests.map(ExternalAudioOpenRequest::id))
    }

    @Test
    fun onlyAnIntentionallySuppressedStaleIntentBindsToTheAcknowledgedId() {
        val completed = ExternalAudioOpenRequestQueueSnapshot(
            acknowledgedRequestIds = setOf(ID_A),
            acknowledgedRequestUris = mapOf(ID_A to "content://media/external/audio/101"),
        )
        val reopened = request(ID_C, "content://media/external/audio/101")
        val pending = listOf(request(ID_B, "content://media/external/audio/101"))

        assertEquals(ID_C, externalAudioOpenRequestIdToBind(reopened, completed, allowAcknowledgedBinding = false))
        assertEquals(ID_A, externalAudioOpenRequestIdToBind(reopened, completed, allowAcknowledgedBinding = true))
        assertEquals(
            ID_B,
            externalAudioOpenRequestIdToBind(reopened, completed.copy(pendingRequests = pending), allowAcknowledgedBinding = true),
        )
    }

    @Test
    fun freshSameUriOnNewIntentKeepsItsIdInsteadOfBindingToExistingCandidate() {
        val candidate = ExternalAudioOpenRequestQueueSnapshot(
            pendingRequests = listOf(
                request(ID_B, "content://media/external/audio/101"),
                request(ID_C, "content://media/external/audio/101"),
            ),
        )
        val incoming = request(ID_C, "content://media/external/audio/101")
        val failedUpdate = ExternalAudioOpenRequestQueueStoreUpdate(
            snapshot = ExternalAudioOpenRequestQueueSnapshot(),
            persisted = false,
            snapshotIsDurable = false,
            retrySnapshot = candidate,
        )

        assertEquals(
            ID_C,
            externalAudioOpenRequestIdToBindAfterUpdate(
                incoming,
                failedUpdate,
                allowAcknowledgedBinding = false,
                bindMatchingUri = false,
            ),
        )
    }

    @Test
    fun failedRetryOfUndurableAcknowledgementDoesNotExposeItsRemovalCandidate() {
        val preferences = InMemoryQueuePreferences()
        val store = preferences.newStore()
        val requestA = request(ID_A, "content://media/external/audio/101")
        val original = store.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA)
        assertTrue(original.persisted)
        preferences.commitSucceeds = false
        val removal = store.replace(original.snapshot, emptyList())
        val undurableRemoval = requireNotNull(removal.retrySnapshot)

        val retryAfterStaleIntent = store.enqueue(
            snapshot = undurableRemoval,
            incomingRequest = requestA,
            useProvidedSnapshotAsBase = true,
        )

        assertFalse(retryAfterStaleIntent.persisted)
        assertTrue(retryAfterStaleIntent.snapshotIsDurable)
        assertRequests(listOf(requestA), retryAfterStaleIntent.snapshot.pendingRequests)
        assertTrue(requireNotNull(retryAfterStaleIntent.retrySnapshot).pendingRequests.isEmpty())
        assertEquals(setOf(ID_A), requireNotNull(retryAfterStaleIntent.retrySnapshot).acknowledgedRequestIds)
    }

    @Test
    fun legacyAcknowledgementWithoutUriRemainsReadable() {
        val preferences = InMemoryQueuePreferences()
        preferences.writeRaw("aftertone-external-audio-open-queue-v1\nA\t$ID_A")

        val loaded = requireNotNull(preferences.newStore().load())

        assertEquals(setOf(ID_A), loaded.acknowledgedRequestIds)
        assertTrue(loaded.acknowledgedRequestUris.isEmpty())
    }

    @Test
    fun undurableQueueUpdateCannotAdvanceLiveActivityState() {
        val current = ExternalAudioOpenRequestQueueSnapshot(
            pendingRequests = listOf(request(ID_A, "content://media/external/audio/101")),
        )
        val proposed = ExternalAudioOpenRequestQueueSnapshot(
            pendingRequests = listOf(request(ID_B, "content://media/external/audio/202")),
        )
        val failedWrite = ExternalAudioOpenRequestQueueStoreUpdate(
            snapshot = proposed,
            persisted = false,
            snapshotIsDurable = false,
        )

        assertEquals(current, externalAudioOpenQueueAfterUpdate(current, failedWrite))
    }

    @Test
    fun failedWriteCanKeepThePreviousDurableQueueVisible() {
        val previous = ExternalAudioOpenRequestQueueSnapshot(
            pendingRequests = listOf(request(ID_A, "content://media/external/audio/101")),
        )
        val failedWrite = ExternalAudioOpenRequestQueueStoreUpdate(
            snapshot = previous,
            persisted = false,
            snapshotIsDurable = true,
        )

        assertEquals(previous, externalAudioOpenQueueAfterUpdate(previous, failedWrite))
    }

    @Test
    fun failedProposalStaysOutsideProcessingQueueUntilDurableThenRetryCandidateClears() {
        val previous = ExternalAudioOpenRequestQueueSnapshot()
        val proposed = ExternalAudioOpenRequestQueueSnapshot(
            pendingRequests = listOf(request(ID_A, "content://media/external/audio/101")),
        )
        val failedWrite = ExternalAudioOpenRequestQueueStoreUpdate(
            snapshot = previous,
            persisted = false,
            snapshotIsDurable = false,
            retrySnapshot = proposed,
        )
        val retryCandidate = externalAudioQueueRetryCandidateAfterUpdate(null, failedWrite)

        assertEquals(previous, externalAudioOpenQueueAfterUpdate(previous, failedWrite))
        assertEquals(proposed, retryCandidate)

        val successfulRetry = ExternalAudioOpenRequestQueueStoreUpdate(snapshot = proposed, persisted = true)
        assertNull(externalAudioQueueRetryCandidateAfterUpdate(retryCandidate, successfulRetry))
        assertEquals(proposed, externalAudioOpenQueueAfterUpdate(previous, successfulRetry))
    }

    @Test
    fun onlyAnIdlessCurrentIntentDuringActivityRecreationUsesAcknowledgedUriSuppression() {
        assertTrue(shouldSuppressAcknowledgedUriOnRecreation(true, true, false))
        assertFalse(shouldSuppressAcknowledgedUriOnRecreation(false, true, false))
        assertFalse(shouldSuppressAcknowledgedUriOnRecreation(true, false, false))
        assertFalse(shouldSuppressAcknowledgedUriOnRecreation(true, true, true))
    }

    @Test
    fun savedAndCurrentDuplicateKeepsSavedFailureAndAttemptState() {
        val failedA = request(
            ID_A,
            "content://media/external/audio/101",
            attempt = 4,
            failureKind = ExternalAudioOpenFailureKind.Import,
            failureMessage = "The source is temporarily unavailable.",
        )
        val requestB = request(ID_B, "content://media/external/audio/202")
        val duplicateCurrent = request(ID_A, failedA.uri.toString(), attempt = 0)

        val merged = mergeExternalAudioOpenRequestQueue(
            persistedSnapshot = null,
            savedStateRequests = listOf(failedA, requestB),
            currentIntentRequest = duplicateCurrent,
        )

        assertRequests(listOf(failedA, requestB), merged.pendingRequests)
        assertEquals(4, merged.pendingRequests.first().attempt)
        assertEquals(ExternalAudioOpenFailureKind.Import, merged.pendingRequests.first().failureKind)
        assertEquals("The source is temporarily unavailable.", merged.pendingRequests.first().failureMessage)
    }

    @Test
    fun savedSameUriRequestsWithDistinctIdsBothSurviveActivityRestoration() {
        val first = request(ID_A, "content://media/external/audio/reopened", attempt = 1)
        val second = request(ID_B, "content://media/external/audio/reopened", attempt = 0)

        val restored = mergeExternalAudioOpenRequestQueue(
            persistedSnapshot = null,
            savedStateRequests = listOf(first, second),
            currentIntentRequest = null,
        )

        assertRequests(listOf(first, second), restored.pendingRequests)
    }

    @Test
    fun sameSavedUriWithDifferentIntentIdIsStillDeduplicatedWithoutRetrying() {
        val failedA = request(
            ID_A,
            "content://media/external/audio/101",
            attempt = 3,
            failureKind = ExternalAudioOpenFailureKind.LibraryLoad,
            failureMessage = "The library is still loading.",
        )
        val duplicateIntent = request(ID_C, failedA.uri.toString())

        val merged = mergeExternalAudioOpenRequestQueue(
            persistedSnapshot = null,
            savedStateRequests = listOf(failedA),
            currentIntentRequest = duplicateIntent,
        )

        assertRequests(listOf(failedA), merged.pendingRequests)
    }

    @Test
    fun acknowledgedIntentCannotBeReintroducedFromStaleSavedStateOrCurrentIntent() {
        val handled = request(ID_A, "content://media/external/audio/101")
        val durableEmpty = ExternalAudioOpenRequestQueueSnapshot(
            pendingRequests = emptyList(),
            acknowledgedRequestIds = setOf(ID_A),
        )

        val merged = mergeExternalAudioOpenRequestQueue(
            persistedSnapshot = durableEmpty,
            savedStateRequests = listOf(handled),
            currentIntentRequest = handled,
        )

        assertTrue(merged.pendingRequests.isEmpty())
        assertEquals(setOf(ID_A), merged.acknowledgedRequestIds)
    }

    @Test
    fun staleCandidateCannotEvictMoreRecentDurableAcknowledgmentHistory() {
        val durableIds = List(MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_IDS) { UUID.randomUUID().toString() }
        val staleCandidateIds = List(8) { UUID.randomUUID().toString() }
        val durableUris = LinkedHashMap<String, String>().apply {
            durableIds.take(MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_URIS)
                .forEachIndexed { index, id -> put(id, "content://media/external/audio/durable/$index") }
        }
        val staleCandidateUris = LinkedHashMap<String, String>().apply {
            staleCandidateIds.forEachIndexed { index, id -> put(id, "content://media/external/audio/stale/$index") }
        }
        val durable = ExternalAudioOpenRequestQueueSnapshot(
            acknowledgedRequestIds = LinkedHashSet(durableIds),
            acknowledgedRequestUris = durableUris,
        )
        val staleCandidate = ExternalAudioOpenRequestQueueSnapshot(
            acknowledgedRequestIds = LinkedHashSet(staleCandidateIds),
            acknowledgedRequestUris = staleCandidateUris,
        )

        val merged = mergeExternalAudioOpenRequestQueueSnapshots(durable, staleCandidate)

        assertEquals(durableIds, merged.acknowledgedRequestIds.toList())
        assertEquals(MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_IDS, merged.acknowledgedRequestIds.size)
        assertEquals(durableUris, merged.acknowledgedRequestUris)
    }

    private fun assertRequests(
        expected: List<ExternalAudioOpenRequest>,
        actual: List<ExternalAudioOpenRequest>,
    ) {
        assertEquals(expected.map { it.id }, actual.map { it.id })
        assertEquals(expected.map { it.uri.toString() }, actual.map { it.uri.toString() })
        assertEquals(expected.map { it.attempt }, actual.map { it.attempt })
        assertEquals(expected.map { it.failureKind }, actual.map { it.failureKind })
        assertEquals(expected.map { it.failureMessage }, actual.map { it.failureMessage })
    }

    private fun request(
        id: String,
        uri: String,
        attempt: Int = 0,
        failureKind: ExternalAudioOpenFailureKind? = null,
        failureMessage: String? = null,
    ) = ExternalAudioOpenRequest(
        id = id,
        uri = TestUri(uri),
        attempt = attempt,
        failureKind = failureKind,
        failureMessage = failureMessage,
    )

    private class InMemoryQueuePreferences {
        private var inMemoryEncoded: String? = null
        private var durableEncoded: String? = null
        private val cacheKey = "queue-test-${UUID.randomUUID()}"
        var commitSucceeds = true
        var backupSucceeds = true
        var quarantinedRaw: String? = null
        var requireBackupBeforeReplacingRaw: String? = null

        fun activeValue(): String? = inMemoryEncoded

        fun newStore() = ExternalAudioOpenRequestQueueStore(
            readEncoded = { inMemoryEncoded },
            commitEncoded = { value ->
                if (commitSucceeds) {
                    requireBackupBeforeReplacingRaw?.takeIf { it == inMemoryEncoded }?.let { raw ->
                        check(quarantinedRaw == raw) { "The raw queue must be backed up before replacement." }
                    }
                    inMemoryEncoded = value
                    durableEncoded = value
                }
                commitSucceeds
            },
            parseUri = ::TestUri,
            processCacheKey = cacheKey,
            backupCorruptSnapshot = { raw ->
                if (!backupSucceeds || inMemoryEncoded != raw) {
                    false
                } else {
                    quarantinedRaw = raw
                    true
                }
            },
        )

        fun newProcessStore() = ExternalAudioOpenRequestQueueStore(
            readEncoded = { durableEncoded },
            commitEncoded = { value ->
                durableEncoded = value
                inMemoryEncoded = value
                true
            },
            parseUri = ::TestUri,
        )

        fun writeRaw(value: String) {
            inMemoryEncoded = value
            durableEncoded = value
        }
    }

    private companion object {
        const val ID_A = "00000000-0000-0000-0000-000000000001"
        const val ID_B = "00000000-0000-0000-0000-000000000002"
        const val ID_C = "00000000-0000-0000-0000-000000000003"
    }
}
