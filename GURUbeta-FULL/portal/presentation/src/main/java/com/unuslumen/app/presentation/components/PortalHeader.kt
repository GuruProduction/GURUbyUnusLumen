package com.unuslumen.app.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.theme.guruTheme

private val GoldGlow = Color(0xFFDAA520)
private val WarmInk = Color(0xFF2B241C)

@Composable
fun PortalHeader(
    onLobbyClick: () -> Unit,
    onOtioClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 32.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: 2x2 grid icon → Lobby
        IconButton(onClick = onLobbyClick) {
            Icon(
                painter = painterResource(id = R.drawable.ic_lobby),
                contentDescription = stringResource(id = R.string.portal_header_lobby_content_description),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        // Center: removed gold logo, now embossed as watermark in the portal canvas
        Spacer(modifier = Modifier.weight(1f))

        // Right: Otio marketplace icon
        IconButton(onClick = onOtioClick) {
            Icon(
                painter = painterResource(id = R.drawable.ic_otio_placeholder),
                contentDescription = stringResource(id = R.string.portal_header_otio_content_description),
                tint = WarmInk
            )
        }
    }
}

@Preview
@Composable
private fun PortalHeaderPreview() {
    guruTheme {
        PortalHeader(
            onLobbyClick = {},
            onOtioClick = {}
        )
    }
}