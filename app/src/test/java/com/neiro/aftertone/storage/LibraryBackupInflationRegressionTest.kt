package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.Track
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupInflationRegressionTest {
    @Test
    fun metadataInspectionLeavesHighRatioRejectionToExtraction() {
        val root = Files.createTempDirectory("aftertone-inspect-without-inflate-").toFile()
        try {
            val archive = createUntrustedDeflatedArchive(root, ByteArray(512 * 1024))

            val inspected = LibraryBackupArchive.inspect(archive)

            assertEquals(1, inspected.trackCount)
            assertEquals(512 * 1024L, inspected.payloadBytes)
            val staging = File(root, "stage").apply { assertTrue(mkdir()) }

            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })
            }
            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsHighCompressionPrefixWhenCompleteEntryRatioIsSafe() {
        val root = Files.createTempDirectory("aftertone-safe-prefix-ratio-").toFile()
        try {
            val audioBytes = ByteArray(64 * 1024 + 256 * 1024)
            ByteArray(256 * 1024).also { Random(2028).nextBytes(it) }.copyInto(audioBytes, 64 * 1024)
            val archive = createUntrustedDeflatedArchive(root, audioBytes)
            val staging = File(root, "stage").apply { assertTrue(mkdir()) }

            val restored = LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })

            assertEquals(1, restored.summary.trackCount)
            assertTrue(File(staging, "track-1.mp3").readBytes().contentEquals(audioBytes))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsOneMiBCompressiblePrefixWhenWholeEntryRatioIsSafe() {
        val root = Files.createTempDirectory("aftertone-long-safe-prefix-ratio-").toFile()
        try {
            val prefixBytes = 1024 * 1024
            val tailBytes = 2 * 1024 * 1024
            val audioBytes = ByteArray(prefixBytes + tailBytes)
            ByteArray(tailBytes).also { Random(2031).nextBytes(it) }.copyInto(audioBytes, prefixBytes)
            val archive = createUntrustedDeflatedArchive(root, audioBytes)
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry("audio/track-1.mp3")
                assertTrue(audioBytes.size.toLong() <= entry.compressedSize * 200L)
            }
            val staging = File(root, "stage").apply { assertTrue(mkdir()) }

            val restored = LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })

            assertEquals(1, restored.summary.trackCount)
            assertArrayEquals(audioBytes, File(staging, "track-1.mp3").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportedSilentPcmWavRestoresWithinTheExistingCompressionRatioLimit() {
        val root = Files.createTempDirectory("aftertone-pcm-wav-roundtrip-").toFile()
        try {
            val audioBytes = silentPcmWav8Seconds()
            val audioDirectory = File(root, "source-audio").apply { assertTrue(mkdir()) }
            val audio = File(audioDirectory, "track-1.wav").apply { writeBytes(audioBytes) }
            val snapshot = LibrarySnapshot(
                tracks = listOf(
                    Track("track-1", "Silent PCM", "Test Artist", durationMs = 8_000, filePath = audio.canonicalPath),
                ),
            )
            val archive = File(root, "wav-backup.zip")
            archive.outputStream().use { LibraryBackupArchive.export(snapshot, audioDirectory, it) }
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry("audio/track-1.wav")
                assertTrue("WAV output must remain within the restore 200:1 cap.", audioBytes.size.toLong() <= entry.compressedSize * 200L)
            }
            val staging = File(root, "stage").apply { assertTrue(mkdir()) }

            val restored = LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })

            assertEquals(1, restored.summary.trackCount)
            assertArrayEquals(audioBytes, File(staging, "track-1.wav").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportedLegacyPcmWavUnderMp3NameRestoresWithinExistingCompressionRatioLimit() {
        val root = Files.createTempDirectory("aftertone-legacy-mislabelled-wav-roundtrip-").toFile()
        try {
            val audioBytes = silentPcmWav8Seconds()
            val audioDirectory = File(root, "source-audio").apply { assertTrue(mkdir()) }
            val audio = File(audioDirectory, "legacy-track.mp3").apply { writeBytes(audioBytes) }
            val snapshot = LibrarySnapshot(
                tracks = listOf(
                    Track("legacy-track", "Legacy WAVE", "Test Artist", durationMs = 8_000, filePath = audio.canonicalPath),
                ),
            )
            val archive = File(root, "legacy-wav-under-mp3-name.zip")
            archive.outputStream().use { LibraryBackupArchive.export(snapshot, audioDirectory, it) }
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry("audio/legacy-track.mp3")
                assertTrue(
                    "Legacy WAVE bytes must remain within the restore 200:1 cap regardless of filename.",
                    audioBytes.size.toLong() <= entry.compressedSize * 200L,
                )
            }
            val staging = File(root, "stage").apply { assertTrue(mkdir()) }

            val restored = LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })

            assertEquals(1, restored.summary.trackCount)
            assertArrayEquals(audioBytes, File(staging, "legacy-track.mp3").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun prefixAllowanceDoesNotRelaxTheFinalWholeEntryRatio() {
        val root = Files.createTempDirectory("aftertone-strict-final-ratio-").toFile()
        try {
            val audioBytes = ByteArray(64 * 1024)
            val archive = createUntrustedDeflatedArchive(root, audioBytes)
            val staging = File(root, "stage").apply { assertTrue(mkdir()) }

            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })
            }

            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun highRatioArchiveIsRejectedBeforeItsFullAssetIsInflated() {
        val root = Files.createTempDirectory("aftertone-bounded-inflate-").toFile()
        try {
            val archive = createUntrustedDeflatedArchive(root, ByteArray(32 * 1024 * 1024))
            val staging = File(root, "stage").apply { assertTrue(mkdir()) }
            var cancellationChecks = 0

            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.extract(
                    archive,
                    staging,
                    availableBytes = { Long.MAX_VALUE },
                    checkCancelled = {
                        cancellationChecks++
                        if (cancellationChecks > 300) throw CancellationException("Inflater exceeded the test work budget.")
                    },
                )
            }

            assertTrue("The bomb must be rejected well before the full 32 MiB payload is inflated.", cancellationChecks < 300)
            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completeStageInspectAndExtractFlowUsesOneFullAssetInflatePass() {
        val root = Files.createTempDirectory("aftertone-single-inflate-work-").toFile()
        try {
            val audioBytes = ByteArray(8 * 1024 * 1024).also { Random(2029).nextBytes(it) }
            val archive = createArchive(root, audioBytes)
            val stagedArchive = File(root, "prepared.zip")
            val staging = File(root, "stage").apply { assertTrue(mkdir()) }
            var cancellationChecks = 0
            val checkCancelled: () -> Unit = { cancellationChecks += 1 }

            LibraryBackupArchive.stageAndInspectArchive(
                input = archive.inputStream(),
                destination = stagedArchive,
                availableBytes = { Long.MAX_VALUE },
                checkCancelled = checkCancelled,
            )
            LibraryBackupArchive.inspect(stagedArchive, checkCancelled = checkCancelled)
            val restored = LibraryBackupArchive.extract(
                stagedArchive,
                staging,
                availableBytes = { Long.MAX_VALUE },
                checkCancelled = checkCancelled,
            )

            assertEquals(1, restored.summary.trackCount)
            assertTrue(File(staging, "track-1.mp3").readBytes().contentEquals(audioBytes))
            assertTrue(
                "Compressed staging plus metadata inspection plus one asset inflate must stay under the repeated-pass budget; checks=$cancellationChecks",
                cancellationChecks < 750,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun silentPcmWav8Seconds(): ByteArray {
        val samplesBytes = 44_100 * 2 * 2 * 8
        return ByteArray(44 + samplesBytes).apply {
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(this, 0)
            writeLittleEndianInt(4, size - 8)
            "WAVEfmt ".toByteArray(Charsets.US_ASCII).copyInto(this, 8)
            writeLittleEndianInt(16, 16)
            writeLittleEndianShort(20, 1)
            writeLittleEndianShort(22, 2)
            writeLittleEndianInt(24, 44_100)
            writeLittleEndianInt(28, 44_100 * 2 * 2 * 2)
            writeLittleEndianShort(32, 4)
            writeLittleEndianShort(34, 16)
            "data".toByteArray(Charsets.US_ASCII).copyInto(this, 36)
            writeLittleEndianInt(40, samplesBytes)
        }
    }

    private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
        repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun ByteArray.writeLittleEndianShort(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun createArchive(root: File, audioBytes: ByteArray): File {
        val audioDirectory = File(root, "source-audio").apply { assertTrue(mkdir()) }
        val audio = File(audioDirectory, "track-1.mp3").apply { writeBytes(audioBytes) }
        val snapshot = LibrarySnapshot(
            tracks = listOf(Track("track-1", "Test track", "Test artist", "Test album", 1_000, audio.canonicalPath, null)),
        )
        val archive = File(root, "backup.zip")
        archive.outputStream().use { output -> LibraryBackupArchive.export(snapshot, audioDirectory, output) }
        return archive
    }

    private fun createUntrustedDeflatedArchive(root: File, audioBytes: ByteArray): File {
        val entryName = "audio/track-1.mp3"
        val snapshot = LibrarySnapshot(
            tracks = listOf(Track("track-1", "Untrusted archive", "Test artist", durationMs = 1_000, filePath = entryName)),
        )
        val manifest = LibraryBackupManifestCodec.encode(
            LibraryStateCodec.encode(snapshot),
            listOf(LibraryBackupAsset(entryName, audioBytes.size.toLong())),
        )
        val archive = File(root, "untrusted-deflated.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(LibraryBackupArchive.MANIFEST_ENTRY))
            zip.write(manifest)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(audioBytes)
            zip.closeEntry()
        }
        return archive
    }
}
