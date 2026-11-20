package com.neiro.aftertone

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.neiro.aftertone.playback.ACTION_OPEN_NOW_PLAYING
import com.neiro.aftertone.playback.nextNowPlayingLaunchRequestId
import com.neiro.aftertone.ui.theme.AftertonePalette
import com.neiro.aftertone.ui.theme.AftertoneTheme
import com.neiro.aftertone.ui.theme.SharedPreferencesThemePreferenceStorage
import com.neiro.aftertone.ui.theme.ThemeSelectionRepository
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ExternalAudioOpenFailureKind {
    Import,
    LibraryLoad,
    RestoreGate,
}

internal enum class ExternalAudioOpenFailureChoice {
    Retry,
    Skip,
}

internal data class ExternalAudioOpenRequest(
    val id: String,
    val uri: Uri,
    val attempt: Int = 0,
    val failureKind: ExternalAudioOpenFailureKind? = null,
    val failureMessage: String? = null,
)

internal data class ExternalAudioOpenIntent(
    val request: ExternalAudioOpenRequest,
    val requestIdWasSupplied: Boolean,
    val requestIdExtraReadFailed: Boolean = false,
)

internal data class ExternalAudioIntentSnapshot(
    val action: String?,
    val uri: Uri?,
    val declaredMimeType: String?,
    val intentFlags: Int,
)

internal data class PreparedExternalAudioOpenIntent<T>(
    val request: ExternalAudioOpenIntent?,
    val sanitizedIntent: T?,
    val intentFlags: Int,
)

internal fun <T> prepareExternalAudioOpenIntent(
    snapshot: ExternalAudioIntentSnapshot?,
    readRequestIdExtra: () -> String?,
    createSanitizedIntent: (ExternalAudioIntentSnapshot) -> T,
): PreparedExternalAudioOpenIntent<T> {
    if (snapshot == null) return PreparedExternalAudioOpenIntent(null, null, 0)
    val sanitizedIntent = snapshot.takeIf { it.action == Intent.ACTION_VIEW }?.let(createSanitizedIntent)
    val request = externalAudioOpenIntentFromValues(
        action = snapshot.action,
        uri = snapshot.uri,
        readDeclaredMimeType = { snapshot.declaredMimeType },
        readRequestIdExtra = readRequestIdExtra,
    )
    return PreparedExternalAudioOpenIntent(request, sanitizedIntent, snapshot.intentFlags)
}

internal fun newExternalAudioOpenRequest(uri: Uri): ExternalAudioOpenRequest =
    ExternalAudioOpenRequest(id = UUID.randomUUID().toString(), uri = uri)

internal fun enqueueExternalAudioOpenRequest(
    pending: List<ExternalAudioOpenRequest>,
    uri: Uri,
): List<ExternalAudioOpenRequest> = pending + newExternalAudioOpenRequest(uri)

internal fun removeExternalAudioOpenRequest(
    pending: List<ExternalAudioOpenRequest>,
    requestId: String,
    attempt: Int? = null,
): List<ExternalAudioOpenRequest> {
    val head = pending.firstOrNull() ?: return pending
    if (head.id != requestId || (attempt != null && head.attempt != attempt)) return pending
    return pending.drop(1)
}

internal fun markExternalAudioOpenRequestFailed(
    pending: List<ExternalAudioOpenRequest>,
    requestId: String,
    attempt: Int,
    failureKind: ExternalAudioOpenFailureKind,
    message: String,
): List<ExternalAudioOpenRequest> {
    val head = pending.firstOrNull() ?: return pending
    if (head.id != requestId || head.attempt != attempt) return pending
    return listOf(
        head.copy(
            failureKind = failureKind,
            failureMessage = utf8Prefix(message, MAX_EXTERNAL_AUDIO_FAILURE_MESSAGE_BYTES),
        ),
    ) + pending.drop(1)
}

internal fun resolveExternalAudioOpenFailure(
    pending: List<ExternalAudioOpenRequest>,
    requestId: String,
    attempt: Int,
    choice: ExternalAudioOpenFailureChoice,
): List<ExternalAudioOpenRequest> {
    val head = pending.firstOrNull() ?: return pending
    if (head.id != requestId || head.attempt != attempt || head.failureKind == null) return pending
    return when (choice) {
        ExternalAudioOpenFailureChoice.Retry -> if (head.attempt == Int.MAX_VALUE) {
            pending
        } else {
            listOf(head.copy(attempt = head.attempt + 1, failureKind = null, failureMessage = null)) + pending.drop(1)
        }
        ExternalAudioOpenFailureChoice.Skip -> pending.drop(1)
    }
}

internal fun restoreExternalAudioOpenRequests(
    requestIds: List<String>?,
    uriStrings: List<String>?,
    attempts: List<Int>?,
    failureKinds: List<String>?,
    failureMessages: List<String>?,
    parseUri: (String) -> Uri = Uri::parse,
): List<ExternalAudioOpenRequest>? {
    if (requestIds == null && uriStrings == null && attempts == null) return null
    if (requestIds == null || uriStrings == null || attempts == null ||
        requestIds.size != uriStrings.size || requestIds.size != attempts.size ||
        requestIds.size > MAX_EXTERNAL_AUDIO_PENDING_REQUESTS ||
        ((failureKinds != null || failureMessages != null) &&
            (failureKinds == null || failureMessages == null ||
                requestIds.size != failureKinds.size || requestIds.size != failureMessages.size))
    ) return emptyList()

    return requestIds.indices.mapNotNull { index ->
        val requestId = requestIds[index]
        val uriString = uriStrings[index]
        if (!isCanonicalExternalAudioOpenRequestId(requestId) ||
            uriString.length > MAX_EXTERNAL_AUDIO_URI_BYTES ||
            utf8Size(uriString) > MAX_EXTERNAL_AUDIO_URI_BYTES
        ) return@mapNotNull null
        val uri = runCatching { parseUri(uriString) }.getOrNull() ?: return@mapNotNull null
        val rawFailureKind = failureKinds?.get(index).orEmpty()
        val decodedFailureKind = ExternalAudioOpenFailureKind.values().firstOrNull { it.name == rawFailureKind }
        val hasUnknownFailureKind = rawFailureKind.isNotEmpty() && decodedFailureKind == null
        if (uri.scheme?.lowercase(Locale.ROOT) != "content") null else ExternalAudioOpenRequest(
            id = requestId,
            uri = uri,
            attempt = attempts[index].coerceAtLeast(0),
            failureKind = decodedFailureKind ?: if (hasUnknownFailureKind) ExternalAudioOpenFailureKind.Import else null,
            failureMessage = failureMessages?.get(index)
                ?.let { utf8Prefix(it, MAX_EXTERNAL_AUDIO_FAILURE_MESSAGE_BYTES) }
                ?.takeIf { it.isNotBlank() }
                ?: if (hasUnknownFailureKind) "Ожидающий запрос требует повторной проверки или пропуска." else null,
        )
    }.distinctBy(ExternalAudioOpenRequest::id)
}

internal fun externalAudioOpenIntentFromValues(
    action: String?,
    uri: Uri?,
    readDeclaredMimeType: () -> String?,
    readRequestIdExtra: () -> String?,
): ExternalAudioOpenIntent? {
    if (action != Intent.ACTION_VIEW || uri == null) return null
    if (uri.scheme?.lowercase(Locale.ROOT) != "content") return null
    val uriString = uri.toString()
    if (uriString.length > MAX_EXTERNAL_AUDIO_URI_BYTES || utf8Size(uriString) > MAX_EXTERNAL_AUDIO_URI_BYTES) return null

    val declaredMimeType = readDeclaredMimeType()
    if (declaredMimeType != null &&
        (declaredMimeType.length > MAX_EXTERNAL_AUDIO_DECLARED_MIME_TYPE_BYTES ||
            utf8Size(declaredMimeType) > MAX_EXTERNAL_AUDIO_DECLARED_MIME_TYPE_BYTES)
    ) return null
    val mimeType = declaredMimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
    val allowedMimeType = mimeType == null ||
        mimeType.startsWith("audio/") ||
        mimeType in setOf("application/x-id3", "application/octet-stream", "binary/octet-stream")
    if (!allowedMimeType) return null

    var requestIdExtraReadFailed = false
    val suppliedExtra = try {
        readRequestIdExtra()
    } catch (_: android.os.BadParcelableException) {
        requestIdExtraReadFailed = true
        null
    } catch (_: ClassCastException) {
        requestIdExtraReadFailed = true
        null
    }
    val suppliedId = suppliedExtra?.takeIf(::isCanonicalExternalAudioOpenRequestId)
    return ExternalAudioOpenIntent(
        request = ExternalAudioOpenRequest(id = suppliedId ?: UUID.randomUUID().toString(), uri = uri),
        requestIdWasSupplied = suppliedId != null,
        requestIdExtraReadFailed = requestIdExtraReadFailed,
    )
}

internal fun externalAudioPersistableReadPermissionFlags(intentFlags: Int): Int {
    val requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    return if ((intentFlags and requiredFlags) == requiredFlags) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0
}

class MainActivity : ComponentActivity() {
    private lateinit var externalAudioOpenRequestQueueStore: ExternalAudioOpenRequestQueueStore
    private var externalAudioOpenRequestQueue by mutableStateOf(ExternalAudioOpenRequestQueueSnapshot())
    private var nowPlayingLaunchRequestId by mutableIntStateOf(0)
    private var undurableExternalAudioRequestQueue: ExternalAudioOpenRequestQueueSnapshot? = null
    private var externalAudioQueueRecoveryDialog: AlertDialog? = null
    private val externalAudioQueueMutationMutex = Mutex()
    private val pendingExternalAudioRequests: List<ExternalAudioOpenRequest>
        get() = externalAudioOpenRequestQueue.pendingRequests

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            nowPlayingLaunchRequestId = nextNowPlayingLaunchRequestId(0, intent?.action)
        }
        val requestPreferences = getSharedPreferences(EXTERNAL_AUDIO_OPEN_REQUEST_QUEUE_PREFERENCES, MODE_PRIVATE)
        externalAudioOpenRequestQueueStore = ExternalAudioOpenRequestQueueStore(
            readEncoded = { requestPreferences.getString(EXTERNAL_AUDIO_OPEN_REQUEST_QUEUE_KEY, null) },
            commitEncoded = { encoded -> commitExternalAudioOpenRequestQueue(requestPreferences, encoded) },
            processCacheKey = EXTERNAL_AUDIO_OPEN_REQUEST_QUEUE_PREFERENCES,
            backupCorruptSnapshot = ::backupExternalAudioQueueSnapshot,
        )
        val canProcessExternalAudio = canProcessExternalAudioQueue(BuildConfig.RECOVERY_DIAGNOSTIC_READ_ONLY)
        val savedStateRequests = if (canProcessExternalAudio) savedInstanceState?.let(::restoreExternalAudioRequests) else null
        val currentExternalAudioIntent = if (canProcessExternalAudio) {
            prepareExternalAudioOpenIntent(intent)
        } else {
            PreparedExternalAudioOpenIntent<Intent>(null, null, 0)
        }
        val retainedIntent = currentExternalAudioIntent.sanitizedIntent
        retainedIntent?.let(::setIntent)
        currentExternalAudioIntent.request?.let {
            persistExternalAudioReadPermission(currentExternalAudioIntent.intentFlags, it.request.uri)
        }
        val suppressAcknowledgedUriOnRecreation = shouldSuppressAcknowledgedUriOnRecreation(
            activityRecreated = savedInstanceState != null,
            hasCurrentExternalIntent = currentExternalAudioIntent.request != null,
            requestIdWasSupplied = currentExternalAudioIntent.request?.requestIdWasSupplied == true,
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            val context = this@MainActivity
            val themeRepository = remember(context) {
                ThemeSelectionRepository(SharedPreferencesThemePreferenceStorage(context))
            }
            var selectedPalette by remember(themeRepository) {
                mutableStateOf(themeRepository.load())
            }
            AftertoneTheme(palette = selectedPalette) {
                AftertoneApp(
                    selectedPalette = selectedPalette,
                    onPaletteSelected = { palette ->
                        if (palette != selectedPalette) {
                            themeRepository.save(palette)
                            selectedPalette = palette
                        }
                    },
                    nowPlayingLaunchRequestId = nowPlayingLaunchRequestId,
                    externalAudioRequest = if (canProcessExternalAudioQueue(BuildConfig.RECOVERY_DIAGNOSTIC_READ_ONLY)) {
                        pendingExternalAudioRequests.firstOrNull()
                    } else {
                        null
                    },
                    onExternalAudioRequestHandled = { requestId, attempt ->
                        updatePendingExternalAudioRequests { requests ->
                            removeExternalAudioOpenRequest(requests, requestId, attempt)
                        }
                    },
                    onExternalAudioRequestFailed = { requestId, attempt, failureKind, message ->
                        updatePendingExternalAudioRequests { requests ->
                            markExternalAudioOpenRequestFailed(
                                requests,
                                requestId,
                                attempt,
                                failureKind,
                                message,
                            )
                        }
                    },
                    onExternalAudioRequestFailureChoice = { requestId, attempt, choice ->
                        updatePendingExternalAudioRequests { requests ->
                            resolveExternalAudioOpenFailure(
                                requests,
                                requestId,
                                attempt,
                                choice,
                            )
                        }
                    },
                )
            }
        }
        lifecycleScope.launch {
            if (!canProcessExternalAudioQueue(BuildConfig.RECOVERY_DIAGNOSTIC_READ_ONLY)) return@launch
            externalAudioQueueMutationMutex.withLock {
                val restoreUpdate = withExternalAudioQueueIo {
                    externalAudioOpenRequestQueueStore.restore(
                        savedStateRequests = savedStateRequests,
                        currentIntentRequest = currentExternalAudioIntent.request?.request,
                        suppressAcknowledgedUriOnRecreation = suppressAcknowledgedUriOnRecreation,
                        hasPersistedReadPermission = ::hasPersistedExternalAudioReadPermission,
                    )
                }
                bindCurrentIntentRequestId(
                    retainedIntent,
                    currentExternalAudioIntent.request?.request,
                    restoreUpdate,
                    allowAcknowledgedBinding = suppressAcknowledgedUriOnRecreation,
                )
                applyExternalAudioOpenRequestQueueUpdate(restoreUpdate)
                currentExternalAudioIntent.request?.request?.let { releaseExternalAudioReadGrantIfUnused(it.uri) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_NOW_PLAYING) {
            nowPlayingLaunchRequestId = nextNowPlayingLaunchRequestId(nowPlayingLaunchRequestId, intent.action)
            return
        }
        if (!canProcessExternalAudioQueue(BuildConfig.RECOVERY_DIAGNOSTIC_READ_ONLY)) return
        val preparedIntent = prepareExternalAudioOpenIntent(intent)
        preparedIntent.sanitizedIntent?.let(::setIntent)
        acceptExternalAudioIntent(preparedIntent)
    }

    override fun onResume() {
        super.onResume()
        if (canProcessExternalAudioQueue(BuildConfig.RECOVERY_DIAGNOSTIC_READ_ONLY) &&
            undurableExternalAudioRequestQueue != null
        ) {
            lifecycleScope.launch {
                retryExternalAudioQueueCandidateIfCurrent(
                    mutationMutex = externalAudioQueueMutationMutex,
                    currentCandidate = { undurableExternalAudioRequestQueue },
                ) { retryCandidate ->
                    val update = withExternalAudioQueueIo { externalAudioOpenRequestQueueStore.retry(retryCandidate) }
                    applyExternalAudioOpenRequestQueueUpdate(update)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val requestsToSave = undurableExternalAudioRequestQueue?.pendingRequests ?: pendingExternalAudioRequests
        if (requestsToSave.isNotEmpty()) {
            outState.putStringArrayList(STATE_EXTERNAL_AUDIO_IDS, ArrayList(requestsToSave.map { it.id }))
            outState.putStringArrayList(STATE_EXTERNAL_AUDIO_URIS, ArrayList(requestsToSave.map { it.uri.toString() }))
            outState.putIntegerArrayList(STATE_EXTERNAL_AUDIO_ATTEMPTS, ArrayList(requestsToSave.map { it.attempt }))
            outState.putStringArrayList(
                STATE_EXTERNAL_AUDIO_FAILURE_KINDS,
                ArrayList(requestsToSave.map { it.failureKind?.name.orEmpty() }),
            )
            outState.putStringArrayList(
                STATE_EXTERNAL_AUDIO_FAILURE_MESSAGES,
                ArrayList(requestsToSave.map { it.failureMessage.orEmpty() }),
            )
            requestsToSave.firstOrNull()?.let { request ->
                outState.putString(STATE_EXTERNAL_AUDIO_ID, request.id)
                outState.putString(STATE_EXTERNAL_AUDIO_URI, request.uri.toString())
                outState.putInt(STATE_EXTERNAL_AUDIO_ATTEMPT, request.attempt)
            }
        }
    }

    private fun acceptExternalAudioIntent(preparedIntent: PreparedExternalAudioOpenIntent<Intent>) {
        if (!canProcessExternalAudioQueue(BuildConfig.RECOVERY_DIAGNOSTIC_READ_ONLY)) return
        val incomingIntent = preparedIntent.request ?: return
        val incomingRequest = incomingIntent.request
        persistExternalAudioReadPermission(preparedIntent.intentFlags, incomingRequest.uri)
        lifecycleScope.launch {
            externalAudioQueueMutationMutex.withLock {
                val retryCandidate = undurableExternalAudioRequestQueue
                val base = retryCandidate ?: externalAudioOpenRequestQueue
                val update = withExternalAudioQueueIo {
                    externalAudioOpenRequestQueueStore.enqueue(
                        snapshot = base,
                        incomingRequest = incomingRequest,
                        useProvidedSnapshotAsBase = retryCandidate != null,
                    )
                }
                bindCurrentIntentRequestId(
                    preparedIntent.sanitizedIntent,
                    incomingRequest,
                    update,
                    bindMatchingUri = false,
                )
                applyExternalAudioOpenRequestQueueUpdate(update)
                releaseExternalAudioReadGrantIfUnused(incomingRequest.uri)
            }
        }
    }

    private fun prepareExternalAudioOpenIntent(source: Intent?): PreparedExternalAudioOpenIntent<Intent> {
        val snapshot = source?.let {
            ExternalAudioIntentSnapshot(
                action = it.action,
                uri = it.data,
                declaredMimeType = it.type,
                intentFlags = it.flags,
            )
        }
        val prepared = prepareExternalAudioOpenIntent(
            snapshot = snapshot,
            readRequestIdExtra = { source?.getStringExtra(EXTRA_EXTERNAL_AUDIO_OPEN_REQUEST_ID) },
            createSanitizedIntent = { safeSnapshot ->
                Intent(safeSnapshot.action).apply {
                    if (safeSnapshot.uri != null || safeSnapshot.declaredMimeType != null) {
                        setDataAndType(safeSnapshot.uri, safeSnapshot.declaredMimeType)
                    }
                    flags = safeSnapshot.intentFlags
                }
            },
        )
        if (prepared.request == null && snapshot?.action == Intent.ACTION_VIEW && snapshot.uri != null &&
            snapshot.uri.scheme?.lowercase(Locale.ROOT) == "content" &&
            utf8Size(snapshot.uri.toString()) > MAX_EXTERNAL_AUDIO_URI_BYTES
        ) {
            Toast.makeText(this, "URI аудиофайла слишком длинный для безопасной очереди.", Toast.LENGTH_LONG).show()
        }
        return prepared
    }

    private fun bindCurrentIntentRequestId(
        retainedIntent: Intent?,
        incomingRequest: ExternalAudioOpenRequest?,
        update: ExternalAudioOpenRequestQueueStoreUpdate,
        allowAcknowledgedBinding: Boolean = false,
        bindMatchingUri: Boolean = true,
    ) {
        if (retainedIntent == null || incomingRequest == null) return
        if (update.rejection != null) return
        retainedIntent.putExtra(
            EXTRA_EXTERNAL_AUDIO_OPEN_REQUEST_ID,
            externalAudioOpenRequestIdToBindAfterUpdate(
                incomingRequest,
                update,
                allowAcknowledgedBinding,
                bindMatchingUri,
            ),
        )
        setIntent(retainedIntent)
    }

    private fun updatePendingExternalAudioRequests(
        updateRequests: (List<ExternalAudioOpenRequest>) -> List<ExternalAudioOpenRequest>,
    ) {
        if (!canProcessExternalAudioQueue(BuildConfig.RECOVERY_DIAGNOSTIC_READ_ONLY)) return
        lifecycleScope.launch {
            externalAudioQueueMutationMutex.withLock {
                val retryCandidate = undurableExternalAudioRequestQueue
                val base = retryCandidate ?: externalAudioOpenRequestQueue
                val update = withExternalAudioQueueIo {
                    externalAudioOpenRequestQueueStore.updatePendingRequests(
                        snapshot = base,
                        useProvidedSnapshotAsBase = retryCandidate != null,
                        updateRequests = updateRequests,
                    )
                }
                applyExternalAudioOpenRequestQueueUpdate(update)
            }
        }
    }

    private fun applyExternalAudioOpenRequestQueueUpdate(update: ExternalAudioOpenRequestQueueStoreUpdate) {
        val previousUris = buildSet {
            externalAudioOpenRequestQueue.pendingRequests.forEach { add(it.uri) }
            undurableExternalAudioRequestQueue?.pendingRequests?.forEach { add(it.uri) }
        }
        undurableExternalAudioRequestQueue =
            externalAudioQueueRetryCandidateAfterUpdate(undurableExternalAudioRequestQueue, update)
        externalAudioOpenRequestQueue = externalAudioOpenQueueAfterUpdate(externalAudioOpenRequestQueue, update)
        if (update.persisted) {
            val currentUris = buildSet {
                externalAudioOpenRequestQueue.pendingRequests.forEach { add(it.uri) }
                undurableExternalAudioRequestQueue?.pendingRequests?.forEach { add(it.uri) }
            }
            (previousUris - currentUris).forEach(::releaseExternalAudioReadGrantIfUnused)
        }
        if (update.reopenRequiredCount > 0) {
            Toast.makeText(this, getString(R.string.external_audio_reopen_required), Toast.LENGTH_LONG).show()
        }
        update.storeProblem?.takeIf { problem ->
            problem == ExternalAudioOpenQueueStoreProblem.Corrupt ||
                problem == ExternalAudioOpenQueueStoreProblem.Oversized ||
                problem == ExternalAudioOpenQueueStoreProblem.RecoveryFailure ||
                problem == ExternalAudioOpenQueueStoreProblem.RecoveryWriteFailure
        }?.let(::showExternalAudioQueueRecoveryDialog)
        val message = when {
            update.rejection == ExternalAudioOpenRequestQueueRejection.QueueFull ->
                "Очередь аудиофайлов заполнена. Дождитесь её обработки и повторите открытие."
            update.rejection == ExternalAudioOpenRequestQueueRejection.UriTooLarge ->
                "URI аудиофайла слишком длинный для безопасной очереди."
            update.rejection == ExternalAudioOpenRequestQueueRejection.SnapshotTooLarge ->
                "Очередь аудиофайлов достигла лимита данных. Дождитесь её обработки и повторите открытие."
            update.rejection == ExternalAudioOpenRequestQueueRejection.InvalidRequest ->
                "Некорректный запрос на открытие аудио не добавлен в очередь."
            update.storeProblem == ExternalAudioOpenQueueStoreProblem.Corrupt ||
                update.storeProblem == ExternalAudioOpenQueueStoreProblem.Oversized -> null
            update.storeProblem == ExternalAudioOpenQueueStoreProblem.ReadFailure ->
                "Не удалось прочитать сохранённую очередь. Запросы оставлены для повторной попытки."
            update.storeProblem == ExternalAudioOpenQueueStoreProblem.WriteFailure ->
                "Не удалось сохранить очередь аудиофайлов. Запросы оставлены для повторной попытки."
            update.storeProblem == ExternalAudioOpenQueueStoreProblem.RecoveryFailure ->
                "Не удалось создать резервную копию; повреждённая очередь и запросы оставлены без изменений."
            update.storeProblem == ExternalAudioOpenQueueStoreProblem.RecoveryWriteFailure ->
                "Резервная копия сохранена, но восстановленную очередь записать не удалось. Повторите восстановление."
            !update.persisted -> getString(R.string.external_audio_queue_persistence_failed)
            else -> null
        }
        if (message != null) {
            if (!update.persisted) Log.e(TAG_EXTERNAL_AUDIO_QUEUE, "Unable to persist the external-open request queue.")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun persistExternalAudioReadPermission(intentFlags: Int, uri: Uri) {
        if (uri.scheme?.lowercase(Locale.ROOT) != "content") return
        val flags = externalAudioPersistableReadPermissionFlags(intentFlags)
        if (flags == 0) return
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { Log.w(TAG_EXTERNAL_AUDIO_QUEUE, "A persistable external audio read grant was not retained.") }
    }

    private fun hasPersistedExternalAudioReadPermission(uri: Uri): Boolean =
        runCatching {
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }.getOrDefault(true)

    private fun releaseExternalAudioReadGrantIfUnused(uri: Uri) {
        if (uri.scheme?.lowercase(Locale.ROOT) != "content") return
        val stillQueued = externalAudioOpenRequestQueue.pendingRequests.any { it.uri == uri } ||
            undurableExternalAudioRequestQueue?.pendingRequests.orEmpty().any { it.uri == uri }
        if (stillQueued) return
        val hasPersistedGrant = runCatching {
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }.getOrDefault(false)
        if (!hasPersistedGrant) return
        runCatching {
            contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.w(TAG_EXTERNAL_AUDIO_QUEUE, "An unused external audio read grant could not be released.") }
    }

    private fun showExternalAudioQueueRecoveryDialog(problem: ExternalAudioOpenQueueStoreProblem) {
        if (isFinishing || isDestroyed || externalAudioQueueRecoveryDialog?.isShowing == true) return
        val pendingCount = undurableExternalAudioRequestQueue?.pendingRequests?.size ?: 0
        val issue = when (problem) {
            ExternalAudioOpenQueueStoreProblem.Oversized ->
                "Сохранённая очередь слишком велика для безопасной загрузки."
            ExternalAudioOpenQueueStoreProblem.RecoveryFailure ->
                "Не удалось создать приватную резервную копию в пределах лимита размера и места. Исходная очередь не изменена."
            ExternalAudioOpenQueueStoreProblem.RecoveryWriteFailure ->
                "Резервная копия создана, но восстановленную очередь записать не удалось. Исходная очередь не изменена."
            else -> "Сохранённая очередь повреждена или не читается."
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Восстановить очередь аудиофайлов?")
            .setMessage(
                "$issue Запросов, сохранённых в текущей сессии: $pendingCount. " +
                    "При подтверждении приложение сохранит или повторно использует точную приватную копию исходного снимка, " +
                    "и только после этого попытается заменить значение очереди внешнего открытия. " +
                    "Обработка возобновится лишь после успешной записи; данные библиотеки и аудиофайлы не изменятся. " +
                    "Если восстановление удастся, записи, доступные только в повреждённом снимке, останутся в резервной копии.",
            )
            .setNegativeButton("Оставить без изменений", null)
            .setPositiveButton("Сохранить копию и восстановить", null)
            .create()
        dialog.setOnDismissListener {
            if (externalAudioQueueRecoveryDialog === dialog) externalAudioQueueRecoveryDialog = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = undurableExternalAudioRequestQueue ?: ExternalAudioOpenRequestQueueSnapshot()
                dialog.dismiss()
                lifecycleScope.launch {
                    externalAudioQueueMutationMutex.withLock {
                        val latestCandidate = undurableExternalAudioRequestQueue ?: candidate
                        val recovery = withExternalAudioQueueIo {
                            externalAudioOpenRequestQueueStore.recoverCorruptSnapshot(latestCandidate)
                        }
                        applyExternalAudioOpenRequestQueueUpdate(recovery)
                        if (recovery.persisted) {
                            Toast.makeText(this@MainActivity, "Очередь восстановлена; исходный снимок сохранён в резервной копии.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        externalAudioQueueRecoveryDialog = dialog
        dialog.show()
    }

    @SuppressLint("UseKtx") // The KTX wrapper drops commit()'s success result, which gates queue processing.
    private fun commitExternalAudioOpenRequestQueue(preferences: SharedPreferences, encoded: String): Boolean =
        preferences.edit().putString(EXTERNAL_AUDIO_OPEN_REQUEST_QUEUE_KEY, encoded).commit()

    private fun backupExternalAudioQueueSnapshot(rawSnapshot: String): Boolean =
        ExternalAudioOpenRequestQueueSnapshotBackup(filesDir).backup(rawSnapshot)

    private fun restoreExternalAudioRequests(state: Bundle): List<ExternalAudioOpenRequest>? {
        val requestIds = state.getStringArrayList(STATE_EXTERNAL_AUDIO_IDS)
        val uriStrings = state.getStringArrayList(STATE_EXTERNAL_AUDIO_URIS)
        val attempts = state.getIntegerArrayList(STATE_EXTERNAL_AUDIO_ATTEMPTS)
        val failureKinds = state.getStringArrayList(STATE_EXTERNAL_AUDIO_FAILURE_KINDS)
        val failureMessages = state.getStringArrayList(STATE_EXTERNAL_AUDIO_FAILURE_MESSAGES)
        val restoredRequests = restoreExternalAudioOpenRequests(
            requestIds,
            uriStrings,
            attempts,
            failureKinds,
            failureMessages,
        )
        if (restoredRequests != null) {
            return restoredRequests
        }

        val uriString = state.getString(STATE_EXTERNAL_AUDIO_URI) ?: return null
        val requestId = state.getString(STATE_EXTERNAL_AUDIO_ID) ?: return null
        if (!isCanonicalExternalAudioOpenRequestId(requestId) ||
            uriString.length > MAX_EXTERNAL_AUDIO_URI_BYTES ||
            utf8Size(uriString) > MAX_EXTERNAL_AUDIO_URI_BYTES
        ) return null
        val uri = uriString.toUri()
        if (uri.scheme?.lowercase(Locale.ROOT) != "content") return null
        return listOf(
            ExternalAudioOpenRequest(
                id = requestId,
                uri = uri,
                attempt = state.getInt(STATE_EXTERNAL_AUDIO_ATTEMPT, 0).coerceAtLeast(0),
            ),
        )
    }

    private companion object {
        const val TAG_EXTERNAL_AUDIO_QUEUE = "AftertoneExternalAudioQueue"
        const val STATE_EXTERNAL_AUDIO_ID = "external_audio_request_id"
        const val STATE_EXTERNAL_AUDIO_URI = "external_audio_request_uri"
        const val STATE_EXTERNAL_AUDIO_ATTEMPT = "external_audio_request_attempt"
        const val STATE_EXTERNAL_AUDIO_IDS = "external_audio_request_ids"
        const val STATE_EXTERNAL_AUDIO_URIS = "external_audio_request_uris"
        const val STATE_EXTERNAL_AUDIO_ATTEMPTS = "external_audio_request_attempts"
        const val STATE_EXTERNAL_AUDIO_FAILURE_KINDS = "external_audio_request_failure_kinds"
        const val STATE_EXTERNAL_AUDIO_FAILURE_MESSAGES = "external_audio_request_failure_messages"
    }
}
