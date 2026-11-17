package com.neiro.aftertone.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ReadOnlyRecoveryStartupTest {
    @Test
    fun startupDoesNotRetryCleanupOrChangeStoredLibraryState() {
        assumeTrue("This test requires the read-only recovery diagnostic build.", diagnosticBuildEnabled())

        val dataDirectory = Files.createTempDirectory("aftertone-read-only-startup").toFile()
        val filesDirectory = File(dataDirectory, "files").apply { mkdirs() }
        val cacheDirectory = File(dataDirectory, "cache")
        val state = LibraryStateCodec.encode(LibrarySnapshot())
        val preferences = InMemoryPreferences(mapOf("library_state" to state))
        val temporaryJournal = File(filesDirectory, ".aftertone-restore-journal-${UUID.randomUUID()}")
            .apply { writeBytes(byteArrayOf(7, 3, 1)) }
        val repository = LocalLibraryRepository(
            ReadOnlyStartupContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value),
        )

        try {
            repository.load()

            assertTrue("Diagnostic startup must not retry journal-temporary cleanup.", temporaryJournal.exists())
            assertEquals("Startup must preserve the stored library state.", state, preferences.stringValue("library_state"))
            assertEquals(
                "The restore-action gate must remain blocked in the read-only diagnostic build.",
                RestoreActionGate.RECOVERY_PENDING_ID,
                repository.restoreActionGate.pendingCleanupTransactionId,
            )
        } finally {
            dataDirectory.deleteRecursively()
        }
    }

    @Test
    fun startupClassifiesTheJournalWithoutCleanupOrExposingAnotherAction() {
        assumeTrue("This test requires the read-only recovery diagnostic build.", diagnosticBuildEnabled())

        val dataDirectory = Files.createTempDirectory("aftertone-read-only-journal-diagnosis").toFile()
        val filesDirectory = File(dataDirectory, "files").apply { mkdirs() }
        val cacheDirectory = File(dataDirectory, "cache")
        val audioDirectory = File(filesDirectory, "audio").apply { mkdirs() }
        val audioSentinel = File(audioDirectory, "local-audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val state = LibraryStateCodec.encode(LibrarySnapshot())
        val preferences = InMemoryPreferences(
            mapOf(
                "library_state" to state,
                "recovery_diagnostic_attempt_status_v1" to "P",
                "recovery_diagnostic_attempt_trace_v1" to "AT00.PF01.JR02.RR02.GT08",
            ),
        )
        val mainJournalBytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 2)
        val mainJournal = File(filesDirectory, ".aftertone-restore-journal").apply { writeBytes(mainJournalBytes) }
        val temporaryJournal = File(filesDirectory, ".aftertone-restore-journal-${UUID.randomUUID()}")
            .apply { writeBytes(byteArrayOf(1, 4, 2)) }
        val repository = LocalLibraryRepository(
            ReadOnlyStartupContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value),
        )

        try {
            repository.load()
            val status = repository.recoveryDiagnosticStatusMessage
            assertTrue("Startup must append only the fixed unsupported-header code.", status.contains("DIA-AT00-PF01-JR02-RR02-GT08-JC13"))
            assertTrue("The UI status must say this was read-only.", status.contains("только для чтения"))
            assertFalse("The status must not offer or suggest another attempt.", status.contains("повтор"))
            assertEquals(
                RestoreActionGate.RECOVERY_PENDING_ID,
                repository.restoreActionGate.pendingCleanupTransactionId,
            )
            assertArrayEquals("The inspected journal must remain byte-for-byte unchanged.", mainJournalBytes, mainJournal.readBytes())
            assertTrue("Startup must not remove temporary recovery files.", temporaryJournal.exists())
            assertTrue("Startup must not inspect or change audio files.", audioSentinel.isFile)
            assertEquals("Startup must preserve the stored library state.", state, preferences.stringValue("library_state"))
            assertFalse(
                "The read-only build must not expose the spent cleanup action.",
                repository.javaClass.declaredMethods.any { it.name.startsWith("runOneShotRecoveryDiagnosticAction") },
            )
            assertEquals(3L, audioSentinel.length())
        } finally {
            dataDirectory.deleteRecursively()
        }
    }

    private fun diagnosticBuildEnabled(): Boolean {
        val field = runCatching {
            Class.forName("com.neiro.aftertone.BuildConfig").getField("RECOVERY_DIAGNOSTIC_READ_ONLY")
        }.getOrNull()
        assertNotNull("A build-time read-only recovery diagnostic flag must exist.", field)
        return field!!.getBoolean(null)
    }
}

private class ReadOnlyStartupContext(
    private val dataDirectory: File,
    private val filesDirectory: File,
    private val cacheDirectory: File,
    private val preferences: SharedPreferences,
) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.neiro.aftertone"
    override fun getDataDir(): File = dataDirectory
    override fun getFilesDir(): File = filesDirectory
    override fun getCacheDir(): File = cacheDirectory
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    override fun getSystemService(name: String): Any? = null
}

private class InMemoryPreferences(initialValues: Map<String, String>) : InvocationHandler {
    private val values = initialValues.toMutableMap<String, Any?>()
    val value: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
        this,
    ) as SharedPreferences

    fun stringValue(key: String): String? = values[key] as? String

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args.orEmpty()
        return when (method.name) {
            "getAll" -> values.toMap()
            "getString" -> values[arguments[0] as String] as? String ?: arguments[1]
            "contains" -> values.containsKey(arguments[0] as String)
            "edit" -> editor()
            "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
            "toString" -> "InMemoryPreferences"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments.firstOrNull()
            else -> error("Unexpected SharedPreferences call: ${method.name}")
        }
    }

    private fun editor(): SharedPreferences.Editor {
        val pending = linkedMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            val arguments = args.orEmpty()
            when (method.name) {
                "putString" -> {
                    pending[arguments[0] as String] = arguments[1]
                    editor
                }
                "remove" -> {
                    pending[arguments[0] as String] = null
                    editor
                }
                "commit" -> {
                    pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    true
                }
                "apply" -> {
                    pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    null
                }
                "toString" -> "InMemoryPreferences.Editor"
                "hashCode" -> System.identityHashCode(editor)
                "equals" -> editor === arguments.firstOrNull()
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return editor
    }
}
