package com.neiro.aftertone.ui.screens

import androidx.compose.ui.graphics.Color
import com.neiro.aftertone.ui.theme.AftertonePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueStyleTest {
    @Test
    fun queueSurfacesAndContentColorsComeFromEverySelectedPalette() {
        AftertonePalette.entries.forEach { palette ->
            val colors = playbackQueueColors(palette)

            assertEquals(Color(palette.surfaceArgb.toInt()), colors.dialogSurface)
            assertEquals(Color(palette.dockArgb.toInt()), colors.currentTrack)
            assertEquals(Color(palette.surfaceHighArgb.toInt()), colors.upcomingTrack)
            assertEquals(Color(palette.textArgb.toInt()), colors.primaryText)
            assertEquals(Color(palette.mutedArgb.toInt()), colors.secondaryText)
            assertEquals(Color(palette.accentArgb.toInt()), colors.action)
            assertEquals(Color(palette.outlineArgb.toInt()), colors.outline)
        }
    }

    @Test
    fun queueTextMeetsReadableContrastOnDialogAndBothTrackCardSurfacesForEveryTheme() {
        AftertonePalette.entries.forEach { palette ->
            val colors = playbackQueueColors(palette)
            val surfaces = listOf(colors.dialogSurface, colors.currentTrack, colors.upcomingTrack)

            assertTrue(
                "Done button contrast is too low in ${palette.id}",
                contrastRatio(colors.action, colors.dialogSurface) >= MINIMUM_TEXT_CONTRAST,
            )
            surfaces.forEach { surface ->
                assertTrue(
                    "Primary text contrast is too low in ${palette.id}",
                    contrastRatio(colors.primaryText, surface) >= MINIMUM_TEXT_CONTRAST,
                )
                assertTrue(
                    "Secondary text contrast is too low in ${palette.id}",
                    contrastRatio(colors.secondaryText, surface) >= MINIMUM_TEXT_CONTRAST,
                )
                assertTrue(
                    "Action icon contrast is too low in ${palette.id}",
                    contrastRatio(colors.action, surface) >= MINIMUM_GRAPHIC_CONTRAST,
                )
            }
        }
    }

    @Test
    fun disabledSystemAnimationsRemoveDurationAndSpatialMovement() {
        val motion = playbackQueueMotion(animationsEnabled = false)

        assertEquals(0, motion.enterDurationMillis)
        assertEquals(0, motion.exitDurationMillis)
        assertEquals(0, motion.contentChangeDurationMillis)
        assertEquals(0, motion.rowPlacementDurationMillis)
        assertEquals(1f, motion.enterScale)
        assertEquals(1f, motion.exitScale)
        assertEquals(0f, motion.verticalOffsetFraction)
    }

    @Test
    fun enabledSystemAnimationsUseShortSmoothTransitions() {
        val motion = playbackQueueMotion(animationsEnabled = true)

        assertTrue(motion.enterDurationMillis in 180..360)
        assertTrue(motion.exitDurationMillis in 120..260)
        assertTrue(motion.contentChangeDurationMillis in 140..300)
        assertTrue(motion.rowPlacementDurationMillis in 140..300)
        assertTrue(motion.enterScale in 0.94f..0.99f)
        assertTrue(motion.exitScale in 0.97f..1f)
        assertTrue(motion.verticalOffsetFraction in 0.01f..0.05f)
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linearize(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }

        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }

    private companion object {
        const val MINIMUM_TEXT_CONTRAST = 4.5
        const val MINIMUM_GRAPHIC_CONTRAST = 3.0
    }
}
