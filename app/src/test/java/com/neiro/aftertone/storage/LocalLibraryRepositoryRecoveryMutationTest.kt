package com.neiro.aftertone.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.neiro.aftertone.model.LibrarySnapshot
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibraryRepositoryRecoveryMutationTest {
    @Test
    fun libraryMutationDoesNotOverwriteStateWhenJournalRecoveryIsPending() {
        val dataDirectory = Files.createTempDirectory("aftertone-recovery-mutation").toFile()
        val filesDirectory = File(dataDirectory, "files").apply { mkdirs() }
        val cacheDirectory = File(dataDirectory, "cache")
        val preferences = InMemorySharedPreferences()
        val repository = LocalLibraryRepository(
            RepositoryTestContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value),
        )

        try {
            repository.addPlaylist("Before recovery")
            File(filesDirectory, ".aftertone-restore-journal").writeBytes(byteArrayOf(0x00))

            var mutationFailure: IOException? = null
            try {
                repository.addPlaylist("Must not be persisted")
            } catch (error: IOException) {
                mutationFailure = error
            }

            assertNotNull("The pending restore journal must reject a library mutation.", mutationFailure)
            assertEquals(listOf("Before recovery"), repository.load().playlists.map { it.name })
            assertEquals(
                RestoreActionGate.RECOVERY_PENDING_ID,
                repository.restoreActionGate.pendingCleanupTransactionId,
            )
        } finally {
            dataDirectory.deleteRecursively()
        }
    }

    @Test
    fun successfulRecoveryCleanupIsNotRepeatedByLaterLoadsOrRepositoryInstances() {
        val dataDirectory = Files.createTempDirectory("aftertone-one-shot-success").toFile()
        val filesDirectory = File(dataDirectory, "files").apply { mkdirs() }
        File(filesDirectory, "audio").mkdirs()
        val cacheDirectory = File(dataDirectory, "cache")
        val preferences = InMemorySharedPreferences()
        val firstTemporary = File(filesDirectory, ".aftertone-restore-journal-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val context = RepositoryTestContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value)
        val firstRepository = LocalLibraryRepository(context)

        try {
            assertEquals(LibrarySnapshot(), firstRepository.load())
            assertFalse(firstTemporary.exists())
            assertFalse(firstRepository.restoreActionGate.isBlocked)

            val laterTemporary = File(filesDirectory, ".aftertone-restore-journal-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
                .apply { writeBytes(byteArrayOf(4, 5, 6)) }
            firstRepository.load()
            assertTrue("A completed authorization is not replayed by another load.", laterTemporary.isFile)

            LocalLibraryRepository(context).load()
            assertTrue("The persisted completion state survives a new repository instance.", laterTemporary.isFile)
        } finally {
            dataDirectory.deleteRecursively()
        }
    }

    @Test
    fun aFailedRecoveryCannotRunAgainAfterTheJournalDisappears() {
        val dataDirectory = Files.createTempDirectory("aftertone-one-shot-failed").toFile()
        val filesDirectory = File(dataDirectory, "files").apply { mkdirs() }
        File(filesDirectory, "audio").mkdirs()
        val cacheDirectory = File(dataDirectory, "cache")
        val preferences = InMemorySharedPreferences()
        val mainJournal = File(filesDirectory, ".aftertone-restore-journal").apply { writeBytes(byteArrayOf(0x01)) }
        val firstTemporary = File(filesDirectory, ".aftertone-restore-journal-cccccccc-cccc-4ccc-8ccc-cccccccccccc")
            .apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val context = RepositoryTestContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value)

        try {
            val firstRepository = LocalLibraryRepository(context)
            firstRepository.load()
            assertTrue(firstRepository.restoreActionGate.isBlocked)
            assertTrue(firstTemporary.isFile)

            assertTrue(mainJournal.delete())
            val laterTemporary = File(filesDirectory, ".aftertone-restore-journal-eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
                .apply { writeBytes(byteArrayOf(2, 4, 8)) }
            val nextRepository = LocalLibraryRepository(context)
            nextRepository.load()

            assertTrue("A failure latch must survive disappearance of the unreadable journal.", laterTemporary.isFile)
            assertTrue(nextRepository.restoreActionGate.isBlocked)
        } finally {
            dataDirectory.deleteRecursively()
        }
    }

    @Test
    fun cleanupDoesNotStartWhenItsAttemptLatchCannotBeCommitted() {
        val dataDirectory = Files.createTempDirectory("aftertone-one-shot-latch").toFile()
        val filesDirectory = File(dataDirectory, "files").apply { mkdirs() }
        File(filesDirectory, "audio").mkdirs()
        val cacheDirectory = File(dataDirectory, "cache")
        val preferences = InMemorySharedPreferences().apply { failedCommitsRemaining = 1 }
        val temporary = File(filesDirectory, ".aftertone-restore-journal-dddddddd-dddd-4ddd-8ddd-dddddddddddd")
            .apply { writeBytes(byteArrayOf(1, 3, 5)) }
        val repository = LocalLibraryRepository(
            RepositoryTestContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value),
        )

        try {
            repository.load()
            assertTrue("No destructive operation runs unless the attempt latch is durably committed.", temporary.isFile)
            assertTrue(repository.restoreActionGate.isBlocked)

            repository.load()
            assertTrue("The same process cannot silently retry after a failed latch write.", temporary.isFile)
            val restartedRepository = LocalLibraryRepository(
                RepositoryTestContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value),
            )
            restartedRepository.load()
            assertTrue("The failed first attempt stays consumed after restart.", temporary.isFile)
            assertTrue(restartedRepository.restoreActionGate.isBlocked)
        } finally {
            dataDirectory.deleteRecursively()
        }
    }

    @Test
    fun completedScopedRecoveryDoesNotDeletePreparedArchivesOnFreshRepositoryStartup() {
        val dataDirectory = Files.createTempDirectory("aftertone-complete-archive-sweep").toFile()
        val filesDirectory = File(dataDirectory, "files").apply { mkdirs() }
        File(filesDirectory, "audio").mkdirs()
        val cacheDirectory = File(dataDirectory, "cache").apply { mkdirs() }
        val preferences = InMemorySharedPreferences()
        val context = RepositoryTestContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value)

        try {
            assertEquals(LibrarySnapshot(), LocalLibraryRepository(context).load())

            val id = "11111111-1111-4111-8111-111111111111"
            val archive = PreparedBackupArchiveFiles.create(cacheDirectory, id).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val ownerMarker = File(cacheDirectory, "aftertone-restore-source-$id.owner")

            assertEquals(LibrarySnapshot(), LocalLibraryRepository(context).load())

            assertTrue("A later launch must not sweep an unrelated prepared ZIP.", archive.isFile)
            assertTrue("The ZIP's ownership marker must remain with it.", ownerMarker.isFile)
        } finally {
            dataDirectory.deleteRecursively()
        }
    }

    @Test
    fun completedScopedRecoveryDoesNotDeleteImportArtifactsOnFreshRepositoryStartup() {
        val dataDirectory = Files.createTempDirectory("aftertone-complete-import-sweep").toFile()
        val filesDirectory = File(dataDirectory, "files").apply { mkdirs() }
        val audioDirectory = File(filesDirectory, "audio").apply { mkdirs() }
        val cacheDirectory = File(dataDirectory, "cache").apply { mkdirs() }
        val preferences = InMemorySharedPreferences()
        val context = RepositoryTestContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value)

        try {
            assertEquals(LibrarySnapshot(), LocalLibraryRepository(context).load())

            val importArtifact = File(audioDirectory, "22222222-2222-4222-8222-222222222222.mp3")
                .apply { writeBytes(byteArrayOf(4, 5, 6)) }

            assertEquals(LibrarySnapshot(), LocalLibraryRepository(context).load())

            assertTrue("A later launch must not sweep an unrelated import artifact.", importArtifact.isFile)
        } finally {
            dataDirectory.deleteRecursively()
        }
    }

    @Test
    fun explicitRetryNeedsCurrentProcessAuthorizationAndRunsOnlyOnce() {
        val dataDirectory = Files.createTempDirectory("aftertone-explicit-recovery-retry").toFile()
        val filesDirectory = File(dataDirectory, "files").apply { mkdirs() }
        File(filesDirectory, "audio").mkdirs()
        val cacheDirectory = File(dataDirectory, "cache").apply { mkdirs() }
        val preferences = InMemorySharedPreferences()
        val context = RepositoryTestContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value)
        val mainJournal = File(filesDirectory, ".aftertone-restore-journal").apply { writeBytes(byteArrayOf(0x01)) }
        val temporaryBeforeAuthorization = File(
            filesDirectory,
            ".aftertone-restore-journal-33333333-3333-4333-8333-333333333333",
        ).apply { writeBytes(byteArrayOf(7, 8, 9)) }

        try {
            val firstRepository = LocalLibraryRepository(context)
            firstRepository.load()
            assertTrue(firstRepository.restoreActionGate.isBlocked)
            assertTrue(temporaryBeforeAuthorization.isFile)
            assertTrue(mainJournal.delete())

            val retryTemporary = File(
                filesDirectory,
                ".aftertone-restore-journal-44444444-4444-4444-8444-444444444444",
            ).apply { writeBytes(byteArrayOf(2, 4, 8)) }
            assertTrue(firstRepository.isScopedRecoveryRetryAvailable)
            assertTrue(firstRepository.authorizeScopedRecoveryRetry())

            val restartedBeforeAttempt = LocalLibraryRepository(context)
            restartedBeforeAttempt.load()
            assertTrue("An unconsumed grant is not persisted across process restart.", retryTemporary.isFile)
            assertTrue(restartedBeforeAttempt.restoreActionGate.isBlocked)
            assertTrue(restartedBeforeAttempt.isRecoveryRetryAuthorizationRequired)
            assertTrue(restartedBeforeAttempt.isScopedRecoveryRetryAvailable)

            assertTrue(restartedBeforeAttempt.authorizeScopedRecoveryRetry())
            restartedBeforeAttempt.load()

            assertFalse("A fresh explicit grant permits one scoped cleanup attempt.", retryTemporary.exists())
            assertFalse(restartedBeforeAttempt.restoreActionGate.isBlocked)

            val laterTemporary = File(
                filesDirectory,
                ".aftertone-restore-journal-55555555-5555-4555-8555-555555555555",
            ).apply { writeBytes(byteArrayOf(3, 6, 9)) }
            LocalLibraryRepository(context).load()
            assertTrue("The consumed explicit grant must not authorize a second attempt.", laterTemporary.isFile)
        } finally {
            dataDirectory.deleteRecursively()
        }
    }
}

private class RepositoryTestContext(
    private val dataDirectory: File,
    private val filesDirectory: File,
    private val cacheDirectory: File,
    private val preferences: SharedPreferences,
) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getDataDir(): File = dataDirectory
    override fun getFilesDir(): File = filesDirectory
    override fun getCacheDir(): File = cacheDirectory
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    override fun getSystemService(name: String): Any? = null
}

private class InMemorySharedPreferences : InvocationHandler {
    private val values = mutableMapOf<String, Any?>()
    var failedCommitsRemaining: Int = 0
    val value: SharedPreferences = proxy(SharedPreferences::class.java, this)

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args.orEmpty()
        return when (method.name) {
            "getAll" -> values.toMap()
            "getString" -> values[arguments[0] as String] as? String ?: arguments[1]
            "getStringSet" -> arguments[1]
            "getInt" -> values[arguments[0] as String] as? Int ?: arguments[1]
            "getLong" -> values[arguments[0] as String] as? Long ?: arguments[1]
            "getFloat" -> values[arguments[0] as String] as? Float ?: arguments[1]
            "getBoolean" -> values[arguments[0] as String] as? Boolean ?: arguments[1]
            "contains" -> values.containsKey(arguments[0] as String)
            "edit" -> newEditor()
            "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
            "toString" -> "InMemorySharedPreferences"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments.firstOrNull()
            else -> error("Unexpected SharedPreferences call: ${method.name}")
        }
    }

    private fun newEditor(): SharedPreferences.Editor {
        val changedValues = linkedMapOf<String, Any?>()
        val removedKeys = linkedSetOf<String>()
        var clearBeforeWrite = false
        lateinit var editor: SharedPreferences.Editor
        editor = proxy(SharedPreferences.Editor::class.java, InvocationHandler { _, method, args ->
            val arguments = args.orEmpty()
            when (method.name) {
                "putString", "putStringSet", "putInt", "putLong", "putFloat", "putBoolean" -> {
                    val key = arguments[0] as String
                    changedValues[key] = arguments[1]
                    removedKeys.remove(key)
                    editor
                }
                "remove" -> {
                    val key = arguments[0] as String
                    changedValues.remove(key)
                    removedKeys.add(key)
                    editor
                }
                "clear" -> {
                    clearBeforeWrite = true
                    editor
                }
                "commit" -> {
                    if (failedCommitsRemaining > 0) {
                        failedCommitsRemaining--
                        false
                    } else {
                        applyChanges(changedValues, removedKeys, clearBeforeWrite)
                        true
                    }
                }
                "apply" -> {
                    applyChanges(changedValues, removedKeys, clearBeforeWrite)
                    null
                }
                "toString" -> "InMemorySharedPreferences.Editor"
                "hashCode" -> System.identityHashCode(editor)
                "equals" -> editor === arguments.firstOrNull()
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        })
        return editor
    }

    private fun applyChanges(changedValues: Map<String, Any?>, removedKeys: Set<String>, clearBeforeWrite: Boolean) {
        if (clearBeforeWrite) values.clear()
        removedKeys.forEach(values::remove)
        values.putAll(changedValues)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> proxy(type: Class<T>, handler: InvocationHandler): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
}
