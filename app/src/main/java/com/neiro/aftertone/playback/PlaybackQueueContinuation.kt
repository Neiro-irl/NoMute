package com.neiro.aftertone.playback

import com.neiro.aftertone.model.Track

internal const val LIBRARY_CONTINUATION_EXTRA_KEY = "com.neiro.nomute.playback.LIBRARY_CONTINUATION"

/** Returns one complete library pass, starting at [startIndex] and wrapping in the given library order. */
internal fun libraryContinuationTracks(
    libraryTracks: List<Track>,
    startIndex: Int,
): List<Track> {
    if (libraryTracks.isEmpty()) return emptyList()
    require(startIndex in libraryTracks.indices) { "The continuation start must refer to a library track." }
    return buildList(libraryTracks.size) {
        addAll(libraryTracks.subList(startIndex, libraryTracks.size))
        addAll(libraryTracks.subList(0, startIndex))
    }
}
