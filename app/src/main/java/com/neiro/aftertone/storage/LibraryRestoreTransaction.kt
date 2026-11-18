package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.model.TrackArtworkFileName
import com.neiro.aftertone.model.TrackFileName
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.UUID

private const val ORPHAN_CLEANUP_PENDING_ID = "orphan-cleanup-pending"

internal interface LibraryStateStore {
    fun read(): String?
    fun write(encoded: String?): Boolean
}

internal data class PendingLibraryRestore(
    val transactionId: String,
    val encodedState: String,
    internal val previousEncodedState: String?,
    internal val previousSnapshot: LibrarySnapshot,
    internal val snapshot: LibrarySnapshot,
    internal val stagingDirectory: File,
    internal val generationDirectory: File,
    internal val integrity: List<RestoredAssetIntegrity>,
)

internal data class LibraryRestoreResult(
    val transactionId: String,
    val snapshot: LibrarySnapshot,
    val previousSnapshot: LibrarySnapshot,
)

internal data class RestoredAssetIntegrity(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal enum class RestoreFinalizationResult {
    COMMITTED_AND_CLEANED,
    ROLLED_BACK_AND_CLEANED,
    COMMITTED_CLEANUP_PENDING,
    ROLLED_BACK_CLEANUP_PENDING,
    CLEANUP_PENDING;

    val cleanupComplete: Boolean
        get() = this == COMMITTED_AND_CLEANED || this == ROLLED_BACK_AND_CLEANED

    val rolledBack: Boolean
        get() = this == ROLLED_BACK_AND_CLEANED || this == ROLLED_BACK_CLEANUP_PENDING
}

internal class LibraryRestoreTransaction(
    private val filesDirectory: File,
    private val audioDirectory: File,
    private val stateStore: LibraryStateStore,
    private val durability: RestoreFileDurability = SystemRestoreFileDurability,
    private val beforeOpeningPreviousFileDirectory: (File) -> Unit = {},
    private val decodeSnapshot: (String?) -> LibrarySnapshot,
) {
    private val recoveryJournalFileAccess = RecoveryJournalFileAccess()
    private var recoveryDiagnosticEventSink: ((RestoreRecoveryAttemptEvent) -> Unit)? = null

    fun createStagingDirectory(): File {
        requireSafeAudioDirectory()
        if (!audioDirectory.exists() && !audioDirectory.mkdirs()) {
            throw IOException("Could not create private restore storage.")
        }
        if (!audioDirectory.isDirectory) throw IOException("The private audio directory is unavailable.")
        val id = UUID.randomUUID().toString()
        val directory = File(audioDirectory, "$STAGING_PREFIX$id")
        if (!PrivateAudioPath.isDirectChild(audioDirectory, directory) || !directory.mkdir()) {
            throw IOException("Could not create private restore staging storage.")
        }
        try {
            durability.syncDirectory(audioDirectory)
            writeOwnerMarker(directory, id)
            durability.syncDirectory(directory)
            if (!isOwnedStagingDirectory(directory, id)) throw SecurityException("The restore staging directory is not owned by this operation.")
            return directory
        } catch (error: Exception) {
            cleanupStagingDirectory(directory, id)
            runCatching { durability.syncDirectory(audioDirectory) }
            throw error
        }
    }

    fun discardStagingDirectory(directory: File): Boolean {
        if (!PrivateAudioPath.isDirectChild(filesDirectory, audioDirectory)) return false
        val id = PrivateAudioPath.restoreStageId(directory.name) ?: return false
        return cleanupStagingDirectory(directory, id)
    }

    fun prepareRestore(
        stagingDirectory: File,
        prepared: PreparedLibraryBackup,
        checkCancelled: () -> Unit = {},
    ): PendingLibraryRestore {
        requireSafeAudioDirectory()
        val transactionId = PrivateAudioPath.restoreStageId(stagingDirectory.name)
            ?: throw SecurityException("The restore staging path is not owned by NoMute.")
        if (!isOwnedStagingDirectory(stagingDirectory, transactionId)) {
            throw SecurityException("The restore staging directory could not be verified.")
        }
        val previousEncoded = stateStore.read()
        val previousSnapshot = decodeSnapshot(previousEncoded)
        val assets = validateStagedAssets(stagingDirectory, prepared)
        assets.forEach { asset ->
            checkCancelled()
            val file = File(stagingDirectory, asset.entryName.substringAfter('/'))
            FileOutputStream(file, true).use(durability::syncFile)
        }
        durability.syncDirectory(stagingDirectory)
        val expectedNames = assets.map { it.entryName }.toSet()
        require(prepared.assetSha256ByEntry.isEmpty() || prepared.assetSha256ByEntry.keys == expectedNames) {
            "The restored file integrity records do not match the archive."
        }
        val integrity = assets.map { asset ->
            checkCancelled()
            val fileName = asset.entryName.substringAfter('/')
            val digest = prepared.assetSha256ByEntry[asset.entryName]
                ?: sha256(File(stagingDirectory, fileName), checkCancelled)
            require(SHA256_PATTERN.matches(digest)) { "The restored file integrity record is invalid." }
            RestoredAssetIntegrity(fileName, asset.sizeBytes, digest)
        }
        val portableState = LibraryStateCodec.encode(prepared.snapshot)
        LibraryBackupManifestCodec.encode(portableState, assets)

        val generationDirectory = File(audioDirectory, "$GENERATION_PREFIX$transactionId")
        if (!PrivateAudioPath.isDirectChild(audioDirectory, generationDirectory) || generationDirectory.exists()) {
            throw SecurityException("The restore destination is not a new private directory.")
        }
        val restoredSnapshot = remapToGeneration(prepared.snapshot, generationDirectory)
        val encodedState = LibraryStateCodec.encode(restoredSnapshot)
        val pending = PendingLibraryRestore(
            transactionId = transactionId,
            encodedState = encodedState,
            previousEncodedState = previousEncoded,
            previousSnapshot = previousSnapshot,
            snapshot = restoredSnapshot,
            stagingDirectory = stagingDirectory,
            generationDirectory = generationDirectory,
            integrity = integrity,
        )

        var journalWritten = false
        var generationMoved = false
        try {
            writeJournal(pending)
            journalWritten = true
            if (!stagingDirectory.renameTo(generationDirectory)) {
                throw IOException("Could not move the verified backup into private storage.")
            }
            generationMoved = true
            durability.syncDirectory(audioDirectory)
            decodeSnapshot(encodedState)
            return pending
        } catch (error: Exception) {
            if (journalWritten && stateStore.read() == previousEncoded) {
                val filesRemoved = if (generationMoved) {
                    cleanupGenerationDirectory(generationDirectory, transactionId)
                } else {
                    cleanupStagingDirectory(stagingDirectory, transactionId)
                }
                if (filesRemoved) deleteJournal(transactionId)
            }
            throw error
        }
    }

    fun commitRestore(
        pending: PendingLibraryRestore,
        beforeCommit: () -> Unit = {},
    ): LibraryRestoreResult {
        requireSafeAudioDirectory()
        val journal = readJournal()
        if (journal?.transactionId != pending.transactionId ||
            journal.previousEncodedState != pending.previousEncodedState ||
            journal.encodedState != pending.encodedState ||
            stateStore.read() != pending.previousEncodedState
        ) {
            throw IOException("The restore transaction changed before it could be saved.")
        }
        try {
            beforeCommit()
            if (!stateStore.write(pending.encodedState) || stateStore.read() != pending.encodedState) {
                throw IOException("Could not save the restored library state.")
            }
            return LibraryRestoreResult(pending.transactionId, pending.snapshot, pending.previousSnapshot)
        } catch (error: Exception) {
            val rolledBack = runCatching {
                stateStore.write(pending.previousEncodedState) && stateStore.read() == pending.previousEncodedState
            }.getOrDefault(false)
            if (rolledBack) {
                if (cleanupGenerationDirectory(pending.generationDirectory, pending.transactionId)) {
                    deleteJournal(pending.transactionId)
                }
            }
            if (rolledBack && error is CancellationException) throw error
            throw IOException(
                if (rolledBack) "The restore could not be saved; the previous library was kept."
                else "The restore could not be saved safely; both library copies were kept for recovery.",
                error,
            )
        }
    }

    fun restore(stagingDirectory: File, prepared: PreparedLibraryBackup): LibraryRestoreResult =
        commitRestore(prepareRestore(stagingDirectory, prepared))

    fun finishRestore(transactionId: String): Boolean =
        finishRestoreOutcome(transactionId).cleanupComplete

    fun finishRestoreOutcome(transactionId: String): RestoreFinalizationResult {
        requireSafeAudioDirectory()
        val journal = readJournal() ?: return if (retryAbandonedRecoveryCleanup(forceSyncDirectories = true)) {
            RestoreFinalizationResult.COMMITTED_AND_CLEANED
        } else {
            RestoreFinalizationResult.CLEANUP_PENDING
        }
        if (journal.transactionId != transactionId) return RestoreFinalizationResult.CLEANUP_PENDING

        return when (stateStore.read()) {
            journal.encodedState -> {
                if (finishCommittedJournal(journal)) {
                    if (deleteJournal(transactionId)) RestoreFinalizationResult.COMMITTED_AND_CLEANED
                    else RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING
                } else if (restorePreviousStateIfNewGenerationIsInvalid(journal)) {
                    cleanupRolledBackJournal(journal)
                } else {
                    val committedGenerationIsValid = runCatching {
                        isCommittedGenerationValid(journal, decodeSnapshot(journal.encodedState))
                    }.getOrDefault(false)
                    if (committedGenerationIsValid) RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING
                    else RestoreFinalizationResult.CLEANUP_PENDING
                }
            }
            journal.previousEncodedState -> cleanupRolledBackJournal(journal)
            else -> RestoreFinalizationResult.CLEANUP_PENDING
        }
    }

    private fun cleanupRolledBackJournal(journal: RestoreJournal): RestoreFinalizationResult {
        val generationRemoved = cleanupGenerationDirectory(
            File(audioDirectory, journal.generationDirectoryName),
            journal.transactionId,
        )
        val stagingRemoved = cleanupStagingDirectory(
            File(audioDirectory, journal.stagingDirectoryName),
            journal.transactionId,
        )
        return if (generationRemoved && stagingRemoved && deleteJournal(journal.transactionId)) {
            RestoreFinalizationResult.ROLLED_BACK_AND_CLEANED
        } else {
            RestoreFinalizationResult.ROLLED_BACK_CLEANUP_PENDING
        }
    }

    @Synchronized
    fun recoverInterruptedRestore(skipNoJournalCleanup: Boolean = false): String? =
        recoverInterruptedRestoreInternal(skipNoJournalCleanup)

    @Synchronized
    internal fun recoverInterruptedRestore(
        onDiagnosticEvent: (RestoreRecoveryAttemptEvent) -> Unit,
    ): String? {
        val previousSink = recoveryDiagnosticEventSink
        recoveryDiagnosticEventSink = onDiagnosticEvent
        return try {
            recoverInterruptedRestoreInternal(skipNoJournalCleanup = false)
        } finally {
            recoveryDiagnosticEventSink = previousSink
        }
    }

    private fun recoverInterruptedRestoreInternal(skipNoJournalCleanup: Boolean): String? {
        recoveryStage(RestoreRecoveryAttemptStage.STORAGE_PREFLIGHT) {
            requireSafeAudioDirectory()
            true
        }
        val journal = recoveryStage(RestoreRecoveryAttemptStage.JOURNAL_READ) { readJournal() }
        if (journal == null) {
            recordRecoveryEvent(
                RestoreRecoveryAttemptStage.STATE_CLASSIFICATION,
                RestoreRecoveryAttemptOutcome.NO_JOURNAL,
            )
            // A successful unlink followed by a failed parent fsync leaves no candidate to rediscover.
            val cleanupComplete = skipNoJournalCleanup || retryAbandonedRecoveryCleanup(forceSyncDirectories = true)
            recordRecoveryEvent(
                RestoreRecoveryAttemptStage.RECOVERY_RESULT,
                if (cleanupComplete) RestoreRecoveryAttemptOutcome.SUCCEEDED else RestoreRecoveryAttemptOutcome.PENDING,
            )
            return if (cleanupComplete) null else ORPHAN_CLEANUP_PENDING_ID
        }
        val storedState = recoveryStage(RestoreRecoveryAttemptStage.STATE_READ, stateStore::read)
        val cleanupComplete = when (storedState) {
            journal.encodedState -> {
                recordRecoveryEvent(
                    RestoreRecoveryAttemptStage.STATE_CLASSIFICATION,
                    RestoreRecoveryAttemptOutcome.COMMITTED_STATE,
                )
                if (finishCommittedJournal(journal)) {
                    recoveryStage(RestoreRecoveryAttemptStage.DELETE_JOURNAL) {
                        deleteJournal(journal.transactionId)
                    }
                } else if (recoveryStage(RestoreRecoveryAttemptStage.ROLLBACK_STATE) {
                        restorePreviousStateIfNewGenerationIsInvalid(journal)
                    }
                ) {
                    val generationRemoved = recoveryStage(RestoreRecoveryAttemptStage.CLEAN_RESTORE_GENERATION) {
                        cleanupGenerationDirectory(
                            File(audioDirectory, journal.generationDirectoryName),
                            journal.transactionId,
                        )
                    }
                    val stagingRemoved = recoveryStage(RestoreRecoveryAttemptStage.CLEAN_RESTORE_STAGING) {
                        cleanupStagingDirectory(
                            File(audioDirectory, journal.stagingDirectoryName),
                            journal.transactionId,
                        )
                    }
                    generationRemoved && stagingRemoved &&
                        recoveryStage(RestoreRecoveryAttemptStage.DELETE_JOURNAL) {
                            deleteJournal(journal.transactionId)
                        }
                } else {
                    false
                }
            }
            journal.previousEncodedState -> {
                recordRecoveryEvent(
                    RestoreRecoveryAttemptStage.STATE_CLASSIFICATION,
                    RestoreRecoveryAttemptOutcome.PREVIOUS_STATE,
                )
                val generationRemoved = recoveryStage(RestoreRecoveryAttemptStage.CLEAN_RESTORE_GENERATION) {
                    cleanupGenerationDirectory(
                        File(audioDirectory, journal.generationDirectoryName),
                        journal.transactionId,
                    )
                }
                val stagingRemoved = recoveryStage(RestoreRecoveryAttemptStage.CLEAN_RESTORE_STAGING) {
                    cleanupStagingDirectory(
                        File(audioDirectory, journal.stagingDirectoryName),
                        journal.transactionId,
                    )
                }
                generationRemoved && stagingRemoved &&
                    recoveryStage(RestoreRecoveryAttemptStage.DELETE_JOURNAL) {
                        deleteJournal(journal.transactionId)
                    }
            }
            else -> {
                recordRecoveryEvent(
                    RestoreRecoveryAttemptStage.STATE_CLASSIFICATION,
                    RestoreRecoveryAttemptOutcome.STATE_MISMATCH,
                )
                false
            }
        }
        recordRecoveryEvent(
            RestoreRecoveryAttemptStage.RECOVERY_RESULT,
            if (cleanupComplete) RestoreRecoveryAttemptOutcome.SUCCEEDED else RestoreRecoveryAttemptOutcome.PENDING,
        )
        return journal.transactionId.takeUnless { cleanupComplete }
    }

    private fun finishCommittedJournal(journal: RestoreJournal): Boolean = try {
        val previous = journal.previousEncodedState?.let { encoded ->
            recoveryStage(RestoreRecoveryAttemptStage.DECODE_PREVIOUS_STATE) {
                LibraryStateCodec.decode(encoded)
            }
        } ?: LibrarySnapshot()
        val current = recoveryStage(RestoreRecoveryAttemptStage.DECODE_COMMITTED_STATE) {
            decodeSnapshot(journal.encodedState)
        }
        if (!recoveryStage(RestoreRecoveryAttemptStage.VERIFY_ACTIVE_GENERATION) {
                isCommittedGenerationValid(journal, current)
            }
        ) return false
        if (!recoveryStage(RestoreRecoveryAttemptStage.CLEAN_PREVIOUS_GENERATIONS) {
                cleanupEmptyPreviousGenerations(previous)
            }
        ) return false
        if (!recoveryStage(RestoreRecoveryAttemptStage.CLEAN_REPLACED_MEDIA) {
                cleanupReplacedFiles(previous, current)
            }
        ) return false
        recoveryStage(RestoreRecoveryAttemptStage.CLEAN_PREVIOUS_GENERATIONS) {
            cleanupEmptyPreviousGenerations(previous)
        }
    } catch (error: RestoreRecoveryDiagnosticTracePersistenceException) {
        throw error
    } catch (_: Throwable) {
        false
    }

    private inline fun <T> recoveryStage(stage: RestoreRecoveryAttemptStage, action: () -> T): T {
        if (recoveryDiagnosticEventSink == null) return action()
        recordRecoveryEvent(stage, RestoreRecoveryAttemptOutcome.STARTED)
        return try {
            val result = action()
            val outcome = if (result == false) RestoreRecoveryAttemptOutcome.FAILED
            else RestoreRecoveryAttemptOutcome.SUCCEEDED
            recordRecoveryEvent(stage, outcome)
            result
        } catch (error: RestoreRecoveryDiagnosticTracePersistenceException) {
            throw error
        } catch (error: Throwable) {
            recordRecoveryEvent(stage, RestoreRecoveryAttemptOutcome.FAILED)
            throw error
        }
    }

    private fun recordRecoveryEvent(
        stage: RestoreRecoveryAttemptStage,
        outcome: RestoreRecoveryAttemptOutcome,
    ) {
        recoveryDiagnosticEventSink?.invoke(RestoreRecoveryAttemptEvent(stage, outcome))
    }

    private fun restorePreviousStateIfNewGenerationIsInvalid(journal: RestoreJournal): Boolean {
        val activeGenerationIsValid = try {
            recoveryStage(RestoreRecoveryAttemptStage.VERIFY_ACTIVE_GENERATION) {
                isCommittedGenerationValid(journal, decodeSnapshot(journal.encodedState))
            }
        } catch (error: RestoreRecoveryDiagnosticTracePersistenceException) {
            throw error
        } catch (_: Throwable) {
            false
        }
        if (activeGenerationIsValid) return false
        if (runCatching { decodeSnapshot(journal.previousEncodedState) }.isFailure) return false
        return runCatching {
            stateStore.write(journal.previousEncodedState) && stateStore.read() == journal.previousEncodedState
        }.getOrDefault(false)
    }

    private fun isCommittedGenerationValid(journal: RestoreJournal, snapshot: LibrarySnapshot): Boolean {
        val generation = File(audioDirectory, journal.generationDirectoryName)
        if (!PrivateAudioPath.isOwnedRestoreDirectory(audioDirectory, generation, journal.transactionId)) return false

        val expectedNames = linkedSetOf<String>()
        snapshot.tracks.forEach { track ->
            val audio = File(track.filePath)
            if (!PrivateAudioPath.isDirectChild(generation, audio) ||
                !TrackFileName.isGeneratedFileName(track.id, audio.name)
            ) return false
            expectedNames += audio.name
            track.artworkPath?.let { path ->
                val artwork = File(path)
                if (!PrivateAudioPath.isDirectChild(generation, artwork) || artwork.name != "${track.id}.img") {
                    return false
                }
                expectedNames += artwork.name
            }
        }
        val records = journal.integrity.associateBy(RestoredAssetIntegrity::fileName)
        if (records.size != journal.integrity.size || records.keys != expectedNames) return false
        return journal.integrity.all { record ->
            val file = File(generation, record.fileName)
            PrivateAudioPath.isDirectChild(generation, file) &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                file.length() == record.sizeBytes &&
                sha256(file) == record.sha256
        }
    }

    private fun sha256(file: File, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun validateStagedAssets(
        stagingDirectory: File,
        prepared: PreparedLibraryBackup,
    ): List<LibraryBackupAsset> {
        val assets = mutableListOf<LibraryBackupAsset>()
        prepared.snapshot.tracks.forEach { track ->
            require(TrackFileName.isGeneratedFileName(track.id, portableFileName(track.filePath, "audio/"))) {
                "The restored library contains an invalid audio reference."
            }
            val audio = File(stagingDirectory, portableFileName(track.filePath, "audio/"))
            require(PrivateAudioPath.isDirectChild(stagingDirectory, audio) && audio.isFile) {
                "The restored audio file is missing from staging."
            }
            assets += LibraryBackupAsset(track.filePath, audio.length())
            track.artworkPath?.let { artworkPath ->
                require(artworkPath == "artwork/${track.id}.img") { "The restored library contains an invalid artwork reference." }
                val artwork = File(stagingDirectory, portableFileName(artworkPath, "artwork/"))
                require(PrivateAudioPath.isDirectChild(stagingDirectory, artwork) && artwork.isFile) {
                    "The restored artwork is missing from staging."
                }
                assets += LibraryBackupAsset(artworkPath, artwork.length())
            }
        }
        val expectedNames = assets.map { it.entryName.substringAfter('/') }.toSet() + OWNER_MARKER
        val children = stagingDirectory.listFiles()?.toList() ?: throw IOException("Could not inspect restore staging files.")
        require(children.size == expectedNames.size && children.all { it.name in expectedNames }) {
            "Restore staging contains an unreferenced or unexpected file."
        }
        require(children.all { child ->
            if (child.name == OWNER_MARKER) {
                isOwnedMarker(stagingDirectory, child, PrivateAudioPath.restoreStageId(stagingDirectory.name)!!)
            } else {
                child.isFile && PrivateAudioPath.isDirectChild(stagingDirectory, child)
            }
        }) { "Restore staging contains an unsafe file." }
        return assets
    }

    private fun remapToGeneration(snapshot: LibrarySnapshot, generationDirectory: File): LibrarySnapshot =
        snapshot.copy(tracks = snapshot.tracks.map { track ->
            val audioName = portableFileName(track.filePath, "audio/")
            require(TrackFileName.isGeneratedFileName(track.id, audioName)) { "The restored library contains an invalid audio reference." }
            val audio = File(generationDirectory, audioName)
            require(PrivateAudioPath.isDirectChild(generationDirectory, audio)) { "The restore path is outside private storage." }
            val artwork = track.artworkPath?.let { path ->
                require(path == "artwork/${track.id}.img") { "The restored library contains an invalid artwork reference." }
                File(generationDirectory, portableFileName(path, "artwork/")).also {
                    require(PrivateAudioPath.isDirectChild(generationDirectory, it)) { "The restore path is outside private storage." }
                }.canonicalPath
            }
            track.copy(filePath = audio.canonicalPath, artworkPath = artwork)
        })

    private fun cleanupReplacedFiles(previous: LibrarySnapshot, current: LibrarySnapshot): Boolean {
        val currentPaths = current.tracks.flatMap { track -> listOfNotNull(track.filePath, track.artworkPath) }.toSet()
        val changedDirectories = linkedMapOf<File, SecureDirectoryStream<Path>>()
        try {
            previous.tracks.forEach { track ->
                val audio = File(track.filePath)
                if (track.filePath !in currentPaths) {
                    if (!deleteOwnedPreviousFile(audio, { isOwnedPreviousTrackAudio(track, audio) }, changedDirectories)) return false
                }
                val artwork = track.artworkPath?.let(::File)
                if (artwork != null && artwork.path !in currentPaths) {
                    if (!deleteOwnedPreviousFile(artwork, { isOwnedPreviousTrackArtwork(track, artwork) }, changedDirectories)) return false
                }
            }
            changedDirectories.forEach { (directory, openedDirectory) ->
                if (!syncDirectoryIfPresent(directory, openedDirectory)) return false
            }
            return true
        } catch (_: Exception) {
            return false
        } finally {
            changedDirectories.values.forEach { opened -> runCatching { opened.close() } }
        }
    }

    private fun deleteOwnedPreviousFile(
        file: File,
        ownershipCheck: () -> Boolean,
        changedDirectories: MutableMap<File, SecureDirectoryStream<Path>>,
    ): Boolean {
        val parent = file.parentFile ?: return false
        val missingParent = isMissingPreviousGenerationDirectory(parent)
        val directory = if (missingParent) audioDirectory else parent
        val expectedDirectoryKey = PrivateAudioPath.directoryFileKeyNoFollow(directory) ?: return false
        val expectedFileKey = if (missingParent) null else PrivateAudioPath.regularFileKeyNoFollow(file.toPath())
        if (!ownershipCheck()) return false

        beforeOpeningPreviousFileDirectory(directory)
        val openedDirectory = changedDirectories.getOrPut(directory) {
            PrivateAudioPath.openDirectoryNoFollow(directory)
        }
        if (!PrivateAudioPath.isOpenedDirectoryKey(expectedDirectoryKey, openedDirectory) ||
            !PrivateAudioPath.isSameDirectory(directory, openedDirectory)
        ) return false

        if (directory == parent) {
            val generationId = PrivateAudioPath.restoreGenerationId(parent.name)
            if (generationId != null && !PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(
                    openedDirectory,
                    directory.toPath().fileSystem.getPath(PrivateAudioPath.restoreOwnerMarkerName()),
                    generationId,
                )
            ) return false

            val childName = directory.toPath().fileSystem.getPath(file.name)
            val currentFileKey = PrivateAudioPath.regularFileKeyNoFollow(openedDirectory, childName)
            if (expectedFileKey != null) {
                if (currentFileKey != expectedFileKey) return false
            } else if (hasEntryNoFollow(openedDirectory, childName)) {
                return false
            }
            if (!PrivateAudioPath.deleteRegularFileNoFollow(openedDirectory, childName, expectedFileKey)) return false
        }
        return true
    }

    private fun cleanupEmptyPreviousGenerations(previous: LibrarySnapshot): Boolean {
        val directories = previous.tracks.flatMap { track ->
            listOfNotNull(File(track.filePath).parentFile, track.artworkPath?.let { File(it).parentFile })
        }.distinct()
        directories.forEach { directory ->
            val id = directory.name.removePrefix(GENERATION_PREFIX)
                .takeIf { directory.name.startsWith(GENERATION_PREFIX) } ?: return@forEach
            if (!directory.exists()) {
                if (Files.isSymbolicLink(directory.toPath()) ||
                    !PrivateAudioPath.isDirectChild(audioDirectory, directory) ||
                    !syncDirectoryIfPresent(audioDirectory)
                ) return false
                return@forEach
            }
            if (!PrivateAudioPath.isOwnedRestoreDirectory(audioDirectory, directory, id)) {
                if (!deleteEmptyMarkerlessGenerationDirectory(directory, id)) return false
                return@forEach
            }
            val children = directory.listFiles()?.toList() ?: return false
            if (children.size == 1 && children.single().name == OWNER_MARKER) {
                if (!deleteEmptyOwnedGenerationDirectory(directory, id)) return false
            }
        }
        return true
    }

    private fun isOwnedPreviousTrackAudio(track: Track, file: File): Boolean =
        TrackFileName.isGeneratedFileName(track.id, file.name) &&
            (PrivateAudioPath.isDirectChild(audioDirectory, file) ||
                PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file) ||
                isMissingPreviousGenerationDirectory(file.parentFile))

    private fun isOwnedPreviousTrackArtwork(track: Track, file: File): Boolean =
        TrackArtworkFileName.isGeneratedFileName(track.id, file.name) &&
            (PrivateAudioPath.isDirectChild(audioDirectory, file) ||
                PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file) ||
                isMissingPreviousGenerationDirectory(file.parentFile))

    private fun isEmptyDirectory(directory: SecureDirectoryStream<Path>): Boolean = !directory.iterator().hasNext()

    private fun deleteEmptyMarkerlessGenerationDirectory(directory: File, id: String): Boolean = try {
        if (PrivateAudioPath.restoreGenerationId(directory.name) != id) return false
        val expectedAudioKey = PrivateAudioPath.directoryFileKeyNoFollow(audioDirectory) ?: return false
        val expectedGenerationKey = PrivateAudioPath.directoryFileKeyNoFollow(directory) ?: return false
        if (!PrivateAudioPath.isDirectChild(audioDirectory, directory)) return false
        val directoryName = audioDirectory.toPath().fileSystem.getPath(directory.name)
        PrivateAudioPath.openDirectoryNoFollow(audioDirectory).use { audioHandle ->
            if (!PrivateAudioPath.isOpenedDirectoryKey(expectedAudioKey, audioHandle) ||
                !PrivateAudioPath.isSameDirectory(audioDirectory, audioHandle)
            ) return false
            audioHandle.newDirectoryStream(directoryName, LinkOption.NOFOLLOW_LINKS).use { generationHandle ->
                if (!PrivateAudioPath.isOpenedDirectoryKey(expectedGenerationKey, generationHandle) ||
                    !PrivateAudioPath.isSameDirectory(directory, generationHandle)
                ) return false
                if (!isEmptyDirectory(generationHandle)) return false
                audioHandle.deleteDirectory(directoryName)
                if (!syncDirectoryIfPresent(audioDirectory, audioHandle)) return false
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun deleteEmptyOwnedGenerationDirectory(directory: File, id: String): Boolean = try {
        if (PrivateAudioPath.restoreGenerationId(directory.name) != id) return false
        val expectedAudioKey = PrivateAudioPath.directoryFileKeyNoFollow(audioDirectory) ?: return false
        val expectedGenerationKey = PrivateAudioPath.directoryFileKeyNoFollow(directory) ?: return false
        if (!PrivateAudioPath.isDirectChild(audioDirectory, directory)) return false
        val directoryName = audioDirectory.toPath().fileSystem.getPath(directory.name)
        val markerName = audioDirectory.toPath().fileSystem.getPath(OWNER_MARKER)
        PrivateAudioPath.openDirectoryNoFollow(audioDirectory).use { audioHandle ->
            if (!PrivateAudioPath.isOpenedDirectoryKey(expectedAudioKey, audioHandle) ||
                !PrivateAudioPath.isSameDirectory(audioDirectory, audioHandle)
            ) return false
            audioHandle.newDirectoryStream(directoryName, LinkOption.NOFOLLOW_LINKS).use { generationHandle ->
                if (!PrivateAudioPath.isOpenedDirectoryKey(expectedGenerationKey, generationHandle) ||
                    !PrivateAudioPath.isSameDirectory(directory, generationHandle)
                ) return false
                val children = generationHandle.map { it.fileName?.toString() ?: return false }.toList()
                if (children.size != 1 || children.single() != OWNER_MARKER ||
                    !PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(generationHandle, markerName, id)
                ) return false
                generationHandle.deleteFile(markerName)
                audioHandle.deleteDirectory(directoryName)
                if (!syncDirectoryIfPresent(audioDirectory, audioHandle)) return false
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun isMissingPreviousGenerationDirectory(directory: File?): Boolean {
        if (directory == null || PrivateAudioPath.restoreGenerationId(directory.name) == null) return false
        return !directory.exists() && !Files.isSymbolicLink(directory.toPath()) &&
            PrivateAudioPath.isDirectChild(audioDirectory, directory)
    }

    private fun isOwnedTrackAudio(track: Track, file: File): Boolean =
        TrackFileName.isGeneratedFileName(track.id, file.name) &&
            (PrivateAudioPath.isDirectChild(audioDirectory, file) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file))

    private fun isOwnedTrackArtwork(track: Track, file: File): Boolean =
        TrackArtworkFileName.isGeneratedFileName(track.id, file.name) &&
            (PrivateAudioPath.isDirectChild(audioDirectory, file) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file))

    private fun writeOwnerMarker(directory: File, id: String) {
        val marker = File(directory, OWNER_MARKER)
        if (!PrivateAudioPath.isDirectChild(directory, marker) || !marker.createNewFile()) {
            throw IOException("Could not create the restore ownership marker.")
        }
        FileOutputStream(marker).use { output ->
            output.write(id.toByteArray(StandardCharsets.UTF_8))
            durability.syncFile(output)
        }
    }

    private fun writeJournal(pending: PendingLibraryRestore) {
        if (!filesDirectory.isDirectory || !PrivateAudioPath.isCanonicalDirectory(filesDirectory)) {
            throw IOException("A previous restore transaction needs recovery.")
        }
        val fileSystem = filesDirectory.toPath().fileSystem
        val journalName = fileSystem.getPath(journalFile.name)
        val temporaryName = fileSystem.getPath("$JOURNAL_TEMP_PREFIX${pending.transactionId}")
        PrivateAudioPath.openDirectoryNoFollow(filesDirectory).use { filesHandle ->
            if (!PrivateAudioPath.isSameDirectory(filesDirectory, filesHandle) ||
                hasEntryNoFollow(filesHandle, journalName) || hasEntryNoFollow(filesHandle, temporaryName)
            ) {
                throw IOException("A previous restore transaction needs recovery.")
            }
            var temporaryCreated = false
            try {
                val channel = filesHandle.newByteChannel(
                    temporaryName,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                )
                temporaryCreated = true
                channel.use { openedChannel ->
                    val fileChannel = openedChannel as? java.nio.channels.FileChannel
                        ?: throw IOException("The secure filesystem did not provide a durable journal channel.")
                    val output = DataOutputStream(object : FilterOutputStream(Channels.newOutputStream(openedChannel)) {
                        override fun close() = flush()
                    })
                    output.writeInt(JOURNAL_MAGIC)
                    output.writeInt(JOURNAL_VERSION)
                    output.writeUTF(pending.transactionId)
                    output.writeUTF(pending.stagingDirectory.name)
                    output.writeUTF(pending.generationDirectory.name)
                    writeNullableState(output, pending.previousEncodedState)
                    writeRequiredState(output, pending.encodedState)
                    output.writeInt(pending.integrity.size)
                    pending.integrity.forEach { asset ->
                        output.writeUTF(asset.fileName)
                        output.writeLong(asset.sizeBytes)
                        output.writeUTF(asset.sha256)
                    }
                    output.flush()
                    durability.syncFile(fileChannel)
                }
                filesHandle.move(temporaryName, filesHandle, journalName)
                temporaryCreated = false
                durability.syncDirectory(filesDirectory, filesHandle)
            } catch (error: Exception) {
                if (temporaryCreated) {
                    runCatching { PrivateAudioPath.deleteRegularFileNoFollow(filesHandle, temporaryName) }
                        .onFailure(error::addSuppressed)
                }
                runCatching { durability.syncDirectory(filesDirectory, filesHandle) }
                    .onFailure(error::addSuppressed)
                throw error
            }
        }
    }

    internal fun diagnoseRecoveryJournalRead(): RecoveryJournalReadClassification = try {
        if (readJournal() == null) {
            RecoveryJournalReadClassification(RecoveryJournalReadDiagnosis.JOURNAL_NOT_PRESENT)
        } else {
            RecoveryJournalReadClassification(RecoveryJournalReadDiagnosis.READABLE)
        }
    } catch (failure: RecoveryJournalReadFailure) {
        RecoveryJournalReadClassification(
            diagnosis = failure.diagnosis,
            osErrorCategory = failure.osErrorCategory,
            rootProbeOutcome = failure.rootProbeOutcome,
            privateFilesDirectoryProbeOutcome = PrivateFilesDirectoryProbeTrigger.probeIfRootOpenFailed(
                failure.diagnosis,
                filesDirectory,
            ) { directory -> PrivateFilesDirectoryNativeProbe.probe(directory) },
            privateFilesDirectoryNioProbeOutcome = PrivateFilesDirectoryNioProbeTrigger.probeIfRootOpenFailed(
                failure.diagnosis,
                filesDirectory,
            ) { directory -> PrivateFilesDirectoryNioProbe.probe(directory) },
        )
    } catch (_: Exception) {
        RecoveryJournalReadClassification(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN)
    }

    private fun readJournal(): RestoreJournal? {
        return try {
            val directoryIsSafe = try {
                filesDirectory.isDirectory && PrivateAudioPath.isCanonicalDirectory(filesDirectory)
            } catch (error: Exception) {
                failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE, error)
            }
            if (!directoryIsSafe) failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE)
            val filesHandle = try {
                PrivateFilesDirectoryNioAnchor.open(filesDirectory)
            } catch (failure: RecoveryJournalReadFailure) {
                throw failure
            } catch (error: Exception) {
                failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_OPEN_FAILURE, error)
            }
            filesHandle.use { handle ->
                val sameDirectory = try {
                    PrivateAudioPath.isSameDirectory(filesDirectory, handle)
                } catch (error: Exception) {
                    failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE, error)
                }
                if (!sameDirectory) {
                    failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE)
                }
                readJournal(handle)
            }
        } catch (failure: RecoveryJournalReadFailure) {
            throw failure
        } catch (_: Exception) {
            failJournalRead(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN)
        }
    }

    private fun readJournal(
        filesHandle: SecureDirectoryStream<Path>,
    ): RestoreJournal? {
        val journalName = filesDirectory.toPath().fileSystem.getPath(journalFile.name)
        val journalObserved = hasEntryNoFollow(filesHandle, journalName)
        val journal = readJournal(filesHandle, journalName, entryPreviouslyObserved = journalObserved)
        if (journal != null) return journal
        val quarantineName = PrivateAudioPath.deletionQuarantineName(journalName) ?: return null
        if (!hasEntryNoFollow(filesHandle, quarantineName)) return null
        return readJournal(
            filesHandle,
            quarantineName,
            entryPreviouslyObserved = true,
        )
    }

    private fun readJournal(
        filesHandle: SecureDirectoryStream<Path>,
        journalName: Path,
        entryPreviouslyObserved: Boolean = false,
    ): RestoreJournal? {
        val name = journalName.fileName?.toString()
        if (journalName.isAbsolute || journalName.nameCount != 1 || name.isNullOrEmpty() || name != journalName.toString()) {
            failJournalRead(RecoveryJournalReadDiagnosis.UNSAFE_PATH)
        }
        val journalFile = File(filesDirectory, name)
        val verifiedDirectoryStillMatches = {
            PrivateAudioPath.isSameDirectory(filesDirectory, filesHandle)
        }
        val journalBytes = recoveryJournalFileAccess.readWithVerifiedDirectoryAnchor(
            file = journalFile,
            verifiedDirectoryAnchor = filesHandle,
            maximumBytes = MAX_JOURNAL_BYTES,
            verifiedDirectoryStillMatches = verifiedDirectoryStillMatches,
            entryPreviouslyObserved = entryPreviouslyObserved,
        ) ?: return null
        return parseJournal(journalBytes)
    }

    private fun parseJournal(bytes: ByteArray): RestoreJournal {
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != JOURNAL_MAGIC || input.readInt() != JOURNAL_VERSION) {
                    failJournalRead(RecoveryJournalReadDiagnosis.UNSUPPORTED_HEADER_VERSION)
                }
                val transactionId = input.readUTF()
                val stagingName = input.readUTF()
                val generationName = input.readUTF()
                if (!isUuid(transactionId) || stagingName != "$STAGING_PREFIX$transactionId" || generationName != "$GENERATION_PREFIX$transactionId") {
                    failJournalRead(RecoveryJournalReadDiagnosis.UNSAFE_PATH)
                }
                val previous = readNullableState(input)
                val encoded = readRequiredState(input)
                val integrityCount = input.readInt()
                if (integrityCount !in 0..MAX_RESTORE_ASSETS) {
                    failJournalRead(RecoveryJournalReadDiagnosis.BAD_INTEGRITY_METADATA)
                }
                val integrity = ArrayList<RestoredAssetIntegrity>(integrityCount)
                val integrityNames = hashSetOf<String>()
                repeat(integrityCount) {
                    val fileName = input.readUTF()
                    val sizeBytes = input.readLong()
                    val sha256 = input.readUTF()
                    val maximumSize = if (fileName.endsWith(".img")) {
                        LibraryBackupLimits.DEFAULT.maxArtworkBytes
                    } else {
                        LibraryBackupLimits.DEFAULT.maxAudioFileBytes
                    }
                    if (!isSafeStagedAssetName(fileName) || !integrityNames.add(fileName) ||
                        sizeBytes !in 1..maximumSize || !SHA256_PATTERN.matches(sha256)
                    ) {
                        failJournalRead(RecoveryJournalReadDiagnosis.BAD_INTEGRITY_METADATA)
                    }
                    integrity += RestoredAssetIntegrity(fileName, sizeBytes, sha256)
                }
                if (input.available() != 0) failJournalRead(RecoveryJournalReadDiagnosis.TRAILING_BYTES)
                RestoreJournal(transactionId, stagingName, generationName, previous, encoded, integrity)
            }
        } catch (failure: RecoveryJournalReadFailure) {
            throw failure
        } catch (_: java.io.EOFException) {
            failJournalRead(RecoveryJournalReadDiagnosis.SIZE_CHANGE_OR_TRUNCATION)
        } catch (_: java.io.UTFDataFormatException) {
            failJournalRead(RecoveryJournalReadDiagnosis.BAD_SERIALIZATION)
        } catch (_: IOException) {
            failJournalRead(RecoveryJournalReadDiagnosis.BAD_SERIALIZATION)
        } catch (_: Exception) {
            failJournalRead(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN)
        }
    }

    private fun failJournalRead(diagnosis: RecoveryJournalReadDiagnosis): Nothing =
        throw RecoveryJournalReadFailure(diagnosis)

    private fun failJournalRead(diagnosis: RecoveryJournalReadDiagnosis, error: Throwable): Nothing =
        throw RecoveryJournalReadFailure(diagnosis, recoveryJournalOsErrorCategory(error))

    private fun deleteJournal(transactionId: String): Boolean {
        if (!filesDirectory.isDirectory || !PrivateAudioPath.isCanonicalDirectory(filesDirectory)) return false
        return try {
            PrivateAudioPath.openDirectoryNoFollow(filesDirectory).use { filesHandle ->
                if (!PrivateAudioPath.isSameDirectory(filesDirectory, filesHandle)) return false
                val journal = readJournal(filesHandle)
                val journalName = filesDirectory.toPath().fileSystem.getPath(journalFile.name)
                if (journal == null) {
                    if (hasEntryNoFollow(filesHandle, journalName)) return false
                    return syncDirectoryIfPresent(filesDirectory, filesHandle)
                }
                if (journal.transactionId != transactionId) return false
                if (!PrivateAudioPath.deleteRegularFileNoFollow(filesHandle, journalName)) return false
                syncDirectoryIfPresent(filesDirectory, filesHandle)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun requireSafeAudioDirectory() {
        if (!PrivateAudioPath.isDirectChild(filesDirectory, audioDirectory)) {
            throw SecurityException("The app-private audio directory is redirected or unavailable.")
        }
    }

    private fun cleanupStagingDirectory(directory: File, id: String): Boolean {
        val expectedDirectoryKey = PrivateAudioPath.directoryFileKeyNoFollow(directory)
        if (!directory.exists()) {
            return !Files.isSymbolicLink(directory.toPath()) && syncDirectoryIfPresent(audioDirectory)
        }
        if (!isOwnedStagingDirectory(directory, id)) return cleanupEmptyMarkerlessStagingDirectory(directory, id)
        return cleanupMarkerOwnedDirectory(directory, id, expectedDirectoryKey ?: return false)
    }

    private fun cleanupEmptyMarkerlessStagingDirectory(directory: File, id: String): Boolean {
        if (PrivateAudioPath.restoreStageId(directory.name) != id ||
            !PrivateAudioPath.isDirectChild(audioDirectory, directory)
        ) return false
        return try {
            val directoryName = audioDirectory.toPath().fileSystem.getPath(directory.name)
            PrivateAudioPath.openDirectoryNoFollow(audioDirectory).use { audioHandle ->
                audioHandle.newDirectoryStream(directoryName, LinkOption.NOFOLLOW_LINKS).use { stagingHandle ->
                    if (!isEmptyDirectory(stagingHandle)) return false
                    audioHandle.deleteDirectory(directoryName)
                }
                syncDirectoryIfPresent(audioDirectory, audioHandle)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanupGenerationDirectory(directory: File, id: String): Boolean {
        val expectedDirectoryKey = PrivateAudioPath.directoryFileKeyNoFollow(directory)
        if (!directory.exists()) {
            return !Files.isSymbolicLink(directory.toPath()) && syncDirectoryIfPresent(audioDirectory)
        }
        if (!PrivateAudioPath.isOwnedRestoreDirectory(audioDirectory, directory, id)) {
            return deleteEmptyMarkerlessGenerationDirectory(directory, id) && syncDirectoryIfPresent(audioDirectory)
        }
        return cleanupMarkerOwnedDirectory(directory, id, expectedDirectoryKey ?: return false)
    }

    private fun cleanupMarkerOwnedDirectory(directory: File, id: String, expectedDirectoryKey: Any): Boolean {
        if (!PrivateAudioPath.isDirectChild(audioDirectory, directory)) return false
        return try {
            val directoryName = audioDirectory.toPath().fileSystem.getPath(directory.name)
            val markerName = audioDirectory.toPath().fileSystem.getPath(OWNER_MARKER)
            PrivateAudioPath.openDirectoryNoFollow(audioDirectory).use { audioHandle ->
                if (!PrivateAudioPath.isSameDirectory(audioDirectory, audioHandle)) return false
                audioHandle.newDirectoryStream(directoryName, LinkOption.NOFOLLOW_LINKS).use { openedDirectory ->
                    if (!PrivateAudioPath.isOpenedDirectoryKey(expectedDirectoryKey, openedDirectory) ||
                        !PrivateAudioPath.isSameDirectory(directory, openedDirectory)
                    ) return false
                    if (!PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(openedDirectory, markerName, id)) return false
                    val childNames = openedDirectory.map { it.fileName ?: return false }.toList()
                    val cleanedQuarantines = cleanupAbandonedAssetDeletionQuarantines(openedDirectory, childNames) ?: return false
                    val children = childNames.filterNot { it in cleanedQuarantines }.map(Path::toString)
                    if (children.count { it == OWNER_MARKER } != 1) return false
                    val assets = children.filterNot { it == OWNER_MARKER }
                    val assetFiles = assets.map { name ->
                        if (!isSafeStagedAssetName(name)) return false
                        val namePath = audioDirectory.toPath().fileSystem.getPath(name)
                        val attributes = openedDirectory.getFileAttributeView(
                            namePath,
                            BasicFileAttributeView::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        )?.readAttributes() ?: return false
                        if (!attributes.isRegularFile) return false
                        namePath to (attributes.fileKey() ?: return false)
                    }
                    assetFiles.forEach { (namePath, expectedFileKey) ->
                        if (!PrivateAudioPath.isSameDirectory(directory, openedDirectory) ||
                            !PrivateAudioPath.deleteRegularFileNoFollow(openedDirectory, namePath, expectedFileKey)
                        ) return false
                    }
                    if (assetFiles.isNotEmpty() && !syncDirectoryIfPresent(directory, openedDirectory)) return false
                    if (!PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(openedDirectory, markerName, id)) return false
                    if (!PrivateAudioPath.isSameDirectory(directory, openedDirectory)) return false
                    if (!PrivateAudioPath.isOpenedDirectoryKey(expectedDirectoryKey, openedDirectory)) return false
                    openedDirectory.deleteFile(markerName)
                    if (!syncDirectoryIfPresent(directory, openedDirectory)) return false
                    if (!PrivateAudioPath.isSameDirectory(directory, openedDirectory) ||
                        !PrivateAudioPath.isSameDirectory(audioDirectory, audioHandle) ||
                        PrivateAudioPath.directoryFileKeyNoFollow(directory) != expectedDirectoryKey
                    ) return false
                    audioHandle.deleteDirectory(directoryName)
                }
                syncDirectoryIfPresent(audioDirectory, audioHandle)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanupAbandonedAssetDeletionQuarantines(
        directory: SecureDirectoryStream<Path>,
        children: List<Path>,
    ): Set<Path>? {
        val cleanedQuarantines = mutableSetOf<Path>()
        children.forEach { quarantineName ->
            val targetName = PrivateAudioPath.deletionQuarantineTargetName(quarantineName) ?: return@forEach
            if (!isSafeStagedAssetName(targetName.toString())) return null
            val attributes = directory.getFileAttributeView(
                quarantineName,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )?.readAttributes() ?: return null
            if (!attributes.isRegularFile ||
                !PrivateAudioPath.cleanupDeleteQuarantineNoFollow(directory, targetName)
            ) return null
            cleanedQuarantines.add(quarantineName)
        }
        return cleanedQuarantines
    }

    private fun retryAbandonedRecoveryCleanup(forceSyncDirectories: Boolean): Boolean {
        val snapshot = runCatching { decodeSnapshot(stateStore.read()) }.getOrNull() ?: return false
        return cleanupApprovedNoJournalArtifactsOnce(snapshot, forceSyncDirectories)
    }

    internal fun cleanupApprovedNoJournalArtifactsOnce(
        snapshot: LibrarySnapshot,
        forceSyncDirectories: Boolean = true,
    ): Boolean {
        val activeAudioPaths = try {
            snapshot.tracks.flatMap { track -> listOfNotNull(track.filePath, track.artworkPath) }
                .map { path -> File(path).toPath().toAbsolutePath().normalize() }
                .toSet()
        } catch (_: Exception) {
            return false
        }
        return runCatching {
            ScopedNoJournalRecoveryCleanup(
                filesDirectory = filesDirectory,
                audioDirectory = audioDirectory,
                activeAudioPaths = activeAudioPaths,
                durability = durability,
                journalIsAbsent = { filesHandle -> readJournal(filesHandle) == null },
                isUuid = ::isUuid,
                isSafeStagedAssetName = ::isSafeStagedAssetName,
                cleanupAssetDeletionQuarantines = ::cleanupAbandonedAssetDeletionQuarantines,
                journalTemporaryPrefix = JOURNAL_TEMP_PREFIX,
                ownerMarkerName = OWNER_MARKER,
                maximumJournalBytes = MAX_JOURNAL_BYTES,
                maximumStagingAssets = MAX_RESTORE_ASSETS,
                forceSyncDirectories = forceSyncDirectories,
            ).cleanup()
        }.getOrDefault(false)
    }

    private fun cleanupAbandonedStagingDirectories(forceSyncDirectory: Boolean): Boolean {
        if (!audioDirectory.exists()) {
            return !forceSyncDirectory || syncDirectoryIfPresent(audioDirectory)
        }
        if (!audioDirectory.isDirectory || !PrivateAudioPath.isCanonicalDirectory(audioDirectory)) return false
        val children = audioDirectory.listFiles() ?: return false
        var cleanupComplete = true
        var candidateFound = false
        children.forEach { child ->
            val id = PrivateAudioPath.restoreStageId(child.name) ?: return@forEach
            candidateFound = true
            if (!cleanupStagingDirectory(child, id)) cleanupComplete = false
        }
        val mustSyncParent = forceSyncDirectory || candidateFound
        val parentSynced = !mustSyncParent || syncDirectoryIfPresent(audioDirectory)
        return cleanupComplete && parentSynced
    }

    private fun cleanupAbandonedRestoreGenerations(forceSyncDirectory: Boolean): Boolean {
        if (!audioDirectory.exists()) {
            return !forceSyncDirectory || syncDirectoryIfPresent(audioDirectory)
        }
        if (!audioDirectory.isDirectory || !PrivateAudioPath.isCanonicalDirectory(audioDirectory)) return false
        val children = try {
            audioDirectory.listFiles()
        } catch (_: Exception) {
            null
        } ?: return false
        var cleanupComplete = true
        var candidateFound = false
        children.forEach { child ->
            val id = PrivateAudioPath.restoreGenerationId(child.name) ?: return@forEach
            if (!PrivateAudioPath.isDirectChild(audioDirectory, child)) return@forEach
            val isRealDirectory = try {
                !Files.isSymbolicLink(child.toPath()) && Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)
            } catch (_: Exception) {
                null
            }
            if (isRealDirectory == null) {
                candidateFound = true
                cleanupComplete = false
                return@forEach
            }
            if (!isRealDirectory) return@forEach

            val contents = try {
                child.listFiles()
            } catch (_: Exception) {
                null
            }
            if (contents == null) {
                candidateFound = true
                cleanupComplete = false
                return@forEach
            }
            if (contents.isNotEmpty()) return@forEach

            candidateFound = true
            if (!deleteEmptyMarkerlessGenerationDirectory(child, id)) cleanupComplete = false
        }
        val mustSyncParent = forceSyncDirectory || candidateFound
        val parentSynced = !mustSyncParent || syncDirectoryIfPresent(audioDirectory)
        return cleanupComplete && parentSynced
    }

    private fun cleanupAbandonedJournalTemporaries(forceSyncDirectory: Boolean): Boolean {
        if (!filesDirectory.isDirectory || !PrivateAudioPath.isCanonicalDirectory(filesDirectory)) return false
        return try {
            PrivateAudioPath.openDirectoryNoFollow(filesDirectory).use { filesHandle ->
                if (!PrivateAudioPath.isSameDirectory(filesDirectory, filesHandle)) return false
                // Probe the resolved path too; cleanup candidates below come only from the pinned handle.
                filesDirectory.listFiles() ?: return false
                val children = filesHandle.map { entry -> entry.fileName ?: return false }.toList()
                var cleanupComplete = true
                var candidateFound = false
                children.forEach { child ->
                    val targetName = PrivateAudioPath.deletionQuarantineTargetName(child) ?: child
                    val id = targetName.toString().removePrefix(JOURNAL_TEMP_PREFIX)
                        .takeIf { targetName.toString().startsWith(JOURNAL_TEMP_PREFIX) && isUuid(it) } ?: return@forEach
                    candidateFound = true
                    val attributes = try {
                        filesHandle.getFileAttributeView(
                            targetName,
                            BasicFileAttributeView::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        )?.readAttributes()
                    } catch (_: NoSuchFileException) {
                        null
                    }
                    if (attributes == null) {
                        if (PrivateAudioPath.hasDeleteQuarantineNoFollow(filesHandle, targetName) &&
                            !PrivateAudioPath.deleteRegularFileNoFollow(filesHandle, targetName)
                        ) cleanupComplete = false
                        return@forEach
                    }
                    if (!attributes.isRegularFile) return@forEach
                    if (!PrivateAudioPath.deleteRegularFileNoFollow(filesHandle, targetName)) cleanupComplete = false
                }
                val mustSyncParent = forceSyncDirectory || candidateFound
                val parentSynced = !mustSyncParent || syncDirectoryIfPresent(filesDirectory, filesHandle)
                cleanupComplete && parentSynced
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEntryNoFollow(directory: SecureDirectoryStream<Path>, childName: Path): Boolean = try {
        directory.getFileAttributeView(
            childName,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )?.readAttributes() != null
    } catch (_: NoSuchFileException) {
        false
    }

    private fun syncDirectoryIfPresent(
        directory: File,
        openedDirectory: SecureDirectoryStream<Path>? = null,
    ): Boolean {
        if (recoveryDiagnosticEventSink == null) return syncDirectoryIfPresentWithoutDiagnostics(directory, openedDirectory)
        val directoryPath = directory.toPath().toAbsolutePath().normalize()
        val stage = when (directoryPath) {
            filesDirectory.toPath().toAbsolutePath().normalize() -> RestoreRecoveryAttemptStage.DIRECTORY_SYNC_FILES
            audioDirectory.toPath().toAbsolutePath().normalize() -> RestoreRecoveryAttemptStage.DIRECTORY_SYNC_AUDIO
            else -> RestoreRecoveryAttemptStage.DIRECTORY_SYNC_RESTORE
        }
        return recoveryStage(stage) { syncDirectoryIfPresentWithoutDiagnostics(directory, openedDirectory) }
    }

    private fun syncDirectoryIfPresentWithoutDiagnostics(
        directory: File,
        openedDirectory: SecureDirectoryStream<Path>? = null,
    ): Boolean {
        if (openedDirectory != null) {
            return runCatching { durability.syncDirectory(directory, openedDirectory) }.isSuccess
        }
        if (!directory.exists()) return true
        if (!directory.isDirectory || !PrivateAudioPath.isCanonicalDirectory(directory)) return false
        return runCatching {
            PrivateAudioPath.openDirectoryNoFollow(directory).use { opened ->
                durability.syncDirectory(directory, opened)
            }
        }.isSuccess
    }

    private fun isOwnedStagingDirectory(directory: File, id: String): Boolean =
        PrivateAudioPath.restoreStageId(directory.name) == id &&
            PrivateAudioPath.isDirectChild(audioDirectory, directory) && directory.isDirectory &&
            isOwnedMarker(directory, File(directory, OWNER_MARKER), id)

    private fun isOwnedMarker(directory: File, marker: File, id: String): Boolean =
        PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(directory, marker, id)

    private fun isSafeStagedAssetName(name: String): Boolean {
        if (name == OWNER_MARKER || '/' in name || '\\' in name) return false
        val id = name.substringBeforeLast('.', missingDelimiterValue = "")
        if (id.isEmpty() || !id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return false
        return TrackFileName.isGeneratedFileName(id, name) || name == "$id.img"
    }

    private fun cleanupEmptyStageFromFailedPreparation(stagingDirectory: File, id: String) {
        cleanupStagingDirectory(stagingDirectory, id)
    }

    private fun writeNullableState(output: DataOutputStream, state: String?) {
        output.writeBoolean(state != null)
        state?.let { writeStateBytes(output, it) }
    }

    private fun writeRequiredState(output: DataOutputStream, state: String) = writeStateBytes(output, state)

    private fun writeStateBytes(output: DataOutputStream, state: String) {
        val bytes = state.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STATE_BYTES) { "The restore journal state is too large." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readNullableState(input: DataInputStream): String? =
        if (input.readBoolean()) readStateBytes(input) else null

    private fun readRequiredState(input: DataInputStream): String = readStateBytes(input)

    private fun readStateBytes(input: DataInputStream): String {
        val length = input.readInt()
        if (length !in 1..MAX_STATE_BYTES) failJournalRead(RecoveryJournalReadDiagnosis.BAD_SERIALIZATION)
        if (length > input.available()) failJournalRead(RecoveryJournalReadDiagnosis.SIZE_CHANGE_OR_TRUNCATION)
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            failJournalRead(RecoveryJournalReadDiagnosis.BAD_SERIALIZATION)
        }
    }

    private fun portableFileName(path: String, prefix: String): String {
        require(path.startsWith(prefix)) { "The restored library contains a non-portable file path." }
        return path.removePrefix(prefix).also { name ->
            require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") {
                "The restored library contains an unsafe file path."
            }
        }
    }

    private val journalFile: File get() = File(filesDirectory, JOURNAL_NAME)

    private data class RestoreJournal(
        val transactionId: String,
        val stagingDirectoryName: String,
        val generationDirectoryName: String,
        val previousEncodedState: String?,
        val encodedState: String,
        val integrity: List<RestoredAssetIntegrity>,
    )

    private companion object {
        const val STAGING_PREFIX = ".aftertone-restore-stage-"
        const val GENERATION_PREFIX = "restore-"
        const val OWNER_MARKER = ".aftertone-restore-owner"
        const val JOURNAL_NAME = ".aftertone-restore-journal"
        const val JOURNAL_TEMP_PREFIX = ".aftertone-restore-journal-"
        const val JOURNAL_MAGIC = 0x4154524a
        const val JOURNAL_VERSION = 2
        const val MAX_STATE_BYTES = LibraryStateCodec.MAX_STATE_UTF8_BYTES
        const val MAX_JOURNAL_BYTES = 64 * 1024 * 1024L
        const val MAX_RESTORE_ASSETS = 40_000
        val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun isUuid(value: String): Boolean = uuidPattern.matches(value)
    }
}
