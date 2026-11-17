package com.neiro.aftertone.storage

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.annotation.RequiresApi
import com.neiro.aftertone.model.PrivateAudioPath
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

internal const val MAX_RECOVERY_JOURNAL_BYTES = 64L * 1024 * 1024

internal enum class RecoveryJournalFileType {
    REGULAR,
    DIRECTORY,
    SPECIAL,
}

internal data class RecoveryJournalFileStat(
    val device: Long,
    val inode: Long,
    val type: RecoveryJournalFileType,
    val size: Long,
    val modifiedSeconds: Long,
    val modifiedNanos: Long,
    val changedSeconds: Long,
    val changedNanos: Long,
    val supportsSubsecondChangeTime: Boolean = true,
)

internal data class RecoveryJournalOpenOptions(
    val readOnly: Boolean,
    val noFollow: Boolean,
    val nonBlocking: Boolean,
    val closeOnExec: Boolean,
)

internal interface RecoveryJournalReadHandle : Closeable {
    fun fstat(): RecoveryJournalFileStat
    fun read(target: ByteArray, offset: Int, length: Int): Int
}

internal interface RecoveryJournalDirectoryHandle : Closeable {
    fun isPathStillSame(): Boolean
    fun lstat(name: String): RecoveryJournalFileStat?
    fun openReadOnly(name: String, options: RecoveryJournalOpenOptions): RecoveryJournalReadHandle?
    fun isSameDirectoryAs(verifiedDirectory: SecureDirectoryStream<Path>): Boolean
}

internal interface RecoveryJournalFileOperations {
    fun openDirectory(directory: File): RecoveryJournalDirectoryHandle
}

private fun failJournalRead(diagnosis: RecoveryJournalReadDiagnosis, error: Throwable): Nothing =
    throw RecoveryJournalReadFailure(diagnosis, recoveryJournalOsErrorCategory(error))

internal class RecoveryJournalFileAccess(
    private val operations: RecoveryJournalFileOperations = defaultRecoveryJournalFileOperations(),
) {
    fun read(
        file: File,
        maximumBytes: Long = MAX_RECOVERY_JOURNAL_BYTES,
        requirePreciseChangeTime: Boolean = true,
        verifiedDirectoryStillMatches: () -> Boolean = { true },
    ): ByteArray? = readInternal(
        file = file,
        maximumBytes = maximumBytes,
        requirePreciseChangeTime = requirePreciseChangeTime,
        verifiedDirectoryStillMatches = verifiedDirectoryStillMatches,
        verifiedDirectoryHandleMatches = { true },
        readOnlyDiagnostic = false,
        entryPreviouslyObserved = false,
    )

    fun readWithVerifiedDirectoryAnchor(
        file: File,
        verifiedDirectoryAnchor: SecureDirectoryStream<Path>,
        maximumBytes: Long = MAX_RECOVERY_JOURNAL_BYTES,
        verifiedDirectoryStillMatches: () -> Boolean = { true },
        entryPreviouslyObserved: Boolean = false,
    ): ByteArray? = readInternal(
        file = file,
        maximumBytes = maximumBytes,
        requirePreciseChangeTime = true,
        verifiedDirectoryStillMatches = verifiedDirectoryStillMatches,
        verifiedDirectoryHandleMatches = { nativeDirectory ->
            nativeDirectory.isSameDirectoryAs(verifiedDirectoryAnchor)
        },
        readOnlyDiagnostic = true,
        entryPreviouslyObserved = entryPreviouslyObserved,
    )

    private fun readInternal(
        file: File,
        maximumBytes: Long,
        requirePreciseChangeTime: Boolean,
        verifiedDirectoryStillMatches: () -> Boolean,
        verifiedDirectoryHandleMatches: (RecoveryJournalDirectoryHandle) -> Boolean,
        readOnlyDiagnostic: Boolean,
        entryPreviouslyObserved: Boolean,
    ): ByteArray? {
        val absoluteFile = file.absoluteFile
        val directoryFile = absoluteFile.parentFile
            ?: fail(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE)
        val name = absoluteFile.name
        validateJournalName(name)
        val nonRegularDiagnosis = if (readOnlyDiagnostic) {
            RecoveryJournalReadDiagnosis.JOURNAL_NON_REGULAR_OR_SYMLINK
        } else {
            RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE
        }
        val disappearedDiagnosis = if (readOnlyDiagnostic) {
            RecoveryJournalReadDiagnosis.JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED
        } else {
            RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE
        }

        val directory = try {
            operations.openDirectory(directoryFile)
        } catch (failure: RecoveryJournalReadFailure) {
            throw failure
        } catch (error: Exception) {
            failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_OPEN_FAILURE, error)
        }

        return try {
            directory.use { anchoredDirectory ->
                verifyDirectory(anchoredDirectory, verifiedDirectoryStillMatches, verifiedDirectoryHandleMatches)
                val pathStat = try {
                    anchoredDirectory.lstat(name)
                } catch (failure: RecoveryJournalReadFailure) {
                    throw failure
                } catch (error: Exception) {
                    failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE, error)
                } ?: if (readOnlyDiagnostic && entryPreviouslyObserved) {
                    fail(disappearedDiagnosis)
                } else {
                    return@use null
                }

                validateRegularBounded(pathStat, maximumBytes, nonRegularDiagnosis)
                validateTimestampPrecision(pathStat, requirePreciseChangeTime)
                val options = RecoveryJournalOpenOptions(
                    readOnly = true,
                    noFollow = true,
                    nonBlocking = true,
                    closeOnExec = true,
                )
                val opened = try {
                    anchoredDirectory.openReadOnly(name, options)
                } catch (failure: RecoveryJournalReadFailure) {
                    if (readOnlyDiagnostic && failure.diagnosis == RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE) {
                        fail(RecoveryJournalReadDiagnosis.JOURNAL_NON_REGULAR_OR_SYMLINK)
                    }
                    throw failure
                } catch (error: Exception) {
                    failJournalRead(RecoveryJournalReadDiagnosis.JOURNAL_FILE_OPEN_FAILURE, error)
                } ?: if (readOnlyDiagnostic) {
                    fail(RecoveryJournalReadDiagnosis.JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED)
                } else {
                    return@use null
                }

                opened.use { handle ->
                    // Recheck the caller's verified directory before reading any journal bytes.
                    verifyDirectory(anchoredDirectory, verifiedDirectoryStillMatches, verifiedDirectoryHandleMatches)
                    val openedStat = descriptorStat(handle, disappearedDiagnosis)
                    validateRegularBounded(openedStat, maximumBytes, nonRegularDiagnosis)
                    validateTimestampPrecision(openedStat, requirePreciseChangeTime)
                    if (openedStat != pathStat) {
                        fail(RecoveryJournalReadDiagnosis.SIZE_CHANGE_OR_TRUNCATION)
                    }

                    val bytes = ByteArray(openedStat.size.toInt())
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = try {
                            handle.read(bytes, offset, bytes.size - offset)
                        } catch (failure: RecoveryJournalReadFailure) {
                            throw failure
                        } catch (_: Exception) {
                            fail(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN)
                        }
                        if (count <= 0 || count > bytes.size - offset) {
                            fail(RecoveryJournalReadDiagnosis.SIZE_CHANGE_OR_TRUNCATION)
                        }
                        offset += count
                    }

                    val finalDescriptorStat = descriptorStat(handle, disappearedDiagnosis)
                    validateRegularBounded(finalDescriptorStat, maximumBytes, nonRegularDiagnosis)
                    validateTimestampPrecision(finalDescriptorStat, requirePreciseChangeTime)
                    if (finalDescriptorStat != openedStat) {
                        fail(RecoveryJournalReadDiagnosis.SIZE_CHANGE_OR_TRUNCATION)
                    }

                    verifyDirectory(anchoredDirectory, verifiedDirectoryStillMatches, verifiedDirectoryHandleMatches)
                    val finalPathStat = try {
                        anchoredDirectory.lstat(name)
                    } catch (failure: RecoveryJournalReadFailure) {
                        throw failure
                    } catch (error: Exception) {
                        failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE, error)
                    } ?: fail(disappearedDiagnosis)
                    validateRegularBounded(finalPathStat, maximumBytes, disappearedDiagnosis)
                    validateTimestampPrecision(finalPathStat, requirePreciseChangeTime)
                    if (finalPathStat != finalDescriptorStat) {
                        fail(RecoveryJournalReadDiagnosis.SIZE_CHANGE_OR_TRUNCATION)
                    }
                    bytes
                }
            }
        } catch (failure: RecoveryJournalReadFailure) {
            throw failure
        } catch (_: Exception) {
            fail(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN)
        }
    }

    private fun verifyDirectory(
        directory: RecoveryJournalDirectoryHandle,
        verifiedDirectoryStillMatches: () -> Boolean,
        verifiedDirectoryHandleMatches: (RecoveryJournalDirectoryHandle) -> Boolean,
    ) {
        val directoryMatches = try {
            directory.isPathStillSame()
        } catch (failure: RecoveryJournalReadFailure) {
            throw failure
        } catch (error: Exception) {
            failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE, error)
        }
        if (!directoryMatches) fail(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE)
        val callerDirectoryMatches = try {
            verifiedDirectoryStillMatches()
        } catch (failure: RecoveryJournalReadFailure) {
            throw failure
        } catch (error: Exception) {
            failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE, error)
        }
        if (!callerDirectoryMatches) fail(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE)
        val openHandlesMatch = try {
            verifiedDirectoryHandleMatches(directory)
        } catch (failure: RecoveryJournalReadFailure) {
            throw failure
        } catch (error: Exception) {
            failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE, error)
        }
        if (!openHandlesMatch) fail(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE)
    }

    private fun validateJournalName(name: String) {
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name) {
            fail(RecoveryJournalReadDiagnosis.UNSAFE_PATH)
        }
    }

    private fun descriptorStat(
        handle: RecoveryJournalReadHandle,
        missingEntryDiagnosis: RecoveryJournalReadDiagnosis,
    ): RecoveryJournalFileStat = try {
        handle.fstat()
    } catch (failure: RecoveryJournalReadFailure) {
        if (failure.diagnosis == RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE) {
            fail(missingEntryDiagnosis)
        }
        throw failure
    } catch (_: Exception) {
        fail(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN)
    }

    private fun validateRegularBounded(
        stat: RecoveryJournalFileStat,
        maximumBytes: Long,
        nonRegularDiagnosis: RecoveryJournalReadDiagnosis,
    ) {
        if (stat.type != RecoveryJournalFileType.REGULAR) {
            fail(nonRegularDiagnosis)
        }
        if (stat.size !in 1..maximumBytes || stat.size > Int.MAX_VALUE) {
            fail(RecoveryJournalReadDiagnosis.SIZE_CHANGE_OR_TRUNCATION)
        }
    }

    private fun validateTimestampPrecision(
        stat: RecoveryJournalFileStat,
        requirePreciseChangeTime: Boolean,
    ) {
        if (requirePreciseChangeTime && !stat.supportsSubsecondChangeTime) {
            fail(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN)
        }
    }

    private fun fail(diagnosis: RecoveryJournalReadDiagnosis): Nothing =
        throw RecoveryJournalReadFailure(diagnosis)
}

private fun defaultRecoveryJournalFileOperations(): RecoveryJournalFileOperations =
    if (System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)) {
        AndroidRecoveryJournalFileOperations
    } else {
        // Local unit tests run on a regular JVM; Android Os methods are SDK stubs there.
        JvmRecoveryJournalFileOperations
    }

private object AndroidRecoveryJournalFileOperations : RecoveryJournalFileOperations {
    // Linux O_CLOEXEC/O_DIRECTORY ABI values; Android exposes O_CLOEXEC by name from API 27.
    private const val LINUX_O_CLOEXEC = 0x80000
    private const val LINUX_O_DIRECTORY = 0x10000

    override fun openDirectory(directory: File): RecoveryJournalDirectoryHandle {
        val closeOnExec = if (Build.VERSION.SDK_INT >= 27) api27CloseOnExecFlag() else LINUX_O_CLOEXEC
        val descriptor = RecoveryJournalDirectoryOpenDiagnostics.run(RecoveryJournalDirectoryOpenStep.DIRECTORY_OPEN) {
            Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or LINUX_O_DIRECTORY or closeOnExec,
                0,
            )
        }
        val parcelDescriptor = try {
            RecoveryJournalDirectoryOpenDiagnostics.run(RecoveryJournalDirectoryOpenStep.DESCRIPTOR_DUPLICATION) {
                ParcelFileDescriptor.dup(descriptor)
            }
        } finally {
            runCatching { Os.close(descriptor) }
        }
        val handle = try {
            AndroidRecoveryJournalDirectoryHandle(directory, parcelDescriptor)
        } catch (failure: RecoveryJournalReadFailure) {
            runCatching { parcelDescriptor.close() }
            throw failure
        } catch (error: Exception) {
            runCatching { parcelDescriptor.close() }
            failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE, error)
        }
        val pathMatches = try {
            handle.isPathStillSame()
        } catch (failure: RecoveryJournalReadFailure) {
            runCatching { handle.close() }
            throw failure
        } catch (error: Exception) {
            runCatching { handle.close() }
            failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE, error)
        }
        if (!pathMatches) {
            runCatching { handle.close() }
            fail(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE)
        }
        return handle
    }

    @RequiresApi(27)
    private fun api27CloseOnExecFlag(): Int = OsConstants.O_CLOEXEC

    private fun fail(diagnosis: RecoveryJournalReadDiagnosis): Nothing =
        throw RecoveryJournalReadFailure(diagnosis)
}

private class AndroidRecoveryJournalDirectoryHandle(
    private val directory: File,
    private val descriptor: ParcelFileDescriptor,
) : RecoveryJournalDirectoryHandle {
    private val originalStat = RecoveryJournalDirectoryOpenDiagnostics.run(
        RecoveryJournalDirectoryOpenStep.DIRECTORY_DESCRIPTOR_STAT,
    ) { androidStat(Os.fstat(descriptor.fileDescriptor)) }
    // A child lookup through this procfs descriptor stays anchored if the original parent path is replaced.
    private val childPathRoot = "/proc/self/fd/${descriptor.fd}"

    init {
        if (originalStat.type != RecoveryJournalFileType.DIRECTORY) {
            failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE)
        }
    }

    override fun isPathStillSame(): Boolean {
        val currentStat = RecoveryJournalDirectoryOpenDiagnostics.run(
            RecoveryJournalDirectoryOpenStep.DIRECTORY_PATH_IDENTITY_STAT,
        ) { androidStat(Os.lstat(directory.absolutePath)) }
        return sameDirectoryIdentity(originalStat, currentStat)
    }

    override fun isSameDirectoryAs(verifiedDirectory: SecureDirectoryStream<Path>): Boolean = try {
        val nativeDescriptorAttributes = Files.readAttributes(
            File("/proc/self/fd/${descriptor.fd}").toPath(),
            BasicFileAttributes::class.java,
        )
        val verifiedAttributes = verifiedDirectory
            .getFileAttributeView(BasicFileAttributeView::class.java)
            ?.readAttributes() ?: return false
        recoveryJournalSameDirectoryFileKey(verifiedAttributes, nativeDescriptorAttributes)
    } catch (_: Exception) {
        false
    }

    override fun lstat(name: String): RecoveryJournalFileStat? = try {
        androidStat(Os.lstat(childPath(name)))
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }

    override fun openReadOnly(
        name: String,
        options: RecoveryJournalOpenOptions,
    ): RecoveryJournalReadHandle? {
        if (!options.readOnly || !options.noFollow || !options.nonBlocking || !options.closeOnExec) {
            failJournalRead(RecoveryJournalReadDiagnosis.JOURNAL_FILE_OPEN_FAILURE)
        }
        val closeOnExec = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0x80000
        val flags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or closeOnExec
        val fileDescriptor = try {
            Os.open(childPath(name), flags, 0)
        } catch (error: ErrnoException) {
            when (error.errno) {
                OsConstants.ENOENT -> return null
                OsConstants.ELOOP -> failJournalRead(RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE)
                else -> throw error
            }
        }
        return AndroidRecoveryJournalReadHandle(fileDescriptor)
    }

    private fun childPath(name: String): String = "$childPathRoot/$name"

    override fun close() {
        descriptor.close()
    }
}

private class AndroidRecoveryJournalReadHandle(
    private val descriptor: FileDescriptor,
) : RecoveryJournalReadHandle {
    override fun fstat(): RecoveryJournalFileStat = androidStat(Os.fstat(descriptor))

    override fun read(target: ByteArray, offset: Int, length: Int): Int =
        Os.read(descriptor, target, offset, length)

    override fun close() {
        Os.close(descriptor)
    }
}

private fun androidStat(stat: StructStat): RecoveryJournalFileStat {
    val type = when (stat.st_mode and OsConstants.S_IFMT) {
        OsConstants.S_IFREG -> RecoveryJournalFileType.REGULAR
        OsConstants.S_IFDIR -> RecoveryJournalFileType.DIRECTORY
        else -> RecoveryJournalFileType.SPECIAL
    }
    val times = if (Build.VERSION.SDK_INT >= 27) {
        api27Times(stat)
    } else {
        RecoveryJournalStatTimes(
            modifiedSeconds = stat.st_mtime,
            modifiedNanos = 0,
            changedSeconds = stat.st_ctime,
            changedNanos = 0,
            supportsSubsecondChangeTime = false,
        )
    }
    return RecoveryJournalFileStat(
        device = stat.st_dev,
        inode = stat.st_ino,
        type = type,
        size = stat.st_size,
        modifiedSeconds = times.modifiedSeconds,
        modifiedNanos = times.modifiedNanos,
        changedSeconds = times.changedSeconds,
        changedNanos = times.changedNanos,
        supportsSubsecondChangeTime = times.supportsSubsecondChangeTime,
    )
}

private data class RecoveryJournalStatTimes(
    val modifiedSeconds: Long,
    val modifiedNanos: Long,
    val changedSeconds: Long,
    val changedNanos: Long,
    val supportsSubsecondChangeTime: Boolean,
)

@RequiresApi(27)
private fun api27Times(stat: StructStat) = RecoveryJournalStatTimes(
    modifiedSeconds = stat.st_mtim.tv_sec,
    modifiedNanos = stat.st_mtim.tv_nsec,
    changedSeconds = stat.st_ctim.tv_sec,
    changedNanos = stat.st_ctim.tv_nsec,
    supportsSubsecondChangeTime = true,
)

private object JvmRecoveryJournalFileOperations : RecoveryJournalFileOperations {
    override fun openDirectory(directory: File): RecoveryJournalDirectoryHandle {
        val stream = try {
            PrivateAudioPath.openDirectoryNoFollow(directory)
        } catch (error: Exception) {
            failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_OPEN_FAILURE, error)
        }
        if (!PrivateAudioPath.isSameDirectory(directory, stream)) {
            runCatching { stream.close() }
            failJournalRead(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE)
        }
        return JvmRecoveryJournalDirectoryHandle(directory, stream)
    }
}

private class JvmRecoveryJournalDirectoryHandle(
    private val directory: File,
    private val stream: SecureDirectoryStream<Path>,
) : RecoveryJournalDirectoryHandle {
    override fun isPathStillSame(): Boolean = PrivateAudioPath.isSameDirectory(directory, stream)

    override fun isSameDirectoryAs(verifiedDirectory: SecureDirectoryStream<Path>): Boolean = try {
        val openedAttributes = stream
            .getFileAttributeView(BasicFileAttributeView::class.java)
            ?.readAttributes() ?: return false
        val verifiedAttributes = verifiedDirectory
            .getFileAttributeView(BasicFileAttributeView::class.java)
            ?.readAttributes() ?: return false
        recoveryJournalSameDirectoryFileKey(openedAttributes, verifiedAttributes)
    } catch (_: Exception) {
        false
    }

    override fun lstat(name: String): RecoveryJournalFileStat? = try {
        val child = childPath(name)
        val view = stream.getFileAttributeView(child, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: return null
        jvmStat(view.readAttributes())
    } catch (_: NoSuchFileException) {
        null
    }

    override fun openReadOnly(
        name: String,
        options: RecoveryJournalOpenOptions,
    ): RecoveryJournalReadHandle? {
        if (!options.readOnly || !options.noFollow || !options.nonBlocking || !options.closeOnExec) {
            failJournalRead(RecoveryJournalReadDiagnosis.JOURNAL_FILE_OPEN_FAILURE)
        }
        val child = childPath(name)
        val channel = try {
            stream.newByteChannel(child, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        } catch (_: NoSuchFileException) {
            return null
        }
        return JvmRecoveryJournalReadHandle(this, name, channel)
    }

    fun statChild(name: String): RecoveryJournalFileStat? = lstat(name)

    private fun childPath(name: String): Path = directory.toPath().fileSystem.getPath(name)

    override fun close() {
        stream.close()
    }
}

private class JvmRecoveryJournalReadHandle(
    private val directory: JvmRecoveryJournalDirectoryHandle,
    private val name: String,
    private val channel: SeekableByteChannel,
) : RecoveryJournalReadHandle {
    override fun fstat(): RecoveryJournalFileStat = directory.statChild(name)
        ?: failJournalRead(RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE)

    override fun read(target: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(target, offset, length))

    override fun close() {
        channel.close()
    }
}

private fun jvmStat(attributes: BasicFileAttributes): RecoveryJournalFileStat {
    val modifiedNanos = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)
    val type = when {
        attributes.isRegularFile -> RecoveryJournalFileType.REGULAR
        attributes.isDirectory -> RecoveryJournalFileType.DIRECTORY
        else -> RecoveryJournalFileType.SPECIAL
    }
    return RecoveryJournalFileStat(
        device = 0,
        inode = attributes.fileKey()?.toString()?.hashCode()?.toLong() ?: 0,
        type = type,
        size = attributes.size(),
        modifiedSeconds = TimeUnit.NANOSECONDS.toSeconds(modifiedNanos),
        modifiedNanos = modifiedNanos % TimeUnit.SECONDS.toNanos(1),
        changedSeconds = 0,
        changedNanos = 0,
    )
}

internal fun recoveryJournalSameDirectoryFileKey(
    first: BasicFileAttributes,
    second: BasicFileAttributes,
): Boolean = first.isDirectory && second.isDirectory &&
    first.fileKey() != null && first.fileKey() == second.fileKey()

private fun sameDirectoryIdentity(
    first: RecoveryJournalFileStat,
    second: RecoveryJournalFileStat,
): Boolean = first.type == RecoveryJournalFileType.DIRECTORY &&
    second.type == RecoveryJournalFileType.DIRECTORY &&
    first.device == second.device &&
    first.inode == second.inode

private fun failJournalRead(diagnosis: RecoveryJournalReadDiagnosis): Nothing =
    throw RecoveryJournalReadFailure(diagnosis)
