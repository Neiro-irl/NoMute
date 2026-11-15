package com.neiro.aftertone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ExternalAudioOpenNotificationQueueTest {
    @Test
    fun cancelledSnackbarHostDoesNotReplayNotificationInRecreatedActivity() = runBlocking {
        val queue = ExternalAudioOpenNotificationQueue()
        val requestId = UUID.randomUUID().toString()
        val message = "Трек добавлен в медиатеку и воспроизводится."
        val snackbarStarted = CompletableDeferred<Unit>()
        val waitForHostDismissal = CompletableDeferred<Unit>()
        queue.enqueue(requestId, message)

        val oldActivityHost = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.deliverNext { shownMessage ->
                assertEquals(message, shownMessage)
                snackbarStarted.complete(Unit)
                waitForHostDismissal.await()
            }
        }
        snackbarStarted.await()
        oldActivityHost.cancelAndJoin()

        assertTrue("A notification is acknowledged when delivery starts, before the suspending snackbar call.", queue.pending.value.isEmpty())
        queue.enqueue(requestId, message)
        assertTrue("An already-delivered request ID cannot be queued again in the same process.", queue.pending.value.isEmpty())

        val messagesShownByRecreatedActivity = mutableListOf<String>()
        queue.deliverNext { shownMessage -> messagesShownByRecreatedActivity += shownMessage }

        assertTrue("The recreated Activity must not replay the cancelled snackbar.", messagesShownByRecreatedActivity.isEmpty())
        assertTrue(queue.pending.value.isEmpty())
    }

    @Test
    fun continuousCollectorDeliversNextMessageAfterHeadIsRemovedDuringSuspendingDisplay() = runBlocking {
        val queue = ExternalAudioOpenNotificationQueue()
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        val firstMessage = "Первый трек добавлен."
        val secondMessage = "Второй трек добавлен."
        val firstSnackbarStarted = CompletableDeferred<Unit>()
        val finishFirstSnackbar = CompletableDeferred<Unit>()
        val secondSnackbarStarted = CompletableDeferred<Unit>()
        val shownMessages = mutableListOf<String>()
        queue.enqueue(firstId, firstMessage)

        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.deliverContinuously { message ->
                shownMessages += message
                if (message == firstMessage) {
                    firstSnackbarStarted.complete(Unit)
                    finishFirstSnackbar.await()
                } else if (message == secondMessage) {
                    secondSnackbarStarted.complete(Unit)
                }
            }
        }
        firstSnackbarStarted.await()
        assertTrue("The first request is dequeued while its snackbar host remains suspended.", queue.pending.value.isEmpty())

        queue.enqueue(secondId, secondMessage)
        finishFirstSnackbar.complete(Unit)
        secondSnackbarStarted.await()

        assertEquals(listOf(firstMessage, secondMessage), shownMessages)
        collector.cancelAndJoin()
    }
}
