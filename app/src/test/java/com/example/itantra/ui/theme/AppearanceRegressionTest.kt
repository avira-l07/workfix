package com.example.itantra.ui.theme

import com.example.itantra.data.settings.ColorPalette
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class AppearanceRegressionTest {
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return channel(c.red) * .2126 + channel(c.green) * .7152 + channel(c.blue) * .0722
    }
    @Test fun `all eight themes have readable text on surfaces and filled controls`() {
        for (dark in listOf(false, true)) for (palette in ColorPalette.entries) {
            val c = itantraColorScheme(dark, palette)
            for ((fg, bg) in listOf(c.onSurface to c.surface, c.onBackground to c.background,
                c.onSurfaceVariant to c.surfaceVariant, c.onPrimary to c.primary,
                c.onPrimaryContainer to c.primaryContainer,
                c.onSecondary to c.secondary, c.onSecondaryContainer to c.secondaryContainer,
                c.onTertiary to c.tertiary, c.onTertiaryContainer to c.tertiaryContainer,
                c.onError to c.error, c.onErrorContainer to c.errorContainer)) {
                val x = luminance(fg); val y = luminance(bg)
                val contrast = (maxOf(x, y) + .05) / (minOf(x, y) + .05)
                assertTrue("$palette dark=$dark contrast=$contrast", contrast >= 4.5)
            }
        }
    }
}
