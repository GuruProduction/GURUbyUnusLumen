// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProviders
import com.unuslumen.app.ui.theme.DarkGray
import com.unuslumen.app.ui.theme.LightGray

val widgetDarkColorScheme = darkColorScheme(
    secondaryContainer = Color(0xFF090909),
    onSecondary = Color(0xFF1A1A1A),
    onSecondaryContainer = Color.White,
    secondary = Color.LightGray
)
val widgetLightColorScheme = darkColorScheme(
    secondaryContainer = Color.White,
    onSecondary = LightGray,
    onSecondaryContainer = Color.Black,
    secondary = DarkGray
)

@Composable
fun WidgetTheme(colors: ColorProviders, content: @Composable () -> Unit) {
    GlanceTheme(
        colors = colors
    ) {
        content()
    }
}