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
 * The app's background, on every page.
 *
 * ONE PLACE, DRAWN AT THE ROOT. The human's rule is that every single page comes
 * with this backdrop as standard, and "standard" has to mean structural: if each
 * screen opted in by drawing its own copy, every new screen, including the rooms
 * GURU builds for itself, would be the one that forgot.
 *
 * So this is drawn once behind the whole app. A screen that wants a different
 * backdrop can paint over it; a screen that says nothing inherits it, which is
 * the correct default.
 *
 * THE WASH IS PART OF THE COMPONENT. The artwork is a dense black line drawing,
 * and black text over unbroken black ink is unreadable. The paper wash is baked
 * in here rather than left to each screen to remember, so a page cannot be
 * accidentally unreadable by forgetting it.
 *
 * The drawable is bundled, so a missing one is a build fault rather than a
 * runtime condition; the wash below still keeps the page readable either way.
 */
@Composable
fun GuruBackground(
    modifier: Modifier = Modifier,
    artAlpha: Float = 0.34f,
    washAlpha: Float = 0.78f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.fillMaxSize()) {
        // painterResource is a composable, so it cannot sit inside runCatching.
        // The resource is a bundled drawable, so its failure is a build fault
        // rather than a runtime condition, and the app drawing it directly is
        // the honest behaviour.
        Image(
            painter = painterResource(id = R.drawable.portal_bg_art),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = artAlpha,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFFFFBF0).copy(alpha = washAlpha * 0.72f),
                            Color(0xFFFFFBF0).copy(alpha = washAlpha),
                        )
                    )
                )
        )
        content()
    }
}
