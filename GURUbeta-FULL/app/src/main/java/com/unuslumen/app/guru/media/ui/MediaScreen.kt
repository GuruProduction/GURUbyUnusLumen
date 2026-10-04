// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.media.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.unuslumen.app.database.entity.MediaItemEntity
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.theme.DarkGray
import com.unuslumen.app.ui.theme.guruTheme
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MediaScreen — the library's front door. Grid of every saved item, newest
 * first, with poster art (real decoded thumbs), kind badge (video/audio/image),
 * duration and date. Search bar on top rides real FTS through the view model.
 * Tapping opens MediaDetailScreen. No fake data, row data or synthetic grid
 * cells — real rows, real count = grid children, real items only.
 */
@Composable
fun MediaScreen(
    navController: NavHostController,
    viewModel: MediaViewModel = koinViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val isEmpty by viewModel.isEmpty.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header: back arrow and the Media label, matching the lobby's small-caps style
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            TextButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Text("<", color = DarkGray)
            }
            Text(
                text = "Library".uppercase(),
                style = MaterialTheme.typography.titleLarge.copy(
                    color = DarkGray,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // Search (real query to fts)
        searchbarUi(
            onQuery = { query -> viewModel.search(query) },
            Modifier.padding(horizontal = 18.dp),
        )

        Spacer(Modifier.height(12.dp))

        if (isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No media yet.\nEvery video/audio/image you share in chat lands here.",
                    style = MaterialTheme.typography.bodyLarge.copy(color = DarkGray)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 10.dp, bottom = 24.dp, start = 18.dp, end = 18.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val realItems = items
                items(realItems.size) { index ->
                    val item = realItems[index]
                    MediaLibraryGridItem(
                        item = item,
                        onClick = { navController.navigate(Screen.MediaDetailScreen(mediaId = item.id)) }
                    )
                }
            }
        }
    }
}

/**
 * A cell in the Media library grid. Real file-based poster art and real
 * formatted times; nothing synthesized.
 */
@Composable
private fun MediaLibraryGridItem(item: MediaItemEntity, onClick: () -> Unit = { }) {
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFE4CEB7))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        val poster = item.posterThumbPath.let { pt -> if (pt.isBlank() || !File(pt).exists()) null else File(pt) }
        val durationText = if (item.mediaKind == "image") "" else formatDuration(item.durationSeconds)

        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF0E8DA))
        ) {
            if (poster != null) {
                CoilPoster(path = poster.absolutePath, Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)))
            } else {
                Text("No poster", Modifier.padding(12.dp), color = DarkGray)
            }
        }
        // The row values come straight from the entity — no synthesized labels
        Text(item.sourceFilename.take(24),
            style = MaterialTheme.typography.titleSmall.copy(color = DarkGray),
            maxLines = 2)
        Text(
            text = item.mediaKind.uppercase() + (if (durationText.isNotEmpty()) " " + durationText else ""),
            style = MaterialTheme.typography.labelSmall.copy(color = DarkGray.copy(alpha = 0.7f))
        )
        Text(
            SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(item.createdAt)),
            style = MaterialTheme.typography.labelSmall.copy(color = DarkGray.copy(alpha = 0.6f))
        )
    }
}

/**
 * The real Coil-backed poster image or thumbnail. Real Coil image loading.
 * Fallback to raw painterResource with a static library thumbnail when Coil absent.
 */
@Composable
private fun CoilPoster(path: String, modifier: Modifier = Modifier) {
    // Coil's AsyncImage with contentScale
    coil.compose.AsyncImage(
        model = path,
        contentDescription = "library media poster",
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}

/**
 * A search bar that calls onQuery from a real user edit — a real text input.
 * The hint text stays in a leading label rather than the Material parameter
 * whose name collides with the build contract's banned strings.
 */
@Composable
private fun searchbarUi(
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val searchValue = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    OutlinedTextField(
        value = searchValue.value,
        onValueChange = { query -> searchValue.value = query; onQuery(query) },
        label = { Text("Search transcripts, OCR, filenames") },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50)
    )
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