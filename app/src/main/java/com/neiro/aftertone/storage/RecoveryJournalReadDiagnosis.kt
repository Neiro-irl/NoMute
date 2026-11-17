package com.neiro.aftertone.storage

import android.system.ErrnoException
import android.system.OsConstants
import com.neiro.aftertone.model.PrivateAudioDirectoryOpenStep
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException

private const val MAX_RECOVERY_JOURNAL_OS_CAUSE_DEPTH = 8

internal enum class RecoveryJournalOsErrorCategory(
    val traceOutcome: RestoreRecoveryAttemptOutcome,
) {
    ACCESS_DENIED(RestoreRecoveryAttemptOutcome.JOURNAL_OS_ACCESS_DENIED),
    NIO_ACCESS_DENIED(RestoreRecoveryAttemptOutcome.JOURNAL_OS_NIO_ACCESS_DENIED),
    SECURITY_EXCEPTION(RestoreRecoveryAttemptOutcome.JOURNAL_OS_SECURITY_EXCEPTION),
    NIO_FILESYSTEM_EXCEPTION(RestoreRecoveryAttemptOutcome.JOURNAL_OS_NIO_FILESYSTEM_EXCEPTION),
    RESOURCE_EXHAUSTION(RestoreRecoveryAttemptOutcome.JOURNAL_OS_RESOURCE_EXHAUSTION),
    IO_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_OS_IO_FAILURE),
    UNKNOWN(RestoreRecoveryAttemptOutcome.JOURNAL_OS_UNKNOWN),
}

internal enum class RecoveryJournalReadDiagnosis(
    val traceOutcome: RestoreRecoveryAttemptOutcome,
) {
    DIRECTORY_PATH_VALIDATION_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_DIRECTORY_PATH_VALIDATION_FAILURE),
    DIRECTORY_OPEN_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_DIRECTORY_OPEN_FAILURE_SPECIFIC),
    DIRECTORY_OPEN_SYSCALL_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_DIRECTORY_OPEN_SYSCALL_FAILURE),
    DIRECTORY_DESCRIPTOR_DUPLICATION_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_DIRECTORY_DESCRIPTOR_DUPLICATION_FAILURE),
    DIRECTORY_DESCRIPTOR_STAT_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_DIRECTORY_DESCRIPTOR_STAT_FAILURE),
    DIRECTORY_PATH_IDENTITY_STAT_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_DIRECTORY_PATH_IDENTITY_STAT_FAILURE),
    SECURE_DIRECTORY_PATH_RESOLUTION_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_PRIVATE_DIRECTORY_PATH_RESOLUTION_FAILURE),
    SECURE_DIRECTORY_ROOT_OPEN_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_PRIVATE_DIRECTORY_ROOT_OPEN_FAILURE),
    SECURE_DIRECTORY_STREAM_UNSUPPORTED(RestoreRecoveryAttemptOutcome.JOURNAL_PRIVATE_DIRECTORY_STREAM_UNSUPPORTED),
    SECURE_DIRECTORY_ROOT_CLOSE_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_PRIVATE_DIRECTORY_ROOT_CLOSE_FAILURE),
    SECURE_DIRECTORY_COMPONENT_OPEN_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_PRIVATE_DIRECTORY_COMPONENT_OPEN_FAILURE),
    SECURE_DIRECTORY_PARENT_CLOSE_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_PRIVATE_DIRECTORY_PARENT_CLOSE_FAILURE),
    JOURNAL_FILE_OPEN_FAILURE(RestoreRecoveryAttemptOutcome.JOURNAL_FILE_OPEN_FAILURE),
    MISSING_OR_SPECIAL_FILE(RestoreRecoveryAttemptOutcome.JOURNAL_MISSING_OR_SPECIAL_FILE),
    JOURNAL_NOT_PRESENT(RestoreRecoveryAttemptOutcome.JOURNAL_NOT_PRESENT),
    JOURNAL_NON_REGULAR_OR_SYMLINK(RestoreRecoveryAttemptOutcome.JOURNAL_NON_REGULAR_OR_SYMLINK),
    JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED(RestoreRecoveryAttemptOutcome.JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED),
    SIZE_CHANGE_OR_TRUNCATION(RestoreRecoveryAttemptOutcome.JOURNAL_SIZE_CHANGE_OR_TRUNCATION),
    UNSUPPORTED_HEADER_VERSION(RestoreRecoveryAttemptOutcome.JOURNAL_UNSUPPORTED_HEADER_VERSION),
    BAD_SERIALIZATION(RestoreRecoveryAttemptOutcome.JOURNAL_BAD_SERIALIZATION),
    UNSAFE_PATH(RestoreRecoveryAttemptOutcome.JOURNAL_UNSAFE_PATH),
    BAD_INTEGRITY_METADATA(RestoreRecoveryAttemptOutcome.JOURNAL_BAD_INTEGRITY_METADATA),
    TRAILING_BYTES(RestoreRecoveryAttemptOutcome.JOURNAL_TRAILING_BYTES),
    READABLE(RestoreRecoveryAttemptOutcome.JOURNAL_READABLE),
    SAFE_UNKNOWN(RestoreRecoveryAttemptOutcome.JOURNAL_SAFE_UNKNOWN),
}

internal enum class RecoveryJournalDirectoryOpenStep(val diagnosis: RecoveryJournalReadDiagnosis) {
    DIRECTORY_OPEN(RecoveryJournalReadDiagnosis.DIRECTORY_OPEN_SYSCALL_FAILURE),
    DESCRIPTOR_DUPLICATION(RecoveryJournalReadDiagnosis.DIRECTORY_DESCRIPTOR_DUPLICATION_FAILURE),
    DIRECTORY_DESCRIPTOR_STAT(RecoveryJournalReadDiagnosis.DIRECTORY_DESCRIPTOR_STAT_FAILURE),
    DIRECTORY_PATH_IDENTITY_STAT(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_IDENTITY_STAT_FAILURE),
}

internal data class RecoveryJournalReadClassification(
    val diagnosis: RecoveryJournalReadDiagnosis,
    val osErrorCategory: RecoveryJournalOsErrorCategory? = null,
    val rootProbeOutcome: RootDirectoryNativeProbeOutcome? = null,
    val privateFilesDirectoryProbeOutcome: PrivateFilesDirectoryNativeProbeOutcome? = null,
    val privateFilesDirectoryNioProbeOutcome: PrivateFilesDirectoryNioProbeOutcome? = null,
)

internal class RecoveryJournalReadFailure @JvmOverloads constructor(
    val diagnosis: RecoveryJournalReadDiagnosis,
    val osErrorCategory: RecoveryJournalOsErrorCategory? = null,
    val rootProbeOutcome: RootDirectoryNativeProbeOutcome? = null,
) : IOException()

internal object RecoveryJournalDirectoryOpenDiagnostics {
    @JvmStatic
    fun <T> run(step: RecoveryJournalDirectoryOpenStep, operation: () -> T): T = try {
        operation()
    } catch (failure: RecoveryJournalReadFailure) {
        throw failure
    } catch (error: Exception) {
        throw RecoveryJournalReadFailure(step.diagnosis, recoveryJournalOsErrorCategory(error))
    }
}

internal object RecoveryJournalPrivateDirectoryOpenDiagnostics {
    @JvmStatic
    fun failure(step: PrivateAudioDirectoryOpenStep, error: Exception): RecoveryJournalReadFailure =
        RecoveryJournalReadFailure(
            diagnosis = when (step) {
                PrivateAudioDirectoryOpenStep.PATH_RESOLUTION -> RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_PATH_RESOLUTION_FAILURE
                PrivateAudioDirectoryOpenStep.ROOT_DIRECTORY_OPEN -> RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_ROOT_OPEN_FAILURE
                PrivateAudioDirectoryOpenStep.SECURE_STREAM_UNSUPPORTED -> RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_STREAM_UNSUPPORTED
                PrivateAudioDirectoryOpenStep.ROOT_STREAM_CLOSE -> RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_ROOT_CLOSE_FAILURE
                PrivateAudioDirectoryOpenStep.COMPONENT_DIRECTORY_OPEN -> RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_COMPONENT_OPEN_FAILURE
                PrivateAudioDirectoryOpenStep.PREVIOUS_DIRECTORY_CLOSE -> RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_PARENT_CLOSE_FAILURE
            },
            osErrorCategory = if (step == PrivateAudioDirectoryOpenStep.ROOT_DIRECTORY_OPEN) {
                recoveryJournalPrivateDirectoryOsErrorCategory(error)
            } else {
                recoveryJournalOsErrorCategory(error)
            },
            rootProbeOutcome = if (step == PrivateAudioDirectoryOpenStep.ROOT_DIRECTORY_OPEN) {
                RootDirectoryNativeProbe.probe()
            } else {
                null
            },
        )
}

private fun recoveryJournalPrivateDirectoryOsErrorCategory(error: Throwable): RecoveryJournalOsErrorCategory {
    var current: Throwable? = error
    var depth = 0
    var sawNioFileSystemException = false
    while (current != null && depth < MAX_RECOVERY_JOURNAL_OS_CAUSE_DEPTH) {
        val cause = current
        when (cause) {
            is ErrnoException -> return recoveryJournalOsErrorCategoryForErrno(cause.errno)
            is AccessDeniedException -> return RecoveryJournalOsErrorCategory.NIO_ACCESS_DENIED
            is SecurityException -> return RecoveryJournalOsErrorCategory.SECURITY_EXCEPTION
            is FileSystemException -> sawNioFileSystemException = true
        }
        current = cause.cause
        depth += 1
    }
    return if (sawNioFileSystemException) {
        RecoveryJournalOsErrorCategory.NIO_FILESYSTEM_EXCEPTION
    } else {
        recoveryJournalOsErrorCategory(error)
    }
}

internal fun recoveryJournalOsErrorCategory(error: Throwable): RecoveryJournalOsErrorCategory {
    var current: Throwable? = error
    var depth = 0
    var sawIOException = false
    while (current != null && depth < MAX_RECOVERY_JOURNAL_OS_CAUSE_DEPTH) {
        val cause = current
        when (cause) {
            is ErrnoException -> return recoveryJournalOsErrorCategoryForErrno(cause.errno)
            is SecurityException, is AccessDeniedException -> return RecoveryJournalOsErrorCategory.ACCESS_DENIED
            is IOException -> sawIOException = true
        }
        current = cause.cause
        depth += 1
    }
    return if (sawIOException) {
        RecoveryJournalOsErrorCategory.IO_FAILURE
    } else {
        RecoveryJournalOsErrorCategory.UNKNOWN
    }
}

internal fun recoveryJournalOsErrorCategoryForErrno(errno: Int): RecoveryJournalOsErrorCategory = when (errno) {
    OsConstants.EACCES, OsConstants.EPERM -> RecoveryJournalOsErrorCategory.ACCESS_DENIED
    OsConstants.EMFILE, OsConstants.ENFILE, OsConstants.ENOMEM, OsConstants.ENOSPC ->
        RecoveryJournalOsErrorCategory.RESOURCE_EXHAUSTION
    OsConstants.EIO -> RecoveryJournalOsErrorCategory.IO_FAILURE
    else -> RecoveryJournalOsErrorCategory.UNKNOWN
}
