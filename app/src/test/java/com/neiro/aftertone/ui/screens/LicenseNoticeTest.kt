package com.neiro.aftertone.ui.screens

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseNoticeTest {
    @Test
    fun settingsDisclosureContainsTheRequiredLicenseNotices() {
        val resources = File("src/main/res/values/strings.xml").readText()

        assertTrue(resources.contains("name=\"license_action\""))
        assertTrue(resources.contains("name=\"license_copyright\""))
        assertTrue(resources.contains("name=\"license_no_warranty\""))
        assertTrue(resources.contains("name=\"license_conveyance\""))
        assertTrue(resources.contains("GPL-3.0-only"))
        assertTrue(resources.contains("без каких-либо гарантий"))
        assertTrue(resources.contains("распространять"))
    }

    @Test
    fun settingsDisclosureIncludesAnAppAccessibleApacheExifInterfaceNotice() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val dialog = File("src/main/java/com/neiro/aftertone/ui/screens/LicenseNoticeDialog.kt").readText()
        val noticeFile = File("src/main/res/raw/third_party_notices.txt")

        assertTrue(strings.contains("<string name=\"license_action\">Открытые лицензии</string>"))
        assertTrue(strings.contains("name=\"third_party_licenses_action\""))
        assertTrue(dialog.contains("R.raw.third_party_notices"))
        assertTrue("The Apache 2.0 notice must be packaged in the APK.", noticeFile.isFile)
        val notice = noticeFile.readText()
        assertTrue(notice.contains("AndroidX ExifInterface 1.4.2"))
        assertTrue(notice.contains("The Android Open Source Project"))
        assertTrue(notice.contains("Apache License"))
        assertTrue(notice.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertTrue(notice.contains("END OF TERMS AND CONDITIONS"))
    }

    @Test
    fun gplTermsAppearOnlyOnTheGplPageNotTheThirdPartyLicensePage() {
        val dialog = File("src/main/java/com/neiro/aftertone/ui/screens/LicenseNoticeDialog.kt").readText()
        val textContent = dialog.substringAfter("text = {").substringBefore("confirmButton = {")
        val thirdPartyBranch = textContent.indexOf("if (showThirdPartyNotices) {")
        val gplBranch = textContent.indexOf("} else {", thirdPartyBranch)

        assertTrue("The two license views must be separate branches.", thirdPartyBranch >= 0 && gplBranch > thirdPartyBranch)
        assertTrue(textContent.substring(thirdPartyBranch, gplBranch).contains("Text(thirdPartyNoticeText"))
        listOf("license_copyright", "license_no_warranty", "license_conveyance", "license_full_text").forEach { resource ->
            assertTrue("$resource must appear only in the GPL branch.", textContent.indexOf("R.string.$resource") > gplBranch)
        }
    }

    @Test
    fun packagedLicenseTextIsIdenticalToTheRepositoryLicense() {
        val repositoryLicense = File("../LICENSE")
        val packagedLicense = File("src/main/res/raw/gpl_3_0.txt")

        assertTrue("The GPL-3.0 license must be included in app resources.", packagedLicense.isFile)
        assertArrayEquals(repositoryLicense.readBytes(), packagedLicense.readBytes())
    }
}
