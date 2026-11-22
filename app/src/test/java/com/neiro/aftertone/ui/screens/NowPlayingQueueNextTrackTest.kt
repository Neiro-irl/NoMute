package com.neiro.aftertone.ui.screens

import androidx.media3.common.Player
import com.neiro.aftertone.playback.PlaybackQueueEntry
import com.neiro.aftertone.playback.PlaybackQueueSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingQueueNextTrackTest {
    @Test
    fun presentationExposesTheFirstUpcomingTrackAndItsPlayerIndex() {
        val queue = PlaybackQueueSnapshot(
            items = listOf(
                entry("current", 2),
                entry("next", 0),
                entry("later", 1),
            ),
            currentIndex = 0,
        )
        val presentation = nowPlayingQueuePresentation(
            hasCurrentTrack = true,
            queue = queue,
            canEditQueue = true,
            repeatMode = Player.REPEAT_MODE_OFF,
        )
        assertEquals("next", presentation.nextEntry?.mediaId)
        assertEquals(0, presentation.nextEntry?.playerIndex)
    }

    @Test
    fun repeatOneShowsTheCurrentTrackAsTheTrackThatWillPlayNext() {
        val queue = PlaybackQueueSnapshot(
            items = listOf(entry("current", 0), entry("next", 1)),
            currentIndex = 0,
        )

        val presentation = nowPlayingQueuePresentation(
            hasCurrentTrack = true,
            queue = queue,
            canEditQueue = true,
            repeatMode = Player.REPEAT_MODE_ONE,
        )

        assertEquals("current", presentation.nextEntry?.mediaId)
        assertEquals(0, presentation.nextEntry?.playerIndex)
    }

    private fun entry(id: String, playerIndex: Int) = PlaybackQueueEntry(
        mediaId = id,
        title = id,
        artist = "Artist",
        playerIndex = playerIndex,
    )
}
