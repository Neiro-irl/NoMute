package com.neiro.aftertone

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomNavigationRecoveryTest {
    @Test
    fun tabSelectionRemainsAvailableWhileReadOnlyRecoveryBlocksLibraryActionsAndRetries() {
        val appSource = File("src/main/java/com/neiro/aftertone/AftertoneApp.kt").readText()
        val tabCallback = Regex("onTabSelected\\s*=\\s*\\{([^}]*)\\}")
            .find(appSource)
            ?.groupValues
            ?.get(1)
            ?: error("The bottom-bar tab callback must be wired.")

        assertFalse(
            "Restore recovery may block library actions, but it must not block navigation to Settings.",
            tabCallback.contains("restoreActionGate.runIfAllowed"),
        )
        assertTrue("Selecting a tab must update the active tab.", tabCallback.contains("activeTab = tab"))
        assertTrue("Selecting a tab must close an open collection detail.", tabCallback.contains("openCollectionId = null"))
        assertFalse(
            "Read-only diagnostics must keep the recovery cleanup retry unavailable.",
            canShowRecoveryRetry(isRecoveryDiagnosticsReadOnly = true, hasPendingRecoveryCleanup = true),
        )
        assertFalse(
            "Read-only diagnostics must keep the external-audio retry unavailable.",
            canShowExternalAudioRetry(isRecoveryDiagnosticsReadOnly = true),
        )
    }
}
