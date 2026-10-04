// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.brain

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * DecayWorker — Applies decay to all memory facts.
 *
 * Strength decays over time. Weak BUFFER facts below the prune threshold
 * get deleted. This keeps the brain from accumulating stale memories.
 *
 * Scheduled to run every 12 hours.
 */
class DecayWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {
    private val brainService: BrainService by inject()

    override suspend fun doWork(): Result {
        return try {
            Log.d("guru_brain", "DecayWorker starting")
            val pruned = brainService.applyDecay()
            Log.d("guru_brain", "DecayWorker complete: pruned=$pruned facts")
            Result.success()
        } catch (e: Exception) {
            Log.e("guru_brain", "DecayWorker failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "brain_decay_work"
    }
}
