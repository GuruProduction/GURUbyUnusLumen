// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.unuslumen.app.ui.R

/**
 * THE app background, on every single page.
 *
 * The supplied artwork, at full strength, edge to edge. No wash, no gradient, no
 * overlay, no tint: the previous attempts all failed for the same reason, which
 * is that each one invented a correction the image did not need. The image is
 * already pale enough to read black text over, so it is drawn exactly as it is.
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
        content()
    }
}
