// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.unuslumen.app.database.entity.MediaItemEntity
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The library's front door, in the house language.
 *
 * A grid of every saved item, newest first, with real poster art, the kind, the
 * duration and the date. Tapping opens the detail screen. Every value here comes
 * from a real row: no synthetic cells, no invented counts.
 *
 * WHY IT WAS REWRITTEN. It was a Material page: a small bold title, a rounded
 * search field, rounded cream cards with a drop shadow, all of which read as a
 * different product beside the lobby's printed page. The house grammar is a flat
 * saturated block, a hard ink edge, a printed drop and poster type, so a cell is
 * now an ink-outlined card carrying its own colour from the rotation.
 */
@Composable
fun MediaScreen(
    navController: NavHostController,
    viewModel: MediaViewModel = koinViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val isEmpty by viewModel.isEmpty.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = "Media",
                stamp = if (items.isEmpty()) null else "${items.size} items",
            )
        },
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            MediaSearchField(
                onQuery = { query -> viewModel.search(query) },
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
            )

            if (isEmpty) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "NO MEDIA YET\n" +
                            "Every video, audio file and image you share in chat lands here.",
                        style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)),
                        modifier = Modifier.padding(28.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp, start = 18.dp, end = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        MediaLibraryGridItem(
                            item = item,
                            ink = Memphis.inkFor(index),
                            onClick = { navController.navigate(Screen.MediaDetailScreen(mediaId = item.id)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One cell in the grid: the poster in a hard ink frame, the filename in the poster
 * face, and the facts that describe it as printed captions.
 */
@Composable
private fun MediaLibraryGridItem(
    item: MediaItemEntity,
    ink: Color,
    onClick: () -> Unit,
) {
    val poster = item.posterThumbPath.let { path ->
        if (path.isBlank() || !File(path).exists()) null else File(path)
    }
    val durationText = if (item.mediaKind == "image") "" else formatDuration(item.durationSeconds)

    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(ink)
                    .border(2.5.dp, Memphis.Ink)
            ) {
                if (poster != null) {
                    coil.compose.AsyncImage(
                        model = poster.absolutePath,
                        contentDescription = item.sourceFilename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = item.mediaKind.uppercase(),
                        style = captionStyle(9f, Memphis.Paper),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp)) {
                Text(
                    text = item.sourceFilename.take(26),
                    style = posterStyle(11f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(item.mediaKind.uppercase())
                        if (durationText.isNotEmpty()) {
                            append(" · ")
                            append(durationText)
                        }
                    },
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.6f)),
                    maxLines = 1,
                )
                Text(
                    text = SimpleDateFormat("d MMM yyyy", Locale.UK)
                        .format(Date(item.createdAt))
                        .uppercase(),
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.45f)),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The search field, drawn as a printed box rather than a rounded Material field.
 *
 * Its label sits above the box in the caption voice, because a printed page labels
 * a field on the rule rather than floating a placeholder into it.
 */
@Composable
private fun MediaSearchField(
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth()) {
        Text(
            text = "SEARCH FILENAMES, TRANSCRIPTS, OCR",
            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
            modifier = Modifier.padding(bottom = 5.dp),
        )
        MemphisSurface(
            modifier = Modifier.fillMaxWidth(),
            color = Memphis.Paper,
            borderWidth = 2.5.dp,
            shadowOffset = 3.dp,
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp)) {
                if (query.value.isEmpty()) {
                    Text(
                        text = "Type to search",
                        style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.35f)),
                    )
                }
                BasicTextField(
                    value = query.value,
                    onValueChange = { typed ->
                        query.value = typed
                        onQuery(typed)
                    },
                    singleLine = true,
                    textStyle = posterStyle(14f),
                    cursorBrush = SolidColor(Memphis.Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatDuration(sec: Double): String {
    val hours = TimeUnit.SECONDS.toHours(sec.toLong())
    val minutes = TimeUnit.SECONDS.toMinutes(sec.toLong() - TimeUnit.HOURS.toSeconds(hours))
    val seconds = sec.toLong() - TimeUnit.MINUTES.toSeconds(minutes) - TimeUnit.HOURS.toSeconds(hours)
    return when (hours > 0) {
        true -> "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        false -> "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
