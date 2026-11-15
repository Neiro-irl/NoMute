package com.neiro.aftertone

import android.net.TestUri
import com.neiro.aftertone.storage.AudioImportDurability
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioOpenRequestQueueSnapshotBackupTest {
    @Test
    fun backupFlushesTheFilesParentAndSnapshotDirectoryBeforeReportingSuccess() {
        val root = Files.createTempDirectory("aftertone-queue-quarantine-").toFile()
        try {
            val filesDirectory = File(root, "files").apply { assertTrue(mkdir()) }
            val events = mutableListOf<DurabilityEvent>()
            val durability = object : AudioImportDurability {
                override fun syncFile(file: File) {
                    events += DurabilityEvent("file", file)
                }

                override fun syncDirectory(directory: File) {
                    events += DurabilityEvent("directory", directory)
                }
            }
            val backupWriter = ExternalAudioOpenRequestQueueSnapshotBackup(filesDirectory, durability)
            val raw = "aftertone-external-audio-open-queue-v1\nP\tinvalid-record"

            val succeeded = backupWriter.backup(raw)

            val quarantineDirectory = File(filesDirectory, "external-audio-queue-quarantine")
            assertTrue(succeeded)
            assertEquals(3, events.size)
            assertEquals("directory", events[0].kind)
            assertEquals(filesDirectory, events[0].file)
            assertEquals("file", events[1].kind)
            assertEquals(quarantineDirectory, events[1].file.parentFile)
            assertTrue(events[1].file.name.endsWith(".tmp"))
            assertEquals("directory", events[2].kind)
            assertEquals(quarantineDirectory, events[2].file)
            val backup = requireNotNull(quarantineDirectory.listFiles { file -> file.name.endsWith(".snapshot") }).single()
            assertEquals(raw, backup.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedQuarantineDirectorySyncCannotReplaceCorruptQueueAndRetryResyncsExistingBackup() {
        val root = Files.createTempDirectory("aftertone-queue-quarantine-retry-").toFile()
        try {
            val filesDirectory = File(root, "files").apply { assertTrue(mkdir()) }
            val quarantineDirectory = File(filesDirectory, "external-audio-queue-quarantine")
            var failSnapshotDirectorySync = true
            val syncedDirectories = mutableListOf<File>()
            val syncedFiles = mutableListOf<File>()
            val durability = object : AudioImportDurability {
                override fun syncFile(file: File) {
                    syncedFiles += file
                }

                override fun syncDirectory(directory: File) {
                    if (directory == quarantineDirectory && failSnapshotDirectorySync) {
                        throw IOException("Injected quarantine-directory sync failure.")
                    }
                    syncedDirectories += directory
                }
            }
            val backupWriter = ExternalAudioOpenRequestQueueSnapshotBackup(filesDirectory, durability)
            val raw = "aftertone-external-audio-open-queue-v1\nP\tinvalid-record"
            val validRequest = ExternalAudioOpenRequest(
                id = "00000000-0000-0000-0000-000000000061",
                uri = TestUri("content://media/external/audio/61"),
            )
            var encoded: String? = raw
            var commitCalls = 0
            val store = ExternalAudioOpenRequestQueueStore(
                readEncoded = { encoded },
                commitEncoded = { replacement ->
                    commitCalls++
                    encoded = replacement
                    true
                },
                parseUri = ::TestUri,
                backupCorruptSnapshot = backupWriter::backup,
            )

            val failedRecovery = store.recoverCorruptSnapshot(
                ExternalAudioOpenRequestQueueSnapshot(pendingRequests = listOf(validRequest)),
            )

            assertFalse(failedRecovery.persisted)
            assertEquals(ExternalAudioOpenQueueStoreProblem.RecoveryFailure, failedRecovery.storeProblem)
            assertEquals(raw, encoded)
            assertEquals(0, commitCalls)

            failSnapshotDirectorySync = false
            syncedDirectories.clear()
            syncedFiles.clear()
            val retriedRecovery = store.recoverCorruptSnapshot(requireNotNull(failedRecovery.retrySnapshot))

            assertTrue(retriedRecovery.persisted)
            assertEquals(1, commitCalls)
            assertTrue(encoded != raw)
            assertEquals(listOf(filesDirectory, quarantineDirectory), syncedDirectories)
            val durableBackup = requireNotNull(quarantineDirectory.listFiles { file -> file.name.endsWith(".snapshot") }).single()
            assertEquals(listOf(durableBackup), syncedFiles)
        } finally {
            root.deleteRecursively()
        }
    }

    private data class DurabilityEvent(val kind: String, val file: File)
}
