package com.neiro.aftertone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistArtworkFileNameTest {
    private val playlistId = "00000000-0000-0000-0000-000000000041"
    private val coverId = "abcdefab-cdef-4abc-8def-cdefabcdefab"

    @Test
    fun generatedPlaylistCoverHasASeparateNamespaceFromTrackArtwork() {
        val fileName = PlaylistArtworkFileName.forCustomCover(playlistId, coverId)

        assertEquals("$playlistId-playlist-artwork-$coverId.img", fileName)
        assertTrue(PlaylistArtworkFileName.isGeneratedFileName(playlistId, fileName))
        assertFalse(TrackArtworkFileName.isGeneratedFileName("$playlistId-playlist-artwork", fileName))
    }

    @Test
    fun playlistCoverNameRejectsUnsafePlaylistIdsAndNonCanonicalCoverIds() {
        listOf("../mix", "", "playlist/name").forEach { unsafeId ->
            assertFalse(PlaylistArtworkFileName.isSafePlaylistId(unsafeId))
        }
        listOf("../cover", coverId.uppercase(), "not-a-uuid").forEach { unsafeCoverId ->
            assertFalse(PlaylistArtworkFileName.isCanonicalCoverId(unsafeCoverId))
        }
    }

    @Test
    fun playlistCoverRecognitionRequiresTheExactOwnerAndSafeFilename() {
        val fileName = PlaylistArtworkFileName.forCustomCover(playlistId, coverId)

        assertFalse(PlaylistArtworkFileName.isGeneratedFileName("another-playlist", fileName))
        assertFalse(PlaylistArtworkFileName.isGeneratedFileName(playlistId, "../$fileName"))
        assertFalse(PlaylistArtworkFileName.isGeneratedFileName(playlistId, "$playlistId-playlist-artwork-$coverId-copy.img"))
        assertFalse(PlaylistArtworkFileName.isGeneratedFileName(playlistId, "$playlistId-playlist-artwork-${coverId}ximg"))
    }
}
