package com.neiro.aftertone.model

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAX_SUPPORTED_STATE_CHARS = 5 * 1024 * 1024

class LibraryPersistenceTest {
    @Test
    fun codecRoundTripsNonAsciiMetadataAndCollections() {
        val track = Track(
            id = "track-1",
            title = "Тёплый вечер: side A",
            artist = "Mira & North",
            album = "First Light",
            durationMs = 182_250,
            filePath = "/private/audio/track-1.mp3",
            artworkPath = "/private/audio/track-1.img",
        )
        val snapshot = LibrarySnapshot(
            tracks = listOf(track),
            playlists = listOf(Playlist("mix-1", "Evening set", listOf("track-1"))),
            favoriteTrackIds = setOf("track-1"),
        )

        assertEquals(snapshot, LibraryStateCodec.decode(LibraryStateCodec.encode(snapshot)))
    }

    @Test
    fun schemaV1KeepsPlaylistOrderDifferentFromTrackStorageOrder() {
        val legacyState = """
            version=1
            tracks.count=2
            track.0.id=first
            track.0.title=First
            track.0.artist=Artist
            track.0.album=
            track.0.duration=1000
            track.0.file=/audio/first.mp3
            track.1.id=second
            track.1.title=Second
            track.1.artist=Artist
            track.1.album=
            track.1.duration=2000
            track.1.file=/audio/second.mp3
            playlists.count=1
            playlist.0.id=mix
            playlist.0.name=Mix
            playlist.0.tracks.count=2
            playlist.0.track.0=second
            playlist.0.track.1=first
            favorites.count=0
        """.trimIndent()

        val decoded = LibraryStateCodec.decode(legacyState)

        assertEquals(listOf("second", "first"), decoded.playlists.single().trackIds)
        assertNull(decoded.playlists.single().artworkPath)
    }

    @Test
    fun versionTwoCodecRoundTripsPlaylistArtworkPath() {
        val snapshot = LibrarySnapshot(
            tracks = listOf(Track("track-1", "First", "Artist", filePath = "/audio/track-1.mp3")),
            playlists = listOf(
                Playlist(
                    id = "mix-1",
                    name = "Mix",
                    trackIds = listOf("track-1"),
                    artworkPath = "/audio/mix-1-playlist-artwork-00000000-0000-0000-0000-000000000041.img",
                ),
            ),
        )

        val encoded = LibraryStateCodec.encode(snapshot)

        assertTrue(encoded.contains("version=2"))
        assertEquals(snapshot, LibraryStateCodec.decode(encoded))
    }

    @Test
    fun codecPersistsRequestIdAndCompletedHandoffForRetryAfterProcessRecreation() {
        val requestId = "f1557c4e-6670-4bc1-a595-914433c9a8d3"
        val snapshot = LibrarySnapshot(
            tracks = listOf(
                Track(
                    id = "track-1",
                    title = "External track",
                    artist = "Test artist",
                    filePath = "/private/audio/track-1.mp3",
                    externalImportRequestId = requestId,
                    externalImportHandoffDispatchStarted = true,
                    externalImportHandoffCompleted = true,
                ),
            ),
        )

        assertEquals(snapshot, LibraryStateCodec.decode(LibraryStateCodec.encode(snapshot)))
    }

    @Test
    fun codecPersistsExplicitNotDispatchedClaimForSafeRetry() {
        val requestId = "0f37c3a3-0d9a-49c8-9f3d-10de8a548f11"
        val snapshot = LibrarySnapshot(
            tracks = listOf(
                Track(
                    id = "track-claimed",
                    title = "Claimed external track",
                    artist = "Test artist",
                    filePath = "/private/audio/track-claimed.mp3",
                    externalImportRequestId = requestId,
                    externalImportHandoffClaimed = true,
                    externalImportHandoffDispatchStarted = false,
                ),
            ),
        )

        assertEquals(snapshot, LibraryStateCodec.decode(LibraryStateCodec.encode(snapshot)))
    }

    @Test
    fun codecLeavesMissingDispatchMarkerUnknownForLegacyClaimedTrack() {
        val requestId = "0f37c3a3-0d9a-49c8-9f3d-10de8a548f11"
        val legacySnapshot = LibrarySnapshot(
            tracks = listOf(
                Track(
                    id = "track-legacy-claim",
                    title = "Claimed before dispatch state existed",
                    artist = "Test artist",
                    filePath = "/private/audio/track-legacy-claim.mp3",
                    externalImportRequestId = requestId,
                    externalImportHandoffClaimed = true,
                ),
            ),
        )

        val decoded = LibraryStateCodec.decode(LibraryStateCodec.encode(legacySnapshot)).tracks.single()

        assertTrue(decoded.externalImportHandoffClaimed)
        assertNull(decoded.externalImportHandoffDispatchStarted)
    }

    @Test
    fun codecDefaultsMissingClaimMarkerToFalseForOlderLibraryStates() {
        val requestId = "f1557c4e-6670-4bc1-a595-914433c9a8d3"
        val previousVersionSnapshot = LibrarySnapshot(
            tracks = listOf(
                Track(
                    id = "track-legacy",
                    title = "Previously completed external track",
                    artist = "Test artist",
                    filePath = "/private/audio/track-legacy.mp3",
                    externalImportRequestId = requestId,
                    externalImportHandoffCompleted = true,
                ),
            ),
        )

        val decoded = LibraryStateCodec.decode(LibraryStateCodec.encode(previousVersionSnapshot))
        assertFalse(decoded.tracks.single().externalImportHandoffClaimed)
        assertTrue(decoded.tracks.single().externalImportHandoffCompleted)
    }

    @Test
    fun corruptedStateIsRejectedInsteadOfBecomingAnEmptyLibrary() {
        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.decode("not a library file")
        }
    }

    @Test
    fun blankStoredStateIsRejectedInsteadOfBecomingAnEmptyLibrary() {
        listOf("", " \t\n").forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) {
                LibraryStateCodec.decode(encoded)
            }
        }
    }

    @Test
    fun oversizedStateIsRejectedInsteadOfBecomingAnEmptyLibrary() {
        val oversized = "x".repeat(MAX_SUPPORTED_STATE_CHARS + 1)

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.decode(oversized)
        }
    }

    @Test
    fun oversizedSnapshotCannotBeEncodedForPersistence() {
        val oversizedTitle = "x".repeat(MAX_SUPPORTED_STATE_CHARS + 1)
        val snapshot = LibrarySnapshot(
            tracks = listOf(
                Track(
                    id = "track-1",
                    title = oversizedTitle,
                    artist = "Artist",
                    filePath = "/private/audio/track-1.mp3",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.encode(snapshot)
        }
    }

    @Test
    fun pathGuardAllowsOnlyDirectChildrenOfThePrivateAudioDirectory() {
        val root = Files.createTempDirectory("aftertone-audio-").toFile()
        try {
            assertTrue(PrivateAudioPath.isDirectChild(root, File(root, "track.mp3")))
            assertFalse(PrivateAudioPath.isDirectChild(root, File(root, "nested/track.mp3")))
            assertFalse(PrivateAudioPath.isDirectChild(root, File(root, "../outside.mp3")))
            assertFalse(PrivateAudioPath.isDirectChild(root, root))
            val realFile = File(root, "owned.mp3").apply { writeText("audio") }
            val link = File(root, "claimed.mp3")
            Files.createSymbolicLink(link.toPath(), realFile.toPath())
            assertFalse(PrivateAudioPath.isDirectChild(root, link))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun decoderRejectsDuplicateTrackRecordsInsteadOfDroppingOne() {
        val track = Track("track-1", "First", "Artist", filePath = "/private/audio/track-1.mp3")
        val damaged = LibrarySnapshot(tracks = listOf(track, track.copy(title = "Second copy")))

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.decode(LibraryStateCodec.encode(damaged))
        }
    }

    @Test
    fun decoderRejectsDanglingPlaylistReferenceInsteadOfDroppingIt() {
        val track = Track("track-1", "First", "Artist", filePath = "/private/audio/track-1.mp3")
        val damaged = LibrarySnapshot(
            tracks = listOf(track),
            playlists = listOf(Playlist("mix", "Mix", listOf("missing-track"))),
        )

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.decode(LibraryStateCodec.encode(damaged))
        }
    }

    @Test
    fun decoderRejectsFavoriteReferenceWithoutAnAudioTrack() {
        val damaged = LibrarySnapshot(favoriteTrackIds = setOf("missing-track"))

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.decode(LibraryStateCodec.encode(damaged))
        }
    }

    @Test
    fun refusesNormalizationThatWouldDiscardAStoredTrack() {
        val stored = LibrarySnapshot(
            tracks = listOf(Track("track-1", "First", "Artist", filePath = "/private/audio/track-1.mp3")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.requireNoDataLoss(stored, LibrarySnapshot())
        }
    }
}
