package com.neiro.aftertone.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import com.neiro.aftertone.model.Track
import java.io.File
import java.io.IOException
import java.io.InputStream

internal data class AudioImportDocument(
    val input: InputStream,
    val displayName: String,
    val mimeType: String?,
)

internal fun interface AudioDocumentSource {
    fun open(uri: Uri): AudioImportDocument
}

internal fun interface AudioTrackImporter {
    fun import(input: InputStream, displayName: String, mimeType: String?): Track
}

internal interface StableIdAudioTrackImporter : AudioTrackImporter {
    fun importWithId(input: InputStream, displayName: String, mimeType: String?, id: String): Track
}

internal class AndroidAudioDocumentSource(private val resolver: () -> ContentResolver) : AudioDocumentSource {
    override fun open(uri: Uri): AudioImportDocument {
        val contentResolver = resolver()
        val displayName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Imported audio"
        val mimeType = contentResolver.getType(uri)
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Could not open the selected audio document.")
        return AudioImportDocument(input, displayName, mimeType)
    }
}

internal fun createDefaultAudioImporter(context: Context): AudioTrackImporter {
    val audioDirectory = File(context.filesDir, "audio")
    val storageManager = context.getSystemService(StorageManager::class.java)
    return LocalAudioImporter(
        audioDirectory = audioDirectory,
        metadataReader = AndroidAudioMetadataReader(),
        availableBytes = {
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(audioDirectory))
        },
    )
}
