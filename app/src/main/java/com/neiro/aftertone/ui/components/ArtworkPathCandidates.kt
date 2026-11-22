package com.neiro.aftertone.ui.components

internal fun artworkPathCandidates(
    artworkPathOverride: String?,
    trackArtworkPath: String?,
): List<String> = listOfNotNull(artworkPathOverride, trackArtworkPath).distinct()
