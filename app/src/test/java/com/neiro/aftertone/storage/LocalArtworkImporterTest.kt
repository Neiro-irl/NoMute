package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.PlaylistArtworkFileName
import com.neiro.aftertone.model.TrackArtworkFileName
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArtworkImporterTest {
    @Test
    fun publishesProcessedImageAtUniqueOwnedPathAndRemovesTemporaryFiles() {
        val root = Files.createTempDirectory("aftertone-custom-artwork-").toFile()
        val audioDirectory = File(root, "audio")
        val trackId = "123e4567-e89b-12d3-a456-426614174000"
        val sourceBytes = byteArrayOf(1, 2, 3, 4, 5)
        val processedBytes = byteArrayOf(9, 8, 7, 6)
        try {
            val importer = LocalArtworkImporter(audioDirectory, ArtworkImageProcessor { source, output ->
                assertArrayEquals(sourceBytes, source.readBytes())
                output.writeBytes(processedBytes)
            })

            val path = importer.import(trackId, ByteArrayInputStream(sourceBytes))
            val stored = File(path)

            assertTrue(PrivateAudioPath.isDirectChild(audioDirectory, stored))
            assertTrue(TrackArtworkFileName.isGeneratedFileName(trackId, stored.name))
            assertArrayEquals(processedBytes, stored.readBytes())
            assertEquals(listOf(stored.name), audioDirectory.listFiles()!!.map(File::getName))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syncsTheAudioDirectoryAfterPublishingTheFinalCover() {
        val root = Files.createTempDirectory("aftertone-custom-artwork-directory-sync-").toFile()
        val audioDirectory = File(root, "audio")
        val trackId = "cover-sync-track"
        val directoryWasSynced = AtomicBoolean(false)
        try {
            val importer = LocalArtworkImporter(
                audioDirectory,
                ArtworkImageProcessor { _, output -> output.writeBytes(byteArrayOf(9, 8, 7)) },
                ArtworkDirectorySynchronizer { directory ->
                    val published = directory.listFiles()!!.singleOrNull { TrackArtworkFileName.isGeneratedCoverFileName(it.name) }
                    assertNotNull("Directory durability must run after the final cover is published.", published)
                    assertTrue(requireNotNull(published).isFile)
                    directoryWasSynced.set(true)
                },
            )

            importer.import(trackId, ByteArrayInputStream(byteArrayOf(1, 2, 3)))

            assertTrue(directoryWasSynced.get())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directorySyncFailureRemovesThePublishedButUnreferencedCover() {
        val root = Files.createTempDirectory("aftertone-custom-artwork-sync-failure-").toFile()
        val audioDirectory = File(root, "audio")
        try {
            val importer = LocalArtworkImporter(
                audioDirectory,
                ArtworkImageProcessor { _, output -> output.writeBytes(byteArrayOf(5, 4, 3)) },
                ArtworkDirectorySynchronizer { throw IOException("private sync detail") },
            )

            assertThrows(IOException::class.java) {
                importer.import("cover-sync-failure", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            }

            assertTrue(audioDirectory.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun processorFailureLeavesPreviousArtworkAndCleansStagingFiles() {
        val root = Files.createTempDirectory("aftertone-custom-artwork-failure-").toFile()
        val audioDirectory = File(root, "audio").apply { mkdirs() }
        val trackId = "123e4567-e89b-12d3-a456-426614174000"
        val oldBytes = byteArrayOf(41, 42, 43)
        try {
            val oldArtwork = File(audioDirectory, "$trackId.img").apply { writeBytes(oldBytes) }
            val importer = LocalArtworkImporter(audioDirectory, ArtworkImageProcessor { _, _ ->
                throw IOException("private decoder detail")
            })

            assertThrows(IOException::class.java) {
                importer.import(trackId, ByteArrayInputStream(byteArrayOf(7, 8, 9)))
            }

            assertArrayEquals(oldBytes, oldArtwork.readBytes())
            assertEquals(listOf(oldArtwork.name), audioDirectory.listFiles()!!.map(File::getName))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsOversizedProcessedArtworkWithoutPublishingIt() {
        val root = Files.createTempDirectory("aftertone-custom-artwork-output-limit-").toFile()
        val audioDirectory = File(root, "audio").apply { mkdirs() }
        val trackId = "123e4567-e89b-12d3-a456-426614174000"
        try {
            val importer = LocalArtworkImporter(audioDirectory, ArtworkImageProcessor { _, output ->
                output.writeBytes(ByteArray(2 * 1024 * 1024 + 1))
            })

            assertThrows(IOException::class.java) {
                importer.import(trackId, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            }

            assertTrue(audioDirectory.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsOversizedSourceBeforeCallingTheImageProcessor() {
        val root = Files.createTempDirectory("aftertone-custom-artwork-input-limit-").toFile()
        val audioDirectory = File(root, "audio").apply { mkdirs() }
        val trackId = "123e4567-e89b-12d3-a456-426614174000"
        val processorCalled = AtomicBoolean(false)
        try {
            val importer = LocalArtworkImporter(audioDirectory, ArtworkImageProcessor { _, _ ->
                processorCalled.set(true)
            })
            val oversizedBytes = ByteArray((LocalArtworkImporter.MAX_INPUT_BYTES + 1).toInt())

            assertThrows(IOException::class.java) {
                importer.import(trackId, ByteArrayInputStream(oversizedBytes))
            }

            assertFalse(processorCalled.get())
            assertTrue(audioDirectory.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publishesProcessedPlaylistArtworkInAPlaylistOwnedNamespace() {
        val root = Files.createTempDirectory("aftertone-playlist-artwork-").toFile()
        val audioDirectory = File(root, "audio")
        val playlistId = "collection-7"
        val sourceBytes = byteArrayOf(2, 4, 6)
        val processedBytes = byteArrayOf(8, 10, 12)
        try {
            val importer = LocalArtworkImporter(
                audioDirectory,
                ArtworkImageProcessor { source, output ->
                    assertArrayEquals(sourceBytes, source.readBytes())
                    output.writeBytes(processedBytes)
                },
                ArtworkDirectorySynchronizer { },
            )

            val path = importer.importPlaylistArtwork(playlistId, ByteArrayInputStream(sourceBytes))
            val stored = File(path)

            assertTrue(PrivateAudioPath.isDirectChild(audioDirectory, stored))
            assertTrue(PlaylistArtworkFileName.isGeneratedFileName(playlistId, stored.name))
            assertFalse(TrackArtworkFileName.isGeneratedFileName(playlistId, stored.name))
            assertArrayEquals(processedBytes, stored.readBytes())
            assertEquals(listOf(stored.name), audioDirectory.listFiles()!!.map(File::getName))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun playlistArtworkProcessorFailureCleansStagingFilesWithoutChangingOtherArtwork() {
        val root = Files.createTempDirectory("aftertone-playlist-artwork-failure-").toFile()
        val audioDirectory = File(root, "audio").apply { mkdirs() }
        val trackId = "123e4567-e89b-12d3-a456-426614174000"
        val otherArtwork = File(audioDirectory, TrackArtworkFileName.forCustomCover(trackId))
            .apply { writeBytes(byteArrayOf(41, 42, 43)) }
        try {
            val importer = LocalArtworkImporter(
                audioDirectory,
                ArtworkImageProcessor { _, _ -> throw IOException("private decoder detail") },
                ArtworkDirectorySynchronizer { },
            )

            assertThrows(IOException::class.java) {
                importer.importPlaylistArtwork(trackId, ByteArrayInputStream(byteArrayOf(7, 8, 9)))
            }

            assertTrue(otherArtwork.isFile)
            assertArrayEquals(byteArrayOf(41, 42, 43), otherArtwork.readBytes())
            assertEquals(listOf(otherArtwork.name), audioDirectory.listFiles()!!.map(File::getName))
        } finally {
            root.deleteRecursively()
        }
    }
}
