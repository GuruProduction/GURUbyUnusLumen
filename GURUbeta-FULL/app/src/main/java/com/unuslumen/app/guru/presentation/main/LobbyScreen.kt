// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.unuslumen.app.guru.presentation.main.LobbyTilesViewModel.LobbyTile
import com.unuslumen.app.presentation.PendingPortalPrompt
import com.unuslumen.app.presentation.PortalChatBarHost
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisConfetti
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.hardShadow
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.ui.theme.guruTheme
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lobby, as a printed poster.
 *
 * THE STRUCTURE, and why it is not a grid any more: the lobby has to hold nine
 * rooms today and a hundred when GURU has built its own, so the screen cannot
 * be a fixed arrangement of same-sized cards. It is a page instead, and a page
 * ranks things. The room you are next to gets the hero block, the busiest few
 * get a chip, and everything else is a ruled list that can grow without ever
 * changing its shape. Ten rooms and a hundred rooms both read as designed.
 *
 * THE COLOUR: every room owns one flat saturated ink, and its position in the
 * list decides it. That is what makes the page look composed rather than
 * generated, and it means a new room GURU builds is dressed the moment it
 * appears, with nothing chosen for it.
 *
 * ORDER: press and hold any room and drag. The order is the one the numen also
 * rewrites by voice, so the drag and the voice write the same store.
 */
@Composable
fun LobbyScreen(
    navController: NavHostController,
    viewModel: LobbyViewModel = koinViewModel(),
    tilesViewModel: LobbyTilesViewModel = koinViewModel(),
) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val tiles by tilesViewModel.tiles.collectAsStateWithLifecycle()

    // Today's calendar count and the media library count are snapshot queries,
    // so they are re-read every time the lobby comes into view.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshTodayEvents()
        viewModel.refreshMediaCount()
    }

    // The order is local until a drag is dropped. Nothing writes mid-drag: the
    // drop is the commit, exactly as the previous surface behaved, so an
    // interrupted drag cannot leave the saved order half-moved.
    var tileList by remember { mutableStateOf<List<LobbyTile>>(emptyList()) }
    // Add room is a page-level action from the front door, so the sheet that asks
    // for the name lives above the scroll rather than inside it.
    var showAddRoom by remember { mutableStateOf(false) }
    var dragInFlight by remember { mutableStateOf(false) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    androidx.compose.runtime.LaunchedEffect(tiles) {
        if (tileList.isEmpty() || !dragInFlight) tileList = tiles
    }

    // THE PAGE IS FIXED AT THE TOP AND LIVE BELOW IT.
    //
    // The hero is the Portal and the three chips are Add room, Settings and Ottio,
    // because those are the four things a person reaches for by hand rather than by
    // whatever happens to have the highest count today. Everything that is ranked
    // and dragged lives under the ALL ROOMS band, where a long list cannot push the
    // front door off the page.
    //
    // Ranked-by-count was the previous rule and it failed in a specific way: a busy
    // Skills count promoted Skills to the hero and the Portal, which is home, slid
    // down the page or off it entirely.
    val liveCounts = tileCounts(tiles = tileList, counts = counts)
    val ranked = rankRooms(tileList, liveCounts)

    // Fixed chips, in the order the page shows them, resolved by key so a rename or
    // a reorder in the tile store cannot leave a blank block on the front door.
    val chipKeys = listOf("static:settings", "static:ottio")
    val chips = ranked.filter { it.tile.key in chipKeys }
        .sortedBy { chipKeys.indexOf(it.tile.key) }

    // Everything else in the ruled list, in the saved drag order, without the four
    // that the top of the page already shows.
    val pinned = chipKeys + "static:portal"
    val rest = ranked.filter { it.tile.key !in pinned }

    // THE LOBBY DRAWS NO BACKDROP OF ITS OWN.
    //
    // The app root paints the house background once for every page, so a second
    // copy here would stack the same image on itself and go muddy. The lobby is
    // transparent and inherits, which is what "standard on every page" means.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {

            LobbyMasthead(roomTotal = tileList.size)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 20.dp),
            ) {
                item(key = "hero") {
                    LobbyHero(
                        // The Portal is the conversation, so it is the room the page
                        // leads with. Home is the first thing a person wants, not the
                        // busiest queue.
                        title = "Portal",
                        // A conversation has no count worth printing, so the hero shows
                        // its door and nothing else rather than a dash pretending to be
                        // a measurement.
                        count = null,
                        ink = Memphis.inkFor(0),
                        onOpen = { navController.navigate(Screen.PortalScreen) { launchSingleTop = true } },
                    )
                }

                item(key = "chips") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // ADD ROOM leads the chips because making a room is the thing a
                        // person does at the front door rather than inside one.
                        LobbyChip(
                            modifier = Modifier.weight(1f),
                            label = "Add room",
                            count = null,
                            ink = Memphis.inkFor(1),
                            onOpen = { showAddRoom = true },
                        )
                        chips.forEachIndexed { chipIndex, room ->
                            LobbyChip(
                                modifier = Modifier.weight(1f),
                                label = room.label,
                                count = room.count,
                                ink = Memphis.inkFor(chipIndex + 2),
                                onOpen = { openRoom(navController, room.tile) },
                            )
                        }
                    }
                }

                item(key = "section") {
                    LobbySectionBand(
                        label = "All rooms",
                        total = tileList.size,
                        modifier = Modifier.padding(top = 22.dp),
                    )
                }

                itemsIndexed(rest, key = { _, room -> room.tile.key }) { index, room ->
                    LobbyRoomRow(
                        label = room.label,
                        count = room.count,
                        ink = Memphis.inkFor(index + 4),
                        comingSoon = isComingSoon(room.tile),
                        lifted = draggingKey == room.tile.key,
                        onOpen = { openRoom(navController, room.tile) },
                        modifier = Modifier.pointerInput(room.tile.key, rest.size) {
                            // Long-press then drag, on the list rows. The list is
                            // the part of the page that can run to any length, so
                            // it is the part that needs reordering by hand. A
                            // drag that ends on a different row commits the new
                            // order once, on the drop; nothing writes while the
                            // finger is down.
                            var travelled = 0f
                            var fromIndex = index
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingKey = room.tile.key
                                    dragInFlight = true
                                    travelled = 0f
                                    fromIndex = rest.indexOfFirst { it.tile.key == room.tile.key }
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    travelled += dragAmount.y
                                },
                                onDragEnd = {
                                    val rowHeightPx = with(density) { 56.dp.toPx() }
                                    val steps = if (rowHeightPx > 0f) (travelled / rowHeightPx).toInt() else 0
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
            }

            // THE PORTAL'S OWN CHAT BAR, NOT A LOOKALIKE.
            //
            // [PortalChatBarHost] renders the same PortalChatBar the portal
            // renders, against the same Activity-scoped PortalViewModel, so the
            // lobby and the portal share one draft, one send path and one
            // conversation. An earlier version drew its own bar here, which was
            // a second input with different behaviour that merely resembled the
            // real one.
            PortalChatBarHost(
                modifier = Modifier.fillMaxWidth(),
                // DOOR MODE. The lobby shows no conversation, so typing here
                // would put words into a chat nobody can see. A tap or a send
                // opens the portal instead, which is what was asked for.
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

        // ADD ROOM. A blank vessel is created here so the room exists and appears in
        // the lobby immediately; what goes inside it is written afterwards through
        // conversation, which is how the module-forge doctrine already works.
        if (showAddRoom) {
            AddRoomSheet(
                onDismiss = { showAddRoom = false },
                onCreate = { name ->
                    showAddRoom = false
                    // The tiles view model owns the module repository, so it is the
                    // one that can actually make a room. The counts view model only
                    // reads numbers.
                    tilesViewModel.createRoom(name)
                },
            )
        }
    }
}

/**
 * One room as the page needs it: the label to print, the number to print, and
 * the tile to open.
 *
 * Built once, up front, so the hero, the chips and the list cannot disagree
 * about what a room is called or how much it holds.
 */
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
 * Rooms with something in them lead, because a room with three events today is
 * more use than one with nothing. Empty rooms keep their saved order behind
 * them, so the page re-ranks as data arrives without ever shuffling rooms a
 * human deliberately arranged.
 */
private fun rankRooms(tiles: List<LobbyTile>, counts: Map<String, Int>): List<RankedRoom> {
    val all = tiles.map { tile ->
        RankedRoom(
            tile = tile,
            label = tile.title,
            count = counts[tile.key] ?: 0,
        )
    }
    val busy = all.filter { it.count > 0 }.sortedByDescending { it.count }
    val idle = all.filter { it.count == 0 }
    return busy + idle
}

private fun isComingSoon(tile: LobbyTile): Boolean =
    tile.key == "static:journal" || tile.key == "static:projects"

/**
 * Move one room by a number of rows, clamped to the list.
 *
 * A drag has already told us how far the finger travelled, so this turns a
 * pixel distance into a position. Clamping rather than wrapping matters: a drag
 * past the end of the list should stop at the end, never teleport to the top.
 */
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
        // journal and projects are built but closed: tapping does nothing,
        // which is the honest behaviour rather than a dead screen.
        else -> Unit
    }
}

/** The masthead: the word LOBBY, the date, and the ink rule under it. */
@Composable
private fun LobbyMasthead(roomTotal: Int) {
    val today = remember {
        SimpleDateFormat("EEE dd MMM", Locale.UK).format(Date())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp)
    ) {
        // The date sits alone on the top line. The black GURU badge that used to
        // lead it is gone: the floating icon is the brand, and a badge that
        // repeats it just competes with the page.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = today.uppercase(),
                style = captionStyle(10f),
                modifier = Modifier
                    .hardShadow(offset = 3.dp)
                    .background(Memphis.Cyan)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }

        // One line: the word, and how many rooms it holds. No icon beside it and
        // no wordmark under it, because both were repeating a brand the floating
        // icon already carries, and neither was earning the space.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.lobby).uppercase(),
                style = posterStyle(42f),
            )
            Text(
                text = "$roomTotal rooms",
                style = captionStyle(10f),
                modifier = Modifier
                    .hardShadow(offset = 2.dp)
                    .background(Memphis.Yellow)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
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

/** The hero: the room you are next to, flooded in its ink. */
@Composable
private fun LobbyHero(
    title: String,
    /** Null when the room has no number worth printing, which is the Portal. */
    count: Int?,
    ink: Color,
    onOpen: () -> Unit,
) {
    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clickable { onOpen() },
        color = ink,
        borderWidth = 3.dp,
        shadowOffset = 8.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 12.dp)) {
            Text(
                text = "NEXT UP",
                style = captionStyle(9.5f, Memphis.Ink),
                modifier = Modifier
                    .background(Memphis.Paper)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title.uppercase(),
                    style = posterStyle(40f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (count != null) {
                    Text(
                        text = if (count > 0) "$count" else "—",
                        style = posterStyle(64f),
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .offset(y = 2.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "OPEN ROOM",
                    style = captionStyle(9f, Memphis.Ink),
                )
                Text(
                    text = "→",
                    style = posterStyle(18f),
                )
            }
        }
    }
}

/** A chip: a room with something in it, sized to share the row. */
@Composable
private fun LobbyChip(
    modifier: Modifier = Modifier,
    label: String,
    /** Null for a chip that is an action rather than a room with a total. */
    count: Int?,
    ink: Color,
    onOpen: () -> Unit,
) {
    MemphisSurface(
        modifier = modifier
            .height(78.dp)
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
            Text(
                text = when {
                    count == null -> "+"
                    count > 0 -> "$count"
                    else -> "—"
                },
                style = posterStyle(26f),
                maxLines = 1,
            )
            Text(
                text = label.uppercase(),
                style = captionStyle(8.5f, Memphis.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The black band that separates one group of rooms from the next. */
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

/** One room in the ruled list: its ink swatch, its name, its number, a door. */
@Composable
private fun LobbyRoomRow(
    label: String,
    count: Int,
    ink: Color,
    comingSoon: Boolean,
    lifted: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MemphisSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .alpha(if (lifted) 0.8f else 1f)
            .then(if (comingSoon) Modifier else Modifier.clickable { onOpen() }),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .rotate(if (comingSoon) -8f else 6f)
                    .background(if (comingSoon) ink.copy(alpha = 0.35f) else ink)
                    .then(Modifier.size(24.dp)),
            )
            Text(
                text = label.uppercase(),
                style = posterStyle(15f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            )
            if (comingSoon) {
                Text(
                    text = "SOON",
                    style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(Memphis.Paper)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Text(
                text = if (count > 0) "$count" else "—",
                style = posterStyle(12f, if (count > 0) Memphis.Ink else Memphis.Ink.copy(alpha = 0.3f)),
                modifier = Modifier
                    .background(if (count > 0) Memphis.Paper else Memphis.Paper)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(
                text = "›",
                style = posterStyle(15f),
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}

/**
 * Name a new room.
 *
 * WHY IT ASKS FOR ONE THING. A room is a vessel: it has a name and, for now, nothing
 * in it. The composition inside is written later through conversation, which is how
 * module-forge already works. Asking for a description, a category and a colour here
 * would be asking a person to fill in a form for a room they cannot see yet.
 *
 * The sheet sits on the same paper as everything else: fixed head, one field, fixed
 * foot, so the Create button cannot scroll off the bottom of a short phone.
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
                                // A room name is one line. A newline in a tile would
                                // break the row that has to hold it.
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
                    // The button is drawn as unavailable while the field is empty rather
                    // than refusing after the press: a person can see there is nothing
                    // to make yet.
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
