package com.neiro.aftertone.ui.screens

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal enum class PlaybackToggleMode {
    SHUFFLE,
    REPEAT,
}

internal data class PlaybackToggleColors(
    val containerColor: Color,
    val contentColor: Color,
)

internal fun playbackToggleColors(
    colorScheme: ColorScheme,
    mode: PlaybackToggleMode,
    active: Boolean,
): PlaybackToggleColors {
    if (!active) {
        return PlaybackToggleColors(
            containerColor = Color.Transparent,
            contentColor = colorScheme.onSurfaceVariant,
        )
    }

    val (containerColor, contentColor) = when (mode) {
        PlaybackToggleMode.SHUFFLE -> colorScheme.primary to colorScheme.onPrimary
        PlaybackToggleMode.REPEAT -> colorScheme.secondary to colorScheme.onSecondary
    }
    return PlaybackToggleColors(
        containerColor = containerColor,
        contentColor = contentColor,
    )
}
