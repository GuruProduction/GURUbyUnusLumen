// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle

/**
 * The masthead every room wears.
 *
 * THIS REPLACED A MATERIAL TOP APP BAR. That bar drew a grey surface, a small
 * bold title and nothing else, which is why every room looked like a settings
 * page that happened to share a background with the lobby. The lobby is a printed
 * poster: paper, ink rules, poster type, a stamp in the corner. A room is now the
 * same page, so the app reads as one thing.
 *
 * THE DOOR. Every room gets a back arrow on the left, because there was no way
 * out of a room except the system gesture, and a system gesture is not a door.
 * The lobby and the portal provide no [LocalRoomNavigation], so they get no
 * arrow: both are home, and an arrow there would either do nothing or leave the
 * app.
 *
 * THE STAMP is the optional trailing slot, used where a screen has one fact worth
 * printing in the corner: how many rooms, how many agents are on, that a draft is
 * unsaved.
 */
@Composable
fun guruAppBar(
    title: String,
    modifier: Modifier = Modifier,
    /** Optional short fact for the corner, in the lobby's stamp style. */
    stamp: String? = null,
    /** The stamp's fill. Defaults to the lobby's cyan date chip. */
    stampColor: androidx.compose.ui.graphics.Color = Memphis.Cyan,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val navigation = LocalRoomNavigation.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 10.dp)
    ) {
        // THE TOP LINE: the door on the left, the stamp on the right, and any
        // actions a screen adds between them.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // The door, drawn as one more printed block so it belongs to the page.
            if (navigation != null && navigation.canGoBack()) {
                MemphisSurface(
                    modifier = Modifier.clickable { navigation.goBack() },
                    color = Memphis.Paper,
                    borderWidth = 2.5.dp,
                    shadowOffset = 4.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, end = 11.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "‹", style = posterStyle(15f))
                        Spacer(Modifier.width(6.dp))
                        Text(text = "BACK", style = captionStyle(9.5f))
                    }
                }
            } else {
                // An empty placeholder keeps the stamp on the right when there is
                // no door, so the corner does not move between screens.
                Spacer(Modifier.width(1.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                actions(this)
                if (stamp != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stamp.uppercase(),
                        style = captionStyle(10f),
                        modifier = Modifier
                            .background(stampColor)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
        }

        // THE WORD, in the poster voice, enormous.
        Text(
            text = title.uppercase(),
            style = posterStyle(38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 14.dp),
        )

        // THE RULE. One ink line under every masthead, exactly as the lobby has.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(3.dp)
                .background(Memphis.Ink)
        )
    }
}
