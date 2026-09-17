package com.unuslumen.app.guru.presentation.main

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.unuslumen.app.guru.presentation.main.components.LobbyCard
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.theme.DarkGray
import com.unuslumen.app.ui.theme.guruTheme
import org.koin.androidx.compose.koinViewModel

/** Whisper tints — desaturated paper tones that read as one family over the cream. */
private val TintProjects = Color(0xFFE4CEB7)
private val TintCalendar = Color(0xFFDED3E3)
private val TintSettings = Color(0xFFDEDAD3)
private val TintSkills = Color(0xFFD2E2E6)
private val TintNotes = Color(0xFFDDE3D1)
private val TintJournal = Color(0xFFE3D6DB)

/** Header label + divider colours straight from the portal's ink. */
private val PortalInk = DarkGray
private val DividerSlate = Color(0xFF9A9384)

/**
 * Lobby, redrawn as a room inside the portal.
 *
 * The background is the portal's own stack: full-bleed art, warm paper
 * gradient wash, faint GURU watermark. Tiles and the ask pill float on top.
 * Badges are real counts supplied by [LobbyViewModel]; when a count is zero
 * no badge is drawn.
 */
@Composable
fun LobbyScreen(
    navController: NavHostController,
    viewModel: LobbyViewModel = koinViewModel(),
) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()

    // Re-query today's calendar range every time the Lobby enters composition.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshTodayEvents()
    }

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
                item {
                    LobbyCard(
                        title = stringResource(R.string.notes),
                        image = R.drawable.lobby_notes,
                        tint = TintNotes,
                        onClick = { navController.navigate(Screen.NotesScreen) }
                    )
                }
                item {
                    LobbyCard(
                        title = stringResource(R.string.journal),
                        image = R.drawable.lobby_journal,
                        tint = TintJournal,
                        comingSoon = true
                    )
                }
                item {
                    LobbyCard(
                        title = stringResource(R.string.projects),
                        image = R.drawable.lobby_projects,
                        tint = TintProjects,
                        badgeCount = counts.projects,
                        comingSoon = true
                    )
                }
                item {
                    LobbyCard(
                        title = stringResource(R.string.calendar),
                        image = R.drawable.lobby_calendar,
                        tint = TintCalendar,
                        badgeCount = counts.calendarToday,
                        onClick = { navController.navigate(Screen.CalendarScreen) }
                    )
                }
                item {
                    LobbyCard(
                        title = stringResource(R.string.settings),
                        image = R.drawable.lobby_settings,
                        tint = TintSettings,
                        onClick = { navController.navigate(Screen.SettingsScreen) }
                    )
                }
                item {
                    LobbyCard(
                        title = stringResource(R.string.skills),
                        image = R.drawable.lobby_skills,
                        tint = TintSkills,
                        badgeCount = counts.skills,
                        onClick = { navController.navigate(Screen.SkillsScreen) }
                    )
                }
            }
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