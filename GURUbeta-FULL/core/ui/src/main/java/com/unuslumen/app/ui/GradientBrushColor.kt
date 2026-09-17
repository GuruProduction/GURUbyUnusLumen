package com.unuslumen.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.unuslumen.app.ui.theme.guruTheme


fun gradientBrushColor(
    vararg colorStops: Pair<Float, Color> = arrayOf(
        0f to Color(0xFFDAA520),
        0.4f to Color(0xFFB8956A),
        1f to Color(0xFF8A6D3A),
    )
) = Brush.linearGradient(
    colorStops = colorStops,
    start = Offset.Zero,
    end = Offset.Infinite
)

@Preview
@Composable
private fun GradientColorPreview() {
    guruTheme {
        Box(
            Modifier
                .size(100.dp)
                .drawBehind {
                    drawRect(gradientBrushColor())
                }

        )
    }
}