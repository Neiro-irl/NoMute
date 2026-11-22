package com.neiro.aftertone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDomainTest {
    @Test
    fun addingAnExistingTrackDoesNotDuplicateIt() {
        val playlist = Playlist(id = "mix", name = "Mix", trackIds = listOf("one"))

        assertEquals(listOf("one", "two"), playlist.withTrack("two").withTrack("two").trackIds)
    }

    @Test
    fun removingATrackRemovesEveryReference() {
        val playlist = Playlist(id = "mix", name = "Mix", trackIds = listOf("one", "two", "two"))

        assertEquals(listOf("one"), playlist.withoutTrack("two").trackIds)
    }

    @Test
    fun collectionTracksFollowPlaylistOrderInsteadOfLibraryOrder() {
        val firstTrack = Track("first", "First", "Artist", filePath = "/audio/first.mp3")
        val secondTrack = Track("second", "Second", "Artist", filePath = "/audio/second.mp3")
        val playlist = Playlist("mix", "Mix", listOf("second", "first"))

        assertEquals(listOf(secondTrack, firstTrack), playlist.tracksInOrder(listOf(firstTrack, secondTrack)))
    }

    @Test
    fun movingAPlaylistTrackChangesOnlyItsPositionAndIgnoresMissingIds() {
        val playlist = Playlist("mix", "Mix", listOf("first", "second", "third"))

        assertEquals(listOf("second", "first", "third"), playlist.moveTrack("second", 0).trackIds)
        assertEquals(playlist, playlist.moveTrack("missing", 0))
        assertEquals(playlist, playlist.moveTrack("first", 3))
    }

    @Test
    fun movingALibraryTrackChangesOnlyItsPosition() {
        val first = Track("first", "First", "Artist", filePath = "/audio/first.mp3")
        val second = Track("second", "Second", "Artist", filePath = "/audio/second.mp3")
        val third = Track("third", "Third", "Artist", filePath = "/audio/third.mp3")
        val playlist = Playlist("mix", "Mix", listOf("third", "first"))
        val snapshot = LibrarySnapshot(
            tracks = listOf(first, second, third),
            playlists = listOf(playlist),
            favoriteTrackIds = setOf("second"),
        )

        val moved = snapshot.moveTrack("second", 0)

        assertEquals(listOf("second", "first", "third"), moved.tracks.map(Track::id))
        assertEquals(listOf(playlist), moved.playlists)
        assertEquals(setOf("second"), moved.favoriteTrackIds)
        assertEquals(listOf("first", "second", "third"), snapshot.tracks.map(Track::id))
    }

    @Test
    fun libraryTrackMoveIgnoresMissingIdsAndInvalidOrUnchangedPositions() {
        val snapshot = LibrarySnapshot(
            tracks = listOf(
                Track("first", "First", "Artist", filePath = "/audio/first.mp3"),
                Track("second", "Second", "Artist", filePath = "/audio/second.mp3"),
            ),
        )

        assertEquals(snapshot, snapshot.moveTrack("missing", 0))
        assertEquals(snapshot, snapshot.moveTrack("first", -1))
        assertEquals(snapshot, snapshot.moveTrack("first", 2))
        assertEquals(snapshot, snapshot.moveTrack("first", 0))
    }

    @Test
    fun importedFilenameNeverUsesTheDocumentPath() {
        val result = TrackFileName.forImport(
            id = "track-01",
            displayName = "../../private/song.mp3",
            mimeType = "audio/mpeg",
        )

        assertEquals("track-01.mp3", result)
    }

    @Test
    fun rejectsNonAudioDocuments() {
        assertNull(TrackFileName.forImport("doc-01", "notes.txt", "text/plain"))
    }

    @Test
    fun acceptsSupportedMp3ExtensionWhenProviderReportsNonAudioMime() {
        assertEquals(
            "track-03.mp3",
            TrackFileName.forImport("track-03", "song.mp3", "application/x-id3"),
        )
    }

    @Test
    fun mapsAudioMimeWhenTheExtensionIsMisleading() {
        assertEquals("track-02.m4a", TrackFileName.forImport("track-02", "audio.exe", "audio/mp4"))
    }

    @Test
    fun mapsAndroidAacTrackMimeToAnExplicitAacFallbackExtension() {
        assertEquals("track-04.aac", TrackFileName.forImport("track-04", "untitled", "audio/mp4a-latm"))
    }

    @Test
    fun doesNotInferAacContainerFromFilenameWhenProviderMimeHasNoContainerEvidence() {
        assertEquals(
            "track-05.aac",
            TrackFileName.forDetectedAudioTrack(
                id = "track-05",
                displayName = "recording.m4a",
                detectedAudioMimeType = "audio/mp4a-latm",
                containerMimeType = "application/octet-stream",
            ),
        )
    }

    @Test
    fun mapsDetectedPcmRawTrackToWavInsteadOfTrustingIncompatibleHints() {
        listOf("audio/wav", "audio/x-wav", "application/octet-stream", "audio/mpeg", null).forEach { containerMime ->
            assertEquals(
                "A detected audio/raw track must use the supported WAV representation for $containerMime.",
                "track-raw.wav",
                TrackFileName.forDetectedAudioTrack(
                    id = "track-raw",
                    displayName = "misleading.mp3",
                    detectedAudioMimeType = "audio/raw",
                    containerMimeType = containerMime,
                ),
            )
        }
    }

    @Test
    fun rejectsAnUnsafeGeneratedIdentifier() {
        assertNull(TrackFileName.forImport("../outside", "song.mp3", "audio/mpeg"))
    }

    @Test
    fun persistedFileNameMustMatchTrackIdAndAnAudioExtension() {
        assertTrue(TrackFileName.isGeneratedFileName("track-1", "track-1.m4a"))
        assertFalse(TrackFileName.isGeneratedFileName("track-1", "track-2.m4a"))
        assertFalse(TrackFileName.isGeneratedFileName("track-1", "track-1.exe"))
    }
}
