package com.neiro.aftertone.storage

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryJournalPrivateDirectoryOpenDiagnosticsTest {
    @Test
    fun eachPrivateDirectoryOpenStepMapsToOneSanitizedFixedCode() {
        val stepClass = optionalClass("com.neiro.aftertone.model.PrivateAudioDirectoryOpenStep")
        assertNotNull("The private-directory open steps must be an explicit allowlist.", stepClass)
        val diagnosticsClass = optionalClass("com.neiro.aftertone.storage.RecoveryJournalPrivateDirectoryOpenDiagnostics")
        assertNotNull("Read-only inspection must classify private-directory open failures.", diagnosticsClass)
        val failureClass = Class.forName("com.neiro.aftertone.storage.RecoveryJournalReadFailure")
        val classifyFailure = diagnosticsClass!!.getDeclaredMethod("failure", stepClass!!, Exception::class.java)
        val traceClass = Class.forName("com.neiro.aftertone.storage.RestoreRecoveryAttemptTrace")
        val companion = traceClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val empty = companion.javaClass.methods.first { it.name == "empty" && it.parameterCount == 0 }
        val appendDiagnosis = traceClass.methods.first { it.name == "appendJournalDiagnosis" && it.parameterCount == 2 }
        val encode = traceClass.getMethod("encode")
        val cases = listOf(
            Triple("PATH_RESOLUTION", "SECURE_DIRECTORY_PATH_RESOLUTION_FAILURE", "JC27.OE42"),
            Triple("ROOT_DIRECTORY_OPEN", "SECURE_DIRECTORY_ROOT_OPEN_FAILURE", "JC28.OE42"),
            Triple("SECURE_STREAM_UNSUPPORTED", "SECURE_DIRECTORY_STREAM_UNSUPPORTED", "JC29.OE42"),
            Triple("ROOT_STREAM_CLOSE", "SECURE_DIRECTORY_ROOT_CLOSE_FAILURE", "JC30.OE42"),
            Triple("COMPONENT_DIRECTORY_OPEN", "SECURE_DIRECTORY_COMPONENT_OPEN_FAILURE", "JC31.OE42"),
            Triple("PREVIOUS_DIRECTORY_CLOSE", "SECURE_DIRECTORY_PARENT_CLOSE_FAILURE", "JC32.OE42"),
        )

        cases.forEach { (stepName, diagnosisName, expectedCode) ->
            val step = stepClass.enumConstants!!.firstOrNull { (it as Enum<*>).name == stepName }
            assertNotNull("The $stepName step must be represented.", step)
            val failure = try {
                classifyFailure.invoke(null, step, IOException("private path and raw system error"))
            } catch (wrapped: InvocationTargetException) {
                wrapped.targetException
            }

            assertTrue("The classifier must return a fixed diagnosis.", failureClass.isInstance(failure))
            assertNull("The raw exception message must not be exposed.", failureClass.getMethod("getMessage").invoke(failure))
            assertNull("The raw exception must not be retained as a cause.", failureClass.getMethod("getCause").invoke(failure))
            assertEquals("The error category must be coarse and allowlisted.", "IO_FAILURE", (failureClass.getMethod("getOsErrorCategory").invoke(failure) as Enum<*>).name)
            val diagnosis = failureClass.getMethod("getDiagnosis").invoke(failure)
            assertEquals(diagnosisName, (diagnosis as Enum<*>).name)
            val trace = appendDiagnosis.invoke(empty.invoke(companion), diagnosis, failureClass.getMethod("getOsErrorCategory").invoke(failure))
            val encoded = encode.invoke(trace) as String
            assertEquals("Only fixed stage and category codes may be persisted.", expectedCode, encoded)
            assertTrue(encoded.matches(Regex("[A-Z0-9]{4}(?:\\.[A-Z0-9]{4}){0,19}")))
        }
    }

    private fun optionalClass(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()
}
