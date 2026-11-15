package com.neiro.aftertone

internal data class ImportOutcomeCounts(
    val importedCount: Int,
    val failedCount: Int,
)

internal fun reconcileImportedTrackIds(
    importedTrackIds: Set<String>,
    failedBeforeReload: Int,
    reloadedTrackIds: Set<String>,
): ImportOutcomeCounts {
    val importedCount = importedTrackIds.count { it in reloadedTrackIds }
    val missingAfterReload = importedTrackIds.size - importedCount
    return ImportOutcomeCounts(
        importedCount = importedCount,
        failedCount = failedBeforeReload + missingAfterReload,
    )
}
