package com.neiro.aftertone.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreRecoveryAttemptDiagnosticsTest {
    @Test
    fun traceRoundTripsOnlyFixedStageAndOutcomeCodes() {
        val traceClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptTrace")
        val stageClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptStage")
        val outcomeClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptOutcome")
        val eventClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptEvent")

        val start = createEvent(stageClass, outcomeClass, eventClass, "ATTEMPT", "STARTED")
        val failedRead = createEvent(stageClass, outcomeClass, eventClass, "JOURNAL_READ", "FAILED")
        val trace = invokeCompanion(traceClass, "empty")
        val append = traceClass.methods.first { it.name == "append" && it.parameterCount == 1 }
        val withStart = append.invoke(trace, start)!!
        val withFailure = append.invoke(withStart, failedRead)!!
        val encoded = traceClass.getMethod("encode").invoke(withFailure) as String
        val decoded = invokeCompanion(traceClass, "decode", encoded)
        val displayCode = traceClass.getMethod("getDisplayCode").invoke(decoded) as String

        assertEquals("DIA-AT00-JR02", displayCode)
        assertEquals(encoded, traceClass.getMethod("encode").invoke(decoded))
        assertTrue(encoded.matches(Regex("[A-Z0-9]{4}(?:\\.[A-Z0-9]{4}){0,19}")))
        assertTrue(displayCode.matches(Regex("DIA-(?:[A-Z0-9]{4})(?:-[A-Z0-9]{4}){0,19}")))
        assertFalse(encoded.contains("JOURNAL_READ"))
        assertFalse(encoded.contains("FAILED"))
    }

    @Test
    fun malformedStoredTraceDropsPathsAndArbitraryExceptionText() {
        val traceClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptTrace")
        val untrusted = "/data/user/0/com.neiro/audio/song.mp3 java.io.IOException secret-value"
        val decoded = invokeCompanion(traceClass, "decode", untrusted)
        val encoded = traceClass.getMethod("encode").invoke(decoded) as String
        val events = traceClass.getMethod("getEvents").invoke(decoded) as List<*>

        assertTrue(events.isEmpty())
        assertEquals("", encoded)
        assertFalse(encoded.contains("/data"))
        assertFalse(encoded.contains("IOException"))
        assertFalse(encoded.contains("secret-value"))
    }

    @Test
    fun journalClassificationCodeIsAllowlistedAndRawSuffixIsDropped() {
        val traceClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptTrace")
        val fixedCode = "AT00.PF01.JR02.RR02.GT08.JC13"
        val decoded = invokeCompanion(traceClass, "decode", fixedCode)
        val encoded = traceClass.getMethod("encode").invoke(decoded) as String
        val displayCode = traceClass.getMethod("getDisplayCode").invoke(decoded) as String

        assertEquals(fixedCode, encoded)
        assertEquals("DIA-AT00-PF01-JR02-RR02-GT08-JC13", displayCode)

        val contaminated = invokeCompanion(
            traceClass,
            "decode",
            "$fixedCode-/storage/emulated/0/music/secret.mp3 java.io.IOException",
        )
        val scrubbed = traceClass.getMethod("encode").invoke(contaminated) as String

        assertEquals("AT00.PF01.JR02.RR02.GT08", scrubbed)
        assertFalse(scrubbed.contains("/storage"))
        assertFalse(scrubbed.contains("secret.mp3"))
        assertFalse(scrubbed.contains("IOException"))
    }

    @Test
    fun directoryAndJournalOpenStagesEncodeDistinctFixedCodes() {
        val cases = listOf(
            RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE to "JC20",
            RecoveryJournalReadDiagnosis.DIRECTORY_OPEN_FAILURE to "JC21",
            RecoveryJournalReadDiagnosis.JOURNAL_FILE_OPEN_FAILURE to "JC22",
        )

        cases.forEach { (diagnosis, expectedCode) ->
            val trace = RestoreRecoveryAttemptTrace.empty().appendJournalDiagnosis(diagnosis, null)

            assertEquals(expectedCode, trace.encode())
            assertEquals("DIA-$expectedCode", trace.displayCode)
        }
    }

    @Test
    fun journalFileOpenAndIoCategoryCodesAreAllowlisted() {
        val traceClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptTrace")
        val fixedCode = "AT00.PF01.JR02.RR02.GT08.JC22.OE42"

        val decoded = invokeCompanion(traceClass, "decode", fixedCode)
        val encoded = traceClass.getMethod("encode").invoke(decoded) as String
        val displayCode = traceClass.getMethod("getDisplayCode").invoke(decoded) as String

        assertEquals(fixedCode, encoded)
        assertEquals("DIA-AT00-PF01-JR02-RR02-GT08-JC22-OE42", displayCode)
        assertTrue(encoded.matches(Regex("[A-Z0-9]{4}(?:\\.[A-Z0-9]{4}){0,19}")))
    }

    @Test
    fun newJournalDiagnosisWithoutOsCategoryClearsPreviousOsCategory() {
        val traceClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptTrace")
        val diagnosisClass = requiredClass("com.neiro.aftertone.storage.RecoveryJournalReadDiagnosis")
        val fixedCode = "AT00.PF01.JR02.RR02.GT08.JC22.OE42"
        val trace = invokeCompanion(traceClass, "decode", fixedCode)
        val diagnosis = diagnosisClass.enumConstants!!.firstOrNull { (it as Enum<*>).name == "READABLE" }
        assertNotNull("A fixed journal diagnosis must exist.", diagnosis)
        val appendJournalDiagnosis = traceClass.methods.firstOrNull {
            it.name == "appendJournalDiagnosis" && it.parameterCount == 2
        }
        assertNotNull("Recording a diagnosis must replace stale OS category data.", appendJournalDiagnosis)

        val updated = appendJournalDiagnosis!!.invoke(trace, diagnosis, null)!!
        val encoded = traceClass.getMethod("encode").invoke(updated) as String

        assertEquals("AT00.PF01.JR02.RR02.GT08.JC18", encoded)
        assertFalse(encoded.contains("OE42"))
    }

    @Test
    fun traceRetainsAtMostTheNewestTwentyAllowlistedStages() {
        val traceClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptTrace")
        val stageClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptStage")
        val outcomeClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptOutcome")
        val eventClass = requiredClass("com.neiro.aftertone.storage.RestoreRecoveryAttemptEvent")
        val append = traceClass.methods.first { it.name == "append" && it.parameterCount == 1 }
        val started = outcomeClass.enumConstants!!.first { (it as Enum<*>).name == "STARTED" }
        var trace = invokeCompanion(traceClass, "empty")
        val stages = stageClass.enumConstants!!.map { it as Enum<*> }
        repeat(25) { index ->
            val stage = stages[index % stages.size]
            val constructor = eventClass.getDeclaredConstructor(stageClass, outcomeClass).apply { isAccessible = true }
            trace = append.invoke(trace, constructor.newInstance(stage, started))!!
        }

        val events = traceClass.getMethod("getEvents").invoke(trace) as List<*>
        val encoded = traceClass.getMethod("encode").invoke(trace) as String
        assertTrue(events.size <= 20)
        assertTrue(encoded.length <= 99)
    }

    private fun requiredClass(name: String): Class<*> {
        val type = runCatching { Class.forName(name) }.getOrNull()
        assertNotNull("Fixed-code recovery diagnostic type $name must exist.", type)
        return type!!
    }

    private fun invokeCompanion(type: Class<*>, name: String, vararg args: Any): Any {
        val companion = type.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val method = companion.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
        assertNotNull("Recovery trace companion method $name must exist.", method)
        return method!!.invoke(companion, *args) ?: error("Recovery trace method $name must return a value.")
    }

    private fun createEvent(
        stageClass: Class<*>,
        outcomeClass: Class<*>,
        eventClass: Class<*>,
        stageName: String,
        outcomeName: String,
    ): Any {
        val stage = stageClass.enumConstants!!.firstOrNull { (it as Enum<*>).name == stageName }
        val outcome = outcomeClass.enumConstants!!.firstOrNull { (it as Enum<*>).name == outcomeName }
        assertNotNull("The stage must be an allowlisted code.", stage)
        assertNotNull("The outcome must be an allowlisted code.", outcome)
        return eventClass.getDeclaredConstructor(stageClass, outcomeClass)
            .apply { isAccessible = true }
            .newInstance(stage, outcome)
    }
}
