package com.neiro.aftertone.model

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

object PrivateAudioPath {
    private const val RESTORE_OWNER_MARKER = ".aftertone-restore-owner"
    private const val DELETE_QUARANTINE_PREFIX = ".aftertone-delete-"
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val deletionLock = Any()

    fun isCanonicalDirectory(directory: File): Boolean = try {
        val absoluteDirectory = directory.absoluteFile.toPath().normalize().toFile()
        val absoluteParent = absoluteDirectory.parentFile
        absoluteParent != null && directory.canonicalFile ==
            File(absoluteParent.canonicalFile, absoluteDirectory.name).absoluteFile.toPath().normalize().toFile()
    } catch (_: Exception) {
        false
    }

    fun isDirectChild(audioDirectory: File, candidate: File): Boolean = try {
        if (!isCanonicalDirectory(audioDirectory)) return false
        val absoluteCandidate = candidate.absoluteFile.toPath().normalize().toFile()
        val canonicalRoot = audioDirectory.canonicalFile
        val canonicalCandidate = candidate.canonicalFile

        // Importers persist canonical paths; compare resolved parents so an equivalent alias for an ancestor does not reject the file.
        canonicalCandidate.parentFile == canonicalRoot &&
            absoluteCandidate.name == canonicalCandidate.name
    } catch (_: Exception) {
        false
    }

    fun isOwnedRestoreFile(audioDirectory: File, candidate: File): Boolean {
        val parent = candidate.absoluteFile.parentFile ?: return false
        val id = restoreGenerationId(parent.name) ?: return false
        return isOwnedRestoreDirectory(audioDirectory, parent, id) && isDirectChild(parent, candidate)
    }

    fun isOwnedRestoreDirectory(audioDirectory: File, candidate: File, expectedId: String): Boolean {
        if (!uuidPattern.matches(expectedId) || candidate.name != "restore-$expectedId") return false
        val directoryPath = candidate.toPath()
        if (Files.isSymbolicLink(directoryPath) ||
            !Files.isDirectory(directoryPath, LinkOption.NOFOLLOW_LINKS) ||
            !isDirectChild(audioDirectory, candidate)
        ) return false
        return hasExpectedRestoreOwnerMarker(candidate, expectedId)
    }

    internal fun hasExpectedRestoreOwnerMarker(directory: File, expectedId: String): Boolean {
        if (!uuidPattern.matches(expectedId) || directory.name != "restore-$expectedId") return false
        val marker = File(directory, RESTORE_OWNER_MARKER)
        return hasExpectedDirectChildContentsNoFollow(directory, marker, expectedId)
    }

    internal fun hasExpectedDirectChildContentsNoFollow(directory: File, child: File, expectedContents: String): Boolean {
        if (expectedContents.isEmpty() || expectedContents.length > 64) return false
        return try {
            openDirectoryNoFollow(directory).use { openedDirectory ->
                if (!isDirectChild(directory, child)) return false
                val childName = child.toPath().fileName ?: return false
                hasExpectedDirectChildContentsNoFollow(openedDirectory, childName, expectedContents)
            }
        } catch (_: Exception) {
            false
        }
    }

    internal fun hasExpectedDirectChildContentsNoFollow(
        directory: SecureDirectoryStream<Path>,
        childName: Path,
        expectedContents: String,
    ): Boolean {
        if (expectedContents.isEmpty() || expectedContents.length > 64 ||
            childName.isAbsolute || childName.nameCount != 1 || childName.toString() == "." || childName.toString() == ".."
        ) return false
        val expectedBytes = expectedContents.toByteArray(Charsets.UTF_8)
        if (expectedBytes.size > 64) return false
        return try {
            val attributes = directory.getFileAttributeView(
                childName,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )?.readAttributes() ?: return false
            if (!attributes.isRegularFile || attributes.size() !in 1L..64L) return false

            val markerBytes = ByteBuffer.allocate(expectedBytes.size + 1)
            directory.newByteChannel(
                childName,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                while (markerBytes.hasRemaining()) {
                    if (channel.read(markerBytes) <= 0) break
                }
            }
            markerBytes.position() == expectedBytes.size &&
                markerBytes.array().copyOf(expectedBytes.size).contentEquals(expectedBytes)
        } catch (_: Exception) {
            false
        }
    }

    internal fun isSameDirectory(directory: File, openedDirectory: SecureDirectoryStream<Path>): Boolean = try {
        val openedAttributes = openedDirectory.getFileAttributeView(BasicFileAttributeView::class.java)?.readAttributes()
            ?: return false
        val currentAttributes = Files.readAttributes(
            directory.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        openedAttributes.isDirectory && currentAttributes.isDirectory &&
            openedAttributes.fileKey() != null && openedAttributes.fileKey() == currentAttributes.fileKey()
    } catch (_: Exception) {
        false
    }

    internal fun directoryFileKeyNoFollow(directory: File): Any? = try {
        val attributes = Files.readAttributes(
            directory.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.isDirectory) attributes.fileKey() else null
    } catch (_: Exception) {
        null
    }

    internal fun isOpenedDirectoryKey(
        expectedFileKey: Any?,
        openedDirectory: SecureDirectoryStream<Path>,
    ): Boolean = try {
        if (expectedFileKey == null) return false
        val openedAttributes = openedDirectory.getFileAttributeView(BasicFileAttributeView::class.java)?.readAttributes()
            ?: return false
        openedAttributes.isDirectory && openedAttributes.fileKey() == expectedFileKey
    } catch (_: Exception) {
        false
    }

    internal fun regularFileKeyNoFollow(path: Path): Any? = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.isRegularFile) attributes.fileKey() else null
    } catch (_: Exception) {
        null
    }

    internal fun regularFileKeyNoFollow(openedParent: SecureDirectoryStream<Path>, childName: Path): Any? {
        if (childName.isAbsolute || childName.nameCount != 1 || childName.toString() == "." || childName.toString() == "..") {
            return null
        }
        return try {
            val attributes = openedParent.getFileAttributeView(
                childName,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )?.readAttributes() ?: return null
            if (attributes.isRegularFile) attributes.fileKey() else null
        } catch (_: Exception) {
            null
        }
    }

    internal fun deleteRegularFileNoFollow(
        openedParent: SecureDirectoryStream<Path>,
        childName: Path,
    ): Boolean = deleteRegularFileNoFollow(
        openedParent,
        childName,
        regularFileKeyNoFollow(openedParent, childName),
    )

    internal fun deleteRegularFileNoFollow(
        openedParent: SecureDirectoryStream<Path>,
        childName: Path,
        expectedFileKey: Any?,
    ): Boolean {
        if (childName.isAbsolute || childName.nameCount != 1 || childName.toString() == "." || childName.toString() == "..") {
            return false
        }
        return synchronized(deletionLock) {
            val quarantineName = deletionQuarantineName(childName) ?: return@synchronized false
            if (hasEntryNoFollow(openedParent, quarantineName)) {
                if (!deleteQuarantineEntryNoFollow(openedParent, quarantineName)) return@synchronized false
                return@synchronized !hasEntryNoFollow(openedParent, childName)
            }
            if (expectedFileKey == null) return@synchronized !hasEntryNoFollow(openedParent, childName)
            deleteEntryWithExpectedFileKey(openedParent, childName, expectedFileKey, allowSymbolicLink = false)
        }
    }

    internal fun deleteEntryNoFollow(
        openedParent: SecureDirectoryStream<Path>,
        childName: Path,
    ): Boolean {
        if (childName.isAbsolute || childName.nameCount != 1 || childName.toString() == "." || childName.toString() == "..") {
            return false
        }
        return synchronized(deletionLock) {
            val quarantineName = deletionQuarantineName(childName) ?: return@synchronized false
            if (hasEntryNoFollow(openedParent, quarantineName)) {
                if (!deleteQuarantineEntryNoFollow(openedParent, quarantineName)) return@synchronized false
                return@synchronized !hasEntryNoFollow(openedParent, childName)
            }
            val expectedFileKey = entryFileKeyNoFollow(openedParent, childName)
                ?: return@synchronized !hasEntryNoFollow(openedParent, childName)
            deleteEntryWithExpectedFileKey(openedParent, childName, expectedFileKey, allowSymbolicLink = true)
        }
    }

    internal fun deletionQuarantineName(targetName: Path): Path? {
        if (targetName.isAbsolute || targetName.nameCount != 1 || targetName.toString() in setOf(".", "..") ||
            targetName.toString().startsWith(DELETE_QUARANTINE_PREFIX) || '/' in targetName.toString() ||
            '\\' in targetName.toString()
        ) return null
        val quarantineName = "$DELETE_QUARANTINE_PREFIX${targetName}"
        if (quarantineName.toByteArray(Charsets.UTF_8).size > 255) return null
        return targetName.fileSystem.getPath(quarantineName)
    }

    internal fun deletionQuarantineTargetName(quarantineName: Path): Path? {
        if (quarantineName.isAbsolute || quarantineName.nameCount != 1 ||
            !quarantineName.toString().startsWith(DELETE_QUARANTINE_PREFIX)
        ) return null
        val targetName = quarantineName.fileSystem.getPath(quarantineName.toString().removePrefix(DELETE_QUARANTINE_PREFIX))
        return targetName.takeIf { deletionQuarantineName(it) == quarantineName }
    }

    internal fun hasDeleteQuarantineNoFollow(
        openedParent: SecureDirectoryStream<Path>,
        targetName: Path,
    ): Boolean {
        val quarantineName = deletionQuarantineName(targetName) ?: return true
        return hasEntryNoFollow(openedParent, quarantineName)
    }

    internal fun cleanupDeleteQuarantineNoFollow(
        openedParent: SecureDirectoryStream<Path>,
        targetName: Path,
    ): Boolean = synchronized(deletionLock) {
        val quarantineName = deletionQuarantineName(targetName) ?: return@synchronized false
        deleteQuarantineEntryNoFollow(openedParent, quarantineName)
    }

    private fun deleteQuarantineEntryNoFollow(
        openedParent: SecureDirectoryStream<Path>,
        quarantineName: Path,
    ): Boolean {
        val first = readChildAttributesNoFollow(openedParent, quarantineName)
            ?: return !hasEntryNoFollow(openedParent, quarantineName)
        if ((!first.isRegularFile && !first.isSymbolicLink) || first.fileKey() == null) return false
        val second = readChildAttributesNoFollow(openedParent, quarantineName) ?: return false
        if (second.fileKey() != first.fileKey() || (!second.isRegularFile && !second.isSymbolicLink)) return false
        return try {
            // Quarantine names are deterministic per target and all in-process deletion helpers are serialized.
            openedParent.deleteFile(quarantineName)
            !hasEntryNoFollow(openedParent, quarantineName)
        } catch (_: NoSuchFileException) {
            !hasEntryNoFollow(openedParent, quarantineName)
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteEntryWithExpectedFileKey(
        openedParent: SecureDirectoryStream<Path>,
        childName: Path,
        expectedFileKey: Any,
        allowSymbolicLink: Boolean,
    ): Boolean {
        val initialAttributes = readChildAttributesNoFollow(openedParent, childName) ?: return false
        if ((!initialAttributes.isRegularFile && !(allowSymbolicLink && initialAttributes.isSymbolicLink)) ||
            initialAttributes.fileKey() != expectedFileKey
        ) return false
        val quarantineName = deletionQuarantineName(childName) ?: return false
        if (hasEntryNoFollow(openedParent, quarantineName)) return false
        try {
            openedParent.move(childName, openedParent, quarantineName)
        } catch (_: NoSuchFileException) {
            return false
        }

        val quarantinedAttributes = readChildAttributesNoFollow(openedParent, quarantineName)
        if (quarantinedAttributes == null || quarantinedAttributes.fileKey() != expectedFileKey ||
            (!quarantinedAttributes.isRegularFile && !(allowSymbolicLink && quarantinedAttributes.isSymbolicLink))
        ) {
            if (!hasEntryNoFollow(openedParent, childName)) {
                runCatching { openedParent.move(quarantineName, openedParent, childName) }
            }
            return false
        }

        val finalAttributes = readChildAttributesNoFollow(openedParent, quarantineName)
        if (finalAttributes == null || finalAttributes.fileKey() != expectedFileKey ||
            (!finalAttributes.isRegularFile && !(allowSymbolicLink && finalAttributes.isSymbolicLink))
        ) return false

        return try {
            openedParent.deleteFile(quarantineName)
            !hasEntryNoFollow(openedParent, quarantineName)
        } catch (_: Exception) {
            if (!hasEntryNoFollow(openedParent, childName) && hasEntryNoFollow(openedParent, quarantineName)) {
                runCatching { openedParent.move(quarantineName, openedParent, childName) }
            }
            false
        }
    }

    private fun entryFileKeyNoFollow(
        openedParent: SecureDirectoryStream<Path>,
        childName: Path,
    ): Any? = readChildAttributesNoFollow(openedParent, childName)
        ?.takeIf { it.isRegularFile || it.isSymbolicLink }
        ?.fileKey()

    private fun readChildAttributesNoFollow(
        openedParent: SecureDirectoryStream<Path>,
        childName: Path,
    ): BasicFileAttributes? = try {
        openedParent.getFileAttributeView(
            childName,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )?.readAttributes()
    } catch (_: NoSuchFileException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun hasEntryNoFollow(openedParent: SecureDirectoryStream<Path>, childName: Path): Boolean = try {
        openedParent.getFileAttributeView(
            childName,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )?.readAttributes()
        true
    } catch (_: NoSuchFileException) {
        false
    } catch (_: Exception) {
        true
    }

    internal fun openDirectoryNoFollow(directory: File): SecureDirectoryStream<Path> {
        val absoluteDirectory = directory.toPath().toAbsolutePath().normalize()
        val root = absoluteDirectory.root ?: throw IOException("The private directory path is not absolute.")
        val rootStream = Files.newDirectoryStream(root)
        @Suppress("UNCHECKED_CAST")
        var current = rootStream as? SecureDirectoryStream<Path> ?: run {
            rootStream.close()
            throw IOException("The filesystem does not support secure directory operations.")
        }
        try {
            for (component in absoluteDirectory) {
                val next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)
                val previous = current
                current = next
                previous.close()
            }
            return current
        } catch (error: Throwable) {
            runCatching { current.close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    internal fun restoreGenerationId(directoryName: String): String? =
        directoryName.removePrefix("restore-").takeIf { directoryName.startsWith("restore-") && uuidPattern.matches(it) }

    internal fun restoreStageId(directoryName: String): String? =
        directoryName.removePrefix(".aftertone-restore-stage-")
            .takeIf { directoryName.startsWith(".aftertone-restore-stage-") && uuidPattern.matches(it) }

    internal fun restoreOwnerMarkerName(): String = RESTORE_OWNER_MARKER
}
