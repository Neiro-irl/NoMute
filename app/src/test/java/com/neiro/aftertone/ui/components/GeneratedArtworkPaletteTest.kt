package com.neiro.aftertone.ui.components

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GeneratedArtworkPaletteTest {
    @Test
    fun fallbackColorsAreDeterministicForATrackAndFollowTheSelectedTheme() {
        val trackId = "track-theme-regression"
        val aftertone = darkColorScheme(
            background = Color(0xFF050505),
            surface = Color(0xFF111111),
            surfaceVariant = Color(0xFF1D1D1F),
            primary = Color(0xFFD6F278),
            secondary = Color(0xFFB8C3A6),
        )
        val graphite = darkColorScheme(
            background = Color(0xFF151716),
            surface = Color(0xFF242725),
            surfaceVariant = Color(0xFF3C3F3E),
            primary = Color(0xFFEBE9E9),
            secondary = Color(0xFFAAACAB),
        )

        val aftertoneColors = generatedArtworkPalette(trackId, aftertone)

        assertEquals(aftertoneColors, generatedArtworkPalette(trackId, aftertone))
        assertNotEquals(aftertoneColors, generatedArtworkPalette(trackId, graphite))
        assertEquals(2, aftertoneColors.size)
    }
}
