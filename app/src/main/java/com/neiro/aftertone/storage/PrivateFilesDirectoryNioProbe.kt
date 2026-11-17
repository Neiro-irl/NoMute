package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

internal enum class PrivateFilesDirectoryNioProbeOutcome(
    val traceOutcome: RestoreRecoveryAttemptOutcome,
) {
    NIO_IDENTITY_CONFIRMED(RestoreRecoveryAttemptOutcome.NIO_DIRECTORY_IDENTITY_CONFIRMED),
    NIO_SECURE_STREAM_UNSUPPORTED(RestoreRecoveryAttemptOutcome.NIO_SECURE_STREAM_UNSUPPORTED),
    NIO_IDENTITY_UNCONFIRMED(RestoreRecoveryAttemptOutcome.NIO_DIRECTORY_IDENTITY_UNCONFIRMED),
    NIO_OPEN_ACCESS_DENIED(RestoreRecoveryAttemptOutcome.NIO_OPEN_ACCESS_DENIED),
    NIO_OPEN_SECURITY_DENIED(RestoreRecoveryAttemptOutcome.NIO_OPEN_SECURITY_DENIED),
    NIO_OPEN_OTHER_FAILURE(RestoreRecoveryAttemptOutcome.NIO_OPEN_OTHER_FAILURE),
    NIO_STREAM_CLOSE_FAILED(RestoreRecoveryAttemptOutcome.NIO_STREAM_CLOSE_FAILED),
}

internal object PrivateFilesDirectoryNioProbe {
    fun probe(filesDirectory: File): PrivateFilesDirectoryNioProbeOutcome = run(filesDirectory) { path ->
        Files.newDirectoryStream(path)
    }

    fun run(
        filesDirectory: File,
        openDirectory: (Path) -> DirectoryStream<Path>,
    ): PrivateFilesDirectoryNioProbeOutcome {
        val stream = try {
            openDirectory(filesDirectory.toPath())
        } catch (_: AccessDeniedException) {
            return PrivateFilesDirectoryNioProbeOutcome.NIO_OPEN_ACCESS_DENIED
        } catch (_: SecurityException) {
            return PrivateFilesDirectoryNioProbeOutcome.NIO_OPEN_SECURITY_DENIED
        } catch (_: Exception) {
            return PrivateFilesDirectoryNioProbeOutcome.NIO_OPEN_OTHER_FAILURE
        }

        val outcome = try {
            val secureStream = stream as? SecureDirectoryStream<*>
                ?: return closeAndReturn(stream, PrivateFilesDirectoryNioProbeOutcome.NIO_SECURE_STREAM_UNSUPPORTED)
            @Suppress("UNCHECKED_CAST")
            val pathStream = secureStream as SecureDirectoryStream<Path>
            if (PrivateAudioPath.isSameDirectory(filesDirectory, pathStream)) {
                PrivateFilesDirectoryNioProbeOutcome.NIO_IDENTITY_CONFIRMED
            } else {
                PrivateFilesDirectoryNioProbeOutcome.NIO_IDENTITY_UNCONFIRMED
            }
        } catch (_: Exception) {
            PrivateFilesDirectoryNioProbeOutcome.NIO_IDENTITY_UNCONFIRMED
        }
        return closeAndReturn(stream, outcome)
    }

    private fun closeAndReturn(
        stream: DirectoryStream<Path>,
        outcome: PrivateFilesDirectoryNioProbeOutcome,
    ): PrivateFilesDirectoryNioProbeOutcome = try {
        stream.close()
        outcome
    } catch (_: Exception) {
        PrivateFilesDirectoryNioProbeOutcome.NIO_STREAM_CLOSE_FAILED
    }
}

internal object PrivateFilesDirectoryNioProbeTrigger {
    fun probeIfRootOpenFailed(
        diagnosis: RecoveryJournalReadDiagnosis,
        filesDirectory: File,
        probe: (File) -> PrivateFilesDirectoryNioProbeOutcome = { directory ->
            PrivateFilesDirectoryNioProbe.probe(directory)
        },
    ): PrivateFilesDirectoryNioProbeOutcome? {
        if (diagnosis != RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_ROOT_OPEN_FAILURE) return null
        return try {
            probe(filesDirectory)
        } catch (_: Exception) {
            PrivateFilesDirectoryNioProbeOutcome.NIO_OPEN_OTHER_FAILURE
        }
    }
}
