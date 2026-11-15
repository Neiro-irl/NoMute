package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRestoreAbandonedGenerationTest {
    @Test
    fun noJournalRecoveryRemovesOnlyEmptyMarkerlessDirectChildAndPreservesUnexpectedPaths() {
        val root = Files.createTempDirectory("aftertone-abandoned-generation-").toFile()
        try {
            val filesDirectory = File(root, "files").apply { assertTrue(mkdir()) }
            val audioDirectory = File(filesDirectory, "audio").apply { assertTrue(mkdir()) }
            val empty = File(audioDirectory, "restore-00000000-0000-0000-0000-000000000001").apply { assertTrue(mkdir()) }
            val nonEmpty = File(audioDirectory, "restore-00000000-0000-0000-0000-000000000002").apply { assertTrue(mkdir()) }
            val unexpected = File(nonEmpty, "unexpected.bin").apply { writeBytes(byteArrayOf(9, 8, 7)) }
            val malformedName = File(audioDirectory, "restore-not-a-uuid").apply { assertTrue(mkdir()) }
            val nestedParent = File(audioDirectory, "unrelated").apply { assertTrue(mkdir()) }
            val nested = File(nestedParent, "restore-00000000-0000-0000-0000-000000000004").apply { assertTrue(mkdir()) }
            val outside = File(root, "outside").apply { assertTrue(mkdir()) }
            val outsideFile = File(outside, "preserve.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val symlink = File(audioDirectory, "restore-00000000-0000-0000-0000-000000000003")
            Files.createSymbolicLink(symlink.toPath(), outside.toPath())
            var audioDirectorySyncs = 0
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    if (directory == audioDirectory) audioDirectorySyncs++
                    SystemRestoreFileDurability.syncDirectory(directory)
                }
            }

            val recovered = transaction(filesDirectory, audioDirectory, durability).recoverInterruptedRestore()

            assertEquals(null, recovered)
            assertFalse("The exact empty markerless generation should be swept after no-journal startup.", empty.exists())
            assertTrue(nonEmpty.isDirectory)
            assertEquals(listOf(9, 8, 7), unexpected.readBytes().map(Byte::toInt))
            assertTrue(malformedName.isDirectory)
            assertTrue(nested.isDirectory)
            assertTrue(Files.isSymbolicLink(symlink.toPath()))
            assertEquals(listOf(1, 2, 3), outsideFile.readBytes().map(Byte::toInt))
            assertTrue("Removing a generation must sync audioDirectory.", audioDirectorySyncs > 0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun markerlessGenerationParentSyncFailureStaysPendingAndRetriesAfterDirectoryIsGone() {
        val root = Files.createTempDirectory("aftertone-abandoned-generation-sync-").toFile()
        try {
            val filesDirectory = File(root, "files").apply { assertTrue(mkdir()) }
            val audioDirectory = File(filesDirectory, "audio").apply { assertTrue(mkdir()) }
            val empty = File(audioDirectory, "restore-00000000-0000-0000-0000-000000000011").apply { assertTrue(mkdir()) }
            var failAudioDirectorySync = true
            var audioDirectorySyncs = 0
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    if (directory == audioDirectory) {
                        audioDirectorySyncs++
                        if (failAudioDirectorySync) throw IOException("Injected audio-directory sync failure.")
                    }
                    SystemRestoreFileDurability.syncDirectory(directory)
                }
            }
            val transaction = transaction(filesDirectory, audioDirectory, durability)

            val pendingId = transaction.recoverInterruptedRestore()

            assertTrue("A failed parent fsync must keep no-journal recovery pending.", pendingId != null)
            assertFalse("The empty directory may already be unlinked when its parent fsync fails.", empty.exists())
            val attemptsAfterUnlinkFailure = audioDirectorySyncs
            assertEquals(RestoreFinalizationResult.CLEANUP_PENDING, transaction.finishRestoreOutcome(requireNotNull(pendingId)))
            assertTrue("Retry must repeat parent fsync even when the directory is already gone.", audioDirectorySyncs > attemptsAfterUnlinkFailure)

            val attemptsBeforeSecondRecovery = audioDirectorySyncs
            assertTrue(
                "A second no-journal recovery must remain pending while parent fsync keeps failing.",
                transaction.recoverInterruptedRestore() != null,
            )
            assertTrue("Startup recovery must retry parent fsync after the directory is gone.", audioDirectorySyncs > attemptsBeforeSecondRecovery)

            failAudioDirectorySync = false
            val attemptsBeforeSuccess = audioDirectorySyncs
            assertEquals(null, transaction.recoverInterruptedRestore())
            assertTrue(audioDirectorySyncs > attemptsBeforeSuccess)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun candidateDirectoryListingFailureStaysPendingAndRetryRemovesIt() {
        val root = Files.createTempDirectory("aftertone-abandoned-generation-listing-").toFile()
        try {
            val filesDirectory = File(root, "files").apply { assertTrue(mkdir()) }
            val realAudioDirectory = File(filesDirectory, "audio").apply { assertTrue(mkdir()) }
            val empty = File(realAudioDirectory, "restore-00000000-0000-0000-0000-000000000021").apply { assertTrue(mkdir()) }
            var failCandidateListing = true
            val audioDirectoryView = object : File(realAudioDirectory.path) {
                override fun listFiles(): Array<File>? = super.listFiles()?.map { child ->
                    if (failCandidateListing && child.name == empty.name) {
                        object : File(child.path) {
                            override fun listFiles(): Array<File>? = throw SecurityException("Injected candidate-listing failure.")
                        }
                    } else {
                        child
                    }
                }?.toTypedArray()
            }
            val transaction = transaction(filesDirectory, audioDirectoryView)

            val pendingId = transaction.recoverInterruptedRestore()

            assertTrue("An unavailable candidate listing must not be mistaken for an empty directory.", pendingId != null)
            assertTrue(empty.isDirectory)
            failCandidateListing = false
            assertEquals(null, transaction.recoverInterruptedRestore())
            assertFalse(empty.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun transaction(
        filesDirectory: File,
        audioDirectory: File,
        durability: RestoreFileDurability = SystemRestoreFileDurability,
    ) = LibraryRestoreTransaction(
        filesDirectory = filesDirectory,
        audioDirectory = audioDirectory,
        stateStore = object : LibraryStateStore {
            override fun read(): String? = null
            override fun write(encoded: String?): Boolean = true
        },
        durability = durability,
        decodeSnapshot = { LibrarySnapshot() },
    )
}
