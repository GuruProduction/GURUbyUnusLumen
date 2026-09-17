package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object PlanningToolDefinitions : ToolSetRegistration {
    const val CREATE_PLAN = "createPlan"
    const val UPDATE_PLAN_STEP = "updatePlanStep"
    const val GET_ACTIVE_PLANS = "getActivePlans"
    const val DELETE_PLAN = "deletePlan"

    override val definitions = listOf(
        ToolDefinition(name = CREATE_PLAN, description = "Create a structured plan. Plans are stored as notes on the device with a special [PLAN] prefix so they can be found later. Each step has a checkbox format that can be updated.", category = "planning", parameters = listOf(ToolParameter("title", ToolParameterType.String, true, "The title of the plan"), ToolParameter("steps", ToolParameterType.String, true, "List of steps in the plan. Pass as comma-separated or newline-separated string.")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_PLAN_STEP, description = "Update a step in a plan. Mark steps as complete or in progress. The step number is 1-based (first step is 1). Use getActivePlans first to find the plan ID.", category = "planning", parameters = listOf(ToolParameter("planId", ToolParameterType.String, true, "The ID of the plan note to update"), ToolParameter("stepIndex", ToolParameterType.Integer, true, "The step number to update (1-based)"), ToolParameter("status", ToolParameterType.String, true, "The new status: 'complete', 'in_progress', or 'pending'")), permissions = emptyList()),
        ToolDefinition(name = GET_ACTIVE_PLANS, description = "Get all plans. Plans are notes with a [PLAN] prefix in the title.", category = "planning", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = DELETE_PLAN, description = "Delete a plan permanently. This deletes the underlying note. Use getActivePlans first to find the plan ID.", category = "planning", parameters = listOf(ToolParameter("planId", ToolParameterType.String, true, "The ID of the plan to delete")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = PlanningToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}