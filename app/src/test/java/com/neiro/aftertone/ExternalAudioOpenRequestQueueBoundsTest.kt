package com.neiro.aftertone

import android.net.TestUri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalAudioOpenRequestQueueBoundsTest {
    @Test
    fun testUriEqualityMatchesAndroidUriStringSemantics() {
        val first = TestUri("content://media/external/audio/equal")
        val second = TestUri("content://media/external/audio/equal")

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun utf8SizeCountsValidUnicodeAndReplacementBytes() {
        assertEquals(1, utf8Size("A"))
        assertEquals(2, utf8Size("é"))
        assertEquals(4, utf8Size("🙂"))
        assertEquals(1, utf8Size("\uD800"))
    }

    @Test
    fun nonCanonicalSavedRequestIdsAreDiscardedInsteadOfEnteringTheRetryQueue() {
        val restored = restoreExternalAudioOpenRequests(
            requestIds = listOf("1-1-1-1-1"),
            uriStrings = listOf("content://media/external/audio/1"),
            attempts = listOf(0),
            failureKinds = null,
            failureMessages = null,
            parseUri = ::TestUri,
        )

        assertTrue(requireNotNull(restored).isEmpty())
    }

    @Test
    fun savedStateRejectsFileUrisInsteadOfRestoringThemAsAudioRequests() {
        val restored = restoreExternalAudioOpenRequests(
            requestIds = listOf("00000000-0000-0000-0000-000000000001"),
            uriStrings = listOf("file:///sdcard/Music/track.mp3"),
            attempts = listOf(0),
            failureKinds = null,
            failureMessages = null,
            parseUri = { TestUri(it, "file") },
        )

        assertTrue(requireNotNull(restored).isEmpty())
    }

    @Test
    fun durableQueueRejectsFileUrisAtEnqueueBoundary() {
        var writes = 0
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { null },
            commitEncoded = { writes += 1; true },
            parseUri = ::TestUri,
        )

        val update = store.enqueue(
            ExternalAudioOpenRequestQueueSnapshot(),
            ExternalAudioOpenRequest(
                id = "00000000-0000-0000-0000-000000000002",
                uri = TestUri("file:///sdcard/Music/track.mp3", "file"),
            ),
        )

        assertEquals(ExternalAudioOpenRequestQueueRejection.InvalidRequest, update.rejection)
        assertTrue(update.snapshot.pendingRequests.isEmpty())
        assertEquals(0, writes)
    }

    @Test
    fun persistedLegacySnapshotRejectsFileUrisDuringDecode() {
        val contentUri = "content://media/external/audio/legacy"
        val fileUri = "file:///sdcard/Music/legacy.mp3"
        var encoded: String? = null
        val writer = ExternalAudioOpenRequestQueueStore(
            readEncoded = { null },
            commitEncoded = { encoded = it; true },
            parseUri = { TestUri(it, "content") },
        )
        assertTrue(
            writer.save(
                ExternalAudioOpenRequestQueueSnapshot(
                    pendingRequests = listOf(
                        ExternalAudioOpenRequest(
                            id = "00000000-0000-0000-0000-000000000003",
                            uri = TestUri(contentUri, "content"),
                        ),
                    ),
                ),
            ),
        )

        val legacyFileSnapshot = requireNotNull(encoded).replace(
            URLEncoder.encode(contentUri, StandardCharsets.UTF_8.name()),
            URLEncoder.encode(fileUri, StandardCharsets.UTF_8.name()),
        )
        val reader = ExternalAudioOpenRequestQueueStore(
            readEncoded = { legacyFileSnapshot },
            commitEncoded = { false },
            parseUri = { value -> TestUri(value, if (value.startsWith("file:")) "file" else "content") },
        )

        assertNull("A legacy file:// queue entry must not survive decoding.", reader.load())
        assertEquals(ExternalAudioOpenQueueLoadStatus.Corrupt, reader.loadStatus)
    }

    @Test
    fun enqueueRejectsANewRequestWhenPendingQueueIsAtCapacityWithoutDroppingAcceptedRequests() {
        val writes = mutableListOf<String>()
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { writes.lastOrNull() },
            commitEncoded = { writes += it; true },
            parseUri = ::TestUri,
        )
        var snapshot = ExternalAudioOpenRequestQueueSnapshot()
        repeat(32) { index ->
            val update = store.enqueue(snapshot, request(index, "content://media/external/audio/$index"))
            assertTrue(update.persisted)
            snapshot = update.snapshot
        }
        val accepted = snapshot

        val rejected = store.enqueue(snapshot, request(33, "content://media/external/audio/33"))

        assertEquals(ExternalAudioOpenRequestQueueRejection.QueueFull, rejected.rejection)
        assertEquals(32, rejected.snapshot.pendingRequests.size)
        assertEquals(accepted.pendingRequests.map { it.id }, rejected.snapshot.pendingRequests.map { it.id })
        assertFalse("The 33rd request must not be committed as accepted work.", rejected.retrySnapshot?.pendingRequests?.any { it.id == request(33, "content://media/external/audio/33").id } == true)
    }

    @Test
    fun capacityRejectionPreservesAnEarlierUndurableAcceptedQueueCandidate() {
        var memoryEncoded: String? = null
        var durableEncoded: String? = null
        var commitSucceeds = true
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { memoryEncoded ?: durableEncoded },
            commitEncoded = { encoded ->
                memoryEncoded = encoded
                if (commitSucceeds) durableEncoded = encoded
                commitSucceeds
            },
            parseUri = ::TestUri,
            processCacheKey = "failed-commit-candidate-${UUID.randomUUID()}",
        )
        var snapshot = ExternalAudioOpenRequestQueueSnapshot()
        repeat(31) { index ->
            val update = store.enqueue(snapshot, request(index + 1, "content://media/external/audio/$index"))
            assertTrue(update.persisted)
            snapshot = update.snapshot
        }
        commitSucceeds = false
        val failedWrite = store.enqueue(snapshot, request(32, "content://media/external/audio/32"))
        val acceptedButUndurable = requireNotNull(failedWrite.retrySnapshot)

        val rejected = store.enqueue(
            snapshot = acceptedButUndurable,
            incomingRequest = request(33, "content://media/external/audio/33"),
            useProvidedSnapshotAsBase = true,
        )

        assertEquals(ExternalAudioOpenRequestQueueRejection.QueueFull, rejected.rejection)
        assertEquals(
            acceptedButUndurable,
            externalAudioQueueRetryCandidateAfterUpdate(acceptedButUndurable, rejected),
        )
        assertEquals(31, rejected.snapshot.pendingRequests.size)
    }

    @Test
    fun retryAfterWaitingForMutationLockUsesTheLatestCandidate() = runBlocking {
        val mutationMutex = Mutex()
        val oldCandidate = ExternalAudioOpenRequestQueueSnapshot(
            pendingRequests = listOf(request(201, "content://media/external/audio/old")),
        )
        val latestCandidate = ExternalAudioOpenRequestQueueSnapshot(
            pendingRequests = listOf(request(202, "content://media/external/audio/latest")),
        )
        var currentCandidate: ExternalAudioOpenRequestQueueSnapshot? = oldCandidate
        val retried = mutableListOf<ExternalAudioOpenRequestQueueSnapshot>()
        mutationMutex.lock()

        val waitingRetry = launch(start = CoroutineStart.UNDISPATCHED) {
            retryExternalAudioQueueCandidateIfCurrent(
                mutationMutex,
                currentCandidate = { currentCandidate },
                retryCandidate = { retried += it },
            )
        }
        currentCandidate = latestCandidate
        mutationMutex.unlock()
        waitingRetry.join()

        assertEquals(listOf(latestCandidate), retried)
    }

    @Test
    fun retryAfterWaitingForMutationLockIsSkippedWhenCandidateWasCleared() = runBlocking {
        val mutationMutex = Mutex()
        var currentCandidate: ExternalAudioOpenRequestQueueSnapshot? = ExternalAudioOpenRequestQueueSnapshot(
            pendingRequests = listOf(request(203, "content://media/external/audio/cleared")),
        )
        val retried = mutableListOf<ExternalAudioOpenRequestQueueSnapshot>()
        mutationMutex.lock()

        val waitingRetry = launch(start = CoroutineStart.UNDISPATCHED) {
            retryExternalAudioQueueCandidateIfCurrent(
                mutationMutex,
                currentCandidate = { currentCandidate },
                retryCandidate = { retried += it },
            )
        }
        currentCandidate = null
        mutationMutex.unlock()
        waitingRetry.join()

        assertTrue(retried.isEmpty())
    }

    @Test
    fun enqueuePersistsAFreshDifferentIdForTheSameUriWithoutMutatingPendingWork() {
        var stored: String? = null
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { stored },
            commitEncoded = { stored = it; true },
            parseUri = ::TestUri,
        )
        val uri = "content://media/external/audio/reopened"
        val existing = request(204, uri).copy(
            attempt = 3,
            failureKind = ExternalAudioOpenFailureKind.Import,
            failureMessage = "Previous request failed",
        )
        val initial = ExternalAudioOpenRequestQueueSnapshot(pendingRequests = listOf(existing))
        assertTrue(store.save(initial))
        val fresh = request(205, uri)

        val update = store.enqueue(initial, fresh)

        assertTrue(update.persisted)
        assertEquals(listOf(existing.id, fresh.id), update.snapshot.pendingRequests.map { it.id })
        assertEquals(listOf(3, 0), update.snapshot.pendingRequests.map { it.attempt })
        assertEquals("Previous request failed", update.snapshot.pendingRequests.first().failureMessage)
    }

    @Test
    fun restoreRejectsOnlyNewIntentWhenSavedQueueIsAtCapacity() {
        val writes = mutableListOf<String>()
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { writes.lastOrNull() },
            commitEncoded = { writes += it; true },
            parseUri = ::TestUri,
        )
        val acceptedSavedRequests = (0 until MAX_EXTERNAL_AUDIO_PENDING_REQUESTS).map { index ->
            request(index + 1, "content://media/external/audio/saved-$index")
        }

        val update = store.restore(
            savedStateRequests = acceptedSavedRequests,
            currentIntentRequest = request(99, "content://media/external/audio/new-intent"),
        )

        assertEquals(ExternalAudioOpenRequestQueueRejection.QueueFull, update.rejection)
        assertTrue(update.persisted)
        assertEquals(acceptedSavedRequests.map { it.id }, update.snapshot.pendingRequests.map { it.id })
        assertTrue("Only the accepted saved queue should be committed.", writes.isNotEmpty())
        assertEquals(update.snapshot, store.load())
    }

    @Test
    fun failedSavedStateCommitKeepsNewCurrentIntentInOrderedRetryCandidate() {
        var stored: String? = null
        var commitSucceeds = false
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { stored },
            commitEncoded = { encoded ->
                if (commitSucceeds) stored = encoded
                commitSucceeds
            },
            parseUri = ::TestUri,
        )
        val savedRequest = request(10, "content://media/external/audio/saved")
        val currentIntentRequest = request(11, "content://media/external/audio/current")

        val failedRestore = store.restore(
            savedStateRequests = listOf(savedRequest),
            currentIntentRequest = currentIntentRequest,
        )

        assertFalse(failedRestore.persisted)
        assertEquals(null, failedRestore.rejection)
        val recoveryCandidate = requireNotNull(failedRestore.retrySnapshot)
        assertEquals(
            listOf(savedRequest.id, currentIntentRequest.id),
            recoveryCandidate.pendingRequests.map { it.id },
        )
        assertEquals(
            listOf(savedRequest.uri.toString(), currentIntentRequest.uri.toString()),
            recoveryCandidate.pendingRequests.map { it.uri.toString() },
        )
        assertTrue(
            "No request becomes live until the recovery candidate is durably committed.",
            externalAudioOpenQueueAfterUpdate(ExternalAudioOpenRequestQueueSnapshot(), failedRestore)
                .pendingRequests.isEmpty(),
        )

        commitSucceeds = true
        val retry = store.retry(recoveryCandidate)

        assertTrue(retry.persisted)
        assertEquals(
            listOf(savedRequest.id, currentIntentRequest.id),
            retry.snapshot.pendingRequests.map { it.id },
        )
        assertEquals(retry.snapshot, store.load())
    }

    @Test
    fun separateStoreInstancesSerializeReadModifyWriteAcrossSharedProcessCache() {
        val cacheKey = "blocked-commit-${UUID.randomUUID()}"
        var stored: String? = null
        val firstCommitEntered = CountDownLatch(1)
        val releaseFirstCommit = CountDownLatch(1)
        val secondCommitEntered = CountDownLatch(1)
        val secondCallStarted = CountDownLatch(1)
        val firstResult = AtomicReference<ExternalAudioOpenRequestQueueStoreUpdate?>(null)
        val secondResult = AtomicReference<ExternalAudioOpenRequestQueueStoreUpdate?>(null)
        val threadFailure = AtomicReference<Throwable?>(null)
        val firstStore = ExternalAudioOpenRequestQueueStore(
            readEncoded = { stored },
            commitEncoded = { encoded ->
                firstCommitEntered.countDown()
                if (releaseFirstCommit.await(5, TimeUnit.SECONDS)) {
                    stored = encoded
                    true
                } else {
                    false
                }
            },
            parseUri = ::TestUri,
            processCacheKey = cacheKey,
        )
        val secondStore = ExternalAudioOpenRequestQueueStore(
            readEncoded = { stored },
            commitEncoded = { encoded ->
                secondCommitEntered.countDown()
                stored = encoded
                true
            },
            parseUri = ::TestUri,
            processCacheKey = cacheKey,
        )
        assertEquals(ExternalAudioOpenQueueLoadStatus.NoData, firstStore.loadStatus)
        assertEquals(ExternalAudioOpenQueueLoadStatus.NoData, secondStore.loadStatus)
        val requestA = request(1, "content://media/external/audio/101")
        val requestB = request(2, "content://media/external/audio/202")
        val firstThread = Thread {
            try {
                firstResult.set(firstStore.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestA))
            } catch (failure: Throwable) {
                threadFailure.compareAndSet(null, failure)
            }
        }.apply { isDaemon = true }
        firstThread.start()
        assertTrue("The first store should reach its blocked commit.", firstCommitEntered.await(5, TimeUnit.SECONDS))

        val secondThread = Thread {
            secondCallStarted.countDown()
            try {
                secondResult.set(secondStore.enqueue(ExternalAudioOpenRequestQueueSnapshot(), requestB))
            } catch (failure: Throwable) {
                threadFailure.compareAndSet(null, failure)
            }
        }.apply { isDaemon = true }
        secondThread.start()
        assertTrue(secondCallStarted.await(5, TimeUnit.SECONDS))
        val secondCommittedBeforeRelease = secondCommitEntered.await(250, TimeUnit.MILLISECONDS)
        releaseFirstCommit.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)

        assertFalse("Both store calls should finish after the blocked commit is released.", firstThread.isAlive || secondThread.isAlive)
        threadFailure.get()?.let { throw AssertionError("A store thread failed.", it) }
        assertFalse("The second store must wait for the first read-modify-write transaction.", secondCommittedBeforeRelease)
        assertTrue(requireNotNull(firstResult.get()).persisted)
        assertTrue(requireNotNull(secondResult.get()).persisted)
        val finalStore = ExternalAudioOpenRequestQueueStore(
            readEncoded = { stored },
            commitEncoded = { false },
            parseUri = ::TestUri,
            processCacheKey = "$cacheKey-verification",
        )
        assertEquals(listOf(requestA.id, requestB.id), requireNotNull(finalStore.load()).pendingRequests.map { it.id })
    }

    @Test
    fun enqueueRejectsAnOversizedUriWithoutPersistingIt() {
        val writes = mutableListOf<String>()
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { writes.lastOrNull() },
            commitEncoded = { writes += it; true },
            parseUri = ::TestUri,
        )

        val update = store.enqueue(
            ExternalAudioOpenRequestQueueSnapshot(),
            ExternalAudioOpenRequest(
                id = id(1),
                uri = TestUri("content://media/" + "a".repeat(2_100)),
            ),
        )

        assertEquals(ExternalAudioOpenRequestQueueRejection.UriTooLarge, update.rejection)
        assertTrue(update.snapshot.pendingRequests.isEmpty())
        assertTrue("Oversized URIs must not reach durable preferences.", writes.isEmpty())
    }

    @Test
    fun failureMessagesAreBoundedBeforeTheyEnterSavedQueueState() {
        val request = request(1, "content://media/external/audio/1")

        val failed = markExternalAudioOpenRequestFailed(
            pending = listOf(request),
            requestId = request.id,
            attempt = request.attempt,
            failureKind = ExternalAudioOpenFailureKind.Import,
            message = "ошибка".repeat(1_000),
        )

        assertTrue(requireNotNull(failed.single().failureMessage).toByteArray(Charsets.UTF_8).size <= 1_024)
    }

    @Test
    fun acknowledgementHistoryRemainsFiniteAfterManyCompletedRequests() {
        var stored: String? = null
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { stored },
            commitEncoded = { stored = it; true },
            parseUri = ::TestUri,
        )
        var snapshot = ExternalAudioOpenRequestQueueSnapshot()

        repeat(300) { index ->
            val queued = store.enqueue(snapshot, request(index + 1, "content://media/external/audio/$index"))
            assertTrue(queued.persisted)
            val acknowledged = store.replace(queued.snapshot, emptyList())
            assertTrue(acknowledged.persisted)
            snapshot = acknowledged.snapshot
        }

        assertTrue("Acknowledged IDs must have a finite retention limit.", snapshot.acknowledgedRequestIds.size <= 256)
        assertTrue("Acknowledged URI suppression must have a finite retention limit.", snapshot.acknowledgedRequestUris.size <= 32)
        val encodedAcknowledgementBytes = requireNotNull(stored).lineSequence().drop(1).sumOf { utf8Size(it) + 1 }
        assertTrue("All acknowledgement records must fit the explicit byte budget.", encodedAcknowledgementBytes <= MAX_EXTERNAL_AUDIO_ACKNOWLEDGED_BYTES)
        assertTrue("The persisted acknowledgement budget must remain bounded.", requireNotNull(stored).toByteArray(Charsets.UTF_8).size <= MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES)
    }

    @Test
    fun encodedByteCapRejectsOnlyTheNewArrivalAndKeepsTheAcceptedPrefix() {
        var stored: String? = null
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { stored },
            commitEncoded = { stored = it; true },
            parseUri = ::TestUri,
        )
        var snapshot = ExternalAudioOpenRequestQueueSnapshot()
        var acceptedBeforeRejection: List<String>? = null
        var rejection: ExternalAudioOpenRequestQueueRejection? = null

        for (index in 0 until MAX_EXTERNAL_AUDIO_PENDING_REQUESTS) {
            val candidate = request(index + 1, "content://media/" + "/a".repeat(980) + index)
            val update = store.enqueue(snapshot, candidate)
            if (update.rejection != null) {
                rejection = update.rejection
                acceptedBeforeRejection = snapshot.pendingRequests.map { it.id }
                assertEquals(acceptedBeforeRejection, update.snapshot.pendingRequests.map { it.id })
                break
            }
            snapshot = update.snapshot
        }

        assertEquals(ExternalAudioOpenRequestQueueRejection.SnapshotTooLarge, rejection)
        assertTrue(snapshot.pendingRequests.size < MAX_EXTERNAL_AUDIO_PENDING_REQUESTS)
        assertTrue(requireNotNull(stored).toByteArray(Charsets.UTF_8).size <= MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES)
    }

    @Test
    fun actualQueueCommitRunsOffTheCallingThreadWhenDispatchedThroughIo() = runBlocking {
        val caller = Thread.currentThread()
        var commitThread: Thread? = null
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { null },
            commitEncoded = { commitThread = Thread.currentThread(); true },
            parseUri = ::TestUri,
        )

        val update = withExternalAudioQueueIo {
            store.enqueue(ExternalAudioOpenRequestQueueSnapshot(), request(1, "content://media/external/audio/1"))
        }

        assertTrue(update.persisted)
        assertNotSame(caller, commitThread)
    }

    @Test
    fun corruptPreferencesAreNotSilentlyReplacedWithAnEmptyQueue() {
        val corruptValue = "not-a-valid-queue-snapshot"
        val writes = mutableListOf<String>()
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { corruptValue },
            commitEncoded = { writes += it; true },
            parseUri = ::TestUri,
        )

        val update = store.restore(savedStateRequests = null, currentIntentRequest = null)

        assertFalse("Corrupt persisted state must not be reported as a successfully restored empty queue.", update.persisted)
        assertTrue("The unreadable original value must be preserved for recovery.", writes.isEmpty())
    }

    @Test
    fun oversizedPreferencesAreDistinguishedFromAnEmptyQueueAndPreserved() {
        val oversizedValue = "x".repeat(MAX_EXTERNAL_AUDIO_QUEUE_SNAPSHOT_BYTES + 1)
        val writes = mutableListOf<String>()
        val store = ExternalAudioOpenRequestQueueStore(
            readEncoded = { oversizedValue },
            commitEncoded = { writes += it; true },
            parseUri = ::TestUri,
        )

        val update = store.restore(savedStateRequests = null, currentIntentRequest = null)

        assertEquals(ExternalAudioOpenQueueStoreProblem.Oversized, update.storeProblem)
        assertFalse(update.persisted)
        assertTrue("Oversized persisted state must remain available for recovery.", writes.isEmpty())
    }

    private fun request(index: Int, uri: String) = ExternalAudioOpenRequest(id(index), TestUri(uri))

    private fun id(index: Int): String =
        UUID.fromString("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}").toString()
}
