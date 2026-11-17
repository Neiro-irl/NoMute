package com.neiro.aftertone.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import com.neiro.aftertone.model.PrivateAudioPath

internal interface RestoreFileDurability {
    fun syncFile(output: FileOutputStream)
    fun syncDirectory(directory: File)

    fun syncFile(channel: FileChannel) {
        channel.force(true)
    }

    fun syncDirectory(directory: File, openedDirectory: SecureDirectoryStream<Path>) {
        if (!PrivateAudioPath.isSameDirectory(directory, openedDirectory)) {
            throw IOException("The directory path no longer identifies the opened directory.")
        }
        syncDirectory(directory)
        if (!PrivateAudioPath.isSameDirectory(directory, openedDirectory)) {
            throw IOException("The directory path changed while it was being synced.")
        }
    }
}

internal object SystemRestoreFileDurability : RestoreFileDurability {
    override fun syncFile(output: FileOutputStream) {
        output.fd.sync()
    }

    override fun syncDirectory(directory: File) {
        require(directory.isDirectory) { "The directory to sync is unavailable." }
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}
