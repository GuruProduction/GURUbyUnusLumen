// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.nowMillis
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.use_case.DeleteNoteUseCase
import com.unuslumen.app.domain.use_case.GetNoteUseCase
import com.unuslumen.app.domain.use_case.SearchNotesUseCase
import com.unuslumen.app.domain.use_case.UpsertNoteUseCase
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

class PlanningToolExecutor(
    private val upsertNote: UpsertNoteUseCase,
    private val searchNotesByName: SearchNotesUseCase,
    private val getNoteUseCase: GetNoteUseCase,
    private val deleteNoteUseCase: DeleteNoteUseCase
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val PLAN_PREFIX = "[PLAN] "

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        PlanningToolDefinitions.CREATE_PLAN -> createPlan(args)
        PlanningToolDefinitions.UPDATE_PLAN_STEP -> updatePlanStep(args)
        PlanningToolDefinitions.GET_ACTIVE_PLANS -> getActivePlans()
        PlanningToolDefinitions.DELETE_PLAN -> deletePlan(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createPlan(args: Map<String, Any?>): ToolExecutionResult {
        val title = args["title"] as? String ?: return ToolExecutionResult.error("Missing 'title'")
        val stepsStr = args["steps"] as? String ?: return ToolExecutionResult.error("Missing 'steps'")
        val steps = stepsStr.split("\n", ",").map { it.trim() }.filter { it.isNotBlank() }
        if (steps.isEmpty()) {
            return ToolExecutionResult.error(
                "A plan with no steps is not a plan. Give at least one step, even if it is the only " +
                    "one you are certain of."
            )
        }

        val tempId = Uuid.random().toString()

        /*
         * THE EXTRA FIELDS RIDE BELOW THE STEPS, IN THE SAME NOTE.
         *
         * The note is the document the model reads back through getActivePlans, and the ledger is
         * the history. Both need the same content, so the structured fields are written here in a
         * form a model can read and the runtime records the same values as its own event. Writing
         * them only to the ledger would leave getActivePlans returning a plan that had lost its
         * objective; writing them only here would put them outside the run's history.
         */
        val objective = (args["objective"] as? String)?.trim().orEmpty()
        val dependencies = (args["dependencies"] as? String)?.trim().orEmpty()
        val expectedOutputs = (args["expectedOutputs"] as? String)?.trim().orEmpty()
        val verification = (args["verification"] as? String)?.trim().orEmpty()
        val tools = (args["tools"] as? String)?.trim().orEmpty()
        val reason = (args["reason"] as? String)?.trim().orEmpty()

        val content = buildString {
            if (objective.isNotEmpty()) appendLine("OBJECTIVE: $objective")
            steps.forEachIndexed { i, s -> appendLine("${i + 1}. [ ] $s") }
            if (expectedOutputs.isNotEmpty()) appendLine("EXPECTED: $expectedOutputs")
            if (dependencies.isNotEmpty()) appendLine("DEPENDS: $dependencies")
            if (verification.isNotEmpty()) appendLine("VERIFY: $verification")
            if (tools.isNotEmpty()) appendLine("TOOLS: $tools")
            if (reason.isNotEmpty()) appendLine("WHY: $reason")
        }.trimEnd()

        val note = Note(
            id = tempId,
            title = "$PLAN_PREFIX$title",
            content = content,
            createdDate = nowMillis(),
            updatedDate = nowMillis(),
        )
        val storedId = upsertNote(note)
        val r = PlanResult(storedId, title, steps, storedId)
        return ToolExecutionResult.success(r, json.encodeToString(PlanResult.serializer(), r))
    }

    private suspend fun updatePlanStep(args: Map<String, Any?>): ToolExecutionResult {
        val planId = args["planId"] as? String ?: return ToolExecutionResult.error("Missing 'planId'")
        val stepIndex = (args["stepIndex"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'stepIndex'")
        val status = args["status"] as? String ?: return ToolExecutionResult.error("Missing 'status'")
        val note = runCatching { getNoteUseCase(planId) }.getOrNull() ?: run {
            val plans = searchNotesByName(PLAN_PREFIX).filter { it.title.startsWith(PLAN_PREFIX) }
            plans.find { it.id == planId || it.title.removePrefix(PLAN_PREFIX).equals(planId, ignoreCase = true) }
                ?: return ToolExecutionResult.error("No plan found with ID: '$planId'")
        }
        if (!note.title.startsWith(PLAN_PREFIX)) return ToolExecutionResult.error("Note '$planId' is not a plan.")
        val lines = note.content.lines().toMutableList()

        /*
         * ONLY NUMBERED STEP LINES ARE STEPS.
         *
         * A PLAN NOTE NOW CARRIES HEADERS TOO: OBJECTIVE, EXPECTED, DEPENDS, VERIFY, TOOLS and WHY.
         * Counting every line would make "step 1" the objective and would let a step update
         * overwrite a header, so the step lines are found by their number-and-checkbox shape and the
         * update is applied to the nth of those, wherever it sits in the document.
         */
        val stepLinePattern = Regex("""^\d+\.\s*\[[ x~]\]""")
        val stepLineIndices = lines.indices.filter { stepLinePattern.containsMatchIn(lines[it]) }
        if (stepIndex < 1 || stepIndex > stepLineIndices.size) {
            return ToolExecutionResult.error(
                "Step index $stepIndex out of range. This plan has ${stepLineIndices.size} steps."
            )
        }
        val checkbox = when (status.lowercase()) {
            "complete", "done", "finished" -> "[x]"
            "in_progress", "in progress", "started" -> "[~]"
            "pending", "not started", "todo" -> "[ ]"
            else -> return ToolExecutionResult.error("Unknown status: '$status'")
        }
        val target = stepLineIndices[stepIndex - 1]
        lines[target] = lines[target].replace(Regex("""\[[ x~]\]"""), checkbox)
        val updated = note.copy(content = lines.joinToString("\n"), updatedDate = nowMillis())
        upsertNote(updated)
        val steps = stepLineIndices.map { lines[it].replace(Regex("""^\d+\.\s*\[[ x~]\]\s*"""), "").trim() }
        val r = PlanResult(planId, note.title.removePrefix(PLAN_PREFIX), steps, planId)
        return ToolExecutionResult.success(r, json.encodeToString(PlanResult.serializer(), r))
    }

    private suspend fun getActivePlans(): ToolExecutionResult {
        val allNotes = searchNotesByName(PLAN_PREFIX)
        val plans = allNotes.filter { it.title.startsWith(PLAN_PREFIX) }.map { note ->
            val steps = note.content.lines().map { it.replace(Regex("""^\d+\.\s*\[[ x~]\]\s*"""), "").trim() }
            PlanSummary(note.id, note.title.removePrefix(PLAN_PREFIX), steps.size, note.content.lines().count { it.contains("[x]") }, note.createdDate, note.updatedDate)
        }
        val r = PlansResult(plans); return ToolExecutionResult.success(r, json.encodeToString(PlansResult.serializer(), r))
    }

    private suspend fun deletePlan(args: Map<String, Any?>): ToolExecutionResult {
        val planId = args["planId"] as? String ?: return ToolExecutionResult.error("Missing 'planId'")
        val note = runCatching { getNoteUseCase(planId) }.getOrNull() ?: run {
            val plans = searchNotesByName(PLAN_PREFIX).filter { it.title.startsWith(PLAN_PREFIX) }
            plans.find { it.id == planId } ?: return ToolExecutionResult.error("No plan found with ID: '$planId'")
        }
        deleteNoteUseCase(note)
        val r = DeletePlanResult(planId); return ToolExecutionResult.success(r, json.encodeToString(DeletePlanResult.serializer(), r))
    }
}