// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
        ToolDefinition(
            name = CREATE_PLAN,
            description = "Create a structured plan before starting work that takes more than one or " +
                "two steps. Plans are stored as notes on the device with a special [PLAN] prefix so " +
                "they can be found later, and every version is also recorded in the run's activity " +
                "history so the person can see how your approach changed. State the objective, then " +
                "the steps in order. If a step depends on another finishing first, say so, because " +
                "that is what lets a reader tell an ordering from a preference.",
            category = "planning",
            parameters = listOf(
                ToolParameter("title", ToolParameterType.String, true, "The title of the plan"),
                ToolParameter(
                    "steps",
                    ToolParameterType.String,
                    true,
                    "List of steps in the plan, in order. Pass as comma-separated or " +
                        "newline-separated string."
                ),
                ToolParameter(
                    "objective",
                    ToolParameterType.String,
                    false,
                    "What the plan is for, in one sentence. State the outcome rather than the " +
                        "activity, so a reader can tell whether the steps actually reach it."
                ),
                ToolParameter(
                    "dependencies",
                    ToolParameterType.String,
                    false,
                    "Which steps must finish before which others, as pairs separated by a vertical " +
                        "bar, using the step numbers: '2>3|1>3' means step 2 and step 1 must both " +
                        "finish before step 3. Leave it out when the steps are genuinely independent."
                ),
                ToolParameter(
                    "expectedOutputs",
                    ToolParameterType.String,
                    false,
                    "What each step should produce, separated by a vertical bar in step order. A " +
                        "step whose output nobody can name is a step that cannot be checked."
                ),
                ToolParameter(
                    "verification",
                    ToolParameterType.String,
                    false,
                    "How you will know the work is right, separated by a vertical bar. A test that " +
                        "must pass, a file that must exist, a measurement that must hold."
                ),
                ToolParameter(
                    "tools",
                    ToolParameterType.String,
                    false,
                    "The tools this plan expects to use, comma-separated. Naming them up front is " +
                        "what makes a missing capability visible before the work stalls on it."
                ),
                ToolParameter(
                    "reason",
                    ToolParameterType.String,
                    false,
                    "Why this plan rather than another, especially when you are replacing one that " +
                        "did not survive contact with the work."
                ),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = UPDATE_PLAN_STEP,
            description = "Update a step in a plan. Mark steps as complete or in progress. The step " +
                "number is 1-based (first step is 1). Use getActivePlans first to find the plan ID. " +
                "If you are changing the plan rather than recording progress against it, call " +
                "createPlan again with the new shape and a reason: a step that was abandoned and a " +
                "step that was never started are different facts and the history should show which.",
            category = "planning",
            parameters = listOf(
                ToolParameter("planId", ToolParameterType.String, true, "The ID of the plan note to update"),
                ToolParameter("stepIndex", ToolParameterType.Integer, true, "The step number to update (1-based)"),
                ToolParameter(
                    "status",
                    ToolParameterType.String,
                    true,
                    "The new status: 'complete', 'in_progress', or 'pending'"
                ),
                ToolParameter(
                    "reason",
                    ToolParameterType.String,
                    false,
                    "Why the step's state changed, when it is not obvious. Especially for a step " +
                        "that is being abandoned."
                ),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(name = GET_ACTIVE_PLANS, description = "Get all plans. Plans are notes with a [PLAN] prefix in the title.", category = "planning", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = DELETE_PLAN, description = "Delete a plan permanently. This deletes the underlying note. Use getActivePlans first to find the plan ID.", category = "planning", parameters = listOf(ToolParameter("planId", ToolParameterType.String, true, "The ID of the plan to delete")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = PlanningToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}