package com.unuslumen.app.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.JournalEntry
import com.unuslumen.app.ui.components.common.previewMarkdownTypography
import com.unuslumen.app.util.date.fullDate
import com.mikepenz.markdown.m3.Markdown

@Composable
fun LazyItemScope.JournalEntryItem(
    modifier: Modifier = Modifier,
    entry: JournalEntry,
    timeText: String = entry.createdDate.fullDate(LocalContext.current),
    onClick: (JournalEntry) -> Unit
) {
    Card(
        modifier = modifier
            .animateItem(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(6.dp)
    ) {
        Column(
            modifier = Modifier
                .clickable { onClick(entry) }
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(entry.mood.iconRes),
                    stringResource(entry.mood.titleRes),
                    tint = entry.mood.color,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.content.isNotBlank()){
                Markdown(
                    content = entry.content,
                    typography = previewMarkdownTypography()
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}