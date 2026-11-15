package com.neiro.aftertone

import com.neiro.aftertone.storage.AndroidAudioImportDurability
import com.neiro.aftertone.storage.AudioImportDurability
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID

internal class ExternalAudioOpenRequestQueueSnapshotBackup(
    private val filesDirectory: File,
    private val durability: AudioImportDurability = AndroidAudioImportDurability,
) {
    fun backup(rawSnapshot: String): Boolean {
        if (rawSnapshot.length > MAX_EXTERNAL_AUDIO_QUEUE_QUARANTINE_BYTES ||
            utf8Size(rawSnapshot) > MAX_EXTERNAL_AUDIO_QUEUE_QUARANTINE_BYTES
        ) return false
        var temporaryFile: File? = null
        return try {
            val directory = File(filesDirectory, "external-audio-queue-quarantine")
            if (!directory.exists() && !directory.mkdirs()) return false
            if (!directory.isDirectory || directory.canonicalFile.parentFile != filesDirectory.canonicalFile) return false
            durability.syncDirectory(filesDirectory)
            val existingBackups = directory.listFiles { file ->
                file.isFile && file.name.endsWith(".snapshot")
            } ?: return false
            val matchingBackup = existingBackups.firstOrNull { backup ->
                backup.length() <= MAX_EXTERNAL_AUDIO_QUEUE_QUARANTINE_BYTES &&
                    runCatching { backup.readText(Charsets.UTF_8) == rawSnapshot }.getOrDefault(false)
            }
            if (matchingBackup != null) {
                durability.syncFile(matchingBackup)
                durability.syncDirectory(directory)
                return true
            }
            if (existingBackups.size >= MAX_EXTERNAL_AUDIO_QUEUE_QUARANTINE_SNAPSHOTS) return false
            val temporary = File.createTempFile("queue-", ".tmp", directory)
            temporaryFile = temporary
            val backupFile = File(directory, "queue-${UUID.randomUUID()}.snapshot")
            FileOutputStream(temporary).use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write(rawSnapshot)
                    writer.flush()
                }
            }
            durability.syncFile(temporary)
            if (!temporary.renameTo(backupFile)) return false
            temporaryFile = null
            durability.syncDirectory(directory)
            true
        } catch (_: Exception) {
            false
        } finally {
            temporaryFile?.delete()
        }
    }
}
