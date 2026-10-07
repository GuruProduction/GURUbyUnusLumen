// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One executed run of an automation, with its full step trace.
 *
 * This is the Observatory's truth floor: every automation execution — manual,
 * scheduled job, or hook-fired — lands here as it happens, so the room the
 * numen builds over it shows live reasoning, visible healing and honest
 * history. The status vocabulary is doctrine: runs are completed,
 * self_healed or needs_attention. There is no "failed".
 */
@Entity(
    tableName = "guru_automation_runs",
    indices = [
        Index(value = ["automationId"], name = "index_guru_automation_runs_automationId"),
        Index(value = ["started_at"], name = "index_guru_automation_runs_started_at"),
        Index(value = ["status"], name = "index_guru_automation_runs_status")
    ]
)
data class GuruAutomationRunEntity(
    @PrimaryKey
    val id: String,
    val automationId: String,
    val automationName: String,
    /** Path the run arrived by: manual | job | hook */
    val trigger: String,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    val durationMs: Long,
    /** running | completed | self_healed | needs_attention */
    @ColumnInfo(defaultValue = "running")
    val status: String = "running",
    /** JSON array trace: [{index, tool, paramsSummary, resultSummary, error, durationMs}] */
    val stepsTraceJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)