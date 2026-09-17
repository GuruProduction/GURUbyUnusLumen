package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.Task
import kotlinx.serialization.Serializable

@Serializable data class SearchTasksResult(val tasks: List<Task>) : ToolResultData
@Serializable data class TaskIdResult(val createdTaskId: String) : ToolResultData
@Serializable data class TaskResult(val task: Task) : ToolResultData
@Serializable data class TaskIdsResult(val createdTaskIds: List<String>) : ToolResultData
@Serializable data class DeleteTaskResult(val deletedTaskId: String) : ToolResultData