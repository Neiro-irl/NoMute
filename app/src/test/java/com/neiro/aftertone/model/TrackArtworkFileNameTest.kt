package com.neiro.aftertone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackArtworkFileNameTest {
    @Test
    fun acceptsLegacyAndUniqueCoverNamesOnlyForTheirOwningTrack() {
        val coverId = "123e4567-e89b-12d3-a456-426614174000"
        val coverName = "track-1-cover-$coverId.img"

        assertTrue(TrackArtworkFileName.isGeneratedFileName("track-1", "track-1.img"))
        assertTrue(TrackArtworkFileName.isGeneratedFileName("track-1", coverName))
        assertFalse(TrackArtworkFileName.isGeneratedFileName("track-2", coverName))
        assertFalse(TrackArtworkFileName.isGeneratedFileName("track-1", "track-1-cover-not-a-uuid.img"))
        assertFalse(TrackArtworkFileName.isGeneratedFileName("track-1", "../track-1.img"))
    }

    @Test
    fun generatesAndRecognizesOnlyCanonicalUniqueCoverNames() {
        val coverId = "123e4567-e89b-12d3-a456-426614174000"
        val name = TrackArtworkFileName.forCustomCover("track-1", coverId)

        assertEquals("track-1-cover-$coverId.img", name)
        assertTrue(TrackArtworkFileName.isGeneratedCoverFileName(name))
        assertFalse(TrackArtworkFileName.isGeneratedCoverFileName("track-1-cover-$coverId.img.bak"))
        assertFalse(TrackArtworkFileName.isGeneratedCoverFileName("../track-1-cover-$coverId.img"))
    }
}
