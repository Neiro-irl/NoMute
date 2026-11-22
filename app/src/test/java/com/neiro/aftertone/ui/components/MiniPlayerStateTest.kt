package com.neiro.aftertone.ui.components

import com.neiro.aftertone.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerStateTest {
    @Test
    fun idleMiniPlayerShowsClearPlaceholderTitle() {
        assertEquals("Пока ничего не играет", miniPlayerTitle(null))
    }

    @Test
    fun activeMiniPlayerShowsTheSelectedTrackTitle() {
        val firstTrack = Track(
            id = "track-1",
            title = "Moonlit Drive",
            artist = "North",
            filePath = "/music/moonlit-drive.mp3",
        )
        val nextTrack = Track(
            id = "track-2",
            title = "Second Sunrise",
            artist = "South",
            filePath = "/music/second-sunrise.mp3",
        )

        assertEquals("Moonlit Drive", miniPlayerTitle(firstTrack))
        assertEquals("Second Sunrise", miniPlayerTitle(nextTrack))
    }

    @Test
    fun playbackStateIsVisibleForPlayingAndPausedInputs() {
        assertEquals("Воспроизводится", miniPlayerPlaybackState(isPlaying = true))
        assertEquals("На паузе", miniPlayerPlaybackState(isPlaying = false))
    }

    @Test
    fun progressUsesCurrentPositionAndClampsToTrackBounds() {
        assertEquals(0.25f, miniPlayerProgress(2_500L, 10_000L, 0L), 0.0001f)
        assertEquals(0f, miniPlayerProgress(-500L, 10_000L, 0L), 0f)
        assertEquals(1f, miniPlayerProgress(12_000L, 10_000L, 0L), 0f)
    }

    @Test
    fun progressFallsBackToTrackDurationAndIsZeroWhenDurationIsUnknown() {
        assertEquals(0.25f, miniPlayerProgress(2_500L, 0L, 10_000L), 0.0001f)
        assertEquals(0f, miniPlayerProgress(2_500L, 0L, 0L), 0f)
    }
}
