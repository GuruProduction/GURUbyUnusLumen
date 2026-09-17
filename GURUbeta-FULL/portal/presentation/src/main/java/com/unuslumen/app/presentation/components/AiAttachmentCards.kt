package com.unuslumen.app.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.SubTask
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.color
import com.unuslumen.app.ui.theme.guruTheme
import com.unuslumen.app.util.date.formatDateDependingOnDay
import com.unuslumen.app.util.date.isDueDateOverdue


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiAttachmentsSection(
    modifier: Modifier = Modifier,
    attachments: List<AiMessageAttachment>,
    onRemove: (Int) -> Unit = {},
    editable: Boolean = false,
    onFilePreview: (String) -> Unit = {},
) {
    if (editable) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 5.dp, end = 5.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.width(4.dp))
            attachments.forEachIndexed { i, it ->
                when (it) {
                    is AiMessageAttachment.Note -> NoteAttachmentCard(
                        note = it.note,
                        showRemoveButton = true
                    ) {
                        onRemove(i)
                    }

                    is AiMessageAttachment.Task -> TaskAttachmentCard(
                        task = it.task,
                        showRemoveButton = true
                    ) {
                        onRemove(i)
                    }

                    is AiMessageAttachment.CalenderEvents -> CalendarEventsAttachmentCard(
                        showRemoveButton = true,
                    ) {
                        onRemove(i)
                    }

                    is AiMessageAttachment.File -> FileAttachmentCard(
                        file = it,
                        showRemoveButton = true,
                        onPreviewClick = { onFilePreview(it.cachedPath) }
                    ) {
                        onRemove(i)
                    }
                }
            }
        }
    } else {
        var maxLines by remember { mutableIntStateOf(2) }
        FlowRow(
            modifier = modifier
                .animateContentSize()
                .padding(4.dp),
            maxLines = maxLines,
        ) {
            attachments.forEach { attachment ->
                when (attachment) {
                    is AiMessageAttachment.Note -> NoteAttachmentCard(attachment.note)
                    is AiMessageAttachment.Task -> TaskAttachmentCard(attachment.task)
                    is AiMessageAttachment.CalenderEvents -> CalendarEventsAttachmentCard()
                    is AiMessageAttachment.File -> FileAttachmentCard(
                        file = attachment,
                        onPreviewClick = { onFilePreview(attachment.cachedPath) }
                    )
                }
            }
            if (maxLines < 10 && attachments.size > 6) {
                Button(
                    onClick = { maxLines++ },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        "+${attachments.size - 6}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

}

@Composable
fun NoteAttachmentCard(
    note: Note,
    modifier: Modifier = Modifier,
    showRemoveButton: Boolean = false,
    onRemoveClick: () -> Unit = {},
) {
    Box(
        modifier
            .widthIn(max = 200.dp)
            .padding(top = 6.dp, end = 6.dp)
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Text(
                text = note.title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(8.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2
            )
        }
        if (showRemoveButton) {
            RemoveButton(
                Modifier.align(Alignment.TopEnd),
                onClick = onRemoveClick
            )
        }
    }
}

@Composable
fun FileAttachmentCard(
    file: AiMessageAttachment.File,
    modifier: Modifier = Modifier,
    showRemoveButton: Boolean = false,
    onPreviewClick: () -> Unit = {},
    onRemoveClick: () -> Unit = {},
) {
    Box(
        modifier
            .widthIn(max = 200.dp)
            .padding(top = 6.dp, end = 6.dp)
    ) {
        val thumbPath = file.thumbnailPath
        val previewModifier = if (onPreviewClick != {}) {
            Modifier.clickable { onPreviewClick() }
        } else Modifier
        if (file.mimeType.startsWith("image/", ignoreCase = true)) {
            // Image files render as a compact visual thumbnail so the user can
            // see what they attached before sending
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                coil.compose.AsyncImage(
                    model = java.io.File(file.cachedPath),
                    contentDescription = file.fileName,
                    modifier = previewModifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        } else if (file.mimeType.startsWith("video/", ignoreCase = true) && thumbPath != null) {
            // Videos show the extracted first-frame still with a play badge so
            // users can tell it is a video at a glance
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Box(modifier = previewModifier) {
                    coil.compose.AsyncImage(
                        model = java.io.File(thumbPath),
                        contentDescription = file.fileName,
                        modifier = Modifier
                            .size(width = 100.dp, height = 72.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    Text(
                        text = "▶",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(
                                Color(0xFF2B241C).copy(alpha = 0.55f),
                                CircleShape
                            )
                            .padding(4.dp)
                    )
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Row(
                    Modifier
                        .clickable { onPreviewClick() }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Gold type badge — extension-derived (PDF, DOCX, MP3...)
                    // falling back to the MIME subtype, then a generic FILE
                    val ext = file.fileName.substringAfterLast('.', "").uppercase()
                    val badge = when {
                        ext.isNotBlank() && ext.length <= 5 && ext.all { it.isLetterOrDigit() } -> ext
                        else -> {
                            val sub = file.mimeType.substringAfterLast('/', "").uppercase()
                            if (sub.isNotBlank() && sub != "OCTET-STREAM" && sub.length <= 5) sub else "FILE"
                        }
                    }
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = Color(0xFFB8860B),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(Color(0x2EDAA520), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = file.fileName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight(600)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val size = file.sizeBytes
                        val sizeLabel = when {
                            size < 0 -> ""
                            size < 1024 -> "$size B"
                            size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
                            size < 1024L * 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024))
                            else -> "%.1f GB".format(size / (1024.0 * 1024 * 1024))
                        }
                        if (sizeLabel.isNotBlank()) {
                            Text(
                                text = sizeLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
        if (showRemoveButton) {
            RemoveButton(
                Modifier.align(Alignment.TopEnd),
                onClick = onRemoveClick
            )
        }
    }
}

@Composable
internal fun TaskAttachmentCard(
    task: Task,
    modifier: Modifier = Modifier,
    showRemoveButton: Boolean = false,
    onRemoveClick: () -> Unit = {},
) {
    val context = LocalContext.current
    Box(
        modifier
            .widthIn(max = 200.dp)
            .padding(top = 6.dp, end = 6.dp)
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                Modifier
                    .padding(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .border(1.dp, task.priority.color, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp)
                                .align(Alignment.Center),
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = null
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                    )
                }
                if (task.subTasks.isNotEmpty() || task.dueDate != 0L) Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (task.subTasks.isNotEmpty()) {
                        val completed = remember {
                            task.subTasks.count { it.isCompleted }
                        }
                        val total = task.subTasks.size
                        Text(
                            text = "$completed/$total",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    if (task.dueDate != 0L) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                modifier = Modifier.size(8.dp),
                                painter = painterResource(R.drawable.ic_alarm),
                                contentDescription = stringResource(R.string.due_date),
                                tint = if (task.dueDate.isDueDateOverdue()) Color.Red else MaterialTheme.colorScheme.onBackground.copy(
                                    alpha = 0.8f
                                )
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = task.dueDate.formatDateDependingOnDay(context),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (task.dueDate.isDueDateOverdue()) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.7f
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        if (showRemoveButton) {
            RemoveButton(
                Modifier.align(Alignment.TopEnd),
                onClick = onRemoveClick
            )
        }
    }
}

@Composable
fun CalendarEventsAttachmentCard(
    modifier: Modifier = Modifier,
    showRemoveButton: Boolean = false,
    onRemoveClick: () -> Unit = {},
) {
    Box(
        modifier.padding(top = 6.dp, end = 6.dp)
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Row(
                Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_calendar),
                    contentDescription = stringResource(R.string.calendar),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.calendar_events_next_7_days),
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2
                )
            }
        }
        if (showRemoveButton) {
            RemoveButton(
                Modifier.align(Alignment.TopEnd),
                onClick = onRemoveClick
            )
        }
    }
}

@Composable
fun RemoveButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = Icons.Default.Clear,
        contentDescription = stringResource(R.string.delete_note),
        modifier = modifier
            .offset(x = 4.dp, y = (-4).dp)
            .clip(CircleShape)
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border((0.5f).dp, Color.LightGray, CircleShape)
            .padding(2.dp)
            .size(10.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}


@Preview
@Composable
private fun NoteAttachmentPreview() {
    guruTheme {
        NoteAttachmentCard(
            note = Note(
                id = "1",
                title = "Test note Note Title".repeat(3),
                content = "Note Content",
            ),
            showRemoveButton = true
        )
    }
}

@Preview
@Composable
private fun TaskAttachmentPreview() {
    guruTheme {
        TaskAttachmentCard(
            task = Task(
                id = "1",
                title = "Test task Task Title".repeat(3),
                description = "Task Description",
                isCompleted = false,
                dueDate = 12345,
                subTasks = listOf(
                    SubTask()
                )
            ),
            showRemoveButton = true
        )
    }
}

@Preview
@Composable
private fun CalendarEventsCardPreview() {
    guruTheme {
        CalendarEventsAttachmentCard(showRemoveButton = true)
    }
}