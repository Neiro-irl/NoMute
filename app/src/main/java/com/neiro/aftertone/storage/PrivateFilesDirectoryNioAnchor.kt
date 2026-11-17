package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import java.io.File
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

internal object PrivateFilesDirectoryNioAnchor {
    fun open(filesDirectory: File): SecureDirectoryStream<Path> = open(filesDirectory) { path ->
        Files.newDirectoryStream(path)
    }

    fun open(
        filesDirectory: File,
        openDirectory: (Path) -> DirectoryStream<Path>,
    ): SecureDirectoryStream<Path> {
        val path = try {
            filesDirectory.toPath().toAbsolutePath().normalize()
        } catch (error: Exception) {
            throw RecoveryJournalReadFailure(
                RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_PATH_RESOLUTION_FAILURE,
                recoveryJournalOsErrorCategory(error),
            )
        }
        val stream = try {
            openDirectory(path)
        } catch (failure: RecoveryJournalReadFailure) {
            throw failure
        } catch (error: Exception) {
            throw RecoveryJournalReadFailure(
                RecoveryJournalReadDiagnosis.DIRECTORY_OPEN_FAILURE,
                recoveryJournalOsErrorCategory(error),
            )
        }

        val secureStream = stream as? SecureDirectoryStream<*>
            ?: closeAndFail(stream, RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_STREAM_UNSUPPORTED)
        @Suppress("UNCHECKED_CAST")
        val pathStream = secureStream as SecureDirectoryStream<Path>
        if (!PrivateAudioPath.isSameDirectory(filesDirectory, pathStream)) {
            closeAndFail(stream, RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE)
        }
        return pathStream
    }

    private fun closeAndFail(
        stream: DirectoryStream<Path>,
        diagnosis: RecoveryJournalReadDiagnosis,
    ): Nothing {
        try {
            stream.close()
        } catch (_: Exception) {
            throw RecoveryJournalReadFailure(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN)
        }
        throw RecoveryJournalReadFailure(diagnosis)
    }
}
