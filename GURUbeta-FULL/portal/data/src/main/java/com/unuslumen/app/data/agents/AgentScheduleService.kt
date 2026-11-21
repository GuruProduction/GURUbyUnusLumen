// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.database.dao.AgentScheduleDao
import com.unuslumen.app.database.entity.AgentScheduleEntity
import com.unuslumen.app.domain.repository.GuruAgentRepository
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Turns a due schedule into a real run, or records why it could not.
 *
 * THE SCHEDULE IS A STANDING INSTRUCTION AND THIS IS THE THING THAT HONOURS IT. It resolves the
 * agent, asks whether that agent is permitted to work unattended, starts an ordinary run through the
 * ordinary runtime, and advances the schedule. Every step of that is checked rather than assumed,
 * because a schedule fires with nobody watching and a mistake here runs work the owner never asked
 * for at a time they did not choose.
 *
 * THE AUTONOMY FLAG IS THE PERMISSION. `operateOnSchedules` has been declared on every agent since
 * Phase 2 and read by nothing. This is its reader, and it is checked here rather than in a prompt
 * because a prompt is not a control: an agent whose prompt says "you may run on a schedule" and
 * whose configuration says otherwise must be refused, and the configuration is what this reads.
 *
 * THE RUN IS ORDINARY. Same runtime, same ledger, same permissions, same room. A scheduled run that
 * took its own path would be a second execution system, which is precisely what the specification
 * forbids for new autonomy.
 *
 * ADVANCE IS CONDITIONAL, AND THAT IS THE WHOLE SAFETY. Two callers — a worker and a manual trigger,
 * or one worker and a restart — can both see the same window as due. Only one can advance it, because
 * the update names the due time it expects to replace. The loser starts nothing.
 */
@OptIn(ExperimentalUuidApi::class)
@Single
class AgentScheduleService(
    private val scheduleDao: AgentScheduleDao,
    private val agentRepository: GuruAgentRepository,
    private val runtime: AgentRuntime,
    private val runHost: AgentRunHost,
    private val ledger: AgentRunLedger,
    /**
     * The database, for one thing only: creating the conversation a scheduled run belongs to.
     *
     * A RUN CANNOT EXIST WITHOUT ONE, because `agent_runs.conversation_id` is a real foreign key and
     * a scheduled run has no originating message to belong to. Rather than demand a conversation id
     * from whoever created the schedule — which would let a schedule write into a person's own chat —
     * the service owns one conversation per schedule, created the first time it runs.
     */
    private val database: com.unuslumen.app.database.guruDatabase,
) {

    /** What one sweep of the schedule table did. */
    data class SweepReport(
        val due: Int,
        val started: Int,
        /** Schedules that were due and did not start, with the reason each gave. */
        val refused: List<Pair<String, String>>,
        val at: Long,
    )

    /** What happened to one schedule. */
    sealed interface Outcome {
        data class Started(val runId: String, val agentName: String) : Outcome

        /**
         * Nothing was started, and the reason says why.
         *
         * [reason] IS WRITTEN TO THE SCHEDULE, not only returned. A schedule that has been failing
         * silently for a week is worse than one that never ran, because it looks like work that was
         * scheduled and the owner has no way to know it never happened.
         */
        data class Refused(val reason: String) : Outcome
    }

    /**
     * Run everything that is due, oldest first.
     *
     * THE SWEEP IS THE ENTRY POINT for whatever wakes this — a worker, the app on foreground, a
     * manual trigger. It does not care which, and it does not require the work to have been running
     * continuously: a device that was off through three windows comes back to three schedules that
     * are overdue, and each one is judged on its own lateness.
     */
    suspend fun sweep(now: Long = System.currentTimeMillis()): SweepReport {
        val due = runCatching { scheduleDao.due(now) }.getOrDefault(emptyList())
        var started = 0
        val refused = mutableListOf<Pair<String, String>>()

        for (schedule in due) {
            when (val outcome = runOne(schedule, now)) {
                is Outcome.Started -> started++
                is Outcome.Refused -> refused += schedule.scheduleId to outcome.reason
            }
        }

        return SweepReport(due = due.size, started = started, refused = refused, at = now)
    }

    /**
     * Start one schedule's run, or record why it could not.
     *
     * THE ORDER OF THE CHECKS IS THE ORDER OF WHAT CAN GO WRONG, cheapest and most decisive first:
     * is it too late to be worth starting, does the agent still exist, is it enabled, is it permitted
     * to work unattended, is there a conversation for the run to belong to. Every refusal leaves the
     * schedule advanced and the reason recorded, so one broken schedule cannot block the sweep.
     */
    suspend fun runOne(schedule: AgentScheduleEntity, now: Long = System.currentTimeMillis()): Outcome {
        val dueAt = schedule.nextDueAt
            ?: return refuse(schedule, "the schedule has no next due time", now)

        /*
         * A WINDOW MISSED BY MORE THAN THE GRACE IS COUNTED, NOT RUN.
         *
         * Starting hours of queued work at once is worse than saying the device was away: each run
         * would do its job against a world that has moved on, and the owner would see a burst of
         * activity with no explanation. The schedule advances past it and the miss is counted, which
         * is the honest record of a device that was off.
         */
        if (now - dueAt > com.unuslumen.app.domain.agents.AgentScheduleRules.MAX_LATE_MILLIS) {
            val next = dueAt + schedule.intervalMillis
            return refuse(
                schedule,
                "this window was missed by ${(now - dueAt) / 60_000} minutes, which is past the " +
                    "grace period, so it was not run late",
                now,
                nextDue = next,
            )
        }

        val agent = agentRepository.getAgent(schedule.agentId)
            ?: return refuse(schedule, "the agent this schedule starts no longer exists", now)

        if (!agent.enabled) {
            return refuse(schedule, "'${agent.name}' is switched off", now)
        }

        /*
         * THE PERMISSION CHECK, AND THE ONE THIS FEATURE EXISTS TO ENFORCE.
         *
         * An agent that has not been granted scheduled operation does not run on a schedule,
         * whatever a schedule row says. Checking the schedule's own existence would be checking the
         * wrong thing: the schedule is the instruction and the autonomy flag is the authority.
         */
        if (!agent.autonomy.operateOnSchedules) {
            return refuse(
                schedule,
                "'${agent.name}' is not permitted to work on a schedule. Allow scheduled operation " +
                    "in the agent's autonomy settings first.",
                now,
            )
        }

        /*
         * A RUN MUST BELONG TO A CONVERSATION.
         *
         * The foreign key is real, so a run with no conversation cannot be inserted at all. A
         * scheduled run has no originating message, so the conversation is its own: one per
         * schedule, created on demand, so a schedule's history is one readable thread rather than
         * work scattered across the person's chats.
         */
        val conversationId = runCatching { conversationFor(schedule) }.getOrNull()
            ?: return refuse(
                schedule,
                "no conversation could be created for this schedule's runs, so nothing was started",
                now,
            )

        val runId = Uuid.random().toString()
        val nextDue = nextDue(schedule, now)

        /*
         * ADVANCE FIRST, START SECOND.
         *
         * If the run is started and the advance fails, the same window is due again on the next
         * sweep and the work repeats. Advancing first means a failed start costs one window and is
         * recorded, while a failed advance costs nothing because no work was done. The ordering is
         * chosen for the failure that is recoverable.
         */
        val advanced = runCatching {
            scheduleDao.advance(
                scheduleId = schedule.scheduleId,
                expectedDueAt = dueAt,
                nextDueAt = nextDue,
                runId = runId,
                ranAt = now,
            )
        }.getOrDefault(0)

        if (advanced != 1) {
            return Outcome.Refused(
                "Another runner took this window first, so nothing was started here."
            )
        }

        val admitted = runCatching {
            val deferred = kotlinx.coroutines.CompletableDeferred<AgentRuntime.SpawnResult>()
            val admission = runHost.start(runId, deferred) {
                runtime.spawn(
                    slugOrName = agent.slug.ifBlank { agent.name },
                    job = schedule.assignment,
                    conversationId = conversationId,
                    runId = runId,
                ).let { flow ->
                    var last = AgentRuntime.SpawnResult(
                        success = false, agentName = agent.name, report = "", toolRounds = 0,
                        status = AgentRuntime.SpawnResult.Status.FAILED,
                        error = "the run produced no result",
                    )
                    flow.collect { last = it }
                    last
                }
            }
            admission is AgentRunHost.Admission.Started
        }.getOrDefault(false)

        if (!admitted) {
            /*
             * THE WINDOW IS ALREADY SPENT, SO THIS IS A FAILURE RATHER THAN A RETRY.
             *
             * The schedule has advanced, which is correct: the window passed. What must not happen
             * is the owner never learning that it passed without work, so the failure is written on
             * the schedule where the next sweep and any surface will see it.
             */
            runCatching {
                scheduleDao.miss(
                    scheduleId = schedule.scheduleId,
                    expectedDueAt = dueAt,
                    nextDueAt = nextDue,
                    dueAt = dueAt,
                    reason = "the run could not be started; the run host refused a job under a fresh id",
                    now = now,
                )
            }
            return Outcome.Refused(
                "A run with the id '$runId' was already executing, which cannot happen for a fresh " +
                    "id, so nothing was started."
            )
        }

        runCatching {
            ledger.append(
                runId,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.RUN_CONFIGURATION_CHANGED,
                    actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_SYSTEM,
                    payload = jsonStringObject(
                        "reason" to "this run was started by a schedule rather than by a person",
                        "scheduleId" to schedule.scheduleId,
                        "scheduleTitle" to schedule.title,
                        "dueAt" to dueAt.toString(),
                        "startedAt" to now.toString(),
                    ),
                )
            )
        }

        return Outcome.Started(runId = runId, agentName = agent.name)
    }

    /** Every schedule, for a surface that lists them. */
    suspend fun all(): List<AgentScheduleEntity> = runCatching { scheduleDao.all() }.getOrDefault(emptyList())

    /** The schedules that will start one agent on its own. */
    suspend fun forAgent(agentId: String): List<AgentScheduleEntity> =
        runCatching { scheduleDao.forAgent(agentId) }.getOrDefault(emptyList())

    /**
     * Create a schedule, with the interval clamped to something a runner can honour.
     *
     * THE CLAMP IS NOT PATERNALISM, IT IS ARITHMETIC. A schedule due every second would be due again
     * before the first run finished, and the sweep would start work faster than any model answers.
     * The minimum is a minute, and a caller asking for less is given a minute rather than an error,
     * because the intent — "as often as possible" — is still satisfiable.
     */
    suspend fun create(
        agentId: String,
        title: String,
        assignment: String,
        intervalMillis: Long,
        firstDueAt: Long = System.currentTimeMillis(),
        now: Long = System.currentTimeMillis(),
    ): AgentScheduleEntity {
        val agent = agentRepository.getAgent(agentId)
        val schedule = AgentScheduleEntity(
            scheduleId = Uuid.random().toString(),
            agentId = agentId,
            agentSlug = agent?.slug.orEmpty(),
            title = title,
            assignment = assignment,
            enabled = true,
            intervalMillis = intervalMillis.coerceAtLeast(com.unuslumen.app.domain.agents.AgentScheduleRules.MIN_INTERVAL_MILLIS),
            nextDueAt = firstDueAt,
            createdAt = now,
            updatedAt = now,
        )
        scheduleDao.insert(schedule)
        return schedule
    }

    /** Arm or disarm a schedule. Disarming clears the next due time so nothing can find it due. */
    suspend fun setEnabled(scheduleId: String, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        val existing = scheduleDao.schedule(scheduleId) ?: return
        val next = when {
            !enabled -> null
            existing.nextDueAt != null -> existing.nextDueAt
            else -> now + existing.intervalMillis
        }
        scheduleDao.setEnabled(scheduleId, enabled, next, now)
    }

    suspend fun delete(scheduleId: String) = scheduleDao.delete(scheduleId)

    /**
     * When this schedule should next be due, given the window it just used.
     *
     * ANCHORED ON THE DUE TIME, NOT ON NOW. Anchoring on now would make every run drift later by
     * whatever the sweep took to reach it, so an hourly schedule would slowly become a ninety-minute
     * one. Anchoring on the window keeps the cadence the person asked for.
     */
    private fun nextDue(schedule: AgentScheduleEntity, now: Long): Long? {
        if (!schedule.enabled) return null
        val dueAt = schedule.nextDueAt ?: return now + schedule.intervalMillis
        val candidate = dueAt + schedule.intervalMillis
        /*
         * A SCHEDULE THAT FELL FAR BEHIND IS RE-ANCHORED ON NOW. Without this, a device off for a
         * week would come back with two hundred windows queued, and the grace rule would refuse
         * every one of them for a week while it counted them out. Re-anchoring resumes the cadence
         * from now, which is what a person means by "every hour".
         */
        return if (candidate <= now) now + schedule.intervalMillis else candidate
    }

    /**
     * The conversation this schedule's runs belong to, created on demand.
     *
     * ONE PER SCHEDULE, NAMED AFTER IT. Reusing the person's own chat would scatter autonomous work
     * through their conversation, and creating one per run would give every night's work its own
     * thread with no way to read a schedule's history together.
     */
    private suspend fun conversationFor(schedule: AgentScheduleEntity): String {
        schedule.conversationId?.takeIf { it.isNotBlank() }?.let { return it }

        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        conversationDao().upsertConversation(
            com.unuslumen.app.database.entity.ConversationEntity(
                id = id,
                title = schedule.title.ifBlank { "Scheduled work" },
                createdDate = now,
                updatedDate = now,
                messageCount = 0,
            )
        )
        /*
         * ATTACH IT CONDITIONALLY, THEN RE-READ. If another runner attached one first this changes
         * nothing, and the fresh read gives whichever id actually won. Returning the id this caller
         * created would leave a thread that no run belongs to.
         */
        scheduleDao.attachConversation(schedule.scheduleId, id, now)
        return scheduleDao.schedule(schedule.scheduleId)?.conversationId?.takeIf { it.isNotBlank() } ?: id
    }

    /** The conversation DAO. The database is injected because a scheduled run needs one created. */
    private fun conversationDao(): com.unuslumen.app.database.dao.ConversationDao =
        database.conversationDao()

    /**
     * Record a refusal on the schedule itself, and move it past the window it could not use.
     *
     * THE SCHEDULE STAYS ARMED. A refusal is usually a condition that can change — the agent gets
     * switched on, a model gets connected — and disarming on the first failure would turn a
     * temporary problem into a permanent stop that nobody notices.
     */
    private suspend fun refuse(
        schedule: AgentScheduleEntity,
        reason: String,
        now: Long,
        nextDue: Long? = dueAfter(schedule, now),
    ): Outcome.Refused {
        runCatching {
            scheduleDao.miss(
                scheduleId = schedule.scheduleId,
                expectedDueAt = schedule.nextDueAt ?: now,
                nextDueAt = nextDue,
                dueAt = schedule.nextDueAt ?: now,
                reason = reason,
                now = now,
            )
        }
        return Outcome.Refused(reason)
    }

    private fun dueAfter(schedule: AgentScheduleEntity, now: Long): Long? =
        if (schedule.enabled) (schedule.nextDueAt ?: now) + schedule.intervalMillis else null

    /** A flat JSON object, with the string escaping this codebase already uses. */
    private fun jsonStringObject(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", prefix = "{", postfix = "}") { (key, value) ->
            "\"" + escape(key) + "\":\"" + escape(value) + "\""
        }

    private fun escape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(String.format("%04x", c.code)) else append(c)
            }
        }
    }
}
