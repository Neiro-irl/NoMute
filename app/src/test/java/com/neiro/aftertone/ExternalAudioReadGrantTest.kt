package com.neiro.aftertone

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalAudioReadGrantTest {
    @Test
    fun onlyExplicitPersistableReadGrantsAreRetained() {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val persistable = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        assertEquals(read, externalAudioPersistableReadPermissionFlags(read or persistable))
        assertEquals(0, externalAudioPersistableReadPermissionFlags(read))
        assertEquals(0, externalAudioPersistableReadPermissionFlags(persistable))
    }
}
