package com.neiro.aftertone

internal class BatchImportSkipActions(
    private val currentState: () -> BatchImportState,
    private val updateState: (BatchImportState) -> Unit,
    private val requestGrantRelease: (String) -> Unit,
) {
    fun onSkipItem(itemId: String) {
        val current = currentState()
        updateState(current.skipFailedItemAndRequestGrantRelease(itemId, requestGrantRelease))
    }

    fun onSkipFailed() {
        val current = currentState()
        updateState(current.skipFailedAndRequestGrantReleases(requestGrantRelease))
    }
}
