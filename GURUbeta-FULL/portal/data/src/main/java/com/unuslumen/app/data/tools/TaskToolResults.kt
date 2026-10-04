// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.Task
import kotlinx.serialization.Serializable

@Serializable data class SearchTasksResult(val tasks: List<Task>) : ToolResultData
@Serializable data class TaskIdResult(val createdTaskId: String) : ToolResultData
@Serializable data class TaskResult(val task: Task) : ToolResultData
@Serializable data class TaskIdsResult(val createdTaskIds: List<String>) : ToolResultData
@Serializable data class DeleteTaskResult(val deletedTaskId: String) : ToolResultData