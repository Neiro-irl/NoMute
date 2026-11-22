package com.neiro.aftertone.playback

import com.neiro.aftertone.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueContinuationTest {
    @Test
    fun continuationStartsAtChosenLibraryIndexAndWrapsInCustomOrder() {
        val libraryTracks = listOf(
            track("custom-first"),
            track("custom-second"),
            track("custom-third"),
        )

        val continuation = libraryContinuationTracks(libraryTracks, startIndex = 2)

        assertEquals(
            listOf("custom-third", "custom-first", "custom-second"),
            continuation.map(Track::id),
        )
    }

    @Test
    fun emptyLibraryProducesNoContinuation() {
        assertEquals(emptyList<Track>(), libraryContinuationTracks(emptyList(), startIndex = 0))
    }

    @Test
    fun oneTrackLibraryProducesOneContinuationTrack() {
        val onlyTrack = track("only-track")

        assertEquals(listOf(onlyTrack), libraryContinuationTracks(listOf(onlyTrack), startIndex = 0))
    }

    private fun track(id: String) = Track(
        id = id,
        title = id,
        artist = "Artist",
        filePath = "/audio/$id.mp3",
    )
}
