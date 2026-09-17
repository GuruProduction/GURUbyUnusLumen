package com.unuslumen.app.presentation.components

import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Portal design language for Settings.
 *
 * Brand tokens mirror PortalScreen: warm ink on aged cream paper, gold
 * monospace accents, hairline gold borders. No cyan anywhere, no neon.
 * Every card is warm paper, every label a quiet ink caption, every action
 * gold. This file is the single place the settings look lives.
 */

// ─── Portal brand tokens (same palette PortalScreen paints with) ───
val SettingsInk = Color(0xFF2B241C)
val SettingsInkSoft = SettingsInk.copy(alpha = 0.72f)
val SettingsInkFaint = SettingsInk.copy(alpha = 0.5f)
val SettingsPaperHigh = Color(0xFFF0E8DA)
val SettingsPaperLow = Color(0xFFE4D9C6)
val SettingsPaperField = Color(0xFFFAF5EA)
val SettingsGold = Color(0xFFDAA520)
val SettingsGoldDeep = Color(0xFFB8956A)
val SettingsSlate = Color(0xFF5B7C99) // grey slate-blue — the non-cyan accent

/** Vertical wash behind every settings page: cream fading to warm bone. */
val SettingsPageBrush = Brush.verticalGradient(
    colors = listOf(
        SettingsPaperHigh.copy(alpha = 0.96f),
        SettingsPaperLow.copy(alpha = 0.9f),
    )
)

/** One rounded warm-paper card with a hairline gold border. */
@Composable
fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                SettingsPaperHigh.copy(alpha = 0.92f),
                                SettingsPaperLow.copy(alpha = 0.88f),
                            )
                        )
                    )
            ) {
                content()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SettingsGold.copy(alpha = 0.35f))
            )
        }
    }
}

/** Gold monospace caption above a block — the Portal slash-menu signature. */
@Composable
fun SettingsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        ),
        color = SettingsInk.copy(alpha = 0.65f),
        modifier = modifier,
    )
}

/**
 * One settings row on warm paper. Icon in a soft ink chip on the left,
 * title + optional caption beside it, value/switch trailing.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: Painter? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        color = SettingsInk.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    painter = icon,
                    contentDescription = title,
                    modifier = Modifier.size(20.dp),
                    tint = SettingsInk.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = SettingsInk,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = SettingsInkSoft,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * Settings row whose trailing is a dropdown. The whole row opens the menu;
 * the current selection shows in gold monospace with a drop arrow.
 */
@Composable
fun SettingsDropdownRow(
    title: String,
    selectedLabel: String,
    options: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: Painter? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            title = title,
            caption = caption,
            icon = icon,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedLabel,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        color = SettingsGoldDeep,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = SettingsGoldDeep,
                    )
                }
            },
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            options.forEach { (label, action) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SettingsInk,
                        )
                    },
                    onClick = {
                        action()
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Warm-ink outlined text field used across settings entry forms. */
@Composable
fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SettingsInk.copy(alpha = 0.65f),
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder?.let {
                { Text(it, color = SettingsInkFaint, style = MaterialTheme.typography.bodyMedium) }
            },
            singleLine = singleLine,
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SettingsGold.copy(alpha = 0.8f),
                unfocusedBorderColor = SettingsGold.copy(alpha = 0.3f),
                focusedTextColor = SettingsInk,
                unfocusedTextColor = SettingsInk,
                cursorColor = SettingsGold,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Full-width ink button with gold border for the primary action of a card. */
@Composable
fun SettingsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(
                    listOf(SettingsInk, Color(0xFF4A3E2E))
                ),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFFF0E8DA),
        )
    }
}

/**
 * Tappable nav link row. Icon in a soft ink chip on the left — the same chip
 * every other settings row uses — title beside it. The icon replaces the old
 * gold monospace key letters, which read as clutter instead of design.
 */
@Composable
fun SettingsNavLinkRow(
    title: String,
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    color = SettingsInk.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                painter = icon,
                contentDescription = title,
                modifier = Modifier.size(20.dp),
                tint = SettingsInk.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = SettingsInk.copy(alpha = 0.85f),
            maxLines = 2,
        )
    }
}

/** Warm-paper switch row. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: Painter? = null,
) {
    SettingsRow(
        title = title,
        caption = caption,
        icon = icon,
        modifier = modifier,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = { onCheck(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = SettingsPaperHigh,
                    checkedTrackColor = SettingsGoldDeep,
                    uncheckedThumbColor = SettingsPaperHigh,
                    uncheckedTrackColor = SettingsInk.copy(alpha = 0.25f),
                ),
            )
        },
        onClick = { onCheck(!checked) },
    )
}

/** Divider between rows inside a section card — faint ink hairline. */
@Composable
fun SettingsRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(SettingsInk.copy(alpha = 0.1f))
    )
}