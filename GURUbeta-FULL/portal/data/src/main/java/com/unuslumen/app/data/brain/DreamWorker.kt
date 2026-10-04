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
 * DreamWorker — Runs dream consolidation when the user is idle or asleep.
 *
 * Merges duplicate facts, prunes weak BUFFER facts, promotes eligible facts
 * to higher layers, builds semantic edges, and generates missing signatures.
 *
 * Scheduled to run once per day during idle time.
 */

class DreamWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {
    private val brainService: BrainService by inject()

    override suspend fun doWork(): Result {
        return try {
            Log.d("guru_brain", "DreamWorker starting")
            val report = brainService.dream()
            Log.d("guru_brain", "DreamWorker complete: pruned=${report.prunedCount} merged=${report.mergedCount} promoted=${report.promotedCount} edges=${report.edgesBuilt} sigs=${report.signaturesGenerated}")
            Result.success()
        } catch (e: Exception) {
            Log.e("guru_brain", "DreamWorker failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "brain_dream_work"
    }
}
