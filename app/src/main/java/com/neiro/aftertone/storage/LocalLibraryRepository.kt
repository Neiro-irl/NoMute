package com.neiro.aftertone.storage

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import androidx.core.content.edit
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.TrackArtworkFileName
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.model.TrackFileName
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID

internal data class PreparedBackupArchive(
    val id: String,
    val summary: BackupSummary,
)

private data class ExternalImportDispatchOutcomeChange(
    val requestId: String,
    val mayHaveStarted: Boolean,
)

class LocalLibraryRepository internal constructor(
    context: Context,
    private val audioDocumentSource: AudioDocumentSource,
    private val importer: AudioTrackImporter,
    private val availableBytesOverride: (() -> Long)? = null,
    private val artworkImporter: TrackArtworkImporter = LocalArtworkImporter(
        File(context.applicationContext.filesDir, "audio"),
    ),
    private val durability: AudioImportDurability = AndroidAudioImportDurability,
) {
    constructor(context: Context) : this(
        context,
        AndroidAudioDocumentSource { context.applicationContext.contentResolver },
        createDefaultAudioImporter(context.applicationContext),
    )

    private val appContext = context.applicationContext
    internal val isRecoveryDiagnosticsReadOnly = isReadOnlyRecoveryDiagnosticBuild(appContext)
    internal val restoreActionGate = RestoreActionGate()
    internal val preparedBackupArchiveOwner = PreparedBackupArchiveOwner()
    private val audioDirectory = File(appContext.filesDir, "audio")
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    @Volatile private var recoveryRetryAuthorizationConsumedInProcess = false
    @Volatile private var scopedRecoveryAttemptInProgress = false
    @Volatile private var scopedRecoveryRetryAuthorizationGrantedInProcess = false
    internal val isRecoveryRetryAuthorizationRequired: Boolean
        get() = recoveryRetryAuthorizationConsumedInProcess ||
            (!scopedRecoveryRetryAuthorizationGrantedInProcess &&
                preferences.getString(SCOPED_RECOVERY_ATTEMPT_STATE_KEY, null)
                    ?.let { it != SCOPED_RECOVERY_ATTEMPT_COMPLETE } == true)
    internal val isScopedRecoveryRetryAvailable: Boolean
        get() = !isRecoveryDiagnosticsReadOnly && !scopedRecoveryAttemptInProgress &&
            restoreActionGate.pendingCleanupTransactionId == RestoreActionGate.RECOVERY_PENDING_ID &&
            preferences.getString(SCOPED_RECOVERY_ATTEMPT_STATE_KEY, null)
                ?.let { it == SCOPED_RECOVERY_ATTEMPT_STARTED || it == SCOPED_RECOVERY_ATTEMPT_FAILED } == true

    @Synchronized
    internal fun authorizeScopedRecoveryRetry(): Boolean = synchronized(importArtifactLock) {
        if (isRecoveryDiagnosticsReadOnly || scopedRecoveryAttemptInProgress ||
            restoreActionGate.pendingCleanupTransactionId != RestoreActionGate.RECOVERY_PENDING_ID
        ) return@synchronized false
        val attemptState = preferences.getString(SCOPED_RECOVERY_ATTEMPT_STATE_KEY, null)
        if (attemptState != SCOPED_RECOVERY_ATTEMPT_STARTED && attemptState != SCOPED_RECOVERY_ATTEMPT_FAILED) {
            return@synchronized false
        }
        scopedRecoveryRetryAuthorizationGrantedInProcess = true
        recoveryRetryAuthorizationConsumedInProcess = false
        true
    }

    @Synchronized
    internal fun cancelUnconsumedScopedRecoveryRetryAuthorization() = synchronized(importArtifactLock) {
        if (scopedRecoveryRetryAuthorizationGrantedInProcess) {
            scopedRecoveryRetryAuthorizationGrantedInProcess = false
            recoveryRetryAuthorizationConsumedInProcess = true
        }
    }

    internal val recoveryDiagnosticAttemptTraceCode: String
        get() = RestoreRecoveryAttemptTrace.decode(
            preferences.getString(RECOVERY_DIAGNOSTIC_ATTEMPT_TRACE_KEY, null),
        ).displayCode ?: "DIA-AT00"
    internal val recoveryDiagnosticStatusMessage: String
        get() = "Журнал восстановления проверен только для чтения; очистка не запускалась. Управление библиотекой остаётся заблокированным. Код: $recoveryDiagnosticAttemptTraceCode"
    private val stateStore = object : LibraryStateStore {
        override fun read(): String? = preferences.getString(STATE_KEY, null)

        @SuppressLint("UseKtx") // The transaction must observe commit()'s Boolean result.
        override fun write(encoded: String?): Boolean = preferences.edit().apply {
            if (encoded == null) remove(STATE_KEY) else putString(STATE_KEY, encoded)
        }.commit()
    }
    private val storageManager = appContext.getSystemService(StorageManager::class.java)
    private val restoreTransaction = LibraryRestoreTransaction(
        filesDirectory = appContext.filesDir,
        audioDirectory = audioDirectory,
        stateStore = stateStore,
        decodeSnapshot = ::decodeStoredSnapshot,
    )
    private var preparedArchiveSweepComplete = false
    private var importArtifactSweepComplete = false

    @Synchronized
    fun load(): LibrarySnapshot = synchronized(importArtifactLock) {
        if (isRecoveryDiagnosticsReadOnly) {
            restoreActionGate.adoptPendingCleanup(RestoreActionGate.RECOVERY_PENDING_ID)
            recordJournalDiagnosis(restoreTransaction.diagnoseRecoveryJournalRead())
            return@synchronized decodeStoredSnapshot(stateStore.read())
        }
        val scopedRecoveryAttemptState = preferences.getString(SCOPED_RECOVERY_ATTEMPT_STATE_KEY, null)
        if (scopedRecoveryAttemptState != null) {
            // The scoped authorization excludes prepared ZIPs and import artifacts on every later launch.
            preparedArchiveSweepComplete = true
            importArtifactSweepComplete = true
        }
        if (scopedRecoveryAttemptState != null && scopedRecoveryAttemptState != SCOPED_RECOVERY_ATTEMPT_COMPLETE) {
            if (scopedRecoveryRetryAuthorizationGrantedInProcess && !scopedRecoveryAttemptInProgress) {
                scopedRecoveryRetryAuthorizationGrantedInProcess = false
                return@synchronized runScopedRecoveryAttempt()
            }
            blockAfterConsumedScopedRecoveryAttempt(scopedRecoveryAttemptState)
            return@synchronized decodeStoredSnapshot(stateStore.read())
        }
        if (recoveryRetryAuthorizationConsumedInProcess) {
            blockAfterConsumedScopedRecoveryAttempt(scopedRecoveryAttemptState)
            return@synchronized decodeStoredSnapshot(stateStore.read())
        }
        if (scopedRecoveryAttemptState == null) {
            return@synchronized runScopedRecoveryAttempt()
        }
        if (!preparedArchiveSweepComplete) {
            runCatching {
                ensureCacheDirectory()
                preparedArchiveSweepComplete = cleanupPreparedArchiveOrphans()
            }.onFailure { preparedBackupArchiveOwner.markSweepPending() }
        }
        var recoveryCompleted = false
        try {
            val pendingTransactionId = restoreTransaction.recoverInterruptedRestore(
                skipNoJournalCleanup = scopedRecoveryAttemptState == SCOPED_RECOVERY_ATTEMPT_COMPLETE,
            )
            if (pendingTransactionId == null) {
                recoveryCompleted = true
            } else {
                restoreActionGate.adoptPendingCleanup(pendingTransactionId)
            }
        } catch (_: Exception) {
            if (restoreActionGate.pendingCleanupTransactionId == null) {
                restoreActionGate.adoptPendingCleanup(RestoreActionGate.RECOVERY_PENDING_ID)
            }
        }
        val snapshot = try {
            decodeStoredSnapshot(stateStore.read())
        } catch (error: Exception) {
            if (restoreActionGate.pendingCleanupTransactionId == null) {
                restoreActionGate.adoptPendingCleanup(RestoreActionGate.RECOVERY_PENDING_ID)
            }
            throw error
        }
        if (recoveryCompleted) restoreActionGate.completeRecoveryWithoutPendingCleanup()
        if (recoveryCompleted && restoreActionGate.pendingCleanupTransactionId == null && !importArtifactSweepComplete) {
            importArtifactSweepComplete = runCatching { cleanupAbandonedImportArtifacts(snapshot) }.getOrDefault(false)
        }
        restoreActionGate.unblock()
        snapshot
    }

    private fun runScopedRecoveryAttempt(): LibrarySnapshot {
        scopedRecoveryAttemptInProgress = true
        scopedRecoveryRetryAuthorizationGrantedInProcess = false
        recoveryRetryAuthorizationConsumedInProcess = true
        try {
            preparedArchiveSweepComplete = true
            importArtifactSweepComplete = true
            restoreActionGate.adoptPendingCleanup(RestoreActionGate.RECOVERY_PENDING_ID)
            if (!writeScopedRecoveryAttemptState(SCOPED_RECOVERY_ATTEMPT_STARTED)) {
                // Try to persist a fail-closed state, but never mutate recovery files without STARTED.
                writeScopedRecoveryAttemptState(SCOPED_RECOVERY_ATTEMPT_FAILED)
                return decodeStoredSnapshot(stateStore.read())
            }

            val snapshot = try {
                decodeStoredSnapshot(stateStore.read())
            } catch (error: Exception) {
                writeScopedRecoveryAttemptState(SCOPED_RECOVERY_ATTEMPT_FAILED)
                throw error
            }
            val cleanupComplete = restoreTransaction.cleanupApprovedNoJournalArtifactsOnce(snapshot)
            if (!cleanupComplete) {
                writeScopedRecoveryAttemptState(SCOPED_RECOVERY_ATTEMPT_FAILED)
                return snapshot
            }
            if (!writeScopedRecoveryAttemptState(SCOPED_RECOVERY_ATTEMPT_COMPLETE)) {
                // STARTED remains the durable state, so another process will stay blocked.
                return snapshot
            }

            recoveryRetryAuthorizationConsumedInProcess = false
            restoreActionGate.completeRecoveryWithoutPendingCleanup()
            return snapshot
        } finally {
            scopedRecoveryAttemptInProgress = false
        }
    }

    private fun blockAfterConsumedScopedRecoveryAttempt(attemptState: String?) {
        recoveryRetryAuthorizationConsumedInProcess = true
        preparedArchiveSweepComplete = true
        importArtifactSweepComplete = true
        restoreActionGate.adoptPendingCleanup(RestoreActionGate.RECOVERY_PENDING_ID)
        if (attemptState == SCOPED_RECOVERY_ATTEMPT_STARTED) {
            // A process stopped during cleanup; promote the durable in-progress latch to failure.
            writeScopedRecoveryAttemptState(SCOPED_RECOVERY_ATTEMPT_FAILED)
        }
    }

    @SuppressLint("UseKtx") // A destructive recovery attempt is not allowed without a confirmed durable latch.
    private fun writeScopedRecoveryAttemptState(state: String): Boolean =
        preferences.edit().putString(SCOPED_RECOVERY_ATTEMPT_STATE_KEY, state).commit()

    private fun recordJournalDiagnosis(classification: RecoveryJournalReadClassification) {
        val trace = RestoreRecoveryAttemptTrace.decode(
            preferences.getString(RECOVERY_DIAGNOSTIC_ATTEMPT_TRACE_KEY, null),
        ).appendJournalDiagnosis(
            classification.diagnosis,
            classification.osErrorCategory,
            classification.rootProbeOutcome,
            classification.privateFilesDirectoryProbeOutcome,
            classification.privateFilesDirectoryNioProbeOutcome,
        )
        preferences.edit {
            putString(RECOVERY_DIAGNOSTIC_ATTEMPT_TRACE_KEY, trace.encode())
        }
    }

    @Synchronized
    internal fun exportBackup(
        output: OutputStream,
        onProgress: (BackupProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): BackupSummary = synchronized(importArtifactLock) {
        if (isRecoveryDiagnosticsReadOnly) throw IOException("This recovery diagnostic build is read-only.")
        val snapshot = load()
        ensureAudioDirectory()
        LibraryBackupArchive.export(snapshot, audioDirectory, output, onProgress, checkCancelled)
    }

    @Synchronized
    internal fun prepareBackupArchive(
        input: InputStream,
        onProgress: (BackupProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): PreparedBackupArchive {
        if (isRecoveryDiagnosticsReadOnly) throw IOException("This recovery diagnostic build is read-only.")
        val id = UUID.randomUUID().toString()
        var archiveFile: File? = null
        try {
            return input.use { source ->
                checkCancelled()
                load()
                if (preparedBackupArchiveOwner.state.value.cleanupPending) {
                    throw IOException("A temporary restore archive still needs cleanup.")
                }
                ensureAudioDirectory()
                ensureCacheDirectory()
                archiveFile = PreparedBackupArchiveFiles.archiveFile(appContext.cacheDir, id)
                val (stagedArchive, summary) = PreparedBackupArchiveFiles.createAndWriteAndValidate(
                    cacheDirectory = appContext.cacheDir,
                    id = id,
                    durability = SystemRestoreFileDurability,
                    writeArchive = { output ->
                        LibraryBackupArchive.stageArchiveToStream(
                            input = source,
                            output = output,
                            availableBytes = ::availableBytes,
                            checkCancelled = checkCancelled,
                            onProgress = onProgress,
                        )
                    },
                    validateArchive = { archive ->
                        LibraryBackupArchive.inspect(archive, checkCancelled = checkCancelled)
                    },
                )
                archiveFile = stagedArchive
                if (availableBytes() < addReservedSpace(summary.payloadBytes)) {
                    throw IOException("There is not enough free space to restore this archive safely.")
                }
                checkCancelled()
                PreparedBackupArchive(id, summary)
            }
        } catch (error: Throwable) {
            if (archiveFile == null) runCatching { input.close() }
            if (archiveFile != null) {
                val removed = runCatching { PreparedBackupArchiveFiles.discard(appContext.cacheDir, id) }
                    .getOrDefault(false)
                if (!removed) {
                    preparedBackupArchiveOwner.retain(
                        PreparedBackupArchive(id, BackupSummary(0, 0, 0, 0)),
                        cleanupPending = true,
                    )
                }
            }
            throw error
        }
    }

    @Synchronized
    internal fun discardPreparedBackup(prepared: PreparedBackupArchive): Boolean {
        if (!VALID_UUID.matches(prepared.id)) return false
        return PreparedBackupArchiveFiles.discard(appContext.cacheDir, prepared.id)
    }

    @Synchronized
    internal fun retryPreparedBackupArchiveCleanupSweep(): Boolean {
        return try {
            ensureCacheDirectory()
            cleanupPreparedArchiveOrphans().also { preparedArchiveSweepComplete = it }
        } catch (error: Exception) {
            preparedBackupArchiveOwner.markSweepPending()
            throw error
        }
    }

    private fun cleanupPreparedArchiveOrphans(): Boolean {
        val cleanup = PreparedBackupArchiveFiles.cleanupOrphansWithPending(
            appContext.cacheDir,
            preparedBackupArchiveOwner.protectedArchiveIdsForCleanup(),
        )
        preparedBackupArchiveOwner.markSweepComplete()
        preparedBackupArchiveOwner.cleanupCandidates().forEach { candidate ->
            if (candidate.id !in cleanup.pendingIds &&
                PreparedBackupArchiveFiles.isFullyDiscarded(appContext.cacheDir, candidate.id)
            ) {
                preparedBackupArchiveOwner.completeCleanup(candidate)
            }
        }
        cleanup.pendingIds.forEach { id ->
            preparedBackupArchiveOwner.retain(
                PreparedBackupArchive(id, BackupSummary(0, 0, 0, 0)),
                cleanupPending = true,
            )
        }
        return cleanup.pendingIds.isEmpty() && !preparedBackupArchiveOwner.state.value.cleanupPending
    }

    @Synchronized
    internal fun restoreBackup(
        prepared: PreparedBackupArchive,
        onProgress: (BackupProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
        onCommitStarted: () -> Unit = {},
    ): LibraryRestoreResult = synchronized(importArtifactLock) {
        val archiveFile = preparedArchiveFileOrNull(prepared)
            ?: throw SecurityException("The selected archive is not owned by this restore operation.")
        var stagingDirectory: File? = null
        try {
            val currentSnapshot = load()
            ensureAudioDirectory()
            if (!archiveFile.isFile || LibraryBackupArchive.inspect(archiveFile, checkCancelled = checkCancelled) != prepared.summary) {
                throw IllegalArgumentException("The selected archive changed after it was checked.")
            }
            checkCancelled()
            // Preserve legacy Track-only dispatch evidence before the restore can replace those Track IDs.
            migrateLegacyExternalImportDispatchOutcomes(currentSnapshot)
            stagingDirectory = restoreTransaction.createStagingDirectory()
            val decoded = LibraryBackupArchive.extract(
                archiveFile = archiveFile,
                stagingDirectory = stagingDirectory,
                availableBytes = ::availableBytes,
                onProgress = onProgress,
                checkCancelled = checkCancelled,
            )
            checkCancelled()
            val pending = restoreTransaction.prepareRestore(stagingDirectory, decoded, checkCancelled)
            checkCancelled()
            restoreTransaction.commitRestore(pending, beforeCommit = onCommitStarted)
        } finally {
            stagingDirectory?.let { directory ->
                if (directory.exists()) restoreTransaction.discardStagingDirectory(directory)
            }
            discardPreparedBackup(prepared)
        }
    }

    @Synchronized
    internal fun finishRestore(transactionId: String): Boolean = synchronized(importArtifactLock) {
        restoreTransaction.finishRestore(transactionId)
    }

    @Synchronized
    internal fun finishRestoreOutcome(transactionId: String): RestoreFinalizationResult =
        synchronized(importArtifactLock) { restoreTransaction.finishRestoreOutcome(transactionId) }

    @Synchronized
    fun import(uri: Uri): Track = importDocument(uri, externalRequestId = null)

    @Synchronized
    internal fun importExternal(uri: Uri, requestId: String): Track {
        require(runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)) {
            "The external audio request ID is invalid."
        }
        return importDocument(uri, externalRequestId = requestId)
    }

    @Synchronized
    internal fun externalImportDispatchMayHaveStarted(requestId: String): Boolean =
        synchronized(importArtifactLock) {
            if (!VALID_UUID.matches(requestId)) return@synchronized false
            if (requestId in readExternalImportDispatchOutcomes()) return@synchronized true

            // Backfill legacy/backup Track markers before a later restore can remove that Track identity.
            val current = load()
            val imported = current.tracks.firstOrNull { it.externalImportRequestId == requestId }
                ?: return@synchronized false
            val mayHaveStarted = imported.externalImportHandoffCompleted ||
                imported.externalImportHandoffDispatchStarted == true ||
                (imported.externalImportHandoffClaimed && imported.externalImportHandoffDispatchStarted == null)
            if (!mayHaveStarted) return@synchronized false

            persist(
                current,
                dispatchOutcomeChange = ExternalImportDispatchOutcomeChange(requestId, mayHaveStarted = true),
            )
            true
        }

    @Synchronized
    internal fun markExternalImportHandoffClaimed(
        requestId: String,
        dispatchPermit: RestoreActionGate.DispatchPermit? = null,
    ): Track = synchronized(importArtifactLock) {
        require(runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)) {
            "The external audio request ID is invalid."
        }
        val current = load()
        val existing = current.tracks.firstOrNull { it.externalImportRequestId == requestId }
            ?: throw IOException("The imported audio file could not be found in the local library.")
        if (existing.externalImportHandoffClaimed || existing.externalImportHandoffCompleted) return@synchronized existing

        val updated = persist(
            current.copy(
                tracks = current.tracks.map { track ->
                    if (track.externalImportRequestId == requestId) {
                        track.copy(
                            externalImportHandoffClaimed = true,
                            externalImportHandoffDispatchStarted = false,
                        )
                    } else {
                        track
                    }
                },
            ),
            dispatchPermit = dispatchPermit,
        )
        updated.tracks.firstOrNull { it.externalImportRequestId == requestId }
            ?: throw IOException("The external handoff claim could not be read back from the local library.")
    }

    @Synchronized
    internal fun markExternalImportHandoffDispatchStarted(
        requestId: String,
        dispatchPermit: RestoreActionGate.DispatchPermit? = null,
    ): Track = synchronized(importArtifactLock) {
        require(runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)) {
            "The external audio request ID is invalid."
        }
        val current = load()
        val existing = current.tracks.firstOrNull { it.externalImportRequestId == requestId }
            ?: throw IOException("The imported audio file could not be found in the local library.")
        if (!existing.externalImportHandoffClaimed || existing.externalImportHandoffCompleted) {
            throw IOException("The external handoff is not eligible to begin playback dispatch.")
        }
        if (existing.externalImportHandoffDispatchStarted == true) return@synchronized existing
        if (existing.externalImportHandoffDispatchStarted == null) {
            throw IOException("The previous external playback attempt is ambiguous and cannot be replayed.")
        }

        val updated = persist(
            current.copy(
                tracks = current.tracks.map { track ->
                    if (track.externalImportRequestId == requestId) {
                        track.copy(externalImportHandoffDispatchStarted = true)
                    } else {
                        track
                    }
                },
            ),
            dispatchPermit = dispatchPermit,
            dispatchOutcomeChange = ExternalImportDispatchOutcomeChange(requestId, mayHaveStarted = true),
        )
        updated.tracks.firstOrNull { it.externalImportRequestId == requestId }
            ?: throw IOException("The playback dispatch marker could not be read back from the local library.")
    }

    @Synchronized
    internal fun markExternalImportHandoffNotDispatched(
        requestId: String,
        dispatchPermit: RestoreActionGate.DispatchPermit? = null,
    ): Track = synchronized(importArtifactLock) {
        require(runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)) {
            "The external audio request ID is invalid."
        }
        val current = load()
        val existing = current.tracks.firstOrNull { it.externalImportRequestId == requestId }
            ?: throw IOException("The imported audio file could not be found in the local library.")
        if (!existing.externalImportHandoffClaimed || existing.externalImportHandoffCompleted) {
            throw IOException("The external handoff is not eligible for a no-dispatch rollback.")
        }
        if (existing.externalImportHandoffDispatchStarted == false) return@synchronized existing
        if (existing.externalImportHandoffDispatchStarted == null) {
            throw IOException("The previous external playback attempt is ambiguous and cannot be rolled back.")
        }

        val updated = persist(
            current.copy(
                tracks = current.tracks.map { track ->
                    if (track.externalImportRequestId == requestId) {
                        track.copy(externalImportHandoffDispatchStarted = false)
                    } else {
                        track
                    }
                },
            ),
            dispatchPermit = dispatchPermit,
            dispatchOutcomeChange = ExternalImportDispatchOutcomeChange(requestId, mayHaveStarted = false),
        )
        updated.tracks.firstOrNull { it.externalImportRequestId == requestId }
            ?: throw IOException("The no-dispatch state could not be read back from the local library.")
    }

    @Synchronized
    internal fun markExternalImportHandoffCompleted(
        requestId: String,
        dispatchPermit: RestoreActionGate.DispatchPermit? = null,
    ): Track = synchronized(importArtifactLock) {
        require(runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)) {
            "The external audio request ID is invalid."
        }
        val current = load()
        val existing = current.tracks.firstOrNull { it.externalImportRequestId == requestId }
            ?: throw IOException("The imported audio file could not be found in the local library.")
        if (existing.externalImportHandoffCompleted) return@synchronized existing
        if (!existing.externalImportHandoffClaimed || existing.externalImportHandoffDispatchStarted != true) {
            throw IOException("The external playback dispatch was not durably recorded before completion.")
        }

        val updated = persist(
            current.copy(
                tracks = current.tracks.map { track ->
                    if (track.externalImportRequestId == requestId) {
                        track.copy(
                            externalImportHandoffClaimed = true,
                            externalImportHandoffDispatchStarted = true,
                            externalImportHandoffCompleted = true,
                        )
                    } else {
                        track
                    }
                },
            ),
            dispatchPermit = dispatchPermit,
            dispatchOutcomeChange = ExternalImportDispatchOutcomeChange(requestId, mayHaveStarted = true),
        )
        updated.tracks.firstOrNull { it.externalImportRequestId == requestId }
            ?: throw IOException("The completed external handoff could not be read back from the local library.")
    }

    private fun importDocument(uri: Uri, externalRequestId: String?): Track = synchronized(importArtifactLock) {
        val current = load()
        if (externalRequestId != null) {
            current.tracks.firstOrNull { it.externalImportRequestId == externalRequestId }?.let { return@synchronized it }
        }
        ensureAudioDirectory()
        val document = audioDocumentSource.open(uri)
        val imported = importer.import(document.input, document.displayName, document.mimeType)
        val track = imported.copy(externalImportRequestId = externalRequestId)

        try {
            persist(
                current.copy(tracks = current.tracks + track),
                requiredTrackId = track.id,
            )
        } catch (error: Exception) {
            // A failed commit may still leave the new state in memory; delete only after a clear read proves it absent.
            val referencedAfterFailure = runCatching {
                load().tracks.any { it.id == track.id }
            }.getOrNull()
            if (referencedAfterFailure == false) {
                try {
                    deleteOwnedFiles(track)
                } catch (cleanupFailure: Throwable) {
                    error.addSuppressed(cleanupFailure)
                }
            }
            throw error
        }

        val reloaded = load()
        reloaded.tracks.firstOrNull { it.id == track.id } ?: run {
            val error = IOException("The selected audio file could not be added to the local library.")
            try {
                deleteOwnedFiles(track)
            } catch (cleanupFailure: Throwable) {
                error.addSuppressed(cleanupFailure)
            }
            throw error
        }
    }

    @Synchronized
    fun toggleFavorite(trackId: String): LibrarySnapshot = synchronized(importArtifactLock) {
        persist(load().toggleFavorite(trackId))
    }

    @Synchronized
    fun addPlaylist(name: String): LibrarySnapshot = synchronized(importArtifactLock) {
        persist(load().addPlaylist(Playlist(UUID.randomUUID().toString(), name)))
    }

    @Synchronized
    fun addToPlaylist(playlistId: String, trackId: String): LibrarySnapshot = synchronized(importArtifactLock) {
        persist(load().addToPlaylist(playlistId, trackId))
    }

    @Synchronized
    fun removeFromPlaylist(playlistId: String, trackId: String): LibrarySnapshot = synchronized(importArtifactLock) {
        persist(load().removeFromPlaylist(playlistId, trackId))
    }

    @Synchronized
    fun removePlaylist(playlistId: String): LibrarySnapshot = synchronized(importArtifactLock) {
        persist(load().removePlaylist(playlistId))
    }

    @Synchronized
    fun removeTrack(trackId: String): LibrarySnapshot = synchronized(importArtifactLock) {
        val current = load()
        val ownedTrack = current.tracks.firstOrNull { it.id == trackId }
        val updated = persist(current.removeTrack(trackId))
        ownedTrack?.let(::deleteOwnedFiles)
        updated
    }

    @Synchronized
    fun setTrackArtwork(trackId: String, input: InputStream): LibrarySnapshot = synchronized(importArtifactLock) {
        val current = load()
        val existingTrack = current.tracks.firstOrNull { it.id == trackId }
            ?: throw IOException("The selected track is no longer in the local library.")
        val importedPath = artworkImporter.import(trackId, input)
        val newArtworkPath = canonicalOwnedArtworkPath(trackId, importedPath)
        val requestedSnapshot = current.copy(
            tracks = current.tracks.map { track ->
                if (track.id == trackId) track.copy(artworkPath = newArtworkPath) else track
            },
        )
        val normalizedTrack = normalize(requestedSnapshot).tracks.firstOrNull { it.id == trackId }
        if (normalizedTrack?.artworkPath != newArtworkPath) {
            if (newArtworkPath != existingTrack.artworkPath) deleteOwnedArtworkFile(trackId, newArtworkPath)
            throw IOException("The selected cover image could not be added to the local library.")
        }

        val savedSnapshot = try {
            persist(requestedSnapshot)
            val readback = load()
            if (readback.tracks.firstOrNull { it.id == trackId }?.artworkPath != newArtworkPath) {
                throw IOException("The updated cover could not be read back from the local library.")
            }
            readback
        } catch (error: Exception) {
            val stateAfterFailure = runCatching { load() }.getOrNull()
            val replacementIsCommitted = stateAfterFailure?.tracks
                ?.firstOrNull { it.id == trackId }
                ?.artworkPath == newArtworkPath
            if (replacementIsCommitted) {
                if (existingTrack.artworkPath != newArtworkPath) {
                    runCatching { deleteOwnedArtworkFile(trackId, existingTrack.artworkPath) }
                }
                return@synchronized requireNotNull(stateAfterFailure)
            }
            if (stateAfterFailure != null &&
                stateAfterFailure.tracks.none { it.artworkPath == newArtworkPath } &&
                newArtworkPath != existingTrack.artworkPath
            ) {
                deleteOwnedArtworkFile(trackId, newArtworkPath)
            }
            throw error
        }

        if (existingTrack.artworkPath != newArtworkPath) {
            runCatching { deleteOwnedArtworkFile(trackId, existingTrack.artworkPath) }
        }
        savedSnapshot
    }

    private fun decodeStoredSnapshot(encoded: String?): LibrarySnapshot {
        if (encoded == null) return LibrarySnapshot()
        val stored = LibraryStateCodec.decode(encoded)
        val normalized = normalize(stored)
        return LibraryStateCodec.requireNoDataLoss(stored, normalized)
    }

    private fun cleanupAbandonedImportArtifacts(snapshot: LibrarySnapshot): Boolean {
        val root = audioDirectory
        if (Files.isSymbolicLink(root.toPath())) return false
        if (!root.exists()) return true
        if (!root.isDirectory || !PrivateAudioPath.isDirectChild(appContext.filesDir, root)) return false
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val referencedPaths = runCatching {
            snapshot.tracks
                .flatMap { track -> listOfNotNull(track.filePath, track.artworkPath) }
                .mapTo(hashSetOf()) { path -> File(path).canonicalPath }
        }.getOrNull() ?: return false
        val children = root.listFiles() ?: return false
        var complete = true

        for (candidate in children) {
            val candidatePath = candidate.toPath()
            try {
                if (!isAbandonedImportArtifactName(candidate.name)) continue
                if (Files.isSymbolicLink(candidatePath)) continue
                if (!Files.isRegularFile(candidatePath, LinkOption.NOFOLLOW_LINKS)) continue
                if (!PrivateAudioPath.isDirectChild(root, candidate)) {
                    complete = false
                    continue
                }
                val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull()
                if (canonicalCandidate == null || canonicalCandidate.parentFile != canonicalRoot) {
                    complete = false
                    continue
                }
                if (canonicalCandidate.path in referencedPaths) continue

                // Recheck the same direct regular file immediately before unlinking it.
                if (Files.isSymbolicLink(candidatePath) ||
                    !Files.isRegularFile(candidatePath, LinkOption.NOFOLLOW_LINKS) ||
                    !PrivateAudioPath.isDirectChild(root, candidate) ||
                    candidate.canonicalFile != canonicalCandidate
                ) {
                    complete = false
                    continue
                }
                Files.deleteIfExists(candidatePath)
            } catch (_: Exception) {
                complete = false
            }
        }
        return complete
    }

    private fun isAbandonedImportArtifactName(fileName: String): Boolean {
        if (fileName.endsWith(".part")) return isCanonicalImportId(fileName.removeSuffix(".part"))
        if (fileName.endsWith(".img")) {
            val stem = fileName.removeSuffix(".img")
            val trackId = stem.substringBefore("-cover-")
            return TrackArtworkFileName.isGeneratedCoverFileName(fileName) ||
                (isCanonicalImportId(trackId) && TrackArtworkFileName.isGeneratedFileName(trackId, fileName))
        }

        val extensionSeparator = fileName.lastIndexOf('.')
        if (extensionSeparator <= 0) return false
        val id = fileName.substring(0, extensionSeparator)
        return isCanonicalImportId(id) && TrackFileName.isGeneratedFileName(id, fileName)
    }

    private fun isCanonicalImportId(id: String): Boolean =
        VALID_UUID.matches(id) && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private fun normalize(snapshot: LibrarySnapshot): LibrarySnapshot {
        val tracks = snapshot.tracks.mapNotNull(::validateTrack).distinctBy(Track::id)
        val knownTrackIds = tracks.mapTo(hashSetOf(), Track::id)
        val validPlaylists = snapshot.playlists
            .filter { it.id.matches(VALID_ID) && it.name.isNotBlank() }
            .distinctBy(Playlist::id)
            .distinctBy { it.name.trim().lowercase(Locale.ROOT) }
            .map { playlist ->
                playlist.copy(
                    name = playlist.name.trim().take(MAX_PLAYLIST_NAME_LENGTH),
                    trackIds = playlist.trackIds.filter { it in knownTrackIds }.distinct(),
                )
            }
        val favorites = snapshot.favoriteTrackIds.filterTo(hashSetOf()) { it in knownTrackIds }
        return LibrarySnapshot(tracks, validPlaylists, favorites)
    }

    private fun validateTrack(track: Track): Track? {
        if (!track.id.matches(VALID_ID)) return null
        val audioFile = File(track.filePath)
        if (!TrackFileName.isGeneratedFileName(track.id, audioFile.name)) return null
        if (!isOwnedAudioFile(audioFile) || !audioFile.isFile) return null
        val artworkPath = track.artworkPath?.let { rawPath ->
            val artworkFile = File(rawPath)
            if (TrackArtworkFileName.isGeneratedFileName(track.id, artworkFile.name) &&
                !Files.isSymbolicLink(artworkFile.toPath()) &&
                Files.isRegularFile(artworkFile.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                isOwnedAudioFile(artworkFile)
            ) {
                artworkFile.canonicalPath
            } else {
                null
            }
        }
        return track.copy(filePath = audioFile.canonicalPath, artworkPath = artworkPath)
    }

    private fun canonicalOwnedArtworkPath(trackId: String, path: String): String {
        val file = File(path)
        if (!TrackArtworkFileName.isGeneratedFileName(trackId, file.name) ||
            Files.isSymbolicLink(file.toPath()) ||
            !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            !isOwnedAudioFile(file)
        ) {
            throw SecurityException("The selected cover was not stored as an owned private image.")
        }
        return file.canonicalPath
    }

    private fun persist(
        snapshot: LibrarySnapshot,
        requiredTrackId: String? = null,
        dispatchPermit: RestoreActionGate.DispatchPermit? = null,
        dispatchOutcomeChange: ExternalImportDispatchOutcomeChange? = null,
    ): LibrarySnapshot {
        check(Thread.holdsLock(importArtifactLock)) {
            "Library-state writes must hold the process-wide repository transaction lock."
        }
        val normalized = normalize(snapshot)
        if (requiredTrackId != null && normalized.tracks.none { it.id == requiredTrackId }) {
            throw IOException("The selected audio file could not be added to the local library.")
        }
        val encoded = LibraryStateCodec.encode(normalized)
        val persisted = if (dispatchPermit == null) {
            restoreActionGate.runIfAllowed {
                writeStateAndDispatchOutcome(encoded, dispatchOutcomeChange)
            }
        } else {
            restoreActionGate.runIfAllowed(dispatchPermit) {
                writeStateAndDispatchOutcome(encoded, dispatchOutcomeChange)
            }
        }
        if (!persisted) {
            throw IOException("Restore recovery is pending; the local music library was not changed.")
        }
        return normalized
    }

    private fun writeStateAndDispatchOutcome(
        encodedState: String,
        outcomeChange: ExternalImportDispatchOutcomeChange?,
    ) {
        if (outcomeChange == null) {
            if (!stateStore.write(encodedState)) {
                throw IOException("Could not save the local music library.")
            }
            return
        }

        val outcomes = readExternalImportDispatchOutcomes()
        if (outcomeChange.mayHaveStarted) {
            outcomes.remove(outcomeChange.requestId)
            outcomes.add(outcomeChange.requestId)
            while (outcomes.size > MAX_EXTERNAL_IMPORT_DISPATCH_OUTCOMES) {
                val oldest = outcomes.iterator()
                oldest.next()
                oldest.remove()
            }
        } else {
            outcomes.remove(outcomeChange.requestId)
        }
        @SuppressLint("UseKtx") // Commit the Track marker and request-level outcome in one synchronous transaction.
        val committed = preferences.edit()
            .putString(STATE_KEY, encodedState)
            .putString(EXTERNAL_IMPORT_DISPATCH_OUTCOMES_KEY, outcomes.joinToString("\n"))
            .commit()
        if (!committed) {
            throw IOException("Could not save the local music library.")
        }
    }

    private fun readExternalImportDispatchOutcomes(): LinkedHashSet<String> {
        val encoded = preferences.getString(EXTERNAL_IMPORT_DISPATCH_OUTCOMES_KEY, null) ?: return LinkedHashSet()
        return encoded.takeLast(MAX_EXTERNAL_IMPORT_DISPATCH_OUTCOMES * 37)
            .lineSequence()
            .filter { VALID_UUID.matches(it) }
            .toCollection(LinkedHashSet())
    }

    private fun migrateLegacyExternalImportDispatchOutcomes(snapshot: LibrarySnapshot) {
        check(Thread.holdsLock(importArtifactLock)) {
            "Legacy dispatch outcomes must be migrated under the process-wide repository transaction lock."
        }
        val legacyRequestIds = snapshot.tracks.asSequence()
            .filter { track ->
                track.externalImportHandoffCompleted ||
                    track.externalImportHandoffDispatchStarted == true ||
                    (track.externalImportHandoffClaimed && track.externalImportHandoffDispatchStarted == null)
            }
            .mapNotNull { track -> track.externalImportRequestId?.takeIf(VALID_UUID::matches) }
            .toCollection(LinkedHashSet())
        if (legacyRequestIds.isEmpty()) return

        val outcomes = readExternalImportDispatchOutcomes()
        val missingRequestIds = legacyRequestIds.filterNot(outcomes::contains)
        if (missingRequestIds.isEmpty()) return
        missingRequestIds.forEach { requestId ->
            outcomes.remove(requestId)
            outcomes.add(requestId)
        }
        if (outcomes.size > MAX_EXTERNAL_IMPORT_DISPATCH_OUTCOMES) {
            throw IOException("Could not preserve all pending external playback outcomes before restore.")
        }

        @SuppressLint("UseKtx") // Persist all legacy request outcomes before allowing the library snapshot to be replaced.
        val committed = preferences.edit()
            .putString(EXTERNAL_IMPORT_DISPATCH_OUTCOMES_KEY, outcomes.joinToString("\n"))
            .commit()
        if (!committed) throw IOException("Could not preserve external playback outcomes before restore.")
    }

    private fun deleteOwnedFiles(track: Track) {
        var cleanupFailure: Throwable? = null
        fun recordCleanupFailure(failure: Throwable) {
            val previous = cleanupFailure
            if (previous == null) cleanupFailure = failure else previous.addSuppressed(failure)
        }
        fun attemptCleanup(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                recordCleanupFailure(failure)
            }
        }
        val changedDirectories = linkedSetOf<File>()
        val directorySyncFailures = linkedMapOf<File, Throwable>()
        fun syncDirectoryAfterDelete(directory: File) {
            try {
                durability.syncDirectory(directory)
                directorySyncFailures.remove(directory)
            } catch (failure: Throwable) {
                directorySyncFailures[directory] = failure
            }
        }
        fun syncDeletedFileParent(file: File) {
            val parent = file.parentFile ?: throw IOException("A private library file has no parent directory.")
            changedDirectories += parent
            syncDirectoryAfterDelete(parent)
        }

        val audioFile = File(track.filePath)
        attemptCleanup {
            if (TrackFileName.isGeneratedFileName(track.id, audioFile.name) && isOwnedAudioFile(audioFile) && audioFile.isFile) {
                if (audioFile.delete()) {
                    syncDeletedFileParent(audioFile)
                } else if (audioFile.exists()) {
                    throw IOException("Could not remove an unreferenced private audio file.")
                }
            }
        }

        track.artworkPath?.let { artworkPath ->
            attemptCleanup {
                val removedArtwork = deleteOwnedArtworkFileEntry(track.id, artworkPath)
                if (removedArtwork != null) {
                    syncDeletedFileParent(removedArtwork)
                }
            }
        }
        changedDirectories.forEach { directory ->
            if (directory !in directorySyncFailures) {
                attemptCleanup { cleanupEmptyRestoreGeneration(directory) }
            }
        }
        directorySyncFailures.values.forEach(::recordCleanupFailure)
        cleanupFailure?.let { throw it }
    }

    private fun deleteOwnedArtworkFile(trackId: String, path: String?) {
        val artworkFile = deleteOwnedArtworkFileEntry(trackId, path) ?: return
        val parent = artworkFile.parentFile ?: throw IOException("A private artwork file has no parent directory.")
        durability.syncDirectory(parent)
        cleanupEmptyRestoreGeneration(parent)
    }

    private fun deleteOwnedArtworkFileEntry(trackId: String, path: String?): File? {
        val artworkFile = path?.let(::File) ?: return null
        if (!TrackArtworkFileName.isGeneratedFileName(trackId, artworkFile.name) ||
            Files.isSymbolicLink(artworkFile.toPath()) ||
            !Files.isRegularFile(artworkFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            !isOwnedAudioFile(artworkFile)
        ) return null
        return if (Files.deleteIfExists(artworkFile.toPath())) artworkFile else null
    }

    private fun cleanupEmptyRestoreGeneration(directory: File?) {
        if (directory == null) return
        val id = PrivateAudioPath.restoreGenerationId(directory.name) ?: return
        if (!PrivateAudioPath.isDirectChild(audioDirectory, directory)) return
        val expectedGenerationKey = PrivateAudioPath.directoryFileKeyNoFollow(directory) ?: return
        val generationName = audioDirectory.toPath().fileSystem.getPath(directory.name)
        val markerName = audioDirectory.toPath().fileSystem.getPath(PrivateAudioPath.restoreOwnerMarkerName())
        val audioDirectoryHandle = PrivateAudioPath.openDirectoryNoFollow(audioDirectory)
        audioDirectoryHandle.use { audioHandle ->
            if (!PrivateAudioPath.isSameDirectory(audioDirectory, audioHandle)) return
            val generationHandle = try {
                audioHandle.newDirectoryStream(generationName, LinkOption.NOFOLLOW_LINKS)
            } catch (missing: java.nio.file.NoSuchFileException) {
                return
            }
            generationHandle.use { openedGeneration ->
                if (!PrivateAudioPath.isOpenedDirectoryKey(expectedGenerationKey, openedGeneration) ||
                    !PrivateAudioPath.isSameDirectory(directory, openedGeneration)
                ) return
                if (!PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(openedGeneration, markerName, id)) return
                val childNames = openedGeneration.map { it.fileName?.toString() ?: return }.toList()
                if (childNames.size != 1 || childNames.single() != markerName.toString()) return

                openedGeneration.deleteFile(markerName)
                try {
                    durability.syncDirectory(directory, openedGeneration)
                } catch (syncFailure: Throwable) {
                    try {
                        restoreRestoreGenerationOwnerMarker(openedGeneration, directory, id)
                        durability.syncDirectory(directory, openedGeneration)
                    } catch (restoreFailure: Throwable) {
                        if (restoreFailure !== syncFailure) syncFailure.addSuppressed(restoreFailure)
                    }
                    throw syncFailure
                }
                try {
                    if (!PrivateAudioPath.isOpenedDirectoryKey(expectedGenerationKey, openedGeneration) ||
                        !PrivateAudioPath.isSameDirectory(directory, openedGeneration) ||
                        !PrivateAudioPath.isSameDirectory(audioDirectory, audioHandle) ||
                        PrivateAudioPath.directoryFileKeyNoFollow(directory) != expectedGenerationKey
                    ) throw IOException("The restore-generation directory changed before deletion.")
                    audioHandle.deleteDirectory(generationName)
                } catch (deleteFailure: Throwable) {
                    try {
                        restoreRestoreGenerationOwnerMarker(openedGeneration, directory, id)
                        durability.syncDirectory(directory, openedGeneration)
                    } catch (restoreFailure: Throwable) {
                        if (restoreFailure !== deleteFailure) deleteFailure.addSuppressed(restoreFailure)
                    }
                    throw deleteFailure
                }
                durability.syncDirectory(audioDirectory, audioHandle)
            }
        }
    }

    private fun restoreRestoreGenerationOwnerMarker(
        directoryHandle: SecureDirectoryStream<Path>,
        directory: File,
        id: String,
    ) {
        if (PrivateAudioPath.restoreGenerationId(directory.name) != id) {
            throw SecurityException("The restore-generation directory changed during cleanup recovery.")
        }
        val markerName = directory.toPath().fileSystem.getPath(PrivateAudioPath.restoreOwnerMarkerName())
        val marker = File(directory, PrivateAudioPath.restoreOwnerMarkerName())
        val alreadyValid = PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(directoryHandle, markerName, id)
        val options = if (alreadyValid) {
            setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        } else {
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        }
        directoryHandle.newByteChannel(markerName, options).use { channel ->
            if (!alreadyValid) {
            val expectedBytes = id.toByteArray(Charsets.UTF_8)
                val bytesToWrite = ByteBuffer.wrap(expectedBytes)
                while (bytesToWrite.hasRemaining()) {
                    if (channel.write(bytesToWrite) <= 0) throw IOException("Could not write the restore-generation owner marker.")
                }
            }
            val fileChannel = channel as? FileChannel
                ?: throw IOException("The secure filesystem did not provide a durable marker channel.")
            durability.syncFile(marker, fileChannel)
        }
        if (!PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(directoryHandle, markerName, id)) {
            throw SecurityException("The restore-generation owner marker changed during cleanup recovery.")
        }
    }

    private fun isOwnedAudioFile(file: File): Boolean =
        PrivateAudioPath.isDirectChild(audioDirectory, file) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file)

    private fun ensureAudioDirectory() {
        if (!PrivateAudioPath.isDirectChild(appContext.filesDir, audioDirectory)) {
            throw SecurityException("The app-private audio directory is redirected or unsafe.")
        }
        if (!audioDirectory.exists() && !audioDirectory.mkdirs()) throw IOException("Could not create the private audio directory.")
        if (!audioDirectory.isDirectory || !PrivateAudioPath.isDirectChild(appContext.filesDir, audioDirectory)) {
            throw IOException("The private audio directory is unavailable or redirected.")
        }
        // The library snapshot may commit only after the containing directory entry is durable.
        durability.syncDirectory(appContext.filesDir)
    }

    private fun ensureCacheDirectory() {
        val directory = appContext.cacheDir
        if (!PrivateAudioPath.isDirectChild(appContext.dataDir, directory)) {
            throw SecurityException("The app-private cache directory is redirected or unsafe.")
        }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create private archive staging storage.")
        if (!directory.isDirectory || !PrivateAudioPath.isDirectChild(appContext.dataDir, directory)) {
            throw SecurityException("The app-private cache directory is unavailable or redirected.")
        }
    }

    private fun availableBytes(): Long = availableBytesOverride?.invoke()
        ?: storageManager.getAllocatableBytes(storageManager.getUuidForPath(audioDirectory))

    private fun addReservedSpace(bytes: Long): Long = try {
        Math.addExact(bytes, LibraryBackupLimits.DEFAULT.reservedSpaceBytes)
    } catch (error: ArithmeticException) {
        throw IOException("The selected backup is too large.", error)
    }

    private fun preparedArchiveFileOrNull(prepared: PreparedBackupArchive): File? {
        if (!VALID_UUID.matches(prepared.id)) return null
        if (!PreparedBackupArchiveFiles.isOwned(appContext.cacheDir, prepared.id)) return null
        return runCatching { PreparedBackupArchiveFiles.archiveFile(appContext.cacheDir, prepared.id) }.getOrNull()
    }

    companion object {
        private val importArtifactLock = Any()

        @Volatile
        private var processRepository: LocalLibraryRepository? = null

        internal fun shared(context: Context): LocalLibraryRepository = processRepository ?: synchronized(this) {
            processRepository ?: LocalLibraryRepository(context.applicationContext).also { processRepository = it }
        }

        const val PREFERENCES_NAME = "aftertone_library_v1"
        const val STATE_KEY = "library_state"
        private const val RECOVERY_DIAGNOSTIC_ATTEMPT_TRACE_KEY = "recovery_diagnostic_attempt_trace_v1"
        private const val SCOPED_RECOVERY_ATTEMPT_STATE_KEY = "recovery_scoped_cleanup_attempt_v1"
        private const val SCOPED_RECOVERY_ATTEMPT_STARTED = "started"
        private const val SCOPED_RECOVERY_ATTEMPT_COMPLETE = "complete"
        private const val SCOPED_RECOVERY_ATTEMPT_FAILED = "failed"
        const val MAX_PLAYLIST_NAME_LENGTH = 48
        private const val EXTERNAL_IMPORT_DISPATCH_OUTCOMES_KEY = "external_import_dispatch_outcomes"
        // Room for every supported library Track plus the previous bounded request-history window.
        private const val MAX_EXTERNAL_IMPORT_DISPATCH_OUTCOMES = 20_256
        val VALID_ID = Regex("[A-Za-z0-9_-]{1,80}")
        val VALID_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
