// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.modules

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.navigation.Screen
import org.koin.androidx.compose.koinViewModel
import java.io.File

/**
 * One module room, full screen.
 *
 * The room renders through ModuleCanvas: the numen's composed HTML on the
 * app's own themed canvas, with taps riding the bridge home to conversation.
 * Empty drafts and broken compositions state their state honestly — the
 * room self-heals through chat, so its screen points home.
 */
@Composable
fun ModuleScreen(
    navController: NavHostController,
    moduleId: String,
    viewModel: ModuleViewModel = koinViewModel()
) {
    LaunchedEffect(moduleId) { viewModel.bind(moduleId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // Portal background stack — the room sits inside the app's own look.
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

        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Opening the room…",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color(0xFF2B241C).copy(alpha = 0.6f)
                        )
                    )
                }
            }
            state.notFound -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "This room is gone.",
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = Color(0xFF2B241C),
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "Ask me and I'll build it again.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color(0xFF2B241C).copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            else -> {
                val module = state.module

                // Portal-style header: the room's own name, ink on paper.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = (module?.displayName ?: "Room").uppercase(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = Color(0xFF2B241C),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.32.em
                        )
                    )
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .width(38.dp)
                            .height(2.dp)
                            .background(Color(0xFF9A9384), RoundedCornerShape(2.dp))
                    )
                }

                // The room itself, live from the repository flow. The numen
                // re-saves; this re-renders in front of the user. The
                // ViewModel holds the webview so its data pushes hydrate
                // the room the moment flows change.
                Box(Modifier.fillMaxSize()) {
                    if (module != null && module.compositionHtml.isNotBlank()) {
                        ModuleCanvas(
                            html = module.compositionHtml,
                            css = module.compositionCss,
                            js = module.compositionJs,
                            moduleId = module.id,
                            onEvent = { name, payload ->
                                // Room taps route through conversation events,
                                // the same contract portal events use.
                                android.util.Log.d("ModuleScreen", "Module event: $name -> $payload")
                            },
                            onWebViewCreated = { wv -> viewModel.setCanvas(wv) },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Doctrine: a door exists but the room was never
                        // composed. Honest state, pointing home to fix it.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "This room has no floor yet.",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        color = Color(0xFF2B241C),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "Ask me to fill it and I'll compose it.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = Color(0xFF2B241C).copy(alpha = 0.7f)
                                    ),
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}