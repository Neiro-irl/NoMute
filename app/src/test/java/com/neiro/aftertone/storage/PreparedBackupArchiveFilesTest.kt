package com.neiro.aftertone.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import com.neiro.aftertone.model.PrivateAudioPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PreparedBackupArchiveFilesTest {
    @Test
    fun queuedCleanupRetryDoesNotDeleteTheCurrentRestoreSelection() = runBlocking {
        val cache = Files.createTempDirectory("aftertone-live-archive-selection-").toFile()
        try {
            val currentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val queuedId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            val currentArchive = PreparedBackupArchiveFiles.create(cache, currentId).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            PreparedBackupArchiveFiles.create(cache, queuedId).writeBytes(byteArrayOf(4, 5, 6))
            val owner = PreparedBackupArchiveOwner()
            val current = PreparedBackupArchive(currentId, BackupSummary(1, 0, 0, 3))
            val queued = PreparedBackupArchive(queuedId, BackupSummary(1, 0, 0, 3))
            assertTrue(owner.retain(current))
            assertTrue(owner.retain(queued, cleanupPending = true))

            assertTrue(owner.discard(queued) { PreparedBackupArchiveFiles.discard(cache, queued.id) })
            val cleanup = PreparedBackupArchiveFiles.cleanupOrphansWithPending(
                cache,
                owner.protectedArchiveIdsForCleanup(),
            )

            assertTrue(cleanup.pendingIds.isEmpty())
            assertTrue(currentArchive.isFile)
            assertTrue(PreparedBackupArchiveFiles.isOwned(cache, currentId))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun ownerMarkerDirectoryEntryIsSyncedBeforeArchiveCanBeWritten() {
        val cache = Files.createTempDirectory("aftertone-owner-marker-durability-").toFile()
        try {
            val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val marker = File(cache, "aftertone-restore-source-$id.owner")
            val archive = File(cache, "aftertone-restore-source-$id.zip")
            var directorySyncs = 0
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    assertEquals(cache, directory)
                    assertTrue(marker.isFile)
                    assertEquals(id, marker.readText(Charsets.UTF_8))
                    assertFalse(archive.exists())
                    directorySyncs++
                }
            }

            assertEquals(archive, PreparedBackupArchiveFiles.create(cache, id, durability))
            assertEquals(1, directorySyncs)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun orphanSweepFailsClosedWhenDirectoryListingIsUnavailable() {
        val cache = Files.createTempDirectory("aftertone-owner-scan-unavailable-").toFile()
        try {
            assertThrows(IOException::class.java) {
                PreparedBackupArchiveFiles.cleanupOrphansWithPending(cache) { null }
            }
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun orphanSweepDeletesOnlyArchivesWithMatchingOwnerMarkers() {
        val cache = Files.createTempDirectory("aftertone-prepared-archive-store-").toFile()
        try {
            val ownedId = "11111111-1111-1111-1111-111111111111"
            val ownedArchive = PreparedBackupArchiveFiles.create(cache, ownedId).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val ownedMarker = File(cache, "aftertone-restore-source-$ownedId.owner")

            val unownedId = "22222222-2222-2222-2222-222222222222"
            val unownedArchive = File(cache, "aftertone-restore-source-$unownedId.zip").apply {
                writeBytes(byteArrayOf(4, 5, 6))
            }
            val mismatchedMarker = File(cache, "aftertone-restore-source-$unownedId.owner").apply {
                writeText("not-the-archive-id")
            }

            assertEquals(1, PreparedBackupArchiveFiles.cleanupOrphans(cache))

            assertFalse(ownedArchive.exists())
            assertFalse(ownedMarker.exists())
            assertTrue(unownedArchive.isFile)
            assertTrue(mismatchedMarker.isFile)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun orphanSweepReportsFailedOwnedArchiveForLaterCleanupRetry() {
        val cache = Files.createTempDirectory("aftertone-prepared-archive-retry-").toFile()
        try {
            val id = "33333333-3333-3333-3333-333333333333"
            val archive = PreparedBackupArchiveFiles.create(cache, id)
            assertTrue(archive.mkdir())

            val failed = PreparedBackupArchiveFiles.cleanupOrphansWithPending(cache)
            assertEquals(listOf(id), failed.pendingIds)
            assertTrue(archive.isDirectory)

            assertTrue(archive.delete())
            val retried = PreparedBackupArchiveFiles.cleanupOrphansWithPending(cache)
            assertTrue(retried.pendingIds.isEmpty())
            assertFalse(File(cache, "aftertone-restore-source-$id.owner").exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun orphanSweepRemovesSafelyNamedArchiveWithoutOwnerMarkerOnly() {
        val cache = Files.createTempDirectory("aftertone-prepared-archive-orphan-zip-").toFile()
        try {
            val id = "55555555-5555-5555-5555-555555555555"
            val orphan = File(cache, "${PreparedBackupArchiveFiles.ARCHIVE_PREFIX}$id.zip").apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val malformedName = File(cache, "${PreparedBackupArchiveFiles.ARCHIVE_PREFIX}not-a-uuid.zip")
                .apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val unexpected = File(cache, "keep-this.txt").apply { writeBytes(byteArrayOf(7, 8, 9)) }

            val cleanup = PreparedBackupArchiveFiles.cleanupOrphansWithPending(cache)

            assertEquals(1, cleanup.removedArchives)
            assertTrue(cleanup.pendingIds.isEmpty())
            assertFalse(orphan.exists())
            assertTrue(malformedName.isFile)
            assertTrue(unexpected.isFile)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun orphanSweepRecoversOwnerMarkerMovedIntoDeleteQuarantine() {
        val cache = Files.createTempDirectory("aftertone-owner-marker-quarantine-").toFile()
        try {
            val id = "66666666-6666-6666-6666-666666666666"
            val marker = PreparedBackupArchiveFiles.ownerMarker(cache, id)
            PreparedBackupArchiveFiles.create(cache, id)
            val markerName = marker.toPath().fileName
            val quarantineName = requireNotNull(PrivateAudioPath.deletionQuarantineName(markerName))
            val abandoned = File(cache, quarantineName.toString())
            Files.move(marker.toPath(), abandoned.toPath())

            val cleanup = PreparedBackupArchiveFiles.cleanupOrphansWithPending(cache)

            assertFalse("Orphan cleanup must recover the marker deletion left mid-flight.", abandoned.exists())
            assertTrue(cleanup.pendingIds.isEmpty())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun discardKeepsExternalMarkerWhenCachePathIsReplacedDuringSync() {
        val root = Files.createTempDirectory("aftertone-prepared-archive-directory-race-").toFile()
        try {
            val cache = File(root, "cache").apply { assertTrue(mkdir()) }
            val movedCache = File(root, "opened-cache")
            val outside = File(root, "outside").apply { assertTrue(mkdir()) }
            val id = "88888888-8888-8888-8888-888888888888"
            val archive = PreparedBackupArchiveFiles.create(cache, id).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val externalMarker = File(outside, "${PreparedBackupArchiveFiles.ARCHIVE_PREFIX}$id.owner").apply { writeText(id) }
            var syncCalls = 0
            val swapCacheDuringSync = object : RestoreFileDurability {
                override fun syncFile(output: FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    syncCalls++
                    if (syncCalls == 1) {
                        Files.move(cache.toPath(), movedCache.toPath())
                        Files.createSymbolicLink(cache.toPath(), outside.toPath())
                    }
                }
            }

            assertFalse(
                "Discard must not report durable cleanup when the cache pathname no longer resolves to the opened directory.",
                PreparedBackupArchiveFiles.discard(cache, id, swapCacheDuringSync),
            )

            assertFalse("The archive should be removed from the opened cache directory.", File(movedCache, archive.name).exists())
            assertTrue("A cache-path replacement must not redirect marker deletion outside the opened cache.", externalMarker.isFile)
            assertEquals(id, externalMarker.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveWriterAndFailureCleanupStayBoundToOpenedCacheAfterPathReplacement() {
        val root = Files.createTempDirectory("aftertone-prepared-archive-writer-race-").toFile()
        try {
            val cache = File(root, "cache").apply { assertTrue(mkdir()) }
            val movedCache = File(root, "opened-cache")
            val outside = File(root, "outside").apply { assertTrue(mkdir()) }
            val id = "99999999-9999-9999-9999-999999999999"
            val archiveName = "${PreparedBackupArchiveFiles.ARCHIVE_PREFIX}$id.zip"
            val externalArchive = File(outside, archiveName).apply { writeText("preserve this file") }
            var validationReached = false
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) = Unit
            }

            assertThrows(IOException::class.java) {
                PreparedBackupArchiveFiles.createAndWriteAndValidate(
                    cacheDirectory = cache,
                    id = id,
                    durability = durability,
                    writeArchive = { output ->
                        Files.move(cache.toPath(), movedCache.toPath())
                        Files.createSymbolicLink(cache.toPath(), outside.toPath())
                        output.write(byteArrayOf(1, 2, 3, 4))
                    },
                    validateArchive = { archive ->
                        validationReached = true
                        archive.readBytes()
                    },
                )
            }

            assertFalse("The staged archive must be removed from the pinned cache after failure.", File(movedCache, archiveName).exists())
            val markerName = "${PreparedBackupArchiveFiles.ARCHIVE_PREFIX}$id.owner"
            assertFalse("The owner marker must be removed from the pinned cache after failure.", File(movedCache, markerName).exists())
            assertTrue("A replacement cache must not be modified or have its archive truncated.", externalArchive.isFile)
            assertEquals("preserve this file", externalArchive.readText(Charsets.UTF_8))
            assertFalse("Archive validation must not run after the pinned path identity is lost.", validationReached)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveValidationRejectsEntryReplacementDuringInspection() {
        val root = Files.createTempDirectory("aftertone-prepared-archive-entry-race-").toFile()
        try {
            val cache = File(root, "cache").apply { assertTrue(mkdir()) }
            val id = "88888888-8888-8888-8888-888888888888"
            val outside = File(root, "outside.zip").apply { writeBytes(byteArrayOf(9, 8, 7)) }
            var validationReadBytes: List<Int>? = null
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) = Unit
            }

            assertThrows(IOException::class.java) {
                PreparedBackupArchiveFiles.createAndWriteAndValidate(
                    cacheDirectory = cache,
                    id = id,
                    durability = durability,
                    writeArchive = { output -> output.write(byteArrayOf(1, 2, 3, 4)) },
                    validateArchive = { archive ->
                        val stagedArchive = PreparedBackupArchiveFiles.archiveFile(cache, id)
                        Files.createSymbolicLink(stagedArchive.toPath(), outside.toPath())
                        validationReadBytes = archive.readBytes().map(Byte::toInt)
                        validationReadBytes
                    },
                )
            }

            assertEquals("Inspection must read the private snapshot, not the replaced source path.", listOf(1, 2, 3, 4), validationReadBytes)
            assertTrue("The outside target must remain unchanged.", outside.isFile)
            assertEquals(listOf(9, 8, 7), outside.readBytes().map(Byte::toInt))
            assertFalse(
                "Failed validation must unlink the replaced symlink from the pinned cache without touching its target.",
                Files.exists(PreparedBackupArchiveFiles.archiveFile(cache, id).toPath(), LinkOption.NOFOLLOW_LINKS),
            )
            assertFalse(PreparedBackupArchiveFiles.ownerMarker(cache, id).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun orphanCleanupRemovesOwnedValidationSnapshot() {
        val cache = Files.createTempDirectory("aftertone-validation-snapshot-orphan-").toFile()
        try {
            val id = "77777777-7777-7777-7777-777777777777"
            PreparedBackupArchiveFiles.create(cache, id)
            val snapshot = File(
                cache,
                "${PreparedBackupArchiveFiles.ARCHIVE_PREFIX}$id.validation-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.zip",
            ).apply { writeBytes(byteArrayOf(1, 2, 3)) }

            val cleanup = PreparedBackupArchiveFiles.cleanupOrphansWithPending(cache)

            assertTrue("An owned abandoned validation snapshot should be discarded.", cleanup.pendingIds.isEmpty())
            assertFalse(snapshot.exists())
            assertFalse(PreparedBackupArchiveFiles.ownerMarker(cache, id).exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun orphanCleanupKeepsUnownedValidationSnapshotPending() {
        val cache = Files.createTempDirectory("aftertone-unowned-validation-snapshot-").toFile()
        try {
            val id = "55555555-5555-5555-5555-555555555555"
            val snapshot = File(
                cache,
                "${PreparedBackupArchiveFiles.ARCHIVE_PREFIX}$id.validation-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.zip",
            ).apply { writeBytes(byteArrayOf(4, 5, 6)) }

            val cleanup = PreparedBackupArchiveFiles.cleanupOrphansWithPending(cache)

            assertEquals(listOf(id), cleanup.pendingIds)
            assertTrue(snapshot.isFile)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun discardKeepsOwnerMarkerWhenArchiveRemovalSyncFails() {
        val cache = Files.createTempDirectory("aftertone-prepared-archive-first-sync-failure-").toFile()
        try {
            val id = "66666666-6666-6666-6666-666666666666"
            val archive = PreparedBackupArchiveFiles.create(cache, id).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val marker = File(cache, "${PreparedBackupArchiveFiles.ARCHIVE_PREFIX}$id.owner")
            var syncCalls = 0
            val failFirstSync = object : RestoreFileDurability {
                override fun syncFile(output: FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    assertEquals(cache, directory)
                    syncCalls++
                    if (syncCalls == 1) throw IOException("Injected archive-removal sync failure.")
                    SystemRestoreFileDurability.syncDirectory(directory)
                }
            }

            assertFalse(PreparedBackupArchiveFiles.discard(cache, id, failFirstSync))
            assertFalse(archive.exists())
            assertTrue(marker.isFile)
            assertTrue(PreparedBackupArchiveFiles.discard(cache, id))
            assertFalse(marker.exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun discardCanRetryDirectorySyncAfterOwnerMarkerRemovalSyncFails() {
        val cache = Files.createTempDirectory("aftertone-prepared-archive-second-sync-failure-").toFile()
        try {
            val id = "77777777-7777-7777-7777-777777777777"
            val archive = PreparedBackupArchiveFiles.create(cache, id).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val marker = File(cache, "${PreparedBackupArchiveFiles.ARCHIVE_PREFIX}$id.owner")
            var syncCalls = 0
            val failSecondSync = object : RestoreFileDurability {
                override fun syncFile(output: FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    assertEquals(cache, directory)
                    syncCalls++
                    if (syncCalls == 2) throw IOException("Injected owner-marker removal sync failure.")
                    SystemRestoreFileDurability.syncDirectory(directory)
                }
            }

            assertFalse(PreparedBackupArchiveFiles.discard(cache, id, failSecondSync))
            assertEquals(2, syncCalls)
            assertFalse(archive.exists())
            assertFalse(marker.exists())
            assertTrue(PreparedBackupArchiveFiles.discard(cache, id))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun markerlessArchiveIsNotReportedAsFullyDiscarded() {
        val cache = Files.createTempDirectory("aftertone-prepared-archive-markerless-").toFile()
        try {
            val id = "44444444-4444-4444-4444-444444444444"
            val archive = PreparedBackupArchiveFiles.create(cache, id).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val marker = File(cache, "aftertone-restore-source-$id.owner")
            assertTrue(marker.delete())

            assertFalse(PreparedBackupArchiveFiles.isFullyDiscarded(cache, id))
            assertFalse(PreparedBackupArchiveFiles.discard(cache, id))
        } finally {
            cache.deleteRecursively()
        }
    }
}
