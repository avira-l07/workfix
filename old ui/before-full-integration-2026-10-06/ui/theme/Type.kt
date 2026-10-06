package com.example.itantra.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun textStyle(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp,
)

val Typography = Typography(
    headlineLarge = textStyle(28, 34, FontWeight.Bold).copy(letterSpacing = (-0.4).sp),
    headlineMedium = textStyle(24, 30, FontWeight.SemiBold),
    titleLarge = textStyle(20, 26, FontWeight.SemiBold),
    titleMedium = textStyle(16, 22, FontWeight.SemiBold),
    titleSmall = textStyle(14, 20, FontWeight.SemiBold),
    bodyLarge = textStyle(15, 23), bodyMedium = textStyle(14, 22), bodySmall = textStyle(12, 18),
    labelLarge = textStyle(13, 18, FontWeight.Medium),
    labelMedium = textStyle(12, 16, FontWeight.Medium),
    labelSmall = textStyle(11, 16, FontWeight.Medium),
)
