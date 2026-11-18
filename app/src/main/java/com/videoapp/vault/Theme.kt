package com.videoapp.vault

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Violet = Color(0xFF6C4CF5)
val Pink = Color(0xFFE04FA3)
val BrandGradient = Brush.linearGradient(listOf(Violet, Pink))

private val LightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF22106B),
    secondary = Pink,
    secondaryContainer = Color(0xFFFFDDEE),
    onSecondaryContainer = Color(0xFF3D0628),
    background = Color(0xFFF8F7FC),
    surface = Color(0xFFF8F7FC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F0F9),
    surfaceContainer = Color(0xFFECE9F6),
    surfaceContainerHigh = Color(0xFFE6E2F2),
    surfaceContainerHighest = Color(0xFFE0DCEE),
    onSurface = Color(0xFF1B1A22),
    onSurfaceVariant = Color(0xFF64617A),
    outlineVariant = Color(0xFFD8D4E6),
    error = Color(0xFFD93A4A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4A3FF),
    onPrimary = Color(0xFF2A1580),
    primaryContainer = Color(0xFF3B2A99),
    onPrimaryContainer = Color(0xFFE7E0FF),
    secondary = Color(0xFFFF9ACD),
    secondaryContainer = Color(0xFF5C1A42),
    onSecondaryContainer = Color(0xFFFFDDEE),
    background = Color(0xFF0F0E17),
    surface = Color(0xFF0F0E17),
    surfaceContainerLowest = Color(0xFF0A0911),
    surfaceContainerLow = Color(0xFF17161F),
    surfaceContainer = Color(0xFF1C1B25),
    surfaceContainerHigh = Color(0xFF26252F),
    surfaceContainerHighest = Color(0xFF31303A),
    onSurface = Color(0xFFE8E6F0),
    onSurfaceVariant = Color(0xFFA9A6BC),
    outlineVariant = Color(0xFF3A3846),
    error = Color(0xFFFF8A94),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun KhoVideoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
