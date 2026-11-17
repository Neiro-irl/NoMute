package com.neiro.aftertone.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal fun generatedArtworkPalette(trackId: String?, colorScheme: ColorScheme): List<Color> {
    val paletteIndex = (trackId?.hashCode()?.and(Int.MAX_VALUE) ?: 0) % 5
    return when (paletteIndex) {
        0 -> listOf(colorScheme.surfaceVariant, colorScheme.primary)
        1 -> listOf(colorScheme.surface, colorScheme.secondary)
        2 -> listOf(colorScheme.background, colorScheme.primary)
        3 -> listOf(colorScheme.surfaceVariant, colorScheme.secondary)
        else -> listOf(colorScheme.background, colorScheme.secondary)
    }
}
