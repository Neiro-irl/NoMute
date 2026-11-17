package com.neiro.aftertone.storage

import java.io.File
import java.io.FileDescriptor
import kotlin.Function1
import kotlin.Function3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryJournalPrivateFilesDirectoryOpenProbeTest {
    @Test
    fun privateDirectoryProbeHasFixedStageAndOutcomeCodes() {
        assertEquals("PRIVATE_FILES_DIRECTORY_NATIVE_PROBE", RestoreRecoveryAttemptStage.fromCode("PN")?.name)
        assertEquals("PRIVATE_FILES_DIRECTORY_OPEN_SUCCEEDED", RestoreRecoveryAttemptOutcome.fromCode("60")?.name)
        assertEquals("PRIVATE_FILES_DIRECTORY_OPEN_ACCESS_DENIED", RestoreRecoveryAttemptOutcome.fromCode("61")?.name)
        assertEquals("PRIVATE_FILES_DIRECTORY_OPEN_SECURITY_DENIED", RestoreRecoveryAttemptOutcome.fromCode("62")?.name)
        assertEquals("PRIVATE_FILES_DIRECTORY_OPEN_OTHER_FAILURE", RestoreRecoveryAttemptOutcome.fromCode("63")?.name)
        assertEquals("PRIVATE_FILES_DIRECTORY_OPEN_SUCCEEDED_CLOSE_FAILED", RestoreRecoveryAttemptOutcome.fromCode("64")?.name)
    }

    @Test
    fun readOnlyDirectoryOpenUsesOnlyDirectoryFlagsAndClosesExactlyOnce() {
        val filesDirectory = File("/private/app/files")
        val descriptor = FileDescriptor()
        val openedPaths = mutableListOf<String>()
        val openedFlags = mutableListOf<Int>()
        val openModes = mutableListOf<Int>()
        val closedDescriptors = mutableListOf<FileDescriptor>()

        val outcome = invokeRun(
            filesDirectory = filesDirectory,
            openDirectory = { path, flags, mode ->
                openedPaths += path
                openedFlags += flags
                openModes += mode
                descriptor
            },
            closeDirectory = { closedDescriptors += it },
            isAccessDenied = { false },
        )

        assertEquals("NATIVE_OPEN_SUCCEEDED", outcome)
        assertEquals(listOf(filesDirectory.absolutePath), openedPaths)
        assertEquals(1, openedFlags.size)
        assertEquals(0x10000 or 0x80000 or 0x20000, openedFlags.single())
        assertEquals(listOf(0), openModes)
        assertEquals(listOf(descriptor), closedDescriptors)
    }

    @Test
    fun openErrorsAndCloseErrorsReturnOnlyFixedOutcomes() {
        class SyntheticDenied : Exception("private path and errno must not escape")

        assertEquals(
            "NATIVE_OPEN_ACCESS_DENIED",
            invokeRun(
                File("/private/app/files"),
                openDirectory = { _, _, _ -> throw SyntheticDenied() },
                closeDirectory = { error("close must not run") },
                isAccessDenied = { it is SyntheticDenied },
            ),
        )
        assertEquals(
            "NATIVE_OPEN_SECURITY_DENIED",
            invokeRun(
                File("/private/app/files"),
                openDirectory = { _, _, _ -> throw SecurityException("private policy detail") },
                closeDirectory = { error("close must not run") },
                isAccessDenied = { false },
            ),
        )
        assertEquals(
            "NATIVE_OPEN_OTHER_FAILURE",
            invokeRun(
                File("/private/app/files"),
                openDirectory = { _, _, _ -> throw IllegalStateException("private implementation detail") },
                closeDirectory = { error("close must not run") },
                isAccessDenied = { false },
            ),
        )
        assertEquals(
            "NATIVE_OPEN_SUCCEEDED_CLOSE_FAILED",
            invokeRun(
                File("/private/app/files"),
                openDirectory = { _, _, _ -> FileDescriptor() },
                closeDirectory = { throw IllegalStateException("private close detail") },
                isAccessDenied = { false },
            ),
        )
    }

    @Test
    fun privateFilesProbeRunsOnlyAfterTheRootDirectoryOpenFailure() {
        val filesDirectory = File("/private/app/files")
        val probeCalls = mutableListOf<File>()
        val outcomeClass = enumClass("PrivateFilesDirectoryNativeProbeOutcome") ?: return
        val success = outcomeClass.enumConstants?.firstOrNull { it.toString() == "NATIVE_OPEN_SUCCEEDED" }
        assertNotNull("A fixed private-directory outcome should exist.", success)
        if (success == null) return

        val rootFailure = invokeProbeTrigger(
            RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_ROOT_OPEN_FAILURE,
            filesDirectory,
            { directory -> probeCalls += directory; success },
        )
        assertEquals("NATIVE_OPEN_SUCCEEDED", rootFailure?.toString())
        assertEquals(listOf(filesDirectory), probeCalls)

        probeCalls.clear()
        val componentFailure = invokeProbeTrigger(
            RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_COMPONENT_OPEN_FAILURE,
            filesDirectory,
            { directory -> probeCalls += directory; success },
        )
        assertNull(componentFailure)
        assertEquals(emptyList<File>(), probeCalls)
    }

    @Test
    fun traceContainsOnlyFixedPrivateDirectoryOutcomeAndNoPathOrErrorDetails() {
        val outcomeClass = enumClass("PrivateFilesDirectoryNativeProbeOutcome") ?: return
        val success = outcomeClass.enumConstants?.firstOrNull { it.toString() == "NATIVE_OPEN_SUCCEEDED" }
        assertNotNull("A fixed private-directory outcome should exist.", success)
        if (success == null) return

        val append = RestoreRecoveryAttemptTrace::class.java.methods.firstOrNull {
            it.name == "appendJournalDiagnosis" && it.parameterCount == 4
        }
        assertNotNull("Trace must accept a fixed private-directory probe result.", append)
        if (append == null) return

        val trace = append.invoke(
            RestoreRecoveryAttemptTrace.empty(),
            RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_ROOT_OPEN_FAILURE,
            RecoveryJournalOsErrorCategory.NIO_ACCESS_DENIED,
            RootDirectoryNativeProbeOutcome.NATIVE_OPEN_ACCESS_DENIED,
            success,
        ) as RestoreRecoveryAttemptTrace
        val code = trace.displayCode

        assertEquals("DIA-JC28-OE44-RP51-PN60", code)
        assertFalse(code.orEmpty().contains("private/app"))
        assertFalse(code.orEmpty().contains("errno"))
    }

    private fun invokeRun(
        filesDirectory: File,
        openDirectory: (String, Int, Int) -> FileDescriptor,
        closeDirectory: (FileDescriptor) -> Unit,
        isAccessDenied: (Exception) -> Boolean,
    ): String {
        val probeClass = Class.forName("com.neiro.aftertone.storage.PrivateFilesDirectoryNativeProbe")
        val instance = probeClass.getField("INSTANCE").get(null)
        val method = probeClass.getDeclaredMethod(
            "run",
            File::class.java,
            Function3::class.java,
            Function1::class.java,
            Function1::class.java,
        )
        return method.invoke(instance, filesDirectory, openDirectory, closeDirectory, isAccessDenied)?.toString()
            ?: "MISSING_OUTCOME"
    }

    private fun invokeProbeTrigger(
        diagnosis: RecoveryJournalReadDiagnosis,
        filesDirectory: File,
        probe: (File) -> Any,
    ): Any? {
        val triggerClass = Class.forName("com.neiro.aftertone.storage.PrivateFilesDirectoryProbeTrigger")
        val instance = triggerClass.getField("INSTANCE").get(null)
        val method = triggerClass.getDeclaredMethod(
            "probeIfRootOpenFailed",
            RecoveryJournalReadDiagnosis::class.java,
            File::class.java,
            Function1::class.java,
        )
        return method.invoke(instance, diagnosis, filesDirectory, probe)
    }

    private fun enumClass(name: String): Class<*>? = runCatching {
        Class.forName("com.neiro.aftertone.storage.$name")
    }.getOrNull().also {
        assertNotNull("A fixed private-directory probe enum should exist.", it)
    }
}
