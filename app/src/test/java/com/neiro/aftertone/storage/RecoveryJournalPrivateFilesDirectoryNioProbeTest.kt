package com.neiro.aftertone.storage

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

class RecoveryJournalPrivateFilesDirectoryNioProbeTest {
    @Test
    fun directOpenConfirmsIdentityClosesOnceAndNeverRequestsIterator() {
        val filesDirectory = Files.createTempDirectory("aftertone-nio-private-dir-")
        var tracked: TrackingSecureDirectoryStream? = null

        try {
            val outcome = PrivateFilesDirectoryNioProbe.run(filesDirectory.toFile()) { path ->
                assertEquals(filesDirectory, path)
                newTrackingSecureDirectoryStream(path).also { tracked = it }
            }

            assertEquals(PrivateFilesDirectoryNioProbeOutcome.NIO_IDENTITY_CONFIRMED, outcome)
            assertEquals(0, tracked?.iteratorCalls)
            assertEquals(1, tracked?.closeCalls)
        } finally {
            tracked?.closeIfOpen()
            Files.deleteIfExists(filesDirectory)
        }
    }

    @Test
    fun defaultProbeCanOpenTheProvidedPrivateDirectory() {
        val filesDirectory = Files.createTempDirectory("aftertone-nio-default-probe-")

        try {
            val outcome = PrivateFilesDirectoryNioProbe.probe(filesDirectory.toFile())

            assertTrue(
                outcome == PrivateFilesDirectoryNioProbeOutcome.NIO_IDENTITY_CONFIRMED ||
                    outcome == PrivateFilesDirectoryNioProbeOutcome.NIO_SECURE_STREAM_UNSUPPORTED,
            )
        } finally {
            Files.deleteIfExists(filesDirectory)
        }
    }

    @Test
    fun noSecureDirectoryStreamOutcomeClosesWithoutEnumerating() {
        val stream = CountingDirectoryStream()

        val outcome = PrivateFilesDirectoryNioProbe.run(File("/private/test/files")) { stream }

        assertEquals(PrivateFilesDirectoryNioProbeOutcome.NIO_SECURE_STREAM_UNSUPPORTED, outcome)
        assertEquals(0, stream.iteratorCalls)
        assertEquals(1, stream.closeCalls)
    }

    @Test
    fun aSymlinkPathCannotBeReportedAsTheOpenedDirectoryIdentity() {
        val parent = Files.createTempDirectory("aftertone-nio-symlink-test-")
        val target = Files.createDirectory(parent.resolve("target"))
        val symlink = Files.createSymbolicLink(parent.resolve("alias"), target.fileName)
        var tracked: TrackingSecureDirectoryStream? = null

        try {
            val outcome = PrivateFilesDirectoryNioProbe.run(symlink.toFile()) { path ->
                assertEquals(symlink, path)
                newTrackingSecureDirectoryStream(path).also { tracked = it }
            }

            assertEquals(PrivateFilesDirectoryNioProbeOutcome.NIO_IDENTITY_UNCONFIRMED, outcome)
            assertEquals(0, tracked?.iteratorCalls)
            assertEquals(1, tracked?.closeCalls)
        } finally {
            tracked?.closeIfOpen()
            Files.deleteIfExists(symlink)
            Files.deleteIfExists(target)
            Files.deleteIfExists(parent)
        }
    }

    @Test
    fun openFailuresMapToFixedOutcomesWithoutRetainingPathOrExceptionText() {
        val denied = PrivateFilesDirectoryNioProbe.run(File("/private/user/files")) {
            throw AccessDeniedException("/private/user/files", null, "sensitive provider detail")
        }
        val securityDenied = PrivateFilesDirectoryNioProbe.run(File("/private/user/files")) {
            throw SecurityException("private path and policy details")
        }
        val otherFailure = PrivateFilesDirectoryNioProbe.run(File("/private/user/files")) {
            throw IllegalStateException("private implementation detail")
        }

        assertEquals(PrivateFilesDirectoryNioProbeOutcome.NIO_OPEN_ACCESS_DENIED, denied)
        assertEquals(PrivateFilesDirectoryNioProbeOutcome.NIO_OPEN_SECURITY_DENIED, securityDenied)
        assertEquals(PrivateFilesDirectoryNioProbeOutcome.NIO_OPEN_OTHER_FAILURE, otherFailure)
        listOf(denied, securityDenied, otherFailure).forEach { outcome ->
            assertFalse(outcome.name.contains("/private"))
            assertFalse(outcome.name.contains("sensitive"))
            assertFalse(outcome.name.contains("policy"))
            assertFalse(outcome.name.contains("errno"))
        }
    }

    @Test
    fun closeFailureMapsToOneFixedOutcome() {
        val stream = CountingDirectoryStream(closeFailure = IllegalStateException("private close detail"))

        val outcome = PrivateFilesDirectoryNioProbe.run(File("/private/user/files")) { stream }

        assertEquals(PrivateFilesDirectoryNioProbeOutcome.NIO_STREAM_CLOSE_FAILED, outcome)
        assertEquals(1, stream.closeCalls)
        assertEquals(0, stream.iteratorCalls)
    }

    @Test
    fun nioProbeRunsOnlyForTheExistingRootOpenFailure() {
        val filesDirectory = File("/private/user/files")
        val probeCalls = mutableListOf<File>()
        val probe: (File) -> PrivateFilesDirectoryNioProbeOutcome = { directory ->
            probeCalls.add(directory)
            PrivateFilesDirectoryNioProbeOutcome.NIO_IDENTITY_CONFIRMED
        }

        val rootOpenFailure = PrivateFilesDirectoryNioProbeTrigger.probeIfRootOpenFailed(
            RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_ROOT_OPEN_FAILURE,
            filesDirectory,
            probe,
        )

        assertEquals(PrivateFilesDirectoryNioProbeOutcome.NIO_IDENTITY_CONFIRMED, rootOpenFailure)
        assertEquals(listOf(filesDirectory), probeCalls)

        probeCalls.clear()
        val componentOpenFailure = PrivateFilesDirectoryNioProbeTrigger.probeIfRootOpenFailed(
            RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_COMPONENT_OPEN_FAILURE,
            filesDirectory,
            probe,
        )

        assertNull(componentOpenFailure)
        assertEquals(emptyList<File>(), probeCalls)
    }

    @Test
    fun tracePersistsOnlyTheFixedNioStageAndOutcomeCode() {
        val trace = RestoreRecoveryAttemptTrace.empty().appendJournalDiagnosis(
            RecoveryJournalReadDiagnosis.SECURE_DIRECTORY_ROOT_OPEN_FAILURE,
            RecoveryJournalOsErrorCategory.NIO_ACCESS_DENIED,
            RootDirectoryNativeProbeOutcome.NATIVE_OPEN_ACCESS_DENIED,
            PrivateFilesDirectoryNativeProbeOutcome.NATIVE_OPEN_SUCCEEDED,
            PrivateFilesDirectoryNioProbeOutcome.NIO_IDENTITY_CONFIRMED,
        )

        assertEquals("DIA-JC28-OE44-RP51-PN60-ND70", trace.displayCode)
        assertFalse(trace.displayCode.orEmpty().contains("/"))
        assertFalse(trace.displayCode.orEmpty().contains("errno"))
    }

    private fun newTrackingSecureDirectoryStream(path: Path): TrackingSecureDirectoryStream {
        val stream = Files.newDirectoryStream(path)
        val secureStream = stream as? SecureDirectoryStream<*>
        if (secureStream == null) {
            stream.close()
            throw AssertionError("The host provider should return SecureDirectoryStream for this test directory.")
        }
        @Suppress("UNCHECKED_CAST")
        return TrackingSecureDirectoryStream(secureStream as SecureDirectoryStream<Path>)
    }

    private class CountingDirectoryStream(
        private val closeFailure: Exception? = null,
    ) : DirectoryStream<Path> {
        var iteratorCalls = 0
        var closeCalls = 0

        override fun iterator(): MutableIterator<Path> {
            iteratorCalls += 1
            throw AssertionError("The NIO probe must not request a directory iterator.")
        }

        override fun close() {
            closeCalls += 1
            closeFailure?.let { throw it }
        }
    }

    private class TrackingSecureDirectoryStream(
        private val delegate: SecureDirectoryStream<Path>,
    ) : SecureDirectoryStream<Path> {
        var iteratorCalls = 0
        var closeCalls = 0

        override fun iterator(): MutableIterator<Path> {
            iteratorCalls += 1
            throw AssertionError("The NIO probe must not request a directory iterator.")
        }

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

        override fun move(
            srcpath: Path,
            targetdir: SecureDirectoryStream<Path>,
            targetpath: Path,
        ) = delegate.move(srcpath, targetdir, targetpath)

        override fun <V : FileAttributeView> getFileAttributeView(type: Class<V>): V? =
            delegate.getFileAttributeView(type)

        override fun <V : FileAttributeView> getFileAttributeView(
            path: Path,
            type: Class<V>,
            vararg options: LinkOption,
        ): V? = delegate.getFileAttributeView(path, type, *options)
    }
}
