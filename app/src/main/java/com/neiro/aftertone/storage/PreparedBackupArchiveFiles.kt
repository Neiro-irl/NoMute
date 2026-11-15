package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.util.UUID

internal object PreparedBackupArchiveFiles {
    const val ARCHIVE_PREFIX = "aftertone-restore-source-"
    private const val OWNER_SUFFIX = ".owner"
    private const val ARCHIVE_SUFFIX = ".zip"
    private const val VALIDATION_SNAPSHOT_MARKER = ".validation-"
    private val validId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun create(cacheDirectory: File, id: String): File =
        create(cacheDirectory, id, SystemRestoreFileDurability)

    internal fun create(
        cacheDirectory: File,
        id: String,
        durability: RestoreFileDurability,
    ): File {
        requireValidCacheAndId(cacheDirectory, id)
        val archive = archiveFile(cacheDirectory, id)
        val marker = ownerMarker(cacheDirectory, id)
        val archiveName = childName(cacheDirectory, archive)
        val markerName = childName(cacheDirectory, marker)
        return PrivateAudioPath.openDirectoryNoFollow(cacheDirectory).use { cacheHandle ->
            if (hasEntry(cacheHandle, archiveName) || hasEntry(cacheHandle, markerName) ||
                PrivateAudioPath.hasDeleteQuarantineNoFollow(cacheHandle, archiveName) ||
                PrivateAudioPath.hasDeleteQuarantineNoFollow(cacheHandle, markerName) ||
                hasValidationSnapshot(cacheHandle, id)
            ) {
                throw IllegalStateException("The temporary backup path already exists.")
            }
            var markerCreated = false
            try {
                val markerChannel = cacheHandle.newByteChannel(
                    markerName,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                )
                markerCreated = true
                markerChannel.use { channel ->
                    val bytes = ByteBuffer.wrap(id.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) {
                        if (channel.write(bytes) <= 0) throw IOException("Could not write the temporary backup owner marker.")
                    }
                    val fileChannel = channel as? FileChannel
                        ?: throw IOException("The secure filesystem did not provide a durable owner-marker channel.")
                    fileChannel.force(true)
                }
                durability.syncDirectory(cacheDirectory, cacheHandle)
                if (!isOwnedMarker(cacheHandle, markerName, id)) {
                    throw SecurityException("The temporary backup ownership marker is invalid.")
                }
                archive
            } catch (error: Throwable) {
                if (markerCreated) {
                    runCatching { PrivateAudioPath.deleteEntryNoFollow(cacheHandle, markerName) }
                        .onFailure(error::addSuppressed)
                }
                throw error
            }
        }
    }

    internal fun <T> createAndWriteAndValidate(
        cacheDirectory: File,
        id: String,
        durability: RestoreFileDurability,
        writeArchive: (OutputStream) -> Unit,
        validateArchive: (File) -> T,
    ): Pair<File, T> {
        requireValidCacheAndId(cacheDirectory, id)
        val archive = archiveFile(cacheDirectory, id)
        val marker = ownerMarker(cacheDirectory, id)
        val archiveName = childName(cacheDirectory, archive)
        val markerName = childName(cacheDirectory, marker)
        val validationName = validationSnapshotName(cacheDirectory, id)
        val validationFile = File(cacheDirectory, validationName.toString())
        return PrivateAudioPath.openDirectoryNoFollow(cacheDirectory).use { cacheHandle ->
            if (hasEntry(cacheHandle, archiveName) || hasEntry(cacheHandle, markerName) ||
                PrivateAudioPath.hasDeleteQuarantineNoFollow(cacheHandle, archiveName) ||
                PrivateAudioPath.hasDeleteQuarantineNoFollow(cacheHandle, markerName) ||
                hasValidationSnapshot(cacheHandle, id) || hasEntry(cacheHandle, validationName)
            ) {
                throw IllegalStateException("The temporary backup path already exists.")
            }
            var markerCreated = false
            var validationSnapshotCreated = false
            var expectedArchiveFileKey: Any? = null
            try {
                val markerChannel = cacheHandle.newByteChannel(
                    markerName,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                )
                markerCreated = true
                markerChannel.use { channel ->
                    val bytes = ByteBuffer.wrap(id.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) {
                        if (channel.write(bytes) <= 0) throw IOException("Could not write the temporary backup owner marker.")
                    }
                    val fileChannel = channel as? FileChannel
                        ?: throw IOException("The secure filesystem did not provide a durable owner-marker channel.")
                    fileChannel.force(true)
                }
                durability.syncDirectory(cacheDirectory, cacheHandle)
                if (!isOwnedMarker(cacheHandle, markerName, id)) {
                    throw SecurityException("The temporary backup ownership marker is invalid.")
                }

                val archiveChannel = cacheHandle.newByteChannel(
                    archiveName,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                )
                archiveChannel.use { channel ->
                    val fileChannel = channel as? FileChannel
                        ?: throw IOException("The secure filesystem did not provide a durable archive channel.")
                    val output = object : FilterOutputStream(Channels.newOutputStream(channel)) {
                        override fun close() = flush()
                    }
                    output.use(writeArchive)
                    fileChannel.force(true)
                }
                durability.syncDirectory(cacheDirectory, cacheHandle)
                if (!isOwnedMarker(cacheHandle, markerName, id) || !isSafeRegularArchive(cacheHandle, archiveName)) {
                    throw SecurityException("The temporary backup files could not be verified.")
                }
                if (!PrivateAudioPath.isSameDirectory(cacheDirectory, cacheHandle)) {
                    throw IOException("The cache directory changed before archive validation.")
                }
                expectedArchiveFileKey = PrivateAudioPath.regularFileKeyNoFollow(cacheHandle, archiveName)
                    ?: throw IOException("The temporary backup archive could not be verified.")
                if (hasEntry(cacheHandle, validationName)) {
                    throw IOException("A temporary validation path already exists.")
                }
                cacheHandle.move(archiveName, cacheHandle, validationName)
                validationSnapshotCreated = true
                durability.syncDirectory(cacheDirectory, cacheHandle)
                if (PrivateAudioPath.regularFileKeyNoFollow(cacheHandle, validationName) != expectedArchiveFileKey) {
                    throw IOException("The private archive snapshot could not be verified.")
                }
                val validated = validateArchive(validationFile)
                if (PrivateAudioPath.regularFileKeyNoFollow(cacheHandle, validationName) != expectedArchiveFileKey) {
                    throw IOException("The temporary backup archive changed during validation.")
                }
                if (!PrivateAudioPath.isSameDirectory(cacheDirectory, cacheHandle)) {
                    throw IOException("The cache directory changed during archive validation.")
                }
                if (hasEntry(cacheHandle, archiveName)) {
                    throw IOException("The archive source path changed during validation.")
                }
                cacheHandle.move(validationName, cacheHandle, archiveName)
                validationSnapshotCreated = false
                if (PrivateAudioPath.regularFileKeyNoFollow(cacheHandle, archiveName) != expectedArchiveFileKey) {
                    throw IOException("The validated archive could not be restored to its staging path.")
                }
                durability.syncDirectory(cacheDirectory, cacheHandle)
                archive to validated
            } catch (error: Throwable) {
                var stagedFileCleanupComplete = true
                if (validationSnapshotCreated) {
                    stagedFileCleanupComplete = runCatching {
                        PrivateAudioPath.deleteRegularFileNoFollow(
                            cacheHandle,
                            validationName,
                            expectedArchiveFileKey,
                        )
                    }.getOrElse {
                        error.addSuppressed(it)
                        false
                    }
                }
                val archiveRemoved = runCatching {
                    PrivateAudioPath.deleteEntryNoFollow(cacheHandle, archiveName)
                }.getOrElse {
                    error.addSuppressed(it)
                    false
                }
                stagedFileCleanupComplete = archiveRemoved && stagedFileCleanupComplete
                if (markerCreated && stagedFileCleanupComplete) {
                    runCatching { PrivateAudioPath.deleteEntryNoFollow(cacheHandle, markerName) }
                        .onFailure(error::addSuppressed)
                }
                runCatching { durability.syncDirectory(cacheDirectory, cacheHandle) }
                    .onFailure(error::addSuppressed)
                throw error
            }
        }
    }

    fun archiveFile(cacheDirectory: File, id: String): File {
        requireValidCacheAndId(cacheDirectory, id)
        return File(cacheDirectory, "$ARCHIVE_PREFIX$id.zip").also { archive ->
            if (!PrivateAudioPath.isDirectChild(cacheDirectory, archive)) {
                throw SecurityException("The temporary archive path is unsafe.")
            }
        }
    }

    fun ownerMarker(cacheDirectory: File, id: String): File {
        requireValidCacheAndId(cacheDirectory, id)
        return File(cacheDirectory, "$ARCHIVE_PREFIX$id$OWNER_SUFFIX").also { marker ->
            if (!PrivateAudioPath.isDirectChild(cacheDirectory, marker)) {
                throw SecurityException("The temporary archive owner marker path is unsafe.")
            }
        }
    }

    private fun validationSnapshotName(cacheDirectory: File, id: String): Path {
        require(validId.matches(id)) { "The temporary backup identifier is invalid." }
        return childName(
            cacheDirectory,
            File(cacheDirectory, "$ARCHIVE_PREFIX$id$VALIDATION_SNAPSHOT_MARKER${UUID.randomUUID()}$ARCHIVE_SUFFIX"),
        )
    }

    private fun validationSnapshotId(name: String): String? {
        if (!name.startsWith(ARCHIVE_PREFIX) || !name.endsWith(ARCHIVE_SUFFIX)) return null
        val stem = name.removePrefix(ARCHIVE_PREFIX).removeSuffix(ARCHIVE_SUFFIX)
        val markerIndex = stem.indexOf(VALIDATION_SNAPSHOT_MARKER)
        if (markerIndex < 0 || stem.indexOf(VALIDATION_SNAPSHOT_MARKER, markerIndex + 1) >= 0) return null
        val id = stem.substring(0, markerIndex)
        val token = stem.substring(markerIndex + VALIDATION_SNAPSHOT_MARKER.length)
        return id.takeIf { validId.matches(it) && validId.matches(token) }
    }

    private fun hasValidationSnapshot(directory: SecureDirectoryStream<Path>, id: String): Boolean = try {
        directory.any { entry ->
            val entryName = entry.fileName ?: return true
            val name = entryName.toString()
            val quarantineTarget = PrivateAudioPath.deletionQuarantineTargetName(entryName)?.toString()
            validationSnapshotId(name) == id || quarantineTarget?.let(::validationSnapshotId) == id
        }
    } catch (_: Exception) {
        true
    }

    private fun deleteValidationSnapshots(
        directory: SecureDirectoryStream<Path>,
        id: String,
    ): Boolean {
        val names = try {
            directory.map { entry -> entry.fileName ?: return false }
        } catch (_: Exception) {
            return false
        }
        return names.all { name ->
            val directName = name.toString()
            val quarantineTarget = PrivateAudioPath.deletionQuarantineTargetName(name)
            when {
                validationSnapshotId(directName) == id -> PrivateAudioPath.deleteEntryNoFollow(directory, name)
                quarantineTarget != null && validationSnapshotId(quarantineTarget.toString()) == id ->
                    PrivateAudioPath.cleanupDeleteQuarantineNoFollow(directory, quarantineTarget)
                else -> true
            }
        }
    }

    fun isOwned(cacheDirectory: File, id: String): Boolean = runCatching {
        val marker = ownerMarker(cacheDirectory, id)
        isOwnedMarker(cacheDirectory, marker, id)
    }.getOrDefault(false)

    fun isFullyDiscarded(cacheDirectory: File, id: String): Boolean =
        isFullyDiscarded(cacheDirectory, id, SystemRestoreFileDurability)

    internal fun isFullyDiscarded(
        cacheDirectory: File,
        id: String,
        durability: RestoreFileDurability,
    ): Boolean = runCatching {
        if (!isSafeCacheDirectory(cacheDirectory) || !validId.matches(id)) return@runCatching false
        val archive = archiveFile(cacheDirectory, id)
        val marker = ownerMarker(cacheDirectory, id)
        val archiveName = childName(cacheDirectory, archive)
        val markerName = childName(cacheDirectory, marker)
        PrivateAudioPath.openDirectoryNoFollow(cacheDirectory).use { cacheHandle ->
            if (hasEntry(cacheHandle, archiveName) || hasEntry(cacheHandle, markerName) ||
                hasValidationSnapshot(cacheHandle, id) ||
                PrivateAudioPath.hasDeleteQuarantineNoFollow(cacheHandle, archiveName) ||
                PrivateAudioPath.hasDeleteQuarantineNoFollow(cacheHandle, markerName)
            ) return@runCatching false
            syncDirectory(cacheDirectory, durability, cacheHandle)
        }
    }.getOrDefault(false)

    fun discard(cacheDirectory: File, id: String): Boolean =
        discard(cacheDirectory, id, SystemRestoreFileDurability)

    internal fun discard(
        cacheDirectory: File,
        id: String,
        durability: RestoreFileDurability,
    ): Boolean {
        if (!isSafeCacheDirectory(cacheDirectory) || !validId.matches(id)) return false
        val archive = archiveFile(cacheDirectory, id)
        val marker = ownerMarker(cacheDirectory, id)
        val archiveName = childName(cacheDirectory, archive)
        val markerName = childName(cacheDirectory, marker)
        val cacheHandle = try {
            PrivateAudioPath.openDirectoryNoFollow(cacheDirectory)
        } catch (_: Exception) {
            return false
        }
        return cacheHandle.use { openedCache ->
            try {
                val markerQuarantineName = PrivateAudioPath.deletionQuarantineName(markerName) ?: return false
                val markerPresent = hasEntry(openedCache, markerName)
                val markerQuarantined = hasEntry(openedCache, markerQuarantineName)
                if (!markerPresent && !markerQuarantined) {
                    if (hasEntry(openedCache, archiveName) || hasValidationSnapshot(openedCache, id)) return false
                    if (PrivateAudioPath.hasDeleteQuarantineNoFollow(openedCache, archiveName)) {
                        if (!PrivateAudioPath.cleanupDeleteQuarantineNoFollow(openedCache, archiveName)) return false
                    }
                    return syncDirectory(cacheDirectory, durability, openedCache)
                }
                if (markerPresent) {
                    if (!isOwnedMarker(openedCache, markerName, id)) return false
                } else if (!PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(openedCache, markerQuarantineName, id)) {
                    return false
                }
                if (!PrivateAudioPath.deleteEntryNoFollow(openedCache, archiveName)) return false
                if (!deleteValidationSnapshots(openedCache, id)) return false
                if (!syncDirectory(cacheDirectory, durability, openedCache)) return false
                if (!PrivateAudioPath.deleteEntryNoFollow(openedCache, markerName)) return false
                syncDirectory(cacheDirectory, durability, openedCache)
            } catch (_: Exception) {
                false
            }
        }
    }

    fun cleanupOrphans(cacheDirectory: File): Int = cleanupOrphansWithPending(cacheDirectory).removedArchives

    fun cleanupOrphansWithPending(cacheDirectory: File): PreparedArchiveCleanupResult =
        cleanupOrphansWithPending(cacheDirectory, emptySet())

    internal fun cleanupOrphansWithPending(
        cacheDirectory: File,
        listFiles: (File) -> Array<File>?,
    ): PreparedArchiveCleanupResult = cleanupOrphansWithPending(cacheDirectory, emptySet(), listFiles)

    internal fun cleanupOrphansWithPending(
        cacheDirectory: File,
        protectedArchiveIds: Set<String>,
        listFiles: (File) -> Array<File>? = { it.listFiles() },
        durability: RestoreFileDurability = SystemRestoreFileDurability,
    ): PreparedArchiveCleanupResult {
        if (!isSafeCacheDirectory(cacheDirectory)) {
            throw SecurityException("The app-private cache directory is redirected or unsafe.")
        }
        val candidates = listFiles(cacheDirectory)?.toList()
            ?: throw IOException("Could not list temporary restore archives safely.")
        val ids = linkedSetOf<String>()
        candidates.forEach { candidate ->
            val id = archiveIdFromName(quarantineTargetOrSelf(candidate.name, cacheDirectory))
            if (id != null && validId.matches(id)) ids += id
        }
        var removedArchives = 0
        val pendingIds = mutableListOf<String>()
        ids.forEach { id ->
            val archive = archiveFile(cacheDirectory, id)
            if (id in protectedArchiveIds &&
                (isSafeRegularArchive(cacheDirectory, archive) ||
                    candidates.any { archiveIdFromName(quarantineTargetOrSelf(it.name, cacheDirectory)) == id })
            ) return@forEach
            val marker = ownerMarker(cacheDirectory, id)
            val archiveQuarantine = PrivateAudioPath.deletionQuarantineName(archive.toPath().fileName)
            val markerQuarantine = PrivateAudioPath.deletionQuarantineName(marker.toPath().fileName)
            val existed = Files.exists(archive.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                archiveQuarantine?.let { Files.exists(File(cacheDirectory, it.toString()).toPath(), LinkOption.NOFOLLOW_LINKS) } == true
            val hasOwnerMarker = Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                markerQuarantine?.let { Files.exists(File(cacheDirectory, it.toString()).toPath(), LinkOption.NOFOLLOW_LINKS) } == true
            val discarded = if (hasOwnerMarker) {
                discard(cacheDirectory, id, durability)
            } else {
                discardOrphanArchive(cacheDirectory, id, durability)
            }
            if (discarded) {
                if (existed) removedArchives++
            } else {
                pendingIds += id
            }
        }
        return PreparedArchiveCleanupResult(removedArchives, pendingIds)
    }

    private fun quarantineTargetOrSelf(name: String, directory: File): String =
        PrivateAudioPath.deletionQuarantineTargetName(directory.toPath().fileSystem.getPath(name))?.toString() ?: name

    private fun archiveIdFromName(name: String): String? = when {
        name.startsWith(ARCHIVE_PREFIX) && name.endsWith(OWNER_SUFFIX) ->
            name.removePrefix(ARCHIVE_PREFIX).removeSuffix(OWNER_SUFFIX)
        name.startsWith(ARCHIVE_PREFIX) && name.endsWith(ARCHIVE_SUFFIX) ->
            validationSnapshotId(name) ?: name.removePrefix(ARCHIVE_PREFIX).removeSuffix(ARCHIVE_SUFFIX)
        else -> null
    }

    private fun discardOrphanArchive(
        cacheDirectory: File,
        id: String,
        durability: RestoreFileDurability,
    ): Boolean {
        if (!isSafeCacheDirectory(cacheDirectory) || !validId.matches(id)) return false
        val archive = archiveFile(cacheDirectory, id)
        val marker = ownerMarker(cacheDirectory, id)
        val archiveName = childName(cacheDirectory, archive)
        val markerName = childName(cacheDirectory, marker)
        val cacheHandle = try {
            PrivateAudioPath.openDirectoryNoFollow(cacheDirectory)
        } catch (_: Exception) {
            return false
        }
        return cacheHandle.use { openedCache ->
            try {
                if (hasEntry(openedCache, markerName) ||
                    PrivateAudioPath.hasDeleteQuarantineNoFollow(openedCache, markerName)
                ) return discard(cacheDirectory, id, durability)
                if (hasValidationSnapshot(openedCache, id)) return false
                if (!hasEntry(openedCache, archiveName)) {
                    if (PrivateAudioPath.hasDeleteQuarantineNoFollow(openedCache, archiveName) &&
                        !PrivateAudioPath.cleanupDeleteQuarantineNoFollow(openedCache, archiveName)
                    ) return false
                    return syncDirectory(cacheDirectory, durability, openedCache)
                }
                if (!isSafeRegularArchive(openedCache, archiveName)) return false
                val expectedFileKey = PrivateAudioPath.regularFileKeyNoFollow(openedCache, archiveName) ?: return false
                if (!PrivateAudioPath.deleteRegularFileNoFollow(openedCache, archiveName, expectedFileKey)) return false
                syncDirectory(cacheDirectory, durability, openedCache)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isSafeRegularArchive(cacheDirectory: File, archive: File): Boolean =
        Files.isRegularFile(archive.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            PrivateAudioPath.isDirectChild(cacheDirectory, archive)

    private fun isSafeRegularArchive(directory: SecureDirectoryStream<Path>, archiveName: Path): Boolean =
        directory.getFileAttributeView(
            archiveName,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )?.readAttributes()?.isRegularFile == true

    private fun hasEntry(directory: SecureDirectoryStream<Path>, childName: Path): Boolean = try {
        val view = directory.getFileAttributeView(
            childName,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: throw IOException("The secure filesystem does not expose basic file attributes.")
        view.readAttributes()
        true
    } catch (_: NoSuchFileException) {
        false
    }

    private fun childName(directory: File, child: File): Path {
        if (!PrivateAudioPath.isDirectChild(directory, child)) {
            throw SecurityException("The temporary archive path is unsafe.")
        }
        val name = child.name
        if (name.isEmpty() || '/' in name || '\\' in name || name == "." || name == "..") {
            throw SecurityException("The temporary archive path is unsafe.")
        }
        return directory.toPath().fileSystem.getPath(name)
    }

    private fun syncDirectory(
        cacheDirectory: File,
        durability: RestoreFileDurability,
        openedDirectory: SecureDirectoryStream<Path>? = null,
    ): Boolean = runCatching {
        if (openedDirectory == null) durability.syncDirectory(cacheDirectory)
        else durability.syncDirectory(cacheDirectory, openedDirectory)
    }.isSuccess

    private fun isOwnedMarker(cacheDirectory: File, marker: File, id: String): Boolean =
        isSafeCacheDirectory(cacheDirectory) &&
            PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(cacheDirectory, marker, id)

    private fun isOwnedMarker(cacheHandle: SecureDirectoryStream<Path>, markerName: Path, id: String): Boolean =
        PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(cacheHandle, markerName, id)

    private fun requireValidCacheAndId(cacheDirectory: File, id: String) {
        require(validId.matches(id)) { "The temporary archive identifier is invalid." }
        if (!isSafeCacheDirectory(cacheDirectory)) {
            throw SecurityException("The app-private cache directory is redirected or unsafe.")
        }
    }

    private fun isSafeCacheDirectory(directory: File): Boolean =
        directory.isDirectory &&
            runCatching { directory.canonicalFile == directory.absoluteFile.toPath().normalize().toFile() }.getOrDefault(false)
}

internal data class PreparedArchiveCleanupResult(
    val removedArchives: Int,
    val pendingIds: List<String>,
)
