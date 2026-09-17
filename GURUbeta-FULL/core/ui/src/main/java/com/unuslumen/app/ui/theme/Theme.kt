package com.unuslumen.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.unuslumen.app.preferences.domain.model.GuruTheme
import com.unuslumen.app.preferences.domain.model.LayoutConfig

val LocalGuruColors = compositionLocalOf { LightGuruColors }

private val LightColorPalette = lightColorScheme(
    primary = PrimaryColor,
    onPrimary = OnPrimary,
    secondary = SecondaryColor,
    tertiary = TertiaryColor,
    background = LightBackgroundColor,
    onBackground = DarkGray,
    onSurfaceVariant = DarkGray,
    surface = LightCardColor,
    surfaceTint = LightCardColor,
    surfaceVariant = LightCardColor,
    surfaceContainerHighest = LightCardColor,
    surfaceContainerLow = LightCardColor,
    surfaceContainerLowest = LightCardColor,
    surfaceContainer = LightCardColor,
    surfaceContainerHigh = LightCardColor,
    surfaceDim = LightCardColor,
    surfaceBright = LightCardColor
)

/**
 * CompositionLocal that provides the current LayoutConfig throughout the compose tree.
 * Access via `LocalLayoutConfig.current` in any composable.
 * Use the extension properties in LayoutConfigExt.kt for dp values.
 */
val LocalLayoutConfig = compositionLocalOf { LayoutConfig.DEFAULT }

@Composable
fun guruTheme(
    fontFamily: FontFamily = Rubik,
    fontSizeScale: Float = 1.0f,
    guruTheme: GuruTheme? = null,
    layoutConfig: LayoutConfig = LayoutConfig.DEFAULT,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = applyGuruThemeLight(guruTheme)
    val effectiveFontSizeScale = guruTheme?.fontSizeScale ?: fontSizeScale
    val effectiveFontFamily = guruTheme?.loadCustomFont(context) ?: fontFamily
    val typography = getTypography(effectiveFontFamily, effectiveFontSizeScale)
    val shapes = guruTheme?.let { theme ->
        androidx.compose.material3.Shapes(
            small = RoundedCornerShape((theme.cornerRadiusSmall ?: 4).dp),
            medium = RoundedCornerShape((theme.cornerRadiusMedium ?: 8).dp),
            large = RoundedCornerShape((theme.cornerRadiusLarge ?: 16).dp),
        )
    } ?: Shapes
    val guruColors = LightGuruColors
    CompositionLocalProvider(
        LocalGuruColors provides guruColors,
        LocalLayoutConfig provides layoutConfig
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = shapes,
        ) {
            content()
        }
    }
}

object GuruTheme {
    val colors: GuruColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGuruColors.current
}
