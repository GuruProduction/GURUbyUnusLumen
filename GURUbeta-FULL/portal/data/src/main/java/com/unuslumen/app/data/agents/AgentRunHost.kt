// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
 * WHAT THIS IS NOT. It is not a promise that work survives process death; nothing in this
 * process can promise that. It is the narrow, honest guarantee that work outlives its
 * caller.
 */
@Single
class AgentRunHost {

    /**
     * Application lifetime, deliberately.
     *
     * A SupervisorJob so one failed run cannot cancel every other run in flight, and IO
     * because every agent turn is network-bound.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
     */
    fun <T> start(
        runId: String,
        deferred: CompletableDeferred<T>,
        block: suspend () -> T,
    ): Job {
        // A second start for the same run replaces the first job reference but never
        // silently kills it: a run id is one execution, so starting one twice is a defect
        // worth seeing in the logs rather than a race to resolve quietly.
        if (jobs.containsKey(runId) && jobs[runId]?.isActive == true) {
            android.util.Log.w("guru_agents", "Run '$runId' is already executing; starting a second job")
        }
        val job = scope.launch {
            try {
                deferred.complete(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                deferred.cancel(e)
                throw e
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            } finally {
                jobs.remove(runId)
            }
        }
        jobs[runId] = job
        return job
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
