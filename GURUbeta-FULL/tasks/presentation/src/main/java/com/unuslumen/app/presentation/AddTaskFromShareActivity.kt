package com.unuslumen.app.presentation

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.ui.R
import com.unuslumen.app.util.date.now
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.uuid.Uuid

class AddTaskFromShareActivity : ComponentActivity() {

    private val viewModel: TasksViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent != null) {
            if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
                val title = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!title.isNullOrBlank()) {
                    viewModel.onEvent(
                        TaskEvent.AddTask(
                            Task(
                                title = title,
                                createdDate = now(),
                                updatedDate = now(),
                                id = Uuid.random().toString()
                            )
                        )
                    )
                    Toast.makeText(this, getString(R.string.added_task), Toast.LENGTH_SHORT)
                        .show()
                } else
                    Toast.makeText(this, getString(R.string.error_empty_title), Toast.LENGTH_SHORT)
                        .show()
            }
        }
        finish()
    }
}