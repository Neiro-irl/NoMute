package com.neiro.aftertone

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadmeWorkflowDocumentationTest {
    @Test
    fun readmeDocumentsTheActualPushAndManualReleaseTriggers() {
        val readme = File("../README.md").readText()
        val workflow = File("../.github/workflows/android-apk.yml").readText()

        assertTrue(readme.contains("pushes to `main`"))
        assertTrue(readme.contains("manual `workflow_dispatch` on `main`"))
        assertTrue(readme.contains("Pull requests run verification and upload a debug artifact only; they do not publish a GitHub Release."))
        assertFalse(readme.contains("when a release is created"))
        assertTrue(workflow.contains("workflow_dispatch"))
        assertTrue(workflow.contains("if: github.event_name != 'pull_request' && github.ref == 'refs/heads/main'"))
    }
}
