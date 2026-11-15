package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioImporterTest {
    private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var wasClosed = false

        override fun close() {
            wasClosed = true
            super.close()
        }
    }

    @Test
    fun copiesAudioToGeneratedPrivatePathAndKeepsMetadata() {
        val directory = Files.createTempDirectory("aftertone-import-").toFile()
        try {
            val content = byteArrayOf(1, 4, 9, 16, 25)
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader {
                    AudioMetadata(title = "Evening Air", artist = "North Window", album = "Blue Hour", durationMs = 4_200)
                },
                availableBytes = { Long.MAX_VALUE },
            )

            val track = importer.import(
                input = ByteArrayInputStream(content),
                displayName = "../../outside/song.mp3",
                mimeType = "audio/mpeg",
            )

            val importedFile = File(track.filePath)
            assertTrue(PrivateAudioPath.isDirectChild(directory, importedFile))
            assertEquals(content.toList(), importedFile.readBytes().toList())
            assertEquals("Evening Air", track.title)
            assertEquals("North Window", track.artist)
            assertEquals("Blue Hour", track.album)
            assertEquals(4_200L, track.durationMs)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun syncsAudioAndEmbeddedArtworkBeforePublishingTheTrack() {
        val directory = Files.createTempDirectory("aftertone-import-durable-").toFile()
        val events = mutableListOf<String>()
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { AudioMetadata(artwork = byteArrayOf(2, 3, 5, 7)) },
                availableBytes = { Long.MAX_VALUE },
                durability = object : AudioImportDurability {
                    override fun syncFile(file: File) {
                        events += "file-${file.extension}"
                    }

                    override fun syncDirectory(directory: File) {
                        assertTrue(directory.listFiles().orEmpty().any { it.extension == "mp3" })
                        events += "directory"
                    }
                },
            )

            importer.import(ByteArrayInputStream(byteArrayOf(1, 4, 9)), "song.mp3", "audio/mpeg")

            assertEquals(listOf("file-part", "file-img", "directory"), events)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun syncsParentDirectoryWhenCreatingThePrivateAudioDirectory() {
        val parent = Files.createTempDirectory("aftertone-import-parent-").toFile()
        val directory = File(parent, "audio")
        val syncedDirectories = mutableListOf<File>()
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { AudioMetadata() },
                availableBytes = { Long.MAX_VALUE },
                durability = object : AudioImportDurability {
                    override fun syncFile(file: File) = Unit

                    override fun syncDirectory(directory: File) {
                        syncedDirectories += directory
                    }
                },
            )

            importer.import(ByteArrayInputStream(byteArrayOf(3, 6, 9)), "song.mp3", "audio/mpeg")

            assertEquals(listOf(parent, directory), syncedDirectories)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun defaultDurabilityDoesNotFollowSymlinkWhenSyncingFiles() {
        val root = Files.createTempDirectory("aftertone-import-no-follow-sync-").toFile()
        try {
            val target = File(root, "external-target.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val link = File(root, "audio.mp3")
            Files.createSymbolicLink(link.toPath(), target.toPath())

            assertThrows(IOException::class.java) { AndroidAudioImportDurability.syncFile(link) }
            assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedDirectorySyncCleansPublishedAudioAndEmbeddedArtwork() {
        val directory = Files.createTempDirectory("aftertone-import-sync-failure-").toFile()
        var directorySyncCalls = 0
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { AudioMetadata(artwork = byteArrayOf(11, 13, 17)) },
                availableBytes = { Long.MAX_VALUE },
                durability = object : AudioImportDurability {
                    override fun syncFile(file: File) = Unit

                    override fun syncDirectory(directory: File) {
                        directorySyncCalls += 1
                        throw IOException("directory sync failed $directorySyncCalls")
                    }
                },
            )

            val failure = assertThrows(IOException::class.java) {
                importer.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "song.mp3", "audio/mpeg")
            }
            assertEquals("Cleanup after a failed publish sync must itself be synced.", 2, directorySyncCalls)
            assertEquals("A cleanup-sync failure must be retained as a suppressed error.", 1, failure.suppressed.size)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun importsExtensionlessMp3WhenMetadataReaderReportsAudioMime() {
        val bytes = LocalAudioImporterTest::class.java.getResourceAsStream("/mp3-fixtures/tone-01.mp3")!!.use { it.readBytes() }
        listOf("application/octet-stream", "application/x-id3").forEach { providerMime ->
            val directory = Files.createTempDirectory("aftertone-import-untitled-mp3-").toFile()
            var mediaParserCalled = false
            try {
                val importer = LocalAudioImporter(
                    directory,
                    AudioMetadataReader { stagedFile ->
                        mediaParserCalled = true
                        assertArrayEquals(bytes, stagedFile.readBytes())
                        AudioMetadata(title = "Untitled MP3", durationMs = 2_000L, audioMimeType = "audio/mpeg")
                    },
                    availableBytes = { Long.MAX_VALUE },
                )

                val track = runCatching {
                    importer.import(ByteArrayInputStream(bytes), "2hug-Время", providerMime)
                }.getOrNull()

                assertTrue("$providerMime candidates must reach content validation.", mediaParserCalled)
                assertNotNull("The valid extensionless MP3 should be imported after media validation.", track)
                requireNotNull(track)
                assertTrue("The verified MP3 must receive an MP3 file extension.", track.filePath.endsWith(".mp3"))
                assertArrayEquals(bytes, File(track.filePath).readBytes())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun rejectsExtensionlessBytesWhenMetadataReaderRejectsAndCleansStagingFile() {
        val directory = Files.createTempDirectory("aftertone-import-invalid-untitled-").toFile()
        var mediaParserCalled = false
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader {
                    mediaParserCalled = true
                    throw IllegalArgumentException("The selected file contains no readable audio track.")
                },
                availableBytes = { Long.MAX_VALUE },
            )

            assertThrows(IllegalArgumentException::class.java) {
                importer.import(ByteArrayInputStream("not audio".toByteArray()), "untitled", null)
            }

            assertTrue("Extensionless generic input must reach content validation, not be rejected by its name.", mediaParserCalled)
            assertTrue("Rejected content must leave no staging or audio files.", directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun importsAacReportedByAndroidMediaExtractorUsingKnownContainerMime() {
        val directory = Files.createTempDirectory("aftertone-import-aac-m4a-").toFile()
        try {
            val content = byteArrayOf(7, 11, 13, 17)
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { AudioMetadata(audioMimeType = "audio/mp4a-latm") },
                availableBytes = { Long.MAX_VALUE },
            )

            val track = importer.import(ByteArrayInputStream(content), "recording.m4a", "audio/mp4")

            assertTrue("AAC in an MPEG-4 container must retain its supported container extension.", track.filePath.endsWith(".m4a"))
            assertEquals(content.toList(), File(track.filePath).readBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun importsExtensionlessAacReportedByAndroidMediaExtractorWithAacFallback() {
        val directory = Files.createTempDirectory("aftertone-import-aac-untitled-").toFile()
        try {
            val content = byteArrayOf(19, 23, 29, 31)
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { AudioMetadata(audioMimeType = "audio/mp4a-latm") },
                availableBytes = { Long.MAX_VALUE },
            )

            val track = importer.import(ByteArrayInputStream(content), "untitled recording", "application/octet-stream")

            assertTrue("AAC without container evidence must use the explicit AAC fallback.", track.filePath.endsWith(".aac"))
            assertEquals(content.toList(), File(track.filePath).readBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun importsExtensionlessPcmWavWhenAndroidReportsAudioRaw() {
        val directory = Files.createTempDirectory("aftertone-import-wav-audio-raw-").toFile()
        val content = byteArrayOf(2, 4, 6, 8)
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { AudioMetadata(audioMimeType = "audio/raw") },
                availableBytes = { Long.MAX_VALUE },
            )

            val track = importer.import(ByteArrayInputStream(content), "untitled recording", "application/octet-stream")

            assertTrue("PCM audio/raw must be stored using the supported WAV extension.", track.filePath.endsWith(".wav"))
            assertEquals(content.toList(), File(track.filePath).readBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsupportedDetectedCodecInsteadOfTrustingMisleadingMp3Extension() {
        val directory = Files.createTempDirectory("aftertone-import-unsupported-codec-").toFile()
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { AudioMetadata(audioMimeType = "audio/x-unsupported-codec") },
                availableBytes = { Long.MAX_VALUE },
            )

            assertThrows(IllegalArgumentException::class.java) {
                importer.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "not-really-mp3.mp3", "application/x-id3")
            }

            assertTrue("An unsupported detected codec must not inherit the provider filename extension.", directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonAudioMimeWithoutLeavingFilesBehind() {
        val directory = Files.createTempDirectory("aftertone-import-").toFile()
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { error("must not parse") },
                availableBytes = { Long.MAX_VALUE },
            )

            assertThrows(IllegalArgumentException::class.java) {
                importer.import(ByteArrayInputStream(byteArrayOf(1)), "notes.txt", "text/plain")
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun closesInputWhenUnsupportedDocumentFailsBeforeCopy() {
        val directory = Files.createTempDirectory("aftertone-import-").toFile()
        val input = CloseTrackingInputStream(byteArrayOf(1))
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { error("must not parse") },
                availableBytes = { Long.MAX_VALUE },
            )

            assertThrows(IllegalArgumentException::class.java) {
                importer.import(input, "notes.txt", "text/plain")
            }

            assertTrue(input.wasClosed)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun removesPartialFileWhenMetadataValidationFails() {
        val directory = Files.createTempDirectory("aftertone-import-").toFile()
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { error("invalid audio data") },
                availableBytes = { Long.MAX_VALUE },
            )

            assertThrows(IllegalStateException::class.java) {
                importer.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "broken.mp3", "audio/mpeg")
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun removesPartialFileWhenAllocatableSpaceRunsOut() {
        val directory = Files.createTempDirectory("aftertone-import-").toFile()
        try {
            var checks = 0
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { AudioMetadata() },
                availableBytes = { if (++checks == 1) Long.MAX_VALUE else 0L },
            )

            assertThrows(IOException::class.java) {
                importer.import(ByteArrayInputStream(ByteArray(1024 * 1024)), "large.mp3", "audio/mpeg")
            }

            assertTrue(checks >= 2)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun storageCheckIOExceptionPropagatesAndCleansStagingFile() {
        val directory = Files.createTempDirectory("aftertone-import-space-check-").toFile()
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { error("The media parser must not run after the capacity check fails.") },
                availableBytes = { throw IOException("/data/user/0/private/device/path") },
            )

            assertThrows(IOException::class.java) {
                importer.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "track.mp3", "audio/mpeg")
            }

            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun insufficientSpaceFailsAndCleansStagingFile() {
        val directory = Files.createTempDirectory("aftertone-import-low-space-").toFile()
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { AudioMetadata() },
                availableBytes = { 0L },
            )

            assertThrows(IOException::class.java) {
                importer.import(ByteArrayInputStream(byteArrayOf(1)), "track.mp3", "audio/mpeg")
            }

            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun metadataParserIOExceptionPropagatesAndCleansStagingFile() {
        val directory = Files.createTempDirectory("aftertone-import-media-error-").toFile()
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { throw IOException("/data/user/0/private/media-parser-detail") },
                availableBytes = { Long.MAX_VALUE },
            )

            assertThrows(IOException::class.java) {
                importer.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "track.mp3", "audio/mpeg")
            }

            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
