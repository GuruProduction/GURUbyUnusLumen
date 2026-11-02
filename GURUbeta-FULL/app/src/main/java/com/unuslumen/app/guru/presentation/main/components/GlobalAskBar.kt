// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.theme.Fraunces
import kotlin.math.roundToInt

/**
 * The GURU icon, floating over every screen that is not the portal or the lobby.
 *
 * WHAT IT IS: the way home, and nothing else. The lobby and the portal carry the
 * real chat bar; everywhere else gets this instead, because a full input bar on
 * every surface was the wrong shape for the job. Tapping the icon opens the
 * lobby, where the bar lives and the rooms are.
 *
 * IT CAN BE DRAGGED. The icon sits where it was last put, anywhere on screen,
 * because a fixed corner collides with real content: the notes list, the
 * calendar grid, the media shelf. The position is remembered for the session so
 * a human who parks it somewhere out of the way does not have to move it again
 * every time they open a screen.
 *
 * IT IS SMALL AND IT IS FAINT when idle. An always-present control that sits on
 * top of content has to earn its place: this one is half the size of a normal
 * button and dimmed, so it reads as a resident tool rather than a call to
 * action.
 */
@Composable
fun FloatingGuruIcon(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onHome: () -> Unit = {
        navController.navigate(Screen.LobbyScreen) {
            launchSingleTop = true
        }
    },
) {
    val density = LocalDensity.current
    val iconSize = 52.dp
    val iconSizePx = with(density) { iconSize.toPx() }

    // Where the icon has been put, in pixels, relative to its container.
    var position by remember { mutableStateOf<Offset?>(null) }
    var containerSize by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }

    // Travel is tracked so a drag never fires the tap. Without this, moving the
    // icon opens the lobby on the way, which makes it feel broken.
    val dragThresholdPx = with(density) { 8.dp.toPx() }

    // The container MUST fill the screen. Without a size of its own it measured
    // as a 52dp square, so the resting spot computed as 52 - 52 - 16, which is
    // negative, and the icon drew itself off the top-left corner where nobody
    // could see it.
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                containerSize = Offset(
                    coords.size.width.toFloat(),
                    coords.size.height.toFloat(),
                )
            },
    ) {
        val restingSpot = position ?: Offset(
            // First run: the right edge, ABOVE the chat bar's own control row.
            //
            // The bar carries a GURU button of its own, so parking the floating
            // icon at the very bottom put two GURU marks on top of each other.
            // This sits clear of the bar and of the gesture area.
            containerSize.x - iconSizePx - with(density) { 12.dp.toPx() },
            containerSize.y - iconSizePx - with(density) { 190.dp.toPx() },
        )

        Box(
            Modifier
                .offset {
                    IntOffset(restingSpot.x.roundToInt(), restingSpot.y.roundToInt())
                }
                .size(iconSize)
                .alpha(if (dragging) 1f else 0.82f)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val base = position ?: restingSpot
                            val moved = Offset(
                                (base.x + dragAmount.x).coerceIn(0f, (containerSize.x - iconSizePx).coerceAtLeast(0f)),
                                (base.y + dragAmount.y).coerceIn(0f, (containerSize.y - iconSizePx).coerceAtLeast(0f)),
                            )
                            position = moved
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    )
                }
                .pointerInput(dragThresholdPx) {
                    // A tap opens the lobby. The drag detector above consumes
                    // movement, so this only ever sees a genuine press.
                    detectTapGestures(onTap = { onHome() })
                },
        ) {
            Image(
                painter = painterResource(id = R.drawable.guru_icon),
                contentDescription = "GURU, open the lobby",
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
