package com.unuslumen.app.presentation.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.unuslumen.app.ui.components.common.drawGradientRadial
import com.unuslumen.app.ui.theme.Blue
import com.unuslumen.app.ui.theme.DarkOrange
import com.unuslumen.app.ui.theme.LightPurple
import kotlin.random.Random

fun DrawScope.drawAiGradientRadials(
    background: Color,
    backgroundAlpha: Float = 0.75f,
    radius: Float = size.maxDimension * 0.8f
) {
    drawRect(background, Offset.Zero, size)
    drawGradientRadial(
        background
            .copy(alpha = backgroundAlpha)
            .compositeOver(Blue),
        Offset(0f, size.height * 0.9f),
        radius
    )
    drawGradientRadial(
        background
            .copy(alpha = backgroundAlpha)
            .compositeOver(DarkOrange),
        Offset(
            size.width * 1.1f,
            size.height
        ),
        radius
    )
    drawGradientRadial(
        background
            .copy(alpha = backgroundAlpha)
            .compositeOver(LightPurple),
        Offset(
            size.width * 1.1f,
            size.height * .1f,
        ),
        radius
    )
}