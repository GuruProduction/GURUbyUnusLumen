package com.unuslumen.app.ui.components.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.previewMarkdownTypography
import com.unuslumen.app.ui.theme.Orange
import com.unuslumen.app.util.date.formatDateDependingOnDay
import com.mikepenz.markdown.m3.Markdown

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
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(4.dp),
        onClick = { onClick(note) }
    ) {
        Column(
            modifier = Modifier
                .clickable { onClick(note) }
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (note.pinned) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pin_filled),
                        contentDescription = stringResource(R.string.pin_note),
                        tint = Orange,
                        modifier = Modifier
                            .size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    note.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Markdown(
                content = note.content,
                typography = previewMarkdownTypography()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = formattedDate,
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}