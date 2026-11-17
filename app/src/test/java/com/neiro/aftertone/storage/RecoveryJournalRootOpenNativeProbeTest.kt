package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioDirectoryOpenStep
import java.io.FileDescriptor
import java.nio.file.AccessDeniedException
import kotlin.Function0
import kotlin.Function1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryJournalRootOpenNativeProbeTest {
    @Test
    fun rootProbeStageAndOutcomesHaveStableFixedCodes() {
        assertEquals("ROOT_OPEN_NATIVE_PROBE", RestoreRecoveryAttemptStage.fromCode("RP")?.name)
        assertEquals("ROOT_NATIVE_OPEN_SUCCEEDED", RestoreRecoveryAttemptOutcome.fromCode("50")?.name)
        assertEquals("ROOT_NATIVE_OPEN_ACCESS_DENIED", RestoreRecoveryAttemptOutcome.fromCode("51")?.name)
        assertEquals("ROOT_NATIVE_OPEN_SECURITY_DENIED", RestoreRecoveryAttemptOutcome.fromCode("52")?.name)
        assertEquals("ROOT_NATIVE_OPEN_OTHER_FAILURE", RestoreRecoveryAttemptOutcome.fromCode("53")?.name)
        assertEquals("ROOT_NATIVE_OPEN_SUCCEEDED_CLOSE_FAILED", RestoreRecoveryAttemptOutcome.fromCode("54")?.name)
    }

    @Test
    fun traceAppendsOnlyTheFixedRootProbeOutcomeCode() {
        val outcomeClass = runCatching {
            Class.forName("com.neiro.aftertone.storage.RootDirectoryNativeProbeOutcome")
        }.getOrNull()
        assertNotNull("The probe outcome should be a fixed enum.", outcomeClass)
        if (outcomeClass == null) return
        val success = outcomeClass.enumConstants?.firstOrNull { it.toString() == "NATIVE_OPEN_SUCCEEDED" }
        assertNotNull(success)

        val trace = RestoreRecoveryAttemptTrace.empty()
        val append = trace.javaClass.methods.firstOrNull {
            it.name == "appendJournalDiagnosis" && it.parameterCount == 3
        }
        assertNotNull("A fixed root-probe outcome must be appendable to the trace.", append)
        if (append == null || success == null) return

        val updated = append.invoke(
            trace,
            RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_ROOT_OPEN_FAILURE,
            null,
            success,
        )
        assertEquals("DIA-JC28-RP50", updated.javaClass.getMethod("getDisplayCode").invoke(updated))
    }

    @Test
    fun diagnosticRootOpenFailureCarriesOnlyAFixedProbeOutcome() {
        val failure = RecoveryJournalPrivateDirectoryOpenDiagnostics.failure(
            PrivateAudioDirectoryOpenStep.ROOT_DIRECTORY_OPEN,
            AccessDeniedException("/must-not-be-recorded", null, "must-not-be-recorded"),
        )
        val getter = failure.javaClass.methods.firstOrNull { it.name == "getRootProbeOutcome" }

        assertNotNull("The root-open diagnostic should carry a fixed probe outcome.", getter)
        val outcome = getter?.invoke(failure)
        assertNotNull(outcome)
        assertNull("The failure must not retain a message.", failure.message)
        assertNull("The failure must not retain a cause.", failure.cause)
        assertFalse(outcome.toString().contains("must-not-be-recorded"))
    }

    @Test
    fun nativeOpenSuccessClosesDescriptorAndReturnsFixedOutcome() {
        val descriptor = FileDescriptor()
        val closeCalls = mutableListOf<FileDescriptor>()
        val outcome = invokeProbe(
            openRoot = { descriptor },
            closeRoot = { descriptor -> closeCalls += descriptor },
            isAccessDenied = { false },
        )

        assertEquals("NATIVE_OPEN_SUCCEEDED", outcome)
        assertEquals(listOf(descriptor), closeCalls)
    }

    @Test
    fun nativeAccessDeniedMapsToFixedOutcomeWithoutRetainingExceptionText() {
        class SyntheticDeniedException : Exception("/sensitive/root and raw errno")

        val outcome = invokeProbe(
            openRoot = { throw SyntheticDeniedException() },
            closeRoot = { error("close must not run when open fails") },
            isAccessDenied = { it is SyntheticDeniedException },
        )

        assertEquals("NATIVE_OPEN_ACCESS_DENIED", outcome)
        assertFalse(outcome.contains("sensitive"))
        assertFalse(outcome.contains("errno"))
    }

    @Test
    fun onlyAllowlistedAccessDeniedErrnosMapToNativeSystemDenial() {
        assertEquals("NATIVE_OPEN_ACCESS_DENIED", invokeErrnoMapper(errno = 13, eacces = 13, eperm = 1))
        assertEquals("NATIVE_OPEN_ACCESS_DENIED", invokeErrnoMapper(errno = 1, eacces = 13, eperm = 1))
        assertEquals("NATIVE_OPEN_OTHER_FAILURE", invokeErrnoMapper(errno = 5, eacces = 13, eperm = 1))
    }

    @Test
    fun nativeSecurityAndOtherFailuresRemainDistinctFixedOutcomes() {
        val security = invokeProbe(
            openRoot = { throw SecurityException("private policy detail") },
            closeRoot = { error("close must not run when open fails") },
            isAccessDenied = { false },
        )
        val other = invokeProbe(
            openRoot = { throw IllegalStateException("private implementation detail") },
            closeRoot = { error("close must not run when open fails") },
            isAccessDenied = { false },
        )

        assertEquals("NATIVE_OPEN_SECURITY_DENIED", security)
        assertEquals("NATIVE_OPEN_OTHER_FAILURE", other)
    }

    @Test
    fun nativeCloseFailureDoesNotHideThatTheOpenSucceeded() {
        val outcome = invokeProbe(
            openRoot = { FileDescriptor() },
            closeRoot = { throw IllegalStateException("private close detail") },
            isAccessDenied = { false },
        )

        assertEquals("NATIVE_OPEN_SUCCEEDED_CLOSE_FAILED", outcome)
        assertFalse(outcome.contains("close detail"))
    }

    private fun invokeProbe(
        openRoot: () -> FileDescriptor,
        closeRoot: (FileDescriptor) -> Unit,
        isAccessDenied: (Exception) -> Boolean,
    ): String {
        val probeClass = runCatching {
            Class.forName("com.neiro.aftertone.storage.RootDirectoryNativeProbe")
        }.getOrNull()
        assertNotNull("A testable, bounded native root probe should exist.", probeClass)
        if (probeClass == null) return "MISSING_PROBE"

        val instance = probeClass.getField("INSTANCE").get(null)
        val method = probeClass.getDeclaredMethod(
            "run",
            Function0::class.java,
            Function1::class.java,
            Function1::class.java,
        )
        return method.invoke(instance, openRoot, closeRoot, isAccessDenied)?.toString() ?: "MISSING_OUTCOME"
    }

    private fun invokeErrnoMapper(errno: Int, eacces: Int, eperm: Int): String {
        val probeClass = runCatching {
            Class.forName("com.neiro.aftertone.storage.RootDirectoryNativeProbe")
        }.getOrNull()
        assertNotNull("The native errno mapper should exist.", probeClass)
        if (probeClass == null) return "MISSING_PROBE"
        val instance = probeClass.getField("INSTANCE").get(null)
        val method = probeClass.getDeclaredMethod(
            "outcomeForErrno",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        return method.invoke(instance, errno, eacces, eperm)?.toString() ?: "MISSING_OUTCOME"
    }
}
