package com.unuslumen.app.presentation.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.theme.guruTheme

@Composable
fun ExperimentalBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.secondary,
        ),
        shape = CircleShape
    ) {
        Text(
            text = stringResource(R.string.experimental),
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 8.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Preview
@Preview(uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ExperimentalBadgePreview() {
    guruTheme {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            ExperimentalBadge()
        }
    }
}