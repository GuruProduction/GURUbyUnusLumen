// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.JournalEntry
import com.unuslumen.app.ui.components.common.previewMarkdownTypography
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.util.date.fullDate
import com.mikepenz.markdown.m3.Markdown

/**
 * One entry as a ruled row, in the house language.
 *
 * THIS WAS A ROUNDED ELEVATED CARD. It now wears the same ink frame and printed drop
 * as every other row in the app, and the mood is a solid block of the entry's colour
 * with the mood's own mark printed on it, rather than a tinted emoticon floating on a
 * white field. The block is the thing the eye lands on, exactly as an agent's swatch
 * is, so a column of entries reads as a composed page.
 *
 * The title sets in the poster face, the body stays real Markdown, and the time is a
 * caption at the end of the header line, so a column of rows has a straight edge of
 * times down the right.
 */
@Composable
fun LazyItemScope.JournalEntryItem(
    modifier: Modifier = Modifier,
    entry: JournalEntry,
    ink: Color = Memphis.inkForName(entry.id),
    timeText: String = entry.createdDate.fullDate(LocalContext.current),
    onClick: (JournalEntry) -> Unit
) {
    MemphisSurface(
        modifier = modifier
            .fillMaxWidth()
            .animateItem()
            .clickable { onClick(entry) },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(26.dp).background(ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(entry.mood.iconRes),
                        contentDescription = stringResource(entry.mood.titleRes),
                        tint = Memphis.Ink,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = entry.title,
                    style = posterStyle(14f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = timeText.uppercase(),
                    style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                )
            }
            if (entry.content.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Markdown(
                    content = entry.content,
                    typography = previewMarkdownTypography()
                )
            }
        }
    }
}
