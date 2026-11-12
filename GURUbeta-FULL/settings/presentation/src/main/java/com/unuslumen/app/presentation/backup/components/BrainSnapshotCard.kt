// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.backup.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unuslumen.app.presentation.backup.BrainSnapshotUiState
import com.unuslumen.app.ui.R

/**
 * The brain snapshot card: the owner's route to a brain that survives a new phone.
 *
 * WHY IT IS SEPARATE FROM "EXPORT" ABOVE IT. The export section writes notes, tasks,
 * journal entries and bookmarks as JSON or Markdown, which is a readable copy of what a
 * person typed. This card seals the whole database, including conversations, memory facts,
 * tool history and the prompt stack, and it is the only artefact that brings a GURU back
 * on a different handset. Conflating the two would let someone believe a notes export was
 * a whole backup.
 *
 * THE PASSPHRASE COPY IS THE HONEST PART. It is not stored, not remembered and not
 * recoverable, so that warning sits on screen before the button is pressed rather than in a
 * dialog afterwards. A snapshot whose passphrase is lost is a file nobody can ever open.
 *
 * THE CARD OWNS THE PASSPHRASE, THE SCREEN OWNS THE PICKER. The passphrase is typed here,
 * so it is handed out with the request: the screen launches a picker and cannot know what
 * was typed unless this call carries it. Splitting it the other way would need the card to
 * hold a launcher and the screen to hold the passphrase, which is two owners for one fact.
 */
@Composable
fun BrainSnapshotCard(
    state: BrainSnapshotUiState,
    onRequestCreate: (passphrase: String) -> Unit,
    onRequestRestore: (passphrase: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var passphrase by remember { mutableStateOf("") }
    var restorePassphrase by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.brain_snapshot_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = stringResource(R.string.brain_snapshot_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.brain_snapshot_passphrase_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.brain_snapshot_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onRequestCreate(passphrase) },
                enabled = !state.busy && passphrase.length >= MIN_PASSPHRASE_LENGTH,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (state.busy) {
                        stringResource(R.string.brain_snapshot_working)
                    } else {
                        stringResource(R.string.brain_snapshot_create)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            AnimatedVisibility(state.message != null) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = state.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.succeeded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.brain_restore_title),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.brain_restore_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = restorePassphrase,
                onValueChange = { restorePassphrase = it },
                label = { Text(stringResource(R.string.brain_restore_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onRequestRestore(restorePassphrase) },
                enabled = !state.busy && restorePassphrase.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.brain_restore_choose),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Matches the tool's own floor, so the screen cannot offer a passphrase the engine refuses.
 *
 * Duplicated as a constant rather than shared across a module boundary that does not exist
 * between the tool layer and presentation: the alternative is a dependency from the
 * settings screen onto the whole tool system for one integer.
 */
private const val MIN_PASSPHRASE_LENGTH = 8
