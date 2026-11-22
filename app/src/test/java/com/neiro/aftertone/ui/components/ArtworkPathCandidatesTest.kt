package com.neiro.aftertone.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkPathCandidatesTest {
    @Test
    fun customArtworkIsTriedBeforeFirstTrackArtwork() {
        assertEquals(
            listOf("playlist-cover.img", "first-track-cover.img"),
            artworkPathCandidates("playlist-cover.img", "first-track-cover.img"),
        )
    }

    @Test
    fun firstTrackArtworkIsUsedWhenThereIsNoCustomCover() {
        assertEquals(
            listOf("first-track-cover.img"),
            artworkPathCandidates(null, "first-track-cover.img"),
        )
    }

    @Test
    fun duplicateFallbackPathIsTriedOnlyOnce() {
        assertEquals(
            listOf("same-cover.img"),
            artworkPathCandidates("same-cover.img", "same-cover.img"),
        )
    }
}
