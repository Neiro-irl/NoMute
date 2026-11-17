package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.Track
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.Enumeration
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupArchiveTest {
    @Test
    fun exportAndExtractPreserveAudioArtworkMetadataFavoritesAndPlaylistOrder() {
        val root = Files.createTempDirectory("aftertone-backup-roundtrip-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "library.zip")

            val exported = archive.outputStream().use { output ->
                LibraryBackupArchive.export(source.snapshot, source.audioDirectory, output)
            }
            val inspected = LibraryBackupArchive.inspect(archive)
            val staging = File(root, "restore-staging").apply { mkdir() }
            val prepared = LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })

            assertEquals(1, exported.trackCount)
            assertEquals(1, inspected.playlistCount)
            assertEquals(1, inspected.favoriteCount)
            assertEquals(exported, inspected)
            assertEquals("Тёплый вечер", prepared.snapshot.tracks.single().title)
            assertEquals(listOf("track-1"), prepared.snapshot.playlists.single().trackIds)
            assertEquals(setOf("track-1"), prepared.snapshot.favoriteTrackIds)
            assertEquals("audio/track-1.mp3", prepared.snapshot.tracks.single().filePath)
            assertEquals("artwork/track-1.img", prepared.snapshot.tracks.single().artworkPath)
            assertTrue(File(staging, "track-1.mp3").readBytes().contentEquals(source.audioBytes))
            assertTrue(File(staging, "track-1.img").readBytes().contentEquals(source.artworkBytes))
            assertFalse(ZipFile(archive).use { zip ->
                zip.getInputStream(zip.getEntry(LibraryBackupArchive.MANIFEST_ENTRY)).bufferedReader().readText()
                    .contains(source.audioDirectory.absolutePath)
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportMapsUniquePrivateArtworkToLegacyPortableAssetName() {
        val root = Files.createTempDirectory("aftertone-backup-custom-cover-").toFile()
        try {
            val source = fixture(root)
            val coverName = "track-1-cover-123e4567-e89b-12d3-a456-426614174000.img"
            val selectedArtworkBytes = byteArrayOf(8, 9, 10, 11)
            val selectedArtwork = File(source.audioDirectory, coverName).apply { writeBytes(selectedArtworkBytes) }
            val selectedSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map { it.copy(artworkPath = selectedArtwork.canonicalPath) },
            )
            val archive = File(root, "custom-cover.zip")
            archive.outputStream().use { output ->
                LibraryBackupArchive.export(selectedSnapshot, source.audioDirectory, output)
            }

            val entries = ZipFile(archive).use { zip -> Collections.list(zip.entries()).map { it.name } }
            assertTrue("Custom image bytes must use the stable portable entry name.", "artwork/track-1.img" in entries)
            assertFalse("The private generated filename must not leak into backups.", "artwork/$coverName" in entries)

            val staging = File(root, "custom-cover-staging").apply { mkdir() }
            val restored = LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })
            assertEquals("artwork/track-1.img", restored.snapshot.tracks.single().artworkPath)
            assertTrue(File(staging, "track-1.img").readBytes().contentEquals(selectedArtworkBytes))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsValidStoredBackupWithMultipleLocalEntries() {
        val root = Files.createTempDirectory("aftertone-backup-valid-stored-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid-stored.zip").apply {
                writeBytes(validStoredBackupBytes(source))
            }

            assertEquals(1, LibraryBackupArchive.inspect(archive).trackCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractsStoredAudioAssetUsingValidatedLocalSpan() {
        val root = Files.createTempDirectory("aftertone-backup-stored-extract-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid-stored.zip").apply {
                writeBytes(validStoredBackupBytes(source))
            }
            val staging = File(root, "restore-staging").apply { mkdir() }

            LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })

            val restoredAudio = File(staging, "track-1.mp3").readBytes()
            assertEquals(source.audioBytes.size, restoredAudio.size)
            assertTrue(restoredAudio.contentEquals(source.audioBytes))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipFileMetadataChangedAfterRawPreflight() {
        val root = Files.createTempDirectory("aftertone-backup-preflight-metadata-change-").toFile()
        try {
            val source = fixture(root)
            val original = validStoredBackupBytes(source)
            val archive = File(root, "metadata-changed.zip").apply { writeBytes(original) }
            val changedCentralDirectory = patchCentralMethod(original, "audio/track-1.mp3", ZipEntry.DEFLATED)
            var zipFileOpened = false

            val error = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(
                    archive,
                    zipFileFactory = { file ->
                        file.writeBytes(changedCentralDirectory)
                        ZipFile(file).also { zipFileOpened = true }
                    },
                )
            }

            assertTrue("The changed archive should reach the second metadata pass.", zipFileOpened)
            assertTrue(error.message.orEmpty().contains("preflight", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsSmallZip64DataDescriptorWhenCentralSizesUseZip64() {
        val root = Files.createTempDirectory("aftertone-backup-zip64-descriptor-valid-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "zip64-descriptor.zip").apply {
                writeBytes(zip64DescriptorBackupBytes(source, descriptorUsesZip64Sizes = true))
            }

            assertEquals(1, LibraryBackupArchive.inspect(archive).trackCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsClassicDataDescriptorWhenCentralSizesUseZip64() {
        val root = Files.createTempDirectory("aftertone-backup-zip64-descriptor-wrong-width-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "wrong-width-descriptor.zip").apply {
                writeBytes(zip64DescriptorBackupBytes(source, descriptorUsesZip64Sizes = false))
            }

            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.inspect(archive) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsLocalHeaderCrcMismatchWhenDescriptorFlagIsClear() {
        val root = Files.createTempDirectory("aftertone-backup-local-crc-mismatch-").toFile()
        try {
            val source = fixture(root)
            val valid = validStoredBackupBytes(source)
            val central = centralDirectoryEntryRecord(valid, "audio/track-1.mp3")
            val malformed = File(root, "local-crc-mismatch.zip").apply {
                writeBytes(
                    patchLocalHeaderMetadata(
                        valid,
                        "audio/track-1.mp3",
                        crc = readUnsignedIntLe(central, 16) xor 1L,
                    ),
                )
            }

            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.inspect(malformed) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsLocalCompressedSizeMismatchWhenDescriptorFlagIsClear() {
        val root = Files.createTempDirectory("aftertone-backup-local-compressed-size-mismatch-").toFile()
        try {
            val source = fixture(root)
            val valid = validStoredBackupBytes(source)
            val central = centralDirectoryEntryRecord(valid, "audio/track-1.mp3")
            val malformed = File(root, "local-compressed-size-mismatch.zip").apply {
                writeBytes(
                    patchLocalHeaderMetadata(
                        valid,
                        "audio/track-1.mp3",
                        compressedSize = readUnsignedIntLe(central, 20) + 1L,
                    ),
                )
            }

            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.inspect(malformed) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsLocalUncompressedSizeMismatchWhenDescriptorFlagIsClear() {
        val root = Files.createTempDirectory("aftertone-backup-local-uncompressed-size-mismatch-").toFile()
        try {
            val source = fixture(root)
            val valid = validStoredBackupBytes(source)
            val central = centralDirectoryEntryRecord(valid, "audio/track-1.mp3")
            val malformed = File(root, "local-uncompressed-size-mismatch.zip").apply {
                writeBytes(
                    patchLocalHeaderMetadata(
                        valid,
                        "audio/track-1.mp3",
                        uncompressedSize = readUnsignedIntLe(central, 24) + 1L,
                    ),
                )
            }

            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.inspect(malformed) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsLocalZip64SizeSentinelsResolvedFromExtraField() {
        val root = Files.createTempDirectory("aftertone-backup-local-zip64-valid-").toFile()
        try {
            val source = fixture(root)
            val audio = RawEntry(
                "audio/track-1.mp3",
                source.audioBytes,
                localZip64CompressedSize = source.audioBytes.size.toLong(),
                localZip64UncompressedSize = source.audioBytes.size.toLong(),
            )
            val archive = File(root, "local-zip64-valid.zip").apply {
                writeBytes(validStoredBackupBytes(source, audio))
            }

            assertEquals(1, LibraryBackupArchive.inspect(archive).trackCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsLocalZip64CompressedSizeSentinelResolvedIndependently() {
        val root = Files.createTempDirectory("aftertone-backup-local-zip64-compressed-only-").toFile()
        try {
            val source = fixture(root)
            val audio = RawEntry(
                "audio/track-1.mp3",
                source.audioBytes,
                localZip64CompressedSize = source.audioBytes.size.toLong(),
            )
            val archive = File(root, "local-zip64-compressed-only.zip").apply {
                writeBytes(validStoredBackupBytes(source, audio))
            }

            assertEquals(1, LibraryBackupArchive.inspect(archive).trackCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsLocalZip64UncompressedSizeSentinelResolvedIndependently() {
        val root = Files.createTempDirectory("aftertone-backup-local-zip64-uncompressed-only-").toFile()
        try {
            val source = fixture(root)
            val audio = RawEntry(
                "audio/track-1.mp3",
                source.audioBytes,
                localZip64UncompressedSize = source.audioBytes.size.toLong(),
            )
            val archive = File(root, "local-zip64-uncompressed-only.zip").apply {
                writeBytes(validStoredBackupBytes(source, audio))
            }

            assertEquals(1, LibraryBackupArchive.inspect(archive).trackCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsLocalZip64SizeThatDiffersFromCentralDirectory() {
        val root = Files.createTempDirectory("aftertone-backup-local-zip64-mismatch-").toFile()
        try {
            val source = fixture(root)
            val audio = RawEntry(
                "audio/track-1.mp3",
                source.audioBytes,
                localZip64CompressedSize = source.audioBytes.size.toLong(),
                localZip64UncompressedSize = source.audioBytes.size.toLong() + 1L,
            )
            val malformed = File(root, "local-zip64-mismatch.zip").apply {
                writeBytes(validStoredBackupBytes(source, audio))
            }

            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.inspect(malformed) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsOverlappingStoredAssetContainingAnotherLocalEntry() {
        val root = Files.createTempDirectory("aftertone-backup-overlapping-stored-").toFile()
        try {
            val source = fixture(root)
            val snapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(filePath = "audio/track-1.mp3", artworkPath = "artwork/track-1.img")
                },
            )
            val embeddedEntryZip = rawZip(listOf(RawEntry("artwork/track-1.img", source.artworkBytes)))
            val embeddedLocalRecord = embeddedEntryZip.copyOfRange(0, centralDirectoryOffset(embeddedEntryZip))
            val manifest = LibraryBackupManifestCodec.encode(
                LibraryStateCodec.encode(snapshot),
                listOf(
                    LibraryBackupAsset("audio/track-1.mp3", embeddedLocalRecord.size.toLong()),
                    LibraryBackupAsset("artwork/track-1.img", source.artworkBytes.size.toLong()),
                ),
            )
            val outerArchive = rawZip(
                listOf(
                    RawEntry(LibraryBackupArchive.MANIFEST_ENTRY, manifest),
                    RawEntry("audio/track-1.mp3", embeddedLocalRecord),
                ),
            )
            val embeddedLocalHeaderOffset = localDataOffsetForEntry(outerArchive, "audio/track-1.mp3")
            val embeddedCentralRecord = centralDirectoryEntryRecord(embeddedEntryZip, "artwork/track-1.img")
            writeUnsignedIntLe(embeddedCentralRecord, 42, embeddedLocalHeaderOffset)
            val archive = File(root, "overlapping-stored.zip").apply {
                writeBytes(appendCentralDirectoryEntry(outerArchive, embeddedCentralRecord))
            }

            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.inspect(archive) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportAndExtractPreserveExternalImportRequestId() {
        val root = Files.createTempDirectory("aftertone-backup-request-id-").toFile()
        try {
            val source = fixture(root)
            val requestId = "f1557c4e-6670-4bc1-a595-914433c9a8d3"
            val snapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(
                        externalImportRequestId = requestId,
                        externalImportHandoffClaimed = true,
                        externalImportHandoffDispatchStarted = true,
                        externalImportHandoffCompleted = true,
                    )
                },
            )
            val archive = File(root, "library-with-request-id.zip")

            LibraryBackupArchive.export(snapshot, source.audioDirectory, archive.outputStream())
            val staging = File(root, "request-id-staging").apply { mkdir() }
            val prepared = LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })

            assertEquals(requestId, prepared.snapshot.tracks.single().externalImportRequestId)
            assertTrue(prepared.snapshot.tracks.single().externalImportHandoffClaimed)
            assertTrue(prepared.snapshot.tracks.single().externalImportHandoffDispatchStarted == true)
            assertTrue(prepared.snapshot.tracks.single().externalImportHandoffCompleted)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportAndExtractPreserveKnownNotDispatchedHandoffState() {
        val root = Files.createTempDirectory("aftertone-backup-not-dispatched-").toFile()
        try {
            val source = fixture(root)
            val requestId = "0f37c3a3-0d9a-49c8-9f3d-10de8a548f11"
            val snapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(
                        externalImportRequestId = requestId,
                        externalImportHandoffClaimed = true,
                        externalImportHandoffDispatchStarted = false,
                    )
                },
            )
            val archive = File(root, "library-not-dispatched.zip")

            LibraryBackupArchive.export(snapshot, source.audioDirectory, archive.outputStream())
            val staging = File(root, "not-dispatched-staging").apply { mkdir() }
            val prepared = LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })
            val restoredTrack = prepared.snapshot.tracks.single()

            assertTrue(restoredTrack.externalImportHandoffClaimed)
            assertEquals(false, restoredTrack.externalImportHandoffDispatchStarted)
            assertFalse(restoredTrack.externalImportHandoffCompleted)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestAcceptsValidUnicodeStateWhoseUtf8BytesExceedFiveMiB() {
        val root = Files.createTempDirectory("aftertone-backup-unicode-state-").toFile()
        try {
            val source = fixture(root)
            val title = "界".repeat(1_800_000)
            val portableSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(title = title, filePath = "audio/track-1.mp3", artworkPath = "artwork/track-1.img")
                },
            )
            val encodedState = LibraryStateCodec.encode(portableSnapshot)
            val stateBytes = encodedState.toByteArray(Charsets.UTF_8)
            assertTrue(encodedState.length < 5 * 1024 * 1024)
            assertTrue(stateBytes.size > 5 * 1024 * 1024)

            val manifest = LibraryBackupManifestCodec.encode(
                encodedState,
                listOf(
                    LibraryBackupAsset("audio/track-1.mp3", source.audioBytes.size.toLong()),
                    LibraryBackupAsset("artwork/track-1.img", source.artworkBytes.size.toLong()),
                ),
            )

            assertEquals(title, LibraryBackupManifestCodec.decode(manifest).snapshot.tracks.single().title)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsMalformedUnicodeInsteadOfReplacingMetadataDuringBackup() {
        val root = Files.createTempDirectory("aftertone-backup-malformed-unicode-").toFile()
        try {
            val source = fixture(root)
            val invalidTitle = String(charArrayOf('x', '\uD800'))
            val invalidSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map { it.copy(title = invalidTitle) },
            )

            assertThrows(IllegalArgumentException::class.java) { LibraryStateCodec.encode(invalidSnapshot) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveWithExcessiveAssetExpansionRatio() {
        val root = Files.createTempDirectory("aftertone-backup-expansion-ratio-").toFile()
        try {
            val source = fixture(root)
            val audioBytes = ByteArray(512 * 1024) { 'A'.code.toByte() }
            val portableSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(filePath = "audio/track-1.mp3", artworkPath = "artwork/track-1.img")
                },
            )
            val manifest = LibraryBackupManifestCodec.encode(
                LibraryStateCodec.encode(portableSnapshot),
                listOf(
                    LibraryBackupAsset("audio/track-1.mp3", audioBytes.size.toLong()),
                    LibraryBackupAsset("artwork/track-1.img", source.artworkBytes.size.toLong()),
                ),
            )
            val archive = File(root, "high-ratio.zip").apply {
                writeBytes(
                    deflatedZip(
                        listOf(
                            RawEntry(LibraryBackupArchive.MANIFEST_ENTRY, manifest),
                            RawEntry("audio/track-1.mp3", audioBytes),
                            RawEntry("artwork/track-1.img", source.artworkBytes),
                        ),
                    ),
                )
            }

            val staging = File(root, "stage").apply { mkdir() }
            assertEquals(1, LibraryBackupArchive.inspect(archive).trackCount)
            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })
            }

            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsForgedCompressedSizeWhenInflaterConsumedBytesExceedAssetRatio() {
        val root = Files.createTempDirectory("aftertone-backup-forged-compressed-size-").toFile()
        try {
            val source = fixture(root)
            val audioBytes = ByteArray(512 * 1024) { 'A'.code.toByte() }
            val artworkBytes = ByteArray(16 * 1024).also { Random(2026).nextBytes(it) }
            val portableSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(filePath = "audio/track-1.mp3", artworkPath = "artwork/track-1.img")
                },
            )
            val manifest = LibraryBackupManifestCodec.encode(
                LibraryStateCodec.encode(portableSnapshot),
                listOf(
                    LibraryBackupAsset("audio/track-1.mp3", audioBytes.size.toLong()),
                    LibraryBackupAsset("artwork/track-1.img", artworkBytes.size.toLong()),
                ),
            )
            val forgedArchive = File(root, "forged-compressed-size.zip").apply {
                writeBytes(
                    rewriteDeflatedEntrySizes(
                        deflatedZip(
                            listOf(
                                RawEntry(LibraryBackupArchive.MANIFEST_ENTRY, manifest),
                                RawEntry("audio/track-1.mp3", audioBytes),
                                RawEntry("artwork/track-1.img", artworkBytes),
                            ),
                        ),
                        "audio/track-1.mp3",
                        compressedSize = 8L * 1024,
                    ),
                )
            }
            assertEquals(1, LibraryBackupArchive.inspect(forgedArchive).trackCount)

            val staging = File(root, "stage").apply { mkdir() }
            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.extract(forgedArchive, staging, availableBytes = { Long.MAX_VALUE })
            }

            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsManifestCompressedRangeThatOverlapsCentralDirectory() {
        val root = Files.createTempDirectory("aftertone-manifest-overlap-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val archiveBytes = archive.readBytes()
            val dataOffset = firstLocalEntryDataOffset(archiveBytes)
            val centralDirectoryOffset = centralDirectoryOffset(archiveBytes)
            assertTrue(centralDirectoryOffset > dataOffset)
            val forgedCompressedSize = centralDirectoryOffset - dataOffset + 1
            assertTrue(dataOffset + forgedCompressedSize <= archiveBytes.size)
            val malformed = File(root, "manifest-overlap.zip").apply {
                writeBytes(patchCentralCompressedSize(archiveBytes, LibraryBackupArchive.MANIFEST_ENTRY, forgedCompressedSize))
            }

            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(malformed)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsManifestCompressedSizeWhenInflaterLeavesDeclaredBytesUnused() {
        val root = Files.createTempDirectory("aftertone-manifest-trailing-compressed-byte-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid.zip").apply { writeBytes(validDeflatedBackupBytes(source)) }
            val archiveBytes = archive.readBytes()
            val dataOffset = localDataOffsetForEntry(archiveBytes, LibraryBackupArchive.MANIFEST_ENTRY)
            val centralDirectoryOffset = centralDirectoryOffset(archiveBytes)
            val actualCompressedSize = ZipFile(archive).use {
                it.getEntry(LibraryBackupArchive.MANIFEST_ENTRY).compressedSize
            }
            val forgedCompressedSize = actualCompressedSize + 1L
            assertTrue(dataOffset + forgedCompressedSize < centralDirectoryOffset)
            val malformed = File(root, "manifest-trailing-byte.zip").apply {
                writeBytes(
                    rewriteDeflatedEntrySizes(
                        archiveBytes,
                        LibraryBackupArchive.MANIFEST_ENTRY,
                        compressedSize = forgedCompressedSize,
                    ),
                )
            }

            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(malformed)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsForgedDirectoryRatioNearPrefixAllowancePlusActualRatioBound() {
        val root = Files.createTempDirectory("aftertone-backup-forged-prefix-bound-").toFile()
        try {
            val source = fixture(root)
            val audioBytes = ByteArray(4 * 1024 * 1024) { 'A'.code.toByte() }
            val artworkBytes = ByteArray(512 * 1024).also { Random(2027).nextBytes(it) }
            val portableSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(filePath = "audio/track-1.mp3", artworkPath = "artwork/track-1.img")
                },
            )
            val manifest = LibraryBackupManifestCodec.encode(
                LibraryStateCodec.encode(portableSnapshot),
                listOf(
                    LibraryBackupAsset("audio/track-1.mp3", audioBytes.size.toLong()),
                    LibraryBackupAsset("artwork/track-1.img", artworkBytes.size.toLong()),
                ),
            )
            val honestArchive = File(root, "honest-ratio.zip").apply {
                writeBytes(
                    deflatedZip(
                        listOf(
                            RawEntry(LibraryBackupArchive.MANIFEST_ENTRY, manifest),
                            RawEntry("audio/track-1.mp3", audioBytes),
                            RawEntry("artwork/track-1.img", artworkBytes),
                        ),
                    ),
                )
            }
            val actualCompressedBytes = ZipFile(honestArchive).use { zip ->
                zip.getEntry("audio/track-1.mp3").compressedSize
            }
            val declaredCompressedBytes = 64 * 1024
            val archive = File(root, "forged-ratio.zip").apply {
                writeBytes(
                    rewriteDeflatedEntrySizes(
                        honestArchive.readBytes(),
                        "audio/track-1.mp3",
                        compressedSize = declaredCompressedBytes.toLong(),
                    ),
                )
            }
            assertEquals(1, LibraryBackupArchive.inspect(archive).trackCount)
            val staging = File(root, "stage").apply { mkdir() }
            val limits = LibraryBackupLimits(
                maxAudioFileBytes = 4L * 1024 * 1024,
                maxTotalPayloadBytes = 5L * 1024 * 1024,
            )
            assertTrue(audioBytes.size.toLong() <= declaredCompressedBytes * 200L)
            var lastProgressBytes = 0L

            val error = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.extract(
                    archive,
                    staging,
                    availableBytes = { Long.MAX_VALUE },
                    onProgress = { progress -> lastProgressBytes = progress.completedBytes },
                    limits = limits,
                )
            }
            assertTrue(error.message.orEmpty().contains("expansion ratio", ignoreCase = true))

            val maximumPrefixExpansion = 1024L * 1024
            val outputChunkAllowance = 64L * 1024
            val maximumExpandedBeforeRejection = maximumPrefixExpansion + actualCompressedBytes * 200L + outputChunkAllowance
            assertTrue("The guard should reach beyond its fixed prefix allowance.", lastProgressBytes > maximumPrefixExpansion)
            assertTrue(
                "Inflation must stop near prefix allowance + 200x actual compressed bytes; wrote $lastProgressBytes bytes, bound=$maximumExpandedBeforeRejection.",
                lastProgressBytes <= maximumExpandedBeforeRejection,
            )
            assertTrue(lastProgressBytes < audioBytes.size.toLong())
            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsClassicEntryCountBeforeOpeningZipFile() {
        val root = Files.createTempDirectory("aftertone-backup-eocd-count-").toFile()
        try {
            val archive = File(root, "over-limit.zip").apply {
                writeBytes(classicEocd(entryCount = 5, centralSize = 0, centralOffset = 0))
            }
            var zipOpened = false

            val error = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(
                    archive,
                    limits = LibraryBackupLimits(maxEntries = 4),
                    zipFileFactory = { file -> zipOpened = true; ZipFile(file) },
                )
            }

            assertFalse(zipOpened)
            assertTrue(error.message.orEmpty().contains("too many", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsZip64EntryCountBeforeOpeningZipFile() {
        val root = Files.createTempDirectory("aftertone-backup-zip64-count-").toFile()
        try {
            val archive = File(root, "over-limit-zip64.zip").apply {
                writeBytes(zip64Eocd(totalEntries = 100_001))
            }
            var zipOpened = false

            val error = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(
                    archive,
                    limits = LibraryBackupLimits(maxEntries = 100_000),
                    zipFileFactory = { file -> zipOpened = true; ZipFile(file) },
                )
            }

            assertFalse(zipOpened)
            assertTrue(error.message.orEmpty().contains("too many", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsOversizedCentralDirectoryBeforeOpeningZipFile() {
        val root = Files.createTempDirectory("aftertone-backup-central-size-").toFile()
        try {
            val archive = File(root, "oversized-directory.zip").apply {
                writeBytes(classicEocd(entryCount = 0, centralSize = 64 * 1024 * 1024, centralOffset = 0))
            }
            var zipOpened = false

            val error = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(archive, zipFileFactory = { file -> zipOpened = true; ZipFile(file) })
            }

            assertFalse(zipOpened)
            assertTrue(error.message.orEmpty().contains("central directory", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipDirectoryEnumerationStopsAtConfiguredLimit() {
        val entries = CountingZipEntries(totalEntries = 10_000)

        assertThrows(IllegalArgumentException::class.java) {
            LibraryBackupArchive.collectZipEntries(entries, LibraryBackupLimits(maxEntries = 3))
        }

        assertEquals(3, entries.consumed)
        assertTrue(entries.hasMoreElements())
    }

    @Test
    fun rejectsTraversalConsistentAcrossManifestAndZipMemberBeforeExtraction() {
        val root = Files.createTempDirectory("aftertone-backup-slip-").toFile()
        try {
            val source = fixture(root)
            val valid = File(root, "valid.zip")
            valid.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val outside = File(root.parentFile, "${root.name}-outside.mp3")
            val traversalPath = "audio/../../${outside.name}"
            val manifest = readEntries(valid).single { it.first == LibraryBackupArchive.MANIFEST_ENTRY }.second
            val maliciousManifest = traversalManifest(manifest, traversalPath)
            val entries = readEntries(valid).map { (name, bytes) ->
                when (name) {
                    LibraryBackupArchive.MANIFEST_ENTRY -> RawEntry(name, maliciousManifest)
                    "audio/track-1.mp3" -> RawEntry(traversalPath, bytes)
                    else -> RawEntry(name, bytes)
                }
            }
            val malicious = File(root, "traversal.zip").apply { writeBytes(rawZip(entries)) }
            val staging = File(root, "stage").apply { mkdir() }

            val inspectionError = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(malicious)
            }
            assertTrue(inspectionError.message.orEmpty().contains("unsafe", ignoreCase = true))
            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.extract(malicious, staging, availableBytes = { Long.MAX_VALUE })
            }
            assertTrue(staging.list().isNullOrEmpty())
            assertFalse(outside.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicateAndMissingArchiveEntries() {
        val root = Files.createTempDirectory("aftertone-backup-entries-").toFile()
        try {
            val source = fixture(root)
            val valid = File(root, "valid.zip")
            valid.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val entries = readEntries(valid)
            val audio = entries.single { it.first == "audio/track-1.mp3" }
            val duplicate = File(root, "duplicate.zip").apply {
                writeBytes(rawZip(entries.map { RawEntry(it.first, it.second) } + RawEntry(audio.first, audio.second)))
            }
            val missing = File(root, "missing.zip").apply {
                writeBytes(rawZip(entries.filterNot { it.first == audio.first }.map { RawEntry(it.first, it.second) }))
            }

            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.inspect(duplicate) }
            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.inspect(missing) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsCorruptPayloadAndTruncatedZipDirectory() {
        val root = Files.createTempDirectory("aftertone-backup-corrupt-").toFile()
        try {
            val source = fixture(root)
            val valid = File(root, "valid.zip")
            valid.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val entries = readEntries(valid)
            val corruptedEntries = entries.map { (name, bytes) ->
                if (name == "audio/track-1.mp3") RawEntry(name, bytes, corruptCrc = true) else RawEntry(name, bytes)
            }
            val corrupted = File(root, "corrupted.zip").apply { writeBytes(rawZip(corruptedEntries)) }
            val truncated = File(root, "truncated.zip").apply {
                writeBytes(valid.readBytes().copyOf(valid.length().toInt() - 8))
            }

            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.extract(
                corrupted,
                File(root, "corrupt-stage").apply { mkdir() },
                availableBytes = { Long.MAX_VALUE },
            ) }
            assertThrows(Exception::class.java) { LibraryBackupArchive.inspect(truncated) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsExpandedPayloadOverLimitBeforeWritingAudio() {
        val root = Files.createTempDirectory("aftertone-backup-limit-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val staging = File(root, "stage").apply { mkdir() }
            val strictLimits = LibraryBackupLimits(maxTotalPayloadBytes = 4)

            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(archive, strictLimits)
            }
            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractRejectsDeflatedBytesAboveManifestAndCentralSizesBeforeReturningStagedAssets() {
        val root = Files.createTempDirectory("aftertone-backup-expanded-entry-").toFile()
        try {
            val source = fixture(root)
            val valid = File(root, "valid.zip")
            valid.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val reducedSize = source.audioBytes.size - 1L
            val entries = readEntries(valid).map { (name, bytes) ->
                RawEntry(
                    name,
                    if (name == LibraryBackupArchive.MANIFEST_ENTRY) {
                        rewriteManifestAssetSize(bytes, "audio/track-1.mp3", reducedSize)
                    } else {
                        bytes
                    },
                )
            }
            val malformed = File(root, "expanded.zip").apply {
                writeBytes(
                    rewriteDeflatedEntrySizes(
                        deflatedZip(entries),
                        "audio/track-1.mp3",
                        uncompressedSize = reducedSize,
                    ),
                )
            }
            val staging = File(root, "stage").apply { mkdir() }

            assertEquals(1, LibraryBackupArchive.inspect(malformed).trackCount)
            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.extract(malformed, staging, availableBytes = { Long.MAX_VALUE })
            }

            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsInsufficientSpaceBeforeExtractingAndHonorsCancellation() {
        val root = Files.createTempDirectory("aftertone-backup-space-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val staging = File(root, "stage").apply { mkdir() }

            assertThrows(IllegalStateException::class.java) {
                LibraryBackupArchive.extract(archive, staging, availableBytes = { 0L })
            }
            assertTrue(staging.list().isNullOrEmpty())
            assertThrows(InterruptedException::class.java) {
                LibraryBackupArchive.extract(
                    archive,
                    staging,
                    availableBytes = { Long.MAX_VALUE },
                    checkCancelled = { throw InterruptedException("cancelled") },
                )
            }
            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractRemovesPartialAudioWhenStorageRunsLowMidStream() {
        val root = Files.createTempDirectory("aftertone-backup-extract-low-space-").toFile()
        try {
            val source = fixture(root)
            val incompressibleAudio = ByteArray(2 * 1024 * 1024).also { Random(42).nextBytes(it) }
            File(source.audioDirectory, "track-1.mp3").writeBytes(incompressibleAudio)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val staging = File(root, "stage").apply { mkdir() }
            var checks = 0

            val error = assertThrows(IllegalStateException::class.java) {
                LibraryBackupArchive.extract(
                    archive,
                    staging,
                    availableBytes = { checks++; if (checks < 3) Long.MAX_VALUE else 0L },
                )
            }

            assertTrue(error.message.orEmpty().contains("ran low", ignoreCase = true))
            assertTrue(checks >= 3)
            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageArchiveEnforcesCompressedInputLimitAndRemovesPartialFile() {
        val root = Files.createTempDirectory("aftertone-backup-input-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val destination = File(root, "staged.zip")
            val limits = LibraryBackupLimits(maxCompressedBytes = 32)

            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.stageArchive(
                    archive.inputStream(),
                    destination,
                    availableBytes = { Long.MAX_VALUE },
                    limits = limits,
                )
            }
            assertFalse(destination.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationDuringInspectionDeletesPreparedArchive() {
        val root = Files.createTempDirectory("aftertone-backup-inspect-cancel-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val staged = File(root, "prepared.zip")
            var checks = 0

            assertThrows(CancellationException::class.java) {
                LibraryBackupArchive.stageAndInspectArchive(
                    input = archive.inputStream(),
                    destination = staged,
                    availableBytes = { Long.MAX_VALUE },
                    checkCancelled = {
                        checks++
                        if (checks == 4) throw CancellationException("cancelled during inspection")
                    },
                )
            }

            assertEquals(4, checks)
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestDecodePropagatesCancellationDuringPropertiesParsing() {
        val root = Files.createTempDirectory("aftertone-backup-manifest-cancel-").toFile()
        try {
            val source = fixture(root)
            val portableSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(filePath = "audio/track-1.mp3", artworkPath = "artwork/track-1.img")
                },
            )
            val encodedState = LibraryStateCodec.encode(portableSnapshot) + "\n#" + "x".repeat(256 * 1024) + "\n"
            val manifest = LibraryBackupManifestCodec.encode(
                encodedState,
                listOf(
                    LibraryBackupAsset("audio/track-1.mp3", source.audioBytes.size.toLong()),
                    LibraryBackupAsset("artwork/track-1.img", source.artworkBytes.size.toLong()),
                ),
            )
            var checks = 0

            val cancellation = assertThrows(CancellationException::class.java) {
                LibraryBackupManifestCodec.decode(
                    manifest,
                    checkCancelled = {
                        checks++
                        if (checks == 3) throw CancellationException("cancelled during state parsing")
                    },
                )
            }

            assertEquals("cancelled during state parsing", cancellation.message)
            assertEquals(3, checks)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageArchiveRejectsSymlinkedPrivateCacheRoot() {
        val root = Files.createTempDirectory("aftertone-backup-cache-root-").toFile()
        try {
            val redirectedTarget = File(root, "redirected-cache").apply { mkdir() }
            val cacheRootLink = File(root, "app-cache-link")
            Files.createSymbolicLink(cacheRootLink.toPath(), redirectedTarget.toPath())

            assertThrows(SecurityException::class.java) {
                LibraryBackupArchive.stageArchive(
                    ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                    File(cacheRootLink, "staged.zip"),
                    availableBytes = { Long.MAX_VALUE },
                )
            }
            assertFalse(File(redirectedTarget, "staged.zip").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageArchiveBatchesFreeSpaceChecksWhileStreamingLargeInput() {
        val root = Files.createTempDirectory("aftertone-backup-space-checks-").toFile()
        try {
            var freeSpaceChecks = 0
            val destination = File(root, "large-input.zip")
            LibraryBackupArchive.stageArchive(
                input = ByteArrayInputStream(ByteArray(2 * 1024 * 1024)),
                destination = destination,
                availableBytes = { freeSpaceChecks++; Long.MAX_VALUE },
            )

            assertTrue(freeSpaceChecks in 1..5)
            assertEquals(2 * 1024 * 1024L, destination.length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageArchiveRemovesPartialFileWhenStorageRunsLowMidStream() {
        val root = Files.createTempDirectory("aftertone-backup-stage-low-space-").toFile()
        try {
            var checks = 0
            val destination = File(root, "partial.zip")

            val error = assertThrows(IllegalStateException::class.java) {
                LibraryBackupArchive.stageArchive(
                    input = ByteArrayInputStream(ByteArray(2 * 1024 * 1024) { (it * 17).toByte() }),
                    destination = destination,
                    availableBytes = { checks++; if (checks == 1) Long.MAX_VALUE else 0L },
                )
            }

            assertTrue(error.message.orEmpty().contains("not enough free space", ignoreCase = true))
            assertTrue(checks >= 2)
            assertFalse(destination.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun traversalManifest(original: ByteArray, traversalPath: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataInputStream(ByteArrayInputStream(original)).use { input ->
            val magic = ByteArray(8).also(input::readFully)
            val version = input.readInt()
            val stateBytes = ByteArray(input.readInt()).also(input::readFully)
            val state = stateBytes.toString(Charsets.UTF_8)
            require("audio/track-1.mp3" in state)
            val maliciousState = state.replace("audio/track-1.mp3", traversalPath)
            val assets = List(input.readInt()) { input.readUTF() to input.readLong() }
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.writeInt(version)
                output.writeInt(maliciousState.toByteArray(Charsets.UTF_8).size)
                output.write(maliciousState.toByteArray(Charsets.UTF_8))
                output.writeInt(assets.size)
                assets.forEach { (name, size) ->
                    output.writeUTF(if (name == "audio/track-1.mp3") traversalPath else name)
                    output.writeLong(size)
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun rewriteManifestAssetSize(original: ByteArray, target: String, sizeBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        DataInputStream(ByteArrayInputStream(original)).use { input ->
            val magic = ByteArray(8).also(input::readFully)
            val version = input.readInt()
            val state = ByteArray(input.readInt()).also(input::readFully)
            val assets = List(input.readInt()) { input.readUTF() to input.readLong() }
            require(assets.any { it.first == target })
            DataOutputStream(output).use { manifest ->
                manifest.write(magic)
                manifest.writeInt(version)
                manifest.writeInt(state.size)
                manifest.write(state)
                manifest.writeInt(assets.size)
                assets.forEach { (name, originalSize) ->
                    manifest.writeUTF(name)
                    manifest.writeLong(if (name == target) sizeBytes else originalSize)
                }
            }
        }
        return output.toByteArray()
    }

    private fun deflatedZip(entries: List<RawEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun validStoredBackupBytes(
        source: Fixture,
        audioEntry: RawEntry = RawEntry("audio/track-1.mp3", source.audioBytes),
    ): ByteArray = rawZip(validBackupEntries(source, audioEntry))

    private fun validDeflatedBackupBytes(source: Fixture): ByteArray =
        deflatedZip(validBackupEntries(source, RawEntry("audio/track-1.mp3", source.audioBytes)))

    private fun validBackupEntries(source: Fixture, audioEntry: RawEntry): List<RawEntry> {
        val snapshot = source.snapshot.copy(
            tracks = source.snapshot.tracks.map {
                it.copy(filePath = "audio/track-1.mp3", artworkPath = "artwork/track-1.img")
            },
        )
        val manifest = LibraryBackupManifestCodec.encode(
            LibraryStateCodec.encode(snapshot),
            listOf(
                LibraryBackupAsset("audio/track-1.mp3", audioEntry.bytes.size.toLong()),
                LibraryBackupAsset("artwork/track-1.img", source.artworkBytes.size.toLong()),
            ),
        )
        return listOf(
            RawEntry(LibraryBackupArchive.MANIFEST_ENTRY, manifest),
            audioEntry,
            RawEntry("artwork/track-1.img", source.artworkBytes),
        )
    }

    private fun patchCentralCompressedSize(bytes: ByteArray, target: String, sizeBytes: Int): ByteArray {
        val patched = bytes.copyOf()
        var found = false
        for (offset in 0..patched.size - 46) {
            if (patched[offset] != 0x50.toByte() || patched[offset + 1] != 0x4b.toByte() ||
                patched[offset + 2] != 0x01.toByte() || patched[offset + 3] != 0x02.toByte()
            ) continue
            val nameLength = readUnsignedShortLe(patched, offset + 28)
            val name = String(patched, offset + 46, nameLength, Charsets.UTF_8)
            if (name != target) continue
            repeat(4) { index -> patched[offset + 20 + index] = (sizeBytes ushr (index * 8)).toByte() }
            found = true
            break
        }
        require(found) { "Test fixture did not contain the requested ZIP member." }
        return patched
    }

    private fun patchCentralMethod(bytes: ByteArray, target: String, method: Int): ByteArray {
        val patched = bytes.copyOf()
        var found = false
        for (offset in 0..patched.size - 46) {
            if (patched[offset] != 0x50.toByte() || patched[offset + 1] != 0x4b.toByte() ||
                patched[offset + 2] != 0x01.toByte() || patched[offset + 3] != 0x02.toByte()
            ) continue
            val nameLength = readUnsignedShortLe(patched, offset + 28)
            val name = String(patched, offset + 46, nameLength, Charsets.UTF_8)
            if (name != target) continue
            writeUnsignedShortLe(patched, offset + 10, method)
            found = true
            break
        }
        require(found) { "Test fixture did not contain the requested ZIP member." }
        return patched
    }

    private fun rewriteDeflatedEntrySizes(
        bytes: ByteArray,
        target: String,
        compressedSize: Long? = null,
        uncompressedSize: Long? = null,
    ): ByteArray {
        val targetRecord = centralDirectoryEntryRecord(bytes, target)
        require(readUnsignedShortLe(targetRecord, 8) and 0x8 != 0)
        require(readUnsignedShortLe(targetRecord, 10) == ZipEntry.DEFLATED)
        val originalCompressedSize = readUnsignedIntLe(targetRecord, 20)
        val originalUncompressedSize = readUnsignedIntLe(targetRecord, 24)
        val rewrittenCompressedSize = compressedSize ?: originalCompressedSize
        val rewrittenUncompressedSize = uncompressedSize ?: originalUncompressedSize
        require(originalCompressedSize <= Int.MAX_VALUE && rewrittenCompressedSize in originalCompressedSize..Int.MAX_VALUE.toLong())
        require(originalUncompressedSize <= Int.MAX_VALUE && rewrittenUncompressedSize in 0..Int.MAX_VALUE.toLong())

        val localHeaderOffset = readUnsignedIntLe(targetRecord, 42).toInt()
        val dataOffset = localHeaderOffset + 30 + readUnsignedShortLe(bytes, localHeaderOffset + 26) +
            readUnsignedShortLe(bytes, localHeaderOffset + 28)
        val oldDescriptorOffset = dataOffset + originalCompressedSize.toInt()
        require(oldDescriptorOffset <= bytes.size - 16)
        require(readUnsignedIntLe(bytes, oldDescriptorOffset) == 0x08074b50L)
        val descriptorCrcOffset = oldDescriptorOffset + 4
        require(readUnsignedIntLe(bytes, descriptorCrcOffset) == readUnsignedIntLe(targetRecord, 16))
        require(readUnsignedIntLe(bytes, descriptorCrcOffset + 4) == originalCompressedSize)
        require(readUnsignedIntLe(bytes, descriptorCrcOffset + 8) == originalUncompressedSize)
        val oldEntryEnd = oldDescriptorOffset + 16
        val addedCompressedBytes = (rewrittenCompressedSize - originalCompressedSize).toInt()
        val rewritten = ByteArray(bytes.size + addedCompressedBytes)
        bytes.copyInto(rewritten, endIndex = oldDescriptorOffset)
        if (addedCompressedBytes > 0) rewritten.fill(0, oldDescriptorOffset, oldDescriptorOffset + addedCompressedBytes)
        bytes.copyInto(
            rewritten,
            destinationOffset = oldDescriptorOffset + addedCompressedBytes,
            startIndex = oldDescriptorOffset,
        )

        val newDirectoryOffset = centralDirectoryOffset(bytes) + addedCompressedBytes
        val eocdOffset = rewritten.size - 22
        require(readUnsignedIntLe(rewritten, eocdOffset) == 0x06054b50L)
        writeUnsignedIntLe(rewritten, eocdOffset + 16, newDirectoryOffset)
        val directorySize = readUnsignedIntLe(rewritten, eocdOffset + 12).toInt()
        val directoryEnd = newDirectoryOffset + directorySize
        require(directoryEnd == eocdOffset)

        val newDescriptorOffset = oldDescriptorOffset + addedCompressedBytes
        writeUnsignedIntLe(rewritten, newDescriptorOffset + 8, rewrittenCompressedSize.toInt())
        writeUnsignedIntLe(rewritten, newDescriptorOffset + 12, rewrittenUncompressedSize.toInt())
        var cursor = newDirectoryOffset
        while (cursor < directoryEnd) {
            require(readUnsignedIntLe(rewritten, cursor) == 0x02014b50L)
            val nameLength = readUnsignedShortLe(rewritten, cursor + 28)
            val extraLength = readUnsignedShortLe(rewritten, cursor + 30)
            val commentLength = readUnsignedShortLe(rewritten, cursor + 32)
            val recordLength = 46 + nameLength + extraLength + commentLength
            require(cursor <= directoryEnd - recordLength)
            val entryName = String(rewritten, cursor + 46, nameLength, Charsets.UTF_8)
            val entryLocalHeaderOffset = readUnsignedIntLe(rewritten, cursor + 42)
            if (entryLocalHeaderOffset >= oldEntryEnd.toLong()) {
                writeUnsignedIntLe(rewritten, cursor + 42, (entryLocalHeaderOffset + addedCompressedBytes).toInt())
            }
            if (entryName == target) {
                writeUnsignedIntLe(rewritten, cursor + 20, rewrittenCompressedSize.toInt())
                writeUnsignedIntLe(rewritten, cursor + 24, rewrittenUncompressedSize.toInt())
            }
            cursor += recordLength
        }
        require(cursor == directoryEnd)
        return rewritten
    }

    private fun patchLocalHeaderMetadata(
        bytes: ByteArray,
        target: String,
        crc: Long? = null,
        compressedSize: Long? = null,
        uncompressedSize: Long? = null,
    ): ByteArray {
        val patched = bytes.copyOf()
        val localHeaderOffset = localHeaderOffsetForEntry(bytes, target)
        crc?.let { writeUnsignedIntLe(patched, localHeaderOffset + 14, it.toInt()) }
        compressedSize?.let { writeUnsignedIntLe(patched, localHeaderOffset + 18, it.toInt()) }
        uncompressedSize?.let { writeUnsignedIntLe(patched, localHeaderOffset + 22, it.toInt()) }
        return patched
    }

    private fun readUnsignedShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readUnsignedIntLe(bytes: ByteArray, offset: Int): Long =
        readUnsignedShortLe(bytes, offset).toLong() or (readUnsignedShortLe(bytes, offset + 2).toLong() shl 16)

    private fun firstLocalEntryDataOffset(bytes: ByteArray): Int {
        require(readUnsignedIntLe(bytes, 0) == 0x04034b50L)
        return 30 + readUnsignedShortLe(bytes, 26) + readUnsignedShortLe(bytes, 28)
    }

    private fun centralDirectoryOffset(bytes: ByteArray): Int {
        val eocdOffset = bytes.size - 22
        require(readUnsignedIntLe(bytes, eocdOffset) == 0x06054b50L)
        require(readUnsignedShortLe(bytes, eocdOffset + 20) == 0)
        return readUnsignedIntLe(bytes, eocdOffset + 16).toInt()
    }

    private fun centralDirectoryEntryRecord(bytes: ByteArray, target: String): ByteArray {
        val eocdOffset = bytes.size - 22
        val directoryOffset = centralDirectoryOffset(bytes)
        val directoryEnd = directoryOffset + readUnsignedIntLe(bytes, eocdOffset + 12).toInt()
        var cursor = directoryOffset
        while (cursor <= directoryEnd - 46) {
            require(readUnsignedIntLe(bytes, cursor) == 0x02014b50L)
            val nameLength = readUnsignedShortLe(bytes, cursor + 28)
            val extraLength = readUnsignedShortLe(bytes, cursor + 30)
            val commentLength = readUnsignedShortLe(bytes, cursor + 32)
            val recordLength = 46 + nameLength + extraLength + commentLength
            require(cursor <= directoryEnd - recordLength)
            val name = String(bytes, cursor + 46, nameLength, Charsets.UTF_8)
            if (name == target) return bytes.copyOfRange(cursor, cursor + recordLength)
            cursor += recordLength
        }
        throw IllegalArgumentException("Test ZIP did not contain the requested central-directory entry.")
    }

    private fun localDataOffsetForEntry(bytes: ByteArray, target: String): Int {
        val localHeaderOffset = localHeaderOffsetForEntry(bytes, target)
        require(readUnsignedIntLe(bytes, localHeaderOffset) == 0x04034b50L)
        return localHeaderOffset + 30 + readUnsignedShortLe(bytes, localHeaderOffset + 26) +
            readUnsignedShortLe(bytes, localHeaderOffset + 28)
    }

    private fun localHeaderOffsetForEntry(bytes: ByteArray, target: String): Int =
        readUnsignedIntLe(centralDirectoryEntryRecord(bytes, target), 42).toInt()

    private fun appendCentralDirectoryEntry(bytes: ByteArray, centralRecord: ByteArray): ByteArray {
        val eocdOffset = bytes.size - 22
        val directoryOffset = centralDirectoryOffset(bytes)
        val directorySize = readUnsignedIntLe(bytes, eocdOffset + 12).toInt()
        require(directoryOffset + directorySize == eocdOffset)
        val updated = ByteArray(bytes.size + centralRecord.size)
        bytes.copyInto(updated, endIndex = eocdOffset)
        centralRecord.copyInto(updated, destinationOffset = eocdOffset)
        bytes.copyInto(updated, destinationOffset = eocdOffset + centralRecord.size, startIndex = eocdOffset)
        val updatedEocdOffset = eocdOffset + centralRecord.size
        writeUnsignedShortLe(updated, updatedEocdOffset + 8, readUnsignedShortLe(bytes, eocdOffset + 8) + 1)
        writeUnsignedShortLe(updated, updatedEocdOffset + 10, readUnsignedShortLe(bytes, eocdOffset + 10) + 1)
        writeUnsignedIntLe(updated, updatedEocdOffset + 12, directorySize + centralRecord.size)
        return updated
    }

    private fun writeUnsignedShortLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeUnsignedIntLe(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun fixture(root: File): Fixture {
        val audioDirectory = File(root, "audio").apply { mkdir() }
        val audioBytes = byteArrayOf(1, 3, 5, 7, 9, 11)
        val artworkBytes = byteArrayOf(2, 4, 6)
        val audio = File(audioDirectory, "track-1.mp3").apply { writeBytes(audioBytes) }
        val artwork = File(audioDirectory, "track-1.img").apply { writeBytes(artworkBytes) }
        return Fixture(
            audioDirectory = audioDirectory,
            audioBytes = audioBytes,
            artworkBytes = artworkBytes,
            snapshot = LibrarySnapshot(
                tracks = listOf(
                    Track("track-1", "Тёплый вечер", "Исполнитель", "Альбом", 61_000, audio.canonicalPath, artwork.canonicalPath),
                ),
                playlists = listOf(Playlist("playlist-1", "Вечер", listOf("track-1"))),
                favoriteTrackIds = setOf("track-1"),
            ),
        )
    }

    private fun readEntries(file: File): List<Pair<String, ByteArray>> = ZipFile(file).use { zip ->
        Collections.list(zip.entries()).map { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
    }

    private fun rawZip(entries: List<RawEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        val central = mutableListOf<CentralEntry>()
        entries.forEach { entry ->
            val name = entry.name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(entry.bytes) }.value xor if (entry.corruptCrc) 1L else 0L
            val localZip64CompressedSize = entry.localZip64CompressedSize
            val localZip64UncompressedSize = entry.localZip64UncompressedSize
            val usesLocalZip64 = localZip64CompressedSize != null || localZip64UncompressedSize != null
            val localZip64Extra = if (!usesLocalZip64) {
                byteArrayOf()
            } else {
                val extraSize = (if (localZip64UncompressedSize != null) 8 else 0) +
                    (if (localZip64CompressedSize != null) 8 else 0)
                ByteArrayOutputStream().also { extra ->
                    DataOutputStream(extra).apply {
                        writeShortLe(1); writeShortLe(extraSize)
                        localZip64UncompressedSize?.let { writeLongLe(it) }
                        localZip64CompressedSize?.let { writeLongLe(it) }
                    }
                }.toByteArray()
            }
            val offset = output.size()
            DataOutputStream(output).apply {
                writeIntLe(0x04034b50)
                writeShortLe(if (usesLocalZip64) 45 else 20); writeShortLe(0); writeShortLe(0); writeShortLe(0); writeShortLe(0)
                writeIntLe(crc.toInt())
                writeIntLe(if (localZip64CompressedSize != null) -1 else entry.bytes.size)
                writeIntLe(if (localZip64UncompressedSize != null) -1 else entry.bytes.size)
                writeShortLe(name.size); writeShortLe(localZip64Extra.size); write(name); write(localZip64Extra); write(entry.bytes)
            }
            central += CentralEntry(name, crc, entry.bytes.size, offset, usesLocalZip64)
        }
        val centralOffset = output.size()
        central.forEach { item ->
            DataOutputStream(output).apply {
                writeIntLe(0x02014b50)
                writeShortLe(20); writeShortLe(if (item.usesLocalZip64) 45 else 20)
                writeShortLe(0); writeShortLe(0); writeShortLe(0); writeShortLe(0)
                writeIntLe(item.crc.toInt()); writeIntLe(item.size); writeIntLe(item.size)
                writeShortLe(item.name.size); writeShortLe(0); writeShortLe(0); writeShortLe(0); writeShortLe(0)
                writeIntLe(0); writeIntLe(item.offset); write(item.name)
            }
        }
        val centralSize = output.size() - centralOffset
        DataOutputStream(output).apply {
            writeIntLe(0x06054b50); writeShortLe(0); writeShortLe(0)
            writeShortLe(central.size); writeShortLe(central.size)
            writeIntLe(centralSize); writeIntLe(centralOffset); writeShortLe(0)
        }
        return output.toByteArray()
    }

    private fun zip64DescriptorBackupBytes(
        source: Fixture,
        descriptorUsesZip64Sizes: Boolean,
    ): ByteArray {
        val audioName = "audio/track-1.mp3"
        val entries = validBackupEntries(source, RawEntry(audioName, source.audioBytes))
        val output = ByteArrayOutputStream()
        val centralRecords = mutableListOf<ByteArray>()

        entries.forEach { entry ->
            val usesZip64Sizes = entry.name == audioName
            val flags = if (usesZip64Sizes) 0x8 else 0
            val method = if (usesZip64Sizes) ZipEntry.DEFLATED else ZipEntry.STORED
            val name = entry.name.toByteArray(Charsets.UTF_8)
            val compressedBytes = if (usesZip64Sizes) rawDeflate(entry.bytes) else entry.bytes
            val crc = CRC32().apply { update(entry.bytes) }.value
            val localHeaderOffset = output.size()

            DataOutputStream(output).apply {
                writeIntLe(0x04034b50)
                writeShortLe(if (usesZip64Sizes) 45 else 20); writeShortLe(flags); writeShortLe(method)
                writeShortLe(0); writeShortLe(0)
                writeIntLe(if (usesZip64Sizes) 0 else crc.toInt())
                writeIntLe(if (usesZip64Sizes) 0 else compressedBytes.size)
                writeIntLe(if (usesZip64Sizes) 0 else entry.bytes.size)
                writeShortLe(name.size); writeShortLe(0); write(name); write(compressedBytes)
                if (usesZip64Sizes) {
                    writeIntLe(0x08074b50)
                    writeIntLe(crc.toInt())
                    if (descriptorUsesZip64Sizes) {
                        writeLongLe(compressedBytes.size.toLong())
                        writeLongLe(entry.bytes.size.toLong())
                    } else {
                        writeIntLe(compressedBytes.size)
                        writeIntLe(entry.bytes.size)
                    }
                }
            }

            val extra = if (usesZip64Sizes) {
                ByteArrayOutputStream().also { extraBytes ->
                    DataOutputStream(extraBytes).apply {
                        writeShortLe(1); writeShortLe(16)
                        writeLongLe(entry.bytes.size.toLong())
                        writeLongLe(compressedBytes.size.toLong())
                    }
                }.toByteArray()
            } else {
                byteArrayOf()
            }
            centralRecords += ByteArrayOutputStream().also { recordBytes ->
                DataOutputStream(recordBytes).apply {
                    writeIntLe(0x02014b50)
                    writeShortLe(if (usesZip64Sizes) 45 else 20); writeShortLe(if (usesZip64Sizes) 45 else 20)
                    writeShortLe(flags); writeShortLe(method); writeShortLe(0); writeShortLe(0)
                    writeIntLe(crc.toInt())
                    writeIntLe(if (usesZip64Sizes) -1 else compressedBytes.size)
                    writeIntLe(if (usesZip64Sizes) -1 else entry.bytes.size)
                    writeShortLe(name.size); writeShortLe(extra.size); writeShortLe(0); writeShortLe(0)
                    writeShortLe(0); writeIntLe(0); writeIntLe(localHeaderOffset)
                    write(name); write(extra)
                }
            }.toByteArray()
        }

        val centralOffset = output.size()
        centralRecords.forEach { output.write(it) }
        val centralSize = output.size() - centralOffset
        DataOutputStream(output).apply {
            writeIntLe(0x06054b50); writeShortLe(0); writeShortLe(0)
            writeShortLe(centralRecords.size); writeShortLe(centralRecords.size)
            writeIntLe(centralSize); writeIntLe(centralOffset); writeShortLe(0)
        }
        return output.toByteArray()
    }

    private fun rawDeflate(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            DeflaterOutputStream(output, deflater).use { it.write(bytes) }
        } finally {
            deflater.end()
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeShortLe(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
    }

    private fun DataOutputStream.writeIntLe(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
        writeByte((value ushr 16) and 0xff)
        writeByte((value ushr 24) and 0xff)
    }

    private fun DataOutputStream.writeLongLe(value: Long) {
        repeat(8) { index -> writeByte(((value ushr (index * 8)) and 0xff).toInt()) }
    }

    private fun classicEocd(entryCount: Int, centralSize: Int, centralOffset: Int): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeIntLe(0x06054b50)
                writeShortLe(0); writeShortLe(0)
                writeShortLe(entryCount); writeShortLe(entryCount)
                writeIntLe(centralSize); writeIntLe(centralOffset); writeShortLe(0)
            }
        }.toByteArray()

    private fun zip64Eocd(totalEntries: Long): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeIntLe(0x06064b50); writeLongLe(44)
            writeShortLe(45); writeShortLe(45); writeIntLe(0); writeIntLe(0)
            writeLongLe(totalEntries); writeLongLe(totalEntries)
            writeLongLe(0); writeLongLe(0)
            writeIntLe(0x07064b50); writeIntLe(0); writeLongLe(0); writeIntLe(1)
            writeIntLe(0x06054b50); writeShortLe(0); writeShortLe(0)
            writeShortLe(0xffff); writeShortLe(0xffff)
            writeIntLe(-1); writeIntLe(-1); writeShortLe(0)
        }
    }.toByteArray()

    private data class Fixture(
        val audioDirectory: File,
        val audioBytes: ByteArray,
        val artworkBytes: ByteArray,
        val snapshot: LibrarySnapshot,
    )

    private data class RawEntry(
        val name: String,
        val bytes: ByteArray,
        val corruptCrc: Boolean = false,
        val localZip64CompressedSize: Long? = null,
        val localZip64UncompressedSize: Long? = null,
    )

    private data class CentralEntry(
        val name: ByteArray,
        val crc: Long,
        val size: Int,
        val offset: Int,
        val usesLocalZip64: Boolean,
    )

    private class CountingZipEntries(private val totalEntries: Int) : Enumeration<ZipEntry> {
        var consumed = 0
            private set

        override fun hasMoreElements(): Boolean = consumed < totalEntries

        override fun nextElement(): ZipEntry = ZipEntry("entry-${consumed++}")
    }
}
