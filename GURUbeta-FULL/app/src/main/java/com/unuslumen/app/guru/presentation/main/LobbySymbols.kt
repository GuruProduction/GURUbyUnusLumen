// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A symbol for every room, drawn rather than shipped.
 *
 * WHY THESE ARE CANVAS PATHS AND NOT IMAGES. A tile grid needs a mark per room and a room
 * can be made at any moment, so a shipped PNG per room cannot exist: GURU builds rooms and
 * they have to be dressed the instant they appear. These are drawn from the ink colour at
 * whatever size the tile is, so a new room gets a symbol with no asset at all.
 *
 * THE LANGUAGE IS THE SAME AS THE REST OF THE PAGE: hard shapes, no rounded ends, strokes
 * at a constant weight and solid fills in one colour. Nothing here has a gradient, a blur
 * or a soft edge, because the page has none.
 *
 * A ROOM WITH NO SYMBOL OF ITS OWN GETS THE MARK. That is deliberate rather than lazy: a
 * room GURU made has no category yet, and inventing one for it would be guessing. The
 * generic mark says "a room" honestly.
 */
@Composable
fun LobbySymbol(
    key: String,
    ink: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 26.dp,
) {
    Canvas(modifier.size(size)) {
        when {
            key == "static:notes" -> noteMark(ink)
            key == "static:calendar" -> calendarMark(ink)
            key == "static:media" -> playMark(ink)
            key == "static:skills" -> sparkMark(ink)
            key == "static:agents" -> agentMark(ink)
            key == "static:ottio" -> marketMark(ink)
            key == "static:heartbeat" -> pulseMark(ink)
            key == "static:innermind" -> mindMark(ink)
            key == "static:thoughts" -> thoughtMark(ink)
            key == "static:settings" -> gearMark(ink)
            key == "static:journal" -> journalMark(ink)
            key == "static:projects" -> projectMark(ink)
            key == "static:portal" -> portalMark(ink)
            else -> roomMark(ink)
        }
    }
}

/** The unit every mark is drawn against, so one symbol cannot be finer than another. */
private fun DrawScope.unit(): Float = size.minDimension / 10f

/** Notes: three ruled lines with a margin, the page itself. */
private fun DrawScope.noteMark(ink: Color) {
    val u = unit()
    val stroke = 1.4f * u
    drawRect(
        color = ink,
        topLeft = Offset(1.6f * u, 1.2f * u),
        size = Size(6.8f * u, 7.6f * u),
        style = Stroke(width = stroke),
    )
    for (i in 0..2) {
        val y = (3.2f + i * 1.9f) * u
        drawLine(ink, Offset(2.8f * u, y), Offset(7.2f * u, y), strokeWidth = stroke * 0.8f)
    }
}

/** Calendar: a page with a bound top edge and one day marked solid. */
private fun DrawScope.calendarMark(ink: Color) {
    val u = unit()
    val stroke = 1.4f * u
    drawRect(
        color = ink,
        topLeft = Offset(1.2f * u, 2.2f * u),
        size = Size(7.6f * u, 6.6f * u),
        style = Stroke(width = stroke),
    )
    drawLine(ink, Offset(1.2f * u, 4.2f * u), Offset(8.8f * u, 4.2f * u), strokeWidth = stroke)
    drawLine(ink, Offset(3.2f * u, 1.2f * u), Offset(3.2f * u, 2.6f * u), strokeWidth = stroke)
    drawLine(ink, Offset(6.8f * u, 1.2f * u), Offset(6.8f * u, 2.6f * u), strokeWidth = stroke)
    drawRect(
        color = ink,
        topLeft = Offset(2.4f * u, 5.4f * u),
        size = Size(2.2f * u, 2.2f * u),
    )
}

/** Media: a solid triangle, the oldest mark there is for play. */
private fun DrawScope.playMark(ink: Color) {
    val u = unit()
    val triangle = Path().apply {
        moveTo(2.8f * u, 1.6f * u)
        lineTo(8.4f * u, 5f * u)
        lineTo(2.8f * u, 8.4f * u)
        close()
    }
    drawPath(triangle, ink)
}

/** Skills: a four-point burst, a thing that fires rather than a thing that sits. */
private fun DrawScope.sparkMark(ink: Color) {
    val u = unit()
    val c = 5f * u
    val spark = Path().apply {
        moveTo(c, 0.8f * u)
        lineTo(6.1f * u, 4.2f * u)
        lineTo(9.2f * u, c)
        lineTo(6.1f * u, 5.8f * u)
        lineTo(c, 9.2f * u)
        lineTo(3.9f * u, 5.8f * u)
        lineTo(0.8f * u, c)
        lineTo(3.9f * u, 4.2f * u)
        close()
    }
    drawPath(spark, ink)
}

/** Agents: one head and two shoulders, the mark for a mind you talk to. */
private fun DrawScope.agentMark(ink: Color) {
    val u = unit()
    drawCircle(ink, radius = 2.1f * u, center = Offset(5f * u, 3.4f * u))
    val shoulders = Path().apply {
        moveTo(1.4f * u, 9f * u)
        cubicTo(1.8f * u, 6.2f * u, 3.4f * u, 5.4f * u, 5f * u, 5.4f * u)
        cubicTo(6.6f * u, 5.4f * u, 8.2f * u, 6.2f * u, 8.6f * u, 9f * u)
        close()
    }
    drawPath(shoulders, ink)
}

/** Ottio: a stall with an awning, the mark for a market. */
private fun DrawScope.marketMark(ink: Color) {
    val u = unit()
    val stroke = 1.4f * u
    drawRect(
        color = ink,
        topLeft = Offset(1.4f * u, 3f * u),
        size = Size(7.2f * u, 5.6f * u),
        style = Stroke(width = stroke),
    )
    // Three stripes of the awning, drawn as teeth so it reads as a stall rather than a box.
    for (i in 0..2) {
        val x = (1.4f + i * 2.4f) * u
        val tooth = Path().apply {
            moveTo(x, 3f * u)
            lineTo((x + 1.2f * u), 3f * u)
            lineTo((x + 1.2f * u), 1.6f * u)
            lineTo(x, 2.4f * u)
            close()
        }
        drawPath(tooth, ink)
    }
}

/** Heartbeat: one sharp beat in a line, the chain's pulse. */
private fun DrawScope.pulseMark(ink: Color) {
    val u = unit()
    val stroke = 1.6f * u
    val pulse = Path().apply {
        moveTo(0.8f * u, 5f * u)
        lineTo(3.2f * u, 5f * u)
        lineTo(4.4f * u, 1.8f * u)
        lineTo(6.2f * u, 8.2f * u)
        lineTo(7.4f * u, 5f * u)
        lineTo(9.2f * u, 5f * u)
    }
    drawPath(pulse, ink, style = Stroke(width = stroke))
}

/** Inner mind: a net of nodes, which is what the room actually is. */
private fun DrawScope.mindMark(ink: Color) {
    val u = unit()
    val nodes = listOf(
        Offset(2f * u, 2f * u),
        Offset(8f * u, 2.6f * u),
        Offset(5f * u, 5f * u),
        Offset(1.8f * u, 8f * u),
        Offset(8.4f * u, 8f * u),
    )
    val stroke = 1.1f * u
    nodes.forEachIndexed { i, a ->
        nodes.drop(i + 1).forEach { b ->
            drawLine(ink, a, b, strokeWidth = stroke)
        }
    }
    nodes.forEach { drawCircle(ink, radius = 1.1f * u, center = it) }
}

/** Thoughts: three stacked sparks of ascending weight, an idea forming. */
private fun DrawScope.thoughtMark(ink: Color) {
    val u = unit()
    drawCircle(ink, radius = 1.5f * u, center = Offset(3f * u, 7.4f * u))
    drawCircle(ink, radius = 1.9f * u, center = Offset(6f * u, 4.8f * u))
    drawCircle(ink, radius = 2.3f * u, center = Offset(8.2f * u, 1.9f * u))
}

/** Settings: a gear reduced to a cog with four teeth, at this size that is enough. */
private fun DrawScope.gearMark(ink: Color) {
    val u = unit()
    val c = 5f * u
    drawCircle(ink, radius = 2.6f * u, center = Offset(c, c), style = Stroke(width = 1.4f * u))
    drawCircle(ink, radius = 1.1f * u, center = Offset(c, c))
    listOf(
        Offset(c, 0.8f * u) to Offset(c, 2.2f * u),
        Offset(c, 7.8f * u) to Offset(c, 9.2f * u),
        Offset(0.8f * u, c) to Offset(2.2f * u, c),
        Offset(7.8f * u, c) to Offset(9.2f * u, c),
    ).forEach { (a, b) -> drawLine(ink, a, b, strokeWidth = 1.6f * u) }
}

/** Journal: a bound book seen edge on. */
private fun DrawScope.journalMark(ink: Color) {
    val u = unit()
    val stroke = 1.4f * u
    drawRect(
        color = ink,
        topLeft = Offset(2.2f * u, 1.4f * u),
        size = Size(5.6f * u, 7.2f * u),
        style = Stroke(width = stroke),
    )
    drawLine(ink, Offset(4f * u, 1.4f * u), Offset(4f * u, 8.6f * u), strokeWidth = stroke)
}

/** Projects: three folders stacked, the work behind the work. */
private fun DrawScope.projectMark(ink: Color) {
    val u = unit()
    val stroke = 1.3f * u
    for (i in 0..2) {
        val inset = i * 1.1f * u
        drawRect(
            color = ink,
            topLeft = Offset((1.6f + inset) * u, (2f + inset) * u),
            size = Size((6.8f - inset * 1.4f) * u, (5.6f - inset * 1.2f) * u),
            style = Stroke(width = stroke),
        )
    }
}

/** Portal: an open door, which is what the conversation is. */
private fun DrawScope.portalMark(ink: Color) {
    val u = unit()
    val stroke = 1.4f * u
    val door = Path().apply {
        moveTo(2.4f * u, 9f * u)
        lineTo(2.4f * u, 1.6f * u)
        lineTo(7.6f * u, 1.6f * u)
        lineTo(7.6f * u, 9f * u)
    }
    drawPath(door, ink, style = Stroke(width = stroke))
    drawCircle(ink, radius = 0.7f * u, center = Offset(6.2f * u, 5.2f * u))
}

/** The generic room: one square, one mark. No category invented for a room that has none. */
private fun DrawScope.roomMark(ink: Color) {
    val u = unit()
    drawRect(
        color = ink,
        topLeft = Offset(2f * u, 2f * u),
        size = Size(6f * u, 6f * u),
        style = Stroke(width = 1.5f * u),
    )
    drawRect(
        color = ink,
        topLeft = Offset(4f * u, 4f * u),
        size = Size(2f * u, 2f * u),
    )
}
