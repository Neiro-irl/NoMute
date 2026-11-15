package com.neiro.aftertone.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.TestUri
import com.neiro.aftertone.ExternalAudioHandoffDispatchOutcome
import com.neiro.aftertone.ExternalAudioImportProcessState
import com.neiro.aftertone.ExternalAudioOpenNotificationQueue
import com.neiro.aftertone.EXTERNAL_AUDIO_SUCCESS_NOTIFICATION_MESSAGE
import com.neiro.aftertone.completeExternalAudioOpen
import com.neiro.aftertone.enqueueExternalAudioSuccessNotification
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.model.TrackArtworkFileName
import com.neiro.aftertone.model.TrackFileName
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class PreferenceCommitGate(
    val entered: CountDownLatch = CountDownLatch(1),
    val release: CountDownLatch = CountDownLatch(1),
)

class LocalLibraryRepositoryImportTest {
    @Test
    fun importPersistsThenReloadsPlayablePrivateAudioAndMetadata() {
        val fixture = mp3Fixture()
        val expectedMetadata = AudioMetadata(
            title = "Night Drive",
            artist = "North Window",
            album = "Blue Hour",
            durationMs = 12_345L,
        )
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader { temporaryAudio ->
                    assertArrayEquals("Importer must parse the bytes supplied by the URI document.", fixture, temporaryAudio.readBytes())
                    expectedMetadata
                },
                availableBytes = { Long.MAX_VALUE },
            )
        }

        try {
            val imported = repositoryFixture.repository.import(TestUri(TEST_URI))
            val reloaded = repositoryFixture.repository.load()
            val stored = reloaded.tracks.singleOrNull { it.id == imported.id }

            assertNotNull("The imported ID must survive the persisted snapshot reload.", stored)
            requireNotNull(stored)
            assertEquals(expectedMetadata.title, stored.title)
            assertEquals(expectedMetadata.artist, stored.artist)
            assertEquals(expectedMetadata.album, stored.album)
            assertEquals(expectedMetadata.durationMs, stored.durationMs)

            val privateAudio = File(stored.filePath)
            assertTrue("The copied audio must be an existing regular file.", privateAudio.isFile)
            assertTrue(
                "The copied audio must remain a direct child of the app-private audio directory.",
                PrivateAudioPath.isDirectChild(repositoryFixture.audioDirectory, privateAudio),
            )
            assertArrayEquals("The bytes must remain playable MP3 bytes after persist and reload.", fixture, privateAudio.readBytes())
            val playbackUri: URI = privateAudio.toURI()
            assertEquals("file", playbackUri.scheme)
            assertEquals(privateAudio.canonicalFile, File(playbackUri).canonicalFile)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun firstAudioDirectoryCreationSyncsFilesParentBeforeCallingImporter() {
        val syncedDirectories = mutableListOf<File>()
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) = Unit

            override fun syncDirectory(directory: File) {
                syncedDirectories += directory
            }
        }
        val repositoryFixture = repositoryFixture(durability = durability) { audioDirectory ->
            LocalAudioImporter(
                audioDirectory,
                AudioMetadataReader {
                    assertEquals(listOf(audioDirectory.parentFile), syncedDirectories)
                    AudioMetadata()
                },
                availableBytes = { Long.MAX_VALUE },
                durability = durability,
            )
        }

        try {
            repositoryFixture.repository.import(TestUri(TEST_URI))

            assertEquals(listOf(repositoryFixture.audioDirectory.parentFile, repositoryFixture.audioDirectory), syncedDirectories)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun removeTrackSyncsNestedRestoreDirectoriesBeforeRemovingOwnershipAndGeneration() {
        val restoreId = "00000000-0000-0000-0000-000000000041"
        val trackId = "00000000-0000-0000-0000-000000000042"
        val coverId = "00000000-0000-0000-0000-000000000043"
        val syncedDirectories = mutableListOf<File>()
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) = Unit

            override fun syncDirectory(directory: File) {
                syncedDirectories += directory
            }
        }
        var generationDirectory: File? = null
        val repositoryFixture = repositoryFixture(durability = durability) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val generation = File(audioDirectory, "restore-$restoreId").apply { assertTrue(mkdir()) }
                generationDirectory = generation
                File(generation, PrivateAudioPath.restoreOwnerMarkerName()).writeText(restoreId)
                val audio = File(generation, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                val artwork = File(generation, TrackArtworkFileName.forCustomCover(trackId, coverId))
                    .apply { writeBytes(byteArrayOf(1, 2, 3)) }
                Track(
                    id = trackId,
                    title = "Restored generation",
                    artist = "Test artist",
                    durationMs = 2_000L,
                    filePath = audio.canonicalPath,
                    artworkPath = artwork.canonicalPath,
                )
            }
        }

        try {
            repositoryFixture.repository.import(TestUri(TEST_URI))
            syncedDirectories.clear()

            repositoryFixture.repository.removeTrack(trackId)

            val generation = requireNotNull(generationDirectory)
            assertEquals(
                listOf(generation, generation, generation, repositoryFixture.audioDirectory),
                syncedDirectories,
            )
            assertFalse(generation.exists())
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun nestedRestoreDirectorySyncFailurePropagatesAndKeepsOwnershipMarker() {
        val restoreId = "00000000-0000-0000-0000-000000000051"
        val trackId = "00000000-0000-0000-0000-000000000052"
        val generationDirectory = AtomicReference<File?>(null)
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) = Unit

            override fun syncDirectory(directory: File) {
                if (directory == generationDirectory.get()) throw IOException("Injected generation-directory sync failure.")
            }
        }
        val repositoryFixture = repositoryFixture(durability = durability) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val generation = File(audioDirectory, "restore-$restoreId").apply { assertTrue(mkdir()) }
                generationDirectory.set(generation)
                File(generation, PrivateAudioPath.restoreOwnerMarkerName()).writeText(restoreId)
                val audio = File(generation, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                Track(
                    id = trackId,
                    title = "Restore cleanup failure",
                    artist = "Test artist",
                    durationMs = 2_000L,
                    filePath = audio.canonicalPath,
                )
            }
        }

        try {
            repositoryFixture.repository.import(TestUri(TEST_URI))
            val generation = requireNotNull(generationDirectory.get())

            val failure = assertThrows(IOException::class.java) {
                repositoryFixture.repository.removeTrack(trackId)
            }

            assertEquals("Injected generation-directory sync failure.", failure.message)
            assertTrue("A failed directory sync must not remove the ownership marker.", File(generation, PrivateAudioPath.restoreOwnerMarkerName()).isFile)
            assertTrue("The generation remains available for safe recovery.", generation.isDirectory)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun markerDirectorySyncFailureRestoresTheOwnershipMarkerBeforePropagating() {
        val restoreId = "00000000-0000-0000-0000-000000000061"
        val trackId = "00000000-0000-0000-0000-000000000062"
        val generationDirectory = AtomicReference<File?>(null)
        val syncedFiles = mutableListOf<File>()
        var generationDirectorySyncCalls = 0
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) {
                syncedFiles += file
            }

            override fun syncDirectory(directory: File) {
                if (directory == generationDirectory.get()) {
                    generationDirectorySyncCalls++
                    if (generationDirectorySyncCalls == 2) {
                        throw IOException("Injected marker-directory sync failure.")
                    }
                    if (generationDirectorySyncCalls == 3) {
                        throw IOException("Injected recovery directory sync failure.")
                    }
                }
            }
        }
        val repositoryFixture = repositoryFixture(durability = durability) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val generation = File(audioDirectory, "restore-$restoreId").apply { assertTrue(mkdir()) }
                generationDirectory.set(generation)
                File(generation, PrivateAudioPath.restoreOwnerMarkerName()).writeText(restoreId)
                val audio = File(generation, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                Track(
                    id = trackId,
                    title = "Marker sync failure",
                    artist = "Test artist",
                    durationMs = 2_000L,
                    filePath = audio.canonicalPath,
                )
            }
        }

        try {
            repositoryFixture.repository.import(TestUri(TEST_URI))
            generationDirectorySyncCalls = 0
            val generation = requireNotNull(generationDirectory.get())

            val failure = assertThrows(IOException::class.java) {
                repositoryFixture.repository.removeTrack(trackId)
            }

            val marker = File(generation, PrivateAudioPath.restoreOwnerMarkerName())
            assertEquals("Injected marker-directory sync failure.", failure.message)
            assertEquals(3, generationDirectorySyncCalls)
            assertEquals("Injected recovery directory sync failure.", failure.suppressed.single().message)
            assertTrue("The marker must be restored after its directory fsync fails.", marker.isFile)
            assertEquals(restoreId, marker.readText(Charsets.UTF_8))
            assertEquals(listOf(marker), syncedFiles)
            assertTrue(generation.isDirectory)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun failedMarkerDirectorySyncDoesNotFollowDanglingSymlinkDuringRecovery() {
        val restoreId = "00000000-0000-0000-0000-000000000071"
        val trackId = "00000000-0000-0000-0000-000000000072"
        val generationDirectory = AtomicReference<File?>(null)
        val externalTarget = AtomicReference<File?>(null)
        var generationDirectorySyncCalls = 0
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) = Unit

            override fun syncDirectory(directory: File) {
                if (directory == generationDirectory.get()) {
                    generationDirectorySyncCalls++
                    if (generationDirectorySyncCalls == 2) {
                        val marker = File(directory, PrivateAudioPath.restoreOwnerMarkerName())
                        Files.createSymbolicLink(marker.toPath(), requireNotNull(externalTarget.get()).toPath())
                        throw IOException("Injected marker-directory sync failure.")
                    }
                }
            }
        }
        val repositoryFixture = repositoryFixture(durability = durability) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val generation = File(audioDirectory, "restore-$restoreId").apply { assertTrue(mkdir()) }
                generationDirectory.set(generation)
                File(generation, PrivateAudioPath.restoreOwnerMarkerName()).writeText(restoreId)
                val audio = File(generation, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                Track(
                    id = trackId,
                    title = "Marker symlink recovery",
                    artist = "Test artist",
                    durationMs = 2_000L,
                    filePath = audio.canonicalPath,
                )
            }
        }

        try {
            val target = File(repositoryFixture.root, "outside/marker-target.txt")
            assertTrue(requireNotNull(target.parentFile).mkdir())
            externalTarget.set(target)
            repositoryFixture.repository.import(TestUri(TEST_URI))
            generationDirectorySyncCalls = 0
            val generation = requireNotNull(generationDirectory.get())

            val failure = assertThrows(IOException::class.java) {
                repositoryFixture.repository.removeTrack(trackId)
            }

            val marker = File(generation, PrivateAudioPath.restoreOwnerMarkerName())
            assertEquals("Injected marker-directory sync failure.", failure.message)
            assertTrue("The recovery symlink must not be followed or removed.", Files.isSymbolicLink(marker.toPath()))
            assertFalse("Recovery must not create a dangling symlink target outside the generation.", target.exists())
            assertTrue("A failed cleanup must retain the generation for safe recovery.", generation.isDirectory)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun markerRecoveryAfterDirectoryReplacementStaysBoundToOpenedGeneration() {
        val restoreId = "00000000-0000-0000-0000-000000000091"
        val trackId = "00000000-0000-0000-0000-000000000092"
        val generationDirectory = AtomicReference<File?>(null)
        val movedGenerationDirectory = AtomicReference<File?>(null)
        val externalGenerationDirectory = AtomicReference<File?>(null)
        var generationDirectorySyncCalls = 0
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) = Unit

            override fun syncDirectory(directory: File) {
                if (directory != generationDirectory.get()) return
                generationDirectorySyncCalls++
                if (generationDirectorySyncCalls == 2) {
                    val generation = requireNotNull(generationDirectory.get())
                    val moved = File(generation.parentFile, "moved-restore-generation")
                    Files.move(generation.toPath(), moved.toPath())
                    Files.createSymbolicLink(generation.toPath(), requireNotNull(externalGenerationDirectory.get()).toPath())
                    movedGenerationDirectory.set(moved)
                    throw IOException("Injected marker-directory sync failure.")
                }
            }
        }
        val repositoryFixture = repositoryFixture(durability = durability) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val generation = File(audioDirectory, "restore-$restoreId").apply { assertTrue(mkdir()) }
                generationDirectory.set(generation)
                File(generation, PrivateAudioPath.restoreOwnerMarkerName()).writeText(restoreId)
                val audio = File(generation, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                Track(trackId, "Directory replacement recovery", "Test artist", durationMs = 2_000L, filePath = audio.canonicalPath)
            }
        }

        try {
            val externalDirectory = File(repositoryFixture.root, "outside-generation").apply { assertTrue(mkdir()) }
            externalGenerationDirectory.set(externalDirectory)
            val externalMarker = File(externalDirectory, PrivateAudioPath.restoreOwnerMarkerName()).apply { writeText("outside-content") }
            val imported = repositoryFixture.repository.import(TestUri(TEST_URI))
            generationDirectorySyncCalls = 0

            val failure = assertThrows(IOException::class.java) {
                repositoryFixture.repository.removeTrack(imported.id)
            }

            val movedGeneration = requireNotNull(movedGenerationDirectory.get())
            val restoredMarker = File(movedGeneration, PrivateAudioPath.restoreOwnerMarkerName())
            val replacedGenerationPath = requireNotNull(generationDirectory.get())
            assertEquals("Injected marker-directory sync failure.", failure.message)
            assertTrue("Recovery must restore the marker through the already-open generation handle.", restoredMarker.isFile)
            assertEquals(restoreId, restoredMarker.readText(Charsets.UTF_8))
            assertTrue("The replaced generation path remains a symlink, not a cleanup target.", Files.isSymbolicLink(replacedGenerationPath.toPath()))
            assertEquals("outside-content", externalMarker.readText(Charsets.UTF_8))
            assertEquals(2, generationDirectorySyncCalls)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun cleanupDoesNotDeleteAnEmptyReplacementGenerationDirectoryDuringSync() {
        val restoreId = "00000000-0000-0000-0000-000000000093"
        val trackId = "00000000-0000-0000-0000-000000000094"
        val generationDirectory = AtomicReference<File?>(null)
        val movedGenerationDirectory = AtomicReference<File?>(null)
        var generationDirectorySyncCalls = 0
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) = Unit

            override fun syncDirectory(directory: File) {
                if (directory != generationDirectory.get()) return
                generationDirectorySyncCalls++
                if (generationDirectorySyncCalls == 2) {
                    val generation = requireNotNull(generationDirectory.get())
                    val moved = File(generation.parentFile, "moved-replaced-generation")
                    Files.move(generation.toPath(), moved.toPath())
                    assertTrue("The attacker-controlled replacement should be created.", generation.mkdir())
                    movedGenerationDirectory.set(moved)
                }
            }
        }
        val repositoryFixture = repositoryFixture(durability = durability) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val generation = File(audioDirectory, "restore-$restoreId").apply { assertTrue(mkdir()) }
                generationDirectory.set(generation)
                File(generation, PrivateAudioPath.restoreOwnerMarkerName()).writeText(restoreId)
                val audio = File(generation, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                Track(trackId, "Empty directory replacement", "Test artist", durationMs = 2_000L, filePath = audio.canonicalPath)
            }
        }

        try {
            val imported = repositoryFixture.repository.import(TestUri(TEST_URI))
            generationDirectorySyncCalls = 0

            assertThrows(IOException::class.java) {
                repositoryFixture.repository.removeTrack(imported.id)
            }

            val movedGeneration = requireNotNull(movedGenerationDirectory.get())
            val replacementDirectory = requireNotNull(generationDirectory.get())
            val restoredMarker = File(movedGeneration, PrivateAudioPath.restoreOwnerMarkerName())
            assertTrue("The unowned empty replacement directory must not be deleted.", replacementDirectory.isDirectory)
            assertTrue("The replacement must remain empty and untouched.", replacementDirectory.listFiles()?.isEmpty() == true)
            assertTrue("The validated generation must regain its marker through the pinned handle.", restoredMarker.isFile)
            assertEquals(restoreId, restoredMarker.readText(Charsets.UTF_8))
            assertEquals(2, generationDirectorySyncCalls)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun cleanupDoesNotDeleteReplacementGenerationCreatedAfterSyncIdentityCheck() {
        val restoreId = "00000000-0000-0000-0000-000000000095"
        val trackId = "00000000-0000-0000-0000-000000000096"
        val generationDirectory = AtomicReference<File?>(null)
        val movedGenerationDirectory = AtomicReference<File?>(null)
        var generationHandleSyncCalls = 0
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) = Unit

            override fun syncDirectory(directory: File) = Unit

            override fun syncDirectory(
                directory: File,
                openedDirectory: java.nio.file.SecureDirectoryStream<java.nio.file.Path>,
            ) {
                super.syncDirectory(directory, openedDirectory)
                if (directory != generationDirectory.get()) return
                generationHandleSyncCalls++
                if (generationHandleSyncCalls == 1) {
                    val generation = requireNotNull(generationDirectory.get())
                    val moved = File(generation.parentFile, "moved-post-sync-generation")
                    Files.move(generation.toPath(), moved.toPath())
                    assertTrue("The attacker-controlled replacement should be created.", generation.mkdir())
                    movedGenerationDirectory.set(moved)
                }
            }
        }
        val repositoryFixture = repositoryFixture(durability = durability) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val generation = File(audioDirectory, "restore-$restoreId").apply { assertTrue(mkdir()) }
                generationDirectory.set(generation)
                File(generation, PrivateAudioPath.restoreOwnerMarkerName()).writeText(restoreId)
                val audio = File(generation, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                Track(trackId, "Post-sync directory replacement", "Test artist", durationMs = 2_000L, filePath = audio.canonicalPath)
            }
        }

        try {
            val imported = repositoryFixture.repository.import(TestUri(TEST_URI))
            generationHandleSyncCalls = 0

            assertThrows(IOException::class.java) {
                repositoryFixture.repository.removeTrack(imported.id)
            }

            val movedGeneration = requireNotNull(movedGenerationDirectory.get())
            val replacementDirectory = requireNotNull(generationDirectory.get())
            val restoredMarker = File(movedGeneration, PrivateAudioPath.restoreOwnerMarkerName())
            assertTrue("The replacement empty generation directory must remain.", replacementDirectory.isDirectory)
            assertTrue("The replacement must remain empty and untouched.", replacementDirectory.listFiles()?.isEmpty() == true)
            assertTrue("The validated generation must regain its marker through the pinned handle.", restoredMarker.isFile)
            assertEquals(restoreId, restoredMarker.readText(Charsets.UTF_8))
            assertEquals(1, generationHandleSyncCalls)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun audioDirectorySyncFailurePropagatesAfterRestoreGenerationRemoval() {
        val restoreId = "00000000-0000-0000-0000-000000000081"
        val trackId = "00000000-0000-0000-0000-000000000082"
        val generationDirectory = AtomicReference<File?>(null)
        val audioDirectory = AtomicReference<File?>(null)
        var failAudioDirectorySync = false
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) = Unit

            override fun syncDirectory(directory: File) {
                if (failAudioDirectorySync && directory == audioDirectory.get()) {
                    throw IOException("Injected final audio-directory sync failure.")
                }
            }
        }
        val repositoryFixture = repositoryFixture(durability = durability) { audioDir ->
            audioDirectory.set(audioDir)
            AudioTrackImporter { input, _, _ ->
                input.close()
                val generation = File(audioDir, "restore-$restoreId").apply { assertTrue(mkdir()) }
                generationDirectory.set(generation)
                File(generation, PrivateAudioPath.restoreOwnerMarkerName()).writeText(restoreId)
                val audio = File(generation, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                Track(
                    id = trackId,
                    title = "Final audio-directory sync failure",
                    artist = "Test artist",
                    durationMs = 2_000L,
                    filePath = audio.canonicalPath,
                )
            }
        }

        try {
            repositoryFixture.repository.import(TestUri(TEST_URI))
            failAudioDirectorySync = true

            val failure = assertThrows(IOException::class.java) {
                repositoryFixture.repository.removeTrack(trackId)
            }

            assertEquals("Injected final audio-directory sync failure.", failure.message)
            assertFalse("A failed parent fsync is still reported after the generation is removed.", requireNotNull(generationDirectory.get()).exists())
            assertTrue(repositoryFixture.repository.load().tracks.isEmpty())
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun preservesOwnedUniqueCoverPathAcrossLibraryPersistenceAndReload() {
        val trackId = UUID.randomUUID().toString()
        val coverName = "$trackId-cover-123e4567-e89b-12d3-a456-426614174000.img"
        val artworkBytes = byteArrayOf(21, 22, 23, 24)
        val repositoryFixture = repositoryFixture { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val audioFile = File(audioDirectory, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                val artworkFile = File(audioDirectory, coverName).apply { writeBytes(artworkBytes) }
                Track(
                    id = trackId,
                    title = "Custom artwork track",
                    artist = "Test artist",
                    durationMs = 2_000L,
                    filePath = audioFile.canonicalPath,
                    artworkPath = artworkFile.canonicalPath,
                )
            }
        }

        try {
            val imported = repositoryFixture.repository.import(TestUri(TEST_URI))
            val reloaded = repositoryFixture.newRepository().load().tracks.single()

            assertEquals(File(repositoryFixture.audioDirectory, coverName).canonicalPath, imported.artworkPath)
            assertEquals(imported.artworkPath, reloaded.artworkPath)
            assertTrue(File(requireNotNull(reloaded.artworkPath)).readBytes().contentEquals(artworkBytes))
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun setTrackArtworkPersistsNewCoverBeforeDeletingThePreviousOne() {
        val trackId = UUID.randomUUID().toString()
        val oldArtworkBytes = byteArrayOf(31, 32, 33)
        val newArtworkBytes = byteArrayOf(41, 42, 43, 44)
        val coverId = "123e4567-e89b-12d3-a456-426614174000"
        val repositoryFixture = repositoryFixture(
            artworkImporterFactory = { audioDirectory ->
                TrackArtworkImporter { requestedTrackId, input ->
                    assertEquals(trackId, requestedTrackId)
                    File(audioDirectory, TrackArtworkFileName.forCustomCover(trackId, coverId))
                        .apply { writeBytes(input.readBytes()) }
                        .canonicalPath
                }
            },
        ) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val audio = File(audioDirectory, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                val oldArtwork = File(audioDirectory, "$trackId.img").apply { writeBytes(oldArtworkBytes) }
                Track(trackId, "Artwork update", "Test artist", durationMs = 1_000L, filePath = audio.canonicalPath, artworkPath = oldArtwork.canonicalPath)
            }
        }

        try {
            val original = repositoryFixture.repository.import(TestUri(TEST_URI))
            val oldArtwork = File(requireNotNull(original.artworkPath))
            val updated = repositoryFixture.repository.setTrackArtwork(trackId, ByteArrayInputStream(newArtworkBytes))
            val stored = updated.tracks.single()
            val newArtwork = File(requireNotNull(stored.artworkPath))

            assertEquals(File(repositoryFixture.audioDirectory, TrackArtworkFileName.forCustomCover(trackId, coverId)).canonicalPath, newArtwork.canonicalPath)
            assertTrue("The new cover must be committed and available offline.", newArtwork.isFile)
            assertTrue(newArtwork.readBytes().contentEquals(newArtworkBytes))
            assertFalse("The previous owned cover is removed only after the replacement commits.", oldArtwork.exists())
            assertEquals(stored.artworkPath, repositoryFixture.newRepository().load().tracks.single().artworkPath)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun setTrackArtworkSyncsTheNestedParentWhenReplacingARestoredCover() {
        val restoreId = "00000000-0000-0000-0000-000000000071"
        val trackId = "00000000-0000-0000-0000-000000000072"
        val oldCoverId = "00000000-0000-0000-0000-000000000073"
        val newCoverId = "00000000-0000-0000-0000-000000000074"
        val syncedDirectories = mutableListOf<File>()
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) = Unit

            override fun syncDirectory(directory: File) {
                syncedDirectories += directory
            }
        }
        var generationDirectory: File? = null
        val repositoryFixture = repositoryFixture(
            artworkImporterFactory = { audioDirectory ->
                TrackArtworkImporter { requestedTrackId, input ->
                    assertEquals(trackId, requestedTrackId)
                    val bytes = input.use { it.readBytes() }
                    File(audioDirectory, TrackArtworkFileName.forCustomCover(trackId, newCoverId))
                        .apply { writeBytes(bytes) }
                        .canonicalPath
                }
            },
            durability = durability,
        ) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val generation = File(audioDirectory, "restore-$restoreId").apply { assertTrue(mkdir()) }
                generationDirectory = generation
                File(generation, PrivateAudioPath.restoreOwnerMarkerName()).writeText(restoreId)
                val audio = File(generation, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                val oldArtwork = File(generation, TrackArtworkFileName.forCustomCover(trackId, oldCoverId))
                    .apply { writeBytes(byteArrayOf(1, 2, 3)) }
                Track(
                    id = trackId,
                    title = "Restored artwork replacement",
                    artist = "Test artist",
                    durationMs = 2_000L,
                    filePath = audio.canonicalPath,
                    artworkPath = oldArtwork.canonicalPath,
                )
            }
        }

        try {
            val imported = repositoryFixture.repository.import(TestUri(TEST_URI))
            val oldArtwork = File(requireNotNull(imported.artworkPath))
            syncedDirectories.clear()

            val updated = repositoryFixture.repository.setTrackArtwork(trackId, ByteArrayInputStream(byteArrayOf(4, 5, 6)))

            val generation = requireNotNull(generationDirectory)
            assertFalse(oldArtwork.exists())
            assertTrue(File(requireNotNull(updated.tracks.single().artworkPath)).isFile)
            assertEquals(listOf(generation), syncedDirectories)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun failedCoverCommitKeepsThePreviousCoverAndRemovesOnlyTheUnreferencedCandidate() {
        val trackId = UUID.randomUUID().toString()
        val oldArtworkBytes = byteArrayOf(51, 52, 53)
        val newArtworkBytes = byteArrayOf(61, 62, 63)
        val coverId = "223e4567-e89b-12d3-a456-426614174000"
        val repositoryFixture = repositoryFixture(
            artworkImporterFactory = { audioDirectory ->
                TrackArtworkImporter { _, input ->
                    File(audioDirectory, TrackArtworkFileName.forCustomCover(trackId, coverId))
                        .apply { writeBytes(input.readBytes()) }
                        .canonicalPath
                }
            },
        ) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                val audio = File(audioDirectory, "$trackId.mp3").apply { writeBytes(mp3Fixture()) }
                val oldArtwork = File(audioDirectory, "$trackId.img").apply { writeBytes(oldArtworkBytes) }
                Track(trackId, "Artwork commit failure", "Test artist", durationMs = 1_000L, filePath = audio.canonicalPath, artworkPath = oldArtwork.canonicalPath)
            }
        }

        try {
            val original = repositoryFixture.repository.import(TestUri(TEST_URI))
            val oldArtwork = File(requireNotNull(original.artworkPath))
            val candidate = File(repositoryFixture.audioDirectory, TrackArtworkFileName.forCustomCover(trackId, coverId))
            repositoryFixture.preferences.failNextCommitBeforeApplyingWrite()

            assertThrows(IOException::class.java) {
                repositoryFixture.repository.setTrackArtwork(trackId, ByteArrayInputStream(newArtworkBytes))
            }

            assertTrue("The old cover remains referenced after a failed commit.", oldArtwork.isFile)
            assertTrue(oldArtwork.readBytes().contentEquals(oldArtworkBytes))
            assertFalse("A definitely unreferenced new cover is discarded.", candidate.exists())
            assertEquals(original.artworkPath, repositoryFixture.repository.load().tracks.single().artworkPath)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun importFailsWhenDocumentProviderCannotOpenSource() {
        val repositoryFixture = repositoryFixture(
            documentSource = AudioDocumentSource {
                throw IOException("/data/user/0/private/provider-detail")
            },
        ) { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                error("The importer must not run when opening the document fails.")
            }
        }

        try {
            assertThrows(IOException::class.java) {
                repositoryFixture.repository.import(TestUri(TEST_URI))
            }
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun sameExternalRequestIdReturnsThePersistedTrackAfterRepositoryRecreation() {
        var metadataReadCount = 0
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader {
                    metadataReadCount++
                    AudioMetadata(title = "Persisted external import")
                },
                availableBytes = { Long.MAX_VALUE },
            )
        }

        try {
            val requestId = "f1557c4e-6670-4bc1-a595-914433c9a8d3"
            val imported = repositoryFixture.repository.importExternal(TestUri(TEST_URI), requestId)
            val recreatedRepository = repositoryFixture.newRepository()
            val recovered = recreatedRepository.importExternal(TestUri(TEST_URI), requestId)

            assertEquals(imported.id, recovered.id)
            assertEquals(requestId, recovered.externalImportRequestId)
            assertEquals(1, metadataReadCount)
            assertEquals(1, recreatedRepository.load().tracks.size)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun completedHandoffMarkerSurvivesRepositoryRecreationAndExternalRetry() {
        var metadataReadCount = 0
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader {
                    metadataReadCount++
                    AudioMetadata(title = "Completed external handoff")
                },
                availableBytes = { Long.MAX_VALUE },
            )
        }

        try {
            val requestId = "f1557c4e-6670-4bc1-a595-914433c9a8d3"
            val imported = repositoryFixture.repository.importExternal(TestUri(TEST_URI), requestId)
            repositoryFixture.repository.markExternalImportHandoffClaimed(requestId)
            repositoryFixture.repository.markExternalImportHandoffDispatchStarted(requestId)
            val completed = repositoryFixture.repository.markExternalImportHandoffCompleted(requestId)
            val recreatedRepository = repositoryFixture.newRepository()
            val recovered = recreatedRepository.importExternal(TestUri(TEST_URI), requestId)

            assertEquals(imported.id, completed.id)
            assertTrue(completed.externalImportHandoffClaimed)
            assertTrue(completed.externalImportHandoffCompleted)
            assertEquals(imported.id, recovered.id)
            assertTrue(recovered.externalImportHandoffClaimed)
            assertTrue(recovered.externalImportHandoffCompleted)
            assertEquals(1, metadataReadCount)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun claimedIncompleteHandoffSurvivesRepositoryRecreationAndExternalRetry() {
        var metadataReadCount = 0
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader {
                    metadataReadCount++
                    AudioMetadata(title = "Claimed external handoff")
                },
                availableBytes = { Long.MAX_VALUE },
            )
        }

        try {
            val requestId = "0f37c3a3-0d9a-49c8-9f3d-10de8a548f11"
            val imported = repositoryFixture.repository.importExternal(TestUri(TEST_URI), requestId)
            val claimed = repositoryFixture.repository.markExternalImportHandoffClaimed(requestId)
            val recreatedRepository = repositoryFixture.newRepository()
            val recovered = recreatedRepository.importExternal(TestUri(TEST_URI), requestId)

            assertEquals(imported.id, claimed.id)
            assertTrue(claimed.externalImportHandoffClaimed)
            assertFalse(claimed.externalImportHandoffCompleted)
            assertEquals(imported.id, recovered.id)
            assertTrue(recovered.externalImportHandoffClaimed)
            assertFalse(recovered.externalImportHandoffCompleted)
            assertEquals(1, metadataReadCount)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun importFailsWhenAReportedSuccessfulCommitDoesNotSurviveReadback() {
        val repositoryFixture = repositoryFixture(persistWrites = false) { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader { AudioMetadata(title = "Not retained") },
                availableBytes = { Long.MAX_VALUE },
            )
        }

        try {
            assertThrows(IOException::class.java) {
                repositoryFixture.repository.import(TestUri(TEST_URI))
            }
            assertTrue(repositoryFixture.repository.load().tracks.isEmpty())
            assertTrue(repositoryFixture.audioDirectory.listFiles().isNullOrEmpty())
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun failedLibraryCommitSyncsRollbackAndSuppressesCleanupSyncFailure() {
        val audioDirectorySyncCalls = AtomicReference(0)
        val durability = object : AudioImportDurability {
            override fun syncFile(file: File) = Unit

            override fun syncDirectory(directory: File) {
                if (directory.name != "audio") return
                val call = audioDirectorySyncCalls.updateAndGet { it + 1 }
                if (call == 2) throw IOException("rollback directory sync failed")
            }
        }
        val repositoryFixture = repositoryFixture(persistWrites = false, durability = durability) { audioDirectory ->
            val importer = LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader { AudioMetadata(title = "Rollback sync") },
                availableBytes = { Long.MAX_VALUE },
                durability = durability,
            )
            AudioTrackImporter { input, displayName, mimeType ->
                importer.import(input, displayName, mimeType).also { track ->
                    val audioFile = File(track.filePath)
                    assertTrue(TrackFileName.isGeneratedFileName(track.id, audioFile.name))
                    assertTrue(PrivateAudioPath.isDirectChild(audioDirectory, audioFile))
                    assertTrue(audioFile.isFile)
                }
            }
        }

        try {
            val failure = assertThrows(IOException::class.java) {
                repositoryFixture.repository.import(TestUri(TEST_URI))
            }

            assertEquals("The selected audio file could not be added to the local library.", failure.message)
            assertEquals(2, audioDirectorySyncCalls.get())
            assertEquals(1, failure.suppressed.size)
            assertEquals("rollback directory sync failed", failure.suppressed.single().message)
            assertTrue(repositoryFixture.audioDirectory.listFiles().isNullOrEmpty())
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun committedAudioIsRetainedWhenPostCommitStateReadFails() {
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader { AudioMetadata(title = "Committed import") },
                availableBytes = { Long.MAX_VALUE },
            )
        }

        try {
            repositoryFixture.preferences.failReadsAfterNextWrite()

            assertThrows(IllegalStateException::class.java) {
                repositoryFixture.repository.import(TestUri(TEST_URI))
            }

            repositoryFixture.preferences.allowReads()
            val recovered = repositoryFixture.repository.load()
            assertEquals(1, recovered.tracks.size)
            assertTrue(File(recovered.tracks.single().filePath).isFile)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun committedAudioIsRetainedWhenCommitReportsFailureAfterApplyingInMemoryState() {
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader { AudioMetadata(title = "In-memory commit") },
                availableBytes = { Long.MAX_VALUE },
            )
        }

        try {
            repositoryFixture.preferences.failNextCommitAfterApplyingWrite()

            assertThrows(IOException::class.java) {
                repositoryFixture.repository.import(TestUri(TEST_URI))
            }

            val current = repositoryFixture.repository.load()
            assertEquals(1, current.tracks.size)
            assertTrue(File(current.tracks.single().filePath).isFile)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun importFailsWhenRestoreRecoveryBlocksLibraryPersistence() {
        lateinit var fixture: RepositoryFixture
        val source = AudioDocumentSource {
            fixture.repository.restoreActionGate.adoptPendingCleanup("restore-pending")
            AudioImportDocument(
                input = ByteArrayInputStream(mp3Fixture()),
                displayName = "tone-01.mp3",
                mimeType = "audio/mpeg",
            )
        }
        fixture = repositoryFixture(documentSource = source) { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader { AudioMetadata(title = "Gate blocked") },
                availableBytes = { Long.MAX_VALUE },
            )
        }

        try {
            assertThrows(IOException::class.java) {
                fixture.repository.import(TestUri(TEST_URI))
            }
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun importFailsInsteadOfReportingSuccessWhenNormalizationDropsNewTrack() {
        val repositoryFixture = repositoryFixture { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                Track(
                    id = "missing-track",
                    title = "Missing file",
                    artist = "Test artist",
                    filePath = File(audioDirectory, "missing-track.mp3").absolutePath,
                )
            }
        }

        try {
            assertThrows(IOException::class.java) {
                repositoryFixture.repository.import(TestUri(TEST_URI))
            }
            assertTrue(
                "A rejected import must not leave a track in the reloaded snapshot.",
                repositoryFixture.repository.load().tracks.isEmpty(),
            )
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun importRejectsInvalidTrackIdWhenNormalizationRejectsIt() =
        assertTrackValidationFailure { audioDirectory ->
            Track(
                id = "invalid id",
                title = "Invalid ID",
                artist = "Test artist",
                filePath = File(audioDirectory, "invalid id.mp3").absolutePath,
            )
        }

    @Test
    fun importRejectsInvalidGeneratedFilenameWhenNormalizationRejectsIt() =
        assertTrackValidationFailure { audioDirectory ->
            val id = UUID.randomUUID().toString()
            val wrongName = File(audioDirectory, "not-$id.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            Track(id, "Wrong filename", "Test artist", filePath = wrongName.absolutePath)
        }

    @Test
    fun importRejectsUnownedAudioPathWhenNormalizationRejectsIt() =
        assertTrackValidationFailure { audioDirectory ->
            val id = UUID.randomUUID().toString()
            val outsideAudioDirectory = File(audioDirectory.parentFile, "$id.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            Track(id, "Unowned path", "Test artist", filePath = outsideAudioDirectory.absolutePath)
        }

    @Test
    fun coldLoadRemovesOnlyUnreferencedGeneratedImportArtifacts() {
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader {
                    AudioMetadata(title = "Referenced track", artwork = byteArrayOf(3, 5, 8, 13))
                },
                availableBytes = { Long.MAX_VALUE },
            )
        }

        try {
            val imported = repositoryFixture.repository.import(TestUri(TEST_URI))
            val referenced = repositoryFixture.repository.load().tracks.single()
            val referencedArtwork = requireNotNull(referenced.artworkPath)
            val orphanId = "6d18e5c8-2260-4382-bd49-6dc67bdcf873"
            val orphanPart = File(repositoryFixture.audioDirectory, "$orphanId.part").apply { writeBytes(byteArrayOf(1, 2)) }
            val orphanAudio = File(repositoryFixture.audioDirectory, "$orphanId.mp3").apply { writeBytes(byteArrayOf(4, 5)) }
            val orphanArtwork = File(repositoryFixture.audioDirectory, "$orphanId.img").apply { writeBytes(byteArrayOf(6, 7)) }
            val orphanCustomArtwork = File(
                repositoryFixture.audioDirectory,
                TrackArtworkFileName.forCustomCover("legacy-track-id"),
            ).apply { writeBytes(byteArrayOf(8, 9)) }
            val outsideTarget = File(repositoryFixture.root, "outside-target.bin").apply { writeBytes(byteArrayOf(9, 10)) }
            val symlinkId = "a6f71d7a-8b27-4b5e-bcbc-2c3a8bcf9e10"
            val symlink = File(repositoryFixture.audioDirectory, "$symlinkId.flac")
            Files.createSymbolicLink(symlink.toPath(), outsideTarget.toPath())
            val malformed = File(repositoryFixture.audioDirectory, "not-a-uuid.part").apply { writeBytes(byteArrayOf(11)) }
            val unsupported = File(repositoryFixture.audioDirectory, "23e4567e-89ab-42cd-8ef0-1234567890ab.txt")
                .apply { writeBytes(byteArrayOf(12)) }
            val extraSuffix = File(repositoryFixture.audioDirectory, "23e4567e-89ab-42cd-8ef0-1234567890ac.mp3.bak")
                .apply { writeBytes(byteArrayOf(14)) }
            val restoreDirectory = File(repositoryFixture.audioDirectory, "restore-23e4567e-89ab-42cd-8ef0-1234567890ad")
                .apply { mkdirs() }
            val nestedAudio = File(restoreDirectory, "23e4567e-89ab-42cd-8ef0-1234567890ad.mp3")
                .apply { writeBytes(byteArrayOf(15, 16)) }

            val recovered = repositoryFixture.newRepository().load()

            assertEquals(imported.id, recovered.tracks.single().id)
            assertFalse("Cold load must reclaim an abandoned staging file.", orphanPart.exists())
            assertFalse("Cold load must reclaim finalized audio that has no Track reference.", orphanAudio.exists())
            assertFalse("Cold load must reclaim unreferenced importer artwork.", orphanArtwork.exists())
            assertFalse("Cold load must reclaim custom-cover orphans for any safe track ID.", orphanCustomArtwork.exists())
            assertTrue("The audio referenced by the library must survive cleanup.", File(referenced.filePath).isFile)
            assertTrue("The artwork referenced by the library must survive cleanup.", File(referencedArtwork).isFile)
            assertTrue("A symlink candidate must remain untouched.", Files.isSymbolicLink(symlink.toPath()))
            assertArrayEquals("The symlink target must not be followed or deleted.", byteArrayOf(9, 10), outsideTarget.readBytes())
            assertTrue("Malformed or unsupported generated-looking names must be preserved.", malformed.isFile && unsupported.isFile && extraSuffix.isFile)
            assertTrue("The sweep must not recurse into restore-generation directories.", nestedAudio.isFile)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun coldLoadFromAnotherRepositoryWaitsForActiveImportBeforeSweeping() {
        val stagingCreated = CountDownLatch(1)
        val finishMetadataRead = CountDownLatch(1)
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader { stagedFile ->
                    stagingCreated.countDown()
                    if (!finishMetadataRead.await(5, TimeUnit.SECONDS)) {
                        throw IOException("The test did not release the staged import.")
                    }
                    if (!stagedFile.isFile) throw IOException("The staged import was removed during metadata inspection.")
                    AudioMetadata(title = "Concurrent import")
                },
                availableBytes = { Long.MAX_VALUE },
            )
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val importFuture = executor.submit<Track> { repositoryFixture.repository.import(TestUri(TEST_URI)) }
            assertTrue("The importer should reach its staged-file phase.", stagingCreated.await(5, TimeUnit.SECONDS))
            val loadStarted = CountDownLatch(1)
            val loadFinished = CountDownLatch(1)
            val loadFuture = executor.submit {
                loadStarted.countDown()
                try {
                    repositoryFixture.newRepository().load()
                } finally {
                    loadFinished.countDown()
                }
            }
            assertTrue(loadStarted.await(5, TimeUnit.SECONDS))
            assertFalse(
                "A second repository's startup sweep must wait while an import owns temporary files.",
                loadFinished.await(150, TimeUnit.MILLISECONDS),
            )

            finishMetadataRead.countDown()
            val imported = importFuture.get(5, TimeUnit.SECONDS)
            loadFuture.get(5, TimeUnit.SECONDS)
            assertTrue(File(imported.filePath).isFile)
            assertTrue(repositoryFixture.newRepository().load().tracks.any { it.id == imported.id })
        } finally {
            finishMetadataRead.countDown()
            executor.shutdownNow()
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun sameRepositoryLoadDuringImportDoesNotDeadlock() {
        val stagingCreated = CountDownLatch(1)
        val finishMetadataRead = CountDownLatch(1)
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(
                audioDirectory = audioDirectory,
                metadataReader = AudioMetadataReader { stagedFile ->
                    stagingCreated.countDown()
                    if (!finishMetadataRead.await(5, TimeUnit.SECONDS)) {
                        throw IOException("The test did not release the staged import.")
                    }
                    if (!stagedFile.isFile) throw IOException("The staged import was removed during metadata inspection.")
                    AudioMetadata(title = "Same-repository concurrent import")
                },
                availableBytes = { Long.MAX_VALUE },
            )
        }
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "aftertone-same-repository-lock-test").apply { isDaemon = true }
        }

        try {
            val importFuture = executor.submit<Track> {
                repositoryFixture.repository.import(TestUri(TEST_URI))
            }
            assertTrue("The importer should reach its staged-file phase.", stagingCreated.await(5, TimeUnit.SECONDS))

            val loadStarted = CountDownLatch(1)
            val loadThread = java.util.concurrent.atomic.AtomicReference<Thread>()
            val loadFuture = executor.submit {
                loadThread.set(Thread.currentThread())
                loadStarted.countDown()
                repositoryFixture.repository.load()
            }
            assertTrue(loadStarted.await(5, TimeUnit.SECONDS))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (loadThread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("The concurrent load should wait for the active import.", Thread.State.BLOCKED, loadThread.get().state)
            assertFalse("The concurrent load must not finish before the import releases its files.", loadFuture.isDone)

            finishMetadataRead.countDown()
            val imported = importFuture.get(5, TimeUnit.SECONDS)
            loadFuture.get(5, TimeUnit.SECONDS)
            assertTrue(File(imported.filePath).isFile)
        } finally {
            finishMetadataRead.countDown()
            executor.shutdownNow()
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun favoriteUpdateAcrossRepositoriesDoesNotOverwriteConcurrentImport() {
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Concurrent favorite") }, { Long.MAX_VALUE })
        }
        val firstTrack = repositoryFixture.repository.import(TestUri(TEST_URI))
        val secondRepository = repositoryFixture.newRepository()

        try {
            assertSecondTransactionWaitsForFirstCommit(
                repositoryFixture,
                firstTransaction = { repositoryFixture.repository.toggleFavorite(firstTrack.id) },
                secondTransaction = { secondRepository.import(TestUri(TEST_URI)) },
            )

            val state = secondRepository.load()
            assertTrue(firstTrack.id in state.favoriteTrackIds)
            assertEquals(2, state.tracks.size)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun playlistUpdateAcrossRepositoriesDoesNotOverwriteConcurrentImport() {
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Concurrent playlist") }, { Long.MAX_VALUE })
        }
        val secondRepository = repositoryFixture.newRepository()

        try {
            assertSecondTransactionWaitsForFirstCommit(
                repositoryFixture,
                firstTransaction = { repositoryFixture.repository.addPlaylist("Keep this playlist") },
                secondTransaction = { secondRepository.import(TestUri(TEST_URI)) },
            )

            val state = secondRepository.load()
            assertEquals(1, state.playlists.size)
            assertEquals("Keep this playlist", state.playlists.single().name)
            assertEquals(1, state.tracks.size)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun handoffClaimAcrossRepositoriesDoesNotOverwriteConcurrentImport() {
        val requestId = "f1557c4e-6670-4bc1-a595-914433c9a8d3"
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Claim race") }, { Long.MAX_VALUE })
        }
        val imported = repositoryFixture.repository.importExternal(TestUri(TEST_URI), requestId)
        val secondRepository = repositoryFixture.newRepository()

        try {
            assertSecondTransactionWaitsForFirstCommit(
                repositoryFixture,
                firstTransaction = { repositoryFixture.repository.markExternalImportHandoffClaimed(requestId) },
                secondTransaction = { secondRepository.import(TestUri(TEST_URI)) },
            )

            val state = secondRepository.load()
            assertEquals(2, state.tracks.size)
            val claimed = state.tracks.single { it.id == imported.id }
            assertTrue(claimed.externalImportHandoffClaimed)
            assertEquals(false, claimed.externalImportHandoffDispatchStarted)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun handoffCompletionAcrossRepositoriesDoesNotOverwriteConcurrentImport() {
        val requestId = "0f37c3a3-0d9a-49c8-9f3d-10de8a548f11"
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Completion race") }, { Long.MAX_VALUE })
        }
        val imported = repositoryFixture.repository.importExternal(TestUri(TEST_URI), requestId)
        repositoryFixture.repository.markExternalImportHandoffClaimed(requestId)
        repositoryFixture.repository.markExternalImportHandoffDispatchStarted(requestId)
        val secondRepository = repositoryFixture.newRepository()

        try {
            assertSecondTransactionWaitsForFirstCommit(
                repositoryFixture,
                firstTransaction = { repositoryFixture.repository.markExternalImportHandoffCompleted(requestId) },
                secondTransaction = { secondRepository.import(TestUri(TEST_URI)) },
            )

            val state = secondRepository.load()
            assertEquals(2, state.tracks.size)
            val completed = state.tracks.single { it.id == imported.id }
            assertTrue(completed.externalImportHandoffClaimed)
            assertTrue(completed.externalImportHandoffDispatchStarted == true)
            assertTrue(completed.externalImportHandoffCompleted)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun confirmedNoStartCanPersistDispatchMarkerRollback() {
        val requestId = "c07bce8d-2e6c-45b7-8df9-b49819211554"
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Dispatch rollback") }, { Long.MAX_VALUE })
        }

        try {
            repositoryFixture.repository.importExternal(TestUri(TEST_URI), requestId)
            val claimed = repositoryFixture.repository.markExternalImportHandoffClaimed(requestId)
            assertEquals(false, claimed.externalImportHandoffDispatchStarted)

            val dispatchStarted = repositoryFixture.repository.markExternalImportHandoffDispatchStarted(requestId)
            assertTrue(dispatchStarted.externalImportHandoffDispatchStarted == true)
            assertTrue(repositoryFixture.repository.externalImportDispatchMayHaveStarted(requestId))

            val rolledBack = repositoryFixture.repository.markExternalImportHandoffNotDispatched(requestId)
            assertEquals(false, rolledBack.externalImportHandoffDispatchStarted)
            assertFalse(repositoryFixture.repository.externalImportDispatchMayHaveStarted(requestId))
            assertFalse(rolledBack.externalImportHandoffCompleted)
            assertEquals(rolledBack, repositoryFixture.repository.load().tracks.single())
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun requestLevelDispatchOutcomeSurvivesTrackRemovalAndReimportWithNewId() {
        val requestId = "9d9b7c2d-e7b5-40f8-9fc3-c64149e26e27"
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Request outcome") }, { Long.MAX_VALUE })
        }

        try {
            val original = repositoryFixture.repository.importExternal(TestUri(TEST_URI), requestId)
            repositoryFixture.repository.markExternalImportHandoffClaimed(requestId)
            repositoryFixture.repository.markExternalImportHandoffDispatchStarted(requestId)
            assertTrue(repositoryFixture.repository.externalImportDispatchMayHaveStarted(requestId))

            repositoryFixture.repository.removeTrack(original.id)
            assertTrue(repositoryFixture.repository.load().tracks.isEmpty())
            val reimported = repositoryFixture.repository.importExternal(TestUri(TEST_URI), requestId)

            assertFalse("A restored-away Track must be reimported with a different identity.", original.id == reimported.id)
            assertTrue(repositoryFixture.repository.externalImportDispatchMayHaveStarted(requestId))
            assertTrue(repositoryFixture.newRepository().externalImportDispatchMayHaveStarted(requestId))
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun restoredStartedTrackSeedsRequestLevelOutcomeWhenLedgerIsMissing() {
        val requestId = "6cd42c41-b1bd-4e6c-9bb4-bdb2ac0ee518"
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Legacy dispatch marker") }, { Long.MAX_VALUE })
        }

        try {
            repositoryFixture.repository.importExternal(TestUri(TEST_URI), requestId)
            repositoryFixture.repository.markExternalImportHandoffClaimed(requestId)
            repositoryFixture.repository.markExternalImportHandoffDispatchStarted(requestId)
            repositoryFixture.preferences.value.edit().remove("external_import_dispatch_outcomes").commit()

            assertTrue(repositoryFixture.repository.externalImportDispatchMayHaveStarted(requestId))
            assertTrue(repositoryFixture.newRepository().externalImportDispatchMayHaveStarted(requestId))
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun restoreMigratesLegacyDispatchMarkerBeforeRemovingTrackAndRetryDoesNotReplay() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Legacy restore marker") }, { Long.MAX_VALUE })
        }

        try {
            val repository = repositoryFixture.repository
            val original = repository.importExternal(TestUri(TEST_URI), requestId)
            repository.markExternalImportHandoffClaimed(requestId)
            repository.markExternalImportHandoffDispatchStarted(requestId)
            repositoryFixture.preferences.value.edit().remove("external_import_dispatch_outcomes").commit()

            val restored = repository.restoreBackup(emptyPreparedBackup(repositoryFixture))
            assertTrue("The backup should replace the only Track carrying the legacy marker.", restored.snapshot.tracks.isEmpty())
            assertTrue("The marker must be migrated before restore removes its Track.", repository.externalImportDispatchMayHaveStarted(requestId))
            assertTrue("The empty restore transaction should finish before the request is reimported.", repository.finishRestore(restored.transactionId))

            val reimported = repository.importExternal(TestUri(TEST_URI), requestId)
            assertFalse("The reimported request must have a new Track identity.", original.id == reimported.id)
            var playerCalls = 0
            var acknowledgementCount = 0
            val retried = completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { reimported },
                processState = ExternalAudioImportProcessState(),
                reconcilePersistedImport = { reimported },
                hasDurableDispatchOutcome = { repository.externalImportDispatchMayHaveStarted(it) },
                dispatchPlayback = { _, _ ->
                    playerCalls++
                    error("A request with a migrated ambiguous marker must never replay playback.")
                },
            )

            assertEquals("The retry represents the newly imported Track identity.", reimported.id, retried.id)
            assertEquals(0, playerCalls)
            assertEquals(1, acknowledgementCount)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun failedLegacyMarkerMigrationKeepsTheCurrentLibraryInsteadOfRestoringOverIt() {
        val requestId = UUID.randomUUID().toString()
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Legacy restore failure") }, { Long.MAX_VALUE })
        }

        try {
            val repository = repositoryFixture.repository
            val original = repository.importExternal(TestUri(TEST_URI), requestId)
            repository.markExternalImportHandoffClaimed(requestId)
            repository.markExternalImportHandoffDispatchStarted(requestId)
            repositoryFixture.preferences.value.edit().remove("external_import_dispatch_outcomes").commit()
            val prepared = emptyPreparedBackup(repositoryFixture)
            repositoryFixture.preferences.failNextCommitAfterApplyingWrite()

            assertThrows(IOException::class.java) { repository.restoreBackup(prepared) }

            assertEquals(
                "A failed migration commit must abort before replacing the current library state.",
                original.id,
                repository.load().tracks.single().id,
            )
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun confirmedNoDispatchProofSurvivesRestoreAndRetryWithANewTrackId() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "No-dispatch restore retry") }, { Long.MAX_VALUE })
        }
        val processState = ExternalAudioImportProcessState()
        var playerCalls = 0
        var rollbackAttempts = 0
        var completionAttempts = 0
        var notificationCount = 0
        var acknowledgementCount = 0

        try {
            val repository = repositoryFixture.repository
            suspend fun attempt(): Track = completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { repository.importExternal(TestUri(TEST_URI), requestId) },
                processState = processState,
                reconcilePersistedImport = { _ ->
                    repository.load().tracks.firstOrNull { it.externalImportRequestId == requestId }
                },
                hasDurableDispatchOutcome = { repository.externalImportDispatchMayHaveStarted(it) },
                markHandoffClaimed = { repository.markExternalImportHandoffClaimed(requestId) },
                markHandoffDispatchStarted = { repository.markExternalImportHandoffDispatchStarted(requestId) },
                markHandoffNotDispatched = {
                    rollbackAttempts++
                    if (rollbackAttempts == 1) throw IOException("The no-dispatch rollback write failed.")
                    repository.markExternalImportHandoffNotDispatched(requestId)
                },
                dispatchPlayback = { _, markDispatchStarted ->
                    markDispatchStarted()
                    playerCalls++
                    if (playerCalls == 1) {
                        ExternalAudioHandoffDispatchOutcome.NotDispatched
                    } else {
                        ExternalAudioHandoffDispatchOutcome.Dispatched
                    }
                },
                markHandoffCompleted = {
                    completionAttempts++
                    repository.markExternalImportHandoffCompleted(requestId)
                },
                onHandoffCompleted = { notificationCount++ },
            )

            val original = repository.importExternal(TestUri(TEST_URI), requestId)
            val denied = runCatching { attempt() }
            assertTrue("An unpersisted rollback must keep the first attempt pending.", denied.isFailure)
            assertTrue(repository.externalImportDispatchMayHaveStarted(requestId))
            assertEquals(1, playerCalls)
            assertEquals(1, rollbackAttempts)
            assertEquals(0, acknowledgementCount)

            val restored = repository.restoreBackup(emptyPreparedBackup(repositoryFixture))
            assertTrue(restored.snapshot.tracks.isEmpty())
            assertTrue(repository.finishRestore(restored.transactionId))
            val reimported = repository.importExternal(TestUri(TEST_URI), requestId)
            assertFalse("Restore and reimport must produce a different Track identity.", original.id == reimported.id)

            val retried = attempt()

            assertEquals(reimported.id, retried.id)
            assertEquals("The explicit no-dispatch result must allow Retry after Track replacement.", 2, playerCalls)
            assertEquals(1, rollbackAttempts)
            assertEquals(1, completionAttempts)
            assertEquals(1, notificationCount)
            assertEquals(1, acknowledgementCount)
            assertTrue(repository.load().tracks.single().externalImportHandoffCompleted)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun appliedButFailedCompletionCommitNotifiesOnceOnConfirmedSameTrackRetry() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Completion persistence retry") }, { Long.MAX_VALUE })
        }
        val processState = ExternalAudioImportProcessState()
        val notificationQueue = ExternalAudioOpenNotificationQueue()
        var playerCalls = 0
        var completionAttempts = 0
        var acknowledgementCount = 0
        var notificationCallbackCount = 0
        var successNotificationMessage: String? = null

        try {
            val repository = repositoryFixture.repository
            val original = repository.importExternal(TestUri(TEST_URI), requestId)
            suspend fun attempt(): Track = completeExternalAudioOpen(
                requestId = requestId,
                onHandled = { acknowledgementCount++ },
                importAndPersist = { repository.importExternal(TestUri(TEST_URI), requestId) },
                processState = processState,
                reconcilePersistedImport = { _ ->
                    repository.load().tracks.firstOrNull { it.externalImportRequestId == requestId }
                },
                hasDurableDispatchOutcome = { repository.externalImportDispatchMayHaveStarted(it) },
                markHandoffClaimed = { repository.markExternalImportHandoffClaimed(requestId) },
                markHandoffDispatchStarted = { repository.markExternalImportHandoffDispatchStarted(requestId) },
                dispatchPlayback = { _, markDispatchStarted ->
                    markDispatchStarted()
                    playerCalls++
                    successNotificationMessage = EXTERNAL_AUDIO_SUCCESS_NOTIFICATION_MESSAGE
                    ExternalAudioHandoffDispatchOutcome.Dispatched
                },
                markHandoffCompleted = {
                    completionAttempts++
                    if (completionAttempts == 1) repositoryFixture.preferences.failNextCommitAfterApplyingWrite()
                    repository.markExternalImportHandoffCompleted(requestId)
                },
                onHandoffCompleted = {
                    notificationCallbackCount++
                    successNotificationMessage = enqueueExternalAudioSuccessNotification(
                        successNotificationMessage,
                    ) { message -> notificationQueue.enqueue(requestId, message) }
                },
            )

            val firstAttempt = runCatching { attempt() }
            assertTrue(firstAttempt.isFailure)
            assertTrue(repository.externalImportDispatchMayHaveStarted(requestId))
            assertEquals(1, playerCalls)
            assertEquals(1, completionAttempts)
            assertEquals(0, notificationCallbackCount)
            assertEquals(0, notificationQueue.pending.value.size)
            assertEquals(0, acknowledgementCount)
            assertTrue("The failed commit applied the completed marker in memory.", repository.load().tracks.single().externalImportHandoffCompleted)

            successNotificationMessage = null
            val recovered = attempt()

            assertEquals(original.id, recovered.id)
            assertTrue(recovered.externalImportHandoffCompleted)
            assertEquals("A confirmed same-Track player dispatch must not be repeated.", 1, playerCalls)
            assertEquals("A confirmed-complete retry must not write completion again.", 1, completionAttempts)
            assertEquals("The confirmed-complete retry must invoke the success callback once.", 1, notificationCallbackCount)
            assertEquals(1, acknowledgementCount)
            assertEquals(1, notificationQueue.pending.value.size)
            assertEquals("Трек добавлен в медиатеку и воспроизводится.", notificationQueue.pending.value.single().message)
            assertEquals(requestId, notificationQueue.pending.value.single().requestId)
            assertTrue(repository.load().tracks.single().externalImportHandoffCompleted)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    @Test
    fun admittedDispatchPermitPersistsNoDispatchRollbackAfterRestoreAdmission() {
        val requestId = "e2db2ab2-3d91-46d3-b126-8d61e0d5c2ea"
        val repositoryFixture = repositoryFixture { audioDirectory ->
            LocalAudioImporter(audioDirectory, AudioMetadataReader { AudioMetadata(title = "Restore gate rollback") }, { Long.MAX_VALUE })
        }

        try {
            repositoryFixture.repository.importExternal(TestUri(TEST_URI), requestId)
            val claimed = repositoryFixture.repository.markExternalImportHandoffClaimed(requestId)
            assertFalse(repositoryFixture.repository.restoreActionGate.isBlocked)
            assertEquals(false, claimed.externalImportHandoffDispatchStarted)

            val gate = repositoryFixture.repository.restoreActionGate
            val permit = gate.tryAcquireExternalAudioDispatchPermit()
                ?: error("The dispatch permit should be admitted before restore begins.")
            val restore = gate.beginRestoreOperation()
            try {
                val started = repositoryFixture.repository.markExternalImportHandoffDispatchStarted(requestId, permit)
                assertTrue(started.externalImportHandoffDispatchStarted == true)
                assertThrows(IOException::class.java) {
                    repositoryFixture.repository.markExternalImportHandoffNotDispatched(requestId)
                }
                assertTrue(repositoryFixture.repository.load().tracks.single().externalImportHandoffDispatchStarted == true)

                val rolledBack = repositoryFixture.repository.markExternalImportHandoffNotDispatched(requestId, permit)
                assertEquals(false, rolledBack.externalImportHandoffDispatchStarted)
                assertEquals(false, repositoryFixture.repository.load().tracks.single().externalImportHandoffDispatchStarted)
            } finally {
                permit.close()
                restore.finish()
            }

            val reloaded = repositoryFixture.newRepository().load().tracks.single()
            assertEquals(requestId, reloaded.externalImportRequestId)
            assertTrue(reloaded.externalImportHandoffClaimed)
            assertEquals(false, reloaded.externalImportHandoffDispatchStarted)
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    private fun assertSecondTransactionWaitsForFirstCommit(
        repositoryFixture: RepositoryFixture,
        firstTransaction: () -> Unit,
        secondTransaction: () -> Unit,
    ) {
        val commitGate = repositoryFixture.preferences.pauseNextCommitBeforeApply()
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "aftertone-cross-repository-transaction-test").apply { isDaemon = true }
        }

        try {
            val firstFuture = executor.submit<Any?> { firstTransaction(); null }
            assertTrue("The first transaction should reach its state commit.", commitGate.entered.await(5, TimeUnit.SECONDS))

            val secondRead = repositoryFixture.preferences.observeNextStateRead()
            val secondStarted = CountDownLatch(1)
            val secondFuture = executor.submit<Any?> {
                secondStarted.countDown()
                secondTransaction()
                null
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(
                "A second repository must not read a snapshot while the first read-modify-write is paused at commit.",
                secondRead.await(500, TimeUnit.MILLISECONDS),
            )

            commitGate.release.countDown()
            firstFuture.get(5, TimeUnit.SECONDS)
            secondFuture.get(5, TimeUnit.SECONDS)
            assertTrue("The second transaction should continue after the first commit releases the shared lock.",
                secondRead.await(5, TimeUnit.SECONDS))
        } finally {
            commitGate.release.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertTrackValidationFailure(trackFactory: (File) -> Track) {
        val repositoryFixture = repositoryFixture { audioDirectory ->
            AudioTrackImporter { input, _, _ ->
                input.close()
                trackFactory(audioDirectory)
            }
        }

        try {
            assertThrows(IOException::class.java) {
                repositoryFixture.repository.import(TestUri(TEST_URI))
            }
        } finally {
            repositoryFixture.root.deleteRecursively()
        }
    }

    private fun repositoryFixture(
        persistWrites: Boolean = true,
        availableBytes: () -> Long = { Long.MAX_VALUE },
        documentSource: AudioDocumentSource? = null,
        artworkImporterFactory: ((File) -> TrackArtworkImporter)? = null,
        durability: AudioImportDurability = AndroidAudioImportDurability,
        importerFactory: (File) -> AudioTrackImporter,
    ): RepositoryFixture {
        val root = Files.createTempDirectory("aftertone-repository-import-").toFile()
        val filesDirectory = File(root, "files").apply { mkdirs() }
        val cacheDirectory = File(root, "cache").apply { mkdirs() }
        val preferences = InMemoryImportPreferences(persistWrites)
        val audioDirectory = File(filesDirectory, "audio")
        val context = ImportTestContext(root, filesDirectory, cacheDirectory, preferences.value)
        val source = documentSource ?: AudioDocumentSource { uri ->
            assertEquals(TEST_URI, uri.toString())
            AudioImportDocument(
                input = ByteArrayInputStream(mp3Fixture()),
                displayName = "tone-01.mp3",
                mimeType = "audio/mpeg",
            )
        }
        val createRepository = {
            LocalLibraryRepository(
                context,
                source,
                importerFactory(audioDirectory),
                availableBytes,
                artworkImporterFactory?.invoke(audioDirectory) ?: LocalArtworkImporter(audioDirectory),
                durability,
            )
        }
        return RepositoryFixture(
            root = root,
            audioDirectory = audioDirectory,
            cacheDirectory = cacheDirectory,
            repository = createRepository(),
            newRepository = createRepository,
            preferences = preferences,
        )
    }

    private fun emptyPreparedBackup(fixture: RepositoryFixture): PreparedBackupArchive {
        val id = UUID.randomUUID().toString()
        val archiveFile = PreparedBackupArchiveFiles.create(fixture.cacheDirectory, id)
        val summary = FileOutputStream(archiveFile).use { output ->
            LibraryBackupArchive.export(LibrarySnapshot(), fixture.audioDirectory, output)
        }
        val prepared = PreparedBackupArchive(id, summary)
        check(fixture.repository.preparedBackupArchiveOwner.retain(prepared))
        return prepared
    }

    private fun mp3Fixture(): ByteArray = LocalLibraryRepositoryImportTest::class.java
        .getResourceAsStream("/mp3-fixtures/tone-01.mp3")!!
        .use { it.readBytes() }

    private data class RepositoryFixture(
        val root: File,
        val audioDirectory: File,
        val cacheDirectory: File,
        val repository: LocalLibraryRepository,
        val newRepository: () -> LocalLibraryRepository,
        val preferences: InMemoryImportPreferences,
    )

    private class ImportTestContext(
        private val root: File,
        private val filesDirectory: File,
        private val cacheDirectory: File,
        private val preferences: SharedPreferences,
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = root
        override fun getFilesDir(): File = filesDirectory
        override fun getCacheDir(): File = cacheDirectory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    private class InMemoryImportPreferences(
        private val persistWrites: Boolean = true,
    ) : InvocationHandler {
        private val values = mutableMapOf<String, Any?>()
        private val valuesLock = Any()
        private val nextCommitGate = AtomicReference<PreferenceCommitGate?>(null)
        private val nextStateReadObserver = AtomicReference<CountDownLatch?>(null)
        @Volatile private var failReadsAfterWrite = false
        @Volatile private var hasWritten = false
        @Volatile private var failNextCommitAfterApplying = false
        @Volatile private var failNextCommitBeforeApplying = false
        val value: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            this,
        ) as SharedPreferences

        fun failReadsAfterNextWrite() {
            failReadsAfterWrite = true
        }

        fun allowReads() {
            failReadsAfterWrite = false
        }

        fun failNextCommitAfterApplyingWrite() {
            failNextCommitAfterApplying = true
        }

        fun failNextCommitBeforeApplyingWrite() {
            failNextCommitBeforeApplying = true
        }

        fun pauseNextCommitBeforeApply(): PreferenceCommitGate {
            val gate = PreferenceCommitGate()
            check(nextCommitGate.compareAndSet(null, gate)) { "A commit gate is already installed." }
            return gate
        }

        fun observeNextStateRead(): CountDownLatch {
            val observer = CountDownLatch(1)
            check(nextStateReadObserver.compareAndSet(null, observer)) { "A state-read observer is already installed." }
            return observer
        }

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args.orEmpty()
            return when (method.name) {
                "getAll" -> synchronized(valuesLock) { values.toMap() }
                "getString" -> {
                    val result = synchronized(valuesLock) {
                        if (failReadsAfterWrite && hasWritten) {
                            throw IllegalStateException("Simulated library-state read failure after commit.")
                        }
                        values[arguments[0] as String] as? String ?: arguments[1]
                    }
                    nextStateReadObserver.getAndSet(null)?.countDown()
                    result
                }
                "getStringSet" -> synchronized(valuesLock) { values[arguments[0] as String] as? Set<*> ?: arguments[1] }
                "getInt" -> synchronized(valuesLock) { values[arguments[0] as String] as? Int ?: arguments[1] }
                "getLong" -> synchronized(valuesLock) { values[arguments[0] as String] as? Long ?: arguments[1] }
                "getFloat" -> synchronized(valuesLock) { values[arguments[0] as String] as? Float ?: arguments[1] }
                "getBoolean" -> synchronized(valuesLock) { values[arguments[0] as String] as? Boolean ?: arguments[1] }
                "contains" -> synchronized(valuesLock) { values.containsKey(arguments[0] as String) }
                "edit" -> editor()
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "InMemoryImportPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val changed = linkedMapOf<String, Any?>()
            val removed = linkedSetOf<String>()
            var clear = false
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                val arguments = args.orEmpty()
                when (method.name) {
                    "putString", "putStringSet", "putInt", "putLong", "putFloat", "putBoolean" -> {
                        val key = arguments[0] as String
                        changed[key] = arguments[1]
                        removed.remove(key)
                        editor
                    }
                    "remove" -> {
                        val key = arguments[0] as String
                        changed.remove(key)
                        removed.add(key)
                        editor
                    }
                    "clear" -> { clear = true; editor }
                    "commit" -> {
                        val gate = nextCommitGate.getAndSet(null)
                        if (gate != null) {
                            gate.entered.countDown()
                            if (!gate.release.await(5, TimeUnit.SECONDS)) {
                                throw IllegalStateException("The test did not release the paused preference commit.")
                            }
                        }
                        val shouldFail = failNextCommitAfterApplying
                        failNextCommitAfterApplying = false
                        val shouldFailBeforeApplying = failNextCommitBeforeApplying
                        failNextCommitBeforeApplying = false
                        if (persistWrites && !shouldFailBeforeApplying) {
                            synchronized(valuesLock) {
                                if (clear) values.clear()
                                removed.forEach(values::remove)
                                values.putAll(changed)
                                hasWritten = true
                            }
                        }
                        !shouldFail && !shouldFailBeforeApplying
                    }
                    "apply" -> {
                        if (persistWrites) {
                            synchronized(valuesLock) {
                                if (clear) values.clear()
                                removed.forEach(values::remove)
                                values.putAll(changed)
                            }
                        }
                        null
                    }
                    "toString" -> "InMemoryImportPreferences.Editor"
                    "hashCode" -> System.identityHashCode(editor)
                    "equals" -> editor === arguments.firstOrNull()
                    else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }

    private companion object {
        const val TEST_URI = "content://test/audio/1"
    }
}
