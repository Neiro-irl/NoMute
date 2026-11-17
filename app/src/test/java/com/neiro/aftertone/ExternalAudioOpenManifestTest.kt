package com.neiro.aftertone

import java.io.File
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioOpenManifestTest {
    @Test
    fun mainActivityCanBeSelectedToOpenAudioFromAFileManager() {
        val manifestFile = File(System.getProperty("user.dir"), "src/main/AndroidManifest.xml")
        assertTrue("Android manifest must be readable from the app module test.", manifestFile.isFile)
        val parserFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = parserFactory.newDocumentBuilder().parse(manifestFile)
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val activities = manifest.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) }
            .firstOrNull { it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue == ".MainActivity" }
        assertNotNull("MainActivity must be declared in the manifest.", mainActivity)

        val activity = requireNotNull(mainActivity)
        assertTrue(
            "MainActivity must be exported so another app can open a selected audio URI.",
            activity.attributes.getNamedItemNS(androidNamespace, "exported")?.nodeValue == "true",
        )
        val filters = activity.childNodes.let { children ->
            (0 until children.length).mapNotNull { children.item(it) }
                .filter { it.nodeName == "intent-filter" }
        }
        fun hasDefaultViewFilterFor(mimeType: String): Boolean = filters.any { filter ->
            val filterElement = filter as Element
            val actions = filterElement.getElementsByTagName("action")
            val categories = filterElement.getElementsByTagName("category")
            val data = filterElement.getElementsByTagName("data")
            val hasViewAction = (0 until actions.length).any {
                actions.item(it).attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue == "android.intent.action.VIEW"
            }
            val hasDefaultCategory = (0 until categories.length).any {
                categories.item(it).attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue == "android.intent.category.DEFAULT"
            }
            val hasRequestedMime = (0 until data.length).any {
                data.item(it).attributes.getNamedItemNS(androidNamespace, "mimeType")?.nodeValue == mimeType
            }
            val hasContentScheme = (0 until data.length).any {
                data.item(it).attributes.getNamedItemNS(androidNamespace, "scheme")?.nodeValue == "content"
            }
            val hasFileScheme = (0 until data.length).any {
                data.item(it).attributes.getNamedItemNS(androidNamespace, "scheme")?.nodeValue == "file"
            }
            hasViewAction && hasDefaultCategory && hasRequestedMime && hasContentScheme && !hasFileScheme
        }

        assertTrue(
            "MainActivity needs an ACTION_VIEW + CATEGORY_DEFAULT audio/* filter for Explorer.",
            hasDefaultViewFilterFor("audio/*"),
        )
        assertTrue(
            "MainActivity needs a specific ACTION_VIEW filter for providers reporting application/x-id3.",
            hasDefaultViewFilterFor("application/x-id3"),
        )
        assertTrue(
            "MainActivity needs an ACTION_VIEW filter for providers reporting application/octet-stream.",
            hasDefaultViewFilterFor("application/octet-stream"),
        )
        assertTrue(
            "MainActivity needs an ACTION_VIEW filter for providers reporting binary/octet-stream.",
            hasDefaultViewFilterFor("binary/octet-stream"),
        )
    }
}
