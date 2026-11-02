// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.components.common

import androidx.compose.runtime.compositionLocalOf

/**
 * The way back out of a room, available to any screen without threading a
 * navigation controller through every signature.
 *
 * WHY THIS IS AMBIENT. Twenty screens already call [guruAppBar] with just a
 * title, and every one of them has a nav controller in scope but does not pass
 * it. Adding a parameter to each was the alternative, which is twenty edits for
 * one behaviour and twenty chances to miss one. A composition local means the
 * app root provides it once, and the bar renders a door on every screen that is
 * not the lobby or the portal, with nothing for a screen author to remember.
 *
 * A room GURU builds for itself inherits it the same way: provide nothing and the
 * bar simply draws no door, which is the honest default for a surface that has
 * nowhere to go back to.
 *
 * [canGoBack] is a lambda rather than a boolean so it reflects the live state at
 * composition time rather than a value captured when the host was built.
 */
data class RoomNavigation(
    val canGoBack: () -> Boolean,
    val goBack: () -> Unit,
)

/**
 * Null means "this surface has no door", which is correct for the lobby and for
 * the portal: both are home, and a back arrow there would either do nothing or
 * leave the app.
 */
val LocalRoomNavigation = compositionLocalOf<RoomNavigation?> { null }
