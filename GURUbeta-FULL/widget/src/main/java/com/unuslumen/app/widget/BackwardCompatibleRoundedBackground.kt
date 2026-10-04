// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.widget

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import com.unuslumen.app.ui.R

@Composable
fun GlanceModifier.largeBackgroundBasedOnVersion() =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        background(
            ImageProvider(R.drawable.large_item_rounded_corner_shape),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.secondaryContainer)
        )
    } else {
        background(GlanceTheme.colors.secondaryContainer)
            .cornerRadius(25.dp)
    }

@Composable
fun GlanceModifier.largeInnerBackgroundBasedOnVersion() =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        background(
            imageProvider = ImageProvider(R.drawable.large_inner_item_rounded_corner_shape),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondary)
        )
    } else {
        background(GlanceTheme.colors.onSecondary)
            .cornerRadius(17.dp)
    }

@Composable
fun GlanceModifier.smallBackgroundBasedOnVersion() =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        background(
            imageProvider = ImageProvider(R.drawable.small_item_rounded_corner_shape),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.secondaryContainer)
        )
    } else {
        background(GlanceTheme.colors.secondaryContainer)
            .cornerRadius(16.dp)
    }