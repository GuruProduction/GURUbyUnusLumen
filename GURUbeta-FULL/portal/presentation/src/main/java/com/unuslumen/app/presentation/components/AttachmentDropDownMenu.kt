package com.unuslumen.app.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.frostedGlass
import io.github.fletchmckee.liquid.LiquidState

@Composable
fun AttachmentDropDownMenu(
    expanded: Boolean,
    excludedItems: List<AttachmentMenuItem>,
    liquidState: LiquidState,
    onDismiss: () -> Unit,
    onItemClick: (AttachmentMenuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(excludedItems) {
        AttachmentMenuItem.entries.filterNot { it in excludedItems }
    }
    val shape = RoundedCornerShape(22.dp)
    DropdownMenu(
        modifier = modifier
            .frostedGlass(liquidState = liquidState, shape = shape, frost = 7.dp),
        expanded = expanded,
        shape = shape,
        onDismissRequest = onDismiss,
        shadowElevation = 4.dp,
        properties = PopupProperties(focusable = false)
    ) {
        items.forEach {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(it.titleRes),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(it.iconRes),
                        contentDescription = stringResource(it.titleRes),
                        modifier = Modifier.size(22.dp)
                    )
                },
                onClick = { onItemClick(it) }
            )
        }
    }
}

enum class AttachmentMenuItem(val titleRes: Int, val iconRes: Int) {
    Note(R.string.add_note, R.drawable.ic_add_note),
    Task(R.string.add_task, R.drawable.ic_check),
    CalendarEvents(R.string.calendar_events_next_7_days, R.drawable.ic_calendar),
    File(R.string.attach_file, R.drawable.ic_attach)
}