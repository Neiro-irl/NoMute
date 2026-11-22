package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.PlaylistArtworkFileName
import com.neiro.aftertone.model.TrackArtworkFileName
import com.neiro.aftertone.model.TrackFileName
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Enumeration
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class LibraryBackupLimits(
    val maxCompressedBytes: Long = 16L * 1024 * 1024 * 1024,
    val maxTotalPayloadBytes: Long = 16L * 1024 * 1024 * 1024,
    val maxAudioFileBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxArtworkBytes: Long = 2L * 1024 * 1024,
    val maxManifestBytes: Int = 20 * 1024 * 1024,
    val maxAssetCompressionRatio: Long = 200,
    val maxEntries: Int = 40_001,
    val reservedSpaceBytes: Long = 32L * 1024 * 1024,
    val spaceCheckIntervalBytes: Long = 512 * 1024,
) {
    init {
        require(maxCompressedBytes > 0 && maxTotalPayloadBytes > 0)
        require(maxAudioFileBytes > 0 && maxArtworkBytes > 0 && maxManifestBytes > 0 && maxAssetCompressionRatio > 0)
        require(maxEntries >= 1 && reservedSpaceBytes >= 0 && spaceCheckIntervalBytes > 0)
    }

    companion object {
        val DEFAULT = LibraryBackupLimits()
    }
}

internal data class BackupSummary(
    val trackCount: Int,
    val playlistCount: Int,
    val favoriteCount: Int,
    val payloadBytes: Long,
)

internal data class BackupProgress(val completedBytes: Long, val totalBytes: Long?)

internal data class PreparedLibraryBackup(
    val snapshot: LibrarySnapshot,
    val summary: BackupSummary,
    val assetSha256ByEntry: Map<String, String> = emptyMap(),
)

internal object LibraryBackupArchive {
    const val MANIFEST_ENTRY = "manifest.bin"
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val MAX_TRANSIENT_EXPANSION_PREFIX_BYTES = 1024L * 1024L
    private const val MAX_ENTRY_NAME_CHARS = 128
    private const val MAX_CENTRAL_DIRECTORY_BYTES = 12L * 1024 * 1024
    private const val MAX_EOCD_SEARCH_BYTES = 22 + 0xffff
    private const val MAX_ZIP64_EOCD_BYTES = 4 * 1024
    private const val MAX_CENTRAL_EXTRA_BYTES = 4 * 1024
    private const val MAX_CENTRAL_COMMENT_BYTES = 1024
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50L
    private const val DATA_DESCRIPTOR_FLAG = 0x0008
    private const val ZIP64_EXTRA_FIELD_ID = 0x0001
    private const val UINT32_MAX = 0xffff_ffffL

    fun export(
        snapshot: LibrarySnapshot,
        audioDirectory: File,
        output: OutputStream,
        onProgress: (BackupProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
    ): BackupSummary {
        require(audioDirectory.isDirectory) { "The local audio directory is not available." }
        val assets = mutableListOf<LibraryBackupAsset>()
        val assetSources = linkedMapOf<String, File>()
        val portableTracks = snapshot.tracks.map { track ->
            checkCancelled()
            require(TrackFileName.isGeneratedFileName(track.id, File(track.filePath).name)) {
                "The local library contains an invalid audio file name."
            }
            val audioFile = File(track.filePath)
            require(isOwnedAudioFile(audioDirectory, audioFile) && audioFile.isFile) {
                "An imported audio copy is missing or outside the app's private storage."
            }
            require(audioFile.length() in 1..limits.maxAudioFileBytes) {
                "An audio file exceeds the backup size limit."
            }
            val artworkFile = track.artworkPath?.let { path ->
                File(path).also { file ->
                    require(TrackArtworkFileName.isGeneratedFileName(track.id, file.name) && isOwnedAudioFile(audioDirectory, file) && file.isFile) {
                        "A track's artwork is missing or outside the app's private storage."
                    }
                    require(file.length() in 1..limits.maxArtworkBytes) { "An artwork file exceeds the backup size limit." }
                }
            }
            val audioEntry = "audio/${audioFile.name}"
            assets += LibraryBackupAsset(audioEntry, audioFile.length())
            assetSources[audioEntry] = audioFile
            artworkFile?.let { file ->
                val artworkEntry = "artwork/${track.id}.img"
                assets += LibraryBackupAsset(artworkEntry, file.length())
                assetSources[artworkEntry] = file
            }
            track.copy(
                filePath = "audio/${audioFile.name}",
                artworkPath = artworkFile?.let { "artwork/${track.id}.img" },
            )
        }
        val portablePlaylists = snapshot.playlists.map { playlist ->
            checkCancelled()
            val artworkFile = playlist.artworkPath?.let { path ->
                File(path).also { file ->
                    require(
                        PlaylistArtworkFileName.isGeneratedFileName(playlist.id, file.name) &&
                            isOwnedAudioFile(audioDirectory, file) &&
                            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS),
                    ) {
                        "A playlist's artwork is missing or outside the app's private storage."
                    }
                    require(file.length() in 1..limits.maxArtworkBytes) {
                        "A playlist artwork file exceeds the backup size limit."
                    }
                }
            }
            artworkFile?.let { file ->
                val artworkEntry = "playlist-artwork/${playlist.id}.img"
                assets += LibraryBackupAsset(artworkEntry, file.length())
                assetSources[artworkEntry] = file
            }
            playlist.copy(artworkPath = artworkFile?.let { "playlist-artwork/${playlist.id}.img" })
        }
        val portableSnapshot = snapshot.copy(tracks = portableTracks, playlists = portablePlaylists)
        val encodedState = LibraryStateCodec.encode(portableSnapshot)
        val manifest = LibraryBackupManifestCodec.encode(encodedState, assets, limits)
        require(assets.size + 1 <= limits.maxEntries) { "The backup contains too many files." }
        var payloadBytes = manifest.size.toLong()
        assets.forEach { asset -> payloadBytes = safeAdd(payloadBytes, asset.sizeBytes) }
        require(payloadBytes <= limits.maxTotalPayloadBytes) { "The backup payload exceeds the supported size." }
        val totalAssetBytes = assets.sumOf(LibraryBackupAsset::sizeBytes)
        val compressedOutput = BoundedNonClosingOutputStream(output, limits.maxCompressedBytes)
        var copiedBytes = 0L
        ZipOutputStream(compressedOutput).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { time = 0L })
            zip.write(manifest)
            zip.closeEntry()
            assets.forEach { asset ->
                checkCancelled()
                val file = assetSources[asset.entryName]
                    ?: throw IllegalArgumentException("The backup asset is not referenced by the library.")
                require(file.length() == asset.sizeBytes) { "A local backup file changed while it was being read." }
                // A legacy import can contain PCM/WAVE bytes under a provider-derived .mp3 name.
                zip.setLevel(if (asset.entryName.startsWith("audio/")) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(asset.entryName).apply { time = 0L })
                FileInputStream(file).use { input ->
                    val copied = copyExact(input, zip, asset.sizeBytes, limits.maxTotalPayloadBytes, checkCancelled) { count ->
                        copiedBytes += count
                        onProgress(BackupProgress(copiedBytes, totalAssetBytes))
                    }
                    require(copied == asset.sizeBytes && file.length() == asset.sizeBytes) {
                        "A local backup file changed while it was being read."
                    }
                }
                zip.closeEntry()
            }
            zip.finish()
        }
        return summary(portableSnapshot, totalAssetBytes)
    }

    fun stageArchive(
        input: InputStream,
        destination: File,
        availableBytes: () -> Long,
        checkCancelled: () -> Unit = {},
        onProgress: (BackupProgress) -> Unit = {},
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
    ): File {
        val parent = destination.parentFile ?: throw IllegalArgumentException("The archive staging path is invalid.")
        if (!parent.isDirectory || !PrivateAudioPath.isDirectChild(parent, destination) || destination.exists()) {
            throw SecurityException("The archive staging path is not a new private file.")
        }
        require(destination.createNewFile()) { "Could not create a private temporary archive." }
        var complete = false
        try {
            FileOutputStream(destination).use { output ->
                stageArchiveToStream(input, output, availableBytes, checkCancelled, onProgress, limits)
                output.fd.sync()
            }
            complete = true
            return destination
        } finally {
            if (!complete && destination.isFile && PrivateAudioPath.isDirectChild(parent, destination)) {
                destination.delete()
            }
        }
    }

    fun stageArchiveToStream(
        input: InputStream,
        output: OutputStream,
        availableBytes: () -> Long,
        checkCancelled: () -> Unit = {},
        onProgress: (BackupProgress) -> Unit = {},
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
    ): Long {
        var copied = 0L
        var nextSpaceCheck = 0L
        input.use { source ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val count = source.read(buffer)
                if (count < 0) break
                require(count > 0 && copied <= limits.maxCompressedBytes - count) {
                    "The selected archive exceeds the compressed-size limit."
                }
                if (copied >= nextSpaceCheck) {
                    if (availableBytes() < safeAdd(count.toLong(), limits.reservedSpaceBytes)) {
                        throw IllegalStateException("There is not enough free space to stage this archive safely.")
                    }
                    nextSpaceCheck = copied + limits.spaceCheckIntervalBytes
                }
                output.write(buffer, 0, count)
                copied += count
                onProgress(BackupProgress(copied, null))
            }
        }
        output.flush()
        require(copied > 0L) { "The selected archive is empty." }
        return copied
    }

    fun stageAndInspectArchive(
        input: InputStream,
        destination: File,
        availableBytes: () -> Long,
        checkCancelled: () -> Unit = {},
        onProgress: (BackupProgress) -> Unit = {},
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
    ): BackupSummary {
        var staged = false
        var complete = false
        try {
            stageArchive(input, destination, availableBytes, checkCancelled, onProgress, limits)
            staged = true
            checkCancelled()
            val inspected = inspect(destination, limits, checkCancelled)
            checkCancelled()
            complete = true
            return inspected
        } finally {
            if (staged && !complete) {
                val parent = destination.parentFile
                if (parent != null && PrivateAudioPath.isDirectChild(parent, destination)) destination.delete()
            }
        }
    }

    fun inspect(
        archiveFile: File,
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
        checkCancelled: () -> Unit = {},
        zipFileFactory: (File) -> ZipFile = { ZipFile(it) },
    ): BackupSummary = openValidatedArchive(archiveFile, limits, checkCancelled, zipFileFactory) { _, manifest, _ ->
        summary(manifest.snapshot, manifest.payloadBytes)
    }

    fun extract(
        archiveFile: File,
        stagingDirectory: File,
        availableBytes: () -> Long,
        onProgress: (BackupProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
        durability: RestoreFileDurability = SystemRestoreFileDurability,
    ): PreparedLibraryBackup {
        require(stagingDirectory.isDirectory && stagingDirectory.canonicalFile == stagingDirectory.absoluteFile.toPath().normalize().toFile()) {
            "The private restore staging directory is not safe."
        }
        require(stagingDirectory.listFiles().orEmpty().all { it.name == ".aftertone-restore-owner" }) {
            "The private restore staging directory is not empty."
        }
        val createdFiles = mutableListOf<File>()
        val assetSha256ByEntry = linkedMapOf<String, String>()
        try {
            return openValidatedArchive(archiveFile, limits, checkCancelled, { ZipFile(it) }) { zip, manifest, entryLocations ->
                val reserve = limits.reservedSpaceBytes
                if (availableBytes() < safeAdd(manifest.payloadBytes, reserve)) {
                    throw IllegalStateException("There is not enough free space to restore this archive safely.")
                }
                var remainingBytes = manifest.payloadBytes
                var completedBytes = 0L
                RandomAccessFile(archiveFile, "r").use { compressedArchive ->
                    manifest.assets.forEach { asset ->
                        checkCancelled()
                        val baseName = stagingFileName(asset.entryName)
                        val destination = File(stagingDirectory, baseName)
                        require(PrivateAudioPath.isDirectChild(stagingDirectory, destination)) {
                            "The restore target is outside its private staging directory."
                        }
                        if (!destination.createNewFile()) throw IllegalArgumentException("The backup contains a duplicate file.")
                        createdFiles += destination
                        val entry = zip.getEntry(asset.entryName)
                            ?: throw IllegalArgumentException("The backup is missing a referenced file.")
                        val location = entryLocations[asset.entryName]
                            ?: throw IllegalArgumentException("A ZIP asset has no validated local data offset.")
                        requireZipEntryMatchesPreflight(entry, location)
                        val crc = CRC32()
                        val digest = MessageDigest.getInstance("SHA-256")
                        var fileBytes = 0L
                        var nextSpaceCheck = 0L
                        FileOutputStream(destination).use { output ->
                            val consumeOutput: (ByteArray, Int) -> Unit = { buffer, count ->
                                checkCancelled()
                                require(count > 0 && fileBytes <= asset.sizeBytes - count) {
                                    "A file in the backup is larger than its manifest entry."
                                }
                                if (fileBytes >= nextSpaceCheck) {
                                    if (availableBytes() < safeAdd(remainingBytes, reserve)) {
                                        throw IllegalStateException("Free space ran low; the current library has not been changed.")
                                    }
                                    nextSpaceCheck = fileBytes + limits.spaceCheckIntervalBytes
                                }
                                output.write(buffer, 0, count)
                                crc.update(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                fileBytes += count
                                remainingBytes -= count
                                completedBytes += count
                                onProgress(BackupProgress(completedBytes, manifest.payloadBytes))
                            }
                            when (location.method) {
                                ZipEntry.DEFLATED -> inflateAssetToOutput(
                                    compressedArchive,
                                    location,
                                    entry,
                                    limits.maxAssetCompressionRatio,
                                    checkCancelled,
                                    consumeOutput,
                                )
                                ZipEntry.STORED -> copyStoredAssetToOutput(
                                    compressedArchive,
                                    location,
                                    checkCancelled,
                                    consumeOutput,
                                )
                                else -> throw IllegalArgumentException("The backup uses an unsupported compression method.")
                            }
                            durability.syncFile(output)
                        }
                        require(fileBytes == asset.sizeBytes && crc.value == location.crc) {
                            "A file in the backup is incomplete or damaged."
                        }
                        assetSha256ByEntry[asset.entryName] = digest.digest().toHex()
                    }
                }
                require(remainingBytes == 0L) { "The backup file sizes do not match its manifest." }
                durability.syncDirectory(stagingDirectory)
                PreparedLibraryBackup(
                    manifest.snapshot,
                    summary(manifest.snapshot, manifest.payloadBytes),
                    assetSha256ByEntry,
                )
            }
        } catch (error: Throwable) {
            createdFiles.asReversed().forEach { file ->
                if (PrivateAudioPath.isDirectChild(stagingDirectory, file) && file.isFile) file.delete()
            }
            runCatching { durability.syncDirectory(stagingDirectory) }
            throw error
        }
    }

    internal fun collectZipEntries(
        source: Enumeration<out ZipEntry>,
        limits: LibraryBackupLimits,
        checkCancelled: () -> Unit = {},
    ): List<ZipEntry> {
        val entries = ArrayList<ZipEntry>(minOf(limits.maxEntries, 1_024))
        val names = HashSet<String>()
        while (source.hasMoreElements()) {
            checkCancelled()
            require(entries.size < limits.maxEntries) { "The backup contains too many files." }
            val entry = source.nextElement()
            require(entry.name.length in 1..MAX_ENTRY_NAME_CHARS) { "The backup contains an invalid ZIP entry name." }
            require(names.add(entry.name)) { "The backup contains duplicate ZIP entries." }
            entries += entry
        }
        return entries
    }

    private data class CentralDirectoryInfo(
        val entriesOnDisk: Long,
        val totalEntries: Long,
        val directorySize: Long,
        val directoryOffset: Long,
        val endRecordOffset: Long,
    )

    private data class ZipEntryDataLocation(
        val localHeaderOffset: Long,
        val dataOffset: Long,
        val compressedDataEndOffset: Long,
        val localRecordEndOffset: Long,
        val nextLocalHeaderOffset: Long,
        val centralDirectoryOffset: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val crc: Long,
        val method: Int,
    )

    private data class ParsedCentralDirectoryEntry(
        val name: String,
        val localHeaderOffset: Long,
        val dataOffset: Long,
        val recordLength: Long,
        val flags: Int,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val crc: Long,
        val usesZip64Sizes: Boolean,
    )

    private data class ResolvedCentralDirectoryFields(
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val usesZip64Sizes: Boolean,
    )

    private data class ResolvedLocalEntrySizes(
        val compressedSize: Long,
        val uncompressedSize: Long,
    )

    private fun preflightCentralDirectory(
        archiveFile: File,
        limits: LibraryBackupLimits,
        checkCancelled: () -> Unit,
    ): Map<String, ZipEntryDataLocation> {
        checkCancelled()
        return RandomAccessFile(archiveFile, "r").use { archive ->
            val fileLength = archive.length()
            val eocdOffset = findEndOfCentralDirectory(archive)
            archive.seek(eocdOffset + 4)
            val diskNumber = archive.readUnsignedShortLe()
            val directoryDisk = archive.readUnsignedShortLe()
            val classicEntriesOnDisk = archive.readUnsignedShortLe().toLong()
            val classicTotalEntries = archive.readUnsignedShortLe().toLong()
            val classicDirectorySize = archive.readUnsignedIntLe()
            val classicDirectoryOffset = archive.readUnsignedIntLe()
            val commentLength = archive.readUnsignedShortLe()
            require(eocdOffset + 22 + commentLength == fileLength) { "The ZIP end record is invalid." }
            require(diskNumber == 0 && directoryDisk == 0) { "Multi-disk ZIP archives are not supported." }

            val needsZip64 = classicEntriesOnDisk == 0xffffL ||
                classicTotalEntries == 0xffffL ||
                classicDirectorySize == 0xffff_ffffL ||
                classicDirectoryOffset == 0xffff_ffffL
            val directory = if (needsZip64) {
                readZip64Directory(archive, eocdOffset).also { zip64 ->
                    if (classicEntriesOnDisk != 0xffffL) require(classicEntriesOnDisk == zip64.entriesOnDisk) {
                        "The ZIP64 entry counts do not match the end record."
                    }
                    if (classicTotalEntries != 0xffffL) require(classicTotalEntries == zip64.totalEntries) {
                        "The ZIP64 entry counts do not match the end record."
                    }
                    if (classicDirectorySize != 0xffff_ffffL) require(classicDirectorySize == zip64.directorySize) {
                        "The ZIP64 directory size does not match the end record."
                    }
                    if (classicDirectoryOffset != 0xffff_ffffL) require(classicDirectoryOffset == zip64.directoryOffset) {
                        "The ZIP64 directory offset does not match the end record."
                    }
                }
            } else {
                CentralDirectoryInfo(
                    entriesOnDisk = classicEntriesOnDisk,
                    totalEntries = classicTotalEntries,
                    directorySize = classicDirectorySize,
                    directoryOffset = classicDirectoryOffset,
                    endRecordOffset = eocdOffset,
                )
            }

            require(directory.entriesOnDisk == directory.totalEntries) { "Multi-disk ZIP archives are not supported." }
            require(directory.totalEntries <= limits.maxEntries.toLong()) { "The backup contains too many files." }
            require(directory.directorySize <= MAX_CENTRAL_DIRECTORY_BYTES) { "The backup central directory is too large." }
            require(directory.directoryOffset <= Long.MAX_VALUE - directory.directorySize) {
                "The backup central directory has invalid bounds."
            }
            val directoryEnd = directory.directoryOffset + directory.directorySize
            require(directory.directoryOffset >= 0 && directoryEnd <= directory.endRecordOffset) {
                "The backup central directory has invalid bounds."
            }

            var cursor = directory.directoryOffset
            val names = HashSet<String>(minOf(limits.maxEntries, 1_024))
            val parsedEntries = ArrayList<ParsedCentralDirectoryEntry>(minOf(directory.totalEntries, 1_024L).toInt())
            repeat(directory.totalEntries.toInt()) {
                checkCancelled()
                require(cursor <= directoryEnd - 46) { "The backup central directory is incomplete." }
                val parsed = readCentralDirectoryEntryLocation(archive, cursor, directory.directoryOffset, directoryEnd)
                require(names.add(parsed.name)) {
                    "The backup contains duplicate ZIP entries."
                }
                parsedEntries += parsed
                cursor += parsed.recordLength
            }
            require(cursor == directoryEnd) { "The backup central directory has unexpected trailing data." }
            validateNonOverlappingEntrySpans(archive, parsedEntries, directory.directoryOffset, checkCancelled)
        }
    }

    private fun readCentralDirectoryEntryLocation(
        archive: RandomAccessFile,
        cursor: Long,
        directoryOffset: Long,
        directoryEnd: Long,
    ): ParsedCentralDirectoryEntry {
        archive.seek(cursor)
        require(archive.readUnsignedIntLe() == CENTRAL_HEADER_SIGNATURE) {
            "The backup central directory is damaged."
        }
        archive.seek(cursor + 8)
        val flags = archive.readUnsignedShortLe()
        val method = archive.readUnsignedShortLe()
        archive.seek(cursor + 16)
        val crc = archive.readUnsignedIntLe()
        archive.seek(cursor + 20)
        val compressedSize32 = archive.readUnsignedIntLe()
        val uncompressedSize32 = archive.readUnsignedIntLe()
        archive.seek(cursor + 28)
        val nameLength = archive.readUnsignedShortLe()
        val extraLength = archive.readUnsignedShortLe()
        val entryCommentLength = archive.readUnsignedShortLe()
        archive.seek(cursor + 34)
        val startDisk = archive.readUnsignedShortLe()
        require(startDisk == 0) { "Multi-disk ZIP archives are not supported." }
        require(nameLength in 1..MAX_ENTRY_NAME_CHARS) { "The backup contains an invalid ZIP entry name." }
        require(extraLength <= MAX_CENTRAL_EXTRA_BYTES) { "A ZIP entry contains excessive metadata." }
        require(entryCommentLength <= MAX_CENTRAL_COMMENT_BYTES) { "A ZIP entry comment is too large." }
        val recordLength = 46L + nameLength + extraLength + entryCommentLength
        require(cursor <= directoryEnd - recordLength) { "The backup central directory is incomplete." }

        archive.seek(cursor + 42)
        val localHeaderOffset32 = archive.readUnsignedIntLe()
        val nameBytes = ByteArray(nameLength)
        archive.seek(cursor + 46)
        archive.readFully(nameBytes)
        val extra = ByteArray(extraLength)
        archive.readFully(extra)
        val fields = resolveCentralDirectoryFields(
            extra,
            compressedSize32,
            uncompressedSize32,
            localHeaderOffset32,
        )
        val dataOffset = readLocalEntryDataOffset(
            archive,
            directoryOffset,
            fields.localHeaderOffset,
            nameBytes,
            flags,
            method,
            crc,
            fields.compressedSize,
            fields.uncompressedSize,
        )
        return ParsedCentralDirectoryEntry(
            name = String(nameBytes, Charsets.UTF_8),
            localHeaderOffset = fields.localHeaderOffset,
            dataOffset = dataOffset,
            recordLength = recordLength,
            flags = flags,
            method = method,
            compressedSize = fields.compressedSize,
            uncompressedSize = fields.uncompressedSize,
            crc = crc,
            usesZip64Sizes = fields.usesZip64Sizes,
        )
    }

    private fun resolveCentralDirectoryFields(
        extra: ByteArray,
        compressedSize32: Long,
        uncompressedSize32: Long,
        localHeaderOffset32: Long,
    ): ResolvedCentralDirectoryFields {
        val usesZip64Sizes = compressedSize32 == UINT32_MAX || uncompressedSize32 == UINT32_MAX
        if (!usesZip64Sizes && localHeaderOffset32 != UINT32_MAX) {
            return ResolvedCentralDirectoryFields(compressedSize32, uncompressedSize32, localHeaderOffset32, false)
        }
        var cursor = 0
        while (cursor <= extra.size - 4) {
            val fieldId = extra.readUnsignedShortLe(cursor)
            val fieldLength = extra.readUnsignedShortLe(cursor + 2)
            val fieldStart = cursor + 4
            val fieldEnd = fieldStart + fieldLength
            require(fieldEnd <= extra.size) { "A ZIP entry has incomplete extra metadata." }
            if (fieldId == ZIP64_EXTRA_FIELD_ID) {
                var valueOffset = fieldStart
                val uncompressedSize = if (uncompressedSize32 == UINT32_MAX) {
                    require(valueOffset <= fieldEnd - 8) { "A ZIP64 uncompressed size is missing." }
                    extra.readUnsignedLongLe(valueOffset).also { valueOffset += 8 }
                } else {
                    uncompressedSize32
                }
                val compressedSize = if (compressedSize32 == UINT32_MAX) {
                    require(valueOffset <= fieldEnd - 8) { "A ZIP64 compressed size is missing." }
                    extra.readUnsignedLongLe(valueOffset).also { valueOffset += 8 }
                } else {
                    compressedSize32
                }
                val localHeaderOffset = if (localHeaderOffset32 == UINT32_MAX) {
                    require(valueOffset <= fieldEnd - 8) { "A ZIP64 local-header offset is missing." }
                    extra.readUnsignedLongLe(valueOffset)
                } else {
                    localHeaderOffset32
                }
                return ResolvedCentralDirectoryFields(compressedSize, uncompressedSize, localHeaderOffset, usesZip64Sizes)
            }
            cursor = fieldEnd
        }
        throw IllegalArgumentException("A ZIP64 central-directory value is missing.")
    }

    private fun resolveLocalEntrySizes(
        extra: ByteArray,
        compressedSize32: Long,
        uncompressedSize32: Long,
    ): ResolvedLocalEntrySizes {
        if (compressedSize32 != UINT32_MAX && uncompressedSize32 != UINT32_MAX) {
            return ResolvedLocalEntrySizes(compressedSize32, uncompressedSize32)
        }
        var cursor = 0
        while (cursor <= extra.size - 4) {
            val fieldId = extra.readUnsignedShortLe(cursor)
            val fieldLength = extra.readUnsignedShortLe(cursor + 2)
            val fieldStart = cursor + 4
            val fieldEnd = fieldStart + fieldLength
            require(fieldEnd <= extra.size) { "A ZIP local header has incomplete extra metadata." }
            if (fieldId == ZIP64_EXTRA_FIELD_ID) {
                var valueOffset = fieldStart
                val uncompressedSize = if (uncompressedSize32 == UINT32_MAX) {
                    require(valueOffset <= fieldEnd - 8) { "A local ZIP64 uncompressed size is missing." }
                    extra.readUnsignedLongLe(valueOffset).also { valueOffset += 8 }
                } else {
                    uncompressedSize32
                }
                val compressedSize = if (compressedSize32 == UINT32_MAX) {
                    require(valueOffset <= fieldEnd - 8) { "A local ZIP64 compressed size is missing." }
                    extra.readUnsignedLongLe(valueOffset)
                } else {
                    compressedSize32
                }
                return ResolvedLocalEntrySizes(compressedSize, uncompressedSize)
            }
            cursor = fieldEnd
        }
        throw IllegalArgumentException("A local ZIP64 size is missing.")
    }

    private fun readLocalEntryDataOffset(
        archive: RandomAccessFile,
        directoryOffset: Long,
        localHeaderOffset: Long,
        centralName: ByteArray,
        centralFlags: Int,
        centralMethod: Int,
        centralCrc: Long,
        centralCompressedSize: Long,
        centralUncompressedSize: Long,
    ): Long {
        require(localHeaderOffset >= 0 && localHeaderOffset <= directoryOffset - 30) {
            "A ZIP local header has invalid bounds."
        }
        archive.seek(localHeaderOffset)
        require(archive.readUnsignedIntLe() == LOCAL_HEADER_SIGNATURE) { "A ZIP local header is damaged." }
        archive.seek(localHeaderOffset + 6)
        val localFlags = archive.readUnsignedShortLe()
        val localMethod = archive.readUnsignedShortLe()
        require(localFlags == centralFlags && localMethod == centralMethod) {
            "A ZIP local header does not match its central directory entry."
        }
        archive.seek(localHeaderOffset + 14)
        val localCrc = archive.readUnsignedIntLe()
        val localCompressedSize32 = archive.readUnsignedIntLe()
        val localUncompressedSize32 = archive.readUnsignedIntLe()
        archive.seek(localHeaderOffset + 26)
        val localNameLength = archive.readUnsignedShortLe()
        val localExtraLength = archive.readUnsignedShortLe()
        require(localNameLength == centralName.size && localExtraLength <= MAX_CENTRAL_EXTRA_BYTES) {
            "A ZIP local header has invalid metadata bounds."
        }
        val dataOffset = safeAdd(localHeaderOffset, 30L + localNameLength + localExtraLength)
        require(dataOffset <= directoryOffset) { "A ZIP entry's data begins inside its central directory." }
        val localName = ByteArray(localNameLength)
        archive.seek(localHeaderOffset + 30)
        archive.readFully(localName)
        val localExtra = ByteArray(localExtraLength)
        archive.readFully(localExtra)
        require(localName.contentEquals(centralName)) { "A ZIP local entry name does not match its central directory." }
        if (localFlags and DATA_DESCRIPTOR_FLAG == 0) {
            require(localCrc == centralCrc) { "A ZIP local-header checksum does not match its central directory." }
            val localSizes = resolveLocalEntrySizes(localExtra, localCompressedSize32, localUncompressedSize32)
            require(localSizes.compressedSize == centralCompressedSize &&
                localSizes.uncompressedSize == centralUncompressedSize
            ) { "A ZIP local-header size does not match its central directory." }
        }
        return dataOffset
    }

    private fun validateNonOverlappingEntrySpans(
        archive: RandomAccessFile,
        entries: List<ParsedCentralDirectoryEntry>,
        centralDirectoryOffset: Long,
        checkCancelled: () -> Unit,
    ): Map<String, ZipEntryDataLocation> {
        val ordered = entries.sortedBy { it.localHeaderOffset }
        val locations = HashMap<String, ZipEntryDataLocation>(entries.size)
        var previousEnd = 0L
        ordered.forEachIndexed { index, entry ->
            checkCancelled()
            val nextLocalHeaderOffset = ordered.getOrNull(index + 1)?.localHeaderOffset
                ?: centralDirectoryOffset
            require(entry.localHeaderOffset >= previousEnd &&
                entry.localHeaderOffset < nextLocalHeaderOffset &&
                nextLocalHeaderOffset <= centralDirectoryOffset
            ) { "ZIP local entry spans overlap." }
            require(entry.dataOffset >= entry.localHeaderOffset + 30L &&
                entry.dataOffset <= centralDirectoryOffset &&
                entry.compressedSize >= 0L &&
                entry.uncompressedSize >= 0L &&
                entry.compressedSize <= centralDirectoryOffset - entry.dataOffset
            ) { "A ZIP entry's compressed data has invalid bounds." }
            val compressedDataEnd = safeAdd(entry.dataOffset, entry.compressedSize)
            require(compressedDataEnd <= nextLocalHeaderOffset) { "ZIP local entry spans overlap." }
            val entryEnd = if (entry.flags and DATA_DESCRIPTOR_FLAG != 0) {
                readDataDescriptorEnd(archive, entry, compressedDataEnd, nextLocalHeaderOffset)
            } else {
                compressedDataEnd
            }
            require(entryEnd <= nextLocalHeaderOffset) { "ZIP local entry spans overlap." }
            previousEnd = entryEnd
            locations[entry.name] = ZipEntryDataLocation(
                localHeaderOffset = entry.localHeaderOffset,
                dataOffset = entry.dataOffset,
                compressedDataEndOffset = compressedDataEnd,
                localRecordEndOffset = entryEnd,
                nextLocalHeaderOffset = nextLocalHeaderOffset,
                centralDirectoryOffset = centralDirectoryOffset,
                compressedSize = entry.compressedSize,
                uncompressedSize = entry.uncompressedSize,
                crc = entry.crc,
                method = entry.method,
            )
        }
        return locations
    }

    private fun readDataDescriptorEnd(
        archive: RandomAccessFile,
        entry: ParsedCentralDirectoryEntry,
        compressedDataEnd: Long,
        nextLocalHeaderOffset: Long,
    ): Long {
        val availableBytes = nextLocalHeaderOffset - compressedDataEnd
        require(availableBytes >= 0L) { "ZIP local entry spans overlap." }
        val matchingLengths = mutableSetOf<Int>()
        for (hasSignature in booleanArrayOf(false, true)) {
            val descriptorLength = (if (hasSignature) 4 else 0) + 4 + if (entry.usesZip64Sizes) 16 else 8
            if (descriptorLength.toLong() > availableBytes) continue
            val descriptor = ByteArray(descriptorLength)
            archive.seek(compressedDataEnd)
            archive.readFully(descriptor)
            var valueOffset = 0
            if (hasSignature) {
                if (descriptor.readUnsignedIntLe(valueOffset) != DATA_DESCRIPTOR_SIGNATURE) continue
                valueOffset += 4
            }
            if (descriptor.readUnsignedIntLe(valueOffset) != entry.crc) continue
            valueOffset += 4
            val sizes = try {
                if (entry.usesZip64Sizes) {
                    descriptor.readUnsignedLongLe(valueOffset) to descriptor.readUnsignedLongLe(valueOffset + 8)
                } else {
                    descriptor.readUnsignedIntLe(valueOffset) to descriptor.readUnsignedIntLe(valueOffset + 4)
                }
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (sizes.first == entry.compressedSize && sizes.second == entry.uncompressedSize) {
                matchingLengths += descriptorLength
            }
        }
        require(matchingLengths.size == 1) { "A ZIP data descriptor is invalid or ambiguous." }
        return safeAdd(compressedDataEnd, matchingLengths.single().toLong())
    }

    private fun findEndOfCentralDirectory(archive: RandomAccessFile): Long {
        val fileLength = archive.length()
        require(fileLength >= 22) { "The ZIP end record is missing." }
        val tailLength = minOf(fileLength, MAX_EOCD_SEARCH_BYTES.toLong()).toInt()
        val tail = ByteArray(tailLength)
        archive.seek(fileLength - tailLength)
        archive.readFully(tail)
        val tailOffset = fileLength - tailLength
        for (index in tail.size - 22 downTo 0) {
            if (tail.readUnsignedIntLe(index) != EOCD_SIGNATURE) continue
            val commentLength = tail.readUnsignedShortLe(index + 20)
            if (index + 22 + commentLength == tail.size) return tailOffset + index
        }
        throw IllegalArgumentException("The ZIP end record is missing or invalid.")
    }

    private fun readZip64Directory(archive: RandomAccessFile, eocdOffset: Long): CentralDirectoryInfo {
        require(eocdOffset >= 20) { "The ZIP64 locator is missing." }
        val locatorOffset = eocdOffset - 20
        archive.seek(locatorOffset)
        require(archive.readUnsignedIntLe() == ZIP64_LOCATOR_SIGNATURE) { "The ZIP64 locator is missing." }
        val recordDisk = archive.readUnsignedIntLe()
        val zip64Offset = archive.readUnsignedLongLe()
        val diskCount = archive.readUnsignedIntLe()
        require(recordDisk == 0L && diskCount == 1L) { "Multi-disk ZIP archives are not supported." }
        require(zip64Offset >= 0 && zip64Offset <= locatorOffset - 12 - 44) { "The ZIP64 end record has invalid bounds." }

        archive.seek(zip64Offset)
        require(archive.readUnsignedIntLe() == ZIP64_EOCD_SIGNATURE) { "The ZIP64 end record is invalid." }
        val recordSize = archive.readUnsignedLongLe()
        require(recordSize in 44..MAX_ZIP64_EOCD_BYTES.toLong()) { "The ZIP64 end record is too large." }
        require(zip64Offset + 12 + recordSize == locatorOffset) { "The ZIP64 end record has invalid bounds." }
        archive.readUnsignedShortLe() // version made by
        archive.readUnsignedShortLe() // version needed
        val diskNumber = archive.readUnsignedIntLe()
        val directoryDisk = archive.readUnsignedIntLe()
        val entriesOnDisk = archive.readUnsignedLongLe()
        val totalEntries = archive.readUnsignedLongLe()
        val directorySize = archive.readUnsignedLongLe()
        val directoryOffset = archive.readUnsignedLongLe()
        require(diskNumber == 0L && directoryDisk == 0L) { "Multi-disk ZIP archives are not supported." }
        return CentralDirectoryInfo(entriesOnDisk, totalEntries, directorySize, directoryOffset, zip64Offset)
    }

    private fun <T> openValidatedArchive(
        archiveFile: File,
        limits: LibraryBackupLimits,
        checkCancelled: () -> Unit,
        zipFileFactory: (File) -> ZipFile,
        action: (ZipFile, PortableLibraryBackupManifest, Map<String, ZipEntryDataLocation>) -> T,
    ): T {
        checkCancelled()
        require(archiveFile.isFile && archiveFile.length() in 1..limits.maxCompressedBytes) {
            "The selected archive is empty or exceeds the compressed-size limit."
        }
        require(archiveFile.canonicalFile == archiveFile.absoluteFile.toPath().normalize().toFile()) {
            "The selected private archive path is not safe."
        }
        val entryLocations = try {
            preflightCentralDirectory(archiveFile, limits, checkCancelled)
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("The selected ZIP archive is incomplete or unreadable.", error)
        }
        val zip = try {
            zipFileFactory(archiveFile)
        } catch (error: Exception) {
            throw IllegalArgumentException("The selected ZIP archive is incomplete or unreadable.", error)
        }
        zip.use { opened ->
            val entries = try {
                collectZipEntries(opened.entries(), limits, checkCancelled)
            } catch (error: InterruptedException) {
                throw error
            } catch (error: RuntimeException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("The selected ZIP directory is damaged.", error)
            }
            require(entries.isNotEmpty() && entries.size <= limits.maxEntries) { "The backup contains too many files." }
            val zipNames = entries.mapTo(hashSetOf()) { it.name }
            require(zipNames == entryLocations.keys) { "The ZIP directory changed after raw preflight." }
            entries.forEach { entry ->
                checkCancelled()
                val location = entryLocations[entry.name]
                    ?: throw IllegalArgumentException("A ZIP entry has no raw-preflight location.")
                requireZipEntryMatchesPreflight(entry, location)
            }
            require(entries.first().name == MANIFEST_ENTRY) { "The backup manifest is missing or out of order." }
            entries.forEach { entry ->
                checkCancelled()
                require(!entry.isDirectory && entry.name.isNotEmpty() && '\\' !in entry.name && !entry.name.startsWith('/')) {
                    "The backup contains an unsafe ZIP path."
                }
                require(entry.size >= 0L) { "The backup ZIP directory contains an unknown file size." }
            }
            var totalUncompressedBytes = 0L
            entries.forEach { entry ->
                checkCancelled()
                totalUncompressedBytes = safeAdd(totalUncompressedBytes, entry.size)
            }
            require(totalUncompressedBytes <= limits.maxTotalPayloadBytes) {
                "The backup payload exceeds the supported size."
            }
            val names = hashSetOf<String>()
            entries.forEach { entry ->
                checkCancelled()
                require(names.add(entry.name)) { "The backup contains duplicate ZIP entries." }
            }
            val manifestEntry = entries.first()
            require(manifestEntry.size in 1..limits.maxManifestBytes.toLong()) {
                "The backup manifest is empty or too large."
            }
            require(manifestEntry.method == ZipEntry.DEFLATED || manifestEntry.method == ZipEntry.STORED) {
                "The backup uses an unsupported compression method."
            }
            val manifestLocation = entryLocations[MANIFEST_ENTRY]
                ?: throw IllegalArgumentException("The backup manifest has no validated local data offset.")
            requirePreflightLocationBounds(manifestLocation)
            if (manifestEntry.method == ZipEntry.STORED) {
                require(manifestLocation.compressedSize == manifestLocation.uncompressedSize) {
                    "A stored ZIP manifest has inconsistent sizes."
                }
            }
            val manifestBytes = readVerifiedEntry(
                archiveFile,
                manifestEntry,
                manifestLocation,
                limits.maxManifestBytes.toLong(),
                checkCancelled,
            )
            checkCancelled()
            val manifest = LibraryBackupManifestCodec.decode(manifestBytes, limits, checkCancelled)
            checkCancelled()
            val expectedNames = hashSetOf(MANIFEST_ENTRY)
            manifest.assets.forEach { asset ->
                checkCancelled()
                expectedNames += asset.entryName
            }
            require(names.toSet() == expectedNames && names.size == expectedNames.size) {
                "The backup is missing a referenced file or contains an unexpected entry."
            }
            val centralAssets = hashMapOf<String, ZipEntry>()
            entries.drop(1).forEach { entry ->
                checkCancelled()
                centralAssets[entry.name] = entry
            }
            manifest.assets.forEach { asset ->
                checkCancelled()
                val entry = centralAssets[asset.entryName]
                    ?: throw IllegalArgumentException("The backup is missing a referenced file.")
                require(isAllowedZipAssetName(entry.name)) { "The backup contains an unsafe ZIP path." }
                val location = entryLocations[entry.name]
                    ?: throw IllegalArgumentException("A ZIP asset has no validated local data offset.")
                require(entry.size == asset.sizeBytes && location.uncompressedSize == asset.sizeBytes) {
                    "A ZIP entry size does not match the backup manifest."
                }
                require(entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED) {
                    "The backup uses an unsupported compression method."
                }
                require(entry.crc in 0..0xffff_ffffL) { "The ZIP entry checksum is missing." }
                requirePreflightLocationBounds(location)
                if (location.method == ZipEntry.STORED) {
                    require(location.compressedSize == location.uncompressedSize) {
                        "A stored ZIP asset has inconsistent sizes."
                    }
                }
            }
            checkCancelled()
            return action(opened, manifest, entryLocations)
        }
    }

    private fun requirePreflightLocationBounds(location: ZipEntryDataLocation) {
        require(location.localHeaderOffset >= 0L && location.localHeaderOffset <= Long.MAX_VALUE - 30L &&
            location.dataOffset >= location.localHeaderOffset + 30L &&
            location.compressedSize >= 0L && location.uncompressedSize >= 0L &&
            location.dataOffset <= location.centralDirectoryOffset &&
            location.compressedDataEndOffset == safeAdd(location.dataOffset, location.compressedSize) &&
            location.compressedDataEndOffset <= location.localRecordEndOffset &&
            location.localRecordEndOffset <= location.nextLocalHeaderOffset &&
            location.nextLocalHeaderOffset <= location.centralDirectoryOffset
        ) { "A ZIP entry does not have a valid raw-preflight span." }
    }

    private fun requireZipEntryMatchesPreflight(entry: ZipEntry, location: ZipEntryDataLocation) {
        requirePreflightLocationBounds(location)
        require(entry.size == location.uncompressedSize &&
            entry.compressedSize == location.compressedSize &&
            entry.crc == location.crc &&
            entry.method == location.method
        ) { "A ZIP directory entry changed after raw preflight." }
    }

    private fun readVerifiedEntry(
        archiveFile: File,
        entry: ZipEntry,
        location: ZipEntryDataLocation,
        maximumBytes: Long,
        checkCancelled: () -> Unit,
    ): ByteArray {
        requireZipEntryMatchesPreflight(entry, location)
        require(location.uncompressedSize in 0..maximumBytes && location.crc in 0..0xffff_ffffL) {
            "The backup manifest entry is invalid."
        }
        val result = ByteArrayOutputStream(location.uncompressedSize.toInt())
        val crc = CRC32()
        var copied = 0L
        RandomAccessFile(archiveFile, "r").use { archive ->
            when (location.method) {
                ZipEntry.STORED -> {
                    require(location.compressedSize == location.uncompressedSize) {
                        "A stored ZIP manifest has inconsistent sizes."
                    }
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var remaining = location.compressedSize
                    archive.seek(location.dataOffset)
                    while (remaining > 0L) {
                        checkCancelled()
                        val count = archive.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(count > 0) { "The backup manifest's compressed data is incomplete." }
                        require(copied <= maximumBytes - count) { "The backup manifest is too large." }
                        result.write(buffer, 0, count)
                        crc.update(buffer, 0, count)
                        copied += count
                        remaining -= count
                    }
                }

                ZipEntry.DEFLATED -> {
                    val inflater = Inflater(true)
                    try {
                        val compressedBuffer = ByteArray(COPY_BUFFER_SIZE)
                        val expandedBuffer = ByteArray(COPY_BUFFER_SIZE)
                        var bytesProvided = 0L
                        while (!inflater.finished()) {
                            checkCancelled()
                            if (inflater.needsInput()) {
                                val remaining = location.compressedSize - bytesProvided
                                require(remaining > 0L) { "The backup manifest's compressed data is incomplete." }
                                val requested = minOf(compressedBuffer.size.toLong(), remaining).toInt()
                                archive.seek(safeAdd(location.dataOffset, bytesProvided))
                                val count = archive.read(compressedBuffer, 0, requested)
                                require(count > 0) { "The backup manifest's compressed data is incomplete." }
                                bytesProvided += count
                                inflater.setInput(compressedBuffer, 0, count)
                            }
                            val expanded = try {
                                inflater.inflate(expandedBuffer)
                            } catch (error: DataFormatException) {
                                throw IllegalArgumentException("The backup manifest's compressed data is invalid.", error)
                            }
                            if (expanded > 0) {
                                require(copied <= maximumBytes - expanded) { "The backup manifest is too large." }
                                result.write(expandedBuffer, 0, expanded)
                                crc.update(expandedBuffer, 0, expanded)
                                copied += expanded
                            } else if (inflater.needsDictionary()) {
                                throw IllegalArgumentException("The backup manifest requires an unsupported compression dictionary.")
                            } else if (!inflater.needsInput() && !inflater.finished()) {
                                throw IllegalArgumentException("The backup manifest's compressed data could not be decoded.")
                            }
                        }
                        require(inflater.bytesRead == location.compressedSize) {
                            "The backup manifest's compressed size does not match the bytes consumed by its inflater."
                        }
                    } finally {
                        inflater.end()
                    }
                }

                else -> throw IllegalArgumentException("The backup uses an unsupported compression method.")
            }
        }
        require(copied == location.uncompressedSize && crc.value == location.crc) {
            "The backup manifest is damaged or incomplete."
        }
        return result.toByteArray()
    }

    private fun copyStoredAssetToOutput(
        archive: RandomAccessFile,
        location: ZipEntryDataLocation,
        checkCancelled: () -> Unit,
        consumeOutput: (ByteArray, Int) -> Unit,
    ) {
        requirePreflightLocationBounds(location)
        require(location.method == ZipEntry.STORED && location.compressedSize == location.uncompressedSize) {
            "A stored ZIP asset has inconsistent sizes."
        }
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var remaining = location.compressedSize
        archive.seek(location.dataOffset)
        while (remaining > 0L) {
            checkCancelled()
            val count = archive.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count > 0) { "A ZIP asset's stored data is incomplete." }
            consumeOutput(buffer, count)
            remaining -= count
        }
    }

    private fun inflateAssetToOutput(
        archive: RandomAccessFile,
        location: ZipEntryDataLocation,
        entry: ZipEntry,
        maximumRatio: Long,
        checkCancelled: () -> Unit,
        consumeOutput: (ByteArray, Int) -> Unit,
    ) {
        requireZipEntryMatchesPreflight(entry, location)
        require(location.uncompressedSize >= 0L && location.compressedSize >= 0L) {
            "The backup ZIP directory contains an unknown file size."
        }
        requirePreflightLocationBounds(location)
        require(location.method == ZipEntry.DEFLATED) { "The backup uses an unsupported compression method." }
        // Reject a declared high-ratio entry up front. The actual ratio is checked after decoding;
        // an unusually compressible prefix alone does not determine the whole entry's ratio.
        require(isWithinExpansionRatio(location.uncompressedSize, location.compressedSize, maximumRatio)) {
            "A ZIP asset exceeds the supported expansion ratio."
        }

        val inflater = Inflater(true)
        try {
            val compressedBuffer = ByteArray(COPY_BUFFER_SIZE)
            val expandedBuffer = ByteArray(COPY_BUFFER_SIZE)
            var bytesProvided = 0L
            var bytesExpanded = 0L
            while (!inflater.finished()) {
                checkCancelled()
                if (inflater.needsInput()) {
                    val remaining = location.compressedSize - bytesProvided
                    require(remaining > 0L) { "A ZIP asset's compressed data is incomplete." }
                    val requested = minOf(compressedBuffer.size.toLong(), remaining).toInt()
                    archive.seek(safeAdd(location.dataOffset, bytesProvided))
                    val count = archive.read(compressedBuffer, 0, requested)
                    require(count > 0) { "A ZIP asset's compressed data is incomplete." }
                    bytesProvided += count
                    inflater.setInput(compressedBuffer, 0, count)
                }
                val expanded = try {
                    inflater.inflate(expandedBuffer)
                } catch (error: DataFormatException) {
                    throw IllegalArgumentException("A ZIP asset's compressed data is invalid.", error)
                }
                if (expanded > 0) {
                    require(bytesExpanded <= location.uncompressedSize - expanded) {
                        "A ZIP asset is larger than its declared uncompressed size."
                    }
                    bytesExpanded += expanded
                    // Allow a long silent/compressible lead-in, then account for expansion using actual consumed bytes.
                    require(isWithinExpansionRatio(
                        bytesExpanded,
                        inflater.bytesRead,
                        maximumRatio,
                        MAX_TRANSIENT_EXPANSION_PREFIX_BYTES,
                    )) {
                        "A ZIP asset exceeds the supported expansion ratio."
                    }
                    consumeOutput(expandedBuffer, expanded)
                } else if (inflater.needsDictionary()) {
                    throw IllegalArgumentException("A ZIP asset requires an unsupported compression dictionary.")
                } else if (!inflater.needsInput() && !inflater.finished()) {
                    throw IllegalArgumentException("A ZIP asset's compressed data could not be decoded.")
                }
            }

            val compressedBytesConsumed = inflater.bytesRead
            require(bytesExpanded == location.uncompressedSize) {
                "A ZIP asset's uncompressed size does not match its directory entry."
            }
            require(isWithinExpansionRatio(bytesExpanded, compressedBytesConsumed, maximumRatio)) {
                "A ZIP asset exceeds the supported expansion ratio."
            }
            require(compressedBytesConsumed == location.compressedSize) {
                "A ZIP asset's compressed size does not match the bytes consumed by its inflater."
            }
        } finally {
            inflater.end()
        }
    }

    private fun copyExact(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long,
        maximumBytes: Long,
        checkCancelled: () -> Unit,
        onCopied: (Int) -> Unit,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            require(count > 0 && copied <= expectedBytes - count && copied <= maximumBytes - count) {
                "A local audio copy changed size during backup."
            }
            output.write(buffer, 0, count)
            copied += count
            onCopied(count)
        }
        return copied
    }

    private fun isOwnedAudioFile(audioDirectory: File, file: File): Boolean =
        !Files.isSymbolicLink(file.toPath()) &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            (PrivateAudioPath.isDirectChild(audioDirectory, file) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file))

    private fun stagingFileName(entryName: String): String {
        if (!entryName.startsWith("playlist-artwork/")) return entryName.substringAfter('/')
        val playlistId = entryName.removePrefix("playlist-artwork/").removeSuffix(".img")
        require(PlaylistArtworkFileName.isSafePlaylistId(playlistId) && entryName == "playlist-artwork/$playlistId.img") {
            "The backup contains an unsafe playlist artwork path."
        }
        return ".playlist-artwork-$playlistId.img"
    }

    private fun isAllowedZipAssetName(name: String): Boolean =
        name.startsWith("audio/") || name.startsWith("artwork/") || name.startsWith("playlist-artwork/")

    private fun summary(snapshot: LibrarySnapshot, payloadBytes: Long) = BackupSummary(
        trackCount = snapshot.tracks.size,
        playlistCount = snapshot.playlists.size,
        favoriteCount = snapshot.favoriteTrackIds.size,
        payloadBytes = payloadBytes,
    )

    private fun RandomAccessFile.readUnsignedShortLe(): Int =
        readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun RandomAccessFile.readUnsignedIntLe(): Long =
        readUnsignedByte().toLong() or
            (readUnsignedByte().toLong() shl 8) or
            (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 24)

    private fun RandomAccessFile.readUnsignedLongLe(): Long {
        var value = 0L
        repeat(8) { index ->
            val next = readUnsignedByte().toLong()
            require(index != 7 || next <= 0x7fL) { "A ZIP64 value exceeds the supported file-size range." }
            value = value or (next shl (index * 8))
        }
        return value
    }

    private fun ByteArray.readUnsignedShortLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readUnsignedIntLe(offset: Int): Long =
        (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)

    private fun ByteArray.readUnsignedLongLe(offset: Int): Long {
        require(offset >= 0 && offset <= size - 8) { "A ZIP64 value is incomplete." }
        var value = 0L
        repeat(8) { index ->
            val next = this[offset + index].toLong() and 0xff
            require(index != 7 || next <= 0x7fL) { "A ZIP64 value exceeds the supported file-size range." }
            value = value or (next shl (index * 8))
        }
        return value
    }

    private fun safeAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("The backup payload is too large.", error)
    }

    private fun isWithinExpansionRatio(
        uncompressedBytes: Long,
        compressedBytes: Long,
        maximumRatio: Long,
        prefixAllowanceBytes: Long = 0L,
    ): Boolean {
        if (uncompressedBytes < 0L || compressedBytes < 0L || maximumRatio <= 0L || prefixAllowanceBytes < 0L) return false
        if (uncompressedBytes == 0L) return true
        val accountableBytes = (uncompressedBytes - prefixAllowanceBytes).coerceAtLeast(0L)
        if (accountableBytes == 0L) return true
        if (compressedBytes <= 0L) return false
        val maximumExpandedBytes = try {
            Math.multiplyExact(compressedBytes, maximumRatio)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        return accountableBytes <= maximumExpandedBytes
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private class BoundedNonClosingOutputStream(
        private val target: OutputStream,
        private val maximumBytes: Long,
    ) : OutputStream() {
        private var written = 0L

        override fun write(value: Int) {
            if (written >= maximumBytes) throw IOException("The backup exceeds the compressed-size limit.")
            target.write(value)
            written++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length < 0 || written > maximumBytes - length) throw IOException("The backup exceeds the compressed-size limit.")
            target.write(buffer, offset, length)
            written += length
        }

        override fun flush() = target.flush()
        override fun close() = flush()
    }
}
