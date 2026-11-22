package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.PlaylistArtworkFileName
import com.neiro.aftertone.model.Track
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedNoJournalRecoveryCleanupTest {
    @Test
    fun noJournalCleanupRemovesApprovedOrphansButKeepsActiveAudioAndMarkerlessStaging() {
        val root = Files.createTempDirectory("aftertone-scoped-recovery-").toFile()
        val filesDirectory = File(root, "files").apply { mkdir() }
        val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
        val temporaryJournal = File(filesDirectory, ".aftertone-restore-journal-11111111-1111-4111-8111-111111111111")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val unrelatedPrivateFile = File(filesDirectory, "do-not-touch.bin").apply { writeBytes(byteArrayOf(8, 9)) }
        val stagingId = "22222222-2222-4222-8222-222222222222"
        val stagingDirectory = File(audioDirectory, ".aftertone-restore-stage-$stagingId").apply { mkdir() }
        File(stagingDirectory, ".aftertone-restore-owner").writeText(stagingId)
        File(stagingDirectory, "restored-1.mp3").writeBytes(byteArrayOf(4, 5, 6))
        val emptyGeneration = File(audioDirectory, "restore-33333333-3333-4333-8333-333333333333").apply { mkdir() }
        val markerlessStage = File(audioDirectory, ".aftertone-restore-stage-55555555-5555-4555-8555-555555555555")
            .apply { mkdir() }
        val activeGeneration = File(audioDirectory, "restore-44444444-4444-4444-8444-444444444444").apply { mkdir() }
        File(activeGeneration, ".aftertone-restore-owner").writeText("44444444-4444-4444-8444-444444444444")
        val activeAudio = File(activeGeneration, "active-1.mp3").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val looseActiveAudio = File(audioDirectory, "loose-active.mp3").apply { writeBytes(byteArrayOf(10, 11)) }

        try {
            assertNull(transaction(filesDirectory, audioDirectory).recoverInterruptedRestore())

            assertFalse("Only the temporary journal file is removed from filesDir.", temporaryJournal.exists())
            assertTrue(unrelatedPrivateFile.isFile)
            assertFalse("An owner-marked staging folder is removed as one approved unit.", stagingDirectory.exists())
            assertFalse(emptyGeneration.exists())
            assertTrue("Markerless staging is outside the approved cleanup scope.", markerlessStage.isDirectory)
            assertTrue("A non-empty generation and its active audio survive.", activeGeneration.isDirectory)
            assertTrue(activeAudio.isFile)
            assertTrue(looseActiveAudio.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anyPresentJournalPreventsScopedCleanup() {
        val root = Files.createTempDirectory("aftertone-scoped-journal-present-").toFile()
        val filesDirectory = File(root, "files").apply { mkdir() }
        val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
        File(filesDirectory, ".aftertone-restore-journal").writeBytes(byteArrayOf(0x01))
        val temporaryJournal = File(filesDirectory, ".aftertone-restore-journal-55555555-5555-4555-8555-555555555555")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stagingId = "66666666-6666-4666-8666-666666666666"
        val stagingDirectory = File(audioDirectory, ".aftertone-restore-stage-$stagingId").apply { mkdir() }
        File(stagingDirectory, ".aftertone-restore-owner").writeText(stagingId)
        File(stagingDirectory, "restored-1.mp3").writeBytes(byteArrayOf(4, 5, 6))
        val emptyGeneration = File(audioDirectory, "restore-77777777-7777-4777-8777-777777777777").apply { mkdir() }

        try {
            val recovery = runCatching { transaction(filesDirectory, audioDirectory).recoverInterruptedRestore() }
            assertTrue("A present but unreadable journal must block rather than fall through to no-journal cleanup.",
                recovery.isFailure || recovery.getOrNull() != null)
            assertTrue("A temporary journal is not touched when a main journal exists.", temporaryJournal.isFile)
            assertTrue(stagingDirectory.isDirectory)
            assertTrue(File(stagingDirectory, "restored-1.mp3").isFile)
            assertTrue(emptyGeneration.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unownedStagingAndSymlinkedTemporaryJournalAreNeverFollowedOrDeleted() {
        val root = Files.createTempDirectory("aftertone-scoped-unowned-").toFile()
        val filesDirectory = File(root, "files").apply { mkdir() }
        val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
        val stagingId = "88888888-8888-4888-8888-888888888888"
        val unownedStaging = File(audioDirectory, ".aftertone-restore-stage-$stagingId").apply { mkdir() }
        val stagedAudio = File(unownedStaging, "restored-1.mp3").apply { writeBytes(byteArrayOf(2, 4, 6)) }
        val emptyMarkerlessStage = File(audioDirectory, ".aftertone-restore-stage-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            .apply { mkdir() }
        val externalFile = File(root, "outside.mp3").apply { writeBytes(byteArrayOf(3, 5, 7)) }
        val symlinkedTemporaryJournal = File(filesDirectory, ".aftertone-restore-journal-99999999-9999-4999-8999-999999999999")
        Files.createSymbolicLink(symlinkedTemporaryJournal.toPath(), externalFile.toPath())

        try {
            transaction(filesDirectory, audioDirectory).recoverInterruptedRestore()

            assertTrue(unownedStaging.isDirectory)
            assertTrue(stagedAudio.isFile)
            assertTrue("Empty markerless staging is not an authorized target.", emptyMarkerlessStage.isDirectory)
            assertTrue(Files.isSymbolicLink(symlinkedTemporaryJournal.toPath()))
            assertTrue("A symlink target is never followed or deleted.", externalFile.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ownerMarkedStagingIsPreservedWhenItsPlaylistArtworkIsInTheActiveSnapshot() {
        val root = Files.createTempDirectory("aftertone-active-staged-audio-").toFile()
        val filesDirectory = File(root, "files").apply { mkdir() }
        val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
        val stagingId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val stagingDirectory = File(audioDirectory, ".aftertone-restore-stage-$stagingId").apply { mkdir() }
        File(stagingDirectory, ".aftertone-restore-owner").writeText(stagingId)
        val activeArtwork = File(
            stagingDirectory,
            PlaylistArtworkFileName.forCustomCover("active-playlist", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        ).apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val snapshot = LibrarySnapshot(
            playlists = listOf(Playlist("active-playlist", "Active", artworkPath = activeArtwork.absolutePath)),
        )

        try {
            transaction(filesDirectory, audioDirectory, snapshot).recoverInterruptedRestore()

            assertTrue("Even owner-marked staging cannot delete playlist artwork referenced by the active snapshot.", stagingDirectory.isDirectory)
            assertTrue(activeArtwork.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun filesDirectoryEntryLimitFailsClosedBeforeDeletingTemporaryJournal() {
        val root = Files.createTempDirectory("aftertone-scoped-files-entry-limit-").toFile()
        val filesDirectory = File(root, "files").apply { mkdir() }
        val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
        val temporaryJournal = File(filesDirectory, ".aftertone-restore-journal-cccccccc-cccc-4ccc-8ccc-cccccccccccc")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        createUnrelatedEntries(filesDirectory, 40_001)

        try {
            val cleanupComplete = transaction(filesDirectory, audioDirectory)
                .cleanupApprovedNoJournalArtifactsOnce(LibrarySnapshot())

            assertFalse("An over-limit filesDir scan must fail closed before any cleanup.", cleanupComplete)
            assertTrue("An eligible temp journal stays intact when the total scan limit is exceeded.", temporaryJournal.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun audioDirectoryEntryLimitFailsClosedBeforeDeletingTemporaryJournal() {
        val root = Files.createTempDirectory("aftertone-scoped-audio-entry-limit-").toFile()
        val filesDirectory = File(root, "files").apply { mkdir() }
        val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
        val temporaryJournal = File(filesDirectory, ".aftertone-restore-journal-dddddddd-dddd-4ddd-8ddd-dddddddddddd")
            .apply { writeBytes(byteArrayOf(4, 5, 6)) }
        createUnrelatedEntries(audioDirectory, 40_001)

        try {
            val cleanupComplete = transaction(filesDirectory, audioDirectory)
                .cleanupApprovedNoJournalArtifactsOnce(LibrarySnapshot())

            assertFalse("An over-limit audio scan must fail closed before any cleanup.", cleanupComplete)
            assertTrue("A temp journal is not removed when the audio scan cannot be bounded.", temporaryJournal.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createUnrelatedEntries(directory: File, count: Int) {
        repeat(count) { index ->
            assertTrue(File(directory, "unrelated-$index.bin").createNewFile())
        }
    }

    private fun transaction(
        filesDirectory: File,
        audioDirectory: File,
        snapshot: LibrarySnapshot = LibrarySnapshot(),
    ) = LibraryRestoreTransaction(
        filesDirectory = filesDirectory,
        audioDirectory = audioDirectory,
        stateStore = object : LibraryStateStore {
            override fun read(): String? = "test-library-state"
            override fun write(encoded: String?): Boolean = true
        },
        decodeSnapshot = { snapshot },
    )
}
