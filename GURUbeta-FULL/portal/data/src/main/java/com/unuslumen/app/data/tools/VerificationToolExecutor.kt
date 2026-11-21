// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.agents.VerificationLevels
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Factory

/**
 * Validates a verification record and hands it back for the runtime to append.
 *
 * THE SAME DIVISION AS THE DECISION AND REPORT VERBS. The ledger is written by the runtime, which
 * knows the run and the round; an executor writing to it would need the run id as an argument, and a
 * model that can name a run can record a check into another one's history.
 *
 * THE LEVEL IS THE ONE FIELD THAT CANNOT BE FUZZED. "Confirmed" when the evidence is a command that
 * exited zero is exactly the false claim section 4.4 exists to stop, so an unknown word is refused
 * with the three that exist rather than being filed under the nearest one.
 */
@Factory
class VerificationToolExecutor : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        if (toolName != VerificationToolDefinitions.RECORD_VERIFICATION) {
            return ToolExecutionResult.error("Unknown tool: $toolName")
        }

        val claim = (args["claim"] as? String)?.trim().orEmpty()
        if (claim.isEmpty()) {
            return ToolExecutionResult.error(
                "Missing 'claim'. Record the thing you are asserting, so the check can be judged " +
                    "against something specific."
            )
        }

        val requested = (args["level"] as? String)?.trim().orEmpty()
        val level = VerificationLevels.of(requested)
            ?: return ToolExecutionResult.error(
                if (requested.isEmpty()) {
                    "Missing 'level'. Use one of: ${VerificationLevels.ALL.joinToString(", ").lowercase()}."
                } else {
                    "'$requested' is not a level of confirmation. Use one of: " +
                        "${VerificationLevels.ALL.joinToString(", ").lowercase()}."
                }
            )

        val evidence = (args["evidence"] as? String)?.trim().orEmpty()
        if (evidence.isEmpty()) {
            /*
             * A CONFIRMATION WITH NO SOURCE IS AN ASSERTION WITH A LONGER WORD. The whole point of
             * the record is that a reader can check it, and a level with nothing behind it gives them
             * nothing to check.
             */
            return ToolExecutionResult.error(
                "Missing 'evidence'. Name what you observed and where: the output, the path, the " +
                    "row. A level of confirmation with nothing behind it cannot be checked by anybody."
            )
        }

        val recorded = RecordedVerification(
            claim = claim,
            level = level,
            evidence = evidence,
            check = (args["check"] as? String)?.trim().orEmpty(),
            uncertainty = (args["uncertainty"] as? String)?.trim().orEmpty(),
        )

        return ToolExecutionResult.success(
            recorded,
            json.encodeToString(RecordedVerification.serializer(), recorded),
        )
    }
}

/**
 * One check, as the agent made it.
 *
 * [supportsCompletion] IS DERIVED HERE RATHER THAN STORED, so the rule lives in one place: only a
 * confirmed check supports reporting the work as done. Storing it would let a record claim a level it
 * had not earned and a completion flag in the same row.
 */
@Serializable
data class RecordedVerification(
    val claim: String,
    val level: String,
    val evidence: String,
    val check: String,
    val uncertainty: String,
) : com.unuslumen.app.data.tools.registry.ToolResultData {
    val supportsCompletion: Boolean get() = VerificationLevels.supportsCompletion(level)
}
