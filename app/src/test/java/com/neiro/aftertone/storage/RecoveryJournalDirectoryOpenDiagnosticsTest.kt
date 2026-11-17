package com.neiro.aftertone.storage

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryJournalDirectoryOpenDiagnosticsTest {
    @Test
    fun eachDirectoryHandleSetupStepHasItsOwnSanitizedFixedCode() {
        val maybeStepClass = optionalClass("com.neiro.aftertone.storage.RecoveryJournalDirectoryOpenStep")
        assertNotNull("Directory handle setup needs an allowlisted step model.", maybeStepClass)
        val stepClass = maybeStepClass!!
        val maybeDiagnosticsClass = optionalClass("com.neiro.aftertone.storage.RecoveryJournalDirectoryOpenDiagnostics")
        assertNotNull("Directory setup failures need one shared classifier.", maybeDiagnosticsClass)
        val diagnosticsClass = maybeDiagnosticsClass!!
        val failureClass = Class.forName("com.neiro.aftertone.storage.RecoveryJournalReadFailure")
        val run = diagnosticsClass.getDeclaredMethod("run", stepClass, kotlin.Function0::class.java).apply { isAccessible = true }
        val traceClass = Class.forName("com.neiro.aftertone.storage.RestoreRecoveryAttemptTrace")
        val companion = traceClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val empty = companion.javaClass.methods.first { it.name == "empty" && it.parameterCount == 0 }
        val appendDiagnosis = traceClass.methods.first { it.name == "appendJournalDiagnosis" && it.parameterCount == 2 }
        val encode = traceClass.getMethod("encode")
        val cases = listOf(
            "DIRECTORY_OPEN" to "JC23.OE42",
            "DESCRIPTOR_DUPLICATION" to "JC24.OE42",
            "DIRECTORY_DESCRIPTOR_STAT" to "JC25.OE42",
            "DIRECTORY_PATH_IDENTITY_STAT" to "JC26.OE42",
        )

        cases.forEach { (stepName, expectedCode) ->
            val step = stepClass.enumConstants!!.firstOrNull { (it as Enum<*>).name == stepName }
            assertNotNull("The fixed directory setup step $stepName must exist.", step)
            var calls = 0
            val operation: () -> Any? = {
                calls += 1
                throw IOException("private path, descriptor, and system error")
            }
            val failure = try {
                run.invoke(null, step, operation)
                throw AssertionError("The $stepName failure must not be swallowed.")
            } catch (wrapped: InvocationTargetException) {
                wrapped.targetException
            }

            assertTrue("The classifier must return a fixed diagnostic failure.", failureClass.isInstance(failure))
            assertEquals("Each injected failing system operation runs once.", 1, calls)
            assertNull("Raw exception details must not be exposed.", failureClass.getMethod("getMessage").invoke(failure))
            val diagnosis = failureClass.getMethod("getDiagnosis").invoke(failure)
            val category = failureClass.getMethod("getOsErrorCategory").invoke(failure)
            assertEquals("IOException remains the safe coarse I/O category.", "IO_FAILURE", (category as Enum<*>).name)
            val trace = appendDiagnosis.invoke(empty.invoke(companion), diagnosis, category)
            assertEquals("The persisted trace must contain only fixed codes.", expectedCode, encode.invoke(trace))
        }
    }

    @Test
    fun directorySetupClassifierPreservesAnAlreadyClassifiedFailure() {
        val maybeStepClass = optionalClass("com.neiro.aftertone.storage.RecoveryJournalDirectoryOpenStep")
        assertNotNull("Directory handle setup needs an allowlisted step model.", maybeStepClass)
        val stepClass = maybeStepClass!!
        val maybeDiagnosticsClass = optionalClass("com.neiro.aftertone.storage.RecoveryJournalDirectoryOpenDiagnostics")
        assertNotNull("Directory setup failures need one shared classifier.", maybeDiagnosticsClass)
        val diagnosticsClass = maybeDiagnosticsClass!!
        val failureClass = Class.forName("com.neiro.aftertone.storage.RecoveryJournalReadFailure")
        val run = diagnosticsClass.getDeclaredMethod("run", stepClass, kotlin.Function0::class.java).apply { isAccessible = true }
        val step = stepClass.enumConstants!!.first { (it as Enum<*>).name == "DIRECTORY_OPEN" }
        val diagnosisClass = Class.forName("com.neiro.aftertone.storage.RecoveryJournalReadDiagnosis")
        val existingDiagnosis = diagnosisClass.enumConstants!!.first { (it as Enum<*>).name == "JOURNAL_FILE_OPEN_FAILURE" }
        val categoryClass = Class.forName("com.neiro.aftertone.storage.RecoveryJournalOsErrorCategory")
        val constructor = failureClass.getDeclaredConstructor(diagnosisClass, categoryClass).apply { isAccessible = true }
        val existingCategory = categoryClass.enumConstants!!.first { (it as Enum<*>).name == "ACCESS_DENIED" }
        val alreadyClassified = constructor.newInstance(existingDiagnosis, existingCategory) as Throwable
        val operation: () -> Any? = { throw alreadyClassified }

        val failure = try {
            run.invoke(null, step, operation)
            throw AssertionError("The already classified failure must be rethrown.")
        } catch (wrapped: InvocationTargetException) {
            wrapped.targetException
        }

        assertTrue("The stage classifier must not overwrite an existing safe diagnosis.", failure === alreadyClassified)
    }

    private fun optionalClass(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()
}
