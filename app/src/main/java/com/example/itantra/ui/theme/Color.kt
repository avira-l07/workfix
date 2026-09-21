package com.example.itantra.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Ported 1:1 from classic_corporate_system/DESIGN.md so the Compose screens match the
 * Stitch mockup exactly.
 */
object ITantraColors {
    val CanvasBg = Color(0xFFF8FAFC)
    val SurfaceWhite = Color(0xFFFFFFFF)
    val TextHeadline = Color(0xFF0F172A)
    val TextBody = Color(0xFF334155)
    val TextMuted = Color(0xFF64748B)
    val BorderSubtle = Color(0xFFE2E8F0)
    val BorderStrong = Color(0xFFCBD5E1)
    val Primary = Color(0xFF2563EB)
    val PrimaryContainer = Color(0xFF2563EB)
    val AccentHover = Color(0xFF1D4ED8)
    val AccentSubtle = Color(0xFFEFF6FF)
    val StatusSuccess = Color(0xFF059669)
    val StatusWarning = Color(0xFFD97706)
    val StatusDanger = Color(0xFFDC2626)
    val ErrorContainer = Color(0xFFFFDAD6)
    val OnErrorContainer = Color(0xFF93000A)
}

// Semantic Tactical Color Aliases (for backwards compatibility)
val TacticalBluePrimary = ITantraColors.Primary
val TacticalBlueContainer = ITantraColors.PrimaryContainer
val TacticalBlueLight = ITantraColors.AccentSubtle

val TacticalGreenSuccess = ITantraColors.StatusSuccess // Connected / Delivered / Ready
val TacticalGreenContainer = Color(0xFFD1FAE5)

val TacticalAmberWarning = ITantraColors.StatusWarning // Warning / Connecting / Retrying
val TacticalAmberContainer = Color(0xFFFEF3C7)

val TacticalRedEmergency = ITantraColors.StatusDanger // Emergency / Failure
val TacticalRedContainer = Color(0xFFFEE2E2)
val TacticalRedDark = Color(0xFF991B1B)

val TacticalGrayMuted = ITantraColors.TextMuted // Inactive / Unavailable
val TacticalGrayOutline = Color(0xFF737686)
val TacticalSurfaceDim = ITantraColors.BorderSubtle
val TacticalSurfaceBg = ITantraColors.CanvasBg
val TacticalTextHeadline = ITantraColors.TextHeadline
val TacticalTextBody = ITantraColors.TextBody

// Dark theme variants
val TacticalBlueDark = Color(0xFF3B82F6)
val TacticalGreenDark = Color(0xFF10B981)
val TacticalAmberDark = Color(0xFFF59E0B)
val TacticalRedDarkTheme = Color(0xFFEF4444)
val TacticalGrayDark = Color(0xFF94A3B8)
val TacticalDarkSurface = Color(0xFF0F172A)
val TacticalDarkSurfaceCard = Color(0xFF1E293B)
