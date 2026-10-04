// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.repository

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.unuslumen.app.data.worker.BackupWorker
import com.unuslumen.app.domain.model.BackupFrequency
import com.unuslumen.app.domain.repository.BackupScheduler
import org.koin.core.annotation.Factory
import java.util.concurrent.TimeUnit

@Factory
class BackupSchedulerImpl(
    private val context: Context
) : BackupScheduler {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    override suspend fun scheduleBackup(
        folderUri: String,
        frequency: BackupFrequency,
        frequencyAmount: Int
    ) {

        val workRequest = PeriodicWorkRequestBuilder<BackupWorker>(
            repeatInterval = frequencyAmount.coerceAtLeast(1).toLong() * frequency.hours,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS,
            flexTimeIntervalUnit = TimeUnit.MILLISECONDS
        ).build()

        workManager.enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }

    override suspend fun cancelBackup() {
        workManager.cancelUniqueWork(BackupWorker.WORK_NAME)
    }
}

