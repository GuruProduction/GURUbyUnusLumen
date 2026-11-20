// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/**
 * Where agent work actually lives.
 *
 * THE PROBLEM THIS EXISTS FOR. An agent run was executed inside whatever coroutine the
 * caller happened to be using — a UI scope, a tool call's scope, a screen's view-model
 * scope. Anything that cancelled the caller cancelled the agent, while the foreground
 * service went on showing a notification and renewing a lease that said the work was
 * healthy. That is the worst of both outcomes: the person is told work is running, and the
 * recovery scan is told the owner is alive, while the work itself is dead and its run row
 * still says RUNNING.
 *
 * SO EXECUTION MOVES HERE. This owns an application-lifetime scope that no screen, view
 * model or tool call can cancel. A caller starts work here and then *waits* for the result
 * through a [CompletableDeferred]. Cancelling the waiter cancels only the wait — the work
 * carries on, keeps writing to the ledger, and finishes. A backgrounded app that is later
 * killed leaves the run INTERRUPTED with a checkpoint, which is a state a person can see.
 *
 * ONE RUN ID IS ONE EXECUTION, AND ADMISSION IS ATOMIC. A run id names a single execution
 * of a single assignment. Two jobs under one id would each run the assignment and repeat
 * every side effect behind it, and whichever finished first would clear the shared entry
 * while the other was still working. So a start either wins the id or is refused, decided
 * by the map itself rather than by a check that another thread can slip past.
 *
 * WHAT THIS IS NOT. It is not a promise that work survives process death; nothing in this
 * process can promise that. It is the narrow, honest guarantee that work outlives its
 * caller, once, per run id.
 */
@Single
class AgentRunHost {

    /**
     * What a start was allowed to do.
     *
     * THE REFUSAL IS A VALUE, NOT AN EXCEPTION. A duplicate start is an ordinary thing to
     * attempt — two answers to one question, a tool call retried — and the caller has to
     * say something sensible about it rather than crash. Returning it as a result makes
     * ignoring it a deliberate act.
     */
    sealed interface Admission {
        /** This caller won the run id and the block is now executing under it. */
        data class Started(val job: Job) : Admission

        /**
         * A run with this id was already live, so nothing was started.
         *
         * [job] is the execution that owns the id, for a caller that wants to wait on it or
         * report it. The block handed to *this* start was never invoked and never will be.
         */
        data class AlreadyRunning(val job: Job) : Admission
    }

    /**
     * Application lifetime, deliberately.
     *
     * A SupervisorJob so one failed run cannot cancel every other run in flight, and IO
     * because every agent turn is network-bound.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The run id currently owned by a live job. One entry per executing run. */
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Run [block] under this host's lifetime and complete [deferred] with its outcome.
     *
     * THE DEFERRED IS SUPPLIED BY THE CALLER, not created here, so this stays free of any
     * dependency on the runtime's result type and the two cannot form a cycle. The caller
     * awaits it if it wants an answer; if the caller goes away, the deferred is simply
     * never collected and the work still finishes.
     *
     * A run that throws completes the deferred exceptionally AND is left in the ledger by
     * the caller's own failure handling, so a crash here does not become a silent no-op.
     *
     * THE JOB IS CREATED LAZY AND STARTED ONLY ONCE ADMISSION IS WON. Creating it eagerly
     * and checking the map afterwards is the race: two callers can both look, both find the
     * id free, and both launch. Here the map decides, and the loser's body never runs at
     * all because a lazy coroutine refused admission is cancelled before its first resume.
     *
     * A refusal also cancels the caller's deferred. That is deliberate: a caller that
     * forgets to inspect the result would otherwise await a deferred nothing will ever
     * complete, and a hang is a far worse failure than an exception naming the reason.
     */
    fun <T> start(
        runId: String,
        deferred: CompletableDeferred<T>,
        block: suspend () -> T,
    ): Admission {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            /*
             * THE JOB REMOVES ONLY ITSELF.
             *
             * Two-argument `remove` compares the value as well as the key, so a job that
             * finishes cannot clear an entry that now belongs to a newer execution of the
             * same run id. Without that guard the older finally block silently deregisters
             * the newer run and the heartbeat stops renewing a lease for live work.
             */
            val self = coroutineContext[Job]
            try {
                deferred.complete(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                deferred.cancel(e)
                throw e
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            } finally {
                jobs.remove(runId, self)
            }
        }

        // The admission loop. Exactly one caller can leave it owning the id, because every
        // path out either inserted this job or found somebody else's live one.
        while (true) {
            val existing = jobs[runId]
            when {
                existing == null -> if (jobs.putIfAbsent(runId, job) == null) break
                existing.isActive -> {
                    // Somebody else owns this run. This job never starts, and cancelling a
                    // lazy coroutine before its first resume means `block` is never invoked.
                    job.cancel()
                    deferred.cancel(
                        kotlinx.coroutines.CancellationException(
                            "Run '$runId' is already executing; this start was refused."
                        )
                    )
                    return Admission.AlreadyRunning(existing)
                }
                // A finished job whose finally has not run yet. Replace only that exact
                // instance, so a third caller racing here cannot be mistaken for it.
                jobs.replace(runId, existing, job) -> break
            }
        }

        job.start()
        return Admission.Started(job)
    }

    /**
     * Whether work for this run is genuinely executing.
     *
     * THE SERVICE ASKS THIS BEFORE RENEWING A LEASE. A heartbeat for a finished job keeps a
     * dead run looking healthy for ever, and the scan would never interrupt it.
     */
    fun isActive(runId: String): Boolean = jobs[runId]?.isActive == true

    /** Stop a run's work. Used by the notification's stop action and by cancellation. */
    fun cancel(runId: String) {
        jobs.remove(runId)?.cancel()
    }

    /** Every run currently executing, for diagnostics and the recovery scan's own report. */
    fun activeRuns(): Set<String> = jobs.filterValues { it.isActive }.keys
}
