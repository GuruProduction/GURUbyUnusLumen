package com.unuslumen.app.presentation.activetasks

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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.domain.model.Task

/**
 * Persistent active tasks popup that sits above the agent selector.
 * Shows a flat rectangular task list on the left side of the screen.
 * Minimises into a small pill. Disappears entirely when no tasks exist.
 *
 * @param tasks The active (incomplete) tasks to display.
 * @param onTaskComplete Called when the user taps a task to mark it done.
 * @param modifier Modifier for positioning.
 */
@Composable
fun ActiveTasksPopup(
    tasks: List<Task>,
    onTaskComplete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tasks.isEmpty()) return

    var minimised by remember { mutableStateOf(false) }

    if (minimised) {
        MinimisedPill(
            count = tasks.size,
            onExpand = { minimised = false },
            modifier = modifier
        )
    } else {
        ExpandedPanel(
            tasks = tasks,
            onTaskComplete = onTaskComplete,
            onMinimise = { minimised = true },
            modifier = modifier
        )
    }
}

@Composable
private fun ExpandedPanel(
    tasks: List<Task>,
    onTaskComplete: (String) -> Unit,
    onMinimise: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth(0.55f)
            .wrapContentHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tasks",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onMinimise)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${tasks.size}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(Modifier.size(2.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Minimise",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        tasks.forEach { task ->
            TaskRow(
                task = task,
                onComplete = { onTaskComplete(task.id) }
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    onComplete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onComplete)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Checkbox square
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                .clickable(onClick = onComplete),
            contentAlignment = Alignment.Center
        ) {
            // Empty box, tap to complete
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = task.title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MinimisedPill(
    count: Int,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .clickable(onClick = onExpand)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$count task${if (count != 1) "s" else ""}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
        Spacer(Modifier.size(4.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(2.dp))
        )
    }
}