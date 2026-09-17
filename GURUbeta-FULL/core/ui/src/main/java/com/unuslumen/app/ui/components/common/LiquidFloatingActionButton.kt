package com.unuslumen.app.ui.components.common

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.notes.NoteCard
import com.unuslumen.app.ui.theme.DarkGray
import com.unuslumen.app.ui.theme.guruTheme
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState

@Composable
fun LiquidFloatingActionButton(
    modifier: Modifier = Modifier,
    liquidState: LiquidState,
    iconPainter: Painter,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = DarkGray.copy(0.01f),
        shape = FloatingActionButtonDefaults.shape,
        modifier = modifier
            .dropShadow(FloatingActionButtonDefaults.shape) {
                alpha = 0.05f
                this.spread = 7f
                radius = 36f

            }
            .iconButtonGlass(liquidState, shape = FloatingActionButtonDefaults.shape)
    ) {
        Icon(
            painter = iconPainter,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = 0.7f
            ),
            modifier = Modifier
                .padding(16.dp)
                .size(24.dp)
        )
    }
}

@Preview
@Preview(uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LiquidFloatingActionButtonPreview() {
    guruTheme {
        Box(
            Modifier
                .width(180.dp)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.BottomStart
        ) {
            val liquidState = rememberLiquidState()
            NoteCard(
                note = Note(content = "Test Content for note"),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(8.dp)
                    .liquefiable(liquidState)
            ) { }
            LiquidFloatingActionButton(
                liquidState = liquidState,
                iconPainter = painterResource(R.drawable.ic_add),
                contentDescription = "Add",
                modifier = Modifier.padding(4.dp)
            ) {}
        }
    }
}