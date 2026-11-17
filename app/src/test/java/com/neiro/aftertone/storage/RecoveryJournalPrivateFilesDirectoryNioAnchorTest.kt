package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import java.io.File
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryJournalPrivateFilesDirectoryNioAnchorTest {
    @Test
    fun rootOpenDeniedStillAllowsOnlyDirectFilesDirectoryAnchor() {
        val filesDirectory = Files.createTempDirectory("aftertone-direct-files-anchor-")
        val requestedPaths = mutableListOf<Path>()
        var rootOpenDenied = false
        var opened: SecureDirectoryStream<Path>? = null

        try {
            opened = PrivateFilesDirectoryNioAnchor.open(filesDirectory.toFile()) { path ->
                requestedPaths.add(path)
                if (path == path.root) {
                    rootOpenDenied = true
                    throw AccessDeniedException(path.toString(), null, "private root detail")
                }
                Files.newDirectoryStream(path)
            }

            assertFalse("The direct filesDir anchor must not try to open filesystem root.", rootOpenDenied)
            assertEquals("Only filesDir itself may be opened.", listOf(filesDirectory), requestedPaths)
            assertTrue("The returned secure stream must identify the supplied filesDir.",
                PrivateAudioPath.isSameDirectory(filesDirectory.toFile(), opened))
        } finally {
            opened?.close()
            Files.deleteIfExists(filesDirectory)
        }
    }

    @Test
    fun symlinkedFilesDirectoryIsRejectedAndOpenedStreamIsClosed() {
        val parent = Files.createTempDirectory("aftertone-anchor-symlink-")
        val target = Files.createDirectory(parent.resolve("target"))
        val symlink = Files.createSymbolicLink(parent.resolve("files"), target.fileName)
        var tracked: CloseTrackingSecureDirectoryStream? = null

        try {
            val failure = runCatching {
                PrivateFilesDirectoryNioAnchor.open(symlink.toFile()) { path ->
                    Files.newDirectoryStream(path).let { stream ->
                        val secure = stream as SecureDirectoryStream<Path>
                        CloseTrackingSecureDirectoryStream(secure).also { tracked = it }
                    }
                }
            }.exceptionOrNull()

            assertTrue("A symlinked filesDir must fail closed.", failure is RecoveryJournalReadFailure)
            assertEquals(
                RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE,
                (failure as RecoveryJournalReadFailure).diagnosis,
            )
            assertNull("Path details must not be exposed.", failure.message)
            assertEquals("The rejected stream must be closed exactly once.", 1, tracked?.closeCalls)
        } finally {
            tracked?.closeIfOpen()
            Files.deleteIfExists(symlink)
            Files.deleteIfExists(target)
            Files.deleteIfExists(parent)
        }
    }

    @Test
    fun replacingFilesDirectoryAfterOpenFailsIdentityCheckAndClosesAnchor() {
        val parent = Files.createTempDirectory("aftertone-anchor-race-")
        val filesDirectory = Files.createDirectory(parent.resolve("files"))
        val replacement = Files.createDirectory(parent.resolve("replacement"))
        val displaced = parent.resolve("displaced")
        var tracked: CloseTrackingSecureDirectoryStream? = null

        try {
            val failure = runCatching {
                PrivateFilesDirectoryNioAnchor.open(filesDirectory.toFile()) { path ->
                    val stream = Files.newDirectoryStream(path) as SecureDirectoryStream<Path>
                    val wrapper = CloseTrackingSecureDirectoryStream(stream).also { tracked = it }
                    Files.move(filesDirectory, displaced)
                    Files.move(replacement, filesDirectory)
                    wrapper
                }
            }.exceptionOrNull()

            assertTrue("A replaced filesDir path must fail closed.", failure is RecoveryJournalReadFailure)
            assertEquals(
                RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE,
                (failure as RecoveryJournalReadFailure).diagnosis,
            )
            assertEquals("The displaced anchor must be closed before any journal read.", 1, tracked?.closeCalls)
        } finally {
            tracked?.closeIfOpen()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun directFilesDirectoryOpenFailureDoesNotFallBackAndExposesOnlyFixedDiagnosis() {
        val filesDirectory = File("/private/app/files")
        val requestedPaths = mutableListOf<Path>()

        val failure = runCatching {
            PrivateFilesDirectoryNioAnchor.open(filesDirectory) { path ->
                requestedPaths.add(path)
                throw AccessDeniedException(path.toString(), null, "private path and policy detail")
            }
        }.exceptionOrNull()

        assertTrue("The open failure must use the fixed journal diagnosis type.", failure is RecoveryJournalReadFailure)
        assertEquals(RecoveryJournalReadDiagnosis.DIRECTORY_OPEN_FAILURE,
            (failure as RecoveryJournalReadFailure).diagnosis)
        assertEquals(RecoveryJournalOsErrorCategory.ACCESS_DENIED, failure.osErrorCategory)
        assertNull("Raw paths and OS details must not escape.", failure.message)
        assertEquals("There must be one direct filesDir attempt and no root fallback.",
            listOf(filesDirectory.toPath()), requestedPaths)
    }

    @Test
    fun unsupportedDirectoryStreamIsClosedBeforeFailing() {
        val stream = CountingDirectoryStream()

        val failure = runCatching {
            PrivateFilesDirectoryNioAnchor.open(File("/private/app/files")) { stream }
        }.exceptionOrNull()

        assertTrue(failure is RecoveryJournalReadFailure)
        assertEquals(RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_STREAM_UNSUPPORTED,
            (failure as RecoveryJournalReadFailure).diagnosis)
        assertEquals("An unsupported provider stream must be closed exactly once.", 1, stream.closeCalls)
    }

    private class CountingDirectoryStream : DirectoryStream<Path> {
        var closeCalls = 0

        override fun iterator(): MutableIterator<Path> =
            throw AssertionError("The direct anchor must not enumerate directory entries.")

        override fun close() {
            closeCalls += 1
        }
    }

    private class CloseTrackingSecureDirectoryStream(
        private val delegate: SecureDirectoryStream<Path>,
    ) : SecureDirectoryStream<Path> {
        var closeCalls = 0
            private set

        override fun iterator(): MutableIterator<Path> = delegate.iterator()

        override fun close() {
            closeCalls += 1
            delegate.close()
        }

        fun closeIfOpen() {
            if (closeCalls == 0) close()
        }

        override fun newDirectoryStream(dir: Path, vararg options: LinkOption): SecureDirectoryStream<Path> =
            delegate.newDirectoryStream(dir, *options)

        override fun newByteChannel(
            path: Path,
            options: MutableSet<out java.nio.file.OpenOption>,
            vararg attrs: FileAttribute<*>,
        ): SeekableByteChannel = delegate.newByteChannel(path, options, *attrs)

        override fun deleteFile(file: Path) = delegate.deleteFile(file)

        override fun deleteDirectory(dir: Path) = delegate.deleteDirectory(dir)

        override fun move(srcpath: Path, targetdir: SecureDirectoryStream<Path>, targetpath: Path) =
            delegate.move(srcpath, targetdir, targetpath)

        override fun <V : FileAttributeView> getFileAttributeView(type: Class<V>): V? =
            delegate.getFileAttributeView(type)

        override fun <V : FileAttributeView> getFileAttributeView(
            path: Path,
            type: Class<V>,
            vararg options: LinkOption,
        ): V? = delegate.getFileAttributeView(path, type, *options)
    }
}
