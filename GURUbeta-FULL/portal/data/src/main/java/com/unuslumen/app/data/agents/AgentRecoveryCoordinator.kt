// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.entity.AgentRunEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

/**
 * Decides which runs are still alive, and which only look alive.
 *
 * THE PROBLEM THIS SOLVES. An abruptly killed Android process cannot run a handler. There
 * is no callback at the instant of death and no opportunity to write `INTERRUPTED`. So the
 * run row of a killed agent keeps saying `RUNNING` until something notices, and without a
 * notice it says `RUNNING` for ever: a person sees work in progress that has not existed
 * for hours, and the recovery scan would keep visiting it.
 *
 * TWO CONDITIONS, BOTH REQUIRED. A run is interrupted only when its owner is no longer in
 * memory AND its heartbeat has gone stale.
 *
 *  - Owner gone alone is not enough: a long model call can miss a heartbeat while the work
 *    is perfectly healthy, and marking that interrupted would kill real work.
 *  - Stale heartbeat alone is not enough: a process that is merely busy still holds a live
 *    entry in the registry, and its run is not dead.
 *
 * THE OWNER IS AN INCARNATION, NOT A PID. [newOwnerId] mints a UUID per process start. PIDs
 * are reused by the kernel, so a recycled number would make a dead owner look alive, which
 * is the one failure this class exists to prevent.
 *
 * WAITING IS NOT DYING. A run waiting on GURU or on a person has no owner because nothing is
 * driving it, and that is correct. Those runs release their lease and are never interrupted
 * by the scan; they are waiting for a reply, not lost.
 */
class AgentRecoveryCoordinator(
    private val runDao: AgentRunDao,
    private val ledger: AgentRunLedger,
) {

    companion object {
        /**
         * How often a live run says it is still there.
         *
         * Thirty seconds is well inside the expiry below, so a single missed beat costs
         * nothing and a genuinely dead process is caught within a few minutes.
         */
        const val HEARTBEAT_INTERVAL_MS = 30_000L

        /**
         * How old a heartbeat must be before the run is presumed dead.
         *
         * Ten times the interval on purpose. The cost of a false interruption is killing
         * real work; the cost of a late one is a row that said RUNNING a few minutes longer
         * than it should have. The asymmetry is deliberate.
         */
        const val LEASE_EXPIRY_MS = 300_000L

        /** Identifies one process incarnation, never a PID. */
        fun newOwnerId(): String = "proc-" + java.util.UUID.randomUUID().toString()
    }

    /**
     * The leases this process is actively driving.
     *
     * The foreground service and the recovery worker both run in this process, so one map
     * is authoritative for both. If a component ever moves to its own process it needs a
     * real cross-process mechanism rather than sharing this assumption.
     */
    private val liveLeases = ConcurrentHashMap.newKeySet<String>()

    private val json = Json { ignoreUnknownKeys = true }

    fun registerLease(runId: String) { liveLeases.add(runId) }
    fun releaseLocalLease(runId: String) { liveLeases.remove(runId) }
    fun isLocallyHeld(runId: String): Boolean = runId in liveLeases

    /**
     * Find the dead and mark them.
     *
     * Idempotent by construction: it writes a transition only when the row is not already
     * INTERRUPTED, so running it twice produces one event, not two. That matters because it
     * runs at every app start and on every WorkManager pass.
     */
    suspend fun scan(ownerId: String): RecoveryReport {
        val now = System.currentTimeMillis()
        val staleBefore = now - LEASE_EXPIRY_MS
        val suspects = runDao.leaseSuspects(staleBefore)
        var interrupted = 0
        var skipped = 0

        for (run in suspects) {
            // BOTH conditions. A run whose owner is in this process's registry is alive
            // whatever its heartbeat says.
            if (isLocallyHeld(run.runId)) { skipped++; continue }
            // A run already interrupted has been dealt with; re-marking it would duplicate
            // the event on every scan.
            if (run.status == AgentRunEntity.STATUS_INTERRUPTED) { skipped++; continue }

            val deadOwner = run.leaseOwner ?: continue
            val evidence = runCatching {
                ledger.appendStateChange(
                    runId = run.runId,
                    fromStatus = run.status,
                    toStatus = AgentRunEntity.STATUS_INTERRUPTED,
                    reason = "the process that owned this run is gone and its lease went stale",
                )
                runDao.releaseDeadLease(run.runId, deadOwner)
            }
            if (evidence.isSuccess) {
                interrupted++
            } else {
                android.util.Log.w(
                    "guru_agents",
                    "Could not mark '${run.runId}' interrupted: ${evidence.exceptionOrNull()?.message}"
                )
            }
        }

        return RecoveryReport(
            scanned = suspects.size,
            interrupted = interrupted,
            skippedAlive = skipped,
            at = now,
        )
    }

    /**
     * What a run needs to carry on, or the reason it cannot.
     *
     * A resume is refused rather than approximated when the recorded model or tool manifest
     * no longer resolves. Falling back to whatever is connected now would execute an old
     * assignment under a new model, which nobody decided and nobody would notice.
     */
    suspend fun resumeReadiness(runId: String): ResumeReadiness {
        val run = runDao.run(runId)
            ?: return ResumeReadiness.Unavailable("no run '$runId' exists")
        if (run.status in AgentRunEntity.TERMINAL_STATUSES) {
            return ResumeReadiness.Unavailable("the run finished as ${run.status}")
        }
        val checkpoint = ledger.latestCheckpoint(runId)
            ?: return ResumeReadiness.Unavailable(
                "the run has no checkpoint, so there is no working state to resume from"
            )
        return when (val verdict = ledger.verifyCheckpoint(checkpoint)) {
            is AgentRunLedger.CheckpointVerdict.Unreadable ->
                ResumeReadiness.Unavailable(verdict.reason)
            AgentRunLedger.CheckpointVerdict.Verified ->
                ResumeReadiness.Ready(run, checkpoint)
        }
    }

    data class RecoveryReport(
        val scanned: Int,
        val interrupted: Int,
        val skippedAlive: Int,
        val at: Long,
    )

    sealed interface ResumeReadiness {
        data class Ready(
            val run: AgentRunEntity,
            val checkpoint: com.unuslumen.app.database.entity.AgentRunCheckpointEntity,
        ) : ResumeReadiness

        data class Unavailable(val reason: String) : ResumeReadiness
    }
}
