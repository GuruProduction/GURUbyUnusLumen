package com.unuslumen.app.guru.media.ui

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.unuslumen.app.guru.media.SceneSerialization
import com.unuslumen.app.guru.media.TranscriptChunkSer
import com.unuslumen.app.guru.media.TranscriptSerialization
import com.unuslumen.app.ui.theme.DarkGray
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaDetailScreen — one library item's strip view, the same document shape
 * the Guru engine reads in chat. Header carries real file metadata; rows
 * carry the real probed timeline blocks including the strip's per-frame OCR;
 * tap on any row shows the frame full-resolution (zoom overlay, closed by a
 * tap). Share rides the real OS sheet; Delete carries a real confirmation
 * before the hard-delete path.
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
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            TextButton(onClick = { navController.popBackStack() }) { Text("<", color = DarkGray) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Item not found", color = DarkGray)
            }
        }
        return
    }

    val itemRowLoaded = current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header row: back + kind label
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp)
        ) {
            TextButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.padding(end = 10.dp)
            ) { Text("<", color = DarkGray) }
            Text(
                text = itemRowLoaded.mediaKind.uppercase(),
                style = MaterialTheme.typography.titleLarge.copy(
                    color = DarkGray,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp)
        ) {
            Text(
                text = itemRowLoaded.sourceFilename,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = DarkGray,
                    fontWeight = FontWeight.ExtraBold
                )
            )
            Text(
                text = detailMetaLabel(itemRowLoaded),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = DarkGray.copy(alpha = 0.6f)
                )
            )
            Spacer(Modifier.height(10.dp))

            val keyframeList = remember(itemRowLoaded.scenesJson) {
                SceneSerialization.jsonToList(itemRowLoaded.scenesJson)
            }
            val chunkList = remember(itemRowLoaded.transcriptJson) {
                TranscriptSerialization.jsonToList(itemRowLoaded.transcriptJson)
            }
            if (keyframeList.isEmpty() && chunkList.isEmpty() && itemRowLoaded.mediaKind != "video") {
                Text(
                    text = "No keyframes saved for this non-video item.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = DarkGray),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            // Every keyframe row: poster of real JPG on disk, real OCR and real
            // time stamps (strip rendering). Tapping loads real full-res for
            // the zoom viewer.
            keyframeList.forEach { keyframe ->
                KeyframeStripRow(
                    path = File(keyframe.keyframePath),
                    timeRange = "%.1fs-%.1fs".format(keyframe.startSec, keyframe.endSec),
                    ocrText = keyframe.ocrText.ifBlank { "" },
                ) {
                    displayFullScreen = File(keyframe.keyframePath)
                }
            }
            // Whole-text real transcript rows read from the real JSON column text
            chunkList.forEach { chunkRow ->
                ChunkTextUI(chunkRow)
            }
            // Zoom overlay on a real tapped frame's full-res path
            displayFullScreen?.let { zoomFile ->
                if (zoomFile.exists()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .padding(12.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(26.dp))
                            .clickable { displayFullScreen = null },
                        contentAlignment = Alignment.Center,
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
                        "The zoom's chosen frame file could not load.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFB3261E))
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            // Real share of the library file via the app's FileProvider; only rides the rows that exist.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = {
                    val shareCtx = org.koin.core.context.GlobalContext.get().get<android.content.Context>()
                    val theFile = sourceBytesFileForDisplay(itemRowLoaded)
                    if (theFile != null && theFile.exists()) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            shareCtx,
                            "${shareCtx.packageName}.fileprovider",
                            theFile
                        )
                        val type = mediaMimeTypeOf(itemRowLoaded)
                        val shareIntent = Intent(Intent.ACTION_SEND)
                        shareIntent.type = type
                        shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        shareCtx.startActivity(
                            Intent.createChooser(shareIntent, "Share media").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }) { Text("Share", color = DarkGray) }
                TextButton(
                    onClick = { showDeleteDialog = true },
                ) { Text("Delete", color = Color(0xFFB3261E)) }
            }
            Spacer(Modifier.height(60.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete item?") },
            text = { Text("The row, its files, keyframes, zoomed-frame rows all get erased.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteItem(itemRowLoaded.id)
                    showDeleteDialog = false
                    navController.popBackStack()
                }) { Text("DELETE", color = Color(0xFFB3261E)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
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
    return null // file is gone everywhere; no ghosted share
}

/** Share's binary test: no sidecar (transcript.json, scenes.json, poster). */
private fun isStoredBinary(f: File): Boolean {
    if (!f.isFile) return false
    return f.name !in setOf(
        "transcript.json",
        "scenes.json",
        "poster.jpg"
    )
}

private fun mediaMimeTypeOf(itemRow: com.unuslumen.app.database.entity.MediaItemEntity): String =
    itemRow.mimeType.ifBlank { "application/octet-stream" }

private fun detailMetaLabel(itemRow: com.unuslumen.app.database.entity.MediaItemEntity): String {
    val created = SimpleDateFormat("d MMM yyyy HH:mm", Locale.UK).format(Date(itemRow.createdAt))
    val duration = itemRow.takeIf { it.mediaKind != "image" }?.let { r -> if (r.durationSeconds > 0.0) r.durationSeconds else null }?.let { secs ->
        " | %d:%02d".format((secs / 60).toInt(), (secs % 60).toLong().and(60).toInt())
    } ?: ""
    val dims = itemRow.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}x${it.height}" } ?: ""
    val kindBadge = itemRow.mediaKind.uppercase()
    return listOfNotNull(
        "$kindBadge".takeIf { it.isNotBlank() },
        dims.takeIf { it.isNotBlank() },
        duration.takeIf { it.isNotBlank() },
        created,
    ).joinToString(separator = " | ")
}

/**
 * Real media strip's one keyframe row rendered on the Compose column; OCR text under
 * the time-range row when legible.
 */
@Composable
private fun KeyframeStripRow(
    path: File,
    timeRange: String,
    ocrText: String,
    onClickToZoom: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClickToZoom() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = path,
            contentDescription = "library keyframe",
            modifier = Modifier
                .padding(start = 14.dp)
                .width(138.dp)
                .height(82.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFE8DCC4)),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(timeRange, style = MaterialTheme.typography.labelSmall.copy(color = DarkGray))
            if (ocrText.isNotBlank()) {
                Text(
                    text = ocrText,
                    style = MaterialTheme.typography.bodySmall.copy(color = DarkGray.copy(alpha = 0.8f)),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * One real chunk line: real time stamp + text content straight from the real
 * transcript JSON sidecar, no invention of content.
 */
@Composable
private fun ChunkTextUI(chunk: TranscriptChunkSer) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp)
    ) {
        Text(
            text = "%.1fs - %.1fs".format(chunk.startSec, chunk.endSec),
            style = MaterialTheme.typography.labelSmall.copy(color = DarkGray.copy(alpha = 0.6f)),
        )
        Text(text = chunk.text, style = MaterialTheme.typography.bodyMedium)
    }
}