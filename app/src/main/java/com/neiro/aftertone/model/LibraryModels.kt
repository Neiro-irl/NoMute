package com.neiro.aftertone.model

private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr", "3gp")

private val audioExtensionsByMime = mapOf(
    "audio/mpeg" to "mp3",
    "audio/mp3" to "mp3",
    "audio/mp4" to "m4a",
    "audio/x-m4a" to "m4a",
    "audio/aac" to "aac",
    "audio/flac" to "flac",
    "audio/x-flac" to "flac",
    "audio/wav" to "wav",
    "audio/x-wav" to "wav",
    "audio/ogg" to "ogg",
    "application/ogg" to "ogg",
    "audio/opus" to "opus",
    "audio/amr" to "amr",
    "audio/3gpp" to "3gp",
)

private data class AudioTrackFormatPolicy(
    val fallbackExtension: String,
    val supportedContainerExtensions: Set<String>,
)

private val audioTrackFormatPolicies = mapOf(
    "audio/mp4a-latm" to AudioTrackFormatPolicy(
        fallbackExtension = "aac",
        supportedContainerExtensions = setOf("aac", "m4a", "3gp"),
    ),
    "audio/raw" to AudioTrackFormatPolicy(
        fallbackExtension = "wav",
        supportedContainerExtensions = setOf("wav"),
    ),
)

private val safeFileId = Regex("[A-Za-z0-9_-]{1,80}")

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val filePath: String,
    val artworkPath: String? = null,
    val externalImportRequestId: String? = null,
    val externalImportHandoffClaimed: Boolean = false,
    val externalImportHandoffDispatchStarted: Boolean? = null,
    val externalImportHandoffCompleted: Boolean = false,
) {
    val displayTitle: String
        get() = title.trim().ifEmpty { "Без названия" }

    val displayArtist: String
        get() = artist.trim().ifEmpty { "Неизвестный исполнитель" }
}

data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val artworkPath: String? = null,
) {
    fun withTrack(trackId: String): Playlist {
        val normalizedId = trackId.trim()
        return if (normalizedId.isEmpty() || normalizedId in trackIds) this
        else copy(trackIds = trackIds + normalizedId)
    }

    fun withoutTrack(trackId: String): Playlist = copy(trackIds = trackIds.filterNot { it == trackId })

    fun moveTrack(trackId: String, destinationIndex: Int): Playlist {
        val sourceIndex = trackIds.indexOf(trackId)
        if (sourceIndex < 0 || destinationIndex !in trackIds.indices || sourceIndex == destinationIndex) return this

        val reorderedTrackIds = trackIds.toMutableList()
        reorderedTrackIds.add(destinationIndex, reorderedTrackIds.removeAt(sourceIndex))
        return copy(trackIds = reorderedTrackIds)
    }

    fun tracksInOrder(availableTracks: List<Track>): List<Track> {
        val tracksById = availableTracks.associateBy(Track::id)
        return trackIds.mapNotNull(tracksById::get)
    }
}

data class LibrarySnapshot(
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val favoriteTrackIds: Set<String> = emptySet(),
) {
    fun toggleFavorite(trackId: String): LibrarySnapshot {
        if (tracks.none { it.id == trackId }) return this
        val favorites = if (trackId in favoriteTrackIds) favoriteTrackIds - trackId else favoriteTrackIds + trackId
        return copy(favoriteTrackIds = favorites)
    }

    fun moveTrack(trackId: String, destinationIndex: Int): LibrarySnapshot {
        val sourceIndex = tracks.indexOfFirst { it.id == trackId }
        if (sourceIndex < 0 || destinationIndex !in tracks.indices || sourceIndex == destinationIndex) return this

        val reorderedTracks = tracks.toMutableList()
        reorderedTracks.add(destinationIndex, reorderedTracks.removeAt(sourceIndex))
        return copy(tracks = reorderedTracks)
    }

    fun removeTrack(trackId: String): LibrarySnapshot = copy(
        tracks = tracks.filterNot { it.id == trackId },
        playlists = playlists.map { it.withoutTrack(trackId) },
        favoriteTrackIds = favoriteTrackIds - trackId,
    )

    fun addPlaylist(playlist: Playlist): LibrarySnapshot {
        val name = playlist.name.trim()
        if (playlist.id.isBlank() || name.isBlank()) return this
        if (playlists.any { it.id == playlist.id || it.name.equals(name, ignoreCase = true) }) return this
        val knownTrackIds = tracks.mapTo(hashSetOf()) { it.id }
        val validTrackIds = playlist.trackIds.filter { it in knownTrackIds }.distinct()
        return copy(playlists = playlists + playlist.copy(name = name, trackIds = validTrackIds))
    }

    fun addToPlaylist(playlistId: String, trackId: String): LibrarySnapshot {
        if (tracks.none { it.id == trackId }) return this
        return copy(playlists = playlists.map { playlist ->
            if (playlist.id == playlistId) playlist.withTrack(trackId) else playlist
        })
    }

    fun removeFromPlaylist(playlistId: String, trackId: String): LibrarySnapshot = copy(
        playlists = playlists.map { playlist ->
            if (playlist.id == playlistId) playlist.withoutTrack(trackId) else playlist
        },
    )

    fun removePlaylist(playlistId: String): LibrarySnapshot = copy(
        playlists = playlists.filterNot { it.id == playlistId },
    )
}

object TrackArtworkFileName {
    private val coverIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun forCustomCover(id: String, coverId: String = java.util.UUID.randomUUID().toString()): String {
        require(safeFileId.matches(id)) { "The track ID is not a safe file name." }
        require(isCanonicalCoverId(coverId)) { "The cover ID is not a canonical UUID." }
        return "$id-cover-$coverId.img"
    }

    fun isGeneratedCoverFileName(fileName: String): Boolean {
        if (!fileName.endsWith(".img")) return false
        val baseName = fileName.removeSuffix(".img")
        val marker = baseName.lastIndexOf("-cover-")
        if (marker <= 0) return false
        val id = baseName.substring(0, marker)
        val coverId = baseName.substring(marker + "-cover-".length)
        return safeFileId.matches(id) && isCanonicalCoverId(coverId)
    }

    fun isGeneratedFileName(id: String, fileName: String): Boolean {
        if (!safeFileId.matches(id)) return false
        return fileName == "$id.img" ||
            (fileName.startsWith("$id-cover-") && isGeneratedCoverFileName(fileName))
    }

    private fun isCanonicalCoverId(value: String): Boolean = coverIdPattern.matches(value) &&
        runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)
}

object PlaylistArtworkFileName {
    private val coverIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val generatedFileNamePattern = Regex(
        "([A-Za-z0-9_-]{1,80})-playlist-artwork-($coverIdPattern)\\.img",
    )

    fun forCustomCover(playlistId: String, coverId: String = java.util.UUID.randomUUID().toString()): String {
        require(isSafePlaylistId(playlistId)) { "The playlist ID is not a safe file name." }
        require(isCanonicalCoverId(coverId)) { "The cover ID is not a canonical UUID." }
        return "$playlistId-playlist-artwork-$coverId.img"
    }

    fun isGeneratedFileName(playlistId: String, fileName: String): Boolean =
        isSafePlaylistId(playlistId) &&
            generatedFileNamePattern.matchEntire(fileName)?.groupValues?.getOrNull(1) == playlistId

    fun playlistIdFromGeneratedFileName(fileName: String): String? =
        generatedFileNamePattern.matchEntire(fileName)?.groupValues?.getOrNull(1)

    fun isSafePlaylistId(playlistId: String): Boolean = safeFileId.matches(playlistId)

    fun isCanonicalCoverId(coverId: String): Boolean = coverIdPattern.matches(coverId) &&
        runCatching { java.util.UUID.fromString(coverId).toString() == coverId }.getOrDefault(false)
}

object TrackFileName {
    fun forImport(id: String, displayName: String, mimeType: String?): String? {
        if (!safeFileId.matches(id)) return null

        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        val isAudioMime = normalizedMime?.startsWith("audio/") == true
        val isGenericMime = normalizedMime == null || normalizedMime in setOf(
            "application/octet-stream",
            "binary/octet-stream",
        )
        val nameOnly = displayName.substringAfterLast('/').substringAfterLast('\\')
        val extensionFromName = nameOnly.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val supportedExtensionFromName = extensionFromName.takeIf { it in audioExtensions }
        if (isAudioMime) {
            val mappedExtension = audioExtensionsByMime[normalizedMime]
                ?: audioTrackFormatPolicies[normalizedMime]?.fallbackExtension
                ?: return null
            return "$id.$mappedExtension"
        }
        if (!isGenericMime && supportedExtensionFromName == null) return null
        val extension = audioExtensionsByMime[normalizedMime] ?: supportedExtensionFromName ?: return null

        return "$id.$extension"
    }

    fun forDetectedAudioTrack(
        id: String,
        displayName: String,
        detectedAudioMimeType: String,
        containerMimeType: String?,
    ): String? {
        if (!safeFileId.matches(id)) return null
        val normalizedDetectedMime = detectedAudioMimeType.substringBefore(';').trim().lowercase()
        val policy = audioTrackFormatPolicies[normalizedDetectedMime]
            ?: return forImport(id, displayName, detectedAudioMimeType)

        val containerExtensionFromMime = containerMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.let(audioExtensionsByMime::get)
            ?.takeIf { it in policy.supportedContainerExtensions }
        val extension = containerExtensionFromMime ?: policy.fallbackExtension
        return "$id.$extension"
    }

    fun isGeneratedFileName(id: String, fileName: String): Boolean {
        if (!safeFileId.matches(id) || !fileName.startsWith("$id.")) return false
        val extension = fileName.removePrefix("$id.")
        return '.' !in extension && extension in audioExtensions
    }
}
