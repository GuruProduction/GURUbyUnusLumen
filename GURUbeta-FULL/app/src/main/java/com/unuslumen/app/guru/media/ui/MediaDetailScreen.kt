// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.media.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.unuslumen.app.guru.media.SceneSerialization
import com.unuslumen.app.guru.media.TranscriptChunkSer
import com.unuslumen.app.guru.media.TranscriptSerialization
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBodyText
import com.unuslumen.app.ui.theme.MemphisSheet
import com.unuslumen.app.ui.theme.MemphisSheetButton
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One library item, in the house language.
 *
 * WHAT IT WAS. A Material detail page: a text-button back arrow, a bold title in the
 * Material type ramp, rounded cream keyframe thumbnails, a rounded zoom panel and a
 * Material confirm dialog. It carried real data correctly and looked like a different
 * product, which is the whole fault this pass exists to remove.
 *
 * WHAT IT IS NOW. The masthead every room wears, an ink-framed poster panel carrying
 * the item's own colour, keyframe rows as ruled cards with the frame in a hard ink
 * frame, and a delete confirmation on the same printed sheet as every other form.
 *
 * Every call underneath is unchanged: the same load, the same real file paths, the same
 * share intent, the same delete.
 */
@Composable
fun MediaDetailScreen(
    mediaId: String,
    navController: NavHostController,
    viewModel: MediaViewModel = koinViewModel(),
) {
    LaunchedEffect(mediaId) {
        viewModel.loadDetail(mediaId)
    }
    val itemRow by viewModel.detailItem.collectAsState()
    val current = itemRow

    var showDeleteDialog by remember { mutableStateOf(false) }
    var displayFullScreen by remember { mutableStateOf<File?>(null) }

    if (current == null) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { guruAppBar(title = "Media") },
        ) { paddingValues ->
            Box(
                Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text("ITEM NOT FOUND", style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)))
            }
        }
        return
    }

    val itemRowLoaded = current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = itemRowLoaded.mediaKind,
                stamp = if (itemRowLoaded.durationSeconds > 0) {
                    formatDurationLabel(itemRowLoaded.durationSeconds)
                } else {
                    null
                },
            )
        },
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Text(
                text = itemRowLoaded.sourceFilename,
                style = posterStyle(18f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = detailMetaLabel(itemRowLoaded),
                style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
            )
            Spacer(Modifier.height(12.dp))

            val keyframeList = remember(itemRowLoaded.scenesJson) {
                SceneSerialization.jsonToList(itemRowLoaded.scenesJson)
            }
            val chunkList = remember(itemRowLoaded.transcriptJson) {
                TranscriptSerialization.jsonToList(itemRowLoaded.transcriptJson)
            }

            if (keyframeList.isEmpty() && chunkList.isEmpty() && itemRowLoaded.mediaKind != "video") {
                Text(
                    text = "NO KEYFRAMES SAVED FOR THIS ITEM.",
                    style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.5f)),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (keyframeList.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp)
                        .background(Memphis.Ink)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("KEYFRAMES", style = captionStyle(9.5f, Memphis.Paper))
                    Text(
                        text = "${keyframeList.size}",
                        style = captionStyle(8f, Memphis.Ink),
                        modifier = Modifier.background(Memphis.Paper).padding(horizontal = 6.dp),
                    )
                }
            }

            keyframeList.forEachIndexed { index, keyframe ->
                KeyframeStripRow(
                    path = File(keyframe.keyframePath),
                    timeRange = "%.1fs-%.1fs".format(keyframe.startSec, keyframe.endSec),
                    ocrText = keyframe.ocrText.ifBlank { "" },
                    ink = Memphis.inkFor(index),
                ) {
                    displayFullScreen = File(keyframe.keyframePath)
                }
            }

            displayFullScreen?.let { zoomFile ->
                Spacer(Modifier.height(10.dp))
                if (zoomFile.exists()) {
                    MemphisSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .clickable { displayFullScreen = null },
                        color = Memphis.Paper,
                        borderWidth = 3.dp,
                        shadowOffset = 6.dp,
                    ) {
                        AsyncImage(
                            model = zoomFile,
                            contentDescription = "media strip full-res zoom",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                } else {
                    Text(
                        text = "THE CHOSEN FRAME COULD NOT LOAD.",
                        style = captionStyle(9f, Memphis.Coral),
                    )
                }
            }

            if (chunkList.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 8.dp)
                        .background(Memphis.Ink)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("TRANSCRIPT", style = captionStyle(9.5f, Memphis.Paper))
                    Text(
                        text = "${chunkList.size}",
                        style = captionStyle(8f, Memphis.Ink),
                        modifier = Modifier.background(Memphis.Paper).padding(horizontal = 6.dp),
                    )
                }
            }

            chunkList.forEach { chunkRow ->
                ChunkTextUI(chunkRow)
            }

            Spacer(Modifier.height(22.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MemphisSurface(
                    modifier = Modifier.weight(1f).clickable {
                        val shareCtx = org.koin.core.context.GlobalContext.get().get<android.content.Context>()
                        val theFile = sourceBytesFileForDisplay(itemRowLoaded)
                        if (theFile != null && theFile.exists()) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                shareCtx,
                                "${shareCtx.packageName}.fileprovider",
                                theFile
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND)
                            shareIntent.type = mediaMimeTypeOf(itemRowLoaded)
                            shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
                            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            shareCtx.startActivity(
                                Intent.createChooser(shareIntent, "Share media").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    },
                    color = Memphis.Paper,
                    borderWidth = 2.5.dp,
                    shadowOffset = 4.dp,
                ) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("SHARE", style = captionStyle(9.5f))
                    }
                }
                MemphisSurface(
                    modifier = Modifier.weight(1f).clickable { showDeleteDialog = true },
                    color = Memphis.Coral,
                    borderWidth = 2.5.dp,
                    shadowOffset = 4.dp,
                ) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("DELETE", style = captionStyle(9.5f))
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showDeleteDialog) {
        MemphisSheet(
            title = "Delete item?",
            subtitle = "The row, its files, its keyframes and the zoomed-frame rows all go.",
            onDismiss = { showDeleteDialog = false },
            accent = Memphis.Coral,
            footer = {
                MemphisSheetButton(
                    label = "Cancel",
                    onClick = { showDeleteDialog = false },
                )
                MemphisSheetButton(
                    label = "Delete",
                    filled = true,
                    ink = Memphis.Coral,
                    onClick = {
                        viewModel.deleteItem(itemRowLoaded.id)
                        showDeleteDialog = false
                        navController.popBackStack()
                    },
                )
            },
            body = {
                Text(
                    text = "This cannot be undone.",
                    style = MemphisBodyText,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            },
        )
    }
}

private fun formatDurationLabel(secs: Double): String {
    val minutes = (secs / 60).toInt()
    val seconds = (secs % 60).toInt()
    return "%d:%02d".format(minutes, seconds)
}

/** The stored source bytes file for one media element share — never a fake path. */
private fun sourceBytesFileForDisplay(itemRow: com.unuslumen.app.database.entity.MediaItemEntity): File? {
    val storedDir = File(itemRow.storedPath)
    val originalSourcePath = File(itemRow.cachedPath)
    if (storedDir.isDirectory) {
        val storedBinary = storedDir.listFiles()?.firstOrNull { isStoredBinary(it) }
        if (storedBinary?.isFile == true && storedBinary.length() > 0L) return storedBinary
    }
    if (originalSourcePath.exists() && originalSourcePath.length() > 0L) return originalSourcePath
    if (storedDir.exists() && storedDir.length() in 1..50_000_000L && !storedDir.isDirectory) return storedDir
    if (!storedDir.isDirectory && storedDir.isFile && storedDir.length() > 0L) return storedDir
    return null
}

/** Share's binary test: no sidecar (transcript.json, scenes.json, poster). */
private fun isStoredBinary(f: File): Boolean {
    if (!f.isFile) return false
    return f.name !in setOf("transcript.json", "scenes.json", "poster.jpg")
}

private fun mediaMimeTypeOf(itemRow: com.unuslumen.app.database.entity.MediaItemEntity): String =
    itemRow.mimeType.ifBlank { "application/octet-stream" }

private fun detailMetaLabel(itemRow: com.unuslumen.app.database.entity.MediaItemEntity): String {
    val created = SimpleDateFormat("d MMM yyyy HH:mm", Locale.UK).format(Date(itemRow.createdAt))
    val duration = itemRow.takeIf { it.mediaKind != "image" }
        ?.let { r -> if (r.durationSeconds > 0.0) r.durationSeconds else null }
        ?.let { secs ->
            " | %d:%02d".format((secs / 60).toInt(), (secs % 60).toLong().and(60).toInt())
        } ?: ""
    val dims = itemRow.takeIf { it.width > 0 && it.height > 0 }
        ?.let { "${it.width}x${it.height}" } ?: ""
    val kindBadge = itemRow.mediaKind.uppercase()
    return listOfNotNull(
        kindBadge.takeIf { it.isNotBlank() },
        dims.takeIf { it.isNotBlank() },
        duration.takeIf { it.isNotBlank() },
        created,
    ).joinToString(separator = " | ")
}

/**
 * One keyframe as a ruled row: the real frame in a hard ink frame carrying the row's
 * colour, and the real time range and OCR printed beside it.
 */
@Composable
private fun KeyframeStripRow(
    path: File,
    timeRange: String,
    ocrText: String,
    ink: Color,
    onClickToZoom: () -> Unit,
) {
    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { onClickToZoom() },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(126.dp)
                    .height(76.dp)
                    .background(ink)
                    .border(2.dp, Memphis.Ink),
            ) {
                AsyncImage(
                    model = path,
                    contentDescription = "library keyframe",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(timeRange.uppercase(), style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.7f)))
                if (ocrText.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = ocrText,
                        style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One transcript chunk: the real time range and the real text, on paper.
 */
@Composable
private fun ChunkTextUI(chunk: TranscriptChunkSer) {
    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        color = Memphis.Paper,
        borderWidth = 2.dp,
        shadowOffset = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
                text = "%.1fs - %.1fs".format(chunk.startSec, chunk.endSec),
                style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.5f)),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = chunk.text,
                style = MemphisBodyText,
            )
        }
    }
}
