// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.use_case.UpsertNoteUseCase
import com.unuslumen.app.ui.R
import com.unuslumen.app.util.date.now
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class AddNoteFromShareActivity : ComponentActivity() {

    private val upsertNote: UpsertNoteUseCase by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent != null) {
            if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
                val content = intent.getStringExtra(Intent.EXTRA_TEXT)
                val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                if (!content.isNullOrBlank()) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            upsertNote(
                                Note(
                                    title = title ?: "",
                                    content = content,
                                    createdDate = now(),
                                    updatedDate = now(),
                                )
                            )
                        }
                        runOnUiThread {
                            Toast.makeText(
                                this@AddNoteFromShareActivity,
                                getString(R.string.added_note),
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    }
                } else {
                    Toast.makeText(this, getString(R.string.error_empty_title), Toast.LENGTH_SHORT)
                        .show()
                    finish()
                }
            }
        } else {
            finish()
        }
    }
}