package com.neiro.aftertone

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExternalAudioOpenRequestRetryTest {
    @Test
    fun freshIntentForSamePendingSourceGetsNewRequestIdAndAttemptZero() {
        val previous = ExternalAudioOpenRequest(
            id = "stable-request-id",
            uri = TestUri("content://media/external/audio/17"),
            attempt = 3,
        )

        val retry = newExternalAudioOpenRequest(
            uri = TestUri("content://media/external/audio/17"),
        )

        assertNotEquals(previous.id, retry.id)
        assertEquals(0, retry.attempt)
        assertEquals(previous.uri.toString(), retry.uri.toString())
    }

    @Test
    fun openingDifferentSourceStartsNewRequestIdentityAndAttempt() {
        val previous = ExternalAudioOpenRequest(
            id = "old-request-id",
            uri = TestUri("content://media/external/audio/17"),
            attempt = 3,
        )

        val next = newExternalAudioOpenRequest(
            uri = TestUri("content://media/external/audio/18"),
        )

        assertNotEquals(previous.id, next.id)
        assertEquals(0, next.attempt)
    }

    @Test
    fun freshSameSourceIntentDoesNotInheritMaximumAttempt() {
        val previous = ExternalAudioOpenRequest(
            id = "stable-request-id",
            uri = TestUri("content://media/external/audio/17"),
            attempt = Int.MAX_VALUE,
        )

        val reopened = newExternalAudioOpenRequest(previous.uri)

        assertNotEquals(previous.id, reopened.id)
        assertEquals(0, reopened.attempt)
    }

    @Test
    fun retryAtMaximumAttemptStaysFailedInsteadOfWrappingOrReplaying() {
        val failed = ExternalAudioOpenRequest(
            id = "stable-request-id",
            uri = TestUri("content://media/external/audio/17"),
            attempt = Int.MAX_VALUE,
            failureKind = ExternalAudioOpenFailureKind.Import,
            failureMessage = "Still unavailable",
        )

        val retry = resolveExternalAudioOpenFailure(
            pending = listOf(failed),
            requestId = failed.id,
            attempt = Int.MAX_VALUE,
            choice = ExternalAudioOpenFailureChoice.Retry,
        )

        assertEquals(listOf(failed), retry)
    }
}
