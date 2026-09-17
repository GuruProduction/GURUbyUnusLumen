package com.unuslumen.app.guru.presentation.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.unuslumen.app.guru.presentation.main.MainViewModel.PermissionGateState
import com.unuslumen.app.guru.presentation.main.MainViewModel.PermissionToggleState
import com.unuslumen.app.util.permissions.Permission

@Composable
fun PermissionGateScreen(
    state: PermissionGateState,
    onGrantAll: () -> Unit,
    onTogglePermission: (Permission) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GURU requires full device access",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Close",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Grant permissions now or close and do it later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                val grouped = state.permissions.groupBy { it.category }

                grouped.forEach { (category, permissions) ->
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    )

                    permissions.forEach { toggle ->
                        PermissionToggleRow(
                            toggle = toggle,
                            onToggle = { onTogglePermission(toggle.permission) }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onGrantAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Grant All",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionToggleRow(
    toggle: PermissionToggleState,
    onToggle: () -> Unit
) {
    val green = Color(0xFF4CAF50)
    val red = Color(0xFFE53935)
    val grey = Color(0xFF9E9E9E)
    val blue = Color(0xFF2196F3)

    val toggleColor = when {
        !toggle.existsOnCurrentApi -> blue
        !toggle.hasHardware -> green
        !toggle.isEnabled -> grey
        toggle.isGranted -> green
        else -> red
    }

    val statusText = when {
        !toggle.existsOnCurrentApi -> "Not required on this Android version"
        !toggle.hasHardware -> "Not available on this device"
        !toggle.isEnabled -> "Grant ${getDependencyName(toggle.permission)} first"
        toggle.isGranted -> "Granted"
        else -> "Tap to grant"
    }

    val isInteractive = toggle.isEnabled && toggle.existsOnCurrentApi && toggle.hasHardware

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = toggleColor,
            modifier = Modifier.size(12.dp)
        ) {}

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = toggle.label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (!toggle.hasHardware || !toggle.existsOnCurrentApi)
                        TextDecoration.LineThrough
                    else TextDecoration.None
                ),
                color = if (isInteractive) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = toggle.isGranted,
            onCheckedChange = { onToggle() },
            enabled = isInteractive,
            colors = SwitchDefaults.colors(
                checkedTrackColor = green,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = grey,
                uncheckedThumbColor = Color.White,
                disabledCheckedTrackColor = green.copy(alpha = 0.5f),
                disabledUncheckedTrackColor = grey.copy(alpha = 0.3f)
            )
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