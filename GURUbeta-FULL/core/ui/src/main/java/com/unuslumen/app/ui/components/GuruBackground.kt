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
 * THE app background, on every single page: the artwork under the house wash.
 *
 * THE WASH IS THE PORTAL'S, EXACTLY. Aged cream at 0.75 falling to warm bone at
 * 0.55, the same two colours at the same two alphas. It is what makes black text
 * readable over the collage, and it is drawn here once so every page gets the
 * same treatment rather than each screen inventing its own.
 *
 * ONE IMAGE, ONE PLACE. The drawable is `portal_bg_art`, which is the single
 * resource the app has for this. No screen paints its own background any more:
 * they were all deleted, so every page inherits this and a new screen, including
 * the rooms GURU builds, gets it without doing anything.
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
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF0E8DA).copy(alpha = 0.92f),
                            Color(0xFFE4D9C6).copy(alpha = 0.86f),
                        )
                    )
                )
        )
        content()
    }
}
