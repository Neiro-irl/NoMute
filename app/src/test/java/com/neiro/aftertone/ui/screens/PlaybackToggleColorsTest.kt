package com.neiro.aftertone.ui.screens

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.neiro.aftertone.ui.theme.AftertonePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaybackToggleColorsTest {
    @Test
    fun activeShuffleAndRepeatUseDifferentColorsFromEveryTheme() {
        AftertonePalette.entries.forEach { palette ->
            val colorScheme = palette.toTestColorScheme()

            val shuffle = playbackToggleColors(
                colorScheme = colorScheme,
                mode = PlaybackToggleMode.SHUFFLE,
                active = true,
            )
            val repeat = playbackToggleColors(
                colorScheme = colorScheme,
                mode = PlaybackToggleMode.REPEAT,
                active = true,
            )

            assertEquals(Color(palette.accentArgb.toInt()), shuffle.containerColor)
            assertEquals(Color(palette.onAccentArgb.toInt()), shuffle.contentColor)
            assertEquals(Color(palette.secondaryArgb.toInt()), repeat.containerColor)
            assertEquals(Color(palette.onSecondaryArgb.toInt()), repeat.contentColor)
            assertNotEquals("${palette.id} should distinguish active controls", shuffle.containerColor, repeat.containerColor)
        }
    }

    @Test
    fun inactiveShuffleAndRepeatHaveTransparentContainersAndMutedIcons() {
        AftertonePalette.entries.forEach { palette ->
            val colorScheme = palette.toTestColorScheme()
            val expectedMuted = Color(palette.mutedArgb.toInt())

            listOf(PlaybackToggleMode.SHUFFLE, PlaybackToggleMode.REPEAT).forEach { mode ->
                val colors = playbackToggleColors(
                    colorScheme = colorScheme,
                    mode = mode,
                    active = false,
                )

                assertEquals(Color.Transparent, colors.containerColor)
                assertEquals(expectedMuted, colors.contentColor)
            }
        }
    }

    private fun AftertonePalette.toTestColorScheme(): ColorScheme = darkColorScheme(
        primary = Color(accentArgb.toInt()),
        onPrimary = Color(onAccentArgb.toInt()),
        secondary = Color(secondaryArgb.toInt()),
        onSecondary = Color(onSecondaryArgb.toInt()),
        onSurfaceVariant = Color(mutedArgb.toInt()),
    )
}
