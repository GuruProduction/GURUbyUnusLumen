package com.unuslumen.app.presentation.integrations.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.unuslumen.app.presentation.components.ExperimentalBadge
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.theme.guruTheme
import com.mohamedrejeb.calf.picker.FilePickerFileType
import com.mohamedrejeb.calf.picker.FilePickerSelectionMode
import com.mohamedrejeb.calf.picker.rememberFilePickerLauncher

@Composable
fun ExternalNotesCard(
    isEnabled: Boolean,
    selectedFolder: String?,
    onFolderSelected: (String) -> Unit,
    onSwitchToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(25.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.external_notes),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    ExperimentalBadge()
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { onSwitchToggled(it) }
                )
            }
            Text(
                text = stringResource(R.string.external_notes_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            WarningCard()
            AnimatedVisibility(isEnabled) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    SourceFolderCard(
                        selectedFolder = selectedFolder,
                        onFolderSelected = onFolderSelected
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceFolderCard(
    selectedFolder: String?,
    onFolderSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val chooseDirectoryLauncher = rememberFilePickerLauncher(
        FilePickerFileType.Folder,
        selectionMode = FilePickerSelectionMode.Single
    ) { files ->
        files.firstOrNull()?.let { file ->
            onFolderSelected(file.uri.toString())
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp)
    ) {
        Text(
            text = stringResource(R.string.source_folder),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(0.dp),
            colors = CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(0.1f).compositeOver(
                        MaterialTheme.colorScheme.surfaceVariant
                    )
            ),
            onClick = { chooseDirectoryLauncher.launch() }
        ) {
            Text(
                text = selectedFolder ?: stringResource(R.string.select_source_folder_for_notes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun WarningCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Text(
            text = stringResource(R.string.external_notes_warning),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview
@Composable
private fun ExternalNotesCardDisabledPreview() {
    guruTheme {
        ExternalNotesCard(
            isEnabled = false,
            selectedFolder = null,
            onFolderSelected = {},
            onSwitchToggled = {}
        )
    }
}

@Preview
@Composable
private fun ExternalNotesCardEnabledPreview() {
    guruTheme {
        ExternalNotesCard(
            isEnabled = true,
            selectedFolder = null,
            onFolderSelected = {},
            onSwitchToggled = {}
        )
    }
}

@Preview
@Composable
private fun ExternalNotesCardEnabledWithFolderPreview() {
    guruTheme {
        ExternalNotesCard(
            isEnabled = true,
            selectedFolder = "/path/to/my/notes",
            onFolderSelected = {},
            onSwitchToggled = {}
        )
    }
}
