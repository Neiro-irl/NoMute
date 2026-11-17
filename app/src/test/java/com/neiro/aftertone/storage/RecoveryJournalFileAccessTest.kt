package com.neiro.aftertone.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryJournalFileAccessTest {
    @Test(timeout = 1_500)
    fun specialFileAppearingAfterPreflightIsRejectedWithoutReadingOrBlocking() = withJournal { file ->
        val regular = stat(type = RecoveryJournalFileType.REGULAR, size = file.length())
        val fifo = stat(type = RecoveryJournalFileType.SPECIAL, size = 0)
        val operations = FakeOperations(
            pathStats = mutableListOf(regular),
            descriptorStats = mutableListOf(fifo),
            content = file.readBytes(),
        )

        val failure = readFailure(file, operations)

        assertEquals(RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE, failure.diagnosis)
        assertEquals("No journal bytes may be read after the opened descriptor is found to be special.", 0, operations.readCalls)
        assertEquals(
            RecoveryJournalOpenOptions(readOnly = true, noFollow = true, nonBlocking = true, closeOnExec = true),
            operations.openOptions,
        )
    }

    @Test
    fun readOnlyDiagnosticClassifiesPreflightSpecialEntryWithoutReading() = withJournal { file ->
        val operations = FakeOperations(
            pathStats = mutableListOf(stat(type = RecoveryJournalFileType.SPECIAL, size = 0)),
            descriptorStats = mutableListOf(),
            content = file.readBytes(),
        )

        val failure = readDiagnosticFailure(file, operations)

        assertEquals("JOURNAL_NON_REGULAR_OR_SYMLINK", failure?.diagnosis?.name)
        assertEquals("JC81", failure?.diagnosis?.let(::fixedDiagnosisCode))
        assertEquals("Non-regular metadata must reject before content reads.", 0, operations.readCalls)
    }

    @Test
    fun readOnlyDiagnosticClassifiesOpenedSpecialDescriptorBeforeReading() = withJournal { file ->
        val initial = stat(size = file.length())
        val special = stat(type = RecoveryJournalFileType.SPECIAL, size = 0)
        val operations = FakeOperations(
            pathStats = mutableListOf(initial),
            descriptorStats = mutableListOf(special),
            content = file.readBytes(),
        )

        val failure = readDiagnosticFailure(file, operations)

        assertEquals("JOURNAL_NON_REGULAR_OR_SYMLINK", failure?.diagnosis?.name)
        assertEquals("JC81", failure?.diagnosis?.let(::fixedDiagnosisCode))
        assertEquals("The opened descriptor must be rejected before content reads.", 0, operations.readCalls)
    }

    @Test
    fun readOnlyDiagnosticClassifiesNoFollowOpenRejectionWithoutReading() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial),
            descriptorStats = mutableListOf(),
            content = file.readBytes(),
            openFailureException = RecoveryJournalReadFailure(RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE),
        )

        val failure = readDiagnosticFailure(file, operations)

        assertEquals("JOURNAL_NON_REGULAR_OR_SYMLINK", failure?.diagnosis?.name)
        assertEquals("JC81", failure?.diagnosis?.let(::fixedDiagnosisCode))
        assertEquals("A no-follow rejection must stop before content reads.", 0, operations.readCalls)
    }

    @Test
    fun readOnlyDiagnosticClassifiesDisappearanceAtOpenWithoutReading() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial),
            descriptorStats = mutableListOf(),
            content = file.readBytes(),
            openReturnsNull = true,
        )

        val failure = readDiagnosticFailure(file, operations)

        assertEquals("JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED", failure?.diagnosis?.name)
        assertEquals("JC82", failure?.diagnosis?.let(::fixedDiagnosisCode))
        assertEquals("Disappearance before open must stop before content reads.", 0, operations.readCalls)
    }

    @Test
    fun readOnlyDiagnosticClassifiesPreviouslyObservedEntryMissingAtInitialStat() = withJournal { file ->
        val operations = FakeOperations(
            pathStats = mutableListOf(null),
            descriptorStats = mutableListOf(),
            content = file.readBytes(),
        )

        val failure = readDiagnosticFailure(file, operations, entryPreviouslyObserved = true)

        assertEquals("JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED", failure?.diagnosis?.name)
        assertEquals("JC82", failure?.diagnosis?.let(::fixedDiagnosisCode))
        assertEquals("The prior presence observation must not trigger a content read.", 0, operations.readCalls)
    }

    @Test
    fun readOnlyDiagnosticClassifiesFinalDisappearanceAfterOnlyTheBoundedRead() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial, null),
            descriptorStats = mutableListOf(initial, initial),
            content = file.readBytes(),
        )

        val failure = readDiagnosticFailure(file, operations)

        assertEquals("JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED", failure?.diagnosis?.name)
        assertEquals("JC82", failure?.diagnosis?.let(::fixedDiagnosisCode))
        assertEquals("Classification must not add content reads.", 1, operations.readCalls)
    }

    @Test
    fun readOnlyDiagnosticClassifiesMissingDescriptorStatBeforeReading() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial),
            descriptorStats = mutableListOf(),
            content = file.readBytes(),
            failDescriptorStatAt = 1,
            descriptorStatFailure = RecoveryJournalReadFailure(RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE),
        )

        val failure = readDiagnosticFailure(file, operations)

        assertEquals("JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED", failure?.diagnosis?.name)
        assertEquals("JC82", failure?.diagnosis?.let(::fixedDiagnosisCode))
        assertEquals("A missing descriptor stat must stop before content reads.", 0, operations.readCalls)
    }

    @Test
    fun readOnlyDiagnosticClassifiesDescriptorDisappearanceAfterRead() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial, initial),
            descriptorStats = mutableListOf(initial),
            content = file.readBytes(),
            failDescriptorStatAt = 2,
            descriptorStatFailure = RecoveryJournalReadFailure(RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE),
        )

        val failure = readDiagnosticFailure(file, operations)

        assertEquals("JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED", failure?.diagnosis?.name)
        assertEquals("JC82", failure?.diagnosis?.let(::fixedDiagnosisCode))
        assertEquals("Final descriptor metadata adds no content read.", 1, operations.readCalls)
    }

    @Test
    fun readOnlyDiagnosticClassifiesFinalSpecialReplacementAfterRead() = withJournal { file ->
        val initial = stat(size = file.length())
        val special = stat(type = RecoveryJournalFileType.SPECIAL, size = 0)
        val operations = FakeOperations(
            pathStats = mutableListOf(initial, special),
            descriptorStats = mutableListOf(initial, initial),
            content = file.readBytes(),
        )

        val failure = readDiagnosticFailure(file, operations)

        assertEquals("JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED", failure?.diagnosis?.name)
        assertEquals("JC82", failure?.diagnosis?.let(::fixedDiagnosisCode))
        assertEquals("The final path replacement adds no content read.", 1, operations.readCalls)
    }

    @Test
    fun sameInodeSameSizeRewriteDuringReadIsClassifiedAsChanged() = withJournal { file ->
        val before = stat(size = 4, modifiedNanos = 10, changedNanos = 20)
        val after = before.copy(modifiedNanos = 11, changedNanos = 21)
        val pathStats = mutableListOf<RecoveryJournalFileStat?>(before, before)
        val descriptorStats = mutableListOf(before, before)
        val operations = FakeOperations(
            pathStats = pathStats,
            descriptorStats = descriptorStats,
            content = byteArrayOf(1, 2, 3, 4),
            afterRead = {
                RandomAccessFile(file, "rw").use { handle ->
                    handle.seek(0)
                    handle.write(byteArrayOf(4, 3, 2, 1))
                }
                pathStats[1] = after
                descriptorStats[1] = after
            },
        )

        val failure = readFailure(file, operations)

        assertEquals(RecoveryJournalReadDiagnosis.SIZE_CHANGE_OR_TRUNCATION, failure.diagnosis)
        assertEquals("The rewrite fixture must preserve size.", 4L, file.length())
        assertEquals(listOf<Byte>(4, 3, 2, 1), file.readBytes().toList())
    }

    @Test
    fun readFailureWithoutShortReadEvidenceIsSafeUnknown() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial),
            descriptorStats = mutableListOf(initial),
            content = file.readBytes(),
            readFailure = true,
        )

        val failure = readFailure(file, operations)

        assertEquals(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN, failure.diagnosis)
        assertNull("Raw I/O failure text must not be exposed.", failure.message)
    }

    @Test
    fun directoryPathValidationFailureIsDistinctAndDoesNotReadJournalBytes() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial),
            descriptorStats = mutableListOf(initial),
            content = file.readBytes(),
            directoryPathMatches = false,
        )

        val failure = readFailure(file, operations)

        assertEquals("DIRECTORY_PATH_VALIDATION_FAILURE", failure.diagnosis.name)
        assertNull("A logical identity mismatch has no OS error category.", osErrorCategoryName(failure))
        assertEquals("Path validation must stop before reading journal bytes.", 0, operations.readCalls)
    }

    @Test
    fun pathSwapBetweenNativeAndNioIdentityChecksIsRejectedBeforeJournalRead() = withJournal { file ->
        var currentPathIdentity = "native-directory-descriptor"
        val operations = FakeOperations(
            pathStats = mutableListOf(stat(size = file.length())),
            descriptorStats = mutableListOf(stat(size = file.length()), stat(size = file.length())),
            content = file.readBytes(),
            directoryPathCheck = {
                currentPathIdentity = "native-directory-descriptor"
                currentPathIdentity == "native-directory-descriptor"
            },
            nativeDirectoryMatchesAnchor = false,
        )
        val anchor = Files.newDirectoryStream(requireNotNull(file.parentFile).toPath()) as SecureDirectoryStream<java.nio.file.Path>

        val failure = try {
            runCatching {
                RecoveryJournalFileAccess(operations).readWithVerifiedDirectoryAnchor(
                    file,
                    anchor,
                    verifiedDirectoryStillMatches = {
                        currentPathIdentity = "different-nio-directory-anchor"
                        currentPathIdentity == "different-nio-directory-anchor"
                    },
                )
            }.exceptionOrNull()
        } finally {
            anchor.close()
        }

        assertTrue("Sequential path checks must not accept different open directory handles.",
            failure is RecoveryJournalReadFailure)
        assertEquals(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE,
            (failure as RecoveryJournalReadFailure).diagnosis)
        assertNull("The journal file must not be opened after an anchor mismatch.", operations.openOptions)
        assertEquals("No journal bytes may be read after an anchor mismatch.", 0, operations.readCalls)
    }

    @Test
    fun verifiedDirectoryAnchorAcceptsSameDirectoryAndRejectsDifferentDirectory() = withJournal { file ->
        val sameDirectory = Files.newDirectoryStream(requireNotNull(file.parentFile).toPath()) as SecureDirectoryStream<java.nio.file.Path>
        val otherDirectory = Files.createTempDirectory("aftertone-other-journal-anchor-")
        val differentDirectory = Files.newDirectoryStream(otherDirectory) as SecureDirectoryStream<java.nio.file.Path>

        try {
            val expectedBytes = file.readBytes()
            val matchingResult = RecoveryJournalFileAccess().readWithVerifiedDirectoryAnchor(file, sameDirectory)
            assertArrayEquals("A descriptor bound to the journal's directory should read the bounded journal.",
                expectedBytes, matchingResult)

            val failure = runCatching {
                RecoveryJournalFileAccess().readWithVerifiedDirectoryAnchor(file, differentDirectory)
            }.exceptionOrNull()
            assertTrue("A different open directory descriptor must fail closed.", failure is RecoveryJournalReadFailure)
            assertEquals(RecoveryJournalReadDiagnosis.DIRECTORY_PATH_VALIDATION_FAILURE,
                (failure as RecoveryJournalReadFailure).diagnosis)
        } finally {
            sameDirectory.close()
            differentDirectory.close()
            otherDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun directoryOpenFailureIsDistinctAndMapsOnlyTheIoCategory() = withJournal { file ->
        val operations = FakeOperations(
            pathStats = mutableListOf(),
            descriptorStats = mutableListOf(),
            content = file.readBytes(),
            directoryOpenFailure = IOException("private directory path and failure"),
        )

        val failure = readFailure(file, operations)

        assertEquals("DIRECTORY_OPEN_FAILURE", failure.diagnosis.name)
        assertEquals("IO_FAILURE", osErrorCategoryName(failure))
        assertNull("Raw open failure text must not be exposed.", failure.message)
        assertEquals("A directory-open failure must happen before any journal read.", 0, operations.readCalls)
    }

    @Test
    fun accessDeniedDirectoryOpenUsesOnlyTheAllowlistedCategory() = withJournal { file ->
        val operations = FakeOperations(
            pathStats = mutableListOf(),
            descriptorStats = mutableListOf(),
            content = file.readBytes(),
            directoryOpenFailure = SecurityException("private path and access detail"),
        )

        val failure = readFailure(file, operations)

        assertEquals("DIRECTORY_OPEN_FAILURE", failure.diagnosis.name)
        assertEquals("ACCESS_DENIED", osErrorCategoryName(failure))
        assertNull("Raw access failure text must not be exposed.", failure.message)
    }

    @Test
    fun openFailureUsesSpecificJournalFileCategoryInsteadOfTruncation() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial),
            descriptorStats = mutableListOf(initial),
            content = file.readBytes(),
            openFailure = true,
        )

        val failure = readFailure(file, operations)

        assertEquals("JOURNAL_FILE_OPEN_FAILURE", failure.diagnosis.name)
        assertEquals("IO_FAILURE", osErrorCategoryName(failure))
        assertNull("Raw open failure text must not be exposed.", failure.message)
        assertEquals("A journal-file-open failure must happen before any journal read.", 0, operations.readCalls)
    }

    @Test
    fun osErrorCategoriesEncodeOnlyFourFixedAllowlistedCodes() {
        val cases = listOf(
            RecoveryJournalOsErrorCategory.ACCESS_DENIED to "40",
            RecoveryJournalOsErrorCategory.RESOURCE_EXHAUSTION to "41",
            RecoveryJournalOsErrorCategory.IO_FAILURE to "42",
            RecoveryJournalOsErrorCategory.UNKNOWN to "43",
        )

        cases.forEach { (category, expectedCode) ->
            val trace = RestoreRecoveryAttemptTrace.empty().appendJournalDiagnosis(
                RecoveryJournalReadDiagnosis.JOURNAL_FILE_OPEN_FAILURE,
                category,
            )

            assertEquals("JC22.OE$expectedCode", trace.encode())
        }
    }

    @Test
    fun legacyMissingOrSpecialDiagnosisKeepsItsFixedOutcome() {
        val normalRecoveryDiagnosis = RestoreRecoveryAttemptTrace.empty().appendJournalDiagnosis(
            RecoveryJournalReadDiagnosis.MISSING_OR_SPECIAL_FILE,
            null,
        )
        assertEquals("JC11", normalRecoveryDiagnosis.encode())
    }

    @Test
    fun finalDescriptorStatFailureWithoutSizeEvidenceIsSafeUnknown() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial),
            descriptorStats = mutableListOf(initial, initial),
            content = file.readBytes(),
            failDescriptorStatAt = 2,
        )

        val failure = readFailure(file, operations)

        assertEquals(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN, failure.diagnosis)
    }

    @Test
    fun finalPathStatFailureIsClassifiedAsPathValidationFailure() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial, initial),
            descriptorStats = mutableListOf(initial, initial),
            content = file.readBytes(),
            failPathStatAt = 2,
        )

        val failure = readFailure(file, operations)

        assertEquals("DIRECTORY_PATH_VALIDATION_FAILURE", failure.diagnosis.name)
        assertEquals("IO_FAILURE", osErrorCategoryName(failure))
    }

    @Test
    fun coarseTimestampJournalRemainsReadableForNormalRecovery() = withJournal { file ->
        val coarse = stat(
            size = file.length(),
            modifiedNanos = 0,
            changedNanos = 0,
            supportsSubsecondChangeTime = false,
        )
        val expectedBytes = file.readBytes()
        val operations = FakeOperations(
            pathStats = mutableListOf(coarse),
            descriptorStats = mutableListOf(coarse, coarse),
            content = expectedBytes,
        )

        val result = runCatching {
            RecoveryJournalFileAccess(operations).read(file, requirePreciseChangeTime = false)
        }

        assertNull("Normal restore must not reject a stable API-26 journal only because timestamps are coarse.", result.exceptionOrNull())
        assertArrayEquals(expectedBytes, result.getOrNull()!!)
    }

    @Test
    fun coarseChangeTimestampDoesNotClaimReadableJournal() = withJournal { file ->
        val coarse = stat(
            size = file.length(),
            modifiedNanos = 0,
            changedNanos = 0,
            supportsSubsecondChangeTime = false,
        )
        val operations = FakeOperations(
            pathStats = mutableListOf(coarse),
            descriptorStats = mutableListOf(coarse, coarse),
            content = file.readBytes(),
        )

        val failure = runCatching {
            RecoveryJournalFileAccess(operations).read(file, requirePreciseChangeTime = true)
        }.exceptionOrNull()

        assertTrue("Coarse timestamps cannot prove same-size journal stability.", failure is RecoveryJournalReadFailure)
        assertEquals(RecoveryJournalReadDiagnosis.SAFE_UNKNOWN, (failure as RecoveryJournalReadFailure).diagnosis)
        assertEquals("No journal bytes should be read without sufficiently precise change metadata.", 0, operations.readCalls)
    }

    @Test
    fun confirmedShortReadUsesSizeChangeOrTruncation() = withJournal { file ->
        val initial = stat(size = file.length())
        val operations = FakeOperations(
            pathStats = mutableListOf(initial),
            descriptorStats = mutableListOf(initial),
            content = file.readBytes(),
            maximumTotalReadBytes = 2,
        )

        val failure = readFailure(file, operations)

        assertEquals(RecoveryJournalReadDiagnosis.SIZE_CHANGE_OR_TRUNCATION, failure.diagnosis)
    }

    private fun readFailure(file: File, operations: RecoveryJournalFileOperations): RecoveryJournalReadFailure {
        val failure = runCatching { RecoveryJournalFileAccess(operations).read(file) }.exceptionOrNull()
        assertNotNull("The invalid or changed file must be rejected with a fixed diagnosis.", failure)
        assertTrue("Only the fixed, message-free diagnostic failure is allowed.", failure is RecoveryJournalReadFailure)
        return failure as RecoveryJournalReadFailure
    }

    private fun readDiagnosticFailure(
        file: File,
        operations: RecoveryJournalFileOperations,
        entryPreviouslyObserved: Boolean = false,
    ): RecoveryJournalReadFailure? {
        val anchor = Files.newDirectoryStream(requireNotNull(file.parentFile).toPath()) as SecureDirectoryStream<java.nio.file.Path>
        val failure = try {
            runCatching {
                RecoveryJournalFileAccess(operations).readWithVerifiedDirectoryAnchor(
                    file,
                    anchor,
                    entryPreviouslyObserved = entryPreviouslyObserved,
                )
            }.exceptionOrNull()
        } finally {
            anchor.close()
        }
        if (failure == null) return null
        assertTrue("Only a fixed, message-free diagnostic failure is allowed.", failure is RecoveryJournalReadFailure)
        return failure as RecoveryJournalReadFailure
    }

    private fun fixedDiagnosisCode(diagnosis: RecoveryJournalReadDiagnosis): String =
        RestoreRecoveryAttemptTrace.empty().appendJournalDiagnosis(diagnosis, null).encode()

    private fun osErrorCategoryName(failure: RecoveryJournalReadFailure): String? =
        failure.javaClass.methods.firstOrNull { it.name == "getOsErrorCategory" }
            ?.invoke(failure)
            ?.let { (it as Enum<*>).name }

    private fun stat(
        type: RecoveryJournalFileType = RecoveryJournalFileType.REGULAR,
        size: Long,
        modifiedNanos: Long = 10,
        changedNanos: Long = 20,
        supportsSubsecondChangeTime: Boolean = true,
    ) = RecoveryJournalFileStat(
        device = 7,
        inode = 42,
        type = type,
        size = size,
        modifiedSeconds = 1,
        modifiedNanos = modifiedNanos,
        changedSeconds = 2,
        changedNanos = changedNanos,
        supportsSubsecondChangeTime = supportsSubsecondChangeTime,
    )

    private fun withJournal(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("aftertone-journal-access-").toFile()
        val file = File(directory, "journal").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            block(file)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FakeOperations(
        private val pathStats: MutableList<RecoveryJournalFileStat?>,
        private val descriptorStats: MutableList<RecoveryJournalFileStat>,
        private val content: ByteArray,
        private val maximumReadSize: Int = Int.MAX_VALUE,
        private val maximumTotalReadBytes: Int = Int.MAX_VALUE,
        private val readFailure: Boolean = false,
        private val openFailure: Boolean = false,
        private val openReturnsNull: Boolean = false,
        private val directoryPathMatches: Boolean = true,
        private val directoryPathCheck: (() -> Boolean)? = null,
        private val nativeDirectoryMatchesAnchor: Boolean = true,
        private val directoryOpenFailure: Exception? = null,
        private val openFailureException: Exception? = null,
        private val failDescriptorStatAt: Int? = null,
        private val descriptorStatFailure: RecoveryJournalReadFailure? = null,
        private val failPathStatAt: Int? = null,
        private val afterRead: () -> Unit = {},
    ) : RecoveryJournalFileOperations {
        var openOptions: RecoveryJournalOpenOptions? = null
        var readCalls: Int = 0
            private set

        override fun openDirectory(directory: File): RecoveryJournalDirectoryHandle {
            directoryOpenFailure?.let { throw it }
            return object : RecoveryJournalDirectoryHandle {
                private var pathStatCalls = 0

                override fun isPathStillSame(): Boolean = directoryPathCheck?.invoke() ?: directoryPathMatches

                override fun isSameDirectoryAs(verifiedDirectory: SecureDirectoryStream<java.nio.file.Path>): Boolean =
                    nativeDirectoryMatchesAnchor

                override fun lstat(name: String): RecoveryJournalFileStat? {
                    pathStatCalls += 1
                    if (pathStatCalls == failPathStatAt) throw IOException("private path and raw failure")
                    return pathStats.getOrNull(pathStatCalls - 1) ?: pathStats.lastOrNull()
                }

                override fun openReadOnly(
                    name: String,
                    options: RecoveryJournalOpenOptions,
                ): RecoveryJournalReadHandle? {
                    openOptions = options
                    if (openFailure) throw IOException("private file name and raw open failure")
                    openFailureException?.let { throw it }
                    if (openReturnsNull) return null
                    return object : RecoveryJournalReadHandle {
                        private var descriptorStatCalls = 0
                        private var cursor = 0
                        private var readMutationApplied = false

                        override fun fstat(): RecoveryJournalFileStat {
                            descriptorStatCalls += 1
                            if (descriptorStatCalls == failDescriptorStatAt) {
                                descriptorStatFailure?.let { throw it }
                                throw IOException("private descriptor and raw failure")
                            }
                            return descriptorStats.getOrElse(descriptorStatCalls - 1) { descriptorStats.last() }
                        }

                        override fun read(target: ByteArray, offset: Int, length: Int): Int {
                            readCalls += 1
                            if (readFailure) throw IOException("private audio metadata must not escape")
                            val readableBytes = minOf(content.size, maximumTotalReadBytes)
                            if (cursor >= readableBytes) return 0
                            val count = minOf(length, maximumReadSize, readableBytes - cursor)
                            content.copyInto(target, offset, cursor, cursor + count)
                            cursor += count
                            if (!readMutationApplied) {
                                readMutationApplied = true
                                afterRead()
                            }
                            return count
                        }

                        override fun close() = Unit
                    }
                }

                override fun close() = Unit
            }
        }
    }
}
