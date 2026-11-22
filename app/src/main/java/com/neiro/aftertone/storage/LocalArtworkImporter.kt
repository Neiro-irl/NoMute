package com.neiro.aftertone.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.PlaylistArtworkFileName
import com.neiro.aftertone.model.TrackArtworkFileName
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

internal fun interface TrackArtworkImporter {
    fun import(trackId: String, input: InputStream): String
}

internal fun interface PlaylistArtworkImporter {
    fun importPlaylistArtwork(playlistId: String, input: InputStream): String
}

internal fun interface ArtworkImageProcessor {
    fun process(source: File, destination: File)
}

internal fun interface ArtworkDirectorySynchronizer {
    fun sync(directory: File)
}

internal class LocalArtworkImporter(
    private val audioDirectory: File,
    private val imageProcessor: ArtworkImageProcessor = AndroidArtworkImageProcessor(),
    private val directorySynchronizer: ArtworkDirectorySynchronizer = AndroidArtworkDirectorySynchronizer(),
) : TrackArtworkImporter, PlaylistArtworkImporter {
    override fun import(trackId: String, input: InputStream): String {
        require(trackId.matches(SAFE_TRACK_ID)) { "The track ID is not a safe file name." }
        return importArtwork(
            finalFileName = TrackArtworkFileName.forCustomCover(trackId),
            isOwnedFileName = { fileName -> TrackArtworkFileName.isGeneratedFileName(trackId, fileName) },
            input = input,
        )
    }

    override fun importPlaylistArtwork(playlistId: String, input: InputStream): String {
        require(PlaylistArtworkFileName.isSafePlaylistId(playlistId)) { "The playlist ID is not a safe file name." }
        return importArtwork(
            finalFileName = PlaylistArtworkFileName.forCustomCover(playlistId),
            isOwnedFileName = { fileName -> PlaylistArtworkFileName.isGeneratedFileName(playlistId, fileName) },
            input = input,
        )
    }

    private fun importArtwork(
        finalFileName: String,
        isOwnedFileName: (String) -> Boolean,
        input: InputStream,
    ): String {
        ensureAudioDirectory()

        val stagedSource = createStageFile(".part")
        var stagedArtwork: File? = null
        var publishedArtwork: File? = null
        try {
            copyBoundedSource(input, stagedSource)
            stagedArtwork = createStageFile(".img")
            imageProcessor.process(stagedSource, stagedArtwork)
            requireValidProcessedImage(stagedArtwork)
            RandomAccessFile(stagedArtwork, "rw").use { it.fd.sync() }

            val finalArtwork = File(audioDirectory, finalFileName)
            if (!isOwnedFileName(finalArtwork.name) ||
                !PrivateAudioPath.isDirectChild(audioDirectory, finalArtwork) ||
                finalArtwork.exists()
            ) {
                throw IOException("Could not safely publish the selected cover image.")
            }
            Files.move(stagedArtwork.toPath(), finalArtwork.toPath(), StandardCopyOption.ATOMIC_MOVE)
            publishedArtwork = finalArtwork
            directorySynchronizer.sync(audioDirectory)
            if (!isOwnedGeneratedArtwork(finalArtwork, isOwnedFileName)) {
                throw SecurityException("The cover image was published outside private storage.")
            }
            return finalArtwork.canonicalPath
        } catch (error: Exception) {
            publishedArtwork?.let { file -> runCatching { deleteGeneratedArtwork(file, isOwnedFileName) } }
            throw error
        } finally {
            deleteStageFile(stagedSource)
            stagedArtwork?.let(::deleteStageFile)
        }
    }

    private fun ensureAudioDirectory() {
        val parent = audioDirectory.parentFile ?: throw SecurityException("The private audio directory has no parent.")
        if (Files.isSymbolicLink(audioDirectory.toPath()) ||
            (!audioDirectory.exists() && !audioDirectory.mkdirs()) ||
            !audioDirectory.isDirectory ||
            !PrivateAudioPath.isDirectChild(parent, audioDirectory)
        ) {
            throw IOException("The private audio directory is unavailable or redirected.")
        }
    }

    private fun createStageFile(suffix: String): File {
        repeat(MAX_STAGE_ATTEMPTS) {
            val candidate = File(audioDirectory, "${UUID.randomUUID()}$suffix")
            if (PrivateAudioPath.isDirectChild(audioDirectory, candidate) && candidate.createNewFile()) return candidate
        }
        throw IOException("Could not create private cover-image staging storage.")
    }

    private fun copyBoundedSource(input: InputStream, destination: File) {
        var totalBytes = 0L
        FileOutputStream(destination, false).use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val nextByte = input.read()
                    if (nextByte < 0) break
                    totalBytes++
                    if (totalBytes > MAX_INPUT_BYTES) throw IOException("The selected cover image is too large.")
                    output.write(nextByte)
                    continue
                }
                totalBytes += count
                if (totalBytes > MAX_INPUT_BYTES) throw IOException("The selected cover image is too large.")
                output.write(buffer, 0, count)
            }
            if (totalBytes == 0L) throw IOException("The selected cover image is empty.")
            output.fd.sync()
        }
    }

    private fun requireValidProcessedImage(file: File) {
        val path = file.toPath()
        if (!PrivateAudioPath.isDirectChild(audioDirectory, file) ||
            Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            file.length() !in 1..MAX_OUTPUT_BYTES
        ) {
            throw IOException("The selected cover image could not be safely processed.")
        }
    }

    private fun isOwnedGeneratedArtwork(file: File, isOwnedFileName: (String) -> Boolean): Boolean =
        isOwnedFileName(file.name) &&
            !Files.isSymbolicLink(file.toPath()) &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            PrivateAudioPath.isDirectChild(audioDirectory, file)

    private fun deleteGeneratedArtwork(file: File, isOwnedFileName: (String) -> Boolean) {
        if (isOwnedGeneratedArtwork(file, isOwnedFileName)) Files.deleteIfExists(file.toPath())
    }

    private fun deleteStageFile(file: File) {
        runCatching {
            if (PrivateAudioPath.isDirectChild(audioDirectory, file) &&
                !Files.isSymbolicLink(file.toPath()) &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                Files.deleteIfExists(file.toPath())
            }
        }
    }

    internal companion object {
        const val MAX_INPUT_BYTES = 25L * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 2L * 1024 * 1024
        const val MAX_SOURCE_DIMENSION = 12_000
        const val MAX_SOURCE_PIXELS = 40_000_000L
        const val MAX_DECODED_DIMENSION = 1_024
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val MAX_STAGE_ATTEMPTS = 4
        private val SAFE_TRACK_ID = Regex("[A-Za-z0-9_-]{1,80}")
    }
}

private class AndroidArtworkImageProcessor : ArtworkImageProcessor {
    override fun process(source: File, destination: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (bounds.outMimeType?.startsWith("image/") != true ||
            width <= 0 || height <= 0 ||
            width > LocalArtworkImporter.MAX_SOURCE_DIMENSION ||
            height > LocalArtworkImporter.MAX_SOURCE_DIMENSION ||
            width.toLong() * height.toLong() > LocalArtworkImporter.MAX_SOURCE_PIXELS
        ) {
            throw IOException("The selected file is not a supported image or exceeds image limits.")
        }

        val sampleSize = calculateSampleSize(width, height)
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw IOException("The selected cover image could not be decoded.")

        var oriented: Bitmap? = null
        try {
            val orientation = ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
            oriented = applyExifOrientation(decoded, orientation)
            val bitmapToSave = oriented ?: decoded
            if (bitmapToSave.width > LocalArtworkImporter.MAX_DECODED_DIMENSION ||
                bitmapToSave.height > LocalArtworkImporter.MAX_DECODED_DIMENSION
            ) {
                throw IOException("The selected cover image could not be reduced to a safe size.")
            }
            FileOutputStream(destination, false).use { output ->
                @Suppress("DEPRECATION")
                val compressed = bitmapToSave.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, output)
                if (!compressed) throw IOException("The selected cover image could not be normalized.")
                output.fd.sync()
            }
        } finally {
            oriented?.takeIf { it !== decoded }?.recycle()
            decoded.recycle()
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        val longestDimension = maxOf(width, height)
        var sampleSize = 1
        while (longestDimension / sampleSize > LocalArtworkImporter.MAX_DECODED_DIMENSION) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap? {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return null
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val WEBP_QUALITY = 90
    }
}

private class AndroidArtworkDirectorySynchronizer : ArtworkDirectorySynchronizer {
    override fun sync(directory: File) {
        try {
            val descriptor = Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0,
            )
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        } catch (error: Exception) {
            throw IOException("Could not make the selected cover durable.", error)
        }
    }
}
