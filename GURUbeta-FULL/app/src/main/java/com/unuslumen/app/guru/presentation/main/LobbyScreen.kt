// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.unuslumen.app.guru.presentation.main.LobbyTilesViewModel.LobbyTile
import com.unuslumen.app.guru.presentation.main.components.LobbyCard
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.theme.DarkGray
import com.unuslumen.app.ui.theme.guruTheme
import org.koin.androidx.compose.koinViewModel
import java.io.File

/** Whisper tints — desaturated paper tones that read as one family over the cream. */
private val TintProjects = Color(0xFFE4CEB7)
private val TintMedia = Color(0xFFCBC6DC)
private val TintCalendar = Color(0xFFDED3E3)
private val TintSettings = Color(0xFFDEDAD3)
private val TintSkills = Color(0xFFD2E2E6)
private val TintNotes = Color(0xFFDDE3D1)
private val TintJournal = Color(0xFFE3D6DB)
private val TintThoughts = Color(0xFFEFD9C6)

/** Header label + divider colours straight from the portal's ink. */
private val PortalInk = DarkGray
private val DividerSlate = Color(0xFF9A9384)

/**
 * Lobby, redrawn as a room inside the portal.
 *
 * The tile surface is unified and alive: GURU-grown rooms and built-in
 * tiles share one order, press-and-hold drags anything anywhere like a
 * phone home screen, and the same order the numen rewrites by voice.
 */
@Composable
fun LobbyScreen(
    navController: NavHostController,
    viewModel: LobbyViewModel = koinViewModel(),
    tilesViewModel: LobbyTilesViewModel = koinViewModel()
) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val tiles by tilesViewModel.tiles.collectAsStateWithLifecycle()

    // Re-query today's calendar range and the media library count every time
    // the Lobby enters composition.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshTodayEvents()
        viewModel.refreshMediaCount()
    }

    // Drag state: local tile order mirrors the flow until the drop, then
    // persists through the same store reorderTiles writes. Nothing writes
    // mid-drag — the drop is the commit.
    var tileList by remember { mutableStateOf<List<LobbyTile>>(emptyList()) }
    var dragInFlight by remember { mutableStateOf(false) }
    var draggingIndex by remember { mutableStateOf(-1) }
    val haptics = LocalHapticFeedback.current

    androidx.compose.runtime.LaunchedEffect(tiles) {
        if (tileList.isEmpty() || !dragInFlight) tileList = tiles
    }

    // Tile geometry for the drop computation: 2 columns, the grid's own
    // spacing tells where a lifted tile lands when it's released.
    val tilePositions = remember { mutableStateOf(listOf<Pair<Int, Int>>()) }
    val draggedOffset = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val draggedOffsetY = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize()) {
        // Portal background stack — art, then warm paper wash.
        Image(
            painter = painterResource(id = R.drawable.portal_bg_art),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF0E8DA).copy(alpha = 0.75f),
                            Color(0xFFE4D9C6).copy(alpha = 0.55f),
                        )
                    )
                )
        )

        // Faint GURU watermark, matching the portal screen treatment.
        Image(
            painter = painterResource(id = R.drawable.guru_logo),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(0.15f)
                .width(400.dp)
                .height(218.dp),
            contentScale = ContentScale.Fit,
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = { lobbyHeader() },
            bottomBar = {
                lobbyAskPill(
                    onClick = { navController.navigate(Screen.PortalScreen) }
                )
            },
        ) { paddingValues ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    top = 10.dp,
                    bottom = 24.dp,
                    start = 18.dp,
                    end = 18.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                itemsIndexed(tileList, key = { _, tile -> tile.key }) { index, tile ->
                    val isDragging = index == draggingIndex && dragInFlight
                    Box(
                        Modifier
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInParent()
                                val current = tilePositions.value
                                if (current.size != tileList.size) {
                                    tilePositions.value = List(tileList.size) { 0 to 0 }
                                }
                                if (index < current.size || index < tileList.size) {
                                    val updated = tilePositions.value.toMutableList()
                                    while (updated.size <= index) updated.add(0 to 0)
                                    updated[index] = pos.x.toInt() to pos.y.toInt()
                                    tilePositions.value = updated
                                }
                            }
                            .pointerInput(tile.key) {
                                // Home-screen feel: press and hold picks the tile
                                // up (haptic confirm), drag moves it live, drop
                                // commits the unified order.
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingIndex = index
                                        dragInFlight = true
                                        draggedOffset.floatValue = 0f
                                        draggedOffsetY.floatValue = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        draggedOffset.floatValue += dragAmount.x
                                        draggedOffsetY.floatValue += dragAmount.y
                                    },
                                    onDragEnd = {
                                        // Where did the lift land? Nearest tile
                                        // centre wins the swap; simple, honest,
                                        // home-screen style.
                                        val positions = tilePositions.value
                                        if (draggingIndex >= 0 && positions.size == tileList.size && positions.isNotEmpty()) {
                                            val start = positions.getOrNull(draggingIndex) ?: (0 to 0)
                                            val landingX = start.first + draggedOffset.floatValue
                                            val landingY = start.second + draggedOffsetY.floatValue
                                            var bestIndex = draggingIndex
                                            var bestDist = Float.MAX_VALUE
                                            positions.forEachIndexed { i, pos ->
                                                val dx = pos.first - landingX
                                                val dy = pos.second - landingY
                                                val d = dx * dx + dy * dy
                                                if (d < bestDist) {
                                                    bestDist = d
                                                    bestIndex = i
                                                }
                                            }
                                            if (bestIndex != draggingIndex) {
                                                val reordered = tileList.toMutableList()
                                                val moved = reordered.removeAt(draggingIndex)
                                                reordered.add(bestIndex, moved)
                                                tileList = reordered
                                            }
                                        }
                                        // Commit: the drop writes, never mid-drag.
                                        tilesViewModel.persistOrder(tileList)
                                        dragInFlight = false
                                        draggingIndex = -1
                                    },
                                    onDragCancel = {
                                        dragInFlight = false
                                        draggingIndex = -1
                                    }
                                )
                            }
                    ) {
                        LobbyTileCard(
                            tile = tile,
                            index = index,
                            counts = counts,
                            lifted = isDragging,
                            onClick = {
                                when {
                                    tile.isModule -> navController.navigate(Screen.ModuleScreen(moduleId = tile.moduleId!!))
                                    tile.key == "static:notes" -> navController.navigate(Screen.NotesScreen)
                                    tile.key == "static:journal" -> {} // coming soon — preserved behaviour
                                    tile.key == "static:projects" -> {} // coming soon — preserved behaviour
                                    tile.key == "static:media" -> navController.navigate(Screen.MediaScreen)
                                    tile.key == "static:calendar" -> navController.navigate(Screen.CalendarScreen)
                                    tile.key == "static:settings" -> navController.navigate(Screen.SettingsScreen)
                                    tile.key == "static:thoughts" -> navController.navigate(Screen.ThoughtCyclesScreen)
                                    tile.key == "static:skills" -> navController.navigate(Screen.SkillsScreen)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** One tile in the unified surface: a LobbyCard for statics, icon-dressed cards for grown rooms. */
@Composable
private fun LobbyTileCard(
    tile: LobbyTile,
    index: Int,
    counts: LobbyViewModel.LobbyCounts,
    lifted: Boolean,
    onClick: () -> Unit
) {
    val tint = if (index % 8 == 0) TintNotes
    else if (index % 8 == 1) TintJournal
    else if (index % 8 == 2) TintProjects
    else if (index % 8 == 3) TintMedia
    else if (index % 8 == 4) TintCalendar
    else if (index % 8 == 5) TintSettings
    else if (index % 8 == 6) TintThoughts
    else TintSkills

    val badge = when (tile.key) {
        "static:projects" -> counts.projects
        "static:calendar" -> counts.calendarToday
        "static:media" -> counts.media
        "static:thoughts" -> counts.thoughts
        "static:skills" -> counts.skills
        else -> 0
    }

    val imageRes = when (tile.key) {
        "static:notes" -> R.drawable.lobby_notes
        "static:journal" -> R.drawable.lobby_journal
        "static:projects" -> R.drawable.lobby_projects
        "static:media" -> R.drawable.lobby_notes
        "static:calendar" -> R.drawable.lobby_calendar
        "static:settings" -> R.drawable.lobby_settings
        "static:thoughts" -> R.drawable.lobby_thoughts
        "static:skills" -> R.drawable.lobby_skills
        else -> R.drawable.lobby_skills // grown rooms without an icon fall back to skills art
    }

    Box(Modifier.alpha(if (lifted) 0.75f else 1f)) {
        if (tile.isModule && tile.iconPath != null) {
            // A grown room with a hand-picked icon: the art the numen chose.
            LobbyCard(
                title = tile.title,
                image = imageRes,
                tint = tint,
                badgeCount = badge,
                onClick = onClick
            )
            // Icon overlay: AsyncImage pinned over the card's default art.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            ) {
                AsyncImage(
                    model = File(tile.iconPath),
                    contentDescription = "${tile.title} icon",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .alpha(0.95f)
                        .width(52.dp)
                        .height(52.dp)
                )
            }
        } else {
            LobbyCard(
                title = tile.title,
                image = imageRes,
                tint = tint,
                badgeCount = badge,
                comingSoon = tile.key == "static:journal" || tile.key == "static:projects",
                onClick = onClick
            )
        }
    }
}

/** Portal-style header: small-caps LOBBY with the tilted ink divider under it. */
@Composable
private fun lobbyHeader() {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Text(
            text = stringResource(R.string.lobby).uppercase(),
            style = MaterialTheme.typography.titleLarge.copy(
                color = PortalInk,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.32.em
            )
        )
        Box(
            Modifier
                .padding(top = 8.dp)
                .width(38.dp)
                .height(2.dp)
                .background(DividerSlate, RoundedCornerShape(2.dp))
        )
    }
}

/** The portal's ask pill — tap lands you in the portal chat.
 *  Navigation-bar inset is consumed first so the pill rides above the
 *  Samsung gesture bar exactly like PortalChatBar does on the portal screen. */
@Composable
private fun lobbyAskPill(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                RoundedCornerShape(50)
            )
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Ask Guru anything…",
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = DarkGray.copy(alpha = 0.5f)
                )
            )
            Text(
                text = "SEND →",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = PortalInk,
                    fontWeight = FontWeight.ExtraBold
                )
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 680)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LobbyScreenPreview() {
    guruTheme {
        LobbyScreen(
            navController = rememberNavController()
        )
    }
}