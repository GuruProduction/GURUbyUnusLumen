// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A standing instruction for an agent to start work on its own.
 *
 * WHY THIS IS A TABLE AND NOT AN ALARM. The heartbeat already fires prompts on a schedule, and
 * reusing it would have been the smaller change. It would also have been the wrong one: a heartbeat
 * wake-up runs the assistant, not an agent, and giving it a second job would make one alarm mean two
 * different things depending on a flag nobody looking at the alarm can see. A schedule that names an
 * agent, a job and a next-due time is a different fact from "wake the assistant every thirty
 * minutes", and it is stored as one.
 *
 * THE RUN IT STARTS IS AN ORDINARY RUN. `last_run_id` points at an `agent_runs` row like any other,
 * with the same ledger, the same permissions and the same room. A scheduled run that took a separate
 * path would be a second execution system, and the specification is explicit that new autonomy must
 * use the same architecture rather than a parallel one.
 *
 * [next_due_at] IS NULL WHEN THE SCHEDULE IS NOT ARMED. A disabled schedule, or one that has spent
 * its last occurrence, has no next time. Storing a far-future sentinel instead would make "never" and
 * "in the year 2200" the same value, and a query for due work would have to know which.
 *
 * A MISSED WINDOW IS RECORDED, NOT SKIPPED SILENTLY. The device can be off, asleep or busy through a
 * scheduled time. [last_due_at] and [missed_count] exist so a schedule that has been missing its
 * windows says so, rather than quietly running late and looking punctual.
 */
@Entity(
    tableName = "agent_schedules",
    indices = [
        Index(value = ["agent_id"], name = "index_agent_schedules_agent_id"),
        Index(value = ["enabled", "next_due_at"], name = "index_agent_schedules_enabled_next_due"),
    ],
)
data class AgentScheduleEntity(
    @PrimaryKey
    @ColumnInfo(name = "schedule_id")
    val scheduleId: String,

    /**
     * The agent this schedule starts.
     *
     * NOT A FOREIGN KEY, for the same reason `agent_runs.agent_id` is not one: an agent can be
     * renamed, republished or deleted, and a schedule that outlived its agent is a fact worth being
     * able to see rather than a row that vanishes. The runner refuses a schedule whose agent no
     * longer resolves, and says so on the schedule's own record.
     */
    @ColumnInfo(name = "agent_id")
    val agentId: String,

    /** The agent's slug at the time the schedule was written, so a renamed agent can still be found. */
    @ColumnInfo(name = "agent_slug", defaultValue = "")
    val agentSlug: String = "",

    /** A name a person recognises. The agent's name is not enough once there are several schedules. */
    @ColumnInfo(name = "title")
    val title: String,

    /**
     * The job handed to the agent when this fires.
     *
     * FROZEN AT WRITE TIME. A schedule that re-read a job from somewhere else would run different
     * work each time without anyone changing it, and the record of what was scheduled would be a
     * pointer to a moving target.
     */
    @ColumnInfo(name = "assignment")
    val assignment: String,

    /** Whether this schedule is armed. A disabled schedule has no next due time. */
    @ColumnInfo(name = "enabled", defaultValue = "1")
    val enabled: Boolean = true,

    /** The interval between runs, in milliseconds. */
    @ColumnInfo(name = "interval_millis")
    val intervalMillis: Long,

    /** When this should next run. Null means it is not armed for any future time. */
    @ColumnInfo(name = "next_due_at")
    val nextDueAt: Long? = null,

    /** The last time this was due, whether or not it actually ran. */
    @ColumnInfo(name = "last_due_at")
    val lastDueAt: Long? = null,

    /** The last time a run was actually started from this schedule. */
    @ColumnInfo(name = "last_started_at")
    val lastStartedAt: Long? = null,

    /** The run this schedule most recently started, so the work is one tap away. */
    @ColumnInfo(name = "last_run_id")
    val lastRunId: String? = null,

    /**
     * The conversation this schedule's runs belong to, created on the first run.
     *
     * ONE THREAD PER SCHEDULE. A run cannot be inserted without a conversation, and a scheduled run
     * has no message that started it. Giving every night's work its own thread would make a
     * schedule's history unreadable, and writing into the person's own chat would scatter autonomous
     * work through a conversation they use. This is the middle: one thread, named after the schedule,
     * holding everything that schedule ever did.
     */
    @ColumnInfo(name = "conversation_id")
    val conversationId: String? = null,

    /**
     * How many due windows have passed with no run started.
     *
     * COUNTED, NOT ESTIMATED. A schedule that has missed four windows says four, which is the
     * difference between a device that was asleep and a schedule that is broken.
     */
    @ColumnInfo(name = "missed_count", defaultValue = "0")
    val missedCount: Int = 0,

    /** Why the last attempt did not start a run, when it did not. Cleared on a successful start. */
    @ColumnInfo(name = "last_failure")
    val lastFailure: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        /**
         * The schedule rules are held by the domain's AgentScheduleRules.
         *
         * THE ENTITY CANNOT SEE THE DOMAIN. The layering is domain → database for the agent feature's
         * shared vocabulary, and the reverse import does not exist. So the numbers live in one place,
         * the domain object, where the product decision belongs and where a test can prove it
         * without a database; everything that needs them names them there.
         */
    }
}
