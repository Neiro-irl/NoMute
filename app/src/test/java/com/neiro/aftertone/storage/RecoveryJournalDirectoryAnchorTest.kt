package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryJournalDirectoryAnchorTest {
    @Test
    fun replacingParentAfterOpeningAnchoredChildStopsBeforeReadingJournalBytes() {
        val root = Files.createTempDirectory("aftertone-journal-parent-race-").toFile()
        val directory = File(root, "active").apply { mkdir() }
        val replacement = File(root, "replacement").apply { mkdir() }
        val displaced = File(root, "displaced")
        val file = File(directory, "journal")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        File(replacement, "journal").writeBytes(byteArrayOf(4, 3, 2, 1))
        val operations = ParentReplacementOperations(directory) {
            Files.move(directory.toPath(), displaced.toPath())
            Files.move(replacement.toPath(), directory.toPath())
        }

        try {
            val failure = runCatching {
                RecoveryJournalFileAccess(operations).read(
                    file,
                    verifiedDirectoryStillMatches = operations::isVerifiedDirectoryStillSame,
                )
            }.exceptionOrNull()

            assertTrue("A replaced verified directory must not be read.", failure is RecoveryJournalReadFailure)
            assertEquals(
                "DIRECTORY_PATH_VALIDATION_FAILURE",
                (failure as RecoveryJournalReadFailure).diagnosis.name,
            )
            assertEquals("Directory replacement must be detected before reading bytes.", 0, operations.readCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    private class ParentReplacementOperations(
        private val directory: File,
        private val afterChildOpen: () -> Unit,
    ) : RecoveryJournalFileOperations {
        private var openedStream: SecureDirectoryStream<Path>? = null
        var readCalls: Int = 0
            private set

        fun isVerifiedDirectoryStillSame(): Boolean {
            val stream = openedStream ?: return false
            return PrivateAudioPath.isSameDirectory(directory, stream)
        }

        override fun openDirectory(directory: File): RecoveryJournalDirectoryHandle {
            val stream = PrivateAudioPath.openDirectoryNoFollow(directory)
            openedStream = stream
            return object : RecoveryJournalDirectoryHandle {
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
                    val view = stream.getFileAttributeView(
                        childPath(name),
                        BasicFileAttributeView::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    ) ?: return null
                    stat(view.readAttributes())
                } catch (_: java.nio.file.NoSuchFileException) {
                    null
                }

                override fun openReadOnly(
                    name: String,
                    options: RecoveryJournalOpenOptions,
                ): RecoveryJournalReadHandle {
                    assertTrue(options.readOnly && options.noFollow && options.nonBlocking && options.closeOnExec)
                    val channel = stream.newByteChannel(
                        childPath(name),
                        setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                    )
                    afterChildOpen()
                    return object : RecoveryJournalReadHandle {
                        override fun fstat(): RecoveryJournalFileStat =
                            lstat(name) ?: throw RecoveryJournalReadFailure(
                                RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE,
                            )

                        override fun read(target: ByteArray, offset: Int, length: Int): Int {
                            readCalls += 1
                            return channel.read(ByteBuffer.wrap(target, offset, length))
                        }

                        override fun close() {
                            channel.close()
                        }
                    }
                }

                override fun close() {
                    stream.close()
                }
            }
        }

        private fun childPath(name: String): Path = directory.toPath().fileSystem.getPath(name)

        private fun stat(attributes: BasicFileAttributes): RecoveryJournalFileStat {
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
    }
}
