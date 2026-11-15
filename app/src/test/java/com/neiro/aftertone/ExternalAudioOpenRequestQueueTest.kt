package com.neiro.aftertone

import android.net.TestUri
import com.neiro.aftertone.model.Track
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioOpenRequestQueueTest {
    @Test
    fun differentIncomingSourceDoesNotReplaceAnAlreadyPendingRequest() {
        val sourceA = TestUri("content://media/external/audio/101")
        val sourceB = TestUri("content://media/external/audio/202")
        val first = enqueueExternalAudioOpenRequest(emptyList(), sourceA)
        val firstId = first.single().id

        val withSecondRequest = enqueueExternalAudioOpenRequest(first, sourceB)

        assertEquals(listOf(sourceA.toString(), sourceB.toString()), withSecondRequest.map { it.uri.toString() })
        assertEquals(firstId, withSecondRequest.first().id)
        assertEquals(2, withSecondRequest.size)

        val afterFirstHandled = removeExternalAudioOpenRequest(withSecondRequest, firstId)
        assertEquals(listOf(sourceB.toString()), afterFirstHandled.map { it.uri.toString() })
    }

    @Test
    fun freshSameSourceRequestAppendsNewIdentityWithoutChangingPendingAttempts() {
        val sourceA = TestUri("content://media/external/audio/101")
        val sourceB = TestUri("content://media/external/audio/202")
        val withTwoRequests = enqueueExternalAudioOpenRequest(
            enqueueExternalAudioOpenRequest(emptyList(), sourceA),
            sourceB,
        )
        val firstId = withTwoRequests.first().id
        val secondId = withTwoRequests.last().id

        val retried = enqueueExternalAudioOpenRequest(
            withTwoRequests,
            TestUri("content://media/external/audio/202"),
        )

        assertEquals(firstId, retried.first().id)
        assertEquals(0, retried.first().attempt)
        assertEquals(secondId, retried[1].id)
        assertEquals(0, retried[1].attempt)
        assertEquals(3, retried.size)
        assertNotEquals(secondId, retried.last().id)
        assertEquals("content://media/external/audio/202", retried.last().uri.toString())
        assertEquals(0, retried.last().attempt)
    }

    @Test
    fun incomingSecondSourceDoesNotCancelFirstImportOrItsHandoff() = runBlocking {
        val sourceA = TestUri("content://media/external/audio/101")
        val sourceB = TestUri("content://media/external/audio/202")
        var pending = enqueueExternalAudioOpenRequest(emptyList(), sourceA)
        val requestA = pending.first()
        val importStarted = CompletableDeferred<Unit>()
        val finishImport = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var importCount = 0

        val openA = async(start = CoroutineStart.UNDISPATCHED) {
            completeExternalAudioOpen(
                requestId = requestA.id,
                onHandled = { requestId ->
                    events += "handled:A"
                    pending = removeExternalAudioOpenRequest(pending, requestId)
                },
                importAndPersist = {
                    importCount++
                    importStarted.complete(Unit)
                    finishImport.await()
                    Track("track-A", "A", "Test", filePath = "/test/A.mp3")
                },
                afterPersist = { track -> events += "handoff:${track.id}" },
            )
        }
        importStarted.await()

        pending = enqueueExternalAudioOpenRequest(pending, sourceB)
        assertEquals(requestA.id, pending.first().id)
        assertEquals(2, pending.size)

        finishImport.complete(Unit)
        assertEquals("track-A", openA.await().id)
        assertEquals(listOf("handoff:track-A", "handled:A"), events)
        assertEquals(1, importCount)
        assertEquals(listOf(sourceB.toString()), pending.map { it.uri.toString() })
    }

    @Test
    fun failedHeadCanRetryWithSameIdentityThenReleaseQueuedRequest() = runBlocking {
        val sourceA = TestUri("content://media/external/audio/301")
        val sourceB = TestUri("content://media/external/audio/302")
        var pending = enqueueExternalAudioOpenRequest(
            enqueueExternalAudioOpenRequest(emptyList(), sourceA),
            sourceB,
        )
        val requestA = pending.first()
        var importCount = 0

        val failedAttempt = runCatching {
            completeExternalAudioOpen(
                requestId = requestA.id,
                onHandled = { requestId ->
                    pending = removeExternalAudioOpenRequest(pending, requestId, requestA.attempt)
                },
                importAndPersist = {
                    importCount++
                    throw IOException("The source is temporarily unavailable.")
                },
                afterPersist = {},
            )
        }

        assertTrue("A failed import must not acknowledge or remove the head request.", failedAttempt.isFailure)
        assertEquals(listOf(sourceA.toString(), sourceB.toString()), pending.map { it.uri.toString() })

        pending = markExternalAudioOpenRequestFailed(
            pending = pending,
            requestId = requestA.id,
            attempt = requestA.attempt,
            failureKind = ExternalAudioOpenFailureKind.Import,
            message = "Не удалось импортировать аудиофайл.",
        )
        assertEquals(ExternalAudioOpenFailureKind.Import, pending.first().failureKind)
        pending = resolveExternalAudioOpenFailure(
            pending = pending,
            requestId = requestA.id,
            attempt = requestA.attempt,
            choice = ExternalAudioOpenFailureChoice.Retry,
        )
        val retry = pending.first()
        assertEquals(requestA.id, retry.id)
        assertEquals(requestA.uri.toString(), retry.uri.toString())
        assertEquals(1, retry.attempt)
        assertEquals(null, retry.failureKind)
        assertEquals(sourceB.toString(), pending.last().uri.toString())

        val retriedTrack = completeExternalAudioOpen(
            requestId = retry.id,
            onHandled = { requestId ->
                pending = removeExternalAudioOpenRequest(pending, requestId, retry.attempt)
            },
            importAndPersist = {
                importCount++
                Track("track-retried-A", "A", "Test", filePath = "/test/A.mp3")
            },
            afterPersist = {},
        )

        assertEquals("track-retried-A", retriedTrack.id)
        assertEquals(2, importCount)
        assertEquals(listOf(sourceB.toString()), pending.map { it.uri.toString() })
    }

    @Test
    fun discardingFailedHeadAdvancesToAndRunsQueuedRequest() = runBlocking {
        val sourceA = TestUri("content://media/external/audio/401")
        val sourceB = TestUri("content://media/external/audio/402")
        var pending = enqueueExternalAudioOpenRequest(
            enqueueExternalAudioOpenRequest(emptyList(), sourceA),
            sourceB,
        )
        val requestA = pending.first()
        val failedAttempt = runCatching {
            completeExternalAudioOpen(
                requestId = requestA.id,
                onHandled = { requestId -> pending = removeExternalAudioOpenRequest(pending, requestId, requestA.attempt) },
                importAndPersist = { throw IOException("The source is permanently unavailable.") },
                afterPersist = {},
            )
        }
        assertTrue(failedAttempt.isFailure)
        pending = markExternalAudioOpenRequestFailed(
            pending,
            requestA.id,
            requestA.attempt,
            ExternalAudioOpenFailureKind.Import,
            "Не удалось импортировать аудиофайл.",
        )

        pending = resolveExternalAudioOpenFailure(
            pending,
            requestA.id,
            requestA.attempt,
            ExternalAudioOpenFailureChoice.Skip,
        )
        assertEquals(listOf(sourceB.toString()), pending.map { it.uri.toString() })
        val requestB = pending.first()
        val trackB = completeExternalAudioOpen(
            requestId = requestB.id,
            onHandled = { requestId -> pending = removeExternalAudioOpenRequest(pending, requestId, requestB.attempt) },
            importAndPersist = { Track("track-B", "B", "Test", filePath = "/test/B.mp3") },
            afterPersist = {},
        )

        assertEquals("track-B", trackB.id)
        assertTrue("Skipping the failed head must let B complete and empty the queue.", pending.isEmpty())
    }

    @Test
    fun staleFailureSkipAndAcknowledgementCannotMutateANewerAttempt() {
        val sourceA = TestUri("content://media/external/audio/501")
        val sourceB = TestUri("content://media/external/audio/502")
        val pending = enqueueExternalAudioOpenRequest(
            enqueueExternalAudioOpenRequest(emptyList(), sourceA),
            sourceB,
        )
        val original = pending.first()
        val failed = markExternalAudioOpenRequestFailed(
            pending,
            original.id,
            original.attempt,
            ExternalAudioOpenFailureKind.Import,
            "failed",
        )
        val retry = resolveExternalAudioOpenFailure(
            failed,
            original.id,
            original.attempt,
            ExternalAudioOpenFailureChoice.Retry,
        )

        assertEquals(1, retry.first().attempt)
        assertEquals(retry, markExternalAudioOpenRequestFailed(
            retry,
            original.id,
            original.attempt,
            ExternalAudioOpenFailureKind.Import,
            "stale failure",
        ))
        assertEquals(retry, resolveExternalAudioOpenFailure(
            retry,
            original.id,
            original.attempt,
            ExternalAudioOpenFailureChoice.Skip,
        ))
        assertEquals(retry, removeExternalAudioOpenRequest(retry, original.id, original.attempt))
        assertFalse(retry.first().failureKind != null)
    }

    @Test
    fun failedHeadAndQueuedSuccessorSurviveActivityStateRestoration() {
        val sourceA = TestUri("content://media/external/audio/601")
        val sourceB = TestUri("content://media/external/audio/602")
        val queued = enqueueExternalAudioOpenRequest(
            enqueueExternalAudioOpenRequest(emptyList(), sourceA),
            sourceB,
        )
        val failed = markExternalAudioOpenRequestFailed(
            queued,
            queued.first().id,
            queued.first().attempt,
            ExternalAudioOpenFailureKind.Import,
            "Не удалось импортировать выбранный файл.",
        )

        val restored = requireNotNull(
            restoreExternalAudioOpenRequests(
                requestIds = failed.map { it.id },
                uriStrings = failed.map { it.uri.toString() },
                attempts = failed.map { it.attempt },
                failureKinds = failed.map { it.failureKind?.name.orEmpty() },
                failureMessages = failed.map { it.failureMessage.orEmpty() },
                parseUri = { TestUri(it) },
            ),
        )

        assertEquals(failed.map { it.id }, restored.map { it.id })
        assertEquals(failed.map { it.uri.toString() }, restored.map { it.uri.toString() })
        assertEquals(failed.map { it.attempt }, restored.map { it.attempt })
        assertEquals(failed.map { it.failureKind }, restored.map { it.failureKind })
        assertEquals(failed.map { it.failureMessage }, restored.map { it.failureMessage })
        assertEquals(ExternalAudioOpenFailureKind.Import, restored.first().failureKind)
        assertEquals(sourceB.toString(), restored.last().uri.toString())
        assertEquals(null, restoreExternalAudioOpenRequests(null, null, null, null, null))
    }
}
