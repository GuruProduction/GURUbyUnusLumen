// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ottio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle

/**
 * One agent in full, before a person commits anything.
 *
 * WHY THIS IS A SHEET AND NOT ANOTHER ROUTE. Going back from a detail should land
 * you where you were in the list, not at the top of it, and a sheet keeps the
 * scroll position behind it. It also makes the decision feel like one surface
 * rather than a page you can wander away from.
 *
 * THE SHAPE IS THE FIX FOR THE OLD FAILURE. It fits inside one screen because the
 * body scrolls while the title and the price stay put, so the way out is never
 * scrolled off past the fold, which is what happened to the agents intro.
 */
@Composable
fun OttioDetailSheet(
    detail: MarketDetail,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onRate: (Int, String?) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            // The scrim shows the page behind it rather than hiding it.
            .background(Memphis.Ink.copy(alpha = 0.34f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MemphisSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // A HARD CEILING, so it can never run past the fold. The body
                // inside scrolls; the masthead and the price do not.
                .heightIn(max = 620.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            color = Memphis.Paper,
            borderWidth = 3.dp,
            shadowOffset = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                // --- fixed head ---
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 15.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            detail.entry.name.uppercase(),
                            style = posterStyle(27f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        if (detail.entry.priceCents > 0) {
                            Box(Modifier.background(Memphis.Coral).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(
                                    MarketViewModel.priceLabel(detail.entry.priceCents),
                                    style = captionStyle(10f, Memphis.Paper),
                                )
                            }
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 7.dp)
                            .height(3.dp)
                            .background(Memphis.Ink),
                    )
                }

                // --- the body scrolls ---
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                ) {
                    if (detail.entry.roleDescription.isNotBlank()) {
                        Text(
                            detail.entry.roleDescription,
                            style = ottioBodyStyle(),
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // Real numbers, or nothing at all.
                    StatsRow(detail)
                    Spacer(Modifier.height(12.dp))

                    Fact("MADE BY", detail.entry.maker?.displayName ?: "Unknown maker")
                    Fact("CATEGORY", detail.entry.category.replaceFirstChar { it.uppercase() })
                    Fact("VERSION", detail.entry.publishedVersion?.toString() ?: "Not published yet")
                    Fact(
                        "CHECKED",
                        if (detail.entry.isBuiltin) "Ships with GURU" else "Reviewed before going live",
                    )

                    Spacer(Modifier.height(12.dp))
                    MarketSectionBand(
                        label = "What it may use",
                        count = detail.entry.tools.size,
                    )
                    if (detail.entry.tools.isEmpty()) {
                        Text(
                            "No tools. It reasons and answers without touching anything.",
                            style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        detail.entry.tools.forEach { tool ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(tool, style = captionStyle(9.5f))
                            }
                            Box(Modifier.fillMaxWidth().height(1.5.dp).background(Memphis.Ink.copy(alpha = 0.14f)))
                        }
                    }

                    if (detail.reviews.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        MarketSectionBand(label = "What people said", count = detail.reviews.size)
                        detail.reviews.take(5).forEach { review ->
                            Column(Modifier.padding(top = 9.dp)) {
                                Text(
                                    "★".repeat(review.stars) + "☆".repeat(5 - review.stars),
                                    style = posterStyle(12f, Memphis.Sun),
                                )
                                review.comment?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.75f)),
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                }

                // --- the fixed foot: the price and the action, always visible ---
                Box(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .background(if (detail.entry.priceCents > 0) Memphis.Ink else Memphis.Paper)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            MarketViewModel.priceLabel(detail.entry.priceCents),
                            style = posterStyle(17f, if (detail.entry.priceCents > 0) Memphis.Paper else Memphis.Ink),
                        )
                    }
                    Box(Modifier.width(3.dp).height(48.dp).background(Memphis.Ink))
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (detail.entry.installed) Memphis.Paper else Memphis.Ink)
                            .clickable(enabled = !detail.entry.installed && !detail.installing) { onInstall() }
                            .padding(vertical = 15.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when {
                                detail.entry.installed -> "In your agents"
                                detail.installing -> "Adding…"
                                detail.entry.priceCents > 0 -> "Buy and add"
                                else -> "Get it"
                            }.uppercase(),
                            style = captionStyle(10.5f, if (detail.entry.installed) Memphis.Ink else Memphis.Paper),
                        )
                    }
                }
            }
        }
    }
}

/** Installs and the real average, shown only where they are real. */
@Composable
private fun StatsRow(detail: MarketDetail) {
    val stats = detail.stats
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        StatCard(
            value = stats?.installs?.toString() ?: "—",
            label = if (stats?.installs == 1L) "INSTALL" else "INSTALLS",
            modifier = Modifier.weight(1f),
            ink = Memphis.Yellow,
        )
        StatCard(
            // A rating with no raters shows a dash, never a zero, because zero
            // stars would read as a bad agent rather than an unrated one.
            value = stats?.ratingAverage?.let { "%.1f".format(it) } ?: "—",
            label = stats?.ratingCount?.let { "$it RATED" } ?: "NOT RATED",
            modifier = Modifier.weight(1f),
            ink = Memphis.Paper,
        )
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier, ink: androidx.compose.ui.graphics.Color) {
    Column(
        modifier
            .background(ink)
            .border(2.5.dp, Memphis.Ink)
            .padding(10.dp),
    ) {
        Text(value, style = posterStyle(20f))
        Text(label, style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.6f)), modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun Fact(key: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)))
        Text(value, style = ottioBodyStyle().copy(fontSize = 10.5.sp, lineHeight = 14.sp))
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(Memphis.Ink))
}
