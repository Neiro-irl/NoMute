package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RecoveryJournalReadDiagnosisTest {
    @Test
    fun missingJournalHasDedicatedFixedClassification() = withFixture { fixture ->
        assertEquals("JOURNAL_NOT_PRESENT", diagnose(fixture.transaction()))
        assertEquals("JC80", diagnosisCode(fixture.transaction()))
    }

    @Test
    fun journalSymlinkIsClassifiedAsNonRegularWithoutFollowingItsTarget() = withFixture { fixture ->
        val external = File(fixture.root, "external-journal").apply { writeBytes(validJournal()) }
        Files.createSymbolicLink(fixture.journal.toPath(), external.toPath())

        assertEquals("JOURNAL_NON_REGULAR_OR_SYMLINK", diagnose(fixture.transaction()))
        assertEquals("JC81", diagnosisCode(fixture.transaction()))
        assertEquals("The external target must remain untouched.", validJournal().size, external.length().toInt())
    }

    @Test
    fun nonDirectoryFilesPathUsesTheFixedPathValidationCode() = withFixture { fixture ->
        val nonDirectory = File(fixture.root, "not-a-directory").apply { writeText("not a directory") }

        assertEquals("DIRECTORY_PATH_VALIDATION_FAILURE", diagnose(fixture.transaction(nonDirectory)))
    }

    @Test
    fun readOnlyDirectDirectoryOpenFailureUsesItsSpecificFixedStage() = withFixture { fixture ->
        val nonDirectory = File(fixture.root, "not-a-directory").apply { writeText("private test fixture") }
        val directoryHintOnly = object : File(nonDirectory.path) {
            override fun isDirectory(): Boolean = true
        }
        val transaction = fixture.transaction(directoryHintOnly)

        assertEquals("DIRECTORY_OPEN_FAILURE", diagnose(transaction))
        assertEquals("IO_FAILURE", diagnoseOsErrorCategory(transaction))
        assertEquals("private test fixture", nonDirectory.readText())
    }

    @Test
    fun readOnlyPathNormalizationFailureUsesItsOwnFixedStage() = withFixture { fixture ->
        val failingPath = object : File(fixture.filesDirectory.path) {
            override fun isDirectory(): Boolean = true
            override fun toPath(): Path = throw InvalidPathException("private filesystem path", "raw path failure")
        }
        val transaction = fixture.transaction(failingPath)

        assertEquals("SECURE_DIRECTORY_PATH_RESOLUTION_FAILURE", diagnose(transaction))
        assertEquals("UNKNOWN", diagnoseOsErrorCategory(transaction))
    }

    @Test
    fun oversizedJournalIsRejectedBeforeReadingItsBytes() = withFixture { fixture ->
        RandomAccessFile(fixture.journal, "rw").use { it.setLength(MAX_JOURNAL_BYTES + 1) }

        assertEquals("SIZE_CHANGE_OR_TRUNCATION", diagnose(fixture.transaction()))
    }

    @Test
    fun truncatedJournalUsesTheSizeChangeOrTruncationCode() = withFixture { fixture ->
        fixture.journal.writeBytes(byteArrayOf(0x41, 0x54))

        assertEquals("SIZE_CHANGE_OR_TRUNCATION", diagnose(fixture.transaction()))
    }

    @Test
    fun unsupportedJournalMagicAndVersionUseTheFixedHeaderCode() = withFixture { fixture ->
        fixture.journal.writeBytes(validJournal(magic = 0, version = JOURNAL_VERSION))
        assertEquals("UNSUPPORTED_HEADER_VERSION", diagnose(fixture.transaction()))

        fixture.journal.writeBytes(validJournal(magic = JOURNAL_MAGIC, version = JOURNAL_VERSION + 1))
        assertEquals("UNSUPPORTED_HEADER_VERSION", diagnose(fixture.transaction()))
    }

    @Test
    fun invalidSerializedStateLengthUsesTheFixedSerializationCode() = withFixture { fixture ->
        fixture.journal.writeBytes(validJournal(encodedStateLength = 0))

        assertEquals("BAD_SERIALIZATION", diagnose(fixture.transaction()))
    }

    @Test
    fun unsafeJournalPathMetadataUsesTheFixedPathCode() = withFixture { fixture ->
        fixture.journal.writeBytes(validJournal(stagingName = "../outside"))

        assertEquals("UNSAFE_PATH", diagnose(fixture.transaction()))
    }

    @Test
    fun invalidIntegrityMetadataUsesTheFixedIntegrityCode() = withFixture { fixture ->
        fixture.journal.writeBytes(validJournal(includeInvalidIntegrity = true))

        assertEquals("BAD_INTEGRITY_METADATA", diagnose(fixture.transaction()))
    }

    @Test
    fun trailingJournalBytesUseTheFixedTrailingDataCode() = withFixture { fixture ->
        fixture.journal.writeBytes(validJournal(trailing = byteArrayOf(0x55)))

        assertEquals("TRAILING_BYTES", diagnose(fixture.transaction()))
    }

    @Test
    fun wellFormedJournalIsClassifiedWithoutInterpretingLibraryOrMediaData() = withFixture { fixture ->
        fixture.journal.writeBytes(validJournal())

        assertEquals("READABLE", diagnose(fixture.transaction()))
    }

    @Test
    fun pathValidationExceptionUsesSafeUnknownCategory() = withFixture { fixture ->
        val unusualDirectory = object : File(fixture.filesDirectory.path) {
            override fun isDirectory(): Boolean = throw IllegalStateException("private path and raw failure")
        }

        val transaction = fixture.transaction(unusualDirectory)
        assertEquals("DIRECTORY_PATH_VALIDATION_FAILURE", diagnose(transaction))
        assertEquals("UNKNOWN", diagnoseOsErrorCategory(transaction))
    }

    private fun diagnose(transaction: LibraryRestoreTransaction): String {
        val result = diagnosisResult(transaction)
        if (result is Enum<*>) return result.name
        val diagnosis = result.javaClass.methods.firstOrNull { it.name == "getDiagnosis" }
        assertNotNull("The read-only result must expose a fixed diagnosis.", diagnosis)
        return (diagnosis!!.invoke(result) as Enum<*>).name
    }

    private fun diagnoseOsErrorCategory(transaction: LibraryRestoreTransaction): String? {
        val result = diagnosisResult(transaction)
        val category = result.javaClass.methods.firstOrNull { it.name == "getOsErrorCategory" }
        assertNotNull("The read-only result must expose only an optional allowlisted category.", category)
        return category!!.invoke(result)?.let { (it as Enum<*>).name }
    }

    private fun diagnosisResult(transaction: LibraryRestoreTransaction): Any {
        val method = transaction.javaClass.declaredMethods.firstOrNull {
            it.name.startsWith("diagnoseRecoveryJournalRead") && it.parameterCount == 0
        }
        assertNotNull("The journal reader must expose an internal read-only classifier.", method)
        method!!.isAccessible = true
        return method.invoke(transaction) ?: error("The read-only classifier must return a fixed result.")
    }

    private fun diagnosisCode(transaction: LibraryRestoreTransaction): String {
        val result = diagnosisResult(transaction)
        val diagnosis = if (result is RecoveryJournalReadDiagnosis) {
            result
        } else {
            val accessor = result.javaClass.methods.firstOrNull { it.name == "getDiagnosis" }
            assertNotNull("The fixed diagnosis must be encoded without exposing raw details.", accessor)
            accessor!!.invoke(result) as RecoveryJournalReadDiagnosis
        }
        return RestoreRecoveryAttemptTrace.empty().appendJournalDiagnosis(diagnosis, null).encode()
    }

    private fun validJournal(
        magic: Int = JOURNAL_MAGIC,
        version: Int = JOURNAL_VERSION,
        stagingName: String? = null,
        encodedStateLength: Int = 1,
        includeInvalidIntegrity: Boolean = false,
        trailing: ByteArray = byteArrayOf(),
    ): ByteArray {
        val transactionId = "01234567-89ab-cdef-0123-456789abcdef"
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(magic)
            output.writeInt(version)
            output.writeUTF(transactionId)
            output.writeUTF(stagingName ?: ".aftertone-restore-stage-$transactionId")
            output.writeUTF("restore-$transactionId")
            output.writeBoolean(false)
            output.writeInt(encodedStateLength)
            if (encodedStateLength > 0) output.writeByte('x'.code)
            output.writeInt(if (includeInvalidIntegrity) 1 else 0)
            if (includeInvalidIntegrity) {
                output.writeUTF("../unsafe.mp3")
                output.writeLong(1)
                output.writeUTF("not-a-sha256")
            }
            output.write(trailing)
        }
        return bytes.toByteArray()
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("aftertone-journal-diagnosis-").toFile()
        val filesDirectory = File(root, "files").apply { mkdir() }
        val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
        val fixture = Fixture(root, filesDirectory, audioDirectory)
        try {
            block(fixture)
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture(
        val root: File,
        val filesDirectory: File,
        val audioDirectory: File,
    ) {
        val journal = File(filesDirectory, JOURNAL_NAME)

        fun transaction(directory: File = filesDirectory) = LibraryRestoreTransaction(
            filesDirectory = directory,
            audioDirectory = audioDirectory,
            stateStore = object : LibraryStateStore {
                override fun read(): String? = null
                override fun write(encoded: String?): Boolean = true
            },
            decodeSnapshot = { LibrarySnapshot() },
        )
    }

    private companion object {
        const val JOURNAL_NAME = ".aftertone-restore-journal"
        const val JOURNAL_MAGIC = 0x4154524a
        const val JOURNAL_VERSION = 2
        const val MAX_JOURNAL_BYTES = 64L * 1024 * 1024
    }
}
