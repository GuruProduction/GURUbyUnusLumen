// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.components.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.random.Random

fun Modifier.singleGradientBackground(
    background: Color,
    gradientColor: Color,
    backgroundAlpha: Float = 0.75f
) = this.drawWithCache {
    val randomX = Random.nextFloat().coerceIn(0.3f, 0.7f)
    val randomY = Random.nextFloat().coerceIn(0.3f, 0.7f)
    val radius = size.maxDimension * 0.85f
    val gradientBrush = Brush.radialGradient(
        colors = listOf(
            background.copy(alpha = backgroundAlpha).compositeOver(gradientColor),
            Color.Transparent
        ),
        center = Offset(size.width * randomX, size.height * randomY),
        radius = radius
    )
    onDrawBehind {
        drawRect(background, Offset.Zero, size)
        drawRect(brush = gradientBrush)
    }
}

fun DrawScope.drawGradientRadial(
    color: Color,
    center: Offset,
    radius: Float = size.maxDimension * 0.75f
) = drawRect(
    brush = Brush.radialGradient(
        colors = listOf(
            color,
            Color.Transparent
        ),
        center = center,
        radius = radius,
    )
)