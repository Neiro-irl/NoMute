package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import java.io.File
import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** A no-journal cleanup that is pinned to the verified filesDir descriptor for its whole lifetime. */
internal class ScopedNoJournalRecoveryCleanup(
    private val filesDirectory: File,
    private val audioDirectory: File,
    private val activeAudioPaths: Set<Path>,
    private val durability: RestoreFileDurability,
    private val journalIsAbsent: (SecureDirectoryStream<Path>) -> Boolean,
    private val isUuid: (String) -> Boolean,
    private val isSafeStagedAssetName: (String) -> Boolean,
    private val cleanupAssetDeletionQuarantines: (SecureDirectoryStream<Path>, List<Path>) -> Set<Path>?,
    private val journalTemporaryPrefix: String,
    private val ownerMarkerName: String,
    private val maximumJournalBytes: Long,
    private val maximumStagingAssets: Int,
    private val forceSyncDirectories: Boolean,
    private val maximumDirectoryEntries: Int = maximumStagingAssets,
) {
    private data class TemporaryFilePlan(
        val entryName: Path,
        val targetName: Path,
        val fileKey: Any,
        val isQuarantine: Boolean,
    )

    private data class JournalPlan(val temporaryFiles: List<TemporaryFilePlan>)

    private data class StagedAssetPlan(val name: Path, val fileKey: Any)

    private data class StagedQuarantinePlan(val targetName: Path, val entryName: Path, val fileKey: Any)

    private data class StagingDirectoryPlan(
        val name: Path,
        val id: String,
        val directory: File,
        val directoryKey: Any,
        val ownerMarkerKey: Any,
        val childKeys: Map<Path, Any>,
        val assets: List<StagedAssetPlan>,
        val quarantines: List<StagedQuarantinePlan>,
    )

    private data class GenerationDirectoryPlan(val name: Path, val directory: File, val directoryKey: Any)

    private data class AudioPlan(
        val stagingDirectories: List<StagingDirectoryPlan>,
        val emptyGenerationDirectories: List<GenerationDirectoryPlan>,
    )

    private data class DirectoryIdentity(
        val filesKey: Any,
        val audioKey: Any?,
    )

    fun cleanup(): Boolean = try {
        if (!PrivateAudioPath.isCanonicalDirectory(filesDirectory)) return false
        val filesKey = PrivateAudioPath.directoryFileKeyNoFollow(filesDirectory) ?: return false
        PrivateFilesDirectoryNioAnchor.open(filesDirectory).use { filesHandle ->
            if (!filesAnchorMatches(filesHandle, filesKey)) return false
            val audioName = filesDirectory.toPath().fileSystem.getPath(audioDirectory.name)
            val audioAttributes = readAttributesNoFollow(filesHandle, audioName)
            if (audioAttributes == null) {
                cleanupPinned(filesHandle, DirectoryIdentity(filesKey, null), audioHandle = null)
            } else {
                if (!audioAttributes.isDirectory || audioAttributes.isSymbolicLink ||
                    !PrivateAudioPath.isDirectChild(filesDirectory, audioDirectory)
                ) return false
                val audioKey = audioAttributes.fileKey() ?: return false
                filesHandle.newDirectoryStream(audioName, LinkOption.NOFOLLOW_LINKS).use { audioHandle ->
                    val identity = DirectoryIdentity(filesKey, audioKey)
                    if (!audioAnchorMatches(audioHandle, identity)) return false
                    cleanupPinned(filesHandle, identity, audioHandle)
                }
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun cleanupPinned(
        filesHandle: SecureDirectoryStream<Path>,
        identity: DirectoryIdentity,
        audioHandle: SecureDirectoryStream<Path>?,
    ): Boolean {
        var filesChanged = false
        var audioChanged = false
        val cleanupSucceeded = try {
            if (!anchorsMatch(filesHandle, identity, audioHandle) || !hasNoRecoveryJournal(filesHandle)) {
                false
            } else {
                val journalPlan = collectJournalPlan(filesHandle) ?: run {
                    return false
                }
                val audioPlan = if (audioHandle == null) {
                    AudioPlan(emptyList(), emptyList())
                } else {
                    collectAudioPlan(filesHandle, identity, audioHandle) ?: run {
                        return false
                    }
                }
                if (!anchorsMatch(filesHandle, identity, audioHandle) || !hasNoRecoveryJournal(filesHandle)) {
                    false
                } else {
                    var complete = true
                    val quarantineFiles = journalPlan.temporaryFiles.filter(TemporaryFilePlan::isQuarantine)
                    val directFiles = journalPlan.temporaryFiles.filterNot(TemporaryFilePlan::isQuarantine)
                    for (plan in quarantineFiles) {
                        if (!canMutateFiles(filesHandle, identity) ||
                            PrivateAudioPath.regularFileKeyNoFollow(filesHandle, plan.entryName) != plan.fileKey ||
                            !PrivateAudioPath.cleanupDeleteQuarantineNoFollow(filesHandle, plan.targetName)
                        ) {
                            complete = false
                            break
                        }
                        filesChanged = true
                        if (!canMutateFiles(filesHandle, identity)) {
                            complete = false
                            break
                        }
                    }
                    if (complete) {
                        for (plan in directFiles) {
                            if (!canMutateFiles(filesHandle, identity) ||
                                PrivateAudioPath.regularFileKeyNoFollow(filesHandle, plan.entryName) != plan.fileKey ||
                                !PrivateAudioPath.deleteRegularFileNoFollow(filesHandle, plan.entryName, plan.fileKey)
                            ) {
                                complete = false
                                break
                            }
                            filesChanged = true
                            if (!canMutateFiles(filesHandle, identity)) {
                                complete = false
                                break
                            }
                        }
                    }
                    if (complete && audioHandle != null) {
                        for (plan in audioPlan.stagingDirectories) {
                            audioChanged = true
                            if (!cleanupOwnedStagingDirectory(filesHandle, identity, audioHandle, plan)) {
                                complete = false
                                break
                            }
                        }
                    }
                    if (complete && audioHandle != null) {
                        for (plan in audioPlan.emptyGenerationDirectories) {
                            audioChanged = true
                            if (!cleanupEmptyGenerationDirectory(filesHandle, identity, audioHandle, plan)) {
                                complete = false
                                break
                            }
                        }
                    }
                    complete && anchorsMatch(filesHandle, identity, audioHandle) && hasNoRecoveryJournal(filesHandle) &&
                        journalPlan.temporaryFiles.none { hasEntryNoFollow(filesHandle, it.entryName) }
                }
            }
        } catch (_: Exception) {
            false
        }

        val filesSynced = if (forceSyncDirectories || filesChanged) syncDirectory(filesDirectory, filesHandle) else true
        val audioSynced = if (audioHandle != null && (forceSyncDirectories || audioChanged)) {
            syncDirectory(audioDirectory, audioHandle)
        } else {
            true
        }
        val finalResult = cleanupSucceeded && filesSynced && audioSynced && anchorsMatch(filesHandle, identity, audioHandle) &&
            hasNoRecoveryJournal(filesHandle)
        return finalResult
    }

    private fun collectJournalPlan(filesHandle: SecureDirectoryStream<Path>): JournalPlan? {
        val plans = mutableListOf<TemporaryFilePlan>()
        var entriesVisited = 0
        for (entry in filesHandle) {
            if (++entriesVisited > maximumDirectoryEntries) return null
            val entryName = entry.fileName ?: return null
            if (!isSingleChild(entryName)) return null
            val quarantineTarget = PrivateAudioPath.deletionQuarantineTargetName(entryName)
            val targetName = quarantineTarget ?: entryName
            if (!isTemporaryJournalName(targetName.toString())) continue
            if (plans.size >= maximumStagingAssets) return null
            val attributes = readAttributesNoFollow(filesHandle, entryName) ?: return null
            if (!attributes.isRegularFile || attributes.isSymbolicLink) continue
            if (!isBoundedRegularFile(attributes) || attributes.fileKey() == null) return null
            plans += TemporaryFilePlan(
                entryName = entryName,
                targetName = targetName,
                fileKey = attributes.fileKey() ?: return null,
                isQuarantine = quarantineTarget != null,
            )
        }
        return JournalPlan(plans)
    }

    private fun collectAudioPlan(
        filesHandle: SecureDirectoryStream<Path>,
        identity: DirectoryIdentity,
        audioHandle: SecureDirectoryStream<Path>,
    ): AudioPlan? {
        val staging = mutableListOf<StagingDirectoryPlan>()
        val generations = mutableListOf<GenerationDirectoryPlan>()
        var candidates = 0
        var entriesVisited = 0
        for (entry in audioHandle) {
            if (++entriesVisited > maximumDirectoryEntries) return null
            val name = entry.fileName ?: return null
            if (!isSingleChild(name)) return null
            val nameText = name.toString()
            val stagingId = PrivateAudioPath.restoreStageId(nameText)
            val generationId = PrivateAudioPath.restoreGenerationId(nameText)
            if (stagingId != null) {
                if (++candidates > maximumStagingAssets) return null
                when (val result = inspectStagingDirectory(filesHandle, identity, audioHandle, name, stagingId)) {
                    null -> return null
                    is StagingInspection -> result.plan?.let(staging::add)
                }
            } else if (generationId != null) {
                if (++candidates > maximumStagingAssets) return null
                when (val result = inspectGenerationDirectory(audioHandle, name)) {
                    null -> return null
                    is GenerationInspection -> result.plan?.let(generations::add)
                }
            }
        }
        return AudioPlan(staging, generations)
    }

    private data class StagingInspection(val plan: StagingDirectoryPlan?)

    private fun inspectStagingDirectory(
        filesHandle: SecureDirectoryStream<Path>,
        identity: DirectoryIdentity,
        audioHandle: SecureDirectoryStream<Path>,
        name: Path,
        id: String,
    ): StagingInspection? {
        val attributes = readAttributesNoFollow(audioHandle, name) ?: return null
        if (!attributes.isDirectory || attributes.isSymbolicLink) return StagingInspection(plan = null)
        val directoryKey = attributes.fileKey() ?: return null
        val directory = File(audioDirectory, name.toString())
        if (!PrivateAudioPath.isDirectChild(audioDirectory, directory)) return null
        val stagingHandle = try {
            audioHandle.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return null
        }
        return stagingHandle.use { handle ->
            if (!audioAnchorMatches(audioHandle, identity) ||
                !PrivateAudioPath.isOpenedDirectoryKey(directoryKey, handle) ||
                !PrivateAudioPath.isSameDirectory(directory, handle)
            ) return null
            val markerName = handlePath(handle, ownerMarkerName)
            val markerAttributes = readAttributesNoFollow(handle, markerName)
                ?: return StagingInspection(plan = null)
            if (!markerAttributes.isRegularFile || markerAttributes.fileKey() == null ||
                !PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(handle, markerName, id)
            ) return null
            val children = childNames(handle, maximumStagingAssets + 1) ?: return null
            if (children.count { it == markerName } != 1) return null
            val childKeys = linkedMapOf<Path, Any>()
            val assets = mutableListOf<StagedAssetPlan>()
            val quarantines = mutableListOf<StagedQuarantinePlan>()
            children.forEach { childName ->
                if (childName == markerName) {
                    childKeys[childName] = markerAttributes.fileKey() ?: return null
                    return@forEach
                }
                val quarantineTarget = PrivateAudioPath.deletionQuarantineTargetName(childName)
                val targetName = quarantineTarget ?: childName
                if (!isSafeStagedAssetName(targetName.toString())) return null
                if (resolveChild(directory, targetName) in activeAudioPaths) return null
                val childAttributes = readAttributesNoFollow(handle, childName) ?: return null
                if (!childAttributes.isRegularFile || childAttributes.fileKey() == null) return null
                val childKey = childAttributes.fileKey() ?: return null
                childKeys[childName] = childKey
                if (quarantineTarget == null) {
                    assets += StagedAssetPlan(childName, childKey)
                } else {
                    quarantines += StagedQuarantinePlan(targetName, childName, childKey)
                }
            }
            if (assets.size + quarantines.size > maximumStagingAssets) return null
            StagingInspection(
                StagingDirectoryPlan(
                    name = name,
                    id = id,
                    directory = directory,
                    directoryKey = directoryKey,
                    ownerMarkerKey = markerAttributes.fileKey() ?: return null,
                    childKeys = childKeys,
                    assets = assets,
                    quarantines = quarantines,
                ),
            )
        }
    }

    private data class GenerationInspection(val plan: GenerationDirectoryPlan?)

    private fun inspectGenerationDirectory(
        audioHandle: SecureDirectoryStream<Path>,
        name: Path,
    ): GenerationInspection? {
        val attributes = readAttributesNoFollow(audioHandle, name) ?: return null
        if (!attributes.isDirectory || attributes.isSymbolicLink) return GenerationInspection(plan = null)
        val directoryKey = attributes.fileKey() ?: return null
        val directory = File(audioDirectory, name.toString())
        if (!PrivateAudioPath.isDirectChild(audioDirectory, directory)) return null
        val generationHandle = try {
            audioHandle.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return null
        }
        return generationHandle.use { handle ->
            if (!PrivateAudioPath.isOpenedDirectoryKey(directoryKey, handle) ||
                !PrivateAudioPath.isSameDirectory(directory, handle)
            ) return null
            val empty = isEmptyDirectory(handle) ?: return null
            if (!empty || activeAudioPaths.any { it.startsWith(directory.toPath().toAbsolutePath().normalize()) }) {
                return GenerationInspection(plan = null)
            }
            GenerationInspection(GenerationDirectoryPlan(name, directory, directoryKey))
        }
    }

    private fun cleanupOwnedStagingDirectory(
        filesHandle: SecureDirectoryStream<Path>,
        identity: DirectoryIdentity,
        audioHandle: SecureDirectoryStream<Path>,
        plan: StagingDirectoryPlan,
    ): Boolean {
        if (!canMutateAudio(filesHandle, identity, audioHandle)) return false
        val attributes = readAttributesNoFollow(audioHandle, plan.name) ?: return false
        if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.fileKey() != plan.directoryKey) return false
        val opened = try {
            audioHandle.newDirectoryStream(plan.name, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return false
        }
        var changed = false
        var removed = false
        var directorySynced = true
        val cleanupSucceeded = try {
            if (!PrivateAudioPath.isOpenedDirectoryKey(plan.directoryKey, opened) ||
                !PrivateAudioPath.isSameDirectory(plan.directory, opened) ||
                !hasExpectedOwner(opened, plan)
            ) return false
            val children = childNames(opened, maximumStagingAssets + 1) ?: return false
            if (children.toSet() != plan.childKeys.keys || children.size != plan.childKeys.size) return false
            for (childName in children) {
                val key = plan.childKeys[childName] ?: return false
                if (PrivateAudioPath.regularFileKeyNoFollow(opened, childName) != key) return false
            }
            for (quarantine in plan.quarantines) {
                if (!canMutateStaging(filesHandle, identity, audioHandle, opened, plan) ||
                    PrivateAudioPath.regularFileKeyNoFollow(opened, quarantine.entryName) != quarantine.fileKey
                ) return false
                changed = true
                val cleanedQuarantines = cleanupAssetDeletionQuarantines(opened, listOf(quarantine.entryName)) ?: return false
                if (quarantine.entryName !in cleanedQuarantines) return false
            }
            for (asset in plan.assets) {
                if (!canMutateStaging(filesHandle, identity, audioHandle, opened, plan) ||
                    PrivateAudioPath.regularFileKeyNoFollow(opened, asset.name) != asset.fileKey
                ) return false
                changed = true
                if (!PrivateAudioPath.deleteRegularFileNoFollow(opened, asset.name, asset.fileKey)) return false
            }
            if (!canMutateStaging(filesHandle, identity, audioHandle, opened, plan) ||
                PrivateAudioPath.regularFileKeyNoFollow(opened, handlePath(opened, ownerMarkerName)) != plan.ownerMarkerKey
            ) return false
            changed = true
            if (!PrivateAudioPath.deleteRegularFileNoFollow(
                    opened,
                    handlePath(opened, ownerMarkerName),
                    plan.ownerMarkerKey,
                )
            ) return false
            if (!syncDirectory(plan.directory, opened)) return false
            val emptyAfterSync = isDirectoryEmptyAtPath(audioHandle, plan.name, plan.directory, plan.directoryKey)
            val parentStillAnchored = canMutateAudio(filesHandle, identity, audioHandle)
            if (emptyAfterSync != true || !parentStillAnchored) {
                return false
            }
            val finalAttributes = readAttributesNoFollow(audioHandle, plan.name) ?: return false
            if (!finalAttributes.isDirectory || finalAttributes.isSymbolicLink ||
                finalAttributes.fileKey() != plan.directoryKey || !PrivateAudioPath.isSameDirectory(plan.directory, opened)
            ) return false
            audioHandle.deleteDirectory(plan.name)
            removed = true
            if (readAttributesNoFollow(audioHandle, plan.name) != null ||
                !audioAnchorMatches(audioHandle, identity)
            ) return false
            true
        } catch (_: Exception) {
            false
        } finally {
            if (changed && !removed) directorySynced = syncDirectory(plan.directory, opened)
            runCatching { opened.close() }
        }
        return cleanupSucceeded && directorySynced
    }

    private fun cleanupEmptyGenerationDirectory(
        filesHandle: SecureDirectoryStream<Path>,
        identity: DirectoryIdentity,
        audioHandle: SecureDirectoryStream<Path>,
        plan: GenerationDirectoryPlan,
    ): Boolean {
        if (!canMutateAudio(filesHandle, identity, audioHandle)) return false
        val attributes = readAttributesNoFollow(audioHandle, plan.name) ?: return false
        if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.fileKey() != plan.directoryKey) return false
        val opened = try {
            audioHandle.newDirectoryStream(plan.name, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return false
        }
        return opened.use { handle ->
            if (!PrivateAudioPath.isOpenedDirectoryKey(plan.directoryKey, handle) ||
                !PrivateAudioPath.isSameDirectory(plan.directory, handle) ||
                isEmptyDirectory(handle) != true ||
                activeAudioPaths.any { it.startsWith(plan.directory.toPath().toAbsolutePath().normalize()) } ||
                !canMutateAudio(filesHandle, identity, audioHandle)
            ) return false
            val finalAttributes = readAttributesNoFollow(audioHandle, plan.name) ?: return false
            val generationEmpty = isDirectoryEmptyAtPath(audioHandle, plan.name, plan.directory, plan.directoryKey)
            val generationKeyMatches = finalAttributes.fileKey() == plan.directoryKey
            if (!finalAttributes.isDirectory || finalAttributes.isSymbolicLink ||
                !generationKeyMatches || generationEmpty != true
            ) {
                return false
            }
            audioHandle.deleteDirectory(plan.name)
            readAttributesNoFollow(audioHandle, plan.name) == null && audioAnchorMatches(audioHandle, identity)
        }
    }

    private fun canMutateFiles(
        filesHandle: SecureDirectoryStream<Path>,
        identity: DirectoryIdentity,
    ): Boolean = filesAnchorMatches(filesHandle, identity.filesKey) && hasNoRecoveryJournal(filesHandle)

    private fun canMutateAudio(
        filesHandle: SecureDirectoryStream<Path>,
        identity: DirectoryIdentity,
        audioHandle: SecureDirectoryStream<Path>,
    ): Boolean = anchorsMatch(filesHandle, identity, audioHandle) && hasNoRecoveryJournal(filesHandle)

    private fun canMutateStaging(
        filesHandle: SecureDirectoryStream<Path>,
        identity: DirectoryIdentity,
        audioHandle: SecureDirectoryStream<Path>,
        stagingHandle: SecureDirectoryStream<Path>,
        plan: StagingDirectoryPlan,
    ): Boolean = canMutateAudio(filesHandle, identity, audioHandle) &&
        PrivateAudioPath.isOpenedDirectoryKey(plan.directoryKey, stagingHandle) &&
        PrivateAudioPath.isSameDirectory(plan.directory, stagingHandle) && hasExpectedOwner(stagingHandle, plan)

    private fun hasExpectedOwner(
        stagingHandle: SecureDirectoryStream<Path>,
        plan: StagingDirectoryPlan,
    ): Boolean = PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(
        stagingHandle,
        handlePath(stagingHandle, ownerMarkerName),
        plan.id,
    )

    private fun filesAnchorMatches(filesHandle: SecureDirectoryStream<Path>, filesKey: Any): Boolean =
        PrivateAudioPath.isOpenedDirectoryKey(filesKey, filesHandle) &&
            PrivateAudioPath.isSameDirectory(filesDirectory, filesHandle)

    private fun audioAnchorMatches(
        audioHandle: SecureDirectoryStream<Path>,
        identity: DirectoryIdentity,
    ): Boolean {
        val audioKey = identity.audioKey ?: return false
        return PrivateAudioPath.isOpenedDirectoryKey(audioKey, audioHandle) &&
            PrivateAudioPath.isSameDirectory(audioDirectory, audioHandle)
    }

    private fun anchorsMatch(
        filesHandle: SecureDirectoryStream<Path>,
        identity: DirectoryIdentity,
        audioHandle: SecureDirectoryStream<Path>?,
    ): Boolean {
        if (!filesAnchorMatches(filesHandle, identity.filesKey)) return false
        return if (identity.audioKey == null) {
            audioHandle == null && readAttributesNoFollow(
                filesHandle,
                filesDirectory.toPath().fileSystem.getPath(audioDirectory.name),
            ) == null
        } else {
            audioHandle != null && audioAnchorMatches(audioHandle, identity)
        }
    }

    private fun hasNoRecoveryJournal(filesHandle: SecureDirectoryStream<Path>): Boolean =
        runCatching { journalIsAbsent(filesHandle) }.getOrDefault(false)

    private fun syncDirectory(directory: File, opened: SecureDirectoryStream<Path>): Boolean =
        runCatching { durability.syncDirectory(directory, opened) }.isSuccess

    private fun isTemporaryJournalName(name: String): Boolean {
        if (!name.startsWith(journalTemporaryPrefix)) return false
        return isUuid(name.removePrefix(journalTemporaryPrefix))
    }

    private fun isBoundedRegularFile(attributes: BasicFileAttributes): Boolean =
        attributes.isRegularFile && attributes.size() in 0..maximumJournalBytes

    private fun readAttributesNoFollow(
        directory: SecureDirectoryStream<Path>,
        childName: Path,
    ): BasicFileAttributes? {
        if (!isSingleChild(childName)) throw IOException("Unsafe private cleanup child name.")
        return try {
            directory.getFileAttributeView(
                childName,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )?.readAttributes() ?: throw IOException("Secure file attributes are unavailable.")
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun hasEntryNoFollow(directory: SecureDirectoryStream<Path>, childName: Path): Boolean =
        runCatching { readAttributesNoFollow(directory, childName) != null }.getOrDefault(false)

    private fun childNames(directory: SecureDirectoryStream<Path>, maximumChildren: Int): List<Path>? {
        val children = ArrayList<Path>()
        for (entry in directory) {
            val name = entry.fileName ?: return null
            if (!isSingleChild(name) || children.size >= maximumChildren) return null
            children.add(name)
        }
        return children
    }

    private fun isEmptyDirectory(directory: SecureDirectoryStream<Path>): Boolean? = try {
        !directory.iterator().hasNext()
    } catch (_: Exception) {
        null
    }

    private fun isDirectoryEmptyAtPath(
        parent: SecureDirectoryStream<Path>,
        directoryName: Path,
        directory: File,
        expectedDirectoryKey: Any,
    ): Boolean? {
        val attributes = readAttributesNoFollow(parent, directoryName) ?: return null
        if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.fileKey() != expectedDirectoryKey) {
            return null
        }
        val opened = try {
            parent.newDirectoryStream(directoryName, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return null
        }
        return opened.use { handle ->
            if (!PrivateAudioPath.isOpenedDirectoryKey(expectedDirectoryKey, handle) ||
                !PrivateAudioPath.isSameDirectory(directory, handle)
            ) null else isEmptyDirectory(handle)
        }
    }

    private fun handlePath(directory: SecureDirectoryStream<Path>, name: String): Path =
        directoryPathFileSystem(directory).getPath(name)

    private fun directoryPathFileSystem(directory: SecureDirectoryStream<Path>): java.nio.file.FileSystem =
        filesDirectory.toPath().fileSystem

    private fun isSingleChild(name: Path): Boolean =
        !name.isAbsolute && name.nameCount == 1 && name.toString() !in setOf(".", "..") &&
            '/' !in name.toString() && '\\' !in name.toString()

    private fun resolveChild(directory: File, childName: Path): Path =
        File(directory, childName.toString()).toPath().toAbsolutePath().normalize()
}
