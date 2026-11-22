package com.neiro.aftertone.ui.screens

import androidx.compose.ui.graphics.Color
import com.neiro.aftertone.ui.theme.AftertonePalette

internal data class PlaybackQueueColors(
    val dialogSurface: Color,
    val currentTrack: Color,
    val upcomingTrack: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val action: Color,
    val outline: Color,
)

internal fun playbackQueueColors(palette: AftertonePalette) = PlaybackQueueColors(
    dialogSurface = Color(palette.surfaceArgb.toInt()),
    currentTrack = Color(palette.dockArgb.toInt()),
    upcomingTrack = Color(palette.surfaceHighArgb.toInt()),
    primaryText = Color(palette.textArgb.toInt()),
    secondaryText = Color(palette.mutedArgb.toInt()),
    action = Color(palette.accentArgb.toInt()),
    outline = Color(palette.outlineArgb.toInt()),
)

internal data class PlaybackQueueMotion(
    val enterDurationMillis: Int,
    val exitDurationMillis: Int,
    val contentChangeDurationMillis: Int,
    val rowPlacementDurationMillis: Int,
    val enterScale: Float,
    val exitScale: Float,
    val verticalOffsetFraction: Float,
)

internal fun playbackQueueMotion(animationsEnabled: Boolean) = if (animationsEnabled) {
    PlaybackQueueMotion(
        enterDurationMillis = 280,
        exitDurationMillis = 180,
        contentChangeDurationMillis = 220,
        rowPlacementDurationMillis = 220,
        enterScale = 0.97f,
        exitScale = 0.985f,
        verticalOffsetFraction = 0.025f,
    )
} else {
    PlaybackQueueMotion(
        enterDurationMillis = 0,
        exitDurationMillis = 0,
        contentChangeDurationMillis = 0,
        rowPlacementDurationMillis = 0,
        enterScale = 1f,
        exitScale = 1f,
        verticalOffsetFraction = 0f,
    )
}

internal fun playbackQueueDialogShouldBeComposed(
    currentState: Boolean,
    targetState: Boolean,
): Boolean = currentState || targetState
