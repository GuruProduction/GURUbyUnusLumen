package com.unuslumen.app.ui.theme

import androidx.compose.ui.graphics.Color

// Grey slate-blue — replaces the old bright cyan brand. Muted, ink-like,
// reads as part of the Portal's warm paper world instead of neon on top of it.
val PrimaryColor = Color(0xFF5B7C99)
val OnPrimary = Color.White
val SecondaryColor = Color(0xFF5F12CA)
val TertiaryColor = SecondaryColor
val DarkGray = Color(0xFF2B241C)

//val SurfaceGray = Color(0xFF121212)
val Red = Color(0xFFD53A2F)
val Blue = Color(0xFF2965C9)
val Green = Color(0xFF1E9651)
val Orange = Color(0xFFE78A00)
val Purple = Color(0xFF6F4CAD)

val Gray = Color(0xFF7E7979)
val LightGray = Color(0xFFECECEC)
val LightPurple = Color(0xFF743AD6)
val DarkOrange = Color(0xFFE84200)

val LightCardColor = Color(0xFFF0E8DA)
val LightBackgroundColor = Color(0xFFEDE4D3)

val SuccessColor = Color(0xFF1E9651)

// Semantic colour tokens — the design system layer
// These map raw colours to their UI purpose so the entire app can be rethemed from here
data class GuruColors(
    val brand: Color,
    val brandSubtle: Color,
    val surfaceGlow: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val thinking: Color,
    val thinkingSubtle: Color,
    val toolRunning: Color,
    val toolSuccess: Color,
    val toolFailed: Color,
    val goldLabel: Color,
    val userLabel: Color,
    val chatGradientStart: Color,
    val chatGradientMid: Color,
    val chatGradientEnd: Color,
)

val LightGuruColors = GuruColors(
    brand = PrimaryColor,
    brandSubtle = PrimaryColor.copy(alpha = 0.12f),
    surfaceGlow = PrimaryColor.copy(alpha = 0.06f),
    success = Color(0xFF4CAF50),
    warning = Color(0xFFFFA726),
    error = Color(0xFFEF5350),
    info = Color(0xFF5B7C99),
    thinking = Color(0xFF9C27B0),
    thinkingSubtle = Color(0xFF9C27B0).copy(alpha = 0.6f),
    toolRunning = Color(0xFFFFA726),
    toolSuccess = Color(0xFF4CAF50),
    toolFailed = Color(0xFFEF5350),
    goldLabel = Color(0xFFDAA520),
    userLabel = Color(0xFF2B241C),
    chatGradientStart = Color(0xFFDAA520),
    chatGradientMid = Color(0xFFB8956A),
    chatGradientEnd = Color(0xFF8A6D3A),
)
