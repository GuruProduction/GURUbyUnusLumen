// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.navigation.Screen
import kotlin.math.roundToInt

/**
 * The GURU icon, floating over every screen that is not the portal or the lobby.
 *
 * WHAT IT IS: the way home. The lobby and the portal carry the real chat bar;
 * everywhere else gets this instead. Tapping it opens the lobby.
 *
 * WHERE IT LIVES. It used to be parked in the bottom-right and stayed wherever it
 * was last dropped, for the session. That made it a piece of furniture competing
 * with the content underneath it. It now has a home: top centre of the fold, just
 * under the status bar, which is a spot nothing else in the app uses and which
 * reads as a fixed resident mark rather than a button that wandered in.
 *
 * DRAGGING STILL WORKS, AND IT SPRINGS BACK. A person can pull it anywhere to get
 * it out of the way of what they are reading, and the moment they let go it snaps
 * straight home. He asked for a snap rather than a glide: the drag is the play,
 * the return is not a performance.
 *
 * IT CAN BE PINCHED, LIKE A STRETCH ARMSTRONG. Two fingers scale it live so it can
 * be squashed small or pulled long, and letting go restores its original shape. The
 * size is deliberately not remembered: the toy is the interaction, not a setting,
 * and an icon that stayed stretched would be a bug the next time it was looked at.
 *
 * ONE GESTURE HANDLER, AND WHY IT IS HAND-WRITTEN. Composition offers
 * detectTransformGestures, which handles pan and zoom but has no end callback, and
 * an icon that cannot notice the end of a gesture cannot snap back. This loops the
 * pointer events itself so the release is a real moment: movement only starts once
 * the finger has travelled past touch slop, nothing is consumed before that, which
 * is what keeps a plain tap arriving at the tap detector below.
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

    // How far the finger has pulled it from home, in pixels. Zero is home.
    var pull by remember { mutableStateOf(Offset.Zero) }
    // Live pinch scale. One is the original shape.
    var scale by remember { mutableStateOf(1f) }
    var containerSize by remember { mutableStateOf(Offset.Zero) }
    var interacting by remember { mutableStateOf(false) }

    // Status-bar inset, so "top" means the top of the visible fold rather than the
    // top of the glass, where the clock and the notch already are.
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .onGloballyPositioned { coords ->
                containerSize = Offset(
                    coords.size.width.toFloat(),
                    coords.size.height.toFloat(),
                )
            },
    ) {
        // HOME: horizontally centred, flush to the top of the visible area.
        val home = Offset(
            x = (containerSize.x - iconSizePx) / 2f,
            y = with(density) { 6.dp.toPx() },
        )

        // The pull is clamped so the icon can never be dragged off the glass and
        // lost; it can reach any edge but never past it.
        val drawn = Offset(
            (home.x + pull.x).coerceIn(0f, (containerSize.x - iconSizePx).coerceAtLeast(0f)),
            (home.y + pull.y).coerceIn(0f, (containerSize.y - iconSizePx).coerceAtLeast(0f)),
        )

        Box(
            Modifier
                .offset { IntOffset(drawn.x.roundToInt(), drawn.y.roundToInt()) }
                .size(iconSize)
                .alpha(if (interacting) 1f else 0.82f)
                // The pinch is drawn, never stored: scaleX and scaleY move together
                // so it reads as one rubber object rather than a distorted image.
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pastSlop = false
                        var travelled = Offset.Zero

                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()

                            if (!pastSlop) {
                                travelled += pan
                                // Touch slop is what separates a tap from a drag. Below
                                // it nothing is consumed, so the tap detector still gets
                                // a clean press and tapping opens the lobby.
                                if (travelled.getDistance() > viewConfiguration.touchSlop) {
                                    pastSlop = true
                                    interacting = true
                                }
                            }

                            if (pastSlop) {
                                if (zoom != 1f) {
                                    scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                }
                                if (pan != Offset.Zero) {
                                    pull += pan
                                }
                                event.changes.forEach { change ->
                                    if (change.positionChange() != Offset.Zero) change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        // THE RELEASE. Back to home and back to shape, in one frame,
                        // because he asked for a snap rather than a glide.
                        if (pastSlop) {
                            pull = Offset.Zero
                            scale = 1f
                            interacting = false
                        }
                    }
                }
                .pointerInput(Unit) {
                    // A plain tap opens the lobby. The handler above only consumes
                    // after slop, so a press that never moves arrives here intact.
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

/** How small a pinch may squash it before the icon stops being tappable. */
private const val MIN_SCALE = 0.55f

/** How far a pinch may stretch it, which is well past the point of being useful. */
private const val MAX_SCALE = 1.9f
