// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.unuslumen.app.ui.R

/**
 * The house background: the doodle, washed back so text stays readable.
 *
 * THE WASH IS THE PORTAL'S, EXACTLY. These are the same two colours at the same
 * two alphas the portal has used since it was built, because the human asked for
 * the lobby and every room to look like the portal. Changing either number here
 * changes every page at once, which is the point: a background that differs
 * screen to screen is what made the app look assembled rather than designed.
 *
 * A LIGHT WASH, NOT A DARK ONE. An earlier version of this component used a
 * heavier, warmer wash that read almost grey and fought the chat messages on top
 * of it. The portal's lighter treatment is the reference.
 */
@Composable
fun GuruBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.portal_bg_art),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // The portal's own wash, unchanged: aged cream at 0.75 falling to warm
        // bone at 0.55. Copied rather than approximated.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF0E8DA).copy(alpha = 0.75f),
                            Color(0xFFE4D9C6).copy(alpha = 0.55f),
                        )
                    )
                )
        )
        content()
    }
}
