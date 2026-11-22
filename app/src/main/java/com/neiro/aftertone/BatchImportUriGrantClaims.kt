package com.neiro.aftertone

import android.net.Uri

internal data class BatchImportUriGrantClaims(
    val pickerSelectionUris: Set<Uri> = emptySet(),
) {
    fun withPickerSelection(uris: Set<Uri>): BatchImportUriGrantClaims =
        copy(pickerSelectionUris = pickerSelectionUris + uris)

    fun promoteBatchItems(activeBatchUris: Set<Uri>): BatchImportUriGrantClaims =
        copy(pickerSelectionUris = pickerSelectionUris - activeBatchUris)

    fun discardPickerSelection(uris: Set<Uri>): BatchImportUriGrantClaims =
        copy(pickerSelectionUris = pickerSelectionUris - uris)

    fun allUris(activeBatchUris: Set<Uri>): Set<Uri> = activeBatchUris + pickerSelectionUris
}
