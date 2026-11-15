package com.neiro.aftertone

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import java.util.LinkedHashMap

internal data class ExternalAudioOpenNotification(
    val requestId: String,
    val message: String,
)

internal class ExternalAudioOpenNotificationQueue {
    private val stateLock = Any()
    private val deliveryMutex = Mutex()
    private val mutablePending = MutableStateFlow<List<ExternalAudioOpenNotification>>(emptyList())
    private val deliveredRequestIds = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > 128
    }

    val pending: StateFlow<List<ExternalAudioOpenNotification>> = mutablePending.asStateFlow()

    fun enqueue(requestId: String, message: String) {
        synchronized(stateLock) {
            val current = mutablePending.value
            if (requestId !in deliveredRequestIds && current.none { it.requestId == requestId }) {
                mutablePending.value = current + ExternalAudioOpenNotification(requestId, message)
            }
        }
    }

    private fun markDelivered(requestId: String) {
        synchronized(stateLock) {
            mutablePending.value = mutablePending.value.filterNot { it.requestId == requestId }
            deliveredRequestIds[requestId] = Unit
        }
    }

    suspend fun deliverNext(showSnackbar: suspend (String) -> Unit) {
        deliveryMutex.lock()
        try {
            val notification = mutablePending.value.firstOrNull() ?: return
            // Snackbar display suspends; acknowledge first so Activity cancellation cannot replay it.
            markDelivered(notification.requestId)
            showSnackbar(notification.message)
        } finally {
            deliveryMutex.unlock()
        }
    }

    suspend fun deliverContinuously(showSnackbar: suspend (String) -> Unit) {
        pending.collect { notifications ->
            if (notifications.isNotEmpty()) deliverNext(showSnackbar)
        }
    }
}

internal object ExternalAudioOpenNotifications {
    private val queue = ExternalAudioOpenNotificationQueue()

    val pending: StateFlow<List<ExternalAudioOpenNotification>> = queue.pending

    fun enqueue(requestId: String, message: String) = queue.enqueue(requestId, message)

    suspend fun deliverNext(showSnackbar: suspend (String) -> Unit) = queue.deliverNext(showSnackbar)

    suspend fun deliverContinuously(showSnackbar: suspend (String) -> Unit) = queue.deliverContinuously(showSnackbar)
}
