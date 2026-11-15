package com.neiro.aftertone

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportOutcomeCountsTest {
    @Test
    fun onlyIdsPresentInFinalReloadCountAsSuccessfulImports() {
        val counts = reconcileImportedTrackIds(
            importedTrackIds = setOf("retained", "disappeared"),
            failedBeforeReload = 1,
            reloadedTrackIds = setOf("retained", "older-track"),
        )

        assertEquals(1, counts.importedCount)
        assertEquals(2, counts.failedCount)
    }
}
