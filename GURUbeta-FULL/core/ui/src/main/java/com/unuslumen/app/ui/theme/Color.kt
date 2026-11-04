// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.theme

import androidx.compose.ui.graphics.Color

// THE LEGACY PALETTE IS GONE FROM THE CHAT SURFACE (issue 312).
//
// These values used to be a warm beige family — a muted slate-blue brand, a
// #F0E8DA card and a #EDE4D3 background — which was correct for the old Portal
// and wrong for Memphis. Beside Memphis.Paper (#FFFBF0) every one of them read
// as dirt on the page, and that is exactly what the owner reported: the chat
// bar and the thinking blocks rendering in a colour that does not match the
// rest of the app.
//
// The two files that had been migrated (AgentsScreen, AgentsIntroSheet) already
// drew from Memphis, so the app had two palettes in one screen. Rather than
// restyling components one at a time and leaving the next one to make the same
// mistake, the whole scheme is repointed here: everything that reads
// MaterialTheme.colorScheme inherits Memphis by construction.
//
// Only COLOUR changes. Shape, radius, elevation, hard shadows and spacing are
// untouched, because that is what was asked for.

val PrimaryColor = Memphis.Ink
val OnPrimary = Memphis.Paper
val SecondaryColor = Memphis.Purple
val TertiaryColor = Memphis.Teal
val DarkGray = Memphis.Ink

//val SurfaceGray = Color(0xFF121212)
val Red = Memphis.Coral
val Blue = Memphis.Cyan
val Green = Memphis.Teal
val Orange = Memphis.Sun
val Purple = Memphis.Purple

val Gray = Color(0xFF7E7979)
val LightGray = Color(0xFFECECEC)
val LightPurple = Color(0xFF743AD6)
val DarkOrange = Color(0xFFE84200)

/**
 * The two colours the whole chat surface is built from.
 *
 * They were #F0E8DA and #EDE4D3, a warm beige pair. Memphis draws on Paper with
 * Ink, and every surface token now points at those two so a card, a bar and a
 * page cannot disagree about what the page colour is.
 */
val LightCardColor = Memphis.Paper
val LightBackgroundColor = Memphis.Paper

val SuccessColor = Memphis.Teal

// Semantic colour tokens — the design system layer
// These map raw colours to their UI purpose so the entire app can be rethemed from here
data class GuruColors(
    val brand: Color,
    val brandSubtle: Color,
    val surfaceGlow: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val thinking: Color,
    val thinkingSubtle: Color,
    val toolRunning: Color,
    val toolSuccess: Color,
    val toolFailed: Color,
    val goldLabel: Color,
    val userLabel: Color,
    val chatGradientStart: Color,
    val chatGradientMid: Color,
    val chatGradientEnd: Color,
)

/**
 * The semantic layer, on Memphis.
 *
 * `thinking` WAS #9C27B0, a magenta, and it is what tinted the thinking label,
 * the icon and the streaming dot. Magenta belongs to no Memphis family, so it
 * read as a foreign colour dropped into a warm page. It is Teal now, which is
 * one of the eight poster inks and sits far enough from Coral and Purple to be
 * distinguishable from both.
 *
 * `goldLabel` and the three chatGradient values were the old gold family
 * (#DAA520, #B8956A, #8A6D3A). Gold was the Portal's accent and Memphis has no
 * gold; its warm ink is Sun. The gradient values are kept because call sites
 * still name them, but they now describe one Sun family rather than three
 * unrelated brass tones, so a gradient built from them reads as a single
 * saturated colour rather than a smear.
 */
val LightGuruColors = GuruColors(
    brand = Memphis.Ink,
    brandSubtle = Memphis.Ink.copy(alpha = 0.12f),
    surfaceGlow = Memphis.Ink.copy(alpha = 0.06f),
    success = Memphis.Teal,
    warning = Memphis.Sun,
    error = Memphis.Coral,
    info = Memphis.Cyan,
    thinking = Memphis.Teal,
    thinkingSubtle = Memphis.Teal.copy(alpha = 0.6f),
    toolRunning = Memphis.Sun,
    toolSuccess = Memphis.Teal,
    toolFailed = Memphis.Coral,
    goldLabel = Memphis.Sun,
    userLabel = Memphis.Ink,
    chatGradientStart = Memphis.Sun,
    chatGradientMid = Memphis.Sun.copy(alpha = 0.55f),
    chatGradientEnd = Memphis.Sun.copy(alpha = 0.2f),
)
