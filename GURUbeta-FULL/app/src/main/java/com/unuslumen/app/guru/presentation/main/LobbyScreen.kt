// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.unuslumen.app.data.metadata.DeviceStatus
import com.unuslumen.app.data.metadata.HeartbeatAwareness
import com.unuslumen.app.guru.presentation.main.LobbyTilesViewModel.LobbyTile
import com.unuslumen.app.presentation.PendingPortalPrompt
import com.unuslumen.app.presentation.PortalChatBarHost
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.navigation.Screen
import androidx.compose.ui.platform.LocalContext
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.hardShadow
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.ui.theme.guruTheme
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lobby: the home of the app and a dashboard into the phone it is running on.
 *
 * THE SHAPE, top to bottom.
 *
 * THE STATUS LINE says what the machine is doing: awake, battery, network, place and the
 * clock. It is the first thing a person reads and the only part of the page that changes on
 * its own.
 *
 * THE MASTHEAD says the word LOBBY, the date, and how many rooms it holds.
 *
 * THE WIDGET AREA is a hero that spans the grid for the Portal, and a row of two-by-one
 * units for the things reached for by hand: Add room, Settings, Ottio.
 *
 * THE TILE GRID is every other room as a square, on a four-column grid, filled with its own
 * ink, carrying a centred symbol, its name underneath in the poster face, and a count badge
 * or a SOON tag in the top-right corner like a phone's notification badge.
 *
 * THE DOCK is pinned above the chat bar and does not scroll: the three rooms a person uses
 * most, so the things you actually use never sink as the grid grows.
 *
 * WHY FOUR COLUMNS. The blueprint asked for four and four is right for a launcher: the
 * symbols here are drawn shapes, so a 76dp cell holds one comfortably, and the name lives
 * underneath the tile rather than inside it, where it has the full cell width and can run to
 * two lines without shrinking below the rest of the page.
 *
 * ORDER: press and hold any tile and drag. The order is the one the numen also rewrites by
 * voice, so the drag and the voice write the same store.
 */
@Composable
fun LobbyScreen(
    navController: NavHostController,
    viewModel: LobbyViewModel = koinViewModel(),
    tilesViewModel: LobbyTilesViewModel = koinViewModel(),
) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val tiles by tilesViewModel.tiles.collectAsStateWithLifecycle()

    // Today's calendar count and the media library count are snapshot queries, so they are
    // re-read every time the lobby comes into view.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshTodayEvents()
        viewModel.refreshMediaCount()
    }

    // The order is local until a drag is dropped. Nothing writes mid-drag: the drop is the
    // commit, so an interrupted drag cannot leave the order half-moved.
    var tileList by remember { mutableStateOf<List<LobbyTile>>(emptyList()) }
    var showAddRoom by remember { mutableStateOf(false) }
    var dragInFlight by remember { mutableStateOf(false) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    androidx.compose.runtime.LaunchedEffect(tiles) {
        if (tileList.isEmpty() || !dragInFlight) tileList = tiles
    }

    /*
     * THE PHONE ITSELF, READ ON EVERY COMPOSITION.
     *
     * A snapshot rather than a flow, because a battery flow means a receiver registered for
     * the life of the composable, and a receiver that outlives its reader is how a process
     * leaks. The lobby recomposes when the counts change, which is the right cadence for a
     * status line: often enough to be current, rarely enough to cost nothing.
     */
    val context = LocalContext.current
    val awake = HeartbeatAwareness.isChainOn()
    val reading = remember(context) { DeviceStatus.read(context) }

    // THE TOP OF THE PAGE IS FIXED AND THE GRID IS LIVE BELOW IT.
    val liveCounts = tileCounts(tiles = tileList, counts = counts)
    val ranked = rankRooms(tileList, liveCounts)

    val chipKeys = listOf("static:settings", "static:ottio")
    val chips = ranked.filter { it.tile.key in chipKeys }
        .sortedBy { chipKeys.indexOf(it.tile.key) }

    val pinned = chipKeys + "static:portal"
    val rest = ranked.filter { it.tile.key !in pinned }

    /*
     * THE DOCK'S CONTENTS.
     *
     * Three rooms, resolved from the tile store rather than hardcoded as labels, so a
     * renamed or reordered room keeps working. Chosen because they are the three a person
     * opens while doing something else: the notes they are keeping, the agents they are
     * running and the calendar they are keeping up with. A room missing from the store is
     * simply absent from the dock rather than drawn as a blank.
     */
    val dockKeys = listOf("static:notes", "static:agents", "static:calendar")
    val dockRooms = dockKeys.mapNotNull { key -> ranked.firstOrNull { it.tile.key == key } }
    val docked = dockRooms.map { it.tile.key }.toSet()

    // The grid, without the dock's own rooms: a door in the dock and a tile in the grid that
    // open the same room is a page saying the same thing twice.
    val gridRooms = rest.filter { it.tile.key !in docked }

    val gutter = 9.dp

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {

            LobbyMasthead(roomTotal = tileList.size)

            LazyVerticalGrid(
                columns = GridCells.Fixed(TILE_COLUMNS),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(gutter),
                verticalArrangement = Arrangement.spacedBy(gutter),
            ) {
                item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                    LobbyStatusBar(reading = reading, gurusAwake = awake)
                }

                item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                    LobbyHero(
                        title = "Portal",
                        count = null,
                        ink = Memphis.inkFor(0),
                        onOpen = { navController.navigate(Screen.PortalScreen) { launchSingleTop = true } },
                    )
                }

                item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(gutter),
                    ) {
                        LobbyChip(
                            modifier = Modifier.weight(1f),
                            label = "Add room",
                            count = null,
                            ink = Memphis.inkFor(1),
                            symbolKey = "add",
                            onOpen = { showAddRoom = true },
                        )
                        chips.forEachIndexed { chipIndex, room ->
                            LobbyChip(
                                modifier = Modifier.weight(1f),
                                label = room.label,
                                count = room.count,
                                ink = Memphis.inkFor(chipIndex + 2),
                                symbolKey = room.tile.key,
                                onOpen = { openRoom(navController, room.tile) },
                            )
                        }
                    }
                }

                item(key = "section", span = { GridItemSpan(maxLineSpan) }) {
                    LobbySectionBand(
                        label = "All rooms",
                        total = tileList.size,
                        modifier = Modifier.padding(top = 9.dp),
                    )
                }

                itemsIndexed(gridRooms, key = { _, room -> room.tile.key }) { index, room ->
                    var cellPitchPx by remember(room.tile.key) { mutableStateOf(0f) }

                    LobbyTileCell(
                        label = room.label,
                        count = room.count,
                        ink = Memphis.inkFor(index + 4),
                        symbolKey = room.tile.key,
                        comingSoon = isComingSoon(room.tile),
                        lifted = draggingKey == room.tile.key,
                        onOpen = { openRoom(navController, room.tile) },
                        modifier = Modifier
                            .onSizeChanged { measured ->
                                // One cell plus one gutter is the distance between two rows.
                                cellPitchPx = measured.height.toFloat() +
                                    with(density) { gutter.toPx() }
                            }
                            .pointerInput(room.tile.key, gridRooms.size, gridRooms) {
                                /*
                                 * LONG PRESS THEN DRAG, ON THE TILE ITSELF.
                                 *
                                 * The pitch is a full cell plus the gutter, captured from
                                 * this tile's own measured size, so the arithmetic and the
                                 * layout are the same number by construction.
                                 *
                                 * A grid moves in whole rows, so the travel becomes rows
                                 * first and then positions. There is no half-row to land in.
                                 */
                                var travelled = 0f
                                var fromIndex = index
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingKey = room.tile.key
                                        dragInFlight = true
                                        travelled = 0f
                                        fromIndex = gridRooms.indexOfFirst { it.tile.key == room.tile.key }
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        travelled += dragAmount.y
                                    },
                                    onDragEnd = {
                                        val rows = if (cellPitchPx > 0f) {
                                            (travelled / cellPitchPx).roundToInt()
                                        } else {
                                            0
                                        }
                                        val steps = rows * TILE_COLUMNS
                                        if (steps != 0 && fromIndex >= 0) {
                                            tileList = moveInOrder(
                                                tiles = tileList,
                                                movingKey = room.tile.key,
                                                bySteps = steps,
                                            )
                                            tilesViewModel.persistOrder(tileList)
                                        }
                                        dragInFlight = false
                                        draggingKey = null
                                    },
                                    onDragCancel = {
                                        dragInFlight = false
                                        draggingKey = null
                                    },
                                )
                            },
                    )
                }

                // Clearance so the last row can scroll clear of the dock rather than
                // disappearing behind it.
                item(key = "tail", span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(4.dp))
                }
            }

            /*
             * THE DOCK, PINNED ABOVE THE CHAT BAR.
             *
             * It does not scroll, which is the whole point: the three rooms a person uses
             * while doing something else never sink as the grid grows past the fold. It is
             * the height of one small block and it carries no label, because a dock is
             * recognised by being at the bottom.
             */
            if (dockRooms.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(gutter),
                ) {
                    dockRooms.forEachIndexed { index, room ->
                        DockButton(
                            modifier = Modifier.weight(1f),
                            label = room.label,
                            ink = Memphis.inkFor(index + 5),
                            symbolKey = room.tile.key,
                            onOpen = { openRoom(navController, room.tile) },
                        )
                    }
                }
            }

            // THE PORTAL'S OWN CHAT BAR, NOT A LOOKALIKE.
            //
            // [PortalChatBarHost] renders the same PortalChatBar the portal renders, against
            // the same Activity-scoped PortalViewModel, so the lobby and the portal share one
            // draft, one send path and one conversation.
            PortalChatBarHost(
                modifier = Modifier.fillMaxWidth(),
                opensPortalOnTap = true,
                onOpenChat = {
                    navController.navigate(Screen.PortalScreen) { launchSingleTop = true }
                },
                onSentToPortal = { sentence ->
                    PendingPortalPrompt.set(sentence)
                    navController.navigate(Screen.PortalScreen) { launchSingleTop = true }
                },
            )
        }

        if (showAddRoom) {
            AddRoomSheet(
                onDismiss = { showAddRoom = false },
                onCreate = { name ->
                    showAddRoom = false
                    tilesViewModel.createRoom(name)
                },
            )
        }
    }
}

/**
 * The grid's column count, named once.
 *
 * FOUR, as the blueprint asked. The name lives underneath the tile where it has the full
 * cell width, so a 76dp square is enough: the symbol sits inside the square and the label
 * runs across the whole column beneath it. Three columns left the symbols too large for the
 * page and the name competing with them for the same space.
 */
private const val TILE_COLUMNS = 4

/** One room as the page needs it. */
private data class RankedRoom(
    val tile: LobbyTile,
    val label: String,
    val count: Int,
)

/** The live number for a tile, or zero when the room has nothing to count. */
private fun countFor(tile: LobbyTile, counts: LobbyViewModel.LobbyCounts): Int = when (tile.key) {
    "static:projects" -> counts.projects
    "static:calendar" -> counts.calendarToday
    "static:media" -> counts.media
    "static:thoughts" -> counts.thoughts
    "static:skills" -> counts.skills
    else -> 0
}

private fun tileCounts(
    tiles: List<LobbyTile>,
    counts: LobbyViewModel.LobbyCounts,
): Map<String, Int> = tiles.associate { it.key to countFor(it, counts) }

/**
 * Order the page by what matters now.
 *
 * Rooms with something in them lead, because a room with three events today is more use than
 * one with nothing. Empty rooms keep their saved order behind them.
 */
private fun rankRooms(tiles: List<LobbyTile>, counts: Map<String, Int>): List<RankedRoom> {
    val all = tiles.map { tile ->
        RankedRoom(tile = tile, label = tile.title, count = counts[tile.key] ?: 0)
    }
    val busy = all.filter { it.count > 0 }.sortedByDescending { it.count }
    val idle = all.filter { it.count == 0 }
    return busy + idle
}

private fun isComingSoon(tile: LobbyTile): Boolean =
    tile.key == "static:journal" || tile.key == "static:projects"

/** Move one room by a number of positions, clamped. */
private fun moveInOrder(
    tiles: List<LobbyTile>,
    movingKey: String,
    bySteps: Int,
): List<LobbyTile> {
    val fromIndex = tiles.indexOfFirst { it.key == movingKey }
    if (fromIndex < 0) return tiles
    val targetIndex = (fromIndex + bySteps).coerceIn(0, tiles.lastIndex)
    if (targetIndex == fromIndex) return tiles
    val reordered = tiles.toMutableList()
    val moved = reordered.removeAt(fromIndex)
    reordered.add(targetIndex, moved)
    return reordered
}

private fun openRoom(navController: NavHostController, tile: LobbyTile) {
    when {
        tile.isModule -> navController.navigate(Screen.ModuleScreen(moduleId = tile.moduleId!!))
        tile.key == "static:notes" -> navController.navigate(Screen.NotesScreen)
        tile.key == "static:media" -> navController.navigate(Screen.MediaScreen)
        tile.key == "static:calendar" -> navController.navigate(Screen.CalendarScreen)
        tile.key == "static:settings" -> navController.navigate(Screen.SettingsScreen)
        tile.key == "static:thoughts" -> navController.navigate(Screen.ThoughtCyclesScreen)
        tile.key == "static:skills" -> navController.navigate(Screen.SkillsScreen)
        tile.key == "static:agents" -> navController.navigate(Screen.AgentsScreen)
        tile.key == "static:ottio" -> navController.navigate(Screen.AgentMarketScreen)
        tile.key == "static:heartbeat" -> navController.navigate(Screen.HeartbeatScreen)
        tile.key == "static:innermind" -> navController.navigate(Screen.InnerMindScreen)
        // journal and projects are built but closed: tapping does nothing.
        else -> Unit
    }
}

/**
 * The masthead: the date on the top line, then the word LOBBY with the room count.
 *
 * THE STATUS BAR SITS UNDER THIS, not beside it. The word LOBBY is set at 42sp and the
 * status line carries five facts; put on one row they would fight, and the word the page is
 * named after would be the thing that lost.
 */
@Composable
private fun LobbyMasthead(roomTotal: Int) {
    val today = remember {
        SimpleDateFormat("EEE dd MMM", Locale.UK).format(Date())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.lobby).uppercase(),
                style = posterStyle(38f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = today.uppercase(),
                    style = captionStyle(9f),
                    modifier = Modifier
                        .hardShadow(offset = 3.dp)
                        .background(Memphis.Cyan)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$roomTotal rooms",
                    style = captionStyle(9f),
                    modifier = Modifier
                        .hardShadow(offset = 2.dp)
                        .background(Memphis.Yellow)
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(3.dp)
                .background(Memphis.Ink)
        )
    }
}

/** The hero: home, flooded in its ink, the width of the page. */
@Composable
private fun LobbyHero(
    title: String,
    count: Int?,
    ink: Color,
    onOpen: () -> Unit,
) {
    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clickable { onOpen() },
        color = ink,
        borderWidth = 3.dp,
        shadowOffset = 8.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 11.dp)) {
            Text(
                text = "NEXT UP",
                style = captionStyle(9f, Memphis.Ink),
                modifier = Modifier
                    .background(Memphis.Paper)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title.uppercase(),
                    style = posterStyle(36f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (count != null) {
                    Text(
                        text = if (count > 0) "$count" else "—",
                        style = posterStyle(56f),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "OPEN ROOM", style = captionStyle(9f, Memphis.Ink))
                Text(text = "→", style = posterStyle(17f))
            }
        }
    }
}

/**
 * A two-by-one widget: a symbol and a total, sized to share the row.
 *
 * THE BIG NUMBER IS THE POINT. A dashboard widget exists to be read across a room, so the
 * count is the largest thing on it and the name is the caption. A widget with nothing to
 * count shows a dash rather than a zero, because zero is a measurement and a dash is the
 * absence of one.
 */
@Composable
private fun LobbyChip(
    modifier: Modifier = Modifier,
    label: String,
    count: Int?,
    ink: Color,
    symbolKey: String,
    onOpen: () -> Unit,
) {
    MemphisSurface(
        modifier = modifier
            .height(84.dp)
            .clickable { onOpen() },
        color = ink,
        borderWidth = 2.5.dp,
        shadowOffset = 5.dp,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 9.dp, end = 9.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                LobbySymbol(key = symbolKey, ink = Memphis.Ink, size = 18.dp)
                Text(
                    text = when {
                        count == null -> "+"
                        count > 0 -> "$count"
                        else -> "—"
                    },
                    style = posterStyle(24f),
                    maxLines = 1,
                )
            }
            Text(
                text = label.uppercase(),
                style = captionStyle(8.5f, Memphis.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The black band that separates the top of the page from the grid. */
@Composable
private fun LobbySectionBand(
    label: String,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Memphis.Ink)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label.uppercase(), style = captionStyle(9.5f, Memphis.Paper))
        Text(
            text = "$total",
            style = captionStyle(9.5f, Memphis.Ink),
            modifier = Modifier
                .background(Memphis.Paper)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * One room as a square tile: its ink, a centred symbol, its name underneath, and a badge.
 *
 * WHY THE SYMBOL IS CENTRED AND THE NAME IS UNDERNEATH. That is what makes a grid of rooms
 * read as a launcher: the mark is what the eye lands on and it sits in the middle of a block
 * of colour, and the name is a caption on the page beneath it rather than a label squeezed
 * inside the paint. It also means a long room name has the full column width to run to.
 *
 * THE BADGE IS TOP-RIGHT, in ink with paper type, drawn as a sticker rather than a pill
 * because nothing on this page is rounded.
 *
 * A COMING-SOON ROOM INVERTS: paper instead of ink, an ink name, a SOON tag. A room that is
 * not open yet should not shout as loudly as one that is.
 */
@Composable
private fun LobbyTileCell(
    label: String,
    count: Int,
    ink: Color,
    symbolKey: String,
    comingSoon: Boolean,
    lifted: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            // A lifted tile fades a little so the finger holding it can see underneath.
            .alpha(if (lifted) 0.75f else 1f)
            .then(if (comingSoon) Modifier else Modifier.clickable { onOpen() }),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .hardShadow(offset = 4.dp)
                .background(if (comingSoon) Memphis.Paper else ink)
                .border(2.5.dp, Memphis.Ink),
        ) {
            if (count > 0 && !comingSoon) {
                Text(
                    text = if (count > 99) "99+" else "$count",
                    style = captionStyle(8.5f, Memphis.Paper),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Memphis.Ink)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }

            if (comingSoon) {
                Text(
                    text = "SOON",
                    style = captionStyle(7f, Memphis.Ink.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Memphis.Paper)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }

            // THE MARK, CENTRED IN THE PAINT. Offset up by a few dp so a badge in the corner
            // cannot crowd it, and so every symbol in the grid shares one optical centre.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 2.dp),
            ) {
                LobbySymbol(
                    key = symbolKey,
                    ink = if (comingSoon) ink.copy(alpha = 0.35f) else Memphis.Ink,
                    size = 30.dp,
                )
            }
        }

        Text(
            text = label.uppercase(),
            style = posterStyle(10.5f, if (comingSoon) Memphis.Ink.copy(alpha = 0.45f) else Memphis.Ink),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp),
        )
    }
}

/**
 * One room in the pinned dock.
 *
 * A DOCK BUTTON IS SMALLER THAN A TILE AND CARRIES ITS NAME INSIDE IT, because it never has
 * a second line to spare: the dock is the last thing above the chat bar and every dp here is
 * taken from the grid.
 */
@Composable
private fun DockButton(
    modifier: Modifier = Modifier,
    label: String,
    ink: Color,
    symbolKey: String,
    onOpen: () -> Unit,
) {
    MemphisSurface(
        modifier = modifier
            .height(46.dp)
            .clickable { onOpen() },
        color = ink,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            LobbySymbol(key = symbolKey, ink = Memphis.Ink, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = label.uppercase(),
                style = captionStyle(8f, Memphis.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Name a new room.
 *
 * WHY IT ASKS FOR ONE THING. A room is a vessel: it has a name and, for now, nothing in it.
 * The composition inside is written later through conversation, which is how module-forge
 * already works.
 */
@Composable
private fun AddRoomSheet(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Memphis.Ink.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MemphisSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            color = Memphis.Paper,
            borderWidth = 3.dp,
            shadowOffset = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    Text("ADD A ROOM", style = posterStyle(28f))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(3.dp)
                            .background(Memphis.Ink),
                    )
                }

                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
                    Text(
                        "WHAT IT IS CALLED",
                        style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
                    )
                    Spacer(Modifier.height(7.dp))
                    MemphisSurface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Memphis.Paper,
                        borderWidth = 2.5.dp,
                        shadowOffset = 3.dp,
                    ) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                            if (name.isEmpty()) {
                                Text(
                                    "Home, Money, The Betting Room",
                                    style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.4f)),
                                )
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                value = name,
                                onValueChange = { typed -> name = typed.replace("\n", "") },
                                singleLine = true,
                                textStyle = posterStyle(20f),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Memphis.Ink),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(
                        "You will be able to fill it in by talking to GURU once it exists.",
                        style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                    )
                }

                Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(3.dp).background(Memphis.Ink))
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MemphisSurface(
                        modifier = Modifier.weight(1f).clickable { onDismiss() },
                        color = Memphis.Paper,
                        borderWidth = 2.5.dp,
                        shadowOffset = 4.dp,
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text("CANCEL", style = captionStyle(9.5f))
                        }
                    }
                    val ready = name.trim().isNotEmpty()
                    MemphisSurface(
                        modifier = Modifier
                            .weight(1f)
                            .then(if (ready) Modifier.clickable { onCreate(name.trim()) } else Modifier),
                        color = if (ready) Memphis.Ink else Memphis.Paper,
                        borderWidth = 2.5.dp,
                        shadowOffset = 4.dp,
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "CREATE",
                                style = captionStyle(9.5f, if (ready) Memphis.Paper else Memphis.Ink.copy(alpha = 0.4f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 380, heightDp = 800)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LobbyScreenPreview() {
    guruTheme {
        LobbyScreen(navController = rememberNavController())
    }
}
