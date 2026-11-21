// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Factory

/**
 * Records a decision as a value the tool layer can hand back.
 *
 * WHY THE EXECUTOR DOES NOT WRITE THE EVENT ITSELF. The ledger is written by the runtime, in the
 * same place it writes a plan revision, because the runtime is what knows which run and which round
 * the call belongs to. An executor writing straight to the ledger would need to be told the run id
 * through its arguments, which would let a model name a different run and record a decision into
 * somebody else's history.
 *
 * SO THIS VALIDATES AND RETURNS. It refuses a record with a decision or a reason missing, because
 * those two are what make the record worth having, and it hands back the whole shape so the runtime
 * can append it verbatim.
 */
@Factory
class DecisionToolExecutor : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        if (toolName != DecisionToolDefinitions.RECORD_DECISION) {
            return ToolExecutionResult.error("Unknown tool: $toolName")
        }

        val decision = (args["decision"] as? String)?.trim().orEmpty()
        if (decision.isEmpty()) {
            return ToolExecutionResult.error(
                "Missing 'decision'. Say what you decided in one sentence, so the record names the " +
                    "fork rather than the work around it."
            )
        }

        val reason = (args["reason"] as? String)?.trim().orEmpty()
        if (reason.isEmpty()) {
            return ToolExecutionResult.error(
                "Missing 'reason'. A decision without its reason is a conclusion nobody can check, " +
                    "which is the one thing this record exists to prevent."
            )
        }

        val recorded = RecordedDecision(
            decision = decision,
            problem = (args["problem"] as? String)?.trim().orEmpty(),
            approach = (args["approach"] as? String)?.trim().orEmpty(),
            alternatives = (args["alternatives"] as? String)
                ?.split('|')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            reason = reason,
            evidence = (args["evidence"] as? String)?.trim().orEmpty(),
            uncertainty = (args["uncertainty"] as? String)?.trim().orEmpty(),
            next = (args["next"] as? String)?.trim().orEmpty(),
        )

        return ToolExecutionResult.success(
            recorded,
            json.encodeToString(RecordedDecision.serializer(), recorded),
        )
    }
}

/**
 * One decision, as the agent put it.
 *
 * EVERY FIELD IS OPTIONAL EXCEPT THE DECISION AND ITS REASON, and that is a product decision rather
 * than laziness. Requiring eight fields would make an agent either skip the record or pad it to
 * satisfy the shape, and a padded record is worse than a short honest one. The two required fields
 * are exactly what a reader needs to disagree with the decision.
 */
@Serializable
data class RecordedDecision(
    val decision: String,
    val problem: String,
    val approach: String,
    val alternatives: List<String>,
    val reason: String,
    val evidence: String,
    val uncertainty: String,
    val next: String,
) : com.unuslumen.app.data.tools.registry.ToolResultData
