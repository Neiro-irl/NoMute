package com.neiro.aftertone

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryLoadRetryPolicyTest {
    @Test
    fun diagnosticModeHidesRecoveryRetryButNormalModeKeepsIt() {
        assertFalse(invokePolicy("canShowRecoveryRetry", true, true))
        assertFalse(invokePolicy("canShowRecoveryRetry", true, false))
        assertTrue(invokePolicy("canShowRecoveryRetry", false, true))
        assertFalse(invokePolicy("canShowRecoveryRetry", false, false))
    }

    @Test
    fun normalModeHidesRecoveryRetryAfterSingleShotAuthorizationIsConsumed() {
        assertFalse(invokePolicy("canShowRecoveryRetry", false, true, true))
    }

    @Test
    fun diagnosticModeHidesExternalAudioRetry() {
        assertFalse(invokePolicy("canShowExternalAudioRetry", true))
        assertTrue(invokePolicy("canShowExternalAudioRetry", false))
    }

    @Test
    fun diagnosticModeBlocksExternalAudioQueueAndFailureChoicesButNormalModeKeepsThem() {
        assertFalse(invokePolicy("canProcessExternalAudioQueue", true))
        assertTrue(invokePolicy("canProcessExternalAudioQueue", false))
        assertFalse(invokePolicy("canShowExternalAudioFailureActions", true))
        assertTrue(invokePolicy("canShowExternalAudioFailureActions", false))
    }

    @Test
    fun externalAudioQueueAndFailureDialogUseTheReadOnlyPolicies() {
        val activitySource = File("src/main/java/com/neiro/aftertone/MainActivity.kt").readText()
        val appSource = File("src/main/java/com/neiro/aftertone/AftertoneApp.kt").readText()

        assertTrue("External-open queue entry points must use the diagnostic policy.", activitySource.contains("canProcessExternalAudioQueue("))
        assertTrue("The failed external-open dialog must hide both actions in diagnostics.", appSource.contains("canShowExternalAudioFailureActions("))
    }

    @Test
    fun bothAppRetryAffordancesUseTheTestedPolicy() {
        val source = File("src/main/java/com/neiro/aftertone/AftertoneApp.kt").readText()
        assertTrue("Recovery load-error retry must use the read-only policy.", source.contains("canShowRecoveryRetry("))
        assertTrue("External-audio retry must use the read-only policy.", source.contains("canShowExternalAudioRetry("))
    }

    private fun invokePolicy(name: String, vararg values: Boolean): Boolean {
        val holder = runCatching {
            Class.forName("com.neiro.aftertone.RecoveryLoadRetryPolicyKt")
        }.getOrNull()
        assertNotNull("The read-only retry policy must exist and be callable by the app.", holder)
        val parameterTypes = Array(values.size) { Boolean::class.javaPrimitiveType!! }
        val method = holder!!.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(parameterTypes)
        }
        assertNotNull("The read-only policy $name must exist and be callable by the app.", method)
        return method!!.invoke(null, *values.toTypedArray()) as Boolean
    }
}
