package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.model.TrackFileName
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.util.UUID

private const val MAX_AUDIO_FILE_BYTES = 2L * 1024 * 1024 * 1024
private const val MAX_ARTWORK_BYTES = 2 * 1024 * 1024
private val mimeTypesAllowedForContentProbe = setOf(
    "application/octet-stream",
    "application/x-id3",
    "binary/octet-stream",
)

internal interface AudioImportDurability {
    fun syncFile(file: File)

    fun syncFile(file: File, channel: FileChannel) {
        syncFile(file)
        channel.force(true)
    }

    fun syncDirectory(directory: File)

    fun syncDirectory(directory: File, openedDirectory: SecureDirectoryStream<Path>) {
        if (!PrivateAudioPath.isSameDirectory(directory, openedDirectory)) {
            throw IOException("The directory path no longer identifies the opened directory.")
        }
        syncDirectory(directory)
        if (!PrivateAudioPath.isSameDirectory(directory, openedDirectory)) {
            throw IOException("The directory path changed while it was being synced.")
        }
    }
}

internal object AndroidAudioImportDurability : AudioImportDurability {
    override fun syncFile(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    override fun syncFile(file: File, channel: FileChannel) {
        channel.force(true)
    }

    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }
}

data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val artwork: ByteArray? = null,
    val audioMimeType: String? = null,
)

fun interface AudioMetadataReader {
    fun read(audioFile: File): AudioMetadata
}

internal class LocalAudioImporter(
    private val audioDirectory: File,
    private val metadataReader: AudioMetadataReader,
    private val availableBytes: () -> Long,
    private val durability: AudioImportDurability = AndroidAudioImportDurability,
) : AudioTrackImporter {
    override fun import(input: InputStream, displayName: String, mimeType: String?): Track =
        input.use { source -> importOpenedStream(source, displayName, mimeType) }

    private fun importOpenedStream(input: InputStream, displayName: String, mimeType: String?): Track {
        val id = UUID.randomUUID().toString()
        val fileNameFromDocument = TrackFileName.forImport(id, displayName, mimeType)
        if (fileNameFromDocument == null && !canProbeByContent(mimeType)) {
            throw IllegalArgumentException("Choose a supported audio file.")
        }

        if (!audioDirectory.exists()) {
            if (!audioDirectory.mkdirs()) throw IOException("Could not create the local audio directory.")
            val parentDirectory = audioDirectory.parentFile
                ?: throw IOException("The local audio directory has no parent.")
            durability.syncDirectory(parentDirectory)
        }

        val temporaryFile = File(audioDirectory, "$id.part")
        val artworkFile = File(audioDirectory, "$id.img")
        if (listOf(temporaryFile, artworkFile).any { !PrivateAudioPath.isDirectChild(audioDirectory, it) }) {
            throw SecurityException("The local file path is outside the audio directory.")
        }
        if (temporaryFile.exists() || artworkFile.exists()) {
            throw IOException("A local file with this identifier already exists.")
        }
        if (!temporaryFile.createNewFile()) throw IOException("Could not create an import staging file.")

        var audioFile: File? = null
        var ownsTemporaryFile = true
        var ownsAudioFile = false
        var ownsArtworkFile = false
        var completed = false
        var operationFailure: Throwable? = null
        try {
            FileOutputStream(temporaryFile).use { destination -> copyBounded(input, destination) }
            if (temporaryFile.length() == 0L) throw IllegalArgumentException("The selected audio file is empty.")
            durability.syncFile(temporaryFile)

            // A filename or provider MIME is a hint, not proof. The Android reader parses
            // the staged bytes and returns the actual audio-track MIME before we finalize.
            val metadata = metadataReader.read(temporaryFile)
            val parsedFileName = metadata.audioMimeType?.let { detectedMime ->
                TrackFileName.forDetectedAudioTrack(id, displayName, detectedMime, mimeType)
            }
            val finalFileName = if (metadata.audioMimeType != null) {
                parsedFileName ?: throw IllegalArgumentException("The selected file uses an unsupported audio codec.")
            } else {
                fileNameFromDocument
                    ?: throw IllegalArgumentException("The selected file does not contain a supported audio track.")
            }
            val destinationAudioFile = File(audioDirectory, finalFileName)
            if (!PrivateAudioPath.isDirectChild(audioDirectory, destinationAudioFile)) {
                throw SecurityException("The local file path is outside the audio directory.")
            }
            if (destinationAudioFile.exists()) {
                throw IOException("A local file with this identifier already exists.")
            }
            audioFile = destinationAudioFile

            val safeArtwork = metadata.artwork?.takeIf { it.isNotEmpty() && it.size <= MAX_ARTWORK_BYTES }
            val artworkPath = if (safeArtwork != null) {
                if (!artworkFile.createNewFile()) throw IOException("Could not create local artwork file.")
                ownsArtworkFile = true
                FileOutputStream(artworkFile).use { it.write(safeArtwork) }
                durability.syncFile(artworkFile)
                artworkFile.canonicalPath
            } else {
                null
            }

            if (!temporaryFile.renameTo(destinationAudioFile)) throw IOException("Could not finish importing the audio file.")
            ownsTemporaryFile = false
            ownsAudioFile = true
            durability.syncDirectory(audioDirectory)

            val fallbackTitle = displayName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringBeforeLast('.')
            val track = Track(
                id = id,
                title = cleanMetadata(metadata.title, 180).ifEmpty { cleanMetadata(fallbackTitle, 180).ifEmpty { "Без названия" } },
                artist = cleanMetadata(metadata.artist, 120).ifEmpty { "Неизвестный исполнитель" },
                album = cleanMetadata(metadata.album, 180),
                durationMs = metadata.durationMs.coerceAtLeast(0),
                filePath = destinationAudioFile.canonicalPath,
                artworkPath = artworkPath,
            )
            completed = true
            return track
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            if (!completed) {
                var directoryEntriesChanged = false
                fun deleteOwnedFile(file: File, isOwned: Boolean) {
                    if (!isOwned || !PrivateAudioPath.isDirectChild(audioDirectory, file) || !file.exists()) return
                    try {
                        if (file.delete()) {
                            directoryEntriesChanged = true
                        } else if (file.exists()) {
                            throw IOException("Could not remove an incomplete private import file.")
                        }
                    } catch (cleanupFailure: Throwable) {
                        operationFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }

                deleteOwnedFile(temporaryFile, ownsTemporaryFile)
                audioFile?.let { deleteOwnedFile(it, ownsAudioFile) }
                deleteOwnedFile(artworkFile, ownsArtworkFile)
                if (directoryEntriesChanged) {
                    try {
                        durability.syncDirectory(audioDirectory)
                    } catch (cleanupSyncFailure: Throwable) {
                        operationFailure?.addSuppressed(cleanupSyncFailure)
                            ?: throw IOException("Could not durably clean up private import files.", cleanupSyncFailure)
                    }
                }
            }
        }
    }

    private fun canProbeByContent(mimeType: String?): Boolean {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase()
        return normalized == null || normalized.startsWith("audio/") || normalized in mimeTypesAllowedForContentProbe
    }

    private fun copyBounded(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        var nextSpaceCheckAt = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (copied + count > MAX_AUDIO_FILE_BYTES) {
                throw IOException("Audio file exceeds the 2 GiB import limit.")
            }
            if (copied >= nextSpaceCheckAt) {
                val available = availableBytes()
                if (available < count) {
                    throw IOException("There is not enough free space to import this file.")
                }
                nextSpaceCheckAt = copied + 512 * 1024L
            }
            output.write(buffer, 0, count)
            copied += count
        }
    }

    private fun cleanMetadata(value: String?, maximumLength: Int): String = value.orEmpty()
        .map { character -> if (character.isISOControl()) ' ' else character }
        .joinToString("")
        .trim()
        .take(maximumLength)
}
