// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/**
 * PortalChatPopup — the small minimisable chat window.
 *
 * Designed spec: a window into the portal, NOT the full portal. Same engine,
 * same capabilities (tools node with count badge included), just smaller and
 * floating over the room the user is already in. The scrim is barely a tint;
 * the screen behind stays readable. Minimise folds it to the ASK GURU pill;
 * tapping the pill restores the thread exactly as it was. Escape/close hides
 * everything; the entry bar reopens it any time.
 */
@Composable
fun PortalChatPopup(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF2B241C).copy(alpha = 0.16f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.BottomEnd
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            elevation = CardDefaults.elevatedCardElevation(8.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFEDE4D3)),
            modifier = Modifier
                .padding(12.dp)
                .widthIn(max = 296.dp)
        ) {
            Column(
                Modifier
                    .background(Color(0xFFEDE4D3))
                    .imePadding()
            ) {
                // mini header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PORTAL · CHAT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Text(
                        text = "×",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(4.dp)
                    )
                }

                // thread: transparent canvas-style paper — seeded demo thread
                Column(
                    Modifier
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp)
                ) {
                    PortalMetaRow(label = "GURU", time = "9:41 pm", right = false)
                    PortalMessageText(guru = true, text = "Evening reflection just ran — two insights landed in your feed below.")
                    PortalMetaRow(label = "YOU", time = "9:41 pm", right = true)
                    PortalMessageText(guru = false, text = "what did you find")
                    PortalMetaRow(label = "GURU", time = "9:42 pm", right = false)
                    PortalMessageText(
                        guru = true,
                        text = "Same late-night streak as last week, three sessions past 1am. Your 6am mornings go quiet after. Worth a wind-down."
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // compact chat bar — PortalChatBar shape scaled down
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFF0E8DA).copy(alpha = 0.5f),
                                    Color(0xFFE4D9C6).copy(alpha = 0.25f)
                                )
                            ),
                            shape = RoundedCornerShape(26.dp)
                        )
                        .padding(10.dp)
                ) {
                    var text by remember { mutableStateOf("") }
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Ask Guru anything...") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Color(0xFFDAA520)
                        )
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            Text("＋", color = Color(0xFF5B7C99), fontSize = 14.sp)
                            Text("⌂", color = Color(0xFF5B7C99), fontSize = 14.sp)
                            Text("⏱", color = Color(0xFF5B7C99), fontSize = 14.sp)
                            Text("🎭", color = Color(0xFF5B7C99), fontSize = 14.sp)
                            Text(
                                "🔧",
                                color = Color(0xFF5B7C99),
                                fontSize = 14.sp
                            )
                        }
                        // gold send node
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(Color(0xFFDAA520), CircleShape)
                                .clickable { },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↑", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortalMetaRow(label: String, time: String, right: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (right) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (right) {
            Text(time, style = portalTimeStyle())
            Spacer(Modifier.width(6.dp))
            Text(label, style = portalLabelStyle())
        } else {
            Text(label, style = portalLabelStyle())
            Spacer(Modifier.width(6.dp))
            Text(time, style = portalTimeStyle())
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp)
    ) {
        Box(
            Modifier
                .align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
                .size(width = 26.dp, height = 1.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f))
        )
    }
}

@Composable
private fun PortalMessageText(guru: Boolean, text: String) {
    if (guru) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp
            ),
            color = Color.Black,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun portalLabelStyle() = MaterialTheme.typography.labelSmall.copy(
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.14.sp
)

@Composable
private fun portalTimeStyle() = MaterialTheme.typography.labelSmall.copy(
    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
    letterSpacing = 0.12.sp
)