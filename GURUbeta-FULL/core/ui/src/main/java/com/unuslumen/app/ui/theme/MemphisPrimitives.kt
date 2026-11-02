// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.ui.R

/**
 * Archivo Black: the poster voice.
 *
 * Resolved through [androidx.compose.ui.text.font.Font] rather than
 * `fontResource`, which is a composable and cannot sit in a top-level val. Both
 * faces are already bundled in core:ui, so this adds no asset.
 */
val MemphisDisplay = FontFamily(Font(R.font.archivo_black))

/** Inter: names, counts, labels. The quiet counterweight to the display face. */
val MemphisBody = FontFamily(Font(R.font.inter))

/**
 * The poster's printed drop shadow.
 *
 * A real offset block in solid ink, drawn behind the surface, never a blur. A
 * blur reads as depth and light; a hard offset reads as print, which is the
 * whole grammar of this screen. The offset is a parameter because the hero uses
 * a bigger one than a row: the size of the drop signals importance before any
 * type is read.
 */
fun Modifier.hardShadow(
    offset: Dp = 4.dp,
    cornerRadius: Dp = 0.dp,
    color: Color = Memphis.Ink,
): Modifier = this.drawBehind {
    val push = offset.toPx()
    val corner = cornerRadius.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(push, push),
        size = Size(size.width, size.height),
        cornerRadius = CornerRadius(corner, corner),
    )
}

/**
 * The ink-outlined surface every Memphis object sits on.
 *
 * One place owns the border weight and the drop, so nothing can draw an
 * almost-black outline beside a black one, which is the tell that a design was
 * assembled rather than drawn.
 */
@Composable
fun MemphisSurface(
    modifier: Modifier = Modifier,
    color: Color = Memphis.Paper,
    borderWidth: Dp = 2.5.dp,
    cornerRadius: Dp = 0.dp,
    shadowOffset: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .hardShadow(offset = shadowOffset, cornerRadius = cornerRadius)
            .clip(shape)
            .background(color, shape)
            .border(borderWidth, Memphis.Ink, shape),
        content = content,
    )
}

/** A type style in the poster voice: heavy, tight tracking, uppercase by caller. */
fun posterStyle(
    sizeSp: Float,
    color: Color = Memphis.Ink,
    family: FontFamily = MemphisDisplay,
): TextStyle = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight.Black,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * 0.88f).sp,
    letterSpacing = (-sizeSp * 0.035f).sp,
    color = color,
)

/** A label in the printed-caption voice: small, spaced, uppercase. */
fun captionStyle(
    sizeSp: Float = 9.5f,
    color: Color = Memphis.Ink,
): TextStyle = TextStyle(
    fontFamily = MemphisBody,
    fontWeight = FontWeight.ExtraBold,
    fontSize = sizeSp.sp,
    letterSpacing = (sizeSp * 0.18f).sp,
    color = color,
)

/**
 * Confetti: the scattered shapes that stop a Memphis page being a spreadsheet.
 *
 * Drawn rather than shipped as a bitmap, because a bitmap is one fixed
 * arrangement forever and would not survive a different screen height. These
 * scale off the parent's own size, so the same set works on a small phone and a
 * large one.
 *
 * Deliberately sparse and behind everything: three marks on a screen read as
 * paper; twenty read as a party.
 */
@Composable
fun MemphisConfetti(modifier: Modifier = Modifier) {
    Box(modifier = modifier.drawBehind { drawConfetti() })
}

private fun DrawScope.drawConfetti() {
    val unit = size.minDimension / 100f
    if (unit <= 0f) return

    // Top right: a coral squiggle, the signature shape of the reference.
    val squiggle = Path().apply {
        moveTo(size.width - 28 * unit, 8 * unit)
        cubicTo(
            size.width - 22 * unit, 1 * unit,
            size.width - 16 * unit, 13 * unit,
            size.width - 9 * unit, 7 * unit,
        )
    }
    drawPath(squiggle, color = Memphis.Coral, style = Stroke(width = 2.2f * unit))

    // Left edge: a yellow ring with an ink outline.
    val ringCenter = Offset(3.5f * unit, 27 * unit)
    val ringRadius = 3.2f * unit
    drawCircle(Memphis.Yellow, ringRadius, ringCenter)
    drawCircle(Memphis.Ink, ringRadius, ringCenter, style = Stroke(width = 0.8f * unit))

    // Right edge: a cyan triangle, filled and outlined.
    val triangle = Path().apply {
        moveTo(size.width - 6 * unit, 32 * unit)
        lineTo(size.width - 1 * unit, 40 * unit)
        lineTo(size.width - 11 * unit, 40 * unit)
        close()
    }
    drawPath(triangle, color = Memphis.Cyan)
    drawPath(triangle, color = Memphis.Ink, style = Stroke(width = 0.8f * unit))
}

/** A small rotated square, used as a section mark. */
@Composable
fun MemphisSquareMark(color: Color, side: Dp = 9.dp, rotation: Float = 16f) {
    Box(
        Modifier
            .rotate(rotation)
            .size(side)
            .background(color)
            .border(1.5.dp, Memphis.Ink),
    )
}
