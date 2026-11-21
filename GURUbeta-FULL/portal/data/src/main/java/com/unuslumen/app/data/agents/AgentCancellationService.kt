// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.entity.AgentRunEntity
import org.koin.core.annotation.Single

/**
 * Stopping a run, in the one order that is safe.
 *
 * TWO HALVES, AND BOTH ARE REQUIRED.
 *
 *  - `AgentRunHost.cancel` cancels the coroutine that is executing the run. Without it, the loop
 *    keeps working: it holds a job, it will keep making model calls and dispatching tools, and it
 *    will eventually write its own terminal state over the cancellation.
 *  - `AgentRunLedger.terminate` writes the terminal state, the cancellation event and the lease
 *    release, and refuses if the run already reached a terminal state. Without it, a run with no
 *    live job — PAUSED, INTERRUPTED, or orphaned before the host ever claimed it — can never be
 *    stopped at all, because there is nothing to cancel and the row stays non-terminal for ever.
 *
 * THE ORDER IS THE POINT. The row is terminalised FIRST, so the loop's next admission check sees a
 * terminal run and stops before doing any more work. The job is cancelled SECOND, which frees the
 * coroutine and its resources. Doing it the other way round leaves a window where the job is dead,
 * the row still says RUNNING, and a recovery scan or a late writer can move it somewhere the person
 * did not choose.
 *
 * A RUN WITH NO JOB IS NOT AN ERROR. That is the common case for the runs a person wants to clear
 * out, and it is exactly the case the old cancellation could not touch.
 */
@Single
class AgentCancellationService(
    private val host: AgentRunHost,
    private val ledger: AgentRunLedger,
    private val runDao: AgentRunDao,
) {

    /** What one cancellation attempt did. */
    data class Cancelled(
        val runId: String,
        /** The state the run was in when the attempt arrived. Null if there is no such run. */
        val fromStatus: String?,
        /** True when a live job was found and cancelled. */
        val stoppedLiveExecution: Boolean,
        /** The sentence a surface or a model can act on. */
        val sentence: String,
        /** True when the run was already finished and nothing changed. */
        val alreadyTerminal: Boolean = false,
    )

    /**
     * Stop one run.
     *
     * `reason` is recorded on the cancellation event, so a reader can tell a person's Stop from a
     * supervisor clearing a stuck run.
     */
    suspend fun cancel(runId: String, reason: String = "cancelled by a person"): Cancelled {
        val run = runCatching { runDao.run(runId) }.getOrNull()
            ?: return Cancelled(
                runId = runId,
                fromStatus = null,
                stoppedLiveExecution = false,
                sentence = "There is no run with the id '$runId', so nothing was cancelled.",
            )

        if (run.status in AgentRunEntity.TERMINAL_STATUSES) {
            return Cancelled(
                runId = runId,
                fromStatus = run.status,
                stoppedLiveExecution = false,
                alreadyTerminal = true,
                sentence = "That run is already ${spoken(run.status)}, so nothing was changed.",
            )
        }

        // The row first, so the loop cannot admit another round while the job is being torn down.
        val outcome = runCatching { ledger.terminate(runId, AgentRunEntity.STATUS_CANCELLED, reason) }
            .getOrElse { failure ->
                return Cancelled(
                    runId = runId,
                    fromStatus = run.status,
                    stoppedLiveExecution = false,
                    sentence = "That run was not cancelled: the record could not be updated " +
                        "(${failure.message ?: failure.javaClass.simpleName}). It is still " +
                        "${spoken(run.status)}.",
                )
            }

        val stopped = runCatching { host.cancel(runId) }.getOrDefault(false)

        return when (outcome) {
            is AgentRunLedger.Outcome.Terminated -> Cancelled(
                runId = runId,
                fromStatus = run.status,
                stoppedLiveExecution = stopped,
                sentence = if (stopped) {
                    "Stopped. It was ${spoken(run.status)} and is now cancelled. Its live " +
                        "execution was stopped and no further round ran. A tool call that was in " +
                        "flight has an unknown outcome and is recorded as unknown rather than as " +
                        "undone."
                } else {
                    "Cancelled. It was ${spoken(run.status)} and nothing was executing it, so " +
                        "there was no job to stop; the run is now finished so it can no longer be " +
                        "resumed or picked up by recovery."
                },
            )

            is AgentRunLedger.Outcome.AlreadyTerminal -> Cancelled(
                runId = runId,
                fromStatus = outcome.status,
                stoppedLiveExecution = stopped,
                alreadyTerminal = true,
                sentence = "That run is already ${spoken(outcome.status)}, so nothing was changed.",
            )

            is AgentRunLedger.Outcome.Refused -> Cancelled(
                runId = runId,
                fromStatus = run.status,
                stoppedLiveExecution = stopped,
                sentence = "That run was not cancelled: ${outcome.reason}. It is still " +
                    "${spoken(run.status)}.",
            )

            AgentRunLedger.Outcome.NoSuchRun -> Cancelled(
                runId = runId,
                fromStatus = null,
                stoppedLiveExecution = false,
                sentence = "There is no run with the id '$runId', so nothing was cancelled.",
            )
        }
    }

    /**
     * Stop everything that is still running.
     *
     * EVERY NON-TERMINAL RUN IS A CANDIDATE, including the ones with no live job. That is the point
     * of a bulk stop: a person asking for everything to stop does not want to be told that four of
     * the seven had nothing driving them and were therefore left behind.
     */
    suspend fun cancelAll(reason: String = "all runs cancelled by a person"): List<Cancelled> {
        val active = runCatching { runDao.nonTerminalRuns() }.getOrDefault(emptyList())
        return active.map { cancel(it.runId, reason) }
    }

    /** A status as a person reads it, never the constant with underscores in it. */
    private fun spoken(status: String): String = status.lowercase().replace('_', ' ')
}
