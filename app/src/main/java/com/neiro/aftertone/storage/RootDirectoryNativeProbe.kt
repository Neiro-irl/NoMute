package com.neiro.aftertone.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.os.Build
import java.io.FileDescriptor

internal enum class RootDirectoryNativeProbeOutcome(
    val traceOutcome: RestoreRecoveryAttemptOutcome,
) {
    NATIVE_OPEN_SUCCEEDED(RestoreRecoveryAttemptOutcome.ROOT_NATIVE_OPEN_SUCCEEDED),
    NATIVE_OPEN_ACCESS_DENIED(RestoreRecoveryAttemptOutcome.ROOT_NATIVE_OPEN_ACCESS_DENIED),
    NATIVE_OPEN_SECURITY_DENIED(RestoreRecoveryAttemptOutcome.ROOT_NATIVE_OPEN_SECURITY_DENIED),
    NATIVE_OPEN_OTHER_FAILURE(RestoreRecoveryAttemptOutcome.ROOT_NATIVE_OPEN_OTHER_FAILURE),
    NATIVE_OPEN_SUCCEEDED_CLOSE_FAILED(RestoreRecoveryAttemptOutcome.ROOT_NATIVE_OPEN_SUCCEEDED_CLOSE_FAILED),
}

internal object RootDirectoryNativeProbe {
    fun probe(): RootDirectoryNativeProbeOutcome = run(
        openRoot = {
            Os.open(
                ROOT_DIRECTORY_PATH,
                OsConstants.O_RDONLY or LINUX_O_DIRECTORY or closeOnExecFlag(),
                0,
            )
        },
        closeRoot = { descriptor -> Os.close(descriptor) },
        isAccessDenied = { error ->
            error is ErrnoException && outcomeForErrno(
                error.errno,
                OsConstants.EACCES,
                OsConstants.EPERM,
            ) == RootDirectoryNativeProbeOutcome.NATIVE_OPEN_ACCESS_DENIED
        },
    )

    fun run(
        openRoot: () -> FileDescriptor,
        closeRoot: (FileDescriptor) -> Unit,
        isAccessDenied: (Exception) -> Boolean,
    ): RootDirectoryNativeProbeOutcome {
        val descriptor = try {
            openRoot()
        } catch (_: SecurityException) {
            return RootDirectoryNativeProbeOutcome.NATIVE_OPEN_SECURITY_DENIED
        } catch (error: Exception) {
            val denied = runCatching { isAccessDenied(error) }.getOrDefault(false)
            return if (denied) {
                RootDirectoryNativeProbeOutcome.NATIVE_OPEN_ACCESS_DENIED
            } else {
                RootDirectoryNativeProbeOutcome.NATIVE_OPEN_OTHER_FAILURE
            }
        }

        return try {
            closeRoot(descriptor)
            RootDirectoryNativeProbeOutcome.NATIVE_OPEN_SUCCEEDED
        } catch (_: Exception) {
            RootDirectoryNativeProbeOutcome.NATIVE_OPEN_SUCCEEDED_CLOSE_FAILED
        }
    }

    fun outcomeForErrno(
        errno: Int,
        accessDeniedErrno: Int,
        operationNotPermittedErrno: Int,
    ): RootDirectoryNativeProbeOutcome =
        if (errno == accessDeniedErrno || errno == operationNotPermittedErrno) {
            RootDirectoryNativeProbeOutcome.NATIVE_OPEN_ACCESS_DENIED
        } else {
            RootDirectoryNativeProbeOutcome.NATIVE_OPEN_OTHER_FAILURE
        }

    private fun closeOnExecFlag(): Int =
        if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else LINUX_O_CLOEXEC

    private const val ROOT_DIRECTORY_PATH = "/"
    private const val LINUX_O_CLOEXEC = 0x80000
    private const val LINUX_O_DIRECTORY = 0x10000
}
