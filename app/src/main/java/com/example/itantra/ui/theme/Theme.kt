package com.example.itantra.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.itantra.data.settings.ColorPalette
import com.example.itantra.data.settings.ThemeMode

internal fun itantraColorScheme(dark: Boolean, palette: ColorPalette): ColorScheme {
    val primary = when (palette) {
        ColorPalette.OCEAN -> if (dark) 0xFF93C5FD else 0xFF1D4ED8
        ColorPalette.FOREST -> if (dark) 0xFF6EE7B7 else 0xFF047857
        ColorPalette.IRIS -> if (dark) 0xFFC4B5FD else 0xFF6D28D9
        ColorPalette.EMBER -> if (dark) 0xFFFDBA74 else 0xFF9A3412
    }
    return if (dark) darkColorScheme(
        primary = Color(primary), onPrimary = Color(0xFF0F172A),
        primaryContainer = Color(0xFF26364A), onPrimaryContainer = Color(0xFFF1F5F9),
        secondary = Color(0xFF6EE7B7), onSecondary = Color(0xFF052E23),
        secondaryContainer = Color(0xFF113C30), onSecondaryContainer = Color(0xFFBBF7D0),
        tertiary = Color(0xFFFCD34D), onTertiary = Color(0xFF302300),
        tertiaryContainer = Color(0xFF40310D), onTertiaryContainer = Color(0xFFFDE68A),
        error = Color(0xFFFFB4AB), onError = Color(0xFF600D12),
        errorContainer = Color(0xFF541D25), onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0F172A), onBackground = Color(0xFFF1F5F9),
        surface = Color(0xFF1E293B), onSurface = Color(0xFFF1F5F9),
        surfaceVariant = Color(0xFF29374A), onSurfaceVariant = Color(0xFFCBD5E1),
        outline = Color(0xFF94A3B8), outlineVariant = Color(0xFF475569),
    ) else lightColorScheme(
        primary = Color(primary), onPrimary = Color.White,
        primaryContainer = Color(primary).copy(alpha = 0.12f), onPrimaryContainer = Color(primary),
        secondary = Color(0xFF047857), onSecondary = Color.White,
        secondaryContainer = Color(0xFFD1FAE5), onSecondaryContainer = Color(0xFF065F46),
        tertiary = Color(0xFF92400E), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFEF3C7), onTertiaryContainer = Color(0xFF78350F),
        error = Color(0xFFB91C1C), onError = Color.White,
        errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF991B1B),
        background = Color(0xFFF8FAFC), onBackground = Color(0xFF0F172A),
        surface = Color.White, onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFF1F5F9), onSurfaceVariant = Color(0xFF475569),
        outline = Color(0xFF64748B), outlineVariant = Color(0xFFCBD5E1),
    )
}

@Composable
private fun smoothColor(color: Color): Color = animateColorAsState(color, tween(180), label = "appearance").value

@Composable
fun ITantraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    palette: ColorPalette = ColorPalette.OCEAN,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> darkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val target = remember(dark, palette, dynamicColor, context) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else itantraColorScheme(dark, palette)
    }
    val colors = target.copy(
        primary = smoothColor(target.primary), onPrimary = smoothColor(target.onPrimary),
        primaryContainer = smoothColor(target.primaryContainer), onPrimaryContainer = smoothColor(target.onPrimaryContainer),
        secondary = smoothColor(target.secondary), onSecondary = smoothColor(target.onSecondary),
        secondaryContainer = smoothColor(target.secondaryContainer), onSecondaryContainer = smoothColor(target.onSecondaryContainer),
        tertiary = smoothColor(target.tertiary), onTertiary = smoothColor(target.onTertiary),
        tertiaryContainer = smoothColor(target.tertiaryContainer), onTertiaryContainer = smoothColor(target.onTertiaryContainer),
        error = smoothColor(target.error), onError = smoothColor(target.onError),
        errorContainer = smoothColor(target.errorContainer), onErrorContainer = smoothColor(target.onErrorContainer),
        background = smoothColor(target.background), onBackground = smoothColor(target.onBackground),
        surface = smoothColor(target.surface), onSurface = smoothColor(target.onSurface),
        surfaceVariant = smoothColor(target.surfaceVariant), onSurfaceVariant = smoothColor(target.onSurfaceVariant),
        outline = smoothColor(target.outline), outlineVariant = smoothColor(target.outlineVariant),
    )
    MaterialTheme(colorScheme = colors, typography = Typography, content = content)
}
