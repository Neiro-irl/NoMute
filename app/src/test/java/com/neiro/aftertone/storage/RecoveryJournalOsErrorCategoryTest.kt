package com.neiro.aftertone.storage

import android.system.ErrnoException
import android.system.OsConstants
import com.neiro.aftertone.model.PrivateAudioDirectoryOpenStep
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryJournalOsErrorCategoryTest {
    @Test
    fun rootOpenErrorUsesSpecificAllowlistedCategoryWithoutRetainingCause() {
        val cases = listOf(
            Case(
                error = AccessDeniedException("/private/root", null, "private access detail"),
                expectedCategory = "NIO_ACCESS_DENIED",
                expectedTrace = "JC28.OE44",
            ),
            Case(
                error = IOException("private wrapper", ErrnoException("open", OsConstants.EACCES)),
                expectedCategory = "ACCESS_DENIED",
                expectedTrace = "JC28.OE40",
            ),
            Case(
                error = IOException(
                    "private wrapper",
                    AccessDeniedException("/private/root", null, "private access detail"),
                ),
                expectedCategory = "NIO_ACCESS_DENIED",
                expectedTrace = "JC28.OE44",
            ),
            Case(
                error = SecurityException("private security detail"),
                expectedCategory = "SECURITY_EXCEPTION",
                expectedTrace = "JC28.OE45",
            ),
            Case(
                error = FileSystemException("/private/root", null, "private filesystem detail"),
                expectedCategory = "NIO_FILESYSTEM_EXCEPTION",
                expectedTrace = "JC28.OE46",
            ),
            Case(
                error = IOException(
                    "private wrapper",
                    FileSystemException("/private/root", null, "private filesystem detail"),
                ),
                expectedCategory = "NIO_FILESYSTEM_EXCEPTION",
                expectedTrace = "JC28.OE46",
            ),
            Case(
                error = IOException("private wrapper and raw detail"),
                expectedCategory = "IO_FAILURE",
                expectedTrace = "JC28.OE42",
            ),
        )

        cases.forEach { case ->
            val failure = RecoveryJournalPrivateDirectoryOpenDiagnostics.failure(
                PrivateAudioDirectoryOpenStep.ROOT_DIRECTORY_OPEN,
                case.error,
            )

            assertEquals(case.expectedCategory, failure.osErrorCategory?.name)
            assertNull("The raw exception message must not be exposed.", failure.message)
            assertNull("The raw exception/cause must not be retained.", failure.cause)
            val trace = RestoreRecoveryAttemptTrace.empty().appendJournalDiagnosis(
                failure.diagnosis,
                failure.osErrorCategory,
            )
            assertEquals(case.expectedTrace, trace.encode())
        }
    }

    @Test
    fun nonRootPrivateDirectoryFailureKeepsExistingCoarseAccessDeniedCategory() {
        val failure = RecoveryJournalPrivateDirectoryOpenDiagnostics.failure(
            PrivateAudioDirectoryOpenStep.ROOT_STREAM_CLOSE,
            SecurityException("private security detail"),
        )

        assertEquals("ACCESS_DENIED", failure.osErrorCategory?.name)
        val trace = RestoreRecoveryAttemptTrace.empty().appendJournalDiagnosis(
            failure.diagnosis,
            failure.osErrorCategory,
        )
        assertEquals("JC30.OE40", trace.encode())
    }

    private data class Case(
        val error: Exception,
        val expectedCategory: String,
        val expectedTrace: String,
    )
}
