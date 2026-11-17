package com.neiro.aftertone

import android.content.Intent
import android.os.BadParcelableException
import android.net.TestUri
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioOpenIntentValidationTest {
    @Test
    fun unsupportedActionDoesNotReadMimeOrRequestIdMetadata() {
        val metadataReads = AtomicInteger()

        val result = externalAudioOpenIntentFromValues(
            action = Intent.ACTION_SEND,
            uri = TestUri("content://media/external/audio/1"),
            readDeclaredMimeType = { metadataReads.incrementAndGet(); "audio/mpeg" },
            readRequestIdExtra = { metadataReads.incrementAndGet(); null },
        )

        assertNull(result)
        assertEquals(0, metadataReads.get())
    }

    @Test
    fun missingUriDoesNotReadMimeOrRequestIdMetadata() {
        val metadataReads = AtomicInteger()

        val result = externalAudioOpenIntentFromValues(
            action = Intent.ACTION_VIEW,
            uri = null,
            readDeclaredMimeType = { metadataReads.incrementAndGet(); "audio/mpeg" },
            readRequestIdExtra = { metadataReads.incrementAndGet(); null },
        )

        assertNull(result)
        assertEquals(0, metadataReads.get())
    }

    @Test
    fun unsupportedUriSchemeDoesNotReadMimeOrRequestIdMetadata() {
        val metadataReads = AtomicInteger()

        val result = externalAudioOpenIntentFromValues(
            action = Intent.ACTION_VIEW,
            uri = TestUri("https://example.invalid/audio.mp3", "https"),
            readDeclaredMimeType = { metadataReads.incrementAndGet(); "audio/mpeg" },
            readRequestIdExtra = { metadataReads.incrementAndGet(); null },
        )

        assertNull(result)
        assertEquals(0, metadataReads.get())
    }

    @Test
    fun fileUriIsRejectedBeforeReadingMimeOrRequestIdMetadata() {
        val metadataReads = AtomicInteger()

        val result = externalAudioOpenIntentFromValues(
            action = Intent.ACTION_VIEW,
            uri = TestUri("file:///sdcard/Music/track.mp3", "file"),
            readDeclaredMimeType = { metadataReads.incrementAndGet(); "audio/mpeg" },
            readRequestIdExtra = { metadataReads.incrementAndGet(); null },
        )

        assertNull(result)
        assertEquals(0, metadataReads.get())
    }

    @Test
    fun oversizedDeclaredMimeTypeIsRejectedBeforeReadingRequestId() {
        val requestIdReads = AtomicInteger()

        val result = externalAudioOpenIntentFromValues(
            action = Intent.ACTION_VIEW,
            uri = TestUri("content://provider/document/oversized-type"),
            readDeclaredMimeType = { "a".repeat(MAX_EXTERNAL_AUDIO_DECLARED_MIME_TYPE_BYTES + 1) },
            readRequestIdExtra = { requestIdReads.incrementAndGet(); null },
        )

        assertNull(result)
        assertEquals(0, requestIdReads.get())
    }

    @Test
    fun invalidSuppliedRequestIdIsReplacedWithCanonicalInternalId() {
        val result = requireNotNull(
            externalAudioOpenIntentFromValues(
                action = Intent.ACTION_VIEW,
                uri = TestUri("content://media/external/audio/1"),
                readDeclaredMimeType = { null },
                readRequestIdExtra = { "1-1-1-1-1" },
            ),
        )

        assertFalse(result.requestIdWasSupplied)
        assertTrue(isCanonicalExternalAudioOpenRequestId(result.request.id))
    }

    @Test
    fun malformedRequestIdExtraFallsBackToGeneratedCanonicalId() {
        val result = requireNotNull(
            externalAudioOpenIntentFromValues(
                action = Intent.ACTION_VIEW,
                uri = TestUri("content://media/external/audio/malformed-extra"),
                readDeclaredMimeType = { "audio/mpeg" },
                readRequestIdExtra = { throw BadParcelableException("malformed extra") },
            ),
        )

        assertFalse(result.requestIdWasSupplied)
        assertTrue(isCanonicalExternalAudioOpenRequestId(result.request.id))
    }

    @Test
    fun nonStringRequestIdExtraFallsBackAndKeepsSanitizedIntent() {
        val prepared = prepareExternalAudioOpenIntent(
            snapshot = ExternalAudioIntentSnapshot(
                action = Intent.ACTION_VIEW,
                uri = TestUri("content://provider/document/non-string-request-id"),
                declaredMimeType = "audio/mpeg",
                intentFlags = 0,
            ),
            readRequestIdExtra = { throw ClassCastException("request-ID extra is not a String") },
            createSanitizedIntent = { snapshot -> snapshot.action },
        )
        val parsedRequest = requireNotNull(prepared.request)

        assertFalse(parsedRequest.requestIdWasSupplied)
        assertTrue(parsedRequest.requestIdExtraReadFailed)
        assertTrue(isCanonicalExternalAudioOpenRequestId(parsedRequest.request.id))
        assertEquals(Intent.ACTION_VIEW, prepared.sanitizedIntent)
    }

    @Test
    fun unexpectedRequestIdReaderRuntimeFailureIsNotSwallowed() {
        val expected = IllegalStateException("unrelated request-ID read failure")

        val actual = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            externalAudioOpenIntentFromValues(
                action = Intent.ACTION_VIEW,
                uri = TestUri("content://media/external/audio/unrelated-failure"),
                readDeclaredMimeType = { "audio/mpeg" },
                readRequestIdExtra = { throw expected },
            )
        }

        org.junit.Assert.assertSame(expected, actual)
    }

    @Test
    fun actionViewFlowDoesNotSetOrMutateTheUntrustedSourceIntent() {
        val mainActivity = File("src/main/java/com/neiro/aftertone/MainActivity.kt").readText()
        val onNewIntent = mainActivity.substringAfter("override fun onNewIntent(intent: Intent)")
            .substringBefore("override fun onResume()")
        val binder = mainActivity.substringAfter("private fun bindCurrentIntentRequestId(")
            .substringBefore("private fun updatePendingExternalAudioRequests(")

        assertFalse("onNewIntent must not retain its untrusted source Intent.", onNewIntent.contains("setIntent(intent)"))
        assertFalse("The request-ID binder must not mutate the source Intent.", binder.contains("source.putExtra("))
        assertFalse("The request-ID binder must not retain the source Intent.", binder.contains("setIntent(source)"))
        assertTrue("ACTION_VIEW intake must prepare a sanitized Intent copy.", mainActivity.contains("prepareExternalAudioOpenIntent"))
    }

    @Test
    fun validProviderUriWithNoDeclaredMimeTypeRemainsEligibleForImport() {
        val metadataReads = AtomicInteger()
        val result = externalAudioOpenIntentFromValues(
            action = Intent.ACTION_VIEW,
            uri = TestUri("content://provider/document/42"),
            readDeclaredMimeType = { metadataReads.incrementAndGet(); null },
            readRequestIdExtra = { null },
        )

        assertNotNull(result)
        assertEquals(1, metadataReads.get())
        assertTrue(isCanonicalExternalAudioOpenRequestId(requireNotNull(result).request.id))
    }

    @Test
    fun invalidExtraCanBeRetriedAndPassedToTheImportCallback() {
        kotlinx.coroutines.runBlocking {
            val parsed = requireNotNull(
                externalAudioOpenIntentFromValues(
                    action = Intent.ACTION_VIEW,
                    uri = TestUri("content://provider/document/43"),
                    readDeclaredMimeType = { null },
                    readRequestIdExtra = { "1-1-1-1-1" },
                ),
            )
            assertFalse(parsed.requestIdWasSupplied)
            assertTrue(isCanonicalExternalAudioOpenRequestId(parsed.request.id))

            var stored: String? = null
            val store = ExternalAudioOpenRequestQueueStore(
                readEncoded = { stored },
                commitEncoded = { stored = it; true },
                parseUri = ::TestUri,
            )
            val queued = store.enqueue(ExternalAudioOpenRequestQueueSnapshot(), parsed.request)
            assertTrue(queued.persisted)
            val failedRequests = markExternalAudioOpenRequestFailed(
                pending = queued.snapshot.pendingRequests,
                requestId = parsed.request.id,
                attempt = parsed.request.attempt,
                failureKind = ExternalAudioOpenFailureKind.Import,
                message = "temporary failure",
            )
            val failedUpdate = store.replace(queued.snapshot, failedRequests)
            assertTrue(failedUpdate.persisted)
            val retryRequests = resolveExternalAudioOpenFailure(
                pending = failedUpdate.snapshot.pendingRequests,
                requestId = parsed.request.id,
                attempt = parsed.request.attempt,
                choice = ExternalAudioOpenFailureChoice.Retry,
            )
            val retryUpdate = store.replace(failedUpdate.snapshot, retryRequests)
            assertTrue(retryUpdate.persisted)
            val retriedRequest = retryUpdate.snapshot.pendingRequests.single()
            var repositoryReceivedId: String? = null
            var handledId: String? = null

            val importedTrack = completeExternalAudioOpen(
                requestId = retriedRequest.id,
                onHandled = { handledId = it },
                importAndPersist = {
                    repositoryReceivedId = retriedRequest.id
                    com.neiro.aftertone.model.Track("track-43", "Audio", "Test", filePath = "/test/audio.mp3")
                },
                afterPersist = {},
            )

            assertEquals(parsed.request.id, retriedRequest.id)
            assertEquals(1, retriedRequest.attempt)
            assertEquals(parsed.request.id, repositoryReceivedId)
            assertEquals(parsed.request.id, handledId)
            assertEquals("track-43", importedTrack.id)
        }
    }
}
