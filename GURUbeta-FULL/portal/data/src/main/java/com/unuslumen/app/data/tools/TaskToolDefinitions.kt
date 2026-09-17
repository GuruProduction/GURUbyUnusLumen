package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object TaskToolDefinitions : ToolSetRegistration {
    const val SEARCH_TASKS = "searchTasks"
    const val CREATE_TASK = "createTask"
    const val UPDATE_TASK_COMPLETED = "updateTaskCompleted"
    const val CREATE_MULTIPLE_TASKS = "createMultipleTasks"
    const val DELETE_TASK = "deleteTask"
    const val UPDATE_TASK = "updateTask"
    const val GET_ALL_TASKS = "getAllTasks"

    override val definitions = listOf(
        ToolDefinition(name = SEARCH_TASKS, description = "Search tasks by title (partial match). If the query is empty, returns all tasks.", category = "task", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = CREATE_TASK, description = "Create a task. isCompleted = false initially. Returns ID.", category = "task", parameters = listOf(ToolParameter("title", ToolParameterType.String, true, "Task title"), ToolParameter("description", ToolParameterType.String, false, "Description"), ToolParameter("priority", ToolParameterType.String, false, "Priority: LOW, MEDIUM, HIGH, URGENT"), ToolParameter("dueDate", ToolParameterType.String, false, "Due date HH:mm dd-MM-yyyy"), ToolParameter("recurring", ToolParameterType.Boolean, false, "Recurring"), ToolParameter("frequency", ToolParameterType.String, false, "DAILY, WEEKLY, MONTHLY, YEARLY"), ToolParameter("frequencyAmount", ToolParameterType.Integer, false, "Frequency interval")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_TASK_COMPLETED, description = "Update task completed status.", category = "task", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Task ID"), ToolParameter("completed", ToolParameterType.Boolean, true, "Completed status")), permissions = emptyList()),
        ToolDefinition(name = CREATE_MULTIPLE_TASKS, description = "Create multiple tasks. Returns IDs.", category = "task", parameters = listOf(ToolParameter("tasks", ToolParameterType.String, true, "JSON array of task inputs")), permissions = emptyList()),
        ToolDefinition(name = DELETE_TASK, description = "Delete a task permanently. This cannot be undone. Will also delete any associated alarm.", category = "task", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Task ID")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_TASK, description = "Update an existing task. Only the fields you provide will be changed. Returns the updated task.", category = "task", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Task ID"), ToolParameter("title", ToolParameterType.String, false, "New title"), ToolParameter("description", ToolParameterType.String, false, "New description"), ToolParameter("priority", ToolParameterType.String, false, "New priority"), ToolParameter("dueDate", ToolParameterType.String, false, "New due date HH:mm dd-MM-yyyy"), ToolParameter("recurring", ToolParameterType.Boolean, false, "Recurring"), ToolParameter("frequency", ToolParameterType.String, false, "Frequency"), ToolParameter("frequencyAmount", ToolParameterType.Integer, false, "Frequency interval")), permissions = emptyList()),
        ToolDefinition(name = GET_ALL_TASKS, description = "Get all tasks. Returns the complete list of tasks.", category = "task", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = TaskToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}