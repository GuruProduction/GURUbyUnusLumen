package com.unuslumen.app.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.theme.guruTheme

enum class GuruAgent(val displayName: String) {
    Coder("CODER"),
    Grafter("GRAFTER"),
    Wingman("WINGMAN"),
    Slik("SLIK"),
}

@Composable
fun GuruAgentSelector(
    selectedAgent: GuruAgent?,
    onAgentSelected: (GuruAgent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gold = Color(0xFFDAA520)
    val brown = Color(0xFF5C4033)
    val selectedBg = gold.copy(alpha = 0.14f)
    val unselectedBorder = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        // Gurus icon + label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(gold.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_tools),
                    contentDescription = stringResource(id = R.string.portal_agent_gurus_content_description),
                    tint = brown,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(id = R.string.portal_agent_gurus_label),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = brown,
                    letterSpacing = 1.sp,
                )
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(GuruAgent.entries) { agent ->
                val isSelected = selectedAgent == agent
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            width = 1.dp,
                            color = if (isSelected) gold else unselectedBorder,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .background(if (isSelected) selectedBg else Color.Transparent)
                        .clickable { onAgentSelected(agent) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = agent.displayName,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) brown else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                            letterSpacing = 0.8.sp,
                        )
                    )
                }
            }
        }
    }
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GuruAgentSelectorPreview() {
    guruTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            GuruAgentSelector(
                selectedAgent = GuruAgent.Coder,
                onAgentSelected = {}
            )
        }
    }
}
