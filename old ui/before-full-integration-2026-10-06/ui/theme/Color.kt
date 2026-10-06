package com.example.itantra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Existing screen names now resolve to the current semantic theme. */
object ITantraColors {
    val CanvasBg: Color @Composable get() = MaterialTheme.colorScheme.background
    val SurfaceWhite: Color @Composable get() = MaterialTheme.colorScheme.surface
    val SurfaceVariant: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
    val TextHeadline: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val TextBody: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val TextMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val BorderSubtle: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
    val BorderStrong: Color @Composable get() = MaterialTheme.colorScheme.outline
    val Primary: Color @Composable get() = MaterialTheme.colorScheme.primary
    val PrimaryContainer: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val OnPrimary: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
    val AccentHover: Color @Composable get() = MaterialTheme.colorScheme.primary
    val AccentSubtle: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val StatusSuccess: Color @Composable get() = MaterialTheme.colorScheme.secondary
    val SuccessContainer: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
    val OnSuccess: Color @Composable get() = MaterialTheme.colorScheme.onSecondary
    val OnSuccessContainer: Color @Composable get() = MaterialTheme.colorScheme.onSecondaryContainer
    val StatusWarning: Color @Composable get() = MaterialTheme.colorScheme.tertiary
    val WarningContainer: Color @Composable get() = MaterialTheme.colorScheme.tertiaryContainer
    val OnWarning: Color @Composable get() = MaterialTheme.colorScheme.onTertiary
    val OnWarningContainer: Color @Composable get() = MaterialTheme.colorScheme.onTertiaryContainer
    val StatusDanger: Color @Composable get() = MaterialTheme.colorScheme.error
    val ErrorContainer: Color @Composable get() = MaterialTheme.colorScheme.errorContainer
    val OnError: Color @Composable get() = MaterialTheme.colorScheme.onError
    val OnErrorContainer: Color @Composable get() = MaterialTheme.colorScheme.onErrorContainer
}

val TacticalBluePrimary: Color @Composable get() = ITantraColors.Primary
val TacticalBlueContainer: Color @Composable get() = ITantraColors.PrimaryContainer
val TacticalBlueLight: Color @Composable get() = ITantraColors.AccentSubtle
val TacticalGreenSuccess: Color @Composable get() = ITantraColors.StatusSuccess
val TacticalGreenContainer: Color @Composable get() = ITantraColors.SuccessContainer
val TacticalAmberWarning: Color @Composable get() = ITantraColors.StatusWarning
val TacticalAmberContainer: Color @Composable get() = ITantraColors.WarningContainer
val TacticalRedEmergency: Color @Composable get() = ITantraColors.StatusDanger
val TacticalRedContainer: Color @Composable get() = ITantraColors.ErrorContainer
val TacticalRedDark: Color @Composable get() = ITantraColors.OnErrorContainer
val TacticalGrayMuted: Color @Composable get() = ITantraColors.TextMuted
val TacticalGrayOutline: Color @Composable get() = ITantraColors.BorderStrong
val TacticalSurfaceDim: Color @Composable get() = ITantraColors.BorderSubtle
val TacticalSurfaceBg: Color @Composable get() = ITantraColors.CanvasBg
val TacticalTextHeadline: Color @Composable get() = ITantraColors.TextHeadline
val TacticalTextBody: Color @Composable get() = ITantraColors.TextBody
