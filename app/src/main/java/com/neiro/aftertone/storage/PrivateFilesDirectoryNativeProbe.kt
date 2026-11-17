package com.neiro.aftertone.storage

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

internal enum class PrivateFilesDirectoryNativeProbeOutcome(
    val traceOutcome: RestoreRecoveryAttemptOutcome,
) {
    NATIVE_OPEN_SUCCEEDED(RestoreRecoveryAttemptOutcome.PRIVATE_FILES_DIRECTORY_OPEN_SUCCEEDED),
    NATIVE_OPEN_ACCESS_DENIED(RestoreRecoveryAttemptOutcome.PRIVATE_FILES_DIRECTORY_OPEN_ACCESS_DENIED),
    NATIVE_OPEN_SECURITY_DENIED(RestoreRecoveryAttemptOutcome.PRIVATE_FILES_DIRECTORY_OPEN_SECURITY_DENIED),
    NATIVE_OPEN_OTHER_FAILURE(RestoreRecoveryAttemptOutcome.PRIVATE_FILES_DIRECTORY_OPEN_OTHER_FAILURE),
    NATIVE_OPEN_SUCCEEDED_CLOSE_FAILED(RestoreRecoveryAttemptOutcome.PRIVATE_FILES_DIRECTORY_OPEN_SUCCEEDED_CLOSE_FAILED),
}

internal object PrivateFilesDirectoryNativeProbe {
    fun probe(filesDirectory: File): PrivateFilesDirectoryNativeProbeOutcome = run(
        filesDirectory = filesDirectory,
        openDirectory = { path, flags, mode -> Os.open(path, flags, mode) },
        closeDirectory = { descriptor -> Os.close(descriptor) },
        isAccessDenied = { error ->
            error is ErrnoException && outcomeForErrno(
                error.errno,
                OsConstants.EACCES,
                OsConstants.EPERM,
            ) == PrivateFilesDirectoryNativeProbeOutcome.NATIVE_OPEN_ACCESS_DENIED
        },
    )

    fun run(
        filesDirectory: File,
        openDirectory: (String, Int, Int) -> FileDescriptor,
        closeDirectory: (FileDescriptor) -> Unit,
        isAccessDenied: (Exception) -> Boolean,
    ): PrivateFilesDirectoryNativeProbeOutcome {
        val descriptor = try {
            openDirectory(filesDirectory.absolutePath, directoryOpenFlags(), 0)
        } catch (_: SecurityException) {
            return PrivateFilesDirectoryNativeProbeOutcome.NATIVE_OPEN_SECURITY_DENIED
        } catch (error: Exception) {
            val denied = runCatching { isAccessDenied(error) }.getOrDefault(false)
            return if (denied) {
                PrivateFilesDirectoryNativeProbeOutcome.NATIVE_OPEN_ACCESS_DENIED
            } else {
                PrivateFilesDirectoryNativeProbeOutcome.NATIVE_OPEN_OTHER_FAILURE
            }
        }

        return try {
            closeDirectory(descriptor)
            PrivateFilesDirectoryNativeProbeOutcome.NATIVE_OPEN_SUCCEEDED
        } catch (_: Exception) {
            PrivateFilesDirectoryNativeProbeOutcome.NATIVE_OPEN_SUCCEEDED_CLOSE_FAILED
        }
    }

    fun outcomeForErrno(
        errno: Int,
        accessDeniedErrno: Int,
        operationNotPermittedErrno: Int,
    ): PrivateFilesDirectoryNativeProbeOutcome =
        if (errno == accessDeniedErrno || errno == operationNotPermittedErrno) {
            PrivateFilesDirectoryNativeProbeOutcome.NATIVE_OPEN_ACCESS_DENIED
        } else {
            PrivateFilesDirectoryNativeProbeOutcome.NATIVE_OPEN_OTHER_FAILURE
        }

    private fun directoryOpenFlags(): Int =
        OsConstants.O_RDONLY or LINUX_O_DIRECTORY or LINUX_O_NOFOLLOW or closeOnExecFlag()

    private fun closeOnExecFlag(): Int =
        if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else LINUX_O_CLOEXEC

    private const val LINUX_O_CLOEXEC = 0x80000
    private const val LINUX_O_DIRECTORY = 0x10000
    private const val LINUX_O_NOFOLLOW = 0x20000
}

internal object PrivateFilesDirectoryProbeTrigger {
    fun probeIfRootOpenFailed(
        diagnosis: RecoveryJournalReadDiagnosis,
        filesDirectory: File,
        probe: (File) -> PrivateFilesDirectoryNativeProbeOutcome = { directory ->
            PrivateFilesDirectoryNativeProbe.probe(directory)
        },
    ): PrivateFilesDirectoryNativeProbeOutcome? {
        if (diagnosis != RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_ROOT_OPEN_FAILURE) return null
        return try {
            probe(filesDirectory)
        } catch (_: Exception) {
            PrivateFilesDirectoryNativeProbeOutcome.NATIVE_OPEN_OTHER_FAILURE
        }
    }
}
