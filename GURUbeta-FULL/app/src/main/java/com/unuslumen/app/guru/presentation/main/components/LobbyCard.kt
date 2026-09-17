package com.unuslumen.app.guru.presentation.main.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.theme.DarkGray
import com.unuslumen.app.ui.theme.guruTheme

/** Gold caret stroke that sits under each tile label — echoes the chat input caret. */
private val LabelStroke = Color(0xFFE3B94E)

/** Orange live-count chip, matching the portal's tool-call badge orange. */
private val BadgeOrange = Color(0xFFE8863B)

/**
 * A single Lobby tile drawn in the portal's visual language:
 * warm tinted paper card, bold label top-left with a hand-drawn gold stroke
 * beneath it, icon bottom-right, and an optional real-data count badge
 * overlapping the top edge.
 */
@Composable
fun LobbyCard(
    title: String,
    image: Int,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    badgeCount: Int = 0,
    comingSoon: Boolean = false,
    onClick: () -> Unit = {},
) {
    val cardShape = RoundedCornerShape(34.dp)
    Box(modifier = modifier) {
        Card(
            modifier = Modifier,
            shape = cardShape,
            elevation = CardDefaults.elevatedCardElevation(6.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = tint ?: MaterialTheme.colorScheme.surfaceVariant,
            )
        ) {
            Column(
                Modifier
                    .then(if (comingSoon) Modifier else Modifier.clickable { onClick() })
                    .aspectRatio(1.0f)
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = DarkGray,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    // Hand-set underline stroke, slightly off level like ink on paper
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .width(26.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(LabelStroke)
                            .rotate(-2f)
                    )
                }
                Image(
                    modifier = Modifier
                        .size(52.dp)
                        .alpha(if (comingSoon) 0.4f else 0.95f)
                        .align(Alignment.End),
                    painter = painterResource(id = image),
                    contentDescription = title
                )
            }
        }

        // Locked banner: a full-width diagonal-striped ribbon pinned across
        // the card's lower edge, with COMING SOON set inside it. Reads as
        // one deliberate object, not a tag bolted onto a live tile.
        if (comingSoon) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                DarkGray.copy(alpha = 0.92f),
                                DarkGray.copy(alpha = 0.78f)
                            )
                        )
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp)
                        .height(3.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFFE3B94E).copy(alpha = 0.85f),
                                    Color.Transparent
                                )
                            )
                        )
                ) {
                    // top hairline accent, gold like the label stroke
                }
                Text(
                    text = "COMING SOON",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = Color(0xFFF3EBDD),
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.22.em
                    ),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(vertical = 14.dp)
                )
            }
        }

        // Live count badge — only rendered when there is something to count.
        if (badgeCount > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 0.dp, y = (-8).dp)
                    .padding(end = 14.dp)
            ) {
                Row(
                    Modifier
                        .border(3.dp, MaterialTheme.colorScheme.background, RoundedCornerShape(50))
                        .background(BadgeOrange, RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (badgeCount > 99) "99+" else "$badgeCount",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

@Preview
@Preview(uiMode = UI_MODE_NIGHT_YES)
@Composable
fun LobbyCardPreview() {
    guruTheme {
        Box(Modifier.size(175.dp)) {
            LobbyCard(
                title = "Notes",
                image = R.drawable.notes_img,
                tint = Color(0xFFE8DCC4),
                badgeCount = 12
            )
        }
    }
}