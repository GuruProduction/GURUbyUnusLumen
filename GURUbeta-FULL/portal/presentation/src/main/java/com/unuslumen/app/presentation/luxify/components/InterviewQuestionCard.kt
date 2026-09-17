package com.unuslumen.app.presentation.luxify.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * A single suggestion option shown inside an interview question card.
 *
 * @property value The stable value sent back to the guru when selected.
 * @property label The human-readable label rendered on the chip.
 * @property description A short explanation shown under or beside the label.
 */
data class InterviewSuggestion(
    val value: String,
    val label: String,
    val description: String
)

/**
 * Native card that renders a guru interview question as interactive suggestion chips plus
 * a free-form "Other" option.
 *
 * @param question The conversational question text to display.
 * @param suggestions Pre-canned options the user can tap.
 * @param onAnswer Called with the selected suggestion value or the user's free-form text.
 */
@Composable
fun InterviewQuestionCard(
    question: String,
    suggestions: List<InterviewSuggestion>,
    onAnswer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showOtherField by remember { mutableStateOf(false) }
    var otherText by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = question,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.size(14.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestions.forEach { suggestion ->
                    SuggestionChipRow(
                        suggestion = suggestion,
                        onTap = { onAnswer(suggestion.value) }
                    )
                }

                FilterChip(
                    selected = showOtherField,
                    onClick = { showOtherField = !showOtherField },
                    label = { Text("Other") },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            if (showOtherField) {
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = otherText,
                    onValueChange = { otherText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your answer") },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (otherText.isNotBlank()) {
                                    onAnswer(otherText.trim())
                                }
                            },
                            enabled = otherText.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Submit answer"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (otherText.isNotBlank()) {
                                onAnswer(otherText.trim())
                            }
                        }
                    ),
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun SuggestionChipRow(
    suggestion: InterviewSuggestion,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(0.dp),
        onClick = onTap
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = suggestion.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (suggestion.description.isNotBlank()) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = suggestion.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Pick",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
