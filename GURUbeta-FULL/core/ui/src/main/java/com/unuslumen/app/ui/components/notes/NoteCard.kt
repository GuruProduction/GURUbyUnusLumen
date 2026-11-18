// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.components.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.ui.components.common.previewMarkdownTypography
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.util.date.formatDateDependingOnDay
import com.mikepenz.markdown.m3.Markdown

/**
 * One note, in the house language.
 *
 * A ruled card on paper with a hard ink edge and a printed drop, carrying the title
 * in the poster face, the body as markdown, and the date as a printed caption. A
 * pinned note gets an ink corner block rather than a coloured pin icon, because
 * nothing on a printed page is a rounded glyph.
 */
@Composable
fun NoteCard(
    modifier: Modifier = Modifier,
    note: Note,
    onClick: (Note) -> Unit,
) {
    val context = LocalContext.current
    val formattedDate by remember(note.updatedDate) {
        derivedStateOf { note.updatedDate.formatDateDependingOnDay(context) }
    }

    MemphisSurface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(note) },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (note.pinned) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Memphis.inkForName(note.title.ifBlank { note.id.toString() }))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text("PINNED", style = captionStyle(8f, Memphis.Ink))
                }
            }

            Column(Modifier.fillMaxWidth().padding(11.dp)) {
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    style = posterStyle(13f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Markdown(
                    content = note.content,
                    typography = previewMarkdownTypography(),
                )
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formattedDate.uppercase(),
                        style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.5f)),
                    )
                }
            }
        }
    }
}
