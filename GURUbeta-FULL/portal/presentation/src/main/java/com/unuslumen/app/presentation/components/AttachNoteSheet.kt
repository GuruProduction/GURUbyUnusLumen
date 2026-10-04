// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.compositeOver
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.ui.ItemView
import com.unuslumen.app.ui.components.notes.NoteSearchContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachNoteSheet(
    state: SheetState,
    onDismissRequest: () -> Unit,
    notes: List<Note>,
    view: ItemView,
    onQueryChange: (String) -> Unit,
    onNoteClick: (Note) -> Unit
) {
    ModalBottomSheet(
        sheetState = state,
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.3f).compositeOver(
            MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        NoteSearchContent(
            notes = notes,
            onQueryChange = onQueryChange,
            onNoteClick = onNoteClick,
            view = view
        )
    }
}