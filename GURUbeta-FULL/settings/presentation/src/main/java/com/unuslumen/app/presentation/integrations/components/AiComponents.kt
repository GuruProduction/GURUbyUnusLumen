package com.unuslumen.app.presentation.integrations.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.unuslumen.app.ui.R

@Composable
fun SavableTextField(
    modifier: Modifier = Modifier,
    text: String,
    infoURL: String? = null,
    label: String,
    onSave: (String) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var localText by remember { mutableStateOf("") }
    val showSave = localText != text
    LaunchedEffect(text) {
        localText = text
    }
    Column(
        modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = localText,
            onValueChange = { localText = it },
            label = { Text(text = label) },
            shape = RoundedCornerShape(15.dp),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (infoURL != null) {
                    IconButton(onClick = { uriHandler.openUri(infoURL) }) {
                        Icon(
                            painterResource(id = R.drawable.ic_info),
                            contentDescription = null
                        )
                    }
                }
            },
        )
        AnimatedVisibility(showSave) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                onClick = { onSave(localText.trim()) }
            ) {
                Text(text = stringResource(R.string.save))
            }
        }
    }
}

@Composable
fun CustomURLSection(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    url: String,
    label: String,
    showCheckbox: Boolean = true,
    warningText: String? = null,
    onSave: (String) -> Unit,
    onEnable: (Boolean) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        if (showCheckbox) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = enabled, onCheckedChange = onEnable)
                Text(
                    text = stringResource(R.string.custom_base_url),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        AnimatedVisibility(!showCheckbox || enabled) {
            Column {
                SavableTextField(
                    text = url,
                    label = label,
                    onSave = onSave
                )
                if (warningText != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = warningText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}