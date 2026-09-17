package com.unuslumen.app.data.tools

import com.unuslumen.app.data.nowMillis
import com.unuslumen.app.data.parseDateTimeFromLLM
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.Priority
import com.unuslumen.app.domain.model.SubTask
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.model.TaskFrequency
import com.unuslumen.app.domain.use_case.DeleteTaskUseCase
import com.unuslumen.app.domain.use_case.GetAllTasksUseCase
import com.unuslumen.app.domain.use_case.GetTaskByIdUseCase
import com.unuslumen.app.domain.use_case.SearchTasksUseCase
import com.unuslumen.app.data.hooks.HookEventBus
import com.unuslumen.app.domain.model.HookEventType
import com.unuslumen.app.domain.use_case.UpdateTaskCompletedUseCase
import com.unuslumen.app.domain.use_case.UpsertTaskUseCase
import com.unuslumen.app.domain.use_case.UpsertTasksUseCase
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

class TaskToolExecutor(
    private val upsertTask: UpsertTaskUseCase,
    private val upsertTasks: UpsertTasksUseCase,
    private val searchTasksByName: SearchTasksUseCase,
    private val getTaskUseCase: GetTaskByIdUseCase,
    private val updateTaskCompletedUseCase: UpdateTaskCompletedUseCase,
    private val deleteTaskUseCase: DeleteTaskUseCase,
    private val getAllTasksUseCase: GetAllTasksUseCase
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        TaskToolDefinitions.SEARCH_TASKS -> { val r = SearchTasksResult(searchTasksByName(args["query"] as? String ?: "").first()); ToolExecutionResult.success(r, json.encodeToString(SearchTasksResult.serializer(), r)) }
        TaskToolDefinitions.CREATE_TASK -> createTask(args)
        TaskToolDefinitions.UPDATE_TASK_COMPLETED -> { val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'"); val completed = (args["completed"] as? Boolean) ?: return ToolExecutionResult.error("Missing 'completed'"); val task = getTaskUseCase(id) ?: return ToolExecutionResult.error("Not found: $id"); updateTaskCompletedUseCase(task, completed); HookEventBus.fire(if (completed) HookEventType.TASK_COMPLETED else HookEventType.TASK_UPDATED, mapOf("taskId" to id, "title" to task.title, "completed" to completed)); ToolExecutionResult.error("OK") }
        TaskToolDefinitions.DELETE_TASK -> { val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'"); val task = getTaskUseCase(id) ?: return ToolExecutionResult.error("Not found: $id"); deleteTaskUseCase(task); HookEventBus.fire(HookEventType.TASK_DELETED, mapOf("taskId" to id, "title" to task.title, "completed" to task.isCompleted)); val r = DeleteTaskResult(id); ToolExecutionResult.success(r, json.encodeToString(DeleteTaskResult.serializer(), r)) }
        TaskToolDefinitions.UPDATE_TASK -> updateTask(args)
        TaskToolDefinitions.GET_ALL_TASKS -> { val t = getAllTasksUseCase(Order.DateModified(OrderType.DESC), true).first(); val r = SearchTasksResult(t); ToolExecutionResult.success(r, json.encodeToString(SearchTasksResult.serializer(), r)) }
        TaskToolDefinitions.CREATE_MULTIPLE_TASKS -> createMultipleTasks(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createTask(args: Map<String, Any?>): ToolExecutionResult {
        val title = args["title"] as? String ?: return ToolExecutionResult.error("Missing 'title'")
        val priority = (args["priority"] as? String)?.let { try { Priority.valueOf(it.uppercase()) } catch (e: Exception) { Priority.LOW } } ?: Priority.LOW
        val dueDate = (args["dueDate"] as? String)?.let { it.parseDateTimeFromLLM() ?: return ToolExecutionResult.error("Invalid date: $it") } ?: 0L
        val recurring = (args["recurring"] as? Boolean) ?: false
        val frequency = (args["frequency"] as? String)?.let { try { TaskFrequency.valueOf(it.uppercase()) } catch (e: Exception) { TaskFrequency.DAILY } } ?: TaskFrequency.DAILY
        val freqAmount = (args["frequencyAmount"] as? Number)?.toInt() ?: 1
        val id = Uuid.random().toString()
        upsertTask(Task(title = title, description = args["description"] as? String ?: "", priority = priority, dueDate = dueDate, subTasks = emptyList(), recurring = recurring, frequency = frequency, frequencyAmount = freqAmount, createdDate = nowMillis(), updatedDate = nowMillis(), id = id))
        HookEventBus.fire(
            HookEventType.TASK_CREATED,
            mapOf("taskId" to id, "title" to title, "completed" to false)
        )
        val r = TaskIdResult(id); return ToolExecutionResult.success(r, json.encodeToString(TaskIdResult.serializer(), r))
    }

    private suspend fun createMultipleTasks(args: Map<String, Any?>): ToolExecutionResult {
        val tasksStr = args["tasks"] as? String ?: return ToolExecutionResult.error("Missing 'tasks'")
        val inputs = try { json.decodeFromString<List<Map<String, Any?>>>(tasksStr) } catch (e: Exception) { return ToolExecutionResult.error("Invalid tasks JSON") }
        val models = inputs.map { input ->
            val id = Uuid.random().toString()
            Task(title = input["title"] as? String ?: "", description = input["description"] as? String ?: "", priority = Priority.LOW, dueDate = 0L, subTasks = emptyList(), recurring = false, frequency = TaskFrequency.DAILY, frequencyAmount = 1, createdDate = nowMillis(), updatedDate = nowMillis(), id = id)
        }
        upsertTasks(models)
        val r = TaskIdsResult(models.map { it.id }); return ToolExecutionResult.success(r, json.encodeToString(TaskIdsResult.serializer(), r))
    }

    private suspend fun updateTask(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val existing = getTaskUseCase(id) ?: return ToolExecutionResult.error("Not found: $id")
        val updated = existing.copy(title = (args["title"] as? String) ?: existing.title, description = (args["description"] as? String) ?: existing.description, updatedDate = nowMillis())
        upsertTask(updated)
        val r = TaskResult(updated); return ToolExecutionResult.success(r, json.encodeToString(TaskResult.serializer(), r))
    }
}