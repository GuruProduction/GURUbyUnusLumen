package com.unuslumen.app.ui.components.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquid


fun Modifier.frostedGlass(
    liquidState: LiquidState,
    shape: Shape = RoundedCornerShape(44.dp),
    frost: Dp = 5.dp
) = liquid(liquidState) {
    this.shape = shape
    refraction = 0.07f
    curve = 0.15f
    edge = 0.01f
    this.frost = frost
}

@Composable
fun Modifier.clearGlass(liquidState: LiquidState, shape: () -> Shape = { RoundedCornerShape(28.dp) }, edge: () ->Float = { 0.03f }) = liquid(liquidState) {
    this.shape = shape()
    refraction = 0.14f
    curve = 0.26f
    frost = 3.dp
    this.edge = edge()
    dispersion = 0.08f
}

fun Modifier.iconButtonGlass(liquidState: LiquidState, shape: Shape) = liquid(liquidState) {
    this.shape = shape
    refraction = 0.36f
    curve = 0.44f
    frost = 2.5.dp
    edge = 0.05f
    dispersion = 0.09f
}