// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.unuslumen.app.guru.presentation.main.MainViewModel.PermissionGateState
import com.unuslumen.app.guru.presentation.main.MainViewModel.PermissionToggleState
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBody
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.util.permissions.Permission
import androidx.compose.ui.unit.TextUnit

/**
 * The permission gate, as an offer rather than a demand, in the lobby's colour.
 *
 * THE COPY. It opened with "GURU requires full device access", which reads as a
 * condition of entry, put a red dot against everything not yet granted, and offered
 * one button called Grant All. It now says what the screen is for, states that GURU
 * works without any of it, and gives two doors: Allow all, or decide later.
 *
 * THE COLOUR. A first pass drew this page in paper and ink alone, which was wrong:
 * the lobby and every room in it give each block its own colour from the same
 * rotation, and a permissions page with none of it was the one page in the app that
 * did not look like the app. Every category now takes an ink from [Memphis.inkFor],
 * every row carries that colour as a swatch, and a granted switch fills with it.
 *
 * Every callback, every state field and every permission fact is unchanged.
 */
@Composable
fun PermissionGateScreen(
    state: PermissionGateState,
    onGrantAll: () -> Unit,
    onTogglePermission: (Permission) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Memphis.Ink.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center
    ) {
        MemphisSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .heightIn(max = 640.dp)
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
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "WHAT GURU MAY USE",
                            style = posterStyle(26f),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        MemphisSurface(
                            modifier = Modifier.clickable { onDismiss() },
                            color = Memphis.Cyan,
                            borderWidth = 2.5.dp,
                            shadowOffset = 3.dp,
                        ) {
                            Box(Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                                Text("CLOSE", style = captionStyle(8.5f))
                            }
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(3.dp)
                            .background(Memphis.Ink)
                    )

                    Text(
                        text = "None of this is needed to use GURU. Each one is a choice you can " +
                            "make now, later, or never, and you can change any of them in Settings.",
                        style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.75f)).copy(
                            fontFamily = MemphisBody,
                            letterSpacing = TextUnit.Unspecified,
                        ),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .padding(start = 18.dp, end = 18.dp, top = 10.dp)
                        .verticalScroll(scrollState),
                ) {
                    val grouped = state.permissions.groupBy { it.category }

                    grouped.entries.forEachIndexed { bandIndex, (category, permissions) ->
                        val bandInk = Memphis.inkFor(bandIndex)

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .background(bandInk)
                                .border(2.5.dp, Memphis.Ink)
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = category.uppercase(),
                                style = captionStyle(9f, Memphis.Ink),
                            )
                            Text(
                                text = "${permissions.count { it.isGranted }}/${permissions.size}",
                                style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.65f)),
                            )
                        }

                        permissions.forEach { toggle ->
                            PermissionToggleRow(
                                toggle = toggle,
                                ink = bandInk,
                                onToggle = { onTogglePermission(toggle.permission) }
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                }

                Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp).background(Memphis.Ink))

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
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("DECIDE LATER", style = captionStyle(9f))
                        }
                    }
                    MemphisSurface(
                        modifier = Modifier.weight(1f).clickable { onGrantAll() },
                        color = Memphis.Yellow,
                        borderWidth = 2.5.dp,
                        shadowOffset = 4.dp,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("ALLOW ALL", style = captionStyle(9f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One permission, carrying its category's colour.
 *
 * The swatch is the same block shape the agents room uses for an agent, so a person
 * can see at a glance which group a row belongs to without reading the band above it.
 * The switch fills with that colour when it is on and stays paper when it is off, so
 * neither state is dressed as a fault.
 */
@Composable
private fun PermissionToggleRow(
    toggle: PermissionToggleState,
    ink: Color,
    onToggle: () -> Unit
) {
    val statusText = when {
        toggle.blockedByRestrictedSettings -> "One extra step on this Android version"
        !toggle.existsOnCurrentApi -> "This Android version does not ask for it"
        !toggle.hasHardware -> "This phone does not have it"
        !toggle.isEnabled -> "Turn on ${getDependencyName(toggle.permission)} first"
        toggle.isGranted -> "Allowed"
        else -> "Not allowed yet"
    }

    val isInteractive =
        toggle.isEnabled && toggle.existsOnCurrentApi && toggle.hasHardware &&
            !toggle.blockedByRestrictedSettings

    val unavailable = !toggle.hasHardware || !toggle.existsOnCurrentApi

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(if (unavailable) ink.copy(alpha = 0.2f) else ink)
                .border(2.dp, Memphis.Ink.copy(alpha = if (unavailable) 0.3f else 1f))
        )

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = toggle.label,
                style = posterStyle(11.5f, Memphis.Ink.copy(alpha = if (unavailable) 0.35f else 1f)),
            )
            Text(
                text = statusText.uppercase(),
                style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.5f)),
                modifier = Modifier.padding(top = 2.dp),
            )
            toggle.restrictedSettingsSentence?.let { sentence ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = sentence,
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.8f)),
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        FlatPermissionSwitch(
            checked = toggle.isGranted,
            enabled = isInteractive,
            ink = ink,
            onChange = { onToggle() },
        )
    }
}

/** A switch in the page's grammar: a rectangle that fills with its colour when on. */
@Composable
private fun FlatPermissionSwitch(
    checked: Boolean,
    enabled: Boolean,
    ink: Color,
    onChange: () -> Unit,
) {
    val fill = when {
        !enabled -> Memphis.Ink.copy(alpha = 0.12f)
        checked -> ink
        else -> Memphis.Paper
    }

    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 26.dp)
            .background(fill)
            .border(2.5.dp, Memphis.Ink.copy(alpha = if (enabled) 1f else 0.35f))
            .then(if (enabled) Modifier.clickable { onChange() } else Modifier),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(18.dp)
                .background(if (checked) Memphis.Paper else Memphis.Ink.copy(alpha = if (enabled) 1f else 0.35f)),
        )
    }
}

private fun getDependencyName(permission: Permission): String {
    return when (permission) {
        Permission.ACCESS_BACKGROUND_LOCATION -> "Fine Location"
        Permission.BODY_SENSORS_BACKGROUND -> "Body Sensors"
        else -> ""
    }
}
