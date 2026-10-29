// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/**
 * One observation as the model reads it back. Every field is the stored one,
 * with tree and fingerprint carried in full: a retrieval that truncated the
 * tree would defeat the whole point of having stored it.
 */
@Serializable
data class ToolObservationEntry(
    val packageName: String,
    val activityClass: String? = null,
    val toolName: String,
    val argumentsJson: String,
    val resultSummary: String,
    val success: Boolean,
    val screenFingerprint: String? = null,
    val accessibilityTreeJson: String? = null,
    val conversationId: String? = null,
    val timestamp: Long,
    val durationMs: Long,
    /** Age in whole minutes, so the model can judge whether the screen has moved on. */
    val ageMinutes: Long
) : ToolResultData

@Serializable
data class ToolObservationLogResult(
    val observations: List<ToolObservationEntry>
) : ToolResultData

@Serializable
data class ToolObservationRecordResult(
    val id: String,
    val recorded: Boolean,
    val message: String
) : ToolResultData
