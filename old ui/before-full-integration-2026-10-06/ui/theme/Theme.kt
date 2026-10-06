package com.example.itantra.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.itantra.data.settings.ColorPalette
import com.example.itantra.data.settings.ThemeMode

internal fun itantraColorScheme(dark: Boolean, palette: ColorPalette): ColorScheme {
    val primary = when (palette) {
        ColorPalette.OCEAN -> if (dark) 0xFF9BB5FF else 0xFF3457CC
        ColorPalette.FOREST -> if (dark) 0xFFA2D8B8 else 0xFF176950
        ColorPalette.IRIS -> if (dark) 0xFFCCB1FF else 0xFF7045B7
        ColorPalette.EMBER -> if (dark) 0xFFF6B298 else 0xFFAC4429
    }
    val background = when (palette) {
        ColorPalette.OCEAN -> if (dark) 0xFF101722 else 0xFFF4F7FB
        ColorPalette.FOREST -> if (dark) 0xFF111C18 else 0xFFF3F7F3
        ColorPalette.IRIS -> if (dark) 0xFF1B1725 else 0xFFF8F5FB
        ColorPalette.EMBER -> if (dark) 0xFF211914 else 0xFFFCF7F1
    }
    val surface = when (palette) {
        ColorPalette.OCEAN -> 0xFF172131
        ColorPalette.FOREST -> 0xFF192821
        ColorPalette.IRIS -> 0xFF241F31
        ColorPalette.EMBER -> 0xFF2C231D
    }
    val ink = when (palette) {
        ColorPalette.OCEAN -> if (dark) 0xFFEDF2FF else 0xFF202D43
        ColorPalette.FOREST -> if (dark) 0xFFE9F5EB else 0xFF21352D
        ColorPalette.IRIS -> if (dark) 0xFFF4EDFF else 0xFF32273E
        ColorPalette.EMBER -> if (dark) 0xFFFFF2E8 else 0xFF3D2C26
    }
    val muted = when (palette) {
        ColorPalette.OCEAN -> if (dark) 0xFFADBED5 else 0xFF596981
        ColorPalette.FOREST -> if (dark) 0xFFB1C7B8 else 0xFF566C5E
        ColorPalette.IRIS -> if (dark) 0xFFC6B7D5 else 0xFF6F5F7B
        ColorPalette.EMBER -> if (dark) 0xFFD3BDAB else 0xFF786153
    }
    val line = when (palette) {
        ColorPalette.OCEAN -> if (dark) 0xFF2C3C52 else 0xFFDCE4EF
        ColorPalette.FOREST -> if (dark) 0xFF30493B else 0xFFDAE5DC
        ColorPalette.IRIS -> if (dark) 0xFF40334F else 0xFFE7DDEE
        ColorPalette.EMBER -> if (dark) 0xFF503C2D else 0xFFEBDFD2
    }
    val accentSoft = when (palette) {
        ColorPalette.OCEAN -> if (dark) 0xFF223353 else 0xFFE9EFFF
        ColorPalette.FOREST -> if (dark) 0xFF244534 else 0xFFE1F1E8
        ColorPalette.IRIS -> if (dark) 0xFF402E5A else 0xFFF0E7FC
        ColorPalette.EMBER -> if (dark) 0xFF533526 else 0xFFFBE9E0
    }
    val secondary = when (palette) {
        ColorPalette.OCEAN -> if (dark) 0xFF93D6C0 else 0xFF277462
        ColorPalette.FOREST -> if (dark) 0xFFE0C28E else 0xFF826018
        ColorPalette.IRIS -> if (dark) 0xFFEDB3C9 else 0xFFA0466B
        ColorPalette.EMBER -> if (dark) 0xFFB9CE9C else 0xFF52704A
    }
    val secondarySoft = when (palette) {
        ColorPalette.OCEAN -> if (dark) 0xFF213C35 else 0xFFE4F3ED
        ColorPalette.FOREST -> if (dark) 0xFF3B3323 else 0xFFF6EEDB
        ColorPalette.IRIS -> if (dark) 0xFF472E3E else 0xFFF9E8EF
        ColorPalette.EMBER -> if (dark) 0xFF323D29 else 0xFFEBF1E3
    }
    return if (dark) darkColorScheme(
        primary = Color(primary), onPrimary = Color(0xFF0F172A),
        primaryContainer = Color(accentSoft), onPrimaryContainer = Color(primary),
        secondary = Color(secondary), onSecondary = Color(0xFF0F172A),
        secondaryContainer = Color(secondarySoft), onSecondaryContainer = Color(secondary),
        tertiary = Color(0xFFFCD34D), onTertiary = Color(0xFF302300),
        tertiaryContainer = Color(0xFF40310D), onTertiaryContainer = Color(0xFFFDE68A),
        error = Color(0xFFFFB1BA), onError = Color(0xFF421821),
        errorContainer = Color(0xFF43262F), onErrorContainer = Color(0xFFFFB1BA),
        background = Color(background), onBackground = Color(ink),
        surface = Color(surface), onSurface = Color(ink),
        surfaceVariant = Color(surface), onSurfaceVariant = Color(muted),
        outline = Color(muted), outlineVariant = Color(line),
    ) else lightColorScheme(
        primary = Color(primary), onPrimary = Color.White,
        primaryContainer = Color(accentSoft), onPrimaryContainer = Color(primary),
        secondary = Color(secondary), onSecondary = Color.White,
        secondaryContainer = Color(secondarySoft), onSecondaryContainer = Color(secondary),
        tertiary = Color(0xFF92400E), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFEF3C7), onTertiaryContainer = Color(0xFF78350F),
        error = Color(0xFFB83445), onError = Color.White,
        errorContainer = Color(0xFFFFF0F2), onErrorContainer = Color(0xFFB83445),
        background = Color(background), onBackground = Color(ink),
        surface = Color.White, onSurface = Color(ink),
        surfaceVariant = Color(background), onSurfaceVariant = Color(muted),
        outline = Color(muted), outlineVariant = Color(line),
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
    MaterialTheme(colorScheme = colors, typography = Typography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content)
}
