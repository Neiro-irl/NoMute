package com.neiro.aftertone.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSessionNavigationTest {
    @Test
    fun mediaSessionActivityActionRequestsNowPlaying() {
        assertEquals(
            5,
            nextNowPlayingLaunchRequestId(
                currentRequestId = 4,
                action = ACTION_OPEN_NOW_PLAYING,
            ),
        )
    }

    @Test
    fun unrelatedActionsDoNotRequestNowPlaying() {
        assertEquals(4, nextNowPlayingLaunchRequestId(4, "android.intent.action.VIEW"))
        assertEquals(0, nextNowPlayingLaunchRequestId(0, null))
    }
}
