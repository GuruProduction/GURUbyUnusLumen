// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * tool_observations — the structured action-and-observation log.
 *
 * tool_results answers "what did this tool return". This table answers the
 * question a second run needs and tool_results cannot: what exactly was on the
 * screen, and exactly which call was made against it, at the moment the tool
 * failed. A model that can read that stops re-guessing a selector that has
 * already been proven brittle and instead compares the stored tree against the
 * screen in front of it.
 *
 * One row per observation:
 *  - package_name / activity_class identify the app surface. activity_class is
 *    the root node's class name, which is the closest honest answer the
 *    accessibility service gives; a window whose root reports nothing stores null
 *    rather than a guess.
 *  - screen_fingerprint is the sorted, de-duplicated list of resource ids of the
 *    visible nodes, excluding the node the call targeted, serialised as a JSON
 *    array. It is the cheap comparison key: two screens with the same fingerprint
 *    are the same surface, no tree parse required.
 *  - accessibility_tree_json is the compact serialised tree itself, for when the
 *    fingerprint says "different" and the model needs to see how.
 *
 * The row is deliberately nullable-tolerant on everything that can be unknown.
 * A tool failure that happens with the accessibility service down is still worth
 * recording: the call, the arguments, the error and the duration are real even
 * when no tree can be captured. Writing the row with a null tree is always
 * better than blocking the tool path or inventing screen state.
 *
 * success is stored as 0/1 rather than a Boolean because the log is queried by
 * raw SQL in diagnostics as often as it is read through Room, and 0/1 is what
 * those reads expect to see.
 */
@Entity(
    tableName = "tool_observations",
    indices = [
        Index(
            value = ["package_name", "timestamp"],
            name = "index_tool_observations_package_timestamp"
        )
    ]
)
data class ToolObservationEntity(
    /** UUID string primary key, generated per observation. */
    @PrimaryKey
    @ColumnInfo(name = "id", defaultValue = "")
    val id: String,

    /** The app package the tool was aimed at, e.g. "com.android.settings". */
    @ColumnInfo(name = "package_name", defaultValue = "")
    val packageName: String,

    /** The root node's class name at the time, null when no window could be read. */
    @ColumnInfo(name = "activity_class")
    val activityClass: String? = null,

    /** The tool that ran, e.g. "clickText". */
    @ColumnInfo(name = "tool_name", defaultValue = "")
    val toolName: String,

    /** The exact arguments the call carried, as JSON. */
    @ColumnInfo(name = "arguments_json", defaultValue = "")
    val argumentsJson: String = "",

    /** The error or the head of the result, whichever the row has to say. */
    @ColumnInfo(name = "result_summary", defaultValue = "")
    val resultSummary: String = "",

    /** 1 when the tool succeeded, 0 when it failed. */
    @ColumnInfo(name = "success", defaultValue = "1")
    val success: Int = 1,

    /** Sorted JSON array of visible resource ids, target excluded. Null when no window was read. */
    @ColumnInfo(name = "screen_fingerprint")
    val screenFingerprint: String? = null,

    /** Compact serialised accessibility tree. Null when no window was read. */
    @ColumnInfo(name = "accessibility_tree_json")
    val accessibilityTreeJson: String? = null,

    /** The conversation the tool call belonged to, null when the caller had none. */
    @ColumnInfo(name = "conversation_id")
    val conversationId: String? = null,

    /** Epoch millis of the observation. */
    @ColumnInfo(name = "timestamp", defaultValue = "0")
    val timestamp: Long,

    /** How long the tool took, in milliseconds. */
    @ColumnInfo(name = "duration_ms", defaultValue = "0")
    val durationMs: Long = 0
) {
    companion object {
        private const val MAX_ARGUMENTS_CHARS = 10_000
        private const val MAX_SUMMARY_CHARS = 2_000

        /** Bounded the same way ToolResultEntity bounds its write, marker and all. */
        fun truncateArguments(arguments: String): String {
            if (arguments.length <= MAX_ARGUMENTS_CHARS) return arguments
            val omitted = arguments.length - MAX_ARGUMENTS_CHARS
            return arguments.take(MAX_ARGUMENTS_CHARS) +
                "\n[... arguments truncated, $omitted chars omitted ...]"
        }

        fun truncateSummary(summary: String): String {
            if (summary.length <= MAX_SUMMARY_CHARS) return summary
            val omitted = summary.length - MAX_SUMMARY_CHARS
            return summary.take(MAX_SUMMARY_CHARS) +
                "\n[... result summary truncated, $omitted chars omitted ...]"
        }
    }
}
