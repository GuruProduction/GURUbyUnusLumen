package com.unuslumen.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.ui.navigation.Screen

data class LobbyRoom(
    val name: String,
    val route: Screen,
)

private val lobbyRooms = listOf(
    LobbyRoom("Notes", Screen.NotesScreen),
    LobbyRoom("Journal", Screen.JournalScreen),
    LobbyRoom("Projects", Screen.ProjectsScreen),
    LobbyRoom("Calendar", Screen.CalendarScreen),
    LobbyRoom("Settings", Screen.SettingsScreen),
    LobbyRoom("Skills", Screen.SkillsScreen),
)

@Composable
fun LobbyNode(
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val warmFill = Color(0xFFE8DDD0)
    val warmFillSelected = Color(0xFFD4C4B0)
    val mutedText = Color(0xFF8A8A8A)

    Column(
        modifier = modifier
            .animateContentSize(tween(200)),
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { onExpandChange(!expanded) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (expanded) warmFillSelected else Color(0xFFBFAA8F)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Lobby",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (expanded) FontWeight.Bold else FontWeight.Normal,
                    color = if (expanded) Color(0xFF5C4033) else mutedText,
                    letterSpacing = 0.5.sp,
                ),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it },
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                items(lobbyRooms) { room ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(warmFill.copy(alpha = 0.5f))
                            .clickable {
                                onNavigate(room.route)
                                onExpandChange(false)
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = room.name,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Normal,
                                color = Color(0xFF6B6B6B),
                                fontSize = 13.sp,
                            ),
                        )
                    }
                }
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}