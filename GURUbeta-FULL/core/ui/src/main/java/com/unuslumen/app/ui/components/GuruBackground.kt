// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The app background. A flat colour, and nothing else.
 *
 * EVERY BACKGROUND IMAGE AND EVERY OVERLAY IS DELETED. No artwork, no wash, no
 * gradient, no tint, on any screen. The house colour is one flat value in one
 * place, so no page can differ from another and no page can reintroduce one.
 */
@Composable
fun GuruBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFFFFFBF0)),
    ) {
        content()
    }
}
